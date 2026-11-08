# 06-界面与交互 · 第 7 篇（#162 ~ #183）

> 主篇索引：[`06-界面与交互.md`](./06-界面与交互.md)（含计数规则与重号警告）
> 本篇 5 章 · 16.5KB · 覆盖 #162 ~ #183

## 162. 🔴 Firefox Android：「能看到填充提示，但密码条目匹配不出来」（2026-10-04，用户真机日志定案）

- **现象**（用户原话）：「firefox 安卓手机版上，输入框能看到 vaultix 的提示，
  但是密码条目无法匹配出来。」日志（`versionName=0.8.0-dev-bc333f4`，HONOR BKQ-AN00，
  Android 17 / sdk 37）给出的三行铁证：

  ```
  12:12:35.310  fillRequest pkg=org.mozilla.firefox webDomain= fallback=null webView=false
                fields=3 hints={USERNAME=1, PASSWORD=1, UNKNOWN=1} targets=2 user=true pass=true
  12:12:35.463  fillResponse domain= active=https://pwd.vv1234.cn unlocked=2
                candidates=216 matched=0 datasets=0
  12:12:35.463  noMatch domain= → search fallback
  ```

- **判读**：`fields=3 user=true pass=true targets=2` ⇒ **解析层完全正常**，账号框密码框都认出来了。
  `candidates=216` 而 `matched=0` ⇒ 216 个候选**全被过滤成 0 分**。
  ⇒ 病根不在「认不出字段」，而在「**拿不到域名**」。
- **根因（两层，都要修，只修一层不够）**：
  1. **空串穿透**：`AssistStructureParser` 收集 `webDomains` 时用的是
     `node.webDomain?.let { webDomains += it }`，**没有空白防御**；而 Kotlin 的 `?:`
     **只对 null 短路**，空串会原样赢掉后面的 `?: fallbackWebDomain`。
     GeckoView 在 Firefox 登录页上报的正是**空字符串**（日志 `webDomain=` 后面什么都没有）。
     ⚠️ 同仓的 `FillTargetResolver.inheritWebDomain` 早就写了 `takeIf { it.isNotBlank() }`，
     唯独页面级收集处漏了 ⇒ **同一件事两处判据不一致**。
  2. **兜底全落空**：日志里 13 次 `fillRequest` 的 `fallback=` **全是 null** ——
     地址栏（`mozac_browser_toolbar_url_view` 明明在 `BrowserUrlBars.URL_BARS` 表里）
     与结构文本两条兜底都没取到。
- **⚠️⚠️ 判读教训（本条最值钱的部分）**：
  「空串」出现 8 次、「真 null」出现 5 次。**若只盯着空串那 8 次下结论，会得出
  「补个 `isNotBlank()` 就好了」的错误判断** —— 因为真 null 的那 5 次 `?:` 根本不短路，
  兜底**已经算了、确实算不出来**。⇒ **修掉空串后，那 8 次只会退化成和另外 5 次一样的 null，
  用户症状一点没变。**
  ⇒ **「分布统计」比「挑一个典型样本」更能定性**：同一份日志里 `webDomain=` 与
  `webDomain=null` 的**出现次数对比**，直接决定了「这是一处判据写错」还是
  「整条路在平台层就不通」。
