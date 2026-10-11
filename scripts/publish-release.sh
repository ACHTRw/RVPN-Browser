#!/usr/bin/env bash
# Publish a GitHub release with the built artefacts in dist/.
#
#   GITHUB_TOKEN=ghp_xxx scripts/publish-release.sh v1.0.0
#   GITHUB_TOKEN=... scripts/publish-release.sh v1.0.0 --draft
#
# The token needs "Contents: write" on the target repository. It is never
# written to disk or echoed.

set -euo pipefail

REPO="${RVPN_REPO:-ACHTRw/RVPN-Browser}"
SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST="$ROOT/dist"

TAG="${1:-}"
shift || true
DRAFT=""
[ "${1:-}" = "--draft" ] && DRAFT=true

[ -n "$TAG" ] || { echo "usage: $0 <tag> [--draft]" >&2; exit 2; }
[ -n "${GITHUB_TOKEN:-}" ] || { echo "!! GITHUB_TOKEN is not set" >&2; exit 2; }
[ -d "$DIST" ] || { echo "!! $DIST does not exist (build something first)" >&2; exit 2; }

api() { curl -sS -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

echo "==> checking release $TAG in $REPO"
RELEASE_ID="$(api "https://api.github.com/repos/$REPO/releases/tags/$TAG" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)"

if [ -z "$RELEASE_ID" ]; then
  echo "==> creating release $TAG"
  BODY="$(cat <<'EOF'
## RVPN Browser v1.0.0

内置浙江大学 RVPN（aTrust / EasyConnect）支持的浏览器，支持 Android 与 Linux。

### 下载

| 平台 | 文件 | 说明 |
|---|---|---|
| Android | `rvpn-browser-1.0.0-android.apk` | 已签名 release APK（arm64-v8a / armeabi-v7a / x86_64），minSdk 26 |
| Linux | `rvpn-browser-1.0.0-linux-amd64.tar.gz` | 便携包：RVPN 引擎 + 浏览器扩展 + 启动器 + 安装脚本 |
| Linux | `rvpn-core-linux-amd64` / `rvpn-core-linux-arm64` | 独立 RVPN 引擎（可配合任意 Chromium/Thorium 使用） |
| Android | `rvpn-core-android-arm64` | 独立 RVPN 引擎（arm64，供自行集成的应用使用） |

### Linux 安装（不需要编译 Chromium）

```bash
tar xzf rvpn-browser-1.0.0-linux-amd64.tar.gz
cd rvpn-browser-1.0.0-linux-amd64
./packaging/linux/install.sh          # 安装到 ~/.local
~/.local/bin/rvpn-browser            # 启动（自动使用已安装的 Thorium/Chromium）
```

### Android 安装

```bash
adb install rvpn-browser-1.0.0-android.apk
```

首次启动后打开「RVPN 设置」填写账号，或在主界面菜单中开启 RVPN 模式。

### 说明

- 普通模式与 RVPN 模式可随时切换；RVPN 模式下仅命中 RVPN 资源规则的流量走隧道，其余直连。
- ZJU 用户默认使用 aTrust 协议；非 ZJU 用户可自定义服务器 / 登录域 / 认证方式。
- 浏览器内核的源码构建（Thorium/Chromium）请见仓库 `docs/BUILD.md`，脚本已就绪。
EOF
)"
  RESPONSE="$(api -X POST "https://api.github.com/repos/$REPO/releases" \
    -d "$(python3 -c '
import json,sys
print(json.dumps({"tag_name": sys.argv[1], "name": "RVPN Browser " + sys.argv[1],
                  "body": sys.stdin.read(), "draft": sys.argv[2] == "true"}))' "$TAG" "${DRAFT:-false}" <<<"$BODY")")"
  RELEASE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' <<<"$RESPONSE")"
  [ -n "$RELEASE_ID" ] || { echo "!! failed to create release: $RESPONSE" >&2; exit 1; }
fi

echo "==> uploading assets to release $RELEASE_ID"
for file in "$DIST"/*; do
  name="$(basename "$file")"
  case "$name" in
    *.idsig) continue ;;
  esac
  echo "    $name ($(du -h "$file" | cut -f1))"
  # Replace an existing asset with the same name.
  EXISTING="$(api "https://api.github.com/repos/$REPO/releases/$RELEASE_ID/assets" \
    | python3 -c "import json,sys;print(next((a['id'] for a in json.load(sys.stdin) if a['name']=='$name'),''))" 2>/dev/null || true)"
  if [ -n "$EXISTING" ]; then
    api -X DELETE "https://api.github.com/repos/$REPO/releases/assets/$EXISTING" >/dev/null || true
  fi
  api -X POST -H "Content-Type: application/octet-stream" \
      --data-binary "@$file" \
      "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$name" \
      | python3 -c 'import json,sys; d=json.load(sys.stdin); print("      ->", d.get("browser_download_url") or d.get("message"))'
done

echo "==> done: https://github.com/$REPO/releases/tag/$TAG"
