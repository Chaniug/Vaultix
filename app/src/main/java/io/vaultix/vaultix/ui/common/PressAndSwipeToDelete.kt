/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 交互设计说明（两步手势，防误删）
 * 「**按住**条目 → 卡片抬起并露出红色删除底 → **向左滑动** → 松手删除」。
 *
 * 为什么不是普通左滑删除：密码条目一旦滑掉就是一条真实凭据的消失，单纯左滑太容易误触
 * （列表滚动、单手操作、口袋误碰）。上游 Bastion 对删除同样是「先长按/选择再动手」的两步
 * 语义（`combinedClickable(onLongClick)` 进入选择模式 + 底部批量操作条）。
 *
 * 实现要点：
 * - 手势**自己手写**（[deleteGesture]）而不是直接用 `detectDragGesturesAfterLongPress`：
 *   因为「长按」现在有两个消费者 —— 本容器的「上膛待滑」与卡片 [EntryCard] 的
 *   「进入选择模式」。若再叠一层 `detectTapGestures(onLongPress)`，两者会抢同一个 down
 *   （`detectTapGestures` 会 `down.consume()`，另一个就等不到了）。
 *   手写后**只有一个** pointerInput：长按成立 → 先回调 [onLongPress]（卡片据此进入选择
 *   模式），随即接管拖动，事件链唯一，不存在竞争；
 * - 拖动过程 `change.consume()`：卡片自带的 clickable 不会在松手时补一个点击，
 *   列表也**不会**跟着滚（长按已表明是删除意图，不是翻页意图）；
 * - 未达阈值松手 → 动画回弹（不删除）；达阈值 → 滑出并触发 [onDelete]；
 * - 长按未成立（抬手 / 移动过大）时**不消费**任何事件：列表滚动与条目点击照常。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 触发删除的滑动距离（向左拖过它才算删除）。 */
private val DELETE_THRESHOLD = 96.dp

/** 拖动的最大位移（再多也不移动，避免卡片飞出可视区造成困惑）。 */
private val MAX_DRAG = 180.dp

/** 回弹 / 滑出动画时长。 */
private const val SETTLE_MS = 180

/** 删除底与卡片同圆角（与 [EntryCard] 的 12dp 对齐）。 */
private const val DELETE_BG_CORNER = 12

/** 按住时的抬起幅度（提示「现在可以拖动」）。 */
private const val ARMED_SCALE = 0.02f

/**
 * 长按「上膛」时静态露出的红色删除底透明度（对齐 Bastion `SwipeArmState` 的 `hintAlpha`）。
 *
 * ⚠️ 没有这一档，这个手势就**等于不存在**：只靠滑动进度显影的话，用户按住之后画面只有
 * 2% 抬起（肉眼不可见），完全不知道自己已经解锁了手势 —— 功能在、但没人会发现。
 */
private const val ARMED_HINT_ALPHA = 0.32f

/**
 * 长按上膛时内容**向左让出的距离**，用来在右缘露出一条红色删除底。
 *
 * 为什么必须让出而不是只调透明度：删除底被不透明的卡片完全盖住，只改 alpha 是看不见的；
 * 卡片左移一条缝才能把「可以滑走」这件事画出来。
 */
private val ARMED_HINT_REVEAL = 16.dp

/**
 * 「按住后滑动删除」容器：把任意条目卡片包进去即可获得该手势。
 *
 * @param onDelete 滑过阈值并松手后的回调（真正删除由调用方执行）。
 * @param onLongPress 长按**成立**的回调（此时手指还没动）。调用方据此进入选择模式
 *   （对齐 Bastion：`onLongClick` → 选择模式）。拖动期间不会重复触发。
 * @param content 条目卡片（内部通常自带 [EntryCard] 的点击）。
 */
