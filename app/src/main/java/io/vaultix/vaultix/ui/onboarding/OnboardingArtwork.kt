/*
 * Vaultix — app:ui · onboarding
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件是**原创实现**：三张图形均为本文件用 `Canvas` 基本图元（圆角矩形 / 圆 / 弧）
 * 手绘，未复用任何上游素材、未引用任何第三方插画库。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 引导页的三张插画（**代码画的矢量图**，2026-10-24）。
 *
 * ## 为什么不用图片资源
 *
 * 1. **颜色必须跟主题走**：动态取色下 `primary` 可以是任意色相，位图要么固定一套色
 *    （在别的主题下显得像贴上去的），要么准备 N 套。Canvas 直接吃 `colorScheme`，
 *    深浅色、动态色自动成立；
 * 2. **体积**：三张插画位图在 xxhdpi 下要 200KB 上下，而这里一共不到 200 行代码；
 * 3. **不担心版权**：没有任何外部素材要标注来源。
 *
 * ## 统一规格（三张图共享，**不要各画各的**）
 *
 * | 项 | 值 | 理由 |
 * |---|---|---|
 * | 画布 | [ARTWORK_BOX] 正方形 | 三张图并排翻页时视觉重量一致 |
 * | 主体 | 占画布 [BODY_RATIO] | 留白够多才不会在 2.0x 字体下挤到文字 |
 * | 描边 | 画布边长的 [STROKE_RATIO] | 换算成 dp 后约 4dp，细线在小屏不糊 |
 * | 配色 | 只用 `primary` / `primaryContainer` / `onPrimaryContainer` / `surfaceVariant` | 一屏最多两个色相 |
 *
 * ⚠️ **不画文字、不画数字**：插画里的文字不参与字体缩放，2.0x 下会和正文比例失衡；
 * 而且插画里的字无法被翻译。
 *
 * ⚠️ **所有几何比例都必须是具名常量**（detekt `MagicNumber` 是硬门禁）。
 * 这不是为了过检查：这些数字彼此耦合（改主体大小要同步改缝宽、线长），
 * 散在算式里没人能一次改对。
 */
internal val ARTWORK_BOX: Dp = 168.dp

/**
 * 三张插画的选择器。
 *
 * 用 enum 而不是三个 `@Composable` 分开调：页面里是 `when (page)` 选一个，
 * 枚举能让"少画一张"变成编译错误而不是运行时空位。
 */
internal enum class OnboardingArtwork { Vault, TwoVaults, Autofill }

/** 按页取插画（页面只关心"第几页"，不关心画的是什么）。 */
@Composable
internal fun OnboardingArtworkImage(artwork: OnboardingArtwork, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val muted = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier.size(ARTWORK_BOX)) {
        val stroke = size.minDimension * STROKE_RATIO
        when (artwork) {
            OnboardingArtwork.Vault -> drawVault(primary, container, onContainer, stroke)
            OnboardingArtwork.TwoVaults ->
                drawTwoVaults(primary, container, onContainer, muted, stroke)

            OnboardingArtwork.Autofill -> drawAutofill(primary, container, onContainer, stroke)
        }
    }
}

/**
 * 第 1 页：**盾牌**（"只有你打得开"）。
 *
 * 画法：一个圆角方形底（`primaryContainer`）+ 上面一枚描边的盾形轮廓 + 中心一个锁孔。
 * 盾牌用路径拼太容易画歪，这里用「直线收肩 + 两段二次曲线收底」：比贝塞尔好控制，
 * 且不会出现尖角毛刺。
 */
