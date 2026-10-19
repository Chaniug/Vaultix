# 诊断报告 · UI 设计规范审计（按钮 / 布局 / 细节）

> **状态**：诊断完成，**未动代码**（沿用 2026-09-28「先要诊断报告」的惯例）。
> **范围**：`app/src/main/java/io/vaultix/vaultix/ui/**`（单模块，22 个子包）+ `res/values`。
> **方法**：全目录脚本化统计（`*.kt` 逐行扫描，剔除注释与注释块）+ 逐处读源码确认；
> 每条结论都附**可复算的数字**与**文件:行号**。凡是我核过、确认**不是**问题的项，
> 单列在 §6 —— 避免把"一致"误报成"不一致"。
>
> **一句话结论**：
> 1. **最贵的不是某一处难看，是同一件事有 3 套规格**：主操作按钮 **3 种填充色 × 2 种高度**、
>    顶栏 **4 种**、卡片圆角 **5 档**、空态 **5 套**。这是 `.ai/conventions/8.4-UI·观感.md`
>    自己写下的判据：「**一屏里出现两套规格 = 观感廉价的首要来源**」。
> 2. **有一条真 bug 级的布局错位**：`VaultListScreen` 的列表底部让位写死 `96.dp`，
>    真实需要 **86dp（底栏）+ 手势条**——而且该页是**首个落地页**，4 键手势机下面那条
>    永远点不到（§2.1）。
> 3. **`Spacing` 这套标尺已经被绕过**：458 处走标尺，**205 处**手写 `.dp` 字面量，
>    其中 `18.dp` **26 次**（23 个图标 + 3 个常量）、`20.dp` **23 次**（内边距/间距/圆角各一摊）。
> 4. **三处"文档说得比代码做的多"**：`EntryCard` 圆角（文档 12dp / 代码 16dp）、
>    `Docs/08 §4` 的三档响应式（代码只有一刀切 600）、中英双语（`res/` 无 `values-en`）。

---

## 1. 基线数据（先看这张表，后面的判断都从这里来）

| 维度 | 实测 | 备注 |
|---|---|---|
| 间距走标尺 | `Spacing.*` **458 处**（sm/lg/md 为前三，509 处原始命中里含 import 与 KDoc） | `theme/Spacing.kt` |
| 间距手写字面量 | `.dp` **205 处 / 36 个不同值**（剔除注释与 import 后的真·代码口径；含注释是 233） | §2.2 频次表 |
| 按钮 | `TextButton` **134** · `IconButton` **99** · `Button` **39** · `FilledTonalButton` **18** · `OutlinedButton` **15** · `FAB` **11** | 全 `ui/` 目录 |
| 按钮显式高度 | 只有 **6 处**；其中 **48/56 两种值**，且同一种按钮在 4 处写 `48.dp` 字面量、2 处写常量 `BUTTON_HEIGHT` | §2.3 |
| 图标尺寸 | `size(18.dp)` **23 处 / 9 个文件** · `20.dp` 8 处 · `22.dp` 4 处 · 其余各 1~2 处 | 无任何 `IconSize` 常量 |
| 卡片圆角 | **12 / 16 / 20 / 28 / 50%** 五档并存 | §2.4 |
| 顶栏 | `VaultixExpressiveTopBar` **6 页** · `VaultixSearchTopAppBar` 4 · 官方 `TopAppBar` **10 页** · `LargeTopAppBar` **2 页** | §2.5 |
| 空态 | **5 套独立实现 + 卡包内联 1 套**（标题/正文样式、边距全不同） | §2.6 |
| 无障碍 | `Role.Tab` **0 处** · `semantics { selected }` **0 处** · 8 处 `clickable` 无 `role` | §3.1 |
| 字体缩放 | 全项目读 `fontScale` **0 处**；`maxLines = 1` **34 处**；固定 `height(...)` 容器 8 处 | §3.2 |
| 国际化 | `strings.xml` **911** 条，`values-*` 只有 `values-night`（**无 `values-en`**）；Kotlin 里**用户可见**中文硬编码 **72 处** | §3.3 |
| `@Preview` | **0 处** | §3.4 |
| 硬编码颜色 | 98 处，其中 **95 处合理**（品牌色板 69 + 银行卡组织色 26），**3 处**是页内私有品牌色 | §6 |

---

## 2. 页面布局与按钮（用户点名的前两项）

### 2.1 🔴 真 bug：库列表的底部让位是魔法数，且**小于**实际需要

`ui/vaultlist/VaultListScreen.kt:192-197`

```kotlin
contentPadding = PaddingValues(
    start = Spacing.lg, end = Spacing.lg, top = Spacing.sm,
    bottom = 96.dp,            // ← 魔法数
)
```

而底栏的真实占位是 **86dp + 手势条**：

`ui/shell/VaultixBottomDock.kt:68-87`

```kotlin
private val DockBarHeight = 60.dp
val BottomDockOccupiedHeight: Dp = DockBarHeight + 6.dp + 20.dp      // = 86dp
fun rememberBottomDockInset(): Dp =
    BottomDockOccupiedHeight + WindowInsets.navigationBars...calculateBottomPadding()
```

