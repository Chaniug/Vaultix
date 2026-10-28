/*
 * Vaultix — app:ui:glass
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（Liquid Glass）观感的**唯一实现点**。
 *
 * ## 一轮返工（2026-10-27 真机反馈）
 *
 * 第一版把 `graphicsLayer { renderEffect = 模糊 }` 挂在包住底栏图标的 Box 上 ——
 * `RenderEffect` 模糊的是**该图层的输出，含全部子内容** ⇒ 图标文字+中央「+」被一起糊成
 * 灰雾。**这个 KDoc 上一版写的"必须挂最外层 Box"是错的**，真机截图直接证伪。
 *
 * ## 现在的结构（对齐 chrisbanes/Haze 的源→消费分离）
 *
 * ```
 * ┌ 源：Modifier.liquidGlassSource（Tab 内容区：录一份进 contentLayer，并照常回屏）
 * └ 浮层：LiquidGlassLayer
 *      ├─ 层 A：Modifier.liquidGlassBackdrop —— 只画"模糊后的源"，带 clip 与 renderEffect
 *      └─ 层 B：content() —— 图标 / 文字 / 中央「+」，完全不含任何模糊
 * ```
 *
 * `Box` 的兄弟节点各画各的层，所以层 B 不会被层 A 的 `RenderEffect` 波及。
 * 这是 StackOverflow「内容放在同一层里也会受效果影响，解法是两个叠加的 Box」
 * 所说的做法，也是 Haze `hazeEffect` 内部对源图层的处理方式。
 *
 * ## 🔴 2026-10-28（第三轮）：源必须**照常回屏**，模糊必须挂在**中转层**
 *
 * 第二轮把源写成"只录不画、屏幕不落像素"，自称是与 Haze 的"唯一有意分歧"。
 * 结果是**除底栏外整屏没有像素** ⇒ 露出窗口底色 ⇒ 用户报「开启液态玻璃后整个页面变白」。
 * 现在：源 `record` 后 `drawLayer(contentLayer)` 回屏；模糊只挂 `effectLayer`。
 * **与 Haze 零分歧**。细节与五条约束见 `LiquidGlassSource.kt` 文件头。
 *
 * ## 🔴 为什么填充 / 高光 / 描边画在层 B 上，而不是层 A
 *
 * 层 A 上有 `renderEffect`：**在同一图层内先画填充再挂效果，填充自己也会被糊**（虽然
 * 半透明纯色糊了看不出，但上沿高光那条 1~6dp 的细带会被糊没 —— 而它恰恰是"玻璃"与
 * "塑料板"的观感分界）。所以视觉装饰一律画在**上层（层 B）的 `drawBehind`**：
 * 它盖在模糊结果之上，是清晰的。
 *
 * ## 为什么没有做 iOS 26 那种"折射"
 *
 * 折射需要把背景当**纹理**喂给 AGSL `RuntimeShader` 做位移采样。现在源已经是
 * 一块 `GraphicsLayer` 了，理论上可以 `toImageBitmap()` 拿到纹理 —— 但那是**每帧
 * 一次 GPU→CPU→GPU 回读**，列表滚动时帧率直接打穿。⇒ **本实现是"模糊 + 上沿高光 +
 * 内描边"的液态玻璃观感，不是折射模拟。** 这是有意的取舍，不是漏做。
 */

/** 模糊半径（dp）。Haze 的 `HazeMaterials` 默认值就是 24dp，与它对齐便于横向对照。 */
private val BlurRadius = 24.dp

/** 液态玻璃的视觉参数（集中一处，避免各处拼参数导致观感漂移）。 */
object LiquidGlass {
    /**
     * 填充不透明度 —— **直接取自 chrisbanes/Haze 的 `HazeMaterials`**（Apache-2.0，
     * 数值可对照其 `haze-materials/…/HazeMaterials.kt`）：
     *
     * | 档位 | light | dark |
     * |---|---|---|
     * | ultraThin | 0.35 | 0.55 |
     * | thin | 0.60 | 0.65 |
     * | regular | 0.73 | 0.80 |
     * | thick | 0.83 | 0.90 |
     * | ultraThick | 0.92 | 0.97 |
     *
     * 第一版取的 0.62 / 0.48（**深色比浅色更透**）是拍脑袋定的，而且方向反了：
     * 深色主题下背景本身更暗、模糊结果更接近黑，填充再薄就分不清"玻璃"与"背景"，
     * 图标反而更难看清。Haze 五档**全部是深色更实**。
     * ⇒ 取 **thin**（0.60 / 0.65）：仍能看出后面的内容在动，同时保住图标对比度。
     */
    const val FILL_ALPHA_LIGHT = 0.60f
    const val FILL_ALPHA_DARK = 0.65f

