# 06-界面与交互 · 第 1 篇（#20 ~ #70）

> 主篇索引：[`06-界面与交互.md`](./06-界面与交互.md)（含计数规则与重号警告）
> 本篇 11 章 · 15.3KB · 覆盖 #20 ~ #70

## 20. Quick Settings 磁贴「加了不显示 / 空白」（2026-09-09）

- **现象**：声明了 TileService，但快捷设置里找不到，或加进去是空白方块
- **根因**：① 只实现 `onClick`，没有 `onStartListening`（系统拿不到 label/icon/state，
  国产 ROM 尤其明显）；② 没调 `requestListeningState`，系统不保证回调 onClick
- **另注**：磁贴**必须由用户手动添加**，系统没有给应用「开关磁贴」的 API——
  设置页只能做「引导添加」（API 33+ `StatusBarManager.requestAddTileService`），不是开关
- **解法**：`onTileAdded` / `onStartListening` 里写 `tile.label/icon/state` + `updateTile()`，
  并 `requestListeningState(this, ComponentName(this, X::class.java))`

## 21. Android 11+ 查不全已安装应用（「关联应用」列表很短）（2026-09-09）

- **现象**：`queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` 只返回很少几个 App
- **根因**：**包可见性限制**（API 30+）——不在「自动可见」白名单的查询需要 `<queries>` 声明
- **解法**：Manifest 加 `<queries><intent><action MAIN/><category LAUNCHER/></intent></queries>`，
  **不要**申请 `QUERY_ALL_PACKAGES`（应用商店审核风险）

## 31. 设置页与 Bastion 的信息架构差距（本轮修复，2026-09-10，be8a5bf）

对 Bastion `AutofillSettingsV2Screen`(1149 行) / `PasskeySettingsScreen`(666 行) /
`SettingsScreen`(1925 行) 逐项比对后补齐：

- **没有状态卡**：用户分不清「没启用」和「启用了但填不出来」→ 新增三态卡
  （未启用 errorContainer / 需注意 tertiaryContainer / 正常 primaryContainer）。
  「需注意」= 密码能填但通行密钥没开（Chromium 半残状态，最常见）。
- **`AutofillStatusChecker` 的两步判定**：`AutofillManager.hasEnabledAutofillServices()`
  只回答「系统有没有启用任何服务」，选的是谁分不出来 → 必须再读
  `Settings.Secure:autofill_service` 按自身类名子串匹配。
  **读不到值（部分 ROM 对第三方 App 隐藏）时按「已启用」处理**——第一步已确认有服务，
  把「读不到」报成「未启用」会误导用户反复去系统设置确认。
- **没有通行密钥入口**：设置页看不到「我到底存了几个通行密钥」，排查 Edge 通行密钥
  问题全靠猜 → 新增分组显示「已解锁库中：N 个」。显示 0 即定位到同步/解析问题。
  口径必须写清「已解锁库中」：锁定库密文读不出来，硬统计得 0 会误导成「没存过」。
- **填充行为无开关**：`MatchConfig` 的两个域匹配参数此前是硬编码默认值 → 提升为用户
  开关（严格匹配 / 允许子域名匹配），在 `VaultixAutofillService.buildResponse` 生效。
  **必须是真开关**：装饰性开关比没有开关更糟。
- **`credential_provider.xml` 的 `settingsSubtitle` 误用标题文案**
  （`@string/setting_credential_provider` = 「凭据提供商（通行密钥）」），
  新增 `setting_credential_provider_subtitle` 专用副标题。

### 明确**不**对齐 Bastion 的三项（有意为之）

1. Bastion CP 文案写「通行密钥**和密码**设置」——与其 `credential_provider_config.xml`
   里「CP 不处理密码」的注释自相矛盾。Vaultix 保持「凭据提供商（通行密钥）」。
2. Bastion 用 `Intent("android.settings.CREDENTIAL_PROVIDER_SETTINGS")` 跳系统设置，
   Vaultix 用 `CredentialManager.createSettingsPendingIntent()`（能直达自家 provider
   的启用开关），不下沉为通用 `ACTION_SETTINGS`。
3. 不搬 Bastion 的「黑名单 / 屏蔽字段 / 智能标题 / 通知时长 / 密码建议 / 影子校验 /
   校验诊断」——Vaultix 无对应能力，搬过来只会是点不动的假开关。
   同理不搬 `PasskeySettingsScreen` 的影子校验/严格校验开关。

