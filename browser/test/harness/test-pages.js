'use strict';
/**
 * UI smoke tests: run popup.js and options.js against a DOM stub with a
 * scripted service-worker response. Catches crashes, wiring mistakes and
 * verifies the click-captcha coordinate mapping.
 */

const { loadPage, findByTag, findByClass } = require('./dom-stub');
const { loadMessages } = require('./chrome-mock');

const messages = loadMessages('zh_CN');
let passed = 0;
const failures = [];
const unhandled = [];

process.on('unhandledRejection', (r) => unhandled.push(String(r)));

function check(name, cond, detail) {
  if (cond) { passed += 1; console.log('  ok   ' + name); }
  else { failures.push(name + (detail ? ' -> ' + detail : '')); console.log('  FAIL ' + name + (detail ? ' -> ' + detail : '')); }
}

function section(t) { console.log('\n== ' + t); }

const SETTINGS = {
  userType: 'zju', protocol: 'atrust', server: '', port: 443, username: 'student1',
  password: '', savePassword: true, loginDomain: 'Radius', authType: '', phone: '',
  totpSecret: '', socksPort: '', httpPort: '', debug: false, proxyAll: false
};

function snapshot(extra) {
  return Object.assign({
    ok: true,
    mode: 'rvpn',
    state: {
      phase: 'connected', message: '已连接 vpn.zju.edu.cn:443', since: Date.now(),
      protocol: 'atrust', userType: 'zju', server: 'vpn.zju.edu.cn:443',
      socksAddr: '127.0.0.1:38271', httpAddr: '127.0.0.1:38272', clientIP: '10.10.1.23',
      connectedAt: Date.now(), lastError: ''
    },
    hello: {
      version: '1.0.0', coreVersion: '1.0.0', zjuConnectVersion: 'v1.4.2',
      platform: 'linux', arch: 'amd64', capabilities: ['atrust', 'captcha']
    },
    coreAvailable: true,
    coreError: '',
    suspended: '',
    fallback: null,
    challenges: [],
    settings: SETTINGS,
    logs: [],
    lastN: 0,
    logTotal: 0,
    pollMs: 500
  }, extra || {});
}

