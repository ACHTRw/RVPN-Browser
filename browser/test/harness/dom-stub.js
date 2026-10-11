'use strict';
/**
 * Very small DOM stub, just big enough to run popup.js / options.js outside a
 * browser and observe what they render and which messages they send.
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const EXT = process.env.RVPN_EXTENSION_DIR || path.resolve(__dirname, '..', '..', 'extension');

class ClassList {
  constructor(el) { this.el = el; this.set = new Set(); }
  add(...c) { c.forEach((x) => this.set.add(x)); this.sync(); }
  remove(...c) { c.forEach((x) => this.set.delete(x)); this.sync(); }
  contains(c) { return this.set.has(c); }
  toggle(c, force) {
    const on = force === undefined ? !this.set.has(c) : !!force;
    if (on) this.set.add(c); else this.set.delete(c);
    this.sync();
    return on;
  }
  sync() { this.el._className = Array.from(this.set).join(' '); }
  static fromString(el, str) {
    const cl = new ClassList(el);
    String(str || '').split(/\s+/).filter(Boolean).forEach((c) => cl.set.add(c));
    cl.sync();
    return cl;
  }
}

const ctxStub = {
  clearRect() {}, drawImage() {}, beginPath() {}, arc() {}, stroke() {},
  fill() {}, fillText() {}, closePath() {}, moveTo() {}, lineTo() {},
  set strokeStyle(v) { this._ss = v; }, get strokeStyle() { return this._ss; },
  set fillStyle(v) { this._fs = v; }, get fillStyle() { return this._fs; },
  set lineWidth(v) { this._lw = v; }, get lineWidth() { return this._lw; },
  set font(v) { this._f = v; }, get font() { return this._f; }
};

class El {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase();
    this.nodeType = 1;
    this.children = [];
    this.parentNode = null;
    this.dataset = {};
    this.style = {};
    this._listeners = {};
    this._text = '';
    this._className = '';
    this.classList = new ClassList(this);
    this.value = '';
    this.checked = false;
    this.disabled = false;
    this.placeholder = '';
    this.type = '';
    this.width = 0;
    this.height = 0;
    this._rect = { left: 0, top: 0, width: 100, height: 50 };
  }
  get className() { return this._className; }
  set className(v) { this.classList = ClassList.fromString(this, v); }
  get textContent() {
    if (!this.children.length) return this._text;
    return this._text + this.children.map((c) => (c && c.textContent) || '').join('');
  }
  set textContent(v) { this._text = v === undefined || v === null ? '' : String(v); this.children = []; }
  get childElementCount() { return this.children.length; }
  appendChild(child) {
    if (child && child._isFragment) {
      child.children.forEach((c) => this.appendChild(c));
      return child;
    }
    this.children.push(child);
    if (child) child.parentNode = this;
    return child;
  }
  removeChild(child) {
    const i = this.children.indexOf(child);
    if (i >= 0) this.children.splice(i, 1);
    if (child) child.parentNode = null;
    return child;
  }
  insertBefore(child) { return this.appendChild(child); }
  querySelector() { return null; }
  querySelectorAll() { return []; }
  addEventListener(type, fn) {
    if (!this._listeners[type]) this._listeners[type] = [];
    this._listeners[type].push(fn);
  }
  dispatch(type, ev) {
    const list = this._listeners[type] || [];
    list.slice().forEach((fn) => fn(Object.assign({ type, target: this, preventDefault() {} }, ev || {})));
  }
  setAttribute(k, v) { this[k] = v; }
  getAttribute(k) { return this[k]; }
  focus() {}
  select() {}
  scrollIntoView() {}
  closest() { return null; }
  getBoundingClientRect() { return this._rect; }
  getContext() { return ctxStub; }
  get firstChild() { return this.children[0] || null; }
}

class Fragment extends El {
  constructor() { super('#fragment'); this._isFragment = true; }
}

class ImageStub {
  constructor() {
    this.naturalWidth = 300;
    this.naturalHeight = 150;
    this.onload = null;
    this.onerror = null;
  }
  set src(v) {
    this._src = v;
    setTimeout(() => { if (this.onload) this.onload(); }, 0);
  }
  get src() { return this._src; }
}

/** Build a document stub whose ids come from the real HTML file. */
function createDocument(htmlPath) {
  const html = fs.readFileSync(htmlPath, 'utf8');
  const ids = Array.from(new Set(Array.from(html.matchAll(/id="([A-Za-z0-9_-]+)"/g)).map((m) => m[1])));
  const byId = new Map();
  ids.forEach((id) => {
    const el = new El(id.indexOf('field-') === 0 ? 'div' : 'div');
    el.id = id;
    byId.set(id, el);
  });
  const body = new El('body');
  const document = {
    readyState: 'complete',
    body,
    activeElement: null,
    _byId: byId,
    getElementById(id) { return byId.get(id) || null; },
    createElement(tag) { return new El(tag); },
    createDocumentFragment() { return new Fragment(); },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    addEventListener() {},
    execCommand() { return true; }
  };
  return document;
}

