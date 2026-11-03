# 07-数据与同步 · 第 3 篇（#190 ~ #209）

> 主篇索引：[`07-数据与同步.md`](./07-数据与同步.md)（含计数规则与重号警告）
> 本篇 4 章 · 7.7KB · 覆盖 #190 ~ #209

## 190. 🔴 409 Conflict 被当成"确定性失败"**弃单** ⇒ 远端那条改动永久丢失（2026-10-26，对比审计）

- **现象**：多设备/多端操作后，某条条目的某次编辑在**所有设备上都不见了**，
  没有报错、没有冲突提示、队列里也没有残留。
- **根因**：`BitwardenSyncService.flushPending` 把 `(400..499)` 一律当"确定性失败"
  ⇒ **删队列行、不重试**。而 **409 Conflict** 恰恰是"这不是我的错，重来一次可能就成"
  的语义 —— 服务端修订号变了。而 `incrementRetry` 那条路（失败可重试）才该接住它。
  Bitwarden 官方客户端对 409 的处理也是**保留并重试**。
- **修法**：
  1. `const val HTTP_CONFLICT = 409`，并从 `DEFINITIVE_HTTP_CODES` 里剔除：
     `(400..499).filter { it !in setOf(401, HTTP_CONFLICT, 408, 429) }`；
  2. 新增 `Throwable.isConflictHttpFailure()`；
  3. 409 走 `incrementRetry`（保留在队列，下次重试；重试次数耗尽后落 `FAILED` 角标可见）。
- **⚠️ detekt 坑**：加完分支后 `flushPending` 圈复杂度 **16 > 14** 直接红。
  拆出 `handleOperationFailure(op, error): Boolean`（返回"算不算冲突"），
  调用点变成一行 `if (handleOperationFailure(effective, error)) conflicts++`。
  **踩过的雷**：降复杂度时"提前 return"也算分支。
- **⚠️ 测试坑**：`BitwardenSyncPendingOpFailureTest` 的 404 用例一开始**假红**
  （本该弃单却留在队列）—— 夹具里 `payload = "{}"` 缺 `CipherRequest.type` 必填项，
  `executeOperation` 在发请求**之前**就抛了 `MissingFieldException`，被归到"网络类"。
  必须给**合法** JSON：`{"type":1,"name":"2.xxx"}`。
  定位手段：临时 `System.err` + 读测试 XML 的 `<system-err>`
  （`VaultixLog` 默认 sink 是丢弃的，logcat 里什么都看不到）。
- **验证**：`BitwardenSyncPendingOpFailureTest` 5 条：409 保留且非 Success、404 仍弃单、
  500 保留可重试、空分组 + 本地 1 个照常删、空分组 + 本地 3 个跳过删除。
- **判据**：**4xx 不等于"可以扔"**。401（鉴权）/ 408（超时）/ 409（冲突）/ 429（限流）
  都是"再试"类，只有其余才是"这条请求本身错了"。

---

## 192. 🔴 远端的"空分组"被当成真相 ⇒ `pruneRemovedRows` 把本地文件夹清空（2026-10-26，对比审计）

- **现象**（潜在，靠 #185 那条链才可能触发，但一旦触发是批量删）：
  一次"服务端返回空分组列表"的同步，会把本地**所有**文件夹当作"远端已删除"清掉。
- **根因**：`pruneRemovedRows` 的逻辑是"远端没有的 = 远端删了"。但 `folderIds` 为空
  有两种可能：**用户真的删光了**（该删）和 **这次响应不完整 / 反序列化失败**
  （不该删）。代码无法区分，却默认按前者处理。
- **修法**：分组段加保护 —— `serverFolderIds.isEmpty() && removedFolders.size >=
  FOLDER_PRUNE_SUSPECT_THRESHOLD(3)` 时 **只报警、`return` 不删**。
  阈值 3 是有意的：删 1~2 个文件夹是日常操作，允许通过；一次要删 3 个以上
  而远端一个都没返回，更像事故。
- **判据**：**"远端没有" ≠ "远端删了"**。批量删除类操作必须有一条"这个数量合理吗"的
  熔断，且熔断方向要选**保守侧**（少删一次比误删一片好）。

---

## 202. 🔴 重复导入 = 每个条目两份，而界面上"无法判断哪一份是新的"（2026-10-28）