⇒ 4 键手势机（手势条约 32dp）真实需要 **≈118dp**，这里只有 **96dp**。
`VaultixBottomDock.kt:75` 的 KDoc 原话就是「**最后一条会被胶囊压住**，所以各页列表必须把它算进
`contentPadding` 的底部」—— 这一页照抄了结论、漏了公式。

**为什么这条最该先修**：`VaultListScreen` 是 `VaultListRoute`（`VaultixApp.kt:232`）的落地页，
`onSwitchVault` 也回这里；而其他四个列表页全部是对的（`Spacing.sm + bottomInset`，
见 `ItemsScreen:1338` / `TotpCodesScreen:417` / `CardWalletScreen:213` / `PasskeysScreen:318`）。

> 修法一行：`bottom = Spacing.sm + rememberBottomDockInset()`。
> ⚠️ 别只把 96 改成 118 —— 手势条高度随机型/横竖屏变化，写死必然在别的机型上再错一次。

### 2.2 🟠 `Spacing` 标尺被大面积绕过，且**已经事实上出现了新档位**

`.ai/conventions/8.4` 的第 2 条纪律是「不在标尺上的值保持字面量」，理由是"强行对齐会改变既有版式"。
问题是这条纪律只约束了"别改"，没有约束"别继续加"——于是代码里长出了 **205 处 / 36 个不同值**：

| 值 | 次数 | 文件数 | 实际身份 |
|---|---|---|---|
| `18.dp` | **26**（23 处 `size(18.dp)` 图标 + 3 处常量） | 11 | 已经是**图标标准尺寸**，但没有统一的名字 |
| `2.dp` | 24 | 15 | `strokeWidth`，合理 |
| `20.dp` | **23** | 14 | 内边距 / 间距 / 进度圈，**三种语义混用** |
| `0.dp` | 20 | 8 | 合理（"没有间距"） |
| `6.dp` | **20** | 12 | 底栏上留白 / 圆角 / 行距，三种语义混用 |
| `48.dp` | **11** | 8 | 触摸目标 / 按钮高 / 图标，三种语义混用 |
| `28.dp` | 10 | 9 | 圆角（对话框）/ 内边距 / 图标槽 |

> 口径：剔除块注释、行注释、`import` 后的代码字面量（脚本 `205 处 / 36 个值`）。
> 含注释的 `grep` 粗口径是 230 — 两者差异全在 KDoc 里引用的数字，不影响结论。

最典型的是**设置页那一组**，`ui/settings/SettingsComponents.kt:79-96`：

```kotlin
private val SETTINGS_CARD_CORNER   = 20.dp   // 圆角
private val SETTINGS_ROW_MIN_HEIGHT = 72.dp  // 行高
private val SETTINGS_ICON_GAP      = 18.dp   // 图标↔文字
private val SETTINGS_ROW_PADDING_H = 20.dp   // 行内边距
private val SETTINGS_ICON_BOX      = 28.dp   // 图标槽
```

**五个值，五个都不在 8dp 标尺上**（20/72/18/20/28），KDoc 都注着"（上游 X dp）"——
这是从 Bastion 平移过来的整套规格，平移时没有做标尺化。结果：
设置页行内边距 20dp、条目卡片内边距 16dp（`EntryCard.kt:57` 的 `CARD_PADDING = Spacing.lg`）、
删除确认行 `Row` 内边距 16dp（`VaultListScreen.kt:504`）——**同为"列表行"，三种内边距**。

> **建议口径（不是让你现在改）**：真问题不是"18 不在标尺上"，而是
> **同一个语义在不同页面取了不同值**。所以优先级应该是：
> ① 先给"图标尺寸 / 卡片内边距 / 行最小高"三个**语义**定常量（值可以先沿用现状）；
> ② 再把散落各处同语义不同值的收敛到那一个常量；
> ③ 最后才谈要不要把 18 挪到 16 或 20（那是设计变更，要单独一轮）。

### 2.3 🟠 主操作按钮：**3 种填充色 × 2 种高度 × 2 种宽度**

| 位置 | 组件 | 填充 | 显式高度 | 宽度 |
|---|---|---|---|---|
| `common/FullScreenDialogShell.kt:531` | `Button` | **filled** | 无（M3 = 40dp） | wrap |
| `settings/CloudAccountsScreen.kt:580`（WebDAV「测试连接」） | `Button` | **filled** | `BUTTON_HEIGHT` = 48dp | fillMaxWidth |
| `unlock/UnlockScreen.kt:281 / 475` | `FilledTonalButton` | **tonal** | `48.dp` | fillMaxWidth |
| `addvault/AddVaultScreen.kt:242` | `FilledTonalButton` | tonal | `48.dp` | fillMaxWidth |
| `addvault/AddCloudVaultScreen.kt:297 / 477` | `FilledTonalButton` | tonal | `BUTTON_HEIGHT` = 48dp | fillMaxWidth |
| `settings/CloudAccountsScreen.kt:330` / `:491` | `FilledTonalButton` | tonal | `BUTTON_HEIGHT` = 48dp | `330` 在 `Row` 内**不占满宽**；`491` 占满宽 |
| `common/TwoFactorStep.kt:140` | `FilledTonalButton` | tonal | **无（40dp）** | fillMaxWidth |
| `settings/FormComponents.kt:97`（`ActionButton`） | `FilledTonalButton` | tonal | **无（40dp）** | fillMaxWidth（含 16/8 内边距） |
| `addvault/AddKdbxScreen.kt:491` | `Button` | **filled** | **`56.dp`** | fillMaxWidth |

