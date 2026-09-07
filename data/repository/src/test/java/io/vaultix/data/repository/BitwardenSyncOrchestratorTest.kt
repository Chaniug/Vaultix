package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.domain.SyncTrigger
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 同步编排器（移植 Bastion SyncOrchestrator 语义）：
 * 手动执行/进页节流/运行中合并回放/指数退避重试/状态记录。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BitwardenSyncOrchestratorTest {

    private val vaultRepository = mockk<VaultRepository>()
    private val sessions = VaultSessionManager()
    private val vaultId = "https://vault.example.com"

    // fake 时钟起点取大值：Runtime.lastPageEnterAt=0 表示「从未同步」，
    // 从 0 起算会把首次进页误判为刚同步过而被节流
    private var nowMs = 1_000_000_000L

    @Before
    fun unlock() {
        // PAGE_ENTER/APP_RESUME 门卫需要已解锁
        coEvery { vaultRepository.syncVault(vaultId) } returns VaultSyncReport.Success(3, 1)
    }

    private fun newOrchestrator(scope: kotlinx.coroutines.CoroutineScope) =
        BitwardenSyncOrchestrator(
            vaultRepository = vaultRepository,
            sessions = sessions,
            scope = scope,
            config = BitwardenSyncOrchestrator.Config(
                pageEnterThrottleMs = 90_000L,
                appResumeThrottleMs = 180_000L,
                retryBaseDelayMs = 5_000L,
                retryMaxDelayMs = 15 * 60_000L,
                retryMaxAttempts = 5,
            ),
            now = { nowMs },
        )

    @Test
    fun manualForceRunsAndRecordsSuccess() = runTest {
        sessions.unlock(vaultId, SymmetricCryptoKey.random())
        val orchestrator = newOrchestrator(backgroundScope)

        orchestrator.requestSync(vaultId, SyncTrigger.MANUAL, force = true)
        advanceTimeBy(1_000)
        runCurrent()

        val status = orchestrator.statusOf(vaultId)
        assertEquals(3, status.lastSuccessCipherCount)
        assertNotNull(status.lastSuccessAt)
        coVerify(exactly = 1) { vaultRepository.syncVault(vaultId) }
    }

    @Test
    fun pageEnterIsThrottledWithinWindow() = runTest {
        sessions.unlock(vaultId, SymmetricCryptoKey.random())
        val orchestrator = newOrchestrator(backgroundScope)

        orchestrator.requestSync(vaultId, SyncTrigger.PAGE_ENTER)
        advanceTimeBy(1_000)
        runCurrent()

        // 90s 窗口内第二次进页：跳过（不执行）
        orchestrator.requestSync(vaultId, SyncTrigger.PAGE_ENTER)
        advanceTimeBy(1_000)
        runCurrent()

        // 窗口过后再进页：执行
        nowMs += 90_000
        orchestrator.requestSync(vaultId, SyncTrigger.PAGE_ENTER)
        advanceTimeBy(1_000)
        runCurrent()

        coVerify(exactly = 2) { vaultRepository.syncVault(vaultId) }
    }

    @Test
    fun requestWhileRunningIsMergedAndReplayed() = runTest {
        sessions.unlock(vaultId, SymmetricCryptoKey.random())
        coEvery { vaultRepository.syncVault(vaultId) } coAnswers {
            delay(200)
            VaultSyncReport.Success(3, 1)
        }
        val orchestrator = newOrchestrator(backgroundScope)

        orchestrator.requestSync(vaultId, SyncTrigger.MANUAL, force = true)
        advanceTimeBy(50) // 同步进行中
        // 运行中再请求（低优先级）：合并等待回放
        orchestrator.requestSync(vaultId, SyncTrigger.PAGE_ENTER)
        advanceTimeBy(1_000)
        runCurrent()

        coVerify(exactly = 2) { vaultRepository.syncVault(vaultId) }
    }

    @Test
    fun retryableErrorSchedulesExponentialRetry() = runTest {
        sessions.unlock(vaultId, SymmetricCryptoKey.random())
        var calls = 0
        coEvery { vaultRepository.syncVault(vaultId) } coAnswers {
            calls++
            if (calls == 1) VaultSyncReport.Retryable("网络不可用")
            else VaultSyncReport.Success(3, 1)
        }
        val orchestrator = newOrchestrator(backgroundScope)

        orchestrator.requestSync(vaultId, SyncTrigger.MANUAL, force = true)
        advanceTimeBy(1_000)
        runCurrent()

        // 第一次失败 → 状态记录错误与 5s 后重试
        val afterFail = orchestrator.statusOf(vaultId)
        assertEquals("网络不可用", afterFail.lastError)
        assertEquals(1, afterFail.retryAttempt)
        assertNotNull(afterFail.nextRetryAt)

        // 时间推进过退避窗口 → 自动 RETRY 并成功
        advanceTimeBy(5_000)
        runCurrent()
        val afterRetry = orchestrator.statusOf(vaultId)
        assertNull(afterRetry.lastError)
        assertEquals(0, afterRetry.retryAttempt)
        coVerify(exactly = 2) { vaultRepository.syncVault(vaultId) }
    }
}
