package io.vaultix.vaultix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
