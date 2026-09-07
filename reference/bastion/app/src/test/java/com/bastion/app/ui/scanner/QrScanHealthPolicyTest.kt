package com.bastion.app.ui.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrScanHealthPolicyTest {
    @Test
    fun longIdleWithHealthyFramesKeepsScanningAndPeriodicallyRefocuses() {
        val policy = QrScanHealthPolicy()
        policy.onSessionStarted(nowMs = 0L)

        var refocusCount = 0
        for (now in 0L..300_000L step 100L) {
            policy.onFrameStarted(now)
            policy.onFrameCompleted(now + 20L, outcome = QrFrameOutcome.Detected)
            if (policy.nextAction(now + 20L, previewActive = true) == QrScanHealthAction.Refocus) {
                refocusCount += 1
                policy.onRefocusRequested(now + 20L)
            }
        }

        assertTrue("long-idle scanning should refresh focus repeatedly", refocusCount >= 30)
        assertFalse(policy.restartRequested)
    }

    @Test
    fun stalledFrameRequestsAFullSessionRestart() {
        val policy = QrScanHealthPolicy()
        policy.onSessionStarted(nowMs = 0L)
        policy.onFrameStarted(nowMs = 100L)

        assertEquals(
            QrScanHealthAction.Restart(QrScanRestartReason.FrameStalled),
            policy.nextAction(nowMs = 6_000L, previewActive = true)
        )
    }

    @Test
    fun missingFramesAfterAHealthyStreamRequestsRestart() {
        val policy = QrScanHealthPolicy()
        policy.onSessionStarted(nowMs = 0L)
        policy.onFrameStarted(nowMs = 100L)
        policy.onFrameCompleted(nowMs = 120L, outcome = QrFrameOutcome.Detected)

        assertEquals(
            QrScanHealthAction.Restart(QrScanRestartReason.FrameStreamStopped),
            policy.nextAction(nowMs = 5_000L, previewActive = true)
        )
    }

    @Test
    fun transientDecoderFailureDoesNotRestartButRepeatedFailuresDo() {
        val policy = QrScanHealthPolicy()
        policy.onSessionStarted(nowMs = 0L)

        // 连续 7 次失败仍不足 8 次阈值，应保持 None
        repeat(7) { index ->
            val now = 100L + index * 100L
            policy.onFrameStarted(now)
            policy.onFrameCompleted(now + 10L, outcome = QrFrameOutcome.Failed)
        }
        assertEquals(QrScanHealthAction.None, policy.nextAction(850L, previewActive = true))

        // 第 8 次失败达到阈值，触发重启
        policy.onFrameStarted(900L)
        policy.onFrameCompleted(910L, outcome = QrFrameOutcome.Failed)
        assertEquals(
            QrScanHealthAction.Restart(QrScanRestartReason.RepeatedDecoderFailure),
            policy.nextAction(920L, previewActive = true)
        )
    }

    @Test
    fun emptyFramesAreNotCountedAsDecoderFailures() {
        // 视野里没码（Empty）应该让失败计数清零，绝不能触发重启。
        val policy = QrScanHealthPolicy()
        policy.onSessionStarted(nowMs = 0L)

        for (index in 0 until 200L) {
            val now = 100L + index * 100L
            policy.onFrameStarted(now)
            policy.onFrameCompleted(now + 10L, outcome = QrFrameOutcome.Empty)
            if (policy.nextAction(now + 20L, previewActive = true)
                is QrScanHealthAction.Restart
            ) {
                throw AssertionError("empty frames should never trigger restart, frame index=$index")
            }
        }
        assertFalse(policy.restartRequested)
    }

    @Test
    fun failedFrameFollowedByEmptyClearsTheFailureStreak() {
        val policy = QrScanHealthPolicy()
        policy.onSessionStarted(nowMs = 0L)

        // 累积 4 次失败，远未达 8 次阈值；插一帧 Empty 把累计清零
        repeat(4) { i ->
            val now = 100L + i * 100L
            policy.onFrameStarted(now)
            policy.onFrameCompleted(now + 10L, outcome = QrFrameOutcome.Failed)
        }
        policy.onFrameStarted(500L)
        policy.onFrameCompleted(510L, outcome = QrFrameOutcome.Empty)

        // 接下来再失败 7 次，累加 = 7 < 8 仍安全
        repeat(7) { i ->
            val now = 600L + i * 100L
            policy.onFrameStarted(now)
            policy.onFrameCompleted(now + 10L, outcome = QrFrameOutcome.Failed)
        }
        assertEquals(QrScanHealthAction.None, policy.nextAction(1_400L, previewActive = true))
    }

    @Test
    fun pausedPreviewNeverTriggersRecovery() {
        val policy = QrScanHealthPolicy()
        policy.onSessionStarted(nowMs = 0L)
        policy.onFrameStarted(nowMs = 100L)

        assertEquals(QrScanHealthAction.None, policy.nextAction(10_000L, previewActive = false))
    }
}
