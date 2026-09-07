# Bitwarden 同步与密码库生态（内部交接）

> 汇总 Bitwarden 同步的完整演进、2026-08-27 同步卡死大修复、passkey 合并、锁重构、生态兼容性。
> 最后整理：2026-08-27。提交归纳见 `项目文档-提交与重点Bug修复总结.md`。

---

## 0. 一句话结论

Bastion 的 Bitwarden 同步经历了「性能优化 → 轻量预检 → 上传/下载解耦 → 可观测性补全 → 卡死根治」五步演进。**最终根因不是 token 过期，而是：①连接池复用被反代静默关闭的空闲连接挂死；②`passkeyMerge` 每次同步对 vault 全部条目逐条 `GET /ciphers/{id}` 检查 legacy 字段，216 条量级直接卡死。** 已在 `aff292c1` 合 main。

---

## 1. 同步演进时间线

| 阶段 | 提交 | 做了什么 | 结果 |
|---|---|---|---|
| 性能基线 | `3eb0187f` | 写库包单事务 + OkHttp 快失败（D1+D4） | 写库/失败更快 |
| 增量尝试 | `9aee0c97` | `sinceRevisionDate` 增量 + 节流拉长 | **Vaultwarden 忽略增量游标，等于空转** |
| 上传/下载解耦 | `10228d6`→`ce539395` | 新建条目走 `POST /ciphers` 轻量路径，全量仅作后台 reconcile | 上传不再被整库下载挡住 |
| 轻量预检 | `349daef7` | `GET /accounts/revision-date` 比对 + 60s 防抖跳过全量 | Vaultwarden 场景显著提速 |
| 诊断补全（阶段0） | `bb67424a` | `sync()`/`refreshToken()` 全程诊断日志 | 可观测性从零到有 |
| 卡死修复（阶段1） | `9bdba9a8` | 401 自动恢复 + 超时收紧 + 主界面错误提示 + 上传埋点 | 反应式恢复 + 用户可见失败 |
| 上传对账/埋点 | `a1c87c92` | 上传前 `revision-date` 预检跳过全量 + POST 细分计时 | 定位 26s 构成 |
| **passkeyMerge 根治** | `f647ba90` | vault 级完成标记 + 15s 超时保护 | **彻底消除卡死** |

## 2. 重点修复详解：同步卡死（2026-08-27）

### 2.1 现象
已登录点同步"很慢很慢，感觉就没同步"；退出重登后正常（重登 token 全新、网络路径能跑通）。

### 2.2 根因（两处）
1. **死连接挂死**：首次全量同步后，OkHttp 连接池复用了被反代（nginx/Cloudflare）静默关闭的空闲连接，后续请求挂到原 `readTimeout=60s` 才失败 → 用户感知"很慢"。
2. **passkeyMerge 全量逐条 GET（真凶）**：`mergeHistoricalStandalonePasskeys` → `cleanupLegacyPasskeyBindingsField` 每次同步对 vault **全部条目**逐条 `GET /ciphers/{id}` 检查 legacy 字段，216 条量级直接卡在 `sync START → fullSync START` 之间，无异常、无超时。

### 2.3 修复清单
- **网络层**（`BitwardenApiFactory.kt`）：`readTimeout`/`writeTimeout` 60s→**30s**；`retryOnConnectionFailure(true)`；连接池 `keepAliveDuration` 5min→**2min**；保留 `pingInterval(30s)` 快速发现死连接。
- **401 反应式恢复**：新增 `interface BitwardenTokenRefresher { refreshForHost(host): String? }` 与 OkHttp `Authenticator`：任意 Bitwarden 请求 401 → 按 host 刷新 token → 用新 token 重试一次（`priorResponse!=null` 即停，防死循环）。`refreshForHost` 内部 `runBlocking(IO)` 调 `refreshTokenByHost`。**注意 OkHttp4 的 `Authenticator.authenticate` 返回类型是 `Request?`（带新凭证），非 `Response?`**（CI 曾因此误改，见 `1907eba6` 修正）。
- **主界面可见失败**（`SimpleMainScreen.kt`）：原 `LaunchedEffect` 只处理 `SyncFinished`；改为 `when` 消费 `ShowError` 弹 `Toast(LENGTH_LONG)`，终结"感觉没同步"。
- **passkeyMerge 根治**（`BitwardenHistoricalPasskeyMergeService.kt`）：开头查 `bw_historical_merge_meta` 标记，命中跳过；末尾跑完置位（SharedPreferences）；整段包 `withTimeout(15s)` + 计时埋点（`passkeyMerge DONE/TIMEOUT/EXCEPTION`）。
- **上传对账/埋点**（`BitwardenSyncService.kt`）：上传前 `GET /accounts/revision-date` 与本地 `vault.revisionDate` 一致则跳过全量对账；`POST /ciphers` 计时 + 响应码；每条 op 计时。

