'use strict';
/**
 * RVPN Browser - options page controller.
 *
 * Full login settings (user type, protocol, credentials, login domain,
 * auth type, TOTP, ports, debug), the `authInfo` probe, `selftest` and the
 * shared event log. All state lives in the service worker; this page is a view.
 */

(function () {
  const POLL_MS = 1000; // popup polls at 500 ms, the full page at 1 s

  const state = {
    mode: 'normal',
    coreAvailable: false,
    coreError: '',
    suspended: '',
    fallback: null,
    hello: null,
    coreState: null,
    settings: null,
    logs: [],
    lastN: 0,
    logDirty: true,
    busy: false,
    probing: false,
    pollTimer: null
  };

  const dom = {};

  function cacheDom() {
    [
      'alerts', 'mode-badge', 'phase-badge', 'status-line', 'btn-mode-normal', 'btn-mode-rvpn',
      'user-type', 'protocol', 'hint-protocol', 'server', 'port', 'field-port', 'username',
      'password', 'save-password', 'field-login-domain', 'login-domain', 'hint-login-domain',
      'field-auth-type', 'auth-type', 'phone', 'field-phone', 'hint-phone', 'btn-probe', 'probe-status',
      'probe-results', 'totp', 'socks-port', 'http-port', 'debug', 'proxy-all', 'core-grid',
      'btn-selftest', 'btn-refresh-core', 'btn-reconnect', 'btn-copy-diag', 'selftest-result',
      'log-view', 'btn-clear-log', 'btn-save', 'btn-save-connect', 'btn-reset', 'save-result'
    ].forEach((id) => {
      dom[id] = document.getElementById(id);
    });
  }

  /* ------------------------------------------------------------------ *
   * Small helpers
   * ------------------------------------------------------------------ */

  function setHint(node, key, substitutions, kind) {
    if (!node) return;
    node.textContent = key ? RVPN.t(key, substitutions) : '';
    node.className = 'hint' + (kind ? ' ' + kind : '');
  }

  function setResult(node, text, kind) {
    if (!node) return;
    node.textContent = text || '';
    node.className = 'hint' + (kind ? ' ' + kind : '');
  }

  function show(node, visible) {
    if (node) node.classList.toggle('hidden', !visible);
  }

  /* ------------------------------------------------------------------ *
   * Alerts / badges
   * ------------------------------------------------------------------ */

  function alertBox(kind, titleText, bodyText, actions) {
    const box = RVPN.el('div', 'alert alert-' + kind);
    box.appendChild(RVPN.el('strong', null, titleText));
    if (bodyText) box.appendChild(RVPN.el('div', null, bodyText));
    if (actions && actions.length) {
      const row = RVPN.el('div', 'alert-actions');
      actions.forEach((a) => {
        const btn = RVPN.el('button', null, a.label);
        btn.type = 'button';
        btn.addEventListener('click', a.onClick);
        row.appendChild(btn);
      });
      box.appendChild(row);
    }
    return box;
  }

  function renderAlerts() {
    const host = dom['alerts'];
    host.textContent = '';
    if (state.fallback && state.fallback.reason) {
      host.appendChild(alertBox('error', RVPN.t('alert_fallback_title'),
        RVPN.t('alert_fallback_body', [RVPN.formatDateTime(state.fallback.ts), state.fallback.reason]),
        [{ label: RVPN.t('btn_retry'), onClick: () => setMode('rvpn') }]));
    }
    if (!state.coreAvailable) {
      host.appendChild(alertBox('error', RVPN.t('alert_core_missing_title'),
        RVPN.t('alert_core_missing_body', [RVPN.HOST_NAME, state.coreError || RVPN.t('err_unknown')]),
        [
          { label: RVPN.t('btn_retry'), onClick: () => doReconnect() },
          { label: RVPN.t('btn_copy_diag'), onClick: () => copyDiagnostics() }
        ]));
    } else if (state.suspended) {
      host.appendChild(alertBox('warn', RVPN.t('alert_suspended_title'), state.suspended, [
        { label: RVPN.t('btn_reconnect'), onClick: () => doReconnect() }
      ]));
    }
  }

  function renderHeader() {
    const rvpn = state.mode === 'rvpn';
    dom['mode-badge'].textContent = RVPN.t(rvpn ? 'mode_rvpn' : 'mode_normal');
    dom['mode-badge'].className = 'badge ' + (rvpn ? 'badge-rvpn' : 'badge-normal');
    const phase = (state.coreState && state.coreState.phase) || 'idle';
    dom['phase-badge'].textContent = RVPN.phaseText(phase);
    dom['btn-mode-normal'].classList.toggle('active', !rvpn);
    dom['btn-mode-rvpn'].classList.toggle('active', rvpn);
    dom['btn-mode-rvpn'].classList.toggle('mode-rvpn-active', rvpn);
    dom['btn-mode-normal'].disabled = state.busy;
    dom['btn-mode-rvpn'].disabled = state.busy;
  }

  function renderStatusLine() {
    const st = state.coreState || {};
    const parts = [];
    parts.push(RVPN.t('label_phase') + ': ' + RVPN.phaseText(st.phase || 'idle'));
    if (st.server) parts.push(RVPN.t('label_server') + ': ' + st.server);
    if (st.socksAddr) parts.push(RVPN.t('label_socks') + ': ' + st.socksAddr);
    if (state.mode === 'rvpn') {
      parts.push(RVPN.t('label_proxy') + ': ' +
        (st.socksAddr && !state.suspended ? RVPN.t('proxy_applied', [st.socksAddr]) : RVPN.t('proxy_not_applied')));
    }
    if (st.message) parts.push(st.message);
    dom['status-line'].textContent = parts.join(' · ');
  }

  function renderCoreGrid() {
    const hello = state.hello || {};
    const st = state.coreState || {};
    const grid = dom['core-grid'];
    grid.textContent = '';
    const rows = [
      ['label_native_host', RVPN.HOST_NAME + ' [' + (state.coreAvailable ? RVPN.t('value_connected') : RVPN.t('value_disconnected')) + ']', true],
      ['label_core_error', state.coreError || '-', false],
      ['label_core_version', hello.coreVersion || '-', false],
      ['label_zjuconnect_version', hello.zjuConnectVersion || '-', false],
      ['label_platform', hello.platform ? (hello.platform + '/' + (hello.arch || '-')) : '-', false],
      ['label_capabilities', (hello.capabilities || []).join(', ') || '-', false],
      ['label_phase', RVPN.phaseText(st.phase || 'idle'), false],
      ['label_server', st.server || '-', true],
      ['label_socks', st.socksAddr || '-', true],
      ['label_http', st.httpAddr || '-', true],
      ['label_client_ip', st.clientIP || '-', true],
      ['label_proxy', state.mode === 'rvpn'
        ? (st.socksAddr && !state.suspended ? RVPN.t('proxy_applied', [st.socksAddr]) : RVPN.t('proxy_not_applied'))
        : RVPN.t('proxy_none'), false]
    ];
    rows.forEach((r) => {
      grid.appendChild(RVPN.el('dt', null, RVPN.t(r[0])));
      grid.appendChild(RVPN.el('dd', r[2] ? 'mono' : null, r[1]));
    });
  }

  function renderLog() {
    if (!state.logDirty) return;
    RVPN.renderLog(dom['log-view'], state.logs, 'log_empty');
    state.logDirty = false;
  }

  function render() {
    renderAlerts();
    renderHeader();
    renderStatusLine();
    renderCoreGrid();
    renderLog();
  }

  /* ------------------------------------------------------------------ *
   * Form
   * ------------------------------------------------------------------ */

  function fillForm(settings) {
    if (!settings) return;
    const editing = document.activeElement && document.activeElement.tagName === 'INPUT' &&
      document.activeElement.closest('.card') === dom['username'].closest('.card');
    if (editing) return; // never clobber a field being edited
    dom['user-type'].value = settings.userType || 'zju';
    dom['protocol'].value = settings.protocol || 'atrust';
    dom['server'].value = settings.server || '';
    dom['port'].value = settings.port || 443;
    dom['username'].value = settings.username || '';
    dom['password'].value = settings.password || '';
    dom['save-password'].checked = settings.savePassword !== false;
    dom['login-domain'].value = settings.loginDomain || 'Radius';
    dom['auth-type'].value = RVPN.AUTH_TYPES.indexOf(settings.authType) === -1 ? '' : (settings.authType || '');
    dom['phone'].value = settings.phone || '';
    dom['totp'].value = settings.totpSecret || '';
    dom['socks-port'].value = settings.socksPort || '';
    dom['http-port'].value = settings.httpPort || '';
    dom['debug'].checked = !!settings.debug;
    dom['proxy-all'].checked = !!settings.proxyAll;
    syncVisibility();
  }

  function syncVisibility() {
    const isOther = dom['user-type'].value === 'other';
    show(dom['field-port'], isOther);
    show(dom['field-login-domain'], isOther);
    show(dom['field-auth-type'], isOther);
    dom['login-domain'].disabled = !isOther;
    dom['auth-type'].disabled = !isOther;
    if (!isOther) dom['login-domain'].value = 'Radius';

    const protocol = dom['protocol'].value;
    const server = RVPN.resolveServer(RVPN.normalizeSettings({
      userType: dom['user-type'].value,
      protocol,
      server: dom['server'].value
    }));
    dom['server'].placeholder = server || RVPN.t('placeholder_server');

    if (isOther) {
      setHint(dom['hint-protocol'], protocol === 'easyconnect' ? 'hint_protocol_easyconnect' : 'hint_protocol_atrust');
      setHint(dom['hint-login-domain'], 'hint_login_domain_other');
      setHint(dom['hint-phone'], 'hint_phone');
    } else {
      setHint(dom['hint-protocol'], protocol === 'easyconnect' ? 'hint_protocol_easyconnect_zju' : 'hint_protocol_atrust_zju');
      setHint(dom['hint-login-domain'], 'hint_login_domain_zju');
      setHint(dom['hint-phone'], 'hint_phone_zju');
    }
  }

  function collectForm() {
    const previous = state.settings || {};
    let password = dom['password'].value;
    if (!password && previous.password && dom['save-password'].checked) {
      password = previous.password; // an empty field keeps the stored password
    }
    return RVPN.normalizeSettings({
      userType: dom['user-type'].value,
      protocol: dom['protocol'].value,
      server: dom['server'].value,
      port: dom['port'].value,
      username: dom['username'].value,
      password,
      savePassword: dom['save-password'].checked,
      loginDomain: dom['login-domain'].value || 'Radius',
      authType: dom['auth-type'].value,
      phone: dom['phone'].value,
      totpSecret: dom['totp'].value,
      socksPort: dom['socks-port'].value,
      httpPort: dom['http-port'].value,
      debug: dom['debug'].checked,
      proxyAll: dom['proxy-all'].checked
    });
  }

  function validate(settings) {
    if (!settings.username) return RVPN.t('err_username_required');
    if (!RVPN.resolveServer(settings)) return RVPN.t('err_server_required');
    if (settings.userType === 'other' && !RVPN.isValidPort(settings.port)) return RVPN.t('err_bad_port');
    if (!RVPN.isValidPort(settings.socksPort)) return RVPN.t('err_bad_port');
    if (!RVPN.isValidPort(settings.httpPort)) return RVPN.t('err_bad_port');
    if (settings.authType === 'auth/smsCheckCode' && !settings.phone) return RVPN.t('err_phone_required');
    return '';
  }

  /* ------------------------------------------------------------------ *
   * Actions
   * ------------------------------------------------------------------ */

  function setMode(mode) {
    state.busy = true;
    renderHeader();
    setResult(dom['save-result'], RVPN.t(mode === 'rvpn' ? 'connecting_rvpn' : 'switching_normal'));
    chrome.runtime.sendMessage({ type: 'setMode', mode }, (res) => {
      const err = RVPN.lastError();
      state.busy = false;
      if (err || !res || !res.ok) {
        setResult(dom['save-result'], RVPN.t('mode_switch_failed', [err || (res && res.error) || RVPN.t('err_unknown')]), 'error');
      } else {
        state.mode = res.mode || mode;
        setResult(dom['save-result'], RVPN.t('saved_ok'), 'ok');
      }
      refresh();
    });
  }

  function save(connect) {
    const settings = collectForm();
    const invalid = validate(settings);
    if (invalid) {
      setResult(dom['save-result'], invalid, 'error');
      return;
    }
    setResult(dom['save-result'], RVPN.t('saving'));
    chrome.runtime.sendMessage({ type: 'saveSettings', settings }, (res) => {
      const err = RVPN.lastError();
      if (err || !res || !res.ok) {
        setResult(dom['save-result'], RVPN.t('save_failed', [err || (res && res.error) || RVPN.t('err_unknown')]), 'error');
        return;
      }
      state.settings = res.settings || settings;
      setResult(dom['save-result'], RVPN.t('saved_ok'), 'ok');
      if (connect) setMode('rvpn');
      refresh();
    });
  }

  function resetForm() {
    fillForm(RVPN.DEFAULT_SETTINGS);
    setResult(dom['save-result'], RVPN.t('reset_done'));
  }

  function probeAuth() {
    if (state.probing) return;
    const settings = collectForm();
    const server = RVPN.resolveServer(settings);
    if (!server) {
      setResult(dom['probe-status'], RVPN.t('err_server_required'), 'error');
      return;
    }
    state.probing = true;
    dom['btn-probe'].disabled = true;
    setResult(dom['probe-status'], RVPN.t('probe_running', [server + ':' + settings.port]));
    chrome.runtime.sendMessage({ type: 'authInfo', server, port: settings.port }, (res) => {
      const err = RVPN.lastError();
      state.probing = false;
      dom['btn-probe'].disabled = false;
      if (err || !res || !res.ok) {
        setResult(dom['probe-status'], RVPN.t('probe_failed', [err || (res && res.error) || RVPN.t('err_unknown')]), 'error');
        show(dom['probe-results'], false);
        return;
      }
      const list = (res.data && res.data.list) || [];
      renderProbeResults(list);
      if (!list.length) {
        setResult(dom['probe-status'], RVPN.t('probe_empty'), 'warn');
        return;
      }
      setResult(dom['probe-status'], RVPN.t('probe_result_hint'), 'ok');
    });
  }

  function renderProbeResults(list) {
    const host = dom['probe-results'];
    host.textContent = '';
    if (!list.length) {
      show(host, false);
      return;
    }
    list.forEach((item) => {
      const btn = RVPN.el('button', 'probe-item');
      btn.type = 'button';
      const name = item.authName || item.authType || '-';
      btn.appendChild(RVPN.el('div', null, name + ' · ' + (item.loginDomain || '-')));
      btn.appendChild(RVPN.el('small', null, (item.authType || '-') + (item.loginUrl ? (' · ' + item.loginUrl) : '')));
      btn.addEventListener('click', () => {
        dom['login-domain'].value = item.loginDomain || 'Radius';
        dom['auth-type'].value = RVPN.AUTH_TYPES.indexOf(item.authType) === -1 ? '' : (item.authType || '');
        setResult(dom['probe-status'], RVPN.t('probe_applied', [item.loginDomain || '-', item.authType || '-']), 'ok');
      });
      host.appendChild(btn);
    });
    show(host, true);
  }

  function doSelftest() {
    const settings = collectForm();
    const server = RVPN.resolveServer(settings);
    if (!server) {
      setResult(dom['selftest-result'], RVPN.t('err_server_required'), 'error');
      return;
    }
    setResult(dom['selftest-result'], RVPN.t('selftest_running', [server + ':' + settings.port]));
    chrome.runtime.sendMessage({ type: 'selftest', server, port: settings.port }, (res) => {
      const err = RVPN.lastError();
      if (err || !res || !res.ok) {
        setResult(dom['selftest-result'], RVPN.t('selftest_failed', [err || (res && res.error) || RVPN.t('err_unknown')]), 'error');
        return;
      }
      const d = res.data || {};
      if (d.reachable) {
        setResult(dom['selftest-result'], RVPN.t('selftest_ok', [d.server || server, String(d.latencyMs)]), 'ok');
      } else {
        setResult(dom['selftest-result'], RVPN.t('selftest_unreachable', [d.server || server, d.error || RVPN.t('err_unknown')]), 'error');
      }
    });
  }

  function doReconnect() {
    setResult(dom['selftest-result'], RVPN.t('reconnecting'));
    chrome.runtime.sendMessage({ type: 'reconnect' }, (res) => {
      const err = RVPN.lastError();
      const ok = !err && res && res.ok;
      setResult(dom['selftest-result'],
        ok ? RVPN.t('reconnect_ok') : RVPN.t('reconnect_failed', [err || (res && res.error) || RVPN.t('err_unknown')]),
        ok ? 'ok' : 'error');
      refresh();
    });
  }

  function refreshCoreInfo() {
    chrome.runtime.sendMessage({ type: 'hello' }, (res) => {
      const err = RVPN.lastError();
      if (err || !res || !res.ok) {
        setResult(dom['selftest-result'], RVPN.t('core_unavailable', [err || (res && res.error) || RVPN.t('err_unknown')]), 'error');
      } else {
        state.hello = res.data || null;
        setResult(dom['selftest-result'], RVPN.t('core_ok'), 'ok');
      }
      refresh();
    });
  }

  function copyDiagnostics() {
    chrome.runtime.sendMessage({ type: 'diagnostics' }, (res) => {
      const err = RVPN.lastError();
      if (err || !res || !res.ok) {
        setResult(dom['save-result'], RVPN.t('diag_copy_failed'), 'error');
        return;
      }
      RVPN.copyText(res.text).then((ok) => {
        setResult(dom['save-result'], RVPN.t(ok ? 'diag_copied' : 'diag_copy_failed'), ok ? 'ok' : 'error');
      });
    });
  }

  /* ------------------------------------------------------------------ *
   * Data flow
   * ------------------------------------------------------------------ */

  function applySnapshot(snap) {
    if (!snap || !snap.ok) return;
    state.mode = snap.mode || 'normal';
    state.coreAvailable = !!snap.coreAvailable;
    state.coreError = snap.coreError || '';
    state.suspended = snap.suspended || '';
    state.fallback = snap.fallback || null;
    state.hello = snap.hello || null;
    state.coreState = snap.state || null;
    if (snap.settings) {
      state.settings = snap.settings;
      fillForm(snap.settings);
    }
    if (Array.isArray(snap.logs) && snap.logs.length) {
      snap.logs.forEach((e) => {
        if (e && Number.isFinite(e.n) && e.n > state.lastN) state.logs.push(e);
      });
      RVPN.trimLog(state.logs);
      state.logDirty = true;
    }
    if (Number.isFinite(snap.lastN)) state.lastN = snap.lastN;
    render();
  }

  function refresh() {
    chrome.runtime.sendMessage({ type: 'requestStatus', logSince: state.lastN }, (res) => {
      const err = RVPN.lastError();
      if (err) {
        state.coreAvailable = false;
        state.coreError = err;
        render();
        return;
      }
      applySnapshot(res);
    });
  }

  function bootstrap() {
    RVPN.applyI18n();
    cacheDom();
    bindEvents();
    RVPN.renderLog(dom['log-view'], [], 'log_empty');
    chrome.runtime.sendMessage({ type: 'getState', logSince: 0 }, (res) => {
      const err = RVPN.lastError();
      if (err) {
        state.coreAvailable = false;
        state.coreError = err;
        render();
        return;
      }
      applySnapshot(res);
    });
    state.pollTimer = setInterval(refresh, POLL_MS);
    window.addEventListener('unload', () => {
      clearInterval(state.pollTimer);
    });
  }

  function bindEvents() {
    dom['user-type'].addEventListener('change', () => {
      if (dom['user-type'].value === 'zju') {
        dom['login-domain'].value = 'Radius';
      }
      syncVisibility();
    });
    dom['protocol'].addEventListener('change', syncVisibility);
    dom['auth-type'].addEventListener('change', () => {
      if (dom['auth-type'].value === 'auth/smsCheckCode') {
        dom['phone'].focus();
      }
    });
    dom['btn-mode-normal'].addEventListener('click', () => setMode('normal'));
    dom['btn-mode-rvpn'].addEventListener('click', () => setMode('rvpn'));
    dom['btn-save'].addEventListener('click', () => save(false));
    dom['btn-save-connect'].addEventListener('click', () => save(true));
    dom['btn-reset'].addEventListener('click', resetForm);
    dom['btn-probe'].addEventListener('click', probeAuth);
    dom['btn-selftest'].addEventListener('click', doSelftest);
    dom['btn-refresh-core'].addEventListener('click', refreshCoreInfo);
    dom['btn-reconnect'].addEventListener('click', doReconnect);
    dom['btn-copy-diag'].addEventListener('click', copyDiagnostics);
    dom['btn-clear-log'].addEventListener('click', () => {
      state.logs = [];
      state.lastN = 0;
      state.logDirty = true;
      chrome.runtime.sendMessage({ type: 'clearLog' }, () => {
        RVPN.lastError();
        render();
      });
    });
    chrome.runtime.onMessage.addListener((msg) => {
      if (msg && msg.type === 'update') refresh();
      return false;
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootstrap);
  } else {
    bootstrap();
  }
}());
