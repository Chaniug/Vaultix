# SESSION-2026-11-08-part3 · 筛选区空白：拆掉 #182 补偿链

> 单主题单篇（[8.9](./conventions/8.9-文档分篇.md) §7）。本篇只讲「展开筛选时回到列表顶部」
> 开关打开后 chips 与首项之间凭空多出空白；同批的同步分派 / 反馈 / 进度组件见
> [part4](./SESSION-2026-11-08-part4.md)，前两批（✕ 移除 / 底栏位移）见
> [主篇](./SESSION-2026-11-08.md) 与 [part2](./SESSION-2026-11-08-part2.md)。

## 0. 一句话结论

**不是补两行能救的。** #182 那套「外壳下移 + 列表让位 + 逐项绘制补偿 + 滚动锚点」
四件套，在用户否决「偷偷回顶部」并加了开关之后就失去了存在理由；而它依赖的滚动锚点
在 Compose 里**本来就不是能可靠设定的值**。⇒ 整条拆掉，让位只走
`contentPadding.top`，全页写 `listState` 的位置收口到唯一一处且必须过偏好门控。

## 1. 现象 / 需求

用户 11-08 反馈：打开「展开筛选时回到列表顶部」后切换筛选类型，
筛选区域与第一条卡片之间出现「莫名其妙的空白」，并怀疑「筛选区域整体可能存在问题」。
四张截图：全部 / 验证码正常，通行密钥 / SSH 必现（SSH 只有 1 条）。
明确要求「仔细看看，给出修复优化建议，给出合理的解决方案」；
在助手给了 A/B 两案后拒绝做选择题：「你再认真看看给我答案」。

## 2. 根因 / 约束（为什么只能整条拆）

### 2.1 空白正好是 56dp

10-24 的 #182 为了让「往下滑之后展开面板、画面一像素不跳」做了四件套，
四项凑齐时首项净位移为 0：

| 环节 | 代码 | 位移 |
|---|---|---|
| 外壳下移 | `ItemsPullToRefresh(padding(top = reveal))` | +56dp |
| 列表让位 | `contentPadding.top = listTopInset + reveal` | +56dp |
| 绘制补偿 | 每个 item `graphicsLayer { translationY = -reveal }` | −56dp |
| **滚动锚点** | `scrollToItem(0, anchorPx)`，anchorPx = 56dp | **−56dp** |

清掉第四项的有两处，都写同一个 `listState`：

- `rememberListEdgeHolder`：`holding` 已为 true 时切筛选走 `else`，
  直接 `listState.scrollToItem(0)`，**显式丢掉 anchorPx**；
- `rememberFilterBarCollapse`：`LaunchedEffect(filter) { listState.scrollToItem(0) }`
  **每次切筛选无条件执行**。

⇒ 三项还在、唯独滚动 −56 没了，首项净下沉 56dp，加上原本 8dp 呼吸缝就是那条空白。
与截图顺序逐张对得上：首次切换还能保住锚点且多等一帧（所以「验证码」正常），
第 2 次起 `holding=true` 必走 `else` ⇒ 必现且一直挂着。

### 2.2 决定性证据：短列表根本锚不住

解项目锁定的 `foundation-1.11.4-sources.jar`：

> `LazyListScrollPosition.kt` KDoc：`scrollToItem` 的请求值只是**下一次测量的起始位置**，
> 会被测量算出的真实位置覆盖，「**there is no guarantee that exactly this index and offset
> will be applied**」，其中一种情况就是「not enough items to fill the viewport after the
> requested index」。
> `LazyListMeasure.kt:243-260`：内容没填满视口时走 `toScrollBack`，把
> `currentFirstItemScrollOffset` 减回去，最终钳到 0。

⇒ `scrollToItem(0, 56)` 要求第 0 项能向上滚出 56dp，条目少于一屏时会被直接钳掉。
图 4 的 SSH 只有 1 条 —— **就算把 `else` 补回 anchorPx，这一页照样空白**。
「最小止血」只能救条目够多的分类，救不了短列表。

### 2.3 结构性缺陷（用户「整体有问题」的直觉成立）

1. **滚动职责没有单点**：两个 `LaunchedEffect` 都写 `listState`；
2. **`holding` 判据错位**：`atTop` 写成 `filter == ItemsQuickFilter.All && ...`，
   拿「筛选是不是全部」冒充「列表在不在顶部」⇒ 开关一开切任一分类，reveal 被永久锁死；
3. **补偿量喂 4 处，漏一处就露馅**：空态 `ItemsEmptyState` 吃含 reveal 的
   `topInset` 却没有 `compensate()`，切到 0 条的分类会额外再沉 56dp；
4. **文档与实现漂移**：#182.1 定稿写「关闭时连切 chip 回第 0 项也不做」，
   但 `91e0684a` 只门控了 holder，第 495 行无条件的 `scrollToItem(0)` 留到今天；
