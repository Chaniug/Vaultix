/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * [VaultixBrandColor] 的单测 —— 钉死 2026-10-05 修掉的「浅色界面配色发紫」缺陷。
 *
 * ## 这类缺陷为什么**必须**有单测
 *
 * 配色 bug 有三个特性，让它特别容易复发：
 *
 * 1. **编译期完全无感**。色值都是合法的 `Color`，detekt / kotlinc 一句都不会说；
 * 2. **CI 不会红**。没有单测的话，CI 四道门禁全绿，"改回基线紫"只是一次看起来
 *    无害的 diff —— 与 W4 踩过的「CI 对勾 ≠ 做对了」是同一类陷阱；
 * 3. **纯靠肉眼守不住**。紫调恰恰是**低饱和**的（M3 基线 `surfaceContainerLow`
 *    的绿通道只比红低 3），低到"看起来像中性灰"。等用户说"配色不对"时，
 *    往往已经换了好几轮改版。
 *
 * ⇒ 判据全部**可量化**，锁在这里。谁把色板改回 `lightColorScheme()` 无参默认值，
 * 或者随手调偏了色相，[notPurple] 立刻会红。
 *
 * ## 与 [io.vaultix.vaultix.ui.common.AvatarHueTest] 同一个思路
 *
 * WCAG 公式是**纯算术**，不依赖 Android 运行时，所以能在 JVM 单测里跑，
 * 而不必靠截图回归。算式的正确性由 [contrastFormulaMatchesDocumentedMeasurements] 钉住。
 */
class VaultixBrandColorTest {

    // ------------------------------------------------------------------
    // WCAG 2.x 相对亮度 / 对比度
    // ------------------------------------------------------------------

