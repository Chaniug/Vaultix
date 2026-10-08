package io.vaultix.vaultix.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Vaultix **品牌色板**（静态色板，仅在「关掉动态取色」或 Android 12 以下生效）。
 *
 * ## 一、不用 M3 基线色板的原因（2026-10-05）
 *
 * 用户反馈「默认浅色界面配色不对」。根因两条：
 *
 * 1. **M3 基线色板本身就是基线紫**。`lightColorScheme()` 无参 ⇒ `primary = #6750A4`；
 * 2. 更要命的是**它的 surface 家族也偏紫**。实测（M3 亮色基线，vs `surface`）：
 *    `surfaceContainerLow` 1.049 · `High` 1.164 · `Highest` 1.232，
 *    而这几档的 RGB 是 `#F7F2FA` / `#ECE6F0` / `#E6E0E9` ——
 *    **绿通道比红通道低 3~5**（`G-R = -3 / -6 / -3`），这正是"紫灰"的来源。
 *
 * ⇒ 紫调从根上消失，而不是靠"再调几个色"压住。
 *
 * ## 二、★ 饱和度：从"靛蓝"降到"低饱和蓝"（2026-10-08，用户反馈「太亮」）
 *
 * 换成靛蓝后用户仍反馈**蓝色太亮**。量化后发现"亮"其实有两层，且**主因是饱和度**
 * 而非明度（实测 HSL）：
 *
 * | 角色 | 上一版 | 饱和度 | 明度 | 问题 |
 * |---|---|---|---|---|
 * | `primary` | `#3B5BDB` (indigo-7) | **69.0%** | 54.5% | 偏"荧光" |
 * | `primaryContainer` | `#DBE4FF` (blue-1) | **100%** | 92.9% | ★ **刺眼大头**：FAB、选中底等**大面积**浅蓝 |
 * | `secondaryContainer` | `#E6ECF9` (blue-1) | 61.3% | 93.9% | 同上 |
 *
 * ⇒ 三条调整：
 * 1. `primary` → Open Color 的 **`blue-8 #3B5BC4`**（饱和度 53.7%，比 indigo-7 低 15 个点，
 *    明度 50%，而对比纯白底反而从 5.67 升到 **6.04**）—— 蓝色身份完整保留；
 * 2. **`primaryContainer` 饱和度从 100% 降到 36.6%**（`#E3E6F2`）—— 这是最大的一刀，
 *    因为它是**大面积**出现的高饱和浅蓝，截图里最扎眼的就是它；
 * 3. `secondary` / `tertiary` 及其容器同步降饱和，避免"某个角色特别跳"。
 *    （`error` **不降** —— 它是语义危险色，见下。）
 *
 * ⚠️ **降饱和不是降可读性** —— 这是本次调整的底线。实测（WCAG 2.x，18 对文本 / 7 对非文本）：
 *
 * | |上一版 | 本版 |
 * |---|---|---|
 * | 浅色 文本级最低 | 5.67（`onPrimary/primary`） | **6.04**（同项，更宽） |
 * | 浅色 非文本级最低 | 3.77（`outline/surfaceContainerHigh`） | 3.77（持平） |
 * | 深色 文本级最低 | 6.79 | 6.77（基本持平） |
 * | 深色 非文本级最低 | 4.51 | **4.57** |
 *
 * ⇒ 饱和度大幅下降的同时**没有任何一对跌破门槛**：文本级最紧的 6.04 仍是 4.5 的 1.34 倍，
 * 非文本级最紧的 3.77 仍是 3.0 的 1.26 倍。**"护眼"不等于"牺牲可读性"**，这是本版的底线。
 * 全部数值由 [VaultixBrandColorTest] 逐对实测，不是估算。
 *
 * ⚠️ **饱和度上限已被单测钉住**：品牌主色 `≤ 58%`、大面积容器 `≤ 40%`、
 * `primary` 色相必须留在蓝区（200°~250°）。
 * 这是本次调整最容易被悄悄改回去的地方 —— 换色时"顺手挑个好看的深蓝"
 * 一不小心就会回到 70%+ 的高饱和，而**高饱和低对比的浅蓝恰恰最刺眼**。
 *
 * ⚠️ 判据**刻意不管 `error` 家族**：危险红是语义色而非品牌色（见 §下方
 * `error` 处注释），实测饱和度 60%~65%，高于上述上限。判据越界去压它，
 * 只会让"危险"这件事变得不危险。
 *
 * ## 三、色值从哪来
 *
 * `primary` 取 **Open Color 9** 的公开色阶（`blue-8`），不手调、不杜撰。
 * 其余浅色容器是**固定色相 228° 下按目标饱和度生成** —— 原因是 Open Color 的
 * `blue-0/1/2`（淡蓝容器档）饱和度**全都是 100%**（实测 96.5% / 92.9% / 87.5% 明度档
 * 均为 S=100%），正是要消除的那一类。
 *
 * ⚠️ 注意：生成值只能落在 8bit 网格上，所以实测色相相对 228° 有最多 **±3.3°** 的
 * 量化偏差（`onSecondaryContainer #1B2352` = 231.3°，最大）。这是**量化误差不是色相漂移**
 * —— 目视无差别，`primaryKeepsBlueHue` 的蓝区（200°~250°）也留了20° 以上余量。
 *
 * 对比度全部由 WCAG 2.x 相对亮度公式实测。算式本身用 `ItemFormDialog.kt`
 * 记录的 M3 基线实测值（1.049 / 1.164 / 1.232）**反向校验过**，吻合到小数点后三位。
 *
 * 这些性质由 `VaultixBrandColorTest` 钉住，改色值时会直接红 —— 配色 bug
 * 编译期完全无感、CI 四道门禁也查不出。
 *
 * ## 四、与动态取色的关系（**二选一，不叠加**）
 *
 * Android 12+ 且「设置 → 显示与填充 → 动态取色」开启时走
 * [androidx.compose.material3.dynamicLightColorScheme]，本文件**完全不参与**。
 * 静态色板是**回退分支**。
 */