- **现象**：从旧设备导出 → 导进新设备 → 过几天又导一次，库里**每个条目都变成两份**。
  导入界面报"已导入 N 个条目"，一切正常。
- **根因**：`applyImportedVault` 的设计是"逐条新建、绝不合并"——这在**首次**迁移时正确，
  在**重复导入**时是灾难。密码管理器里两份同名条目的最坏后果不是难看，而是**改了一份、
  自动填充撞上另一份**，用户从此不知道该信哪一条。
- **修法**（三层，缺一不可）：
  1. **领域** `ImportDuplicatePolicy`：判重键 = **type + title + username**（`trim().lowercase()`
     归一化）。纯函数、表驱动单测 9 例。
  2. **仓储** `previewImport(vaultId, imported): ImportPlan`：写之前先算"新建 N / 已存在 M"，
     并把样例标题带回 UI；`applyImportedVault` 用**同一套**键跳过重复。
  3. **UI**：预览卡多一行"将新建 N 个；另有 M 个已存在（GitHub、GitLab），会被跳过"；
     预演没算完之前「确认导入」**点不动**（`canApply` 要求 `importPlan != null`）。
- **为什么判重键不用密码/网址/id**：改密码后标题用户名不变（该判重，按密码判会漏）；
  一条条目常挂多个网址且可空（笔记全撞一起）；id 换库必变（等于永不判重）。
  **用户名参与判定**是为了区分同一站点的两个账号 —— 这是唯一会把"该分开的"合并错的地方。
- **为什么"不静默跳过"**：导入是不可撤销的批量写。悄悄跳过，用户只会以为**文件少了条**。
- **判据**：**凡是"批量写且不可撤销"的操作，写之前必须给出可核对的数字，且数字与
  实际写入一致**（`预演的数字与实际写入的数字一致` 这条测试就是锁这个）。另：
  **判重口径只能有一处**（`ImportDuplicatePolicy.keyOf`），仓储的预演与写入都调它。

---

## 209. 改密码后：`passwordRevisionDate` 不更新、密码历史**永不增长**（2026-10-29）

**现象**：用户在 Vaultix 里改一百次密码，服务端与其它客户端看到的仍是建条目那天的状态：
**"密码年龄"恒旧、`passwordHistory` 恒空**，且**没有任何报错**。

**根因**：`CipherMapper.toUpdateRequest` 里两段都是**纯透传**：
- `overlayLogin` 的 `passwordRevisionDate` 沿用服务端原密文（KDoc 还写着"其余字段沿用原值"）；
- `passwordHistory = stored.passwordHistory`（透传的理由是对的：`PasswordHistory?.Select`
  ⇒ 不带即清空。但**只透传 = 永远不增长**）。

⇒ 这是典型的"**不报错但结果是假的**"：防丢失的那条纪律（未编辑段沿用原密文）被
错误地套用到了**恰恰应该变化**的字段上。

**修法**：新增 `passwordRevisionPlan()`（一次解密同时算两笔账，避免解两遍，也避免
"一个改了一个没改"的自相矛盾请求）：
- 旧密码明文 ≠ 新密码且新密码非空 ⇒
  ① `passwordRevisionDate` 写**现在**（`BitwardenTimestamps.format`，唯一时间口径）；
  ② 历史追加一条 `password = 旧密码原密文`、`lastUsedDate = 旧 passwordRevisionDate`（原密文，
  **不解密再重加密**：没有收益，只多一次"失败即丢数据"的机会；没有旧 revision 才兜现在）；
- 历史上限 `MAX_PASSWORD_HISTORY = 20`（服务端不设限，但每次保存都要整坨上传）。

**保守边界**：
- 旧密码**解不开** ⇒ **不加历史**（写进去的是空串 = 一条假记录：用户会以为自己某天用过空密码），
  但 revision 照写（"此刻重写了密码"这个事实成立）；
- 新密码为空 / 与旧值相同 ⇒ 两笔账都不动（"没改"不该留痕迹）；
- 非 Login 类型 / 服务端本来没有 login 段 ⇒ 不动。

> **判据**：① **"防丢失"的纪律（未编辑段沿用原密文）不能套到"恰恰该变"的字段上** ——
> 沿用是**手段**（别整条重写），不是**目的**（保持原样）；
> ② **一次改动留下两笔账**（生效时间 + 被替换者的停用时间）是官方语义，缺一笔就是假状态；
> ③ **拿不到旧值就不要编一条历史**（空密码历史比没有历史更糟）。、#209

