/**
 * Real-core smoke test: browser -> extension -> native messaging -> rvpn-core.
 *
 * Unlike extension_test.mjs (which uses a mock host to prove that traffic is
 * proxied), this test drives the *installed* RVPN core through the extension
 * exactly as a user would after running packaging/linux/install.sh:
 *
 *   - the native messaging manifest points at the real rvpn-core binary,
 *   - `hello` must report the core version and capabilities,
 *   - `selftest` must reach the real ZJU RVPN server from inside the browser.
 *
 * It needs no ZJU credentials (it never logs in) but does need network access
 * to the VPN server - run it from the campus network for a meaningful result.
 *
 * Usage: node real_core_test.mjs --chromium /path/to/chrome --core /path/to/rvpn-core
 */

import { chromium } from 'playwright-core';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const EXTENSION_DIR = process.env.RVPN_EXTENSION_DIR || path.join(REPO_ROOT, 'browser', 'extension');
const EXTENSION_ID = 'lhgepbmchpealfajciakglacaedkjenh';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const CHROMIUM = opt('chromium', process.env.RVPN_CHROMIUM || '');
const CORE = opt('core', process.env.RVPN_CORE_BIN || path.join(REPO_ROOT, 'core', 'bin', 'rvpn-core'));
const SERVER = opt('server', 'vpn.zju.edu.cn');

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok });
  console.log(`${ok ? '\u001b[32mPASS\u001b[0m' : '\u001b[31mFAIL\u001b[0m'} ${name}${detail ? '  -- ' + detail : ''}`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function poll(fn, timeoutMs, intervalMs = 300) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await fn();
    if (value) return value;
    await sleep(intervalMs);
  }
  return null;
}

async function main() {
  for (const [what, target] of [['chromium', CHROMIUM], ['rvpn-core', CORE]]) {
    if (!target || !fs.existsSync(target)) {
      console.error(`!! ${what} not found: ${target || '(not given)'}`);
      return 2;
    }
  }

  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rvpn-real-core-'));
  const hostsDir = path.join(userDataDir, 'NativeMessagingHosts');
  fs.mkdirSync(hostsDir, { recursive: true });
  fs.writeFileSync(path.join(hostsDir, 'com.achtrw.rvpn.json'), JSON.stringify({
    name: 'com.achtrw.rvpn',
    description: 'RVPN Browser engine (ZJU RVPN / aTrust / EasyConnect)',
    path: CORE,
    type: 'stdio',
    allowed_origins: [`chrome-extension://${EXTENSION_ID}/`],
  }, null, 2));

  const context = await chromium.launchPersistentContext(userDataDir, {
    executablePath: CHROMIUM,
    headless: false,
    args: [
      `--disable-extensions-except=${EXTENSION_DIR}`,
      `--load-extension=${EXTENSION_DIR}`,
      '--no-first-run', '--no-default-browser-check', '--no-sandbox', '--disable-dev-shm-usage',
    ],
  });

  try {
    const ui = await context.newPage();
    await ui.goto(`chrome-extension://${EXTENSION_ID}/options.html`, { waitUntil: 'domcontentloaded' });
    const send = (msg) => ui.evaluate((m) => new Promise((resolve) => {
      chrome.runtime.sendMessage(m, (response) => {
        if (chrome.runtime.lastError) {
          resolve({ ok: false, error: String(chrome.runtime.lastError.message) });
          return;
        }
        resolve(response);
      });
    }), msg);

    const hello = await poll(async () => {
      const res = await send({ type: 'hello' });
      return res && res.ok ? res : null;
    }, 25000);
    const info = hello ? (hello.hello || hello.data || {}) : {};
    check('extension reaches the real rvpn-core', !!hello,
      hello ? `${info.version} protocol-lib ${info.zjuConnectVersion}` : 'no reply');
    check('core reports its capabilities', Array.isArray(info.capabilities) && info.capabilities.includes('atrust'),
      (info.capabilities || []).join(','));

    const selftest = await send({ type: 'selftest', server: SERVER, port: 443 });
    const st = (selftest && selftest.data) || {};
    check(`selftest reaches ${SERVER}:443`, st.reachable === true,
      st.reachable ? `latency=${st.latencyMs}ms` : (st.error || JSON.stringify(selftest).slice(0, 120)));

    const authInfo = await send({ type: 'authInfo', server: SERVER, port: 443 });
    const list = (authInfo && authInfo.data && authInfo.data.list) || [];
    check('authInfo discovers aTrust login methods', list.length > 0,
      list.slice(0, 2).map((e) => `${e.loginDomain}/${e.authType}`).join(' '));

    return results.every((r) => r.ok) ? 0 : 1;
  } finally {
    try { await context.close(); } catch (e) { /* ignore */ }
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* ignore */ }
    const failed = results.filter((r) => !r.ok);
    console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  }
}

main().then((code) => process.exit(code)).catch((e) => {
  console.error('test crashed:', e);
  process.exit(3);
});
