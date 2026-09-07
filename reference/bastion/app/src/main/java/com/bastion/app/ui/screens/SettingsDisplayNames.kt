package com.bastion.app.ui.screens

import android.content.Context
import com.bastion.app.R
import com.bastion.app.data.ColorScheme
import com.bastion.app.data.Language
import com.bastion.app.data.ProgressBarStyle
import com.bastion.app.data.ThemeMode

internal fun getThemeDisplayName(theme: ThemeMode, context: Context): String {
    return when (theme) {
        ThemeMode.SYSTEM -> context.getString(R.string.theme_system)
        ThemeMode.LIGHT -> context.getString(R.string.theme_light)
        ThemeMode.DARK -> context.getString(R.string.theme_dark)
    }
}

internal fun getAppearanceDisplayName(
    theme: ThemeMode,
    oledPureBlackEnabled: Boolean,
    context: Context
): String {
    val themeLabel = getThemeDisplayName(theme, context)
    return if (oledPureBlackEnabled) {
        context.getString(R.string.appearance_with_oled_subtitle, themeLabel)
    } else {
        themeLabel
    }
}

internal fun getLanguageDisplayName(language: Language, context: Context): String {
    return when (language) {
        Language.SYSTEM -> context.getString(R.string.language_system)
        Language.CHINESE -> context.getString(R.string.language_chinese)
    }
}

internal fun getAutoLockDisplayName(minutes: Int, context: Context): String {
    return when (minutes) {
        0 -> context.getString(R.string.auto_lock_immediately)
        1 -> context.getString(R.string.auto_lock_1_minute)
        5 -> context.getString(R.string.auto_lock_5_minutes)
        10 -> context.getString(R.string.auto_lock_10_minutes)
        15 -> context.getString(R.string.auto_lock_15_minutes)
        30 -> context.getString(R.string.auto_lock_30_minutes)
        60 -> context.getString(R.string.auto_lock_1_hour)
        300 -> context.getString(R.string.auto_lock_5_hours)
        1440 -> context.getString(R.string.auto_lock_1_day)
        -1 -> context.getString(R.string.auto_lock_never)
        -2 -> context.getString(R.string.auto_lock_on_restart)
        else -> if (minutes > 0) {
            context.getString(R.string.auto_lock_minutes, minutes)
        } else {
            context.getString(R.string.auto_lock_never)
        }
    }
}

internal fun getColorSchemeDisplayName(colorScheme: ColorScheme, context: Context): String {
    return when (colorScheme) {
        ColorScheme.DEFAULT -> context.getString(R.string.default_color_scheme)
        ColorScheme.OCEAN_BLUE -> context.getString(R.string.ocean_blue_scheme)
        ColorScheme.SUNSET_ORANGE -> context.getString(R.string.sunset_orange_scheme)
        ColorScheme.FOREST_GREEN -> context.getString(R.string.forest_green_scheme)
        ColorScheme.TECH_PURPLE -> context.getString(R.string.tech_purple_scheme)
        ColorScheme.BLACK_MAMBA -> context.getString(R.string.black_mamba_scheme)
        ColorScheme.GREY_STYLE -> context.getString(R.string.grey_style_scheme)
        ColorScheme.WATER_LILIES -> context.getString(R.string.water_lilies_scheme)
        ColorScheme.IMPRESSION_SUNRISE -> context.getString(R.string.impression_sunrise_scheme)
        ColorScheme.JAPANESE_BRIDGE -> context.getString(R.string.japanese_bridge_scheme)
        ColorScheme.HAYSTACKS -> context.getString(R.string.haystacks_scheme)
        ColorScheme.ROUEN_CATHEDRAL -> context.getString(R.string.rouen_cathedral_scheme)
        ColorScheme.PARLIAMENT_FOG -> context.getString(R.string.parliament_fog_scheme)
        ColorScheme.CATPPUCCIN_LATTE -> context.getString(R.string.catppuccin_latte_scheme)
        ColorScheme.CATPPUCCIN_FRAPPE -> context.getString(R.string.catppuccin_frappe_scheme)
        ColorScheme.CATPPUCCIN_MACCHIATO -> context.getString(R.string.catppuccin_macchiato_scheme)
        ColorScheme.CATPPUCCIN_MOCHA -> context.getString(R.string.catppuccin_mocha_scheme)
        ColorScheme.CUSTOM -> context.getString(R.string.custom_color_scheme)
    }
}

internal fun getProgressBarStyleDisplayName(style: ProgressBarStyle, context: Context): String {
    return when (style) {
        ProgressBarStyle.LINEAR -> context.getString(R.string.progress_bar_style_linear)
        ProgressBarStyle.WAVE -> context.getString(R.string.progress_bar_style_wave)
    }
}
