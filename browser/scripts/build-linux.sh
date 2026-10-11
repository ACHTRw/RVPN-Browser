#!/usr/bin/env bash
# Build the Linux RVPN Browser from a prepared Chromium/Thorium tree.
#
# Usage:
#   browser/scripts/build-linux.sh [--chromium-src DIR] [--out DIR] [--jobs N] [--low-cost|--thorium-args]
#
# --low-cost (default)  : no PGO/ThinLTO/CFI, symbol_level=0 -> much faster on
#                         small machines, fully functional browser.
# --thorium-args        : use Thorium's official args.gn (PGO + ThinLTO + CFI);
#                         several times slower and much more memory hungry.

set -euo pipefail

CR_DIR="${CR_DIR:-$HOME/chromium/src}"
OUT_DIR="out/rvpn-browser"
JOBS=""
MODE="low-cost"

while [ $# -gt 0 ]; do
  case "$1" in
    --chromium-src) CR_DIR="$2"; shift 2 ;;
    --out)          OUT_DIR="$2"; shift 2 ;;
    --jobs|-j)      JOBS="$2"; shift 2 ;;
    --low-cost)     MODE="low-cost"; shift ;;
    --thorium-args) MODE="thorium"; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
THOR_DIR="${THOR_DIR:-$HOME/src/thorium}"
export PATH="${DEPOT_TOOLS_DIR:-$HOME/depot_tools}:$PATH"

if [ ! -d "$CR_DIR" ]; then
  echo "!! Chromium source not found at $CR_DIR (run browser/scripts/setup-thorium.sh first)" >&2
  exit 1
fi

mkdir -p "$CR_DIR/$OUT_DIR"

if [ "$MODE" = "thorium" ]; then
  echo "==> using Thorium's official args.gn (PGO + ThinLTO + CFI)"
  cp "$THOR_DIR/args.gn" "$CR_DIR/$OUT_DIR/args.gn"
  # Keep our own output product name/branding but nothing else changes.
else
  echo "==> writing low-cost args.gn"
  cat > "$CR_DIR/$OUT_DIR/args.gn" <<'EOF'
# RVPN Browser - Linux x64
# Only well established GN arguments are used here: an argument that no longer
# exists makes gn gen complain, so this list is deliberately conservative.
target_os = "linux"
target_cpu = "x64"

is_official_build = true
is_debug = false
is_component_build = false

# Keep the build affordable on small machines: no PGO, no ThinLTO, no CFI.
use_thin_lto = false
is_cfi = false
chrome_pgo_phase = 0
use_lld = true

symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
exclude_unwind_tables = true
enable_stripping = true

enable_precompiled_headers = false
treat_warnings_as_errors = false
disable_fieldtrial_testing_config = true

# Wider media support, matching Thorium's defaults.
proprietary_codecs = true
ffmpeg_branding = "Chrome"
media_use_ffmpeg = true
media_use_libvpx = true
rtc_use_h264 = true
use_vaapi = true

# Widevine needs a redistributable CDM payload; the RVPN integration does not.
enable_widevine = false
bundle_widevine_cdm = false

# No installer packages during development builds.
enable_linux_installer = false
EOF
fi

cd "$CR_DIR"
echo "==> gn gen $OUT_DIR"
gn gen "$OUT_DIR"

echo "==> building chrome (this takes hours on small machines)"
if [ -n "$JOBS" ]; then
  autoninja -C "$OUT_DIR" -j "$JOBS" chrome
else
  autoninja -C "$OUT_DIR" chrome
fi

echo
echo "==> build finished"
ls -la "$CR_DIR/$OUT_DIR/chrome" 2>/dev/null || true
echo "    run with: browser/scripts/run-linux.sh --chromium-src $CR_DIR --out $OUT_DIR"
