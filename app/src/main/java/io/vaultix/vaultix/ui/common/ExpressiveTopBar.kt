/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「表达力顶栏 + 沉浸式状态栏」的规格移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）
 * 的 `ui/components/ExpressiveTopBar.kt`（其中滚动收起部分）：
 *   - **快照式**收起：调用方按「首个可见项偏移 > 8dp」判定 0/1，内部用 200ms 补间动画过渡
 *     （不是按滚动距离连续缩放）；
 *   - 标题字号 32sp → 16sp、栏最小高度 72dp → 48dp、上下内边距 8dp → 4dp、
 *     内容下移 8dp → 0dp；
 *   - 栏背景 = `surface` 的 alpha 在**收起后变 0**（内容从栏下方穿过 = 沉浸），
 *     且背景在 `statusBarsPadding()` **之前**绘制 → 覆盖状态栏区域（状态栏沉浸）；
 *   - 右侧动作按钮装在一个胶囊里（`RoundedCornerShape(50)`、`surface` 同色、
 *     `tonalElevation = 0`、`shadowElevation = 1.dp`），高度 48dp → 40dp（spring），
 *     收起时整组缩放 1.0 → 0.85；内容色在收起时向 `onSurfaceVariant` 过渡；
 *   - 标题过长时按 `onTextLayout` 的溢出反馈自动缩小字号（下限 0.72）。
 * 本文件为独立实现（去掉了上游与搜索框、左右滑手势、标题点击展开耦合的部分 ——
 * Vaultix 的搜索态走独立的固定高度顶栏，见 `VaultixSearchTopAppBar`）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

/** 收起/展开的判定阈值（首个可见项偏移超过它即视为「已收起」）。 */
private val COLLAPSE_THRESHOLD = 8.dp

/** 动画时长：与上游一致（200ms；按钮胶囊走 spring）。 */
private const val ANIM_MS = 200

/** 展开态标题字号（sp）。 */
private const val TITLE_EXPANDED_SP = 32f

/** 收起态标题字号（sp）。 */
private const val TITLE_COLLAPSED_SP = 16f

/** 标题溢出自缩的下限。 */
private const val TITLE_MIN_SCALE = 0.72f

/** 每次检测到溢出时缩小的一档（上游同值 0.04）。 */
private const val TITLE_SCALE_STEP = 0.04f

/** 行高相对字号的倍数（上游同款：`lineHeight = fontSize * 1.2`）。 */
private const val LINE_HEIGHT_RATIO = 1.2f

/** 展开态 / 收起态栏高（不含状态栏内边距）。 */
private val BAR_EXPANDED = 72.dp
private val BAR_COLLAPSED = 48.dp

/**
 * 动作胶囊圆角百分比（50 = 50%，即两端完全半圆的「药丸」形）。
 * `RoundedCornerShape(Int)` 的重载语义是**百分比**而非 dp（与 [io.vaultix.vaultix.ui.shell.VaultixBottomDock] 同款写法）。
 */
private const val PILL_CORNER_PERCENT = 50

/**
 * 滚动收起的**快照**进度：0 = 展开，1 = 收起（对齐 Bastion 的 `derivedStateOf` 写法）。
 *
 * 快照而非连续：连续缩放会让标题在滚动过程中一直在抖，而「越过一点就整体切换」的观感
 * 更干净（切换本身由 [VaultixExpressiveTopBar] 内部 200ms 动画完成）。
 */
@Composable
fun rememberScrollCollapseFraction(listState: LazyListState): Float {
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) {
        COLLAPSE_THRESHOLD.toPx()
    }
    val collapsed by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > threshold
        }
    }
    return if (collapsed) 1f else 0f
}

/** 同上，供 `Column + verticalScroll` 的页面（设置页）使用。 */
@Composable
fun rememberScrollCollapseFraction(scrollState: ScrollState): Float {
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) {
        COLLAPSE_THRESHOLD.toPx()
    }
    val collapsed by remember(scrollState) {
        derivedStateOf { scrollState.value > threshold }
    }
    return if (collapsed) 1f else 0f
}

/**
 * 列表顶部要让出的高度 = 状态栏内边距 + 当前栏高（随收起动画一起变）。
 *
 * 展开时首条内容不被顶栏遮住；收起后让出的高度缩到 48dp，内容随之**滑到半透明顶栏下方**，
 * 这正是「沉浸」的来源（对齐 Bastion 的 `listTopPadding`）。
 */
