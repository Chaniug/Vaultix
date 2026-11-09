# 底栏半透明 & 「+」面板精装修 · 探索方案

> 2026-11-09 21:20 · 状态：**用户已拍板，P2-a / P2-b 已施工**（玻璃调淡 + 面板重写），
> 本机门禁全绿，交 CI 出包。P2-c（玻璃连续 / container transform）仍留待真机反馈。
> 可交互对照：[`dock-glass-and-sheet-preview.html`](./dock-glass-and-sheet-preview.html)
> 上游：[`dock-phase1-workorder.md`](./dock-phase1-workorder.md)（P1 已装机验收）、
> [`ui-polish-plan.md`](./ui-polish-plan.md)、[`app-icon-design-proposal.md`](./app-icon-design-proposal.md)

---

## 0. 结论先行

用户问「底栏是否要做成半透明、能淡看到下方密码条目」——**这个能力项目里已经完整实现了，
不是待建，而是「默认关着 + 档位偏实」**。

1. **已有实现**：`ui/glass/` 四文件，源→消费分离、24dp 实时模糊、AGSL 边缘折射、
   上沿高光 / rim 暗线 / 内描边、API 分档降级；2026-10-27 起经四轮真机返工。
   开关在 **设置 → 主题模式 → 液态玻璃底栏，`liquid_glass_dock` 默认 false**
   （`8.4-UI·观感-part3.md` §开关落地位置，当时用户明确指定默认关闭）。
2. **内容侧前置条件也已具备**：`AdaptiveMainScaffold` 窄屏走叠层 `Box`，
   `content(PaddingValues())` 让内容铺到屏幕底，底栏浮在其上；各页只给列表末尾留
   `bottomInset`。⇒ **滚动中条目本来就会从胶囊后面穿过**，滚到底时胶囊后面是空白。
3. 所以真正要做的是三件小事：**把已有档位调淡、把装饰减到「淡」、让选中 pill 与玻璃贯通**；
   外加把「默认关」这条旧定案重新拍一次。

「+」面板则是另一回事：现在 7 个可点项竖排、全是灰图标、小屏必须滚动。
M3 官方对 `ModalBottomSheet` **不规定内容布局**，网格 / 主卡 / 工具条都在规范内且零 alpha API。
⇒ 这里可以放开做，我的推荐是 **③ 主卡 + 网格 × ④ 字段剪影卡** 的组合。

**把两件事收成一句话**：底栏是「一块玻璃」，面板是「从它长出来的另一块玻璃」。
材质一致，比各自单独变好看更值钱。

---

## 1. 取证：现状到底长什么样

| 项 | 实测 | 出处 |
|---|---|---|
| 玻璃默认 | **关** | `MainActivity.kt:78` `initialValue=false`；偏好名 `liquid_glass_dock` |
| 填充 alpha | 浅 **0.60** / 深 **0.65** | `LiquidGlass.FILL_ALPHA_*`，对齐 Haze `thin` 档 |
| 模糊半径 | **24dp** | `LiquidGlass.kt:84` |
| 真模糊门槛 | **API 31+**（`RenderEffect`） | `isLiquidGlassBlurSupported()` |
| 低版本降级 | **全不透明实色** `FALLBACK_ALPHA=1f` | `LiquidGlass.kt:115` + part3 §四处静默失效 4 |
| 装饰 | 上沿高光 0.55、内描边 0.55 / 0.28、1dp 亮线 0.34、rim 暗线 0.16 | `drawGlass()` |
| 选中 pill | **实色** `secondaryContainer` / `primaryContainer` | `VaultixBottomDock.kt:440` |
| 内容是否铺到底 | ✅ 是（叠层 Box + `PaddingValues()`） | `AdaptiveMainScaffold.kt:71-90` |
| 列表底部留白 | `Spacing.sm + bottomInset`（`rememberBottomDockInset()`） | `ItemsScreen.kt:1787` |

### ⚠️ 一条被拍过的定案，这轮必须重新拍

`8.4-UI·观感-part3.md` 第 54–56 行原话：

