# 下一步任务清单 · 归档 2/6

> **历史归档，勿通读。** 覆盖 2026-10-22 ~ 2026-09-29 的已完成记录。
> 入口与当前待办见 [`next-steps.md`](./next-steps.md)。
> 拆出于 2026-11-10：原文件 3592 行 / 280 KB，远超 §7.1 的 300 行上限，
> 且真正的待办被埋在 96% 位置。归档按时间倒序，**只在需要追溯时检索**。

---

## ✅ 详情页闪过 1 帧「条目不存在或已被删除」（2026-10-22）· #181 修完

用户真机反馈；交接 `.ai/SESSION-2026-10-22.md`。

| 层 | 改动 |
|---|---|
| 状态 | `ItemDetailViewModel.UiState` 新增 `itemLoaded`（首个发射即真、永不回落）|
| 呈现 | `DetailBodyContent` 加 `loading`：`loading && item == null` 画转圈，`item == null` 才画提示 |
| 数据 | `observeItem` 加 `.onStart { emit(null) }`（前置订阅优化，**非**修复点，KDoc 已注明）|

**判据**：「未知」必须是一种显式状态，不能借用「空值」表达。

**门禁**：编译 ✅ · detekt ✅ · `gradle test` **160 套 / 1591 例 0 失败** ✅ · 10 个 Python 检查 ✅。

**待办（真机）**：① 点进条目不再闪提示；② 同步刷写触发流重发时不闪回"不存在"。

---

## ✅ KDBX 写入/同步链三条静默丢数据（2026-10-20 下半场）· #178–#180 修完

来源：用户「还有其他的bug需要修复吗？一起修复了」。交接 `.ai/SESSION-2026-10-20-b.md`。
三条的共同形态：**失败不报错、用户以为成功** —— 密码管理器里最坏的一类。

| # | 缺陷 | 根因 | 落点 |
|---|---|---|---|
| 178 | 并发编辑**静默丢一笔**，两次都返回成功 | `mutate` 的 `get → 改 → put` 不原子；`ConcurrentHashMap` 只保护单次操作 | `Kdbx.writeLocks` / `lockOf`（每库 `Mutex`，与 `replaceSession` 共用） |
| 179 | 锁掉/删除的库仍握着一把活锁 | `getOrPut` 只增不减，`lock` 只 `close` 会话 | `Kdbx.lock` / `lockAll` 一并 `remove` / `clear`（**先 close 会话再丢锁**） |
| 180 | 「用远端覆盖/拉取」窗口内的编辑被整体抹掉且记 `IN_SYNC` | check-then-act：`read` 要 1–10 s，判断却在 `read` 之前做完 | `KdbxCloudSyncCoordinator.resolveUsingRemote` 在 `read` **之前**再判一次 |

**验证**（三条都做了变异验证，撤掉修复 → 对应用例转红）：

| # | 正例 | 反证 |
|---|---|---|
| 178 | 两**线程**并发改不同条目，两笔都必须留（用协程撞不进窗口 —— `runBlocking` 是单线程事件循环） | 撤锁 → 5/5 红（`missing (1): 改乙`） |
| 179 | 写一次后计数 `> 0` → `lock` 后 `== 0` | 反证在**同一用例内**，证明装置能观测到"有" |
| 180 | `coVerify(exactly = 0) { source.read() }` 且不替换会话 | 独立用例：本地没改时必须 read 1 次、替换 1 次 |

**门禁**：`:app:compileFullDebugKotlin` + `:app:compileFullDebugUnitTestKotlin` + `detekt --rerun-tasks` ✅ ·
`gradle test --rerun-tasks` **套数=84 / 用例=776 / 失败=0 / 错误=0 / 跳过=0**（XML 时间戳在当次窗口内）✅ ·
9 个 Python 静态检查全 OK ✅（`check_issues_index` → "索引 = 题注 = 正文，全部一致"）。

> 🔴 **本轮又踩到"假绿"**：第一次 `gradle test` 超时被转后台，回来读到的 42 套/388 例
> 是 **08:21 的旧报告**。纪律重申：**看数字之前先 `stat` 报告时间戳**。

**查过但判定不是 bug（下一轮别重复查）**：

| 位置 | 结论 |
|---|---|
| `persist` 里 `source.write(bytes)` 不传 `expectedVersion` | 有意为之：`KdbxFileSource.write` 四象限表写明"全默认 = 本地 SAF 主写路径" |
| `KdbxItemRepository.update` 在锁外取 `before` | 安全：`applyDomainFields` 用锁内 `existing` 打底，`before` 只判"哪些字段被用户删了" |
| KDBX 库无 SSH 字段映射 | A3 入口硬闸整体拒收，不会产生静默残骸 |
| `Kdbx.save(File)` 零生产调用 | 能力（往返自检）已迁到 `persist` 的 `Kdbx.verifyWrite` |

---

## ✅ UI 审计剩余 5 项收口（2026-10-20）· 四项落地、一项判定不做

来源 `Docs/progress/audit/ui-design-audit-2026-10-19.md`；交接 `.ai/SESSION-2026-10-20.md`。
**上游这一轮已清空审计 §4 全表** —— 之后没有"审计遗留项"了，剩下的都是真机项与需硬件/需设计决策的项。

| 审计条目 | 级别 | 判定 | 落点 |
|---|---|---|---|
| §2.2 `Spacing` 被绕过（205 处字面量）+ §3.7 `18.dp` 散落 | P3 | ✅ **合并成一轮做完**：新增第四张表 `UiSize`（9 个成员），80 处字面量逐行精确替换（27 文件），清掉 9 个孤儿私有常量 | `app/.../ui/theme/UiSize.kt`（新建） |
| §3.4 中文硬编码 + 无 `values-en` | P3 | ✅ **117 → 0**（审计写 72，实测 117）：四类 `Res/Detail` 密封类型 + 反向哨兵脚本 | `settings/CloudError.kt`、`QuickUnlockText.kt`、`vaultlist/SyncNotice.kt`、`unlock/LocalUnlockMessage.kt`、`.ai/tools/check_ui_hardcoded_text.py` |
| §2.7 响应式三档未落地 | P3 | ✅ **只改文档**（一行布局没动）：`Docs/08 §4` 拆成 4.1 已实现 / 4.2 计划，登记 #177 | `Docs/08-界面设计规格.md` §4、`.ai/issues/06` #177 |
| §2.5 顶栏四套收敛 | P2 | ❌ **明确不做**：审计自己写"需真机看折叠手感"，本地改数值等于凭感觉 | 无改动 |

**§2.7 待做三项**（`Docs/08 §4.2` 计划表，均需硬件）：

| 项 | 目标 | 前置条件 |
|---|---|---|
| 三档化（Compact / Medium / Expanded） | 引入 `WindowSizeClass`，Medium 与 Expanded 分别定布局 | 折叠屏 + 平板真机 |
| Expanded 双栏 | `ListDetailPaneScaffold`：列表 360dp 固定 + 详情自适应、最大 720 居中 | 同上；且与"详情是独立页面"的导航模型冲突，要先定返回栈语义 |
| 折叠铰链避让 | `FoldingFeature` 取铰链位置，两侧各占一屏 | 折叠设备 |

