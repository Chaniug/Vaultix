# 底栏精装修 · 思路探索（非施工单）

> 2026-11-09 · 状态：**思路已定、全部拍板完毕，合并施工单可直接执行**。
> 用户已选定方案 ③、加号不许变符号、图标 one-shot、架构方案 A。
> **执行见 [`dock-phase1-workorder.md`](./dock-phase1-workorder.md)；本文件只保留思路推导。**
> 可点击对照：[`dock-polish-preview.html`](./dock-polish-preview.html)
> 总单：[`ui-polish-plan.md`](./ui-polish-plan.md) · 图标施工：[`app-icon-design-proposal.md`](./app-icon-design-proposal.md)

---

## 0. 一句话结论

**M3 只规范了「按下那一瞬」，而底栏点击其实有三个时刻；项目现在只做了第一个，且是用自绘绕开 M3 做的。**
精装修的空间不在「把 ripple 加回来」，而在把 **T1 松手确认** 与 **T2 选中驻留** 补上——
这两段现在是空白，也正是「看起来廉价」的来源。

---

## 1. 你问的「M3 点击效果有没有用法」：有，而且分三层

| 层 | M3 的机制 | 数值 / API |
|---|---|---|
| 视觉层 | **state layer**：在组件上叠一层半透明色表达状态 | pressed **0.10**、hover 0.08、focus 0.10、dragged 0.16 |
| 动效层 | **ripple indication**：按压时从触点扩散的涟漪 | `Modifier.clickable(indication = ripple())`；`LocalRippleConfiguration` 可改颜色/alpha |
| 状态层 | `MutableInteractionSource` 把 Press/Hover/Focus 发出来，组件自己决定怎么画 | `collectIsPressedAsState()`、`interactions` flow |

2026 还多了一个正好合用的东西：`androidx.compose.material3.ripple` 的
`RippleNodeConfiguration` 可以**分别**配 press / hover / focus / drag 的视觉，
其中 `PressConfiguration.Opacity` 只叠一层固定 alpha、**不播扩散动画**，
`PressConfiguration.None` 则完全关掉。⇒ 「要 M3 的状态层但不要扩散动画」现在是官方支持的组合。

⚠️ 官方还有一条容易被忽略的口径：**pressed 态不被「底部导航整体」继承，
但其中可点击的项可以继承**。我们这枚是自绘胶囊，不套 `NavigationBar` 组件，
所以这条不约束实现，但说明「导航项有按压反馈」本身是合规的。

⚠️ **API 核对留到施工前**：项目锁 `material3 1.5.0-alpha16`（8.8 记着 alpha API
会改名），上面这些签名必须按 8.8 给的方式解 sources jar 复核，不照抄网页。
本阶段只定思路，不写不确定 API。

---

## 2. 现状：三个时刻里只做了 T0，而且是绕开 M3 做的

`ui/shell/VaultixBottomDock.kt` 的 `DockTabItem`：

- `indication = null`（全项目仅两处关 ripple，另一处在 `ItemsScreen`）
- 自绘 `pillColor` 瞬切 + `pressScale 0.94` + `tween(90ms)`
- 理由写在 KDoc 里：**玻璃态下 ripple 会被半透明层吃掉**，所以用具名实色档画瞬时 pill

这个决策当时是对的，但代价是：

| 时刻 | 现状 | 问题 |
|---|---|---|
| **T0 按下** 0–90ms | 有（缩放 + 实色 pill） | 非 M3 语义；玻璃态与实色态共用一套规格，两边都不够好 |
| **T1 松手** 90–250ms | **无** | 点完没有「动作被接收」的确认，手感发木 |
| **T2 选中驻留** >250ms | 只有一个 `secondaryContainer` pill | 选中态与按下态色档太近，且图标始终是同一支 outline |

⇒ **不是「点击没反馈」，是「反馈只有半拍」。**

---

## 3. 大胆方向（本阶段不受旧红线约束，逐个可试）

### A · T0 合规化：把 state layer 拿回来，但不要扩散涟漪

用 M3 的 press state layer（0.10）替代自绘实色 pill；玻璃态把 alpha 提到约 **0.16–0.18**
并换成**实色档**而非半透明（保留原 KDoc 那条实证：半透明在模糊层上看不见）。
`PressConfiguration.Opacity` 正好是「只叠层、不扩散」，不会重现历史上「旁边跟着闪」。

### B · T1 补上「松手确认」：弹簧而非补间

`tween(90)` 换成 M3E 的 **spring**（`MotionScheme`）或一条轻微过冲曲线：
按下瞬间吸入、松手回弹到 1.0。这是用户刚放开的动效范围。
⚠️ 弹簧只作用于**被按的那一个 item**，禁止跨 tab 并行（见 §5）。

### C · T2 选中态形变：让「我在哪一页」有形状记忆

- 选中 pill 从 16dp 圆角矩形 **morph 成全圆角胶囊**（M3E 的 `MaterialShapes` / shape morph）
- 图标 outline → filled 切换（列表已有 `Icons.Filled.*`，可成对准备）
- 或加一条极细的指示线 / 顶部微光，作为第二个锚点（**无障碍也受益**：读屏之外多一条视觉线索）

