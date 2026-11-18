package io.vaultix.vaultix.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 主题色档位（2026-11-18，用户拍板「机制 A · 预设 4 档」）。
 *
 * ## 范围刻意收窄：只换**强调色**，不动 surface / error / 形状 / 字阶
 *
 * M3 的 `ColorScheme` 有 40+ 角色，但真正构成「这套界面是什么颜色」的是
 * primary / secondary / tertiary 三组强调色。surface 家族是**中性灰**（G-R ≥ 0），
 * error 是**语义危险红**——这两族跟着主题色走只会破坏对比度与语义，
 * 所以四档共用 [VaultixBrandColor] 里那一套，只有强调色按档替换。
 *
 * ## 为什么 default 仍是蓝
 *
 * 用户拍板「默认档保留现行低饱和蓝不动」：BLUE 档的色值**全部来自
 * [VaultixBrandColor]**，不是另写一份相近的常量 —— 否则两个真源必然漂移。
 *
 * ## 色值不是手挑的
 *
 * TEAL / SLATE / INDIGO 三档由固定色相（186° / 210° / 236°）按 HSL 生成，
 * 每一对前景/背景都过 WCAG 2.x 文本级 4.5:1；饱和度主色 ≤58%、容器 ≤40%、亮度 ≤96%。
 * 判据见 [VaultixAccentPaletteTest]。原品牌色板的「主色锁蓝区 200–250°」只约束
 * BLUE 档（那是品牌身份），可选档改用「各自色相稳定 + 对比度」判据。
 */
enum class VaultixAccentPalette(val raw: String) {
    BLUE("blue"),
    TEAL("teal"),
    SLATE("slate"),
    INDIGO("indigo"),
    ;

    companion object {
        /** 未知值（含旧版本残留）一律回退默认蓝档。 */
        fun from(raw: String?): VaultixAccentPalette =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: BLUE
    }
}

/** 一组强调色：浅色档 + 深色档各 9 个角色 + 4 个 fixed 角色（深浅同值）。 */
private data class AccentSet(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val darkSecondary: Color,
    val darkOnSecondary: Color,
    val darkSecondaryContainer: Color,
    val darkOnSecondaryContainer: Color,
    val fixed: Color,
    val fixedDim: Color,
    val onFixed: Color,
    val onFixedVariant: Color,
)

/** TEAL：色相 186°（松石青，密码管理器品类里少见）。白字 4.82:1。 */
private val TealAccent = AccentSet(
    primary = Color(0xFF337C84),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1EEEF),
    onPrimaryContainer = Color(0xFF193E43),
    inversePrimary = Color(0xFFA9D5DA),
    secondary = Color(0xFF3C767C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6EFEF),
    onSecondaryContainer = Color(0xFF205055),
    darkPrimary = Color(0xFFA9D5DA),
    darkOnPrimary = Color(0xFF123438),
    darkPrimaryContainer = Color(0xFF234043),
    darkOnPrimaryContainer = Color(0xFFD5EAEC),
    darkSecondary = Color(0xFFA1CACE),
    darkOnSecondary = Color(0xFF123034),
    darkSecondaryContainer = Color(0xFF233B3E),
    darkOnSecondaryContainer = Color(0xFFD3E8E9),
    fixed = Color(0xFFD1E4E6),
    fixedDim = Color(0xFF9CC5C9),
    onFixed = Color(0xFF0F2B2E),
    onFixedVariant = Color(0xFF205055),
)

/** SLATE：色相 210°（石墨蓝灰，最克制）。白字 6.40:1。 */
private val SlateAccent = AccentSet(
    primary = Color(0xFF4C6176),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E8EF),
    onPrimaryContainer = Color(0xFF192E43),
    inversePrimary = Color(0xFFA9C2DA),
    secondary = Color(0xFF3F6183),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6EBEF),
    onSecondaryContainer = Color(0xFF203B55),
    darkPrimary = Color(0xFFA9C2DA),
    darkOnPrimary = Color(0xFF122A3C),
    darkPrimaryContainer = Color(0xFF233343),
    darkOnPrimaryContainer = Color(0xFFD5E3EF),
    darkSecondary = Color(0xFFA1B8CE),
    darkOnSecondary = Color(0xFF122736),
    darkSecondaryContainer = Color(0xFF23303E),
    darkOnSecondaryContainer = Color(0xFFD3E1EB),
    fixed = Color(0xFFD1DBE6),
    fixedDim = Color(0xFF9CB2C9),
    onFixed = Color(0xFF0F1F2E),
    onFixedVariant = Color(0xFF203B55),
)