    /**
     * 降级（无模糊硬件能力）时**直接画实色**，而不是"半透明 + 一点补偿"。
     *
     * Haze 的做法同样是降级成 flat scrim（`fallbackColorEffect`），而不是留半透明糊弄：
     * 半透明的底栏浮在滚动内容上，文字会**穿过**底栏（读不清），那才是真正不可用的状态。
     * ⇒ 无模糊 = 主题色 `surfaceContainerHigh` **全不透明**，观感等同第一版的实心底栏。
     */
    const val FALLBACK_ALPHA = 1f

    /** 上沿高光带高（占整体高度的比例）。 */
    const val EDGE_HIGHLIGHT_FRACTION = 0.16f

    /** 上沿高光透明度 —— 它才是"玻璃"与"塑料边框"的观感分界。 */
    const val TOP_SHEEN_ALPHA = 0.55f

    /** 内描边透明度（深色下白描边要更淡）。 */
    const val STROKE_ALPHA_LIGHT = 0.55f
    const val STROKE_ALPHA_DARK = 0.28f

    /** 阴影高度：玻璃态比实心态更需要它把胶囊"抬"离内容，否则边界读不出来。 */
    val ShadowElevation = 8.dp
}

/**
 * 系统是否支持**真模糊**。
 *
 * 只有 API 31+ 可以。低版本返回 false，UI 侧据此给出"降级为实心底栏"的说明 ——
 * **不要静默降级**：用户打开开关却看不到模糊，会以为开关坏了。
 *
 * @param sdkInt 显式入参只为可单测；业务代码不要传。
 */
fun isLiquidGlassBlurSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/**
 * 是否真的会把模糊挂上去（开关 × 系统能力）。
 *
 * 抽成纯函数是为了能在 JVM 单测里验证门槛逻辑 —— `RenderEffect` 本身在单测环境拿不到。
 */
internal fun shouldApplyBlur(requested: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    requested && isLiquidGlassBlurSupported(sdkInt)

/**
 * 液态玻璃的绘制参数（由调用方从主题取，本文件不读 `MaterialTheme`）。
 *
 * @param fill **必须带 alpha**（真模糊档）：不透明的填充会把模糊结果整块盖掉。
 * @param stroke 内描边色。
 * @param sheen 上沿高光色（固定白，深色下仍成立）。
 */
data class LiquidGlassStyle(
    val fill: Color,
    val stroke: Color,
    val sheen: Color,
)

/**
 * 按深浅色取一套风格。
 *
 * @param blurAvailable 有没有真模糊。**没有时填充直接拉到不透明**（见 [LiquidGlass.FALLBACK_ALPHA]）：
 *   这是"降级成实心底栏"，不是"半透明凑合"。
 */
fun liquidGlassStyle(
    darkTheme: Boolean,
    surface: Color,
    onSurface: Color,
    blurAvailable: Boolean = true,
): LiquidGlassStyle {
    val alpha = when {
        !blurAvailable -> LiquidGlass.FALLBACK_ALPHA
        darkTheme -> LiquidGlass.FILL_ALPHA_DARK
        else -> LiquidGlass.FILL_ALPHA_LIGHT
    }
    return LiquidGlassStyle(
        fill = surface.copy(alpha = alpha),
        stroke = onSurface.copy(
            alpha = if (darkTheme) LiquidGlass.STROKE_ALPHA_DARK else LiquidGlass.STROKE_ALPHA_LIGHT,
        ),
        sheen = Color.White,
    )
}

/** 一个 `RenderEffect` 模糊。API < 31 与创建失败都返回 null（调用方按降级处理）。 */
internal fun blurEffect(radiusPx: Float): ComposeRenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    // ⚠️ 某些 ROM / 软件渲染路径上创建 RenderEffect 会抛 —— 那应当降级成实色，
    //    而不是让整个主界面崩掉。
    return runCatching {
        RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    }.getOrNull()
}

/**
 * 液态玻璃图层（= 上面结构图里的"浮层"）。
 *
 * ## 🔴 本函数最长的一行注释：层 A 与层 B 必须**分开**
 *
 * ```kotlin
 * Box(modifier) {
 *     Box(Modifier.matchParentSize().liquidGlassBackdrop(…))   // ← 层 A：唯一带模糊的东西
 *     Box(Modifier.drawBehind { drawGlass(…) }) { content() }   // ← 层 B：图标，零模糊
 * }
 * ```
 *
 * 只要把这两件事写进**同一个** `Box` 的 modifier 链（第一版的写法），
 * `renderEffect` 就会连层 B 一起糊。要验证有没有写错，看**图标清不清晰**就够了。
 *
 * @param shape 外轮廓（底栏是药丸形）。
 * @param enabled 用户开关（设置页「主题模式」组里的「液态玻璃底栏」）。
 * @param source 背景源；null 时等同一块纯色背景（预览 / 无源的场合）。
 * @param style 填充 / 描边 / 高光配色。
 */