internal object VaultixBrandColor {

    // ---- Open Color 9 · blue（主色相；blue-8 是公开常量）----
    private val Blue8 = Color(0xFF3B5BC4)

    /** 大面积浅蓝容器：**固定色相 228°、饱和度 36%** 生成的淡灰蓝（非 Open Color 常量）。 */
    private val BlueContainer = Color(0xFFE3E6F2)

    /** 更浅的大面积底（选中态等）：饱和度再压到 28.6%。 */
    private val BlueShell = Color(0xFFEDEFF5)

    /**
     * 品牌浅色色板。
     *
     * surface 家族刻意用**绿通道 ≥ 红通道**的中性灰（`G-R = 0 ~ +1`），
     * 而 M3 基线是 `G-R = -3 ~ -6`（那正是"紫"的量化签名）。
     */
    val Light: ColorScheme = lightColorScheme(
        primary = Blue8,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = BlueContainer,
        onPrimaryContainer = Color(0xFF1E2A5A),
        inversePrimary = Color(0xFFB8C4E8),

        secondary = Color(0xFF33417F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = BlueShell,
        onSecondaryContainer = Color(0xFF1B2352),

        tertiary = Color(0xFF3F6B4A),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE4EDE6),
        onTertiaryContainer = Color(0xFF16301E),

        // error **刻意不染品牌色**：危险态必须与其他语义一眼区分（Docs/07 §9.3）。
        // 也因此它**不受**饱和度上限约束（实测 S≈60%~65%）—— 判据越界压它只会削弱危险语义。
        error = Color(0xFFA8342A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF6DEDB),
        onErrorContainer = Color(0xFF3A0B07),

        background = Color(0xFFFDFCFB),
        onBackground = Color(0xFF1A1B1A),
        surface = Color(0xFFFDFCFB),
        onSurface = Color(0xFF1A1B1A),
        surfaceVariant = Color(0xFFE2E1E0),
        onSurfaceVariant = Color(0xFF45464A),
        outline = Color(0xFF75767B),
        outlineVariant = Color(0xFFC6C6C5),
        inverseSurface = Color(0xFF303032),
        inverseOnSurface = Color(0xFFF2F1F0),
        scrim = Color(0xFF000000),

        surfaceBright = Color(0xFFFDFCFB),
        surfaceDim = Color(0xFFDAD9D8),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6F5F4),
        surfaceContainer = Color(0xFFF1F0EF),
        surfaceContainerHigh = Color(0xFFEBEAE9),
        surfaceContainerHighest = Color(0xFFE5E4E3),
    )

    /** 品牌深色色板（与 [Light] 同色相，饱和度同样压低）。 */
    val Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFF9DAFD8),
        onPrimary = Color(0xFF1A2450),
        primaryContainer = Color(0xFF2E3A63),
        onPrimaryContainer = Color(0xFFD8E0F2),
        inversePrimary = Blue8,

        secondary = Color(0xFFAEB8DC),
        onSecondary = Color(0xFF1B2352),
        secondaryContainer = Color(0xFF2A3460),
        onSecondaryContainer = Color(0xFFDCE2F5),

        tertiary = Color(0xFF9CC5A6),
        onTertiary = Color(0xFF0E2A19),
        tertiaryContainer = Color(0xFF24462F),
        onTertiaryContainer = Color(0xFFD4E8D9),

        error = Color(0xFFE6A9A2),
        onError = Color(0xFF4A0F09),
        errorContainer = Color(0xFF6E1D17),
        onErrorContainer = Color(0xFFF6DEDB),

        background = Color(0xFF111110),
        onBackground = Color(0xFFE4E3E2),
        surface = Color(0xFF111110),
        onSurface = Color(0xFFE4E3E2),
        surfaceVariant = Color(0xFF45464A),
        onSurfaceVariant = Color(0xFFC6C6C5),
        outline = Color(0xFF8E8F94),
        outlineVariant = Color(0xFF45464A),
        inverseSurface = Color(0xFFE4E3E2),
        inverseOnSurface = Color(0xFF303032),
        scrim = Color(0xFF000000),

        surfaceBright = Color(0xFF373736),
        surfaceDim = Color(0xFF111110),
        surfaceContainerLowest = Color(0xFF0B0B0A),
        surfaceContainerLow = Color(0xFF1A1A19),
        surfaceContainer = Color(0xFF1E1E1D),
        surfaceContainerHigh = Color(0xFF282827),
        surfaceContainerHighest = Color(0xFF333332),
    )
}
