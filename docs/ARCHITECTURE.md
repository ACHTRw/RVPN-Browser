# 架构

## 1. 总体结构

```
                      ┌──────────────────────────────┐
   桌面 (Linux)        │  浏览器内核 (Thorium/Chromium) │
                      │   ┌────────────────────────┐  │
                      │   │ RVPN 扩展 (MV3, JS)     │  │
                      │   │  · 模式切换 chrome.proxy│  │
                      │   │  · 登录 / 状态 / 验证码 │  │
                      │   └───────────┬────────────┘  │
                      └───────────────┼───────────────┘
                                      │ Chrome Native Messaging (stdio, 4B 长度前缀 + JSON)
                      ┌───────────────▼───────────────┐
                      │        rvpn-core (Go)         │
                      │  ┌─────────┬─────────┬──────┐ │
                      │  │ 会话/事件│ 挑战应答 │代理  │ │
                      │  └────┬────┴────┬────┴──┬───┘ │
                      │       │         │       │     │
                      │  ┌────▼─────────▼───────▼───┐ │
                      │  │  zju-connect 协议栈       │ │
                      │  │  aTrust(L3) / EasyConnect │ │
                      │  └──────────────────────────┘ │
                      └───────┬───────────────┬───────┘
                              │ SOCKS5/HTTP   │ 回环 TCP + 令牌（Android）
                              ▼               ▼
                       浏览器流量        :rvpn 进程 / 其它前端
```

## 2. 组件

### 2.1 `core/`（Go，RVPN 引擎）

| 文件 | 职责 |
|---|---|
| `rvpn/config.go` | 配置模型、默认值、校验（含回环地址强制、非 ZJU 用户规则） |
| `rvpn/engine.go` | 与 zju-connect 的接线：underlay → 协议客户端 → 资源 → 网络栈 → DNS → 拨号器 → 本地代理 |
| `rvpn/session.go` | 生命周期（start/stop 幂等、可重复启停）、状态机、事件增量轮询 |
| `rvpn/events.go` | 有界事件环形缓冲 + 状态；同时把上游 `log` 输出重定向进事件流 |
| `rvpn/challenge.go` | 实现 `authchallenge.Handler`，把验证码/短信/TOTP/二次认证/外部登录转成事件并等待回答 |
| `rvpn/proxy.go` | 自建 SOCKS5 + HTTP 代理（显式 listener、真实端口上报、优雅关闭） |
| `rvpn/api.go` | 命令分发（hello/start/stop/status/answer/authInfo/selftest/setDebug/shutdown） |
| `rvpn/nativehost.go` | Chrome 原生消息帧格式；回环 TCP 控制服务（令牌 + 行分隔 JSON） |
| `cmd/rvpn-core` | 可执行入口：原生消息宿主 / `--once` / `--selftest` / `--tcp` / `--log-file` |
| `mobile/rvpncore` | gomobile 绑定：`Request()` 与 `StartControlServer()` |

**为什么自建代理层**：上游 `service.ServeSocks5/ServeHTTP` 内部自行创建 listener、失败时 `panic`，
并通过 `internal/hook_func` 注册清理函数（外部模块无法调用）。浏览器需要在"连接成功"事件之前就拿到
**真实端口**，并且必须能可靠地停止/重启并释放端口，因此代理层由本项目管理生命周期，其余（协议、资源、
网络栈、DNS、拨号规则）全部复用上游实现。

**事件模型**：核心不推送，前端按 `since` 序号轮询增量事件。这样桌面扩展的 Service Worker 被挂起、
Android 进程被回收都不会丢状态；验证码图片以 data URL 随事件下发。挑战通过独立通道等待回答，
超时（5 分钟）后返回错误给协议层。

**分流规则**：默认只把命中服务端下发的资源（IP/网段 + 域名 + DNS 资源）的流量送进 RVPN；
ZJU 用户使用 EasyConnect 协议时额外注入 `zju.edu.cn`（全部端口）与 `10.0.0.0/8`，与上游 CLI 行为一致。

