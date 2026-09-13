/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 交互设计说明（长按勾选 → 继续拖动才滑删）
 *
 * 「**长按**条目 → 进入多选（勾选框出现）→ 若手指**继续向左拖** → 红底跟手显影 → 松手删除」。
 *
 * 为什么长按不再立刻显红（2026-09-13 用户反馈「长按和删除有问题，红底不跟手」）：
 * 上一版在长按成立的那一刻就 `armed=1`，静态露出 32% 红底 + 卡片左移 16dp。
 * 但长按的语义**同时**是「进入多选」—— 两者撞在一起时，用户看到的是
 * 「我刚长按，红底就冒出来了，可我只想勾一条」，而且那 16dp 是被动画「推」出来的、
 * 不是跟手的，手感上就表现为「不显示、不跟手」。现在把两件事分开：
 * 长按只负责勾选（不显红、不位移），**位移只由手指产生**，红底只由位移驱动。
 *
 * 实现要点：
 * - 手势**自己手写**（[deleteGesture]）而不是用 `detectDragGesturesAfterLongPress`：
 *   因为「长按」有两个阶段语义 —— 先回调 [onLongPress]（勾选），再进入拖动。
 *   手写后**只有一个** pointerInput，不与卡片 [EntryCard] 的 `combinedClickable` 抢事件；
 * - 拖动过程 `change.consume()`：卡片自带的 clickable 不会在松手时补一个点击，
 *   列表也**不会**跟着滚（长按已表明是删除意图，不是翻页意图）；
 * - 未达阈值松手 → 回弹（不删除）；达阈值 → 滑出并触发 [onDelete]；
 * - 长按未成立（抬手 / 移动过大）时**不消费**任何事件：列表滚动与条目点击照常。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.animation.core.Animatable
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
    val scope = rememberCoroutineScope()
    val currentDelete by rememberUpdatedState(onDelete)
    val currentLongPress by rememberUpdatedState(onLongPress)

    // 实时位移：拖动期间只改这一个 Float 状态（**不启动协程**，避免每个拖动事件都起一个）。
    var offsetX by remember { mutableFloatStateOf(0f) }

    fun settleTo(target: Float, then: () -> Unit = {}) {
        scope.launch {
            val anim = Animatable(offsetX)
            anim.animateTo(target, tween(SETTLE_MS)) { offsetX = value }
            then()
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // 删除底**常驻**在卡片之下（只调 alpha，不条件式增删节点）。
        //
        // 为什么不做成 `if (reveal > 0)`：条件式增删会让这一层在「第一像素位移」的瞬间
        // 才被组合进来，那一帧的布局/合成抖动正是用户描述的「不显示、不跟手」；
        // 而且删除底被不透明的卡片完全盖住时，alpha=0 与不存在在视觉上等价 ——
        // 常驻没有任何代价，却换来了跟手的显影。
        val reveal = (-offsetX / thresholdPx).coerceIn(0f, 1f)
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX }
                .deleteGesture(
                    onLongPress = currentLongPress,
                    onDrag = { delta -> offsetX = (offsetX + delta).coerceIn(-maxDragPx, 0f) },
                    onDragEnd = {
                        if (-offsetX >= thresholdPx) {
                            // 滑出屏幕后再删除：先给一个「卡片被扔掉」的视觉收尾。
                            settleTo(-maxDragPx * 2) { currentDelete() }
                        } else {
                            settleTo(0f)
                        }
                    },
                    onDragCancel = { settleTo(0f) },
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
