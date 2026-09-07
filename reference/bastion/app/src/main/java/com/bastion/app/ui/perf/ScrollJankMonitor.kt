package com.bastion.app.ui.perf

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Collections

private const val TAG = "ScrollPerf"

/** 单帧超过这个间隔记为一次掉帧（32ms ≈ 60Hz 下丢 2 帧；120Hz 屏也不会误判）。 */
private const val JANK_THRESHOLD_MS = 32L

/** 触发输出的门槛：一次滚动里掉帧次数 ≥ 3，或出现 ≥ 50ms 的长帧。否则完全静默。 */
private const val REPORT_MIN_JANKY = 3
private const val REPORT_MIN_MAX_FRAME_MS = 50L

/**
 * 滚动掉帧采样器。
 *
 * 设计取向（避免日志噪音）：**只在一次滚动结束且确实掉帧时输出一行摘要**，
 * 正常滚动一行都不打。摘要形如：
 *
 * ```
 * ScrollPerf: jank label=passwords frames=142 janky=9(6.3%) avg=17.1ms max=68ms
 * ```
 *
 * 用于回答两类问题：
 * 1. 某个列表滚动到底卡不卡（量化，而非凭手感）；
 * 2. 修复（如顶栏联动重组）前后是否有可观测的改善。
 */
class ScrollJankMonitor {
    private var frames = 0
    private var jankyFrames = 0
    private var totalMs = 0L
    private var maxFrameMs = 0L
    private var lastFrameNanos = 0L

    fun reset() {
        frames = 0
        jankyFrames = 0
        totalMs = 0L
        maxFrameMs = 0L
        lastFrameNanos = 0L
    }

    /** 每帧调用一次（frameTimeNanos 来自 Choreographer）。 */
    fun onFrame(frameTimeNanos: Long) {
        if (lastFrameNanos != 0L) {
            val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L
            // 首帧与异常回拨（如跨屏刷新率切换）不计入
            if (deltaMs in 1..500) {
                frames++
                totalMs += deltaMs
                if (deltaMs > maxFrameMs) maxFrameMs = deltaMs
                if (deltaMs >= JANK_THRESHOLD_MS) jankyFrames++
            }
        }
        lastFrameNanos = frameTimeNanos
    }

    /** 滚动结束时调用：仅在掉帧达到门槛时输出一行。 */
    fun reportIfJanky(label: String) {
        if (frames <= 0) return
        if (jankyFrames < REPORT_MIN_JANKY && maxFrameMs < REPORT_MIN_MAX_FRAME_MS) return
        val avgMs = totalMs.toFloat() / frames
        val percent = jankyFrames * 100f / frames
        Log.d(
            TAG,
            "jank label=$label frames=$frames janky=$jankyFrames(${percent.toInt()}%) " +
                "avg=${"%.1f".format(avgMs)}ms max=${maxFrameMs}ms"
        )
        reset()
    }
}

/** 已打过挂载确认的 label：整进程每个列表只打一次，反复切 Tab 不会重复刷。 */
private val attachedLabels: MutableSet<String> =
    Collections.synchronizedSet(mutableSetOf())

/**
 * 挂到列表上：滚动期间逐帧采样，滚动停止后按需输出一行摘要。
 *
 * 采样本身只是每帧一次减法与比较，开销可忽略；列表静止时不注册任何帧回调。
 *
 * 日志量：**最多两行**——
 * 1. 挂载确认 `attach label=passwords`（整进程一次，证明采样器确实挂上了；
 *    否则「0 行 jank」无法区分「滚动真的流畅」还是「采样器没跑」）；
 * 2. 掉帧摘要，仅在一次滚动结束且确实掉帧时输出。
 */
@Composable
fun ScrollJankReporter(
    listState: LazyListState,
    label: String
) {
    val monitor = remember { ScrollJankMonitor() }
    LaunchedEffect(listState, label) {
        if (attachedLabels.add(label)) Log.d(TAG, "attach label=$label")
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) return@collect
                monitor.reset()
                while (listState.isScrollInProgress) {
                    withFrameNanos { nanos -> monitor.onFrame(nanos) }
                }
                monitor.reportIfJanky(label)
            }
    }
}
