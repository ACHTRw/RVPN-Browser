# RVPN Core 控制协议 v1（冻结）

本文件是 RVPN Browser 各组件之间的**唯一接口契约**。Go 核心（`core/`）、桌面扩展（`browser/extension/`）、
Android 前端（Java/Kotlin）都必须严格按此实现，任何一方不得单方面扩展语义。

- 桌面（Linux）：扩展通过 **Chrome Native Messaging**（stdio，4 字节小端长度前缀 + UTF-8 JSON）与 `rvpn-core` 通信。
- Android：Java 通过 gomobile 绑定调用 `rvpncore.Request(json) string`，语义完全相同。
- 独立调试：`rvpn-core --stdio` 亦可直接作为 native host 运行。

所有消息均为 JSON 对象，**一问一答**。核心不主动推送；前端通过 `status` 轮询增量事件（建议 500 ms）。

## 1. 请求 / 响应信封

请求：

```json
{ "id": 12, "cmd": "start", "config": { ... } }
```

响应（成功）：

```json
{ "id": 12, "ok": true, "data": { ... } }
```

响应（失败）：

```json
{ "id": 12, "ok": false, "error": "human readable message", "code": "AUTH_FAILED" }
```

- `id`：整数或字符串，由前端生成，响应原样返回；核心保证顺序处理，但前端应按 `id` 匹配。
- 未知命令必须返回 `{"ok": false, "error": "unknown command: xxx"}`，**不得**崩溃或退出。
- 任何命令都不得 panic 导致进程退出；核心必须捕获 panic 并转为 `ok:false`。

## 2. 命令表

| cmd | 参数 | 返回 `data` | 说明 |
|---|---|---|---|
| `hello` | — | `HelloData` | 能力探测、版本、平台 |
| `start` | `config: Config` | `{state: State}` | 异步启动；立即返回，进度通过事件上报 |
| `stop` | — | `{state: State}` | 停止并释放全部资源（幂等） |
| `status` | `since: int` | `StatusData` | 状态 + 自 `since` 起的新事件 |
| `answer` | `AnswerRequest` | `{accepted: bool}` | 回答验证码 / 短信码 / 二次认证 |
| `authInfo` | `{server, port}` | `{list: [AuthInfoItem]}` | 非 ZJU 用户探测 aTrust 登录域与认证方式 |
| `selftest` | `{server, port}` | `SelfTestData` | 探测服务器 TCP 可达性与延迟（诊断） |
| `setDebug` | `{debug: bool}` | `{debug: bool}` | 打开/关闭调试日志 |
| `shutdown` | — | `{}` | 停止 VPN 并退出进程 |

### 2.1 HelloData

```json
{
  "version": "1.0.0",
  "coreVersion": "1.0.0",
  "zjuConnectVersion": "v1.x.x",
  "platform": "linux",            // linux | android | darwin | windows
  "arch": "amd64",
  "capabilities": ["atrust", "easyconnect", "zju", "customServer", "captcha",
                   "clickCaptcha", "sms", "totp", "authInfo", "selftest", "clientData"]
}
```

### 2.2 Config（`start` 的 `config` 字段）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `userType` | string | `"zju"` | `zju` = 浙江大学用户；`other` = 非 ZJU 用户（自定义服务器） |
| `protocol` | string | `"atrust"` | `atrust`（L3，ZJU 用户默认）\| `easyconnect`（L2） |
| `server` | string | 见下 | VPN 服务器地址（不含端口） |
| `port` | int | `443` | 服务器端口 |
| `username` | string | — | 账号（ZJU 为学号/工号） |
| `password` | string | — | 密码 |
| `loginDomain` | string | `"Radius"` | aTrust 登录域；ZJU 固定 `Radius`，非 ZJU 由 `authInfo` 探测 |
| `authType` | string | `""` | `""`(自动) \| `auth/psw` \| `auth/cas` \| `auth/smsCheckCode` \| `auth/httpsOauth2` |
| `phone` | string | — | 短信认证手机号（含国家码），`auth/smsCheckCode` 时必填 |
| `totpSecret` | string | — | TOTP 密钥（可选，用于自动完成动态口令） |
| `clientDataFile` | string | 平台默认 | aTrust 会话持久化文件路径；空字符串 = 不持久化 |
| `socksBind` | string | `"127.0.0.1:0"` | SOCKS5 监听地址；端口 0 = 自动分配 |
| `httpBind` | string | `"127.0.0.1:0"` | HTTP 代理监听地址；`""` = 不启用 |
| `proxyAll` | bool | `false` | 全部流量走 VPN（调试用；默认仅 ZJU 资源走 VPN） |
| `disableZJUConfig` | bool | `false` | 不使用内置 ZJU 规则（非 ZJU 用户应设 `true`） |
| `skipDomainResource` | bool | `false` | 不使用服务端下发的域名资源规则 |
| `disableRemoteDNS` | bool | `false` | 使用本地 DNS 而非远端 DNS |
| `remoteDNSServer` | string | `"auto"` | 远端 DNS 服务器，`auto` = 由服务端下发 |
| `secondaryDNSServer` | string | `"auto"` | 备用 DNS |
| `dnsTTLSeconds` | int | `3600` | DNS 缓存 TTL |
| `debug` | bool | `false` | 调试日志 |

`server` 默认值规则（用户未显式填写时）：

