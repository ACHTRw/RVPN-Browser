# RVPN Browser — 浏览器扩展（`browser/extension/`）

RVPN Browser 的桌面前端：一个 **Manifest V3** Chromium/Thorium 扩展，提供 RVPN 的
登录界面、验证码交互与浏览器代理控制，并通过 **Chrome Native Messaging** 驱动本机
Go 核心 `rvpn-core`。

- 接口契约：[`../../docs/PROTOCOL.md`](../../docs/PROTOCOL.md)（v1，冻结）
- 固定扩展 ID：`lhgepbmchpealfajciakglacaedkjenh`（由 `manifest.json` 的 `key` 决定）
- 原生消息宿主名：`com.achtrw.rvpn`
- 无构建步骤、无 npm、无外部资源，完全离线可用

---

## 1. 目录结构

```
browser/extension/
├── manifest.json          # MV3 清单（固定 key/ID，权限仅 5 项，无 host 权限）
├── background.js          # Service Worker：原生消息客户端、代理、模式、轮询、验证码队列
├── lib/common.js          # 共享库：i18n、存储、Config 构造、日志（importScripts + <script>）
├── popup.html/.css/.js    # 工具栏弹窗：模式切换、状态、验证码、快速登录、日志
├── options.html/.css/.js  # 登录设置页：完整配置、authInfo 探测、连通性自检、核心诊断
├── _locales/zh_CN/        # 默认语言（default_locale）
├── _locales/en/           # 英文
├── icons/icon{16,32,48,128}.png
└── README.md
```

## 2. 安装 / 加载

### 2.1 随 RVPN Browser 一起（推荐）

`packaging/linux/install.sh` 会把本目录复制到 `~/.local/share/rvpn-browser/extension`
（或 `/usr/local/share/rvpn-browser/extension`），并写入原生消息宿主清单：

```
~/.config/{chromium,google-chrome,thorium,rvpn-browser}/NativeMessagingHosts/com.achtrw.rvpn.json
```

启动器 `rvpn-browser` 用 `--load-extension=<extension 目录>` 加载扩展，扩展 ID 由
`manifest.json` 里的 `key` 固定，与宿主清单 `allowed_origins` 中的
`chrome-extension://lhgepbmchpealfajciakglacaedkjenh/` 完全对应。

### 2.2 手动加载（任意 Chromium 系浏览器）

1. 打开 `chrome://extensions`，开启右上角「开发者模式」；
2. 「加载已解压的扩展程序」→ 选择本目录 `browser/extension`；
3. 确认扩展 ID 显示为 `lhgepbmchpealfajciakglacaedkjenh`
   （若不同，说明 `manifest.json` 的 `key` 被改动，原生宿主会拒绝连接）；
4. 安装宿主清单（二选一）：
   - 运行 `./packaging/linux/install.sh`（会同时安装 `rvpn-core` 与宿主清单）；
   - 或手写 `~/.config/chromium/NativeMessagingHosts/com.achtrw.rvpn.json`：

```json
{
  "name": "com.achtrw.rvpn",
  "description": "RVPN Browser engine (ZJU RVPN / aTrust / EasyConnect)",
  "path": "/home/<user>/.local/bin/rvpn-core",
  "type": "stdio",
  "allowed_origins": ["chrome-extension://lhgepbmchpealfajciakglacaedkjenh/"]
}
```

5. `rvpn-core` 必须可执行（`chmod +x`），路径必须是绝对路径；改完清单后重启浏览器。

## 3. 扩展如何与核心通话

| 环节 | 实现 |
|---|---|
| 连接 | `chrome.runtime.connectNative("com.achtrw.rvpn")`，stdio + 4 字节小端长度前缀（帧化由 Chrome 负责） |
| 握手 | 连接后立即发 `hello`，失败即判定宿主不可用 |
| 请求 | `request(cmd, params)` → `{id, cmd, ...params}`，按 `id` 匹配响应，一问一答 |
| 超时 | 默认 20 s；`start` 120 s；`status` 10 s；`selftest`/`authInfo`/`stop` 30 s |
| 重连 | 断线自动重连，退避 1 s → 2 s → 4 s … 上限 30 s；收到正常响应即复位为 1 s |
| 轮询 | 弹窗打开时 500 ms，后台 RVPN 模式下 2 s；`since = nextSince` 增量取事件 |
| 代理 | RVPN：`{mode:"fixed_servers", rules:{singleProxy:{scheme:"socks5", host, port}, bypassList:["localhost","127.0.0.1","[::1]"]}}`；普通：`chrome.proxy.settings.clear({scope:"regular"})` |
| 保活 | MV3 Worker 空闲会被回收：`chrome.alarms` 30 s 看门狗负责重启轮询 / 重连 / 清理残留代理 |

