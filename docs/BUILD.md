# 构建指南

本仓库有三个可独立构建的产物：

| 产物 | 目录 | 依赖 | 典型耗时 |
|---|---|---|---|
| RVPN 引擎 `rvpn-core` | `core/` | Go 1.25+ | 1 分钟 |
| Android 应用 APK（WebView 壳） | `android/` | JDK 17+、Android SDK 35、NDK（生成 AAR 用） | 3–5 分钟 |
| 浏览器内核（Thorium/Chromium 源码） | `browser/` | 完整 Chromium 工具链 | **数小时～十几小时** |

---

## 1. RVPN 引擎（必做）

```bash
cd core
go build -o bin/rvpn-core ./cmd/rvpn-core          # 本机
GOOS=linux   GOARCH=arm64 go build -o bin/rvpn-core-linux-arm64 ./cmd/rvpn-core
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -o bin/rvpn-core-android-arm64 ./cmd/rvpn-core
go test ./...                                      # 单元测试
```

依赖固定在上游提交 `github.com/mythologyli/zju-connect v1.3.2-0.20261008095431-c19eb4ed34b9`。
国内建议：

```bash
export GOPROXY=https://goproxy.cn,direct
```

> `core/go.mod` 中保留了上游需要的 `replace inet.af/netaddr`：Go 只会应用**主模块**的 replace，
> 依赖模块里的 replace 会被忽略，删除它会导致构建出与上游不一致的行为。

## 2. Android 应用（WebView 壳 + RVPN 引擎）

### 2.1 生成 gomobile 绑定（AAR）

```bash
export ANDROID_NDK_HOME=$HOME/android-ndk-r27c      # 或 Chromium 自带的 third_party/android_ndk
export PATH=$PATH:$(go env GOPATH)/bin
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
core/scripts/build-android-aar.sh                    # 产物：core/build/rvpncore.aar
```

### 2.2 构建 APK

```bash
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
android/build.sh                                     # assembleDebug + assembleRelease
```

`android/build.sh` 会自动把 `core/build/rvpncore.aar` 复制到 `android/app/libs/`。
未配置签名的 release APK 使用 debug 签名（仅用于自测）。

## 3. 浏览器内核（Thorium / Chromium 源码构建）

> ⚠️ 这是本仓库最重的步骤：需要约 **60 GB 磁盘**、**8 GB 以上内存**，
> 4 核机器上首次全量编译通常需要 **8–20 小时**。建议在空闲机器上执行。

### 3.1 准备源码

```bash
export THOR_DIR=$HOME/src/thorium
export CR_DIR=$HOME/chromium/src
export DEPOT_TOOLS_DIR=$HOME/depot_tools
export PATH=$PATH:$DEPOT_TOOLS_DIR
export https_proxy=http://<proxy>:<port>   # 中国大陆访问 googlesource/CIPD 通常需要代理

git clone --recursive https://github.com/gz83/thorium.git "$THOR_DIR"
cd "$THOR_DIR"
python3 get_repo.py --no-history            # depot_tools + fetch chromium + gclient sync
python3 version.py                          # 切到 Thorium 固定 tag，运行 hooks
python3 setup.py                            # 叠加 Thorium 补丁与覆盖文件
```

`.gclient` 中按需设置目标平台（同时构建 Linux 与 Android 时）：

```python
target_os = ["linux", "android"]
```

### 3.2 应用 RVPN 集成补丁

```bash
browser/scripts/apply-rvpn-patches.py --chromium-src "$CR_DIR" --thorium-root "$THOR_DIR"
```

该脚本是**幂等**的，并且对每一处修改做**严格校验**（找不到预期代码就报错退出，不会静默改坏源码）：

1. 品牌：产品名 `RVPN Browser`、`chrome/app/theme/chromium/BRANDING`、可执行文件名；
2. 图标：由 `icon.svg` 生成的多尺寸 PNG 覆盖 Chromium 图标资源；
3. 原生消息宿主：在 `chrome_paths_linux.cc` 中追加 RVPN Browser 专用查找目录；
4. 内置扩展：把 `browser/extension` 注册为浏览器自带扩展；
5. Android：注册 RVPN 代理配置服务与 RVPN 设置界面。

### 3.3 生成构建配置并编译

```bash
cd "$CR_DIR"
cp "$THOR_DIR/args.gn" out/rvpn-browser/args.gn     # Thorium 官方参数（含 PGO/ThinLTO/CFI）
# 时间/内存有限时改用低开销参数（见下）
gn gen out/rvpn-browser
autoninja -C out/rvpn-browser chrome
```

低开销参数（推荐在 4–8 核机器上使用，产物功能完整但体积略大、无 PGO 优化）：

```gn
is_official_build = true
is_debug = false
symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
use_thin_lto = false
is_cfi = false
chrome_pgo_phase = 0
enable_widevine = false
bundle_widevine_cdm = false
enable_linux_installer = false
proprietary_codecs = true
ffmpeg_branding = "Chrome"
```

Android（`browser/scripts/build-android.sh` 已封装）：

```bash
gn gen out/rvpn-browser-android --args='target_os="android" target_cpu="arm64" is_official_build=true symbol_level=0 use_thin_lto=false is_cfi=false chrome_pgo_phase=0 enable_widevine=false'
autoninja -C out/rvpn-browser-android chrome_public_apk
```

### 3.4 运行

```bash
out/rvpn-browser/rvpn-browser                       # Linux
browser/scripts/run-linux.sh                        # 自动带上扩展与原生消息宿主
```

## 4. Linux 安装（不编译内核也可用）

```bash
packaging/linux/install.sh                 # ~/.local（推荐，无需 sudo）
packaging/linux/install.sh --system        # /usr/local（注册系统级原生消息宿主）
packaging/linux/install.sh --uninstall
```

安装内容：`rvpn-core`、扩展目录、启动器、桌面项、多尺寸图标，以及
`~/.config/{chromium,google-chrome,thorium,rvpn-browser}/NativeMessagingHosts/com.achtrw.rvpn.json`。

## 5. 常见构建问题

| 现象 | 处理 |
|---|---|
| `gclient sync` 卡住 / 超时 | 设置 `https_proxy`，或重试 `gclient sync --nohooks --force`；CIPD 失败可重跑 `gclient runhooks` |
| `gn gen` 报缺少 sysroot | 运行 `gclient runhooks` 或 `build/install-build-deps.sh` |
| 编译 OOM | 降低并发：`autoninja -C out/xxx chrome -j 2`；增加 swap；`use_thin_lto=false` |
| Android 缺 SDK/NDK | `gclient runhooks` 会下载到 `third_party/android_sdk/public`、`third_party/android_ndk` |
| `gomobile: no NDK` | 设置 `ANDROID_NDK_HOME`，或安装 `sdkmanager "ndk;27.0.12077973"` |
| Go 拉依赖慢 | `export GOPROXY=https://goproxy.cn,direct` |
