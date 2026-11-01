# 06-界面与交互 · 第 8 篇（#184 ~ #198）

> 主篇索引：[`06-界面与交互.md`](./06-界面与交互.md)（含计数规则与重号警告）
> 本篇 3 章 · 14.8KB · 覆盖 #184 ~ #198

## 184. 🟢 首次启动引导缺失 —— 设计文档里的 S2「3 页引导」从未实现（2026-10-24，用户提问）

- **触发**：用户问「首次启动的引导该如何设置呢」。查证后发现：项目**没有任何引导页**，
  也没有「已看过」的偏好位（`grep -rn "onboarding\|firstRun\|welcome"` 在 `app/src/main` 零命中）。
  全新安装看到的是 `VaultListRoute` 的**空库品牌页**（`EmptyVaultState`）。
- **文档与实现的裂缝**：`Docs/08-界面设计规格.md` 第 63 行早就写了
  `| S2 | 引导（3 页） | 插画 + 标题 + 说明 + 按钮 | 翻页指示 |`，只是没实现。
  此后 2026-09-14 的品牌页（四点反馈：仪式感 / 用软件名当标题 / 两个方形品牌色按钮 / 空态隐藏 FAB）
  **顶替**了它 —— 但两者不是一回事：品牌页回答"从哪进"，引导页回答"这是什么、为什么用它"。
- **🔴 最容易写错的一条：准入条件不是"第一次装 App"，而是「无库」且「没看过」**
  - 只看"没看过" ⇒ 从旧版本升级上来的老用户（那个键**不存在**，读作 false）会被拦一次；
  - 只看"无库" ⇒ 老用户把库删光后每次都要重看一遍；
  - 因此 `RootNavState.Onboarding`（= 无库）**不能**直接当引导条件用。它的语义是
    "没有库可解锁"（见该类 KDoc 的启动死锁说明），第三个条件必须另取偏好位。
- **修法**：
  1. 新增 `core:datastore` 的 `OnboardingPreferences`（**独立类**，不塞进已有 35 个函数的
     `VaultixPreferences` —— 那离 detekt `TooManyFunctions` 的 40 上限只剩 5 格）。
     两把键：`onboarding_seen`、`onboarding_fill_setup_prompted`，都默认 `false`；
  2. `RootNavViewModel.showOnboarding` = `vaults.isEmpty() && !seen`，**初值 `false`**（不拦）；
  3. `VaultixApp` 只在 **`!routedOnce`**（真正冷启动）时落引导 —— 否则"在设置里删掉最后一个库"
     会把人从设置页拽到引导页；
  4. 引导页 `ui/onboarding/OnboardingScreen.kt`：`HorizontalPager` 3 页 + 跳过 + 指示点；
     插画 `OnboardingArtwork.kt` 用 `Canvas` 画（吃主题色，深浅/动态色自动成立，无外链素材）。
- **文案与插画的两条对应纪律**：
  - 第 2 页说"两个库地位一样"，插画里两块石头**就必须等大** —— 画成一朵云 + 一个文件，
    云的视觉重量天然压过文件，图会自己打脸文案；
  - 「去开启自动填充」放在**第 3 页**而不是第 1 页：还没接库的用户去配填充服务是空转。
- **两条行为约定**：
  - **跳过 = 看完**（同一个标记）。区分开就要在"跳过"后再弹一次"真的不看吗"，而这页没有需要补问的东西；
  - 点「去开启自动填充」**不结束引导**，只记 `fill_setup_prompted` 再跳系统页 ——
    把用户扣在引导页等一个系统页面回来，是"引导变路障"的典型。
- **测试**：`OnboardingPreferencesTest` 6 例（真 DataStore + `TemporaryFolder`）、
  `RootNavViewModelTest` 新增 4 例（三条准入 + `finishOnboarding` 的两个写入分支）。
- ⚠️ **测试桩的教训（本轮真踩了）**：第一版手写 `MemoryStore`，只改 `updateData` 里的字段、
  `data` 返回构造时的 `flowOf(initial)` ⇒ 四条用例全挂（`expected true but was false`），
  **而产品代码是对的**。根因：`DataStore.edit { }` 内部**也要读 `data`** 来合成新值。
  ⇒ 手写桩越"简单"，越容易造出产品里不存在的执行路径。能用官方实现的，就别手写。
