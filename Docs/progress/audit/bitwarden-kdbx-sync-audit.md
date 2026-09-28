# Bitwarden / KDBX 连接与同步排查报告（2026-09-28 晚）

> **触发**：用户问「app 与 Bitwarden 服务连接的时候，或 kdbx 连接的时候，是否还有 bug，比如同步的问题，排查一下」。
> **性质**：纯诊断，未动任何生产代码（与 09-28 白天 autofill 诊断同一纪律）。
> **方法**：历史问题账本（`.ai/issues/07`、`05`）逐条对代码核验在位性 + 两条同步链路（`BitwardenSyncOrchestrator` / `KdbxSyncOrchestrator` 及其上下游）通读 + 真机日志（09-28 采集）交叉验证。

---

## 0. 结论速览

| # | 组件 | 结论 | 定级 |
|---|---|---|---|
| A | Bitwarden 同步历史修复（#8/#22/#91/#92/#119） | **全部在位**，含数据层两道 pendingIds 保护 | ✅ |
| B | Bitwarden 遗留尾巴：`collectionIds`/`archivedDate` 未建模 | 已知开放项（#119 记录在案），仅影响**组织集合**条目 | ⚠️ 低（用户当前无组织库） |
| C | KDBX 写路径 #106 | **已修**（`requireWritable` 前置闸，`ReadOnlyVaultException`） | ✅ |
| D | KDBX 网盘同步（OneDrive/WebDAV，09-17 落地） | 条件写/往返自检/冲突三选项**实现齐整** | ✅ |
| E | **OneDrive KDBX 库的指纹快解在填充扇出中 100% 失败** | 🔴 **真 bug，真机实证 ×4**，永不自愈 | **P0** |
| F | NeedsReload 路径**提前推进 token 基线** | 🔴 设计缺陷：R-6~R-9 开放编辑后 = **静默覆盖远端改动** | **P1**（当前休眠） |
| G | `notifyLocalChangedDuringUpload` app 层零调用 | ⚠️ 「上传期间又改了」竞态防护**未接线** | P1（当前休眠） |
| H | `localChangedSinceLastSync` 两处口径不一 + 伪冲突 | ⚠️ 伪冲突弹窗里的「用本地覆盖远端」= **真数据丢失入口** | P1 |

---

## 1. Bitwarden 侧：修复在位核验

逐条对照 `.ai/issues/07-数据与同步.md` 与当前代码：

| 历史坑 | 核验点 | 现状 |
|---|---|---|
| #8 登录易失效三根因 | Bearer 预挂 / 解锁路径 `registerServer` / 刷新三分 | ✅ `VaultRepositoryImpl.completeLocalUnlock` L461-463 解锁即 `registerServer` 在位 |
| #91 全量拉取覆盖队列改动 | `persistCiphers` 前过滤 pendingIds | ✅ `BitwardenSyncService.kt:364-366` |
| #92 4xx 弃单误删本地行 | `pruneRemovedRows` pendingIds 保护 | ✅ `BitwardenSyncService.kt:341-345`；弃单分流逻辑在位 |
| #92 附属 | flushPending 部分失败不阻断拉取（本地改动受 pendingIds 保护） | ✅ `BitwardenSyncService.kt:93-98` |
| #119 三字段透传 | `passwordHistory`/`organizationId`/`lastKnownRevisionDate` 仅更新路径透传 | ✅（06f0168，探针 `verify_sync_fields.py` 守护） |
| 编排器 isRunning 永久卡死（09-17 修） | 取消路径清理 | ✅ `BitwardenSyncOrchestrator.kt:185-203` 注释与实现一致 |
| 解锁触发 2FA 重登（09-16 修） | `doUnlock(preferLocalUnlock)` | ✅ `VaultRepositoryImpl.kt:755+` 主密码解开本地密钥，全程不触网 |

**编排器总体评价**：触发分类/节流/合并/退避/取消清理均按 Bastion 语义移植且带单测（`BitwardenSyncOrchestratorTest.kt`），未发现新问题。

### 1.1 遗留开放项（非新发现，账本已记）

- **`collectionIds` / `archivedDate` 未建模**（#119「仍未做的」段）：若服务端是「不带即清空」语义，**组织库条目**每次编辑会掉出集合。用户服务端为自建 Vaultwarden（`pwd.vv1234.cn`），当前 3 个库均为个人库，风险未激活；一旦使用组织集合需先做一次服务端行为确认（建一条组织集合条目 → Vaultix 编辑 → 查集合归属）。

---

## 2. KDBX 侧：修复在位核验

