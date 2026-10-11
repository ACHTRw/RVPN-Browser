'use strict';
/**
 * RVPN Browser - MV3 service worker.
 *
 * Responsibilities:
 *   1. native messaging client for `rvpn-core` (com.achtrw.rvpn)
 *   2. proxy configuration (normal mode = no proxy, RVPN mode = core SOCKS5)
 *   3. persistent mode across browser restarts (chrome.storage.local + onStartup)
 *   4. status polling, event log and challenge queue
 *   5. message router for popup.html / options.html
 *
 * Protocol contract: docs/PROTOCOL.md (frozen v1). No field outside that
 * document is invented here.
 */

importScripts('lib/common.js');

/* ------------------------------------------------------------------ *
 * Constants
 * ------------------------------------------------------------------ */

const WATCHDOG_ALARM = 'rvpn-watchdog';
const WATCHDOG_PERIOD_MIN = 0.5; // 30 s on Chrome >= 120, clamped to 1 min before that
const STORAGE_KEYS = [
  'settings', 'mode', 'logs', 'challenges', 'since', 'fallback', 'state', 'logSeq',
  'suspended', 'coreError'
];

/* ------------------------------------------------------------------ *
 * In-memory mirror of chrome.storage.local
 * ------------------------------------------------------------------ */

const mem = {
  ready: false,
  settings: null,
  mode: 'normal',
  logs: [],
  challenges: [],
  since: 0,
  logSeq: 0,
  state: null,
  hello: null,
  fallback: null,
  suspended: '',
  coreError: '',
  popupCount: 0,
  pollTimer: null,
  pollInterval: 0,
  pollInFlight: null,
  lastPollTs: 0,
  proxyKey: '',
  sessionPassword: '', // used when the user disabled "save password"
  logPersistTimer: null
};

/* ------------------------------------------------------------------ *
 * Persistence helpers
 * ------------------------------------------------------------------ */

function init() {
  if (mem.ready) return Promise.resolve();
  return RVPN.storageGet(STORAGE_KEYS).then((items) => {
    mem.settings = RVPN.normalizeSettings(items.settings);
    mem.mode = items.mode === RVPN.MODE_RVPN ? RVPN.MODE_RVPN : RVPN.MODE_NORMAL;
    mem.logs = Array.isArray(items.logs) ? items.logs.slice(-RVPN.MAX_LOG_ENTRIES) : [];
    mem.challenges = Array.isArray(items.challenges) ? items.challenges.filter((c) => c && c.id) : [];
    mem.since = Number.isFinite(items.since) ? items.since : 0;
    mem.fallback = items.fallback && items.fallback.reason ? items.fallback : null;
    mem.suspended = typeof items.suspended === 'string' ? items.suspended : '';
    mem.coreError = typeof items.coreError === 'string' ? items.coreError : '';
    mem.state = items.state && typeof items.state === 'object' ? items.state : null;
    let maxSeq = Number.isFinite(items.logSeq) ? items.logSeq : 0;
    mem.logs.forEach((e) => {
      if (e && Number.isFinite(e.n) && e.n > maxSeq) maxSeq = e.n;
    });
    mem.logSeq = maxSeq;
    mem.ready = true;
    updateBadge();
    return undefined;
  });
}

function save(items) {
  return RVPN.storageSet(items).then((res) => {
    if (!res.ok) console.warn('[rvpn] persist failed:', res.error);
    return res;
  });
}

function persistLogSoon() {
  if (mem.logPersistTimer) return;
  mem.logPersistTimer = setTimeout(() => {
    mem.logPersistTimer = null;
    save({ logs: RVPN.logForStorage(mem.logs), logSeq: mem.logSeq });
  }, 400);
}

function persistChallenges() {
  return save({ challenges: mem.challenges });
}

/** Record why the proxy is currently not applied (survives worker restarts). */
function setSuspended(reason, coreError) {
  const changed = mem.suspended !== (reason || '') ||
    (coreError !== undefined && mem.coreError !== (coreError || ''));
  mem.suspended = reason || '';
  if (coreError !== undefined) mem.coreError = coreError || '';
  if (changed) save({ suspended: mem.suspended, coreError: mem.coreError });
}

/* ------------------------------------------------------------------ *
 * Log
 * ------------------------------------------------------------------ */

function log(level, message, kind) {
  const entry = {
    n: (mem.logSeq += 1),
    ts: Date.now(),
    kind: kind || 'log',
    level: level || 'info',
    message: RVPN.redact(message)
  };
  mem.logs.push(entry);
  RVPN.trimLog(mem.logs);
  persistLogSoon();
  broadcast({ type: 'update', reason: 'log' });
  return entry;
}

function ingestEvents(events) {
  if (!Array.isArray(events) || !events.length) return;
  let challengeChanged = false;
  events.forEach((ev) => {
    if (!ev || typeof ev !== 'object') return;
    const entry = {
      n: (mem.logSeq += 1),
      seq: ev.seq,
      ts: Number.isFinite(ev.ts) ? ev.ts : Date.now(),
      kind: ev.kind || 'log',
      level: ev.level || 'info',
      message: RVPN.redact(ev.message || '')
    };
    mem.logs.push(entry);
    if (ev.kind === 'challenge' && ev.challenge && ev.challenge.id) {
      addChallenge(ev.challenge);
      challengeChanged = true;
    } else if (ev.kind === 'challengeDone') {
      handleChallengeDone(ev);
      challengeChanged = true;
    }
  });
  RVPN.trimLog(mem.logs);
  persistLogSoon();
  if (challengeChanged) persistChallenges();
  broadcast({ type: 'update', reason: 'events' });
}

