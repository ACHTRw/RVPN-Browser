# 测试与验证

本项目的验证策略：**能用自动测试证明的，绝不靠"看起来对"**。所有不依赖真实账号的环节都有自动化测试；
需要真实账号的环节给出明确的手工验收清单。

## 1. Go 单元测试（无需网络）

```bash
cd core && go test ./...          # 24 个用例
cd core && go test -race ./...    # 竞态检测
```

覆盖范围：

| 用例 | 验证内容 |
|---|---|
| `TestNormalizeZJUDefaults` | ZJU 用户默认 aTrust + `vpn.zju.edu.cn`；切换 EasyConnect 时自动用 `rvpn.zju.edu.cn`；端口/监听地址默认值 |
| `TestNormalizeNonZJU` | 非 ZJU 用户必须填服务器；自动关闭 ZJU 内置规则 |
| `TestNormalizeRejects` | 非法用户类型/协议/端口、缺密码、短信缺手机号、非回环监听、非法直连代理全部被拒 |
| `TestSummaryHidesCredentials` | 日志摘要不含密码、用户名打码 |
| `TestRedaction` | 已登记密钥在日志中被替换为 `***`；过短的字符串不参与替换（避免破坏日志） |
| `TestEventBusIncremental` | 事件序号单调、增量拉取正确、环形缓冲有界（不无限增长） |
| `TestEventBusStateTransitions` | 状态机 idle→connecting→connected→error→idle，`since` 语义正确 |
| `TestChallengeRelay` | 验证码事件携带 data URL；回答被正确回传给协议层；重复回答返回 `NO_SUCH_CHALLENGE` |
| `TestChallengeKinds` | 短信/TOTP/Radius/二次认证/点选/外部登录六种挑战的类型映射 |
| `TestChallengeCloseCancels` | 会话停止时挂起的挑战立即返回（不会卡住协议 goroutine 5 分钟） |
| `TestImageDataURLFormats` | PNG/JPEG/GIF/WebP/SVG 格式嗅探 |
| `TestHandlerDispatch` | hello/status/stop/answer 命令语义、未知命令拒绝、`stop` 幂等 |
| `TestHandlerPanicSafety` | 畸形 JSON、缺 config 不会让宿主崩溃 |
| `TestNativeMessagingFraming` | Chrome 原生消息 4 字节长度前缀帧格式 |
| `TestTCPControlServer*` | 回环 TCP 控制通道：令牌校验、错误令牌断连、公网地址被拒 |
| `TestStatusBoundsEventBatch` | 单次 status 返回的事件数量有上界（保证原生消息帧不超 1 MB） |
| `TestHandlerConcurrentRequests` | 并发请求安全 |

## 2. 端到端协议测试（需要校园网，**不需要真实账号**）

```bash
core/test/e2e_dummy_login.py
```

用**故意错误的账号密码**打真实 ZJU 服务器，验证到"认证环节"为止的整条链路。实测结果（浙江大学校内网络）：

```
[PASS] hello returns capabilities  -- version=1.0.0
[PASS] unknown command is rejected without crashing
[PASS] non-ZJU user without server is rejected
[PASS] non-loopback proxy bind is rejected
[PASS] stop is idempotent
[PASS] selftest reaches vpn.zju.edu.cn  -- latency=10ms
[PASS] selftest reports unreachable hosts
[PASS] authInfo discovers aTrust login methods
       -- [{"authName":"上网账号","authType":"auth/psw","loginDomain":"Radius"},
           {"authName":"IDC运维账号","authType":"auth/psw","loginDomain":"local"}]
[PASS] start (atrust) is accepted
[PASS] challenge (atrust) carries a decodable captcha image
       -- type=clickCode bytes=13175 magic=ffd8ffe0        # 真实服务端下发的点选验证码(JPEG)
[PASS] answer (atrust) is accepted by the core
[PASS] login (atrust) continues after the answered challenge
[PASS] stop (atrust) returns to idle
[PASS] start (easyconnect) is accepted
[PASS] login (easyconnect) with invalid credentials fails cleanly
       -- "Invalid username or password!"                   # 真实 rvpn.zju.edu.cn 的应答
[PASS] stop (easyconnect) returns to idle
[PASS] restart: first start accepted / second start accepted / second stop returns to idle
20/20 checks passed
```

意义：aTrust 的 TLS 握手、manifest/authConfig 拉取、密码提交流程、**真实验证码的下发与回传**、
EasyConnect 的登录请求与错误解析、以及"失败后重启不泄漏监听端口"都得到了真实验证。

