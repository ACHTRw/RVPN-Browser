#!/usr/bin/env bash
# Prepare the Thorium/Chromium source tree that RVPN Browser is built from.
#
# Usage:
#   browser/scripts/setup-thorium.sh [--thorium-root DIR] [--chromium-src DIR] [--platforms linux,android]
#
# Environment honoured by the official Thorium tooling:
#   THOR_DIR, CR_DIR, DEPOT_TOOLS_DIR
#
# This script only orchestrates the upstream scripts (get_repo.py, version.py,
# setup.py) and adds the RVPN specific bits (target_os, patch application).

set -euo pipefail

THOR_DIR="${THOR_DIR:-$HOME/src/thorium}"
CR_DIR="${CR_DIR:-$HOME/chromium/src}"
DEPOT_TOOLS_DIR="${DEPOT_TOOLS_DIR:-$HOME/depot_tools}"
PLATFORMS="${RVPN_PLATFORMS:-linux}"
SKIP_PATCHES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --thorium-root) THOR_DIR="$2"; shift 2 ;;
    --chromium-src) CR_DIR="$2"; shift 2 ;;
    --platforms)    PLATFORMS="$2"; shift 2 ;;
    --skip-patches) SKIP_PATCHES=1; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

export THOR_DIR CR_DIR DEPOT_TOOLS_DIR
export PATH="$DEPOT_TOOLS_DIR:$PATH"

if [ ! -d "$THOR_DIR" ]; then
  echo "!! Thorium checkout not found at $THOR_DIR"
  echo "   git clone --recursive https://github.com/gz83/thorium.git \"$THOR_DIR\""
  exit 1
fi

echo "==> [1/4] depot_tools + Chromium checkout (get_repo.py)"
if [ -f "$DEPOT_TOOLS_DIR/gclient.py" ] && [ -d "$CR_DIR/.git" ]; then
  echo "    already present, skipping"
else
  ( cd "$THOR_DIR" && python3 get_repo.py --no-history )
fi

echo "==> [2/4] target_os = [$PLATFORMS] in $CR_DIR/../.gclient"
GCLIENT="$(dirname "$CR_DIR")/.gclient"
if [ -f "$GCLIENT" ]; then
  python3 - "$GCLIENT" "$PLATFORMS" <<'PY'
import ast, sys, re
path, platforms = sys.argv[1], [p.strip() for p in sys.argv[2].split(',') if p.strip()]
src = open(path, encoding='utf-8').read()
if 'target_os' in src:
    src = re.sub(r'target_os\s*=\s*\[[^\]]*\]', 'target_os = %r' % platforms, src)
    src = src.replace("'", '"')
else:
    src = src.rstrip() + '\ntarget_os = %r\n' % platforms
    src = src.replace("'", '"')
open(path, 'w', encoding='utf-8').write(src)
print('    .gclient target_os ->', platforms)
PY
else
  echo "    !!.gclient not found; skipping (gclient sync will use host defaults)"
fi

echo "==> [3/4] pin the Thorium Chromium revision and sync dependencies (version.py)"
( cd "$THOR_DIR" && python3 version.py )

echo "==> [4/4] apply the Thorium patch series and overlays (setup.py)"
( cd "$THOR_DIR" && python3 setup.py )

if [ "$SKIP_PATCHES" = "0" ]; then
  echo "==> applying RVPN Browser patches"
  python3 "$SCRIPT_DIR/apply-rvpn-patches.py" --chromium-src "$CR_DIR" --thorium-root "$THOR_DIR"
fi

echo
echo "==> source tree ready:"
echo "    Thorium:  $THOR_DIR"
echo "    Chromium: $CR_DIR"
echo "    next:     browser/scripts/build-linux.sh"
