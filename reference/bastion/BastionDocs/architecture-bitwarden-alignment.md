# 架构改造：对齐 Bitwarden 原生模型（验证码 / 通行密钥归属密码条目）

> **状态**：🔧 **进行中**（阶段 3 部分完成）。当前 HEAD `899393d`，dev / main 已同步。
> **目标**：消除验证码/通行密钥的"双表示 + 双写"问题，根治 Bitwarden 同步冲突。
> **当前方向**：绑定型验证码采用**方案二（明确主从）**（§5.1），等用户真机验证（阶段 4）后再继续实施。

---

## 0. 接力说明（下一个会话请先读这里）

### 📍 进度快照（2026-09-04 更新）

| 阶段 | 状态 | 说明 |
|---|---|---|
| 阶段 0 准备 | ✅ 完成 | 备份已导出；三项待确认已拍板 |
| 阶段 1 数据流梳理 | ✅ 完成 | 产出见 §6.1（双表示 / 双写确切位置） |
| 阶段 2 日志打点 | 🟡 部分 | 分流 + 虚拟条目丢弃已加，另 3 项待补 |
| 阶段 3 对齐改造 | 🟡 部分 | 迁移语义 ✅、单一归属 UI ✅；通行密钥 / 删除语义 / 显示待做 |
| 阶段 4 验证 | 🟡 首轮未过 → 已修 | 首轮抓到根因，`d15f022` 已修复（CI success），等用户装包复验 |
| 阶段 5 拆分大文件 | ⬜ 未开始 | 单独一轮 |

**本轮提交（dev / main 均已同步）**：

| commit | 内容 | 状态 |
|---|---|---|
| `4349a5f` | 验证器编辑绑定型不再走 `saveTotpAcrossTargets`（**核心冲突修复**） | ✅ |
| `808603e` | 同步分流 / 虚拟条目去重诊断日志 | ✅ |
| `95e28e2` | 密码条目真迁移：改选库移除旧副本 + 解锁已有位置 | ✅ |
| `d2b260d` | 存储选择器单一归属（直接点选数据库） | ✅ |
| `e9526ca` | ~~单一数据源~~ | ❌ 已回滚（误判双写） |
| `49658d5` | revert `e9526ca` | ✅ |
| `bd6e549`、`899393d` | 文档更正 + 确定方案二 | ✅ |
| `d15f022` | **绑定型验证码不占 Bitwarden 存储位**：保存时不继承 vault + 上传时跳过（两道防线） | ✅ CI success，669 tests 通过 |

**下一步**：

1. 等用户装 `d15f022` 复验：新建密码条目（含验证码、选 Bitwarden）→ 本地应**只有一条**、服务器**无新增 `otpauth://`**
2. 复验通过后，再做**彻底的方案一**（用户最终诉求）：
   绑定型验证码**不建** `secure_items`，只随密码条目 `authenticatorKey` 同步；
   验证器界面仍可编辑，编辑结果**写回密码条目**（正面解决负 id 虚拟条目不可编辑，而非靠多存一份绕过）
3. 清理历史遗留的 `otpauth://` 脏数据（前几轮测试产生）

---

本文档是自包含的改造纲。继续工作时：

1. 先读本文档全文，再读 `.codebuddy/memory/2026-09-03.md`（含所有排查细节与踩坑记录）。
2. **严格遵守第 7 节的阶段顺序**，不要跳步。
3. 文档中标注「待确认」的点，先与用户确认再动手。
4. **严禁在上下文吃紧时动手改核心逻辑**——2026-09-03 已在疲惫状态犯过两次错（漏删 `previewQuickFiltersEnabled` 三处引用、承载条目标记漏判 `issuer` 为空）。
5. 每完成一个阶段，回来更新本文档的「状态」与进度。

---

## 1. 背景与问题陈述

用户反馈的严重问题（已真机复现）：

1. 在密码条目里填验证码并同步到 Bitwarden 后，**App 报同步冲突**。
2. 验证码在验证器页的 **Bitwarden 分类下看不到**，必须切到 All 搜索才能找到。
3. 删除本地那条验证码 → **连带把 Bitwarden 服务器上正主条目的验证码也删掉了**。
4. 把验证码"复制"到 Bitwarden → 服务器上多出一条**网站为 `otpauth://totp/xxx` 的垃圾密码条目**。

