# KDBX 写回（阶段 B）——增删改查 + **新建库** · 施工工作单

> **状态**：📋 待开工（2026-09-30 晚立项；用户 21:05 拍板「kdbx 的增删改查都需要完善」，
> 21:3x 追加两条硬要求：**新建库** + **格式只认最新 KDBX（4.1）** + **必须能被 KeePassDX / KeePassXC 打开**）
> **性质**：M2 阶段 B · **密码库最不能出错的一条路径**（写坏一个文件 = 条目/通行密钥全没）
> **前置阅读**：`.ai/conventions/8.3-M2KDBX.md`（本单的上位约定）· `.ai/issues/05-KDBX本地库.md` #106（为什么闸掉）

---

## 0. 一句话目标

1. **新建**：能创建**全新的 KDBX 4.1 库文件**（本机 SAF / OneDrive / WebDAV 三处都能落盘）；
2. **增删改查**：已有库支持**新建 / 编辑 / 删除（含回收站）/ 恢复**条目，体验与 Bitwarden 库一致；
3. 以上**本地立即生效，上传异步**；保真铁律零妥协；
4. 产物必须**能被 KeePassDX / KeePassXC 正常打开并读写**（第三方可互操作 = 格式合规的验收标准）。

> ★ **2026-09-30 用户追加（原话）**：「库的标准要匹配最新的 kdbx 格式，只做最新兼容，
> 4.1 以上，不向下兼容 3.0 等」「库要能被 DX、XC 之类的 kdbx 密码管理器打开」
> ⇒ 写入一律 **KDBX 4.1**；不写 3.x。下行读取策略见 §2.6（待拍板）。

## 1. 为什么是现在、为什么这么做（背景速览，新会话免翻史）

- **坑 #106（2026-09-17）**：读路径分流了 KDBX、写路径没分流 ⇒ 新建"看似成功但条目
  永远不出现"（写进了 Bitwarden 的 Room 表）、编辑报"条目不存在"。当时**闸掉写入口止血**
  （`ItemRepositoryImpl.requireWritable` → `ReadOnlyVaultException`）＝今天"只读"的唯一原因。
  **不是权限问题**：`Kdbx.saveVia`（条件写 + 冲突翻译）一直在用，基础设施都在。
- **网络实测（2026-09-30 真机）**：Wi-Fi 链路正常（ping Graph ~100ms 0% 丢包），但
  30KB 下载 **5–7s、偶发 60s 超时**（国内访问 Graph 的典型表现）⇒ **同步上传的方案直接否决**，
  必须本地先行。
- **用户拍板（2026-09-30 21:05）**：做阶段 B；策略 = **本地立即生效 + 上传异步**。

## 2. 现状盘点（已核实，别再花时间查）

| 件 | 状态 | 位置 |
|---|---|---|
| 读映射（Entry→VaultItem，含 OTP/通行密钥/自定义字段解析） | ✅ 完整 | `data:kdbx/KdbxItemMapper.kt`（**只读方向**，无反向） |
| **写映射（VaultItem→Entry 的修改/新建）** | ❌ **不存在，本单核心** | 需新建 `KdbxItemWriter.kt`（或并入 Mapper） |
| 会话存储（内存 `KeePassDatabase`，可 get/put） | ✅ | `KdbxOpener.kt` 内 `internal object KdbxSessionStore` |
| 整库编码 + 写文件 + 条件写 + 冲突翻译 | ✅ | `Kdbx.saveVia` / `KdbxAtomicWriter` / `KdbxFileSource.write` |
| **保真自检（改前 vs 改后逐字段比）** | ✅ 现成 | `KdbxFidelity.describeLosses(before, after)` |
| 云同步编排（远端变了→冲突对话框已存在） | ✅ | `KdbxSyncOrchestrator` / `KdbxCloudSyncCoordinator` |
| 会话变更 → UI 刷新信号 | ✅ | `kdbxSessions.bump()`（`KdbxSessionFlow`） |
| 回收站（KDBX） | ❌ 明确返回空 | `ItemRepositoryImpl.observeTrash:157`（阶段 A 刻意） |
| 写入口闸门 | 🔒 待撤 | `ItemRepositoryImpl.requireWritable:527` |
| UI 闸门（只读） | 🔒 待撤 | `ItemsScreen`（FAB 隐藏 :347 / `ReadOnlyVaultNotice` / `readOnlyVaultOf`）、`ItemDetailViewModel.readOnly:115`、`AddRequestEffect` |
| 只读文案 | 🔒 待撤 | `kdbx_vault_read_only` / `kdbx_vault_read_only_notice` |
| 既有测试 | 部分 | `data:kdbx/KdbxWritePathTest.kt`（写文件层已有）；条目层零覆盖 |