> 降级**直接画全不透明**（`FALLBACK_ALPHA = 1f`），不是「半透明 + 补偿」：
> **半透明底栏浮在滚动列表上，条目会从图标之间透出来 ⇒ 读不清，那是「不可用」不是「不好看」**。

用户这次要的恰好是「透出来」，但限定词是「**淡淡的**」「稍微看到」。
两者不必然冲突——真模糊档能把内容糊成色块、不再有可读的文字干扰，而无模糊档做不到。
⇒ **推荐只在 API 31+ 调淡，降级档维持实色**；若要在低版本也半透明，必须真机验对比度后单独拍板。
这是有事故记录的可用性取舍，不能顺手改。

### ⚠️ 两条实施陷阱（part3 已记，复述一遍防再踩）

- **不要在别处再叠「半透明白色蒙层假装磨砂」**——会和真模糊打架，表现为「有时糊有时不糊」。
  调淡只能改 `LiquidGlass` 里的 alpha 常量，不能在外面加层。
- **两种外观必须共用同一份 `DockRow`**：玻璃态与实体态只差外框，调 tab 间距 / 「+」尺寸时
  只改一处。若为了「pill 贯通」要给 `DockRow` 加参数，仍是同一份布局，不新建第二套 row。
- 填充 alpha **必须 < 1f**（真模糊档），有单测 `真模糊档填充保持半透明且深色更实` 反向锁定；
  建议值只要保持 `dark > light` 且都 < 1，单测不会破。

---

## 2. 底栏材质：四档候选

预览页 A 区可切。四格共用同一份可滚动列表，拖动能看条目从底栏下穿过。

| 档 | 填充（浅/深） | 模糊 | 装饰 | 判断 |
|---|---|---|---|---|
| ① 实色（装机默认） | 1.0 | 无 | 6dp 阴影 | 基线 |
| ② 现状 thin | 0.60 / 0.65 | 24dp | 全量 | 开关打开即此档，偏实 |
| **③ 更透 ultraThin** | **0.40 / 0.55** | **18dp** | 全量 | ★ 推荐落点，最接近「淡淡」 |
| ④ 更透 + 减装饰 | 0.36 / 0.52 | 18dp | 压到约 35% | 备选，最轻也最容易丢边界 |

### 建议改动清单（全部集中在 `ui/glass/LiquidGlass.kt`）

```
FILL_ALPHA_LIGHT   0.60 → 0.40        FILL_ALPHA_DARK    0.65 → 0.55
BlurRadius          24dp → 18dp
TOP_SHEEN_ALPHA     0.55 → 0.32        STROKE_ALPHA_LIGHT 0.55 → 0.34
TOP_LINE_ALPHA      0.34 → 0.20        RimLineColor alpha 0.16 → 0.10
（STROKE_ALPHA_DARK 0.28 保持不变：深色下描边已经很淡）
```

⚠️ 这些是**预览调出来的起点，不是真机结论**。真机上深色 / OLED 纯黑下模糊纯黑仍是纯黑
（8.4 主篇 §244 已有记录），浅色下才看得出「透」。装机后按实拍再收一次数值。

### 「pill 贯通」这一条

现在选中 pill 是**实色** `secondaryContainer`，浮在玻璃上像贴了一块塑料。
玻璃态下改成 `secondaryContainer.copy(alpha = 0.55f)`（按压时 `primaryContainer.copy(alpha = 0.7f)`），
让「玻璃里有一块更浓的玻璃」而不是「玻璃上贴了张纸」。

实现上给 `DockRow` / `DockTabItem` 加一个 `glass: Boolean` 参数即可，**布局代码一行不复制**。
选中色仍**瞬切**（`VaultixBottomDock.kt` KDoc 的老 bug），只改 alpha 不引入动画。

### 默认值要不要改（待拍板）

`liquid_glass_dock` 默认 false 是当时的定案。用户这轮表达的是「想要这个观感」。
- **建议：API 31+ 默认开，低版本仍默认关（或开了也走实色）**，并保留设置开关。
- 风险：改变全部用户的首屏观感，属产品变更；且默认开后若某机型 shader 失败，
  降级路径会更容易被撞到。
