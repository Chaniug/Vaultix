package io.vaultix.vaultix.security

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.AutoUnlockRepository
import io.vaultix.domain.AutoRestoreReport
import io.vaultix.domain.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 「从不」档自动恢复的**闸门**测试（2026-09-29，方案 A）。
 *
 * ## 这套测试守的是什么
 *
 * 用户报「设置了从不，锁屏/清后台后 Vaultix 直接就是开着的，有风险」。
 * 修复分两半，本文件钉第二半（**恢复的前台门禁**）：
 *
 * 1. `AutoUnlockRepository.softLock()` —— 离场清密钥、**留**信封
 *    （该动作的实现测试在 `AutoUnlockRepositoryImplTest`）；
 * 2. `AutoRestoreTrigger` —— ⚠️ **只有前台才允许恢复**。
 *
 * ## 为什么第 2 条是安全底线（不是优化）
 *
 * 离场软锁会把 `houseKeyInMemory` 从 true 翻成 false，而本类正是在 combine 里
 * 观察这个值。**没有门禁的话**：
 *
 * ```
 * 划掉后台 → softLock()（钥匙清零）→ houseKeyInMemory=false
 *   → 本类立刻触发恢复分支 → 钥匙回到内存
 *   ⇒ 软锁从未发生：后台进程照样抓着密钥
 * ```
 *
 * ⇒ 若哪天有人"顺手"把这个门禁去掉，本文件的用例必须变红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoRestoreTriggerTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 夹具 ----

    /** 三源的可变状态（测试逐个翻动它们，观察触发器怎么反应）。 */
    private class Fixture {
        val timeout = MutableStateFlow<VaultTimeout>(VaultTimeout.Never)
        val keyInMemory = MutableStateFlow(false)
        val foreground = MutableStateFlow(false)
    }

    private fun fixture(): Fixture = Fixture()

    private fun trigger(
        f: Fixture,
        hasEnvelope: Boolean = true,
        restoreReport: AutoRestoreReport = AutoRestoreReport(true, 1, 1, emptyList()),
    ): Pair<AutoRestoreTrigger, AutoUnlockRepository> {
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.vaultTimeout } returns f.timeout

        val autoUnlock = mockk<AutoUnlockRepository>(relaxed = true)
        every { autoUnlock.houseKeyInMemory } returns f.keyInMemory
        coEvery { autoUnlock.hasEnvelope() } returns hasEnvelope
        coEvery { autoUnlock.restore() } returns restoreReport

        val lifecycle = mockk<AutoLockController>(relaxed = true)
        every { lifecycle.isForeground } returns f.foreground

        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeUnlockedVaultIds() } returns flowOf(emptySet())

        return AutoRestoreTrigger(prefs, autoUnlock, repository, lifecycle) to autoUnlock
    }

    /** 轮询终态：`Dispatchers.Default` 上的收集不受 `runTest` 调度控制（见项目纪律）。 */
    private suspend fun awaitCondition(description: String, predicate: () -> Boolean) {
        withTimeoutOrNull(5_000L) { while (!predicate()) delay(1) }
            ?: error("等待超时：$description")
    }

    // ---- 前台门禁（本文件的重点）----

    @Test
    fun `后台无钥匙有信封_绝不恢复`() = runTest {
        val f = fixture()
        val (_, autoUnlock) = trigger(f)

        // 离场软锁造成的状态：钥匙不在内存、信封还在、**进程在后台**。
        f.timeout.value = VaultTimeout.Never
        f.keyInMemory.value = false
        f.foreground.value = false
        delay(50)

        // ★ 安全底线：后台绝不把钥匙解回来（否则软锁形同虚设）。
        coVerify(exactly = 0) { autoUnlock.restore() }
    }

    @Test
    fun `回到前台_才恢复`() = runTest {
        val f = fixture()
        val (_, autoUnlock) = trigger(f)

        f.timeout.value = VaultTimeout.Never
        f.keyInMemory.value = false
        f.foreground.value = false
        delay(50)
        coVerify(exactly = 0) { autoUnlock.restore() }

        // 用户回到前台 ⇒ 闸门打开，免交互恢复（体感「回来就开着」）。
        f.foreground.value = true
        awaitCondition("回前台后应发生恢复") {
            runCatching { coVerify(exactly = 1) { autoUnlock.restore() } }.isSuccess
        }
    }

    @Test
    fun `前台但钥匙在内存_写信封而不是恢复`() = runTest {
        val f = fixture()
        val (_, autoUnlock) = trigger(f, hasEnvelope = false)

        f.timeout.value = VaultTimeout.Never
        f.keyInMemory.value = true
        f.foreground.value = true
        awaitCondition("应幂等写信封") {
            runCatching { coVerify(exactly = 1) { autoUnlock.enrollEnvelope() } }.isSuccess
        }
        coVerify(exactly = 0) { autoUnlock.restore() }
    }

    @Test
    fun `档位离开Never_删信封且不恢复`() = runTest {
        val f = fixture()
        val (_, autoUnlock) = trigger(f, hasEnvelope = true)

        f.foreground.value = true
        f.keyInMemory.value = false
        f.timeout.value = VaultTimeout.FiveMinutes
        awaitCondition("离开 Never 应删信封") {
            runCatching { coVerify(exactly = 1) { autoUnlock.removeEnvelope() } }.isSuccess
        }
        // 非 Never 档下恢复永不发生（该档位由超时定时器负责锁定）。
        coVerify(exactly = 0) { autoUnlock.restore() }
    }

    @Test
    fun `后台写信封不被门禁挡住`() = runTest {
        // 门禁只管"恢复"这一支：写信封在后台做是安全的（钥匙本来就在内存里），
        // 而且必须做 —— 否则用户切后台再被杀进程，Never 档就恢复不了了。
        val f = fixture()
        val (_, autoUnlock) = trigger(f, hasEnvelope = false)

        f.timeout.value = VaultTimeout.Never
        f.keyInMemory.value = true
        f.foreground.value = false
        awaitCondition("后台也该写信封") {
            runCatching { coVerify(exactly = 1) { autoUnlock.enrollEnvelope() } }.isSuccess
        }
        coVerify(exactly = 0) { autoUnlock.restore() }
    }

    @Test
    fun `有信封但无钥匙且后台_信封不得被删`() = runTest {
        // 这条守的是「别把门禁写成'后台什么都不做'」：后台该做的收敛动作
        // （写信封）照做，只是"恢复"被挡。信封也必须**留着**（那是回来自动开的凭据）。
        val f = fixture()
        val (_, autoUnlock) = trigger(f, hasEnvelope = true)

        f.timeout.value = VaultTimeout.Never
        f.keyInMemory.value = false
        f.foreground.value = false
        delay(50)

        coVerify(exactly = 0) { autoUnlock.removeEnvelope() }
        coVerify(exactly = 0) { autoUnlock.restore() }
    }
}
