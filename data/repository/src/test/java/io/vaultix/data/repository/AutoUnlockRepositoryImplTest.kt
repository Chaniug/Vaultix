package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultTimeoutPreferences
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 「从不」档**离场软锁**的编排纪律（2026-09-29，方案 A）。
 *
 * ## 本类守的不变量
 *
 * 软锁 = 「清密钥、**留**信封」。它与硬锁（`VaultRepository.lockVault/lockAll`）
 * 在「密钥是否清零」上完全一致，**唯一差别是信封的留与删** —— 那条差别正是
 * 「回来免交互打开」（方案 A）与「回来重新过门锁」（方案 B）的分界。
 *
 * 1. **绝不删信封** —— 删了用户回来就要重新指纹，与承诺不符；
 * 2. **必须真的清房钥匙** —— 否则「后台期间密钥不在内存」这个安全保证不存在；
 * 3. **两条会话模型都要收**（Bitwarden 密钥 + KDBX 整库明文），漏一个就漏一把密钥。
 *
 * ⚠️ 恢复的前台门禁由 `AutoRestoreTriggerTest` 守（那是安全底线的另一半）。
 */
class AutoUnlockRepositoryImplTest {

    private val houseKeyStore = mockk<HouseKeyStore>()
    private val vaultRepository = mockk<VaultRepository>()
    private val sessions = mockk<VaultSessionManager>(relaxed = true)

    /**
     * 每库档位（D3，2026-09-29）：`restore()` 会按库读档位（只恢复 Never 档的房间）。
     * 默认全部 Never ⇒ 与 D3 之前的行为等价（那时判据是全局档位）。
     */
    private val preferences = mockk<VaultTimeoutPreferences>(relaxed = true)
    private lateinit var repo: AutoUnlockRepositoryImpl

