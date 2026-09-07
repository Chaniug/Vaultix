# 本地 → GitHub 网络与推送通道说明（bastion 项目）

> 用途：本沙箱直接 `git push` 走不通，本文记录根因与已落地的可用通道，方便后续 agent 接力。
> 更新时间：2026-08-30
> 关联项目规范第 7 条。

## 结论速览
- 本沙箱 **`github.com` 的 443（HTTPS）被网络层屏蔽**：`git fetch` / `git push` over HTTPS 均失败（`gnutls_handshake() failed` / `GnuTLS recv error -110`，TLS 握手成功但服务端在 receive-pack 路径直接 RST）。
- **`github.com` 的 22（SSH）可用**：TCP 握手成功，只需配公钥即可正常 `git push` / `pull`。
- `api.github.com:443` 稳定（`gh` / REST API 可用，作为兜底写通道）。
- **所有内网代理均不可达**：`auth.proxy:8080`、`SPACE_PROXY_ENDPOINT=10.96.32.90`（8080/80/3128）、`socks5 1080` 全部超时。

## 已落地的配置
1. **hosts**（`/etc/hosts` 与 `~/.user_hosts` 同步；系统会在重启时还原 `/etc/hosts`，需从 `~/.user_hosts` 恢复）：
   ```
   20.205.243.166 github.com
   20.205.243.168 api.github.com
   20.205.243.165 codeload.github.com
   ```
   > 说明：github.com 的 HTTPS(443) 实际已被屏蔽，上述 IP 主要用于 SSH(22) 与 api；保留无害。
2. **SSH 密钥**：`~/.ssh/id_ed25519`（comment=`codebuddy-sandbox`）。
3. **deploy key**：仓库 `Chaniug/bastion` 已添加标题 `codebuddy-sandbox` 的**写权限**公钥（通过 `gh api POST /repos/Chaniug/bastion/keys` 写入）。
4. **remote**：
   ```
   origin  git@github.com:Chaniug/bastion.git            (fetch/push，走 SSH 22)
   mirror  https://ghfast.top/https://github.com/Chaniug/bastion.git  (fetch 备用，走 CDN 镜像)
   ```

## 日常操作
- 推送：`git push origin dev`
- 拉取：`git fetch origin`（或 GitHub 慢时 `git fetch mirror`）
- 看 CI：`https://github.com/Chaniug/bastion/actions`

## 重要提醒（对照规范）
- 推送走 `dev` 分支；dev 验证无误后再合并 `main`（规范第 2 条）。
- push 会触发 GitHub Actions，务必观察运行结果，报错及时修复（规范第 3、4 条）。
- 若某天 SSH 也连不上：用腾讯/阿里 DoH（`https://doh.pub/dns-query`、`https://dns.alidns.com/resolve`）重新解析 `github.com` 真实 IP，核对 hosts；或改用 `gh api` 走 Git Data API 兜底推送（blob→tree→commit→更新 ref）。
- 删除本沙箱 deploy key：`gh api -X DELETE /repos/Chaniug/bastion/keys/<id>`。
