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

### 2.2 主题配置

```kotlin
@Composable
fun VaultixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,          // API 31+ 生效
    contrast: Contrast = Contrast.Standard, // 支持系统高对比度
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        darkTheme -> darkScheme      // 由种子色生成
        else -> lightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = VaultixTypography,
        shapes = VaultixShapes,
        content = content,
    )
}
```

### 2.3 主题策略（参考 Bastion 的多主题设计）

提供 **5 套主题**，用户可随时切换：

| 主题 | 说明 | 适用 |
|---|---|---|
| **自然（Nature）** | 低饱和的自然色系种子色（柔和绿/土色），长时间使用不易疲劳 | 默认推荐 |
| **Material You（动态取色）** | API 31+ 取壁纸主色，与系统浑然一体 | 追求与系统一致的用户 |
| **暗色（Dark）** | 标准 M3 深色（`surface` 为深灰，非纯黑） | OLED 之外的屏幕，省电且对比舒适 |
| **纯黑（AMOLED Black）** | `surface = #000000`，卡片用极深灰区分层次 | OLED 屏幕，省电；密码类应用常见诉求 |
| **RG 护眼** | 降低红/绿通道权重、压低蓝光比例的暖色调变体 | 夜间、对频闪/蓝光敏感的用户 |

补充选项：

| 项 | 选项 |
|---|---|
| 深浅模式 | 跟随系统 / 始终浅色 / 始终深色（与上表主题叠加） |
| 动态取色 | 开 / 关（关闭时用当前主题的静态种子色，仅"Material You"主题强制开启） |
| 对比度 | 标准 / 中 / 高（跟随系统 `Contrast` 设置） |
| 纯黑主题下的强调 | 卡片层级用 `#0A0A0A`–`#1A1A1A` 递进，**不靠阴影**区分 |

> 实现要点：5 套主题共用同一套 `ColorScheme` 生成逻辑（种子色 → `lightColorScheme`/`darkColorScheme`），仅替换种子色与 `surface` 基准值，避免维护 5 份色表。

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
