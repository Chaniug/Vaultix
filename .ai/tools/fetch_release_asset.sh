#!/usr/bin/env bash
# 分块断点续传下载 GitHub release 资产（APK）—— 沙箱专用
#
# 为什么需要它（issues #105）：
#   沙箱里「整体下载」GitHub release 资产**非常不可靠**：
#     - 可能 240s 只到 3.4MB 就超时截断
#     - 可能直接 `http=000`（连不上，90s 放弃）
#   但同一 URL 用 **range 请求** `curl -r 0-0` 能稳定拿到 `http=206`。
#   ⇒ CDN 可达、有数据，坏的是「整文件连续传输」。
#   ⇒ 解法：拆成 1MB 小块，逐块重试，失败就回滚到上一次确认的偏移。
#
# 用法：
#   bash .ai/tools/fetch_release_asset.sh <url> <输出路径>
# 例：
#   bash .ai/tools/fetch_release_asset.sh \
#     https://github.com/Chaniug/Vaultix/releases/download/preview/app-full-debug.apk \
#     /tmp/app-full-debug.apk
#
# 下完**务必**用 zipfile 校验（脚本会自动做）：
#   ❌ 别用 `file` —— 它只看头几个字节，截断的 APK 照样报 "Android package"。
#   ✅ 用 zipfile 打开，能列 namelist 且含 AndroidManifest.xml 才算完整。

set -u

URL="${1:?用法: fetch_release_asset.sh <url> <输出路径>}"
OUT="${2:?用法: fetch_release_asset.sh <url> <输出路径>}"
CHUNK=$((1024 * 1024))   # 1MB
MAX_RETRY=5
MAX_CONSEC_FAIL=8

echo "[1/3] 探测总长（range 请求读 Content-Range）…"
TOTAL=""
for i in $(seq 1 8); do
  TOTAL=$(curl -sL --max-time 45 -r 0-0 -D - -o /dev/null "$URL" 2>/dev/null \
          | tr -d '\r' | grep -i '^content-range' | sed 's#.*/##')
  [ -n "$TOTAL" ] && break
  echo "  [probe $i] 未拿到 Content-Range，重试…"
  sleep 2
done
if [ -z "$TOTAL" ]; then
  echo "❌ 探长失败。先检查 hosts：getent hosts release-assets.githubusercontent.com"
  echo "   看到 198.18.0.x 就是 hosts 丢了（见 conventions/8.7-环境.md）"
  exit 1
fi
echo "  TOTAL=$TOTAL 字节"

echo "[2/3] 分块下载（每块 $((CHUNK / 1024))KB，最多 $MAX_RETRY 次重试/块）…"
: > "$OUT"
off=0
consec_fail=0
while [ "$off" -lt "$TOTAL" ]; do
  end=$((off + CHUNK - 1))
  [ "$end" -ge "$TOTAL" ] && end=$((TOTAL - 1))
  ok=0
  for t in $(seq 1 "$MAX_RETRY"); do
    if curl -sL --max-time 60 -r "${off}-${end}" "$URL" >> "$OUT" 2>/dev/null; then
      cur=$(stat -c%s "$OUT" 2>/dev/null || echo 0)
      if [ "$cur" -ge $((end + 1)) ]; then ok=1; break; fi
    fi
    truncate -s "$off" "$OUT"   # 回滚到上一次确认成功的偏移
    sleep 1
  done
  if [ "$ok" != "1" ]; then
    consec_fail=$((consec_fail + 1))
    echo "  [chunk $off] $MAX_RETRY 次均失败（连续失败 $consec_fail）"
    [ "$consec_fail" -ge "$MAX_CONSEC_FAIL" ] && { echo "❌ 连续失败过多，放弃"; exit 2; }
  else
    consec_fail=0
  fi
  off=$((off + CHUNK))
  printf "\r  进度 %s / %s" "$(stat -c%s "$OUT")" "$TOTAL"
done
echo ""

echo "[3/3] 校验（zipfile，不是 file）…"
python3 - "$OUT" <<'PY'
import hashlib, sys, zipfile
p = sys.argv[1]
try:
    z = zipfile.ZipFile(p)
except Exception as e:
    print("❌ 不是完整 zip:", e); sys.exit(1)
names = z.namelist()
bad = z.testzip()
print("  ✅ 完整 zip，条目数:", len(names))
print("  AndroidManifest.xml:", "AndroidManifest.xml" in names)
print("  CRC 校验:", ("❌ 坏条目 " + str(bad)) if bad else "✅ 全部通过")
print("  sha256:", hashlib.sha256(open(p, "rb").read()).hexdigest())
if "AndroidManifest.xml" not in names or bad:
    sys.exit(1)
PY
echo "✅ 完成：$OUT"