/* ------------------------------------------------------------------ *
 * Native messaging client
 * ------------------------------------------------------------------ */

const native = {
  port: null,
  nextId: 1,
  pending: new Map(),
  backoff: RVPN.BACKOFF_MIN_MS,
  reconnectTimer: null,
  connecting: null
};

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function nativeFailure(error, code) {
  return { ok: false, error: error || RVPN.t('err_unknown'), code: code || 'INTERNAL', data: null };
}

/** Tear down the port and fail every in-flight request (never rejects). */
function teardownPort(reason, code) {
  const port = native.port;
  native.port = null;
  if (port) {
    try {
      port.onMessage.removeListener(onNativeMessage);
      port.onDisconnect.removeListener(onNativeDisconnect);
      port.disconnect();
    } catch (e) {
      /* port already gone */
    }
  }
  native.pending.forEach((p) => {
    clearTimeout(p.timer);
    p.resolve(nativeFailure(reason, code || 'DISCONNECTED'));
  });
  native.pending.clear();
}

function onNativeMessage(msg) {
  if (!msg || typeof msg !== 'object') return;
  const p = native.pending.get(msg.id);
  if (!p) return; // late/unknown response, ignore
  native.pending.delete(msg.id);
  clearTimeout(p.timer);
  if (msg.ok) {
    native.backoff = RVPN.BACKOFF_MIN_MS; // healthy traffic resets backoff
    p.resolve({ ok: true, data: msg.data === undefined ? {} : msg.data, code: undefined, error: undefined });
  } else {
    p.resolve(nativeFailure(msg.error || RVPN.t('err_core_error'), msg.code || 'INTERNAL'));
  }
}

function onNativeDisconnect() {
  const err = RVPN.lastError(); // must be read synchronously inside the handler
  const reason = err || RVPN.t('err_disconnected');
  native.port = null;
  native.pending.forEach((p) => {
    clearTimeout(p.timer);
    p.resolve(nativeFailure(reason, 'DISCONNECTED'));
  });
  native.pending.clear();
  mem.coreError = reason;
  mem.hello = null;
  log('warn', RVPN.t('log_core_disconnected', [reason]));
  // Never leave a dangling proxy pointing at a dead port.
  if (mem.mode === RVPN.MODE_RVPN) {
    dropProxy(RVPN.t('suspend_core_down', [reason])).then(() => {
      broadcast({ type: 'update', reason: 'disconnected' });
    });
  } else {
    setSuspended('');
    broadcast({ type: 'update', reason: 'disconnected' });
  }
  scheduleReconnect();
}

function scheduleReconnect() {
  if (native.reconnectTimer) return;
  const delay = native.backoff;
  native.backoff = Math.min(native.backoff * 2, RVPN.BACKOFF_MAX_MS);
  native.reconnectTimer = setTimeout(() => {
    native.reconnectTimer = null;
    ensureConnected()
      .then((res) => {
        if (res.ok) {
          log('info', RVPN.t('log_core_reconnected'));
          if (mem.mode === RVPN.MODE_RVPN) return ensureRunning();
          return undefined;
        }
        scheduleReconnect();
        return undefined;
      })
      .catch((e) => {
        console.warn('[rvpn] reconnect failed:', e);
        scheduleReconnect();
      });
  }, delay);
}

/** Connect (idempotent) and run the `hello` handshake. */
function ensureConnected() {
  if (native.port) return Promise.resolve({ ok: true, data: mem.hello });
  if (native.connecting) return native.connecting;
  const attempt = new Promise((resolve) => {
    let port;
    try {
      port = chrome.runtime.connectNative(RVPN.HOST_NAME);
    } catch (e) {
      mem.coreError = String(e && e.message ? e.message : e);
      resolve(nativeFailure(mem.coreError, 'NO_HOST'));
      return;
    }
    if (!port) {
      mem.coreError = RVPN.t('err_host_not_found');
      resolve(nativeFailure(mem.coreError, 'NO_HOST'));
      return;
    }
    native.port = port;
    port.onMessage.addListener(onNativeMessage);
    port.onDisconnect.addListener(onNativeDisconnect);
    // Handshake: proves the host really is alive (a missing host disconnects
    // immediately, with chrome.runtime.lastError set in onDisconnect).
    request('hello', {}, { timeout: 10000 }).then((res) => {
      if (res.ok) {
        mem.hello = res.data || {};
        mem.coreError = '';
        native.backoff = RVPN.BACKOFF_MIN_MS;
      } else {
        mem.coreError = res.error || RVPN.t('err_unknown');
      }
      resolve(res.ok ? { ok: true, data: mem.hello } : nativeFailure(mem.coreError, res.code));
    });
  }).then((res) => {
    native.connecting = null;
    broadcast({ type: 'update', reason: 'connection' });
    return res;
  });
  native.connecting = attempt;
  return attempt;
}

/**
 * Send one command. Resolves (never rejects) with
 *   { ok: true, data } | { ok: false, error, code }.
 */