**门禁**：编译 ✅ · `detekt --rerun-tasks` **`178 actionable tasks: 178 executed`** ✅ ·
`testFullDebugUnitTest` **`tests=388 skipped=0 failures=0 errors=0`**（数 XML 得）✅ ·
9 个 Python 静态检查全 OK ✅ · CJK 行宽超标 11 处全是 KDoc 表格行（detekt 实跑未报）✅。

**新增判据 5 条**（已写进 `.ai/conventions/8.4-UI·观感.md` 新纪律 5/6）：

1. **`Spacing` 管"两者之间"、`UiSize` 管"自己多大"**；18 与 20 并存不是重复，判据是**它和谁并排**。
2. **`Spacing` 纪律第 2 条「不在标尺上的值保持字面量」有边界** —— 一旦同一语义的值在几十个文件里反复出现，它就已经是语义了，该升级成常量。
3. **文案哨兵是反方向的**：`check_orphan_strings` 管"资源没人用"，`check_ui_hardcoded_text` 管"代码里写死了中文"，两条才闭环。
4. **兜底文案归 UI 层**：ViewModel 原样传 `null`，由 UI 拼模板 —— "异常没细节时说什么"是呈现决定。
5. **"拿不到 `Context`"不是写死中文的理由**：共享层产出带资源 id 的枚举，解析交给有 `Context` 的调用方。

**下一件该做的事：真机验收**（6 项一起量，一次装包全测完）

| # | 量什么 | 关联 |
|---|---|---|
| 1 | 4 键手势机下，库列表最后一条是否真的可点 | §2.1（2026-10-19 修） |
| 2 | 2.0x 字体下底栏是否还裁切 | §3.3 |
| 3 | 深色模式 `EntryCard` 0.5dp 描边是否还看得见 | §2.4 |
| 4 | 顶栏在 6 个沉浸式页面下的折叠手感（决定 §2.5 收敛方案） | §2.5（本轮判定不做） |
| 5 | OneDrive `If-None-Match: *` 的 201 / 412 | 旧真机项 |
| 6 | W6 KeePassXC 互操作 | 旧真机项 |

---

## ✅ UI 设计审计 §4 五个施工项全部落地（2026-10-19）· P0 → P2

来源 `Docs/progress/audit/ui-design-audit-2026-10-19.md` §4；交接 `.ai/SESSION-2026-10-19.md`。
**没写代码的地方**：本轮全部是 UI 层，域名/契约/DB 零改动。

| # | 级别 | 一句话 | 落点 |
|---|---|---|---|
| #31 | P2（**排最前做**） | 零 `@Preview` ⇒ 观感改动全靠装包验。建 `ui/preview/`，11 条，3 规格中心 × 字体档（1.0/1.3/2.0x）× 明暗 | `ui/preview/PreviewCanvas.kt`、`ComponentPreviews.kt` |
| #32 | **P0** | **真 bug**：库列表让位写死 `96.dp`，4 键手势机真实需 ≈118dp ⇒ 首个落地页最后一条点不到。函数化为 `rememberBottomDockInset()`（86 占位 + 16 字体增量 + 导航栏） | `VaultixBottomDock.kt` / `VaultListScreen.kt` |
| #32 | **P0** | `Role.Tab` 全项目 **0 处**（功能缺失）。补 5 处语义：底栏 tab / 显示选项 / 设置行 / 分组折叠 / 云账号列表 | 5 个文件 |
| #33 | P1 | 主 CTA「3 填色 × 3 高度 × 2 宽度」9 种写法 → `PrimaryActionButton` 1 个组件 | `ui/common/PrimaryActionButton.kt` + 9 处调用 |
| #33 | P1 | `IconButton` 压到 36dp ⇒ `minimumInteractiveComponentSize()`；字体缩放 2.0x 三处兜底 | `TotpCodesScreen` / `PasswordGeneratorDialog` |
| #34 | P2 | 空态 5 套 → `EmptyState` 1 个（卡包同时拆成标题 + 正文，6 条新字符串） | `ui/common/EmptyState.kt` + 5 处 |
| #34 | P2 | 圆角 6 种 → `VaultixCorner` 三档（`Small`/`Panel`/`Pill`，**按尺寸分档**）；顺带修 6 处「幽灵圆角」 | `ui/theme/VaultixCorner.kt` + `VaultixShapes.medium` |

**门禁**：编译 ✅ · `detekt --rerun-tasks` **10 模块任务行真执行**（无 `UP-TO-DATE`）✅ ·
`testFullDebugUnitTest` **42 套 / 388 例 0 失败**（数 `test-results/*.xml` 得）✅ ·
9 个 Python 静态检查全 OK ✅ · CJK 行宽 28 文件全 ≤120 ✅ · 函数 ≤150 行 / 参数 ≤7 ✅。

**新增判据 7 条**（已写进 `.ai/conventions/8.4-UI·观感.md`）：

1. 让位 = 占位高度 + 字体增量 + 系统内边距，三项缺一都会在某种设备上翻车。
2. `role` 与 `selected` 是两件事，`clickable` 一个都不给，自绘组件必须自己写。
3. `Shapes(...)` 是**部分覆盖**不是全量替换 —— 只传 `extraSmall` 时其余档位吃 M3 基线。
4. `MatchingDeclarationName` **不认挪文件** —— 一个文件只能有一个非 `private` 顶层声明。
5. `minimumInteractiveComponentSize()` **优于**手写 `heightIn(min)`（后者会拉大水波纹）。
6. Preview 的字体档必须覆写 `LocalDensity.fontScale`，设 `textSize` 是假的。
7. 圆角**按尺寸分档不按组件分档**，一句话判定 = 看它有多高。

**下一批（P3，都不建议现在单独做）**：

| 项 | 为什么先不做 |
|---|---|
| §2.2 `Spacing` 语义化（205 处字面量）+ §3.7 图标 `18.dp`（9 文件） | **必须合并成一轮**，先定"语义常量层"的形态；否则会产出一堵没人记得住名字的常量墙 |
| §2.5 顶栏四套收敛（沉浸式 6 页 vs 官方 10 页） | 需真机看折叠手感，纯数值改不动 |
| §2.7 响应式三档 | **文档超期**而非代码缺陷，改文档即可 |
| §3.4 中文硬编码 72 处 + `values-en` | 涉及 13 个 VM/Controller 例外，需单独排期 |
| **真机验收（本轮全部 5 项都没在真机上量过）** | 要量：4 键手势机下库列表最后一条是否真可点、2.0x 字体下底栏是否还裁切、深色下 `EntryCard` 的 0.5dp 描边是否还看得见 |

---