用到的命令：`hello`、`start`、`stop`、`status`、`answer`、`authInfo`、`selftest`、`setDebug`。
`shutdown` 只由核心侧使用，扩展不主动杀进程。

**所有 `chrome.*` 回调都检查 `chrome.runtime.lastError`；所有请求都 resolve 成
`{ok:false, error, code}` 而从不 reject**，因此核心缺失时不会产生未处理的 Promise 拒绝。

## 4. 使用说明

### 4.1 两种模式

- **普通模式**：不使用 VPN，清除扩展设置的代理（`scope: "regular"`，只动扩展自己的设置）。
- **RVPN 模式**：`start` 核心 → 拿到 `state.socksAddr` → 把浏览器代理指向该 SOCKS5 端口。
  端口永远取自核心回报的 `status`，不会自行猜测。

模式保存在 `chrome.storage.local`，浏览器重启时由 `chrome.runtime.onStartup` 自动恢复：
保存的是 RVPN 就重连并重新应用代理；核心不可达则**回落到普通模式**、记录原因
（弹窗顶部横幅 + 系统通知 + 日志），并清除代理——不会留下指向死端口的代理。

运行中核心崩溃：立即清除代理（避免浏览器整体断网）、挂起原因写入状态、
按退避自动重连，恢复后重新应用代理。

### 4.2 登录设置（弹窗 + 设置页）

- **用户类型**：浙江大学用户（`zju`，默认）/ 非 ZJU 用户（`other`）
- **ZJU**：协议默认 `atrust`（L3，服务器自动填 `vpn.zju.edu.cn`），
  可选 `easyconnect`（L2，高级选项，服务器自动填 `rvpn.zju.edu.cn`），登录域固定 `Radius`
- **非 ZJU**：服务器、端口必填，另有登录域与认证方式；「探测登录方式」调用 `authInfo`
  并把返回的 `[{loginDomain, authType, authName, loginUrl}]` 渲染成可点击选项；
  核心会自动施加 `disableZJUConfig=true` / `skipDomainResource=true`（扩展也会显式下发）；
  认证方式选 `auth/smsCheckCode` 时必须填手机号
- **高级**：TOTP 密钥、SOCKS/HTTP 端口（留空=自动，即 `127.0.0.1:0`）、调试日志、
  「全部流量走 VPN」（`proxyAll`）
- **连通性自检**：调用 `selftest`，显示可达性与延迟

**密码存储**：保存在 `chrome.storage.local`（明文，界面有明确警告）。
取消勾选「保存密码」后，密码不写入存储，只保留在 Service Worker 内存中用于本次会话，
浏览器重启后需重新输入。

### 4.3 验证码 / 二次认证（`challenge` 事件）

弹窗顶部高亮显示；弹窗未打开时会发系统通知并尝试打开弹窗（`chrome.action.openPopup()`，
不可用时退化为独立弹窗窗口）。**待处理验证码持久化在 `chrome.storage.local`**，
弹窗关闭、Worker 被回收都不会丢失。

| `type` | 界面 | `answer` 载荷 |
|---|---|---|
| `text` | 显示验证码图片 + 输入框 | `code` |
| `clickCode` | `<canvas>` 显示图片，可多次点选，支持撤销/清空 | `points`（**原图像素坐标**）+ `width`/`height` |
| `smsCode` / `totp` / `radiusCode` | 输入框 | `code` |
| `secondaryAuth` | 输入框 + 「跳过二次认证」复选框 | `code` 或 `skipSecondaryAuth: true` |
| `externalLogin` | 可点击 `loginUrl`（新标签页打开）+ 粘贴回调 URL | `code` |