### 2.2 `browser/extension/`（桌面界面）

Manifest V3，固定扩展 ID `lhgepbmchpealfajciakglacaedkjenh`（manifest `key` 指定），
权限仅 `proxy / storage / nativeMessaging / notifications / alarms`，不申请任何 host 权限。

- 普通模式：`chrome.proxy.settings.clear()`；
- RVPN 模式：`start` 成功后读取核心回报的 SOCKS5 地址，写入 `fixed_servers` 规则
  （`bypassList` 含 localhost），断开时清除；
- 浏览器重启后按持久化模式自动恢复，失败则回落到普通模式并给出明确原因。

### 2.3 `android/`（Android 应用）

- 引擎运行在独立进程 `:rvpn`（`RvpnService` 前台服务 + gomobile 绑定），主进程通过
  `files/rvpn/control.json` 中的地址与令牌连接核心的回环 TCP 控制服务；
- RVPN 模式用 `androidx.webkit.ProxyController` 设置**本应用**代理（不改变系统代理，不影响其它应用）；
- 引擎崩溃不会带走浏览器进程，服务重启后界面自动重连。

### 2.4 从源码构建的浏览器

`browser/patches/` + `browser/scripts/apply-rvpn-patches.py` 在 Thorium 补丁之上叠加：

1. 品牌：产品名 `RVPN Browser`、图标（由 `icon.svg` 生成多尺寸 PNG）、可执行文件名；
2. 内置扩展：把 `browser/extension` 作为浏览器自带扩展加载；
3. 原生消息宿主路径：让浏览器在自己的配置目录中查找 `com.achtrw.rvpn.json`；
4. Android：把 RVPN 代理配置注入 Chromium 的 `ProxyConfigService` 链（读取本应用写入的
   `rvpn/proxy.json`），并把 RVPN 设置界面接入 APK。

## 3. 数据流（一次登录）

```
扩展/Android UI ──start(config)──▶ rvpn-core
rvpn-core: underlay.New → atrust/easyconnect.NewClient → Setup()
     ├─ 需要验证码 ──▶ 事件 challenge(text/clickCode, imageDataUrl) ──▶ UI 显示
     │                                   UI ──answer(code/points)──▶ 核心 ──▶ 协议层
     ├─ 需要短信/TOTP/二次认证 ──▶ 同上
     ├─ 成功 ──▶ 资源解析 → 网络栈 → DNS → 拨号器 → SOCKS5/HTTP 监听（127.0.0.1:随机端口）
     └─ state=connected（含 socksAddr / httpAddr / clientIP）
UI: chrome.proxy / ProxyController ──▶ 所有标签页流量走本地代理 ──▶ 按规则分流 ──▶ RVPN 或直连
```

## 4. 可靠性与兼容性设计

| 关注点 | 做法 |
|---|---|
| 启停安全 | `stop` 幂等；引擎每次 start 新建、stop 全量释放（listener/goroutine/客户端）；失败路径统一 `close()` |
| 崩溃隔离 | Android 引擎独立进程；桌面端为独立原生消息宿主进程，浏览器与核心互不拖累 |
| 断连恢复 | 扩展/APP 自动重连（指数退避），状态与事件可重放；模式持久化并在重启后自动恢复 |
| 端口冲突 | 默认 `127.0.0.1:0` 由内核分配，端口上报给前端，避免固定端口冲突 |
| 凭据泄漏 | 日志正则脱敏；`client_data.json` 0600；非回环监听直接拒绝 |
| 兼容性 | 协议只依赖 Chrome 78+/WebView 78+ 的稳定 API（`chrome.proxy`、`ProxyController`）；Android 最低 API 26 |
| 上游漂移 | `core/go.mod` 固定 zju-connect 提交（`v1.3.2-0.20261008095431-c19eb4ed34b9`），升级需重跑 `core/test/e2e_dummy_login.py` |
