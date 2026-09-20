# 07 · Material 3 设计系统

> 目标：视觉与交互对齐 Google Material Design 3（含 2025 年 M3 Expressive 更新），在 Android 上"看起来像系统应用"，同时针对密码管理器的高风险操作做必要强化。

## 1. 设计原则

| 原则 | 落地 |
|---|---|
| **个人化** | 支持 Material You 动态取色（API 31+）与壁纸取色；低版本提供 6 套静态种子色 |
| **清晰层级** | 容器色（`surface-container*`）分层替代阴影；条目卡片用 `surface-container-low` |
| **一致性** | 所有组件直接用 `material3` 官方实现，**不重写**基础组件；自定义仅做组合封装 |
| **克制的高亮** | 危险操作（删除、永久删除）用 `error` 容器；主操作用 `primary`，全屏主 CTA 不超过 1 个 |
| **动效有语义** | M3 Expressive 的强调动效只用于"状态跃迁"（解锁成功、保存成功），不用于装饰 |

## 2. 色彩系统

### 2.1 角色定义

| 角色 | 用途 |
|---|---|
| `primary` / `onPrimary` / `primaryContainer` | 主 CTA、选中态、解锁按钮 |
| `secondary` / `secondaryContainer` | 次要强调：收藏标记、TOTP 芯片 |
| `tertiary` | 生成密码、附加操作 |
| `surface` / `surface-container-lowest…highest` | 背景分层（页面 → 卡片 → 输入框 → 对话框） |
| `error` / `errorContainer` / `onErrorContainer` | 校验失败、危险操作、弱密码提示 |
| `outline` / `outlineVariant` | 描边、分割线、未选中图标 |
| `surfaceVariant` / `onSurfaceVariant` | 次级文字、辅助信息 |

### 2.2 主题配置（**以代码为准**）

> ⚠️ 2026-09-20 更正：本节此前写的是**设计意图**（含 `contrast` 参数、种子色方案），
> 但代码里从未实现。为避免"文档说一套、代码做一套"，这里改为**如实描述现有实现**，
> 未实现的移到 §2.3 的"计划"里，不再与现状混写。

实现见 `app/src/main/java/io/vaultix/vaultix/ui/theme/Theme.kt`：

```kotlin
enum class ThemeMode { SYSTEM, LIGHT, DARK }   // 跟随系统 / 强制浅 / 强制深

@Composable
fun VaultixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,       // Android 12+ → Material You 取壁纸色
    oledPureBlack: Boolean = false,     // 深色下 background/surface 改纯黑
    content: @Composable () -> Unit,
)
```

配色来源**二选一**（不叠加）：

| 条件 | 配色来源 |
|---|---|
| `dynamicColor && SDK ≥ 31` | `dynamicLight/DarkColorScheme(context)` —— 壁纸派生 |
| 否则 | `lightColorScheme()` / `darkColorScheme()` —— **M3 基线色板** |

`oledPureBlack` 在上述结果上再 `copy(background = Black, surface = Black)`。

### 2.3 主题策略

#### 现状（已实现）

| 能力 | 状态 | 对应设置项 |
|---|---|---|
| **Material You 动态取色** | ✅ 已实现（API 31+） | 设置 → 外观 → 动态取色 |
| **浅色 / 深色 / 跟随系统** | ✅ 已实现 | 设置 → 外观 → 主题模式 |
| **纯黑（AMOLED Black）** | ✅ 已实现（深色下生效） | 设置 → 外观 → 纯黑主题 |
| 静态种子色板 | ⚠️ **用的是 M3 基线色板**（`lightColorScheme()` 无参默认），并非自定义种子色 | — |

#### 计划（**尚未实现**，不要当成已有能力）

以下为设计意图，落地前不应在 README / 设置页里描述为已有能力：

