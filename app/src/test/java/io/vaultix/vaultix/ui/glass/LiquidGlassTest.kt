/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
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
 * 2. **填充必须带半透明（真模糊档）**：填充 alpha 一旦是 1.0，模糊结果会被整块盖掉，
 *    同样是"开了跟没开一样"。这条最容易在重构配色时被改坏。
 *
 * ## 2026-10-27 第二轮：真机截图证伪了第一版的两条"纪律"，这里补上反向锁定
 *
 * 第一版断言「深色更透」并以此为据取了 0.62 / 0.48。真机上一是**图标被一起糊掉**
 * （架构问题，单测测不到，见 `LiquidGlassSource` 的 KDoc），二是**深色下填充太薄**，
 * 玻璃与背景分不开。现在数值直接对齐 chrisbanes/Haze 的 `HazeMaterials` 五档
 * （**全部深色更实**），下面的用例把这个方向钉住。
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
     * 真模糊档的填充必须半透明（否则模糊被盖掉），且**深色更实**。
     *
     * 「深色更实」是 Haze 五档的共同规律（0.55/0.65、0.65/0.80、0.80/0.90…）：
     * 深色主题下背景更暗、模糊结果更接近黑，填充再薄就分不清"玻璃"与"背景"。
     * 第一版取反了（深色 0.48 < 浅色 0.62），真机上玻璃透得连边界都看不出来。
     */
    @Test
    fun `真模糊档填充保持半透明且深色更实`() {
        assertThat(LiquidGlass.FILL_ALPHA_LIGHT).isLessThan(1f)
        assertThat(LiquidGlass.FILL_ALPHA_DARK).isLessThan(1f)
        assertThat(LiquidGlass.FILL_ALPHA_DARK).isGreaterThan(LiquidGlass.FILL_ALPHA_LIGHT)

        val surface = Color(0xFF1A1C1E)
        val light = liquidGlassStyle(darkTheme = false, surface = surface, onSurface = Color.Black)
        val dark = liquidGlassStyle(darkTheme = true, surface = surface, onSurface = Color.White)
        assertThat(light.fill.alpha).isLessThan(1f)
        assertThat(dark.fill.alpha).isLessThan(1f)
        assertThat(dark.fill.alpha).isGreaterThan(light.fill.alpha)
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
     * 降级档（无真模糊）必须**全不透明**。
     *
     * 第一版是"半透明 + 0.18 补偿"（约 0.80），理由是"补到接近不透"。
     * 那在真机上等于**文字能穿过底栏**：底栏浮在滚动列表上，半透明就意味着
     * 底下的条目从图标之间透出来，读不清 —— 这是"不可用"，不是"不够好看"。
     * Haze 对无模糊平台的做法同样是画 flat scrim（`fallbackColorEffect`）。
     * ⇒ 现在降级 = 实色底栏，与第一版的实心观感一致。
     */
    @Test
    fun `降级档直接不透明`() {
        assertThat(LiquidGlass.FALLBACK_ALPHA).isEqualTo(1f)
        val surface = Color(0xFF1A1C1E)
        listOf(true, false).forEach { dark ->
            val style = liquidGlassStyle(
                darkTheme = dark,
                surface = surface,
                onSurface = Color.White,
                blurAvailable = false,
            )
            assertThat(style.fill.alpha).isEqualTo(1f)
            assertThat(style.fill.red).isEqualTo(surface.red)
        }
    }

    /** 默认参数 `blurAvailable = true` —— 老调用点不掉进降级档。 */
    @Test
    fun `blurAvailable 默认按真模糊算`() {
        val surface = Color(0xFF1A1C1E)
        val default = liquidGlassStyle(darkTheme = true, surface = surface, onSurface = Color.White)
        val explicit = liquidGlassStyle(
            darkTheme = true,
            surface = surface,
            onSurface = Color.White,
            blurAvailable = true,
        )
        assertThat(default).isEqualTo(explicit)
        assertThat(default.fill.alpha).isLessThan(1f)
    }

    /** 上沿高光带只占顶部一小段，且不能是 0（否则玻璃退化成一块纯色）。 */
    @Test
    fun `上沿高光带比例合理`() {
        assertThat(LiquidGlass.EDGE_HIGHLIGHT_FRACTION).isGreaterThan(0f)
        assertThat(LiquidGlass.EDGE_HIGHLIGHT_FRACTION).isLessThan(0.5f)
        assertThat(LiquidGlass.TOP_SHEEN_ALPHA).isGreaterThan(0f)
        assertThat(LiquidGlass.TOP_SHEEN_ALPHA).isAtMost(1f)
    }

    /**
     * 深色判定看**实际底色亮度**，不看系统主题。
     *
     * 本 App 有独立的「主题模式」（浅/深/跟随系统）与 OLED 纯黑：App 设深色、系统浅色时，
     * 按系统判会把填充取成浅色档，玻璃立刻不透。这条第一版就有，保留防回归。
     */
    @Test
    fun `深浅判定取底色亮度而非系统主题`() {
        assertThat(isDarkSurface(Color(0xFF0D0E11))).isTrue()
        assertThat(isDarkSurface(Color(0xFF000000))).isTrue()
        assertThat(isDarkSurface(Color(0xFFFDFCFF))).isFalse()
        assertThat(isDarkSurface(Color.White)).isFalse()
    }
}
