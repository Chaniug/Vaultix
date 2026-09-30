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

### 2.6 ⚠️ 待拍板：已有的 **KDBX 3.1** 库怎么办

用户的「不向下兼容 3.0」有两种读法，**实现完全不同**，必须先定：

| 读法 | 含义 | 代价 |
|---|---|---|
| **A（推荐）只约束写入** | 新建 = 4.1；**已有的 3.1 库仍能读**，但一旦编辑保存 ⇒ **升格写回 4.1** | 需要"Ver3x → Ver4x"的转换路径（kotpass 两个类是分开的，**不能直接 copy**），并要验 4.1 里 3.x 没有的字段（如 `RecycleBin`/`CustomData`）怎么落 |
| **B 严格只认 4.x** | 读到 3.1 直接拒绝并提示「请先用 KeePassXC 升级到 4.1」 | 只需改 `KdbxFormat.SUPPORTED_MAJOR`（现为 `3`）；但**会挡掉老用户已有的库** |

现状：`data:kdbx/KdbxFormat.kt` 的 `SUPPORTED_MAJOR = 3`（接受 3.1+），
且写回走 `Kdbx.saveVia` 的 `currentVersion` 分支 —— **默认会原地保持原版本**。

⇒ **开工前请拍板 A / B。** 未定之前，W1 按"只处理 4.x 会话"实现，
3.x 会话走"可读不可写 + 明确提示"（等价于 B 的写侧），这条不会白做。

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
- [ ] `KdbxCreator.createEmpty(name, password, keyFile?)`：
      `KeePassDatabase.Ver4x.create(...)` → 建 `RecycleBin` 组 → `KdbxEncoder.encode` → 落盘
      ⇒ 目标 **KDBX 4.1 + Argon2d + AES-256 + gzip**（**只选 XC/DX 都支持的套件**，见 §6）
- [ ] **KDF 参数与 KC 对齐**：默认档位抄 KeepassXC 的"默认"（Argon2d 内存/迭代），
      不要用 kotpass 的 `Argon2.default()` 裸值而不核对 —— 太弱会让 XC 提示"KDF 参数过旧"，
      太强会让低端机解锁十几秒。**开工时把两边参数抄下来写在代码注释里**
- [ ] 落盘三个来源各一条路径：SAF（`CREATE_DOCUMENT` 建新文件）/ OneDrive（上传新文件）/
      WebDAV（`PUT` 新文件）；⚠️ `OneDriveKdbxFileSource` 目前只有"解析已有 origin"，
      要补"创建远端新文件"的分支（先 `adb`/网页端核实 API 可行性）
- [ ] 添加流程 UI：`AddVaultTypeDialog` 增「**新建 KDBX 库**」选项 + 名称/主密码/keyfile(可选) 表单
- [ ] 测试：新建 → `KdbxOpener.open` 能开 → `KdbxRoundTrip` 零损失 → 空库条目数 = 0
- [ ] ★ **互操作验收（本批就要过，别留到 W3）**：新建的文件分别用
      **桌面 KeePassXC** 与 **手机 KeePassDX** 打开、建一条、存盘、再由 Vaultix 打开读回

### 批次 W1 · 写映射 + 内存事务（纯 data:kdbx，零 UI）
- [ ] `KdbxItemWriter`：`createEntry / updateEntry / softDelete(→RecycleBin 组) / restore / permanentDelete`
      —— **底座直接用 §2.5 的 modifier**（`modifyEntry` / `removeEntry` / `withHistory` /
      `withRecycleBin`），本类只做**领域字段 ↔ Entry 字段**的映射
- [ ] 对齐 `KdbxItemMapper` 读方向的字段集合：OTP 位置式/`TimeOtp-*`、通行密钥 `KPEX_*`、自定义字段含排除表
- [ ] ⚠️ **保真铁律**（8.3 预告，逐条落测试）：`KPEX_*` 与不认识的自定义字段**原样保留**；
      位置式 `TOTP Settings` 按出现顺序写回；`TimeOtp-Secret-Hex|Base64` 真解码再编码；
      目标格式 KDBX 4.1；群组路径用读方向预留的 `groupPaths`
- [ ] **每次编辑都 `withHistory`**（KDBX 语义：改条目要把旧版推进历史，XC 里能看到）——
      ⚠️ 别漏，否则用户在 XC 侧"历史记录"永远是空的，而且**不可事后补**
- [ ] 内存事务 helper：`mutate(vaultId) { db -> db' }` = get → copy → 改 → **Fidelity 自检** → put → bump
- [ ] 测试（`data:kdbx`，纯 JVM）：每操作一条"往返逐字段相等"用例 +
      **"改 A 条目不影响 B 条目"** 用例（Fidelity 自检的判据就是它）

### 批次 W2 · 仓储分流（data:repository，撤第一道闸）
- [ ] `ItemRepositoryImpl` 五个写方法加 `kind == KDBX` 分流（#106 的病根就在这：**当年没分流**）
- [ ] `KdbxItemRepository`（或内联私有函数）：内存事务 + `VaultSaveOutcome`（新增 `LocalApplied`
      语义或复用 Queued——**开工时定**，注意 BW 侧消费点）
- [ ] 异步上传：接 `KdbxSyncOrchestrator`；失败 → 同步状态条（`KdbxSyncStatus` 已有 FAILED），
      **不静默**（"每个早退分支要么改状态、要么留日志"）
- [ ] 撤 `requireWritable` 的 KDBX 拦截（保留给"库未解锁"场景的防御）
- [ ] 测试：`ItemRepositoryImpl` 层 KDBX 分流单测（mock `Kdbx` 门面）

### 批次 W3 · UI 放闸（app，撤所有静默闸）
- [ ] `ItemsScreen`：恢复「+」（`readOnlyVaultOf` 改为可写/删除本函数）、删 `ReadOnlyVaultNotice`
- [ ] `ItemDetailViewModel.readOnly` → false 路径恢复编辑/删除按钮
- [ ] 详情页保存成功后的反馈对齐 BW（含"待上传"状态条的展示时机）
- [ ] 撤两条只读文案；孤儿串探针核对零新增
- [ ] **真机验收**（清单见 §6）

### 批次 W4 · 回收站映射（可选，独立批）
- [ ] `observeTrash` 对 KDBX 分流：RecycleBin 组 → `TrashEntry`
- [ ] `restoreItem` / `permanentDeleteItem` 接 W1 的对应操作
- [ ] ⚠️ 与 `TrashCleanupPolicy`（30 天自动清理）的交互：KDBX 侧清理 = 从 RecycleBin 组永久删除

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

**已完成（2026-09-30 夜）**
- ✅ 今日第二批已提交（viewLock 静默 no-op 修复 · 只读可见提示 · 缓存未命中留证）。
- ✅ **本单 §2 要求的 API 查证已做完**（§2.5 是反编译实证，直接照此写码）。
- ✅ 顺带修掉 `7902f76` 顶破 detekt `TooManyFunctions` 造成的红门禁（抽 `VaultTimeoutPreferences`）。

**开工第一件**：**拍板 §2.6（3.1 老库的 A / B 策略）** —— 它决定 W1 要不要写"Ver3x → Ver4x"转换。
未拍板时 W1 按"只处理 4.x 会话"推进（不白做）。

**随后按 W0 → W1 → W2 → W3 → W4 顺序做**，每批独立可验收、独立提交。

**可并行、与本单无依赖**
- 「解锁慢」的诊断埋点已装机（`CachedKdbxFileSource` 缓存判定日志），下次解锁即可取数；
  若查实是"token 判定 bug"，优先修（影响体验且改动小）。
