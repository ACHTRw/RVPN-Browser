#!/usr/bin/env bash
# Install RVPN Browser (core, extension, launcher, desktop entry, icon) for the
# current user. Run without arguments; use --prefix to change the target.
#
#   ./packaging/linux/install.sh                 # ~/.local (no sudo needed)
#   ./packaging/linux/install.sh --system        # /usr/local (uses sudo)
#   ./packaging/linux/install.sh --uninstall

set -euo pipefail

SYSTEM=0
UNINSTALL=0
PREFIX="${HOME}/.local"
SHARE_SUBDIR="share/rvpn-browser"
BIN_SUBDIR="bin"
APPS_SUBDIR="share/applications"
ICON_SUBDIR="share/icons/hicolor"

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

while [ $# -gt 0 ]; do
  case "$1" in
    --system) SYSTEM=1; PREFIX="/usr/local"; shift ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

SUDO=""
if [ "$SYSTEM" = "1" ] && [ "$(id -u)" != "0" ]; then SUDO="sudo"; fi
run() { if [ -n "$SUDO" ]; then $SUDO "$@"; else "$@"; fi; }

SHARE_DIR="$PREFIX/$SHARE_SUBDIR"
BIN_DIR="$PREFIX/$BIN_SUBDIR"
APPS_DIR="$PREFIX/$APPS_SUBDIR"

icon_install() {
  local src="$1" size="$2" name="rvpn-browser"
  local dir="$PREFIX/$ICON_SUBDIR/${size}x${size}/apps"
  run mkdir -p "$dir"
  run cp -f "$src" "$dir/$name.png"
}

if [ "$UNINSTALL" = "1" ]; then
  echo "==> removing RVPN Browser from $PREFIX"
  run rm -rf "$SHARE_DIR"
  run rm -f "$BIN_DIR/rvpn-browser" "$BIN_DIR/rvpn-core"
  run rm -f "$APPS_DIR/rvpn-browser.desktop"
  for size in 16 32 48 64 128 256; do
    run rm -f "$PREFIX/$ICON_SUBDIR/${size}x${size}/apps/rvpn-browser.png"
  done
  for cfg in chromium google-chrome thorium rvpn-browser; do
    rm -f "${XDG_CONFIG_HOME:-$HOME/.config}/$cfg/NativeMessagingHosts/com.achtrw.rvpn.json" 2>/dev/null || true
  done
  run rm -f /etc/chromium/native-messaging-hosts/com.achtrw.rvpn.json 2>/dev/null || true
  run rm -f /etc/opt/chrome/native-messaging-hosts/com.achtrw.rvpn.json 2>/dev/null || true
  echo "==> done"
  exit 0
fi

echo "==> installing into $PREFIX"

# ---------------------------------------------------------------- core binary
CORE_SRC=""
for candidate in "$REPO_ROOT/core/bin/rvpn-core" "$REPO_ROOT/core/rvpn-core" "$(command -v rvpn-core 2>/dev/null || true)"; do
  if [ -n "$candidate" ] && [ -x "$candidate" ]; then CORE_SRC="$candidate"; break; fi
done
if [ -z "$CORE_SRC" ]; then
  echo "!! rvpn-core not built yet; building it now (requires Go 1.25+)"
  ( cd "$REPO_ROOT/core" && mkdir -p bin && go build -o bin/rvpn-core ./cmd/rvpn-core )
  CORE_SRC="$REPO_ROOT/core/bin/rvpn-core"
fi
run mkdir -p "$BIN_DIR" "$SHARE_DIR"
run install -m 0755 "$CORE_SRC" "$BIN_DIR/rvpn-core"
echo "    core     -> $BIN_DIR/rvpn-core"

# ------------------------------------------------------------------ extension
run mkdir -p "$SHARE_DIR"
run rm -rf "$SHARE_DIR/extension"
run cp -r "$REPO_ROOT/browser/extension" "$SHARE_DIR/extension"
echo "    extension-> $SHARE_DIR/extension"

