#!/usr/bin/env bash
# Package a built RVPN Browser (out/rvpn-browser) together with the RVPN engine
# and the installer into a distributable tarball.
#
#   scripts/package-linux-browser.sh [--out dist] [--version 1.0.0]
#
# The result contains the real browser binary (built from the Thorium/Chromium
# sources with the RVPN branding and the built-in extension), so the archive
# needs no system Chromium.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CR_DIR="${CR_DIR:-$HOME/chromium/src}"
OUT_DIR="${OUT_DIR:-out/rvpn-browser}"
DIST="$ROOT/dist"
VERSION="1.0.0"

while [ $# -gt 0 ]; do
  case "$1" in
    --out) DIST="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --chromium-src) CR_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

BUILD="$CR_DIR/$OUT_DIR"
[ -x "$BUILD/chrome" ] || { echo "!! no built browser in $BUILD (run browser/scripts/build-linux.sh)" >&2; exit 2; }
[ -x "$ROOT/core/bin/rvpn-core" ] || { echo "!! core/bin/rvpn-core missing" >&2; exit 2; }

NAME="rvpn-browser-$VERSION-linux-amd64"
STAGE="$(mktemp -d)/$NAME"
mkdir -p "$STAGE" "$DIST"

echo "==> staging browser"
mkdir -p "$STAGE/browser"
# Ship everything the binary needs at runtime (binary, pak files, locales, ICU
# data, ANGLE/vulkan libraries), but not the build objects.
for item in chrome chrome_sandbox icudtl.dat v8_context_snapshot.bin resources.pak \
            chrome_100_percent.pak chrome_200_percent.pak snapshot_blob.bin \
            v8_context_snapshot.bin locales resources chrome_crashpad_handler \
            libEGL.so libGLESv2.so libvk_swiftshader.so libvulkan.so.1 vk_swiftshader_icd.json; do
  if [ -e "$BUILD/$item" ]; then cp -a "$BUILD/$item" "$STAGE/browser/"; fi
done
# The built-in extension is compiled into the binary; the standalone copy is
# only needed for the "use another Chromium" path.
cp -r "$ROOT/browser/extension" "$STAGE/browser/extension-on-disk"

echo "==> staging engine, launcher and installer"
mkdir -p "$STAGE/core/bin"
cp "$ROOT/core/bin/rvpn-core" "$STAGE/core/bin/rvpn-core" 2>/dev/null || true
if [ ! -x "$STAGE/core/bin/rvpn-core" ]; then
  # Fall back to a fresh build.
  ( cd "$ROOT/core" && mkdir -p bin && go build -o bin/rvpn-core ./cmd/rvpn-core )
  cp "$ROOT/core/bin/rvpn-core" "$STAGE/core/bin/rvpn-core"
fi
mkdir -p "$STAGE/packaging/linux" "$STAGE/docs"
cp "$ROOT/packaging/linux/install.sh" "$ROOT/packaging/linux/rvpn-browser" \
   "$ROOT/packaging/linux/rvpn-browser.desktop.in" "$STAGE/packaging/linux/"
cp "$ROOT/README.md" "$ROOT/NOTICE.md" "$ROOT/LICENSE" "$ROOT/icon.svg" "$STAGE/"
cp "$ROOT/docs/"*.md "$STAGE/docs/" 2>/dev/null || true
mkdir -p "$STAGE/browser/scripts"
cp "$ROOT/browser/scripts/verify-build.sh" "$ROOT/browser/scripts/run-linux.sh" "$STAGE/browser/scripts/" 2>/dev/null || true

cat > "$STAGE/RUN.txt" <<EOF
RVPN Browser $VERSION (Linux x86_64, built from source)

启动 / run:
    ./browser/chrome --user-data-dir=\$HOME/.config/rvpn-browser

浏览器已内置 RVPN 界面（component extension），无需 --load-extension。
RVPN 引擎需要注册原生消息宿主，二选一：

  1) 安装到用户目录（推荐，同时安装图标与桌面项）：
        ./packaging/linux/install.sh
        ~/.local/bin/rvpn-browser

  2) 手动注册（不安装任何东西）：
        mkdir -p ~/.config/rvpn-browser/NativeMessagingHosts
        cat > ~/.config/rvpn-browser/NativeMessagingHosts/com.achtrw.rvpn.json <<JSON
        {
          "name": "com.achtrw.rvpn",
          "description": "RVPN Browser engine",
          "path": "$STAGE/core/bin/rvpn-core",
          "type": "stdio",
          "allowed_origins": ["chrome-extension://lhgepbmchpealfajciakglacaedkjenh/"]
        }
        JSON

验证 / verify:
    CR_DIR=... browser/scripts/verify-build.sh --chromium ./browser/chrome
EOF

echo "==> compressing"
( cd "$(dirname "$STAGE")" && tar czf "$DIST/$NAME.tar.gz" "$NAME" )
rm -rf "$(dirname "$STAGE")"

ls -la "$DIST/$NAME.tar.gz"
echo "==> packaged: $DIST/$NAME.tar.gz"
