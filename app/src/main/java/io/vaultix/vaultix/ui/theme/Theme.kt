package io.vaultix.vaultix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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

/**
 * 全局形状。
 *
 * ⚠️ 只动 `extraSmall`，因为 **M3 的 `OutlinedTextField` / `TextField` 默认取的就是
 * `shapes.extraSmall`**（基线只有 **4dp**）—— 全 App 的输入框因此全是近直角的
 * "工程原型"观感，正是用户反馈「新建条目页面也丑」的直接来源之一。
 *
 * 抬到 12dp（`Spacing.md`）。**改这一处等于把全 App 的输入框
 * 一次性变圆**，比逐个字段加 `shape = RoundedCornerShape(12.dp)` 稳妥：不会漏、也不会漂移。
 * 其余档位保持 M3 基线，避免顺手改到 Card / Dialog 等无关组件的圆角。
 *
 * ⚠️ 2026-10-19 更正：原文写「与项目里卡片的圆角（`EntryCard` 12dp）对齐」——
 * **这个锚点已经不存在**。`EntryCard.CARD_CORNER` 在 2026-09-16 就上调到 **16dp**
 * （见该文件 KDoc）。所以输入框（12dp）与条目卡（16dp）**当前并不同值**，这是有意的：
 * 输入框是"控件"，卡片是"容器"，同一屏里 12/16 并置不会读成两套规格。
 * ⇒ **不要再拿"与卡片对齐"来论证这个 12**（论证已失效），
 * 也不要为了对齐把它改成 16 —— 那会把每个输入框都撑得过圆。
 * 审计报告：`Docs/progress/audit/ui-design-audit-2026-10-19.md` §2.4。
 *
 * ⚠️ 另注：`medium` 档**没有覆盖**，所以不传 `shape` 的 `Card` 吃的是 M3 基线（12dp），
 * 全项目有 6 处（库卡 / 回收站行 / 导入导出 ×2 / 转换 ×2）。
 * 要统一卡片圆角，得先补 `medium`。
 */
private val VaultixShapes = Shapes(extraSmall = RoundedCornerShape(Spacing.md))

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
 * - [dynamicColor]：Material You 动态取色（Android 12+；低版本回退固定色板）；
 * - [oledPureBlack]：深色模式下 surface/background 用纯黑（省电 / 减少烙印），
 *   对动态取色与固定色板同样生效。
 */
@Composable
fun VaultixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    oledPureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val colorScheme = if (darkTheme && oledPureBlack) {
        base.copy(background = Color.Black, surface = Color.Black)
    } else {
        base
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = VaultixShapes,
        content = content,
    )
}