**根因不是某个 bug，而是数据模型与 Bitwarden 不匹配。**

---

## 2. 现状架构：Bastion vs Bitwarden

| | Bitwarden 原生 | Bastion 当前 |
|---|---|---|
| 密码 | cipher（login.username/password） | `password_entries` |
| 验证码 | cipher 的 `login.totp` 字段 | **两种并存**：①`password_entries.authenticatorKey` ②独立的 `secure_items`(itemType=TOTP) |
| 通行密钥 | cipher 的 `login.fidos` | 独立的 `passkeys` 表（`bound_password_id` 关联） |
| 独立验证器 | **不存在此概念** | 支持（不依附密码条目的 TOTP） |

---

## 3. 根因：同一份数据的两种表示 → 双写 → 冲突

一条绑定型验证码（依附某条密码条目）当前会落两处：

| 表示 | 存储位置 | 同步行为 |
|---|---|---|
| ① 密码条目的属性 | `password_entries.authenticatorKey` | 同步到 cipher 的 `login.totp` |
| ② 独立的 TOTP 记录 | `secure_items`（带 `boundPasswordId`，自身**不占** `bitwardenVaultId`/`bitwardenCipherId`） | 自己也维护一份状态 |

两份指向同一份数据，走两套独立代码路径，状态认知一旦不一致 → **同步冲突**。
叠加"复制"产生的多副本（实测 testtop2 产生 **3 条 `password_entries` + 3 条 `secure_items`**），冲突进一步放大。

---

## 4. 目标架构

**对齐 Bitwarden：验证码与通行密钥都是密码条目的附属属性。**

```
PasswordEntry（对应一个 cipher）
├── authenticatorKey   ←→ cipher.login.totp      （验证码，唯一数据源）
├── passkey 绑定       ←→ cipher.login.fidos     （通行密钥，唯一数据源）
├── customIconType / customIconValue              （图标复用密码条目的）
└── bitwardenVaultId / bitwardenCipherId          （存储归属）
```

**分两类处理**：

- **绑定型**（依附某条密码条目，占绝大多数）→ 严格对齐 Bitwarden，**不再创建独立记录**。
- **独立型**（不依附任何密码条目，Bastion 特有扩展）→ **保留**（见第 12 节待确认项）。

---

## 5. 改造方案

### 5.1 绑定型验证码（核心）— 方案二：明确主从（**已定，2026-09-04**）

> 曾尝试「方案一：删掉绑定型记录、只留虚拟条目」，因误判双写且会导致绑定型验证码**不可编辑**（负 id）而撤回，详见 §7 更正块。

**主从模型**：

|  | 主（Master） | 从（Slave） |
|---|---|---|
| 载体 | `password_entries.authenticatorKey` | `secure_items`(TOTP)，`bound_password_id` 指向主 |
| 职责 | **唯一同步源** → `cipher.login.totp` | 只存 **Bastion 特有属性**（图标、本地备注等） |
| 同步 | ✅ 参与（随密码条目） | ❌ **永不参与**（`bitwardenVaultId`/`bitwardenCipherId` 恒为 NULL，`syncStatus=NONE`） |
| 生命周期 | 独立 | 跟随主（主删 → 从删） |
| 存在意义 | 与服务器对齐 | 给验证器页一个**正 id 可编辑实体**（虚拟条目 id 为负，无法编辑） |

| 操作 | 改后行为 |
|---|---|
| 保存 | 主：写 `authenticatorKey`；从：创建/更新且**不分配 vault/cipher** |
| 显示 | stored(从) 与 virtual(自主解析) 合并去重；**存储归属继承自主记录** |
| 删除 | 清空主的 `authenticatorKey`（触发同步）+ 删除从记录 |
| 图标 | 存在**从记录**上（这正是它存在的价值），主不冗余存图标 |

**关键约束（必须写进代码注释 + 回归测试）**：

1. 绑定型记录**永不**分配 `bitwardenVaultId` / `bitwardenCipherId`。
2. 绑定型记录**永不**进入任何 sync 目标写入路径。
3. **服务器路径唯一**：只有密码条目的 `authenticatorKey` → `cipher.login.totp`。
4. **显示归属继承**：验证器页筛选/归类时，绑定型记录的存储归属**取绑定密码条目的**，而非自身（自身为 NULL）——否则会重现"Bitwarden 的验证码被归类到本地 / Bitwarden 下搜不到"（§6 `TotpCategoryFilter` 已记录此坑）。