- **待真机验收**：① 全新安装 → 三页引导 → 「开始使用」→ 品牌页；② 点「跳过」→ 直接品牌页；
  ③ 第 3 页「去开启自动填充」→ 跳系统页，返回后仍停在第三页；④ 杀掉重开 → **不再出现引导**；
  ⑤ 深色 + 2.0x 字体下三页标题/正文不裁切、插画不压字。


---

## 191. 🟡 搜索只覆盖标题与用户名 ⇒ 备注里的信息"看得见却搜不到"（2026-10-26，对比审计）

- **现象**：备注里明明写着"公司 VPN"或某个自定义字段值，搜"VPN"搜不出来。
- **根因**：`ItemFilter.matches` 只比 `title` / `username` / uri。
  Bitwarden 官方、KeePassDX 的搜索都覆盖备注与自定义字段。
- **修法**：`matches` 增加 `item.notes.contains(q, ignoreCase = true)` 与
  `customFields.any { name/value contains }`。
- **⚠️ 明确不做**：**不搜密码**。搜索是"找哪条"，密码值参与搜索会让"输入半截密码"
  这类误操作直接暴露命中信息；官方也不搜密码。
- **验证**：`ItemFilterTest` 新增 `matchesNotes`、`matchesCustomFieldNameAndValue`、
  `passwordIsNotSearchable`（**反向锁定**：改了实现立刻红）。
- **判据**：**"界面上展示出来的字段"都应该是可搜索的**，唯一的例外必须写进注释并配
  反向测试，否则下一个人会顺手补上。

---

## 198. 🟢 底栏液态玻璃（Liquid Glass）—— 真模糊只有一条路，且有三处静默失效陷阱（2026-10-27，用户需求）

- **需求原话**：「我想底栏做液态玻璃的通透效果，能做吗，开启底栏的液态玻璃效果，
  需要在设置里面主题模式里面添加液态玻璃开关按钮。」
- **能做，但必须先说清"能做到什么程度"**：
  - ✅ 做：半透明填充 + **实时模糊其后的列表内容** + 上沿高光 + 1px 内描边；
  - ❌ 不做：iOS 26 的**透镜折射**（边缘把背景像素往里挤）。
- **为什么"真模糊"只能这么做**：Compose 没有"模糊背后像素"的 API。
  `Modifier.blur` 模糊的是**自己绘制的内容**，不是身后已画好的像素。
  唯一可行路径是把底栏所在**图层**做成模糊渲染层 ——
  `graphicsLayer { renderEffect = RenderEffect.createBlurEffect(...) }`，
  它作用于该图层的**输入**（= 背后已合成内容）。
- **🔴 三处静默失效陷阱（都不报错，只表现为"开关像坏了"）**：
  1. **顺序陷阱**：模糊必须挂**最外层** Box。挂到内层内容 Box 上 → 模糊的是"内层自己"（近乎空白）；
     先用不透明 `Surface(color=…)` 铺底再模糊 → 模糊的是一块纯色。两者看起来一模一样：没生效。
  2. **填充 alpha 必须是半透明**：`fill.alpha == 1f` 会把模糊结果整块盖掉。
     这条最容易在后续改配色时被顺手改坏 ⇒ 单测 `填充保持半透明且深色更透` 反向锁定。
  3. **降级会静默**：`RenderEffect.createBlurEffect` **只有 API 31+ 有**。低版本若"开关打开、
     什么都不提示"，用户只会认为功能坏了 ⇒ 设置页副标题换成
     「当前系统版本不支持实时模糊，将使用半透明底栏」，**显式降级而不是静默降级**。
- **为什么不做折射（写在这里免得下一棒以为漏做）**：在 `RenderEffect` 之上再叠折射，需要把
  背景内容当**纹理**喂给 AGSL `RuntimeShader`；而 `graphicsLayer` 的 renderEffect
  只能对自己的图层输入生效，拿不到可控采样源。硬做只剩 `PixelCopy` 逐帧回读屏幕 ——
  列表滚动时帧率直接打穿。⇒ 这是**有意的取舍**，不是未完成项。
