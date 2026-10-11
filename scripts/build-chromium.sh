#!/usr/bin/env bash
# End-to-end Chromium/Thorium build for RVPN Browser.
#
# Steps (each one is idempotent and can be re-run):
#   1. gclient sync      - fetch/refresh Chromium dependencies
#   2. version.py        - pin Thorium's Chromium revision, run hooks (toolchain)
#   3. setup.py          - apply the Thorium patch series and overlays
#   4. apply-rvpn-patches.py - RVPN branding, built-in extension, Android hooks
#   5. gn gen + autoninja    - build the browser
#
# Usage:
#   scripts/build-chromium.sh                     # Linux x64, low-cost args
#   RVPN_PLATFORMS=linux,android scripts/build-chromium.sh --android
#   scripts/build-chromium.sh --skip-sync         # source tree already in sync
#
# Everything is logged to $LOG (default /tmp/rvpn-chromium-build.log).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

export THOR_DIR="${THOR_DIR:-$HOME/src/thorium}"
export CR_DIR="${CR_DIR:-$HOME/chromium/src}"
export DEPOT_TOOLS_DIR="${DEPOT_TOOLS_DIR:-$HOME/depot_tools}"
export RVPN_PLATFORMS="${RVPN_PLATFORMS:-linux}"
LOG="${LOG:-/tmp/rvpn-chromium-build.log}"

# Chromium's tooling needs the proxy in most Chinese networks; override with
# RVPN_NO_PROXY=1 when a direct connection works.
if [ "${RVPN_NO_PROXY:-0}" != "1" ]; then
  export http_proxy="${http_proxy:-http://172.18.112.1:7897}"
  export https_proxy="${https_proxy:-http://172.18.112.1:7897}"
  export no_proxy="${no_proxy:-localhost,127.0.0.1,::1,.zju.edu.cn,172.18.112.1}"
fi
export PATH="$DEPOT_TOOLS_DIR:$HOME/go/bin:/usr/local/go/bin:$PATH"
export GIT_TERMINAL_PROMPT=0
export DEPOT_TOOLS_UPDATE="${DEPOT_TOOLS_UPDATE:-0}"

SKIP_SYNC=0
BUILD_ANDROID=0
for arg in "$@"; do
  case "$arg" in
    --skip-sync) SKIP_SYNC=1 ;;
    --android) BUILD_ANDROID=1 ;;
    --help|-h) sed -n '2,20p' "$0"; exit 0 ;;
  esac
done

log() { echo "[$(date -Is)] $*" | tee -a "$LOG"; }
fail() { log "FAILED: $*"; exit 1; }

log "=== RVPN Browser Chromium build pipeline ==="
log "thorium=$THOR_DIR chromium=$CR_DIR platforms=$RVPN_PLATFORMS android=$BUILD_ANDROID"

[ -d "$CR_DIR" ] || fail "Chromium source missing at $CR_DIR"
[ -d "$THOR_DIR" ] || fail "Thorium checkout missing at $THOR_DIR"

# 1. dependencies ------------------------------------------------------------
if [ "$SKIP_SYNC" = "0" ]; then
  log "--- [1/5] gclient sync"
  ( cd "$CR_DIR" && gclient sync --nohooks --no-history -D --force -j2 ) >>"$LOG" 2>&1 \
    || fail "gclient sync"
else
  log "--- [1/5] gclient sync skipped"
fi

# 2. pinned revision + hooks (downloads the toolchain) -----------------------
log "--- [2/5] version.py (pin revision, sync, runhooks)"
( cd "$THOR_DIR" && python3 version.py ) >>"$LOG" 2>&1 || fail "version.py"

# 3. Thorium patches ---------------------------------------------------------
log "--- [3/5] setup.py (Thorium patch series)"
( cd "$THOR_DIR" && python3 setup.py ) >>"$LOG" 2>&1 || fail "setup.py"

# 4. RVPN patches ------------------------------------------------------------
log "--- [4/5] apply-rvpn-patches.py"
PATCH_ARGS=(--chromium-src "$CR_DIR" --thorium-root "$THOR_DIR")
[ "$BUILD_ANDROID" = "1" ] && PATCH_ARGS+=(--android)
python3 "$ROOT/browser/scripts/apply-rvpn-patches.py" "${PATCH_ARGS[@]}" >>"$LOG" 2>&1 \
  || fail "apply-rvpn-patches.py"

# 5. build -------------------------------------------------------------------
if [ "$BUILD_ANDROID" = "1" ]; then
  log "--- [5/5] build android chrome_public_apk"
  CR_DIR="$CR_DIR" "$ROOT/browser/scripts/build-android.sh" >>"$LOG" 2>&1 || fail "android build"
else
  log "--- [5/5] build linux chrome"
  CR_DIR="$CR_DIR" "$ROOT/browser/scripts/build-linux.sh" --low-cost >>"$LOG" 2>&1 || fail "linux build"
fi

log "=== pipeline complete ==="
log "log: $LOG"