function request(cmd, params, opts) {
  const options = opts || {};
  const timeout = options.timeout || (cmd === 'start' ? RVPN.START_TIMEOUT_MS : RVPN.TIMEOUT_MS);
  return new Promise((resolve) => {
    if (!native.port) {
      resolve(nativeFailure(mem.coreError || RVPN.t('err_host_not_found'), 'NO_HOST'));
      return;
    }
    const id = native.nextId++;
    const timer = setTimeout(() => {
      native.pending.delete(id);
      resolve(nativeFailure(RVPN.t('err_timeout', [cmd]), 'TIMEOUT'));
    }, timeout);
    native.pending.set(id, { resolve, timer, cmd });
    const payload = Object.assign({ id, cmd }, params || {});
    try {
      native.port.postMessage(payload);
    } catch (e) {
      native.pending.delete(id);
      clearTimeout(timer);
      resolve(nativeFailure(String(e && e.message ? e.message : e), 'SEND_FAILED'));
    }
  });
}

/* ------------------------------------------------------------------ *
 * Proxy control (PROTOCOL.md section 4.3: the front-end owns the proxy)
 * ------------------------------------------------------------------ */

function applyProxy(addr) {
  const parts = RVPN.splitAddr(addr);
  if (!parts) return Promise.resolve({ ok: false, error: RVPN.t('err_bad_socks_addr', [String(addr)]) });
  const value = {
    mode: 'fixed_servers',
    rules: {
      singleProxy: { scheme: 'socks5', host: parts.host, port: parts.port },
      bypassList: RVPN.BYPASS_LIST.slice()
    }
  };
  return new Promise((resolve) => {
    try {
      chrome.proxy.settings.set({ value, scope: 'regular' }, () => {
        const err = RVPN.lastError();
        if (err) {
          resolve({ ok: false, error: err });
          return;
        }
        resolve({ ok: true });
      });
    } catch (e) {
      resolve({ ok: false, error: String(e) });
    }
  });
}

function clearProxy() {
  return new Promise((resolve) => {
    try {
      chrome.proxy.settings.clear({ scope: 'regular' }, () => {
        const err = RVPN.lastError();
        resolve({ ok: !err, error: err || undefined });
      });
    } catch (e) {
      resolve({ ok: false, error: String(e) });
    }
  });
}

/** Remove any proxy override we own. */
function dropProxy(reason) {
  return clearProxy().then((res) => {
    const wasApplied = !!mem.proxyKey;
    mem.proxyKey = '';
    if (reason !== undefined) setSuspended(reason);
    if (wasApplied || reason === undefined) broadcast({ type: 'update', reason: 'proxy' });
    return res;
  });
}

/**
 * Make the browser proxy match the current mode and core state.
 * RVPN mode + live socksAddr  -> point the browser at that SOCKS5 port.
 * anything else              -> guarantee no proxy override is left behind.
 */
function syncProxy() {
  return init().then(() => {
    const state = mem.state || {};
    if (mem.mode !== RVPN.MODE_RVPN) {
      if (!mem.proxyKey) {
        setSuspended('');
        return { ok: true, applied: false };
      }
      return dropProxy('').then((res) => ({ ok: res.ok, applied: false, error: res.error }));
    }
    const addr = state.socksAddr || '';
    if (!addr) {
      if (mem.proxyKey) return dropProxy(RVPN.t('suspend_no_socks')).then(() => ({ ok: true, applied: false }));
      setSuspended(mem.suspended || RVPN.t('suspend_no_socks'));
      return Promise.resolve({ ok: true, applied: false });
    }
    if (mem.proxyKey === addr) return Promise.resolve({ ok: true, applied: true });
    return applyProxy(addr).then((res) => {
      if (res.ok) {
        mem.proxyKey = addr;
        setSuspended('');
        log('info', RVPN.t('log_proxy_applied', [addr]));
      } else {
        setSuspended(RVPN.t('suspend_proxy_failed', [res.error]));
        log('error', RVPN.t('log_proxy_failed', [res.error]));
      }
      broadcast({ type: 'update', reason: 'proxy' });
      return { ok: res.ok, applied: res.ok, error: res.error };
    });
  });
}

/* ------------------------------------------------------------------ *
 * Core lifecycle
 * ------------------------------------------------------------------ */

function setState(state) {
  if (!state || typeof state !== 'object') return;
  mem.state = state;
  save({ state });
  if (state.phase === 'error') {
    setSuspended(state.lastError || mem.suspended, mem.coreError);
  }
  updateBadge();
  broadcast({ type: 'update', reason: 'state' });
}

function validateConfig(settings) {
  const s = effectiveSettings(settings);
  if (!s.username) return RVPN.t('err_username_required');
  if (!RVPN.resolveServer(s)) return RVPN.t('err_server_required');
  if (!RVPN.isValidPort(s.socksPort) || !RVPN.isValidPort(s.httpPort)) return RVPN.t('err_bad_port');
  if (s.userType === 'other' && !RVPN.isValidPort(s.port)) return RVPN.t('err_bad_port');
  if (s.authType === 'auth/smsCheckCode' && !s.phone) return RVPN.t('err_phone_required');
  return '';
}

/**
 * Settings as used for the current process: when "save password" is off the
 * password is only kept in worker memory, never written to storage.
 */
function effectiveSettings(settings) {
  const s = settings || mem.settings;
  if (s.password) return s;
  if (!mem.sessionPassword) return s;
  return Object.assign({}, s, { password: mem.sessionPassword });
}

