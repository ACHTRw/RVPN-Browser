#!/usr/bin/env python3
"""End-to-end checks for rvpn-core that do not need a real ZJU account.

These tests talk to the real ZJU RVPN servers (run them from the campus
network) with intentionally invalid credentials, which is enough to verify:

  * the native messaging transport and framing,
  * the aTrust/EasyConnect protocol handshake up to the authentication step,
  * the interactive challenge relay (captcha / SMS / TOTP),
  * answer delivery and error reporting,
  * start/stop/restart robustness (no leaked listeners).

Usage:  python3 e2e_dummy_login.py [--bin path/to/rvpn-core] [--quick]
"""

import argparse
import base64
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from native_client import CoreClient  # noqa: E402

PASS, FAIL = "\033[32mPASS\033[0m", "\033[31mFAIL\033[0m"
results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print("[%s] %s%s" % (PASS if ok else FAIL, name, ("  -- " + detail) if detail else ""))
    return ok


def wait_for(client, since, predicate, timeout, interval=0.5):
    """Poll status until predicate(state, events) is true; returns (state, events, since)."""
    deadline = time.time() + timeout
    collected = []
    while time.time() < deadline:
        resp = client.request("status", since=since)
        data = resp.get("data", {})
        since = data.get("nextSince", since)
        events = data.get("events", [])
        collected.extend(events)
        state = data.get("state", {})
        if predicate(state, collected):
            return state, collected, since
        time.sleep(interval)
    return None, collected, since


def test_hello(client):
    resp = client.request("hello")
    data = resp.get("data", {})
    check("hello returns capabilities", resp.get("ok") and "atrust" in data.get("capabilities", []),
          "version=%s" % data.get("version"))
    check("hello reports the upstream protocol library", bool(data.get("zjuConnectVersion")),
          data.get("zjuConnectVersion", ""))


def test_unknown_command(client):
    resp = client.request("this-command-does-not-exist")
    check("unknown command is rejected without crashing", resp.get("ok") is False and "unknown command" in resp.get("error", ""),
          resp.get("error", ""))


def test_invalid_config(client):
    resp = client.request("start", config={"userType": "other", "username": "a", "password": "b"})
    check("non-ZJU user without server is rejected", resp.get("ok") is False and resp.get("code") == "INVALID_CONFIG",
          resp.get("error", ""))
    resp = client.request("start", config={"userType": "zju", "username": "a", "password": "b", "socksBind": "0.0.0.0:1080"})
    check("non-loopback proxy bind is rejected", resp.get("ok") is False and resp.get("code") == "INVALID_CONFIG",
          resp.get("error", ""))
    resp = client.request("stop")
    check("stop is idempotent", resp.get("ok") is True)


def test_selftest(client):
    resp = client.request("selftest", server="vpn.zju.edu.cn", port=443)
    data = resp.get("data", {})
    check("selftest reaches vpn.zju.edu.cn", data.get("reachable") is True,
          "latency=%sms" % data.get("latencyMs"))
    resp = client.request("selftest", server="127.0.0.1", port=1)
    check("selftest reports unreachable hosts", resp.get("data", {}).get("reachable") is False)


def test_auth_info(client):
    resp = client.request("authInfo", server="vpn.zju.edu.cn", port=443)
    data = resp.get("data", {})
    ok = resp.get("ok") and isinstance(data.get("list"), list) and len(data["list"]) > 0
    detail = json.dumps(data.get("list", [])[:2], ensure_ascii=False) if ok else resp.get("error", "")
    check("authInfo discovers aTrust login methods", ok, detail)
    return data.get("list", []) if ok else []