## 2.1 反复启停泄漏测试

```bash
core/test/stress_restart.py --cycles 6
```

连续 6 次「连接 → 停止」（其中一半在验证码挂起状态直接停止），检查文件描述符 / 线程 / 内存增长：

```
cycle 0..5: stop=0.00s fds=5 threads=8 rss=17-18MB
growth: fds +1, threads +7, rss +17MB        # 均为运行时一次性初始化
[PASS] file descriptors stay bounded
[PASS] thread count stays bounded
[PASS] memory usage stays bounded
[PASS] no listening sockets left after stop (fds=5)
6 restart cycles completed without leaks
```

该测试在开发过程中真实抓到了两个缺陷并已修复：

1. **停止时挂起 10 秒**：等待验证码的协议 goroutine 在会话结束时没有被唤醒（补上 `done` 通道取消）；
2. **每个会话泄漏一个套接字**：上游 aTrust 客户端持有自己的 HTTP transport，其 keep-alive 连接从不关闭；
   本项目的 `rvpn/underlay.go` 用跟踪式底层拨号器接管所有连接，停止时统一关闭，
   修复后 fds 在 6 个循环中恒定不变。

`core/test/native_client.py` 会在 Go 源码比二进制更新时自动重新构建，避免"用旧二进制测新代码"。

## 3. 控制通道测试（Android 使用）

```bash
rvpn-core --tcp 127.0.0.1:0 --token-file /tmp/rvpn.token
```

已验证：令牌认证成功/失败、`hello`/`selftest`/`stop`/`answer` 命令、行分隔 JSON 帧、非回环地址被拒。

## 4. 扩展静态检查

```bash
cd browser/extension
python3 -c "import json;[json.load(open(f)) for f in ['manifest.json','_locales/zh_CN/messages.json','_locales/en/messages.json']]"
for f in *.js lib/*.js; do node --check "$f"; done
```

已验证：manifest 合法、所有 JS 通过 `node --check`、`__MSG_*` / `data-i18n` / `RVPN.t()` 引用的
155 个字符串键在中英文语言包中全部存在且键集一致、四个图标尺寸正确、
manifest `key` 推导出的扩展 ID 与原生消息宿主 `allowed_origins` 完全一致
（`lhgepbmchpealfajciakglacaedkjenh`）。

## 5. 浏览器集成测试

`browser/test/` 提供自动化集成测试：用一个 **模拟原生消息宿主**（本地 SOCKS5 转发 + 协议实现）
驱动真实 Chromium，验证：

1. 扩展被正确加载、Service Worker 启动；
2. 扩展能通过原生消息连上宿主（`hello`）；
3. 切到 RVPN 模式后 `chrome.proxy` 被设置为 `socks5://127.0.0.1:<port>`；
4. 浏览器实际流量确实经过该 SOCKS 端口（模拟宿主记录到的连接数 > 0）；
5. 切回普通模式 / 断开后代理被清除。

```bash
browser/scripts/test-extension.sh --chromium /path/to/chrome
```

实测结果（Chromium 143 / Linux，X11）：

```
[PASS] extension service worker starts  -- lhgepbmchpealfajciakglacaedkjenh
[PASS] extension page loads  -- chrome-extension://lhgepbmchpealfajciakglacaedkjenh/options.html
[PASS] extension reaches the native host (hello)
[PASS] setMode(rvpn) is accepted
[PASS] session reaches the connected state  -- socks=127.0.0.1:42621 clientIP=10.10.0.99
[PASS] chrome.proxy points at the core SOCKS5 port  -- {"host":"127.0.0.1","port":42621,"scheme":"socks5"}
[PASS] browser traffic is proxied through the SOCKS5 listener  -- body=ok log=8 entr(y|ies)
[PASS] challenge reaches the UI  -- type=text image=data:image/png;base64,...
[PASS] answer is delivered to the core  -- {"ok":true,"data":{"accepted":true}}
[PASS] answered challenge is cleared from the UI
[PASS] switching back to normal mode clears the proxy  -- mode=system
11/11 checks passed          # 含 --challenge 的完整验证码往返
```

崩溃恢复模式（`--kill-core`）额外验证：RVPN 模式下杀掉核心进程后，扩展会在数秒内**清除代理**
并给出暂停原因，绝不留下"代理还在但引擎已死"的上网故障状态（10/10 通过）。

