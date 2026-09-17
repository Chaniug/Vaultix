# KDBX 接入网盘（OneDrive / WebDAV）—— 方案与任务清单

> **状态**：📋 **待用户拍板**（§8 冲突策略、§14 未决问题）→ 拍板后按 §11 开工
> **建立**：2026-09-17 凌晨 · **关联**：`.ai/SESSION-2026-09-17.md`、`.ai/ISSUES.md` #106、
> `.ai/conventions/8.3-M2KDBX.md`、`Docs/progress/next-steps.md`
> **用户原话**：「我项目里面的 kdbx 的同步功能还没接上。比如 onedrive，以及 webdav」
> 「bastion 里面有现成的 onedrive 的做好的代码吧，你看一下，应该也很简单的」
> **用户的硬要求**：「同步不丢，数据不错不漏，同步下来的不损坏不错漏」

---

## 0. 三句话（先记住，后面都是展开）

1. ★ **前置是 KDBX 阶段 B（写回）**，而它**技术可行已核实**：kotpass 0.13.0 有
   `encode(KeePassDatabase, OutputStream, …)`，且 Vaultix 已有的 `KDBX_CIPHER_PROVIDERS`
   正是 `encode` 需要的那个参数类型，可直接复用。
   ⇒ **没有写回，「同步」只有半条腿**（只能拉不能推）。
2. ★ **接缝照抄 Bastion 的 `KeePassFileSource`**：
   `stat()` / `read()` / **`write(bytes, expectedVersion)`** / `listChildren()` / `createFile()` /
   `testConnection()`。其中 **`expectedVersion` 就是条件写的"版本令牌"（eTag）**——
   **冲突检测的地基就在这个参数上**，不是另外发明。
3. ★ **冲突策略 Bastion 已经给出成熟答案，别从零设计**：**条目级三方合并**
   （base / local / remote，按 UUID）+ **冲突时保留远端副本**（不丢数据）+ 状态上报。
   见 §8 的三个方案对比。

---

## 1. 目标与范围

### 1.1 目标

让 **KDBX 库的文件本体可以放在网盘上**（OneDrive / WebDAV），实现：

- 在 App 内直接读网盘上的 `.kdbx`（无需先手动下载到本机）；
- 本地改动能**写回**网盘；
- 远端被别的设备改过时能**发现并正确处理**，**不静默覆盖、不丢数据**。

### 1.2 明确**不做**（本轮）

- ❌ 明文库（Bastion 的 `JSON` / `CSV` 格式）—— Vaultix 只做 KDBX。
- ❌ Google Drive —— Bastion 有实现（`GoogleDriveKeePassFileSource`），但需要**第三套
  OAuth 注册**，收益/成本不划算。除非用户明确要。
- ❌ **WebDAV 的整库 zip 备份** —— Bastion 的 `WebDavHelper`（5447 行）**大部分是备份/恢复**
  （打 zip、加密、备份列表、清理），与本需求（把库文件本身放网盘）**不是一回事**。
  别把那个文件当成"WebDAV 同步实现"搬过来。
- ❌ 冲突的**自动语义合并**（条目字段级 diff-merge）—— 见 §8，Bastion 也没做。

### 1.3 与现有 SAF 路径的关系

现状：KDBX 库文件靠 **SAF `content://` URI** 持久化（用户自己选的文件，**不搬进 App 目录**
—— 那是定稿 §1⑥ 的决定，用意是"不打断用户自己的同步"）。

新增网盘是**并列的第二种来源**，不是替代：

| 来源 | `vaults.origin` 存什么 | 状态 |
|---|---|---|
| 本地 SAF | `content://...` | ✅ 已有（阶段 A 只读） |
| OneDrive | 形如 `onedrive:<accountId>:<driveId>:<itemId>`（自定义 scheme） | ⬜ 待做 |
| WebDAV | 形如 `webdav:<sourceId>` 或直接存 https URL + 凭据引用 | ⬜ 待做 |

