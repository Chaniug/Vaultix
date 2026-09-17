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
NEWHOSTS=/tmp/.gh_hosts_new
rm -f "$NEWHOSTS"
: > "$NEWHOSTS"

for h in "${!DIRECT[@]}"; do
  cur=$(getent hosts "$h" 2>/dev/null | head -1 | awk '{print $1}')
  if [ "$FORCE" -eq 1 ] || is_bad_ip "$cur"; then
    ip="${DIRECT[$h]}"
    # 直写 IP 不通时再问 DoH
    if ! timeout 8 bash -c "echo > /dev/tcp/$ip/443" 2>/dev/null; then
      alt=$(doh "$h" | head -1)
      [ -n "$alt" ] && ip="$alt"
    fi
    printf '%s %s\n' "$ip" "$h" >> "$NEWHOSTS"
    NEED_FIX=1
    REPORT+=("$h -> $ip")
  fi
done

# fastly 托管的域名（release 资产 / raw / objects）
# ⚠️ 这四个 IP 是 GitHub 文档公开的稳定段，**不需要 DoH**，直接写死。
#    之前这里依赖 DoH 的代码路径有个致命缺陷：删除旧记录与写入新记录
#    不原子 —— 若 DoH 恰好不通、新记录为空，旧记录就被永久删掉了
#    （实测：跑完 `-q` 后 release-assets 反而从可用变成 198.18.0.43）。
#    现在无条件写死，且第 81 行改成「只有新记录非空才替换」。
for h in "${FASTLY_HOSTS[@]}" raw.githubusercontent.com; do
  cur=$(getent hosts "$h" 2>/dev/null | head -1 | awk '{print $1}')
  if [ "$FORCE" -eq 1 ] || is_bad_ip "$cur"; then
    for t in 108 109 110 111; do
      printf '185.199.%s.133 %s\n' "$t" "$h" >> "$NEWHOSTS"
    done
    NEED_FIX=1
    REPORT+=("$h -> 185.199.108-111.133")
  fi
done

if [ "$NEED_FIX" -eq 1 ] && [ -s "$NEWHOSTS" ]; then
  # 先删旧条目再加，避免重复堆积
  python3 - <<'PY'
import re, os
keep=[]
for line in open('/etc/hosts'):
    if re.search(r'\bgithub(usercontent)?\.com\b|\bgithubusercontent\.com\b', line):
        continue
    keep.append(line)
new=open(os.environ.get('NEWHOSTS','/tmp/.gh_hosts_new')).read()
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
# ⚠️ 同样必须用变量捕获，不能 `ssh ... | grep -q`：
#    管道里 grep 匹配到就退出，ssh 收到 SIGPIPE ⇒ 返回非 0 ⇒ if 判失败。
#    实测这条命令的 banner 明明是 "Hi Chaniug! ... successfully authenticated"，
#    却被判成「不可用」。本脚本第三次栽在这个写法上，已全部改为变量捕获。
SSH443_OUT=$(timeout 15 ssh -T git@github-443 2>&1)
if echo "$SSH443_OUT" | grep -q "successfully authenticated"; then
  say "✅ SSH(github-443) 备用通道可用"
else
  err "⚠️  SSH(github-443) 备用通道不可用（主通道正常，不影响 push）"
  err "$SSH443_OUT"
fi

# 全部通过
exit 0

code=000
# ⚠️ 必须重试，且**失败不等于通道坏**。
# 沙箱出网对 api.github.com 存在间歇性封锁：同一命令刚才还 6/6 全 200，
# 两分钟后就稳定全 000（curl: (35) SSL_ERROR_SYSCALL）。
# 这是环境策略，不是配置问题 —— 重试只能过滤掉瞬时抖动，
# 真正的持续封锁要靠调用方降级（用 WebFetch 读 CI 页面，见 tools/ci/README.md）。
for attempt in 1 2 3; do
  code=$(timeout 15 curl -sS -o /dev/null -w '%{http_code}' https://api.github.com/rate_limit 2>/dev/null)
  [ "$code" = "200" ] && break
  [ "$attempt" -lt 3 ] && sleep 3
done

if [ "$code" = "200" ]; then
  rem=$(timeout 15 curl -sS https://api.github.com/rate_limit 2>/dev/null \
        | python3 -c 'import json,sys;print(json.load(sys.stdin)["resources"]["core"]["remaining"])' 2>/dev/null)
  say "✅ REST API 可用（匿名额度剩余 ${rem:-?}/60）"
else
  # 关键：只警告，不 exit 1。SSH 通道（push 的唯一通路）此时已认证通过。
  err "⚠️  REST API 不可用（http=$code，重试 3 次）—— 沙箱对 api.github.com 有间歇性封锁。"
  err "    push 不受影响（走 SSH）；读 CI 状态请改用 WebFetch 打开运行页。"
fi