    private fun vault(id: String, unlocked: Boolean) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = "库 $id",
        account = null,
        origin = "https://vault.example.com",
        unlocked = unlocked,
    )

    @Before
    fun setUp() {
        // ⚠️ 非 suspend 成员（`lock()` / `isUnlocked` / `isUnlockedFlow`）只能用 `every` ——
        //   用 `coEvery` 会静默失效，等到实现里真调用时 mockk 报 "no answer found"。
        every { houseKeyStore.lock() } just Runs
        every { houseKeyStore.isUnlocked } returns true
        every { houseKeyStore.isUnlockedFlow } returns MutableStateFlow(true)
        coEvery { houseKeyStore.hasAutoEnvelope() } returns true
        coEvery { houseKeyStore.removeAutoEnvelope() } just Runs
        coEvery { houseKeyStore.roomVaultIds() } returns emptyList()
        // ★ 多库锁模型定稿 **D1**（2026-09-29）：`restore()` 现在会读「用户主动锁」名单
        //   （跳过这些库）。HouseKeyStore 是**非 relaxed** mock ⇒ 未打桩即抛
        //   "no answer found"，所以这两条是必需的。
        coEvery { houseKeyStore.userLockedVaultIds() } returns emptySet()
        coEvery { houseKeyStore.clearUserLocked(any()) } just Runs
        // D3：默认所有库都是 Never 档（= D3 之前「全局 Never」的等价结论）。
        every { preferences.vaultTimeout(any()) } returns flowOf(VaultTimeout.Never)
        every { vaultRepository.observeVaults() } returns
            flowOf(listOf(vault("a", true), vault("b", false), vault("c", true)))
        repo = AutoUnlockRepositoryImpl(houseKeyStore, vaultRepository, sessions, preferences)
    }

    @Test
    fun `离场软锁_清房钥匙`() = runTest {
        // ★ 软锁的首要目的：后台期间密钥不在内存（内存转储捞不到）。
        repo.softLock()
        verify(exactly = 1) { houseKeyStore.lock() }
    }

    @Test
    fun `离场软锁_绝不删信封`() = runTest {
        // ★ 方案 A 的核心：信封留着，用户回来才能免交互打开。
        //   这条变红 = 退化成方案 B（用户回来要重新指纹）。
        repo.softLock()
        coVerify(exactly = 0) { houseKeyStore.removeAutoEnvelope() }
    }

    @Test
    fun `离场软锁_两条会话模型都收`() = runTest {
        // 漏掉任一条就漏一把密钥：Bitwarden 侧是密钥，KDBX 侧是整库明文。
        repo.softLock()
        coVerify(exactly = 1) { sessions.lockAll() }
    }

    @Test
    fun `离场软锁_上报软锁前仍解锁的库`() = runTest {
        // 返回值是诊断/测试用：只数软锁**前**处于解锁态的库（b 本来就锁着，不该算）。
        assertEquals(listOf("a", "c"), repo.softLock())
    }

    @Test
    fun `离场软锁_幂等`() = runTest {
        // 连续两次软锁（例如切后台两次）不该出任何岔子：各自清一次、信封始终不动。
        repo.softLock()
        repo.softLock()
        verify(exactly = 2) { houseKeyStore.lock() }
        coVerify(exactly = 0) { houseKeyStore.removeAutoEnvelope() }
    }

    @Test
    fun `信封存在性_透传自HouseKeyStore`() = runTest {
        coEvery { houseKeyStore.hasAutoEnvelope() } returns false
        assertEquals(false, repo.hasEnvelope())
    }

    @Test
    fun `恢复_钥匙已在内存则不碰信封`() = runTest {
        // 钥匙已在内存（别的入口刚解开）⇒ 信封不是唯一途径，别白解一次。
        every { houseKeyStore.isUnlocked } returns true
        val report = repo.restore()
        assertEquals(true, report.envelopeOpened)
    }

    @Test
    fun `恢复_信封不可解则如实报未恢复`() = runTest {
        every { houseKeyStore.isUnlocked } returns false
        coEvery { houseKeyStore.openAutoEnvelope() } returns false
        val report = repo.restore()
        assertEquals(false, report.envelopeOpened)
    }

    @Test
    fun `恢复_逐库独立成败`() = runTest {
        every { houseKeyStore.isUnlocked } returns true
        coEvery { houseKeyStore.roomVaultIds() } returns listOf("a", "b")
        coEvery { vaultRepository.unlockVaultFromRoom("a") } returns RoomUnlockOutcome.Opened
        coEvery { vaultRepository.unlockVaultFromRoom("b") } returns
            RoomUnlockOutcome.Unavailable("文件移走了")
        val report = repo.restore()
        assertEquals(1, report.opened)
        assertEquals(listOf("b"), report.failedVaultIds)
    }

    // ============ 多库锁模型定稿 D1 / §3.2（2026-09-29）：用户主动锁的库不恢复 ============

    /**
     * ★★ **D1 的恢复侧一半**：用户主动锁过的库，自动恢复**必须跳过**。
     *
     * 「锁 A 不影响 B 的恢复能力」+「A 也不会在冷启动被悄悄开回来」两件事，
     * 由「每库标记 + 这里跳过」共同保证（写标记在 `VaultRepositoryImpl.lockVault`，
     * 清标记在 `AutoRestoreTrigger` 的解锁成功钩子）。
     *
     * ⚠️ 本条变红 = 用户锁过的库又被自动开了 ⇒「锁定」被静默撤销
     * （旧实现靠删**全局**信封达到这个效果，代价是连带掐掉别的库 —— 见 #131）。
     */
    @Test
    fun `恢复_跳过用户主动锁的库`() = runTest {
        every { houseKeyStore.isUnlocked } returns true
        coEvery { houseKeyStore.roomVaultIds() } returns listOf("a", "b")
        coEvery { houseKeyStore.userLockedVaultIds() } returns setOf("a")
        coEvery { vaultRepository.unlockVaultFromRoom("b") } returns RoomUnlockOutcome.Opened

        val report = repo.restore()

        assertEquals(1, report.roomCount)
        assertEquals(1, report.opened)
        coVerify(exactly = 0) { vaultRepository.unlockVaultFromRoom("a") }
    }

    /** 清标记透传（`AutoRestoreTrigger` 的解锁成功钩子调它）。 */
    @Test
    fun `用户锁解除_透传自HouseKeyStore`() = runTest {
        repo.clearUserLock("a")
        coVerify(exactly = 1) { houseKeyStore.clearUserLocked("a") }
    }

    /**
     * ★★ **D3 / §3.2 的档位侧**：**非 Never 档**的库，冷启动**不恢复**。
     *
     * 非 Never 的语义是「离开期间可能已到期」⇒ 冷启动不该把它悄悄开回来
     * （对齐 Bitwarden：`autoUnlockKey` 仅 Never 档存在）。
     *
     * ⚠️ 本条变红 = 定时档的库被冷启动静默开回来，档位形同虚设。
     */
    @Test
    fun `恢复_跳过非Never档的库`() = runTest {
        every { houseKeyStore.isUnlocked } returns true
        coEvery { houseKeyStore.roomVaultIds() } returns listOf("a", "b")
        every { preferences.vaultTimeout("a") } returns flowOf(VaultTimeout.Never)
        every { preferences.vaultTimeout("b") } returns flowOf(VaultTimeout.FiveMinutes)
        coEvery { vaultRepository.unlockVaultFromRoom("a") } returns RoomUnlockOutcome.Opened

        val report = repo.restore()

        assertEquals(1, report.roomCount)
        assertEquals(1, report.opened)
        coVerify(exactly = 0) { vaultRepository.unlockVaultFromRoom("b") }
    }
}
