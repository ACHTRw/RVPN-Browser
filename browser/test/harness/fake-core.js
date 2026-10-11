'use strict';
/**
 * In-process fake `rvpn-core` implementing docs/PROTOCOL.md v1 exactly:
 * one request -> one response, sequential, `status` returns incremental events.
 */

function createFakeCore(opts) {
  const options = opts || {};
  const core = {
    running: false,
    phase: 'idle',
    events: [],
    seq: 0,
    lastError: '',
    config: null,
    clientIP: '',
    connectedAt: 0,
    socksAddr: '',
    httpAddr: '',
    answered: [],
    requests: [],
    challenges: new Map(),
    failStart: options.failStart || '',
    delay: Object.assign({ hello: 0, status: 0, start: 0 }, options.delay || {}),
    silent: options.silent || [], // commands that never answer
    closed: false
  };

  function push(kind, level, message, challenge) {
    const ev = { seq: (core.seq += 1), ts: Date.now(), kind, level, message };
    if (challenge) ev.challenge = challenge;
    core.events.push(ev);
    return ev;
  }

  core.push = push;

  function state() {
    return {
      phase: core.phase,
      message: core.phase === 'connected'
        ? '已连接 ' + (core.config ? core.config.server + ':' + core.config.port : '')
        : (core.phase === 'connecting' ? '正在连接' : ''),
      since: Date.now(),
      protocol: core.config ? core.config.protocol : '',
      userType: core.config ? core.config.userType : '',
      server: core.config ? core.config.server + ':' + core.config.port : '',
      socksAddr: core.socksAddr,
      httpAddr: core.httpAddr,
      clientIP: core.clientIP,
      connectedAt: core.connectedAt,
      lastError: core.lastError
    };
  }
  core.state = state;

  core.injectChallenge = function injectChallenge(ch) {
    core.challenges.set(ch.id, ch);
    return push('challenge', 'warn', ch.message || 'challenge', ch);
  };

  core.injectLog = function injectLog(level, message) {
    return push('log', level || 'info', message);
  };

  function respond(cb, payload, cmd) {
    const delay = core.delay[cmd] || 0;
    setTimeout(() => cb(payload), delay);
  }

  core.handle = function handle(req, cb) {
    core.requests.push(JSON.parse(JSON.stringify(req)));
    const cmd = req.cmd;
    if (core.silent.indexOf(cmd) !== -1) return;

    switch (cmd) {
      case 'hello':
        return respond(cb, {
          id: req.id,
          ok: true,
          data: {
            version: '1.0.0',
            coreVersion: '1.0.0',
            zjuConnectVersion: 'v1.4.2',
            platform: 'linux',
            arch: 'amd64',
            capabilities: ['atrust', 'easyconnect', 'zju', 'customServer', 'captcha',
              'clickCaptcha', 'sms', 'totp', 'authInfo', 'selftest', 'clientData']
          }
        }, cmd);

      case 'start': {
        if (core.failStart) {
          core.phase = 'error';
          core.lastError = core.failStart;
          return respond(cb, { id: req.id, ok: false, error: core.failStart, code: 'AUTH_FAILED' }, cmd);
        }
        const config = req.config || {};
        if (!config.server) {
          return respond(cb, { id: req.id, ok: false, error: 'server is required', code: 'INVALID_CONFIG' }, cmd);
        }
        core.config = config;
        core.running = true;
        core.phase = 'connecting';
        // PROTOCOL.md 4.1: listeners must be ready before the state is reported.
        core.socksAddr = '127.0.0.1:' + (config.socksBind && config.socksBind.endsWith(':0') ? 38271 : 38271);
        core.httpAddr = config.httpBind ? '127.0.0.1:38272' : '';
        push('log', 'info', 'SOCKS5 监听于 ' + core.socksAddr);
        setTimeout(() => {
          core.phase = 'connected';
          core.clientIP = '10.10.1.23';
          core.connectedAt = Date.now();
          push('state', 'info', '已连接');
          push('log', 'info', '已连接 ' + config.server + ':' + config.port);
        }, options.connectDelay === undefined ? 10 : options.connectDelay);
        return respond(cb, { id: req.id, ok: true, data: { state: state() } }, cmd);
      }

      case 'stop':
        core.running = false;
        core.phase = 'idle';
        core.socksAddr = '';
        core.httpAddr = '';
        core.clientIP = '';
        core.connectedAt = 0;
        core.challenges.clear();
        push('state', 'info', '已停止');
        return respond(cb, { id: req.id, ok: true, data: { state: state() } }, cmd);

      case 'status': {
        const since = Number(req.since) || 0;
        const events = core.events.filter((e) => e.seq > since);
        return respond(cb, {
          id: req.id,
          ok: true,
          data: { state: state(), events, nextSince: core.seq }
        }, cmd);
      }

      case 'answer': {
        const ch = core.challenges.get(req.challengeId);
        if (!ch) {
          return respond(cb, { id: req.id, ok: false, error: 'no such challenge', code: 'NO_SUCH_CHALLENGE' }, cmd);
        }
        core.answered.push(JSON.parse(JSON.stringify(req)));
        core.challenges.delete(req.challengeId);
        setTimeout(() => {
          push('challengeDone', 'info', '验证完成 ' + req.challengeId, { id: req.challengeId, type: ch.type });
        }, 5);
        return respond(cb, { id: req.id, ok: true, data: { accepted: true } }, cmd);
      }

      case 'authInfo':
        return respond(cb, {
          id: req.id,
          ok: true,
          data: {
            list: [
              { loginDomain: 'Radius', authType: 'auth/psw', authName: '上网账号', loginUrl: '' },
              { loginDomain: 'Radius', authType: 'auth/smsCheckCode', authName: '短信认证', loginUrl: '' },
              { loginDomain: 'CAS', authType: 'auth/cas', authName: '统一身份认证', loginUrl: 'https://zjuam.zju.edu.cn' }
            ]
          }
        }, cmd);

      case 'selftest':
        return respond(cb, {
          id: req.id,
          ok: true,
          data: {
            reachable: req.server !== 'unreachable.invalid',
            latencyMs: 18,
            server: req.server + ':' + req.port,
            error: req.server === 'unreachable.invalid' ? 'connection refused' : ''
          }
        }, cmd);

      case 'setDebug':
        core.debug = !!req.debug;
        return respond(cb, { id: req.id, ok: true, data: { debug: core.debug } }, cmd);

      case 'shutdown':
        core.running = false;
        core.closed = true;
        return respond(cb, { id: req.id, ok: true, data: {} }, cmd);

      default:
        return respond(cb, { id: req.id, ok: false, error: 'unknown command: ' + cmd }, cmd);
    }
  };

  return core;
}

module.exports = { createFakeCore };
