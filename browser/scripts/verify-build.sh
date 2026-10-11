#!/usr/bin/env bash
# Verify a locally built RVPN Browser: branding, built-in extension, native
# messaging to rvpn-core, and the ZJU connectivity probe.
#
#   browser/scripts/verify-build.sh [--chromium out/rvpn-browser/chrome]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CR_DIR="${CR_DIR:-$HOME/chromium/src}"

CHROMIUM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --chromium) CHROMIUM="$2"; shift 2 ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$CHROMIUM" ] || CHROMIUM="$CR_DIR/out/rvpn-browser/chrome"
[ -x "$CHROMIUM" ] || { echo "!! built browser not found: $CHROMIUM" >&2; exit 2; }

NODE_PATH_GUESS=""
for candidate in "$REPO_ROOT/node_modules" "$HOME/src/pwtest/node_modules" "/usr/lib/node_modules"; do
  if [ -d "$candidate/playwright-core" ]; then NODE_PATH_GUESS="$candidate"; break; fi
done
[ -n "$NODE_PATH_GUESS" ] || { echo "!! playwright-core not found (npm install playwright-core)" >&2; exit 2; }

echo "==> browser: $CHROMIUM"
NODE_PATH="$NODE_PATH_GUESS" node "$REPO_ROOT/browser/test/built_browser_test.mjs" --chromium "$CHROMIUM" "$@"
