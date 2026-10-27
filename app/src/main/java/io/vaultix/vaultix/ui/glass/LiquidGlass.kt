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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（Liquid Glass）观感的唯一实现点。
 *
 * ## 为什么"真模糊"只能这样做
 *
 * Compose 没有"模糊背后的像素"这个能力：`Modifier.blur` 模糊的是**自己**绘制的内容，
 * 不是身后已经画好的像素。要真正把"底栏后面的列表"糊掉，只有一条路 ——
 * 把底栏所在的**图层**做成模糊渲染层：
 *
 * - **Android 12（API 31）起**：`RenderEffect.createBlurEffect` 作用于该图层的**输入**
 *   （= 背后已合成的内容），这正是我们要的；
 * - **Android 12 以下**：没有公开 API。只能做半透明降级（见 [isLiquidGlassBlurSupported]）。
 *
 * ⚠️ 不要在别处再叠"半透明白色蒙层假装磨砂"：那会和真模糊互相打架，
 * 表现为同一台机器上"有时糊有时不糊"，且难以解释。
 *
 * ## 为什么没有做 iOS 26 那种"折射"
 *
 * iOS 26 的 Liquid Glass 还有一层**透镜折射**（边缘把背景像素往里挤）。在
 * `RenderEffect` 之上再叠折射需要把背景内容当纹理喂给 AGSL `RuntimeShader`，
 * 而 Compose 的 `graphicsLayer { renderEffect = ... }` 只能对自己图层的输入生效、
 * 拿不到可控的采样源；硬做只能 `PixelCopy` 逐帧回读屏幕 —— 列表滚动时帧率直接打穿。
 * ⇒ **本实现是"模糊 + 上沿高光 + 内描边"的液态玻璃观感，不是折射模拟。**
 * 这是有意的取舍，写在这里免得下一棒以为是漏做。
 */

/** 模糊半径（dp）。24dp 足够把文字糊成色块，同时保留大色块走向。 */
private val BlurRadius = 24.dp

/** 液态玻璃的视觉参数（集中一处，避免各处拼参数导致观感漂移）。 */
object LiquidGlass {
    /** 半透明填充不透明度（深色下更低才显得"透"）。 */
    const val FILL_ALPHA_LIGHT = 0.62f
    const val FILL_ALPHA_DARK = 0.48f

    /** 降级（无模糊）时补上的额外不透明度 —— 保证文字压在滚动内容上仍可读。 */
    const val FALLBACK_EXTRA_ALPHA = 0.18f

    /** 上沿高光带高（占整体高度的比例）。 */
    const val EDGE_HIGHLIGHT_FRACTION = 0.16f

    /** 上沿高光透明度 —— 它才是"玻璃"与"塑料边框"的观感分界。 */
    const val TOP_SHEEN_ALPHA = 0.55f

    /** 内描边透明度（深色下白描边要更淡）。 */
    const val STROKE_ALPHA_LIGHT = 0.55f
    const val STROKE_ALPHA_DARK = 0.28f
}

/**
 * 系统是否支持**真模糊**。
 *
 * 只有 API 31+ 可以。低版本返回 false，UI 侧据此给出"降级为半透明"的说明 ——
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
 * @param fill **必须带 alpha**：不透明的填充会把模糊结果整块盖掉（表现为"开了没效果"）。
 * @param stroke 内描边色。
 * @param sheen 上沿高光色（固定白，深色下仍成立）。
 */
data class LiquidGlassStyle(
    val fill: Color,
    val stroke: Color,
    val sheen: Color,
)

/** 按深浅色取一套风格，避免各调用点各写一套 alpha。 */
fun liquidGlassStyle(darkTheme: Boolean, surface: Color, onSurface: Color): LiquidGlassStyle =
    LiquidGlassStyle(
        fill = surface.copy(
            alpha = if (darkTheme) LiquidGlass.FILL_ALPHA_DARK else LiquidGlass.FILL_ALPHA_LIGHT,
        ),
        stroke = onSurface.copy(
            alpha = if (darkTheme) LiquidGlass.STROKE_ALPHA_DARK else LiquidGlass.STROKE_ALPHA_LIGHT,
        ),
        sheen = Color.White,
    )

/**
 * 液态玻璃图层：真模糊 + 半透明填充 + 上沿高光 + 内描边。
 *
 * ## 🔴 顺序陷阱（本功能最容易踩、且**不报错**的一处）
 *
 * `graphicsLayer { renderEffect = blur }` 模糊的是**该图层的输入**，也就是它背后
 * 已合成的内容。所以：
 *
 * - ✅ 对：把 `clip` + `blurBehind` **挂在最外层 Box 上**，背景由 `drawBehind` 画；
 * - ❌ 错 1：挂到内层内容 Box 上 → 模糊的是"内层自己"（几乎空白），看起来像没生效；
 * - ❌ 错 2：先用不透明 `Surface(color=…)` 铺底再模糊 → 模糊的是那块纯色，同样像没生效。
 *
 * @param shape 外轮廓（底栏是药丸形）。
 * @param enabled 用户开关（设置页「主题模式」组里的「液态玻璃底栏」）。
 * @param style 填充 / 描边 / 高光配色。
 */
@Composable
fun LiquidGlassLayer(
    shape: Shape,
    enabled: Boolean,
    style: LiquidGlassStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val blur = shouldApplyBlur(requested = enabled)
    Box(
        modifier = modifier
            .clip(shape)
            .blurBehind(radius = with(density) { BlurRadius.toPx() }, enabled = blur)
            .drawBehind {
                if (enabled) drawGlass(style = style, density = density, blurAvailable = blur)
            },
    ) {
        content()
    }
}

/**
 * 模糊渲染效果。
 *
 * ⚠️ 这里用 `runCatching` 包住：某些 ROM / 软件渲染路径上创建 `RenderEffect` 会抛，
 * 那应当**降级成半透明**而不是让整个界面崩掉。
 */
private fun Modifier.blurBehind(radius: Float, enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        graphicsLayer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                renderEffect = runCatching {
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                }.getOrNull()
            }
        }
    }

/** 填充 + 上沿高光 + 内描边。真模糊与降级只差"要不要补不透明度"。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlass(
    style: LiquidGlassStyle,
    density: Density,
    blurAvailable: Boolean,
) {
    val fill = if (blurAvailable) {
        style.fill
    } else {
        style.fill.copy(alpha = (style.fill.alpha + LiquidGlass.FALLBACK_EXTRA_ALPHA).coerceAtMost(1f))
    }
    drawRect(
        brush = Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.88f))),
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
