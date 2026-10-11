#!/usr/bin/env python3
"""Mock Chrome native messaging host for RVPN Browser integration tests.

It speaks the same protocol as rvpn-core (docs/PROTOCOL.md) but fakes the VPN:
on `start` it opens a real SOCKS5 listener that answers every CONNECT with a
canned HTTP response, so a test can prove that the browser really sends its
traffic through the proxy the extension configured - without needing ZJU
credentials or a working tunnel.

Every accepted SOCKS5 target is appended to $RVPN_MOCK_LOG as one JSON line.

Environment:
  RVPN_MOCK_LOG        path of the connection log (default: /tmp/rvpn-mock.log)
  RVPN_MOCK_CHALLENGE  "1" to emit a click-captcha challenge on start
  RVPN_MOCK_FAIL       "1" to make start fail with AUTH_FAILED
"""

from __future__ import annotations

import json
import os
import socket
import struct
import sys
import threading
import time

LOG_PATH = os.environ.get("RVPN_MOCK_LOG", "/tmp/rvpn-mock.log")
EMIT_CHALLENGE = os.environ.get("RVPN_MOCK_CHALLENGE", "") == "1"
FAIL_START = os.environ.get("RVPN_MOCK_FAIL", "") == "1"

CANNED_BODY = b"<html><body><h1>RVPN-MOCK-OK</h1></body></html>"
CANNED_RESPONSE = (
    b"HTTP/1.1 200 OK\r\n"
    b"Content-Type: text/html; charset=utf-8\r\n"
    b"Content-Length: " + str(len(CANNED_BODY)).encode() + b"\r\n"
    b"Connection: close\r\n\r\n" + CANNED_BODY
)

state_lock = threading.Lock()
state = {"phase": "idle", "message": "", "since": int(time.time() * 1000),
         "protocol": "atrust", "userType": "zju", "server": "", "socksAddr": "",
         "httpAddr": "", "clientIP": "", "connectedAt": 0, "lastError": ""}
events: list[dict] = []
seq = 0
socks_server: socket.socket | None = None
socks_thread: threading.Thread | None = None
log_lock = threading.Lock()


def log_connection(target: str) -> None:
    with log_lock:
        with open(LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"ts": time.time(), "target": target}) + "\n")


def publish(kind: str, level: str, message: str, challenge: dict | None = None) -> None:
    global seq
    seq += 1
    event = {"seq": seq, "ts": int(time.time() * 1000), "kind": kind,
             "level": level, "message": message}
    if challenge:
        event["challenge"] = challenge
    events.append(event)
    if len(events) > 500:
        del events[:100]


def set_phase(phase: str, message: str) -> None:
    with state_lock:
        state["phase"] = phase
        state["message"] = message
        state["since"] = int(time.time() * 1000)
    publish("state", "info", message)


def handle_socks_client(conn: socket.socket) -> None:
    try:
        conn.settimeout(15)
        header = conn.recv(2)
        if len(header) < 2 or header[0] != 5:
            return
        nmethods = header[1]
        conn.recv(nmethods)
        conn.sendall(b"\x05\x00")  # no auth

        req = conn.recv(4)
        if len(req) < 4:
            return
        _, cmd, _, atyp = req
        if atyp == 1:
            host = socket.inet_ntoa(conn.recv(4))
        elif atyp == 3:
            length = conn.recv(1)[0]
            host = conn.recv(length).decode("utf-8", "replace")
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, conn.recv(16))
        else:
            return
        port = struct.unpack("!H", conn.recv(2))[0]
        log_connection(f"{host}:{port}")

        if cmd != 1:  # only CONNECT is supported
            conn.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
            return
        conn.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")

        # Serve a canned HTTP response so the test needs no real destination.
        conn.settimeout(10)
        try:
            conn.recv(65536)
        except socket.timeout:
            pass
        conn.sendall(CANNED_RESPONSE)
    except Exception:
        pass
    finally:
        try:
            conn.close()
        except Exception:
            pass


def socks_accept_loop(server: socket.socket) -> None:
    while True:
        try:
            conn, _ = server.accept()
        except OSError:
            return
        threading.Thread(target=handle_socks_client, args=(conn,), daemon=True).start()


def start_socks() -> str:
    global socks_server, socks_thread
    if socks_server is None:
        socks_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        socks_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        socks_server.bind(("127.0.0.1", 0))
        socks_server.listen(16)
        socks_thread = threading.Thread(target=socks_accept_loop, args=(socks_server,), daemon=True)
        socks_thread.start()
    host, port = socks_server.getsockname()
    return f"{host}:{port}"


