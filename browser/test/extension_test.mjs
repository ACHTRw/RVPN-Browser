/**
 * RVPN Browser extension integration test.
 *
 * Launches a real Chromium with the RVPN extension loaded and a mock native
 * messaging host (browser/test/mock_native_host.py) registered for the test
 * profile, then verifies the whole chain:
 *
 *   1. the extension loads and its service worker starts,
 *   2. the extension talks to the native host (hello),
 *   3. switching to RVPN mode sets chrome.proxy to the reported SOCKS address,
 *   4. browser traffic really goes through that SOCKS proxy,
 *   5. switching back to normal mode clears the proxy override.
 *
 * Usage: node extension_test.mjs --chromium /path/to/chrome [--keep]
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
const MOCK_HOST = path.join(HERE, 'mock_native_host.py');
const EXTENSION_ID = 'lhgepbmchpealfajciakglacaedkjenh';
const TEST_URL = 'http://rvpn-integration-test.invalid/';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const CHROMIUM = opt('chromium', process.env.RVPN_CHROMIUM || '');
const KEEP = args.includes('--keep');
const CHALLENGE = args.includes('--challenge');

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? '\u001b[32mPASS\u001b[0m' : '\u001b[31mFAIL\u001b[0m'} ${name}${detail ? '  -- ' + detail : ''}`);
  return ok;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function poll(fn, timeoutMs, intervalMs = 250) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await fn();
      if (last) return last;
    } catch (e) {
      last = e;
    }
    await sleep(intervalMs);
  }
  return null;
}

function writeHostManifest(userDataDir) {
  const dir = path.join(userDataDir, 'NativeMessagingHosts');
  fs.mkdirSync(dir, { recursive: true });
  const wrapper = path.join(userDataDir, 'mock-host.sh');
  fs.writeFileSync(wrapper, `#!/bin/sh\nexec python3 ${JSON.stringify(MOCK_HOST)}\n`, { mode: 0o755 });
  const manifest = {
    name: 'com.achtrw.rvpn',
    description: 'RVPN Browser mock engine (integration test)',
    path: wrapper,
    type: 'stdio',
    allowed_origins: [`chrome-extension://${EXTENSION_ID}/`],
  };
  fs.writeFileSync(path.join(dir, 'com.achtrw.rvpn.json'), JSON.stringify(manifest, null, 2));
  return wrapper;
}

// Messages must be sent from a *different* extension frame than the service
// worker: chrome.runtime.sendMessage does not fire onMessage in the sender's
// own frame, so the test drives the extension from its options page.
async function sendToExtension(ui, message) {
  return ui.evaluate((msg) => new Promise((resolve) => {
    chrome.runtime.sendMessage(msg, (response) => {
      if (chrome.runtime.lastError) {
        resolve({ ok: false, error: String(chrome.runtime.lastError.message) });
        return;
      }
      resolve(response);
    });
  }), message);
}

async function getProxySettings(ui) {
  return ui.evaluate(() => new Promise((resolve) => {
    chrome.proxy.settings.get({ incognito: false }, (details) => resolve(details));
  }));
}

async function main() {
  if (!CHROMIUM || !fs.existsSync(CHROMIUM)) {
    console.error(`!! chromium binary not found: ${CHROMIUM || '(not given)'}`);
    console.error('   pass --chromium /path/to/chrome (or set RVPN_CHROMIUM)');
    return 2;
  }
  if (!fs.existsSync(path.join(EXTENSION_DIR, 'manifest.json'))) {
    console.error(`!! extension not found in ${EXTENSION_DIR}`);
    return 2;
  }

  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rvpn-ext-test-'));
  const mockLog = path.join(userDataDir, 'mock-connections.log');
  fs.writeFileSync(mockLog, '');
  writeHostManifest(userDataDir);

  const context = await chromium.launchPersistentContext(userDataDir, {
    executablePath: CHROMIUM,
    headless: false,
    args: [
      `--disable-extensions-except=${EXTENSION_DIR}`,
      `--load-extension=${EXTENSION_DIR}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--no-sandbox',
      '--disable-dev-shm-usage',
    ],
    env: {
      ...process.env,
      RVPN_MOCK_LOG: mockLog,
      RVPN_MOCK_CHALLENGE: CHALLENGE ? '1' : '',
      DISPLAY: process.env.DISPLAY || ':0',
    },
  });

  let worker = null;
  try {
    worker = await poll(async () => {
      const workers = context.serviceWorkers();
      return workers.find((w) => w.url().includes(EXTENSION_ID)) || null;
    }, 20000, 250);

    if (!check('extension service worker starts', !!worker,
      worker ? worker.url().replace(/^chrome-extension:\/\//, '').split('/')[0] : 'no service worker')) {
      return 1;
    }

    const ui = await context.newPage();
    await ui.goto(`chrome-extension://${EXTENSION_ID}/options.html`, { waitUntil: 'domcontentloaded' });
    await poll(async () => ui.evaluate(() => typeof chrome !== 'undefined' && !!chrome.runtime), 10000, 200);
    check('extension page loads', ui.url().includes(EXTENSION_ID), ui.url());

    // 2. native messaging handshake
    const hello = await poll(async () => {
      const res = await sendToExtension(ui, { type: 'hello' });
      return res && res.ok ? res : null;
    }, 20000, 500);
    check('extension reaches the native host (hello)', !!hello,
      hello ? JSON.stringify(hello.data || {}).slice(0, 90) : 'no reply');

    // 3. switch to RVPN mode
    await sendToExtension(ui, {
      type: 'saveSettings',
      settings: {
        userType: 'zju',
        protocol: 'atrust',
        server: 'vpn.zju.edu.cn',
        port: 443,
        username: '0000000000',
        password: 'integration-test',
        loginDomain: 'Radius',
        authType: 'auth/psw',
        rememberPassword: true,
      },
    });
    const setMode = await sendToExtension(ui, { type: 'setMode', mode: 'rvpn' });
    check('setMode(rvpn) is accepted', !!setMode && setMode.ok !== false,
      setMode ? JSON.stringify(setMode).slice(0, 120) : 'no reply');

    const diag = await sendToExtension(ui, { type: 'diagnostics' });
    if (diag && !diag.ok) {
      console.log('    (diagnostics: ' + JSON.stringify(diag).slice(0, 200) + ')');
    }

    const state = await poll(async () => {
      const res = await sendToExtension(ui, { type: 'getState' });
      // getState replies with { ok, mode, state, hello } at the top level.
      const state = res && res.state;
      return state && state.phase === 'connected' ? state : null;
    }, 30000, 500);
    check('session reaches the connected state', !!state,
      state ? `socks=${state.socksAddr} clientIP=${state.clientIP}` : 'never connected');

    const socksPort = state && state.socksAddr ? Number(String(state.socksAddr).split(':').pop()) : 0;

    // 4. chrome.proxy must point at the reported SOCKS port
    const proxy = await poll(async () => {
      const details = await getProxySettings(ui);
      const value = details && details.value;
      const rule = value && value.rules && value.rules.singleProxy;
      return rule && rule.port === socksPort ? value : null;
    }, 15000, 500);
    check('chrome.proxy points at the core SOCKS5 port', !!proxy,
      proxy ? JSON.stringify(proxy.rules.singleProxy) : 'proxy not applied');

    // 5. real traffic must traverse the SOCKS proxy
    const page = await context.newPage();
    let body = '';
    try {
      await page.goto(TEST_URL, { timeout: 20000 });
      body = await page.content();
    } catch (e) {
      body = 'navigation error: ' + e.message;
    }
    const logged = fs.readFileSync(mockLog, 'utf8').trim();
    check('browser traffic is proxied through the SOCKS5 listener',
      body.includes('RVPN-MOCK-OK') && logged.includes('rvpn-integration-test.invalid:80'),
      `body=${body.includes('RVPN-MOCK-OK') ? 'ok' : body.slice(0, 60)} log=${logged.split('\n').filter(Boolean).length} entr(y|ies)`);
    await page.close();

    // 5b. interactive challenge round-trip (captcha / SMS / TOTP)
    if (CHALLENGE) {
      const pending = await poll(async () => {
        const res = await sendToExtension(ui, { type: 'getState' });
        const list = (res && res.challenges) || [];
        return list.length ? list[0] : null;
      }, 15000, 300);
      check('challenge reaches the UI', !!pending,
        pending ? `type=${pending.type} image=${(pending.imageDataUrl || '').slice(0, 22)}...` : 'no challenge');
      // The extension wraps answer parameters in `payload` (see handleMessage).
      const accepted = pending
        ? await sendToExtension(ui, {
            type: 'answer',
            payload: { challengeId: pending.id, code: '1234' },
          })
        : null;
      check('answer is delivered to the core', !!accepted && accepted.ok !== false,
        accepted ? JSON.stringify(accepted).slice(0, 90) : 'not attempted');
      const cleared = await poll(async () => {
        const res = await sendToExtension(ui, { type: 'getState' });
        const list = (res && res.challenges) || [];
        return list.length === 0 ? true : null;
      }, 10000, 300);
      check('answered challenge is cleared from the UI', !!cleared);
    }

    // 6. back to normal mode clears the proxy
    await sendToExtension(ui, { type: 'setMode', mode: 'normal' });
    const cleared = await poll(async () => {
      const details = await getProxySettings(ui);
      const value = details && details.value;
      const rule = value && value.rules && value.rules.singleProxy;
      return (!rule || !rule.port) ? value : null;
    }, 15000, 500);
    check('switching back to normal mode clears the proxy', !!cleared,
      cleared ? `mode=${cleared.mode}` : 'proxy still set');

    return results.every((r) => r.ok) ? 0 : 1;
  } finally {
    try { await context.close(); } catch (e) { /* ignore */ }
    if (!KEEP) {
      try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* ignore */ }
    } else {
      console.log(`(kept profile at ${userDataDir})`);
    }
    const failed = results.filter((r) => !r.ok);
    console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
    if (failed.length) console.log('failed: ' + failed.map((f) => f.name).join(', '));
  }
}

main().then((code) => process.exit(code)).catch((e) => {
  console.error('test crashed:', e);
  process.exit(3);
});