- **修法（五个改动点，全部收敛到一个实现）**：
  1. 新建 `app/src/main/java/io/vaultix/vaultix/ui/glass/LiquidGlass.kt` ——
     **唯一实现点**：`isLiquidGlassBlurSupported` / `shouldApplyBlur` / `LiquidGlassStyle` /
     `liquidGlassStyle` / `LiquidGlassLayer` / `Modifier.blurBehind` / `DrawScope.drawGlass`。
     所有视觉参数集中在 `object LiquidGlass` 常量里，**避免各处拼参数导致观感漂移**；
  2. `VaultixBottomDock(liquidGlass: Boolean = false)` 分支：真玻璃走 `LiquidGlassLayer`，
     否则保留原 `Surface`；并**抽出 `DockRow`** 让两条分支共用同一份布局；
  3. 偏好链：`VaultixPreferences.liquidGlassDock`（默认 **false**）→
     `SettingsViewModel.liquidGlassDock: StateFlow<Boolean?>`（初值 **null**，遵守"布尔开关
     初值一律 null"契约）→ `SettingsScreen.DisplaySection` 里「主题模式」组中 **OLED 纯黑之后**
     新增一行开关（`BlurOn` 图标）；
  4. 状态穿透：`MainActivity` 收集 → `VaultixApp(liquidGlassDock)` →
     `vaultEntryGraph(..., liquidGlassDock)` → `MainShellScreen(liquidGlassDock = …)` →
     `VaultixBottomDock(liquidGlass = …)`；
  5. 文案：`setting_liquid_glass` / `setting_liquid_glass_desc` / `setting_liquid_glass_unsupported`。
- **⚠️ 本轮又踩了一次"以为已接线其实没接"**：连报三次
  `Unresolved reference 'liquidGlassDock'`，真实原因是参数的实际调用点在
  `VaultixApp.kt` 里的**私有函数 `vaultEntryGraph`**，不是 `VaultixApp` 函数体。
  ⇒ **加"从底到顶"的参数时，先用 grep 找出谁真正构造那个 Composable**，
  不要去猜调用链。命名参数也要认准位置（`MainShellScreen(` 的参数是**紧跟左括号**的第一个）。
- **测试**：`LiquidGlassTest` 8 例 —— API 门槛表驱动（26/29/30/31/33/35）、开关关闭不挂模糊、
  默认参数取真实 `Build.VERSION.SDK_INT`、填充半透明且深色更透、填充只改 alpha 不改色相、
  描边深色更淡 + 高光恒白、降级补偿"接近不透但不越界"、上沿高光带比例合理。
  ⚠️ **不测"画出来好不好看"** —— 那只能在真机上看；这里只锁**会静默出错**的数值约束。
- **待真机验收**：① API 31+ 打开开关 → 列表滚动时底栏后面的文字被糊成色块；
  ② API 30 及以下打开开关 → 底栏实色、设置页副标题为"不支持实时模糊"；
  ③ 关闭开关 → 回到原实心底栏（含 `tonalElevation` 的观感）；
  ④ 深色 + 动态色下玻璃不显脏；⑤ 底栏三段（导航 / 搜索 / 更多）在玻璃态下布局与实体态**完全一致**。

### 198.1 🔴 返工：第一版把图标一起糊掉了（2026-10-27 用户真机截图）

- **用户原话**：「液态玻璃的底栏不对啊，按钮都看不清，参考开源项目是如何实现的？」（附截图：
  整条胶囊连同 4 个 tab 图标、文字、中央「+」全被糊成灰雾）。
- **根因（一句话）**：`graphicsLayer { renderEffect = 模糊 }` 模糊的是**该图层的输出，
  包含它全部子内容**。第一版把它挂在**包住 `DockRow` 的那个 Box** 上 ⇒ 图标文字连同背景
  一起被糊。上面 §198 写的「✅ 对：把 `clip` + `blurBehind` 挂在最外层 Box 上」
  **这条判断是错的**，真机截图直接证伪 —— 已在本轮改写。
