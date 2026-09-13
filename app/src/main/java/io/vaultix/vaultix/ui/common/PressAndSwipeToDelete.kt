/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 交互设计说明（直接左滑 → 露出删除按钮 → 点击后二次确认）
 *
 * 2026-09-13 用户反馈：「从条目的右边往左边滑动的删除还没做」「删除的时候二次确认还没有做」。
 *
 * 上一版是「**长按成立之后**才接管位移」的两段式手势（长按先勾选、继续拖才删除）。
 * 它的问题不是没实现，而是**用户根本滑不动**：长按的语义已经被多选占用，
 * 「长按 → 继续拖」这条路径在真实手指下几乎不可达，用户感知就是「滑动删除没做」。
 *
 * 现在改成「**一条手势只承担一种语义**」：
 * - **长按** = 进入多选 —— 由卡片 [EntryCard] 的 `onLongClick` 独占，本组件不参与；
 * - **水平左滑** = 露出右侧删除动作区（跟手，**不需要任何前置长按**）；
 * - **点击删除** = 弹二次确认 —— 对话框内聚在本组件里，调用方不必自己管状态。
 *
 * 为什么是「点按」而不是「滑过阈值即刻删除」：用户明确要求二次确认。
 * 滑动只负责**露出**动作，删除还要再经一次显式点击 + 一次对话框确认，误触几乎不可能。
 *
 * 为什么不用 `detectDragGesturesAfterLongPress`：那条路要求长按，与多选直接冲突（见上）。
 * 改用 `detectHorizontalDragGestures`：它只在**水平**方向越过 touch slop 后才接管，
 * 竖直方向的上下滑动照旧交给 `LazyColumn`，两者按方向自然分流、互不抢事件。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import kotlinx.coroutines.launch

/** 左侧滑开后露出的删除动作区宽度（也是滑开后的停靠位）。 */
private val ACTION_WIDTH = 96.dp

/** 回弹 / 停靠的动画时长。 */
private const val SETTLE_MS = 180

/** 删除底色与卡片同圆角（与 [EntryCard] 的 12dp 对齐）。 */
private const val REVEAL_CORNER = 12

/**
 * 「左滑露出删除 → 点删除 → 二次确认」容器：把任意条目卡片包进去即可获得该交互。
 *
 * 三个列表（密码 / 验证码 / 卡包）共用同一个组件，因此三处的手感与确认文案天然一致。
 *
 * 手势分层（**务必保持**，否则会退化成上一版「滑不动」）：
 * - 长按选中 → 卡片自己负责（[EntryCard] 的 `onLongClick`），本容器**不**参与；
 * - 水平左滑 → 本容器接管（`detectHorizontalDragGestures`，无需长按前置）；
 * - 竖直滑动 → 不消费，交给 `LazyColumn` 滚动。
 *
 * 只允许**向左**滑（向右会被 `coerceIn(.., 0f)` 归零）：删除方向唯一，语义更清楚，
 * 也避免与「从屏幕左缘右滑返回」的系统手势打架。
 *
 * ## 门槛（2026-09-13 第二轮用户反馈）
 * 「直接右边往左滑也能删除，这样的逻辑不对吧。需要按住进入选择才能删。」
 * ⇒ 加 [enabled]：**只有按住进入多选之后，左滑才生效**。裸滑（没先长按）什么都不做——
 * 列表里横向滑动是个太容易误触的手势，不该让它直接通向删除。
 *
 * 三个列表的开关来源：
 * - 密码页 / 验证码页：`selectedIds.isNotEmpty()`（长按进多选即打开）；
 * - 卡包页：暂**无**多选态，因此保持常开 —— 待卡包补多选后再统一（已在 MEMORY 登记）。
 *
 * @param onDelete 二次确认点「删除」后的回调（真正删除由调用方执行）。
 * @param enabled 是否允许左滑进入删除（`false` 时手势完全让位给列表滚动与卡片点击）。
 * @param content 条目卡片（内部自带 [EntryCard] 的点击 / 长按选中）。
 */
