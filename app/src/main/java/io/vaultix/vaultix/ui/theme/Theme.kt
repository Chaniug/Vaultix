package io.vaultix.vaultix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * **OLED 纯黑模式的 surface 家族**（2026-11-12 #285）。
 *
 * ⚠️ 修的是 #283「深色模式下常态化底栏外围有点脏脏的」在**色板层**的残留：
 * 旧实现只 `copy(background = Black, surface = Black)`，而底栏 / 卡片 / 分组头用的
 * `surfaceContainerLow / High / Highest` 以及 `surfaceBright` 仍是 `#1A1A19 ~ #333332`
 * 的深灰 → 纯黑背景上浮出一块块灰面，观感就是「脏」。#283 只重做了玻璃绘制层的
 * 光照，没动这套容器底色。
 *
 * ⇒ 整个 surface 家族一起压到**纯黑起算的中性灰阶**，并保持 M3 深色单调性
 *   （Low → Highest 逐级变亮，见 `VaultixBrandColorTest.surfaceContainersAreMonotonic`）：
 *   底色越贴近黑，卡片越靠一层「光」而不是一块「灰」立起来，玻璃高光才不显脏。
 *
 * ⚠️ 刻意**不**把 `surfaceContainerLowest` 也设成纯黑：它要留在 `surface` 之下
 *   （M3 定义 `Lowest < surface`），全设为黑会让「最低层」和「页面底」糊成一片。
 * ⚠️ `onSurface` / `onSurfaceVariant` 不动：它们原本就对浅色文字，压黑背景只会让对比度更高。
 *
 * 纯函数、不依赖 Android 运行时 ⇒ 可在 JVM 单测里直接验单调性与对比度。
 *
 * ⚠️ 灰阶一律抽成**属性常量**再引用：detekt `MagicNumber` 会拦函数参数里的
 *    `Color(0xFF…)` 裸字面量（#285 首次跑 detekt 就红在 7 行上），而属性声明里的
 *    同一个字面量不报 —— 与 `VaultixBrandColor` 的 fixed 色值是同一形态。
 */
private val PureBlackSurfaceDim = Color(0xFF080808)
private val PureBlackSurfaceBright = Color(0xFF232323)
private val PureBlackContainerLowest = Color(0xFF050505)
private val PureBlackContainerLow = Color(0xFF0D0D0D)
private val PureBlackContainer = Color(0xFF141414)
private val PureBlackContainerHigh = Color(0xFF1C1C1C)
private val PureBlackContainerHighest = Color(0xFF242424)

private fun applyPureBlack(base: ColorScheme): ColorScheme = base.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = PureBlackSurfaceDim,
    surfaceBright = PureBlackSurfaceBright,
    surfaceContainerLowest = PureBlackContainerLowest,
    surfaceContainerLow = PureBlackContainerLow,
    surfaceContainer = PureBlackContainer,
    surfaceContainerHigh = PureBlackContainerHigh,
    surfaceContainerHighest = PureBlackContainerHighest,
)

/**
 * **Material You 动态取色是否可用**（Android 12 / API 31+）。
 *
 * ⚠️ #285：这是「动态取色版本门控」的**唯一真源** —— 本文件的取色分支与设置页
 *    「低版本禁用 + 说明」都调用它，别再各写一遍 `SDK_INT >= S`（两个真源必然漂移）。
 *    与玻璃模糊的 `isLiquidGlassBlurSupported` 同款：显式 [sdkInt] 只为 JVM 单测
 *    能验门槛逻辑，业务代码不要传。
 *
 * ⚠️ 为什么低版本**必须**从 UI 上禁掉：`dynamicLightColorScheme` / `dynamicDarkColorScheme`
 *    只在 S+ 存在；若开关能点却不生效，就是本项目最忌讳的「静默降级」—— 用户会
 *    反复拨动一个坏掉的开关。禁用 + 副标题说明才是诚实的做法。
 */
fun supportsDynamicColor(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/**
 * 深色色板（品牌色，见 [VaultixBrandColor]）。
 *
 * ⚠️ 2026-10-05 换掉 `darkColorScheme()` 无参默认值：那是 M3 **基线紫**
 * （`primary = #D0BCFF` 一系），且它的 surface 家族同样偏紫
 * （`#141218` / `#1D1B20`，绿通道低于红通道）⇒ 深色下整屏也带紫。
 */
private val DarkColorScheme = VaultixBrandColor.Dark

/**
 * 浅色色板（品牌色，见 [VaultixBrandColor]）。
 *
 * ⚠️ 2026-10-05 换掉 `lightColorScheme()` 无参默认值 —— 这正是用户报告
 * 「默认浅色界面配色不对」的根因，详见 [VaultixBrandColor] 的 KDoc。
 *
 * 保留这个别名是为了不改动 `VaultixTheme` 里的 `when` 分支结构：
 * 动态取色与固定色板二选一，**不叠加**。
 */
private val LightColorScheme = VaultixBrandColor.Light

// 全局形状 `VaultixShapes` 已移出本文件，见 `VaultixShapes.kt`：
// 圆角的三档语义（12 小面 / 16 大面 / 50% 药丸）与「为什么不能只改 extraSmall」
// 都在那里推导。本文件只负责把它交给 `MaterialTheme`。

/**
 * 主题模式（对齐 Bastion themeMode 三态）；持久化值为小写名，未知值回退跟随系统。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun from(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Vaultix 全局主题。
 *
 * - [themeMode]：跟随系统 / 强制浅色 / 强制深色（设置页「外观 → 主题模式」，
 *   VaultixPreferences.themeMode）；
 * - [dynamicColor]：Material You 动态取色（Android 12+；低版本回退固定色板）。
 *   ⚠️ #285 默认改为 **false**：用户反馈「默认的浅色配色有点不符合标准」。
 *   根因是 `VaultixPreferences.dynamicColor` 旧默认 `true` ⇒ 首次启动直接吃
 *   **壁纸派生色**，把 [VaultixBrandColor] 里两轮调过（去紫 / 降饱和 / WCAG 实测）
 *   的品牌浅色板整个绕过，还让「动态取色开/关」两档的 fixed 角色色系跳变。
 *   详见 [VaultixBrandColor] 文件头 §五。动态取色仍可在设置里主动开启。
 * - [oledPureBlack]：深色模式下**整套** surface 家族压到纯黑灰阶（#285：不再只改
 *   background / surface 两个角色），对动态取色与固定色板同样生效。
 */
@Composable
fun VaultixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    oledPureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // ⚠️ #285：版本门控走 [supportsDynamicColor] 单一真源，与设置页共用同一个事实。
    val base = when {
        dynamicColor && supportsDynamicColor() ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val colorScheme = if (darkTheme && oledPureBlack) {
        applyPureBlack(base)
    } else {
        base
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = VaultixShapes,
        // 字阶是本项目**自己**的一份（中文适配），不是 M3 默认值 —— 见 [VaultixTypography]
        // 的 KDoc。这里不传的话会静默退回拉丁文那套，表现为"中文正文偏挤、字距漏风"，
        // 而且没有任何编译或门禁会报错。
        typography = VaultixTypography,
        content = content,
    )
}