- 保守做法：默认值不动，只在设置页把开关文案改得更可发现（例如副标题点明「可看到下方条目」）。

---

## 3. 「+」面板：M3 查证与五种布局

### 3.1 官方给了什么

| 路径 | 官方说法 | 本项目判断 |
|---|---|---|
| `ModalBottomSheet` | 只规定容器与行为，**不规定内容布局**；Google 自家 Drive / Photos 的创建面板用磁贴网格 | ✅ 网格 / 主卡 / 工具条全在规范内，零 alpha API |
| M3E `FAB Menu` | 2–6 个相关动作，弹簧展开，触发按钮 morph 成关闭 | ⚠️ 项目锁 `material3 1.5.0-alpha16`，8.8 §6① 要求 alpha 只用于叶子组件；且「+ 变 ×」正撞 11-09 用户否决的判据（动作按钮不能让人以为变成别的功能）。**只借「从原位展开」的运动语义，不借组件** |
| M3E `ButtonGroup` | 表达单选 / 分段，connected 组做相邻形变 | ❌ 动作集合不是选择态 |
| M3E `FloatingToolbar` | 横向悬浮动作条 | ❌ 与底栏胶囊抢同一块空间 |
| M3 `state layer` / 一屏一个 Filled | 一屏只该有一个 Filled 主按钮 | ✅ 网格全部用 tonal / outlined，主卡独享主色 |

⇒ **结论：M3 没规定「必须用列表」，列表只是我们上一批图省事选的。** 换布局不需要理由，
也不需要新依赖。

### 3.2 五种候选（预览页 B 区可点）

| 方案 | 结构 | 高度 | 判断 |
|---|---|---|---|
| ① 线性列表（现状） | 标题 + Filled + 分区标签 + 5 行 ListItem + 生成器 | ≈500dp | 基线，小屏要滚动、无焦点 |
| ② 磁贴网格 | 2×3 tonal 卡（图标 + 名称 + 一句说明） | ≈260dp | 最稳的官方路径 |
| **③ 主卡 + 网格** | 当前 Tab 默认动作升为主色大卡 + 2×2 其余类型 + 生成器 chip | ≈250dp | ★ 推荐结构 |
| **④ 字段剪影卡** | 网格里画每种条目的**字段剪影**而非抽象图标 | ≈260dp | ★ 我的大胆建议 |
| ⑤ 玻璃连续 | 面板沿用底栏同一块玻璃 + 从「+」原位展开 | 同③ | 材质收束，风险最高 |

### 3.3 三条大胆建议（我的判断）

**1 · 类型卡画「字段剪影」，不画抽象图标。**
登录画两行 + 锁、银行卡画磁条 + 四位点、安全笔画三行文本、身份画头像 + 两行、
SSH 画等宽字符。用户扫一眼就知道要建的是哪种，比读文字快；这是密码管理器独有的表达，
也是刚落地的折纸 V「一个不单调的单一符号」思路的延续。
实现用纯 Compose `Box` / `drawBehind` 画几条线即可，**不新增 5 个 vector XML**。

**2 · 面板与底栏同一块材质。**
面板从「+」原位展开（container transform：圆角 16dp → 28dp、位置从胶囊中央 → 全宽），
顶部沿用同一道上沿高光。这样两个问题合成一个答案：底栏不是「贴上去的一块玻璃」，
面板是它长出来的另一块。动效约束本轮已放开，但**只借运动语义，不引入 alpha 组件**。

⚠️ 真实风险：`ModalBottomSheet` 跑在窗口 / Dialog 层，`glassSource` 的
`boundsInRoot()` 坐标系与底栏不同时，直接复用会「糊到屏幕外一块空白」
（part3 §采样偏移已有同类事故）。⇒ ⑤ 必须先做一个最小验证，**不能想当然复用**。
若坐标不可靠，退而求其次：面板用同色同 alpha 的**静态玻璃观感**（不采样内容），
视觉上仍然连续。

**3 · 生成器不排第 7 项。**
它是动作不是条目类型，塞进网格会污染分类。做成底部工具条或 `AssistChip`，
与「新建什么」拉开层级。顺带解决现状里「分隔线 + 一行 ListItem」那种
「既像第 6 种类型、又不完全是」的模糊感。

