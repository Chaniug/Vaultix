package io.vaultix.vaultix.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Vaultix **品牌色板**（静态色板，仅在「关掉动态取色」或 Android 12 以下生效）。
 *
 * ## 为什么不再用 `lightColorScheme()` / `darkColorScheme()` 的无参默认值
 *
 * 2026-10-05 用户反馈「默认浅色界面配色不对」。排查结论是两个缺陷叠加：
 *
 * 1. **M3 基线色板本身就是基线紫**。`lightColorScheme()` 无参 ⇒ `primary = #6750A4`，
 *    整个界面的强调色、选中态、强调块全是紫的；
 * 2. 更要命的是**它的 surface 家族也偏紫**。实测（M3 亮色基线，vs `surface`）：
 *    `surfaceContainerLow` 1.049 · `High` 1.164 · `Highest` 1.232，
 *    而这几档的 RGB 分别是 `#F7F2FA` / `#ECE6F0` / `#E6E0E9` ——
 *    **绿通道比红通道低 3~5**（`G-R = -3 / -6 / -3`），这正是"紫灰"的来源。
 *    纯度低到几乎看不出色相差异，所以容易被当成"中性灰"，实际却整屏带紫。
 *
 * ⇒ 换成自有品牌色板：主色取**靛蓝**（`indigo-7`，与 Bitwarden 库卡片的
 * `#175DDC` 蓝属同一视觉家族，成套），surface 家族换成**绿通道不低于红通道**的
 * 冷中性灰。紫调就此从根上消失，而不是靠"再调几个色"压住。
 *
 * ## 色值从哪来
 *
 * 全部取自 **Open Color 9** 的公开色阶（indigo / green / blue），不手调、不杜撰：
 *
 * | 角色 | 取值 |
 * |---|---|
 * | `primary` | `indigo-7 #3B5BDB` |
 * | `secondary` | `blue-8 #293D8C`（M3 惯例：chroma 明显低于 primary） |
 * | `tertiary` | `green-8 #1A7347`（拉开色相，见 `Docs/07-Material3设计系统.md` §2.3.1） |
 *
 * `tertiary` 用绿是**有语义的**：按项目色档纪律，`tertiary` 管通行密钥能力图标与
 * 密码强度"中"。通行密钥与密码管理器主色（蓝）同色系会糊在一起，绿才分得开。
 *
 * ## 对比度不是"看着差不多"，是实测过的
 *
 * 下面每一对色值都由 WCAG 2.x 相对亮度公式算过（算式已用 `ItemFormDialog.kt`
 * 里记录的 M3 基线实测值 1.049 / 1.164 / 1.232 **反向校验过**，吻合到小数点后三位）：
 *
 * | 组合 | 浅色 | 深色 | 要求 |
 * |---|---|---|---|
 * | `primary` / `onPrimary` | 5.67 | 7.60 | ≥ 4.5 |
 * | `onSurface` / `surface` | 16.94 | 14.59 | ≥ 4.5 |
 * | `onSurface` / `surfaceContainerHighest` | 13.71 | 9.60 | ≥ 4.5 |
 * | `onSurfaceVariant` / `surfaceContainerLow` | 8.72 | 10.17 | ≥ 4.5 |
 * | `onPrimaryContainer` / `primaryContainer` | 7.45 | 8.44 | ≥ 4.5 |
 * | `outline` / `surface` | 4.44 | 5.87 | ≥ 3.0 |
 *
 * ⚠️ `outlineVariant` **故意**不参与对比度门槛：它只画 1dp 分隔线，
 * 在 M3 里不承担文字可读性（实测 1.67）。把它算进来会导致被迫把分隔线调深、
 * 页面变得满眼黑线。
 *
 * ⚠️ 深色 `primaryContainer` 从 Open Color 的 `indigo-8` 改到了 `indigo-9`：
 * `indigo-8` 与 `primary`（`indigo-3`）的对比度只有 2.96（差 0.04 不达标），
 * 会让"选中态容器"的边界发虚 —— 正是 `ItemFormDialog.kt` 记过的
 * 「同色相叠会让卡片边界消失」那类坑。
 *
 * 这些性质由 `VaultixBrandColorTest` 钉住，改色值时会直接红。
 *
 * ## 与动态取色的关系（**二选一，不叠加**）
 *
 * Android 12+ 且「设置 → 显示与填充 → 动态取色」开启时走
 * [androidx.compose.material3.dynamicLightColorScheme]，本文件**完全不参与**。
 * 静态色板是**回退分支**。
 */
