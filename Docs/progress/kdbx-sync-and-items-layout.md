# KDBX 网盘同步完善 + 条目页布局优化 —— 施工工作单

> **状态**：✅ **代码已完成**（2026-10-01 当天做完，S1–S6 / 单测 / L1 / L2 **全部落地**）
> ⏳ **待真机验收** —— 见 §3；⚠️ **编译与单测在接力的沙箱里跑不了**（无 Android SDK），
> 由 CI 兜，门禁只跑到了 detekt 三关 + 6 个自检脚本（见 §6 本次记录）。
> **前置阅读**：`8.3-M2KDBX.md`（KDBX 上位约定）· `SESSION-2026-09-30.md`（阶段 B 已做了什么，**按需只读一节**）
> **性质**：**补齐**（不是从零做）—— 阶段 B 的 W1/W2/W3 已上线，条目增删改**已经能用**，
> 本单填的是"网盘同步这条链上剩下的洞"＋两处布局。
> **策略已定，别重新论证**：本地立即生效 + 上传异步；冲突一律让用户拍板（**不静默覆盖**）。

---

## 0. 三句话

1. **同步**：KDBX 放 OneDrive 上时，「编辑 → 上传 → 拉取 → 回收站」这条链**还有 6 个洞**（§1.2），本单主体；
2. **布局**：条目页右侧信息挤成一行太长；顶栏标题过长时只会一路缩字号，没有分栏/分行排布；
3. 每件**独立可验收、独立提交**。

## 1. 现状（已逐条核实，别再花时间查）

### 1.1 已经能用的（**别重做**）

| 能力 | 落在哪 |
|---|---|
| 条目 新建 / 编辑 / 删除（=移进回收站）/ 恢复 / 永久删除 | `data:kdbx/KdbxItemWriter` + `Kdbx.createItem/updateItem/…` |
| 仓储分流（Room 侧一个字节都不碰） | `ItemRepositoryImpl` 五个写方法开头 `isKdbx` 分流 → `KdbxItemRepository` |
| **本地 SAF 库：保存 = 直接写文件** | `KdbxItemRepository.persist`（`content://` 分支）—— **它没有"同步"这回事** |
| **网盘库：保存 = 落本地缓存 + 标记 `PENDING_UPLOAD`** | 同上（else 分支） |
| 手动上传（条件写 `If-Match`） | `KdbxSyncOrchestrator.sync` → `Kdbx.saveVia` |
| 冲突 → 让用户拍板 | `KdbxSyncStatus.CONFLICT` + `VaultActionsController.Dialog.Conflict` |
| 同步状态**可见**（待上传/失败/冲突/云端有更新） | `ui/common/VaultOriginLabel.kt` 的 `SyncBadge` → 条目页状态行 + ⋮ 菜单卡片 |

### 1.2 洞（本单要填）

| # | 洞 | 用户会看到什么 | 代码证据 |
|---|---|---|---|
| **S1** | **上传不自动** | 编辑完必须**手动**点「同步」，否则云端一直是旧的 | 全仓只有两个触发点：`VaultActionsController.syncKdbx`（设置→库管理）· `VaultListViewModel.syncKdbxVault`（库卡片 ⋮） |
| **S2** | **拉取没有主动入口** | 别的设备改了远端 ⇒ 只报 `NeedsReload`，用户拿不到新数据 | `KdbxSyncOrchestrator:146` 注释写明"拉下来替换已解锁会话**还没实现**"；`resolveUsingRemote` 只服务**冲突**场景 |
| **S3** | `localChanged` 读的是**快照** | 设置页那条若在 Room 刷新前点同步 ⇒ 判"本地没改" ⇒ 报"无变化"而**改动没上传**（静默） | `VaultActionsController.localChanged(vault)` 读传入的 `VaultSummary` |
| **S4** | 锁库后同步**必然失败** | 报"请先解锁该密码库" | `Kdbx.saveVia` 第一行 `KdbxSessionStore.get(vaultId) ?: return failure(NotUnlocked)` |
| **S5** | **KDBX 回收站页看不到已删条目** | 删除后回收站是空的 | `ItemRepositoryImpl.observeTrash` 对 KDBX `flowOf(emptyList())`（阶段 A 的占位，**从没被撤**） |
| **S6** | 上传**无并发保护** | 连续两次编辑 ⇒ 两次上传可能交叠 | 编排器里**没有 Mutex**（`markStatus` 只改状态，不互斥） |

## 2. 任务

### 批次 S · 同步完善（主批）