# ------------------------------------------------------------------- launcher
run install -m 0755 "$SCRIPT_DIR/rvpn-browser" "$BIN_DIR/rvpn-browser"
# The launcher defaults to /usr/local/share; point it at the real share dir.
run sed -i "s|^RVPN_SHARE_DIR=.*|RVPN_SHARE_DIR=\"\${RVPN_SHARE_DIR:-$SHARE_DIR}\"|" "$BIN_DIR/rvpn-browser"
echo "    launcher -> $BIN_DIR/rvpn-browser"

# ----------------------------------------------------------------------- icon
ICON_SRC="$REPO_ROOT/icon.svg"
if command -v rsvg-convert >/dev/null 2>&1; then
  tmp="$(mktemp -d)"
  for size in 16 32 48 64 128 256; do
    rsvg-convert -w "$size" -h "$size" "$ICON_SRC" -o "$tmp/rvpn-$size.png"
    icon_install "$tmp/rvpn-$size.png" "$size"
  done
  rm -rf "$tmp"
  echo "    icons    -> $PREFIX/$ICON_SUBDIR/*/apps/rvpn-browser.png"
else
  echo "    !! rsvg-convert not found (apt install librsvg2-bin); icons not installed"
fi

# --------------------------------------------------------------- desktop file
run mkdir -p "$APPS_DIR"
sed "s|@BIN@|$BIN_DIR/rvpn-browser|g" "$SCRIPT_DIR/rvpn-browser.desktop.in" > /tmp/rvpn-browser.desktop.$$
run install -m 0644 /tmp/rvpn-browser.desktop.$$ "$APPS_DIR/rvpn-browser.desktop"
rm -f /tmp/rvpn-browser.desktop.$$
echo "    desktop  -> $APPS_DIR/rvpn-browser.desktop"

# ------------------------------------------------- native messaging manifests
write_host_manifest() {
  local dir="$1"
  if [ -n "$SUDO" ]; then $SUDO mkdir -p "$dir" 2>/dev/null || return 0; else mkdir -p "$dir" 2>/dev/null || return 0; fi
  cat > /tmp/rvpn-host.json.$$ <<EOF
{
  "name": "com.achtrw.rvpn",
  "description": "RVPN Browser engine (ZJU RVPN / aTrust / EasyConnect)",
  "path": "$BIN_DIR/rvpn-core",
  "type": "stdio",
  "allowed_origins": [
    "chrome-extension://lhgepbmchpealfajciakglacaedkjenh/"
  ]
}
EOF
  if [ -n "$SUDO" ]; then $SUDO install -m 0644 /tmp/rvpn-host.json.$$ "$dir/com.achtrw.rvpn.json"; else install -m 0644 /tmp/rvpn-host.json.$$ "$dir/com.achtrw.rvpn.json"; fi
  rm -f /tmp/rvpn-host.json.$$
  echo "    host     -> $dir/com.achtrw.rvpn.json"
}

if [ "$SYSTEM" = "1" ]; then
  for dir in /etc/chromium/native-messaging-hosts /etc/opt/chrome/native-messaging-hosts \
             /etc/thorium/native-messaging-hosts /etc/rvpn-browser/native-messaging-hosts; do
    write_host_manifest "$dir"
  done
fi

CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
for cfg in chromium google-chrome thorium rvpn-browser; do
  write_host_manifest "$CONFIG_HOME/$cfg/NativeMessagingHosts"
done

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPS_DIR" >/dev/null 2>&1 || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -f -t "$PREFIX/$ICON_SUBDIR" >/dev/null 2>&1 || true
fi

echo
echo "==> RVPN Browser installed."
echo "    启动方式 / launch with:  $BIN_DIR/rvpn-browser"
echo "    首次使用请在扩展弹窗中开启 RVPN 模式并登录。"
echo "    Add $BIN_DIR to PATH if needed."