**三处不一致，都在同一批页面上可直接对比：**

1. **40 vs 48 vs 56**。`AddKdbxScreen.kt:476` 的 KDoc 写着「高 **48dp → 56dp**（M3 的 large button 规格）。
   48dp 在满宽按钮上偏扁」——而**同一个流程的上一页** `AddVaultScreen` 仍是 48dp，
   `TwoFactorStep` / `ActionButton`（2FA、导入导出、转换）甚至还是默认 40dp。
   ⇒ 用户在「添加库」三步里会看到 **48 → 56 → 40** 三种高度的主按钮。
2. **tonal vs filled**。`8.4` 明文写着「主操作必须是 filled」，
   但**登录类主操作（解锁、添加库、连接网盘）全是 `FilledTonalButton`**，
   只有对话框底栏和 WebDAV「测试连接」是 filled。同一个 App 里"主按钮"长两个样。
3. **`height(48.dp)` 与 `minimumInteractiveComponentSize` 的记账错位**。
   `FullScreenDialogShell.kt:527-530` 那条注释是对的：「M3 `Button` 视觉高 40dp，
   触摸目标已撑到 48dp，**不要加 `heightIn(min = 48)`**」。
   但 `UnlockScreen` 直接写 `.height(48.dp)` —— 这不是把触摸目标撑大，是**把视觉高度改大**，
   属于另一种改法（不是错，但和上面那句纪律是两套口径，得写清楚哪个是标准）。

> **建议**：主 CTA 收成 **一个** composable（`PrimaryActionButton`），固定
> `filled + 48dp 视觉高 + fillMaxWidth + 加载态转圈`；`FilledTonal` 只留给
> "次强动作"（如「去设置」「换一种方式」）。这条一旦收掉，上面 4 张表里的 9 行会塌成 1 行。

### 2.4 🟠 卡片圆角五档并存，且**文档与代码已经矛盾**

| 圆角 | 用在哪 | 位置 |
|---|---|---|
| **12dp** | `ItemFormDialog` 字段块 / 左滑删除揭示层 | `ItemFormDialog.kt:450`、`PressAndSwipeToDelete.kt:102` |
| **16dp** | 条目卡片（4 个列表共用）/ 详情页卡片 / 底栏「+」/ 空态品牌按钮 | `EntryCard.kt:54`、`ItemDetailScreen.kt:553`、`VaultixBottomDock.kt:190`、`VaultListScreen.kt:470` |
| **20dp** | 设置卡 / 添加库类型卡 / 表单分组卡 | `SettingsComponents.kt:80`、`AddVaultTypeDialog.kt:65`、`ItemFormDialog.kt:390` |
| **28dp** | 对话框面板 / 搜索栏 | `DialogShell.kt:62`、`SearchTopAppBar.kt:53` |
| **50%** | 底栏胶囊 | `VaultixBottomDock.kt:94` |

**文档-代码矛盾（这条要单独记，因为会误导接力者）：**

- `.ai/conventions/8.4-UI·观感.md:31-32`：条目卡片「M3 默认 Card + **12dp 圆角** + 16dp 内边距」
- `ui/theme/Theme.kt:47-52`：`VaultixShapes` 的 KDoc 也说「抬到 12dp 与项目里卡片的圆角（**EntryCard 12dp**）对齐」
- 而 `ui/common/EntryCard.kt:50-54` 实际是：`2026-09-16 **从 12dp 提到 16dp**` ⇒ `CARD_CORNER = 16.dp`

⇒ 两处文档停留在 16dp 之前。**后果不只是"文档旧了"**：`VaultixShapes.extraSmall = 12dp`
直接决定全 App 输入框的圆角，它被论证为"与卡片对齐"——锚点已经变成 16，但输入框还停在 12。

**16 与 20 并存是肉眼可见的**：库列表页里，`VaultCard`（`VaultListScreen.kt:496-502`）
用 `Card` 但**不传 `shape`** ⇒ 走 `CardDefaults.shape`（M3 基线 12dp，
而 `VaultixShapes` 只覆盖了 `extraSmall`，没覆盖 `medium`）；
条目卡片用 16dp；设置卡用 20dp —— 三个列表在**同一个导航栈里前后两屏**，圆角各不同。

> ⇒ `Card` 的调用里有 **6 处完全不传 `shape`**：
> `VaultListScreen:496`（库卡）· `TrashScreen:204`（回收站行）·
> `ImportExportScreen:424,454` · `ConversionScreen:573,658`。
> 它们直接吃了 `CardDefaults.shape`（M3 基线 12dp）—— 而 `VaultixShapes`
> 只覆盖了 `extraSmall`，**没有覆盖 `medium`**，所以这个 12dp 既不在项目代码里、
> 也不在主题里，只是渲染出来是 12。
> 这是 §2.4 里最隐蔽的一档：**它不是被选中的，是被继承的**。

> **建议**：定一个三档语义而不是五个数：`CardShape`(条目/容器) / `PanelShape`(对话框/表尾) /
> `PillShape`(胶囊)。值先按 16 / 28 / 50% 落，`20dp` 那批向 16 或 28 归并时要单独看一屏定。