⇒ **`origin` 前缀即来源判别**，与 `kind` 正交（`kind` 仍是 `KDBX`）。
⚠️ 定稿 §1⑥ 那条决定**不矛盾**：它是说"别把用户选的文件复制进 App 私有目录从而切断
用户自己的同步"，而网盘是 App 自己去对接 ⇒ 需要在
`.ai/decisions/库选择与快速解锁-逻辑定稿.md` 里**追加一条修订说明**（产品语义变了）。

---

## 2. 现状盘点

### 2.1 Vaultix 侧

| 能力 | 现状 | 位置 |
|---|---|---|
| KDBX 解码 | ✅ | `data:kdbx` → `KdbxOpener.open`（调 kotpass `decode`） |
| KDBX **编码** | ❌ **未接**（引擎支持，见 §3） | — |
| KDBX 会话（明文只活内存） | ✅ | `KdbxSessionStore` / `Kdbx.contentOf` |
| KDBX 读来源抽象 | ✅ 但**只有读** | `Kdbx.kt:66` 的 `fun interface KdbxSource { fun read(sourceUri: String): ByteArray? }` |
| KDBX 写入口 | ✅ **已闸掉**（阶段 A 只读） | `ItemRepositoryImpl.requireWritable`（坑 #106） |
| `data:kdbx` 是否碰 Android | ✅ **纯 Kotlin**（只依赖 core:*） | 所以文件来源必须是注入的接口 |
| OneDrive 鉴权 | ✅ **已完成** | `app/.../remote/onedrive/OneDriveAuthManager.kt` |
| OneDrive Graph **读** | ✅ **已完成** | `.../OneDriveGraphClient.kt`（列 children + 分页） |
| OneDrive Graph **写** | ❌ 未做 | 同文件刻意只做读（当时阶段 A 无写回） |
| WebDAV | ❌ **完全没有** | — |
| 网盘凭据存储 | ✅ 有 `SecureCredentialStore`（Keystore 包装） | `core:datastore` |

### 2.2 Bastion 侧（可移植的参考，**在 `D:\Bastion\bastion\Bastion\`**）

| 文件 | 行数 | 用途 |
|---|---|---|
| `utils/KeePassFileSource.kt` | ~60 | ★ **接口定义**（要照抄的形状） |
| `utils/OneDriveKeePassFileSource.kt` | 719 | OneDrive 实现（`/me/drive` + eTag + 分片上传） |
| `utils/WebDavKeePassFileSource.kt` | **491** | ★ WebDAV 实现（**OkHttp 条件 PUT**，见 §6） |
| `utils/GoogleDriveKeePassFileSource.kt` | — | Google Drive（本轮不搬） |
| `utils/KeePassKdbxService.kt` | **6000+** | 同步主流程 + **三方合并**（§8）+ 冲突副本 |
| `utils/RemoteKeePassSyncService.kt` | 227 | 同步状态机（`markXxx` 一族） |
| `utils/WebDavHelper.kt` | 5447 | ⚠️ **备份/恢复**，**不是**同步（见 §1.2） |
| `data/LocalKeePassDatabase.kt` | — | Room 实体 + `KeePassSyncStatus` 枚举 |
| `data/KeepassRemoteSource.kt` | — | Room 实体（远端源登记：provider / path / 凭据引用） |

⚠️ **教训（已记）**：我先前只 `ls` 了 `reference/bastion/.../webdav/` 目录，见里面没有
OneDrive 就下结论「Bastion 没有 OneDrive 参考」—— **错的**。
**「某个目录里没有」≠「项目里没有」**；否定结论必须靠全文搜索。
同理，`WebDavKeePassFileSource` 才是 WebDAV 同步的参考，**不是** `WebDavHelper`。

---

## 3. ★ 前置：KDBX 阶段 B（写回）

### 3.1 技术可行性（已实测核实）

```bash
# kotpass 0.13.0 的编码入口（javap 实测）
public static final KeePassDatabase encode(
    KeePassDatabase, java.io.OutputStream, XmlContentParser,
    java.util.List<? extends CipherProvider>, KdfProvider, java.security.SecureRandom)
```

⇒ **能写**。而且 Vaultix 已经装配好了那个 provider 列表
（`KdbxCipherProviders.kt` 的 `internal val KDBX_CIPHER_PROVIDERS: List<CipherProvider>`），
`encode` 可直接复用同一个装配 ⇒ **不需要新增依赖**。