### D · 相邻联动（最胆大，也最该小心）

M3E 的 ButtonGroup 有「选中项膨胀、邻居轻微避让」的 bump 语义。
让按下的 tab 微放大、左右邻居微缩，能做出「一排按钮在互相让位」的活感。
⚠️ 这正是历史上「点一个、旁边跟着闪」的高危区，但**根因不同**：
那次是「跨 tab、200ms、两个 pill 并行颜色渐变」，这次是「几何位移、单帧、被按项主导」。
值得试，但必须录屏逐帧确认没有第二处在动。

### E · 玻璃态专属反馈

项目已有 `ui/glass/LiquidGlassOptics.kt`（AGSL 折射 + 三档降级），可以只在按压瞬间：

- 上沿高光提亮一档 / 内描边加重
- 模糊半径 24dp 微增到约 28dp，做出「按压处聚焦」
- 或让采样 tint 随当前 Tab 的主色轻微偏移

⚠️ `LiquidGlassOptics.kt:50` 记着「每帧新建 = 每帧编译 shader，帧率打穿」——
任何光学变化都必须是**参数插值**，不能重建 RenderEffect。

### F · 中央「+」：独立语言，且**不许改变符号**

现在是 `Surface(onClick)` 吃 M3 默认 ripple，和两侧自绘反馈**不同族**。

⚠️ **探索中发现并否决：让「+」旋转**。三条理由（用户在预览页当场指出「好像不对」）：

1. 「+」有**四次对称**，旋转 90° 的终态与初始态完全相同 —— 松手后看不到任何结果；
   而过渡过程中它会变成「×」，× 在界面里是「关闭 / 取消」，与「新建」语义相反。
2. 两侧 tab 是**导航项**（有 selected 态），中央「+」是**动作按钮**（无 selected 态）。
   形状变化在两者身上说的是不同的事，不该套同一套形变语言。
3. 判据：**动作按钮的形状变化，不能让用户以为它变成了另一个功能。**

⇒ 加号的反馈只表达「我按下了」，四个候选（预览页可切换）：

| 候选 | 做法 | 观感 |
|---|---|---|
| 压缩回弹（推荐） | 整体 scale 0.9 + 弹簧回弹，符号恒为 + | 与 tab 的弹簧同族，但幅度更小 |
| 笔画伸缩 | 两条笔画分别 scaleX / scaleY 收缩再回弹 | 像「正在生成」，最贴新建语义 |
| 底色下压 | 明度提一档 + 图标下移 2dp + 轻微阴影 | 最克制的物理按压感 |
| 外扩光晕 | 一圈短促的 0.16 alpha 光环 + 图标微放大 | 最「有动作发生」，略张扬 |

M3E 的 **FAB Menu 展开**属于**产品变更**（8.8 §6④），需单独拍板，不混在精装修里。

### G · 底栏本体的光学精装修

- 玻璃态现在只有「模糊 + 高光」，可补**下沿暗线 / 边缘色散 / 深浅两档填充不透明度**
- 外层 18dp 预留区官方本是给 parallax 的：让胶囊随列表滚动有极轻微位移（转场限制已放开，可试）
- 玻璃采样 tint 跟随当前 Tab 主色，让「切 Tab」在底栏上也有呼应

### H · 图标 one-shot 动效（用户 19:38 提出）

> 需求原文：「密码、验证码、卡包、设置这些小图标能做成动态的吗，点击的时候图标有一个动态的效果，然后停止」。
> **可以做**，而且这正是主流 App 的常见做法：one-shot（播完停住），不是循环。

**官方三条实现路径**（Android Developers《Animated vector images in Compose》）：

| 路径 | 需要 | 本项目现状 |
|---|---|---|
| AVD + `AnimatedImageVector.animatedVectorResource` / `rememberAnimatedVectorPainter` | `androidx.compose.animation:animation-graphics` + 每个图标一份 `animated-vector` XML | ❌ 依赖没有；且 pathData morph 要求命令结构完全一致，4 份手工成本高 |
| **`ImageVector` + Compose 动画 API** | `Animatable` / `animateFloatAsState` + `LaunchedEffect(点击)` | ✅ **零新增依赖、零新增资源**，可直接做整图标变换 |
| Lottie | 三方依赖 + 动画资源 | ❌ 项目依赖极简，不推荐 |

四个 tab 图标现在是 `Icons.Default.Lock / Security / Wallet / Settings`（静态 `ImageVector`），
没有分层 ⇒ 「锁梁开合 / 齿轮转 / 卡片翻」这类**部件级**动画必须自绘分层矢量；
但「整图标弹跳、微旋转、描边转实心 + 弹簧回弹」直接可做，且已依赖
`material.icons.extended`（每个图标有 Outlined / Filled / Rounded / Sharp 多套）。

**四个候选（预览页可点）**：弹跳回弹 / 微旋转 / 描边转实心 / 部件动。
我倾向**弹跳回弹**：最贴「点一下有反应、然后停住」，且与方案 ③ 的弹簧同族。

**三条硬提醒（不是风格偏好，是踩过的坑）**：

