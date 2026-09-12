package io.vaultix.vaultix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme()
private val LightColorScheme = lightColorScheme()

/**
 * 全局形状。
 *
 * ⚠️ 只动 `extraSmall`，因为 **M3 的 `OutlinedTextField` / `TextField` 默认取的就是
 * `shapes.extraSmall`**（基线只有 **4dp**）—— 全 App 的输入框因此全是近直角的"工程原型"观感，
 * 正是用户反馈「新建条目页面也丑」的直接来源之一。
 *
 * 抬到 12dp 与项目里卡片的圆角（`EntryCard` 12dp）对齐。**改这一处等于把全 App 的输入框
 * 一次性变圆**，比逐个字段加 `shape = RoundedCornerShape(12.dp)` 稳妥：不会漏、也不会漂移。
 * 其余档位保持 M3 基线，避免顺手改到 Card / Dialog 等无关组件的圆角。
 */
private val VaultixShapes = Shapes(extraSmall = RoundedCornerShape(12.dp))

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