function startCore() {
  return init().then(() => {
    const invalid = validateConfig(mem.settings);
    if (invalid) return nativeFailure(invalid, 'INVALID_CONFIG');
    return ensureConnected().then((conn) => {
      if (!conn.ok) return nativeFailure(conn.error, conn.code);
      const config = RVPN.buildConfig(effectiveSettings(mem.settings));
      log('info', RVPN.t('log_starting', [config.server + ':' + config.port]));
      return request('start', { config }, { timeout: RVPN.START_TIMEOUT_MS }).then((res) => {
        if (!res.ok) {
          if (res.code === 'ALREADY_RUNNING') {
            // Someone (or a previous worker) already started it - adopt it.
            return doStatus().then(() => syncProxy()).then(() => ({ ok: true, adopted: true }));
          }
          setSuspended(res.error);
          log('error', RVPN.t('log_start_failed', [res.error]));
          broadcast({ type: 'update', reason: 'start' });
          return res;
        }
        if (res.data && res.data.state) setState(res.data.state);
        return syncProxy().then(() => res);
      });
    });
  });
}

function stopCore() {
  if (!native.port) return Promise.resolve({ ok: true });
  return request('stop', {}, { timeout: 30000 }).then((res) => {
    if (res.ok) {
      if (res.data && res.data.state) setState(res.data.state);
      log('info', RVPN.t('log_stopped'));
    } else if (res.code !== 'NOT_RUNNING') {
      log('warn', RVPN.t('log_stop_failed', [res.error]));
    }
    return res;
  });
}

/** stop + start (used when settings change while RVPN mode is active). */
function restartCore() {
  return stopCore()
    .then(() => sleep(300))
    .then(() => startCore());
}

/** Connect + start when RVPN mode is wanted but the core is not running. */
function ensureRunning() {
  return init().then(() => {
    if (mem.mode !== RVPN.MODE_RVPN) return { ok: true };
    // Refresh first: the core may have been restarted (or never started) while
    // this worker was asleep, so the cached state cannot be trusted.
    return doStatus().then(() => {
      const phase = mem.state && mem.state.phase;
      if (phase && phase !== 'idle' && phase !== 'error' && phase !== 'stopping') {
        return syncProxy().then(() => ({ ok: true }));
      }
      return startCore();
    });
  });
}

/* ------------------------------------------------------------------ *
 * Mode switching
 * ------------------------------------------------------------------ */

function switchToNormal(opts) {
  const options = opts || {};
  return init().then(() => {
    mem.mode = RVPN.MODE_NORMAL;
    setSuspended('');
    if (!options.keepFallback) mem.fallback = null;
    return save({ mode: mem.mode, fallback: mem.fallback }).then(() => {
      // Order matters: drop the proxy override first so the browser is never
      // pointed at a port we are about to close.
      return dropProxy('').then(() => stopCore());
    }).then(() => {
      log('info', RVPN.t('log_mode_normal'));
      updateBadge();
      broadcast({ type: 'update', reason: 'mode' });
      return { ok: true, mode: mem.mode };
    });
  });
}

function switchToRvpn() {
  return init().then(() => {
    mem.mode = RVPN.MODE_RVPN;
    mem.fallback = null;
    setSuspended('');
    return save({ mode: mem.mode, fallback: null }).then(() => {
      log('info', RVPN.t('log_mode_rvpn'));
      updateBadge();
      broadcast({ type: 'update', reason: 'mode' });
      return startCore().then((res) => {
        if (res.ok) return { ok: true, mode: mem.mode, state: mem.state };
        return fallbackToNormal(res.error || RVPN.t('err_unknown'), { notify: true }).then(() => ({
          ok: false,
          mode: mem.mode,
          error: res.error,
          code: res.code,
          fellBack: true
        }));
      });
    });
  });
}

/** RVPN could not be established - go back to normal mode and say why. */
function fallbackToNormal(reason, opts) {
  const options = opts || {};
  const text = reason || RVPN.t('err_unknown');
  mem.fallback = { reason: text, ts: Date.now() };
  log('error', RVPN.t('log_fallback', [text]));
  return switchToNormal({ keepFallback: true }).then(() => {
    save({ fallback: mem.fallback });
    if (options.notify) {
      notify(RVPN.t('notif_fallback_title'), RVPN.t('notif_fallback_body', [text]));
    }
    broadcast({ type: 'update', reason: 'fallback' });
    return { ok: false, error: text };
  });
}

/**
 * chrome.runtime.onStartup: mode is persistent, so re-apply whatever the user
 * had. RVPN mode is retried a few times before falling back to normal mode.
 */
