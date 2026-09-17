#!/usr/bin/env bash
# GitHub release 资产断点续传下载器（沙箱专用）
#
# ════════════════════════════════════════════════════════════════
# 【环境事实（2026-09-17 实测，别再怀疑是脚本问题）】
#
# github.com:443 的 **HTTPS 被强烈限流**：
#   连打 8 次 `curl -sSL -r 0-0 -o /dev/null <release 资产 URL>`，
#   只有 3 次返回 206，其余 5 次是
#       curl: (35) OpenSSL SSL_connect: SSL_ERROR_SYSCALL in connection to github.com:443
#   且成功率随时间衰减（前 4 次 2 成、后 4 次 1 成）。
#
# ⚠️ 但 **SSH 通路完全不受影响**：
#   `git push` 走 20.205.243.166 的 SSH 协议，与 HTTPS 限流是两条独立通路。
#   所以「推不上去」和「下不下来」是两个不同的问题，别混为一谈。
#
# 同域名对比（同一时刻）：
#   github.com:443   → 大概率 000（SSL_ERROR_SYSCALL）
#   codeload.github.com → 301（正常）
#   raw.githubusercontent.com → 301（正常）
#
# ════════════════════════════════════════════════════════════════
# 【设计决策：为什么不探长度】
#
# 探长度（`curl -r 0-0` 读 content-range）本身就是一次碰运气的请求。
# 而且这里有两个额外陷阱：
#
#   陷阱 1：重定向链会产生**两段 header**。
#     第 1 段来自 github.com 的 302（`content-length: 0`，**无 content-range**）；
#     第 2 段来自 release-assets 的 206（**才有 content-range**）。
#     ⇒ 必须取**最后一段**。
#
#   陷阱 2：★ `curl -D - | grep | tail | sed` 这种一行式**会偶发返回空**。
#     完全相同的命令分步执行能拿到 `content-range: bytes 0-0/35883835`，
#     写成一行管道就拿到空串 —— grep 匹配后提前退出，
#     curl 写 header 时收到 SIGPIPE。
#     ⇒ 若要探长度，必须 `-D <文件>` 落盘再读，彻底不用管道。
#
# 既然调用方通常已经从 release API / `gh release view` 知道字节数，
# 就**直接传入**，把有限的「成功窗口」全用在真正的下载上。
# ════════════════════════════════════════════════════════════════
#
# 用法：
#   fetch-release-asset.sh <url> <目标路径> <总字节数> [块大小] [每块重试次数]
# 示例：
#   fetch-release-asset.sh \
#     https://github.com/OWNER/REPO/releases/download/preview/app.apk \
#     /workspace/preview/app.apk 35883835 262144 25
#
# 退出码：0 = 下载完整且 zip 自检通过；1 = 放弃
set -uo pipefail

if [ $# -lt 3 ]; then
  sed -n '1,50p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'
  exit 2
fi

URL="$1"
DST="$2"
TOTAL="$3"
CHUNK="${4:-$((256*1024))}"
MAXTRY="${5:-25}"
MAXFAILED_BLOCKS="${6:-15}"

mkdir -p "$(dirname "$DST")"

# 已存在且完整则直接跳过（幂等，可重复调用）
if [ -f "$DST" ] && [ "$(stat -c%s "$DST" 2>/dev/null || echo 0)" = "$TOTAL" ]; then
  echo "已存在且字节数吻合，跳过下载：$DST"
else
  echo "开始下载：$TOTAL 字节 / 块 $((CHUNK/1024))KB / 每块最多 $MAXTRY 次"
  offset=0; failed=0

  while [ "$offset" -lt "$TOTAL" ]; do
    end=$((offset + CHUNK - 1))
    [ "$end" -ge "$TOTAL" ] && end=$((TOTAL - 1))
    want=$((end - offset + 1))

    got=0
    for try in $(seq 1 "$MAXTRY"); do
      BLOB=$(mktemp)
      # ⚠️ 先下到独立临时文件再 dd 进目标：
      #    直接 `dd of=DST seek=` 接 curl 输出的话，一次半截写入就会污染目标文件，
      #    而 `conv=notrunc` 不会因为「这次写少了」而回滚。
      if timeout 90 curl -sSL -r "${offset}-${end}" -o "$BLOB" "$URL" 2>/dev/null; then
        sz=$(stat -c%s "$BLOB" 2>/dev/null || echo 0)
        if [ "$sz" -eq "$want" ]; then
          dd if="$BLOB" of="$DST" bs=1 seek="$offset" conv=notrunc status=none 2>/dev/null
          rm -f "$BLOB"; got=1; break
        fi
      fi
      rm -f "$BLOB"
      sleep 0.3
    done

    if [ "$got" -eq 1 ]; then
      offset=$((offset + want))
      if [ $((offset % (4*1024*1024))) -lt "$CHUNK" ]; then
        echo "  $((offset * 100 / TOTAL))%  ($offset / $TOTAL)"
      fi
    else
      failed=$((failed+1))
      echo "  ⚠️ 偏移 $offset 连续 $MAXTRY 次失败（累计 $failed 块），继续"
      if [ "$failed" -ge "$MAXFAILED_BLOCKS" ]; then
        echo "❌ 失败块过多，放弃。已下 $offset / $TOTAL"
        exit 1
      fi
    fi
  done
fi

# ── 完整性自证 ────────────────────────────────────────────────
# ⚠️ 绝不能只核对字节数就宣布成功：限流下的部分写入可能凑巧凑够长度。
#    APK 本质是 zip，用 zipfile 全量校验才是有意义的证据。
echo "字节数：$(stat -c%s "$DST" 2>/dev/null || echo 0) / $TOTAL"
python3 - "$DST" <<'PY'
import zipfile, sys
p = sys.argv[1]
try:
    z = zipfile.ZipFile(p)
    bad = z.testzip()
    print("zip 校验：", "OK" if bad is None else f"损坏条目 {bad}")
    print("条目数：", len(z.namelist()))
    sys.exit(0 if bad is None else 1)
except Exception as e:
    print("zip 校验失败：", e)
    sys.exit(1)
PY
rc=$?
if [ "$rc" -eq 0 ]; then
  echo "✅ 完整且校验通过：$DST"
else
  echo "⚠️ zip 校验未通过，不要当作可用产物：$DST"
fi
exit $rc