⚠️ **第一步的 API 查证已完成（2026-09-30，见 §2.5）** —— 不用再翻 `reference/bastion/`。

### 2.5 ★ kotpass 0.13.0 API 查证结论（本轮反编译核实，直接照此写码）

`app.keemobile:kotpass:0.13.0`，`KeePassDatabase` 是 **sealed**（`Ver3x` / `Ver4x`），
`Ver4x(credentials, header, content, innerHeader)` 是 data class ⇒ **改 = `copy` 放回**，确认。

| 要干的事 | kotpass 现成 API（`javap` 实证） |
|---|---|
| **从零建库** | `KeePassDatabase.Ver4x.create(name, meta, credentials, random)` ★ **自带工厂** |
| 默认头 | `DatabaseHeader.Ver4x.Companion.create(random)` → 字节码实读 **`FormatVersion(4, 1)`** + `KdfParameters.Argon2.default(seed32)` |
| 凭据 | `Credentials.from(password: EncryptedValue)` / `from(bytes)` / `from(EncryptedValue, byte[])`（后者 = 密码 + keyfile）；`createKeyfile(bytes)` 生成 keyfile |
| 改条目 | `db.modifyEntry(uuid) { it.copy(...) }` · `db.modifyEntries { }` · `db.removeEntry(uuid)` · `db.moveEntry(uuid, groupUuid)` |
| **改条目留历史** | `Entry.withHistory { }` ★ KDBX 的历史记录语义（XC 打开能看到「之前的版本」） |
| 改群组 | `db.modifyGroup / modifyGroups / modifyParentGroup / moveGroup / removeGroup` |
| **回收站** | `db.withRecycleBin { db, uuid -> db' }` ★ **自带**，不用手搓 |
| 历史清理 | `db.cleanupHistory(instant)` |
| 找条目 | `db.getEntryBy { }` / `findEntryBy { }` / `getEntries { }`（按谓词） |
| 编码 | `KdbxEncoder` 已包好（`encode(db, out, cipherProviders)`），内部会 `regenerateVectors` |
| 版本上下限 | `KeePassDatabase.MinSupportedVersion = 3` / `MaxSupportedVersion = 4` |

**⇒ 结论：本阶段没有"缺 API 所以做不了"的阻塞项**；W1 的形态可以定为
「`KdbxItemWriter` 薄封装这些 modifier + 字段映射」，不需要自己遍历 `Group`/`Entry` 树。

### 2.6 ✅ 已拍板（2026-09-30 22:0x 用户）：**B —— 严格只认 4.x**

用户原话：「**只认 4.X 吧**」。

| 项 | 判决 |
|---|---|
| 写入 | 一律 **KDBX 4.1**（`FormatVersion(4,1)`），不写 3.x |
| **读取** | **只接受主版本 = 4**；读到 3.1 ⇒ **明确拒绝**并提示「这是 KDBX 3.1 旧格式，请先用 KeePassXC 打开并另存为 4.1」 |
| 不做 | ❌ 不写 `Ver3x → Ver4x` 转换（省掉一整条最容易写坏的字段迁移路径） |

⚠️ **落地要点**：现有 `KdbxFormat.kt` 的 `SUPPORTED_MAJOR` 是 **3**（接受 3.1+），要改成 **4**。
⚠️ 拒绝文案**必须给出下一步**（"用 XC 另存为 4.1"），不能只说"不支持" —— 用户手上可能真有 3.1 老库，
只说"不支持"等于把人堵死（与「不谎报 / 如实说明」同一条纪律）。
⚠️ 这条与历史行为**相反**：旧代码能读 3.1，是本轮主动收窄的（用户 2026-09-30 拍板）。
⇒ `KdbxFileSource` / 解锁路径的既有测试里若有 3.1 样本，要一并改成"应被拒绝"。

### 2.7 ✅ 历史记录：**跟随 KeePass 的做法**（2026-09-30 用户要求「参考他们的做法」）

上游（KeePass / KeePassXC / KeePassDX 与 kotpass）存记录靠**三条腿**：

