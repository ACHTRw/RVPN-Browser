#!/usr/bin/env bash
# =============================================================================
#  RVPN Browser 一键编译脚本 / one-click build script
# =============================================================================
#
#  用法：
#     ./build.sh                  # 编译 Linux 版浏览器（引擎 → 补丁 → 编译 → 验证 → 打包）
#     ./build.sh --android        # 编译 Android 应用 APK（WebView + RVPN 引擎）
#     ./build.sh --all            # 上面两个都编译
#     ./build.sh --check          # 只显示当前状态，不做任何改动
#     ./build.sh --jobs 4         # 指定并行度（默认交给 autoninja 决定）
#     ./build.sh --publish        # 编译完成后上传到 GitHub Release（需 GITHUB_TOKEN）
#
#  断点续跑：
#     本脚本完全幂等，且 Chromium 编译是增量的。中途关机 / Ctrl-C 之后
#     重新执行 ./build.sh 会从断点继续，不会重头再来。
#
#  环境变量：
#     RVPN_PROXY      代理地址（默认 http://172.18.112.1:7897，设为 off 可关闭）
#     CR_DIR          Chromium 源码目录（默认 ~/chromium/src）
#     THOR_DIR        Thorium 检出目录（默认 ~/src/thorium）
#     DEPOT_TOOLS_DIR depot_tools 目录（默认 ~/depot_tools）
#     LOG             日志文件（默认 /tmp/rvpn-build.log）
#     GITHUB_TOKEN    --publish 时需要
#
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
ROOT="$SCRIPT_DIR"

export CR_DIR="${CR_DIR:-$HOME/chromium/src}"
export THOR_DIR="${THOR_DIR:-$HOME/src/thorium}"
export DEPOT_TOOLS_DIR="${DEPOT_TOOLS_DIR:-$HOME/depot_tools}"
LOG="${LOG:-/tmp/rvpn-build.log}"
export PATH="$DEPOT_TOOLS_DIR:$HOME/go/bin:/usr/local/go/bin:$PATH"

# 代理：Chromium 的 gclient / CIPD 在国内通常需要；export RVPN_PROXY=off 可关闭
if [ "${RVPN_PROXY:-http://172.18.112.1:7897}" != "off" ]; then
  export http_proxy="${RVPN_PROXY:-http://172.18.112.1:7897}"
  export https_proxy="$http_proxy"
  export no_proxy="${no_proxy:-localhost,127.0.0.1,::1,.zju.edu.cn,172.18.112.1}"
fi
export GIT_TERMINAL_PROMPT=0

DO_LINUX=1
DO_ANDROID=0
CHECK_ONLY=0
PUBLISH=0
JOBS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --android) DO_LINUX=0; DO_ANDROID=1; shift ;;
    --all)     DO_ANDROID=1; shift ;;
    --check)   CHECK_ONLY=1; shift ;;
    --publish) PUBLISH=1; shift ;;
    --jobs|-j) JOBS="$2"; shift 2 ;;
    --linux)   DO_LINUX=1; DO_ANDROID=0; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数 / unknown argument: $1" >&2; exit 2 ;;
  esac
done

BOLD=$'\033[1m'; BLUE=$'\033[1;34m'; GREEN=$'\033[1;32m'; YELLOW=$'\033[1;33m'; RED=$'\033[1;31m'; OFF=$'\033[0m'
step() { printf '\n%s==> %s%s\n' "$BLUE" "$*" "$OFF" | tee -a "$LOG"; }
info() { printf '    %s\n' "$*" | tee -a "$LOG"; }
ok()   { printf '    %s✓%s %s\n' "$GREEN" "$OFF" "$*" | tee -a "$LOG"; }
warn() { printf '    %s!%s %s\n' "$YELLOW" "$OFF" "$*" | tee -a "$LOG"; }
die()  { printf '\n%s错误 / ERROR:%s %s\n' "$RED" "$OFF" "$*" >&2; echo "（详细日志：$LOG）" >&2; exit 1; }