### 2.5 🟠 顶栏四套并存（沉浸式 6 页 vs 官方 10 页）

| 顶栏 | 页数 | 页面 |
|---|---|---|
| `VaultixExpressiveTopBar`（沉浸式，26sp→16sp 折叠） | **6** | 条目 / 验证码 / 卡包 / 通行密钥 / 设置 / 详情 |
| `VaultixSearchTopAppBar` | 4 | 同上的搜索态 |
| 官方 `TopAppBar`（M3 默认，small，无折叠） | **10** | 权限 / 关于 / 自动填充 / 云账号 / 导入导出 / 解锁方式 / 库管理 / 转换 / 添加库 ×3 |
| `LargeTopAppBar` | 2 | 库列表 / 回收站 |

`8.4:34-36` 定的规则是「搜索输入框绝不能放进 `LargeTopAppBar`」——这条守住了。
但**没定"二级页该用哪种顶栏"**，于是 10 个设置子页各写了一个官方 `TopAppBar`：
从「库列表」（LargeTopAppBar，会折叠）点进「库管理」（官方 small TopAppBar，恒定高），
**标题尺寸、栏高、有无折叠**三者全跳一次。`PasskeysScreen.kt:93-95` 的 KDoc 已经记过
同款退化（「此前是 `LargeTopAppBar`，收起时顶栏变矮而内容不同步穿过去」）——
那次的修法是**换到沉浸式**，但没有把这个结论推广到另外 10 页。

> **建议**：二级页统一 `TopAppBar`（不折叠、恒定 64dp、只有返回 + 标题）是可以接受的，
> 但必须**收成一个 `SecondaryTopBar` composable**，让"二级页顶栏"这件事只有一个实现；
> 那 10 处里现在标题样式、actions 排布、返回键 contentDescription 各写各的。

### 2.6 🟡 空态：5 套独立实现，同一屏里就有 3 种写法

| 空态 | 标题样式 | 正文字样 | 左右边距 |
|---|---|---|---|
| `EmptyItemsState`（`ItemsScreen.kt:1516`） | `titleLarge` | `bodyMedium` + `padding(top = sm)` | **无** |
| `EmptyTotpState`（`TotpCodesScreen.kt:621`） | `titleLarge` | `bodyMedium` + `padding(top=sm, start=xl, end=xl)` | **24dp（写在正文上）** |
| `EmptyTrashState`（`TrashScreen.kt:176`） | `titleLarge` | `bodyMedium` + `padding(top = sm)` | **32dp（写在 Column 上）** |
| `PasskeysEmptyState`（`PasskeysScreen.kt:346`） | `titleLarge` | `bodyMedium` + `padding(top=sm)` | **24dp（写在 Column 上）** |
| 卡包空态（`CardWalletScreen.kt:185-200`） | **无标题**，只有一行 `bodyMedium` | — | **无** |
| `EmptyVaultState`（`VaultListScreen.kt:381`） | `headlineMedium` + 72dp 图标 + 品牌按钮 | `bodyMedium` + `labelSmall` | **28dp（不在标尺上）** |

`EmptyVaultState` 还有两个魔法数：`Spacer(Modifier.height(6.dp))`（`:400`）
与 `Spacer(Modifier.height(36.dp))`（`:407`）——36 不在标尺上，且与上下的 `Spacing.lg`/`Spacing.xl`
放在一起时没有任何可解释的节奏。

> **建议**：抽 `EmptyState(icon?, title, body, action?)` 一枚，全项目共用；
> 6 处塌成 1 处后，"空态该多大边距"这个问题从此只回答一次。

### 2.7 🟡 响应式只实现了一刀切，文档承诺的三档没有落地

- 代码：`ui/shell/MainShellScreen.kt:48,113`
  ```kotlin
  private const val WIDE_SCREEN_MIN_WIDTH_DP = 600
  val isCompactWidth = configuration.screenWidthDp < WIDE_SCREEN_MIN_WIDTH_DP
  ```
  全项目 `screenWidthDp` **只有这一处**，没用 `WindowSizeClass`，也没有 `FoldingFeature`。
- 文档：`Docs/08-界面设计规格.md:389-394` 承诺 Compact / Medium / Expanded 三档 +
  折叠态 + `ListDetailPaneScaffold`；`:209` 还承诺「≥ 840dp 用 `ListDetailPaneScaffold`」。

⇒ 平板 / 折叠展开态下，用户在 **600dp 一步跨到 `NavigationRail`**，没有中间档；
`ListDetailPaneScaffold` **不存在**（`grep` 0 命中）。
这一条属于"文档超期"，不是"代码写错"——但交接文档里一直当作已实现，会误导排期。

### 2.8 🟡 解锁页的主入口层级是**反的**（与同页自己的 KDoc 矛盾）

`UnlockScreen.kt:314-321` 的 KDoc 写：

> 「解锁按钮仍是页面唯一的实心主按钮……44dp 与按钮的 48dp 高度接近，视觉上落在同一层级」

但同一文件里：指纹大图标 **44dp**（`:321`），主按钮是 `FilledTonalButton` **48dp**（`:281`）
——**tonal 不是实心**（`filled` 才是），且 44dp 的图标在视觉重量上明显强于一个 tonal 按钮。
页面实际呈现的主次是「指纹图标 > 解锁按钮」，而 KDoc 声称相反。
这正是 §2.3「tonal vs filled」在**最关键的入口页**上的后果：主按钮不"实"，图标就抢了戏。