## ✅ #176 已修（2026-10-18）· `StandardTestDispatcher` 只解决"时间"，不解决"线程"

| 项 | 一句话 | 落点 |
|---|---|---|
| ① 取到红了 | CI run `39139391458`（sha `d646127`）硬门禁又红：`UnlockViewModelTest > promptCarriesTheFilteredCandidateVaults`，仍是 `Dispatchers.Main was accessed…`，**本地 `--rerun-tasks` 全绿** | CI 工件 `debug-unit-test-reports` |
| ② 根因 | `StandardTestDispatcher.dispatch` 只把任务排进 `TestCoroutineScheduler`，跑任务时**不恢复** `ContinuationInterceptor` ⇒ `withContext(io)` 的续体恢复时上下文里既没拦截器也没 `Dispatchers.Main` ⇒ `TestMainDispatcher.isDispatchNeeded` 炸（堆栈 `DispatchedCoroutine.afterResume` ← `Dispatchers.IO` 真线程 worker） | `kotlinx-coroutines-test` 内部 |
| ③ 修法 | 新增**拦截口** `testingBlockableIoDispatcher` + `ioContext`（语义是拦截不是替换），8 处 `withContext(io)` 改 `withContext(ioContext)`；测试传一个**非** `TestDispatcher` 子类的 `StandardTestDispatcher` ⇒ 不被 `runTest` 收编 ⇒ 内联执行且不改上下文 | `UnlockViewModel` / `UnlockViewModelTest` |
| ④ 门禁 | `ui.unlock.*` 整包 **29 例 × 连跑 5 次 = 5/5 全绿**；全量 **388 例 0 失败**；detekt ✅；8 个静态检查 ✅；索引校验 ✅ | — |

**实证**：`> Task :app:testFullDebugUnitTest` 真实执行（非 `FROM-CACHE`）388 例 0 失败；
`detekt --rerun-tasks` BUILD SUCCESSFUL。**CI run `39141070281` success**（sha `d32657c`），
硬门禁日志含 `> Task :app:testFullDebugUnitTest` + `BUILD SUCCESSFUL`。

判据一句话（写进 `.ai/issues/01` #176）：

> **虚拟时钟管的是"时间"，不是"线程"。** 凡是用 `runTest` + 注入调度器消灭
> `Dispatchers.Main` 竞态的，都要额外确认被测代码里有没有
> `withContext(<别的调度器>)` —— 有的话，那个调度器必须在测试里被**拦截**掉。

---

## ✅ #175 已修（2026-10-18）· `init { scope.launch { … collect } }` 是单测随机假红的总闸

| 项 | 一句话 | 落点 |
|---|---|---|
| ① 取到红了 | #174 把调度器注入做好之后，`security.*` 仍**偶发** 1–2 条红，且能出现**两条互斥断言同时红**（"不该锁"与"该锁"都红），每次红的不是同一条 | `VaultLockManagerImplNeverTest` |
| ② 根因（两层） | `init { scope.launch { vaultRepository.observeVaults().collect {…} } }`：**构造期派发**（`scope` 的调度器来自构造后赋值的 `internal var`，那一发在赋之前就落到 `Dispatchers.Default`，虚拟时钟永远推不动）+ **无界 `collect`**（协程永不结束 ⇒ `runTest` 的 idle 判据被污染） | `VaultLockManagerImpl` / `AutoLockController` |
| ③ 修法 | 启动时机从 `init` 交出来：`startObservingVaults()` / `startCollecting()`（均幂等 + `@Volatile` 守卫），生产在 `VaultixApplication.onCreate` 显式调；`scope` 保持 getter | 同上 + `VaultixApplication` |
| ④ 测试 | 夹具顺序改为"**先注入调度器 → 再 start → `runCurrent()`**"；新增只读钩子 `timerCountForTest()`，`settle()` 用"计数稳定（≥3 轮下限）"当收敛信号；`AutoLockControllerTest` 从零补 6 例 | 两份测试文件 |
| ⑤ 门禁 | `security.*` 19 例 × 连跑 6 次（`--rerun-tasks`）= **PASS 6 / FAIL 0**；探针全清（`probeCtxForTest` + 全部 `PROBE`/`DBG`） | — |

**实证**：本地 `:app:testFullDebugUnitTest --rerun-tasks` 真实执行（任务行
`> Task :app:testFullDebugUnitTest`，**非 `FROM-CACHE`**）**388 例 0 失败**
（原 377 + `AutoLockControllerTest` 6 + `VaultLockManagerImplNeverTest` 8→13 的净增 5）；
`security.*` 四类共 30 例（AutoLockController 6 / AutoRestoreTrigger 5 / ScreenSecurityGuard 6 /
VaultLockManagerImplNever 13）。
detekt ✅ BUILD SUCCESSFUL；8 个 Python 静态检查全 OK（`check_signature_types.py`
因删掉 `DelegatingDispatcher` 死代码从 FAIL 转 OK）；`check_issues_index.py` ✅；
变异验证 **6 组全部精确命中**（红的条数依次 6 / 3 / 2 / 3 / 1 / 1，每组范围都能一句话解释）。
双变体编译 `:app:compileOfflineDebugKotlin` + `:app:compileFullDebugKotlin`
（`--rerun-tasks` 真跑，非 UP-TO-DATE）✅ BUILD SUCCESSFUL。

两条**试过但不通**的路已写进 KDoc 防止后人重踩：

1. 自定义 `CoroutineDispatcher` 包装做"延迟解析" ⇒ **`delay` 永远醒不过来**
   （虚拟时钟要求上下文里的 `CoroutineDispatcher` 就是 `TestDispatcher` 本身；实测
   `timer coroutine started` 出现 5 次、`timer fired` 0 次）；
2. `launchIn` 想"等到注入之后再派发" ⇒ 它**立即** `dispatch`，救不了构造期派发。

虚拟时钟三条规则（连踩四轮才定）：`settle()` 建钟不推钟；
`advanceTimeBy(N-1)` + `runCurrent()` 断"不该锁"（**不能跟 `advanceUntilIdle`**）；
`advanceTimeBy(N)` + `advanceUntilIdle()` 断"会锁"。
细节见 `.ai/issues/01-构建与环境.md` #175。

### 遗留

1. `AutoLockController.lockAllNow()` 是**生产零调用**的死链（已写进类 KDoc），是否接线到
   UI 的"立即锁定"待定 —— 当前唯一入口是 `VaultLockManager.lockAll`；
2. 两个真机项仍不可执行（见文末"下一步"）。

---

## ✅ #174 已修（2026-10-18）· 解锁页协程作用域可注入，CI 单测步转绿