1. 动画状态必须放在 `key(item)` 稳定的组合槽里 —— `DockTabItem` 的 KDoc 记着
   嵌套函数 + 无 key 会重建组合身份，重现「**点一个、旁边那个 pill 也跟着闪**」。
2. **只做 one-shot，不循环**：循环动画会持续重组，列表滚动时掉帧；
   播完停在选中态，正好符合「然后停止」。
3. 选中 pill 的**颜色仍须瞬切**（禁 `animateColorAsState`）；图标自身的单 item 动画不违反这条
   —— KDoc §11-07 已明确「按压反馈 ≠ 选中态渐变」，两者可以同时成立。
4. 附带：`graphicsLayer` 变换不重建渲染层，比 `Modifier.size()` 动画省；
   动画期间 `Role.Tab` + `selected` + `contentDescription` 不变，无障碍不回归。

⚠️ 与 8.8 §5「不采纳夸张动效」的关系：用户已明确本阶段放开，故列为可探索；
   落地强度仍以真机逐帧为准。

---

## 4. 建议的推进顺序

| 批 | 内容 | 状态 |
|---|---|---|
| **P0** | 可点击对照预览（含 5 种点击效果 + 加号 4 候选） | ✅ 已出 |
| **P1** | **用户选定方案 ③**：B 弹簧松手 + C 选中形变 + F 加号独立反馈 + **H 图标 one-shot**；T0 可叠 A 的 10% state layer | ⏳ 待施工 |
| **P2** | D 相邻联动 + E 玻璃光学 + G 视差 | 真机看过 P1 手感后再定 |
| **P3** | 全 App 点击反馈一致性（另一处 `indication = null` 在 `ItemsScreen`） | 跟随共享组件自动受益 |

⚠️ 原计划把 A+B 与 C+F 拆两批，是因为怕一次推太多分不清手感归因。
用户已明确选 ③，故合并为 P1；但**施工时仍要能单独关掉 A / C / F**，方便真机逐项比对
（8.4 里滑动删除就是「一次推太多」反复了四轮）。

每批验收：真机**录屏逐帧**（历史上几次动效事故都是靠逐帧才定位）、
默认字号 + 2.0x 字号、玻璃开 / 关、浅 / 深 / 纯黑；三关门禁分开单跑。

---

## 5. 旧「红线」在本阶段降级为「历史证据」

不是不许试，是**别重复踩**：

| 实证 | 内容 | 对探索的提示 |
|---|---|---|
| #87 / 用户原话 | 「不要花里胡哨的动画」 | 现已松绑；但真机看过再定强度 |
| 「点一个、旁边跟着闪」 | 跨 tab 200ms 双 pill 并行颜色渐变 | 联动改走**几何位移 + 单帧**，别回到双色渐变并行 |
| 玻璃第一版 | RenderEffect 挂错层，把图标一起糊成灰雾 | 模糊只挂采样层，内容层不含任何 renderEffect |
| 玻璃第二轮 | 源「只录不画」导致整屏变白 | 源必须照常回屏 |
| 「返回时页面缩小」 | AnimatedContent 尺寸补间被重放 | 形状动画别碰容器尺寸 |

---

## 6. 拍板进度

| # | 问题 | 结论 |
|---|---|---|
| 1 | 主方案选哪个 | ✅ **方案 ③ 弹簧 + 选中形变**（用户 19:32 选定） |
| 2 | 加号怎么处理 | ✅ **否决旋转**。用户当场指出「加号的变化好像不对」，已改为四种不改变符号的候选，在预览页可切 |
| 3 | 转场 / 形变硬约束 | ✅ 本阶段**全部放开**，旧红线降级为历史证据（§5） |
| 4 | T0 要不要叠 M3 10% state layer | ❓ 未定 —— 建议叠，与弹簧并存；也可只留弹簧 |
| 5 | 加号四个候选选哪个 | ❓ 未定 —— 我倾向「压缩回弹」（与 tab 弹簧同族、幅度更小） |
| 6 | D / E / G 要不要做 | ❓ 留到 P1 真机手感确认后再定 |
| 7 | 图标要不要做 one-shot 动效 | ✅ 用户已提出要做；四种候选在预览页可切，我倾向「弹跳回弹」 |

> 剩下三题（4/5/6）都不阻塞开工：**P1 可以先按「③ + 加号压缩回弹 + 图标弹跳回弹」落地**，
> 施工时把 A / C / F / H 做成可单独开关，真机逐项比对后再定。

---

## 7. 参考

- `ui/shell/VaultixBottomDock.kt` — 现状与全部历史取舍（文件头 + `DockTabItem` KDoc）
- `ui/glass/LiquidGlass.kt` / `LiquidGlassOptics.kt` — 玻璃分层与帧率纪律
- `.ai/conventions/8.8-M3Expressive-采纳范围与顺序.md` — M3E 采纳边界、API 查证姿势
- `.ai/issues/06-界面与交互-part2.md` §87 — 转场动效历史坑
- `.ai/conventions/8.4-UI·观感.md` — 按压反馈 / 选中色瞬切 / 滑动删除四轮反复