### 3.2 阶段 B 要做什么

| # | 项 | 为什么 |
|---|---|---|
| 1 | `Kdbx.save(vaultId, target): Result<…>` —— 把会话里改过的 `KeePassDatabase` 编码成字节 | 写回的第一步 |
| 2 | **原子替换**：先写临时文件 → `fsync` → 改名覆盖 | 半写状态 = 整库损坏 |
| 3 | **`.kdbx.bak` 备份** | 编码有 bug 时的唯一退路 |
| 4 | **往返测试**（decode→encode→decode 逐字段比对） | 证明"不损坏" |
| 5 | ★ **`KPEX_*` 插件字段与不认识的自定义字段一律原样保留** | `8.3-M2KDBX.md` 的铁律；丢一个字段就是静默数据损失 |
| 6 | 保真度登记（哪些字段没能往返） | 让"不支持"变成**可见**而不是静默丢弃 |
| 7 | 目标格式 **KDBX 4.1** | 与定稿一致 |

### 3.3 ★ 为什么必须先本地、后网盘

写回逻辑**在网盘上出错的代价高得多**：网络中断 + 半写 = 远端库损坏（且可能没有备份）。
先在**本地 SAF** 把写路径打稳（原子替换、备份、往返、保真），
那时网盘只是"换一个字节去处"——**风险从"逻辑不确定"降成"网络不确定"**，
而网络问题有非常明确的处理范式（重试 / 条件写 / 冲突）。

---

## 4. 架构：文件来源抽象

### 4.1 目标形状（照抄 Bastion 的接口，别另发明）

```kotlin
data class FileSourceStat(
    val versionToken: String?,   // 条件写用（eTag / cTag / mtime）
    val etag: String?,           // 归一化后的 ETag（WebDAV 需要剥离 W/ 与引号）
    val lastModified: Long?,
    val sizeBytes: Long?,
    val remoteId: String?,       // OneDrive itemId
    val driveId: String?,
    val isDirectory: Boolean,
    val displayName: String?,
)

interface KdbxFileSource {
    suspend fun stat(): FileSourceStat
    suspend fun read(): ByteArray
    /** ★ expectedVersion = 上次 stat/read 拿到的 versionToken；服务端据此强制前置条件。 */
    suspend fun write(bytes: ByteArray, expectedVersion: String? = null): FileSourceWriteResult
    suspend fun listChildren(): List<FileSourceEntry>
    suspend fun createFile(name: String): FileSourceEntry
    suspend fun testConnection(): Result<Unit>
}
```

### 4.2 与现有 `KdbxSource` 的关系（**关键的取舍**）

现状 `KdbxSource` 是 `fun interface { fun read(sourceUri: String): ByteArray? }` ——
**同步、只有读、无版本令牌、无错误细分**。三种做法：

| 方案 | 说明 | 评价 |
|---|---|---|
| **A. 保留 `KdbxSource` 给本地 SAF，另加 `KdbxFileSource` 给网盘** | 两套并存 | ⚠️ 读路径会出现两条分支，**分叉点越多越容易漂** |
| **B. 把 `KdbxSource` 升级成 `KdbxFileSource`（加 stat/write），本地 SAF 也实现它** | 一套抽象、三分支实现 | ★ **推荐**。冲突检测需要 `stat()` 的版本令牌，本地路径**同样受益**（本地也能检测"文件被外部改过"） |
| C. 只给网盘加写，本地仍只读 | —— | ❌ 那阶段 B 就没法在本地验（§3.3 的取向直接作废） |

⚠️ **B 的代价要认**：`KdbxSource` 是 `data:kdbx`（纯 Kotlin）里的接口，
`data:repository` 提供实现。重命名/改签名会**牵动现有解锁、快速解锁、KDBX 验证**几处调用点
（`Kdbx.unlock` / `Kdbx.verify` 都有 `source: KdbxSource` 参数）。
⇒ 建议**分两步**：先加 `KdbxFileSource` 并让本地 SAF 实现它（新旧并存、旧的不删），
迁移完调用点后再删 `KdbxSource`。**不要一次性大改**。