| 历史坑 | 核验点 | 现状 |
|---|---|---|
| #106 写路径未分流（新建静默丢失/编辑报错） | 写入口前置闸 | ✅ `ItemRepositoryImpl.kt:460-484` `requireWritable` 对 KDBX 一律抛 `ReadOnlyVaultException`，且防了 DELETE 毒丸入队（:314-316） |
| 网盘同步三态决策 | 冲突拒写、条件写 | ✅ `KdbxSyncOrchestrator` 决策与文档一致；`Kdbx.saveVia`（`Kdbx.kt:334-386`）先往返自检再条件写，冲突单独归类 |
| WebDAV 条件写 | `If-Match` / `If-None-Match: *` | ✅ `WebDavKdbxFileSource.kt:119-151` |
| OneDrive 条件写 | eTag / 412 | ✅ `OneDriveKdbxFileSource.kt`（eTag 即令牌，不归一化——注释已点名） |
| 「重新解锁后会拉取」提示真实性 | 解锁是否现读文件 | ✅ `unlockKdbxInternal`（`VaultRepositoryImpl.kt:265-293`）每次从 fileSource 现读 |
| WebDAV eTag 归一化 | `W/"x"` vs `"x"` | ✅ `KdbxSyncOrchestrator.kt:125-131` + `normalizeVersionToken` |

**已实现但尚未接线的能力**（见 §4.2）。

---

## 3. 🔴 真 bug：OneDrive KDBX 库的指纹快解在填充扇出中 100% 失败

### 3.1 真机实证（09-28 采集，4 次独立复现）

来自 `Docs/progress/audit/evidence/2026-09-28-bprime-verify-VaultixAutofill.log`（另见 `build/adb-capture/bprime-verify.txt`、`vx-hits.txt`，横跨 4 个进程 PID：12195 / 14261 / 15018 / 26888）：

```
fanout start first=https://pwd.vv1234.cn rest=1
fanout first=https://pwd.vv1234.cn → Success
fanout rest=onedrive:00000000-...:Keepass%2Fvalkjin.kdbx → Unknown(本地解锁凭据不可用)
fanout done first=Success opened=0 failed=1
```

**四个关键事实**：

1. **首个库（Vaultwarden）每次都成功**——CryptoObject 绑定的那条路是好的；
2. **OneDrive KDBX 库每次都失败**，`opened=0` 无一例外；
3. **失败发生在密码学层，不是网络层**：rest 失败距 `fanout start` 仅 ~50ms（19:29:16.097→.144）。OneDrive 开库需走 Graph API 拉整个文件（数百 ms 起），50ms 只够本地一次 `unwrap` 抛异常——即 `VaultRepositoryImpl.completeLocalUnlockKdbx` L569-575 的 `runCatching { localUnlockKeyStore.unwrap(...) }` 失败分支；
4. 报文是 `本地解锁凭据不可用` = **异常 message 为 null 的兜底文案**——`check(parts.size==2)` 和 Base64 解码都带 message，只有 `cipher.doFinal` 抛出的异常（`AEADBadTagException` / `UserNotAuthenticatedException`）典型地无 message。

### 3.2 两个候选根因（需真机一锤定音，二者可并存）

**H1：payload 损坏或过期（unwrap → AEADBadTagException）**

两条机制：

- **多库 wrap 复用同一 Cipher**：`LocalUnlockEnrollment.commitForVaults`（:263-289）把**一个** cipher 连续 `wrap` 多个库；`LocalUnlockKeyStore.wrap`（:158-166）是「先读 `cipher.iv`、再 `doFinal`」——第一次 `doFinal` 消费掉 Keystore 操作后，第二次 `doFinal` 若自动开启新操作（新 IV），则存下的 IV 与密文**必然错位** ⇒ 第二个库的 payload 从登记那一刻起就是死的，且登记「成功」不抛错（`local_unlock_enabled=true` 落盘）。
- **KEK 换代后未重包该库**：KEK 是全局单 alias（`LocalUnlockKeyStore.kt:191` 旧失效先删再建）；指纹变更后重登记时只重包「本次勾选的库」，名单外的库留下旧 KEK 包的 payload + `enabled=true`。

用户设备侧旁证：`quick_unlock_scope_confirmed=1`（走过「生效范围」多库登记流程）、`local_unlock_enabled_*`×2 —— 若这 2 个库（Vaultwarden + OneDrive）是**同一次**多库 commit 登记的，则 H1 的第一种机制恰好解释「第一个好、第二个坏」。

**H2：auth-per-use 下 rest 库的新 cipher 未被授权（doFinal → UserNotAuthenticatedException）**

