'use strict';
/**
 * RVPN Browser - popup controller.
 *
 * The popup is a pure view over the service worker: it polls `requestStatus`
 * every 500 ms, appends the log delta and renders the challenge queue. Every
 * request is answered by the worker even when rvpn-core is absent, so the popup
 * always renders (with a diagnostic instead of a status).
 */

(function () {
  const POLL_MS = 500;

  const state = {
    mode: 'normal',
    coreAvailable: false,
    coreError: '',
    suspended: '',
    fallback: null,
    challenges: [],
    settings: null,
    logs: [],
    lastN: 0,
    hello: null,
    coreState: null,
    pollTimer: null,
    logDirty: true,
    busy: false
  };

  /** Click-code points, keyed by challenge id, in ORIGINAL image pixels. */
  const clickPoints = new Map();
  const imageCache = new Map();

  const dom = {};

  function cacheDom() {
    [
      'alerts', 'mode-badge', 'phase-badge', 'mode-hint', 'btn-mode-normal', 'btn-mode-rvpn',
      'challenge-area', 'challenge-list', 'status-grid', 'state-message', 'selftest-result',
      'btn-selftest', 'btn-reconnect', 'btn-copy-diag', 'user-type', 'protocol', 'server',
      'port', 'field-port', 'username', 'password', 'save-password', 'btn-save-connect',
      'btn-open-options', 'save-result', 'log-view', 'btn-clear-log', 'btn-options'
    ].forEach((id) => {
      dom[id] = document.getElementById(id);
    });
  }

  /* ------------------------------------------------------------------ *
   * Rendering
   * ------------------------------------------------------------------ */

  function setText(node, text) {
    if (node && node.textContent !== text) node.textContent = text;
  }

  function show(node, visible) {
    if (!node) return;
    node.classList.toggle('hidden', !visible);
  }

  function render() {
    renderAlerts();
    renderMode();
    renderStatus();
    renderChallenges();
    if (state.logDirty) {
      RVPN.renderLog(dom['log-view'], state.logs, 'log_empty');
      state.logDirty = false;
    }
  }

  function renderMode() {
    const rvpn = state.mode === 'rvpn';
    dom['mode-badge'].textContent = RVPN.t(rvpn ? 'mode_rvpn' : 'mode_normal');
    dom['mode-badge'].className = 'badge ' + (rvpn ? 'badge-rvpn' : 'badge-normal');
    dom['btn-mode-normal'].classList.toggle('active', !rvpn);
    dom['btn-mode-rvpn'].classList.toggle('active', rvpn);
    dom['btn-mode-rvpn'].classList.toggle('mode-rvpn-active', rvpn);
    dom['btn-mode-normal'].disabled = state.busy;
    dom['btn-mode-rvpn'].disabled = state.busy;
    setText(dom['mode-hint'], RVPN.t(rvpn ? 'hint_mode_rvpn' : 'hint_mode_normal'));
  }

  function alertBox(kind, titleText, bodyText, actions) {
    const box = RVPN.el('div', 'alert alert-' + kind);
    const title = RVPN.el('strong', null, titleText);
    box.appendChild(title);
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
    const add = (node) => host.appendChild(node);

    if (state.fallback && state.fallback.reason) {
      add(alertBox('error', RVPN.t('alert_fallback_title'),
        RVPN.t('alert_fallback_body', [RVPN.formatDateTime(state.fallback.ts), state.fallback.reason]),
        [
          { label: RVPN.t('btn_retry'), onClick: () => setMode('rvpn') },
          { label: RVPN.t('btn_open_options'), onClick: () => RVPN.openOptions() }
        ]));
    }

    if (!state.coreAvailable) {
      add(alertBox('error', RVPN.t('alert_core_missing_title'),
        RVPN.t('alert_core_missing_body', [RVPN.HOST_NAME, state.coreError || RVPN.t('err_unknown')]),
        [
          { label: RVPN.t('btn_retry'), onClick: () => doReconnect() },
          { label: RVPN.t('btn_copy_diag'), onClick: () => copyDiagnostics() }
        ]));
    } else if (state.suspended) {
      add(alertBox('warn', RVPN.t('alert_suspended_title'), state.suspended, [
        { label: RVPN.t('btn_reconnect'), onClick: () => doReconnect() }
      ]));
    }
  }

  function statusRow(labelKey, value, mono) {
    const dt = RVPN.el('dt', null, RVPN.t(labelKey));
    const dd = RVPN.el('dd', mono ? 'mono' : null, value || '-');
    return [dt, dd];
  }

  function renderStatus() {
    const st = state.coreState || {};
    const hello = state.hello || {};
    const grid = dom['status-grid'];
    grid.textContent = '';

    const rows = [
      ['label_phase', RVPN.phaseText(st.phase || 'idle'), false],
      ['label_server', st.server || '-', true],
      ['label_socks', st.socksAddr || '-', true],
      ['label_http', st.httpAddr || '-', true],
      ['label_client_ip', st.clientIP || '-', true],
      ['label_connected_at', st.connectedAt ? RVPN.formatDateTime(st.connectedAt) : '-', false],
      ['label_core_version', hello.coreVersion ? (hello.coreVersion + ' / zjuConnect ' + (hello.zjuConnectVersion || '-')) : '-', false],
      ['label_platform', hello.platform ? (hello.platform + '/' + (hello.arch || '-')) : '-', false]
    ];
    if (state.mode === 'rvpn' && (state.suspended || !st.socksAddr)) {
      rows.splice(1, 0, ['label_proxy', RVPN.t('proxy_not_applied'), false]);
    } else if (state.mode === 'rvpn') {
      rows.splice(1, 0, ['label_proxy', RVPN.t('proxy_applied', [st.socksAddr]), true]);
    }
    rows.forEach((r) => {
      statusRow(r[0], r[1], r[2]).forEach((n) => grid.appendChild(n));
    });

    const phase = st.phase || 'idle';
    dom['phase-badge'].textContent = RVPN.phaseText(phase);
    dom['phase-badge'].className = 'badge badge-phase badge-phase-' + phase;

    const msg = st.message || (state.coreAvailable ? '' : RVPN.t('core_not_connected'));
    setText(dom['state-message'], msg);
    dom['state-message'].classList.toggle('error', phase === 'error' || (!state.coreAvailable && !!state.coreError));
  }

  /* ------------------------------------------------------------------ *
   * Challenges
   * ------------------------------------------------------------------ */

  function renderChallenges() {
    const list = dom['challenge-list'];
    const pending = state.challenges || [];
    const isNew = list.childElementCount !== pending.length ||
      Array.prototype.some.call(list.children, (child, i) => pending[i] && child.dataset.id !== pending[i].id);
    if (!isNew) {
      pending.forEach((c, i) => {
        const child = list.children[i];
        if (!child) return;
        const dl = child.querySelector('.challenge-deadline');
        if (dl && c.deadline) updateDeadline(dl, c.deadline);
        const note = child.querySelector('.challenge-submitted');
        if (note) note.classList.toggle('hidden', !c.submitted);
      });
      show(dom['challenge-area'], pending.length > 0);
      return;
    }
    list.textContent = '';
    pending.forEach((c) => list.appendChild(buildChallenge(c)));
    show(dom['challenge-area'], pending.length > 0);
    if (pending.length) {
      dom['challenge-area'].scrollIntoView({ block: 'nearest' });
    }
  }

  function updateDeadline(node, deadline) {
    const left = deadline - Date.now();
    if (left <= 0) {
      node.textContent = RVPN.t('challenge_expired');
      node.classList.add('expired');
      return;
    }
    node.classList.remove('expired');
    node.textContent = RVPN.t('challenge_deadline_in', [formatDuration(left)]);
  }

  function formatDuration(ms) {
    const total = Math.max(0, Math.round(ms / 1000));
    const m = Math.floor(total / 60);
    const s = total % 60;
    return m + ':' + String(s).padStart(2, '0');
  }

  function buildChallenge(c) {
    const box = RVPN.el('div', 'challenge');
    box.dataset.id = c.id;
    box.appendChild(RVPN.el('p', 'challenge-type', RVPN.challengeTypeText(c.type)));
    if (c.message) box.appendChild(RVPN.el('p', 'challenge-message', c.message));

    if (c.type === 'clickCode') {
      box.appendChild(buildClickCode(c));
    } else if (c.type === 'externalLogin') {
      box.appendChild(buildExternalLogin(c));
    } else {
      box.appendChild(buildCodeInput(c));
    }

    if (c.deadline) {
      const dl = RVPN.el('p', 'challenge-deadline');
      updateDeadline(dl, c.deadline);
      box.appendChild(dl);
    }
    const submitted = RVPN.el('p', 'challenge-submitted hidden', RVPN.t('challenge_submitted_wait'));
    box.appendChild(submitted);
    return box;
  }

  function buildCodeInput(c) {
    const wrap = document.createDocumentFragment();
    // PROTOCOL.md 2.6: imageDataUrl is present for graphic captchas; a `text`
    // challenge is unreadable without showing it.
    if (c.imageDataUrl) {
      const imageWrap = RVPN.el('div', 'challenge-image-wrap');
      const img = document.createElement('img');
      img.className = 'challenge-image';
      img.alt = '';
      img.src = c.imageDataUrl;
      imageWrap.appendChild(img);
      wrap.appendChild(imageWrap);
    }
    const row = RVPN.el('div', 'challenge-row');
    const input = document.createElement('input');
    input.type = 'text';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.placeholder = c.type === 'smsCode' ? RVPN.t('placeholder_sms_code') : RVPN.t('placeholder_code');
    row.appendChild(input);

    let skipBox = null;
    if (c.type === 'secondaryAuth' && c.canSkipSecondaryAuth) {
      const label = RVPN.el('label', 'checkline');
      skipBox = document.createElement('input');
      skipBox.type = 'checkbox';
      label.appendChild(skipBox);
      label.appendChild(RVPN.el('span', null, RVPN.t('challenge_skip_secondary')));
      wrap.appendChild(label);
    }

    const submit = RVPN.el('button', 'primary', RVPN.t('challenge_submit'));
    submit.type = 'button';
    submit.addEventListener('click', () => {
      if (skipBox && skipBox.checked) {
        submitAnswer(c.id, { skipSecondaryAuth: true });
        return;
      }
      const code = input.value.trim();
      if (!code) {
        flashRow(row, RVPN.t('challenge_empty_input'));
        return;
      }
      submitAnswer(c.id, { code });
    });
    row.appendChild(submit);
    wrap.appendChild(row);

    input.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter') submit.click();
    });
    return wrap;
  }

  function buildExternalLogin(c) {
    const wrap = document.createDocumentFragment();
    const link = RVPN.el('p', 'challenge-row');
    if (c.loginUrl) {
      const a = document.createElement('a');
      a.href = c.loginUrl;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      a.textContent = RVPN.t('challenge_open_login');
      link.appendChild(a);
    } else {
      link.appendChild(RVPN.el('span', 'hint', RVPN.t('challenge_no_login_url')));
    }
    wrap.appendChild(link);

    const row = RVPN.el('div', 'challenge-row');
    const input = document.createElement('input');
    input.type = 'text';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.placeholder = RVPN.t('placeholder_callback_url');
    row.appendChild(input);
    const submit = RVPN.el('button', 'primary', RVPN.t('challenge_submit'));
    submit.type = 'button';
    submit.addEventListener('click', () => {
      const code = input.value.trim();
      if (!code) {
        flashRow(row, RVPN.t('challenge_empty_input'));
        return;
      }
      submitAnswer(c.id, { code });
    });
    row.appendChild(submit);
    input.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter') submit.click();
    });
    wrap.appendChild(row);
    return wrap;
  }

  function buildClickCode(c) {
    const wrap = document.createDocumentFragment();
    const imageWrap = RVPN.el('div', 'challenge-image-wrap');
    const canvas = document.createElement('canvas');
    imageWrap.appendChild(canvas);
    wrap.appendChild(imageWrap);

    const pointsLine = RVPN.el('p', 'challenge-points', RVPN.t('challenge_points', [0]));
    wrap.appendChild(pointsLine);

    const points = clickPoints.get(c.id) || [];
    clickPoints.set(c.id, points);

    const redraw = () => {
      const img = imageCache.get(c.id);
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (img) ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      const r = Math.max(6, Math.round(Math.min(canvas.width, canvas.height) / 22));
      points.forEach((p, i) => {
        ctx.beginPath();
        ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
        ctx.strokeStyle = '#d93025';
        ctx.lineWidth = Math.max(2, r / 4);
        ctx.stroke();
        ctx.beginPath();
        ctx.arc(p.x, p.y, Math.max(1.5, r / 6), 0, Math.PI * 2);
        ctx.fillStyle = '#d93025';
        ctx.fill();
        ctx.fillStyle = '#d93025';
        ctx.font = 'bold ' + Math.round(r * 1.6) + 'px sans-serif';
        ctx.fillText(String(i + 1), p.x + r, p.y - r);
      });
      pointsLine.textContent = RVPN.t('challenge_points', [points.length]);
    };

    const draw = () => {
      const img = new Image();
      img.onload = () => {
        imageCache.set(c.id, img);
        canvas.width = img.naturalWidth || 300;
        canvas.height = img.naturalHeight || 150;
        redraw();
      };
      img.onerror = () => {
        canvas.width = 300;
        canvas.height = 150;
        pointsLine.textContent = RVPN.t('challenge_image_failed');
      };
      img.src = c.imageDataUrl || '';
    };

    if (c.imageDataUrl) draw();

    // Map clicks back to the ORIGINAL image pixel coordinates (canvas holds the
    // unscaled bitmap; only its CSS size differs, hence the rect ratio).
    canvas.addEventListener('click', (ev) => {
      const rect = canvas.getBoundingClientRect();
      if (!rect.width || !rect.height) return;
      const x = (ev.clientX - rect.left) * (canvas.width / rect.width);
      const y = (ev.clientY - rect.top) * (canvas.height / rect.height);
      points.push({ x: Math.round(x), y: Math.round(y) });
      redraw();
    });

    const row = RVPN.el('div', 'challenge-row');
    const undo = RVPN.el('button', null, RVPN.t('challenge_undo'));
    undo.type = 'button';
    undo.addEventListener('click', () => {
      points.pop();
      redraw();
    });
    const clear = RVPN.el('button', null, RVPN.t('challenge_clear'));
    clear.type = 'button';
    clear.addEventListener('click', () => {
      points.length = 0;
      redraw();
    });
    const submit = RVPN.el('button', 'primary', RVPN.t('challenge_submit'));
    submit.type = 'button';
    submit.addEventListener('click', () => {
      if (!points.length) {
        flashRow(row, RVPN.t('challenge_points_required'));
        return;
      }
      submitAnswer(c.id, {
        points: points.slice(),
        width: canvas.width,
        height: canvas.height
      });
    });
    row.appendChild(undo);
    row.appendChild(clear);
    row.appendChild(submit);
    wrap.appendChild(row);
    return wrap;
  }

  function flashRow(row, message) {
    let node = row.querySelector('.row-error');
    if (!node) {
      node = RVPN.el('span', 'hint error row-error');
      row.appendChild(node);
    }
    node.textContent = message;
    setTimeout(() => {
      if (node.parentNode) node.parentNode.removeChild(node);
    }, 4000);
  }

  function submitAnswer(challengeId, payload) {
    const body = Object.assign({ challengeId }, payload);
    chrome.runtime.sendMessage({ type: 'answer', payload: body }, (res) => {
      const err = RVPN.lastError();
      if (err) {
        showToast(RVPN.t('challenge_answer_failed', [err]), true);
        return;
      }
      if (!res || !res.ok) {
        showToast(RVPN.t('challenge_answer_failed', [(res && res.error) || RVPN.t('err_unknown')]), true);
        return;
      }
      clickPoints.delete(challengeId);
      showToast(RVPN.t('challenge_answer_sent'), false);
      refresh(true);
    });
  }

  function showToast(message, isError) {
    setText(dom['save-result'], message);
    dom['save-result'].classList.remove('hidden');
    dom['save-result'].classList.toggle('error', !!isError);
    dom['save-result'].classList.toggle('ok', !isError);
    setTimeout(() => {
      dom['save-result'].classList.add('hidden');
    }, 5000);
  }

  /* ------------------------------------------------------------------ *
   * Form
   * ------------------------------------------------------------------ */

  function fillForm(settings) {
    if (!settings) return;
    // Do not clobber what the user is currently typing.
    if (document.activeElement && document.activeElement.closest &&
        document.activeElement.closest('section') === dom['username'].closest('section')) {
      return;
    }
    dom['user-type'].value = settings.userType || 'zju';
    dom['protocol'].value = settings.protocol || 'atrust';
    dom['server'].value = settings.server || '';
    dom['port'].value = settings.port || 443;
    dom['username'].value = settings.username || '';
    dom['password'].value = settings.password || '';
    dom['save-password'].checked = settings.savePassword !== false;
    dom['server'].placeholder = RVPN.resolveServer(
      RVPN.normalizeSettings({ userType: settings.userType, protocol: settings.protocol })
    ) || RVPN.t('placeholder_server');
    syncFormVisibility();
  }

  function syncFormVisibility() {
    const isOther = dom['user-type'].value === 'other';
    dom['field-port'].classList.toggle('hidden', !isOther);
  }

  function collectForm() {
    const previous = state.settings || {};
    let password = dom['password'].value;
    if (!password && previous.password && dom['save-password'].checked) {
      password = previous.password; // empty field keeps the stored password
    }
    return RVPN.normalizeSettings({
      userType: dom['user-type'].value,
      protocol: dom['protocol'].value,
      server: dom['server'].value,
      port: dom['port'].value,
      username: dom['username'].value,
      password,
      savePassword: dom['save-password'].checked,
      loginDomain: previous.loginDomain || 'Radius',
      authType: previous.authType || '',
      phone: previous.phone || '',
      totpSecret: previous.totpSecret || '',
      socksPort: previous.socksPort || '',
      httpPort: previous.httpPort || '',
      debug: previous.debug || false,
      proxyAll: previous.proxyAll || false
    });
  }

  function applyAutoServer() {
    const userType = dom['user-type'].value;
    const protocol = dom['protocol'].value;
    if (userType === 'zju') {
      const current = dom['server'].value.trim();
      const known = Object.keys(RVPN.ZJU_SERVERS).map((k) => RVPN.ZJU_SERVERS[k]);
      if (!current || known.indexOf(current) !== -1) {
        dom['server'].value = RVPN.ZJU_SERVERS[protocol] || '';
      }
    }
    syncFormVisibility();
  }

  /* ------------------------------------------------------------------ *
   * Actions
   * ------------------------------------------------------------------ */

  function setMode(mode) {
    state.busy = true;
    renderMode();
    setText(dom['mode-hint'], RVPN.t(mode === 'rvpn' ? 'connecting_rvpn' : 'switching_normal'));
    // `start` may legitimately take a while (120 s timeout in the worker);
    // never leave the buttons disabled forever if the answer never arrives.
    const guard = setTimeout(() => {
      state.busy = false;
      renderMode();
    }, 125000);
    chrome.runtime.sendMessage({ type: 'setMode', mode }, (res) => {
      clearTimeout(guard);
      const err = RVPN.lastError();
      state.busy = false;
      if (err || !res || !res.ok) {
        const reason = err || (res && res.error) || RVPN.t('err_unknown');
        showToast(RVPN.t('mode_switch_failed', [reason]), true);
      } else if (res.mode) {
        state.mode = res.mode;
      }
      refresh(true);
    });
  }

  function doReconnect() {
    setText(dom['selftest-result'], RVPN.t('reconnecting'));
    show(dom['selftest-result'], true);
    dom['selftest-result'].className = 'selftest';
    chrome.runtime.sendMessage({ type: 'reconnect' }, (res) => {
      const err = RVPN.lastError();
      const ok = !err && res && res.ok;
      setText(dom['selftest-result'], ok ? RVPN.t('reconnect_ok') : RVPN.t('reconnect_failed', [err || (res && res.error) || RVPN.t('err_unknown')]));
      dom['selftest-result'].className = 'selftest ' + (ok ? 'ok' : 'error');
      refresh(true);
    });
  }

  function doSelftest() {
    const settings = collectForm();
    const server = RVPN.resolveServer(settings);
    if (!server) {
      setText(dom['selftest-result'], RVPN.t('err_server_required'));
      dom['selftest-result'].className = 'selftest error';
      show(dom['selftest-result'], true);
      return;
    }
    show(dom['selftest-result'], true);
    dom['selftest-result'].className = 'selftest';
    setText(dom['selftest-result'], RVPN.t('selftest_running', [server + ':' + settings.port]));
    chrome.runtime.sendMessage({ type: 'selftest', server, port: settings.port }, (res) => {
      const err = RVPN.lastError();
      if (err || !res || !res.ok) {
        setText(dom['selftest-result'], RVPN.t('selftest_failed', [err || (res && res.error) || RVPN.t('err_unknown')]));
        dom['selftest-result'].className = 'selftest error';
        return;
      }
      const d = res.data || {};
      if (d.reachable) {
        setText(dom['selftest-result'], RVPN.t('selftest_ok', [d.server || server, String(d.latencyMs)]));
        dom['selftest-result'].className = 'selftest ok';
      } else {
        setText(dom['selftest-result'], RVPN.t('selftest_unreachable', [d.server || server, d.error || RVPN.t('err_unknown')]));
        dom['selftest-result'].className = 'selftest error';
      }
    });
  }

  function copyDiagnostics() {
    chrome.runtime.sendMessage({ type: 'diagnostics' }, (res) => {
      const err = RVPN.lastError();
      if (err || !res || !res.ok) {
        showToast(RVPN.t('diag_copy_failed'), true);
        return;
      }
      RVPN.copyText(res.text).then((ok) => {
        showToast(RVPN.t(ok ? 'diag_copied' : 'diag_copy_failed'), !ok);
      });
    });
  }

  function saveAndConnect() {
    const settings = collectForm();
    dom['btn-save-connect'].disabled = true;
    chrome.runtime.sendMessage({ type: 'saveSettings', settings }, (res) => {
      const err = RVPN.lastError();
      if (err || !res || !res.ok) {
        dom['btn-save-connect'].disabled = false;
        showToast(RVPN.t('save_failed', [err || (res && res.error) || RVPN.t('err_unknown')]), true);
        return;
      }
      showToast(RVPN.t('saved_ok'), false);
      setMode('rvpn');
      dom['btn-save-connect'].disabled = false;
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
    state.challenges = snap.challenges || [];
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
        // Worker asleep / reloading: surface it instead of silently freezing.
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
    if (location.hash === '#challenge') document.body.classList.add('detached');
    bindEvents();
    RVPN.renderLog(dom['log-view'], [], 'log_empty');

    chrome.runtime.sendMessage({ type: 'popupOpened', logSince: 0 }, (res) => {
      const err = RVPN.lastError();
      if (err) {
        state.coreError = err;
        state.coreAvailable = false;
        render();
        return;
      }
      applySnapshot(res);
    });

    state.pollTimer = setInterval(refresh, POLL_MS);
    window.addEventListener('unload', () => {
      clearInterval(state.pollTimer);
      try {
        chrome.runtime.sendMessage({ type: 'popupClosed' }, () => {
          RVPN.lastError();
        });
      } catch (e) {
        /* popup is going away */
      }
    });
  }

  function bindEvents() {
    dom['btn-mode-normal'].addEventListener('click', () => setMode('normal'));
    dom['btn-mode-rvpn'].addEventListener('click', () => setMode('rvpn'));
    dom['btn-options'].addEventListener('click', () => RVPN.openOptions());
    dom['btn-open-options'].addEventListener('click', () => RVPN.openOptions());
    dom['btn-selftest'].addEventListener('click', doSelftest);
    dom['btn-reconnect'].addEventListener('click', doReconnect);
    dom['btn-copy-diag'].addEventListener('click', copyDiagnostics);
    dom['btn-save-connect'].addEventListener('click', saveAndConnect);
    dom['btn-clear-log'].addEventListener('click', () => {
      state.logs = [];
      state.lastN = 0;
      state.logDirty = true;
      chrome.runtime.sendMessage({ type: 'clearLog' }, () => {
        RVPN.lastError();
        render();
      });
    });
    dom['user-type'].addEventListener('change', applyAutoServer);
    dom['protocol'].addEventListener('change', applyAutoServer);
    dom['save-password'].addEventListener('change', () => {
      dom['password'].disabled = false;
      if (!dom['save-password'].checked) {
        showToast(RVPN.t('hint_password_not_saved'), false);
      }
    });

    // Push updates from the worker (challenge / state changes) - the 500 ms
    // poll is the primary source, this just makes UI changes feel instant.
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