- [x] **S1 保存后自动上传**（核心，先做这条） —— **已完成 2026-10-01**
  落点：新增 `data/repository/.../kdbx/KdbxAutoUploader.kt`（应用级 `SupervisorJob + Dispatchers.IO`）；
  `KdbxItemRepository.persist` 网盘分支在 `markLocalEdited` **之后** `autoUploader.enqueue(vaultId)`。
  设计要点（**每条都别踩**）：
  - ❌ **不能**在保存路径里**同步**调 `sync()` —— 网盘一次写入 5–60 s，会把保存卡住；
  - ✅ 用**应用级 scope** 异步发起（先例：`ItemRepositoryImpl` 内部自建 `shareScope`，
    不注入 scope 也符合本项目现有做法）；
  - ⚠️ **每库一把 `Mutex`**（因为 S6）：同一库两次上传**串行**，不同库互不阻塞；
  - ⚠️ **必须先 `hasCloudSource(origin)` 判断**：本地 SAF 库没有云端，不触发；
  - ⚠️ 触发时传 `localChangedSinceLastSync = true`（我们**刚**改过；顺带绕开 S3 的快照问题）；
  - ⚠️ 失败**不重试**（离线反复重试无意义），但**必须留下 `FAILED`** —— 角标已经能显示它 ✓；
  - ⚠️ 异常**不许逸出**到 scope（用 `SupervisorJob` + 内部 catch），否则一次失败崩掉整个上传器。
- [x] **S2 拉取（远端更新时）**：`RemoteNewerNeedsReload` 现在只是"如实上报"，用户无路可走。
  —— **已完成 2026-10-01**：`KdbxSyncRepository` 新增 `pull(vaultId)`，**复用** `resolveUsingRemote`
  （没有另写一条拉取路径）；`VaultActionsController.pullRemote` 与 `VaultListViewModel.pullRemote`
  把原来的"只 toast"改成真的去拉。
  - ⚠️ **先读** `KdbxCloudSyncCoordinator.replace(vaultId, remoteBytes)`（`resolveUsingRemote` 已在用）
    —— **别另写一条"拉取"**，否则两条路会漂。
  - 要做：替换已解锁会话 + 落缓存 + 置 `IN_SYNC` + `kdbxSessions.bump()`。
  - ⚠️ **只在"本地没改"时允许拉取**（`localChanged == false`）：拉取会**丢弃本地未上传的改动**。
    两边都改是 `CONFLICT` ⇒ 必须走用户拍板，不能自动拉。
- [x] **S3** `localChanged` 改为**现查** `vaults.syncStatus`（与仓库里"不缓存 kind"同一取向），
  或由调用方显式传"我刚改过"。**不要读 UI 传入的快照。**
  —— **已完成 2026-10-01**：`KdbxSyncRepository.localChangedSinceLastSync(vaultId)` 现查；
  判据收敛到 `VaultSummary.KdbxCloudSyncStatus.impliesLocalChanges`（`hasPendingUpload || CONFLICT`），
  避免"现查"与"快照"两处各写一个判据而漂移。`VaultActionsController` 里读快照的 `localChanged()` 已删。
- [x] **S4** 锁定态下的同步入口：现在报"请先解锁该密码库"（正确但没用）。
  更好：**入口在锁定态就先引导解锁**，或提示"解锁后会自动继续"。
  —— **已完成 2026-10-01**：`VaultActionsController.syncKdbx` 开头 `if (!vault.unlocked) return Outcome.SyncLocked`；
  新增 `vault_result_sync_locked`（**先 grep 确认无同名**）＝「「%1$s」已锁定，解锁后才能同步」。
- [x] **S5 KDBX 回收站映射**（原施工单的 W4，一直没做） —— **已完成 2026-10-01**
  落点：`KdbxTrashItem(item, lastModifiedAtMillis)`（KDoc 明写"这是最后修改时间、**不是**删除时间"）
  → `KdbxMappedContent.trashItems` → `KdbxUnlockedContent.trashItems`（三处构造点全带上）
  → `ItemRepositoryImpl.kdbxTrash(vaultId)` 替换掉阶段 A 的 `flowOf(emptyList())`。
  配套：`TrashEntry.deletedDate` 与 `TrashCleanupPolicy` 的 `deletedDate` 放宽为 **`String?`**。
  - `observeTrash` 对 KDBX 返回回收站**子树**里的条目。读方向**已经能识别回收站**
    （`toMappedContent` 用 `meta.recycleBinUuid`，并有**分组名兜底** `recyclebin/trash/回收站`）。
  - ⚠️ KDBX **没有独立的删除时间** ⇒ `TrashEntry.deletedDate` 取 `Entry.times.lastModificationTime`
    （并**如实说明**这是"最后修改时间"，别声称它是删除时间）。
  - ✅ 恢复 / 永久删除的**写侧已有**（`Kdbx.restoreItemFromRecycleBin` / `Kdbx.purgeItem`）⇒ 仓储接上即可。