---

## 3. 细节优化（用户点名的第三项）

### 3.1 🟠 自绘导航缺无障碍语义（`Role.Tab` 全项目 0 处）

`ui/shell/VaultixBottomDock.kt:241-248`

```kotlin
Box(
    modifier = modifier
        .fillMaxHeight()
        .clip(RoundedCornerShape(Spacing.lg))
        .clickable(indication = null, interactionSource = …) { onClick() },   // ← 无 role
    …
)
```

- `grep "Role.Tab"` → **0 命中**；`grep "selected ="` 在语义块里 → **0 命中**
- `8.4` 明文要求：「自绘组件必须提供 `semantics { role = Role.Tab; selected = … }`」
- 全项目 13 处 `clickable` 里只有 5 处带 `role`，剩下 8 处：

| 位置 | 缺什么 |
|---|---|
| `shell/VaultixBottomDock.kt:245` | `Role.Tab` + `selected`（底栏 4 个 tab 全都读不出选中态） |
| `items/DisplayOptionsSheet.kt:189` | `Role.RadioButton` + `selected`（视觉上靠 `Check` 图标与 Semibold 表达，读屏读不到） |
| `settings/SettingsComponents.kt:351` | `Role.RadioButton` + `selected`（`SingleChoiceRow` 同款） |
| `items/ItemsScreen.kt:1574` | 分组折叠行，缺 `stateDescription`（展开/收起读不出来） |
| `items/ItemsScreen.kt:1185` | 全屏关闭遮罩 —— 合理（不该被读屏聚焦） |
| `common/EntryCard.kt:157` | 卡片 = 按钮，缺 `role` —— 影响小（`Card` 的语义够用），可不动 |
| `addvault/AddCloudVaultScreen.kt:205` | `ListItem` 选择，缺 `Role.RadioButton` |
| `addvault/AddCloudVaultScreen.kt:404` | 同上 |

> **这条比 §2 的所有"不统一"都更该修**：前四项是**功能缺失**（读屏用户无法知道当前在哪个 tab /
> 选中了哪个选项），不是审美问题。而且修法确定、无设计取舍。

### 3.2 🟠 `IconButton` 被压到 36dp，触摸目标低于平台底线

`ui/totp/TotpCodesScreen.kt:1282,1126-1129`

```kotlin
private val TOTP_MENU_BUTTON_SIZE = 36.dp
…
IconButton(onClick = { expanded = true }, modifier = Modifier.size(TOTP_MENU_BUTTON_SIZE))
```

原因是观感（`：1120` 注释：「触控区压到 36dp、图标缩到 20dp ⇒ 视觉空隙收到 ~16dp」），
这个取舍本身可以理解，但 `IconButton` 的 `minimumInteractiveComponentSize` 是
**48dp 下限且被 `.size()` 强制覆盖**——它是**硬压**，不是"视觉小、热区大"。
Material 的可访问性底线是 48dp；同项目 `FullScreenDialogShell.kt:527-530` 那段注释
恰恰在讲"触摸目标和视觉高度是两件事"，这里却把两者一起压掉了。

> 建议：保留 36dp 的**视觉**（用 `Box(36dp) { Icon(20dp) }` 画），外面套 48dp 的可点区域；
> 或至少 44dp。若确实要 36，把它记进 `.ai` 的"已知取舍"而不是留在代码注释里。

### 3.3 🟠 字体缩放到 2.0x 没有兜底（全项目 `fontScale` 读取 0 处）

风险最高的两处，都是**固定高度 + 单行文字**：

1. **底栏 tab**：`VaultixBottomDock.kt:251-273`
   ```kotlin
   Column(
       modifier = Modifier.widthIn(min = 56.dp).height(48.dp),   // 固定 48dp
   ) {
       Icon(… , modifier = Modifier.size(22.dp))
       Text(…, style = labelMedium, maxLines = 1, overflow = Ellipsis)
   }
   ```
   2.0x 下 `labelMedium`(12sp) 的字面高 ≈ 24sp ≈ 48dp，加上 22dp 图标 ⇒ **必然裁切**。
   胶囊本体 `DockBarHeight = 60.dp` 也是固定的，而 `BottomDockOccupiedHeight` 是
   **常量 86dp**（`:77`），不会随字体变大 ⇒ 各页让位算少了。
2. **密码生成器的开关行**：`common/PasswordGeneratorDialog.kt:159` `.height(40.dp)`
   内含 `bodyMedium` 文本 + `Switch`，2.0x 下同样裁切。

全项目 `maxLines = 1` **34 处**、固定 `height(...)` 容器 **8 处**，
没有一处读 `LocalDensity.fontScale`，也没有 `@Preview(fontScale = 2f)`（因为 `@Preview` 总数为 0，见 §3.5）。

> 建议：先做两件事，成本很低 —— ① 底栏两处 `height(48.dp)` / `DockBarHeight` 改 `heightIn(min=…)`
> 或按 `fontScale` 抬高 `BottomDockOccupiedHeight`；② 给上面两处加 `@Preview(fontScale = 2f)`。

### 3.4 🟡 72 处用户可见中文硬编码在 Kotlin 里，且**没有 `values-en`**

