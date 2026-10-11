'use strict';
/**
 * Minimal but faithful chrome.* mock + extension loader, used to exercise the
 * RVPN Browser service worker outside a browser (no browser is started).
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const EXT = process.env.RVPN_EXTENSION_DIR || path.resolve(__dirname, '..', '..', 'extension');

function loadMessages(locale) {
  return JSON.parse(fs.readFileSync(path.join(EXT, '_locales', locale, 'messages.json'), 'utf8'));
}

function createChrome(opts) {
  const options = opts || {};
  const messages = loadMessages(options.locale || 'zh_CN');
  const store = Object.assign({}, options.storage || {});
  const calls = {
    proxySet: [],
    proxyClear: [],
    notifications: [],
    badgeText: [],
    windows: [],
    tabs: [],
    nativeConnects: []
  };
  const listeners = {
    message: [],      // raw registration order
    bg: [],           // listeners registered before markBackgroundLoaded()
    page: [],         // listeners registered after (popup / options pages)
    startup: [],
    installed: [],
    alarm: [],
    notifClicked: [],
    disconnect: []
  };
  let messageTarget = listeners.bg;
  let lastError = null;

  function withCallback(errMessage, fn, arg) {
    lastError = errMessage ? { message: errMessage } : null;
    try {
      if (fn) fn(arg);
    } finally {
      lastError = null;
    }
  }

  function makeEvent(list) {
    return {
      addListener(fn) { list.push(fn); },
      removeListener(fn) {
        const i = list.indexOf(fn);
        if (i >= 0) list.splice(i, 1);
      },
      hasListener(fn) { return list.indexOf(fn) >= 0; }
    };
  }

  const runtime = {
    id: 'lhgepbmchpealfajciakglacaedkjenh',
    get lastError() { return lastError; },
    getManifest() {
      return JSON.parse(fs.readFileSync(path.join(EXT, 'manifest.json'), 'utf8'));
    },
    getURL(p) { return 'chrome-extension://lhgepbmchpealfajciakglacaedkjenh/' + p; },
    connectNative(name) {
      calls.nativeConnects.push(name);
      const port = {
        name,
        _messageListeners: [],
        _disconnectListeners: [],
        onMessage: null,
        onDisconnect: null,
        postMessage(msg) {
          if (port._dead) return;
          const core = port._core;
          if (!core) return;
          core.handle(msg, (resp) => {
            if (port._dead) return;
            port._messageListeners.slice().forEach((fn) => fn(resp, port));
          });
        },
        disconnect() { port._dead = true; }
      };
      port.onMessage = makeEvent(port._messageListeners);
      port.onDisconnect = makeEvent(port._disconnectListeners);
      calls.killPort = (errMessage) => {
        if (port._dead) return;
        port._dead = true;
        withCallback(errMessage || 'Native host has exited.', () => {
          port._disconnectListeners.slice().forEach((fn) => fn(port));
        });
      };
      const core = options.coreFactory ? options.coreFactory(name) : null;
      if (core === 'missing') {
        // Chrome: a missing host disconnects the port immediately with
        // chrome.runtime.lastError set inside the onDisconnect handler.
        setTimeout(() => {
          if (port._dead) return;
          port._dead = true;
          withCallback('Specified native messaging host not found.', () => {
            port._disconnectListeners.slice().forEach((fn) => fn(port));
          });
        }, 5);
        port._dead = false;
        port._core = null;
        return port;
      }
      port._core = core;
      return port;
    },
    sendMessage(msg, cb) {
      // Chrome never delivers a sendMessage back to the sender's own context.
      const pageListeners = listeners.page;
      if (pageListeners.length) {
        pageListeners.slice().forEach((fn) => fn(msg, { id: 'page' }, () => {}));
        if (cb) withCallback(null, cb, undefined);
        return Promise.resolve();
      }
      if (cb) withCallback('Could not establish connection. Receiving end does not exist.', cb, undefined);
      return Promise.resolve();
    },
    openOptionsPage(cb) { if (cb) withCallback(null, cb); },
    onMessage: {
      addListener(fn) { listeners.message.push(fn); messageTarget.push(fn); },
      removeListener(fn) {
        const i = messageTarget.indexOf(fn);
        if (i >= 0) messageTarget.splice(i, 1);
      },
      hasListener(fn) { return messageTarget.indexOf(fn) >= 0; }
    },
    onStartup: makeEvent(listeners.startup),
    onInstalled: makeEvent(listeners.installed),
    onSuspend: makeEvent([])
  };

  const chrome = {
    runtime,
    i18n: {
      getMessage(key, subs) {
        const entry = messages[key];
        if (!entry) return '';
        let msg = entry.message;
        if (subs === undefined) return msg;
        const arr = Array.isArray(subs) ? subs : [subs];
        return msg.replace(/\$(\d)/g, (m, d) => {
          const v = arr[Number(d) - 1];
          return v === undefined ? '' : String(v);
        });
      },
      getUILanguage() { return options.locale || 'zh_CN'; }
    },
    storage: {
      local: {
        get(keys, cb) {
          let out = {};
          if (keys === null || keys === undefined) {
            out = Object.assign({}, store);
          } else if (typeof keys === 'string') {
            if (keys in store) out[keys] = store[keys];
          } else if (Array.isArray(keys)) {
            keys.forEach((k) => { if (k in store) out[k] = store[k]; });
          } else if (typeof keys === 'object') {
            Object.keys(keys).forEach((k) => { out[k] = (k in store) ? store[k] : keys[k]; });
          }
          setTimeout(() => withCallback(null, cb, JSON.parse(JSON.stringify(out))), 0);
        },
        set(items, cb) {
          Object.keys(items).forEach((k) => { store[k] = JSON.parse(JSON.stringify(items[k])); });
          setTimeout(() => withCallback(null, cb), 0);
        },
        remove(keys, cb) {
          const arr = Array.isArray(keys) ? keys : [keys];
          arr.forEach((k) => { delete store[k]; });
          setTimeout(() => withCallback(null, cb), 0);
        }
      }
    },
    proxy: {
      settings: {
        set(details, cb) {
          calls.proxySet.push(JSON.parse(JSON.stringify(details)));
          setTimeout(() => withCallback(null, cb), 0);
        },
        clear(details, cb) {
          calls.proxyClear.push(JSON.parse(JSON.stringify(details)));
          setTimeout(() => withCallback(null, cb), 0);
        }
      }
    },
    alarms: {
      create(name, info) {
        calls.alarms = calls.alarms || [];
        calls.alarms.push({ name, info });
        return Promise.resolve();
      },
      onAlarm: makeEvent(listeners.alarm)
    },
    notifications: {
      create(id, opts, cb) {
        calls.notifications.push({ id, opts });
        if (cb) setTimeout(() => withCallback(null, cb, id), 0);
        return Promise.resolve(id);
      },
      onClicked: makeEvent(listeners.notifClicked)
    },
    action: {
      setBadgeText(details, cb) {
        calls.badgeText.push(details.text);
        if (cb) setTimeout(() => withCallback(null, cb), 0);
      },
      setBadgeBackgroundColor(details, cb) {
        calls.badgeColor = details.color;
        if (cb) setTimeout(() => withCallback(null, cb), 0);
      },
      openPopup(cb) {
        calls.openPopup = (calls.openPopup || 0) + 1;
        if (cb) {
          setTimeout(() => withCallback(options.openPopupFails ? 'openPopup is not allowed' : null, cb), 0);
        }
      }
    },
    windows: {
      create(opts, cb) {
        calls.windows.push(opts);
        if (cb) setTimeout(() => withCallback(null, cb, { id: 1 }), 0);
      }
    },
    tabs: {
      create(opts, cb) {
        calls.tabs.push(opts);
        if (cb) setTimeout(() => withCallback(null, cb, { id: 2 }), 0);
      }
    }
  };

  return { chrome, store, calls, listeners, markBackgroundLoaded() { messageTarget = listeners.page; } };
}

/** Load lib/common.js + background.js into a fresh VM context. */
function loadBackground(api, extraGlobals) {
  const sandbox = Object.assign({
    console,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    queueMicrotask,
    navigator: { userAgent: 'rvpn-harness/1.0' },
    chrome: api.chrome,
    Promise,
    Date,
    Math,
    JSON,
    Map,
    Set
  }, extraGlobals || {});
  const ctx = vm.createContext(sandbox);
  vm.runInContext('globalThis.self = globalThis;', ctx, { filename: 'bootstrap.js' });
  sandbox.__rvpnLoad = (paths) => {
    paths.forEach((p) => {
      const code = fs.readFileSync(path.join(EXT, p), 'utf8');
      vm.runInContext(code, ctx, { filename: p });
    });
  };
  vm.runInContext('globalThis.importScripts = function () { globalThis.__rvpnLoad(Array.prototype.slice.call(arguments)); };',
    ctx, { filename: 'importScripts.js' });
  sandbox.__rvpnLoad(['lib/common.js', 'background.js']);
  if (api.markBackgroundLoaded) api.markBackgroundLoaded();
  return { ctx, sandbox, RVPN: vm.runInContext('RVPN', ctx) };
}

/** Deliver a message to the service worker exactly like chrome.runtime does. */
function bgRequest(api, msg, timeoutMs) {
  return new Promise((resolve, reject) => {
    const list = api.listeners.bg;
    if (!list.length) {
      reject(new Error('background has no onMessage listener'));
      return;
    }
    let done = false;
    const timer = setTimeout(() => {
      if (!done) reject(new Error('no response for ' + JSON.stringify(msg)));
    }, timeoutMs || 15000);
    const sendResponse = (res) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      resolve(res);
    };
    list.slice().forEach((fn) => {
      try {
        fn(msg, { id: 'page' }, sendResponse);
      } catch (e) {
        if (!done) {
          done = true;
          clearTimeout(timer);
          reject(e);
        }
      }
    });
  });
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function waitFor(fn, timeoutMs, label) {
  const deadline = Date.now() + (timeoutMs || 5000);
  for (;;) {
    let v;
    try {
      v = await fn();
    } catch (e) {
      v = false;
    }
    if (v) return v;
    if (Date.now() > deadline) throw new Error('waitFor timed out: ' + (label || fn.toString()));
    await sleep(10);
  }
}

module.exports = { EXT, createChrome, loadBackground, bgRequest, sleep, waitFor, loadMessages };