function restoreOnStartup() {
  return init().then(() => {
    log('info', RVPN.t('log_startup', [RVPN.t(mem.mode === RVPN.MODE_RVPN ? 'mode_rvpn' : 'mode_normal')]));
    if (mem.mode !== RVPN.MODE_RVPN) {
      setSuspended('');
      return dropProxy('').then(() => {
        ensurePolling();
        broadcast({ type: 'update', reason: 'startup' });
        return { ok: true, mode: mem.mode };
      });
    }
    let lastError = '';
    const attempt = (n) => ensureConnected().then((conn) => {
      if (!conn.ok) {
        lastError = conn.error || RVPN.t('err_host_not_found');
        return false;
      }
      return startCore().then((res) => {
        if (res.ok) return true;
        lastError = res.error || RVPN.t('err_unknown');
        return false;
      });
    }).then((ok) => {
      if (ok) {
        log('info', RVPN.t('log_restore_ok'));
        ensurePolling();
        broadcast({ type: 'update', reason: 'startup' });
        return { ok: true, mode: mem.mode };
      }
      if (n < 3) return sleep(1000 * n).then(() => attempt(n + 1));
      return fallbackToNormal(lastError || RVPN.t('err_host_not_found'), { notify: true });
    });
    return attempt(1);
  });
}

/* ------------------------------------------------------------------ *
 * Status polling
 * ------------------------------------------------------------------ */

function shouldPoll() {
  if (mem.popupCount > 0) return true;
  return mem.mode === RVPN.MODE_RVPN;
}

function ensurePolling() {
  if (!shouldPoll()) {
    stopPolling();
    return;
  }
  const interval = mem.popupCount > 0 ? RVPN.POLL_POPUP_MS : RVPN.POLL_BACKGROUND_MS;
  if (mem.pollTimer && mem.pollInterval === interval) return;
  stopPolling();
  mem.pollInterval = interval;
  mem.pollTimer = setInterval(() => {
    pollOnce().catch((e) => console.warn('[rvpn] poll error:', e));
  }, interval);
}

function stopPolling() {
  if (mem.pollTimer) {
    clearInterval(mem.pollTimer);
    mem.pollTimer = null;
    mem.pollInterval = 0;
  }
}

/** One `status` round trip; concurrent callers share the in-flight request. */
function pollOnce() {
  if (mem.pollInFlight) return mem.pollInFlight;
  const run = init().then(() => {
    mem.lastPollTs = Date.now();
    if (!native.port) {
      return ensureConnected().then((conn) => {
        if (!conn.ok) return { ok: false, error: conn.error, code: conn.code };
        return doStatus();
      });
    }
    return doStatus();
  });
  const tracked = run.then(
    (res) => {
      mem.pollInFlight = null;
      return res;
    },
    (e) => {
      mem.pollInFlight = null;
      console.warn('[rvpn] pollOnce failed:', e);
      return nativeFailure(String(e), 'INTERNAL');
    }
  );
  mem.pollInFlight = tracked;
  return tracked;
}

function doStatus() {
  return request('status', { since: mem.since }, { timeout: 10000 }).then((res) => {
    if (!res.ok) {
      if (res.code !== 'TIMEOUT') mem.coreError = res.error;
      return res;
    }
    const data = res.data || {};
    if (Number.isFinite(data.nextSince)) {
      mem.since = data.nextSince;
      save({ since: mem.since });
    }
    if (data.state) setState(data.state);
    ingestEvents(data.events);
    return syncProxy().then(() => res);
  });
}

/* ------------------------------------------------------------------ *
 * Challenges
 * ------------------------------------------------------------------ */

function addChallenge(ch) {
  const existing = mem.challenges.filter((c) => c.id === ch.id)[0];
  const record = {
    id: ch.id,
    type: RVPN.CHALLENGE_TYPES.indexOf(ch.type) === -1 ? 'text' : ch.type,
    message: ch.message || '',
    imageDataUrl: ch.imageDataUrl || '',
    canSkipSecondaryAuth: !!ch.canSkipSecondaryAuth,
    loginUrl: ch.loginUrl || '',
    deadline: Number.isFinite(ch.deadline) ? ch.deadline : 0,
    receivedAt: existing ? existing.receivedAt : Date.now(),
    submitted: false
  };
  if (existing) {
    Object.assign(existing, record);
  } else {
    mem.challenges.push(record);
    log('warn', RVPN.t('log_challenge_received', [RVPN.challengeTypeText(record.type)]));
    notifyChallenge(record);
  }
  updateBadge();
  broadcast({ type: 'update', reason: 'challenge' });
  return record;
}

/**
 * `challengeDone` events carry no challenge id in the frozen contract
 * (PROTOCOL.md 2.5), so: prefer an id if the core echoes one, otherwise drop
 * the challenges we optimistically marked as submitted, and finally - if the
 * core reports the round finished - drop everything pending.
 */
function handleChallengeDone(ev) {
  const id = (ev.challenge && ev.challenge.id) || ev.challengeId || '';
  if (id) {
    removeChallenge(id);
    return;
  }
  const submitted = mem.challenges.filter((c) => c.submitted);
  if (submitted.length) {
    submitted.forEach((c) => removeChallenge(c.id, true));
  } else if (mem.challenges.length) {
    mem.challenges.forEach((c) => removeChallenge(c.id, true));
  }
}

function removeChallenge(id, skipPersist) {
  const before = mem.challenges.length;
  mem.challenges = mem.challenges.filter((c) => c.id !== id);
  if (mem.challenges.length !== before) {
    if (!skipPersist) persistChallenges();
    updateBadge();
    broadcast({ type: 'update', reason: 'challenge' });
  }
}

function markSubmitted(id) {
  mem.challenges.forEach((c) => {
    if (c.id === id) c.submitted = true;
  });
  persistChallenges();
  broadcast({ type: 'update', reason: 'challenge' });
}

