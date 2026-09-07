package com.bastion.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

private const val TICK_STOP_TIMEOUT_MS = 5_000L

// 设计意图（架构升级 C.4.4，2026-09-05 性能修订）：
// 1. tickerScope 刻意作为文件级长生命周期 scope，其下 ticker 均用
//    SharingStarted.WhileSubscribed(5_000ms) 启动，无订阅自动停止，不会空转泄漏。
// 2. 数据层统一为「秒级对齐发射」（此前 smooth 为 50ms 高频发射）：50ms tick 会驱动
//    整个 TOTP 列表每秒 20×N 次重组，是长列表滑动掉帧主因（Monica 同款问题）。
//    平滑视觉改由绘制层动画补齐（rememberTotpSmoothProgress，1s 线性动画），
//    重组频率降至 1Hz，绘制仍保持每帧匀速推进。
private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private val smoothTicker = tickerFlow(smooth = true).stateIn(
    scope = tickerScope,
    started = SharingStarted.WhileSubscribed(TICK_STOP_TIMEOUT_MS),
    initialValue = System.currentTimeMillis()
)

private val secondTicker = tickerFlow(smooth = false).stateIn(
    scope = tickerScope,
    started = SharingStarted.WhileSubscribed(TICK_STOP_TIMEOUT_MS),
    initialValue = System.currentTimeMillis()
)

@Composable
fun rememberTotpTickerMillis(smooth: Boolean): Long {
    val millis by (if (smooth) smoothTicker else secondTicker).collectAsState()
    return millis
}

/**
 * 平滑进度动画：配合秒级数据源（rememberTotpTickerMillis）在绘制层补齐平滑视觉。
 *
 * - 常规推进：对秒级阶梯 fraction 施加 1s 线性动画，视觉与 50ms 数据驱动等价（匀速推进）；
 * - 周期翻转：fraction 从 ~1 回落到 ~0 时直接 snap，避免动画倒卷。
 *
 * 非平滑模式不走本函数（沿用各组件原有的秒级跳变 + 短过渡行为）。
 */
@Composable
fun rememberTotpSmoothProgress(target: Float): Float {
    val clamped = target.coerceIn(0f, 1f)
    var lastTarget by remember { mutableFloatStateOf(clamped) }
    val wrapDetected = clamped < lastTarget - 0.5f
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = if (wrapDetected) {
            snap()
        } else {
            tween(durationMillis = 1_000, easing = LinearEasing)
        },
        label = "totp_smooth_progress"
    )
    SideEffect { lastTarget = clamped }
    return animated
}

private fun tickerFlow(smooth: Boolean) = flow {
    while (currentCoroutineContext().isActive) {
        val now = System.currentTimeMillis()
        emit(now)
        // 统一秒级对齐发射（对齐 Unix 秒边界；周期从整秒开始，code 生成秒不变）。
        delay(1000L - (now % 1000L))
    }
}