### 5.2 绑定型通行密钥

同理：数据落在密码条目上（`passkey_bindings` 或对应字段 ↔ cipher 的 `login.fidos`），
消除 `passkeys.bound_password_id` 与 `password_entries.passkey_bindings` **两套关联并存**的问题
（这也是"通行密钥无法筛选"的根因：`includePasskeyChip=false` 被硬编码禁用）。

### 5.3 连带收益

- 单一数据源 → **无双写 → 同步冲突消失**
- 虚拟 TOTP 天然带密码条目归属 → **"归类到本地"与"Bitwarden 下搜不到"两个显示问题自动消失**
- 一套关联 → **通行密钥筛选可恢复**

---

## 6. 涉及代码清单（已确认位置）

### TotpViewModel.kt
| 函数 | 行号 | 改动 |
|---|---|---|
| `savePasswordBoundTotp` | 907 | 增加 `onComplete` 回调（**已完成**） |
| `savePasswordBoundTotps` | 927 | 改为只写 authenticatorKey |
| `savePasswordBoundTotpInternal` | 952 | **核心**：移除创建 SecureItem 的逻辑 |
| `saveTotpItem` / `saveTotpAcrossTargets` / `saveTotpItemInternal` | 1058 / 1095 / 1223 | 仅服务独立型，保持不变 |
| `deleteTotpItem` | 1549 | 绑定型分支改为清空 authenticatorKey；需补 replica 副本清理 |
| `unbindTotpFromPassword` | 1462 | 保留（解绑语义） |
| `removeOtherBoundTotpsForPassword` | 1501 | 保留 |
| `mergeStoredAndVirtualTotps` | 284 | 绑定型不再有 stored 记录后，合并逻辑需简化 |
| `collapseDuplicateBoundStoredTotps` | 323 | 同上 |
| **筛选逻辑** | 400-450 | **关键**：`TotpCategoryFilter.BitwardenVault` 按记录自身 `bitwardenVaultId` 过滤，导致绑定型筛不到 |
| `moveTotpToStorage` / `moveTotpToBastionLocal` | 1877 / 1812 | 独立型移动逻辑，保持不变 |

### CipherSyncProcessor.kt
| 函数 | 行号 | 说明 |
|---|---|---|
| `syncLoginCipher` | 217 | 对 type=1 cipher **先无条件** `syncPasswordCipher`，再补充 TOTP → 承载条目被处理两次 |
| `isBastionStandaloneTotpContainer` | 新增 | 我加的判据：`isStandaloneTotpCipher` **且** `login.uris` 含 `otpauth://` |
| `syncPasswordCipher` | 255 | 密码条目同步（authenticatorKey ↔ login.totp 转换在此） |

### TotpMapper.kt
| 函数 | 行号 | 说明 |
|---|---|---|
| `toCreateRequest` | 28 | 承载条目构造；**已修**：URI 始终写入（issuer 为空用 title 兜底） |
| `fromCipherResponse` | 53 | cipher → SecureItem；用 `parseIssuerFromUri(login.uris)` 回解 issuer |
| `isStandaloneTotpCipher` | 223 | 判据：`type==1 && totp 非空 && password 为空` |

### 其他
| 文件 | 位置 | 说明 |
|---|---|---|
| `AddEditPasswordScreen.kt` | ~1561 | 调用 `savePasswordBoundTotps`；需同步改造 |
| `AuthenticatorTabPane.kt` | onSave 102 / 162 | 验证器保存入口（已加绑定型分流 `4349a5f`） |
| `TotpListContent.kt` | 453-490 | `boundPasswordIdFor`、`willClearBoundPasswordTotp`、`totpStorageCounts`（归类已修 `212a597`） |
| `PasswordViewModel.kt` | ~3173 / 3274 | `savePasswordsAcrossTargets`；**staleReplicas 只 Log 不删**（"只能复制不能迁移"根因） |
| `MultiStorageTargetPickerBottomSheet.kt` | ~431/548/556 | 存储选择器；`lockedTargetKeys` 锁住已有位置（文案已改为移动/复制 `0ae2e3e`） |

