# 第三方组件与致谢 / Third-party notices

RVPN Browser 站在以下项目的肩膀上：

## zju-connect（AGPL-3.0）
- 项目：https://github.com/Mythologyli/zju-connect
- 用途：RVPN 协议实现（aTrust L3、EasyConnect L2）、资源解析、用户态网络栈、DNS 解析与 SOCKS5 基础库。
- 本仓库 `core/` 以 Go module 依赖方式链接上游代码（`github.com/mythologyli/zju-connect`），
  因此 `core/` 与其衍生的二进制受 **AGPL-3.0** 约束。

## Thorium（BSD-3-Clause）
- 项目：https://github.com/gz83/thorium （上游 https://github.com/Alex313031/thorium）
- 用途：Chromium 构建脚本与补丁集，作为浏览器内核的构建基础。
- 本仓库不包含 Thorium 源码，仅提供在其之上叠加的集成补丁与构建脚本。

## Chromium
- 项目：https://chromium.googlesource.com/chromium/src
- 许可：BSD-3-Clause 及第三方许可（详见 Chromium 源码树中的 LICENSE）。

## 其他 Go 依赖
`core/go.mod` 中列出的所有模块（gVisor、wireguard-go、sing-tun、go-socks5、utls 等）遵循其各自许可。

## 免责声明
本项目仅供浙江大学校园网用户及有合法授权的用户访问其有权访问的资源使用。
请遵守浙江大学网络使用规定与相关法律法规。作者不对使用本软件造成的任何后果负责。