### 2.4 验证
- CI：`dev` 分支 Android CI + CodeQL 双绿（#496/#497/#498）。
- 真机（荣耀 Android 17）：已登录连续点 2 次同步不再卡死；`Bitwarden Persisted Logs` 出现 `refreshTokenByHost` / `uploadPendingSecureItems` 等新增埋点。

## 3. 上传与全量下载解耦（提速核心）

- 根因：原 `BitwardenRepository.sync()` 先 `fullSync()`（整库下载）再 `processPendingOperations()`（上传），新建条目要等整库下载完才 `POST`。在 Vaultwarden + 高 RTT 下就是"一直在同步中"。
- 修复（`10228d6`）：新增轻量入口只跑 `processPendingOperations()`（上传 pending 增删改），不调 `GET /api/sync`；`requestLocalMutationSync` 去抖后调 `pushPendingNow()` 而非整段 `executeSync`。对照 Bitwarden 官方/Keyguard："上传单条改动 = 一次独立轻量请求，绝不等全量下载"。
- 配套：乐观 UI + 逐条目同步态（P2，体验）、401 Authenticator（P3，稳定性）。

## 4. revision-date 轻量预检跳过

- Vaultwarden 忽略 `sinceRevisionDate`，故客户端先用极小的 `GET /accounts/revision-date`（只返回时间戳字符串）判断是否要全量，一致就整段跳过下载+解密（`349daef7`）。
- 跳过条件（全部满足）：`forced==false` + 本地无待上传 + 距上次全量 <60s（per-vault 防抖）+ 远程 revision-date 与本地一致。误跳只"多拉一次全量"，绝不丢数据。

## 5. Bitwarden 锁/解锁入口重构

- 现象：app 内 Bitwarden「锁定/重新解锁」是无效操作。根因 `forceLock` 只清内存缓存，本地密文由 app 级 MDK 加密，与 Bitwarden 锁正交（`bitwarden-lock-redesign-2026-08.md`）。
- 修复（A1，`6b86427c`）：移除无效锁/解锁 UI，统一依赖 Bastion app 锁；保留「永不锁定」合法密码校验对话框与内部 `forceLock/unlock/lock` 方法。
- 已知局限：WebDAV 恢复出的 vault 无 UI 可解锁（除非删除重加/登出重登），后续需让恢复流程自动 populate 对称密钥。

## 6. Passkey 合并进密码 cipher

- 背景：绑定型 passkey 同步后在服务器显示为独立 `[Passkey]` 条目，无法与密码条目同显（`passkey-merge-into-password-cipher.md`）。
- Bitwarden 官方模型：passkey 存于密码 cipher 的 `login.fido2Credentials` 数组（与 username/password 平级）。
- 修复（`d692acaa`/`f0fd1d03`）：
  - `mergePasskeyIntoPasswordCipher`：GET 密码 cipher baseline → 解密已有 fido2 → 按 `credentialId` 去重合并（本地覆盖同名、保留其他）→ 重加密 → **仅替换 `login.fido2Credentials`** → `PUT /ciphers/{id}`。**绝不盲 PUT 清空服务器 passkey**。
  - 历史独立 `[Passkey]` cipher 同步时自动迁移合并（软删进回收站，幂等可重试）。
  - 删除绑定型 passkey 改为标记 `DELETE_PENDING`，由同步从 fido2 数组移除，不误删密码条目本体。
- 安全点：合并必须基于 GET baseline；只动 fido2Credentials；KeePass 侧 passkey 不参与 Bitwarden 同步。
- 已知边界：解绑场景不自动移除旧 credential；多设备并发追加极短覆盖窗口（已缓解）。

## 7. KDBX–Bitwarden 架构与生态兼容性

- 三后端架构见 `架构与路线图.md` §1。
- `bitwarden-kdbx生态兼容性对照与改进计划.md`：对齐 Bitwarden 官方导出格式 / KeePassDX / Keepass2Android / KeePassXC KPH。结论：KDBX 兼容性优于 Bitwarden（passkey KPH 字段逐字对齐、TOTP 双格式、WiFi kp2a 模板对齐）；主要缺口是"外部编辑条目的重建保真"。T1/T2/T3/T5/T7 已落地 dev 且 CI 全绿，T4 待单独设计（高风险）。
- `bitwarden-sync-monica-comparison.md`：对照上游 Monica-Pass/Monica 的修复，bastion 存在 1 个明确数据保真 bug（自定义字段下载丢失，已修）+ 1 个性能隐患（多账号被动同步未串行化，已修）；另 2 个 Monica 修复在 bastion 不适用/已规避。
- `entry-field-compat-optimization-plan.md`：条目字段兼容性 P0–P2（monica 遗留 `monica_app_package`/`monica_app_name` 读取兼容、Bitwarden/KeePass 应用绑定互通、`parseLinkedAppBindings` 分隔符统一为 `|,;`），采用读取端别名兼容，零数据改写。
- `azure-app-registration-guide.md`：无 Azure 账号权限时可自注册 Azure 应用对接 Bastion OneDrive（改一行 client_id）。