    private fun linearize(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

    private fun relativeLuminance(color: Color): Double {
        val r = linearize(color.red)
        val g = linearize(color.green)
        val b = linearize(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 对比度，返回 1.0 ~ 21.0。 */
    private fun contrast(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val hi = max(a, b)
        val lo = min(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** 取 0~255 的通道值（`Color.red` 是 0~1 浮点，乘回便于与文档里的 8 位色值对照）。 */
    private fun channel8(value: Float): Float = value * 255f

    // ------------------------------------------------------------------
    // 算式自校验：拿项目文档里已记录的实测值反向核对
    // ------------------------------------------------------------------

    /**
     * `ItemFormDialog.kt` 的 KDoc 记着 M3 亮色基线的实测对比度
     * （`surfaceContainerLow` 1.049 / `High` 1.164 / `Highest` 1.232）。
     *
     * 这些值是项目此前**独立实测**得到的。若本文件的算式与它们吻合到小数点后三位，
     * 说明算式可信 —— 否则后面所有基于它的断言都不可信，这条就是它们的**地基**。
     */
    @Test
    fun contrastFormulaMatchesDocumentedMeasurements() {
        val baselineSurface = Color(0xFFFEF7FF)
        val checks = listOf(
            Triple(Color(0xFFF7F2FA), baselineSurface, 1.049),
            Triple(Color(0xFFECE6F0), baselineSurface, 1.164),
            Triple(Color(0xFFE6E0E9), baselineSurface, 1.232),
        )
        for ((container, surface, documented) in checks) {
            val computed = contrast(container, surface)
            assertThat(computed).isWithin(TOLERANCE).of(documented)
        }
    }

    // ------------------------------------------------------------------
    // 判据 1：★ surface 家族不偏紫（这次 bug 的正主）
    // ------------------------------------------------------------------

    /**
     * M3 基线色板发紫的**量化签名**是绿通道比红通道低。
     *
     * 实测基线三档：`#F7F2FA` 的 G-R = **-3**、`#ECE6F0` = **-6**、`#E6E0E9` = **-3**。
     * 纯度低到看不出色相，所以没人会发现，只会感觉"配色怪"。
     *
     * ⇒ 判据取 `G-R >= -2`：允许极轻微的冷调（蓝比绿高一点很正常），
     * 但把"绿通道凹陷"这种紫调特征钉死。品牌色板实测是 `G-R ∈ {0, -1}`，有余量。
     */
    @Test
    fun notPurple() {
        for ((name, scheme) in listOf("浅色" to VaultixBrandColor.Light, "深色" to VaultixBrandColor.Dark)) {
            for ((role, color) in scheme.surfaceFamily()) {
                val gMinusR = channel8(color.green) - channel8(color.red)
                assertWithMessage("%s %role=%s 的 G-R=%s", name, color.toHex(), gMinusR)
                    .that(gMinusR)
                    .isAtLeast(NEUTRAL_COLD_TOLERANCE)
            }
        }
    }

    /**
     * 品牌主色**不该是紫**。
     *
     * 这条最直白：把 `primary` 的绿通道钉住。一旦有人图省事换回
     * `lightColorScheme()` 无参默认值（`primary = #6750A4`，G-R = **-21**），
     * 这里会立刻炸掉，而不是等到用户截图反馈。
     */
    @Test
    fun primaryIsNotM3BaselinePurple() {
        for ((name, scheme) in listOf("浅色" to VaultixBrandColor.Light, "深色" to VaultixBrandColor.Dark)) {
            val primary = scheme.primary
            val gMinusR = channel8(primary.green) - channel8(primary.red)
            // 基线紫浅色版 G-R = -21；品牌蓝为 +20 / +22。
            // 界取 0 —— 主色一律不许绿通道凹陷。
            assertWithMessage("%s primary=%s 的 G-R=%s", name, primary.toHex(), gMinusR)
                .that(gMinusR)
                .isAtLeast(0f)
        }
    }

    // ------------------------------------------------------------------
    // 判据 2：文本级对比度 ≥ 4.5:1（WCAG AA）
    // ------------------------------------------------------------------

    @Test
    fun lightSchemeMeetsTextContrast() {
        assertTextPairsMeet(VaultixBrandColor.Light, "浅色", MIN_TEXT_CONTRAST)
    }

    @Test
    fun darkSchemeMeetsTextContrast() {
        assertTextPairsMeet(VaultixBrandColor.Dark, "深色", MIN_TEXT_CONTRAST)
    }

    private fun assertTextPairsMeet(scheme: ColorScheme, label: String, threshold: Double) {
        val pairs = listOf(
            "onSurface/surface" to (scheme.onSurface to scheme.surface),
            "onSurface/surfaceContainerLow" to (scheme.onSurface to scheme.surfaceContainerLow),
            "onSurface/surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
            "onSurface/surfaceContainerHigh" to (scheme.onSurface to scheme.surfaceContainerHigh),
            "onSurface/surfaceContainerHighest" to (scheme.onSurface to scheme.surfaceContainerHighest),
            "onSurface/surfaceBright" to (scheme.onSurface to scheme.surfaceBright),
            "onSurface/surfaceDim" to (scheme.onSurface to scheme.surfaceDim),
            "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
            "onSurfaceVariant/surfaceContainerLow" to (scheme.onSurfaceVariant to scheme.surfaceContainerLow),
            "onSurfaceVariant/surfaceContainerHigh" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
            "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
            "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to
                (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "onTertiaryContainer/tertiaryContainer" to
                (scheme.onTertiaryContainer to scheme.tertiaryContainer),
            "onError/error" to (scheme.onError to scheme.error),
            "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
            "onBackground/background" to (scheme.onBackground to scheme.background),
            "inverseOnSurface/inverseSurface" to (scheme.inverseOnSurface to scheme.inverseSurface),
        )
        for ((name, pair) in pairs) {
            val ratio = contrast(pair.first, pair.second)
            assertWithMessage("%s %s = %s", label, name, ratio).that(ratio).isAtLeast(threshold)
        }
    }

    // ------------------------------------------------------------------
    // 判据 3：非文本（UI 边界）对比度 ≥ 3:1
    // ------------------------------------------------------------------

    @Test
    fun lightSchemeMeetsUiContrast() {
        assertUiPairsMeet(VaultixBrandColor.Light, "浅色", MIN_UI_CONTRAST)
    }

    @Test
    fun darkSchemeMeetsUiContrast() {
        assertUiPairsMeet(VaultixBrandColor.Dark, "深色", MIN_UI_CONTRAST)
    }

    private fun assertUiPairsMeet(scheme: ColorScheme, label: String, threshold: Double) {
        val pairs = listOf(
            "outline/surface" to (scheme.outline to scheme.surface),
            "outline/surfaceContainerLow" to (scheme.outline to scheme.surfaceContainerLow),
            "outline/surfaceContainerHigh" to (scheme.outline to scheme.surfaceContainerHigh),
            "primary/surfaceContainerHigh" to (scheme.primary to scheme.surfaceContainerHigh),
            "primary/primaryContainer" to (scheme.primary to scheme.primaryContainer),
            "tertiary/surface" to (scheme.tertiary to scheme.surface),
            "secondary/surfaceContainerLow" to (scheme.secondary to scheme.surfaceContainerLow),
        )
        for ((name, pair) in pairs) {
            val ratio = contrast(pair.first, pair.second)
            assertWithMessage("%s %s = %s", label, name, ratio).that(ratio).isAtLeast(threshold)
        }
    }

    /**
     * `outlineVariant` **故意不在**上一份校验清单里：它只画 1dp 分隔线，
     * 在 M3 里不承担文字可读性（实测浅色仅 1.67）。
     *
     * 这条断言把"它是故意被排除的"钉下来 —— 免得后人看到 1.67 就误以为是漏算，
     * 跑去把它调深，导致整个页面浮满黑线。
     */
    @Test
    fun outlineVariantIsDecorativeAndStaysLowContrast() {
        val light = VaultixBrandColor.Light
        val ratio = contrast(light.outlineVariant, light.surface)
        assertThat(ratio).isLessThan(DIVIDER_CONTRAST_CEILING)
    }

    // ------------------------------------------------------------------
    // 判据 4：容器色阶必须单调（层级是设计系统的地基）
    // ------------------------------------------------------------------

    /**
     * 五档 `surfaceContainer*` 在浅色下必须**逐级加深**、深色下**逐级变浅**。
     *
     * 这条不是审美偏好，是层级的前提：色阶一乱，"页面 → 卡片 → 对话框"的
     * 分层就说不清，`ItemFormDialog` 那套"底色比宿主低一档"的推理会全线失效。
     *
     * ⚠️ `surfaceContainerLowest` **不参与**单调性：它在浅色下是纯白
     * （比 `surface` 还亮，这是 M3 的定义），不是"最深一档"。
     */
    @Test
    fun surfaceContainersAreMonotonic() {
        // 浅色：Low → Highest 逐级**变暗**，相对亮度必须递减。
        val lightSteps = VaultixBrandColor.Light.orderedContainers()
        for (i in 0 until lightSteps.size - 1) {
            val (nameA, colorA) = lightSteps[i]
            val (nameB, colorB) = lightSteps[i + 1]
            assertWithMessage("浅色 %s=%s 应比 %s=%s 更亮", nameA, colorA.toHex(), nameB, colorB.toHex())
                .that(relativeLuminance(colorA))
                .isGreaterThan(relativeLuminance(colorB))
        }

        // 深色：同一顺序逐级**变亮**，相对亮度必须递增。
        val darkSteps = VaultixBrandColor.Dark.orderedContainers()
        for (i in 0 until darkSteps.size - 1) {
            val (nameA, colorA) = darkSteps[i]
            val (nameB, colorB) = darkSteps[i + 1]
            assertWithMessage("深色 %s=%s 应比 %s=%s 更暗", nameA, colorA.toHex(), nameB, colorB.toHex())
                .that(relativeLuminance(colorA))
                .isLessThan(relativeLuminance(colorB))
        }
    }

    private fun ColorScheme.orderedContainers(): List<Pair<String, Color>> = listOf(
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
    )

    private fun ColorScheme.surfaceFamily(): List<Pair<String, Color>> = listOf(
        "background" to background,
        "surface" to surface,
        "surfaceBright" to surfaceBright,
        "surfaceDim" to surfaceDim,
        "surfaceContainerLowest" to surfaceContainerLowest,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
    )

    private fun Color.toHex(): String {
        val r = (red * 255).toInt().toString(16).padStart(2, '0').uppercase()
        val g = (green * 255).toInt().toString(16).padStart(2, '0').uppercase()
        val b = (blue * 255).toInt().toString(16).padStart(2, '0').uppercase()
        return "#$r$g$b"
    }

    private companion object {
        /** WCAG AA 文本级。 */
        const val MIN_TEXT_CONTRAST = 4.5

        /** WCAG 非文本级（图标、控件边界）。 */
        const val MIN_UI_CONTRAST = 3.0

        /** 绿通道允许的最低值；低于此即带紫调（基线为 -3 ~ -6）。 */
        const val NEUTRAL_COLD_TOLERANCE = -2f

        /** 算式与文档实测值的允许偏差。 */
        const val TOLERANCE = 0.0005

        /** 分隔线（outlineVariant）的对比度上限，见 [outlineVariantIsDecorativeAndStaysLowContrast]。 */
        const val DIVIDER_CONTRAST_CEILING = 3.0
    }
}
