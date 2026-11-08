# SESSION-2026-11-08-part4 · 同步按库类型分派 + 保存反馈 + 活泼进度组件

> 单主题单篇（[8.9](./conventions/8.9-文档分篇.md) §7）。本篇讲同批的 B / C 两批；
> 筛选区拆补偿链见 [part3](./SESSION-2026-11-08-part3.md)。三批同一个提交、同一条 CI。

## 0. 一句话结论

用户的判断对：**两套同步底层完全不同、也已经分开实现，但 UI / 状态层没有按库类型分派** ——
「⋮ 同步点了没反应」「保存后空窗几秒」「进度条很老实」是同一个缺口的不同表现。
修法按顺序：先分派 → 再把库级上传态做到列表页可见 → 最后才换动画。

## 1. 现象 / 需求

用户 11-08 连提四条：

1. 「kdbx 和 bitwarden 的同步方案应该是存在不同的吧，应该要做区分的吧」；
2. 「kdbx 库状态下，这个 3 个点展开的同步按钮，好像点了没啥反应的样子」；
3. 「新建编辑 kdbx 条目，点击保存后，上传云端的状态好像也不明显，需要在页面等待几秒，
   这个等待的时候没有提醒，没有图标展示，等待完成后才会出现加载条进度和小云图标进度」；
4. 「这个进度条感觉很老实，不够新颖，有其他的进度加载条方案吗，看起来更活泼的那种」。

指令：「上一轮的筛选区按你的建议做吧，另外这一批的也按你推荐的做吧。都做完。
注意观察 github 的 actions」。

## 2. 根因 / 约束

### 2.1 两套方案确实不同，区分做了一半

| | Bitwarden | KDBX 网盘 |
|---|---|---|
| 实现 | `BitwardenSyncOrchestrator` + `BitwardenSyncService` | `KdbxSyncOrchestrator` + `KdbxCloudSyncCoordinator` + `KdbxAutoUploader` |
| 粒度 | 条目级增量（API、`pending_ops`、`cipherCount`） | **整文件条件写**（远端版本令牌、KDF、冲突三选项） |
| 状态源 | `statusByVault`（运行 / 成功 / 失败时间戳、节流退避） | `vaults.syncStatus`（PENDING_UPLOAD→SYNCING→IN_SYNC / FAILED / CONFLICT）+ 缓存脏标记 |
| 云图标 | `CloudSyncIcon`（条目级，KDoc 明确 KDBX 不画） | `DetailSyncIndicator`（库级，仅 KDBX） |

**已经区分的**：首页「密码库」⋮ 同步走 `VaultActionsController.sync()`，按
`vault.kind == KDBX` 分派到 `syncKdbx`（含冲突弹窗）；`resolveSyncDot` 优先读 KDBX
库级状态，null 才回落 Bitwarden 编排器；两种云图标刻意分开。

**没区分的**：条目页 `ItemsViewModel` 只注入 `BitwardenSyncOrchestrator` ——
`retrySync()`、同步横幅、下拉刷新、部分状态点全只看它一个。

### 2.2 ⋮ 同步「点了没反应」是静默空转（`.ai` 已知未修项 B）

```
retrySync() → BitwardenSyncOrchestrator.requestSync(MANUAL, force)
  → process() 无库类型判断 → vaultRepository.syncVault(vaultId)
  → VaultRepositoryImpl:918  if (kind != BITWARDEN) return Unsupported
  → settleOutcomeLocked: Unsupported → 只 updateStatus { isRunning = false }
```

**不写成功、不写失败、不写错误** ⇒ `toSyncNote` 拿不到新时间戳（横幅不出）、
`isSyncing` 只把 `true→false` 闪一下（下拉指示器是 `{}` 静默）、
菜单那颗「同步成功 ●」读 KDBX 库级 `IN_SYNC`（点完纹丝不动）。
三个反馈通道同时沉默。此项原记在
[SESSION-2026-11-07-part2](./SESSION-2026-11-07-part2.md) §「仍未修」B，
当时搁置理由是 `ItemsViewModel` 构造已 9 参数、怕和缓存格式升级混批。
实证 `VaultListViewModel` 构造参数更多且 CI 长期绿 ⇒ 本批直接注入第 10 个依赖。

### 2.3 保存后的空窗：反馈起点太晚，且只在详情页有

- 保存 → `VaultSaveOutcome.Queued` → Snackbar 只有「已保存到本地」
  （`item_saved_queued`），没说上传、没提示要等；
- `KdbxAutoUploader.enqueue()` **立即返回**，真正的整文件上传在后台 `drain()`，
  KDoc 自己写着「上传那 5–60s」；
- UI 侧只有详情页 `DetailHeader` 画 `DetailSyncIndicator` + `DetailSyncProgress`；
  列表页 `SyncNoteBanner` 只跟 Bitwarden 状态走（KDBX 全程不出）、条目行尾
  `CloudSyncIcon` 对 KDBX 恒不画、下拉指示器静默；