| 项 | 一句话 | 落点 |
|---|---|---|
| ① 取到红了 | CI `Run unit tests (hard gate)` 只红一例：`UnlockViewModelTest > candidateVaultsExcludeTheTargetItself`，`Dispatchers.Main was accessed when the platform dispatcher was absent` | 工件 `debug-unit-test-reports` |
| ② 机理 | `viewModelScope.launch { withContext(IO) {…} }` 的续体回主线程是真线程 resume，而 `@After resetMain()` 已把 `TestMainDispatcher` 卸下 ⇒ 竞态 | — |
| ③ 修法 | `testingScope` / `testingIoDispatcher` 两个 `internal var`（生产恒 null），协程挂载点收敛到 `scope` / `io` | `UnlockViewModel` |
| ④ 测试 | 注入 `TestScope` + `StandardTestDispatcher(testScheduler)`；等待从"轮询真实终态 + 5s 超时"改写为 `runCurrent()` / `advanceTimeBy(1)` | `UnlockViewModelTest` / `StaleRoomResealTest` |
| ⑤ 门禁 | 新增 `overrideScopeIsActuallyUsedByTheViewModel`（Job 同一性）+ `promptCarriesTheFilteredCandidateVaults`（端到端），断言未放宽 | 同上 |

**实证**：本地 app 单测真实执行（任务行非 `FROM-CACHE`）**377 例 0 失败**
（原 375 + 2 条新门禁）；CI run `39085733395` **success**，硬门禁日志含
`> Task :app:testFullDebugUnitTest` 与 `BUILD SUCCESSFUL`；detekt / 编码检查 /
索引校验 ✅；`lintFullDebug` 仍为存量 34 errors（与基线逐条一致）。
变异验证 4 组各自精确命中（细节见 `.ai/issues/01-构建与环境.md` #174）。

---

## ✅ #172 已修（2026-10-17）· OneDrive 无基线覆盖 + 分片上传非法 JSON

| 项 | 一句话 | 落点 |
|---|---|---|
| ① 无基线覆盖 | `KdbxFileSource.write` 加**独立参数** `failIfExists`（不靠 `expectedVersion == null` 推断），三个来源各自对齐 | 接口 + SAF / WebDAV / OneDrive |
| ② 分片上传一直是非法 JSON | `createUploadSession` 手拼 `"conflictBehavior":fail`（值漏引号）⇒ **> 2MiB 的库在 OneDrive 上从来没存进去过**。改 DTO + `Json(explicitNulls = false)` | `OneDriveGraphClient` |
| ③ 补写后读回 | `verifyReadBack`：size 廉价预筛 + 下载逐字节比对；**读不回来也算冲突** | `OneDriveKdbxFileSource` |
| ④ 文案 | `add_kdbx_privacy_note` 原写"文件只保存在本机"—— 与支持 OneDrive/WebDAV **直接冲突**；另加"下一步选位置"提示 | `strings.xml` / `AddKdbxScreen` |

**实证**：CI 同款 8 任务 `--rerun-tasks` 强制重跑，JUnit XML 重数 **1155 例 0 失败**
（app 375 / repository 176 / kdbx 171 / crypto 195 / common 151 / bitwarden 64 /
datastore 18 / model 5，净增 13 例）；变异验证 **6 组全部精确命中**；
detekt / 编码检查 ✅。`lintFullDebug` 报 34 errors，但在 `82baef8` 基线 worktree
上跑出**完全相同的 34 条**（逐条消息比对，差异集为空）⇒ 与改动无关，属存量。

### ✅ 已闭环：CI 单测步的红（#173 → #174，2026-10-18 修）

拿到 PAT 之后取到真相：**只红一例** ——
`UnlockViewModelTest > candidateVaultsExcludeTheTargetItself`
（`375 tests completed, 1 failed`），报
`IllegalStateException: Dispatchers.Main was accessed when the platform dispatcher
was absent`（`TestMainDispatcher.isDispatchNeeded`）。

- **机理**：VM 里 `viewModelScope.launch { withContext(Dispatchers.IO) {…} }` 的
  **续体回主线程**是真线程 resume；而 JVM 单测的 `Dispatchers.Main` 只是替身
  （`kotlinx-coroutines-android` 不在测试 classpath），`@After resetMain()` 一卸
  ⇒ 真线程恰好在这之后 resume 就炸。同文件另两条同款代码没红 ⇒ **竞态**。
- **修法**：`UnlockViewModel` 加 `testingScope` / `testingIoDispatcher` 两个
  `internal var`（生产恒 null ⇒ `viewModelScope` / `Dispatchers.IO`），
  测试注入 `TestScope` + `StandardTestDispatcher(testScheduler)`，等待转成确定性虚拟时间。
- **结果**：本地 `:app:testFullDebugUnitTest` 真实执行 **377 例 0 失败**
  （任务行非 `FROM-CACHE`）；CI run `39085733395` 硬门禁
  `> Task :app:testFullDebugUnitTest` + `BUILD SUCCESSFUL`。
- 细节与四条"更标准的路为何不通"见 `.ai/issues/01-构建与环境.md` #174。

### 下一步

1. **真机验 OneDrive `If-None-Match: *`**：Graph 官方文档只字未提该头语义，
   201/412 的实际表现需实测（本环境无 Microsoft 账号）；
2. **W6 真机互操作验证**（KeePassXC / KeePassDX 打开 Vaultix 写的库）——仍需真机；
3. 存量 34 条 lint error 是否要做一轮清理（`LocalContextGetResourceValueCall` 25 条占大头）——待定，
   注意 CI 里 lint 步骤是 `continue-on-error: true` 且 push 链路 `skipped`（见 ISSUES #100）。

---

## ✅ W5 已落地（2026-10-16）· CI `38927742868` 真绿

**commit**：`6ae9bcd`（转换器 + 入口 UI + 测试，17 files +2995/-97）
**实证**：本地 CI 同款 8 任务 + detekt 全绿，JUnit XML 重数 **1142 例 0 失败**
（app 363 / repository 175 / kdbx 171 / bitwarden 64 / core:* 369）；
CI run `38927742868` success。⚠️ 沙箱 `gh` 未认证 ⇒ run 状态走匿名 REST API，
**工件下载 401 做不到** ⇒ 核数以本地同命令替代（已如实记入施工单）。

| 项 | 一句话 | 落点 |
|---|---|---|
| 转换器本体 | `build`/`read` 双向；写完即 `KdbxRoundTrip.verify` 自检（不过 ⇒ `WriteCheckFailed`）；**无会话转换**（不登记 `KdbxSessionStore`） | `data/kdbx/KdbxConversion.kt` |
| 领域接口 | `VaultConverterRepository` + 六类 `VaultConversionException` | domain / `data/repository/VaultConverterRepositoryImpl.kt` |
| 入口 UI | 设置 → 导入/导出 → 页首转换行 → `ConversionScreen`（SAF 两读两写 + 预览计数卡） | `app/.../ui/settings/` |
| 密码纪律 | 预览后即弃文件密码、转换成功后即弃输出密码；字节只经 UI 走 SAF 落盘 | `ConversionViewModel` 状态机 |

三个关键设计决策（施工单与 SESSION 有完整论证）：

