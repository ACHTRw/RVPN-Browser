#!/usr/bin/env bash
# Run the RVPN extension integration test against a real Chromium binary.
#
#   browser/scripts/test-extension.sh --chromium /path/to/chrome
#   RVPN_CHROMIUM=/path/to/chrome browser/scripts/test-extension.sh
#
# Requires playwright-core (npm i -g playwright-core, or a local node_modules
# with playwright-core installed).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHROMIUM=""

while [ $# -gt 0 ]; do
  case "$1" in
    --chromium) CHROMIUM="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$CHROMIUM" ]; then
  for candidate in \
      "$REPO_ROOT/out/rvpn-browser/chrome" \
      "$HOME/chromium/src/out/rvpn-browser/chrome" \
      "$(command -v thorium 2>/dev/null || true)" \
      "$(command -v chromium 2>/dev/null || true)" \
      "$HOME/src/cr/chrome-linux/chrome"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then CHROMIUM="$candidate"; break; fi
  done
fi
[ -n "$CHROMIUM" ] && [ -x "$CHROMIUM" ] || { echo "!! no Chromium binary found (use --chromium)" >&2; exit 2; }

# playwright-core must be resolvable from the test directory.
NODE_PATH_GUESS=""
for candidate in "$REPO_ROOT/node_modules" "$HOME/src/pwtest/node_modules" "/usr/lib/node_modules"; do
  if [ -d "$candidate/playwright-core" ]; then NODE_PATH_GUESS="$candidate"; break; fi
done
if [ -z "$NODE_PATH_GUESS" ]; then
  echo "!! playwright-core not found. Install it with:" >&2
  echo "   cd $REPO_ROOT && npm install playwright-core" >&2
  exit 2
fi

echo "==> chromium: $CHROMIUM"
echo "==> playwright-core: $NODE_PATH_GUESS"

status=0
run() {
  echo
  echo "==> $1"
  NODE_PATH="$NODE_PATH_GUESS" node "$REPO_ROOT/browser/test/extension_test.mjs" --chromium "$CHROMIUM" "${@:2}" || status=1
}

run "mock engine integration (proxy + traffic)" "$@"
run "challenge round-trip" --challenge "$@"

echo
if [ "$status" = "0" ]; then echo "==> all extension integration tests passed"; else echo "==> FAILURES (see above)"; fi
exit "$status"