### 数据表速查
- `password_entries`：`authenticatorKey`、`passkey_bindings`、`bitwarden_vault_id`、`bitwarden_cipher_id`、`custom_icon_type/value`、`replica_group_id`
- `secure_items`：`itemType`、`itemData`(加密，含 `boundPasswordId`/`customIconType`)、`bitwarden_vault_id`、`bitwarden_cipher_id`、`replica_group_id`、`sync_status`
- `passkeys`：`bound_password_id`、`bitwarden_vault_id`、`bitwarden_cipher_id`
- `bitwarden_pending_operations`、`bitwarden_conflict_backups`

> ⚠️ **列名坑**：bitwarden 相关列是 **snake_case**（`bitwarden_vault_id`），普通列是驼峰（`isDeleted`、`itemType`）。查询前先 `PRAGMA table_info`。

---

## 6.1 【阶段 1 产出】双表示 / 双写的确切位置（2026-09-04 已定位）

### ★ 源头：一次保存，落了两处

`AddEditPasswordScreen.kt` 保存密码条目时：

1. **先**保存密码条目本身，`commonEntry` 里已包含 `authenticatorKey`（→ 同步到 cipher 的 `login.totp`）
2. **随后**又调用 `savePasswordBoundTotps` 额外建一条绑定型 SecureItem

```kotlin
// AddEditPasswordScreen.kt:1548-1577
if (currentAuthKey.isNotEmpty() && totpViewModel != null) {
    val totpData = resolvedAuthTotp.copy(..., boundPasswordId = firstPasswordId)
    totpViewModel.savePasswordBoundTotps(   // ★ 双表示源头
        passwordIds = savedPasswordIds.ifEmpty { listOf(firstPasswordId) },
        title = currentTitle, notes = "", totpData = totpData,
        preferredTotpId = existingTotpId
    )
}
```

→ `TotpViewModel.savePasswordBoundTotpInternal`（952）创建/更新 `secure_items`(TOTP)。
于是同一份验证码有了两种表示。

### ★ 危害一：显示层——"Bitwarden 分类下搜不到"的确切原因

`TotpViewModel.mergeStoredAndVirtualTotps`（284）：

```kotlin
val virtualTotps = allPasswords.mapNotNull { password ->
    val resolvedTotpData = resolvePasswordAuthenticatorTotp(password) ?: return@mapNotNull null
    val identityKey = buildTotpIdentityKey(resolvedTotpData)
    if (identityKey in existingKeys || !seenVirtualKeys.add(identityKey)) {
        return@mapNotNull null   // ★ 298 行：存在同密钥 stored TOTP 时，虚拟 TOTP 被丢弃
    }
    SecureItem(
        id = -password.id,                              // 虚拟条目用负 id
        ...
        bitwardenVaultId = password.bitwardenVaultId,   // ★ 315 行：虚拟 TOTP 本带正确归属
        bitwardenFolderId = password.bitwardenFolderId
    )
}
```

虚拟 TOTP 明明带着密码条目的 `bitwardenVaultId`（315 行，归属正确），却在 **298 行因去重被丢弃**；保留下来的 stored TOTP 自身 `bitwardenVaultId = null`。
再叠加筛选逻辑（**419-426**）按记录自身 vaultId 过滤 → null → **Bitwarden 分类下永远筛不到**。

> 这也解释了 212a597 为何只修好了一半：修了条数统计，没修列表筛选与去重。

### ★ 危害二：删除层——"删验证器连带删掉服务器验证码"

`TotpViewModel.deleteTotpItem`（1557-1591）：绑定型记录被删时，会清空所绑定密码条目的 `authenticatorKey` 并 `requestBitwardenMutationSync` → 同步后服务器上该 cipher 的 `login.totp` 被清掉。
因为绑定型记录被当成了"这条验证码的唯一载体"，删它＝删验证码。

### ★ 改造后（单一数据源）的连带影响：负 id 虚拟条目

改为单一数据源后，验证器界面里的绑定型验证码**只能由虚拟 TOTP 呈现**，其 `id = -password.id`（负 id）。

