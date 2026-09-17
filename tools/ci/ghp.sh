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

# 0) 确保通道可用
github-channel-check -q || { echo "❌ github 通道不可用，先修 hosts" >&2; exit 1; }

cd "$REPO_DIR" || exit 1

BRANCH="${1:-$(git rev-parse --abbrev-ref HEAD)}"
shift 2>/dev/null || true
EXTRA="$*"

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
git push ${EXTRA:+"$@"} git@github.com:Chaniug/Vaultix.git "$BRANCH"
rc=$?

if [ $rc -eq 0 ]; then
  echo "✅ 已推送到 origin/$BRANCH"
else
  echo "❌ push 失败（rc=$rc）" >&2
fi
exit $rc