1. **旁路字段只写不读**：`VPX_BW_ID` / `VPX_BW_TYPE` 只由转换器直接消费，
   不进领域模型 —— KDBX 没有 Bitwarden 的 id/type 概念，泄漏进模型会污染主读写链。
2. **降级必须可见**：KDBX 无 Card/Identity/SshKey 承载 ⇒ 载荷守卫
   （`restoredType`）+ `typeDegradedCount` 计数，预览卡与转换结果都能看到。
3. **Bitwarden UUID 硬约束**：folder/cipher id 必须合法 UUID ⇒
   `remapFolderIds` 建新 UUID 映射（item 取 `VPX_BW_ID` 或裸 uuid 兜底）。

新增坑 **#171**（过期 test-results XML 误导核数）。`ConversionViewModelTest` 11 例，
含变异验证（抽掉「预览后清空文件密码」⇒ 精确只红 1 例）。

### 下一步

1. **W6 真机互操作验证**（KeePassXC / KeePassDX 打开 Vaultix 写的库）——
   **需真机，本环境不可执行，是唯一遗留项**；
2. 审计 B 组剩余项。

---

## ✅ A4 + A6 已修 · A5 提案驳回（2026-10-16）· CI `38894211052` / `38898302875`

**commits**：`3fc8649`（A4 安全笔记类型往返翻转）→ `fb7cc9d`（A4 文档）→
`d50463f`（A6 标准字段受保护属性）
**实证**：本地全量门禁 8 任务 + detekt 全绿，JUnit XML 重数 **1108 例 0 失败**；
CI 工件核数见 `SESSION-2026-10-16.md`。

| 项 | 一句话 | 落点 |
|---|---|---|
| A4 | 空笔记的安全笔记存盘再读回**静默翻成登录**（备注可空 ⇒ 三字段全空 ⇒ 启发式判登录） | `VPX_SECURE_NOTE` 工具区，读侧三态 |
| A6 | 第三方库里受保护的用户名/备注被编辑一次后**落盘即丢 `Protected="True"`** | `protectedAware` 泛化 `passwordValue`，四标准字段一律走它 |
| A5（驳回） | 「时间戳不刷新」提案被自己的测试 + 上游源码查证推翻：kotpass 全链路已包 | 无代码改动；注释查证记录 + 一条契约护栏测试 |

新增坑 **#169 / #170**（ISSUES 索引已核对一致）。A5 的教训（本轮最贵）：
**「代码里没写」≠「没发生」**——数据类默认值与上游修饰器都是隐形写点；
且**空转测试比没有测试更糟**（判别标准：回退修复，测试必须变红）。

### 下一步

1. **W5**（JSON⇄KDBX 无损互转：转换器本体 + 入口 UI）—— 最大块未做项，顺序仍是
   **先做转换器本体**（清晰契约 + 可测结果），UI 是薄壳；
2. W6 真机互操作验证（KeePassXC / KeePassDX 打开 Vaultix 写的库）；
3. 审计 B 组剩余项。

---

## ✅ KDBX × Bitwarden 边界审计五条已修完（2026-10-14）· CI `38495406982` 真绿

**commits**：`57fc416`（license 头）→ `de0ab2e`（B6）→ `f2a4e03`（A1）→
`cd99bbb`（A2）→ `a3782ca`（A3）→ `47ca162`（B5）→ `2c5f3eb`（审计文档 + `.ai` 同步）
**实证**：本地全量门禁 1m14s 全绿；CI 工件解出 108 个 XML 重数 **1096 例 0 失败**
（不只看步骤颜色）。审计报告：`kdbx-bitwarden-boundary-audit-2026-10-12.md`。

| 条目 | 一句话 | 落点 |
|---|---|---|
| B6 | KDBX 通行密钥从「读得到、存不下」到读写闭环（私钥仍强制 `Encrypted`） | `ItemRepositoryImpl` 双会话校验 + KDBX 感知 `loadItem` |
| A1 | 收藏从静默 no-op 到读写闭环 | `VPX_FAVORITE` 工具区（`KdbxToolFields` / mapper / writer） |
| A2 | KDBX 文件夹从「四处不可见」到全链路可见 | `observeFolders` 按库分流 + `KdbxSessionFlow` 触发器 |
| A3 | KDBX 库不再能建出「类型是卡、字段全空」的残骸 | 表单裁剪 + 卡包门控 + 仓储硬闸（截导入路径） |
| B5 | #161 文案承诺兑现：「忘记 PIN？」不验任何东西清信封 | `QuickUnlockController` + 锁与安全页新行 |

新增坑 #164–#168（ISSUES 索引已用 `check_issues_index.py` 核对一致）。
共同判据：**「底层能力写好」≠「链路通」** —— 三条断在中间层
（writer 有没人调 / mapper 有没人读 / 表单裁了导入没人拦）。

### 下一步

1. **W5**（JSON⇄KDBX 无损互转：转换器本体 + 入口 UI）—— 最大块未做项，顺序仍是
   **先做转换器本体**（清晰契约 + 可测结果），UI 是薄壳；
2. W6 真机互操作验证（KeePassXC / KeePassDX 打开 Vaultix 写的库）；
3. 审计 B 组剩余项。

---

## ✅ W4 已落地（2026-10-07）· `VPX_` 工具字段 + 多 URL 无损降级

**commit**：`92d62a0`（实现 + 单测）→ `eb410a1`（单测编译修复）→ `d141538`（修两个真 bug）
**CI**：`37612405103` ✅ 真绿 —— `:data:kdbx` 141 例**零失败**（含新增 27 例）

### 解决了什么

W4 之前多 URL 是**静默丢数据**：`applyDomainFields` 只写
`item.uris.firstOrNull()`，KDBX 的 `Url` 只装一条 ⇒ 一条挂 3 个网址的登录，
**改一次标题**就被抹掉 2 个网址。不报错、不提示、文件能正常打开。

### 落法

| 来源 | KDBX 落法 |
|---|---|
| 第 1 条 URI | 标准 `Url` |
| 第 2 条起 | `VPX_URL_n`（n 从 1 起） |
| 每条的匹配档位 | `VPX_MATCH_n`（**含 `n=0`**） |
| `androidapp://` | `App Package Name`（裸包名，读侧兼容 5 个别名） |

### ⚠️ 施工单与实现的三处偏差（都有实证依据）

1. 字段名不是 `AndroidApp` 而是 `App Package Name`（Bastion 实测常量）
2. `VPX_PW_HISTORY` **不做** —— `VaultItem` 无该字段，写进去就再也读不出来
3. 多个应用 URI **只保留第一个** —— `App Package Name` 是单个字段位；
   硬塞进 `VPX_URL_n` 会被 KeePassXC 当域名匹配，等于为多存一条而让**所有**应用 URI 匹配全失效

### 下一步：W5（转换入口 UI + 转换器接口本体）