progress() {
  local line
  line="$(grep -oE '^\[[0-9]+/[0-9]+\] [0-9]+h[0-9]+m[0-9.]+s' "$LOG" 2>/dev/null | tail -1)"
  [ -n "$line" ] && info "编译进度：$line"
}

# -----------------------------------------------------------------------------
# 状态
# -----------------------------------------------------------------------------
show_status() {
  local binary="$CR_DIR/out/rvpn-browser/chrome"
  printf '%s== RVPN Browser 状态 ==%s\n' "$BOLD" "$OFF"
  printf '  %-14s %s\n' "仓库"      "$ROOT"
  printf '  %-14s %s\n' "引擎"      "$( [ -x "$ROOT/core/bin/rvpn-core" ] && echo "已编译 · $("$ROOT/core/bin/rvpn-core" --version 2>/dev/null)" || echo "未编译" )"
  printf '  %-14s %s\n' "Thorium"   "$( [ -d "$THOR_DIR" ] && echo "已就绪 $THOR_DIR" || echo "未检出" )"
  printf '  %-14s %s\n' "Chromium"  "$( [ -d "$CR_DIR" ] && echo "已就绪 $(du -sh "$CR_DIR" 2>/dev/null | cut -f1) $CR_DIR" || echo "未检出" )"
  printf '  %-14s %s\n' "RVPN 补丁" "$( grep -q 'AddRvpnBrowserExtension' "$CR_DIR/chrome/browser/extensions/component_loader.cc" 2>/dev/null && echo "已应用" || echo "未应用" )"
  printf '  %-14s %s\n' "浏览器产物" "$( [ -x "$binary" ] && ls -la "$binary" | awk '{print $5" 字节  "$9}' || echo "未生成" )"
  printf '  %-14s %s\n' "编译进程"  "$( pgrep -fc 'autoninja -C out/rvpn-browser' 2>/dev/null || echo 0 )"
  printf '  %-14s %s\n' "Android APK" "$( ls "$ROOT"/android/app/build/outputs/apk/*/*.apk 2>/dev/null | head -1 || echo '未生成' )"
  printf '  %-14s %s\n' "发布产物"  "$( ls "$ROOT"/dist/* 2>/dev/null | wc -l ) 个文件于 dist/"
  progress
}

if [ "$CHECK_ONLY" = "1" ]; then
  show_status
  echo
  echo "续跑编译： ./build.sh"
  exit 0
fi

# -----------------------------------------------------------------------------
# 0. 前置检查
# -----------------------------------------------------------------------------
step "[0/6] 环境检查"
command -v git    >/dev/null || die "缺少 git"
command -v python3 >/dev/null || die "缺少 python3"
command -v go     >/dev/null || warn "未找到 go（仅影响 RVPN 引擎；Chromium 编译不需要）"
[ -d "$DEPOT_TOOLS_DIR" ] || warn "depot_tools 未安装，首次编译时会自动克隆到 $DEPOT_TOOLS_DIR"
avail_kb=$(df -Pk "$HOME" | awk 'NR==2{print $4}')
info "磁盘可用：$(( avail_kb / 1024 / 1024 )) GB（源码约 50 GB，编译产物约 15 GB）"
if [ "$avail_kb" -lt 30000000 ] && [ ! -x "$CR_DIR/out/rvpn-browser/chrome" ]; then
  warn "磁盘剩余不足 30 GB，编译可能失败；建议先清理"
fi
ok "环境检查完成"

# -----------------------------------------------------------------------------
# 1. RVPN 引擎
# -----------------------------------------------------------------------------
build_core() {
  step "[1/6] 编译 RVPN 引擎 rvpn-core"
  if [ -x "$ROOT/core/bin/rvpn-core" ] && [ -z "$(find "$ROOT/core" -name '*.go' -newer "$ROOT/core/bin/rvpn-core" 2>/dev/null | head -1)" ]; then
    ok "已是最新（$( "$ROOT/core/bin/rvpn-core" --version )）"
    return 0
  fi
  command -v go >/dev/null || die "编译 RVPN 引擎需要 Go 1.25+（apt install golang-go 或 https://go.dev/dl/）"
  ( cd "$ROOT/core" && mkdir -p bin && GOPROXY="${GOPROXY:-https://goproxy.cn,direct}" go build -o bin/rvpn-core ./cmd/rvpn-core ) \
    || die "rvpn-core 编译失败"
  ok "$( "$ROOT/core/bin/rvpn-core" --version )"
}

