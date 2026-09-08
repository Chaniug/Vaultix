package io.vaultix.vaultix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme()
private val LightColorScheme = lightColorScheme()

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
        content = content,
    )
}