用户已明确「接口要做好」⇒ 顺序是**先做转换器本体**（清晰契约 + 可测结果），
UI 只是薄壳：`选 BW JSON` → `文件密码` → `输出库主密码` → `预览条目数 + 计数`
→ `SAF 选路径` → 产出；密码**用完即弃**。


> ## ⚡【2026-10-06 已落地 · CI 全绿】JSON⇄KDBX 无损互转 · W3（TOTP 往返验证）
>
> 施工单 W3 落地 `4ea40f5` + `15ff84b`，CI `37444254405` 全绿（detekt + 编译 + 单测 + 编码门禁）。
> **结论：往返本来就是通的，`KdbxTotpCodec` 一行未改** —— 新增
> `KdbxTotpRoundTripTest`（9 条用例 / 11 种otpauth 形态）把「从未验证过」变成「已钉死」。
> - 11 形态主判据：SHA1/256/512 × 6/8 位 × 15/30/60 秒step × HOTP counter × Steam × Yandex × label 含空格
> - Steam base64 密钥（`+/=` 三类 URL 不安全字符）、HOTP `counter=42`、`digits=8`/`period=60` 不被默认兜底
> - 幂等（未改 TOTP 不重写）、otp 不泄漏进 `customFields`、清空会真删字段
>
> **断的是 `OtpUriParser.parse` 之后的 `TotpConfig` 各分量，不是字面串** —— 两种合法写法之间
> 比字符串会误报，真正的数据丢失反而可能放过。**纯新增测试，未动任何生产代码。**
>
> ⚠️ 过程中两次返工的教训（都写进了提交信息）：
> ① 沙箱无 SDK 时**不能凭记忆改解析语义** —— 我一度想"修"位置式 `TOTP Settings` 里的
>    HOTP counter，但既无 KeePass 格式佐证、既有测试也明确断言 `counter == 0L`（有意设计），
>    遂回退。**没有证据就不改语义。**
> ② **CI 的对勾不等于单测通过**：单测步骤标了 non-blocking，`37443116170` 显示 `✓ main`
>    而 `:data:kdbx:compileDebugUnitTestKotlin` 其实 FAILED（漏 `getEntry` 扩展的 import）。
>    ⇒ 判定必须落到具体任务行。
>
> ⏳ 下一步 = **W4（`VPX_` 工具字段 + 多 URL 降级）**；W5 入口 / W6 真机互操作待真机。

> ## ⚡【2026-10-06 已落地 · CI 全绿】JSON⇄KDBX 无损互转 · W2（通行密钥写回）
>
> 施工单 W2（= HANDOFF 第 5 项）落地 `569c398`，CI `37348461753` 全绿（detekt + 编译 + 单测 + 编码门禁）。
> - `KdbxPasskeyCodec.fromCredential` 覆盖 **10 键**（补 `RP_NAME`/`USER_DISPLAY_NAME`/`CREATION_DATE`，
>   与 bw2keepass 并集）；多凭证第 n 条带 `_n` 后缀，`isPasskeyFieldName` 后缀感知
> - 读侧 `toPasskeyFields` → `passkeyCredentials` 返回**列表**（修「一条登录挂 2 个通行密钥只读出一个」）
> - 写侧 `KdbxItemWriter.applyPasskeys` 专属通道；私钥 PEM / credentialId / userHandle 强制 `Encrypted`
>   单测 `KdbxPasskeyWritebackTest`（既有 `KdbxPasskeyCodecTest` 未动）。
> ⚠️ 已知不保真：`FLAG_BE`/`FLAG_BS` 领域模型无此二位 ⇒ 写回取默认值，W6 真机需确认。
> ⏳ 下一步 = **W3（TOTP 往返验证）→ W4（`VPX_` + 多 URL）**；W5 入口 / W6 真机互操作待真机。

> ## ⚡【2026-10-05 已落地 · 待 CI + 真机验】JSON⇄KDBX 无损互转 · W1（修现网数据毁 bug）
>
> 施工单 [`Docs/progress/json-kdbx-lossless-conversion.md`](./json-kdbx-lossless-conversion.md)（W1–W6）。
> W1（自定义字段 `Title`/`Url` 覆盖标准键 + URL 白名单大小写 `"URL"`→`"Url"`）已落地：
> 新增 `KdbxFieldKeys` 单一真源（`isReserved` 大小写折叠判区）+ `KdbxItemWriter.applyCustomFields` 写回前 R1 跳过
> + `KdbxItemMapper.customFieldsOf` 改走 `KdbxFieldKeys.isReserved`。单测 `KdbxFieldKeysTest` / `KdbxReservedFieldTest` 落到 `:data:kdbx` test 源集。
> ⚠️ 沙箱无 Android SDK ⇒ 未本地编译，靠 CI（`:data:kdbx:testDebugUnitTest`）+ 真机验；`check_compile_smells` / `check_orphan_strings --gate` 全绿。
> W2–W6（通行密钥写回 / TOTP 往返 / `VPX_` 多值 / 转换入口 / 真机互操作）待做。

> ## 🔧【2026-10-02 完成（待真机验收）】双库健康度审计 · 批次 A —— 七个「报了成功、其实没有」的洞
>
> **起因**：不为缺功能，而是逐个追问「每个返回成功的点，凭什么算成功」。 ⇒ ISSUES **#153–#159**。
> ① KeePassXC 的 TOTP 条目**恒为 `000000`**（算法名不归一化）② `period=0` 让验证码页**整页崩**
> ③ 编辑一次 ⇒ 组织条目**本地降级为个人条目** ④ 离线「新建 → 编辑」⇒ 那笔编辑**静默丢弃**
> ⑤ KDBX 软删/永久删**谎报成功** ⑥ 主写路径**绕过往返自检**（KDoc 承诺的兜底入口零调用点）
> ⑦ 防截屏只挂 1 个 Activity、**另外 9 个全裸**。
> **本地实证**：detekt 19 源集 0 违规（探针自证 27151）· 六个 `check_*.py` 全绿 · 孤儿串 118 < 基线 120
> · TOTP 独立 JVM 工程跑 RFC 6238 向量 27 条断言全通。
> **CI 实证**（run `36950743186`，head `f2a9438`）：`e:` **0** · **BUILD SUCCESSFUL ×3** ·
> `:core:common:testDebugUnitTest` **真执行**（非 FROM-CACHE）⇒ 新增 13 条断言真的跑了。
> ⚠️ 沙箱无 Android SDK ⇒ 编译与单测这一半只能靠 CI（`#137`）。
> ⏳ **下一步 = 真机验收**（四项，见 `SESSION-2026-10-02.md` §6）⇒ 之后 **W0 剩下的云一半（新建 KDBX 上云）**。
>
> 💡 **本轮顺带才发现：`.ai/ISSUES.md` 的索引已漂到第四次**（→ #160）。以前那个约定
> 「改哪篇就顺手改那篇的数字」**已被证明失效四次**，改成 `.ai/tools/check_issues_index.py` 全量重数。


