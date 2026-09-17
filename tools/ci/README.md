# GitHub 通道工具（沙箱 / 受限网络环境）

> 来源：2026-09-17 排查「GitHub 工具无法使用」时落地的修复。
> 三个文件是一套，**缺任何一个都会让 push 以看不懂的方式失败**。

## 症状对照表

| 症状 | 真实原因 | 修法 |
|---|---|---|
| `kex_exchange_identification: Connection closed by remote host` | `/etc/hosts` 被重置，`ssh.github.com` 解析到 `198.18.0.39`（保留网段） | `github-channel-check` |
| `ssh -v` 显示连的是 `198.18.0.39`，但 hosts 里明明写着 `20.205.243.160` | **`HostName` 写成域名时，openSSH 的解析会绕过 hosts** | `ssh-config.sample`：`HostName` 必须写 **IP** |
| push 报凭证相关错误 / 卡住 | `~/.gitconfig` 有 `[url "https://github.com/"] insteadOf = git@github.com:`，把 SSH 劫持成 HTTPS，而沙箱 `git-credential-helper` 返回 404 | `ghp.sh`（临时摘掉重写再推） |
| `curl https://api.github.com` 偶发 `http=000` | 沙箱出网瞬时抖动，**不是限流**（实测连打 6 次全 200） | 自检重试 3 次 |

## 安装

```bash
install -m755 tools/ci/github-channel-check.sh /usr/local/bin/github-channel-check
install -m755 tools/ci/ghp.sh                  /usr/local/bin/ghp
cp tools/ci/ssh-config.sample ~/.ssh/config    # 注意：会覆盖已有配置，先备份
```

密钥放到 `~/.ssh/id_ed25519_vaultix`（`chmod 600`）。

## 用法

```bash
github-channel-check      # 检查通道（含 hosts 自愈 + SSH 认证 + API 额度），全绿才继续
ghp                       # 推当前分支到 origin/main
ghp main                  # 指定分支
```

`ghp` 内部会先调 `github-channel-check -q`，通道不通直接拒绝推送，
**不会**出现「以为推上去了其实没有」。

## ★ 最关键的三个坑（别再踩）

1. **`HostName` 必须写 IP，不能写域名。**
   这是 2026-09-17 那次「明明 hosts 写对了却连不上」的根因：
   `HostName ssh.github.com` → openSSH 自己去解析这个名字 → 拿到 `198.18.0.39` → 失败。
   写 `HostName 20.205.243.160` 连解析都省了，**22 和 443 两个端口都能通**。

2. **不要在命令行堆 `-o HostName=...`。**
   它会**覆盖** `~/.ssh/config` 里 `Host github.com` 的 `HostName`，
   把已经修好的配置重新换回域名 → 立刻复现坑 1。
   既然 config 里已经写好了，命令行什么都不用传。

3. **REST API 偶发 `http=000` 不是坏消息。**
   自检里必须重试；一次失败就判定「通道坏了」会把正常推送拦住。

## CI 日志读不到怎么办

`/actions/jobs/<id>/logs` 需要 admin 权限，沙箱匿名额度拿不到（403）。
替代办法：用 WebFetch 打开运行页
`https://github.com/<owner>/<repo>/actions/runs/<id>`，
页面 **Annotations** 区块会逐字给出每条 `e:` 编译错误与 `Caused by:`。

> ⚠️ Annotations 里 `Caused by` 是**倒序**的 —— 从下往上读才是真正的因果链。