def stop_socks() -> None:
    global socks_server, socks_thread
    if socks_server is not None:
        try:
            socks_server.close()
        except Exception:
            pass
        socks_server = None
    socks_thread = None


def handle(req: dict) -> dict:
    req_id = req.get("id")
    cmd = (req.get("cmd") or "").lower()

    def reply(ok: bool, data=None, error: str = "", code: str = "") -> dict:
        out = {"id": req_id, "ok": ok}
        if data is not None:
            out["data"] = data
        if error:
            out["error"] = error
        if code:
            out["code"] = code
        return out

    if cmd == "hello":
        return reply(True, {"version": "mock", "coreVersion": "mock", "zjuConnectVersion": "mock",
                            "platform": "linux", "arch": "amd64",
                            "capabilities": ["atrust", "easyconnect", "zju", "customServer",
                                             "captcha", "clickCaptcha", "sms", "totp",
                                             "authInfo", "selftest", "clientData"]})

    if cmd == "start":
        if FAIL_START:
            set_phase("error", "mock authentication failure")
            with state_lock:
                state["lastError"] = "mock authentication failure"
            publish("error", "error", "mock authentication failure")
            return reply(True, {"state": dict(state)})
        cfg = req.get("config") or {}
        addr = start_socks()
        with state_lock:
            state.update({"protocol": cfg.get("protocol", "atrust"),
                          "userType": cfg.get("userType", "zju"),
                          "server": cfg.get("server", "mock.server:443"),
                          "socksAddr": addr, "httpAddr": addr,
                          "clientIP": "10.10.0.99",
                          "connectedAt": int(time.time() * 1000),
                          "lastError": ""})
        set_phase("connected", "mock connected")
        publish("log", "info", f"mock SOCKS5 listening on {addr}")
        if EMIT_CHALLENGE:
            publish("challenge", "info", "mock captcha", {
                "id": "mock-c1", "type": "text", "message": "mock captcha",
                "imageDataUrl": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
                "deadline": int(time.time() * 1000) + 60000,
            })
        return reply(True, {"state": dict(state)})

    if cmd == "stop":
        stop_socks()
        with state_lock:
            state.update({"phase": "idle", "message": "", "socksAddr": "", "httpAddr": "",
                          "clientIP": "", "connectedAt": 0})
            state["since"] = int(time.time() * 1000)
        publish("state", "info", "mock stopped")
        return reply(True, {"state": dict(state)})

    if cmd == "status":
        since = int(req.get("since") or 0)
        new_events = [e for e in events if e["seq"] > since]
        with state_lock:
            snapshot = dict(state)
        return reply(True, {"state": snapshot, "events": new_events[-200:],
                            "nextSince": seq, "pending": [], "running": snapshot["phase"] == "connected",
                            "capabilities": ["atrust"]})

    if cmd == "answer":
        publish("challengeDone", "info", "mock answer received")
        return reply(True, {"accepted": True})

    if cmd in ("authinfo", "auth_info"):
        return reply(True, {"list": [{"loginDomain": "Radius", "authType": "auth/psw",
                                      "authName": "mock account", "loginUrl": ""}]})

    if cmd == "selftest":
        return reply(True, {"reachable": True, "latencyMs": 1, "server": "mock", "error": ""})

    if cmd == "setdebug":
        return reply(True, {"debug": bool(req.get("debug"))})

    if cmd == "shutdown":
        stop_socks()
        return reply(True, {})

    return reply(False, error=f"unknown command: {cmd}", code="INVALID_CONFIG")


def read_message(stream) -> bytes | None:
    header = stream.read(4)
    if len(header) < 4:
        return None
    (length,) = struct.unpack("<I", header)
    if length == 0:
        return b""
    return stream.read(length)


def write_message(stream, payload: bytes) -> None:
    stream.write(struct.pack("<I", len(payload)) + payload)
    stream.flush()


def main() -> int:
    inp = sys.stdin.buffer
    out = sys.stdout.buffer
    while True:
        payload = read_message(inp)
        if payload is None:
            break
        if not payload:
            continue
        try:
            request = json.loads(payload)
        except Exception:
            write_message(out, json.dumps({"ok": False, "error": "bad json",
                                           "code": "INVALID_CONFIG"}).encode())
            continue
        response = handle(request)
        write_message(out, json.dumps(response).encode())
        if (request.get("cmd") or "").lower() == "shutdown":
            break
    stop_socks()
    return 0


if __name__ == "__main__":
    sys.exit(main())