5. 全链路是纯 Compose 布局 / 滚动态，**没有任何 UI 测试**，只能真机验。

### 2.4 为什么这套链现在不该存在

补偿的唯一目的是「偷偷把列表滚回顶部，但画面看不出」。而用户当初否决的正是
「偷偷回顶部」⇒ 加了开关。**开关打开 = 明确要求位置改变**，「画面不跳」这个目标本身
就没了；开关关闭则要求完全不碰滚动位。两种取值下补偿链都没有正当性，留下来的只有负债。

## 3. 做法（改了什么，文件:行）

单文件 `app/src/main/java/io/vaultix/vaultix/ui/items/ItemsScreen.kt`，净删约 137 行。

- **删**：`QUICK_FILTER_TOP_ANCHOR` / `ENTRY_CARD_ESTIMATED_HEIGHT`、
  `itemsShellTopInset`、`rememberListEdgeHolder`（含 `holding` / 两帧等待 / `atTop`）、
  `itemsFilterPanelReveal`；主函数里 `holdingEdge` / `reveal` / `shellTopInset` 三行；
  `ItemsPullToRefresh` 的 `padding(top = reveal)`；`ItemsBody` / `ItemsList` 的
  `scrollCompensation` 参数、`compensationPx`、`Modifier.compensate()` 及三处调用；
  `withFrameNanos` / `LocalDensity` / `roundToInt` 三个已孤儿 import。
- **留**：面板让位只走 `itemsTopInset(searchActive, barPadding, filterRowInset, ...)`
  → `LazyColumn.contentPadding.top`（72dp），首项天然落在 chips 下方 8dp。
- **改**：`rememberFilterBarCollapse` 增加 `jumpToTop` 参数，
  `LaunchedEffect(panelExpanded, filter, jumpToTop)` 里**仅当 `jumpToTop && panelExpanded`**
  才 `scrollToItem(0)`；KDoc 写死「本函数是全页唯一允许写 `listState` 滚动位置的地方」。
  同时补上 #182.1 漏的门控：开关关闭时切 chip 也不再回顶部。
- 拆除位置留了一段 KDoc 说明「为什么整条链不该存在」，避免下轮接力又把它加回来。

行为变化：

| | 改前 | 改后 |
|---|---|---|
| 开关打开、切分类 | 第 2 次起多 56dp 空白；短列表必空白 | 直接回顶，chips 与首项始终 8dp 缝 |
| 开关关闭、切分类 | **仍会回顶部**（#182.1 漂移） | 完全不碰滚动位 |
| 展开面板、已滚到中间 | 补偿链掩盖位移、偶尔失效 | 打开就直接回顶；关闭则原地不动、接受空白（用户选过的取舍） |
| 空态 | 吃含 reveal 的 topInset，额外下沉 | 消失 |

**判据（写进 KDoc）**：位置类状态不要绑成「需要运行时凑齐的等式」——
滚动锚点在 Compose 里是测量结果的副产物，不是可写入的输入。

## 4. 验证

- 本地 9 个静态门禁全绿（清单同 [主篇](./SESSION-2026-11-08.md) §4）；
  无新增 >120 字符行；`SyncNoteBanner` 抽分支后 113 行，低于 `LongMethod ≤150`。
- 不本地编译 / 不出包（约束 #6，沙箱无 Android SDK），交 GitHub Actions。
- **真机待验四条**（交用户）：
  ① 开关打开，连点 全部→验证码→通行密钥→SSH→全部，每页 chips 与第一张卡片始终只有 8dp 缝；
  ② 往下滑几屏再展开面板，面板下方立刻是列表头；
  ③ 开关关闭，往下滑再展开 / 切分类，列表原地不动；
  ④ 切到 0 条的分类，空态不额外下沉。

## 5. CI 结论与指针

见 [part4](./SESSION-2026-11-08-part4.md) §5 —— 三批同一个提交、同一条 CI run，
结论只写一处。

## 6. 未决与指针

- **数值契约单测未落地**：上一轮建议里「给 `QUICK_FILTER_ROW_HEIGHT` 与
  `rememberQuickFilterRowInset` 的数值契约加一条纯函数单测或静态门禁」这一条**没做**。
  理由：`rememberQuickFilterRowInset` 是 `@Composable`（依赖 `animateDpAsState`），
  抽纯函数要动常量可见性、跨文件搬 5 个尺寸 token，与本批「只拆滚动链」的主题不符，
  且本批没有真机回归兜底 ⇒ 不在这轮冒险。已用 KDoc（§3 的判据）替代一半。
  真要做应单独一批，把算式下沉到 `internal` 纯函数再补 JVM 单测。
- 冲突 / 同步 / 进度组件 → [part4](./SESSION-2026-11-08-part4.md)。
- #182 / #182.1 的历史定稿仍在
  [06-界面与交互-part7](./issues/06-界面与交互-part7.md)，其「补偿链」结论已被本篇推翻，
  接力时以本篇为准。