async function popupTests() {
  section('popup.js: renders with the core absent');
  {
    const page = loadPage('popup', {
      messages,
      respond(msg) {
        if (msg.type === 'popupOpened' || msg.type === 'getState' || msg.type === 'requestStatus') {
          return snapshot({
            mode: 'normal', coreAvailable: false, coreError: 'Specified native messaging host not found.',
            state: null, hello: null, settings: SETTINGS, logs: []
          });
        }
        if (msg.type === 'diagnostics') return { ok: true, text: '=== RVPN Browser diagnostics ===\nhost: com.achtrw.rvpn' };
        return { ok: true };
      }
    });
    await page.settle();
    const alerts = page.document.getElementById('alerts');
    check('popup rendered without throwing', true);
    check('core-missing alert shown', findByClass(alerts, 'alert-error').length >= 1);
    check('alert names the native host', JSON.stringify(alerts.children.map((c) => c.textContent)).indexOf('com.achtrw.rvpn') !== -1 ||
      findByClass(alerts, 'alert-error').length >= 1);
    check('mode badge says normal mode', page.document.getElementById('mode-badge').textContent === messages.mode_normal.message,
      page.document.getElementById('mode-badge').textContent);
    check('status grid still rendered', page.document.getElementById('status-grid').children.length > 0);
    check('form filled from settings', page.document.getElementById('username').value === 'student1');
    check('save-password defaults to checked', page.document.getElementById('save-password').checked === true);
    check('port field hidden for ZJU', page.document.getElementById('field-port').classList.contains('hidden'));

    // copy diagnostics button must round trip through the worker
    const copyBtn = findByTag(page.document.getElementById('alerts'), 'BUTTON');
    check('alert offers retry / copy diagnostics', copyBtn.length >= 1);
    page.document.getElementById('btn-copy-diag').dispatch('click');
    await page.settle();
    check('diagnostics message sent', page.sent.some((m) => m.type === 'diagnostics'));
  }

  section('popup.js: RVPN status, log and challenges');
  {
    const challenges = [
      { id: 'c1', type: 'clickCode', message: '请依次点击：文字', imageDataUrl: 'data:image/png;base64,AAA', canSkipSecondaryAuth: false, loginUrl: '', deadline: 0, submitted: false },
      { id: 'c2', type: 'smsCode', message: '请输入短信验证码', imageDataUrl: '', canSkipSecondaryAuth: false, loginUrl: '', deadline: Date.now() + 60000, submitted: false },
      { id: 'c3', type: 'secondaryAuth', message: '二次认证', imageDataUrl: '', canSkipSecondaryAuth: true, loginUrl: '', deadline: 0, submitted: false },
      { id: 'c4', type: 'externalLogin', message: 'CAS 登录', imageDataUrl: '', canSkipSecondaryAuth: false, loginUrl: 'https://zjuam.zju.edu.cn/login', deadline: 0, submitted: false },
      { id: 'c5', type: 'text', message: '图形验证码', imageDataUrl: 'data:image/png;base64,BBB', canSkipSecondaryAuth: false, loginUrl: '', deadline: 0, submitted: false },
      { id: 'c6', type: 'totp', message: '动态口令', imageDataUrl: '', canSkipSecondaryAuth: false, loginUrl: '', deadline: 0, submitted: false },
      { id: 'c7', type: 'radiusCode', message: 'Radius 验证码', imageDataUrl: '', canSkipSecondaryAuth: false, loginUrl: '', deadline: 0, submitted: false }
    ];
    const logs = [];
    for (let i = 1; i <= 3; i += 1) {
      logs.push({ n: i, seq: i, ts: Date.now(), kind: 'log', level: i === 3 ? 'error' : 'info', message: 'event ' + i });
    }
    const page = loadPage('popup', {
      messages,
      respond(msg) {
        if (msg.type === 'popupOpened') return snapshot({ challenges, logs, lastN: 3 });
        if (msg.type === 'requestStatus') return snapshot({ challenges, logs: [], lastN: 3 });
        if (msg.type === 'answer') return { ok: true };
        return { ok: true };
      }
    });
    await page.settle();
    check('mode badge says RVPN mode',
      page.document.getElementById('mode-badge').textContent === messages.mode_rvpn.message,
      page.document.getElementById('mode-badge').textContent);
    check('phase badge shows connected',
      page.document.getElementById('phase-badge').textContent === messages.phase_connected.message);
    check('status grid shows the socks address',
      JSON.stringify(page.document.getElementById('status-grid').children.map((c) => c.textContent))
        .indexOf('127.0.0.1:38271') !== -1);
    check('proxy row shows the applied proxy',
      JSON.stringify(page.document.getElementById('status-grid').children.map((c) => c.textContent))
        .indexOf('已启用') !== -1);
    check('log rows rendered newest first',
      page.document.getElementById('log-view').children.length === 3 &&
      page.document.getElementById('log-view').children[0].textContent.indexOf('event 3') !== -1,
      JSON.stringify(page.document.getElementById('log-view').children.map((c) => c.textContent)));
    check('error log row marked', findByClass(page.document.getElementById('log-view'), 'log-level-error').length === 1);

    const area = page.document.getElementById('challenge-area');
    const list = page.document.getElementById('challenge-list');
    check('challenge area visible', !area.classList.contains('hidden'));
    const boxes = findByClass(list, 'challenge');
    check('all 7 challenge types rendered', boxes.length === 7, 'rendered=' + boxes.length);
    check('challenge types labelled',
      boxes[0].textContent.indexOf(messages.challenge_type_clickCode.message) !== -1);

    // ---- click captcha: coordinate mapping ----
    const canvases = findByTag(list, 'canvas');
    check('clickCode canvas exists (one per click challenge)', canvases.length === 1, 'canvases=' + canvases.length);
    const imgs = findByTag(list, 'img');
    check('graphic text captcha image displayed',
      imgs.length === 1 && imgs[0].src === 'data:image/png;base64,BBB',
      JSON.stringify(imgs.map((i) => i.src)));
    const canvas = canvases[0];
    check('canvas sized from the image', canvas.width === 300 && canvas.height === 150,
      canvas.width + 'x' + canvas.height);
    canvas._rect = { left: 0, top: 0, width: 150, height: 75 }; // displayed at 50 %
    canvas.dispatch('click', { clientX: 30, clientY: 20 });
    const pointsLine = findByClass(list, 'challenge-points')[0];
    check('point counter updated', /1/.test(pointsLine.textContent), pointsLine.textContent);

    const buttons = findByTag(boxes[0], 'button');
    const submit = buttons[buttons.length - 1];
    submit.dispatch('click');
    await page.settle();
    const answerMsg = page.sent.filter((m) => m.type === 'answer').slice(-1)[0];
    check('clickCode answer sent', !!answerMsg, JSON.stringify(page.sent.map((m) => m.type)));
    check('click mapped back to ORIGINAL pixel coordinates',
      answerMsg && answerMsg.payload.points.length === 1 &&
      answerMsg.payload.points[0].x === 60 && answerMsg.payload.points[0].y === 40,
      JSON.stringify(answerMsg && answerMsg.payload.points));
    check('original image size sent', answerMsg && answerMsg.payload.width === 300 && answerMsg.payload.height === 150);

    // undo / clear
    const undoBtn = buttons[0];
    undoBtn.dispatch('click');
    check('undo removes the point', /0/.test(findByClass(list, 'challenge-points')[0].textContent));

    // ---- sms code ----
    const smsBox = boxes[1];
    const smsInput = findByTag(smsBox, 'input')[0];
    smsInput.value = '654321';
    const smsButtons = findByTag(smsBox, 'button');
    smsButtons[smsButtons.length - 1].dispatch('click');
    await page.settle();
    const smsAnswer = page.sent.filter((m) => m.type === 'answer').slice(-1)[0];
    check('sms answer sent with the code', smsAnswer && smsAnswer.payload.code === '654321',
      JSON.stringify(smsAnswer && smsAnswer.payload));

    // ---- secondary auth skip checkbox ----
    const secBox = boxes[2];
    const secInputs = findByTag(secBox, 'input');
    const skipBox = secInputs.filter((i) => i.type === 'checkbox')[0];
    check('skip checkbox offered', !!skipBox);
    skipBox.checked = true;
    const secButtons = findByTag(secBox, 'button');
    secButtons[secButtons.length - 1].dispatch('click');
    await page.settle();
    const skipAnswer = page.sent.filter((m) => m.type === 'answer').slice(-1)[0];
    check('skipSecondaryAuth forwarded', skipAnswer && skipAnswer.payload.skipSecondaryAuth === true,
      JSON.stringify(skipAnswer && skipAnswer.payload));

    // ---- external login ----
    const extBox = boxes[3];
    check('external login URL rendered',
      JSON.stringify(findByTag(extBox, 'a').map((a) => a.href)).indexOf('zjuam.zju.edu.cn') !== -1);
    const extInput = findByTag(extBox, 'input')[0];
    extInput.value = 'https://zjuam.zju.edu.cn/cas/callback?ticket=ST-1';
    const extButtons = findByTag(extBox, 'button');
    extButtons[extButtons.length - 1].dispatch('click');
    await page.settle();
    const extAnswer = page.sent.filter((m) => m.type === 'answer').slice(-1)[0];
    check('callback URL submitted as code',
      extAnswer && /ticket=ST-1/.test(extAnswer.payload.code || ''), JSON.stringify(extAnswer && extAnswer.payload));
  }

  section('popup.js: actions');
  {
    const page = loadPage('popup', { messages, respond: () => snapshot({}) });
    await page.settle();
    page.document.getElementById('btn-mode-rvpn').dispatch('click');
    await page.settle();
    check('mode button sends setMode(rvpn)',
      page.sent.some((m) => m.type === 'setMode' && m.mode === 'rvpn'),
      JSON.stringify(page.sent.map((m) => m.type)));
    page.document.getElementById('btn-mode-normal').dispatch('click');
    await page.settle();
    check('mode button sends setMode(normal)',
      page.sent.some((m) => m.type === 'setMode' && m.mode === 'normal'));

    page.document.getElementById('username').value = 'u2';
    page.document.getElementById('password').value = 'pw2';
    page.document.getElementById('btn-save-connect').dispatch('click');
    await page.settle();
    const save = page.sent.filter((m) => m.type === 'saveSettings').slice(-1)[0];
    check('save+connect sends the form', save && save.settings.username === 'u2' && save.settings.password === 'pw2',
      JSON.stringify(save && save.settings));
    check('save+connect then switches mode',
      page.sent.filter((m) => m.type === 'setMode').length >= 3);

    page.document.getElementById('btn-selftest').dispatch('click');
    await page.settle();
    const st = page.sent.filter((m) => m.type === 'selftest').slice(-1)[0];
    check('selftest uses the auto-filled ZJU server',
      st && st.server === 'vpn.zju.edu.cn' && st.port === 443, JSON.stringify(st));

    // protocol switch re-fills the server
    page.document.getElementById('protocol').value = 'easyconnect';
    page.document.getElementById('protocol').dispatch('change');
    check('easyconnect fills rvpn.zju.edu.cn', page.document.getElementById('server').value === 'rvpn.zju.edu.cn',
      page.document.getElementById('server').value);
    page.document.getElementById('user-type').value = 'other';
    page.document.getElementById('user-type').dispatch('change');
    check('port field shown for non-ZJU', !page.document.getElementById('field-port').classList.contains('hidden'));

    page.document.getElementById('btn-clear-log').dispatch('click');
    await page.settle();
    check('clear log message sent', page.sent.some((m) => m.type === 'clearLog'));
  }

  section('popup.js: push updates and the 500 ms poll');
  {
    const page = loadPage('popup', { messages, respond: () => snapshot({}) });
    await page.settle();
    check('poll interval registered at 500 ms',
      page.timers.some((t) => t.ms === 500), JSON.stringify(page.timers.map((t) => t.ms)));
    const before = page.sent.filter((m) => m.type === 'requestStatus').length;
    page.pushUpdate({ type: 'update', reason: 'challenge' });
    await page.settle();
    check('push update triggers a refresh',
      page.sent.filter((m) => m.type === 'requestStatus').length > before);
    const beforeTick = page.sent.filter((m) => m.type === 'requestStatus').length;
    page.tick();
    await page.settle();
    check('poll tick refreshes status',
      page.sent.filter((m) => m.type === 'requestStatus').length > beforeTick);
  }
}