> ## ⚡【2026-09-30 凌晨 完成】批次 B1 —— KDBX 远端文件**本地缓存 + 条件回源**（解锁提速）
>
> **定稿**：[`.ai/decisions/多库锁模型-定稿.md`](../../.ai/decisions/多库锁模型-定稿.md) §5「批次 B」（含**方案为何改版**的证据）
> **改动**：`KdbxFileCache` / `FileKdbxFileCache`（带字节数校验，防半截文件）/ `CachedKdbxFileSource`（装饰器）
> + 挂载在 `KdbxCloudSyncCoordinator.fileSourceFor`（唯一闸口 ⇒ 解锁/校验/keyfile/同步全覆盖）
> + `noBackupFilesDir/kdbx_cache` 落盘 + `signOut`/`removeVault` 按 origin 清。
> **门禁（分开单跑）**：detekt ✅ · `:app:compileFullDebugKotlin` ✅ · app **289** + repo **127** = **416 / 0 失败**。
> **变异验证**：④ 漏掉 `token != null` ⇒ 1 红；⑤ 命中忘了 `return` ⇒ 1 红。
> ⏳ **下一步 = B1 真机验收**（装新包 + 解锁一次）：目标 KDBX 解锁 **3.58 s → ≈1.2–1.5 s**；
> 之后决定要不要做 **B2**（信封包派生密钥，再吃掉那 1.2 s）。
> 再之后 = **批次 C**（管理页 UI：§7 单选区 + D7 解锁方式行）。
> ✅ **已合入 `rele` 并推送 ⇒ 发布中 `v0.7.0`**（run `36608137362`；`main`/`rele` 同在 `a072f17`）。
> ⚠️ 升版本是**必需**的：versionCode 由版本名推导（0.6.0=6000），不升则用户收不到更新提示。

> ## 🔒【2026-09-30 凌晨 完成】多库锁模型 **批次 A 第二片**（D3 + §3.2 档位侧）落地 ⇒ **批次 A 全部完成**
>
> **定稿**：[`.ai/decisions/多库锁模型-定稿.md`](../../.ai/decisions/多库锁模型-定稿.md) §5「施工进度」
> **本片内容**：D3 档位**每库一份**（`vault_timeout::<vaultId>`，**惰性迁移**——旧全局键永不删除，
> 作为「未写过显式档位的库」的回退）+ §3.2 档位侧（`restore()` 只恢复 `Never` 档房间）。
> 调用侧改齐：`VaultLockManagerImpl` / `AutoRestoreTrigger`（`anyVaultNever()` 按库聚合）/
> `AutofillActivity`（判据下沉 restore）/ `SettingsViewModel`（读写**活跃库**档位）。
> **门禁（分开单跑）**：detekt ✅ · `:app:compileFullDebugKotlin` ✅ · app **289** + repo **116** = **405 / 0 失败**。
> **变异验证**：D1/D2/D3 三次注入各「恰好一条」变红，还原后全绿。
> **下一批 = B（KDBX 信封改包派生密钥 ⇒ 解锁提速）**，随后 C（管理页 UI：§7 单选区 + D7 解锁方式行）。
> ✅ **已提交** `7a29abe`（`main`，**未推送**；⚠️ `rele` 未动）。

> ## 🔒【2026-09-29 深夜续 完成】多库锁模型 **批次 A 第一片**（D1 + D2 + D5 + §3.2）落地
>
> **定稿**：[`.ai/decisions/多库锁模型-定稿.md`](../../.ai/decisions/多库锁模型-定稿.md) §5「施工进度」
> **本片内容**：D1 每库「用户主动锁」标记（`lockVault` 去掉删全局信封的跨层副作用，改 `markUserLocked`；
> `restore()` 逐库跳过；解锁成功钩子统一清标记）· D2 切后台给**每个已解锁库**各起计时器、回前台**撤全部** ·
> D5 补开挪到**进程级 scope**（修 `Job was cancelled` ⇒ 多余第二次指纹）。
> **门禁（分开单跑）**：detekt ✅ · `:app:compileFullDebugKotlin` ✅ · app **289** + `data:repository` **115** 全过（**404 / 0 失败**）。
> **变异验证**：`restore` 去跳过 ⇒ 1 红；`onAppBackgrounded` 退回活动库 ⇒ 1 红（均恰好一条，还原后全绿）。
> **下一片 = D3**（档位每库存储 + 惰性迁移 + 设置页每库入口 + §3.2「非 Never 房间不恢复」），随后 B、C。
> ✅ **已提交** `7a29abe`（`main`，**未推送**；⚠️ `rele` 未动）。

> ## 🔒【2026-09-29 深夜 完成】多库锁模型定稿（D1–D7）+ 文档瘦身执行 —— 下一批 = 定稿批次 A
>
> **定稿（自包含施工单）**：[`.ai/decisions/多库锁模型-定稿.md`](../../.ai/decisions/多库锁模型-定稿.md)
> —— 一句话模型「一栋房子，一扇门，N 个房间；任何锁只动自己那一层」；施工批次 A（D1→D2→D3→D5）→ B（KDBX 信封提速）→ C（管理页 UI）。
> **实测依据**：BW 33ms vs KDBX 3.58s（信封 payload 不对称）；秒切已证；「又要验证」真凶 = Honor `iAwareF` 杀后台（非锁屏钩子，Bitwarden 源码核证）。
> **新坑**：#131–#134（04 篇 ×3 + 01 篇 ×1）。
> **文档侧**：8.9 分篇约定开始执行 —— README 状态区 401→131 行；本文件为**最大存量欠账（198KB）**，
> 已完结登记应逐轮下沉当日 SESSION（清单见 [8.9 §6](../../.ai/conventions/8.9-文档分篇.md)）。
> **本轮零行为变更**（唯一代码改动：`VaultTimeout.kt` KDoc 纠错 + compile 验证）。

> ## 🔒 【2026-09-29 完成】批次 5.0 收工 —— 软锁 + 前台门禁 + 解锁提速
>
> **施工工作单**：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)（批次 5.0 专节，自包含）
> **定稿实施记录**：定稿 **§6.2**（含软锁/硬锁对照、前台门禁、提速与 Bitwarden 对照）。
> **触发**：用户真机反馈 —— ①「设置**从不**，锁屏/清后台后 Vaultix 直接就是开着的，
>   有风险」（对照 Bitwarden 会锁）；②「指纹解锁进密码库要等好几秒，应秒解秒进」。
>   用户拍板**方案 A**：对齐 Bitwarden —— 离开 App 就真锁，回来靠恢复信封免交互自动开。
> **① Never 档不再「从不锁」**：`VaultLockManagerImpl` 旧 `Never -> return@launch`
>   改为 `AppBackgrounded` 时 `AutoUnlockRepository.softLock()` —— 清房钥匙 + 收两条
>   会话模型，**保留信封**（回来自动开的凭据）。
> **② ★★ 前台门禁（安全底线）**：软锁把 `houseKeyInMemory` 翻 false，而
>   `AutoRestoreTrigger` 正 observe 它 —— **没门禁会立刻把钥匙读回内存、等于没锁**。
>   故新增 `AutoLockController.isForeground` 作 `combine` 第三源，**非前台一律不恢复**。
> **③ 指纹提速两层**：候选库改「一次读范围快照」消除 N 次串行 Keystore 往返；
>   `completeLocalUnlock` 拆「先开核心库放行 → 异步补开其余库」。
> **★ 核心洞察**：软锁 vs 硬锁 = **信封的留与删**（软 = 留、硬 = 删）；旧 bug 根因 =
>   Never 档从不软锁 ⇒ 密钥常驻内存。
> 门禁：detekt / `:app:compileFullDebugKotlin` / 单测 **398 全过 0 failed**
> （`data:repository` 113 + `app` 285）+ 孤儿串未超基线。
> **下一步 = 批次 5（真机验收清单 1-11，需真手指）**：⭐ 新增第 10 条（Never 档离场软锁 +
> 回来自动开）、第 11 条（多库指纹秒进）。