@Composable
fun PressAndSwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionWidthPx = with(density) { ACTION_WIDTH.toPx() }
    val scope = rememberCoroutineScope()
    val currentDelete by rememberUpdatedState(onDelete)

    // 实时位移：滑动期间只改这一个 Float 状态（**不启动协程**，避免每个事件起一个）。
    var offsetX by remember { mutableFloatStateOf(0f) }
    var confirmOpen by remember { mutableStateOf(false) }

    fun settleTo(target: Float) {
        scope.launch {
            val anim = Animatable(offsetX)
            anim.animateTo(target, tween(SETTLE_MS)) { offsetX = value }
        }
    }

    // ⚠️ 删除动作区**必须**按位移给 alpha，否则会漏出一条极细的红边：
    // 卡片与动作区是**两个各自栅格化的 12dp 圆角矩形**，边缘一像素的抗锯齿差异足以
    // 在静息态（两者完全重合）也透出底下的 `errorContainer` —— 深色主题下尤其显眼
    // （2026-09-13 用户报告「密码条目和卡包条目周围有很细小的红色，像删除按钮溢出」）。
    // 静息态 alpha = 0 ⇒ 那条缝根本不存在；滑动时才随位移显影（顺带保留跟手感）。
    val reveal = (-offsetX / actionWidthPx).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(REVEAL_CORNER)),
    ) {
        // 删除动作区：**常驻**在卡片之下、靠右对齐（不条件式增删节点，避免首帧抖动）。
        DeleteActionArea(reveal = reveal, onClick = { confirmOpen = true })

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = if (enabled) offsetX else 0f }
                .swipeToReveal(
                    enabled = enabled,
                    onDrag = { delta -> offsetX = (offsetX + delta).coerceIn(-actionWidthPx, 0f) },
                    onDragEnd = {
                        // 过半即停靠到「全开」，否则回弹 —— 不需要精确拖到位。
                        settleTo(if (-offsetX > actionWidthPx / 2f) -actionWidthPx else 0f)
                    },
                    onDragCancel = { settleTo(0f) },
                ),
        ) {
            content()
        }
    }

    if (confirmOpen) {
        DeleteConfirmDialog(
            onConfirm = {
                confirmOpen = false
                settleTo(0f)
                currentDelete()
            },
            onDismiss = {
                confirmOpen = false
                settleTo(0f)
            },
        )
    }
}

/**
 * 删除动作区（红色底 + 图标 + 文字）。
 *
 * 用 `matchParentSize()` 而不是 `fillMaxSize()`：后者会按**传入约束**撑满
 * （在 `LazyColumn` 条目里 maxHeight 是无穷大，直接崩），前者按**父 Box 实测尺寸**对齐，
 * 且不参与父尺寸计算 —— 高度完全由卡片决定。
 */
@Composable
private fun BoxScope.DeleteActionArea(reveal: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer { alpha = reveal }
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.CenterEnd,
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .width(ACTION_WIDTH)
                .fillMaxHeight(),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.action_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 删除前的二次确认（文案刻意与详情页的删除对话框区分：这里滑开的是列表行）。 */
@Composable
private fun DeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_swipe_title)) },
        text = { Text(stringResource(R.string.delete_swipe_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 水平拖动手势接线（独立函数，避免主 composable 的圈复杂度/条件数越界）。
 *
 * [enabled] 为 `false` 时**完全不挂手势**（而不是挂上再忽略）：列表滚动、卡片点击/长按
 * 全部照原样工作，不会因为多了一层 `pointerInput` 而产生任何延迟或抢事件。
 *
 * 开启时 `detectHorizontalDragGestures` 只在水平方向越过 touch slop 后才接管并 `consume()`，
 * 因此：竖直滑动仍归 `LazyColumn`，卡片自带的 `clickable` / `combinedClickable` 也照常
 * 收到点击与长按（它们不会因为水平拖动而误触发 —— 拖动本身会取消 press）。
 */
private fun Modifier.swipeToReveal(
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(Unit) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, dragAmount ->
                if (dragAmount != 0f) {
                    change.consume()
                    onDrag(dragAmount)
                }
            },
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
        )
    }
}