# -----------------------------------------------------------------------------
# 2. Chromium 源码
# -----------------------------------------------------------------------------
prepare_source() {
  step "[2/6] 准备 Chromium / Thorium 源码"
  if [ ! -d "$DEPOT_TOOLS_DIR" ]; then
    info "克隆 depot_tools ..."
    git clone --depth 1 https://chromium.googlesource.com/chromium/tools/depot_tools.git "$DEPOT_TOOLS_DIR" \
      || die "depot_tools 克隆失败（代理是否可用？可试 RVPN_PROXY=off ./build.sh）"
  fi

  if [ ! -d "$THOR_DIR" ]; then
    info "克隆 Thorium ..."
    git clone --recursive https://github.com/gz83/thorium.git "$THOR_DIR" || die "Thorium 克隆失败"
  fi

  if [ ! -d "$CR_DIR/.git" ]; then
    info "拉取 Chromium 源码（约 50 GB，首次需要较长时间）..."
    mkdir -p "$(dirname "$CR_DIR")"
    ( cd "$(dirname "$CR_DIR")" && fetch --nohooks --no-history chromium ) || die "Chromium 拉取失败"
  fi

  local marker="$CR_DIR/chrome/browser/extensions/component_loader.cc"
  if grep -q 'AddRvpnBrowserExtension' "$marker" 2>/dev/null; then
    ok "源码已就绪且 RVPN 补丁已应用"
    return 0
  fi

  info "同步依赖（gclient sync -j1，避免触发 googlesource 限流）..."
  ( cd "$CR_DIR" && gclient sync --nohooks --no-history -D --force -j1 ) >>"$LOG" 2>&1 \
    || warn "gclient sync 有问题，继续尝试（可查看 $LOG）"

  info "固定 Thorium 的 Chromium 版本并运行 hooks ..."
  ( cd "$THOR_DIR" && python3 version.py ) >>"$LOG" 2>&1 || die "version.py 失败（见 $LOG）"
  info "应用 Thorium 补丁集 ..."
  ( cd "$THOR_DIR" && python3 setup.py ) >>"$LOG" 2>&1 || die "setup.py 失败（见 $LOG）"
  info "应用 RVPN 集成补丁 ..."
  python3 "$ROOT/browser/scripts/apply-rvpn-patches.py" --chromium-src "$CR_DIR" --thorium-root "$THOR_DIR" \
    >>"$LOG" 2>&1 || die "RVPN 补丁应用失败（见 $LOG）"
  ok "源码与补丁就绪"
}

# -----------------------------------------------------------------------------
# 3~5. 编译 Linux 版
# -----------------------------------------------------------------------------
build_linux() {
  step "[3/6] 生成构建配置并编译浏览器（4 核机器约需数小时）"
  local out="$CR_DIR/out/rvpn-browser"
  if [ -x "$out/chrome" ]; then
    ok "浏览器已编译完成：$out/chrome"
    return 0
  fi
  mkdir -p "$out"
  info "写入低开销参数（无 PGO/ThinLTO/CFI，symbol_level=0）"
  "$ROOT/browser/scripts/build-linux.sh" --low-cost ${JOBS:+--jobs "$JOBS"} >>"$LOG" 2>&1 &
  local pid=$!
  local last=""
  while kill -0 "$pid" 2>/dev/null; do
    sleep 20
    local line
    line="$(grep -oE '^\[[0-9]+/[0-9]+\] [0-9]+h[0-9]+m[0-9.]+s' "$LOG" 2>/dev/null | tail -1)"
    if [ -n "$line" ] && [ "$line" != "$last" ]; then
      printf '\r    %s   ' "$line"
      last="$line"
    fi
  done
  printf '\r'
  wait "$pid" || die "编译失败（日志尾部：$(tail -3 "$LOG" | tr '\n' ' ')）"
  [ -x "$out/chrome" ] || die "编译进程结束但没有产物：$out/chrome"
  ok "编译完成：$(ls -la "$out/chrome" | awk '{print $5" 字节"}')"
}