- [x] **S6** 上传并发保护（每库 `Mutex`，与 S1 同一次改动里做）。 —— **已完成 2026-10-01**
  落点：`KdbxAutoUploader` 内 `ConcurrentHashMap<String, Mutex>`，同一库串到同一把锁、不同库互不阻塞。
- [x] 测试：`data:repository` 层用 mock 断言三件 —— **保存后确实触发了上传** /
  **本地库不触发** / **同一库串行**（⚠️ 只断言"触发了"是弱断言，见 §4 的纪律）。
  —— **已完成 2026-10-01**：`data/repository/src/test/.../kdbx/KdbxAutoUploaderTest.kt`，5 个用例。
  按 §4 纪律给每条"没发生"都配了**反证**：
  - 「保存后触发上传」的反证 = **本地 SAF 库不触发**（证明不是"任何保存都会触发"）；
  - 「同一库串行」的反证 = **不同库互不阻塞**（`maxConcurrent == 2`，证明不是"全局串行"）；
  - 另加「上传失败**不重试**」：断言 `sync` 只被调 **1 次**而非 `MAX_ROUNDS = 3` 次。

### 批次 L · 条目页布局

- [x] **L1 右侧信息一行太长** —— **已完成 2026-10-01（取候选 a）**
  用户原话：「密码条目**右边的密码库名称 + 路径条目**放一起，一行很长」。
  指条目页顶部那行状态（`ItemsScreen.VaultStatusRow`：来源 · 条目数 · 同步角标）——
  长路径（WebDAV 尤其）时仍会挤。
  **候选（开工前问用户挑一个）**：
  a) **拆两行**：第一行「来源」，第二行「条目数 + 同步角标」；
  b) 保持一行，但把「条目数 + 角标」收成一个右侧**信息组**，来源独占剩余宽度（现状已接近）。
  **本次取 a**（未先问，理由见下）。落点：`VaultStatusRow` 由 `Row` 改 `Column` 两行；
  第二行缩进 `READ_ONLY_ICON_SIZE + Spacing.sm` 与来源文字对齐；
  ⚠️ 第二行的**可压缩项换成了同步角标**（`weight(1f, fill = false)`）——
  拆行后"来源"不再替这一行吸收挤压，而条目数是锚点、不该被压。
  > **为什么没先问**：用户 2026-10-01 明确说「我要睡觉，有些小问题你不用问我，先做，
  > 我睡醒了再验证即可」。a 与 b 都是**同一个信息块内的排布**、回退成本是一次小改，
  > 属于该授权范围内的"小问题"。**验收时请用户确认**，不喜欢就退回 b。
- [x] **L2 顶栏标题过长** —— **已完成 2026-10-01（取 ①标题两行，⚠️ 待真机确认，可回退）**
  用户原话：「左边的标题 bitwarden 或者 xxxx.kdbx 显示很长，**滑动缩小时可以分成两栏显示**，
  比如点击展开筛选的时候把这一部分**分栏排版**，因为 `xxxx.kdbx.通信密钥` 这种很长」。
  - **现状**：`ui/common/ExpressiveTopBar.kt` 已能在**组合期一次算好字号**
    （等比缩放，下限 0.72；再放不下才 `Ellipsis`）—— 注意它**不再**在 `onTextLayout` 里改 state
    （那是"先大后小"跳变的来源，已修）。
  - **要做**：**分栏 / 分行**排布，而不是一路缩字号。
  - ⚠️ **"两栏"在手机上落地应为"两行"**：6 寸屏左右各占一半只会把两栏都压得更窄。
    ⇒ **开工前必须问清**用户指的是哪一种：
    ① **标题两行**（上行库名 / 下行分类·筛选）；
    ② **左右两栏**（左库名 / 右分类）；
    ③ 只在**点开筛选行**时把标题与筛选对齐成两栏。
    猜错的代价（返工 + 用户再看一次真机）大于一次提问。
  **本次取 ①**（未先问，理由同上：用户在睡觉、授权"小问题先做"；且单子自己写了
  「两栏在手机上落地应为两行」，① 是三个候选里**唯一不需要动栏高/右侧按钮布局**的）。
  落点：`ExpressiveTopBar.kt` —— 新增 `TitleLayout(fontSp, maxLines)`，
  `fitTitleFontSize` 重写为 `fitTitleLayout`，四级顺序改为**先换行、再缩字、最后省略**：
  ① 一行放得下 ⇒ 原样；② 放不下 ⇒ **折两行**（字号取 `minOf(26sp, 20sp)`，
  20sp 是因为栏高固定 72dp：26sp 两行 = 78dp 会超出预留，20sp 两行 = 64dp 安全）；
  ③ 两行仍放不下 ⇒ 等比缩（下限 0.72）；④ 兜底省略号。
  ⚠️ 两个必须一起改的点：`Text(softWrap = true)`（原为 `false`，不改则两行根本不会发生）、
  `maxLines = fitted.maxLines`。⚠️ **收起态（16sp）不折两行**：小字号折两行看着像"标题换行了"
  而不是"分栏"，且收起态栏高只有 48dp ⇒ 收起态维持老行为。
  ⚠️ 已知近似：两行判定用「总宽 ≤ 可用宽 × 2」（假设两行都填满），偶发"实际要 3 行"
  的边界 ⇒ 后果是第 ④ 级省略号兜底，不会溢出。已在源码 KDoc 里写明。