function answerChallenge(payload) {
  return init().then(() => {
    const p = payload || {};
    if (!p.challengeId) return nativeFailure(RVPN.t('err_no_challenge_id'), 'INVALID_CONFIG');
    const params = { challengeId: p.challengeId };
    if (typeof p.code === 'string') params.code = p.code;
    if (Array.isArray(p.points) && p.points.length) {
      params.points = p.points.map((pt) => ({ x: Math.round(pt.x), y: Math.round(pt.y) }));
      if (Number.isFinite(p.width)) params.width = Math.round(p.width);
      if (Number.isFinite(p.height)) params.height = Math.round(p.height);
    }
    if (p.skipSecondaryAuth) params.skipSecondaryAuth = true;
    return ensureConnected().then((conn) => {
      if (!conn.ok) return nativeFailure(conn.error, conn.code);
      return request('answer', params, { timeout: RVPN.TIMEOUT_MS }).then((res) => {
        if (res.ok && res.data && res.data.accepted === false) {
          return nativeFailure(RVPN.t('err_answer_rejected'), 'REJECTED');
        }
        if (res.ok) markSubmitted(p.challengeId);
        return res;
      });
    });
  });
}

/* ------------------------------------------------------------------ *
 * Notifications
 * ------------------------------------------------------------------ */

function notify(title, message) {
  try {
    chrome.notifications.create('rvpn-' + Date.now(), {
      type: 'basic',
      iconUrl: 'icons/icon128.png',
      title,
      message,
      priority: 2
    }, () => {
      RVPN.lastError();
    });
  } catch (e) {
    console.warn('[rvpn] notification failed:', e);
  }
}

function notifyChallenge(ch) {
  notify(RVPN.t('notif_challenge_title'), ch.message || RVPN.challengeTypeText(ch.type));
  if (mem.popupCount === 0) openPopup();
}

/** Best effort: open the toolbar popup, else a standalone popup window. */
function openPopup() {
  try {
    if (chrome.action && typeof chrome.action.openPopup === 'function') {
      chrome.action.openPopup(() => {
        const err = RVPN.lastError();
        if (err) openPopupWindow();
      });
      return;
    }
  } catch (e) {
    /* fall through to a detached window */
  }
  openPopupWindow();
}

function openPopupWindow() {
  try {
    chrome.windows.create({
      url: chrome.runtime.getURL('popup.html') + '#challenge',
      type: 'popup',
      width: 420,
      height: 680,
      focused: true
    }, () => {
      RVPN.lastError();
    });
  } catch (e) {
    console.warn('[rvpn] could not open popup window:', e);
  }
}

function updateBadge() {
  try {
    if (!chrome.action || !chrome.action.setBadgeText) return;
    let text = '';
    let color = '#d93025';
    if (mem.challenges.length) {
      text = String(mem.challenges.length);
      color = '#d93025';
    } else if (mem.mode === RVPN.MODE_RVPN) {
      const phase = mem.state && mem.state.phase;
      if (phase === 'connected') {
        text = 'ON';
        color = '#188038';
      } else if (phase === 'error') {
        text = '!';
        color = '#d93025';
      } else {
        text = '...';
        color = '#f9ab00';
      }
    }
    chrome.action.setBadgeText({ text }, () => {
      RVPN.lastError();
    });
    if (chrome.action.setBadgeBackgroundColor) {
      chrome.action.setBadgeBackgroundColor({ color }, () => {
        RVPN.lastError();
      });
    }
  } catch (e) {
    /* badge is cosmetic */
  }
}

/* ------------------------------------------------------------------ *
 * Diagnostics
 * ------------------------------------------------------------------ */

function diagnostics() {
  const m = chrome.runtime.getManifest();
  const st = mem.state || {};
  const hello = mem.hello || {};
  const lines = [];
  lines.push('=== RVPN Browser diagnostics ===');
  lines.push('time: ' + RVPN.formatDateTime(Date.now()));
  lines.push('extension: ' + m.name + ' v' + m.version + ' (' + m.default_locale + ')');
  lines.push('browser: ' + (self.navigator && navigator.userAgent ? navigator.userAgent : 'n/a'));
  lines.push('native host: ' + RVPN.HOST_NAME + ' [' + (native.port ? 'connected' : 'disconnected') + ']');
  lines.push('mode: ' + mem.mode);
  lines.push('core error: ' + (mem.coreError || '-'));
  lines.push('suspended: ' + (mem.suspended || '-'));
  lines.push('fallback: ' + (mem.fallback ? (RVPN.formatDateTime(mem.fallback.ts) + ' ' + mem.fallback.reason) : '-'));
  lines.push('hello: version=' + (hello.version || '-') +
    ' coreVersion=' + (hello.coreVersion || '-') +
    ' zjuConnect=' + (hello.zjuConnectVersion || '-') +
    ' platform=' + (hello.platform || '-') + '/' + (hello.arch || '-') +
    ' capabilities=' + ((hello.capabilities || []).join(',') || '-'));
  lines.push('state: phase=' + (st.phase || '-') +
    ' server=' + (st.server || '-') +
    ' socksAddr=' + (st.socksAddr || '-') +
    ' httpAddr=' + (st.httpAddr || '-') +
    ' clientIP=' + (st.clientIP || '-') +
    ' connectedAt=' + (st.connectedAt ? RVPN.formatDateTime(st.connectedAt) : '-'));
  lines.push('state.message: ' + (st.message || '-'));
  lines.push('state.lastError: ' + (st.lastError || '-'));
  lines.push('proxy applied: ' + (mem.proxyKey || 'none'));
  lines.push('since: ' + mem.since + ' logSeq: ' + mem.logSeq);
  lines.push('pending challenges: ' + (mem.challenges.map((c) => c.id + ':' + c.type).join(', ') || 'none'));
  lines.push('--- log (newest last, max ' + RVPN.PERSIST_LOG_LINES + ') ---');
  RVPN.logForStorage(mem.logs).forEach((e) => {
    lines.push('[' + RVPN.formatTime(e.ts) + '] ' + (e.level || 'info') + '/' + (e.kind || 'log') + ' ' + (e.message || ''));
  });
  return RVPN.redact(lines.join('\n'));
}