| 机制 | 存什么 | kotpass 里的入口 |
|---|---|---|
| **`<History>`**（每条目） | **一份快照列表**，每项是一条**完整 `<Entry>` 副本**（不是 diff） | `Entry.withHistory { }` |
| **`<Times>`**（每条目） | Creation / LastAccess / LastModification / LocationChanged / Expiry / Expires / UsageCount | `Group.modifyEntry` **自动**刷新后两者 |
| **`<DeletedObjects>`**（整库） | 墓碑列表（"这条被删了"，供同步判定） | `removeEntry` 会写它 |
| 修剪 | `Meta.historyMaxItems`（默认 10）/ `historyMaxSize` / `maintenanceHistoryDays`（默认 365） | `cleanupHistory(reference)` |

★ **`withHistory` 的确切语义**（读上游源码核实，别凭签名猜）：
**先把「当前 entry（并把它的 `history` 清空）」存成快照，再返回 `block()` 的结果、
并把快照追加进结果的 `history`**。⇒ 就是「改之前那一版进历史」，与 KeePass 行为一致。

**判决：W1 的 `updateEntry` 一律走 `withHistory`；修剪交给 `cleanupHistory`。**

⚠️ **这条不能推迟**：`history` 是**不可事后补**的 —— 某一次编辑若没写快照，
那一版旧值就**永久拿不回来**（没法凭空造）。所以必须在**第一次写回之前**定死，
"先不存、以后再补"不成立。

⚠️ **与 Bitwarden 的不对称要如实对待**：BW 只有 `passwordHistory`（**只存密码的旧值**，
不存整条）。Vaultix 的 `CipherDto` 已声明 `passwordHistory` 但**没有映射进领域模型**
（与 `creationDate` 同一种"服务端有、我们没接"，见 2026-09-30 的条目时间字段修复）。
⇒ KDBX 侧用完整快照、BW 侧只有密码历史，**这是两个库能力的真实差异**，
不要为了"体验统一"把 KDBX 降成只存密码（那是主动丢掉它更好的能力）。

## 3. 目标架构（三层，别多别少）

```
UI（不感知库类型）
  └ ItemRepositoryImpl —— ★ 分流点：kind==KDBX → KdbxItemRepository（新）
      ├ 内存层：改 KdbxSessionStore 里的 KeePassDatabase → bump() → UI 立即刷新
      │         （★ 同一事务内做 Fidelity 自检：describeLosses(before, after)
      │            只允许"本次操作的条目"出现差异，出现别的 = 编码 bug，拒绝提交）
      └ 持久层（异步）：整库 encode → saveVia（条件写/冲突翻译/原子替换/缓存更新）
            ├ 成功 → 无感
            ├ 网络失败 → 状态条提示"待上传"（不弹窗）；下次同步/解锁时重试
            └ 冲突（KdbxFileConflictException）→ 复用既有冲突对话框
```

**三条设计判决（提前定死，避免新会话重新论证）**：

1. **本地先行**：`create/update/delete` 返回成功 = **内存已改 + UI 已刷新**，与上传解耦。
   （不学 BW 的"落库+队列"：KDBX 没有本地库表，内存会话就是真源。）
2. **每改一条 = 整库重写**（KDBX 格式使然）。30KB 文件无所谓；超大库（>10MB）再议分库。
3. **不引入本地暂存文件**：改完不落盘副本，直接依赖内存 + 下次上传时的原子写
   （`.kdbx.bak` 备份由 `KdbxAtomicWriter` 既有逻辑负责——开工前核实它确实建 bak）。

## 4. 分批施工（每批独立可验收、可提交）

### 批次 W0 · **新建库**（纯 data:kdbx + 添加流程，可独立验收）
- [x] ✅ **数据层已完成（2026-09-30，提交 `e75de10`）**：`KdbxCreator.createEmpty`
      （`Ver4x.create` → `withRecycleBin` → `KdbxEncoder.encode`）+ `Kdbx.createVault`
      （编码 → 落盘 → **顺手登记会话**，省一次 Argon2 且避免"写的凭据 ≠ 读的凭据"）。
      格式选型已逐字节反编译核实 = **KDBX 4.1 / AES-256 / GZip / Argon2**（见 §2.5）。
- [x] ✅ **KDF/套件与 XC 对齐**：结论是**直接取 kotpass 默认值即为 XC/DX 通用组合**，
      无需也不应该"手动调参"（代码里已写明"别顺手加强"）。
- [x] ✅ 测试：`KdbxCreatorTest` 11 条（往返开库 / 版本 4.1 / **cipher UUID 逐字节钉死** /
      GZip / 回收站组 / 空内容 / keyfile 三态 / **3.1 被拒** / 拒绝文案不许指向 3.x）。
      ★ 变异验证：把 `SUPPORTED_MAJOR` 改回 3 ⇒ **恰好 1 条红**（非假绿）。
