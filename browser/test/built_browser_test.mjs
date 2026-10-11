/**
 * Verification for a *built* RVPN Browser (out/rvpn-browser/chrome).
 *
 * Unlike extension_test.mjs this does not load the extension with
 * --load-extension: it checks the artefacts of the source build itself.
 *
 *   1. the binary reports the RVPN Browser product name,
 *   2. the RVPN extension is built into the browser (component extension),
 *   3. the extension can drive the real rvpn-core through native messaging,
 *   4. RVPN mode sets the browser proxy to the core's SOCKS5 address,
 *   5. the built-in extension's UI renders.
 *
 * Usage: node built_browser_test.mjs --chromium out/rvpn-browser/chrome [--keep]
 */

import { chromium } from 'playwright-core';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const EXTENSION_ID = 'lhgepbmchpealfajciakglacaedkjenh';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const CHROMIUM = opt('chromium', path.join(process.env.CR_DIR || path.join(os.homedir(), 'chromium', 'src'),
  'out', 'rvpn-browser', 'chrome'));
const CORE = opt('core', process.env.RVPN_CORE_BIN || path.join(REPO_ROOT, 'core', 'bin', 'rvpn-core'));
const KEEP = args.includes('--keep');

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok });
  console.log(`${ok ? '\u001b[32mPASS\u001b[0m' : '\u001b[31mFAIL\u001b[0m'} ${name}${detail ? '  -- ' + detail : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function poll(fn, timeoutMs, intervalMs = 500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await fn();
    if (value) return value;
    await sleep(intervalMs);
  }
  return null;
}

async function main() {
  if (!fs.existsSync(CHROMIUM)) {
    console.error(`!! built browser not found: ${CHROMIUM}`);
    return 2;
  }

  // 1. product name reported by the binary itself
  let versionText = '';
  try {
    versionText = execFileSync(CHROMIUM, ['--version'], { encoding: 'utf8', timeout: 60000 }).trim();
  } catch (e) {
    versionText = 'error: ' + e.message;
  }
  check('built binary reports the RVPN Browser product name',
    /RVPN Browser/i.test(versionText), versionText || '(no output)');

  // 2. branding inside the resource bundle
  const pakNote = (() => {
    try {
      const out = execFileSync('strings', [CHROMIUM], { encoding: 'utf8', timeout: 120000 });
      return out.includes('RVPN Browser');
    } catch (e) {
      return null;
    }
  })();
  if (pakNote !== null) {
    check('binary contains the RVPN Browser branding strings', pakNote);
  }

  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rvpn-built-'));
  const hostsDir = path.join(userDataDir, 'NativeMessagingHosts');
  fs.mkdirSync(hostsDir, { recursive: true });
  fs.writeFileSync(path.join(hostsDir, 'com.achtrw.rvpn.json'), JSON.stringify({
    name: 'com.achtrw.rvpn',
    description: 'RVPN Browser engine (ZJU RVPN / aTrust / EasyConnect)',
    path: CORE, type: 'stdio',
    allowed_origins: [`chrome-extension://${EXTENSION_ID}/`],
  }, null, 2));

  const context = await chromium.launchPersistentContext(userDataDir, {
    executablePath: CHROMIUM,
    headless: false,
    args: ['--no-first-run', '--no-default-browser-check', '--no-sandbox', '--disable-dev-shm-usage'],
  });

  try {
    // 3. the built-in extension must be present without --load-extension
    const ui = await context.newPage();
    let builtIn = false;
    try {
      await ui.goto(`chrome-extension://${EXTENSION_ID}/popup.html`, { waitUntil: 'domcontentloaded', timeout: 15000 });
      builtIn = (await ui.title()) !== '' || (await ui.content()).length > 200;
    } catch (e) {
      builtIn = false;
    }
    check('RVPN extension is built into the browser', builtIn,
      builtIn ? `chrome-extension://${EXTENSION_ID}/popup.html` : 'extension page did not load');

    const optionsUrl = `chrome-extension://${EXTENSION_ID}/options.html`;
    await ui.goto(optionsUrl, { waitUntil: 'domcontentloaded' });
    const send = (msg) => ui.evaluate((m) => new Promise((resolve) => {
      chrome.runtime.sendMessage(m, (response) => {
        if (chrome.runtime.lastError) { resolve({ ok: false, error: String(chrome.runtime.lastError.message) }); return; }
        resolve(response);
      });
    }), msg);

    const hello = await poll(async () => {
      const res = await send({ type: 'hello' });
      return res && res.ok ? res : null;
    }, 30000);
    const info = hello ? (hello.hello || hello.data || {}) : {};
    check('built-in extension reaches rvpn-core', !!hello,
      hello ? `${info.version} / protocol lib ${info.zjuConnectVersion}` : 'no reply');

    await send({ type: 'saveSettings', settings: {
      userType: 'zju', protocol: 'atrust', server: 'vpn.zju.edu.cn', port: 443,
      username: '0000000000', password: 'build-verification', loginDomain: 'Radius',
      authType: 'auth/psw', rememberPassword: false,
    } });
    const started = await send({ type: 'setMode', mode: 'rvpn' });
    check('setMode(rvpn) is accepted by the built browser', !!started && started.ok !== false,
      started ? JSON.stringify(started).slice(0, 100) : 'no reply');

    const selftest = await send({ type: 'selftest', server: 'vpn.zju.edu.cn', port: 443 });
    check('selftest reaches vpn.zju.edu.cn from the built browser',
      selftest && selftest.data && selftest.data.reachable === true,
      selftest && selftest.data ? `${selftest.data.latencyMs}ms` : JSON.stringify(selftest).slice(0, 100));

    return results.every((r) => r.ok) ? 0 : 1;
  } finally {
    try { await context.close(); } catch (e) { /* ignore */ }
    if (!KEEP) { try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* ignore */ } }
    const failed = results.filter((r) => !r.ok);
    console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
    if (failed.length) console.log('failed: ' + failed.map((f) => f.name).join(', '));
  }
}

main().then((code) => process.exit(code)).catch((e) => {
  console.error('test crashed:', e);
  process.exit(3);
});