### 3.4 一个顺带发现的实质问题（非装饰）

面板现在把 5 种类型**全列可点**，但 KDBX 库只支持部分类型，
`ItemFormDialog` 靠 `fallbackFormType` 静默兜底到该库支持的第一种。
⇒ 用户在 KDBX 库点「身份」，可能建出一个登录条目而毫无提示。
上一批特意没在面板里过滤（避免与库摘要加载时序同步，见 `AddActionSheet.kt:153`），
但网格化后可以给不支持的卡做**禁用态 + 一句说明**，比静默兜底诚实。
成本中等（要把 `vaultKind` 传进面板），列为可选，不阻塞主方案。

---

## 4. 施工分批与验收

| 批 | 内容 | 涉及文件 | 风险 |
|---|---|---|---|
| **P2-a** | 底栏玻璃调淡 + 减装饰 + pill 贯通 + 默认值拍板 | `LiquidGlass.kt`、`VaultixBottomDock.kt`、`MainActivity.kt`、设置页文案 | 低（全是常量与 alpha） |
| **P2-b** | 「+」面板改 **③ 主卡 + ④ 字段剪影卡** 组合，生成器独立 chip | `AddActionSheet.kt`、少量 strings | 中（布局重写） |
| **P2-c** | ⑤ 玻璃连续 + container transform（可选） | `AddActionSheet.kt`、`MainShellScreen.kt` | 中高（窗口层坐标） |

### 门禁与纪律（照抄上一批踩过的坑）

- **新增任何 material3 组件必跑 `.ai/tools/check_experimental_optin.py`** ——
  上一批 `ModalBottomSheet` 缺 `@OptIn(ExperimentalMaterial3Api::class)` 让 CI 编译挂了一轮。
- `AddActionSheet.kt` 当前约 140 行，改网格**必须抽函数**，别往 `AddActionSheet()` 里塞行：
  detekt `LongMethod ≤150`，`ItemsScreen.kt` 也贴着上限。
- 本机不编译（AGP 9.5.0 缺缓存）⇒ push `main` 交 CI 跑 `detekt` / `assembleFullDebug` / `test`，
  **绝不 push `rele`**。
- 本地静态门禁：`check_compile_smells.py` / 编码 / md 链接 / workflow shell + secret /
  `git diff --check`，三关分开单跑。

### 真机验收清单

1. 浅色 / 深色 / **OLED 纯黑** × 动态取色开 / 关，各一张截图。
2. 列表三种状态：**滚动中**（条目从栏下穿过）、**滚到底**（栏后是空白）、
   **条目不足一屏**；每种状态下 4 个 tab 的图标与文字都要读得清。
3. 选中 / 未选中 / 按压三态的 pill，在玻璃上是否仍能一眼分辨。
4. **API 30 或以下**（或强制关模糊）确认降级仍是实色、且设置页有说明，不出现「字穿栏」。
5. 面板在 **小屏 + 2.0x 字号**下是否一屏放得下；不够就退化成 2 列 + 滚动，别裁字。
6. TalkBack：网格卡的 `contentDescription` / 选中态不回归（P1 里 `Role.Tab` 那套已修好）。

---

## 5. 拍板结果与本轮实际施工

| 题 | 用户拍板 | 落地 |
|---|---|---|
| 玻璃默认开不开 | 「按你的建议改一版」⇒ **采纳默认开** | `VaultixPreferences.liquidGlassDock` 默认值 `false → true`；低版本由 `isLiquidGlassBlurSupported()` + `FALLBACK_ALPHA=1f` 自动走实色，观感不回退；用户显式关过则尊重其选择 |
| 面板选哪套 | 「主卡加网格 + 字段剪影卡一起实现」 | `AddActionSheet.kt` 重写：主卡 + 2 列字段剪影网格 + 生成器独立胶囊工具条 |
| ⑤ 玻璃连续 | 未点名 ⇒ 按建议**留到 P2-c** | 本轮未做 |
| **默认新建逻辑** | 用户明确要求**保留** | ✅ 主卡走 `currentTab.addTarget → onDefaultAction → runDefaultAdd`，密码页→登录、验证码页→验证码、卡包页→银行卡，一行未改 |