## 38. 搜索框「乱跳」——三处顶栏写法不一致（2026-09-11，P1，`a3093e4`）

**现象**：用户反馈「一直在输入框，搜索框乱跳」。

**根因**：三个带搜索的界面**各写各的**，且都不符合 Bitwarden 做法：

| 屏幕 | 旧写法 | 为何会跳 |
| --- | --- | --- |
| `ItemsScreen` | `OutlinedTextField` 挂在 `LargeTopAppBar` **外层** `Column` | 大标题栏滚动变高 → 输入框被反复垫高（最严重） |
| `PasskeysScreen` | 输入框塞进 `LargeTopAppBar.title` | 大标题栏自身可变高度 → 展开/收起时抖 |
| `TotpCodesScreen` | 同上（清除按钮语义还写成「取消」） | 同上 |

**修法（照抄 Bitwarden `BitwardenSearchTopAppBar`）**：

- 新增 `ui/common/SearchTopAppBar.kt`（`VaultixSearchTopAppBar`）：固定高度 `TopAppBar`
  （**绝不用 `LargeTopAppBar`**）+ 搜索态输入框整体占据 `title` 槽 +
  `FocusRequester`/`LaunchedEffect` 主动聚焦 + `ImeAction.Done` + 清除按钮动画。
- 三个屏幕统一 `if (searchActive) 搜索顶栏 else 普通顶栏`（**整体替换，不叠加**）。
- `searchActive` 由 `remember` 改 `rememberSaveable`。
- 删除三个屏幕各自的旧 `SearchField` 与失效 import。

**验证**：Compose API 桩 + kotlin-compiler-embeddable 真实编译新组件 → 0 error；
API 参数名逐字对齐 Bastion；四文件无超 120 字符行（UTF-16 语义）；无未使用 import。

### 教训

同一功能在项目里出现 **3 种不同写法**，是最危险的信号之一 —— 说明没有统一封装。
**优先建一个复用组件、三处统一，再谈“修 bug”**；否则今天修好一处，另两处还在跳。

---

## 56. 「整行点击 = 复制」与「按住后滑动删除」的手势冲突（2026-09-12，第四十四轮）

**需求（用户原话）**：「验证码界面的点击复制验证码到剪切板的，按住验证码条目，滑动删除」；
密码条目同样要「按住后滑动删除」。

**冲突点**：卡片内层已有 `EntryCard` 的 `clickable`（点击 = 打开详情 / 复制验证码），
删除手势若也用普通拖动，会在松手时**同时**触发点击（变成「删掉又打开详情」）。

**解法**（`ui/common/PressAndSwipeToDelete.kt`）：
1. 用 `detectDragGesturesAfterLongPress` —— **只有长按之后**的拖动才算删除手势，
   与点击互不干扰（点击仍在长按超时前完成）；
2. 拖动事件 `change.consume()` —— 内层 clickable 见到已消费的 move 会放弃这次点击；
3. 只允许向左拖（`minOf(amount.x, 0f)`），红色删除底随位移渐显，未过阈值回弹、过阈值滑出后回调；
4. 删除一律走**软删除**（`ItemRepository.softDeleteItem`，进回收站可恢复），并在列表给一句提示。

**为什么不做普通左滑删除**：密码条目一滑就没，误触代价太高（列表滚动/单手/口袋误碰）。
上游 Bastion 的删除也是「先长按/选择、再动手」的两步语义。

⚠️ 副作用：验证码行点击改成「复制」后**编辑入口必须显式补一个**（否则条目改不了）——
已在行内加「编辑」按钮；密码列表则仍由点击进详情编辑。

---

## 57. 沉浸式顶栏：必须「浮在内容之上」才成立（2026-09-12，第四十四轮）

**用户要求**：「滑动就左上角的字放大缩小的效果、状态栏沉浸、右上角按钮沉浸」。

**规格**（`ui/common/ExpressiveTopBar.kt`，移植 Bastion `ExpressiveTopBar`）：
标题 32sp→16sp、栏高 72dp→48dp、上下内边距 8dp→4dp、内容下移 8dp→0dp、
**收起后栏背景 alpha→0**、动作胶囊 48dp→40dp + 整组缩放 0.85、内容色向 `onSurfaceVariant` 过渡。
收起判定是**快照式**（首个可见项偏移 > 8dp → 1，否则 0），内部 200ms 补间，
判定值用 `derivedStateOf`（避免每帧重组）。