注意 `AuthenticatorTabPane.kt:134` 有：
```kotlin
} else if (selectedTotpItem == null || selectedTotpItem.id <= 0L || selectedTotpData == null) {
    Text("This item is not available for inline editing")   // ★ 负 id 条目不能行内编辑
}
```
**影响**：绑定型验证码在验证器界面将变成"只读/虚拟"条目，无法在此编辑（含改图标）。
**应对**：图标复用密码条目的 `customIconType/customIconValue`，用户需到密码条目页修改——这与"方案一（图标挂密码条目）"一致，属预期行为，但需告知用户并视情况优化该提示文案。

---

## 7. 实施阶段（务必按顺序，可跨会话接力）

### 阶段 0：准备
- [x] 用户导出 Bitwarden 备份（2026-09-04 用户确认已完成；后续删除类改动可放心进行）
- [x] 确认第 12 节待确认项（2026-09-04：①独立型验证器保留 ②通行密钥一起改 ③迁移问题一起解决）

### 阶段 1：数据流梳理（自己读代码，勿依赖子代理）
> 子代理已两次未能回传结论（输出被截断、要求写文件也未成功），此路不通。
- [x] 读 `AddEditPasswordScreen` 验证器保存路径
- [x] 读 `TotpViewModel` 284-450（merge / 去重 / 筛选）
- [x] 读 `CipherSyncProcessor` 分流与 `syncPasswordCipher`
- [x] 读 `deleteTotpItem` 绑定型分支
- [x] **产出**：双表示 / 双写确切行号清单 → 见 **6.1 节**

### 阶段 2：加日志打点（与阶段 3 同批提交）
在以下节点补 `Log`，让同步过程可见（当前正常路径**完全不打日志**，这是之前定位失败的直接原因）：
- [x] `CipherSyncProcessor` 分流：此 cipher 判为密码条目 / 承载验证器（`808603e`）
- [x] 验证器虚拟条目被去重丢弃：`TotpViewModel:298` → `VIRTUAL_TOTP_DROPPED`（`808603e`）
- [ ] `resolveBitwardenTransition`：本地与远端如何匹配、是否新建
- [ ] `savePasswordBoundTotp*`：写了哪些表/字段
- [ ] 同步队列每条操作入队 / 出队 / 成败

### 阶段 3：实施对齐改造（部分完成 2026-09-04）
- [x] 迁移语义（`95e28e2`）：密码条目 `staleReplicas` 真删除（对齐验证器）+ 解锁 `lockedTargetKeys`（改选库=真迁移）
- [x] 日志打点（`808603e`）：`VIRTUAL_TOTP_DROPPED` / `CIPHER_ROUTE_TOTP_CONTAINER` / `CIPHER_ROUTE_PASSWORD`
- [x] 存储选择器：单一归属（`d2b260d` 隐藏"移动/复制"切换 + 文案改为"切换数据库＝迁移"）
- [ ] 绑定型通行密钥：落到密码条目，统一关联
- [ ] 删除逻辑：语义改为"修改密码条目字段"
- [ ] 验证器显示：待真机验证

#### ⚠️ 重要更正：绑定型 SecureItem **不是**双写源，`e9526ca` 已回滚（`49658d5`）

曾误判「密码页保存验证码 = 双写」，据此提交 `e9526ca` 移除 `savePasswordBoundTotps` 调用。**这是错的，已回滚。** 事实如下：

1. **绑定型记录根本不参与 Bitwarden 同步**：实测 `secure_items` 中绑定型记录为 `bitwarden_vault_id=NULL`、`bitwarden_cipher_id=NULL`、`sync_status=NONE`，它只是**本地影子记录**；服务器上的验证码**只有一个来源**——密码条目的 `authenticatorKey` → `cipher.login.totp`。**不存在双写。**
2. **它是验证器页可编辑性的保障**：回归测试 `TotpPasswordBindingRegressionGuardTest` 明确断言——
   > "Do not return early to rely only on virtual password.authenticatorKey entries; virtual entries have negative ids and cannot be edited as real TOTP rows."

   去掉 stored 记录后，绑定型验证码只剩负 id 虚拟条目，**无法编辑**（改图标失效），属功能退化；CI 因此报 `669 tests completed, 2 failed`。
3. **真正的冲突源是别的**：`4349a5f` 之前，验证器界面编辑走 `saveTotpAcrossTargets`，把绑定型 TOTP 当独立条目写入 Bitwarden → 新建 `otpauth://` cipher。**这个已经修好了**，不需要再动密码页。
4. **教训**：
   - 改动前先确认该记录**是否真的参与同步**，不能凭"看起来存了两份"就判定双写。
   - **回归测试失败是强信号**，必须先读懂测试意图，再决定改实现还是改测试——这次测试是对的，是我的实现错了。
