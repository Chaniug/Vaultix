# KDBX 网盘同步完善 + 条目页布局优化 —— 施工工作单

> **状态**：📋 待开工（2026-10-01 凌晨立项；用户 00:47 / 00:49 两条实测反馈）
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

- [ ] **S1 保存后自动上传**（核心，先做这条）
  设计要点（**每条都别踩**）：
  - ❌ **不能**在保存路径里**同步**调 `sync()` —— 网盘一次写入 5–60 s，会把保存卡住；
  - ✅ 用**应用级 scope** 异步发起（先例：`ItemRepositoryImpl` 内部自建 `shareScope`，
    不注入 scope 也符合本项目现有做法）；
  - ⚠️ **每库一把 `Mutex`**（因为 S6）：同一库两次上传**串行**，不同库互不阻塞；
  - ⚠️ **必须先 `hasCloudSource(origin)` 判断**：本地 SAF 库没有云端，不触发；
  - ⚠️ 触发时传 `localChangedSinceLastSync = true`（我们**刚**改过；顺带绕开 S3 的快照问题）；
  - ⚠️ 失败**不重试**（离线反复重试无意义），但**必须留下 `FAILED`** —— 角标已经能显示它 ✓；
  - ⚠️ 异常**不许逸出**到 scope（用 `SupervisorJob` + 内部 catch），否则一次失败崩掉整个上传器。
- [ ] **S2 拉取（远端更新时）**：`RemoteNewerNeedsReload` 现在只是"如实上报"，用户无路可走。
  - ⚠️ **先读** `KdbxCloudSyncCoordinator.replace(vaultId, remoteBytes)`（`resolveUsingRemote` 已在用）
    —— **别另写一条"拉取"**，否则两条路会漂。
  - 要做：替换已解锁会话 + 落缓存 + 置 `IN_SYNC` + `kdbxSessions.bump()`。
  - ⚠️ **只在"本地没改"时允许拉取**（`localChanged == false`）：拉取会**丢弃本地未上传的改动**。
    两边都改是 `CONFLICT` ⇒ 必须走用户拍板，不能自动拉。
- [ ] **S3** `localChanged` 改为**现查** `vaults.syncStatus`（与仓库里"不缓存 kind"同一取向），
  或由调用方显式传"我刚改过"。**不要读 UI 传入的快照。**
- [ ] **S4** 锁定态下的同步入口：现在报"请先解锁该密码库"（正确但没用）。
  更好：**入口在锁定态就先引导解锁**，或提示"解锁后会自动继续"。
- [ ] **S5 KDBX 回收站映射**（原施工单的 W4，一直没做）
  - `observeTrash` 对 KDBX 返回回收站**子树**里的条目。读方向**已经能识别回收站**
    （`toMappedContent` 用 `meta.recycleBinUuid`，并有**分组名兜底** `recyclebin/trash/回收站`）。
  - ⚠️ KDBX **没有独立的删除时间** ⇒ `TrashEntry.deletedDate` 取 `Entry.times.lastModificationTime`
    （并**如实说明**这是"最后修改时间"，别声称它是删除时间）。
  - ✅ 恢复 / 永久删除的**写侧已有**（`Kdbx.restoreItemFromRecycleBin` / `Kdbx.purgeItem`）⇒ 仓储接上即可。
- [ ] **S6** 上传并发保护（每库 `Mutex`，与 S1 同一次改动里做）。
- [ ] 测试：`data:repository` 层用 mock 断言三件 —— **保存后确实触发了上传** /
  **本地库不触发** / **同一库串行**（⚠️ 只断言"触发了"是弱断言，见 §4 的纪律）。

### 批次 L · 条目页布局

- [ ] **L1 右侧信息一行太长**
  用户原话：「密码条目**右边的密码库名称 + 路径条目**放一起，一行很长」。
  指条目页顶部那行状态（`ItemsScreen.VaultStatusRow`：来源 · 条目数 · 同步角标）——
  长路径（WebDAV 尤其）时仍会挤。
  **候选（开工前问用户挑一个）**：
  a) **拆两行**：第一行「来源」，第二行「条目数 + 同步角标」；
  b) 保持一行，但把「条目数 + 角标」收成一个右侧**信息组**，来源独占剩余宽度（现状已接近）。
- [ ] **L2 顶栏标题过长**
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
| 布局改错（"两栏"理解偏差） | L2 **开工前问清**三选一 |
