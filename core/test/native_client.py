#!/usr/bin/env python3
"""Minimal Chrome-native-messaging client for rvpn-core.

Used by the test suite and for manual diagnostics:

    ./native_client.py --cmd hello
    ./native_client.py --cmd start --config '{"userType":"zju","username":"x","password":"y"}' --watch 20
"""

import argparse
import json
import os
import struct
import subprocess
import sys
import threading
import time

DEFAULT_BIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bin", "rvpn-core")


class CoreClient:
    def __init__(self, binary=DEFAULT_BIN, extra_args=()):
        self.proc = subprocess.Popen(
            [binary, *extra_args],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.next_id = 1
        self.lock = threading.Lock()
        self.stderr_lines = []
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            self.stderr_lines.append(line.decode("utf-8", "replace").rstrip())

    def request(self, cmd, **kwargs):
        with self.lock:
            req_id = self.next_id
            self.next_id += 1
            payload = json.dumps({"id": req_id, "cmd": cmd, **kwargs}).encode()
            self.proc.stdin.write(struct.pack("<I", len(payload)) + payload)
            self.proc.stdin.flush()
            header = self._read_exactly(4)
            if not header:
                raise RuntimeError("core closed stdout (stderr: %s)" % "\n".join(self.stderr_lines[-5:]))
            (length,) = struct.unpack("<I", header)
            body = self._read_exactly(length)
            return json.loads(body)

    def _read_exactly(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.proc.stdout.read(n - len(buf))
            if not chunk:
                return None if not buf else buf
            buf += chunk
        return buf

    def close(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass
        try:
            self.proc.wait(timeout=10)
        except Exception:
            self.proc.kill()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bin", default=DEFAULT_BIN)
    parser.add_argument("--cmd", default="hello")
    parser.add_argument("--config", default=None, help="JSON config for start")
    parser.add_argument("--watch", type=float, default=0, help="poll status for N seconds")
    parser.add_argument("--interval", type=float, default=0.5)
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()

    client = CoreClient(args.bin)
    try:
        if args.cmd == "start":
            config = json.loads(args.config) if args.config else {}
            print("START ->", json.dumps(client.request("start", config=config), ensure_ascii=False))
        elif args.cmd != "hello":
            print(json.dumps(client.request(args.cmd), ensure_ascii=False))
        else:
            print("HELLO ->", json.dumps(client.request("hello"), ensure_ascii=False))

        if args.watch:
            since = 0
            deadline = time.time() + args.watch
            while time.time() < deadline:
                resp = client.request("status", since=since)
                data = resp.get("data", {})
                since = data.get("nextSince", since)
                for ev in data.get("events", []):
                    ch = ev.get("challenge") or {}
                    extra = ""
                    if ch:
                        img = ch.get("imageDataUrl") or ""
                        extra = " challenge=%s type=%s image=%dB" % (ch.get("id"), ch.get("type"), len(img))
                    print("[%s] %-8s %s%s" % (ev.get("level"), ev.get("kind"), ev.get("message"), extra))
                state = data.get("state", {})
                if state.get("phase") in ("error",) and not data.get("events"):
                    break
                time.sleep(args.interval)
            resp = client.request("status", since=since)
            print("FINAL STATE ->", json.dumps(resp.get("data", {}).get("state", {}), ensure_ascii=False))
            print("STDERR:", "\n".join(client.stderr_lines[-10:]))
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