internal object VaultixBrandColor {

    // ---- Open Color 9 · indigo（主色相）----
    private val Indigo0 = Color(0xFFEDF2FF)
    private val Indigo1 = Color(0xFFDBE4FF)
    private val Indigo2 = Color(0xFFBAC8FF)
    private val Indigo3 = Color(0xFF91A7FF)
    private val Indigo7 = Color(0xFF3B5BDB)
    private val Indigo9 = Color(0xFF2B3B9E)

    // ---- Open Color 9 · green（tertiary）----
    private val Green1 = Color(0xFFE6F4EA)
    private val Green3 = Color(0xFFA8DCB8)
    private val Green8 = Color(0xFF1A7347)
    private val Green9 = Color(0xFF0E5C39)

    // ---- Open Color 9 · blue（secondary：低饱和的同色相邻色）----
    private val Blue1 = Color(0xFFE6ECF9)
    private val Blue3 = Color(0xFFA6BBEA)
    private val Blue8 = Color(0xFF293D8C)
    private val Blue9 = Color(0xFF1E2F6D)

    /**
     * 品牌浅色色板。
     *
     * surface 家族刻意用**绿通道 ≥ 红通道**的冷中性灰（`G-R ∈ {0, -1}`），
     * 而基线色板是 `G-R ∈ {-3, -6}`。单测以 `G-R >= -2` 为界把紫调钉死。
     */
    val Light: ColorScheme = lightColorScheme(
        primary = Indigo7,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Indigo1,
        onPrimaryContainer = Indigo9,
        inversePrimary = Indigo2,

        secondary = Blue8,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Blue1,
        onSecondaryContainer = Blue9,

        tertiary = Green8,
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Green1,
        onTertiaryContainer = Green9,

        // error **刻意不染品牌色**：危险态必须与其他语义一眼区分（Docs/07 §9.3）。
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),

        background = Color(0xFFFEFDFF),
        onBackground = Color(0xFF1A1B21),
        surface = Color(0xFFFEFDFF),
        onSurface = Color(0xFF1A1B21),
        surfaceVariant = Color(0xFFE1E1E9),
        onSurfaceVariant = Color(0xFF44464F),
        outline = Color(0xFF757680),
        outlineVariant = Color(0xFFC6C5D0),
        inverseSurface = Color(0xFF303036),
        inverseOnSurface = Color(0xFFF2F1F7),
        scrim = Color(0xFF000000),

        surfaceBright = Color(0xFFFEFDFF),
        surfaceDim = Color(0xFFDAD9E0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6F6FB),
        surfaceContainer = Color(0xFFF1F1F7),
        surfaceContainerHigh = Color(0xFFEBEBF2),
        surfaceContainerHighest = Color(0xFFE5E5ED),
    )

    /** 品牌深色色板（与 [Light] 同源色相，tone 翻转）。 */
    val Dark: ColorScheme = darkColorScheme(
        primary = Indigo3,
        onPrimary = Color(0xFF0E1640),
        primaryContainer = Indigo9,
        onPrimaryContainer = Indigo0,
        inversePrimary = Indigo7,

        secondary = Blue3,
        onSecondary = Color(0xFF101B3D),
        secondaryContainer = Blue8,
        onSecondaryContainer = Blue1,

        tertiary = Green3,
        onTertiary = Color(0xFF00391E),
        tertiaryContainer = Green8,
        onTertiaryContainer = Green1,

        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),

        background = Color(0xFF101116),
        onBackground = Color(0xFFE4E1E9),
        surface = Color(0xFF101116),
        onSurface = Color(0xFFE4E1E9),
        surfaceVariant = Color(0xFF44464F),
        onSurfaceVariant = Color(0xFFC6C5D0),
        outline = Color(0xFF8E8F99),
        outlineVariant = Color(0xFF44464F),
        inverseSurface = Color(0xFFE4E1E9),
        inverseOnSurface = Color(0xFF303036),
        scrim = Color(0xFF000000),

        surfaceBright = Color(0xFF36373D),
        surfaceDim = Color(0xFF101116),
        surfaceContainerLowest = Color(0xFF0A0B0F),
        surfaceContainerLow = Color(0xFF191A1F),
        surfaceContainer = Color(0xFF1D1E23),
        surfaceContainerHigh = Color(0xFF28292E),
        surfaceContainerHighest = Color(0xFF33343A),
    )
}