`strings.xml` 收得很好（**911 条**，`Text("…")` 直接写中文 **0 处**），
但 ViewModel / Controller 层的**错误提示**作为中文字面量直接构造进了 UiState：

| 文件 | 条数 | 样例 |
|---|---|---|
| `settings/QuickUnlockController.kt` | 15 | `"PIN 不正确，还剩 ${outcome.remainingAttempts} 次机会"`、`"本设备未设置锁屏或生物识别，无法启用"` |
| `vaultlist/VaultListViewModel.kt` | 13 | `"已上传到云端"`、`"已用本地覆盖云端"`、`"云端又有了新改动，请重新同步"`、`"未知错误"` |
| `settings/CloudAccountsViewModel.kt` | 11 | `"这个来源已不可用，请重新连接"` |
| `unlock/LocalUnlockFanout.kt` | 9 | `"指纹门锁已失效，请用主密码解锁"`、`"系统指纹已变更，指纹解锁已关闭，请用主密码解锁"` |
| `addvault/AddCloudVaultViewModel.kt` | 8 | `"OneDrive 登录状态异常，请重试"` |
| `unlock/UnlockViewModel.kt` | 5 | `"PIN 尝试次数过多，请改用主密码解锁后在设置里重设"` |

这些都是**用户会读到的**（不是日志）。同时 `res/` 只有 `values` + `values-night`，
**没有 `values-en`** ⇒ `Docs/08 §6`「文案放 strings.xml」与实际双语承诺都未落地。

> 建议：把这一批改成 `@StringRes Int` 或密封类型（项目里 `UnlockUiError`
> 已经是这个路子，可以直接复用），再补 `values-en`。

### 3.5 🟡 零 `@Preview`：所有观感问题只能靠真机验

`grep "@Preview"` → **0**。这个项目的历史（`.ai/ISSUES.md` 里反复出现
「真机反馈『观感廉价』『上下的空白太多了』『指纹图标太大』」）说明观感迭代成本极高，
而**没有 Preview 意味着每次改一个 `dp` 都要装一次包**。§2 与 §3 里的每一条
（圆角、按钮高度、2.0x 裁切、底栏）都可以被一张 `@Preview` 直接暴露出来。

> 建议：先给 6 个"规格中心"加 Preview —— `EntryCard` / `SettingsGroupCard` /
> `VaultixExpressiveTopBar` / `VaultixBottomDock` / `EmptyState` / 主 CTA。
> 每个加 `FontScale = 1f / 1.3f / 2f` 三档 + 亮/暗两套 = 6 × 6 = 36 张，
> 一次把 §2.4 / §3.3 全部可视化了。

### 3.6 🟡 「表单页 24dp」与「设置页 16dp」两套边距，但 `AddKdbxScreen` 是**表单却用 16dp**

先说清楚现状（这一条我逐页核过，不是笼统的"混用"）：

| 页面类型 | 页面 | 主容器左右边距 |
|---|---|---|
| **表单页**（整页填写 + 底部主 CTA） | `AddVaultScreen:152` · `AddCloudVaultScreen:159` · `CloudAccountsScreen:157` | **`Spacing.xl` = 24dp** |
| **表单页**（同上） | **`AddKdbxScreen:214`** | **`Spacing.lg` = 16dp** ← 唯一的例外 |
| **设置类子页**（列表/分组卡） | `ImportExport` · `Conversion` · `Autofill` · `Permissions` · `AboutApp` · `VaultManagement` · `UnlockMethod` | `Spacing.lg` = 16dp（全部一致） |

⇒ 真实问题是**一个例外**，不是"乱七八糟"：`AddKdbxScreen` 和 `AddVaultScreen` 是
同一个「添加库」流程的相邻两页，前者 16dp、后者 24dp，**点"下一步"时内容整体横向跳 8dp**；
而 `AddKdbxScreen` 的主 CTA 又是全项目唯一 `height(56.dp)` 的（见 §2.3），
两处偏差叠在同一页上，跨页对比时最扎眼。

> 修法：`AddKdbxScreen:214` 的 `Spacing.lg` 改 `Spacing.xl`（与其兄弟页对齐）。
> 这条是**单点改动**，不需要先建常量——先把例外消掉，建常量的收益才成立。

### 3.7 🟡 图标尺寸 `18.dp` 散落 9 个文件，无一处收敛

`Modifier.size(18.dp)` **23 处 / 9 个文件**，分布：`ItemDetailScreen`(7) / `ItemFormDialog`(6) /
`UnlockScreen`(3) / `AddKdbxScreen`(2) / `SettingsComponents`·`ItemsScreen`·
`VaultListScreen`·`SshKeyGenerateDialog`·`FormComponents`·`CloudAccountsScreen`(各 1)。

其中 `ItemsScreen`·`SshKeyGenerateDialog`·`FormComponents`·`ItemDetailScreen`·
`ItemFormDialog`·`AddKdbxScreen`·`UnlockScreen`·`VaultListScreen` = **8 个文件直接写字面量**，
只有 `CloudAccountsScreen` 把它命名成了 `INLINE_ICON`。
另有 4 个 `18.dp` 是**非图标语义**（`SettingsComponents.kt:86` 的 `SETTINGS_ICON_GAP`、
`CloudAccountsScreen.kt:113` 的 `INLINE_PROGRESS` 等），不该并进图标常量。