- [ ] ⬜ **落盘三个来源**：SAF（`CREATE_DOCUMENT` 建新文件）/ OneDrive（上传新文件）/
      WebDAV（`PUT` 新文件）。⚠️ `OneDriveKdbxFileSource` 目前只有"解析已有 origin"，
      要补"创建远端新文件"；`KdbxFileSource.write(expectedVersion = null)` 这条路
      **未在新文件上实机验证过**（SAF 侧理论上可用：`"wt"` 覆盖 + 写后读回校验）。
- [ ] ⬜ **仓储入口**：`domain` 新接口 + `data:repository` 实现 + DI
      （⚠️ **不要加到 `VaultRepository`**：`VaultRepositoryImpl` 正好卡 40 函数，加就爆门禁 —— 照
      `KdbxSyncRepository` / `AutoUnlockRepository` / `LocalUnlockEnrollment` 的先例另立一个）。
- [ ] ⬜ **添加流程 UI**：`AddVaultTypeDialog` 增「**新建 KDBX 库**」+ 名称/主密码/keyfile(可选) 表单
      （SAF 用 `ActivityResultContracts.CreateDocument`，对齐 `ImportExportScreen` 的既有写法）。
- [ ] ⬜ **互操作验收（本批就要过，别留到 W3）**：用 **桌面 KeePassXC** 与 **手机 KeePassDX**
      打开刚新建的库、建一条、存盘、再由 Vaultix 读回。

### 批次 W1 · 写映射 + 内存事务 ✅ **已完成**（2026-10-01，提交 `214f6ec`）
- ✅ `data:kdbx/KdbxItemWriter`：`createEntry / updateEntry / moveToRecycleBin /
  restoreFromRecycleBin / permanentDelete`。
- ★ **核心原则**：只覆盖**领域模型承载的字段**，其余交给 kotpass 的 `copy` 原样带过
  （`KPEX_*` / 未知自定义字段 / 附件 / 自定义图标 / 历史）。
  **代码里禁止"从领域模型重建一条 Entry"** —— 重建必然丢掉没建模的东西（8.3 铁律）。
- ★ **三个会静默出事的地方**（各有对策 + 测试）：
  ① **未变更的字段不重写**（TOTP 只在值真的变了时才动，否则 Hex 存法会被改成 otpauth）
  —— 这也是 `updateEntry` 必须同时收 `before` 与 `after` 的原因；
  ② **`moveEntry` 是"先删后加"**（目标组不存在 ⇒ 条目**凭空消失**）⇒ 所有移动前先验目标组存在；
  ③ 改条目走 `withHistory`（施工单 §2.7 的判决）。
- ✅ 测试 `data:kdbx/KdbxItemWriterTest` 14 条（每条对应一个"会静默坏事"）。

### 批次 W2 · 仓储分流 ✅ **已完成**（2026-10-01，提交 `5b44ba0` + `c92eac8`）
- ✅ 数据层入口：`Kdbx.createItem / updateItem / moveItemToRecycleBin /
  restoreItemFromRecycleBin / purgeItem` → `Result<KdbxPendingWrite>`
  （**公开签名不含 `KeePassDatabase`** —— #64 引擎门面不外泄；引擎类型关在 `private mutate`）。
- ✅ 新 `data:repository/KdbxItemRepository`：五个写方法各自分流。
  **落盘规则**（本地 vs 网盘语义不同，实现前核实过）：
  - 本地 SAF（`content://`）⇒ **直接写文件**（文件即存储，没有"云端"这回事）；
  - 网盘 ⇒ **先写本地缓存 + 标记待上传**（上传异步；这就是"本地立即生效、上传异步"的落点）；
  - ★ 缓存里的 `versionToken` **沿用旧的**（它是下次条件写的基线，换成新值就再也
    检测不到"远端变过" ⇒ 会静默覆盖别人的改动）；
  - 三处都要 `kdbxSessions.bump()`。
- ✅ 「待上传」入口：`KdbxSyncRepository.markLocalEdited` + 协调器 `hasCloudSource/markLocalEdited`。
  ★ **它不是锦上添花**：同步的 `localChangedSinceLastSync` 由调用方从**持久化的
  `vaults.syncStatus`** 读出来（`VaultActionsController.localChanged`）⇒ 不标记的话
  状态还停在 IN_SYNC，下次同步判"两边都没变"，**那笔改动永远推不上去**。
  只对**网盘库**标记；冲突态不许降级。
