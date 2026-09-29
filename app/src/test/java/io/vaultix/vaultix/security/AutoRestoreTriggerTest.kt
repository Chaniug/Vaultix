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
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 「从不」档自动恢复的**编排规则**测试（2026-09-29 二次定稿）。
 *
 * ## ⚠️ 本文件已反转：从「守前台门禁」改为「守无门禁」
 *
 * 上一版本文件守的是「**只有前台才允许恢复**」这个闸门。该闸门连同
 * `Never` 档的离场软锁**一起被推翻**（用户三次拍板「对齐 Bitwarden」）——
 * 见 `VaultLockManagerImpl.checkForVaultTimeoutInternal` 的 KDoc「为什么推翻」。
 *
 * > 没有软锁 ⇒ 没有「后台密钥被读回」这个风险 ⇒ 门禁失去保护对象，
 * > 反而会挡住 autofill 冷启动进程的恢复（历史上实测卡 5.6 秒）。
 *
 * ## 当前判据（三条，与 `AutoRestoreTrigger.reconcile` 一一对应）
 *
 * | 状态 | 应做什么 |
 * |---|---|
 * | 档位 ≠ Never 且信封在 | **删信封**，且不恢复 |
 * | 档位 = Never 且钥匙在内存 | **幂等写信封**（不恢复） |
 * | 档位 = Never 且无钥匙且信封在 | **恢复**（⚠️ 不再判前台） |
 *
 * ## 变异验证（本文件建好后必做）
 *
 * 临时给 `AutoRestoreTrigger.combine` 加回 `lifecycle.isForeground` 作第三源、
 * 并在 `reconcile` 里加回 `if (!foreground) return`，确认
 * `Never且无钥匙_后台也恢复` **变红**。
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

    /** 触发器的两个输入源（测试逐个翻动它们，观察触发器怎么反应）。 */
    private class Fixture {
        val timeout = MutableStateFlow<VaultTimeout>(VaultTimeout.Never)
        val keyInMemory = MutableStateFlow(false)
    }

    /**
     * D3（2026-09-29）后触发器要读**库表**（它按库聚合「是否还有 Never 档的库」）——
     * 这里给一个固定库 id，测试仍靠翻转 `f.timeout` 驱动（`vaultTimeout(any())` 统一返回它）。
     */
    private fun vault(id: String) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = "库 $id",
        account = null,
        origin = "https://vault.example.com",
        unlocked = true,
    )

    private fun trigger(
        f: Fixture,
        hasEnvelope: Boolean = true,
        restoreReport: AutoRestoreReport = AutoRestoreReport(true, 1, 1, emptyList()),
    ): Pair<AutoRestoreTrigger, AutoUnlockRepository> {
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        // D3：档位**每库一份** ⇒ 触发器改为「按库聚合是否还有 Never」（`anyVaultNever()`）。
        // 本夹具统一回同一支 flow，测试依旧靠翻转 `f.timeout` 驱动（与改前等价）。
        every { prefs.vaultTimeout(any()) } returns f.timeout

        val autoUnlock = mockk<AutoUnlockRepository>(relaxed = true)
        every { autoUnlock.houseKeyInMemory } returns f.keyInMemory
        coEvery { autoUnlock.hasEnvelope() } returns hasEnvelope
        coEvery { autoUnlock.restore() } returns restoreReport

        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeUnlockedVaultIds() } returns flowOf(emptySet())
        // D3：`anyVaultNever()` 要先知道库表（逐库读档位）⇒ 这里给一个固定库。
        every { repository.observeVaults() } returns flowOf(listOf(vault("vault-1")))

        return AutoRestoreTrigger(prefs, autoUnlock, repository) to autoUnlock
    }

    /**
     * 轮询终态：`Dispatchers.Default` 上的收集**不受 `runTest` 虚拟时钟控制**（见项目纪律）。
     *
     * ⚠️ **2026-09-29 修：必须走真实时间。**
     *
     * `AutoRestoreTrigger.scope` 硬编码 `Dispatchers.Default`，`Dispatchers.setMain(...)`
     * 对它无效；而 `runTest` 只虚拟化**测试调度器**上的 `delay`。旧写法
     * `withTimeoutOrNull(5_000L) { while (!predicate()) delay(1) }` 会在**真实时间的一瞬**
     * 耗尽 5000ms 虚拟预算 ⇒ 等价于"只检查一次 predicate"，成败取决于 Default 线程
     * 是否碰巧已跑完 —— **既可能假绿、也可能假红**。
     *
     * 现改为在 `Dispatchers.Default` 上 `delay` ⇒ 走真实时间，可靠等到终态。
     */
    private suspend fun awaitCondition(description: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        error("等待超时：$description")
    }

    /** 让出**真实时间**，等 `Dispatchers.Default` 上的协程跑完（同 [awaitCondition] 的理由）。 */
    private suspend fun settle(ms: Long = 500L) = withContext(Dispatchers.Default) { delay(ms) }

    // ---- 规则 3：恢复（⚠️ 本文件的重点，已去掉前台门禁）----

    @Test
    fun `Never且无钥匙_后台也恢复`() = runTest {
        // ⚠️ 这条是**反转后的门禁**：上一版本断言"后台绝不恢复"，现在断言"后台也要恢复"。
        //   变红的含义：有人把前台门禁加回来了 ⇒ autofill 冷启动的恢复又会被挡住。
        val f = Fixture()
        val (_, autoUnlock) = trigger(f)

        // 新进程冷启动：钥匙不在内存、信封在（此处"进程在后台"是常态，因为
        // autofill 拉起的进程还没走完 onStart —— 门禁正是卡在这里）。
        f.timeout.value = VaultTimeout.Never
        f.keyInMemory.value = false
        awaitCondition("无钥匙 + 有信封 ⇒ 应恢复（不判前台）") {
            runCatching { coVerify(exactly = 1) { autoUnlock.restore() } }.isSuccess
        }
    }

    @Test
    fun `Never且无钥匙但没信封_不恢复`() = runTest {
        // 信封不在（例如用户主动「锁定」删了它）⇒ 没什么可恢复的，
        // 老老实实让用户走指纹/PIN。不能在这里伪造恢复。
        val f = Fixture()
        val (_, autoUnlock) = trigger(f, hasEnvelope = false)

        f.timeout.value = VaultTimeout.Never
        f.keyInMemory.value = false
        settle()

        coVerify(exactly = 0) { autoUnlock.restore() }
    }

    // ---- 规则 2：幂等写信封 ----

    @Test
    fun `Never且钥匙在内存_写信封而不是恢复`() = runTest {
        val f = Fixture()
        val (_, autoUnlock) = trigger(f, hasEnvelope = false)

        f.timeout.value = VaultTimeout.Never
        f.keyInMemory.value = true
        awaitCondition("应幂等写信封") {
            runCatching { coVerify(exactly = 1) { autoUnlock.enrollEnvelope() } }.isSuccess
        }
        coVerify(exactly = 0) { autoUnlock.restore() }
    }

    // ---- 规则 1：档位离开 Never ⇒ 删信封 ----

    @Test
    fun `档位离开Never_删信封且不恢复`() = runTest {
        val f = Fixture()
        val (_, autoUnlock) = trigger(f, hasEnvelope = true)

        f.keyInMemory.value = false
        f.timeout.value = VaultTimeout.FiveMinutes
        awaitCondition("离开 Never 应删信封") {
            runCatching { coVerify(exactly = 1) { autoUnlock.removeEnvelope() } }.isSuccess
        }
        // 非 Never 档下恢复永不发生（该档位由超时定时器负责锁定）。
        coVerify(exactly = 0) { autoUnlock.restore() }
    }

    @Test
    fun `非Never档_即使有信封也不恢复`() = runTest {
        val f = Fixture()
        val (_, autoUnlock) = trigger(f, hasEnvelope = true)

        f.timeout.value = VaultTimeout.FiveMinutes
        f.keyInMemory.value = false
        settle()

        coVerify(exactly = 0) { autoUnlock.restore() }
    }
}