@Composable
fun rememberImmersiveBarPadding(collapseFraction: Float): Dp {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight by animateDpAsState(
        targetValue = lerp(BAR_EXPANDED, BAR_COLLAPSED, collapseFraction),
        animationSpec = tween(ANIM_MS),
        label = "immersive_bar_height",
    )
    return statusBar + barHeight
}

/**
 * 沉浸式顶栏（大标题随滚动缩小、栏背景收起后透明、右侧按钮胶囊悬浮）。
 *
 * ⚠️ 必须**浮在内容之上**使用（`Box { 列表; 本顶栏 }`），并且列表顶部留白取
 * [rememberImmersiveBarPadding]：否则「收起后透明、内容从下方穿过」不成立。
 *
 * @param collapseFraction 0 = 展开（大标题、栏不透明），1 = 收起（小标题、栏透明）。
 * @param navigationIcon 左侧返回等（tab 内页不传）。
 * @param actions 右侧动作按钮（自动装进胶囊并跟随时机缩放）。
 */
@Composable
fun VaultixExpressiveTopBar(
    title: String,
    collapseFraction: Float,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // 所有过渡量走同一条 200ms 补间，保证「标题、栏高、按钮组」同步到位（上游同款取舍）。
    val progress by animateFloatAsState(
        targetValue = collapseFraction,
        animationSpec = tween(ANIM_MS),
        label = "topbar_collapse_progress",
    )
    val titleFontSize by animateFloatAsState(
        targetValue = TITLE_EXPANDED_SP + (TITLE_COLLAPSED_SP - TITLE_EXPANDED_SP) * collapseFraction,
        animationSpec = tween(ANIM_MS),
        label = "topbar_title_size",
    )
    val verticalPadding by animateDpAsState(
        targetValue = lerp(8.dp, 4.dp, collapseFraction),
        animationSpec = tween(ANIM_MS),
        label = "topbar_vpadding",
    )
    val contentOffset by animateDpAsState(
        targetValue = lerp(8.dp, 0.dp, collapseFraction),
        animationSpec = tween(ANIM_MS),
        label = "topbar_content_offset",
    )
    val pillHeight by animateDpAsState(
        targetValue = lerp(48.dp, 40.dp, collapseFraction),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "topbar_action_pill_height",
    )
    // 栏背景：展开不透明（并覆盖状态栏区域），收起透明 → 内容从下方穿过。
    val barBackgroundAlpha by animateFloatAsState(
        targetValue = if (collapseFraction < 0.5f) 1f else 0f,
        animationSpec = tween(ANIM_MS),
        label = "topbar_bg_alpha",
    )
    val contentColor by animateColorAsState(
        targetValue = lerp(
            MaterialTheme.colorScheme.onBackground,
            MaterialTheme.colorScheme.onSurfaceVariant,
            collapseFraction,
        ),
        animationSpec = tween(ANIM_MS),
        label = "topbar_content_color",
    )
    // 标题过长时按溢出反馈自缩（上限 0.72），避免尾部字符被裁。
    var titleScale by remember(title) { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BAR_COLLAPSED)
            // ⚠️ 背景必须在 statusBarsPadding 之前 → 覆盖到状态栏区域（状态栏沉浸）。
            .background(MaterialTheme.colorScheme.surface.copy(alpha = barBackgroundAlpha))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(y = contentOffset)
                .padding(end = ACTIONS_RESERVE),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            navigationIcon?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = (titleFontSize * titleScale).sp,
                lineHeight = (titleFontSize * titleScale * LINE_HEIGHT_RATIO).sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false,
                onTextLayout = { result ->
                    if (result.hasVisualOverflow && titleScale > TITLE_MIN_SCALE) {
                        titleScale = (titleScale - TITLE_SCALE_STEP).coerceAtLeast(TITLE_MIN_SCALE)
                    }
                },
            )
        }

        // 右侧动作胶囊：与栏背景同色同透明度 → 收起后按钮直接浮在内容上。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = contentOffset),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier.height(pillHeight),
                shape = RoundedCornerShape(PILL_CORNER_PERCENT),
                color = MaterialTheme.colorScheme.surface.copy(alpha = barBackgroundAlpha),
                tonalElevation = 0.dp,
                shadowElevation = if (collapseFraction < 0.5f) 1.dp else 0.dp,
            ) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    Row(
                        modifier = Modifier
                            .graphicsLayer {
                                val scale = 1f + (0.85f - 1f) * progress
                                scaleX = scale
                                scaleY = scale
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

/** 顶栏右侧为动作胶囊预留的宽度（避免长标题压到按钮上）。 */
private val ACTIONS_RESERVE = 144.dp