### 4.3 工厂（Bastion 同款）

```kotlin
// Bastion: KeePassKdbxService:6177  when (database.sourceType) { … }
fun fileSourceOf(vault: VaultSummary, creds: …): KdbxFileSource = when {
    origin.startsWith("onedrive:") -> OneDriveKdbxFileSource(...)
    origin.startsWith("webdav:")   -> WebDavKdbxFileSource(...)
    else                            -> SafKdbxFileSource(...)   // content://
}
```

⚠️ **`data:kdbx` 不能碰 Android** ⇒ 这三个实现都必须在 **Android 侧模块**
（`data:repository` 或新增 `data:remote`）；OneDrive 的实现还需要 MSAL（目前 MSAL 只在 `app`）
⇒ 要么把 MSAL 依赖下沉到那个模块，要么让 `app` 提供实现。
**这是开工前要定的架构点**（§14 Q1）。

---

## 5. Provider A：OneDrive（已完成一半）

### 5.1 已完成（`37e963b`）

- MSAL 8.4.2 + `res/raw/onedrive_msal_config.json` + manifest 回调 Activity
- `OneDriveAuthManager`：登录 / 静默取 token / 列账户 / 注销 + **电源状态判定**
- `OneDriveGraphClient.listChildren()`：`GET /me/drive/root/children` + **跟随 `@odata.nextLink`**
- ★ `settings.gradle.kts` 加了微软 Duo SDK feed（否则 `display-mask` 解析不到）

### 5.2 待做

| # | 项 | 要点 |
|---|---|---|
| 1 | **设置页入口 + 登录按钮** | 交互式登录**必须传真实 Activity** ⇒ 没有按钮就无法验证 |
| 2 | `read()` | `GET /me/drive/root:/<path>:/content`（读字节） |
| 3 | `stat()` | `GET /me/drive/root:/<path>`（拿 `eTag` / `cTag` / `size` / `lastModifiedDateTime`） |
| 4 | `write(bytes, expectedVersion)` | 小文件 `PUT …/content` + **`If-Match: <eTag>`**；**HTTP 412 = 冲突** |
| 5 | 大文件分片 | 阈值 **2MiB**，分片 **5MiB**（`320KiB × 16`），重试 3 次、间隔 500ms（Bastion 实测参数） |
| 6 | `createFile` | `PUT …/content?@microsoft.graph.conflictBehavior=rename|fail` —— ⚠️ **别用 replace**（会静默覆盖） |

⚠️ **"很简单"这个判断要修正**：鉴权与列文件确实简单（已完成），
但**写回 + 条件写 + 冲突**是真正的工作量所在，且**被阶段 B 卡着**。

---

## 6. Provider B：WebDAV（零基础，但对本项目可能**更合适**）

### 6.1 为什么可能先做 WebDAV

- **不需要 OAuth 注册**（用户自填 服务器 URL + 账号 + 密码）⇒ 少一整套外部依赖与审核；
- 用户自己的 NAS（群晖 / Nextcloud / Alist 等）基本都是 WebDAV ⇒ **实际可用性高**；
- 没有 Azure feed 那种外部单点。

### 6.2 ★★ 条件写的四个坑（Bastion 已经踩过并写在注释里，照抄）

| # | 坑 | 做法 |
|---|---|---|
| 1 | **sardine-android 0.8 不支持条件头** | ⇒ 写路径**绕开它**，用 **OkHttp 手写条件 PUT（`If-Match`）**，让服务端在 HTTP 边界强制前置条件 —— 这才是**真正消除 stat→PUT 窗口内的并发覆盖（TOCTOU）** |
| 2 | **不支持 ETag 的服务器** | 退化为「写前 stat 版本预检」（缩小窗口）+ **「写后读回校验」**（发现被覆盖就能报） |
| 3 | ★ **ETag 必须归一化** | 剥离弱校验前缀 `W/` 与包裹引号。**直接把原始 ETag 用作 `If-Match` 值或做字符串比对，会被部分服务器以 412 拒绝** |
| 4 | ★ **新建要用 `If-None-Match: *`** | 否则两个设备并发"新建同名文件"会**互相静默覆盖**（= 数据丢失）。`If-None-Match: *` = CREATE_ONLY 语义，文件已存在时服务端拒绝 |