/** INDIGO：色相 236°（深靛，仍在蓝区但更冷）。白字 6.91:1。 */
private val IndigoAccent = AccentSet(
    primary = Color(0xFF4D539D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E2EF),
    onPrimaryContainer = Color(0xFF191C43),
    inversePrimary = Color(0xFFA9ADDA),
    secondary = Color(0xFF4C529E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E6EF),
    onSecondaryContainer = Color(0xFF272C68),
    darkPrimary = Color(0xFFA9ADDA),
    darkOnPrimary = Color(0xFF121534),
    darkPrimaryContainer = Color(0xFF232543),
    darkOnPrimaryContainer = Color(0xFFD5D6EA),
    darkSecondary = Color(0xFFA1A4CE),
    darkOnSecondary = Color(0xFF121432),
    darkSecondaryContainer = Color(0xFF23253E),
    darkOnSecondaryContainer = Color(0xFFD3D4E7),
    fixed = Color(0xFFD1D2E6),
    fixedDim = Color(0xFF9C9FC9),
    onFixed = Color(0xFF0F112E),
    onFixedVariant = Color(0xFF272C68),
)

/** 按档取强调色集；BLUE 档走 null（= 完全沿用 [VaultixBrandColor]，零重复定义）。 */
private fun accentOf(palette: VaultixAccentPalette): AccentSet? = when (palette) {
    VaultixAccentPalette.BLUE -> null
    VaultixAccentPalette.TEAL -> TealAccent
    VaultixAccentPalette.SLATE -> SlateAccent
    VaultixAccentPalette.INDIGO -> IndigoAccent
}

/**
 * 取某档的浅色色板。
 *
 * BLUE 档直接返回 [VaultixBrandColor.Light] —— **不复制任何色值**。
 * 其余档只覆盖 primary / secondary 两组（含 fixed），tertiary 刻意保留
 * [VaultixBrandColor] 的绿色系：它是「与品牌主色互补的第三种强调色」，
 * 跟着主题色漂移只会让三档挤在同一色相里，失去层级。
 */
internal fun colorSchemeOf(palette: VaultixAccentPalette, dark: Boolean): ColorScheme {
    val base = if (dark) VaultixBrandColor.Dark else VaultixBrandColor.Light
    val accent = accentOf(palette) ?: return base
    return if (dark) {
        base.copy(
            primary = accent.darkPrimary,
            onPrimary = accent.darkOnPrimary,
            primaryContainer = accent.darkPrimaryContainer,
            onPrimaryContainer = accent.darkOnPrimaryContainer,
            inversePrimary = accent.primary,
            secondary = accent.darkSecondary,
            onSecondary = accent.darkOnSecondary,
            secondaryContainer = accent.darkSecondaryContainer,
            onSecondaryContainer = accent.darkOnSecondaryContainer,
            primaryFixed = accent.fixed,
            primaryFixedDim = accent.fixedDim,
            onPrimaryFixed = accent.onFixed,
            onPrimaryFixedVariant = accent.onFixedVariant,
            secondaryFixed = accent.fixed,
            secondaryFixedDim = accent.fixedDim,
            onSecondaryFixed = accent.onFixed,
            onSecondaryFixedVariant = accent.onFixedVariant,
        )
    } else {
        base.copy(
            primary = accent.primary,
            onPrimary = accent.onPrimary,
            primaryContainer = accent.primaryContainer,
            onPrimaryContainer = accent.onPrimaryContainer,
            inversePrimary = accent.inversePrimary,
            secondary = accent.secondary,
            onSecondary = accent.onSecondary,
            secondaryContainer = accent.secondaryContainer,
            onSecondaryContainer = accent.onSecondaryContainer,
            primaryFixed = accent.fixed,
            primaryFixedDim = accent.fixedDim,
            onPrimaryFixed = accent.onFixed,
            onPrimaryFixedVariant = accent.onFixedVariant,
            secondaryFixed = accent.fixed,
            secondaryFixedDim = accent.fixedDim,
            onSecondaryFixed = accent.onFixed,
            onSecondaryFixedVariant = accent.onFixedVariant,
        )
    }
}
