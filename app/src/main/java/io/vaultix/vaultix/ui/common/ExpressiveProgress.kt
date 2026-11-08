/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * M3 Expressive（2026）**进度 / 等待指示器**的唯一封装点。
 *
 * 2026-11-08 起多收两类：变形圆点（[VaultixMorphingProgress]）与扫光细条
 * （[VaultixMorphingProgressBar]）。此前上传态散在 `CloudSyncIcon.kt` 的老式
 * `Linear/CircularProgressIndicator`、本文件的波浪、详情页细条三处三套规格，
 * 用户原话「进度条很老实，不够新颖」。现在统一收口，**调用点不许再直接引实验性 API**。
 *
 * 为什么封装而不是在各处直接调用：
 *   1. `Circular/LinearWavyProgressIndicator` 与 `ContainedLoadingIndicator` 带
 *      `@ExperimentalMaterial3ExpressiveApi`，调用方都要写 `@OptIn`。集中到本文件后，
 *      `@OptIn` 只出现三次，.alpha 改签名时也只需改这里一处。
 *   2. **回退成本可控**：若真机验收认为波浪不合适，把这两个函数的实现换回
 *      `CircularProgressIndicator` / `LinearProgressIndicator` 即可，9 个调用点一行不用动。
 *
 * ⚠️ 尺寸是**写死**的（解 `material3-1.5.0-alpha16` 源码实测，不是推测）：
 *   - `CircularWavyProgressIndicator` 内部 `.size(48.dp)`（`CircularProgressIndicatorTokens.WaveSize`）；
 *   - `LinearWavyProgressIndicator` 内部 `.size(width = 240.dp, height = 10.dp)`
 *     （`LinearContainerWidth = 240.dp` / `LinearProgressIndicatorTokens.WaveHeight`）。
 *   ⇒ **传 `Modifier.fillMaxWidth()` 或 `Modifier.size(18.dp)` 都不会改变最终尺寸**，
 *     modifier 只作用于外层的 Box/Spacer，内部那次 `.size()` 覆盖了宽度约束。
 *
 *   老组件其实同样是写死的（`LinearIndicatorWidth = 240.dp`、`CircularIndicatorDiameter = 40.dp`），
 *   所以两处尺寸差异只有：
 *   - Linear：宽 240dp **不变**，高 4dp → 10dp（波浪需要垂直空间，不可避免）；
 *   - Circular：40dp → 48dp（+8dp）。
 *
 * 因此**以下场景不能用本封装**（仍用原组件）：
 *   - 18dp / 20dp 的内联小转圈（按钮里、文案旁）—— 换成波浪会被撑到 48dp，破坏布局；
 *   - 需要**精确读数**的确定态进度 —— 密码强度条（`ItemFormDialog`，靠 `weight(1f)` 撑宽，
 *     换波浪会变成固定 240dp）与 TOTP 倒计时（`UnifiedProgressBar`）；
 *   - 任何需要自定义尺寸的场合。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.ui.theme.VaultixCorner

/**
 * 圆形**波浪**等待指示器（不确定态），替代老的无参 `CircularProgressIndicator()`。
 *
 * 尺寸固定 48dp，调用方传的 [modifier] 只能影响定位（如 `align`），不能改大小。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultixWavyProgress(modifier: Modifier = Modifier) {
    CircularWavyProgressIndicator(modifier = modifier)
}

/**
 * 线性**波浪**进度条（不确定态），替代老的 `LinearProgressIndicator(modifier = Modifier.fillMaxWidth())`。
 *
 * 宽度固定 240dp（与老组件一致，所以 `fillMaxWidth()` 传不传都一样），高度 10dp。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultixWavyProgressBar(modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(modifier = modifier)
}

/**
 * M3 Expressive **变形圆点**（不确定态）—— 上传 / 等待的主视觉。
 *
 * 形状在一串圆角多边形之间 morph + 旋转，比老式转圈「活泼」；官方是
 * `ContainedLoadingIndicator`，带胶囊容器底，放在徽标行也不飘。
 *
 * ## ⚠️ 尺寸是写死的，必须靠本封装缩放
 *
 * 解 `material3-1.5.0-alpha16` 源码：`LoadingIndicatorImpl` 内部
 * `.size(width = 48.dp, height = 48.dp)`（`LoadingIndicatorDefaults.ContainerWidth/Height`），
 * **传 `Modifier.size()` 改不动**（内部那次 `.size` 覆盖外层约束）。而详情页徽标行
 * 只有 24dp、列表页横幅只有 32dp —— 直接放会挤开布局。
 *
 * ⇒ 这里用 `wrapContentSize(unbounded = true)` 让 48dp 的内容**无约束测量**、按外层占位居中，
 *   再用 `graphicsLayer` 把绘制结果整体缩到 [indicatorSize]。布局占位始终是 [indicatorSize]。
 *
 * @param indicatorSize 最终占位与视觉尺寸（默认官方原生 48dp）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultixMorphingProgress(
    indicatorSize: Dp = MORPH_CONTAINER_SIZE,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scale = with(density) { indicatorSize.toPx() / MORPH_CONTAINER_SIZE.toPx() }
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val indicatorColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(indicatorSize)
            .wrapContentSize(align = Alignment.Center, unbounded = true),
    ) {
        ContainedLoadingIndicator(
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            containerColor = containerColor,
            indicatorColor = indicatorColor,
        )
    }
}

/**
 * **活泼细条**（不确定态）：胶囊轨道里一段高亮块循环扫过，取代详情页那条 3dp 老式直线。
 *
 * ⚠️ 为什么不用官方 `LinearWavyProgressIndicator`：它内部写死 240×10dp
 * （见文件头实测），放进 3dp 的标题下细条位会变成一条粗棒，宽度还撑不满。
 * 自绘是这里唯一能同时保住「满宽 + 3dp + 会动」的做法；颜色全部走 `MaterialTheme`，
 * 没引入新的尺寸 token。
 */
@Composable
fun VaultixMorphingProgressBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "VaultixMorphingBar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MORPH_BAR_SWEEP_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "MorphingBarSweep",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(MORPH_BAR_HEIGHT)
            .clip(VaultixCorner.Pill)
            .drawBehind {
                drawRect(color = trackColor)
                val segment = size.width * MORPH_BAR_SEGMENT_FRACTION
                val left = -segment + sweep * (size.width + segment)
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(left, 0f),
                    size = Size(segment, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            },
    )
}

/** 变形圆点的官方原生尺寸（`LoadingIndicatorDefaults.ContainerWidth/Height`）。 */
private val MORPH_CONTAINER_SIZE = 48.dp

/** 细条厚度：沿用老式 `DetailSyncProgress` 的 3dp，不抢标题。 */
private val MORPH_BAR_HEIGHT = 3.dp

/** 扫光块占整条宽度的比例。 */
private const val MORPH_BAR_SEGMENT_FRACTION = 0.35f

/** 一次扫光的时长（ms）。 */
private const val MORPH_BAR_SWEEP_MS = 1200