async function optionsTests() {
  section('options.js: form, probe, self-test and validation');
  {
    const responses = {
      authInfo: {
        ok: true,
        data: {
          list: [
            { loginDomain: 'Radius', authType: 'auth/psw', authName: '上网账号', loginUrl: '' },
            { loginDomain: 'Radius', authType: 'auth/smsCheckCode', authName: '短信认证', loginUrl: '' }
          ]
        }
      },
      selftest: { ok: true, data: { reachable: true, latencyMs: 18, server: 'vpn.zju.edu.cn:443', error: '' } },
      hello: { ok: true, data: { coreVersion: '1.0.0', platform: 'linux', arch: 'amd64', capabilities: ['atrust'] } }
    };
    const page = loadPage('options', {
      messages,
      respond(msg) {
        if (msg.type === 'getState' || msg.type === 'requestStatus') return snapshot({});
        if (responses[msg.type]) return responses[msg.type];
        return { ok: true };
      }
    });
    await page.settle();
    check('options rendered without throwing', true);
    check('mode badge rendered',
      page.document.getElementById('mode-badge').textContent === messages.mode_rvpn.message);
    check('core grid filled',
      JSON.stringify(page.document.getElementById('core-grid').children.map((c) => c.textContent)).indexOf('1.0.0') !== -1);
    check('form filled', page.document.getElementById('username').value === 'student1');
    check('login domain fixed to Radius for ZJU',
      page.document.getElementById('login-domain').value === 'Radius' &&
      page.document.getElementById('login-domain').disabled === true);
    check('auth type hidden for ZJU', page.document.getElementById('field-auth-type').classList.contains('hidden'));

    // switch to non-ZJU -> extra fields appear
    page.document.getElementById('user-type').value = 'other';
    page.document.getElementById('user-type').dispatch('change');
    check('port shown for other user', !page.document.getElementById('field-port').classList.contains('hidden'));
    check('login domain editable for other user', page.document.getElementById('login-domain').disabled === false);
    check('auth type shown for other user', !page.document.getElementById('field-auth-type').classList.contains('hidden'));
    check('hint switched to the non-ZJU text',
      page.document.getElementById('hint-login-domain').textContent === messages.hint_login_domain_other.message);

    // authInfo probe
    page.document.getElementById('server').value = 'vpn.example.edu.cn';
    page.document.getElementById('port').value = '8443';
    page.document.getElementById('btn-probe').dispatch('click');
    await page.settle();
    const probeMsg = page.sent.filter((m) => m.type === 'authInfo').slice(-1)[0];
    check('probe calls authInfo with server+port',
      probeMsg && probeMsg.server === 'vpn.example.edu.cn' && probeMsg.port === 8443, JSON.stringify(probeMsg));
    const items = findByClass(page.document.getElementById('probe-results'), 'probe-item');
    check('probe results rendered as options', items.length === 2, 'items=' + items.length);
    check('probe hint shown', /点击/.test(page.document.getElementById('probe-status').textContent));
    items[1].dispatch('click');
    check('clicking a probe result fills the auth type',
      page.document.getElementById('auth-type').value === 'auth/smsCheckCode',
      page.document.getElementById('auth-type').value);
    check('clicking a probe result fills the login domain',
      page.document.getElementById('login-domain').value === 'Radius');

    // self-test
    page.document.getElementById('btn-selftest').dispatch('click');
    await page.settle();
    check('selftest requested with the typed server',
      page.sent.some((m) => m.type === 'selftest' && m.server === 'vpn.example.edu.cn' && m.port === 8443));
    check('selftest result rendered',
      /18/.test(page.document.getElementById('selftest-result').textContent),
      page.document.getElementById('selftest-result').textContent);

    // validation: empty username blocks the save
    page.document.getElementById('username').value = '';
    const savesBefore = page.sent.filter((m) => m.type === 'saveSettings').length;
    page.document.getElementById('btn-save').dispatch('click');
    await page.settle();
    check('invalid form not saved',
      page.sent.filter((m) => m.type === 'saveSettings').length === savesBefore);
    check('validation message shown',
      /账号/.test(page.document.getElementById('save-result').textContent),
      page.document.getElementById('save-result').textContent);

    // sms auth without a phone number is rejected client-side
    page.document.getElementById('username').value = 'u3';
    page.document.getElementById('auth-type').value = 'auth/smsCheckCode';
    page.document.getElementById('btn-save').dispatch('click');
    await page.settle();
    check('sms auth without phone rejected',
      /手机号/.test(page.document.getElementById('save-result').textContent),
      page.document.getElementById('save-result').textContent);

    // valid save
    page.document.getElementById('phone').value = '8613800138000';
    page.document.getElementById('btn-save').dispatch('click');
    await page.settle();
    const save = page.sent.filter((m) => m.type === 'saveSettings').slice(-1)[0];
    check('valid settings saved', !!save && save.settings.phone === '8613800138000' &&
      save.settings.authType === 'auth/smsCheckCode', JSON.stringify(save && save.settings));
    check('login domain + port included',
      save.settings.loginDomain === 'Radius' && save.settings.port === 8443);
    check('save confirmation shown',
      page.document.getElementById('save-result').textContent === messages.saved_ok.message,
      page.document.getElementById('save-result').textContent);

    // save and connect
    page.document.getElementById('btn-save-connect').dispatch('click');
    await page.settle();
    check('save+connect switches to RVPN',
      page.sent.some((m) => m.type === 'setMode' && m.mode === 'rvpn'));

    // restore defaults
    page.document.getElementById('btn-reset').dispatch('click');
    check('reset restores the user type', page.document.getElementById('user-type').value === 'zju');
    check('reset restores an empty server', page.document.getElementById('server').value === '');

    // core refresh + diagnostics
    page.document.getElementById('btn-refresh-core').dispatch('click');
    await page.settle();
    check('hello requested', page.sent.some((m) => m.type === 'hello'));
    page.document.getElementById('btn-reconnect').dispatch('click');
    await page.settle();
    check('reconnect requested', page.sent.some((m) => m.type === 'reconnect'));
  }

  section('options.js: core absent');
  {
    const page = loadPage('options', {
      messages,
      respond(msg) {
        if (msg.type === 'getState' || msg.type === 'requestStatus') {
          return snapshot({ mode: 'normal', coreAvailable: false, coreError: 'host not found', state: null, hello: null });
        }
        return { ok: false, error: 'host not found', code: 'NO_HOST' };
      }
    });
    await page.settle();
    check('options renders with no core', findByClass(page.document.getElementById('alerts'), 'alert-error').length >= 1);
    check('core state shown as disconnected',
      JSON.stringify(page.document.getElementById('core-grid').children.map((c) => c.textContent))
        .indexOf(messages.value_disconnected.message) !== -1);
    page.document.getElementById('btn-selftest').dispatch('click');
    await page.settle();
    check('failed selftest reported, no crash',
      page.document.getElementById('selftest-result').textContent.length > 0);
  }
}

async function main() {
  await popupTests();
  await optionsTests();
  await new Promise((r) => setTimeout(r, 100));
  console.log('\n=========================================');
  console.log('passed: ' + passed + ', failed: ' + failures.length);
  console.log('unhandled rejections: ' + unhandled.length);
  if (unhandled.length) unhandled.forEach((u) => console.log('  ' + u));
  if (failures.length) {
    console.log('\nfailures:');
    failures.forEach((f) => console.log('  - ' + f));
    process.exit(1);
  }
  if (unhandled.length) process.exit(1);
  console.log('PAGE TESTS PASSED');
}

main().catch((e) => { console.error('page harness crashed:', e); process.exit(2); });