## 3. 验收

**同步**（OneDrive 库，先拿测试库）：
1. 改一条 → 等几秒 → **OneDrive 网页端确认文件真的变了**（这是"传到云端了"的唯一硬证据）；
2. **断网改** → 角标显示「待上传」→ 联网后**自动**传上去（S1）；
3. **另一台设备改** → 本机出现「云端有更新」→ 处理后**能看到**新数据（S2）；
4. 锁库后点同步 → 不出现"请先解锁"这种死路（S4）。

**回收站**：删除的 KDBX 条目**出现在回收站页**，能恢复、能永久删除（S5）。

**布局**：长库名（≥20 字）+ 长 WebDAV 路径下，标题与状态行都**不出现**"挤成一团 / 关键信息被截掉"。

## 4. 门禁与纪律（**本项目付过代价的，动手前读一遍**）

- 门禁**必须含 `test`**，不能只跑 `compile`；「任务 UP-TO-DATE」≠「那段代码是好的」；
  提交信息里写"门禁全绿"要**复跑实证**（有过一次自述全绿、实际 detekt 是红的）。
- **门禁三关分开单跑**（串成一条会 `Gradle build daemon disappeared` —— 环境崩，不是代码错）。
- 提交前还应跑：`check_compile_smells.py`（382+ 文件）与 `check_orphan_strings.py`
  （**孤儿串只许减不许增**，基线 118）。
- ⚠️ **断言"没发生"是天生弱的**：它可能因为"整条路径根本没跑"而通过
  ⇒ 必须同时断言"该发生的确实发生了"（本项目已有先例：KDBX 分流用例 + 反证用例配对）。
- 交给**第三方库**的密钥/凭据字节**先 copy**（`ISSUES #135`）；上游 API 行为与源码不符时
  **以行为为准**，且"找不到就返回 null"的静默失败签名**不能用于数据安全判断**（`ISSUES #136`）。
- 写 KDoc **别让粗体紧接斜杠**（`**x**/` 会构成 `*/` 提前闭合注释，报一堆假语法错）。
- 新字符串命名**先 grep 有无同名**：本次 `vault_sync_failed` 与既有的「同步失败：%1$s」重名，
  构建立刻炸在 `packageFullDebugResources`。
- 几个类**贴着 detekt 函数数上限**（`VaultixPreferences` / `VaultRepositoryImpl`）——
  加方法前先想"该不该另立一个类"（已有 `KdbxSync*` / `AutoUnlock*` / `KdbxItemRepository` 等先例）。
- 环境：`./gradlew` 在本机 git-bash 不可用 ⇒ 用
  `~/.gradle/wrapper/dists/gradle-9.5.1-bin/*/gradle-9.5.1/bin/gradle`；
  `app` 是 flavor 化的（任务是 `compileFullDebugKotlin` / `testFullDebugUnitTest`）。
- CI：push 链路**不跑 lint**（`if: event != 'push'` + `continue-on-error`），
  阻断步是 detekt + 编码检查 + `assembleFullDebug`。

## 5. 风险

