#!/usr/bin/env bash
# Build the Android RVPN Browser APK from a prepared Chromium/Thorium tree.
#
# Usage: browser/scripts/build-android.sh [--chromium-src DIR] [--out DIR] [--cpu arm64|arm|x64] [--jobs N]

set -euo pipefail

CR_DIR="${CR_DIR:-$HOME/chromium/src}"
OUT_DIR="out/rvpn-browser-android"
CPU="arm64"
JOBS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --chromium-src) CR_DIR="$2"; shift 2 ;;
    --out)          OUT_DIR="$2"; shift 2 ;;
    --cpu)          CPU="$2"; shift 2 ;;
    --jobs|-j)      JOBS="$2"; shift 2 ;;
    -h|--help) sed -n '2,5p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

export PATH="${DEPOT_TOOLS_DIR:-$HOME/depot_tools}:$PATH"
[ -d "$CR_DIR" ] || { echo "!! Chromium source not found at $CR_DIR" >&2; exit 1; }

mkdir -p "$CR_DIR/$OUT_DIR"
cat > "$CR_DIR/$OUT_DIR/args.gn" <<EOF
# RVPN Browser - Android $CPU
target_os = "android"
target_cpu = "$CPU"

is_official_build = true
is_debug = false
is_component_build = false

use_thin_lto = false
is_cfi = false
chrome_pgo_phase = 0
use_lld = true

symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
enable_stripping = true

enable_precompiled_headers = false
treat_warnings_as_errors = false
disable_fieldtrial_testing_config = true
enable_reporting = false

proprietary_codecs = true
ffmpeg_branding = "Chrome"
rtc_use_h264 = true

enable_widevine = false
bundle_widevine_cdm = false

# RVPN Browser additions (see browser/patches)
rvpn_browser_build = true
EOF

cd "$CR_DIR"
echo "==> gn gen $OUT_DIR"
gn gen "$OUT_DIR"

echo "==> building chrome_public_apk"
if [ -n "$JOBS" ]; then
  autoninja -C "$OUT_DIR" -j "$JOBS" chrome_public_apk
else
  autoninja -C "$OUT_DIR" chrome_public_apk
fi

echo
echo "==> APK(s):"
find "$CR_DIR/$OUT_DIR/apks" -name '*.apk' -printf '    %p (%s bytes)\n' 2>/dev/null || true