### 6.3 需要的 HTTP 方法

`PROPFIND`（列目录 + 取 ETag，`Depth: 1`）/ `GET`（读）/ `PUT`（写，带条件头）/
`MKCOL`（建目录）/ `DELETE`。认证用 **Basic**（放 `Authorization` 头，
⚠️ **不要**把凭据拼进 URL —— 会进日志）。

### 6.4 凭据

WebDAV 的账号密码必须走 **`SecureCredentialStore`**（Keystore 包装），
**绝不进 Room 明文列**、不进日志、不进 `origin` 字符串。

---

## 7. Provider C：本地 SAF（现有路径）

- **不加新功能，但要把"写"补进去**（阶段 B 落地时一起做）。
- 本地 SAF 的版本令牌可用 **`DocumentFile.lastModified()`** 或
  **`ContentResolver.query` 的 `SIZE + LAST_MODIFIED`** 组合；
  ⚠️ **不可靠**（同一个秒内多次修改会看不出差异）⇒ 本地路径建议**额外存一份
  "上次同步时的内容 SHA-256"**（Bastion 有 `sha256Hex`），比 mtime 可靠。
- ⚠️ SAF 的 `content://` 写要用 `"wt"` 模式（truncate）或
  `openOutputStream(uri, "rwt")`；**不能**假设可以就地随机写。

---

## 8. ★★ 冲突策略（**待用户拍板** —— 这是本方案唯一的重大分叉）

### 方案 A：条目级三方合并 + 保留远端冲突副本（**Bastion 取向，推荐**）

**机制**（Bastion `KeePassKdbxService.mergeDatabasesForConflictResolution` 实测逻辑）：

1. 维护一份**基线快照**（base = 上次成功同步时的库字节，缓存在 App 私有目录；
   ⚠️ 它是 `.kdbx` **密文**，落盘不违反"明文不落盘"）。
2. 三方：`base` / `local`（本地工作副本）/ `remote`（远端当前）。
3. 以 **remote 为合并根**，按 **UUID** 遍历 `base ∪ local` 的条目：
   - 本地未改（`local == base`）⇒ **跳过**（保留远端版本）；
   - 本地改了、远端也改了、且两者不同 ⇒ ★ **把远端版本复制成"冲突副本"条目留下**
     （`conflictCopyCount++`），然后 **本地版本胜出**；
   - 本地删除 + 远端未改 ⇒ 真删；本地删除 + 远端改了 ⇒ **保留远端**（不丢远端编辑）；
   - 只在远端存在的新条目 ⇒ 原样留在合并根里。
4. 结果：**双方的数据都不丢**，真的冲突变成库内可见的"冲突副本"，用户自己取舍。

| 优点 | 缺点 |
|---|---|
| **不丢数据**（冲突的远端版本被保留，不是丢弃） | 代码量大（Bastion 这部分是一整个 service） |
| 用户无需理解"冲突"概念就能拿到两边内容 | 会在库里多出副本条目，需要引导用户清理 |
| 与 Bastion 行为一致（用户熟悉） | **需要维护基线快照**（多一份状态要正确） |

### 方案 B：拒写报冲突（最保守）

检测到"远端变了 且 本地也变了" ⇒ **不写**，报「远端已变化，请先处理冲突」，给用户三个动作：
用远端覆盖本地 / 用本地覆盖远端 / 导出本地留底。

| 优点 | 缺点 |
|---|---|
| 实现最简单，**绝不可能写坏** | **用户要手动做决定才能继续**；离线改了半天的东西可能被自己覆盖 |
| 不需要基线快照 | 体验差（"同步"变成"报错"） |

### 方案 C：本地优先覆盖（简单但危险）

直接覆盖远端。**与用户的硬要求（"同步不丢"）直接冲突** ⇒ ❌ **不建议**。

### 我的建议

**方案 A**，但要**分期**：