5. **已定方向（2026-09-04 用户拍板）**：改用**方案二（明确主从）**，详见 §5.1。核心是**重新定位**绑定型记录——降为"只存 Bastion 属性、不参与同步"的附属品，而不是删除它。

### 阶段 4：验证（2026-09-04 真机首轮 → **未通过**，已定位根因）

#### 取证结果（数据库实锤，设备 BKQ-AN00）

**现象**：添加密码条目 test4（选定 Bitwarden 存储），本地多出一条同名条目。

```
password_entries:
  id=456 | test4 | cipher=d6475517-... | rg=646aeb01-... ← 用户真正创建的
  id=457 | test4 | cipher=4650a5c2-... | rg=None        ← 多出来的"副本"（自带 authenticatorKey）

secure_items (TOTP):
  id=34 | test4 | bwv=1 | cipher=4650a5c2-... | SYNCED   ← 与 id=457 同一个 cipher
```

**同一个验证码被建了多条记录**（test3 更明显，3 条）：

| id | vault | cipher | 说明 |
|---|---|---|---|
| 32 | **1** | 57aff8b2 | ❌ 被当独立条目上传 |
| 31 | None | None | ✅ 绑定型（正确） |
| 30 | None | None | ✅ 绑定型（正确） |

#### 根因链（已定位）

1. 密码页保存验证码 → `savePasswordBoundTotpInternal`
2. `TotpViewModel:1006` `preserveSelectedSourceStorage = selectedSourceItem?.first?.id == preferredItem?.first?.id`
   —— 当匹配复用到某条**历史遗留的、已归属 Bitwarden 的独立 TOTP** 时为 `true`，
   于是**连 `bitwardenVaultId=1` 和 cipher 一起继承** → 绑定型被"洗白"成独立条目
   （另注：新建场景两者皆 null 时 `null == null` 也为 `true`，属逻辑瑕疵）
3. 该 TOTP 因此进入 Bitwarden 上传队列
4. `CipherUploadProcessor:121` `ItemType.TOTP -> createTotpCipherRequest(...)`
   —— **无条件**当独立条目上传，**不判断 `boundPasswordId`** → 创建 `otpauth://` cipher
5. 同步拉回 → 该 cipher 被解析成**密码条目** → 本地多出"副本"（即 `id=457`）

> 结论：**"本地多一份副本"是结果，不是原因**。根因是绑定型 TOTP 拿到了不该有的 Bitwarden 归属并被上传。

#### 修复方向（两道防线，纵深防御）

1. **保存层**：`savePasswordBoundTotpInternal` 中，绑定型 TOTP **强制清空** Bitwarden 归属
   （`bitwardenVaultId` / `bitwardenCipherId` = null），**不继承**来源记录的存储。
2. **上传层**：`CipherUploadProcessor` 增加守卫——TOTP 若 `boundPasswordId != null`，**拒绝上传**
   （其验证码已随密码条目 `authenticatorKey` → `cipher.login.totp` 同步，无需自立门户）。

#### 遗留观察

- 本轮日志中**未捕获到** `808603e` 加的业务日志（`CIPHER_ROUTE_*` / `VIRTUAL_TOTP_DROPPED`），
  需确认用户所装包是否包含该 commit，或日志条件未触发。

- [ ] 用户真机复现原场景，用新日志确认无冲突
- [ ] 数据库取证：`password_entries` 与 `secure_items` 不再出现同 cipher 双记录
- [ ] 回归：独立型验证器仍正常

### 阶段 5：拆分 `PasswordListContent`（单独一轮）
**现状**：2138 行 / 103KB，单函数含 **50+ 个 `by remember` 状态**，ART 告警 `Method exceeds compiler instruction limit: 18895`。
- [ ] 抽出 `PasswordQuickFilterState`（14+ 个 quickFilter* 状态）
- [ ] 抽出 `PasswordSelectionState`（多选/批量）
- [ ] 抽出 `PasswordDialogState`（各类对话框）
- [ ] 复用已有 `PasswordQuickFolderSupport.kt`、`PasswordGrouping.kt`
- [ ] 完整回归（密码页最高频）

