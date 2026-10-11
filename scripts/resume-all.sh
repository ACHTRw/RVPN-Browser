#!/usr/bin/env bash
# Resume everything after an interruption (session end, reboot, Ctrl-C):
# finish the build, verify it, package it, and (optionally) publish it.
#
#   scripts/resume-all.sh                  # build + verify + package
#   scripts/resume-all.sh --publish        # ... and upload to the GitHub release
#   scripts/resume-all.sh --check          # only report the current state
#
# Every step is idempotent. The Chromium build is incremental: re-running
# autoninja continues where it stopped.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CR_DIR="${CR_DIR:-$HOME/chromium/src}"
OUT_DIR="${OUT_DIR:-out/rvpn-browser}"
VERSION="${RVPN_VERSION:-1.0.0}"
PUBLISH=0
CHECK_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1; shift ;;
    --check) CHECK_ONLY=1; shift ;;
    --version) VERSION="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

status() {
  local binary="$CR_DIR/$OUT_DIR/chrome"
  echo "== RVPN Browser 状态 =="
  echo "源码:      $( [ -d "$CR_DIR" ] && du -sh "$CR_DIR" 2>/dev/null | cut -f1 || echo 缺失 ) ($CR_DIR)"
  echo "RVPN 补丁: $(grep -q 'RvpnProxyConfigService\|AddRvpnBrowserExtension' "$CR_DIR/chrome/browser/extensions/component_loader.cc" 2>/dev/null && echo 已应用 || echo 未应用 )"
  echo "编译产物:  $( [ -x "$binary" ] && ls -la "$binary" | awk '{print $5" bytes"}' || echo 未生成 )"
  echo "编译进程:  $(pgrep -fc "autoninja -C $OUT_DIR" 2>/dev/null || echo 0)"
  echo "最近进度:  $(grep -oE '^\[[0-9]+/[0-9]+\] [0-9]+h[0-9]+m[0-9.]+s' /tmp/rvpn-chromium-build.log 2>/dev/null | tail -1)"
  [ -f /tmp/rvpn-build-result.txt ] && { echo "验证结果:"; tail -5 /tmp/rvpn-build-result.txt; }
  echo "发布产物:  $(ls "$ROOT"/dist/*.apk "$ROOT"/dist/*.tar.gz 2>/dev/null | wc -l) 个文件于 dist/"
}

status
[ "$CHECK_ONLY" = "1" ] && exit 0

if [ ! -x "$CR_DIR/$OUT_DIR/chrome" ]; then
  echo
  echo "==> 继续编译（增量）"
  "$ROOT/browser/scripts/build-linux.sh" --low-cost || { echo "!! 编译失败，见 /tmp/rvpn-chromium-build.log" >&2; exit 1; }
fi

echo
echo "==> 验证构建产物"
"$ROOT/browser/scripts/verify-build.sh" --chromium "$CR_DIR/$OUT_DIR/chrome" || echo "!! 验证未全部通过（见上）"

echo
echo "==> 打包"
"$ROOT/scripts/package-linux-browser.sh" --version "$VERSION" || exit 1

if [ "$PUBLISH" = "1" ]; then
  echo
  if [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "!! --publish 需要 GITHUB_TOKEN" >&2
    exit 2
  fi
  "$ROOT/scripts/publish-release.sh" "v$VERSION"
fi

echo
echo "==> 完成。产物在 $ROOT/dist/"