| 计划项 | 说明 | 状态 |
|---|---|---|
| **自然（Nature）种子色** | 低饱和自然色系（柔和绿/土色），长时间使用不易疲劳 | 📋 未做 |
| **RG 护眼（低蓝光暖色）** | 压低蓝光比例，夜间 / 对蓝光敏感用户 | 📋 未做 |
| **对比度档位（标准/中/高）** | 跟随系统 `Contrast` 设置 | 📋 未做（`VaultixTheme` 无此参数） |

> 实现要点（若将来做）：多套主题应**共用同一套 `ColorScheme` 生成逻辑**（种子色 →
> `lightColorScheme`/`darkColorScheme`），仅替换种子色与 `surface` 基准值，
> 避免维护 N 份手写色表。

### 2.3.1 强调色（accent）的分配纪律

> 起因：2026-09-20 用户问「material3 设计上，我这个页面是否还要稍微加一点强调色之类的，
> 感觉目前界面的配色略微单一了」。排查后的结论是 **不是强调色不够，是强调色缺语义**，
> 因此本节把"哪个角色管什么"写死成纪律，而不是笼统地"再加点颜色"。

**角色职责表**（改动前先查这张表）：

| 角色 | 管什么 | 不要用来做 |
|---|---|---|
| `primary` | 主 CTA、选中态、解锁按钮、验证码能力图标 | 不要用来"给某个页面加点色" |
| `tertiary` | 通行密钥能力图标、密码强度"中" | 不要因为"想多几种颜色"改成它 |
| `secondaryContainer` | 条目/设置项**选中**底色 | — |
| `error` / `errorContainer` | 危险操作、校验失败、弱密码 | 不要用于非危险的"醒目" |
| `surfaceContainerLowest…Highest` | 背景分层（页面 → 卡片 → 输入框） | 不要当强调色用 |
| `onSurfaceVariant` | 次级文字、辅助信息 | 不要用作图标的"强调" |

**已落地的三处修正**（2026-09-20）：

1. **能力图标配色收敛为唯一出处** —— `capabilityTint(Capability)`。
   修正前 `ItemsScreen` 与 `ItemDetailScreen` **各写一遍** `colorScheme.tertiary`，
   想调通行密钥的色相就得记得改两处，忘一处又制造出一组不一致。
2. **首字母头像改为彩色底衬** —— `SiteIcon.fallbackAvatar(seed)`。
   三个列表页左端那个 40dp 头像，取不到站点图标时**原本全是同一个灰底**，
   而兜底恰恰是常态（自建 Vaultwarden 不开图标代理 / 局域网地址 / KDBX 无 origin
   全都走兜底）⇒ 一屏几十条里最显眼的一列元素全是灰的，这正是"配色单一"的**主要来源**。
   现按**标题哈希**取 12 档色相之一（同一条目恒定，不会滚动时"闪色"）。
3. **对比度实测钉住** —— 头像明度取 `0.32` 而非直觉上的 `0.45`：
   白字要求 ≥ 4.5:1，青黄两色的相对亮度远高于红蓝，实测 `L=0.45` 时最差档位只有
   **2.57**（读不清），`L=0.32` 时 12 档全部 ≥ **4.75**。见 `AvatarHueTest`。

> ⚠️ 别把"配色单一"理解成"要加更多颜色"。M3 的**大面积容器**（页面底、卡片、对话框）
> 本就该低饱和，那里加色即是反模式（见 §9）。真正的着力点是**小面积、语义明确**的元素
> （能力图标、头像、徽标）—— 它们天生就是"需要被区分开"的。

### 2.3 语义色（风险表达）

| 语义 | 浅色 | 用途 |
|---|---|---|
| 密码强度：极弱 | `errorContainer` | 生成器、密码健康度 |
| 密码强度：弱 | `#F2B8B5` 系 | 同上 |
| 密码强度：中 | `tertiaryContainer` | 同上 |
| 密码强度：强 | `primaryContainer` | 同上 |
| TOTP 剩余时间 | `primary` → `error`（剩余 < 5 s 变红） | 倒计时进度环 |
| 敏感字段遮罩 | `onSurfaceVariant` + `•` | 密码点阵 |

