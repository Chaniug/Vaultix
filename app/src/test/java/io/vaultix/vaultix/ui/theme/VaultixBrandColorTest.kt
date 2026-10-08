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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * [VaultixBrandColor] 的单测 —— 钉死两次配色反馈背后的缺陷。
 *
 * ## 缺陷一：浅色界面发紫（2026-10-05，用户反馈「默认浅色界面配色不对」）
 *
 * ## 缺陷二：蓝色太刺眼（2026-10-08，用户反馈「这个蓝色太亮了一点」）
 *
 * **"太亮"不等于"明度高"** —— 量化后主因是**饱和度**：
 * 上一版 `primaryContainer` 的 `#DBE4FF` 是 Open Color 的 `blue-1`，
 * 饱和度 **100%**（一个完全饱和的色），大面积铺开时最晃眼。
 * 本版把它压到 36.6%，同时把 WCAG 对比度守在门槛之上。
 *
 * ## 这类缺陷为什么**必须**有单测
 *
 * 配色 bug 有三个特性，让它特别容易复发：
 *
 * 1. **编译期完全无感**。色值都是合法的 `Color`，detekt / kotlinc 一句都不会说；
 * 2. **CI 不会红**。没有单测的话，CI 四道门禁全绿，"改回基线紫"、"挑个好看的深蓝"
 *    都只是一次看起来无害的 diff —— 与 W4 踩过的「CI 对勾 ≠ 做对了」是同一类陷阱；
 * 3. **纯靠肉眼守不住**。紫调恰恰是**低饱和**的（M3 基线 `surfaceContainerLow`
 *    的绿通道只比红低 3），低到"看起来像中性灰"；饱和度则相反，
 *    稍一不注意就从"柔和的蓝"滑回"荧光蓝"。等用户说"配色不对"时，
 *    往往已经换了好几轮改版。
 *
 * ⇒ 判据全部**可量化**，锁在这里。谁把色板改回 `lightColorScheme()` 无参默认值
 * （[primaryIsNotM3BaselinePurple]会红），或者随手挑个高饱和深蓝
 * （[accentSlotsStayBelowSaturationCeiling] 会红），CI 会当场拦下。
 *
 * ## 与 [io.vaultix.vaultix.ui.common.AvatarHueTest] 同一个思路
 *
 * WCAG 公式与 HSL 换算都是**纯算术**，不依赖 Android 运行时，所以能在 JVM 单测里跑，
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

    // ------------------------------------------------------------------
    // 判据 5：★ 饱和度上限（"太亮"的真正病因，2026-10-08 用户反馈）
    // ------------------------------------------------------------------

    /**
     * HSL 饱和度（0~100）。
     *
     * ⚠️ **自己算，不碰 Compose 的 HSL API**：`Color` 确实有 [Color.hsl] 之类的转换，
     * 但沙箱内无Gradle 缓存、无法查证其签名是否稳定 —— 按项目铁律
     * 「符号存在性要实测」，不赌这个 API。通道值 `red/green/blue` 是已在
     * [relativeLuminance] 里实证可用的，这里复用同一组访问器。
     *
     * 实测确认：`8bit → float32 存储 → *255 → Int` 对**全部 256 个通道值精确可逆**
     * （0 偏差），所以这里取整不会引入误差。
     */
    private fun saturationPercent(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        val lightness = (maxChannel + minChannel) / 2f
        if (maxChannel == minChannel) return 0f
        val delta = maxChannel - minChannel
        return (delta / (1f - abs(2f * lightness - 1f)) * 100f).coerceIn(0f, 100f)
    }

    /** HSL 色相（0~360）。灰色（饱和度 0）返回 0。 */
    private fun hueDegrees(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        if (maxChannel == minChannel) return 0f
        val delta = maxChannel - minChannel
        val sector = when (maxChannel) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        val degrees = sector * 60f
        return if (degrees < 0f) degrees + 360f else degrees
    }

    /**
     * ★ **护眼判据：品牌主色不许是高饱和荧光色**。
     *
     * 用户反馈「这个蓝色太亮」，量化后发现"亮"有两层且**主因是饱和度**：
     * `primary` S=69.0%、`primaryContainer` S=**100%**（大面积浅蓝，最刺眼）。
     * 上一版的 `#DBE4FF` 是 Open Color 的 `blue-1` —— 一个**完全饱和**的色，
     * 看着"干净"，实际在大面积铺开时晃眼。
     *
     * ⇒ 上限取 **58%**（本版主色实测最高 55.56%，留 2.44 个点余量）。
     * 界不是随手取的：低于 58% 时色彩开始发灰、失去品牌识别；
     * 高于 60% 时浅色容器就会回到"荧光"观感。
     *
     * ⚠️ **刻意不覆盖 `error` 家族** —— 危险红是语义色不是品牌色（`Docs/07` §9.3），
     * 实测 S≈60%~65%。判据越界去压它，等于为了配色好看而削弱"危险"的辨识度，
     * 那是本末倒置。`error` 的对比度另有 [lightSchemeMeetsTextContrast] 等把关。
     *
     * ⚠️ 上限常量是 `Float` 而非 `Int`：Truth 的 `FloatSubject` 继承
     * `ComparableSubject<Float>`，父类的 `isAtMost(Float)` 重载会被选中。
     * 若把常量改成 `Int`，`Float` 实测值会走 `isAtMost(int)` 重载而**静默截断**
     * （55.56 → 55），判据会变得比预期宽松。
     */
    @Test
    fun accentSlotsStayBelowSaturationCeiling() {
        for ((name, scheme) in schemes()) {
            for ((role, color) in scheme.accentSlots()) {
                val saturation = saturationPercent(color)
                assertWithMessage(
                    "%s %role=%s 的饱和度 %.1f%% 超过上限 %.0f%%",
                    name, color.toHex(), saturation, MAX_ACCENT_SATURATION,
                ).that(saturation).isAtMost(MAX_ACCENT_SATURATION)
            }
        }
    }

    /**
     * ★ **大面积容器不许是高饱和浅色**（用户反馈"太亮"的头号病灶）。
     *
     * `primaryContainer` / `secondaryContainer` / `tertiaryContainer` 是 FAB、
     * 选中态底色、分组底色 —— 一次出现就是**成片面积**，人眼对大面积高饱和最敏感。
     *
     * ⇒ 上限取 **40%**（float32 实测最高 39.13%，留 0.87 个点）。
     * 上一版的 `#DBE4FF`是 **S=100%**，整整超标 60 个点。
     *
     * 同时钉住**明度 ≤ 96%**：饱和度降下来后，如果明度顶到 98%+ 仍会"发白刺眼"。
     * 两条一起，才能真正把"大面积浅蓝"这件事按住。
     *
     * ⚠️ 同样受 [accentSlotsStayBelowSaturationCeiling] 里那条 Truth 重载陷阱影响：
     * 常量必须保持 `Float`，改成 `Int` 会截断小数、把 39.13% 判成 39%。
     */
    @Test
    fun largeAreaContainersStayMuted() {
        for ((name, scheme) in schemes()) {
            for ((role, color) in scheme.largeAreaContainers()) {
                val saturation = saturationPercent(color)
                val lightness = (max(color.red, max(color.green, color.blue)) +
                    min(color.red, min(color.green, color.blue))) / 2f * 100f
                assertWithMessage(
                    "%s %role=%s 饱和度 %.1f%% 超过容器上限 %.0f%%",
                    name, color.toHex(), saturation, MAX_CONTAINER_SATURATION,
                ).that(saturation).isAtMost(MAX_CONTAINER_SATURATION)
                assertWithMessage(
                    "%s %role=%s 明度 %.1f%% 过高（会发白刺眼）",
                    name, color.toHex(), lightness,
                ).that(lightness).isAtMost(MAX_CONTAINER_LIGHTNESS)
            }
        }
    }

    /**
     * ★ **降饱和 ≠ 变成灰色**。用户明确选了"保留蓝"，这条把蓝色身份钉住。
     *
     * 降饱和是无方向的——朝"灰"降和朝"柔和的蓝"降，在代码上都只是改两个数字。
     * 一路降到底会得到一个毫无个性的灰蓝，品牌识别就没了。
     *
     * ⇒ `primary` 色相必须留在**蓝区 200°~250°**（本版实测 226.0° / 221.7°，居中）。
     * 这条同时会拦住"顺手换成墨绿 / 高级灰"这类看起来更有格调的改法。
     */
    @Test
    fun primaryKeepsBlueHue() {
        for ((name, scheme) in schemes()) {
            val hue = hueDegrees(scheme.primary)
            val message = "%s primary=%s 的色相 %.1f° 不在蓝区 %.0f°~%.0f°"
            val args = arrayOf<Any>(name, scheme.primary.toHex(), hue, MIN_BLUE_HUE, MAX_BLUE_HUE)
            // Truth 的 `FloatSubject extends ComparableSubject<Float>`，父类提供
            // `isAtLeast/isAtMost(Float)` —— 走的是**浮点**重载，不会截断小数。
            assertWithMessage(message, *args).that(hue).isAtLeast(MIN_BLUE_HUE)
            assertWithMessage(message, *args).that(hue).isAtMost(MAX_BLUE_HUE)
        }
    }

    private fun schemes(): List<Pair<String, ColorScheme>> =
        listOf("浅色" to VaultixBrandColor.Light, "深色" to VaultixBrandColor.Dark)

    /** 品牌主色桶 —— 见 [accentSlotsStayBelowSaturationCeiling]。 */
    private fun ColorScheme.accentSlots(): List<Pair<String, Color>> = listOf(
        "primary" to primary,
        "secondary" to secondary,
        "tertiary" to tertiary,
        "inversePrimary" to inversePrimary,
        "onPrimaryContainer" to onPrimaryContainer,
        "onSecondaryContainer" to onSecondaryContainer,
        "onTertiaryContainer" to onTertiaryContainer,
    )

    /** 大面积容器桶 —— 见 [largeAreaContainersStayMuted]。刻意不含 `errorContainer`（语义色）。 */
    private fun ColorScheme.largeAreaContainers(): List<Pair<String, Color>> = listOf(
        "primaryContainer" to primaryContainer,
        "secondaryContainer" to secondaryContainer,
        "tertiaryContainer" to tertiaryContainer,
    )

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

        /** 品牌主色饱和度上限（%）。本版最高实测 55.6%，上一版 69.0%。 */
        const val MAX_ACCENT_SATURATION = 58f

        /** 大面积容器饱和度上限（%）。本版最高实测 39.1%，上一版 100%。 */
        const val MAX_CONTAINER_SATURATION = 40f

        /** 大面积容器明度上限（%）。过高会"发白刺眼"。 */
        const val MAX_CONTAINER_LIGHTNESS = 96f

        /** 品牌主色色相下限（度）。 */
        const val MIN_BLUE_HUE = 200f

        /** 品牌主色色相上限（度）。 */
        const val MAX_BLUE_HUE = 250f
    }
}