def test_challenge_flow(client, protocol):
    """Start a login with wrong credentials and drive the challenge relay."""
    config = {
        "userType": "zju",
        "protocol": protocol,
        "username": "0000000000",
        "password": "definitely-not-the-password",
        "debug": True,
    }
    resp = client.request("start", config=config)
    if not check("start (%s) is accepted" % protocol, resp.get("ok") is True, resp.get("error", "")):
        return

    state, events, since = wait_for(
        client,
        resp.get("data", {}).get("state", {}).get("since", 0),
        lambda st, evs: any(e.get("kind") == "challenge" for e in evs) or st.get("phase") == "error",
        timeout=90,
    )
    challenges = [e for e in events if e.get("kind") == "challenge"]
    if challenges:
        ch = challenges[0]["challenge"]
        image = ch.get("imageDataUrl", "")
        ok_image = image.startswith("data:image/")
        raw = base64.b64decode(image.split(",", 1)[1]) if ok_image else b""
        check("challenge (%s) carries a decodable captcha image" % protocol,
              ok_image and len(raw) > 500,
              "type=%s bytes=%d magic=%s" % (ch.get("type"), len(raw), raw[:4].hex()))

        # Answer with a syntactically valid but wrong response.
        if ch.get("type") == "clickCode":
            answer = {"challengeId": ch["id"], "points": [{"x": 10, "y": 10}], "width": 300, "height": 150}
        else:
            answer = {"challengeId": ch["id"], "code": "0000"}
        resp = client.request("answer", **answer)
        check("answer (%s) is accepted by the core" % protocol, resp.get("ok") is True, resp.get("error", ""))

        state, events, since = wait_for(
            client, since,
            lambda st, evs: st.get("phase") in ("error",) or any(e.get("kind") == "challenge" for e in evs),
            timeout=90,
        )
        # Another challenge (retry) or a clean auth error are both fine: the
        # point is that the answer reached the server and the loop continued.
        progressed = state is not None and (state.get("phase") == "error" or
                                           any(e.get("kind") == "challenge" for e in events))
        check("login (%s) continues after the answered challenge" % protocol, progressed,
              state.get("lastError") if state else "timeout")
    else:
        state = state or {}
        check("login (%s) with invalid credentials fails cleanly" % protocol,
              state.get("phase") == "error" and bool(state.get("lastError")),
              state.get("message", "timeout"))

    resp = client.request("stop")
    state = resp.get("data", {}).get("state", {})
    check("stop (%s) returns to idle" % protocol, resp.get("ok") and state.get("phase") == "idle",
          state.get("phase", ""))


def test_restart_cleans_up(client):
    """A failed run followed by a successful start must not leak listeners."""
    resp = client.request("start", config={"userType": "zju", "protocol": "atrust",
                                           "username": "0000000000", "password": "x-invalid-x",
                                           "socksBind": "127.0.0.1:0", "httpBind": "127.0.0.1:0"})
    ok = check("restart: first start accepted", resp.get("ok") is True, resp.get("error", ""))
    since = resp.get("data", {}).get("state", {}).get("since", 0)
    wait_for(client, since, lambda st, evs: st.get("phase") == "error" or any(e.get("kind") == "challenge" for e in evs),
             timeout=60)
    client.request("stop")
    resp = client.request("start", config={"userType": "zju", "protocol": "atrust",
                                           "username": "0000000000", "password": "x-invalid-x"})
    check("restart: second start accepted", resp.get("ok") is True, resp.get("error", ""))
    resp = client.request("stop")
    check("restart: second stop returns to idle", resp.get("data", {}).get("state", {}).get("phase") == "idle")
    return ok


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bin", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bin", "rvpn-core"))
    parser.add_argument("--protocols", default="atrust,easyconnect")
    args = parser.parse_args()

    client = CoreClient(args.bin)
    try:
        test_hello(client)
        test_unknown_command(client)
        test_invalid_config(client)
        test_selftest(client)
        auth_list = test_auth_info(client)
        for protocol in [p for p in args.protocols.split(",") if p]:
            test_challenge_flow(client, protocol)
        test_restart_cleans_up(client)
    finally:
        client.close()

    failed = [name for name, ok, _ in results if not ok]
    print("\n%d/%d checks passed" % (len(results) - len(failed), len(results)))
    if failed:
        print("failed: " + ", ".join(failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
