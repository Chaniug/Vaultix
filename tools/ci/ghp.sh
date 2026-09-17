#!/usr/bin/env bash
# ghp —— 在沙箱里可靠地 push 到 GitHub
#
# 为什么需要它：
#   ~/.gitconfig 里有
#       [url "https://github.com/"]
#           insteadOf = git@github.com:
#   这条重写会把 SSH 地址劫持成 HTTPS，而 HTTPS 在沙箱里依赖
#   /usr/local/bin/git-credential-helper —— 该端点返回
#       {"code":404,"msg":"git credentials not found in space labels"}
#   即沙箱环境未适配，拿不到凭证 ⇒ push 必然失败。
#
#   而 SSH over 443（ssh.github.com）一直是通的。
#   本脚本临时摘掉重写 → 用 SSH 推 → 无论成败都把 gitconfig 还原。
#
# 用法：
#   ghp                         # push 当前分支到 origin
#   ghp main                    # push 指定分支
#   ghp -u origin main          # 额外参数原样传给 git push
set -uo pipefail

REPO_DIR="${GH_REPO_DIR:-/workspace/Vaultix}"
KEY=/root/.ssh/id_ed25519_vaultix

# 0) 确保通道可用。
#    ⚠️ 只把 **SSH 认证** 当硬门槛 —— 那才是 push 真正走的路。
#    REST API（api.github.com）在沙箱里会间歇性被 SSL_ERROR_SYSCALL 掐断
#    （实测同一命令可能 6/6 全 200，两分钟后又稳定全 000），
#    它只用于「读 CI 状态」这类可选增强，**绝不能**因为它拦下推送。
SSH_OUT=$(timeout 15 ssh -T git@github.com 2>&1)
if echo "$SSH_OUT" | grep -q "successfully authenticated"; then
  :
else
  echo "❌ SSH 认证失败，推送不可能成功：" >&2
  echo "$SSH_OUT" >&2
  echo "   → 先跑 github-channel-check（会修 hosts）" >&2
  exit 1
fi

cd "$REPO_DIR" || exit 1

BRANCH="${1:-$(git rev-parse --abbrev-ref HEAD)}"
shift 2>/dev/null || true
# ⚠️ 用**数组**收集剩余参数，不能用 `EXTRA="$*"` 再配 `${EXTRA:+"$@"}`：
#    后者在无额外参数时，`"$@"` 已为空 ⇒ 展开成空串 ⇒ 变成给 git push
#    传了一个空位置参数，git 会把它当成一个空 refspec。
#    （实测症状：stderr 冒出 grep/ssh 的 `Usage:` 报错。）
#    数组 + `"${ARGS[@]}"` 是唯一在 set -u 下也安全的两全写法。
ARGS=("$@")

# 1) 备份并摘掉 URL 重写
CFG=~/.gitconfig
BAK=$(mktemp)
cp "$CFG" "$BAK" 2>/dev/null
RESTORE() { cp "$BAK" "$CFG" 2>/dev/null; rm -f "$BAK"; }
trap RESTORE EXIT
git config --global --unset-all "url.https://github.com/.insteadof" 2>/dev/null

# 2) 用 SSH 推。
#    ⚠️ 不再往命令行堆 `-o HostName=...`：那会覆盖 ~/.ssh/config 里
#    `Host github.com` 的 HostName，把目标换成域名 ssh.github.com，
#    而该域名在本沙箱走 DNS 解析到 198.18.x（保留网段）⇒ 必然 kex 失败。
#    一切都交给 ~/.ssh/config（HostName 已写死 IP）。
if [ ${#ARGS[@]} -gt 0 ]; then
  git push "${ARGS[@]}" git@github.com:Chaniug/Vaultix.git "$BRANCH"
else
  git push git@github.com:Chaniug/Vaultix.git "$BRANCH"
fi
rc=$?

if [ $rc -eq 0 ]; then
  echo "✅ 已推送到 origin/$BRANCH"
else
  echo "❌ push 失败（rc=$rc）" >&2
fi
exit $rc