**三个必须同时满足的条件（缺一个就退化成「顶栏变短了但内容还是被压着」）**：
1. 顶栏必须画在**内容之上**（`Box { 列表; 顶栏 }`），**不能**放 `Scaffold(topBar = …)`
   —— Scaffold 会把内容整体压到顶栏下方，「内容从半透明栏下穿过」就不存在了；
2. 列表顶部留白取 `rememberImmersiveBarPadding(collapse)` = 状态栏内边距 + 当当前栏高
   （随收起动画变短），这样展开时首条不被挡、收起后内容滑到栏下；
3. 外层壳（`AdaptiveMainScaffold` 的 `Scaffold`）必须 `contentWindowInsets = 0`
   —— 否则外层已把内容压到状态栏下方，顶栏再加一次 `statusBarsPadding` 就是双份留白。

搜索态例外：搜索输入框不能塞进会折叠的大标题里，故搜索态仍走固定高度的
`VaultixSearchTopAppBar`（并恢复默认 `contentWindowInsets`）。

---

## 58. 验证码页「统一进度条」与详情页「去动态验证码」（2026-09-12，第四十四轮）

**① 统一进度条**（`ui/totp/UnifiedProgressBar.kt`，移植 Bastion `UnifiedProgressBar`）：
整页**一条**倒计时（取最快翻转那条条目的周期），进度 = 本周期已过/周期，
剩余 ≤5s 切 `error` 色，右侧固定「Ns」，其后是可选的**尾部插槽**——
「通行密钥」入口就挂在这里（对齐 Bastion，空列表时独立成行兜底，
否则用户在空态下失去进入通行密钥页的路径）。
**行内进度条全部删除**：每行每秒重绘一条进度条是纯浪费，统一到顶部一条即可（用户要求降功耗）。

**② 详情页去动态验证码**：`ItemDetailScreen` 此前每秒重算验证码 + 重绘进度条（页面开着就一直跑），
现改为**只提示**「该条目含有验证码 / 已绑定 N 个通行密钥」，并注明去「验证码」页查看。
⚠️ 点击行 = 复制之后，验证码页的编辑入口必须显式保留（见 #56）。

**③ 2FA 第二步页面完全不响应**：`AutofillFillTargetPolicy.isOtpOnly` →
`VaultixAutofillService.buildResponse` 直接 `return null`。验证码在上一步填账号密码时就已进剪贴板，
这一页既不该列条目、也不该弹「没有匹配的密码」（对齐上游 `Unfillable → onSuccess(null)`）。

---

## 62. 点库名展开的筛选条必须「浮在内容之上」（2026-09-12，第四十八轮）

**用户要求**：「点左上角库名展开/收起分类筛选」。

**坑**：把筛选条塞进可滚动的 `Column`（列表上方）会让它**随列表滚走**，
而顶栏的箭头还指着「已展开」——用户会以为筛选条坏了。
这与 #57（沉浸式顶栏必须浮在内容之上）是**同一类问题的第二次出现**。

**解法**：筛选条与顶栏同层（`Box` 的另一个子节点），
`topPadding = rememberImmersiveBarPadding(collapse)` 让它正好压在顶栏下方；
另铺一层 `matchParentSize()` 的透明遮罩（点面板外即收起，免去找关闭按钮）。

**另一个刻意的决定：筛选状态不持久化。** 落盘会让用户下次打开 App 对着一个空列表发呆
（条目都在，只是被上次的筛选挡着），而收起状态下那排 chip 是看不见的 ——
这就是「条目莫名其妙不见了」类投诉的来源。`rememberSaveable` 只保证切 Tab 回来仍在。

**还有一处**：有筛选时标题必须拼成「库名 · 筛选名」。
只显示库名的话，用户看到条数明显变少会以为数据丢了。

---

## 67. 顶部 / 底部「让位」写在外层容器 padding 上 ⇒ 三个症状同一个根因（2026-09-13）

**现象**（用户一次报三个）：① 筛选 chip 行压在第一条条目上；② 顶栏与底栏「不沉浸」
（滚动时两片区域都不是透明的）；③ 搜索态出现巨大黑色横幅，且返回手势**直接退回桌面**。