@Composable
fun LiquidGlassLayer(
    shape: Shape,
    enabled: Boolean,
    style: LiquidGlassStyle,
    modifier: Modifier = Modifier,
    source: LiquidGlassSource? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val blur = shouldApplyBlur(requested = enabled)
    val radiusPx = with(density) { BlurRadius.toPx() }
    // 浮层相对源的偏移。**必须是 state**：坐标系每帧都可能变（旋转、手势条、字号），
    // 而 `drawWithCache` 只在 key 变化时重建 —— 用普通字段会停在首帧的偏移上（底栏错位一截）。
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier) {
        if (blur && source != null) {
            // ★ 层 A：只画"源被模糊之后的样子"。这里**不能**再画任何填充 ——
            //   同一个 renderEffect 会把它自己的填充也糊掉（见文件头 KDoc 的第四条注释）。
            //
            // ⚠️ `.clip(shape)` 是层 A 的第一要务：源录的是整块 Tab 区域，比胶囊高得多，
            //   不裁剪就会把一大块糊影画到胶囊外面去。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    // 位置要在 backdrop **之前**更新：修饰符链按顺序执行，
                    // 放后面会让首帧用旧 offset 画一次（真机上表现为底栏糊影往上错一截）。
                    // ⚠️ `boundsInRoot()` 要取**自己**的位置，所以这个节点外的
                    //   `onGloballyPositioned` 拿到的就是本层 —— 与 [backdrop] 同尺寸。
                    .onGloballyPositioned { coords ->
                        val own = coords.boundsInRoot()
                        val src = source.bounds
                        // ⚠️ 符号别手写：底栏在源下方 ⇒ 该值 y 为负，绘制时按**同值**平移。
                        val next = backdropOffset(sourceBounds = src, layerBounds = own)
                        if (next != offset) offset = next
                    }
                    .liquidGlassBackdrop(source = source, offset = offset, radiusPx = radiusPx),
            )
        }
        // ★ 层 B：视觉装饰 + 内容。装饰画在 `drawBehind`，因此永远是清晰的。
        //
        // 🔴 这一层必须是**唯一参与父 Box 测量**的子节点（2026-10-28 第四轮修复）。
        //
        //   原先它和层 A 一样写 `matchParentSize()` ⇒ 父 Box 里**没有一个可测量的子节点**
        //   ⇒ 高度只能取约束下限 = **0** ⇒ `clip(shape)` 把 0 高的区域裁成空
        //   ⇒ **整条底栏不可见**（用户报「打开液态玻璃后，底栏消失不见了」）。
        //
        //   `matchParentSize()` 的语义是「尺寸跟随父，且**不参与**父的尺寸计算」——
        //   全都用它，就等于没有谁告诉父 Box "你该多高"。这不是"没生效"，是"算出来就是 0"。
        //   第二轮的"整页变白"把这个问题盖住了（屏幕一片白时看不出底栏没了）。
        //
        //   ⇒ 这里改成按内容测量：`fillMaxWidth()` 只锁宽度，高度随 [content]。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .drawBehind { if (enabled) drawGlass(style = style, density = density, blurAvailable = blur) },
        ) {
            content()
        }
    }
}

/**
 * 填充 + 上沿高光 + 内描边。**画在浮层的上层**（层 B 的 `drawBehind`）。
 *
 * 真模糊与降级只差"填充的不透明度"：降级时 style 已经是全不透明（见 [liquidGlassStyle]）。
 */
private fun DrawScope.drawGlass(
    style: LiquidGlassStyle,
    density: Density,
    blurAvailable: Boolean,
) {
    val fill = style.fill
    // 真模糊档再叠一道极轻的竖向渐变，让玻璃"上亮下暗"有厚度感；
    // 降级档不加 —— 不透明色再做渐变就是两块塑料，反而更廉价。
    drawRect(
        brush = if (blurAvailable) {
            Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.88f)))
        } else {
            Brush.verticalGradient(listOf(fill, fill))
        },
    )

    // 上沿高光：只盖顶部一小段，迅速淡出（真实玻璃的受光边永远在上沿）。
    val band = (size.height * LiquidGlass.EDGE_HIGHLIGHT_FRACTION).coerceAtLeast(1f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(style.sheen.copy(alpha = LiquidGlass.TOP_SHEEN_ALPHA), Color.Transparent),
            startY = 0f,
            endY = band,
        ),
        size = Size(size.width, band),
    )

    // 内描边：1px，让玻璃与背后内容有边界。
    val w = with(density) { 1.dp.toPx() }
    drawRect(
        color = style.stroke,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f)),
        style = Stroke(width = w),
    )
}

/**
 * 深色判定的亮度中点。
 *
 * 0.5 不是随手取的：WCAG 的相对亮度在中点附近最容易分错，而 Material 的深浅两套
 * `colorScheme` 里 `surfaceContainerHigh` 恰好分别落在 0.02~0.15（深）与 0.85~0.95（浅），
 * 离 0.5 都足够远 ⇒ 判定不会因为主题微调而翻转。
 */
private const val DARK_LUMINANCE_THRESHOLD = 0.5f

/** 深色判定：取实际渲染底色的亮度，**不要**用 `isSystemInDarkTheme()`。 */
internal fun isDarkSurface(surface: Color): Boolean = surface.luminance() <= DARK_LUMINANCE_THRESHOLD
