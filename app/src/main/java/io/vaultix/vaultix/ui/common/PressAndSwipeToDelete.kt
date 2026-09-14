/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 交互设计说明（直接左滑 → 松手过半即弹二次确认）
 *
 * ## 演进史（三次迭代，每次都是用户反馈驱动的）
 *
 * **① 2026-09-13 初版**：「长按成立之后才接管位移」的两段式手势。问题是**用户根本滑不动**——
 * 长按的语义已被多选占用，「长按 → 继续拖」在真实手指下几乎不可达。
 *
 * **② 2026-09-13 第二轮**：改成「长按只勾选，滑动才显红」+ `enabled` 门槛
 * （必须先长按进多选，左滑才生效）。用户反馈是「从右往左的删除按钮好像与 UI 冲突了，
 * 不显示不跟手，但删除是生效的」——于是补了跟手位移与常驻红底。
 *
 * **③ 2026-09-14 第三轮（当前）**：用户反馈「删除按钮滑动出来后需要点击才可以」。
 * 拍板两件事：
 *   1. **取消「必须先长按进多选」的门槛** —— 任意条目直接左滑即可（免长按）；
 *   2. **松手即裁决** —— 滑过阈值直接弹二次确认，**不再需要点击按钮**。
 * 同时确认**保留**滑动露出的跟手动画（红底随手指显影），只是按钮不再承担点击职责。
 * ⇒ 手势从「滑动 + 点击 + 确认」三步压成「滑动 + 确认」两步。
 *
 * ## 当前手势分层（务必保持）
 * - **长按** = 进入多选 —— 由卡片 [EntryCard] 的 `onLongClick` 独占，本组件不参与；
 * - **水平左滑** = 跟手露出红色动作区（**不需要任何前置长按**）；
 * - **松手过半** = 弹二次确认；**未过半** = 回弹归零。
 * - **竖直滑动** = 不消费，交给 `LazyColumn` 滚动。
 *
 * 为什么保留二次确认：用户明确要求过「删除的时候二次确认还没有做」，且软删除虽可恢复、
 * 但误删仍会打断心流。手势压到两步（滑动 + 确认）已经足够轻快。
 *
 * 为什么不干脆「滑过阈值即刻删除（无对话框）」：那就成了「滑动即达删除」，
 * 横向滑动在列表里太容易误触 —— 保留对话框作为最后一道闸。
 *
 * 为什么不用 `detectDragGesturesAfterLongPress`：那条路要求长按前置，正是本轮要取消的东西。
 * 用 `detectHorizontalDragGestures`：只在**水平**方向越过 touch slop 后才接管，
 * 竖直方向照旧交给 `LazyColumn`，两者按方向自然分流、互不抢事件。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
 * 「左滑 → 松手过半 → 二次确认」容器：把任意条目卡片包进去即可获得该交互。
 *
 * 三个列表（密码 / 验证码 / 卡包）共用同一个组件，因此三处的手感与确认文案天然一致。
 *
 * 手势分层（**务必保持**）：
 * - 长按选中 → 卡片自己负责（[EntryCard] 的 `onLongClick`），本容器**不**参与；
 * - 水平左滑 → 本容器接管（`detectHorizontalDragGestures`，**无需长按前置**）；
 * - 松手过半 → 弹二次确认；未过半 → 回弹；
 * - 竖直滑动 → 不消费，交给 `LazyColumn` 滚动。
 *
 * 只允许**向左**滑（向右会被 `coerceIn(.., 0f)` 归零）：删除方向唯一，语义更清楚，
 * 也避免与「从屏幕左缘右滑返回」的系统手势打架。
 *
 * ## 门槛的移除（2026-09-14 第三轮用户反馈）
 * 此前的 [enabled] 要求「必须长按进多选，左滑才生效」（2026-09-13 用户为防误触所选）。
 * 实际用起来的问题是：**多了一道"先长按"的前置，手势显得不跟手**；且滑开后还要再
 * **点一次按钮**才弹确认，等于「滑动 + 点击 + 确认」三步。
 * ⇒ 本轮拍板：免长按直接滑，且**松手即裁决**（过半直接弹确认），压到两步。
 *
 * [enabled] 参数保留，但语义退化为「**整个容器是否允许滑动删除**」——目前三处调用方
 * 都传 `true`（或不再传）。保留它是因为多选态下另有自己的批量操作路径，
 * 将来若需要在某些页面整体关掉滑动删除，仍可用它一键关闭。
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
        // ⚠️ 它现在是**纯视觉**的：不再挂 `TextButton`（点击职责已上移到「松手过半」），
        // 但保留图标 + 文字，让用户滑开时明确知道「这一滑通向删除」。
        DeleteActionArea(reveal = reveal)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = if (enabled) offsetX else 0f }
                .swipeToReveal(
                    enabled = enabled,
                    onDrag = { delta -> offsetX = (offsetX + delta).coerceIn(-actionWidthPx, 0f) },
                    onDragEnd = {
                        // ★ 松手即裁决：过半 → 直接弹二次确认并回弹；未过半 → 只回弹。
                        // 两个分支都要回弹（确认框弹出时卡片不该停在半开位），差别只在是否升旗。
                        confirmOpen = -offsetX > actionWidthPx / 2f
                        settleTo(0f)
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
                currentDelete()
            },
            onDismiss = { confirmOpen = false },
        )
    }
}

/**
 * 删除动作区（红色底 + 图标 + 文字）。
 *
 * ⚠️ **纯视觉**：2026-09-14 起不再承载点击（删除改为「松手过半 → 直接弹确认」）。
 * 保留图标与文字是为了让滑开过程有明确的语义指引。
 *
 * 用 `matchParentSize()` 而不是 `fillMaxSize()`：后者会按**传入约束**撑满
 * （在 `LazyColumn` 条目里 maxHeight 是无穷大，直接崩），前者按**父 Box 实测尺寸**对齐，
 * 且不参与父尺寸计算 —— 高度完全由卡片决定。
 */
@Composable
private fun BoxScope.DeleteActionArea(reveal: Float) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer { alpha = reveal }
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .width(ACTION_WIDTH)
                .fillMaxHeight(),
        ) {
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