/** Load popup.js / options.js in a VM with the stubs and a scripted worker. */
function loadPage(page, options) {
  const opts = options || {};
  const document = createDocument(path.join(EXT, page + '.html'));
  const sent = [];
  const pageListeners = [];
  const timers = [];
  const chrome = {
    runtime: {
      lastError: null,
      getURL(p) { return 'chrome-extension://lhgepbmchpealfajciakglacaedkjenh/' + p; },
      getManifest() { return { name: 'RVPN Browser', version: '1.0.0', default_locale: 'zh_CN' }; },
      sendMessage(msg, cb) {
        sent.push(msg);
        const respond = opts.respond || (() => ({ ok: true }));
        setTimeout(() => {
          const res = respond(msg);
          if (cb) cb(res === undefined ? { ok: true } : res);
        }, 0);
        return Promise.resolve();
      },
      onMessage: {
        addListener(fn) { pageListeners.push(fn); },
        removeListener() {}
      },
      openOptionsPage(cb) { if (cb) cb(); }
    },
    i18n: {
      getMessage(key, subs) {
        const table = opts.messages || {};
        const entry = table[key];
        if (!entry) return '';
        let msg = entry.message;
        if (subs === undefined) return msg;
        const arr = Array.isArray(subs) ? subs : [subs];
        return msg.replace(/\$(\d)/g, (m, d) => {
          const v = arr[Number(d) - 1];
          return v === undefined ? '' : String(v);
        });
      }
    },
    tabs: { create(o, cb) { if (cb) cb({}); } }
  };

  const sandbox = {
    console,
    chrome,
    document,
    navigator: { clipboard: { writeText: () => Promise.resolve() }, userAgent: 'rvpn-harness' },
    location: { hash: opts.hash || '' },
    setTimeout, clearTimeout,
    setInterval(fn, ms) { timers.push({ fn, ms }); return timers.length; },
    clearInterval() {},
    addEventListener() {},
    removeEventListener() {},
    Promise, Date, Math, JSON, Map, Set, Image: ImageStub
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;
  const ctx = vm.createContext(sandbox);
  const load = (p) => vm.runInContext(fs.readFileSync(path.join(EXT, p), 'utf8'), ctx, { filename: p });
  load('lib/common.js');
  load(page + '.js');
  return {
    document, chrome, sent, pageListeners, timers, sandbox,
    RVPN: vm.runInContext('RVPN', ctx),
    /** wait for the page's async work to settle */
    settle: () => new Promise((r) => setTimeout(r, 30)),
    pushUpdate(msg) { pageListeners.slice().forEach((fn) => fn(msg, { id: 'bg' }, () => {})); },
    tick() { timers.slice().forEach((t) => t.fn()); }
  };
}

function findByTag(root, tag) {
  const want = tag.toUpperCase();
  const out = [];
  const walk = (n) => {
    if (!n || !n.children) return;
    n.children.forEach((c) => {
      if (c.tagName === want) out.push(c);
      walk(c);
    });
  };
  walk(root);
  return out;
}

function findByClass(root, cls) {
  const out = [];
  const walk = (n) => {
    if (!n || !n.children) return;
    n.children.forEach((c) => {
      if (c.classList && c.classList.contains(cls)) out.push(c);
      walk(c);
    });
  };
  walk(root);
  return out;
}

module.exports = { loadPage, createDocument, findByTag, findByClass, El };
