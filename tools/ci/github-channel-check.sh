#!/usr/bin/env bash
# GitHub 通道自愈脚本（沙箱内）
# 用途：沙箱重启后 /etc/hosts 会被清空，导致所有 github 域名解析到 198.18.0.x 保留网段，
#       SSH 与 HTTPS 全部失效。本脚本从阿里 DoH 查真实 IP 并写回 hosts。
#
# 用法：
#   github-channel-check          # 检查并按需修复
#   github-channel-check -f       # 强制重写 hosts
#   github-channel-check -q       # 静默（仅失败时输出）
set -uo pipefail

FORCE=0; QUIET=0
for a in "$@"; do
  case "$a" in -f) FORCE=1 ;; -q) QUIET=1 ;; esac
done

say() { [ "$QUIET" -eq 1 ] || echo "$@"; }
err() { echo "$@" >&2; }

# host:期望的写死 IP（github 自己的 IP 段，实测稳定）
# 注意：release-assets / objects 属于 fastly，有 4 个 IP 轮询，单写一个会偶发 404。
declare -A DIRECT=(
  [github.com]=20.205.243.166
  [api.github.com]=20.205.243.168
  [ssh.github.com]=20.205.243.160
  [codeload.github.com]=20.205.243.165
)
FASTLY_HOSTS=(release-assets.githubusercontent.com objects.githubusercontent.com)

doh() { # 阿里 DoH 查 A 记录
  timeout 10 curl -sS "https://dns.alidns.com/resolve?name=$1&type=A" 2>/dev/null \
    | python3 -c 'import json,sys
try: d=json.load(sys.stdin)
except Exception: raise SystemExit
for a in d.get("Answer",[]):
    if a.get("type")==1: print(a["data"])' 2>/dev/null
}

is_bad_ip() { # 198.18.0.0/15 是沙箱占位网段
  case "$1" in 198.18.*|198.19.*|"") return 0 ;; *) return 1 ;; esac
}

NEED_FIX=0; REPORT=()
rm -f /tmp/.gh_hosts_new

for h in "${!DIRECT[@]}"; do
  cur=$(getent hosts "$h" 2>/dev/null | head -1 | awk '{print $1}')
  if [ "$FORCE" -eq 1 ] || is_bad_ip "$cur"; then
    ip="${DIRECT[$h]}"
    # 直写 IP 不通时再问 DoH
    if ! timeout 8 bash -c "echo > /dev/tcp/$ip/443" 2>/dev/null; then
      alt=$(doh "$h" | head -1)
      [ -n "$alt" ] && ip="$alt"
    fi
    printf '%s %s\n' "$ip" "$h" >> /tmp/.gh_hosts_new
    NEED_FIX=1
    REPORT+=("$h -> $ip")
  fi
done

for h in "${FASTLY_HOSTS[@]}"; do
  cur=$(getent hosts "$h" 2>/dev/null | head -1 | awk '{print $1}')
  if [ "$FORCE" -eq 1 ] || is_bad_ip "$cur"; then
    for t in 108 109 110 111; do
      printf '185.199.%s.133 %s\n' "$t" "$h" >> /tmp/.gh_hosts_new
    done
    NEED_FIX=1
    REPORT+=("$h -> 185.199.108-111.133")
  fi
done

# raw.githubusercontent 也是 fastly
cur=$(getent hosts raw.githubusercontent.com 2>/dev/null | head -1 | awk '{print $1}')
if [ "$FORCE" -eq 1 ] || is_bad_ip "$cur"; then
  for t in 108 109 110 111; do
    printf '185.199.%s.133 raw.githubusercontent.com\n' "$t" >> /tmp/.gh_hosts_new
  done
  NEED_FIX=1; REPORT+=("raw.githubusercontent.com -> 185.199.108-111.133")
fi

if [ "$NEED_FIX" -eq 1 ] && [ -s /tmp/.gh_hosts_new ]; then
  # 先删旧条目再加，避免重复堆积
  python3 - <<'PY'
import re
keep=[]
for line in open('/etc/hosts'):
    if re.search(r'\bgithub(usercontent)?\.com\b|\bgithubusercontent\.com\b', line):
        continue
    keep.append(line)
new=open('/tmp/.gh_hosts_new').read()
open('/etc/hosts','w').write(''.join(keep).rstrip('\n')+'\n'+new)
PY
  cp /etc/hosts /root/.user_hosts 2>/dev/null
  say "✅ GitHub hosts 已修复："
  for r in "${REPORT[@]}"; do say "   $r"; done
else
  say "✅ GitHub hosts 正常，无需修复"
fi
rm -f /tmp/.gh_hosts_new

# 通道连通性自检
# ⚠️ 这里必须用 `out=$(...)` 而不是 `... | grep -q`：
#   管道版在 ssh 失败时（rc=255）只会得到「没匹配到」，
#   把真正的错误信息（kex_exchange_identification 等）全丢了，
#   排查时只能看到一句「认证失败」，完全不知道是网络还是密钥。
SSH_OUT=$(timeout 15 ssh -T git@github.com 2>&1)
SSH_RC=$?
if echo "$SSH_OUT" | grep -q "successfully authenticated"; then
  say "✅ SSH(git@github.com, HostName 写死 IP) 认证通过"
else
  err "❌ SSH 认证失败（rc=$SSH_RC）："
  err "$SSH_OUT"
  err "   → 先确认 /root/.ssh/id_ed25519_vaultix 还在，且 ~/.ssh/config 里 HostName 是 IP 不是域名"
  exit 1
fi

# 443 备用通道（防火墙只放行 443 时用）
if timeout 15 ssh -T git@github-443 2>&1 | grep -q "successfully authenticated"; then
  say "✅ SSH(github-443) 备用通道可用"
else
  err "⚠️  SSH(github-443) 备用通道不可用（主通道正常，不影响 push）"
fi

code=000
# ⚠️ 必须重试：沙箱出网偶发 http=000（TCP 建立即被拒），
#    一次失败不代表通道坏了。曾在 `ghp` 里因为这个直接拦住了一次正常推送。
for attempt in 1 2 3; do
  code=$(timeout 15 curl -sS -o /dev/null -w '%{http_code}' https://api.github.com/rate_limit 2>/dev/null)
  [ "$code" = "200" ] && break
  [ "$attempt" -lt 3 ] && sleep 2
done

if [ "$code" = "200" ]; then
  rem=$(timeout 15 curl -sS https://api.github.com/rate_limit 2>/dev/null \
        | python3 -c 'import json,sys;print(json.load(sys.stdin)["resources"]["core"]["remaining"])' 2>/dev/null)
  say "✅ REST API 可用（匿名额度剩余 ${rem:-?}/60）"
else
  err "❌ REST API 不可用（http=$code，已重试 3 次）"
  exit 1
fi