- `ItemDetailViewModel.publishSyncStatus` 的 `MIN_UPLOAD_VISIBLE_MS` 是**延长已有状态**，
  补不了「状态还没到」的前置空窗。

⇒ 从表单退回列表的那几秒，整页确实没有任何上传指示。

### 2.4 「老实」的进度条具体是哪个

详情页上传用 `DetailSyncProgress` = 3dp 老式 `LinearProgressIndicator` 直线，
`DetailSyncIndicator` = 普通 `CircularProgressIndicator` 转圈（11-07 定稿原话
「M3 内置不确定进度细条」）。列表页横幅用的其实是波浪
`VaultixWavyProgressBar`，但 KDBX 根本走不到那条分支 ⇒ 用户看到的永远是老实版。

项目锁定的 `material3-1.5.0-alpha16` **已有** Expressive 招牌的变形圆点
`LoadingIndicator` / `ContainedLoadingIndicator`（形状在圆角多边形间 morph + 旋转），
但 8.8 当初因「`polygons` 需新增 `androidx.graphics:graphics-shapes` 依赖」判为不划算。
本次复核：不确定态重载的 `polygons` 有默认值（`LoadingIndicatorDefaults.IndeterminateIndicatorPolygons`），
**不需要新增依赖、不需要传 polygons** ⇒ 8.8 那条结论按实测修订。

## 3. 做法（改了什么）

### 批次 B — 分派 + 反馈

`ItemsViewModel.kt`：

- 注入 `KdbxSyncRepository`；`retrySync()` 按 `_state.value.vault?.kind` 分派：
  Bitwarden 走原编排器，KDBX 走新 `retrySyncKdbx()`。
- `retrySyncKdbx()`：`localChangedSinceLastSync` **现查**（不用 UI 快照，见
  `KdbxSyncRepository` KDoc）→ `sync()` → 结果映射成可见的 `SyncNote`。
  运行态单独一个 `_kdbxManualRunning`，并入 `isSyncing`。
- `syncNote` 改成 **combine 四源按优先级合成**：
  手动同步一次性结果 → `isSyncUploading(vaults[i].syncStatus)` ⇒ `SyncNote.Uploading`
  → Bitwarden 编排器状态。判据 `isSyncUploading` 与详情页共用，不另抄一份。
- `SyncNote` 新增 `Uploading` 与 `Notice(@StringRes resId, detail, warning)`；
  文案走资源 id，ViewModel 不持 Context、中文不进 Kotlin（审计 §3.4）。
- `dismissSyncNote()` 同时清 `_manualSyncNote`，否则三源合成立刻把结果重放出来。
- **冲突不在列表页收敛**：列表页没有三选项对话框，强行收敛会静默丢改动。
  如实提示「请到「密码库」页选择保留哪一版」，不编造「已同步」。

`ItemsScreen.kt`：横幅新增 `Uploading`（变形圆点 + 「正在上传改动到云端…」）与
`Notice`（对勾 / 警告 + 文案 + 警告态「重试」）两态；两个新分支抽成
`SyncUploadingRow` / `SyncNoticeRow`，避免 `SyncNoteBanner` 顶破 detekt
`LongMethod`；自动消失判据与底色判据又抽成 `SyncNote` 的私有扩展属性
（首轮 CI 实测圈复杂度 15 > 14，见 §5）。

`strings.xml`：`item_saved_queued` 从「已保存到本地」改为
「**已保存到本地 · 正在同步到云端**」；新增 `items_sync_uploading`、
`items_sync_conflict_go_to_vaults`。

**刻意没做**：条目行尾 `CloudSyncIcon` 对 KDBX 仍然不画 —— 整文件同步没有
「某一条上云了」的概念，画出来是编造状态。反馈统一走库级横幅。

### 批次 C — 活泼进度组件，收进唯一封装点

`ExpressiveProgress.kt` 从「波浪的唯一封装点」升格为
**「进度 / 等待指示器的唯一封装点」**：

- `VaultixMorphingProgress`：`ContainedLoadingIndicator` 不确定态（变形圆点）。
  ⚠️ 官方内部写死 `.size(48.dp)`（`LoadingIndicatorDefaults.ContainerWidth/Height`），
  传 `Modifier.size()` 改不动 ⇒ 用 `wrapContentSize(unbounded = true)` 让内容无约束测量，
  再 `graphicsLayer { scaleX/scaleY }` 缩到 `indicatorSize`（详情页 24dp / 横幅 32dp），
  布局占位始终是目标尺寸。`@OptIn` 只在这一处。