## 3. 字体

采用 M3 排版比例（Type Scale），中文使用系统字体（Noto Sans CJK / 思源），英文使用 Roboto Flex 或系统默认。

| 样式 | 字号/行高 | 用途 |
|---|---|---|
| `displayLarge` | 57/64 | 空态大标题（谨慎使用） |
| `headlineMedium` | 28/36 | 详情页主标题 |
| `titleLarge` | 22/28 | 顶部大标题栏（展开态）、对话框标题 |
| `titleMedium` | 16/24 | 列表项主标题、卡片标题 |
| `bodyLarge` | 16/24 | 正文、输入内容 |
| `bodyMedium` | 14/20 | 辅助说明、字段标签 |
| `labelLarge` | 14/20 | 按钮 |
| `labelMedium` | 12/16 | 芯片、TOTP 倒计时 |

- **等宽**：密码、TOTP、密钥显示为 `monospace`（避免 `l/1` 混淆），并提供"分段显示"（每 4 位空格）。
- 支持系统字体缩放（最小 0.85x，最大 2.0x）；所有文本容器需通过 `sp` 与可滚动验证。

## 4. 形状与高度

| 组件 | 圆角 |
|---|---|
| 大卡片 / 对话框 / BottomSheet | 28 dp（`ExtraLarge`） |
| 标准卡片、列表项容器 | 16 dp（`Large`） |
| 芯片、输入框 | 8 dp（`Medium`）/ 输入框用 M3 默认 4 dp 顶角 |
| 按钮（Filled/Tonal/Text） | 全圆（`ShapeStyle.Full`） |
| FAB | 16 dp（M3 Expressive 支持方形/圆形切换） |

高度：不依赖阴影，改用 `surface-container` 层级；仅 BottomSheet / 悬浮搜索用 `tonalElevation` 3–6 dp。

## 5. 动效

| 场景 | 规范 |
|---|---|
| 页面切换 | 共享元素（图标 → 详情图标）；无共享元素时用淡入 + 8 dp 位移，`300 ms` `Emphasized` |
| 列表项展开/折叠 | `250 ms` `Standard` |
| 解锁成功 | 图标缩放 + `primaryContainer` 扩散，`400 ms` `Emphasized` |
| 复制成功 | Snackbar + 图标勾选动画 |
| 密码显示/隐藏 | 交叉淡入 `150 ms`，无位移 |
| TOTP 倒计时环 | 线性 `30 s`（与系统帧率无关，按时间插值） |
| 减少动效 | 尊重系统 `Animator duration scale = 0`，降级为瞬时切换 |

## 6. 核心组件规范

### 6.1 应用栏

| 类型 | 使用位置 |
|---|---|
| `TopAppBar`（Small） | 详情页、编辑页、设置二级页 |
| `MediumTopAppBar` | 设置页、生成器 |
| `LargeTopAppBar` | 主列表（Vault 首页，滚动折叠）、库列表 |
| 搜索栏 | 主列表顶部使用 `SearchBar` + `ExpandedFullScreenSearchBar`（M3 Expressive） |

- 滚动行为：主列表用 `exitUntilCollapsedScrollBehavior()`；设置页用 `pinnedScrollBehavior()`。
- 选中态：多选时应用栏切换为 `secondaryContainer` + "已选 N 项" + 批量操作（移动/删除/收藏）。

### 6.2 底部导航

- 紧凑/中等宽度（< 840 dp）：**默认 5 个目的地**：
  **密码库 / 验证器 / 安全中心 / 生成器 / 设置**。
- **可自定义**（参考 Monica 的 Dock）：设置 → 底栏管理，可调整顺序、开关以下可选页：卡包、证件、安全笔记、回收站、总览。总数上限 5（超出提示先关闭其他项），最少 3 项。
- 展开宽度：改用 `NavigationRail`（可显示 5–7 项，可选页全部展开）。
- 底栏项配置存 DataStore，退出登录/重置可恢复默认。

