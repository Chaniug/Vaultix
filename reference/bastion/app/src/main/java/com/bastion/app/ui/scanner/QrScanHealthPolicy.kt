package com.bastion.app.ui.scanner

internal enum class QrScanRestartReason {
    FrameStalled,
    FrameStreamStopped,
    RepeatedDecoderFailure
}

/**
 * 一帧解码流水线的最终结局，用于 [QrScanHealthPolicy.onFrameCompleted]。
 *
 * 区分「没扫到码」和「解码失败」很关键：长时间对着一帧无码的画面是正常场景，
 * 不应被计数为「解码管线反复出错」。旧版用单 Boolean 表达"成功"，
 * 实现里"成功 = 这帧扫到码"，结果冷启动后只要前几帧没码就触发重启循环。
 */
internal enum class QrFrameOutcome {
    /** 解到至少一个候选码。 */
    Detected,
    /** 管线跑通但视野里没码，正常。 */
    Empty,
    /** 解码异常（ImageProxy.image == null、YUV 转换抛错、ZXing decode 抛错等）。 */
    Failed
}

internal sealed interface QrScanHealthAction {
    data object None : QrScanHealthAction
    data object Refocus : QrScanHealthAction
    data class Restart(val reason: QrScanRestartReason) : QrScanHealthAction
}

/**
 * Pure health policy for a live QR scan session.
 *
 * Long periods without a barcode are healthy as long as camera frames continue to complete.
 * The policy periodically refreshes focus/metering, and only rebuilds the session when the
 * frame stream stalls or [QrFrameOutcome.Failed] repeats, which signals a real pipeline
 * fault rather than a frame that simply contained no barcode.
 */
internal class QrScanHealthPolicy(
    private val refocusIntervalMs: Long = DEFAULT_REFOCUS_INTERVAL_MS,
    private val frameStallTimeoutMs: Long = DEFAULT_FRAME_STALL_TIMEOUT_MS,
    private val frameStreamTimeoutMs: Long = DEFAULT_FRAME_STREAM_TIMEOUT_MS,
    private val decoderFailureThreshold: Int = DEFAULT_DECODER_FAILURE_THRESHOLD
) {
    private var sessionStartedAtMs: Long = 0L
    private var activeFrameStartedAtMs: Long? = null
    private var lastFrameCompletedAtMs: Long? = null
    private var lastRefocusAtMs: Long = 0L
    private var consecutiveDecoderFailures: Int = 0
    private var pendingRestartReason: QrScanRestartReason? = null

    var restartRequested: Boolean = false
        private set

    @Synchronized
    fun onSessionStarted(nowMs: Long) {
        sessionStartedAtMs = nowMs
        lastRefocusAtMs = nowMs
        activeFrameStartedAtMs = null
        lastFrameCompletedAtMs = null
        consecutiveDecoderFailures = 0
        pendingRestartReason = null
        restartRequested = false
    }

    @Synchronized
    fun onFrameStarted(nowMs: Long) {
        activeFrameStartedAtMs = nowMs
    }

    @Synchronized
    fun onFrameCompleted(nowMs: Long, outcome: QrFrameOutcome) {
        activeFrameStartedAtMs = null
        lastFrameCompletedAtMs = nowMs
        when (outcome) {
            QrFrameOutcome.Detected, QrFrameOutcome.Empty -> {
                // 管线健康（要么扫到码，要么确认没码），把累计失败清零。
                consecutiveDecoderFailures = 0
                pendingRestartReason = null
            }
            QrFrameOutcome.Failed -> {
                consecutiveDecoderFailures += 1
                if (consecutiveDecoderFailures >= decoderFailureThreshold) {
                    pendingRestartReason = QrScanRestartReason.RepeatedDecoderFailure
                }
            }
        }
    }

    @Synchronized
    fun onRefocusRequested(nowMs: Long) {
        lastRefocusAtMs = nowMs
    }

    @Synchronized
    fun nextAction(nowMs: Long, previewActive: Boolean): QrScanHealthAction {
        if (!previewActive || restartRequested) return QrScanHealthAction.None

        pendingRestartReason?.let { reason ->
            restartRequested = true
            return QrScanHealthAction.Restart(reason)
        }

        activeFrameStartedAtMs?.let { frameStartedAt ->
            if (nowMs - frameStartedAt >= frameStallTimeoutMs) {
                restartRequested = true
                return QrScanHealthAction.Restart(QrScanRestartReason.FrameStalled)
            }
        }

        lastFrameCompletedAtMs?.let { lastFrameAt ->
            if (activeFrameStartedAtMs == null && nowMs - lastFrameAt >= frameStreamTimeoutMs) {
                restartRequested = true
                return QrScanHealthAction.Restart(QrScanRestartReason.FrameStreamStopped)
            }
        }

        if (nowMs - lastRefocusAtMs >= refocusIntervalMs) {
            return QrScanHealthAction.Refocus
        }

        return QrScanHealthAction.None
    }

    companion object {
        const val DEFAULT_REFOCUS_INTERVAL_MS = 8_000L
        const val DEFAULT_FRAME_STALL_TIMEOUT_MS = 5_000L
        const val DEFAULT_FRAME_STREAM_TIMEOUT_MS = 4_000L
        const val DEFAULT_DECODER_FAILURE_THRESHOLD = 8
    }
}
