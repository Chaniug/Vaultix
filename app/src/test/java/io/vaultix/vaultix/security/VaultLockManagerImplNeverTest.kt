package io.vaultix.vaultix.security

import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.AutoUnlockRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
 * 「从不」档**离场软锁**的触发纪律（2026-09-29，方案 A）—— 用户报的那个 bug 的回归门禁。
 *
 * ## 用户原话与病灶
 *
 * > 「我是设置了从不，bitwarden 好像是清掉后台或者锁屏，还是会加锁，
 * >   而 vaultix 直接就是开着的，有风险吧。」
 *
 * 旧实现在 `VaultTimeout.Never` 分支直接 `return@launch` —— **任何原因都不锁**。
 * 用户划掉后台 / 锁屏后房钥匙一直留在内存里，进程被内存转储时可捞到密钥。
 *
 * ## 修复后的判据（本文件逐条钉死）
 *
 * | 触发 | Never 档应做什么 |
 * |---|---|
 * | `onAppBackgrounded()` | ★ **软锁**（清密钥、留信封） |
 * | `onAppCreated()` | **不**软锁（进程刚重建，钥匙本就不在内存） |
 *
 * ⚠️ 「留信封」由 `AutoUnlockRepositoryImplTest` 守、「回来自动开」由
 * `AutoRestoreTriggerTest` 守 —— 本文件只守**触发点**：后台这一下必须调到。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultLockManagerImplNeverTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vault(id: String) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = "库 $id",
        account = null,
        origin = "https://vault.example.com",
        unlocked = true,
    )

    /**
     * 造一个已定位到活动库的管理器（`activeVaultId` 由 `observeVaults()` 填充）。
     *
     * 返回前轮询到 `activeVaultId` 就位 —— 否则 `onAppBackgrounded()` 会在
     * `activeVaultId ?: return` 处早退，测试变成"什么都没验证"。
     */
    private suspend fun manager(
        timeout: VaultTimeout,
        autoUnlock: AutoUnlockRepository,
    ): VaultLockManagerImpl {
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.vaultTimeout } returns flowOf(timeout)

        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeVaults() } returns flowOf(listOf(vault("vault-1")))
        every { repository.observeUnlockedVaultIds() } returns flowOf(emptySet())

        val m = VaultLockManagerImpl(
            context = mockk<Context>(relaxed = true),
            vaultRepository = repository,
            preferences = prefs,
            autoUnlock = autoUnlock,
        )
        // 等 `activeVaultId` 被 init 里的收集赋值。
        withTimeoutOrNull(5_000L) { while (!m.isVaultUnlocked("vault-1")) delay(1) }
            ?: error("activeVaultId 未就位")
        return m
    }

    @Test
    fun `Never档_切后台_必须软锁`() = runTest {
        val autoUnlock = mockk<AutoUnlockRepository>(relaxed = true)
        val m = manager(VaultTimeout.Never, autoUnlock)

        m.onAppBackgrounded()

        // ★ 这条变红 = 退回了「Never 档任何原因都不锁」的旧 bug。
        withTimeoutOrNull(5_000L) {
            while (runCatching { coVerify(exactly = 1) { autoUnlock.softLock() } }.isFailure) delay(1)
        } ?: error("切后台未触发软锁")
    }

    @Test
    fun `Never档_进程创建_不软锁`() = runTest {
        // 进程刚重建时房钥匙本来就不在内存，软锁无事可做；恢复由 AutoRestoreTrigger 负责。
        val autoUnlock = mockk<AutoUnlockRepository>(relaxed = true)
        val m = manager(VaultTimeout.Never, autoUnlock)

        m.onAppCreated(isFirstCreation = true, createdForAutofill = false)
        delay(50)

        coVerify(exactly = 0) { autoUnlock.softLock() }
    }

    @Test
    fun `非Never档_切后台_不软锁`() = runTest {
        // 5 分钟档由超时定时器负责锁定（本测试不推进时间 ⇒ 定时器不触发）。
        // 重点：**软锁是 Never 档专属动作**，别档位混进来会与定时器语义打架。
        val autoUnlock = mockk<AutoUnlockRepository>(relaxed = true)
        val m = manager(VaultTimeout.FiveMinutes, autoUnlock)

        m.onAppBackgrounded()
        delay(50)

        coVerify(exactly = 0) { autoUnlock.softLock() }
    }
}
