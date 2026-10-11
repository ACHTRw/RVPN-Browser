#!/usr/bin/env bash
# Run a locally built RVPN Browser with the RVPN extension and native messaging
# host wired up, without touching the system installation.

set -euo pipefail
CR_DIR="${CR_DIR:-$HOME/chromium/src}"
OUT_DIR="${OUT_DIR:-out/rvpn-browser}"
SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BIN="$CR_DIR/$OUT_DIR/chrome"
if [ ! -x "$BIN" ]; then
  BIN="$CR_DIR/$OUT_DIR/rvpn-browser"
fi
if [ ! -x "$BIN" ]; then
  echo "!! built browser not found in $CR_DIR/$OUT_DIR; run browser/scripts/build-linux.sh first" >&2
  exit 1
fi

export RVPN_BROWSER_BIN="$BIN"
export RVPN_EXTENSION_DIR="$REPO_ROOT/browser/extension"
export RVPN_CORE_BIN="${RVPN_CORE_BIN:-$REPO_ROOT/core/bin/rvpn-core}"
export RVPN_PROFILE_DIR="${RVPN_PROFILE_DIR:-$HOME/.config/rvpn-browser}"
exec "$REPO_ROOT/packaging/linux/rvpn-browser" "$@"