- **外部佐证（不是我们写错了）**：Bitwarden Android 在 Firefox Android 上**同样**
  做不了域名匹配 —— 其 issue [#5720](https://github.com/bitwarden/android/issues/5720)
  「Autofill does not propose correct vault entries」与社区帖
  [#19444](https://community.bitwarden.com/t/compatability-with-android-mobile-browsers-that-arent-chrome/19444)
  都记着这件事。**Firefox 用 GeckoView，第三方 AutofillService 拿不到可用 `webDomain`
  是已知平台限制**，用户搜到的普遍结论是「Firefox 上改用 Bitwarden 的扩展（Add-on）」。
- **修法（两刀，缺一不可）**：
  1. **空白防御**：域名收集与兜底裁决抽成纯函数 [WebDomainResolver](../../app/src/main/java/io/vaultix/vaultix/autofill/parser/WebDomainResolver.kt)
     （`ViewNode` 是 `@SystemApi` 抽象类、构造不出实例，逻辑不抽出来就没法 JVM 单测），
     判据与 `inheritWebDomain` 对齐。
  2. **无域名降级**（真正解决用户问题的那一刀）：`webDomain` 为空时**不返回 0 条**，
     改为按「收藏优先 → 名称升序」返回全部候选（`orderWithoutDomain`），
     再由 `MAX_DATASETS` 截断 ⇒ 用户在 Firefox 上仍能看到条目并一键填入，
     而不是面对一个空面板。**对齐 Bitwarden 的搜索兜底语义**：它也没让用户挡在门外。
- **⚠️ 安全边界**：降级**不关闭** `AutofillRequestContextPolicy` 的包名匹配闸门 ——
  浏览器里缺域名时包名是**浏览器自己**的，拿它匹配会弹出「浏览器身份」的无关条目，
  那才是真的错条目。降级只在确实是登录表单时生效（由 `noFillTarget` 闸门保证）。
- **遗留（下次真机验收要盯）**：`AutofillCredential` **没有 `lastUsedAt`** ⇒ 降级只能按
  收藏 + 名称排，不能按「最近使用」。Bitwarden 有这个字段，是本条最大的可改进点。

## 177. 🔴 文档承诺四档响应式，代码只有一刀切两档 —— **文档大于事实**（2026-10-21，审计 §2.7）

- **现象**：`Docs/08 §4`「大屏与折叠适配」是一张四档表（Compact / Medium / Expanded / 折叠展开态），
  写着 `WindowSizeClass` + `ListDetailPaneScaffold`（列表 360 dp + 详情自适应、最大 720 dp）
  + `FoldingFeature` 铰链避让。任何人按它验收都会得出「缺三档 + 缺双栏 + 缺铰链」的结论。
- **事实**（逐项核过代码）：
  - 只有一个阈值：`MainShellScreen.WIDE_SCREEN_MIN_WIDTH_DP = 600`，判据是
    `LocalConfiguration.screenWidthDp`，**不是** `WindowSizeClass`；
  - 两档：< 600 走悬浮胶囊底栏，≥ 600 走 `NavigationRail`（`AdaptiveMainScaffold`）；
  - **详情页在任何宽度都是独立页面**，没有 Medium 档"全屏对话框"这回事；
  - 全仓 `ListDetailPaneScaffold` / `FoldingFeature` **零命中**。
- **根因**：设计规格先行（§4 写的是目标形态），实现跟不上时**没人回头改文档**，
  而文档看起来和已完成的章节长得一样（同样的标题层级、同样的表格）。读者无法分辨。
- **教训（本条最值钱的部分）**：**同一份文档里"已实现"与"计划"必须用不同措辞区分**，
  不能靠读者从上下文猜。四档表用了"为准"的语气写未来功能 ⇒ 那个语气本身就是谎言。
- **修法**：`Docs/08 §4` 拆成「4.1 已实现（照实描述代码 + 代码位置）」与
  「4.2 计划（均未实现 + 为什么现在不做）」，三档化 / 双栏 / 铰链避让逐项登记为待做。
  **没有实现任何布局** —— 这三项都要真机（折叠屏 + 平板）验证，本地改断点等于凭感觉，
  那正是本轮刻意不碰 §2.5 顶栏收敛的同一个理由。
- **同族**：本轮 §3.4 的「文案双源」（`strings.xml` 与 Kotlin 各一份）本质相同 ——
  同一事实存两处，且没有门禁盯着它们是否一致。文档这边目前仍**没有**交叉检查脚本，
  只能靠这条条目提醒下一个改 §4 的人。

## 181. 🔴 详情页「条目不存在或已被删除」一闪而过 —— **「未知」借用了「空值」**（2026-10-22，用户真机反馈）

- **现象**：在条目列表里滑动后点进某条详情，会闪过**不到 1 帧**的「条目不存在或已被删除」，
  随后内容正常出现。不崩、不报错、logcat 干净，只有眼睛能抓到。
- **根因**：`ItemDetailViewModel.UiState.item` 的初始值是 `null`，而 UI 用它表达**两件事**：
  「还没解出来」和「确认不存在」。`DetailBodyContent` 的 `when` 里 `item == null` 直接落到
  缺失提示分支 ⇒ **订阅建立之前的那几帧被渲染成"确认不存在"**。
  窗口窄到只剩一两次重组（新 ViewModel 实例、`observeItem` 还在建立订阅、
  或库类型/密钥尚未就绪），所以是"1 帧"，而不是"白屏一秒"。
- **修法**（两层，缺一不可）：
  1. 状态层：`UiState` 新增 `itemLoaded`（**已经收到过一次条目流发射**），
     一旦置真不再回落 —— 流重发时若短暂解不出条目，继续显示上一次的内容，而不是闪回"不存在"；
  2. 呈现层：`DetailBodyContent` 增加 `loading = !itemLoaded`，
     `loading && item == null` 画转圈，`item == null` 才画缺失提示。
- **判据**：**「未知」必须是一种显式状态，不能借用「空值」来表达**。
  `null` 同时表示"没有"和"不知道"时，UI 必然在其中一种情况下撒谎。
- **⚠️ 排查过程中的一个反例（值得记）**：最初怀疑的是 `ItemRepositoryImpl.observeItem`
  的 `vaultDao.observeAll()`（Room 流要先跑一次查询）把 `cipherDao.observe()` 卡在
  `flatMapLatest` 上游，为此加了 `.onStart { emit(null) }`。**实测证伪**：
  撤掉该行，`observeItem(...).first()` 仍然拿到条目（`runTest` 的虚拟时钟会推进上游）。
  该行作为**廉价的前置订阅优化**保留，但**它不是本 bug 的修复点**，
  真正修复上面那两层。教训：**"看起来合理"的链路要先用探针证伪/证实，别照着直觉改**。
- **测试**：`ItemRepositoryImplTest.observeItem_firstEmissionIsNeverNullOrBeforeVaultKindResolves`
  锁住数据层"首值不为 null"；状态层的 `itemLoaded` 属于 Compose 状态机，
  本地无 Compose UI 测试装置，**留待真机验收**（点进条目不应再看到该提示）。

## 182. 🟡 筛选面板贴条目、滚动后展开难看 —— **面板与列表的两条边都没有"落点"**（2026-10-24，用户真机反馈）

- **现象**（用户原话）：
  1. 「展开筛选的时候，全部、验证码、通行密钥这些展开栏目好像和底下的密码条目隔得很近」；
  2. 「在往下滑动的时候点击筛选，好像显示效果很不好看」。
- **根因（两个，表现不同但同源：**没有一条边界被明确画出来**）**：
  1. **面板与列表的缝只有 8dp**，而第一张卡片的底色 `surfaceContainerLow` 与页面背景
     对比度只有 1.049（`EntryCard` KDoc 有推导）⇒ 8dp 的缝在像素上读不出来，
     观感就是「chip 行坐在第一张卡片上」。这正是 `.ai/conventions/8.4` 判据⑨
     「几何上有间距 ≠ 看起来没被遮住」的**第二次**复现（上一次是 2026-10-04 的浮层底色）。
  2. **往下滑之后，顶栏之下是一片空白**：面板靠 `AnimatedVisibility` 自上而下展开，
     只能画出已经滚到顶栏之下的内容 —— 列表滚下去之后那里什么都没有，
     于是面板往空处展开，chip 行像悬在半空。
- **修法**：
  1. chip 行下内边距 8dp → 12dp（`QUICK_FILTER_ROW_PADDING_BOTTOM`），
     并在**让位侧**另加 [QUICK_FILTER_LIST_GAP] = 8dp 的呼吸缝
     —— 「面板多高」与「列表让多少」分开两处，各调各的不互相牵动；
     `QUICK_FILTER_ROW_HEIGHT` 从字面量 48dp 改成**算式**
     （`上下内边距 + FILTER_CHIP_HEIGHT`），内边距再变也不会漏改让位常数。
  2. 新增「面板展开 ⇒ 列表顶端露在面板下方」：面板已展开**且列表已滚离顶部**时，
     主动把列表滚到 `first = 第 0 项、offset = QUICK_FILTER_TOP_ANCHOR(56dp)`，
     同时外壳 `padding(top)` 下移同一个值、列表内各项用 `graphicsLayer(translationY)`
     **反向补偿** ⇒ 可见内容**一像素都不跳**，用户只看到「面板盖住上面几行」。
     列表本就在顶部时这条路径**完全不走**（`reveal = 0`），与改动前逐字节一致。
- **⚠️ 顺序是硬性的：先让位、再滚。** `scrollToItem(0, anchor)` 的上限是
  `contentPadding.top - viewport`，而让位要**下一帧**才生效；先滚的话那一刻
  `padding.top` 还是 0，锚点被夹到 0 ⇒ 滚了等于没滚（`scrollToItem` 只记录目标位，
  不会在让位生效后自己修正）。实现上用两帧：`withFrameNanos { }` 之后再 `scrollToItem`。
- **代价（有意接受）**：滚到下面之后点开面板，列表会跳回顶端一个视图 —— 数据没变，
  只是"看的位置"变了；点面板外收起时停在原位（**不回跳**，避免一次意料之外的位移）。
- **测试**：全部是 Compose 布局/滚动态，本地无 UI 测试装置 ⇒ **留待真机验收**：
  ① 列表在顶部点开面板，首条与 chip 行之间应是明显的一道缝；
  ② 往下滑几屏再点库名，面板下方应立刻能看到列表顶端（而不是一片空）；
  ③ 面板展开期间连点 chip，顶栏标题不应缩一下。

### 182.1 ★ 更正（2026-10-24 当晚）：**"回到顶部"被用户否决，改为默认关闭的选项**

- **用户反馈**（原话）：「现在往下滑动的时候，发现点击筛选，会直接返回最上面。这样设定不友好」。
- **我错在哪**：我把「面板展开时下方不能是空的」当成了**唯一**要满足的约束，
  于是拿"滚到顶端"去满足它 —— 补偿只保证了**不跳**（可见内容一像素不动），
  却没保证**不换位置**。而用户的诉求是**位置连续性本身**：他在看第 30 条，
  点筛选是想在**这个上下文里**换一维，不是从头再看一遍。
  ⇒ **"画面连续" ≠ "位置连续"**。前者能靠 `graphicsLayer` 补偿做到，后者意味着压根不该滚。
- **修法**：`rememberListEdgeHolder` 增加 `jumpToTop` 门控，接偏好项
  `VaultixPreferences.itemsFilterJumpToTop`（**默认 `false`**）。
  关闭时该函数**一个字节都不碰滚动位置** —— 连"切 chip 回第 0 项"也不做，
  因为那也是"换一种方式回顶部"。
- **开关位置**：「条目显示」浮层（顶栏 🔍 旁边那个）最下面新增一组，
  文案 `展开筛选时回到列表顶部` / 说明 `展开分类筛选面板时，把列表拉回最上面（关闭则保持当前位置）`。
  放在这里而不是设置页：它只在**这个列表**上有意义，设置页是全局的，放那儿会让人误以为影响所有页面。
- **⚠️ 代价（如实记）**：关掉之后，往下滑再展开面板时顶栏之下仍是空白 —— 那正是 #182 想解决的"难看"。
  **这个取舍交给用户**：两边诉求都真实，只是优先级不同，不该由我再猜一次。
- **教训**：**当"修好一个现象"需要对用户的浏览上下文动手时，先问这是不是他要的**。
  位置类改动（滚动、分页、折叠）默认应该是"不打扰"，要打扰就必须是用户显式打开的行为。
- **⚠️ 2026-11-08 更正（补偿链整条拆除）**：上面 #182 / #182.1 描述的
  「外壳下移 + 列表让位 + 逐项绘制补偿 + 滚动锚点」四件套**已从 `ItemsScreen.kt` 删除**。
  开关打开后切分类出现 56dp 空白，根因正是这套链依赖的 `scrollToItem(0, 56)`
  被两个 `LaunchedEffect` 清掉、且短列表会被 foundation 直接钳回 0（补不回来）。
  现让位只走 `contentPadding.top`，滚动位写入收口到 `rememberFilterBarCollapse`
  唯一一处并过 `jumpToTop` 门控（顺带真正落实本条说的「关闭时也不回顶部」）。
  完整判据与真机清单 → [`SESSION-2026-11-08-part3.md`](../SESSION-2026-11-08-part3.md)。

---

## 183. 🟡 权限管理页右侧不齐 + 状态卡曲率与分组卡打架（2026-10-24，用户真机反馈）

- **现象**（用户原话）：「另外权限管理部分设置里面，好像还没有对齐」，附真机截图。
  截图里四行的右侧：相机行是「去设置」（`TextButton`）、其余三行是「已允许」（裸 `Text`），
  **左边缘明显错开**；同时顶部「一切就绪」状态卡是 28dp 圆角，紧贴其下的
  `SettingsGroupCard` 是 16dp，两者同宽同列却**曲率不一致**。
- **根因（同一屏的两套规格，8.4 判据的又一次复现）**：
  1. `PermissionTrailing` 里"状态"分支用裸 `Text`（直接贴卡片右边），
     "动作"分支用 `TextButton` —— 而 `TextButton` 自带 `ButtonHorizontalPadding`（左右各 24dp），
     ⇒ 文字被向内推 24dp。**同一列里两种缩进**。
  2. 状态卡照抄了 `AutofillStatusCard` 的 `RoundedCornerShape(28.dp)`，但 `VaultixCorner`
     的定稿早就写明「28dp 只留给 M3 对话框，不进『面』的三档」—— 这个卡是与分组卡并排的普通面。
- **修法**：
  1. 抽 `TrailingSlot`：`widthIn(min = TRAILING_MIN_WIDTH(112dp))` + `heightIn(min = UiSize.TouchTarget)`
     + `contentAlignment = Alignment.CenterEnd`，**所有分支（含按钮）一律装进去**；
     抽出 `TrailingLabel`（`labelLarge` + `maxLines = 1`）让纯文本与按钮 label 同字号。
     ⚠️ 宽度**必须写死而不是 `wrapContentWidth`**：要的是"四行右侧同一条右边线"，
     `wrapContent` 会让每行各贴各的；而宽度不够会换行 ⇒ 行高从 72dp 跳到 96dp，
     四行高矮不一比不齐更难看出原因。
  2. 两页状态卡统一 `shape = VaultixCorner.Panel`（16dp）。**`AutofillSettingsScreen` 同批改**——
     不改就是"同一个 App 的两个二级设置页有两种状态卡圆角"，等于把问题挪了个位置。
  3. 顺带：权限页状态卡上方补 `Spacing.md`（原本贴着顶栏，自动填充页是 12dp）；
     两张卡到分组标题的间距两页现在都是 12 + 16 = 28dp（`SettingsGroupTitle` 自带 `top = 16dp`）。
- **测试**：纯 Compose 布局，本地无 UI 测试装置 ⇒ **留待真机验收**：
  ① 四行右侧文字**右边缘与左边缘都应各自成一条竖线**（「去设置」与「已允许」不再错开）；
  ② 顶部状态卡与下方分组卡的圆角应看不出差别；
  ③ 深色/浅色两套主题各看一次（`primaryContainer` 上的「已允许」在深色下更淡）。

---