- KEK 用 `setUserAuthenticationParameters(0, …)`（`LocalUnlockKeyStore.kt:202-208`）= **每次使用都需认证**，没有「授权窗口」；
- 扇出（`LocalUnlockFanout.unlockAll` :89-101）对 rest 库各自 `prepareLocalUnlock` **新建解密 cipher**——BiometricPrompt 的 CryptoObject 只授权**首个库的那一个**操作；
- `LocalUnlockFanout` 文件头注释写「趁 KEK 的授权窗口各自新建一个解密 cipher」——这个「窗口」在 API 30+ 的 per-use 语义下**不存在**（API 26-29 分支也是 `-1` = 每次都需认证）。

### 3.3 放大因素：KDBX 侧永不自愈（策略不一致）

| | Bitwarden 侧 | KDBX 侧 |
|---|---|---|
| unwrap 失败的处理 | `isLocalUnlockUnrecoverable()`（含 `AEADBadTagException`，`LocalUnlockFailure.kt:57-71`）→ `clearBrokenLocalUnlock` 清登记**自愈**（`VaultRepositoryImpl.kt:465-470`） | `completeLocalUnlockKdbx` L569-575 刻意「勿删登记」，返回 `Unavailable` |
| 自愈路径 | 清回「未启用」，用户可重新启用 | D3 自愈只覆盖 `StaleCredentials`（**payload 解得开**但密码不对）；**unwrap 本身失败没有对应出口** |

⇒ OneDrive 库的坏登记：设置页显示「已启用」，**每次**填充扇出都重试-失败（正好喂养用户 09-28 报的「解锁频繁」观感），且**没有任何自动或引导式的修复路径**——用户只能碰巧自己重新走一遍启用流程。

### 3.4 决定性实验（下次真机会话第一步）

**把 OneDrive KDBX 库切为活跃库 → 锁定 → 在解锁页用指纹作为「首个库」解锁：**

- **仍失败**（报「本地解锁凭据不可用」）⇒ **H1 坐实**：payload 本身坏了（首个库用的是已授权 cipher，若还失败只能是密文/IV 错位）；
- **成功** ⇒ payload 是好的 ⇒ **H2 坐实**：扇出的 rest-cipher 授权缺口。

辅证手段：重走一次「生效范围」登记并勾上 OneDrive 库，抓 logcat 看 `commitForVaults` 对第二个库的 wrap 是否抛 `UserNotAuthenticatedException` / `IllegalStateException`（`commitOneSafely` 会吞成 `Failed(...)` 但有日志可查）。

### 3.5 修复方向（待拍板，不在本报告实施）

- **H1 修法**：`wrap` 每库**各用独立 cipher**（`newEncryptCipher` 只用于首个库的认证绑定；多库场景需重新设计——auth-per-use 下「一次认证包 N 个库」本身存疑，见 H2 同源问题）；
- **H2 修法**：扇出的 rest 解锁在 per-use 语义下**结构性不可行**——要么每库单独一次 CryptoObject 认证（交互不可接受），要么放弃「一次指纹开多库」（把 rest 库留给下次各自解锁），要么改用 setInvalidatedByBiometricEnrollment + 短有效期窗口（安全降级，需拍板）；
- **共同修法（P0.5，独立于 H1/H2）**：KDBX 侧 unwrap 失败遇到 `isLocalUnlockUnrecoverable()` 时**对齐 Bitwarden 侧清登记**——「勿删登记」的本意是保 D3 自愈，但 D3 只覆盖 StaleCredentials；AEADBadTag 类失败没有任何自愈路径，留着只会每轮扇出重试失败 + 谎报「已启用」（与 #93「谎报状态的开关」同族）。

---

## 4. ⚠️ 设计隐患（当前休眠，R-6~R-9 开放编辑后激活）

### 4.1 NeedsReload 路径提前推进 token 基线 → 潜在静默覆盖

`KdbxSyncOrchestrator.sync` 的「远端更新、本地未改」分支（:145-149）：

```kotlin
markStatus(vaultId, KdbxSyncTransitions.markRemoteChanges(), remoteNow)  // ← token := remoteNow
return SyncOutcome.RemoteNewerNeedsReload
```

问题：**token 的语义是「本地会话基于哪一版」**。NeedsReload 时会话还停在 v1，token 却被推进到 v2 ⇒ 系统从此「认为」本地已有 v2。

危险时序（**R-6~R-9 开放编辑后**）：

```
远端 v1→v2 → 同步报 NeedsReload（token:=v2，会话仍 v1 内容）
→ 用户在 v1 会话上编辑 → 再同步：remoteChanged=false（token 已是 v2）、localChanged=true
→ 走「只有本地变 ⇒ 推」→ saveVia(expected=v2) 条件写成功
→ **远端 v2 的改动被基于 v1 的内容静默覆盖**
```