- ✅ `VaultSaveOutcome.Queued` 的语义扩写为「已安全落到本机」（覆盖三种情形），
  文案去掉"联网后自动同步"（对本地库是假信息）。**刻意没加枚举值**（要改 3 个 ViewModel，
  收益只是措辞更细）。
- ✅ 回归门禁**按新行为重写**：原来那条锁死"KDBX 写被拒绝"的用例改成"委托给 KDBX 写回
  且 Room 一个字节都不碰"；并补**反证**用例（单断言"没发生"是天生弱的 ——
  也可能因为"整条路径根本没跑"而通过）。

### 批次 W3 · UI 放闸 ✅ **已完成**（2026-10-01，提交 `c92eac8`）
- ✅ `ItemsScreen`：删 `ReadOnlyVaultNotice` / `readOnlyVaultOf` / 局部 `readOnlyVault`、
  FAB 的只读门、`AddRequestEffect` 的只读分支（它会把「+」请求换成一句"暂为只读"的 snackbar）。
- ✅ `ItemDetailViewModel` + `ItemDetailScreen`：删 `UiState.readOnly` 与"不画编辑/删除"的分支。
- ✅ 删掉两条只读文案（不删会变成孤儿串），孤儿串总数保持 **118（净零）**。
- ⚠️ **保留两处"不支持"**（换名与理由）：`requireWritable` → `requireNotKdbx(vaultId, reason)`。
  剩下的两块是**通行密钥的写**（`KPEX_*` 由浏览器/服务端创建）与**旧孤儿行的回收站清理**
  （#106 之前留下的脏数据，`cleanupExpiredTrash` 会查到它们并给 KDBX 库入队 DELETE ⇒ 毒丸）。

### 批次 W4 · 回收站映射 ⏭ **已挪走 → 见 [`kdbx-sync-and-items-layout.md`](./kdbx-sync-and-items-layout.md) 的批次 S5**

⚠️ 规格**只在那一份**（避免两处各写一遍、必然漂移）。一句话摘要：
`observeTrash` 对 KDBX 恒返回空 ⇒ 回收站页看不到已删条目；写侧的
`restoreItemFromRecycleBin` / `purgeItem` **已经能用**，缺的是读侧映射与仓储接线。

## 5. 门禁与纪律（每批必过，不分批跳过）

- 三关分开单跑：`:data:kdbx:testDebugUnitTest` / `:data:repository:testDebugUnitTest` /
  `:app:testFullDebugUnitTest` + `detekt`（两个模块都要）+ `compileFullDebugKotlin`
- 探针：`check_orphan_strings`（只读文案撤除后**净减**）· `check_orphan_state`（新增 UI 状态零命中）
- **变异验证**：W1 每个写操作至少一次"故意写坏 → Fidelity 用例必须红"
- ⚠️ **detekt 先于编译**：改完必真跑 compile（门禁纪律，见 8.6）
- ⚠️ 给 `sealed` 加分支（如 `VaultSaveOutcome`）后**全局搜消费点**
- ⚠️ 真机数据不可逆操作前先 `adb pull` 备份（拉二进制用 `exec-out + MSYS_NO_PATHCONV=1`，字节数核对）

## 6. 真机 / 桌面验收清单（W0 起每批都跑，不留到最后）

> ★ 用户 2026-09-30 追加的硬要求：**「库要能被 DX、XC 之类的 kdbx 密码管理器打开」**
> ⇒ 互操作不是"有空再测"，而是**每批的验收项**。XC / DX 打不开 = 格式不合规 = 本批不过。

0. **互操作（W0 起每题必做）**：新建 / 改动后的库，
   - 桌面 **KeePassXC** 打开 → 条目、TOTP、通行密钥（`KPEX_*`）都在 → **改一条并存盘**；
   - 手机 **KeePassDX** 打开 → 同上；
   - 再由 **Vaultix** 打开读回 ⇒ **三方往返零丢失**。
   - ⚠️ 套件必须选 XC/DX 都支持的组合（AES-256 + Argon2d + gzip）；
     写 Twofish / ChaCha20 前先在**两边**实测能不能开，别写完才发现只有自己认。
