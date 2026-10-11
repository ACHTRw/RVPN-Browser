#!/usr/bin/env python3
"""Restart-leak stress test for rvpn-core.

Repeatedly starts and stops sessions (against the real ZJU server, with
intentionally invalid credentials so no account is needed) and watches the
process for leaked file descriptors, threads and memory. It also stops a
session while an interactive challenge is pending, which used to block the
protocol goroutine until the 5 minute timeout.

Usage: python3 stress_restart.py [--cycles 6] [--bin path/to/rvpn-core]
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from native_client import CoreClient  # noqa: E402

PASS, FAIL = "\033[32mPASS\033[0m", "\033[31mFAIL\033[0m"


def proc_stats(pid):
    stats = {"fds": 0, "threads": 0, "rss_kb": 0}
    try:
        stats["fds"] = len(os.listdir("/proc/%d/fd" % pid))
        with open("/proc/%d/status" % pid) as fh:
            for line in fh:
                if line.startswith("Threads:"):
                    stats["threads"] = int(line.split()[1])
                elif line.startswith("VmRSS:"):
                    stats["rss_kb"] = int(line.split()[1])
    except (FileNotFoundError, ProcessLookupError):
        pass
    return stats


def wait_phase(client, since, phases, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        data = client.request("status", since=since).get("data", {})
        since = data.get("nextSince", since)
        state = data.get("state", {})
        events = data.get("events", [])
        if state.get("phase") in phases:
            return state, events, since
        if any(e.get("kind") == "challenge" for e in events):
            return state, events, since
        time.sleep(0.4)
    return None, [], since


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cycles", type=int, default=6)
    parser.add_argument("--bin", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bin", "rvpn-core"))
    parser.add_argument("--protocol", default="atrust")
    args = parser.parse_args()

    client = CoreClient(args.bin)
    pid = client.proc.pid
    failures = []
    samples = []

    try:
        baseline = proc_stats(pid)
        print("baseline: %s" % baseline)

        for cycle in range(args.cycles):
            config = {
                "userType": "zju", "protocol": args.protocol,
                "username": "0000000000", "password": "stress-test-invalid",
                "socksBind": "127.0.0.1:0", "httpBind": "127.0.0.1:0",
            }
            resp = client.request("start", config=config)
            if not resp.get("ok"):
                failures.append("cycle %d: start rejected: %s" % (cycle, resp.get("error")))
                continue
            since = resp.get("data", {}).get("state", {}).get("since", 0)

            # Stop immediately on odd cycles (challenge possibly pending) and
            # wait for the auth result on even ones.
            if cycle % 2 == 1:
                time.sleep(1.0)
            else:
                state, _, _ = wait_phase(client, since, ("error",), 45)

            t0 = time.time()
            client.request("stop")
            stop_seconds = time.time() - t0
            if stop_seconds > 12:
                failures.append("cycle %d: stop took %.1fs" % (cycle, stop_seconds))

            snapshot = proc_stats(pid)
            samples.append(snapshot)
            print("cycle %d: stop=%.2fs fds=%d threads=%d rss=%dMB"
                  % (cycle, stop_seconds, snapshot["fds"], snapshot["threads"], snapshot["rss_kb"] // 1024))

            # The core must still answer after the restart cycle.
            status = client.request("status", since=0).get("data", {})
            if status.get("state", {}).get("phase") != "idle":
                failures.append("cycle %d: not idle after stop (%s)" % (cycle, status.get("state", {}).get("phase")))

        # A start must still work after all those cycles.
        resp = client.request("start", config={
            "userType": "zju", "protocol": args.protocol,
            "username": "0000000000", "password": "stress-test-invalid"})
        if not resp.get("ok"):
            failures.append("final start after %d cycles failed: %s" % (args.cycles, resp.get("error")))
        client.request("stop")

        final = proc_stats(pid)
        samples.append(final)
        fd_growth = final["fds"] - baseline["fds"]
        thread_growth = final["threads"] - baseline["threads"]
        rss_growth = final["rss_kb"] - baseline["rss_kb"]

        print()
        print("final: %s" % final)
        print("growth: fds %+d, threads %+d, rss %+dMB" % (fd_growth, thread_growth, rss_growth // 1024))

        ok_fd = fd_growth <= 8
        ok_threads = thread_growth <= 12
        ok_rss = rss_growth <= 200 * 1024
        print("[%s] file descriptors stay bounded" % (PASS if ok_fd else FAIL))
        print("[%s] thread count stays bounded" % (PASS if ok_threads else FAIL))
        print("[%s] memory usage stays bounded" % (PASS if ok_rss else FAIL))
        if not (ok_fd and ok_threads and ok_rss):
            failures.append("resource growth too large")

        # Leaked listening sockets would show up as extra fds; check the
        # process is not holding ports after the final stop.
        time.sleep(1.0)
        idle_fds = proc_stats(pid)["fds"]
        print("[%s] no listening sockets left after stop (fds=%d)" % (PASS if idle_fds <= baseline["fds"] + 8 else FAIL, idle_fds))
        if idle_fds > baseline["fds"] + 8:
            failures.append("sockets leaked after stop")
    finally:
        client.close()

    print()
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        return 1
    print("%d restart cycles completed without leaks" % args.cycles)
    return 0


if __name__ == "__main__":
    sys.exit(main())