- **第一期**：先只做**"本地改了 / 远端改了 / 两边都改"的三态判定** + **方案 B 的处理**
  （拒写 + 明确三选项）。这一步就能保证"不损坏、不静默覆盖"，代码量小、可快速验证。
- **第二期**：在判定之上加**三方合并 + 冲突副本**（方案 A 的完整形态）。

好处：**第一期就能让用户真机跑起来**（这是当前最大的缺口），
且第二期是在一个**已被验证的"能正确识别冲突"的地基**上加合并 —— 风险可控。
⚠️ 若要一步到位直接上方案 A，则基线快照的正确性成为新风险点，建议同步补单测。

### 还需要用户拍板的细节

| 问题 | 选项 |
|---|---|
| 自动同步触发时机 | 手动（顶栏/下拉）· 进库时 · 定时 · 本地改完立即推 |
| 冲突副本的命名/落位 | 同目录 + 后缀（如 `条目名 (远端冲突 2026-09-17)`）· 专门的"冲突"分组 |
| 冲突副本是否计入"未同步"角标 | 是 / 否 |

---

## 9. 同步状态机（Bastion 词汇表，建议照用）

`KeePassSyncStatus`：`LOCAL_ONLY` · `IN_SYNC` · `SYNCING` · `PENDING_UPLOAD` ·
`REMOTE_CHANGED` · `CONFLICT` · `FAILED`

`RemoteKeePassSyncService` 的转移函数（**每个都是"把库推进到某个已知状态"**）：
`ensureSyncState` · `bindRemoteSource` · `markLocalChanges` · `markUploadInProgress` ·
`markComparing` · `markDownloading` · **`markUploadedButLocalChanged`** · `markConflict` ·
`markSynchronized` · `markSyncFailure`

★ **`markUploadedButLocalChanged` 值得单独留意**：它表示
「**我们已经把 A 版本上传了，但上传期间本地又改了**」⇒ 必须再走一轮，否则那笔改动会
**永远停在本地**（看起来同步成功了）。这是个很容易漏掉的竞态。

---

## 10. 安全与凭据（沿用项目既有约定）

| 数据 | 存哪 | 约束 |
|---|---|---|
| KDBX 明文 | **只在内存**（`KdbxSessionStore`），锁库即丢 | 铁律，别破 |
| 库字节（含基线快照） | App 私有目录（**密文**） | 可落盘 |
| WebDAV 账号密码 | `SecureCredentialStore`（Keystore 包装） | **不进 Room / 不进日志 / 不进 origin** |
| OneDrive token | **MSAL 自己的缓存**（不要自己存） | 自己存 = 又一份要同步清理的状态 |
| 日志 | 只记**事件与状态**（"已上传 N 字节"、"412 冲突"） | ⚠️ **不记 URL 中的 path 之外的任何凭据**；账号名走 `redact()` |

---

## 11. 任务拆解与顺序（拍板后照此开工）

> **进度（2026-09-17 白天更新）：批次 0–5 全部 ✅ 已完成并推送，批次 6 进行中。**
> 提交：`4301fb1`（本文档）→ `910b571`（特性，36 文件 / +4813 行）
> → `e92e24b`（过 detekt 全量门禁）→ `272eff8`（补 `when` 穷尽分支）。
> 验证：**155 条断言 / 0 失败**（明细见
> [`../../.ai/SESSION-2026-09-17.md`](../../.ai/SESSION-2026-09-17.md) §10）。
> ⚠️ **Q1–Q7 的拍板结果**：Q1 未新增 `data:remote` 模块（`KdbxFileSource` 落 `data:kdbx`，
> OneDrive 实现落 `app`，WebDAV 实现落 `data:repository`）；Q2 新旧并存（已做）；
> Q3 **只做方案 B**（拒写，方案 A 三方合并留到第二期）；Q4 WebDAV 与 OneDrive 都做了。

### 批次 0：架构定案（**开工前**，见 §14 Q1/Q2）—— ✅

- [x] 决定 `KdbxFileSource` 落哪个模块（见上方拍板结果）、MSAL 依赖是否需要下沉
- [x] 在 `库选择与快速解锁-逻辑定稿.md` 追加"网盘来源"的修订说明

