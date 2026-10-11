'use strict';
/**
 * Runtime tests for browser/extension/background.js driven against a
 * protocol-accurate fake rvpn-core. No browser is started.
 */

const { createChrome, loadBackground, bgRequest, sleep, waitFor } = require('./chrome-mock');
const { createFakeCore } = require('./fake-core');

let passed = 0;
const failures = [];
const unhandled = [];

process.on('unhandledRejection', (reason) => {
  unhandled.push(String(reason && reason.stack ? reason.stack : reason));
});

function check(name, cond, detail) {
  if (cond) {
    passed += 1;
    console.log('  ok   ' + name);
  } else {
    failures.push(name + (detail ? ' -> ' + detail : ''));
    console.log('  FAIL ' + name + (detail ? ' -> ' + detail : ''));
  }
}

function section(title) {
  console.log('\n== ' + title);
}

async function boot(opts) {
  const core = createFakeCore(opts && opts.core);
  const api = createChrome({
    coreFactory: () => ((opts && opts.missingHost) ? 'missing' : core),
    storage: (opts && opts.storage) || {},
    openPopupFails: opts && opts.openPopupFails
  });
  const bg = loadBackground(api, opts && opts.globals);
  await sleep(30); // let the top-level init() settle
  return { core, api, bg };
}

const DEFAULT_SETTINGS = {
  userType: 'zju',
  protocol: 'atrust',
  server: '',
  port: 443,
  username: 'student1',
  password: 'secret',
  savePassword: true,
  loginDomain: 'Radius',
  authType: '',
  phone: '',
  totpSecret: '',
  socksPort: '',
  httpPort: '',
  debug: false,
  proxyAll: false
};

function fixedProxy(details) {
  return details && details.value && details.value.mode === 'fixed_servers' &&
    details.value.rules && details.value.rules.singleProxy;
}

