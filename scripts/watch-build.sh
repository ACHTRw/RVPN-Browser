#!/usr/bin/env bash
# Wait for a running Chromium build to finish, then verify the result.
#
#   scripts/watch-build.sh [--log /tmp/rvpn-chromium-build.log] [--out out/rvpn-browser]
#
# Writes a report to /tmp/rvpn-build-result.txt so the outcome can be inspected
# later without keeping a terminal open:
#
#   BUILD_OK   - the browser binary exists and passed verification
#   BUILD_FAIL - the build stopped with errors (tail of the log is included)
#   BUILD_LOST - no build process and no binary were found
#
# Run it in the background while `autoninja` works.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CR_DIR="${CR_DIR:-$HOME/chromium/src}"
BUILD_LOG="${BUILD_LOG:-/tmp/rvpn-chromium-build.log}"
OUT_DIR="${OUT_DIR:-out/rvpn-browser}"
REPORT="/tmp/rvpn-build-result.txt"
RESULT_LOG="/tmp/rvpn-built-browser-test.log"

while [ $# -gt 0 ]; do
  case "$1" in
    --log) BUILD_LOG="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

BINARY="$CR_DIR/$OUT_DIR/chrome"
say() { echo "[$(date -Is)] $*" | tee -a "$REPORT"; }

say "watching $BUILD_LOG (binary target: $BINARY)"

# Wait for the compiler to disappear *and* stay gone (ninja restarts phases).
while true; do
  if ! pgrep -f "autoninja -C $OUT_DIR" >/dev/null 2>&1 && ! pgrep -f "siso.*$OUT_DIR" >/dev/null 2>&1; then
    sleep 20
    if ! pgrep -f "autoninja -C $OUT_DIR" >/dev/null 2>&1 && ! pgrep -f "siso.*$OUT_DIR" >/dev/null 2>&1; then
      break
    fi
  fi
  sleep 30
done

say "build process finished"

# Ninja prints failures as a bare "FAILED: <target>" line; the pipeline's own
# progress lines are prefixed with a timestamp, so they must not match here.
if grep -qE "^(FAILED: |ninja: build stopped)" "$BUILD_LOG" 2>/dev/null; then
  say "BUILD_FAIL - errors in the build log:"
  grep -E "^(FAILED: |ninja: build stopped)|error:" "$BUILD_LOG" | tail -15 | tee -a "$REPORT"
  exit 1
fi

if [ ! -x "$BINARY" ]; then
  say "BUILD_LOST - no binary at $BINARY"
  tail -5 "$BUILD_LOG" | tee -a "$REPORT"
  exit 1
fi

say "binary present: $(ls -la "$BINARY" | awk '{print $5" bytes"}')"
say "version: $("$BINARY" --version 2>&1 | head -1)"

NODE_PATH_GUESS=""
for candidate in "$ROOT/node_modules" "$HOME/src/pwtest/node_modules" "/usr/lib/node_modules"; do
  if [ -d "$candidate/playwright-core" ]; then NODE_PATH_GUESS="$candidate"; break; fi
done

if [ -n "$NODE_PATH_GUESS" ]; then
  say "running built-browser verification"
  if NODE_PATH="$NODE_PATH_GUESS" node "$ROOT/browser/test/built_browser_test.mjs" \
        --chromium "$BINARY" >"$RESULT_LOG" 2>&1; then
    say "BUILD_OK - verification passed"
    tail -8 "$RESULT_LOG" | tee -a "$REPORT"
  else
    say "BUILD_OK_BUT_VERIFY_FAILED - see $RESULT_LOG"
    tail -12 "$RESULT_LOG" | tee -a "$REPORT"
  fi
else
  say "BUILD_OK - playwright-core missing, verification skipped"
fi