/* ------------------------------------------------------------------ *
 * Message router (popup / options page)
 * ------------------------------------------------------------------ */

function publicSettings() {
  const s = mem.settings;
  return Object.assign({}, s, {
    // Never hand the stored password back when the user chose not to save it.
    password: s.savePassword ? s.password : ''
  });
}

function snapshot(logSince) {
  const since = Number.isFinite(logSince) ? logSince : 0;
  return {
    ok: true,
    mode: mem.mode,
    state: mem.state,
    hello: mem.hello,
    coreAvailable: !!native.port,
    coreError: mem.coreError,
    suspended: mem.suspended,
    fallback: mem.fallback,
    challenges: mem.challenges.slice(),
    settings: publicSettings(),
    logs: mem.logs.filter((e) => e.n > since),
    lastN: mem.logSeq,
    logTotal: mem.logs.length,
    pollMs: mem.popupCount > 0 ? RVPN.POLL_POPUP_MS : RVPN.POLL_BACKGROUND_MS
  };
}

function saveSettings(raw) {
  return init().then(() => {
    const next = RVPN.normalizeSettings(raw);
    const previous = mem.settings;
    // "Do not save the password": keep it for this session only.
    if (next.savePassword) {
      mem.sessionPassword = '';
    } else {
      mem.sessionPassword = next.password || mem.sessionPassword;
      next.password = '';
    }
    mem.settings = next;
    return save({ settings: next }).then(() => {
      log('info', RVPN.t('log_settings_saved'));
      broadcast({ type: 'update', reason: 'settings' });
      const passwordChanged = previous.password !== next.password && !!next.password;
      const connectionChanged = previous.userType !== next.userType ||
        previous.protocol !== next.protocol ||
        previous.server !== next.server ||
        previous.port !== next.port ||
        previous.username !== next.username ||
        previous.loginDomain !== next.loginDomain ||
        previous.authType !== next.authType ||
        previous.phone !== next.phone ||
        passwordChanged ||
        previous.proxyAll !== next.proxyAll ||
        previous.socksPort !== next.socksPort ||
        previous.httpPort !== next.httpPort ||
        previous.totpSecret !== next.totpSecret;
      if (next.debug !== previous.debug) {
        request('setDebug', { debug: next.debug }, { timeout: RVPN.TIMEOUT_MS }).catch(() => null);
      }
      if (mem.mode === RVPN.MODE_RVPN && connectionChanged) {
        log('info', RVPN.t('log_settings_restart'));
        return restartCore().then((res) => ({
          // The save itself succeeded; a failed restart is reported separately
          // so the UI can say "saved, but reconnecting failed: ...".
          ok: true,
          restarted: true,
          restartError: res.ok ? undefined : res.error,
          settings: publicSettings()
        }));
      }
      return { ok: true, restarted: false, settings: publicSettings() };
    });
  });
}

function setMode(mode) {
  if (mode === RVPN.MODE_RVPN) return switchToRvpn();
  return switchToNormal({});
}