两个文件各自定义了 `INLINE_ICON = 18.dp`（`AddCloudVaultScreen.kt:107`、`CloudAccountsScreen.kt:112`），
是同值不同名 —— 而同项目里 `EntryCardIconSize = 40.dp` 是有名字的
（`EntryCard.kt:207`）—— 说明团队知道该这么做，只是没做完。

> 建议：`IconSize` 常量组（`inline = 18` / `action = 20` / `tab = 22` / `cardLeading = 40`），
> 替换 **23 处图标**；`SETTINGS_ICON_GAP` / `INLINE_PROGRESS` 单独归到间距与进度常量组。

---

## 4. 优先级建议（如果要动手）

| 级别 | 条目 | 理由 | 改动面 |
|---|---|---|---|
| **P0** | §2.1 库列表底部让位 | **真 bug**，首个落地页，手势机下最后一条点不到 | 1 行 |
| **P0** | §3.1 `Role.Tab` / `selected` / `Role.RadioButton` | **功能缺失**（读屏用户无法判断当前位置），无设计取舍 | 4 处 |
| **P1** | §2.3 主 CTA 收成一个组件 | 9 行塌成 1 行，直接消灭"3 填色 × 3 高度" | 9 处调用 + 1 新组件 |
| **P1** | §3.2 `IconButton` 触摸目标 | 无障碍底线，改法确定 | 1 处 |
| **P1** | §3.3 底栏/开关行的字体缩放 | 2.0x 下必然裁切 | 2~3 处 |
| **P2** | §2.6 空态收敛、§2.5 二级页顶栏收敛、§2.4 圆角三档 | 纯观感统一，需要看真机定值 | 各 5~10 处 |
| **P2** | §3.5 加 `@Preview` | **前置工程**：不加这个，上面每一条都要装包验 | 6 个组件 |
| **P3** | §2.2 `Spacing` 语义化、§3.4 中文硬编码 + `values-en`、§2.7 响应式三档 | 工作量大、收益慢；且 §2.7 是"文档超期"而非代码缺陷 | 大 |

> **顺序建议**：`§3.5（Preview）` 应该**排在最前面做**，尽管它的级别是 P2 ——
> 因为 §2.3 / §2.4 / §2.6 都是"改完必须看"的改动，没有 Preview 就是装包 × N。
> 一件事的收益不在它自己身上，而在它让后面几件事变便宜。

---

## 5. 与既有纪律的关系（哪些是"违反"，哪些是"纪律过期"）

| 纪律（出处） | 代码现状 | 判定 |
|---|---|---|
| 「一屏两套规格 = 廉价感首要来源」（`8.4:41`「同一功能 3 种写法 = 高危信号」） | 主 CTA 9 种写法 / 顶栏 4 套 / 空态 5 套 / 圆角 5 档 | **违反**（§2.3–2.6） |
| 「主操作必须是 filled」（`8.4`） | 登录类主操作全是 `FilledTonalButton` | **违反**（§2.3） |
| 「自绘组件必须补 `semantics { role / selected }`」（`8.4`） | `Role.Tab` 0 处 | **违反**（§3.1） |
| 「不给 filled `Button` 加 `heightIn(48)`」（`8.4:⑤` + `FullScreenDialogShell:527`） | `UnlockScreen` 写 `.height(48.dp)`；`AddKdbxScreen` 写 `.height(56.dp)` | **口径冲突**（视觉高 vs 触摸目标被混用） |
| 「`contentPadding` 不做外层 padding」（`8.4`） | 五个列表页全部合规 | ✅ 守住 |
| 「条目卡片 12dp 圆角」（`8.4:31`、`Theme.kt:47-52`） | 代码是 16dp（2026-09-16 有意上调） | **纪律过期**，文档要改（§2.4） |
| 「不在标尺上的值保持字面量」（`Spacing.kt` 纪律 2） | 205 处代码字面量，`18.dp` 已事实成档 | **纪律不够用**：缺"语义常量"这一层 |
| 「浮层与让位共用同一 `animationSpec`」（`8.4`） | `QuickFilterPanel` 与 `rememberQuickFilterRowInset` 已显式同步 | ✅ 守住 |
| 「文案放 strings.xml」（`Docs/08 §6`） | 76 文件合规，**13 个 VM/Controller 例外** | **部分违反**（§3.4） |

---

## 6. 核过、**不是**问题的项（避免误报）

这一节同样重要：审计的价值一半在于"别把对的也报成错的"。