- `VaultixMorphingProgressBar`：胶囊轨道里高亮块循环扫过（自绘 `drawBehind` +
  `rememberInfiniteTransition`），保住「满宽 + 3dp + 会动」。
  不用官方 `LinearWavyProgressIndicator` 是因为它内部写死 240×10dp
  （[8.8](./conventions/8.8-M3Expressive-采纳范围与顺序.md) 已实测），
  放进标题下的细条位会变成一条撑不满的粗棒。
- `CloudSyncIcon.kt`：`DetailSyncIndicator` 上传态换变形圆点，
  `DetailSyncProgress` 委托给扫光细条（公开签名不变，详情页调用处一行没动）；
  删掉 `Linear/CircularProgressIndicator` 两个老式入口。
- `.ai/tools/check_experimental_optin.py` 符号表登记
  `ContainedLoadingIndicator` / `LoadingIndicator`，自测 7 项仍全绿。

**顺序没有反**：先有 B 的时序与列表页可见，再换动画 —— 否则再活泼的进度条
也只在最后几秒闪一下。

## 4. 验证

- 本地 11 个静态门禁全绿（9 个代码门禁 + `check_md_links` + `check_issues_index`），
  `verify_sync_fields` 通过；`check_experimental_optin --selftest` 7/7；
  `strings.xml` XML 解析通过；`git diff --check` 无空白错误。
- 无新增 >120 字符行；`SyncNoteBanner` 113 行、抽出的两个 Row 15 / 36 行。
- 不本地编译 / 不出包（沙箱无 Android SDK），交 GitHub Actions。
- **真机待验**（交用户）：
  ① KDBX 库 ⋮「同步」点下去立刻有横幅、结束有结果，不再是静默；
  ② 新建 / 编辑 KDBX 条目保存后，退回列表的那几秒能看到「正在上传」横幅与变形圆点；
  ③ 详情页上传态的变形圆点不挤开「类型徽标 / 能力图标」那一行；
  ④ 扫光细条在深色 / 动态取色下仍可辨；
  ⑤ Bitwarden 库的下拉刷新与同步横幅行为与改前一致。

## 5. CI 结论与指针

三批同一个代码提交 `21d47336`；detekt 修复单独一个提交 `b46e7355`。分支 `main`，`rele` 未动。

| run | 结论 | 说明 |
|---|---|---|
| [`42449552919`](https://github.com/Chaniug/Vaultix/actions/runs/42449552919)（`21d47336`） | **failure** | `:app:detekt` 挡下 `SyncNoteBanner` 圈复杂度 **15**（上限 14），`Analysis failed with 1 issues`。后续编译 / 单测被 skip，**这一轮不构成编译结论**。 |
| [`42450232589`](https://github.com/Chaniug/Vaultix/actions/runs/42450232589)（`b46e7355`） | **success** | 全链路绿，见下 |

**最终结论**：`Android CI (Debug)` run `42450232589` `conclusion=success`
（05:18:09 → 05:22:31，约 4m22s）。单个 job `Lint, test diagnostics, and debug build`
共 33 步全绿：`:app:detekt` `BUILD SUCCESSFUL in 25s`、Debug 编译
`BUILD SUCCESSFUL in 2m`、单测硬门禁 `TEST_EXIT=0`。
⚠️ 日志未打印单测**总例数**，不写数字。

⇒ 本批新增的 Compose 代码（`ContainedLoadingIndicator` 不确定态重载、
`wrapContentSize` + `graphicsLayer` 缩放、`drawBehind` 自绘、`combine` 四源、
`@StringRes` 数据类）经**真编译器**通过，不只是本地静态门禁绿。

- `AI docs consistency` run `42449552978`（与首轮代码提交同批）`success`；
  本篇、[part3](./SESSION-2026-11-08-part3.md)、8.8 与 README 修订推送后
  由 `ci-docs.yml` 单独再跑一轮。
- preview：`tag=preview` → `Vaultix v0.11.2-dev-b46e735 · Preview`，
  `app-full-debug.apk`（35.6 MB）上传成功；稳定版 `v0.11.2` 未动。
- ⚠️ **首轮红灯的教训**：本地 11 个静态门禁**没有一条算圈复杂度**，detekt 才是唯一真源。
  往 `SyncNoteBanner` 这类多分支横幅加分支时，先把「是否自动消失 / 是否警告底色」
  这类判据外移成扩展属性，别让主体函数吃到复杂度预算。

## 6. 未决与指针

- 已知未修项 **B 已销**（§2.2）；同篇的 **C**（一次远端拉取约两次整库下载 +
  两次 KDF 导致偏慢）**仍未修**，需要单独一批加真机计时。
- 列表页冲突仍靠「去密码库页拍板」；若用户要求列表页也能三选一，
  需把 `VaultActionsController.Dialog.Conflict` 的 UI 提到共用层，属独立一批。
- 数值契约单测未落地，理由见 [part3](./SESSION-2026-11-08-part3.md) §6。
- 8.8 中「未用 `LoadingIndicator` / `ContainedLoadingIndicator`」已按本批实测修订。