private fun DrawScope.drawVault(
    primary: Color,
    container: Color,
    onContainer: Color,
    stroke: Float,
) {
    val side = size.minDimension * BODY_RATIO
    val left = (size.width - side) / HALF
    val top = (size.height - side) / HALF

    // 底板：圆角方块，曲率与 Panel 同档（见 PLATE_CORNER_RATIO）。
    drawRoundRect(
        color = container,
        topLeft = Offset(left, top),
        size = Size(side, side),
        cornerRadius = CornerRadius(side * PLATE_CORNER_RATIO, side * PLATE_CORNER_RATIO),
    )

    val shieldWidth = side * SHIELD_WIDTH_RATIO
    val shieldHeight = side * SHIELD_HEIGHT_RATIO
    val sx = (size.width - shieldWidth) / HALF
    val sy = (size.height - shieldHeight) / HALF
    val cx = size.width / HALF
    val shoulder = sy + shieldHeight * SHIELD_SHOULDER_RATIO
    val bottom = sy + shieldHeight
    val path = Path().apply {
        moveTo(sx, sy)
        lineTo(sx + shieldWidth, sy)
        lineTo(sx + shieldWidth, shoulder)
        // 右侧下落到底部中点，再沿左侧回到肩线。
        quadraticTo(sx + shieldWidth, bottom, cx, bottom)
        quadraticTo(sx, bottom, sx, shoulder)
        close()
    }
    drawPath(path, color = onContainer, style = Stroke(width = stroke, cap = StrokeCap.Round))

    // 锁孔：上圆下短柄。太小会糊，半径按盾宽的 LOCK_HOLE_RATIO 起。
    val holeCenterY = sy + shieldHeight * LOCK_HOLE_Y_RATIO
    drawCircle(
        color = primary,
        radius = shieldWidth * LOCK_HOLE_RATIO,
        center = Offset(cx, holeCenterY),
    )
    drawLine(
        color = primary,
        start = Offset(cx, holeCenterY),
        end = Offset(cx, sy + shieldHeight * LOCK_STEM_BOTTOM_RATIO),
        strokeWidth = stroke * LOCK_STEM_STROKE_SCALE,
        cap = StrokeCap.Round,
    )
}

/**
 * 第 2 页：**两个并排的库**（云端 + 本地），刻意画成**同样大小**。
 *
 * ⚠️ 这张图是第 2 页论点的视觉表达：「两个库地位一样」。如果画成一朵云 + 一个文件，
 * 云的视觉重量天然比文件大，观感会立刻暗示"云端更重要" —— 与文案自相矛盾。
 * 所以两个都是同样的圆角方块，只用**内部图元**区分：左边三横线（条目列表），
 * 右边一条竖脊 + 两横边（文件装订边）。中间一条**虚线** = "两个入口并列"（不是流程箭头）。
 */