**底栏样式二选一**（参考 Bastion v1.0.325 的胶囊底栏改版，但默认为 Material 3 官方形态）：

| 样式 | 规格 | 说明 |
|---|---|---|
| **贴底（默认）** | 官方 `NavigationBar`，通栏贴底；M3 Expressive 为更矮的 flexible 版本；选中态为图标后 pill | 与系统应用观感一致，自动继承 M3 更新，无障碍语义开箱可用 |
| **悬浮胶囊（可选）** | 自绘 `Row`：胶囊高 **60 dp**，距屏幕底部 **20 dp** 抬离手势导航条；选中态为**色块整体包住图标 + 文字**；中间为添加按钮（左 2 + 中 + 右 2） | 观感更轻盈；**必须**复用 M3 `colorScheme` 与形状 token，并补齐 `semantics` 无障碍语义 |

要点：
- **FAB 与中间加号二选一**：胶囊模式用底栏中间加号（不显示 FAB）；贴底模式保留 FAB。**禁止**两者同时出现。
- 中间添加按钮**只放图标、不放文字**（更紧凑）。
- 底部留白：内容/悬浮元素的底部内边距 = 悬浮层实测高度 + **额外 12 dp** 呼吸空间，避免元素贴住手势条。
- 自绘组件必须提供 `semantics { role = Role.Tab; selected = ... }`，否则 TalkBack 无法识别选中态。

### 6.3 列表项（VaultItemRow）

```
┌────────────────────────────────────────────────┐
│ [40dp 图标]  标题（titleMedium，最多 2 行）      │
│              副标题（bodyMedium，onSurfaceVariant）│
│                                    [TOTP] [收藏] │
└────────────────────────────────────────────────┘
```

| 元素 | 规范 |
|---|---|
| 图标 | 40 dp 圆角容器（`surface-container-high`），内部为单色网站图标或内置图标；加载失败显示首字母（首字母取标题，背景色由 id 哈希生成，保证稳定） |
| 标题 | `titleMedium`，`maxLines = 2`，溢出省略 |
| 副标题 | `bodyMedium`，`onSurfaceVariant`，`maxLines = 1` |
| TOTP 芯片 | 仅当条目含 TOTP 显示；显示当前码 + 环形倒计时 |
| 收藏 | 图标按钮，`Icons.Filled.Star` / `Icons.Outlined.Star`；点击即切换（带 150 ms 缩放反馈） |
| 分隔 | 使用 `HorizontalDivider`（`outlineVariant`，1 dp），左侧与标题对齐 |
| 滑动 | 支持左滑"复制用户名"、右滑"复制密码"（需设置开启，避免误触） |

### 6.4 卡片与分组头

- 分组头：`labelLarge` + `onSurfaceVariant`，sticky 吸顶（`surface-container` 背景）。
- 折叠组（KDBX 群组树）：`NavigationDrawerItem` 风格的行 + 展开箭头 + 条目计数。

### 6.4.1 设置项（参考 Bastion：加大圆角 + 图标底衬）

```
┌────────────────────────────────────────────┐
│ ┌──┐                                        │
│ │🔒│  生物识别解锁                    [ ○] │  图标 40dp 底衬容器
│ └──┘  使用指纹或面容快速解锁                 │  副标题 bodySmall
├────────────────────────────────────────────┤  分组内圆角 16dp
│ ┌──┐                                        │
│ │⏱│  自动锁定                     15 分钟 › │
│ └──┘                                        │
└────────────────────────────────────────────┘
```