verify_and_package() {
  step "[4/6] 验证构建产物"
  if [ -d "$ROOT/node_modules/playwright-core" ] || [ -d "$HOME/src/pwtest/node_modules/playwright-core" ]; then
    "$ROOT/browser/scripts/verify-build.sh" --chromium "$CR_DIR/out/rvpn-browser/chrome" || warn "验证未全部通过（不影响使用）"
  else
    info "未安装 playwright-core，跳过自动化验证；可手动运行："
    info "  $CR_DIR/out/rvpn-browser/chrome --version    # 应输出 RVPN Browser ..."
  fi

  step "[5/6] 打包"
  "$ROOT/scripts/package-linux-browser.sh" || warn "打包失败（不影响直接运行 out/rvpn-browser/chrome）"
}

# -----------------------------------------------------------------------------
# Android 应用（WebView + RVPN 引擎）
# -----------------------------------------------------------------------------
build_android() {
  step "[A] 编译 Android 应用 APK"
  command -v java >/dev/null || die "需要 JDK 17+（apt install openjdk-21-jdk）"
  if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/android-sdk" ]; then export ANDROID_HOME="$HOME/android-sdk"; fi
  [ -n "${ANDROID_HOME:-}" ] || die "需要 Android SDK（设置 ANDROID_HOME，或用 sdkmanager 安装）"

  if [ ! -f "$ROOT/core/build/rvpncore.aar" ] && [ ! -f "$ROOT/android/app/libs/rvpncore.aar" ]; then
    info "生成 gomobile 绑定（需要 Android NDK）..."
    ( cd "$ROOT" && ./core/scripts/build-android-aar.sh ) || die "AAR 构建失败（设置 ANDROID_NDK_HOME）"
  fi
  ( cd "$ROOT/android" && ./build.sh ) >>"$LOG" 2>&1 || die "APK 构建失败（见 $LOG）"
  ok "APK：$(ls "$ROOT"/android/app/build/outputs/apk/*/*.apk 2>/dev/null | tr '\n' ' ')"
}

# -----------------------------------------------------------------------------
# 主流程
# -----------------------------------------------------------------------------
printf '%s\n' "=============================================================="
printf '%s   RVPN Browser 一键编译%s\n' "$BOLD" "$OFF"
printf '%s\n' "=============================================================="
info "日志：$LOG"
DO_LINUX=1
[ "$DO_ANDROID" = "1" ] && DO_LINUX=1 || true

build_core

if [ "$DO_LINUX" = "1" ]; then
  prepare_source
  build_linux
  verify_and_package
fi
[ "$DO_ANDROID" = "1" ] && build_android

step "[6/6] 完成"
show_status

if [ "$PUBLISH" = "1" ]; then
  step "上传到 GitHub Release"
  [ -n "${GITHUB_TOKEN:-}" ] || die "--publish 需要 GITHUB_TOKEN"
  "$ROOT/scripts/publish-release.sh" "v${RVPN_VERSION:-1.0.0}" || die "发布失败"
fi

echo
printf '%s完成。%s\n' "$GREEN" "$OFF"
if [ "$DO_LINUX" = "1" ]; then
  printf '  启动： %s/out/rvpn-browser/chrome --user-data-dir=$HOME/.config/rvpn-browser\n' "$CR_DIR"
  printf '  或：   %s/packaging/linux/install.sh && ~/.local/bin/rvpn-browser\n' "$ROOT"
fi
[ "$DO_ANDROID" = "1" ] && printf '  APK：  %s/android/app/build/outputs/apk/\n' "$ROOT"
