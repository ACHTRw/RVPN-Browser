'use strict';
/**
 * RVPN Browser - shared front-end library.
 *
 * Loaded by the MV3 service worker through importScripts() and by the popup /
 * options pages through a plain <script> tag. It must therefore never touch
 * `window`/`document` at load time.
 *
 * The module exposes a single global: `RVPN`.
 */
(function (global) {
  const RVPN = global.RVPN || (global.RVPN = {});

  /* ------------------------------------------------------------------ *
   * Constants (kept in sync with docs/PROTOCOL.md - frozen contract v1)
   * ------------------------------------------------------------------ */

  RVPN.HOST_NAME = 'com.achtrw.rvpn';
  RVPN.DEFAULT_LOCALE = 'zh_CN';

  /** request timeout defaults, milliseconds */
  RVPN.TIMEOUT_MS = 20000;
  RVPN.START_TIMEOUT_MS = 120000;

  /** native reconnection backoff, milliseconds */
  RVPN.BACKOFF_MIN_MS = 1000;
  RVPN.BACKOFF_MAX_MS = 30000;

  /** status polling intervals, milliseconds */
  RVPN.POLL_POPUP_MS = 500;
  RVPN.POLL_BACKGROUND_MS = 2000;

  /** log retention */
  RVPN.MAX_LOG_ENTRIES = 500;
  RVPN.PERSIST_LOG_LINES = 200;

  RVPN.MODE_NORMAL = 'normal';
  RVPN.MODE_RVPN = 'rvpn';

  RVPN.PHASES = ['idle', 'connecting', 'authenticating', 'connected', 'error', 'stopping'];

  /** PROTOCOL.md 2.6 - challenge types */
  RVPN.CHALLENGE_TYPES = [
    'text',
    'clickCode',
    'smsCode',
    'totp',
    'radiusCode',
    'secondaryAuth',
    'externalLogin'
  ];

  /** PROTOCOL.md 2.2 - authType values accepted by the core */
  RVPN.AUTH_TYPES = ['', 'auth/psw', 'auth/cas', 'auth/smsCheckCode', 'auth/httpsOauth2'];

  /** servers auto-filled for ZJU users (PROTOCOL.md 2.2 default rules) */
  RVPN.ZJU_SERVERS = {
    atrust: 'vpn.zju.edu.cn',
    easyconnect: 'rvpn.zju.edu.cn'
  };

  RVPN.BYPASS_LIST = ['localhost', '127.0.0.1', '[::1]'];

  /* ------------------------------------------------------------------ *
   * i18n
   * ------------------------------------------------------------------ */

  /**
   * Translate a message key. Falls back to the key itself (never throws,
   * never returns undefined) so a missing locale entry can never break UI.
   */
  RVPN.t = function t(key, substitutions) {
    try {
      if (global.chrome && chrome.i18n && chrome.i18n.getMessage) {
        const msg = substitutions === undefined
          ? chrome.i18n.getMessage(key)
          : chrome.i18n.getMessage(key, substitutions);
        if (msg) return msg;
      }
    } catch (e) {
      /* ignore - fall through to key */
    }
    return key;
  };

  /** Apply data-i18n* attributes inside `root` (defaults to document). */
  RVPN.applyI18n = function applyI18n(root) {
    const scope = root || (typeof document !== 'undefined' ? document : null);
    if (!scope || !scope.querySelectorAll) return;
    scope.querySelectorAll('[data-i18n]').forEach((node) => {
      node.textContent = RVPN.t(node.getAttribute('data-i18n'));
    });
    scope.querySelectorAll('[data-i18n-placeholder]').forEach((node) => {
      node.setAttribute('placeholder', RVPN.t(node.getAttribute('data-i18n-placeholder')));
    });
    scope.querySelectorAll('[data-i18n-title]').forEach((node) => {
      node.setAttribute('title', RVPN.t(node.getAttribute('data-i18n-title')));
    });
    scope.querySelectorAll('[data-i18n-aria-label]').forEach((node) => {
      node.setAttribute('aria-label', RVPN.t(node.getAttribute('data-i18n-aria-label')));
    });
  };

  /* ------------------------------------------------------------------ *
   * chrome.* promise wrappers.
   * Every callback checks chrome.runtime.lastError; no wrapper ever
   * rejects, so an absent core or denied API can not produce an
   * unhandled promise rejection.
   * ------------------------------------------------------------------ */

  /** `chrome.runtime.lastError` -> string | null */
  RVPN.lastError = function lastError() {
    try {
      const err = global.chrome && chrome.runtime && chrome.runtime.lastError;
      return err ? (err.message || String(err)) : null;
    } catch (e) {
      return null;
    }
  };

  RVPN.storageGet = function storageGet(keys) {
    return new Promise((resolve) => {
      try {
        chrome.storage.local.get(keys, (items) => {
          const err = RVPN.lastError();
          if (err) {
            console.warn('[rvpn] storage.local.get failed:', err);
            resolve({});
            return;
          }
          resolve(items || {});
        });
      } catch (e) {
        console.warn('[rvpn] storage.local.get threw:', e);
        resolve({});
      }
    });
  };

  RVPN.storageSet = function storageSet(items) {
    return new Promise((resolve) => {
      try {
        chrome.storage.local.set(items, () => {
          const err = RVPN.lastError();
          if (err) {
            console.warn('[rvpn] storage.local.set failed:', err);
            resolve({ ok: false, error: err });
            return;
          }
          resolve({ ok: true });
        });
      } catch (e) {
        console.warn('[rvpn] storage.local.set threw:', e);
        resolve({ ok: false, error: String(e) });
      }
    });
  };

  RVPN.storageRemove = function storageRemove(keys) {
    return new Promise((resolve) => {
      try {
        chrome.storage.local.remove(keys, () => {
          const err = RVPN.lastError();
          resolve({ ok: !err, error: err || undefined });
        });
      } catch (e) {
        resolve({ ok: false, error: String(e) });
      }
    });
  };

  /* ------------------------------------------------------------------ *
   * Settings model (maps 1:1 onto PROTOCOL.md 2.2 Config)
   * ------------------------------------------------------------------ */

  RVPN.DEFAULT_SETTINGS = {
    userType: 'zju',
    protocol: 'atrust',
    server: '',
    port: 443,
    username: '',
    password: '',
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

  function str(v) {
    return typeof v === 'string' ? v.trim() : (v === undefined || v === null ? '' : String(v));
  }

  function bool(v) {
    return v === true || v === 'true' || v === 1 || v === '1';
  }

  /** Coerce anything into a valid settings object. */
  RVPN.normalizeSettings = function normalizeSettings(raw) {
    const d = RVPN.DEFAULT_SETTINGS;
    const s = raw && typeof raw === 'object' ? raw : {};
    const userType = str(s.userType) === 'other' ? 'other' : 'zju';
    let protocol = str(s.protocol);
    if (protocol !== 'atrust' && protocol !== 'easyconnect') protocol = d.protocol;
    let authType = str(s.authType);
    if (RVPN.AUTH_TYPES.indexOf(authType) === -1) authType = '';
    let port = parseInt(s.port, 10);
    if (!Number.isFinite(port) || port <= 0 || port > 65535) port = d.port;
    return {
      userType,
      protocol,
      server: str(s.server),
      port,
      username: str(s.username),
      password: typeof s.password === 'string' ? s.password : '',
      savePassword: s.savePassword === undefined ? d.savePassword : bool(s.savePassword),
      loginDomain: userType === 'zju' ? 'Radius' : (str(s.loginDomain) || 'Radius'),
      authType,
      phone: str(s.phone),
      totpSecret: str(s.totpSecret),
      socksPort: RVPN.portOrEmpty(s.socksPort),
      httpPort: RVPN.portOrEmpty(s.httpPort),
      debug: bool(s.debug),
      proxyAll: bool(s.proxyAll)
    };
  };

  /** "" | 0 | invalid -> "" (auto); valid port -> string */
  RVPN.portOrEmpty = function portOrEmpty(value) {
    const s = str(value);
    if (!s) return '';
    const n = parseInt(s, 10);
    if (!Number.isFinite(n) || n < 0 || n > 65535) return '';
    return String(n);
  };

  RVPN.isValidPort = function isValidPort(value) {
    const s = str(value);
    if (!s) return true; // empty means auto
    const n = Number(s);
    return Number.isInteger(n) && n >= 1 && n <= 65535;
  };

  /** Server address actually sent to the core (PROTOCOL.md 2.2). */
  RVPN.resolveServer = function resolveServer(settings) {
    if (settings.server) return settings.server;
    if (settings.userType === 'zju') {
      return RVPN.ZJU_SERVERS[settings.protocol] || RVPN.ZJU_SERVERS.atrust;
    }
    return '';
  };

  /** Settings -> PROTOCOL.md 2.2 Config payload for `start`. */
  RVPN.buildConfig = function buildConfig(settings) {
    const s = RVPN.normalizeSettings(settings);
    const isOther = s.userType === 'other';
    return {
      userType: s.userType,
      protocol: s.protocol,
      server: RVPN.resolveServer(s),
      port: s.port,
      username: s.username,
      password: s.password,
      loginDomain: s.loginDomain,
      authType: s.authType,
      phone: s.phone,
      totpSecret: s.totpSecret,
      // PROTOCOL.md 2.2: port 0 = auto assign, "" = disabled
      socksBind: '127.0.0.1:' + (s.socksPort || '0'),
      httpBind: '127.0.0.1:' + (s.httpPort || '0'),
      proxyAll: s.proxyAll,
      // The core forces these for non-ZJU users too; we send them explicitly
      // so the intent is visible and older cores behave identically.
      disableZJUConfig: isOther,
      skipDomainResource: isOther,
      debug: s.debug
    };
  };

  /* ------------------------------------------------------------------ *
   * Log helpers
   * ------------------------------------------------------------------ */

  /** Trim a log array in place to MAX_LOG_ENTRIES. */
  RVPN.trimLog = function trimLog(entries) {
    if (entries.length > RVPN.MAX_LOG_ENTRIES) {
      entries.splice(0, entries.length - RVPN.MAX_LOG_ENTRIES);
    }
    return entries;
  };

  /** Serialise the newest PERSIST_LOG_LINES lines for chrome.storage.local. */
  RVPN.logForStorage = function logForStorage(entries) {
    return entries.slice(-RVPN.PERSIST_LOG_LINES);
  };

  RVPN.formatTime = function formatTime(ts) {
    const d = ts ? new Date(ts) : new Date();
    const p = (n) => String(n).padStart(2, '0');
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  };

  RVPN.formatDateTime = function formatDateTime(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    const p = (n) => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
      ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  };

  /** i18n key describing a State.phase value. */
  RVPN.phaseKey = function phaseKey(phase) {
    const p = RVPN.PHASES.indexOf(phase) === -1 ? 'idle' : phase;
    return 'phase_' + p;
  };

  RVPN.phaseText = function phaseText(phase) {
    return RVPN.t(RVPN.phaseKey(phase));
  };

  /** i18n key describing a challenge type. */
  RVPN.challengeTypeText = function challengeTypeText(type) {
    const t = RVPN.CHALLENGE_TYPES.indexOf(type) === -1 ? 'text' : type;
    return RVPN.t('challenge_type_' + t);
  };

  /**
   * Redact secrets from diagnostic text (PROTOCOL.md 5.2 keeps secrets out of
   * the core logs; the extension must not leak them into copied diagnostics).
   */
  RVPN.redact = function redact(text) {
    let out = String(text === undefined || text === null ? '' : text);
    out = out.replace(/("?(?:password|totpSecret)"?\s*[:=]\s*")[^"]*(")/gi, '$1***$2');
    out = out.replace(/(密码|动态口令密钥)\s*[:=]\s*\S+/g, '$1=***');
    return out;
  };

  /* ------------------------------------------------------------------ *
   * DOM helpers (pages only)
   * ------------------------------------------------------------------ */

  RVPN.$ = function $(sel, root) {
    const scope = root || document;
    return scope.querySelector(sel);
  };

  RVPN.$$ = function $$(sel, root) {
    const scope = root || document;
    return Array.prototype.slice.call(scope.querySelectorAll(sel));
  };

  RVPN.el = function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  };

  /**
   * Render log entries into a container (newest first).
   * Uses textContent only - core messages are never interpreted as HTML.
   */
  RVPN.renderLog = function renderLog(container, entries, emptyKey) {
    if (!container) return;
    container.textContent = '';
    if (!entries || !entries.length) {
      const empty = RVPN.el('div', 'log-empty', RVPN.t(emptyKey || 'log_empty'));
      container.appendChild(empty);
      return;
    }
    const frag = document.createDocumentFragment();
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i] || {};
      const row = RVPN.el('div', 'log-row log-level-' + (e.level || 'info'));
      row.appendChild(RVPN.el('span', 'log-time', RVPN.formatTime(e.ts)));
      row.appendChild(RVPN.el('span', 'log-kind', e.kind || 'log'));
      row.appendChild(RVPN.el('span', 'log-msg', e.message || ''));
      frag.appendChild(row);
    }
    container.appendChild(frag);
  };

  /** Copy text to the clipboard, with a textarea fallback. */
  RVPN.copyText = function copyText(text) {
    return new Promise((resolve) => {
      const done = (ok) => resolve(ok);
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(
            () => done(true),
            () => done(RVPN.copyTextFallback(text))
          );
          return;
        }
      } catch (e) {
        /* fall through */
      }
      done(RVPN.copyTextFallback(text));
    });
  };

  RVPN.copyTextFallback = function copyTextFallback(text) {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', 'readonly');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return !!ok;
    } catch (e) {
      return false;
    }
  };

  /** Send a message to the background worker; never rejects. */
  RVPN.send = function send(message) {
    return new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage(message, (response) => {
          const err = RVPN.lastError();
          if (err) {
            console.warn('[rvpn] sendMessage failed:', err);
            resolve({ ok: false, error: err, code: 'NO_BACKGROUND' });
            return;
          }
          resolve(response || { ok: false, error: 'empty response', code: 'EMPTY_RESPONSE' });
        });
      } catch (e) {
        resolve({ ok: false, error: String(e), code: 'NO_BACKGROUND' });
      }
    });
  };

  /** Open the options page. */
  RVPN.openOptions = function openOptions() {
    return new Promise((resolve) => {
      try {
        if (chrome.runtime.openOptionsPage) {
          chrome.runtime.openOptionsPage(() => {
            resolve({ ok: !RVPN.lastError() });
          });
          return;
        }
      } catch (e) {
        /* fall through */
      }
      try {
        chrome.tabs.create({ url: chrome.runtime.getURL('options.html') }, () => {
          resolve({ ok: !RVPN.lastError() });
        });
      } catch (e) {
        resolve({ ok: false, error: String(e) });
      }
    });
  };

  /** Pretty-print a socksAddr/httpAddr value ("127.0.0.1:38271"). */
  RVPN.splitAddr = function splitAddr(addr) {
    const s = str(addr);
    const idx = s.lastIndexOf(':');
    if (idx <= 0) return null;
    const host = s.slice(0, idx);
    const port = parseInt(s.slice(idx + 1), 10);
    if (!host || !Number.isFinite(port) || port <= 0) return null;
    return { host, port };
  };
}(typeof self !== 'undefined' ? self : globalThis));