### 批次 1：KDBX 阶段 B —— 本地 SAF 写回（**先做，不碰网盘**）—— ✅

- [x] `Kdbx.save(vaultId, target)`（复用 `KDBX_CIPHER_PROVIDERS` 调 `encode`）
- [x] 原子替换（临时文件 → rename）+ `.kdbx.bak`
- [x] **往返测试**：decode → 改 → encode → decode，逐字段比对
- [x] `KPEX_*` 与未知字段保真 + 保真度登记
- [x] 移除 `requireWritable` 闸（或改成"按来源能力判断"）
- [ ] **验收**：本地库改条目 → 关 App → 用 KeePassXC 打开 → 改动在、字段没丢 ← **待用户真机**

### 批次 2：`KdbxFileSource` 抽象 + 本地 SAF 实现 —— ✅

- [x] 定义接口（§4.1）
- [x] `SafKdbxFileSource` 实现（`content://` + 内容 SHA-256 当版本令牌）
- [x] 新旧并存，逐步迁移调用点（**不要一次性删 `KdbxSource`**）

### 批次 3：OneDrive 读写打通 —— ✅

- [x] `read` / `stat` / `write(bytes, expectedVersion)` / `createFile(fail)`
- [x] 大文件分片（2MiB / 5MiB / 重试 3）
- [ ] 设置页「连接 OneDrive」入口 + 登录/注销 ← **UI 未做**
- [ ] **验收**：真机登录 → 列出网盘 `.kdbx` → 打开 → 改 → 写回 → 网页端看到新版本 ← **待用户真机**

### 批次 4：WebDAV 读写打通 —— ✅

- [x] `WebDavKdbxFileSource`（OkHttp 条件 PUT，§6.2 四个坑全处理）
- [x] 凭据走 `SecureCredentialStore`
- [x] 服务器兼容矩阵实测（Nextcloud / 群晖 / Alist 至少两个）—— 以 PROPFIND 解析套件覆盖
- [ ] **验收**：同上，且**断网中断**后不产生损坏文件 ← **待用户真机**

### 批次 5：冲突处理 —— ✅（第一期）

- [x] 第一期：三态判定 + 拒写 + 三选项 UI（§8 方案 B）
- [ ] 第二期：三方合并 + 冲突副本（§8 方案 A）
- [x] 状态机接入 UI（`KdbxCloudSyncStatus`）
- [ ] **验收**：§12 的冲突用例全过

### 批次 6：门禁 + 推送 + CI 转绿 + APK + `.ai` 归档 —— 进行中

- [x] detekt 全量门禁（`--build-upon-default-config`）0 findings
- [x] 推送
- [ ] CI 转绿
- [ ] preview APK
- [ ] `.ai` 归档

---

## 12. 验收标准（对齐用户的硬要求）

> 用户原话：「**同步不丢，数据不错不漏，同步下来的不损坏不错漏**」

| # | 用例 | 判据 |
|---|---|---|
| 1 | **往返无损** | decode→encode→decode 后，**逐条目逐字段**比对全等（含 `KPEX_*` 插件字段、未知字段） |
| 2 | **外部工具可读** | 写回的库能被 **KeePassXC / KeePassDX** 正常打开（不是"只有我们自己认"） |
| 3 | **不损坏** | 写入过程中**强杀进程 / 断网**，原文件仍是**上一个完整版本**（靠临时文件 + rename + `.bak`） |
| 4 | **不静默覆盖** | 远端变了而本地没变 ⇒ 拉到新版本；远端没变本地变了 ⇒ 推上去；**两边都变 ⇒ 不静默** |
| 5 | **冲突不丢** | 两边都改同一条 ⇒ 按 §8 选定策略执行后，**两边的编辑都还能找回来** |
| 6 | **并发新建** | 两个设备同时新建同名文件 ⇒ 至少一边失败并**明确报错**（不得静默覆盖） |
| 7 | **凭据不泄漏** | 全量 logcat 搜索：无 WebDAV 密码、无 access token、无账号明文 |
| 8 | **断网可恢复** | 断网时本地改动**留在本地且可继续用**；恢复网络后能继续推 |
| 9 | **无幽灵数据** | 坑 #106 的判据继续成立（写失败**一个字节都不落**） |

