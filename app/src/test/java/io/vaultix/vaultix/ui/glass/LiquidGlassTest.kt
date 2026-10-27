/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

import android.os.Build
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 液态玻璃的门槛与配色单测。
 *
 * 这里**不**验证"画出来好不好看" —— 那只能在真机上看。可验证的是两类**会静默出错**的点：
 *
 * 1. **API 门槛**：`RenderEffect` 只有 API 31+ 有。低于 31 若仍声称"支持"，用户会看到
 *    开关打开、底栏却不糊 —— 表现为"开关坏了"，而且不报任何错。
 * 2. **填充必须带半透明**：填充 alpha 一旦是 1.0，模糊结果会被整块盖掉，
 *    同样是"开了跟没开一样"。这条最容易在重构配色时被改坏。
 */
class LiquidGlassTest {

    /** API 门槛边界：正好 31 是真支持的起点，30 及以下是降级区。 */
    @Test
    fun `模糊能力以 API 31 为界`() {
        val cases = listOf(
            26 to false, // minSdk
            29 to false,
            30 to false, // Android 11，刚好不支持
            31 to true, // Android 12，支持起点
            33 to true,
            35 to true,
        )
        cases.forEach { (sdk, expected) ->
            assertThat(isLiquidGlassBlurSupported(sdk))
                .isEqualTo(expected)
            assertThat(shouldApplyBlur(requested = true, sdkInt = sdk))
                .isEqualTo(expected)
        }
    }

    /** 开关关闭时，任何系统版本都不挂模糊（不能因为系统支持就擅自开）。 */
    @Test
    fun `开关关闭时不挂模糊`() {
        listOf(26, 30, 31, 35).forEach { sdk ->
            assertThat(shouldApplyBlur(requested = false, sdkInt = sdk)).isFalse()
        }
    }

    /** 默认参数走真实 `Build.VERSION.SDK_INT`，别把写死的值传进业务调用点。 */
    @Test
    fun `默认参数取真实系统版本`() {
        assertThat(isLiquidGlassBlurSupported())
            .isEqualTo(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }

    /**
     * 填充透明度：深浅色都必须 < 1（否则模糊被盖掉），且深色更透。
     *
     * "深色更透"不是审美偏好 —— 深色背景本身对比度低，填充越实越像一块塑料板。
     */
    @Test
    fun `填充保持半透明且深色更透`() {
        assertThat(LiquidGlass.FILL_ALPHA_LIGHT).isLessThan(1f)
        assertThat(LiquidGlass.FILL_ALPHA_DARK).isLessThan(1f)
        assertThat(LiquidGlass.FILL_ALPHA_DARK).isLessThan(LiquidGlass.FILL_ALPHA_LIGHT)

        val surface = Color(0xFF1A1C1E)
        val light = liquidGlassStyle(darkTheme = false, surface = surface, onSurface = Color.Black)
        val dark = liquidGlassStyle(darkTheme = true, surface = surface, onSurface = Color.White)
        assertThat(light.fill.alpha).isLessThan(1f)
        assertThat(dark.fill.alpha).isLessThan(1f)
        assertThat(dark.fill.alpha).isLessThan(light.fill.alpha)
    }

    /** 填充色只改 alpha，不许改 RGB（底栏底色要跟着主题走）。 */
    @Test
    fun `填充只改透明度不改色相`() {
        val surface = Color(0xFF3366CC)
        val style = liquidGlassStyle(darkTheme = false, surface = surface, onSurface = Color.Black)
        assertThat(style.fill.red).isEqualTo(surface.red)
        assertThat(style.fill.green).isEqualTo(surface.green)
        assertThat(style.fill.blue).isEqualTo(surface.blue)
    }

    /** 描边深色下更淡；高光恒为白（受光边不随主题变色）。 */
    @Test
    fun `描边深色更淡且高光为白`() {
        val surface = Color(0xFF202020)
        val light = liquidGlassStyle(darkTheme = false, surface = surface, onSurface = Color.Black)
        val dark = liquidGlassStyle(darkTheme = true, surface = surface, onSurface = Color.White)
        assertThat(dark.stroke.alpha).isLessThan(light.stroke.alpha)
        assertThat(light.sheen).isEqualTo(Color.White)
        assertThat(dark.sheen).isEqualTo(Color.White)
    }

    /**
     * 降级补偿必须能让填充达到"不透"（可读性兜底），但补偿量要小到**只补不盖**。
     *
     * 若 `FALLBACK_EXTRA_ALPHA` 大到 `FILL + EXTRA >= 1`，降级态就退化成实心底栏 ——
     * 用户会觉得"开了开关反而更丑"。
     */
    @Test
    fun `降级补偿把填充推到接近不透但不越界`() {
        assertThat(LiquidGlass.FALLBACK_EXTRA_ALPHA).isGreaterThan(0f)
        val worst = LiquidGlass.FILL_ALPHA_LIGHT + LiquidGlass.FALLBACK_EXTRA_ALPHA
        assertThat(worst).isAtLeast(0.75f)
        assertThat(worst).isLessThan(1f)
    }

    /** 上沿高光带只占顶部一小段，且不能是 0（否则玻璃退化成一块纯色）。 */
    @Test
    fun `上沿高光带比例合理`() {
        assertThat(LiquidGlass.EDGE_HIGHLIGHT_FRACTION).isGreaterThan(0f)
        assertThat(LiquidGlass.EDGE_HIGHLIGHT_FRACTION).isLessThan(0.5f)
        assertThat(LiquidGlass.TOP_SHEEN_ALPHA).isGreaterThan(0f)
        assertThat(LiquidGlass.TOP_SHEEN_ALPHA).isAtMost(1f)
    }
}