`clickCode` 坐标换算：canvas 位图尺寸等于原图尺寸，CSS 尺寸可能被缩放，
点击时按 `getBoundingClientRect()` 的比例换算回原图像素坐标，提交时附带 `width`/`height`。

### 4.4 日志与诊断

- 事件日志最多保留 **500** 条，最近 **200** 条持久化到 `chrome.storage.local`（弹窗关闭后仍在）
- 「复制诊断信息」复制扩展版本、宿主名与连接状态、核心 `hello`、`state`、挂起/回落原因、
  代理地址与最近 200 行日志；**密码与 TOTP 密钥会被脱敏**
- 核心缺失时弹窗照常打开，显示红色诊断横幅（含宿主名与具体错误）+ 重试 / 复制诊断按钮

## 5. 权限说明

| 权限 | 用途 |
|---|---|
| `proxy` | 设置/清除浏览器代理（RVPN / 普通模式） |
| `storage` | 保存设置、模式、日志、待处理验证码 |
| `nativeMessaging` | 与 `rvpn-core` 通信 |
| `notifications` | 验证码提醒、RVPN 回落通知 |
| `alarms` | MV3 Worker 保活看门狗 |

`host_permissions` 为空数组：代理 API 与原生消息都不需要任何主机权限，
扩展不会读取、注入或拦截任何网页内容。

## 6. 安全说明

- SOCKS/HTTP 代理只监听回环地址（核心侧强制；扩展只使用核心回报的地址）
- 密码 / TOTP 密钥不写日志（核心侧 `***`，扩展侧 `RVPN.redact()` 再兜底一次）
- 密码明文存于 `chrome.storage.local`，仅本机用户可读；不勾选保存则只留在内存
- 复制到剪贴板的诊断信息始终经过脱敏

## 7. 故障排查

| 现象 | 处理 |
|---|---|
| 弹窗显示「未检测到本机 rvpn-core」 | 检查宿主清单路径与 `allowed_origins`、`rvpn-core` 是否可执行、扩展 ID 是否为 `lhgepbmchpealfajciakglacaedkjenh` |
| 模式切到 RVPN 后立刻回到普通模式 | 看弹窗横幅的回落原因与日志；多为服务器 / 账号 / 验证码问题 |
| 浏览器重启后没有自动连上 | 核心可能尚未就绪；重试 3 次后回落。点「重新连接」或再切一次 RVPN 模式 |
| 提示「RVPN 代理已暂停」 | 核心进程退出或端口变化，扩展已清除代理并在自动重连 |
| 需要给开发者看的现场 | 弹窗 / 设置页「复制诊断信息」 |

调试：`chrome://extensions` → 本扩展 → 「Service Worker」查看后台日志；
`chrome://net-internals/#proxy` 查看当前生效的代理设置。

## 8. 开发与自检（无构建步骤）

```bash
cd browser/extension
python3 -c "import json; json.load(open('manifest.json'))"   # 清单是合法 JSON
for f in $(find . -name '*.js'); do node --check "$f"; done  # 所有 JS 可解析
```

- 所有 UI 文案走 `chrome.i18n.getMessage`；HTML 用 `data-i18n` / `data-i18n-placeholder` /
  `data-i18n-title` 标注，新增文案必须同时补 `_locales/zh_CN` 与 `_locales/en`
- 图标由仓库根目录 `icon.svg` 生成：
  `rsvg-convert -w 128 -h 128 -o icons/icon128.png ../../icon.svg`（16/32/48/128）

---

## English summary

MV3 Chromium extension that renders the RVPN UI and drives the Go core
`rvpn-core` over native messaging (`com.achtrw.rvpn`). Two modes — *normal*
(no proxy override) and *RVPN* (browser proxy pointed at the core's SOCKS5
port, the port always taken from `status`). The mode is persisted and restored
on browser start, with an explained fallback to normal mode when the core is
unreachable. Challenges (`text` / `clickCode` / `smsCode` / `totp` /
`radiusCode` / `secondaryAuth` / `externalLogin`) are queued, persisted and
surfaced in the popup, via notification and via the toolbar badge. All strings
are localized (zh_CN default, en fallback), no host permissions are requested,
and there is no build step.