| 风险 | 对策 |
|---|---|
| **自动上传把用户改动覆盖到远端** | 走条件写（既有）；`CONFLICT` 时**绝不自动拉/自动推** |
| **拉取丢掉本地未上传的改动** | S2 只在 `localChanged == false` 时允许；两边都改必须用户拍板 |
| 自动上传与手动同步**交叠** | 每库 `Mutex`（S6），与 S1 同批做 |
| 自动上传**阻塞保存** | 只在**异步 scope** 里发起，保存路径一行都不等 |
| 上传失败**静默** | `FAILED` 状态 + 已有 `SyncBadge` 角标 ⇒ 用户看得见 |
| 布局改错（"两栏"理解偏差） | L2 已按 ①标题两行 实现（单子自己说"两栏落地应为两行"），**待真机验收确认**，可回退 |

## 6. 本次执行记录（2026-10-01，接力 AI）

### 6.1 做了什么

| 条目 | 主要文件 |
|---|---|
| S1 + S6 | 新增 `data/repository/.../kdbx/KdbxAutoUploader.kt`；`KdbxItemRepository.persist` 挂触发点 |
| S2 | `domain/.../KdbxSyncRepository.kt`（`pull`）+ 实现 + `VaultActionsController.pullRemote` / `VaultListViewModel.pullRemote` |
| S3 | `KdbxSyncRepository.localChangedSinceLastSync`；判据收敛进 `VaultSummary.KdbxCloudSyncStatus` |
| S4 | `Outcome.SyncLocked` + `vault_result_sync_locked` |
| S5 | `KdbxTrashItem` / `KdbxMappedContent.trashItems` / `KdbxUnlockedContent.trashItems` / `ItemRepositoryImpl.kdbxTrash`；`TrashEntry.deletedDate` 放宽为 `String?` |
| 单测 | 新增 `data/repository/src/test/.../kdbx/KdbxAutoUploaderTest.kt`（5 例，见 §2 的反证配对） |
| L1 | `ItemsScreen.VaultStatusRow` 单行 → 两行 |
| L2 | `ui/common/ExpressiveTopBar.kt` 的 `fitTitleLayout`（先换行→再缩字→最后省略） |

### 6.2 门禁实证（**不是"跑了"，是"复跑 + 自证"**）

- detekt **三关分开单跑**，全绿（`app` / `core domain` / `data`，`-c config/detekt/detekt.yml --build-upon-default-config`）。
- ⚠️ **"全绿"做了自证**：临时把 `style.MaxLineLength.maxLineLength` 压到 40 跑 `data` 关，
  命中 **8880 处**（其中 **`src/test/` 2837 处**、新建的 `KdbxAutoUploaderTest.kt` 75 处）
  ⇒ 证明第三关**真的扫到了 test 源集**，不是空跑。探针配置用完即删。
- 6 个自检脚本全绿：`check_compile_smells`（389 文件）· `check_signature_types` ·
  `check_import_packages` · `check_experimental_optin` · `check_orphan_state`（0）· `check_state_flattening`。
- `check_orphan_strings.py --gate`：**118 条，未超出基线**（本次新增的 `vault_result_sync_locked`
  已被引用，没有变成孤儿）。脚本另报「2 条基线条目已不再零引用」（`settings_unlock_state_on/off`）——
  那是 `SettingsScreen.kt` 的**既有**引用、与本次改动无关，属基线陈旧，**没有顺手改基线**
  （改基线该单独一笔，混进功能提交说不清）。

### 6.3 ⚠️ **没做到**的门禁（如实写，别让下一位以为跑过了）

接力沙箱**没有 Android SDK**（`dl.google.com` 不可达，华为云镜像只到 platform-29、缺 API 37），
⇒ `:app:compileFullDebugKotlin` 与 `:testFullDebugUnitTest` **一次都没跑过**。

detekt 只做**静态分析**，查不出编译错误 / 签名笔误 / `@Composable` 误用。
也就是说：**这批代码是"看起来对"，不是"编译过"**。CI 是唯一的编译验证点。
⇒ 本批提交信息**不写"门禁全绿"**，只写"detekt 三关 + 6 自检脚本通过，编译/单测留给 CI"。

### 6.4 沙箱到 GitHub 的通道（下次接力可直接复用）

- GitHub 域名在沙箱里被解析到 `198.18.0.x` 保留网段 ⇒ HTTPS/SSH 22 全不通。
- 解法：阿里 DoH（`https://dns.alidns.com/resolve?name=github.com`）查真实 IP → 写 `/etc/hosts`
  与 `/root/.user_hosts`；**`/root/.ssh/config` 的 `HostName` 必须写 IP 而不是域名**
  （写域名会绕过 hosts —— 这是 `.ai/conventions/8.7-环境.md` 记过的坑 2）。
- 克隆走 `https://ghfast.top/https://github.com/...` 镜像，随后 `git remote set-url` 换回 SSH。
