# 源码构建状态（Chromium / Thorium）

> 本文件记录**本机**的构建进度，便于中断后续跑。最终交付状态以 README 为准。

## 环境

| 项目 | 值 |
|---|---|
| Thorium 检出 | `~/src/thorium`（gz83/thorium，固定 Chromium `156.0.8078.28`） |
| Chromium 源码 | `~/chromium/src`（blobless + 无历史克隆） |
| depot_tools | `~/depot_tools` |
| 构建输出 | `~/chromium/src/out/rvpn-browser`（另有 Thorium 默认的 `out/thorium`） |
| 构建参数 | `browser/scripts/build-linux.sh --low-cost`（无 PGO/ThinLTO/CFI，symbol_level=0） |
| 磁盘占用 | 源码约 47 GB；`out/` 约 2 GB（构建中增长） |

## 已完成

1. ✅ `fetch --nohooks --no-history chromium`（首次拉取）
2. ✅ `gclient sync --nohooks --no-history -D --force`（依赖）
3. ✅ `python3 version.py`（切到 `156.0.8078.28`，再次 sync + `gclient runhooks`，生成构建文件）
4. ✅ `python3 setup.py`（Thorium 的 181 条补丁 + 覆盖文件）
5. ✅ `browser/scripts/apply-rvpn-patches.py`（30 个文件：品牌 / 22 个图标 /
   内置扩展 16 个资源 + `component_loader.cc` 注册）
6. ⏳ `gn gen out/rvpn-browser` + `autoninja -C out/rvpn-browser chrome`（进行中）

```
[25082/75810] 2h21m49.31s S CXX obj/third_party/blink/public/mojom/mojom_core_blink/service_worker_container.mojom-blink.o
```

> 说明：V8 / Blink 阶段的单个编译单元很重，4 核机器上每个文件需要数十秒，因此总耗时以**小时**计；ninja/siso 支持断点续跑，中断不会丢失已完成的工作。

## 断点续跑

```bash
scripts/resume-all.sh --check     # 看当前状态（进度 / 进程 / 产物 / 验证结果）
scripts/resume-all.sh             # 续跑编译 → 验证 → 打包
scripts/resume-all.sh --publish   # 上面全部 + 上传到 GitHub Release（需要 GITHUB_TOKEN）

# 只要编译（增量，几十秒内回到断点）
CR_DIR=~/chromium/src browser/scripts/build-linux.sh --low-cost
```

### 自动验证

`scripts/watch-build.sh` 会等待编译进程结束，然后自动检查产物、运行
`browser/test/built_browser_test.mjs`（品牌 / 内置扩展 / 原生消息 / ZJU 连通性），
结果写入 `/tmp/rvpn-build-result.txt`：

```
BUILD_OK                    # 构建成功且验证通过
BUILD_OK_BUT_VERIFY_FAILED  # 构建成功但验证有问题（详见 /tmp/rvpn-built-browser-test.log）
BUILD_FAIL                  # 编译报错（日志尾部会写入报告）
```

编译本身是增量的：中断后重新运行 `autoninja` 会从断点继续，不会重头再来。

## 构建完成后

```bash
browser/scripts/verify-build.sh            # 品牌 / 内置扩展 / 原生消息 / 连通性
browser/scripts/run-linux.sh               # 用构建产物启动
```

## 踩过的坑（复现时注意）

| 现象 | 原因与处理 |
|---|---|
| `gclient sync` 报 `LockError ... gsutil_5.35` | 两个 sync 进程并发引导 gsutil；确保同一时间只有一个 sync |
| `fatal: shallow file has changed since we read it` | 同样源于并发 git 操作；清理残留进程后重试 |
| `RESOURCE_EXHAUSTED ... 429` | chromium.googlesource.com 短时限流；改用 `gclient sync ... -j1` 串行重试 |
| `pkill -f <模式>` 把自己的 shell 也杀了 | 模式出现在自身命令行里；按 `/proc/<pid>/cmdline` 精确匹配 |
| 测试通过但代码没生效 | 旧二进制：`core/test/native_client.py` 现在会在源码更新时自动重建 |
