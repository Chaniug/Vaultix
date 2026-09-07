package io.vaultix.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF5C7CFA),
    background = Color(0xFFF8F9FC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF748FFC),
    onPrimary = Color(0xFF0B1437),
    secondary = Color(0xFF91A7FF),
    background = Color(0xFF10142A),
)

@Composable
fun VaultixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