`browser/scripts/test-extension.sh` 会依次运行这三种模式。

第 7 项是**关键证据**：测试页访问一个无法解析的域名 `http://rvpn-integration-test.invalid/`，
浏览器只可能通过代理拿到页面内容（模拟宿主记录了 8 次 SOCKS5 CONNECT，页面内容为 `RVPN-MOCK-OK`），
因此可以证明 RVPN 模式下浏览器流量确实走了本地代理。

### 5.1 真实引擎冒烟测试（安装后的实际链路）

```bash
node browser/test/real_core_test.mjs --chromium /path/to/chrome
```

用**真实的 rvpn-core**（安装脚本部署的那个二进制）替换模拟宿主，验证安装后的实际链路：

```
[PASS] extension reaches the real rvpn-core  -- 1.0.0 protocol-lib v1.3.2-0.20261008095431-c19eb4ed34b9
[PASS] core reports its capabilities  -- atrust,easyconnect,zju,customServer,captcha,...
[PASS] selftest reaches vpn.zju.edu.cn:443  -- latency=22ms
[PASS] authInfo discovers aTrust login methods  -- Radius/auth/psw local/auth/psw
4/4 checks passed
```

### 5.2 扩展逻辑单元测试（无需浏览器）

```bash
node browser/test/harness/test-background.js   # 124/124 通过（chrome.* 模拟 + 协议级假核心）
node browser/test/harness/test-pages.js        #  74/74 通过（DOM 桩上运行 popup.js / options.js）
```

覆盖：ZJU / 非 ZJU 的 start 配置、`chrome.proxy` 精确设置与清除、模式持久化与恢复、
宿主缺失时的降级与原因上报、断言失败的重连退避（1 s→30 s）、超时、日志上限、
点选验证码坐标换算、六种挑战处理、设置清洗等。

## 5.3 APK 静态验证

```bash
ANDROID_HOME=~/android-sdk android/tools/verify-apk.sh dist/rvpn-browser-1.0.0-android.apk
```

实测（发布 APK，16 项全部通过）：

```
OK   package name (com.rvpnbrowser.browser)      OK   RVPN settings activity declared
OK   versionName (1.0.0) / targetSdkVersion (35) OK   RVPN engine service declared
OK   minSdkVersion (26)                          OK   engine runs in its own process (:rvpn)
OK   INTERNET / FOREGROUND_SERVICE / dataSync    OK   service is a foreground service
OK   Go engine bundled for arm64-v8a/armeabi-v7a/x86_64
OK   APK signature valid (SHA-256 24ced165...)
```

## 6. 需要真实账号的手工验收清单

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 桌面：安装后启动 `rvpn-browser`，打开扩展弹窗 | 显示"普通模式"，无代理配置 |
| 2 | 填写 ZJU 账号 → 连接 | 状态依次 connecting → authenticating → connected，显示客户端内网 IP |
| 3 | 若弹出点选验证码 | 点击图片字符位置后提交，登录继续 |
| 4 | 访问 `https://www.zju.edu.cn` | 正常打开；访问 `https://www.baidu.com` 也正常（直连） |
| 5 | 访问校内资源（如图书馆、教务） | 正常打开（走 RVPN） |
| 6 | 关闭浏览器再打开 | 自动恢复 RVPN 模式（会话未过期时免登录） |
| 7 | 切到"普通模式" | 校内资源不可访问，公网正常，`chrome://net-internals` 无代理 |
| 8 | 断开后立即重启连接 | 端口重新分配，无"端口占用"错误 |
| 9 | 故意输错密码 | 明确报错（用户名或密码错误），不会卡在"正在连接" |
| 10 | Android：安装 APK → RVPN 设置 → 连接 → 浏览 | 状态栏出现前台服务通知；校内站点可访问；其它 App 不受影响 |
| 11 | Android：杀进程后重开 | 自动重连并恢复 RVPN 模式 |

## 7. 已知未覆盖项

- **未用真实账号完成登录**：本项目开发环境没有可用账号，隧道内的实际数据转发由第 6 节手工验收覆盖。
- **未做 Android 真机/模拟器运行测试**：构建环境无 KVM，无法运行 x86 模拟器；APK 构建通过，
  运行时行为按第 6 节验收（Android 端逻辑与桌面端共用同一套核心协议，核心部分已被自动化覆盖）。
- **Chromium 源码构建**属于长耗时任务（数小时～十几小时），其产物验证依赖构建完成后的手工冒烟测试。