function handleMessage(msg) {
  const type = msg && msg.type;
  switch (type) {
    case 'popupOpened':
      mem.popupCount += 1;
      ensurePolling();
      pollOnce().catch(() => null);
      broadcast({ type: 'update', reason: 'popup' });
      return Promise.resolve(snapshot(msg.logSince));

    case 'popupClosed':
      mem.popupCount = Math.max(0, mem.popupCount - 1);
      ensurePolling();
      return Promise.resolve({ ok: true });

    case 'getState':
      return init().then(() => {
        ensurePolling();
        return pollOnce().then(() => snapshot(msg.logSince), () => snapshot(msg.logSince));
      });

    case 'requestStatus':
      return init().then(() => pollOnce().then(() => snapshot(msg.logSince), () => snapshot(msg.logSince)));

    case 'setMode':
      return init().then(() => setMode(msg.mode));

    case 'reconnect':
      return init().then(() => {
        teardownPort(RVPN.t('err_disconnected'), 'DISCONNECTED');
        native.backoff = RVPN.BACKOFF_MIN_MS;
        return ensureConnected().then((conn) => {
          if (!conn.ok) return { ok: false, error: conn.error, code: conn.code };
          if (mem.mode === RVPN.MODE_RVPN) return ensureRunning();
          return { ok: true };
        });
      });

    case 'answer':
      return answerChallenge(msg.payload);

    case 'selftest':
      if (!msg.server) return Promise.resolve(nativeFailure(RVPN.t('err_server_required'), 'INVALID_CONFIG'));
      return init()
        .then(() => ensureConnected())
        .then((conn) => {
          if (!conn.ok) return nativeFailure(conn.error, conn.code);
          return request(
            'selftest',
            { server: msg.server, port: Number(msg.port) || 443 },
            { timeout: 30000 }
          );
        });

    case 'authInfo':
      if (!msg.server) return Promise.resolve(nativeFailure(RVPN.t('err_server_required'), 'INVALID_CONFIG'));
      return init()
        .then(() => ensureConnected())
        .then((conn) => {
          if (!conn.ok) return nativeFailure(conn.error, conn.code);
          return request(
            'authInfo',
            { server: msg.server, port: Number(msg.port) || 443 },
            { timeout: 30000 }
          );
        });

    case 'hello':
      return init().then(() => ensureConnected());

    case 'setDebug':
      return init().then(() => {
        mem.settings.debug = !!msg.debug;
        return save({ settings: mem.settings })
          .then(() => ensureConnected())
          .then((conn) => {
            if (!conn.ok) return { ok: true, forwarded: false, error: conn.error };
            // Best effort: the flag is also part of the next `start` config, so
            // a core that is not running does not make this an error.
            return request('setDebug', { debug: !!msg.debug }, { timeout: RVPN.TIMEOUT_MS })
              .then((res) => (res.ok ? { ok: true, forwarded: true } : { ok: true, forwarded: false, error: res.error }));
          });
      });

    case 'saveSettings':
      return saveSettings(msg.settings);

    case 'clearLog':
      return init().then(() => {
        mem.logs = [];
        return save({ logs: [] }).then(() => {
          broadcast({ type: 'update', reason: 'log-cleared' });
          return { ok: true };
        });
      });

    case 'diagnostics':
      return init().then(() => ({ ok: true, text: diagnostics() }));

    case 'openOptions':
      return Promise.resolve({ ok: true });

    default:
      return Promise.resolve(nativeFailure(RVPN.t('err_unknown_command', [String(type)]), 'UNKNOWN_COMMAND'));
  }
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  let result;
  try {
    result = handleMessage(msg);
  } catch (e) {
    console.error('[rvpn] handler threw:', e);
    sendResponse(nativeFailure(String(e), 'INTERNAL'));
    return false;
  }
  Promise.resolve(result).then(
    (res) => sendResponse(res || { ok: true }),
    (e) => {
      console.error('[rvpn] handler rejected:', e);
      sendResponse(nativeFailure(String(e), 'INTERNAL'));
    }
  );
  return true; // async response
});

/** Tell every open extension page that something changed. */
function broadcast(payload) {
  try {
    chrome.runtime.sendMessage(payload, () => {
      RVPN.lastError(); // "receiving end does not exist" is expected and harmless
    });
  } catch (e) {
    /* no receivers */
  }
}

/* ------------------------------------------------------------------ *
 * Lifecycle
 * ------------------------------------------------------------------ */

chrome.runtime.onInstalled.addListener((details) => {
  init()
    .then(() => {
      const patch = {};
      if (!mem.settings) patch.settings = RVPN.DEFAULT_SETTINGS;
      if (!mem.mode) patch.mode = RVPN.MODE_NORMAL;
      if (Object.keys(patch).length) return save(patch);
      return undefined;
    })
    .then(() => {
      log('info', RVPN.t('log_installed', [details && details.reason ? details.reason : 'install']));
      ensureWatchdog();
      ensurePolling();
    })
    .catch((e) => console.error('[rvpn] onInstalled failed:', e));
});

chrome.runtime.onStartup.addListener(() => {
  ensureWatchdog();
  restoreOnStartup().catch((e) => console.error('[rvpn] onStartup failed:', e));
});

function ensureWatchdog() {
  try {
    const created = chrome.alarms.create(WATCHDOG_ALARM, { periodInMinutes: WATCHDOG_PERIOD_MIN });
    if (created && typeof created.catch === 'function') created.catch(() => null);
  } catch (e) {
    console.warn('[rvpn] alarms.create failed:', e);
  }
}

/**
 * Watchdog: an MV3 worker is killed when idle, so a periodic alarm re-arms
 * polling / reconnection and guarantees no stale proxy is ever left behind.
 */
function watchdog() {
  return init().then(() => {
    if (mem.mode === RVPN.MODE_RVPN) {
      if (!native.port) {
        return ensureConnected().then((conn) => {
          if (conn.ok) return ensureRunning();
          return undefined;
        });
      }
      return pollOnce().then(() => undefined);
    }
    if (mem.proxyKey) return dropProxy('').then(() => undefined);
    return undefined;
  }).then(() => {
    ensurePolling();
    updateBadge();
  });
}

chrome.alarms.onAlarm.addListener((alarm) => {
  if (!alarm || alarm.name !== WATCHDOG_ALARM) return;
  watchdog().catch((e) => console.warn('[rvpn] watchdog failed:', e));
});

chrome.notifications.onClicked.addListener(() => {
  openPopup();
});

// Warm up on worker start so a cold start after a browser restart already
// knows the persisted mode (onStartup is not fired for every worker wake-up).
ensureWatchdog();
init()
  .then(() => {
    if (mem.mode === RVPN.MODE_RVPN || mem.challenges.length) ensurePolling();
    updateBadge();
    return undefined;
  })
  .catch((e) => console.error('[rvpn] init failed:', e));