---

## 8. 验证工具（已验证有效）

**数据库取证**（debug 包可 `run-as`）：
```powershell
$env:PATH += ";C:\AndroidSDK\platform-tools"
foreach($s in @("","-wal","-shm")){
  Start-Process -FilePath "adb" -ArgumentList "exec-out","run-as","com.bastion.app","cat","databases/password_database$s" `
    -RedirectStandardOutput "$env:TEMP\pwd.db$s" -NoNewWindow -Wait
}
python query.py   # 用 sqlite3 查询
```
关键查询：同一 cipher 是否同时存在于 `password_entries` 与 `secure_items`（是 = 双表示未消除）。

**adb 其他**：
- 截图：Bastion 设了 FLAG_SECURE，多数界面截不到；新建/编辑页有时可成功
- `adb shell uiautomator dump /sdcard/win.xml` 导出 UI 树读文本，**不受 FLAG_SECURE 限制**

---

## 9. 风险与回滚

| 风险 | 应对 |
|---|---|
| 改动删除远端数据（Bitwarden/KeePass） | 动手前用户导出备份；删除走软删除 + 回收站 |
| 历史脏数据迁移出错 | 单独一个阶段，先只读校验再执行 |
| 拆分 UI 引入回归 | 阶段 5 独立一轮 + 完整回归 |

**回滚**：每个阶段一个 commit，出问题按 commit 回退。

---

## 10. 已完成的配套修复（保留）

| commit | 内容 |
|---|---|
| `4349a5f` | 验证器编辑绑定型时不再走 `saveTotpAcrossTargets`（不自立 cipher） |
| `d9a448e` | 承载 cipher 只同步为验证器，不再重复生成密码条目 |
| `8ac03bb` | 承载条目始终写入 otpauth 标记（修 `issuer` 为空时判据失效） |
| `212a597` | 绑定型验证码归类继承密码条目归属；删除提示仅在目标存在时警告 |
| `0ae2e3e` | 存储选择器文案「单选/多选」→「移动/复制」 |

---

## 11. 教训（写给自己）

1. **修症状不修根因，必然改一个冒一个。** 前 5 个 commit 都只是在堵漏洞。
2. **没有日志就只能靠结果倒推**，倒推出来的结论只能支撑打补丁。先让过程可见，再谈修。
3. **共用字符串先查引用**（`single_select` 被滑动选择模式共用，差点误改成"移动"）。
4. **删字段要同时搜字段名与 UI 局部变量名**（漏删 `previewQuickFiltersEnabled` 导致 CI 挂 3 次）。
5. **Composable 内不能给局部函数加 `private`**（Kotlin 不允许，lint 不报）。
6. **lint 通过 ≠ 编译通过**，推送后必须 `gh run watch` 确认 CI。

---

## 12. 已确认决策（2026-09-04 用户拍板）

1. **独立型验证器：保留**（继续用 SecureItem + 承载 cipher）
2. **通行密钥：本次一并改造**（与验证码同样绑到密码条目，消除两套关联并存）
3. **"只能复制不能迁移"：本轮一并解决**（密码条目 `staleReplicas` 由"只 Log 不删"改为真删除）
4. **绑定型验证码采用「方案二：明确主从」**（2026-09-04 用户拍板）：密码条目为唯一同步源，绑定型 SecureItem 保留但降为"只存 Bastion 特有属性、不参与同步"的附属品——既保住验证器页可编辑，又只有一条路径写服务器（替代已撤回的方案一）

> ⚠️ 见下方第 5 条：用户同日深夜进一步明确诉求后，方向向"方案一"回调，**本条待重新评估**。

5. **单一归属：一条数据只存在于一个地方**（2026-09-04 深夜用户明确拍板）

   > **用户原话**：「添加密码条目的时候，里面涵盖了验证码，就提交到 bitwarden 等他同步完成，本地就不要画蛇添足保存到本地，多此一举。」

   - 用户选哪儿就存哪儿，**保存时不得"顺手"在本地再留一份**。
   - **绑定型验证码不再建本地 `secure_items` 记录**，只随密码条目的 `authenticatorKey` → `cipher.login.totp`。
   - **必须区分两个概念**（否则会误删必需的东西）：
     | | 说明 | 处置 |
     |---|---|---|
     | 本地**镜像** | 云端数据同步下来的缓存，标记了真实归属（`bitwarden_vault_id=1`）；离线可读、自动填充依赖它 | ✅ **必需**，保留 |
     | 本地**副本** | 额外存一份、归属为本地、与云端那条并存的第二条 | ❌ **多余**，是 bug，删除 |
   - **可编辑性必须正面解决，不得靠"多存一份"绕过**：验证器界面照常编辑绑定型验证码，但编辑结果**写回密码条目**
     （而非另存一条 SecureItem）。下午回滚方案一正是因为被"负 id 虚拟条目不可编辑"拦住——
     那是用错方法绕开问题，不是问题无解。
   - 独立型验证器（不绑密码条目的、用户自己的）**保持不变**：仍用 SecureItem + 承载 cipher。

---

## 13. 移动语义（用户方案 · 核心设计）

> **用户原话**：「以后本地保存的验证码或通行密钥，可以选择保存到 Bitwarden 或 KDBX 上，保存的时候就清理掉本地的内容。从远程迁移到本地同理（比如从 Bitwarden 迁移到本地，那么 Bitwarden 的原始条目会被删掉）。这样就不会因为副本和保存位置导致出错。」

### 13.1 原则

**一条数据在任何时刻只存在于一个位置（单一归属）。**

| 操作 | 行为 |
|---|---|
| 选择保存位置 | = **移动**，不是复制 |
| 改到新位置 | 写入新位置 → 确认成功 → 删除原位置 |
| 远程 → 本地 | 写入本地 → 成功后删除远程原始条目 |
| 本地 → 远程 | 上传远程 → 成功后删除本地内容 |

### 13.2 为什么这个方案对（评估）

- **根治副本问题**：没有副本 → 没有残留、没有多副本打架、没有"编辑页减不掉"的怪事
- **符合用户心智**：用户整晚的困惑就源于"点了移动，两边都还在"
- **契合远程存储的实际**：Bitwarden / KeePass 是权威存储，本地是镜像，多活副本在此没有价值
- **消除半成品行为**：`staleReplicas` 那条 "Preserving..." 的日志本就是没做完的遗留

### 13.3 实施时必须处理的三个技术点

1. **事务性（防数据丢失，最重要）**
   先写目标 → 确认成功 → 再删源。删源失败要标记待清理或回滚，**绝不能出现"两边都没有"**。
   参考：`TotpViewModel.moveTotpToBastionLocal`（~1812）已有 "source cleanup failed" 处理雏形，需把这套"先写后删 + 失败处理"推广到所有移动路径。

2. **离线 / 同步失败**
   目标在远端而当前离线时**不能立即删源**（否则数据丢失）。应保持"待同步"状态，待 `bitwarden_pending_operations` 出队成功后再清理源。

3. **多设备**
   若用户在其他设备仍持有旧副本，同步后可能"复活"。删除需走 pending operation 同步到所有端。

### 13.4 涉及改动

- `PasswordViewModel.savePasswordsAcrossTargets`（~3173 / 3274）：`staleReplicas` 从 `Log.w("Preserving")` 改为真删除
- `MultiStorageTargetPickerBottomSheet`：解除 `lockedTargetKeys` 对已有位置的锁定（否则永远不会有 stale 副本）
- 所有移动路径统一"先写后删 + 失败标记"的事务模式
- KDBX 侧同理（`moveTotpToStorage` / KeePass 移动路径）

### 13.5 已确认（2026-09-04）：**完全取消"复制 / 多位置保存"**

用户在"完全取消"与"保留为高级选项"之间选择**完全取消**。

**影响**：
- 存储位置选择器**只保留单一位置选择**，移除"移动 / 复制"模式切换（`StoragePickerSelectionMode.SINGLE / MULTI`）
- `replicaGroupId` / 副本机制退场：不再有多份副本，`staleReplicas` 相关逻辑一并删除
- 文案调整：不再需要"移动 / 复制"措辞，改为直接选"保存到哪里"
- **注意**：`single_select` / `multi_select` 字符串仍被 `ExtensionsScreen` 的滑动选择模式共用，清理时不要误删原字符串；新增的 `storage_picker_mode_move` / `_copy` 可删除
- 历史遗留副本需要一次性迁移清理（阶段 3 处理）
