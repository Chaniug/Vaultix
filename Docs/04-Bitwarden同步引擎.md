# 04 · Bitwarden 同步引擎

## 1. 服务端兼容性

| 服务端 | 基础地址 | 说明 |
|---|---|---|
| Bitwarden 云（US） | `https://vault.bitwarden.com` / identity: `https://identity.bitwarden.com` | 官方 |
| Bitwarden 云（EU） | `https://vault.bitwarden.eu` / `https://identity.bitwarden.eu` | 官方 |
| Vaultwarden 自托管 | 用户填写，如 `https://vw.example.com`，identity 与 api 同一域名 | 社区服务端 |

> 差异处理：允许用户在高级设置中**分别**指定 API / Identity / Icons 三个地址。官方云为分域名，Vaultwarden 通常同域。

## 2. 认证流程

### 2.1 步骤总览

```
① POST {identity}/accounts/prelogin      {"email": "..."}      ← 新版 identity 路径
   ← {"Kdf": 1, "KdfIterations": 3, "KdfMemory": 64, "KdfParallelism": 4}
     首选 Argon2id；服务端返回 PBKDF2 时按其 iterations 执行（见 03）
② 派生 MasterKey → StretchedMasterKey → MasterPasswordHash（见 03）
③ POST {identity}/connect/token   Content-Type: application/x-www-form-urlencoded
   grant_type=password
   &username=<email>
   &password=<base64(MasterPasswordHash)>
   &scope=api offline_access
   &client_id=mobile
   &deviceType=0|1|2|...        （Android 设备类型，按官方枚举）
   &deviceIdentifier=<安装时生成的持久 UUID>
   &deviceName=<如 "Pixel 8">
   &devicePushToken=
   ← {access_token, expires_in:3600, refresh_token, token_type:"Bearer", ...}
④ GET {api}/sync?excludeDomains=false   Authorization: Bearer <access_token>
   ← { profile: { ..., userDecryption: { masterPasswordUnlock: { kdfType, kdfIterations,
        kdfMemory, kdfParallelism, masterKeyEncrypted } } }, folders, collections, ciphers, domains }
⑤ 用 StretchedMasterKey 解包 userDecryption.masterPasswordUnlock.masterKeyEncrypted
   → 64B 账号对称密钥（唯一取值路径，无旧字段回退）
```

**版本红线**：只支持返回 `userDecryption` 结构的服务端。若 `/api/sync` 响应中缺少 `userDecryption.masterPasswordUnlock`，直接报"服务端版本过低，请升级到最新版 Bitwarden / Vaultwarden"，**不回退**到旧版 `profile.key` / 登录响应 `Key` 字段。同理，`prelogin` 只请求新版 `{identity}/accounts/prelogin`，不兼容历史 `{api}/accounts/prelogin`。

### 2.2 双因素（2FA）

`connect/token` 返回 HTTP 400 且带 `TwoFactorProviders` 数组时，进入 2FA 流程：

| Provider ID | 方式 | V1 支持 |
|---|---|---|
| 0 | Authenticator（TOTP） | ✅ |
| 1 | Email | ✅（需轮询/用户提供码） |
| 2 | Duo | ❌ 引导至网页 |
| 3 | YubiKey OTP | ✅（配合外接/ NFC） |
| 4 | U2F / 6 WebAuthn | ⚠️ 需 WebView 流程，V1.5 |
| 5 | Remember（记住本设备） | ✅（`twoFactorRemember`） |
| 7 | Duo Organizations | ❌ |

重发请求时补 `twoFactorToken` 与 `twoFactorProvider`（以及可选的 `twoFactorRemember=1`）。成功后返回的 `TwoFactorToken` 用于后续免验证。

### 2.3 Token 续期

```
refresh_token → POST {identity}/connect/token   grant_type=refresh_token
```

- access_token 有效期 3600 s。策略：**提前 60 s 静默续期**，或在收到 401 时续期一次再重试。
- 续期使用**不带**生物识别门禁的 Keystore 密钥（后台无用户交互）。
- 续期失败（security stamp 变更、密码已改、服务端踢出）→ 回落 `LOCKED` 并要求重新输入主密码。
- `deviceIdentifier` 必须持久保存，频繁变化会触发服务端的新设备验证/邮件告警。

## 3. 数据同步

### 3.1 全量与增量

Bitwarden 客户端实际采用"**全量 sync + 本地 diff**"模式：

```
GET /api/sync  → 得到服务端全量密文快照
本地：cipher.id 与本地记录逐条比对 revisionDate
  ├─ 服务端较新      → 覆盖本地（若本地 dirty，进入冲突流程）
  ├─ 本地较新且 dirty → 推送本地修改
  └─ 服务端缺失本地项 → 本地已删除（或用户主动删除）→ 按删除语义处理
```

- 支持 `?excludeDomains=true` 减小响应体（等价域名表可缓存）。
- 响应体可能数 MB，使用流式解析；**全程只落密文**。
- 同步节奏：① 冷启动/解锁后 ② 前台每 5 分钟（可配置）③ WorkManager 周期（默认 6 h，需网络与电量放宽）④ 手动下拉 ⑤ 保存条目后立即推送。