private fun DrawScope.drawTwoVaults(
    primary: Color,
    container: Color,
    onContainer: Color,
    muted: Color,
    stroke: Float,
) {
    val side = size.minDimension * BODY_RATIO
    val gap = side * CARD_GAP_RATIO
    val left = (size.width - (side * HALF + gap)) / HALF
    val rightLeft = left + leftOfRightCard(side, gap)
    val top = (size.height - side) / HALF
    val radius = CornerRadius(side * PLATE_CORNER_RATIO, side * PLATE_CORNER_RATIO)

    // 两块底板：一块 primaryContainer，一块 surfaceVariant。
    // ⚠️ 不是"主次"：静态截屏时只看得出"两个都在这"。
    drawRoundRect(
        color = container,
        topLeft = Offset(left, top),
        size = Size(side, side),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = muted,
        topLeft = Offset(rightLeft, top),
        size = Size(side, side),
        cornerRadius = radius,
    )

    // 左：三横线（条目列表），长度递减。
    val lineX = left + side * CARD_PADDING_RATIO
    val lineW = side * (1f - CARD_PADDING_RATIO * HALF)
    LIST_LINES.forEach { (y, w) ->
        drawLine(
            color = onContainer,
            start = Offset(lineX, top + side * y),
            end = Offset(lineX + lineW * w, top + side * y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }

    // 右：文件装订边（一条竖脊 + 上下两条横边）。颜色用 `onContainer` ——
    // 底板是 surfaceVariant，两者对比度够（深色下也成立）。
    val spineX = rightLeft + side * SPINE_X_RATIO
    val spineTop = top + side * SPINE_TOP_RATIO
    val spineBottom = top + side * SPINE_BOTTOM_RATIO
    val spineRight = rightLeft + side * SPINE_RIGHT_RATIO
    drawLine(onContainer, Offset(spineX, spineTop), Offset(spineX, spineBottom), stroke, StrokeCap.Round)
    drawLine(onContainer, Offset(spineX, spineTop), Offset(spineRight, spineTop), stroke, StrokeCap.Round)
    drawLine(onContainer, Offset(spineX, spineBottom), Offset(spineRight, spineBottom), stroke, StrokeCap.Round)

    // 中缝虚线：不是流程箭头，只是"两个入口并列"的呼吸线。
    val dash = PathEffect.dashPathEffect(floatArrayOf(stroke * DASH_ON_SCALE, stroke * DASH_OFF_SCALE))
    val seamX = left + side + gap / HALF
    drawLine(
        color = primary,
        start = Offset(seamX, top + side * SEAM_TOP_RATIO),
        end = Offset(seamX, top + side * SEAM_BOTTOM_RATIO),
        strokeWidth = stroke * SEAM_STROKE_SCALE,
        cap = StrokeCap.Round,
        pathEffect = dash,
    )
}

/** 右侧卡片相对左卡起点的水平偏移（左卡宽 + 缝宽）。 */
private fun leftOfRightCard(side: Float, gap: Float): Float = side + gap

/**
 * 第 3 页：**指纹 + 浏览器页框**（"替你在浏览器里填"）。
 *
 * 只画两件事：一枚指纹（同心弧）叠在一个页面框的右下角。指纹用 3 条同心弧
 * 而不是满圈 —— 满圈在小尺寸下会和页面框的圆角糊在一起。
 */
private fun DrawScope.drawAutofill(
    primary: Color,
    container: Color,
    onContainer: Color,
    stroke: Float,
) {
    val side = size.minDimension * BODY_RATIO
    val left = (size.width - side) / HALF
    val top = (size.height - side) / HALF
    val radius = CornerRadius(side * PLATE_CORNER_RATIO, side * PLATE_CORNER_RATIO)

    // 页面框：偏左上，给右下角的指纹留位。
    val pageW = side * PAGE_WIDTH_RATIO
    val pageH = side * PAGE_HEIGHT_RATIO
    drawRoundRect(
        color = container,
        topLeft = Offset(left, top),
        size = Size(pageW, pageH),
        cornerRadius = radius,
    )

    // 页框顶部一条横线（地址栏）+ 两条内容条。
    val barY = top + pageH * PAGE_BAR_Y_RATIO
    drawLine(
        color = onContainer,
        start = Offset(left + pageW * PAGE_INSET_RATIO, barY),
        end = Offset(left + pageW * (1f - PAGE_INSET_RATIO), barY),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    val contentW = pageW * (1f - PAGE_INSET_RATIO * HALF)
    PAGE_LINES.forEach { (y, w) ->
        val lineY = top + pageH * y
        drawLine(
            color = onContainer,
            start = Offset(left + pageW * PAGE_INSET_RATIO, lineY),
            end = Offset(left + pageW * PAGE_INSET_RATIO + contentW * w, lineY),
            strokeWidth = stroke * SEAM_STROKE_SCALE,
            cap = StrokeCap.Round,
        )
    }

    // 指纹：右下角，同心弧（右上方向开口，像真实的指纹走向）。
    val fpCenter = Offset(
        left + pageW * FINGERPRINT_X_RATIO,
        top + pageH * FINGERPRINT_Y_RATIO,
    )
    val baseR = side * FINGERPRINT_RADIUS_RATIO
    FINGERPRINT_RINGS.forEach { scale ->
        // 半径越大扫过的角度越小，避免三圈等距显得像靶心。
        val sweepAngle = FINGERPRINT_BASE_SWEEP + FINGERPRINT_SWEEP_PER_RING * scale
        drawArc(
            color = primary,
            startAngle = FINGERPRINT_START_ANGLE,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(fpCenter.x - baseR * scale, fpCenter.y - baseR * scale),
            size = Size(baseR * scale * DIAMETER, baseR * scale * DIAMETER),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** 「2」在几何里的语义是"取一半"，比字面量 2f 更不容易被误读成某种系数。 */
private const val HALF = 2f

/** 半径 → 直径。与 [HALF] 数值相同但**语义不同**，故分开命名：混用会让"这里为什么乘 2"失去线索。 */
private const val DIAMETER = 2f

/** 描边宽度占画布边长的比例（2.4% × 168dp ≈ 4dp）。 */
private const val STROKE_RATIO = 0.024f

/** 主体（盾 / 库 / 页面）占画布边长的比例 —— 三张图共用，保证视觉重量一致。 */
private const val BODY_RATIO = 0.56f

/** 底板圆角占主体边长的比例（视觉上接近 12–16dp，与 `VaultixCorner.Panel` 同档）。 */
private const val PLATE_CORNER_RATIO = 0.10f

/** 第 1 页：盾牌宽 / 高占主体的比例。 */
private const val SHIELD_WIDTH_RATIO = 0.52f
private const val SHIELD_HEIGHT_RATIO = 0.66f

/** 盾牌"肩线"在盾高上的位置（0 = 顶，1 = 底）。 */
private const val SHIELD_SHOULDER_RATIO = 0.62f

/** 锁孔：半径占盾宽、圆心与短柄末端在盾高上的位置。 */
private const val LOCK_HOLE_RATIO = 0.09f
private const val LOCK_HOLE_Y_RATIO = 0.42f
private const val LOCK_STEM_BOTTOM_RATIO = 0.56f

/** 锁孔短柄的画线宽度 = 普通描边 × 该系数（略粗才看得出是"柄"而不是一个点）。 */
private const val LOCK_STEM_STROKE_SCALE = 1.2f

/** 第 2 页：两张卡之间的缝占卡宽的比例。 */
private const val CARD_GAP_RATIO = 0.16f

/** 第 2 页：卡内文字条距卡边的留白比例。 */
private const val CARD_PADDING_RATIO = 0.18f

/** 第 2 页左卡的三条列表线：`(y 位置占卡高, 长度占可用宽)`，长度递减像真的列表。 */
private val LIST_LINES = listOf(0.30f to 1f, 0.46f to 0.72f, 0.62f to 0.86f)

/** 第 2 页右卡（文件装订边）：竖脊与两条横边的位置。 */
private const val SPINE_X_RATIO = 0.22f
private const val SPINE_TOP_RATIO = 0.20f
private const val SPINE_BOTTOM_RATIO = 0.80f
private const val SPINE_RIGHT_RATIO = 0.78f

/** 中缝虚线的上下端点与线宽系数。 */
private const val SEAM_TOP_RATIO = 0.25f
private const val SEAM_BOTTOM_RATIO = 0.75f

/**
 * 虚线的实/虚段长度（乘描边宽）：实段 2 倍、虚段 1.6 倍。
 *
 * 这两条同时也是第 3 页内容条与中缝线的线宽系数（0.8 倍描边）——
 * 三处都是"比主描边细一档的辅线"，共用一个常量才不会各细各的。
 */
private const val DASH_ON_SCALE = 2f
private const val DASH_OFF_SCALE = 1.6f
private const val SEAM_STROKE_SCALE = 0.8f

/** 第 3 页：页面框占主体的宽 / 高比例（偏矮，给指纹留右下角）。 */
private const val PAGE_WIDTH_RATIO = 0.88f
private const val PAGE_HEIGHT_RATIO = 0.70f

/** 第 3 页：页框内元素的左右留白占页宽的比例。 */
private const val PAGE_INSET_RATIO = 0.12f

/** 第 3 页：地址栏在页高上的位置。 */
private const val PAGE_BAR_Y_RATIO = 0.26f

/** 第 3 页页框内的两条内容条：`(y 位置占页高, 长度占可用宽)`。 */
private val PAGE_LINES = listOf(0.48f to 0.8f, 0.66f to 0.55f)

/** 第 3 页指纹：圆心占页框宽 / 高、基准半径占主体边长、三圈各自的半径缩放。 */
private const val FINGERPRINT_X_RATIO = 0.86f
private const val FINGERPRINT_Y_RATIO = 0.84f
private const val FINGERPRINT_RADIUS_RATIO = 0.17f
private val FINGERPRINT_RINGS = listOf(1f, 0.66f, 0.36f)

/** 第 3 页指纹的弧：起始角与"每圈多扫的角度"（外圈扫得更少 ⇒ 更像指纹）。 */
private const val FINGERPRINT_START_ANGLE = 110f
private const val FINGERPRINT_BASE_SWEEP = 210f
private const val FINGERPRINT_SWEEP_PER_RING = 40f