- **正确架构 = 源 → 消费 两段分离**（对齐 [chrisbanes/Haze](https://github.com/chrisbanes/haze)
  的 `hazeSource` / `hazeEffect`，Apache-2.0）：
  ```kotlin
  // Haze 的原始范式
  val hazeState = rememberHazeState()
  LazyColumn(Modifier.hazeSource(hazeState)) { … }   // ① 登记"可被模糊的内容"
  TopAppBar(Modifier.hazeEffect(hazeState)) { … }     // ② 浮层采样它，自己不受影响
  ```
  本项目**不引新依赖**，用 Compose 自带的 `rememberGraphicsLayer` + `record` + `drawLayer`
  实现了同一机制：
  | 角色 | 实现 | 关键 API |
  |---|---|---|
  | 源 | `Modifier.liquidGlassSource(source, enabled)` 挂在 Tab 内容区 Box 上 | `drawWithContent { layer.record { drawContent() } }` |
  | 消费 | `Modifier.liquidGlassBackdrop(source, offset, radiusPx)` 挂在浮层的"背景层" | `canvas.translate(-offset); layer.renderEffect = blur; drawLayer(layer)` |
  | 共享 | `rememberLiquidGlassSource()` → `LiquidGlassSource(layer, bounds)` | `boundsInRoot()` 求偏移 |
- **结构必须分两层**（这是本次修复的核心，改错一行就复现 bug）：
  ```
  Box(modifier) {
      Box(matchParentSize().clip(shape).onGloballyPositioned{…}.liquidGlassBackdrop(…))  // 层 A：唯一带模糊
      Box(matchParentSize().clip(shape).drawBehind { drawGlass(…) }) { content() }        // 层 B：图标 + 填充/高光/描边
  }
  ```
  层 A / 层 B 是**兄弟节点**，各画各的层 ⇒ 层 A 的 `renderEffect` 波及不到层 B 的图标。
  这正是 StackOverflow 上「内容放在同一层里也会受效果影响，解法是两个叠加的 Box」所说的做法。
- **四条"不报错、只是不糊/糊错"的约束**（写进 `LiquidGlassSource` KDoc）：
  1. 源的子树里**不能有不透明铺底**（`Surface(color=…)` / `background`）：录到的是"子树自己
     画了什么"，铺底会盖掉底下的一切 ⇒ 录下一块纯色，模糊它等于没做。本项目 Tab 页背景透明
     （`VaultixTheme` 的 `Surface` 在更外层），而**顶栏自己是不透明 `Surface`** ⇒ 若把源挂到
     含顶栏的位置，只会糊到一片灰。
  2. 源与浮层都用 `boundsInRoot()` 描述，跨兄弟层级会对不上。
  3. **每帧重录**：`record` 每帧跑一遍，列表滚动才跟着糊 —— 这是成本所在，所以只挂一个源。
  4. 尺寸为 0 时跳过 `record`（首帧 / 页面切走会分配 0×0 位图）。
- **🔴 顺手修掉的第二个真 bug（视觉）**：源的录制区**比胶囊高得多**，层 A 若不 `clip(shape)`
  就会把一大块糊影画到胶囊外面。第一版没有层 A，所以这个坑当时不存在；分层之后必须补。
- **顺带纠正两处拍脑袋的数值**（数值直接对齐 Haze `HazeMaterials` 五档）：
  | 项 | 第一版 | 现在 | 理由 |
  |---|---|---|---|
  | 填充 alpha（浅/深） | 0.62 / 0.48（**深色更透**） | 0.60 / 0.65（**深色更实**，= Haze `thin`） | Haze 五档全部深色更实（0.55/0.65、0.65/0.80、0.80/0.90…）：深色背景更暗、模糊结果更接近黑，填充再薄就分不清"玻璃"与"背景" |
  | 降级档 | 半透明 + 0.18 补偿（≈0.80） | **全不透明**（`FALLBACK_ALPHA = 1f`） | 半透明底栏浮在滚动列表上，条目会从图标之间透出来 → 读不清，那是"不可用"不是"不好看"。Haze 对无模糊平台同样画 flat scrim |
- **测试**：`LiquidGlassTest` **10 例**（原 8 例，其中 2 例被真机证伪而改写）——
  新增 `降级档直接不透明`、`blurAvailable 默认按真模糊算`、`深浅判定取底色亮度而非系统主题`；
  `真模糊档填充保持半透明且深色更实` 把方向反过来钉住。
  ⚠️ **架构正确性单测测不到**（要 Compose UI 测试 + 真机），只能靠真机看图标清不清晰。
- **待真机验收（本轮重做后）**：① 图标 / 文字 / 中央「+」**必须清晰**（这是本次 bug 的核心）；
  ② 列表滚动时胶囊后面有内容在动、被糊开；③ 糊影**不越出胶囊边界**（不出现方形色块）；
  ④ 深色主题下玻璃与背景能分出边界；⑤ API 30 及以下 → 实色底栏 + 设置页提示"将使用实色底栏"。

---