---

## 242. 🔴 新接入的网盘库「基线为空 + 待上传」⇒ **一个字节没改也报冲突**（2026-11-03，真机）

**现象**（用户原话）："打开 onedrive 上的 kdbx 就是冲突，点击同步说选择覆盖，覆盖点完
就会卡死在页面，然后 bitwarden 切换到 kdbx，发现 kdbx 还是需要密码，然后提示没有纳入快速解锁。"

三个现象**同源**：

```
刚添加 OneDrive KDBX → syncStatus = PENDING_UPLOAD（impliesLocalChanges = true）
                     → remoteVersionToken = null（新行）
                     → 编排器 remoteChanged = (远端令牌 != 基线) = true（恒真）
                     → isConflict(true, true) = true ⇒ **每次都报冲突**
冲突未解决 ⇒ 库始终不在 quickUnlockScope ⇒ 快速解锁碰不到它 ⇒「仍要密码 / 未纳入」
```

**根因**：`forNewlyRegisteredVault(hasCloudSource = true)` 返回 `PENDING_UPLOAD` 而
**基线令牌留空**。它当时是被当成"正确但严格"写进 KDoc 的（首版文档里明写
"会判成冲突。这是**正确**的结局而不是误报"）——**那句是错的**：
用户刚刚用主密码解开这个文件、确认这就是他要的那一版，**远端此刻的内容就是他的基线**。
把"手里没有基线"当成"远端一定变了"，等于让用户替一个我们没记录的事实买单。

**修法**：新增 `KdbxSyncTransitions.adoptRemoteBaseline(vaultId, source, vaultDao, …)`，
两条接入路径（`VaultRepositoryImpl.addKdbxVault` / `KdbxCreateRepositoryImpl.createVault`）
在建完行之后立刻调一次，结局三选一：

| stat 令牌 | 本地可比字节 | 内容 | 结局 |
|---|---|---|---|
| 有 | — | 不比 | `IN_SYNC` + 写入令牌 |
| 无 | 无 | 不知道 | `Unknown` ⇒ 保持调用方的保守默认值（旧行为） |
| 无 | 有 | 一致 | `IN_SYNC`（令牌留空，下次同步由内容对账补上） |
| 无 | 有 | 不同 / 读不出 | ★ `REMOTE_CHANGED` |

**为什么不能"为了不报冲突"一律写 `IN_SYNC`**：那是在**没有依据**的情况下断言对齐。
从网盘添加时，用户输的那次主密码开的可能只是**远端当前文件**，引擎侧根本不会写本地缓存
（见 `CachedKdbxFileSource` 的 KDoc）⇒ 本机没有一段能称作"我这一版"的字节。
此时若标 `PENDING_UPLOAD` / `LOCAL_ONLY`，自动上传会拿**旧缓存或别的库**去条件写，
**远端那份被静默覆盖**。⇒ 判不出来就只能保守（`PENDING_UPLOAD` + 空令牌，报一次冲突），
判出来"不一样"就必须 `REMOTE_CHANGED`（只读、绝不自动推）。

**顺带修的第二处**：`VaultListViewModel.resolveKdbxConflict` 此前整体没有异常兜底。
`resolveUsingLocal` / `resolveUsingRemote` 内部虽然把 IO 失败收成了 `Result`，
但会话/编码/DAO 任一环节抛出（KDF 编码失败、`markResolved` 写库失败）就会让协程
静默终止：对话框已被 UI 清空、没有任何提示 ⇒ 用户看到的就是**"点覆盖之后卡死"**。
现在 `runCatching` 兜住，`onFailure` 里先尽力 `defer(vaultId)` 把状态拉回 `CONFLICT`
（否则界面是个永远转不完的圈），再如实报错。

> **判据**：① **"手里没有基线"不等于"远端变了"** —— 没记录的事实要**去问**（stat /
> 内容对账），不能默认成最坏值推给用户；② 但**"问不到"更不等于"对齐了"** ——
> 两种失败模式里，只有"多报一次冲突"是可接受的，静默覆盖不是；
> ③ **凡是用户点了有后果的动作（覆盖/放弃/删除），都必须有异常出口** ——
> 无兜底的 `launch` 等于把"崩了"呈现成"没反应"，这比报错难查十倍。