| 项 | 实测 | 结论 |
|---|---|---|
| 五个列表页 `contentPadding` 左右边距 | 全部 `Spacing.lg`（16dp） | ✅ 一致 |
| 五个列表页卡片间距 | 全部 `Arrangement.spacedBy(Spacing.sm)` | ✅ 一致 |
| 四个列表页底部让位 | `Spacing.sm + bottomInset` | ✅ 一致（**只有 VaultList 例外**，见 §2.1） |
| `embedded = true` 时不画 FAB | `ItemsScreen` / `TotpCodesScreen` / `CardWalletScreen` 均合规 | ✅ 与底栏「+」互斥，符合 `Docs/07 §6.2` |
| `SelectionActionBar` 的 `dockInset` | 主 Tab 页传 `BottomDockOccupiedHeight`，二级页传 0 | ✅ 已修过（`SelectionActionBar.kt:63-80` 有完整 KDoc） |
| `EntryCard` 图标尺寸 | `EntryCardIconSize` 有名字，`SiteIcon` 默认值引用它 | ✅ 唯一的"做对了"的图标规格 |
| 回收站页未做底部让位 | `TrashScreen` 是独立路由（`VaultixApp.kt:441`），不在底栏内 | ✅ 正确 |
| 硬编码 `Color(0x…)` 98 处 | 69 在 `VaultixBrandColor`（品牌色板本身）+ 26 在 `CardBrandLibraryLogo`（Visa/Mastercard 官方色） | ✅ 合理 |
| 页内私有品牌色 3 处 | `VaultListScreen.kt:442-448`（Bitwarden 蓝 + KDBX 金） | ✅ 有 KDoc 论证"刻意不用动态取色" |
| `ProgressIndicator` 尺寸 | 12 处，18/20/24dp 三种（含按钮内转圈与大号忙碌态） | ✅ 属于"按钮内 vs 页面级"两种语义，可接受 |
| `DockTabItem` 的 `key(item)` 包裹 | 4 处 tab + 「+」全部包裹 | ✅ 守住了"点一个旁边闪"的老坑 |
| `EmptyVaultState` 两枚品牌按钮等权 | 同为 `fillMaxWidth + heightIn(min=64)` | ✅ 符合「Bitwarden 与 KDBX 地位平等」定稿 |

---

## 7. 复算方式（便于下一轮核对）

```bash
cd app/src/main/java/io/vaultix/vaultix/ui

# 1) 间距标尺 vs 字面量
grep -rn "Spacing\.[a-z]" --include=*.kt . | grep -vE ":[0-9]+:\s*(\*|//)" \
  | grep -v import | wc -l                                      # 458
grep -rhoE "(^|[^0-9.])[0-9]+\.dp" --include=*.kt . | wc -l     # 含注释 230 / 代码 205

#    ⚠️ 两个口径差在注释行（KDoc 里引用 `48dp` 之类）：精确的"代码字面量"
#    由脚本按块注释/行注释/import 三重剔除后得 **205 处 / 36 个值**。

# 2) 按钮高度是否显式
grep -rn "height(48.dp)\|height(56.dp)" --include=*.kt .         # 6 处

# 3) 无障碍语义缺口
grep -rn "Role.Tab" --include=*.kt . | wc -l                     # 0
grep -rn "clickable(" --include=*.kt . | grep -v "role = Role"   # 8 处

# 4) 圆角与卡片形状
grep -rn "CORNER\s*=\|_SHAPE\s*=" --include=*.kt .               # 常量定义 5 档
grep -rn "^ *Card($" --include=*.kt .                            # 6 处不传 shape（§2.4）

# 5) 顶栏四套
grep -rn "VaultixExpressiveTopBar(\|TopAppBar(\|LargeTopAppBar(" --include=*.kt .
#    得 6 / 4 / 10 / 2（VaultixSearchTopAppBar 内部用 TopAppBar，不在 10 里）

# 6) 字体缩放 / 预览 / 国际化
grep -rn "fontScale" --include=*.kt app/src/main | wc -l           # 0
grep -rn "@Preview"  --include=*.kt app/src/main | wc -l           # 0
ls app/src/main/res/                                               # 无 values-en
grep -c "<string " app/src/main/res/values/strings.xml             # 911
```

> 上面 6 条命令我都从**仓库根**跑过一遍，注释里的数字即当时的实得值。

> §1 表里的 **72 处中文硬编码**是脚本统计：剔除块注释/行注释后，扫所有字符串字面量里
> 含 CJK 且不含 `->  ${  =  ::` 等日志特征的（即**像 UI 文案**的那些）。
> 全量含中文的字符串字面量是 **169**（含 `OneDriveGraphClient` 27、`HintClassifier` 13
> 等日志/分类词），那部分不属 UI 规范问题，故未计入。

---

## 8. 未做 / 未验证

- **未在真机或 Preview 上验过任何一条**：本报告的"观感"判断全部来自**数值比较**
  （同语义不同值、文档与代码不一致、触摸目标 < 48dp），没有一句是"我觉得不好看"。
- **未核对 `res/values-night` 的内容**（只有颜色覆盖，`strings.xml` 条目数 0）。
- **未验证 M3 各组件在当前版本下的确切基线值**：`Card` 默认圆角、`Button` 视觉高
  我按 M3 公开基线（12dp / 40dp）陈述；项目用的是 `material3 1.5.0-alpha16`
  （`gradle/libs.versions.toml:26`），alpha 版本改过 token 的可能性存在。
  §2.3 / §2.4 的**相对结论**（存在 2 种高度、5 档圆角）不依赖这些绝对值，
  但"默认是 40dp 还是 44dp"这类具体数字需在设备上量一次。
- **未评估深色模式下的具体对比度**：`EntryCard` 的 `surfaceContainerLow` 与背景对比度
  代码注释里写的是 **1.049**（`EntryCard.kt` KDoc），并明确"边界几乎全靠 0.5dp 描边"——
  这个值在动态取色（壁纸派生底色）下会变，但没有跑过任何壁纸组合，**留作待验**。
- **未动代码**：本报告不含任何 diff。