当前不触发的原因：KDBX 只读（`requireWritable`），没有「本地编辑」这个输入。**这是 R-6~R-9 的直接前置**。
修法方向：NeedsReload **不推进 token**（保留旧值让 `remoteChanged` 持续为 true，直到会话真的被替换）；或 `REMOTE_CHANGES` 状态下闸掉写入。

### 4.2 `notifyLocalChangedDuringUpload` 全链路实现、零调用

「上传期间本地又改了 ⇒ 那笔改动永远推不上去」的竞态防护：`KdbxSyncOrchestrator:203-210` 实现、`KdbxCloudSyncCoordinator:204-206` 转发、`KdbxSyncRepositoryImpl:65-66` 再转发——**app 层没有任何调用方**（全仓 grep 仅数据层三跳）。当前只读所以无感；开放编辑后必须接上。

### 4.3 `localChangedSinceLastSync` 两处口径不一 + 伪冲突的数据丢失入口

| 入口 | 判定 | 位置 |
|---|---|---|
| 库列表页「同步」 | **硬编码 `true`**（保守取舍，注释已论证） | `VaultListViewModel.kt:178-180` |
| 设置页库行「同步」 | 持久化 `syncStatus` ∈ {PENDING_UPLOAD, PENDING_UPLOAD_WITH_LOCAL_CHANGES, CONFLICT} | `VaultActionsController.kt:158, 241-247` |

硬编码 `true` 的副作用：**远端更新 + 本地没改**（本应 NeedsReload）被伪报成**冲突**，弹三选项对话框。危害链：

1. 用户看到一个**不存在的冲突**（本地明明没改过）；
2. 若用户误选「用本地覆盖远端」⇒ `resolveUsingLocal` → `saveVia(force=true)` 把**旧会话内容**真覆盖远端——**云端新版本被永久丢弃**（对话框虽有警告，但用户是被伪冲突误导至此）。

### 4.4 已知边界（如实记录，非 bug）

- `RemoteNewerNeedsReload` 的**自动**拉取替换会话未实现（编排器文件头已声明）；手动路径 `resolveUsingRemote`（`KdbxCloudSyncCoordinator.kt:148-165`）实现完整（拉字节→换会话→才记状态）。
- KDBX 回收站不映射（`ItemRepositoryImpl.kt:110-112` 注释声明，`recycleBinCount` 另行告知 UI）。

---

## 5. 优先级建议

| 优先级 | 事项 | 理由 |
|---|---|---|
| **P0** | §3.4 决定性实验（切库锁定指纹解锁 OneDrive 库） | 一分钟出结论，决定 §3.5 修哪个 |
| P0.5 | KDBX 侧 unwrap 不可恢复时对齐「清登记」自愈策略 | 独立于 H1/H2，直接止住「每轮扇出重试失败」 |
| **P1** | §4.1 NeedsReload 不推进 token + §4.2 接线 notifyLocalChangedDuringUpload + §4.3 统一口径 | 三件都是 R-6~R-9 的硬前置；建议随「KDBX 单条编辑」方案一并拍板 |
| P2 | `collectionIds` 服务端行为确认（一次性实验） | 组织库激活前必须确认；当前个人库无感 |

---

## 附录：证据与代码索引

- 真机扇出失败日志：`Docs/progress/audit/evidence/2026-09-28-bprime-verify-VaultixAutofill.log`（另 `build/adb-capture/{bprime-verify.txt,vx-hits.txt}`，4 进程 ×4 次）
- 快解扇出实现：`app/src/main/java/io/vaultix/vaultix/ui/unlock/LocalUnlockFanout.kt:84-104`
- KDBX unwrap 失败分支：`data/repository/.../VaultRepositoryImpl.kt:562-575`
- Bitwarden 侧自愈对照：`VaultRepositoryImpl.kt:450-470` + `LocalUnlockFailure.kt:57-71`
- 多库登记连续 wrap：`data/repository/.../LocalUnlockEnrollment.kt:263-289` + `core/datastore/.../LocalUnlockKeyStore.kt:158-166`
- auth-per-use 参数：`LocalUnlockKeyStore.kt:202-213`
- NeedsReload token 推进：`data/repository/.../kdbx/KdbxSyncOrchestrator.kt:145-149, 241-259`
- 伪冲突入口：`app/.../ui/vaultlist/VaultListViewModel.kt:161-180`
- 数据层保护（#91/#92）：`data/bitwarden/.../sync/BitwardenSyncService.kt:93-98, 341-345, 364-366`
- 历史账本：`.ai/issues/07-数据与同步.md`、`.ai/issues/05-KDBX本地库.md`（#106）、`.ai/README.md`