1. KDBX（OneDrive）**新建**条目 → 列表立即出现（不等网络）→ 稍后行尾云图标变"已同步"
2. **编辑**已有条目 → 详情立即变 → OneDrive 网页端确认文件真的变了 → XC 里能看到**历史记录**
3. **删除** → 进回收站（W4 后）→ OneDrive 确认
4. 断网（飞行模式）新建 → 成功且状态条提示待上传 → 联网后自动补传
5. **冲突**：桌面 KeePassXC 改同一库 → 手机再存 → 应弹既有冲突对话框（不是静默覆盖）
6. **保真回归**：改一条后，桌面 KeePassXC 打开：通行密钥条目仍可用、TOTP 仍出码、
   自定义字段无丢失（对照 `KdbxFidelity` 报告）
7. ⚠️ 先拿**测试库**过 1–7，再上真库

## 7. 已知风险与预置对策

| 风险 | 对策 |
|---|---|
| 写坏用户库（最严重） | Fidelity 自检在**内存事务内**拒绝提交；`saveVia` 条件写兜底；真机验收 §6.7 |
| ~~kotpass API 形态与预期不符~~ | ✅ **已查证**（§2.5）：`Ver4x.create` / `modifyEntry` / `withHistory` / `withRecycleBin` 全部现成，无阻塞 |
| **写出只有自己认的库**（XC/DX 打不开） | 套件限定 XC/DX 都支持的组合；§6.0 三方往返为**每批验收项** |
| KDF 参数照抄不当（XC 判「参数过旧」/ 低端机解锁十几秒） | W0 抄 KeepassXC 默认档并把数值写进注释；真机计时 |
| 3.1 老库的升降格策略未定 | §2.6 待拍板（A 只约束写入 / B 严格只认 4.x） |
| OneDrive 5–60s 延迟让用户以为没存上 | 本地先行 + 状态条；**不做**同步等待 |
| 双端并发改同一条 | 条件写 + 冲突对话框（既有）；条目级合并**不做**（KDBX 无此语义） |
| BW 侧回归 | W2 只加分支不动 BW 路径；`VaultSaveOutcome` 改动全仓搜消费点 |

## 8. 接力提示（新会话从这里开始）

**基座**：W1/W2/W3 已提交并推送 `origin/main` —— 新会话能直接读到本单（接力第一步不会断）。
⚠️ **本文件刻意不写 HEAD 哈希**：写死的哈希每提交一次就过期一次，而**过期不可观测**
（文件还在、只是内容旧了，AI 不会怀疑）⇒ **开工前自己 `git log -1` / `git status -sb` 确认基座**。

**已完成**：W0 数据层（`e75de10`）· W1（`214f6ec`）· W2（`5b44ba0` + `c92eac8`）·
W3（`c92eac8`）。逐项做法与踩到的坑见各批次标题后的 ✅ 与
[`SESSION-2026-09-30.md`](../../.ai/SESSION-2026-09-30.md)（**按需只读一节**）。

**下一步（按序，各自独立可验收、独立提交）**

1. **W4 · 回收站映射**（见上）—— 最小、独立、能立刻验收；
2. **W0 收尾**：落盘三来源（SAF `CreateDocument` / OneDrive / WebDAV 新建文件）
   + 仓储入口（⚠️ **另立 domain 接口，别动 `VaultRepository`**：`VaultRepositoryImpl`
   正好卡 40 函数）+ 添加流程 UI + **XC/DX 互操作验收**。
   ⚠️ 数据层（`KdbxCreator`）**已就绪**，缺的是"入口"—— 用户现在还不能新建一个空库。

**可并行、与本单无依赖**
- 「解锁慢」的诊断埋点已装机（`CachedKdbxFileSource` 缓存判定日志），下次解锁即可取数；
  若查实是"token 判定 bug"，优先修（影响体验且改动小）。

**纪律提醒（本项目付过代价的，动手前读一遍）**
- 提交信息里写"门禁全绿"必须**复跑实证**（`7902f76` 自述全绿、实际 detekt 是红的）；
  「任务 UP-TO-DATE」≠「代码是好的」。
- **门禁三关分开单跑**（串成一条会 `Gradle build daemon disappeared` —— 环境崩，不是代码错）。
- 交给**第三方库**的密钥/凭据字节**先 copy**（#135）；
  上游 API 行为与源码不符时**以行为为准**（#136）。
- 写 KDoc **别让粗体紧接斜杠**（`**x**/` 会构成 `*/` 提前闭合注释）。
- `VaultixPreferences` / `VaultRepositoryImpl` 等几个类**贴着 detekt 函数数上限**，
  加方法前先想"该不该另立一个类"（本项目已有 `KdbxSync*` / `AutoUnlock*` / `LocalUnlockEnrollment`
  等多个先例）。