> ## 🏠 【2026-09-29 完成】快速解锁「房子化」批次 4 收工 —— 失效矩阵（定稿 §6）
>
> **接力文档（下一会话从这里进，自包含）**：[`house-rework-batch4-handoff.md`](house-rework-batch4-handoff.md)
> **施工工作单**：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)（批次 1-4 ✅，5 未动）
> **定稿实施记录**：定稿 **§6.1**（含决策理由与与 Bitwarden 的有意偏离）。
> **本轮四条目标全部落地**：
> ① **rearm** —— ⚠️ 真实形态是「**延迟重装**」而非「静默重包」：硬约束 #2（auth-per-use
>    一次授权只保一次 `doFinal`）⇒ 重写门锁信封**必须**再弹一次认证。故 =「失效时只打标记
>    （`house_lock_fingerprint_rearm_pending`）→ 下次认证时补写新信封」。用户拍板
>    「可以接受重新安装」。
> ② **降级** —— `UnlockRecoveryRepositoryImpl.degradeFingerprintLock()` → 禁锁 + 三选一
>    明确文案 + 回主密码（**绝不静默**）；无指纹信封时 no-op。
> ③ **StaleCredentials** —— `RoomResealRepositoryImpl.resealRoom(vaultId, newMasterPassword)`
>    复用 `LocalUnlockEnrollment` 只重包**该房间软件信封**，门锁不动。
> ④ **PIN 熔断全局 5 次** —— 批次 1 已达成（N 信封 → 1 门锁信封 ⇒ 计数天然全局）。
> 另新增失效三态分类 `LocalUnlockFailureKind { Recoverable, Rearmable, Unavailable }`。
> **★ 最重要的判据**：开门 / 关门的唯一正确判据是 `HouseKeyStore.isUnlocked`（不是
> 「信封存不存在」）—— 误用后者会把 rearm 走成降级、**连带清掉房间信封**（用户丢库）。
> 门禁：detekt / `:app:compileFullDebugKotlin` / 单测 **377 全过 0 failed**
> （`data:repository` 104 + `app` 273）+ 孤儿串未超基线。
> **下一步 = 批次 5（真机验收清单 1-9，需真手指）**：⭐ 新增重点是第 4 条（重录指纹后
> 开门态 rearm / 关门态降级）与设置页「需要重新启用」副标题。

> ## 🏠 【2026-09-29 深夜续】真机验收反馈修复收工 —— 「从不锁定」对齐 Bitwarden + 解锁方式三行精简（✅ 已被批次 4 接续）
>
> **接力文档**：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)（批次 3.5 专节，自包含）
> **触发**：用户真机装 debug 版验收（批次 5 提前反馈）——「从不锁定下划掉后台后填充不及时，
> 对标 Bitwarden」+「解锁方式三行冗余」。
> **本轮**：① ★★ **硬约束 #1 修订（定稿级）**：「房钥匙绝不落盘」→「绝不以**明文**落盘」；
> Never 档对标 Bitwarden `userAutoUnlockKey`（`reference/bitwarden/` 源码核证）落地为
> 第三把锁 `AutoUnlockKeyStore` + `AutoRestoreTrigger` 协调器（主动锁库删信封=真锁；
> 解锁成功事件幂等重建）② autofill 双管齐下：`buildResponse` 1s 恢复等待窗口 +
> `ItemRepositoryImpl.observeItems` 改 Eagerly 共享缓存（冷流每次全量重解密=卡顿主源）
> ③ 设置页三行→两行：行点击=进向导（`manageBiometric`/`managePin`），开关=On 关/Off 进向导
> ④ `HouseKeyStoreTest` +3 条 auto 信封用例。
> 门禁：detekt / compile / 单测（app + data:repository `--rerun-tasks`）全绿 + 孤儿串未超基线。
> **下一步 = 批次 4（失效矩阵：rearm / 降级 / StaleCredentials 重包房间 / PIN 熔断全局 5 次；
> auto 信封失效分支已做已测）** → 批次 5（真机验收，清单已扩到 9 条，新增 ⑧ Never 档
> 填充及时性 ⑨ 设置页两行交互）。

> ## 🏠 【2026-09-29 深夜】快速解锁「房子化」批次 3 收工 —— 设置页简化「删五类」（✅ 已被真机验收修复轮接续）
>
> **接力文档（下一会话从这里进，自包含）**：[`house-rework-batch3-handoff.md`](house-rework-batch3-handoff.md)
> **施工工作单**：[`quick-unlock-house-rework.md`](quick-unlock-house-rework.md)（批次 1-3 ✅，4-5 未动）
> **本轮**：① 删 `Partial` 三态（开关收敛为**二值** = 门锁存在性）
> ② 删向导里每库「指纹 / PIN」角标（范围列表变纯复选框）
> ③ 结果页成功/跳过改**计数**（「已纳入 N / 跳过 M」），**失败仍逐条列**
> ④ 副标题改定稿 §5.1 文案（PIN 那句的两个数字由 `PIN_MIN_LENGTH` / `PIN_MAX_ATTEMPTS` 传入，不写死）
> ⑤ **顺带解决批次 2 遗留 #1**：把 `lockState` / `locksToOpen` / `roomSealingBlocker` / `assemble`
> 挪到文件级 `internal` 纯函数 ⇒ `QuickUnlockControllerTest` 7 条改写为 **17 条**，
> 动作表判定终于有行为级证据。
> 门禁：detekt / `:app:compileFullDebugKotlin` / 单测 **328 全过 0 failed** + 孤儿串门禁未超基线。
> **下一步 = 批次 4（失效矩阵：rearm / 降级 / StaleCredentials 重包房间 / PIN 熔断全局 5 次）**
> → 批次 5（真机验收 1-7）。
> ⚠️ 遗留：**整条编排流程仍无自动化证据**（只有判定逻辑被钉住）⇒ 真机验收必做。
