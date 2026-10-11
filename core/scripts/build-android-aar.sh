#!/usr/bin/env bash
# Build the gomobile AAR used by the Android application.
#
# Requires: Go 1.25+, JDK 17+, Android NDK.
#   export ANDROID_NDK_HOME=$HOME/android-ndk-r27c
#   go install golang.org/x/mobile/cmd/gomobile@latest

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="$CORE_DIR/build/rvpncore.aar"

export PATH="$(go env GOPATH)/bin:/usr/local/go/bin:$PATH"
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"

if ! command -v gomobile >/dev/null 2>&1; then
  echo "==> installing gomobile"
  go install golang.org/x/mobile/cmd/gomobile@latest
  go install golang.org/x/mobile/cmd/gobind@latest
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  for candidate in "$HOME/android-ndk-r27c" "$HOME/android-ndk-r28b" "$HOME/chromium/src/third_party/android_ndk"; do
    if [ -d "$candidate" ]; then export ANDROID_NDK_HOME="$candidate"; break; fi
  done
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "!! ANDROID_NDK_HOME is not set and no NDK was found." >&2
  exit 1
fi
echo "==> NDK: $ANDROID_NDK_HOME"

mkdir -p "$CORE_DIR/build"
cd "$CORE_DIR"
gomobile bind \
  -target=android/arm64,android/arm,android/amd64 \
  -androidapi 26 \
  -javapkg=org.rvpnbrowser.core \
  -o "$OUT" \
  ./mobile/rvpncore

ls -la "$OUT"
echo "==> AAR ready: $OUT"
