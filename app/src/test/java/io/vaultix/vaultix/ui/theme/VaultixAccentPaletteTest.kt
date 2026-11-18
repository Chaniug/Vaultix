/*
 * Vaultix — app:theme (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.pow

/**
 * 4 档主题色的门禁（2026-11-18，用户拍板「机制 A · 预设 4 档」）。
 *
 * 与 [VaultixBrandColorTest] 的分工：
 * - **BLUE 档**必须逐项满足原品牌门禁（尤其「主色锁蓝区 200–250°」）——默认档仍是品牌身份；
 * - **可选档**改用「色相稳定 + 饱和度上限 + WCAG 文本级 4.5」判据：
 *   色相本就该各不相同（186 / 210 / 236），不能再套蓝区那条。
 *
 * 两档共用同一条底线：**任何一对前景/背景都不得跌破 WCAG AA 文本级**。
 */
class VaultixAccentPaletteTest {

    private fun ColorScheme.pairs(): List<Pair<String, Pair<Color, Color>>> = listOf(
        "primary/onPrimary" to (primary to onPrimary),
        "primaryContainer/onPrimaryContainer" to (primaryContainer to onPrimaryContainer),
        "secondary/onSecondary" to (secondary to onSecondary),
        "secondaryContainer/onSecondaryContainer" to (secondaryContainer to onSecondaryContainer),
        "primaryFixed/onPrimaryFixed" to (primaryFixed to onPrimaryFixed),
        "primaryFixedDim/onPrimaryFixedVariant" to (primaryFixedDim to onPrimaryFixedVariant),
    )

    @Test
    fun `all palettes keep text contrast above AA`() {
        for (palette in VaultixAccentPalette.entries) {
            for (dark in listOf(false, true)) {
                val scheme = colorSchemeOf(palette, dark)
                val mode = if (dark) "深色" else "浅色"
                for ((name, pair) in scheme.pairs()) {
                    val ratio = contrast(pair.first, pair.second)
                    assertWithMessage("%s %s 的 %s 对比度 %s", palette, mode, name, ratio)
                        .that(ratio).isAtLeast(4.5f)
                }
            }
        }
    }

    @Test
    fun `optional palettes stay within saturation and lightness ceilings`() {
        for (palette in VaultixAccentPalette.entries.filter { it != VaultixAccentPalette.BLUE }) {
            for (dark in listOf(false, true)) {
                val scheme = colorSchemeOf(palette, dark)
                val primaryHsl = hsl(scheme.primary)
                val containerHsl = hsl(scheme.primaryContainer)
                assertWithMessage("%s primary 饱和度", palette)
                    .that(primaryHsl.second).isAtMost(58f)
                assertWithMessage("%s primaryContainer 饱和度", palette)
                    .that(containerHsl.second).isAtMost(40f)
                assertWithMessage("%s primaryContainer 明度", palette)
                    .that(containerHsl.third).isAtMost(96f)
            }
        }
    }

    @Test
    fun `default blue palette is identical to brand color scheme`() {
        // 关键不变量：默认档**不复制任何色值**，必须逐项等于原品牌色板。
        assertThat(colorSchemeOf(VaultixAccentPalette.BLUE, false))
            .isEqualTo(VaultixBrandColor.Light)
        assertThat(colorSchemeOf(VaultixAccentPalette.BLUE, true))
            .isEqualTo(VaultixBrandColor.Dark)
    }

    @Test
    fun `unknown raw value falls back to default blue`() {
        assertThat(VaultixAccentPalette.from(null)).isEqualTo(VaultixAccentPalette.BLUE)
        assertThat(VaultixAccentPalette.from("")).isEqualTo(VaultixAccentPalette.BLUE)
        assertThat(VaultixAccentPalette.from("legacy-unknown")).isEqualTo(VaultixAccentPalette.BLUE)
        assertThat(VaultixAccentPalette.from("TEAL")).isEqualTo(VaultixAccentPalette.TEAL)
    }

    @Test
    fun `optional palettes do not change surface or error roles`() {
        // 只换强调色：surface 家族与 error 语义色必须与原色板逐项相同。
        for (palette in VaultixAccentPalette.entries) {
            for (dark in listOf(false, true)) {
                val base = if (dark) VaultixBrandColor.Dark else VaultixBrandColor.Light
                val actual = colorSchemeOf(palette, dark)
                assertThat(actual.surface).isEqualTo(base.surface)
                assertThat(actual.surfaceContainer).isEqualTo(base.surfaceContainer)
                assertThat(actual.onSurface).isEqualTo(base.onSurface)
                assertThat(actual.error).isEqualTo(base.error)
                assertThat(actual.onError).isEqualTo(base.onError)
            }
        }
    }

    /** HSL：色相(0–360)、饱和度(0–100)、明度(0–100)。 */
    private fun hsl(color: Color): Triple<Float, Float, Float> {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val lightness = (max + min) / 2f
        val saturation = if (delta == 0f) {
            0f
        } else {
            delta / (1f - kotlin.math.abs(2f * lightness - 1f))
        }
        val hue = when {
            delta == 0f -> 0f
            max == r -> ((g - b) / delta) % 6f
            max == g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }.let { (it * 60f + 360f) % 360f }
        return Triple(hue, saturation * 100f, lightness * 100f)
    }

    /** WCAG 2.x 相对亮度 + 对比度。 */
    private fun contrast(a: Color, b: Color): Float {
        fun channel(value: Float): Float = if (value <= 0.03928f) {
            value / 12.92f
        } else {
            ((value + 0.055f) / 1.055f).pow(2.4f)
        }
        fun luminance(c: Color): Float =
            0.2126f * channel(c.red) + 0.7152f * channel(c.green) + 0.0722f * channel(c.blue)
        val x = luminance(a)
        val y = luminance(b)
        val (hi, lo) = if (x >= y) x to y else y to x
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