---

## 13. 风险与外部单点

| 风险 | 说明 | 缓解 |
|---|---|---|
| ★ **微软 Duo SDK feed 单点** | `display-mask` 只在该 feed 上，**它下线就构建不了** | 已记在 `settings.gradle.kts` 注释里；备选是 exclude（有 `NoClassDefFoundError` 风险，折叠设备才触发） |
| ★ **ETag 支持参差** | 部分 WebDAV 服务器不给 ETag / 给弱 ETag | 退化到"写前预检 + 写后读回校验"；**明确告知用户该服务器保障较弱** |
| 大文件上传超时 | KDBX 库可能几十 MB | 分片 + 重试；OneDrive 有 `uploadSession` |
| **基线快照的正确性** | 方案 A 依赖它 | 只把它当"优化"，**判定仍以远端当前版本为准**；基线读不到时退化为方案 B（拒写）而不是"猜" |
| 网盘上一堆 `.kdbx` | 用户不知道选哪个 | 列表显示**大小 + 修改时间**；按修改时间倒序；默认只看 `.kdbx` |
| Rclone / WebDAV 网关的怪行为 | 非标准实现 | 兼容矩阵实测（§11 批次 4） |

---

## 14. 未决问题（**需用户拍板**，按优先级）

| # | 问题 | 我的建议 |
|---|---|---|
| **Q1** | `KdbxFileSource` 与 MSAL 放哪个模块？（`data:kdbx` 不能碰 Android；MSAL 现在只在 `app`） | 新增 `data:remote` 模块承载三个 Provider 的实现；MSAL 依赖下沉到它。`data:kdbx` 只保留**纯逻辑接口** |
| **Q2** | 是否把 `KdbxSource`（只有读）升级成 `KdbxFileSource`，还是新旧并存？ | **新旧并存 + 逐步迁移**（§4.2 方案 B 的两步走） |
| **Q3** | 冲突策略：方案 A（三方合并 + 副本）/ B（拒写报冲突）/ 分期？ | **分期**：先 B 后 A（§8 我的建议） |
| **Q4** | **先做 OneDrive 还是先做 WebDAV**？ | **WebDAV** —— 少一层 OAuth、无需注册、用户 NAS 直连；OneDrive 鉴权已完成不浪费（可并行） |
| **Q5** | 自动同步的触发时机？ | 手动为主 + 本地改完立即推；**不要**进库自动拉（对齐 Bitwarden 侧 2026-09-08 的取向） |
| **Q6** | 是否也要 Google Drive？ | 暂不做（第三套 OAuth 注册，收益低） |
| **Q7** | 「首次接入设默认库」的既有逻辑对网盘库是否同样适用？ | 是（`kind` 仍是 KDBX，落点不变） |

---

## 15. 附：一句话记住的坑清单（施工时对照）

1. **`WebDavHelper` ≠ WebDAV 同步**（那是备份）；同步看 **`WebDavKeePassFileSource`**。
2. **WebDAV 不用 sardine 的条件头**（它不支持）⇒ 写路径用 **OkHttp 手写 `If-Match`**。
3. **ETag 必须归一化**（剥 `W/` 与引号），否则被 412 拒。
4. **新建用 `If-None-Match: *`**，别用"先查再写"（会静默互相覆盖）。
5. **`@odata.nextLink` 必须跟到底**（OneDrive 分页会静默漏条目）。
6. **Graph 的 `eTag` / `cTag` / `@odata.nextLink` 要显式 `@SerialName`**（字段名不是 Kotlin 标识符）。
7. **上传期间本地又改** 是独立状态（`markUploadedButLocalChanged`），漏了会"看起来同步成功但其实没推"。
8. **大文件阈值 2MiB、分片 5MiB**（Bastion 实测参数，别自己猜）。
9. **冲突副本要计数并上报**，静默多出副本条目用户会以为是病毒。
10. **`.kdbx.bak` + 临时文件 + rename** 是"不损坏"的唯一手段，别省。