@Composable
fun PressAndSwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val maxDragPx = with(density) { MAX_DRAG.toPx() }
    val thresholdPx = with(density) { DELETE_THRESHOLD.toPx() }
    val hintRevealPx = with(density) { ARMED_HINT_REVEAL.toPx() }
    val scope = rememberCoroutineScope()
    val currentDelete by rememberUpdatedState(onDelete)
    val currentLongPress by rememberUpdatedState(onLongPress)

    // 实时位移：拖动期间只改这一个 Float 状态（**不启动协程**，避免每个拖动事件都起一个）。
    var offsetX by remember { mutableFloatStateOf(0f) }
    // 是否已长按「上膛」（0/1）；经 animateFloatAsState 平滑成 0→1 进度驱动提示动画。
    var armed by remember { mutableFloatStateOf(0f) }
    val armedProgress by animateFloatAsState(
        targetValue = armed,
        animationSpec = tween(SETTLE_MS),
        label = "swipe_armed_progress",
    )

    fun settleTo(target: Float, then: () -> Unit = {}) {
        scope.launch {
            val anim = Animatable(offsetX)
            anim.animateTo(target, tween(SETTLE_MS)) { offsetX = value }
            then()
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // 删除底显隐 = max(滑动进度, 长按提示)。长按提示随真实拖动淡出，避免与拖拽重复叠加。
        val dragReveal = (-offsetX / thresholdPx).coerceIn(0f, 1f)
        val armedHint = ARMED_HINT_ALPHA * armedProgress * (1f - dragReveal)
        val reveal = maxOf(dragReveal, armedHint)
        if (reveal > 0f) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = reveal }
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(DELETE_BG_CORNER),
                    )
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.action_delete),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // 长按上膛时额外交出一小段位移，把红底从右缘「挤」出来当提示；
                    // 真实拖动期间按 dragReveal 淡出，两段位移不叠加。
                    translationX = offsetX - hintRevealPx * armedProgress * (1f - dragReveal)
                    val scale = 1f + ARMED_SCALE * armedProgress
                    scaleX = scale
                    scaleY = scale
                }
                .deleteGesture(
                    onLongPress = {
                        armed = 1f
                        currentLongPress()
                    },
                    onDrag = { delta -> offsetX = (offsetX + delta).coerceIn(-maxDragPx, 0f) },
                    onDragEnd = {
                        armed = 0f
                        if (-offsetX >= thresholdPx) {
                            // 滑出屏幕后再删除：先给一个「卡片被扔掉」的视觉收尾。
                            settleTo(-maxDragPx * 2) { currentDelete() }
                        } else {
                            settleTo(0f)
                        }
                    },
                    onDragCancel = {
                        armed = 0f
                        settleTo(0f)
                    },
                ),
        ) {
            content()
        }
    }
}

/**
 * 长按 → 拖动的手势接线（独立函数，避免主 composable 的圈复杂度/条件数越界）。
 *
 * 只允许**向左**滑：向右拖会被 `minOf(x, 0f)` 归零（删除方向唯一，语义更清楚）。
 *
 * 手写而非 `detectDragGesturesAfterLongPress`：需要在「长按成立」的那一刻回调
 * [onLongPress]（卡片据此进入选择模式），上游封装没有这个时机。
 */
private fun Modifier.deleteGesture(
    onLongPress: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = pointerInput(Unit) {
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 长按不成立 → 本次手势作废，**不消费**任何事件（见 [deleteGesture] 文档）。
        val longPressed = withTimeoutOrNull(longPressTimeout) { awaitBreak(down, touchSlop) } == null
        if (!longPressed) return@awaitEachGesture

        onLongPress()
        // 长按已成立：接管后续事件。这里 consume 掉位移，列表便不会跟着滚 ——
        // 用户已经用长按表明「我要动这张卡」，不是「我要翻页」。
        val released = drag(down.id) { change ->
            // ⚠️ 用 `position - previousPosition` 而不是 `change.positionChange()`：
            // 后者在 ui 1.11 起是**顶层扩展函数**（不是成员），漏 import 会解析成
            // 内部的 `positionChange: Boolean` 字段，报「Boolean 不能当函数调用」。
            val delta = (change.position - change.previousPosition).x
            if (delta != 0f) {
                onDrag(minOf(delta, 0f))
            }
            change.consume()
        }
        if (released) {
            onDragEnd()
        } else {
            onDragCancel()
        }
    }
}

/**
 * 阻塞式等待「长按被打断」：手指抬起、或移动超过 [touchSlop] 即返回。
 *
 * 返回时外层 `withTimeoutOrNull` 拿到非 null ⇒ 长按失败；超时（返回 null）⇒ 长按成立。
 */
private suspend fun AwaitPointerEventScope.awaitBreak(down: PointerInputChange, touchSlop: Float) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val moved = event.changes
            .firstOrNull { it.id == down.id }
            ?.let { (it.position - down.position).getDistance() > touchSlop }
            ?: false
        val lifted = event.changes.all { !it.pressed }
        if (moved || lifted) return
    }
}
