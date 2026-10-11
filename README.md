# RVPN Browser

> 内置浙江大学 RVPN（aTrust / EasyConnect）支持的浏览器，支持 **Android** 与 **Linux**。

RVPN Browser = **浏览器内核** + **RVPN 引擎（rvpn-core）** + **RVPN 图形界面**。三个部分通过一份冻结的
JSON 协议（[docs/PROTOCOL.md](docs/PROTOCOL.md)）解耦，因此同一个引擎可以驱动桌面扩展、Android 应用，
以及从源码构建的浏览器内置界面。

- 浏览器内核：基于 [gz83/thorium](https://github.com/gz83/thorium)（Chromium fork，Linux/Android 源码构建）；
  在尚未完成源码构建时，也可直接驱动任意已安装的 Thorium / Chromium / Chrome。
- RVPN 引擎：基于 [Mythologyli/zju-connect](https://github.com/Mythologyli/zju-connect) 的协议实现
  （aTrust L3 / EasyConnect L2、资源分流、远端 DNS、验证码与人机校验流程）。

## 功能

| 功能 | 说明 |
|---|---|
| 普通模式 | 不启用任何代理，与普通浏览器完全一致 |
| RVPN 模式 | 一键登录 RVPN，浏览器流量按服务端资源规则分流；未命中规则的流量直连 |
| ZJU 用户 | 默认使用 **aTrust 协议**（`vpn.zju.edu.cn`），登录域 `Radius`；可切换到 EasyConnect（`rvpn.zju.edu.cn`） |
| 非 ZJU 用户 | 自定义服务器 / 端口 / 登录域 / 认证方式，支持"探测登录方式"（`authInfo`） |
| 交互式认证 | 图形验证码（文本 / 点选，点选坐标按原图分辨率回传）、短信验证码、TOTP 动态口令、二次认证跳过、CAS/OAuth2 外部登录回调 |
| 会话保持 | aTrust 会话写入 `client_data.json`（0600），重启浏览器后免重复认证 |
| 诊断 | 连通性自检（`selftest`）、实时日志、复制诊断信息 |
| 平台 | Linux（x64/arm64）、Android（arm64/armv7；WebView 应用已可直接构建，源码构建的浏览器 APK 见下文） |

## 下载

| 平台 | 文件 | 说明 |
|---|---|---|
| Android | `rvpn-browser-1.0.0-android.apk` | 已签名 release APK（arm64-v8a / armeabi-v7a / x86_64），minSdk 26 |
| Linux | `rvpn-browser-1.0.0-linux-amd64.tar.gz` | 便携包：RVPN 引擎 + 扩展 + 启动器 + 安装脚本 |
| Linux | `rvpn-core-linux-amd64` / `-arm64` | 独立 RVPN 引擎 |
| Android | `rvpn-core-android-arm64` | 独立 RVPN 引擎（arm64） |

见 [Releases](https://github.com/ACHTRw/RVPN-Browser/releases)。

## 快速开始（Linux，不需要编译 Chromium）

```bash
# 1) 构建 RVPN 引擎
cd core && go build -o bin/rvpn-core ./cmd/rvpn-core && cd ..

# 2) 安装引擎、扩展、启动器、图标、原生消息宿主
packaging/linux/install.sh            # 默认安装到 ~/.local
#   packaging/linux/install.sh --system   # 安装到 /usr/local（需要 sudo）

# 3) 启动
~/.local/bin/rvpn-browser
```

启动后在工具栏点击 **RVPN** 扩展图标 → 选择"RVPN 模式" → 填写账号密码 → 连接。

> 未编译 Chromium 时，启动器会自动使用系统已安装的 Thorium / Chromium / Chrome 作为内核，
> 并自动注册原生消息宿主与扩展，因此 RVPN 功能可立即使用。

## 从源码构建浏览器（Thorium / Chromium）

```bash
# 一次性准备（拉取 Chromium 源码 + Thorium 补丁，耗时长、占用约 60 GB）
browser/scripts/setup-thorium.sh          # get_repo.py + version.py + setup.py

# 应用 RVPN 集成补丁（品牌、内置扩展、原生消息路径、Android 代理服务）
browser/scripts/apply-rvpn-patches.py --chromium-src ~/chromium/src

# 构建 Linux 版
browser/scripts/build-linux.sh            # 产物：~/chromium/src/out/rvpn-browser/

# 构建 Android 版（需要 SDK/NDK，由 gclient hooks 自动准备）
browser/scripts/build-android.sh          # 产物：out/rvpn-browser-android/apks/*.apk
```

详见 [docs/BUILD.md](docs/BUILD.md)。

## Android

```bash
# RVPN 引擎的 gomobile 绑定（需要 Android NDK）
core/scripts/build-android-aar.sh

# 构建 APK
android/build.sh                          # 产物：android/app/build/outputs/apk/**/*.apk
```

Android 应用把 RVPN 引擎运行在独立的 `:rvpn` 进程（崩溃隔离），通过回环 TCP + 令牌与界面通信；
RVPN 模式下用 `ProxyController` 把**本应用**的所有 WebView 流量指向本地代理，不影响系统全局代理。

## 验证状态

| 项目 | 状态 | 证据 |
|---|---|---|
| RVPN 引擎（Go） | ✅ 通过 | 24 个单元测试（含 `-race`）、20/20 真实服务器协议测试、6 轮启停无泄漏 |
| 交互式认证 | ✅ 通过 | 真实 ZJU aTrust 服务端下发点选验证码并成功回传（见 [docs/TESTING.md](docs/TESTING.md)） |
| 桌面扩展逻辑 | ✅ 通过 | 124 + 74 项无浏览器逻辑测试（模拟 `chrome.*` 与协议级假核心） |
| 浏览器集成 | ✅ 通过 | 真实 Chromium 中 8/8：模式切换、`chrome.proxy` 生效、**流量确实经代理**、断开清除代理 |
| 真实引擎链路 | ✅ 通过 | 浏览器 → 扩展 → 原生消息 → 真实 `rvpn-core`：4/4（含连通真实 `vpn.zju.edu.cn:443`） |
| Linux 安装/启动器 | ✅ 通过 | 发布包实测：解压 → 安装 → 启动器生成正确命令行 |
| Android APK | ✅ 构建通过 | debug + release（R8/资源压缩）均成功，内含三个 ABI 的 Go 引擎；已签名 |
| Android 运行 | ⚠️ 待真机验证 | 构建环境无 KVM，无法跑模拟器；运行路径与桌面共用同一核心协议 |
| Chromium 源码构建 | ⏳ 进行中 | 拉取约 30 GB 源码/依赖中；补丁脚本已对真实源码做过 dry-run 校验 |

## 仓库结构

```
core/                 RVPN 引擎（Go，基于 zju-connect）
  cmd/rvpn-core/      可执行文件：Chrome 原生消息宿主 + 调试 CLI + 回环 TCP 控制服务
  rvpn/               配置、事件、挑战应答、代理、会话、协议分发
  mobile/rvpncore/    gomobile 绑定（Android）
  test/               协议与端到端测试（含真实 ZJU 服务器探测）
browser/extension/    浏览器扩展：RVPN 图形界面 + chrome.proxy 模式切换（MV3）
browser/patches/      Thorium/Chromium 源码补丁与集成脚本
android/              Android 应用（Java，Gradle）
packaging/linux/      Linux 安装脚本、启动器、桌面图标、原生消息宿主清单
docs/                 协议、架构、构建、使用、测试文档
```

## 安全说明

- 代理只监听 `127.0.0.1`，核心会拒绝任何非回环的监听地址。
- 日志中的密码 / TOTP 密钥一律以 `***` 替换；`client_data.json` 以 `0600` 写入。
- 密码保存在浏览器本地（扩展 `chrome.storage.local` / Android SharedPreferences），**未做额外加密**，
  可在设置中关闭"记住密码"。
- 原生消息宿主只允许固定扩展 ID（`lhgepbmchpealfajciakglacaedkjenh`）连接；回环 TCP 模式需要一次性令牌。

## 许可

本项目采用 **AGPL-3.0**（因为核心链接了 AGPL-3.0 的 [zju-connect](https://github.com/Mythologyli/zju-connect)）。
Thorium 部分遵循其 BSD-3-Clause 许可；详见 [NOTICE.md](NOTICE.md)。