- `userType=zju` 且 `protocol=atrust` → `vpn.zju.edu.cn`
- `userType=zju` 且 `protocol=easyconnect` → `rvpn.zju.edu.cn`
- `userType=other` → 必填，缺失则 `start` 返回 `ok:false, code:"INVALID_CONFIG"`

非 ZJU 用户（`userType=other`）时核心自动施加如下等价配置：
`disableZJUConfig=true`、`skipDomainResource=true`、`disableMultiLine=true`（easyconnect）、
`remoteDNSServer` 保持调用方给定值（推荐 `auto`）。

### 2.3 State

```json
{
  "phase": "idle",              // idle | connecting | authenticating | connected | error | stopping
  "message": "正在连接 vpn.zju.edu.cn:443",
  "since": 1712345678901,       // 该 phase 的起始时间（毫秒）
  "protocol": "atrust",
  "userType": "zju",
  "server": "vpn.zju.edu.cn:443",
  "socksAddr": "127.0.0.1:38271",  // 未监听时为 ""
  "httpAddr": "127.0.0.1:38272",   // 未启用时为 ""
  "clientIP": "10.10.1.23",        // 未获取时为 ""
  "connectedAt": 0,                // 毫秒时间戳，未连接为 0
  "lastError": ""                  // 最近一次错误信息
}
```

`phase` 状态机：`idle → connecting → authenticating → connected`；
任意阶段可转 `error`（伴随 `lastError`）；`stop` 后回到 `idle`。

### 2.4 StatusData

```json
{
  "state": State,
  "events": [Event, ...],
  "nextSince": 42
}
```

前端下次携带 `since = nextSince`。`since` 小于最早的可用序号时，核心返回其拥有的全部事件（不报错）。

### 2.5 Event

```json
{
  "seq": 42,
  "ts": 1712345678901,
  "kind": "log",                // log | state | challenge | challengeDone | error
  "level": "info",              // debug | info | warn | error
  "message": "SOCKS5 监听于 127.0.0.1:38271",
  "challenge": { ... }          // 仅 kind=challenge 时存在
}
```

### 2.6 Challenge（`kind="challenge"`）

```json
{
  "id": "c7",
  "type": "text",               // text | clickCode | smsCode | totp | radiusCode | secondaryAuth | externalLogin
  "message": "请输入短信验证码",
  "imageDataUrl": "data:image/png;base64,...",  // 图形/点选验证码时存在
  "canSkipSecondaryAuth": false,
  "loginUrl": "",               // externalLogin 时的登录 URL
  "deadline": 1712345978901     // 超时时间戳（毫秒），无超时为 0
}
```

`type` 语义与期望的 `answer`：

| type | 期望字段 | 说明 |
|---|---|---|
| `text` | `code` | 文本图形验证码 |
| `clickCode` | `points`（可选 `width`/`height`） | 点选验证码，坐标为图片原始像素 |
| `smsCode` / `totp` / `radiusCode` | `code` | 验证码 / 动态口令 |
| `secondaryAuth` | `code` 或 `skipSecondaryAuth:true` | 二次认证；`skipSecondaryAuth` 对应上游 `$` 前缀语义 |
| `externalLogin` | `code`（回调 URL） | CAS / OAuth2 外部登录，用户把回调 URL 粘回 |

### 2.7 AnswerRequest

```json
{
  "challengeId": "c7",
  "code": "123456",
  "points": [{"x": 12, "y": 34}],
  "width": 300,
  "height": 150,
  "skipSecondaryAuth": false
}
```

未知 `challengeId` → `{"ok": false, "error": "...", "code": "NO_SUCH_CHALLENGE"}`。

### 2.8 AuthInfoItem / SelfTestData

```json
{ "loginDomain": "Radius", "authType": "auth/psw", "authName": "上网账号", "loginUrl": "" }
```

```json
{ "reachable": true, "latencyMs": 18, "server": "vpn.zju.edu.cn:443", "error": "" }
```

## 3. 错误码

| code | 含义 |
|---|---|
| `INVALID_CONFIG` | 配置非法（缺字段、端口越界等） |
| `ALREADY_RUNNING` | 已处于运行/连接中 |
| `NOT_RUNNING` | 未运行却调用 `stop`/`answer`（`stop` 应幂等返回成功） |
| `AUTH_FAILED` | 认证失败 |
| `NETWORK_ERROR` | 网络不可达 / TLS 失败 |
| `SERVER_REJECTED` | 服务端拒绝（资源为空、账号锁定等） |
| `TIMEOUT` | 超时 |
| `INTERNAL` | 其他内部错误 |

## 4. 代理行为约定

1. 核心启动成功后，`socksAddr` / `httpAddr` 必须立即可用（监听早于事件上报）。
2. 默认分流规则：仅命中服务端资源（以及 ZJU 用户的内置 `zju.edu.cn` / `10.0.0.0/8` 规则）的流量走 RVPN，
   其余直连——保证浏览器在 RVPN 模式下仍能正常访问公网。
3. 前端负责把浏览器代理指向 `socksAddr`（优先）并在断开时清除代理设置。
4. 核心必须支持 `stop` 后再次 `start`（可重复启停，不泄漏监听端口与 goroutine）。

## 5. 安全约定

1. 代理只监听回环地址（`127.0.0.1`）；若用户显式指定非回环地址，核心必须拒绝并返回 `INVALID_CONFIG`。
2. 密码、TOTP 密钥不得写入日志；日志中一律以 `***` 代替。
3. `clientDataFile` 以 `0600` 权限写入。