**根因**
- ①③ 与 ②的顶栏部分：内容顶部让位一律写成
  `Column(modifier = Modifier.padding(top = barPadding))`（密码 / 验证码 / 卡包 / 设置
  **四个 Tab 全是这个写法**）。后果三条：列表视口被整体下压 ⇒ 内容永远画不到顶栏区域，
  「收起后顶栏透明、内容从下方穿过」不成立；不感知筛选行高度 ⇒ chip 压住首条；
  搜索态 `Scaffold` 已按 `contentWindowInsets` 让过位，外层再叠一次 ⇒ **双重留白** = 黑横幅。
- ②的底栏部分：底栏走 `Scaffold(bottomBar = ...)`，内容被让出底栏高度 ⇒
  胶囊周围那圈留白只能露出纯背景，与内容割裂。
- ③的返回：搜索态**没有 `BackHandler`**，而主界面是**根路由**
  （`popUpTo(0) { inclusive = true }`，栈里没有上一层）⇒ 系统返回直接结束 Activity。

**解法**：顶部让位一律改到 **`LazyColumn.contentPadding`**（列表）或
**可滚动的 `Spacer`**（`verticalScroll` 页面），可随动画变化、可叠加，搜索态归零；
底栏改**叠层悬浮**（`Box { content(); Box(align = BottomCenter) { bottomBar() } }`），
各页用 `bottomInset`（`rememberBottomDockInset` = 胶囊 86dp + 系统手势条）自己留位；
搜索态加 `BackHandler`。

**判据**：**「让位」必须做在滚动内容里，不能做在滚动容器外。**
做在外面，内容就永远到不了那片区域 —— 无论顶栏的 alpha 是 0 还是 1。

---

## 68. 手势有实现但**无可感知反馈** ⇒ 功能等于不存在（2026-09-13）

**现象**：用户要求「按住密码条目 / 验证码条目，然后从右往左滑删除，**分组密码也要**」——
而该手势**三个列表早就接了**（含分组路径：分组只多插一个分组头，行 composable 是同一个）。

**根因**：长按「上膛」后唯一的反馈是 `ARMED_SCALE = 0.02f`（2% 缩放），
红色删除底只在 `offsetX < 0` 之后才显影 ⇒ 用户按住时画面几乎没有变化，
**根本不知道自己已经解锁了这个手势**。参考实现 Bastion 有显式的 armed 态：
`SwipeArmState.arm()` + 静态露出 `hintAlpha = 0.32f` 的红底。

**解法**：armed 用 `animateFloatAsState` 平滑成 0→1；红底显隐取
`max(滑动进度, 0.32 × armedProgress)`；并让内容在 armed 时**额外左移 16dp** ——
删除底被不透明的卡片完全盖住，只调 alpha 是看不见的，必须挤出一条缝。

**判据**：**「功能存在」与「用户知道功能存在」是两件事。**
手势、快捷键这类隐藏交互，没有可见反馈就等于没做。

---

## 70. Tab 切换闪一下 —— 进出动画不同步造成的「双向半透明」漏光（2026-09-13）

**现象**：密码条目页 → 验证码页（以及反向）切换时屏幕闪一下；**深色模式尤其晃眼**。

**根因**：`TabTransitions` 原为 `fadeIn(220ms)` + `fadeOut(120ms)`，另叠加 `slideInVertically`。
`AnimatedContent` 过渡的**中间帧两页同时在屏幕上**：新页 alpha = a、旧页 alpha = b 时，
与背景 `Bg` 的合成结果是 `新*a + 旧*b*(1-a) + Bg*(1-a)*(1-b)`。
两条**时长不同**的曲线必然在中间某处同时处于「半透明」（a≈b≈0.5），
此时背景占比 `(1-a)(1-b) = 25%` ⇒ 黑底透出四分之一 = **一帧明显的闪光**。
位移动画把这块漏光「抹开」，观感更糟。

**解法**：进出**同时长**（140ms）且 alpha **互为补数**（enter 0.95→1、exit 1→0.05，
`CubicBezierEasing(0.6,0,0.4,1)`），`(1-a)(1-b)` 峰值降到约 1.2%；去掉位移。

**判据**：**任何 cross-fade，进出必须同时长 + alpha 互补。**
「让退出更快一点」这类看着更利落的配置，在深色底上就是一次闪光。

---