### 3.2 写入接口

| 操作 | 请求 |
|---|---|
| 新建条目 | `POST /api/ciphers` |
| 更新条目 | `PUT /api/ciphers/{id}` |
| 删除（软删） | `PUT /api/ciphers/{id}/delete` |
| 永久删除 | `DELETE /api/ciphers/{id}` |
| 恢复 | `PUT /api/ciphers/{id}/restore` |
| 移动文件夹 | `PUT /api/ciphers/{id}/move`（可带 `folderId` + 多 id） |
| 收藏 | `PUT /api/ciphers/{id}/favorite` |
| 附件 | `POST /api/ciphers/{id}/attachment`（需 Premium，V1 只读） |

请求体为 `CipherRequest`：`type`、`name`、`notes`、`fields`、`favorite`、`folderId`、`login`/`card`/`identity`/`secureNote`、`passwordHistory`、`reprompt`、`lastKnownRevisionDate`（**冲突检测关键字段**，必传）。

### 3.3 冲突处理

```
推送时服务端返回 400/409 且指示版本落后
  → 拉取该条最新版本
  → 展示"冲突"对话框：并排显示本地/远端字段，逐字段选择保留哪一侧
  → 用户确认后以最新 revisionDate 重新推送
自动策略（可在设置开启）：
  · 若仅本地修改时间在 30 s 内且字段无实质差异 → 接受远端
  · 若本地为"仅新增的自定义字段" → 采用字段级合并（Merge）
  · 删除优先：任一标记为删除且另一端未修改 → 删除生效
```

## 4. 离线与缓存策略

| 场景 | 行为 |
|---|---|
| 无网络、库已解锁 | 读写本地 Room 密文缓存，标记 `dirty`；UI 顶部显示"离线，N 项待同步" |
| 无网络、库未解锁 | 只允许用**上次成功解锁时缓存的**解密密钥（若启用"离线解锁"）；否则提示需联网 |
| 网络恢复 | WorkManager 或前台监听触发 flush：`dirty` 队列按序推送 |
| 推送部分失败 | 失败的条目保留 dirty 并重试（指数退避，最多 5 次），其余继续 |
| 服务端数据大变更 | 提示"远端已变更，是否重新同步"（避免误覆盖） |

**离线解锁（可选设置）**：解锁成功后，把账号对称密钥用 Keystore 密钥（带生物识别门禁）加密存盘；开启后即使无网络也能打开已缓存的库。默认**关闭**，开启时明确告知风险。

## 5. 领域等价域名（Autofill 匹配）

`/api/sync` 的 `domains` 返回：

```json
{
  "equivalentDomains": [["google.com","youtube.com","gmail.com"]],
  "globalEquivalentDomains": [ { "type": 1, "domains": ["ameritrade.com","tdameritrade.com"] } ]
}
```

匹配算法（与官方一致，见 [06](./06-Android平台集成.md)）：
1. 条目 URI 的 `match` 规则：`0` 基础域名 / `1` 主机 / `2` 以…开头 / `3` 完全匹配 / `4` 正则 / `5` 永不
2. 先按精确/主机匹配，再套用等价域名与全局等价域名表
3. 结果按"匹配精度 + 最近使用时间 + 收藏"排序

## 6. 图标

```
GET {icons}/{host}/icon.png      例：https://icons.bitwarden.net/github.com/icon.png
```

- 缓存到磁盘（非备份目录），LRU 上限 5000 张。
- 默认**关闭**网络图标，需用户在设置中开启（开启即视为同意向图标服务发起请求）。

## 7. 错误码与用户提示

| HTTP | 含义 | 提示动作 |
|---|---|---|
| 400 + `TwoFactorProviders` | 需要 2FA | 进入 2FA 输入页 |
| 400 + `"Two factor required."` | 同上 | 同上 |
| 401 | token 失效 | 静默 refresh，失败则回落登录 |
| 429 | 限流 | 指数退避，提示稍后重试 |
| 5xx / 超时 | 服务端问题 | 保留离线态，允许继续编辑 |
| 证书错误 | 自签名/中间人 | **阻断**并明确提示，禁止"忽略证书"开关（安全红线） |

## 8. 安全约束（硬性）

1. `MUST NOT` 在生产构建中放行自签名证书或禁用证书校验；仅 `debug` 构建可通过 `network_security_config` 允许用户证书（便于抓包调试，且需显式开启）。
2. `MUST` 对所有请求使用 TLS 1.2+；建议对官方域名做证书固定时提供"关闭固定"开关，避免自托管用户被锁死。
3. `MUST NOT` 在主密码输入框启用任何第三方键盘的"学习"能力不受控——提供"仅使用系统键盘"提示。
4. `MUST` 在退出登录时清除：token、对称密钥、本地密文缓存、图标缓存（可选）、`deviceIdentifier` 保留与否由用户决定。