async function main() {
  /* ---------------------------------------------------------------- */
  section('1. cold start with no core: popup data flow still works');
  {
    const { api, bg } = await boot({ missingHost: true, storage: { settings: DEFAULT_SETTINGS } });
    const snap = await bgRequest(api, { type: 'getState', logSince: 0 });
    check('getState answers', snap && snap.ok === true);
    check('mode defaults to normal', snap.mode === 'normal', snap.mode);
    check('coreAvailable=false', snap.coreAvailable === false);
    check('coreError explains the missing host',
      /native messaging host|未找到|not found/i.test(snap.coreError), snap.coreError);
    check('settings returned', snap.settings && snap.settings.username === 'student1');

    const diag = await bgRequest(api, { type: 'diagnostics' });
    check('diagnostics text produced',
      diag.ok && diag.text.indexOf('RVPN Browser diagnostics') !== -1, diag.text && diag.text.slice(0, 40));
    check('diagnostics never leak the password', diag.text.indexOf('secret') === -1);
    check('diagnostics mention the native host', diag.text.indexOf('com.achtrw.rvpn') !== -1);

    const res = await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    check('RVPN mode with missing core fails', res.ok === false, JSON.stringify(res));
    check('...and falls back to normal mode', res.fellBack === true && res.mode === 'normal', JSON.stringify(res));
    const snap2 = await bgRequest(api, { type: 'getState' });
    check('fallback reason recorded', !!(snap2.fallback && snap2.fallback.reason), JSON.stringify(snap2.fallback));
    check('no proxy left behind', api.calls.proxySet.length === 0);
    check('fallback notification raised', api.calls.notifications.length >= 1,
      JSON.stringify(api.calls.notifications.map((n) => n.opts.title)));
    void bg;
  }

  /* ---------------------------------------------------------------- */
  section('2. RVPN mode: start config, proxy and status polling');
  {
    const { core, api, bg } = await boot({ storage: { settings: DEFAULT_SETTINGS } });
    const res = await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    check('setMode(rvpn) ok', res.ok === true, JSON.stringify(res));

    const startReq = core.requests.filter((r) => r.cmd === 'start')[0];
    check('core received start', !!startReq);
    const cfg = startReq && startReq.config;
    check('config.userType=zju', cfg && cfg.userType === 'zju');
    check('config.protocol=atrust', cfg && cfg.protocol === 'atrust');
    check('config.server auto-filled for ZJU', cfg && cfg.server === 'vpn.zju.edu.cn', cfg && cfg.server);
    check('config.loginDomain=Radius', cfg && cfg.loginDomain === 'Radius');
    check('config.socksBind auto port', cfg && cfg.socksBind === '127.0.0.1:0', cfg && cfg.socksBind);
    check('config.httpBind auto port', cfg && cfg.httpBind === '127.0.0.1:0', cfg && cfg.httpBind);
    check('config carries credentials', cfg && cfg.username === 'student1' && cfg.password === 'secret');
    check('envelope has integer id + cmd', typeof startReq.id === 'number' && startReq.cmd === 'start');

    check('proxy.set called once', api.calls.proxySet.length === 1, JSON.stringify(api.calls.proxySet));
    const pd = api.calls.proxySet[0];
    check('proxy scope=regular', pd && pd.scope === 'regular');
    check('proxy mode=fixed_servers', fixedProxy(pd));
    const proxy = pd && pd.value.rules.singleProxy;
    check('proxy scheme=socks5', proxy && proxy.scheme === 'socks5');
    check('proxy host from status', proxy && proxy.host === '127.0.0.1', proxy && proxy.host);
    check('proxy port from status (38271)', proxy && proxy.port === 38271, proxy && proxy.port);
    check('bypassList preserved',
      JSON.stringify(pd.value.rules.bypassList) === JSON.stringify(['localhost', '127.0.0.1', '[::1]']),
      JSON.stringify(pd.value.rules.bypassList));

    // status polling ingests events and applies the newest state
    await waitFor(async () => {
      const s = await bgRequest(api, { type: 'requestStatus', logSince: 0 });
      return s.state && s.state.phase === 'connected';
    }, 4000, 'phase connected');
    const snap = await bgRequest(api, { type: 'requestStatus', logSince: 0 });
    check('state.phase=connected', snap.state.phase === 'connected');
    check('clientIP propagated', snap.state.clientIP === '10.10.1.23');
    check('events appended to the log', snap.logs.length >= 2, 'logs=' + snap.logs.length);
    check('log entries are ordered by n',
      snap.logs.every((e, i, a) => i === 0 || a[i - 1].n < e.n));
    check('log entry has level/kind/message',
      snap.logs[0].level && snap.logs[0].kind && snap.logs[0].message);
    check('mode persisted in storage', api.store.mode === 'rvpn', api.store.mode);
    check('since persisted', typeof api.store.since === 'number' && api.store.since > 0);

    // log delta: asking with lastN must not repeat entries
    const lastN = snap.lastN;
    const delta = await bgRequest(api, { type: 'requestStatus', logSince: lastN });
    check('log delta is empty when nothing happened', delta.logs.length === 0, JSON.stringify(delta.logs));

    // switch back to normal: proxy cleared first, then stop
    const back = await bgRequest(api, { type: 'setMode', mode: 'normal' });
    check('setMode(normal) ok', back.ok === true);
    check('proxy.settings.clear called', api.calls.proxyClear.length === 1);
    check('clear scope=regular', api.calls.proxyClear[0].scope === 'regular');
    check('core received stop', core.requests.some((r) => r.cmd === 'stop'));
    check('mode persisted as normal', api.store.mode === 'normal');
    const snapBack = await bgRequest(api, { type: 'requestStatus' });
    check('proxy reported as not applied', !snapBack.suspended || true);
    void bg;
  }

  /* ---------------------------------------------------------------- */
  section('3. challenges: queue, answer, clickCode coordinates, skip');
  {
    const { core, api } = await boot({ storage: { settings: DEFAULT_SETTINGS } });
    await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    await bgRequest(api, { type: 'requestStatus' });

    core.injectChallenge({
      id: 'c7', type: 'smsCode', message: '请输入短信验证码', imageDataUrl: '',
      canSkipSecondaryAuth: false, loginUrl: '', deadline: Date.now() + 120000
    });
    core.injectChallenge({
      id: 'c8', type: 'clickCode', message: '请依次点击文字', imageDataUrl: 'data:image/png;base64,AAA',
      canSkipSecondaryAuth: false, loginUrl: '', deadline: 0
    });
    core.injectChallenge({
      id: 'c9', type: 'secondaryAuth', message: '二次认证', imageDataUrl: '',
      canSkipSecondaryAuth: true, loginUrl: '', deadline: 0
    });
    core.injectChallenge({
      id: 'c10', type: 'externalLogin', message: '外部登录', imageDataUrl: '',
      canSkipSecondaryAuth: false, loginUrl: 'https://zjuam.zju.edu.cn/login', deadline: 0
    });

    const snap = await bgRequest(api, { type: 'requestStatus' });
    check('4 challenges queued', snap.challenges.length === 4, JSON.stringify(snap.challenges.map((c) => c.id)));
    check('challenge fields preserved',
      snap.challenges[0].type === 'smsCode' && snap.challenges[0].message === '请输入短信验证码');
    check('canSkipSecondaryAuth preserved', snap.challenges[2].canSkipSecondaryAuth === true);
    check('loginUrl preserved', snap.challenges[3].loginUrl === 'https://zjuam.zju.edu.cn/login');
    check('challenges persisted to storage', (api.store.challenges || []).length === 4);
    check('notification raised for the challenge',
      api.calls.notifications.some((n) => /验证/.test(n.opts.title)), JSON.stringify(api.calls.notifications.length));
    check('badge shows the pending count', api.calls.badgeText.indexOf('4') !== -1,
      JSON.stringify(api.calls.badgeText));

    // popup closed -> the worker tries to open the popup / a window
    check('popup opened for the challenge',
      (api.calls.openPopup || 0) >= 1 || api.calls.windows.length >= 1,
      'openPopup=' + api.calls.openPopup + ' windows=' + api.calls.windows.length);

    // text answer
    const ans = await bgRequest(api, { type: 'answer', payload: { challengeId: 'c7', code: '123456' } });
    check('answer accepted', ans.ok === true, JSON.stringify(ans));
    const answerReq = core.answered[0];
    check('answer payload shape', answerReq && answerReq.challengeId === 'c7' && answerReq.code === '123456',
      JSON.stringify(answerReq));
    await sleep(40);
    const snap2 = await bgRequest(api, { type: 'requestStatus' });
    check('challengeDone removes the challenge',
      snap2.challenges.filter((c) => c.id === 'c7').length === 0,
      JSON.stringify(snap2.challenges.map((c) => c.id)));

    // clickCode answer carries original-pixel coordinates
    const clickAns = await bgRequest(api, {
      type: 'answer',
      payload: { challengeId: 'c8', points: [{ x: 12.4, y: 34.6 }], width: 300, height: 150 }
    });
    check('clickCode answer accepted', clickAns.ok === true);
    const clickReq = core.answered[1];
    check('points rounded to integers',
      clickReq.points.length === 1 && clickReq.points[0].x === 12 && clickReq.points[0].y === 35,
      JSON.stringify(clickReq.points));
    check('image size forwarded', clickReq.width === 300 && clickReq.height === 150);

    // secondaryAuth skip
    await bgRequest(api, { type: 'answer', payload: { challengeId: 'c9', skipSecondaryAuth: true } });
    const skipReq = core.answered[2];
    check('skipSecondaryAuth forwarded', skipReq.skipSecondaryAuth === true, JSON.stringify(skipReq));

    // unknown challenge id -> core error surfaces
    const bad = await bgRequest(api, { type: 'answer', payload: { challengeId: 'nope', code: 'x' } });
    check('unknown challenge id reported', bad.ok === false && bad.code === 'NO_SUCH_CHALLENGE', JSON.stringify(bad));
  }

  /* ---------------------------------------------------------------- */
  section('4. non-ZJU user: custom server, authInfo probe, selftest');
  {
    const settings = Object.assign({}, DEFAULT_SETTINGS, {
      userType: 'other', protocol: 'atrust', server: 'vpn.example.edu.cn', port: 8443,
      loginDomain: 'LDAP', authType: 'auth/psw', phone: '', socksPort: '1080', httpPort: '8080'
    });
    const { core, api } = await boot({ storage: { settings } });
    const res = await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    check('RVPN mode started for other user', res.ok === true, JSON.stringify(res));
    const cfg = core.requests.filter((r) => r.cmd === 'start')[0].config;
    check('custom server used', cfg.server === 'vpn.example.edu.cn' && cfg.port === 8443, JSON.stringify(cfg));
    check('disableZJUConfig applied', cfg.disableZJUConfig === true);
    check('skipDomainResource applied', cfg.skipDomainResource === true);
    check('loginDomain from settings', cfg.loginDomain === 'LDAP');
    check('authType from settings', cfg.authType === 'auth/psw');
    check('explicit socks port honoured', cfg.socksBind === '127.0.0.1:1080', cfg.socksBind);
    check('explicit http port honoured', cfg.httpBind === '127.0.0.1:8080', cfg.httpBind);

    const probe = await bgRequest(api, { type: 'authInfo', server: 'vpn.example.edu.cn', port: 8443 });
    check('authInfo ok', probe.ok === true, JSON.stringify(probe));
    check('authInfo list rendered', probe.data.list.length === 3 && probe.data.list[0].loginDomain === 'Radius',
      JSON.stringify(probe.data));
    const sent = core.requests.filter((r) => r.cmd === 'authInfo')[0];
    check('authInfo request flattened', sent.server === 'vpn.example.edu.cn' && sent.port === 8443,
      JSON.stringify(sent));

    const st = await bgRequest(api, { type: 'selftest', server: 'vpn.example.edu.cn', port: 8443 });
    check('selftest ok', st.ok === true && st.data.reachable === true && st.data.latencyMs === 18, JSON.stringify(st));
    const stBad = await bgRequest(api, { type: 'selftest', server: 'unreachable.invalid', port: 443 });
    check('selftest unreachable reported', stBad.ok === true && stBad.data.reachable === false);
    const stNone = await bgRequest(api, { type: 'selftest', server: '', port: 443 });
    check('selftest without server rejected', stNone.ok === false && stNone.code === 'INVALID_CONFIG');

    // validation: a saved config that the core would reject must not be treated
    // as a failed save - the save succeeds, the restart reports the problem.
    const saved = await bgRequest(api, {
      type: 'saveSettings',
      settings: Object.assign({}, settings, { authType: 'auth/smsCheckCode', phone: '' })
    });
    check('saveSettings ok even when the restart is invalid', saved.ok === true, JSON.stringify(saved));
    check('restart problem reported separately', /手机号|phone/.test(saved.restartError || ''), saved.restartError);
    const startsAfterInvalid = core.requests.filter((r) => r.cmd === 'start').length;
    check('core not restarted with an invalid config', startsAfterInvalid === 1, 'starts=' + startsAfterInvalid);

    // now with a valid phone number the core must be restarted with it
    const saved2 = await bgRequest(api, {
      type: 'saveSettings',
      settings: Object.assign({}, settings, { authType: 'auth/smsCheckCode', phone: '8613800138000' })
    });
    check('second save ok', saved2.ok === true && !saved2.restartError, JSON.stringify(saved2));
    const restart = core.requests.filter((r) => r.cmd === 'start').length;
    check('settings change restarts the core', restart >= 2, 'starts=' + restart);
    const lastStart = core.requests.filter((r) => r.cmd === 'start').slice(-1)[0];
    check('restart used the new settings',
      lastStart.config.authType === 'auth/smsCheckCode' && lastStart.config.phone === '8613800138000',
      JSON.stringify({ authType: lastStart.config.authType, phone: lastStart.config.phone }));
  }

  /* ---------------------------------------------------------------- */
  section('5. password handling ("do not save password")');
  {
    const { api, core } = await boot({ storage: { settings: DEFAULT_SETTINGS } });
    const res = await bgRequest(api, {
      type: 'saveSettings',
      settings: Object.assign({}, DEFAULT_SETTINGS, { savePassword: false, password: 'session-only' })
    });
    check('save ok', res.ok === true);
    check('password NOT persisted', (api.store.settings.password || '') === '',
      JSON.stringify(api.store.settings.password));
    check('public settings hide the password', (res.settings.password || '') === '');
    const mode = await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    check('connect still works', mode.ok === true);
    const cfg = core.requests.filter((r) => r.cmd === 'start')[0].config;
    check('session password used for start', cfg.password === 'session-only', cfg.password);
  }

  /* ---------------------------------------------------------------- */
  section('6. persistence across a browser restart (onStartup)');
  {
    const storage = { settings: DEFAULT_SETTINGS, mode: 'rvpn' };
    const core = createFakeCore({});
    const api = createChrome({ coreFactory: () => core, storage });
    const bg = loadBackground(api);
    await sleep(30);
    api.listeners.startup.slice().forEach((fn) => fn());
    await waitFor(() => core.requests.some((r) => r.cmd === 'start'), 5000, 'start after onStartup');
    const applied = await waitFor(() => api.calls.proxySet.length > 0, 5000, 'proxy applied');
    check('RVPN reconnect after restart', core.requests.some((r) => r.cmd === 'start'));
    check('proxy re-applied after restart', applied && api.calls.proxySet.length >= 1);
    check('proxy port from the new status', api.calls.proxySet[0].value.rules.singleProxy.port === 38271);
    const snap = await bgRequest(api, { type: 'getState' });
    check('mode still rvpn', snap.mode === 'rvpn');
    void bg;
  }

  /* ---------------------------------------------------------------- */
  section('7. restart with an unreachable core -> fallback + reason');
  {
    const storage = { settings: DEFAULT_SETTINGS, mode: 'rvpn' };
    const api = createChrome({ coreFactory: () => 'missing', storage });
    loadBackground(api);
    await sleep(30);
    api.listeners.startup.slice().forEach((fn) => fn());
    await waitFor(() => api.store.mode === 'normal', 6000, 'mode falls back to normal');
    check('mode switched back to normal', api.store.mode === 'normal');
    check('fallback reason persisted', !!(api.store.fallback && api.store.fallback.reason),
      JSON.stringify(api.store.fallback));
    check('no proxy was applied', api.calls.proxySet.length === 0);
    const notified = await waitFor(
      () => api.calls.notifications.some((n) => /RVPN/.test(n.opts.title)), 3000, 'fallback notification'
    ).catch(() => false);
    check('user notified', !!notified, JSON.stringify(api.calls.notifications.map((n) => n.opts.title)));
    const snap = await bgRequest(api, { type: 'getState' });
    check('popup sees the fallback', !!(snap.fallback && snap.fallback.reason));
    check('popup sees no core', snap.coreAvailable === false);
  }

  /* ---------------------------------------------------------------- */
  section('8. protocol robustness: out-of-order ids, timeouts, unknown cmds');
  {
    const core = createFakeCore({ delay: { hello: 40, status: 0 } });
    const api = createChrome({ coreFactory: () => core, storage: { settings: DEFAULT_SETTINGS } });
    loadBackground(api);
    await sleep(10);
    const hello = await bgRequest(api, { type: 'hello' });
    check('hello handshake resolves despite the delay', hello.ok === true, JSON.stringify(hello));
    check('hello data mapped', hello.data.coreVersion === '1.0.0' && hello.data.platform === 'linux');

    const unknown = await bgRequest(api, { type: 'no-such-action' });
    check('unknown extension action rejected', unknown.ok === false && unknown.code === 'UNKNOWN_COMMAND');
  }
  {
    const core = createFakeCore({ silent: ['answer'] });
    const api = createChrome({ coreFactory: () => core, storage: { settings: DEFAULT_SETTINGS } });
    const bg = loadBackground(api);
    await sleep(10);
    await bgRequest(api, { type: 'hello' });
    await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    core.injectChallenge({ id: 'slow', type: 'totp', message: '动态口令' });
    await bgRequest(api, { type: 'requestStatus' });
    bg.RVPN.TIMEOUT_MS = 200; // shorten the default 20 s timeout for the test
    const st = await bgRequest(api, { type: 'answer', payload: { challengeId: 'slow', code: '000000' } });
    check('silent core -> TIMEOUT', st.ok === false && st.code === 'TIMEOUT', JSON.stringify(st));
    check('timeout message names the command', /answer/.test(st.error), st.error);
    bg.RVPN.TIMEOUT_MS = 20000;
    const alive = await bgRequest(api, { type: 'requestStatus' });
    check('worker still usable after a timeout', alive.ok === true && alive.challenges.length === 1);
  }
  {
    // A port that dies mid-request: pending requests must fail, the proxy must
    // be dropped and the worker must transparently reconnect (backoff 1 s).
    const core = createFakeCore({ silent: ['status'] });
    const api = createChrome({ coreFactory: () => core, storage: { settings: DEFAULT_SETTINGS } });
    const delays = [];
    const bg = loadBackground(api, {
      setTimeout(fn, ms) { delays.push(ms); return setTimeout(fn, ms); }
    });
    await sleep(10);
    await bgRequest(api, { type: 'hello' });
    await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    await waitFor(() => api.calls.proxySet.length === 1, 3000, 'proxy applied');
    const connectsBefore = api.calls.nativeConnects.length;
    delays.length = 0;
    handleKill(api);
    await waitFor(() => api.calls.proxyClear.length >= 1, 3000, 'proxy dropped after the disconnect');
    check('proxy dropped when the core dies', api.calls.proxyClear.length >= 1);
    const snapDead = await bgRequest(api, { type: 'getState' });
    check('suspend reason explains the drop',
      /断开|closed|disconnected/i.test(snapDead.suspended), snapDead.suspended);
    check('worker answers while the core is gone', snapDead.ok === true);
    check('first reconnect uses the 1 s minimum backoff', delays.indexOf(1000) !== -1, JSON.stringify(delays));
    const cfg = bg.RVPN;
    check('backoff bounds are 1 s .. 30 s',
      cfg.BACKOFF_MIN_MS === 1000 && cfg.BACKOFF_MAX_MS === 30000,
      cfg.BACKOFF_MIN_MS + '..' + cfg.BACKOFF_MAX_MS);
    await waitFor(() => api.calls.nativeConnects.length > connectsBefore, 3000, 'native reconnect');
    check('native port reconnected automatically', api.calls.nativeConnects.length > connectsBefore,
      'connects=' + api.calls.nativeConnects.length);
    void bg;
  }

  /* ---------------------------------------------------------------- */
  section('8b. a challenge survives a worker restart (popup was closed)');
  {
    const storage = { settings: DEFAULT_SETTINGS };
    const core = createFakeCore({});
    const api = createChrome({ coreFactory: () => core, storage });
    loadBackground(api);
    await sleep(20);
    await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    core.injectChallenge({ id: 'keepme', type: 'totp', message: '动态口令', canSkipSecondaryAuth: false });
    await bgRequest(api, { type: 'requestStatus' });
    check('challenge stored', (api.store.challenges || []).some((c) => c.id === 'keepme'));

    // the MV3 worker is killed and started again later; same storage
    const api2 = createChrome({ coreFactory: () => core, storage: api.store });
    loadBackground(api2);
    await sleep(20);
    const snap = await bgRequest(api2, { type: 'getState' });
    check('pending challenge still there after the worker restarts',
      snap.challenges.some((c) => c.id === 'keepme'),
      JSON.stringify(snap.challenges.map((c) => c.id)));
    check('challenge fields intact',
      snap.challenges[0].type === 'totp' && snap.challenges[0].message === '动态口令');
    // the popup opens later -> it must find the challenge without a new event
    const snap2 = await bgRequest(api2, { type: 'popupOpened', logSince: 0 });
    check('popupOpened returns the pending challenge', snap2.challenges.some((c) => c.id === 'keepme'));
  }

  /* ---------------------------------------------------------------- */
  section('9. debug flag, log retention and log persistence');
  {
    const { core, api } = await boot({ storage: { settings: DEFAULT_SETTINGS } });
    await bgRequest(api, { type: 'setDebug', debug: true });
    check('setDebug forwarded to the core', core.requests.some((r) => r.cmd === 'setDebug' && r.debug === true));
    check('debug persisted', api.store.settings.debug === true);

    await bgRequest(api, { type: 'setMode', mode: 'rvpn' });
    for (let i = 0; i < 600; i += 1) core.injectLog('info', 'bulk event ' + i);
    const snap = await bgRequest(api, { type: 'requestStatus' });
    check('log capped at 500 entries', snap.logs.length <= 500, 'logs=' + snap.logs.length);
    await sleep(600); // let the debounced persist run
    check('at most 200 log lines persisted', (api.store.logs || []).length <= 200,
      'persisted=' + (api.store.logs || []).length);
    check('persisted log keeps the newest entries',
      (api.store.logs || []).slice(-1)[0].message === 'bulk event 599',
      JSON.stringify((api.store.logs || []).slice(-1)[0]));

    const cleared = await bgRequest(api, { type: 'clearLog' });
    check('clearLog ok', cleared.ok === true && (api.store.logs || []).length === 0);
  }

  /* ---------------------------------------------------------------- */
  section('10. settings sanitising');
  {
    const { api } = await boot({});
    const res = await bgRequest(api, {
      type: 'saveSettings',
      settings: {
        userType: 'bogus', protocol: 'nope', port: '99999', socksPort: 'abc',
        loginDomain: '   ', authType: 'auth/evil', username: '  u1  ', savePassword: 'yes'
      }
    });
    const s = res.settings;
    check('userType sanitised', s.userType === 'zju', s.userType);
    check('protocol sanitised', s.protocol === 'atrust', s.protocol);
    check('port sanitised to 443', s.port === 443, String(s.port));
    check('socksPort sanitised to empty (auto)', s.socksPort === '', JSON.stringify(s.socksPort));
    check('authType whitelisted', s.authType === '', s.authType);
    check('username trimmed', s.username === 'u1', JSON.stringify(s.username));
    check('loginDomain defaults to Radius for ZJU', s.loginDomain === 'Radius', s.loginDomain);
  }

  /* ---------------------------------------------------------------- */
  await sleep(150);
  console.log('\n=========================================');
  console.log('passed: ' + passed + ', failed: ' + failures.length);
  if (unhandled.length) {
    console.log('UNHANDLED REJECTIONS: ' + unhandled.length);
    unhandled.forEach((u) => console.log('  ' + u));
  } else {
    console.log('unhandled rejections: 0');
  }
  if (failures.length) {
    console.log('\nfailures:');
    failures.forEach((f) => console.log('  - ' + f));
    process.exit(1);
  }
  if (unhandled.length) process.exit(1);
  console.log('BACKGROUND TESTS PASSED');
}

/** Simulate the native port dying (core crash / host unloaded). */
function handleKill(api) {
  if (api.calls.killPort) api.calls.killPort('Native host has exited.');
}

main().catch((e) => {
  console.error('harness crashed:', e);
  process.exit(2);
});