### 施工文件

| 文件 | 改动 |
|---|---|
| `ui/glass/LiquidGlass.kt` | 填充 0.60/0.65 → **0.40/0.55**、模糊 24 → **18dp**、高光 0.55 → 0.32、描边 0.55 → 0.34、亮线 0.34 → 0.20、rim 0.16 → 0.10、阴影 8 → 6dp。**`FALLBACK_ALPHA` 保持 1f** |
| `ui/shell/VaultixBottomDock.kt` | `DockRow` / `DockTabItem` 加 `glass: Boolean = false`；玻璃态给选中与按压 pill 叠 `alpha=0.55`，颜色仍瞬切；实心态不受影响，两条分支仍共用一份 `DockRow` |
| `ui/shell/AddActionSheet.kt` | 见上；每个视觉块抽成顶层 private composable，`AddActionSheet()` 留足 detekt `LongMethod ≤150` 余量 |
| `MainActivity.kt` | 首帧占位值改 `isLiquidGlassBlurSupported()`，避免「第一帧实色、第二帧玻璃」闪一下 |
| `core/datastore/…/VaultixPreferences.kt` | 玻璃开关默认值改 true（含降级安全网说明） |
| `values/strings.xml` | 新增 `dock_add_hero_subtitle` / `dock_add_tool_subtitle` |

> ⚠️ 面板不做「组合卡」：用户 21:11 澄清「不用宽卡，只用原有的卡包即可」。
> 银行卡与身份仍是两个**独立、1 步直达**的网格项。

---

## 5.1 P3（下一批，**本轮明确不做**）：卡包页的展示归置

用户 21:14 的精确需求，复述备查：

- 现状：身份 / 个人信息条目**混在密码条目页**，且那里**没有身份筛选按钮**；
- 目标：以后个人信息、银行卡、证照这些**都在卡包页面看得到**，条目也能在卡包页看到；
- 边界：**卡包页原有内容不动**；密码页仍是这些条目的数据源，不搬走、不改类型；
  **远程服务器 / 同步 / KDBX 与 Bitwarden 映射一概不动** —— 纯 Vaultix 客户端的展示归置。

技术判断：`CardWalletScreen` 现在大概率只筛 `VaultItemType.Card`，放宽到
`Card + Identity` 是**同一个库、同一张 `VaultItem` 表**的展示筛选，不碰数据层，
正好落在用户划的边界内。配套补一个类型筛选 chip（全部 / 银行卡 / 个人信息），
解决「没有筛选按钮」那半句。

⚠️ 「证照」目前**不是条目类型**（`VaultItemType` 只有 Login / SecureNote / Card /
Identity / SshKey）。新增第 6 种会牵动双端映射、表单字段、`itemTypeLabelRes`、
`fallbackFormType`、导入导出 —— 与「远程不管它」冲突。
⇒ P3 先复用 `Identity` 承载证照，**不新增类型**；真要独立类型须单独拍板。

⚠️ 施工前先取证 `CardWalletScreen` 的查询条件、空态文案、条目计数与点击跳转，
确认「追加展示」不会把原有卡包内容的排序 / 分组 / 计数带偏。

---

## 6. 不可动 / 需带证据再动

- 不改 `DockRow` 的布局结构（两种外观共用一份，#120 / #193 / #195 的失败模型）。
- 不在 `ui/glass/` 之外另叠半透明蒙层假装磨砂（part3 硬纪律）。
- 不改 `currentTab` / `SaveableStateProvider` / `contentKey` / Tab 枚举（方案 A 的最大收益）。
- 「+」符号恒为「+」，不旋转、不 morph 成「×」（11-09 用户当场否决）。
- 选中 pill 颜色**仍瞬切**，只改 alpha 不引入跨 tab 颜色动画。
- 本轮 P2-a / P2-b **已改代码**（6 个源码文件 + 2 条字符串），未做 ⑤ 与 P3；
  `Manifest` / `mipmap` / `currentTab` / `SaveableStateProvider` / `contentKey` 一行未动。