| 元素 | 规范 |
|---|---|
| 容器 | 分组卡片：`surface-container-low`，圆角 **16 dp**（比 M3 列表默认更大，视觉更整） |
| 图标 | 前置 40 dp 圆角底衬（`secondaryContainer` / `surface-container-high`），内部 24 dp 图标；无图标时文字左对齐不留空位 |
| 标题 | `bodyLarge`；副标题 `bodySmall` + `onSurfaceVariant` |
| 尾部 | `Switch` / 当前值文本 + `›` / 纯 `›` |
| 分割 | 分组内用 1 dp `outlineVariant`，左右内缩 16 dp（不贯通） |
| 危险项 | 标题与图标用 `error` 色 |

### 6.5 对话框

| 类型 | 用途 | 规范 |
|---|---|---|
| `BasicAlertDialog` | 确认删除、丢弃修改 | 标题 + 说明 + 两个 TextButton（`error` 色用于破坏性确认） |
| `AlertDialog` with icon | 生物识别失败、密码错误 | 顶部 24 dp 图标 + 标题 + 说明 |
| 全屏对话框 | 密码生成器、选择器 | `Scaffold` + 关闭/保存 |
| BottomSheet | 排序/筛选、URI 匹配规则选择 | `ModalBottomSheet`，圆角 28 dp |

### 6.6 输入组件

| 场景 | 组件 |
|---|---|
| 普通文本 | `OutlinedTextField`（M3 默认描边风格） |
| 密码 | `OutlinedTextField` + `PasswordVisualTransformation` + 尾部显隐 `IconButton` |
| 主密码 | 同上，额外：`imeOptions = Done`、`keyboardOptions` 禁用自动更正/个性化学习 |
| 搜索 | `SearchBar` + 建议词行 |
| 开关 | `Switch`（危险项用 `errorContainer` 底色） |
| 选择 | `SegmentedButton`（单/多选）、`Slider`（生成器长度）、`FilterChip`（筛选） |
| 日期 | `DatePicker`（历史记录筛选） |

### 6.7 反馈组件

| 组件 | 用法 |
|---|---|
| `Snackbar` | 复制成功（带"撤销"）、保存成功、同步完成；**禁止**显示敏感内容 |
| `LinearProgressIndicator` | 同步、KDF 计算（带百分比与"取消"） |
| `CircularProgressIndicator` | 按钮内加载、附件下载 |
| `PullRefresh` | 主列表下拉触发同步 |
| 空态 | 插画/大图标 + `headlineSmall` 标题 + `bodyMedium` 说明 + 1 个主操作 |

## 7. 图标

- 统一 `Material Symbols`（`androidx.compose.material.icons`），风格 `Outlined`（默认）/ `Filled`（选中态）。
- 网站图标：Coil 加载，失败回退首字母；可关闭网络请求。
- 所有图标必须有 `contentDescription`（或标记 `null` 表示纯装饰）。

## 8. 无障碍

| 项 | 要求 |
|---|---|
| 对比度 | 文本 ≥ 4.5:1，大文本 ≥ 3:1，图标/控件边界 ≥ 3:1 |
| 触摸目标 | 最小 48 × 48 dp |
| 语义 | 密码字段标注"密码"而非内容；敏感值在 TalkBack 下提供"隐藏/朗读"切换 |
| 屏幕阅读 | 列表项合并语义（`Modifier.semantics(mergeDescendants = true)`），一次朗读"标题 + 用户名" |
| 字体缩放 | 2.0x 下不裁切（关键页面需实测） |
| 动效 | 支持"减少动效"系统设置 |
| 焦点 | 键盘/开关键盘导航顺序正确（`focusOrder`） |

## 9. 反模式（明确禁止）

1. 用彩虹渐变或高饱和大面积色块做主背景。
2. 全屏居中悬浮的"安全锁"装饰图标堆砌。
3. 破坏性操作（永久删除、清空回收站）使用默认主色按钮。
4. 同一页面出现两个 `ExtendedFAB`。
5. 用 Toast 承载需要用户处理的信息（应用 Snackbar + 动作）。
6. 自定义 `TextField` 边框覆盖 M3 描边规范。
