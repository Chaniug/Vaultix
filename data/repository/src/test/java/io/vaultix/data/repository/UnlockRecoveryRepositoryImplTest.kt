package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import javax.crypto.Cipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 快速解锁失效矩阵的善后编排（批次 4，定稿 §6）。
 *
 * ## 本类守的不变量
 *
 * `HouseKeyStore` 的钥匙学正确性由 `HouseKeyStoreTest` 守；这里守的是**编排纪律**，
 * 尤其是**唯一判据**那条：
 *
 * 1. **rearm 与降级的分水岭是「房钥匙在不在内存」**，不是「信封在不在」。
 *    混淆会把开门态误判成关门态 ⇒ 降级 ⇒ **房间信封全丢**（用户每个库都要重登记）。
 * 2. **rearm 失败必须保留标记**（钥匙回来后仍应能重装）。
 * 3. **降级必须如实上报影响**（连带清掉几个房间信封 —— 硬约束 #5：绝不静默）。
 * 4. **无信封时降级是 no-op**：不能因为「恰好 KEK 失效」就去动别的锁的房间。
 */
class UnlockRecoveryRepositoryImplTest {

    private val houseKeyStore = mockk<HouseKeyStore>()
    private lateinit var repo: UnlockRecoveryRepositoryImpl
    private val cipher = mockk<Cipher>()

    @Before
    fun setUp() {
        // 默认态：房钥匙不在内存、无任何信封、无房间 —— 各用例按需覆写。
        coEvery { houseKeyStore.isFingerprintLockInvalidated() } returns false
        coEvery { houseKeyStore.hasFingerprintRearmPending() } returns false
        coEvery { houseKeyStore.markFingerprintRearmPending() } just Runs
        coEvery { houseKeyStore.clearFingerprintRearmPending() } just Runs
        coEvery { houseKeyStore.rearmFingerprintLock(any()) } returns false
        coEvery { houseKeyStore.hasFingerprintEnvelope() } returns false
        coEvery { houseKeyStore.hasAnyLock() } returns false
        coEvery { houseKeyStore.roomVaultIds() } returns emptyList()
        coEvery { houseKeyStore.disableFingerprintLock() } just Runs
        every { houseKeyStore.isUnlockedFlow } returns MutableStateFlow(false)
        repo = UnlockRecoveryRepositoryImpl(houseKeyStore)
    }

    // ===== 1) 判据源 =====

    @Test
    fun `房钥匙内存态_透传自 HouseKeyStore`() = runTest {
        // 判据必须来自「钥匙在不在」这一个源 —— 换个源就会出现两处判断漂移，
        // 而这条漂移的代价是把 rearm 走成降级（丢房间信封）。
        every { houseKeyStore.isUnlockedFlow } returns MutableStateFlow(true)
        assertTrue(UnlockRecoveryRepositoryImpl(houseKeyStore).houseKeyInMemory.first())
    }

    @Test
    fun `失效信号_原样透传不做二次加工`() = runTest {
        coEvery { houseKeyStore.isFingerprintLockInvalidated() } returns true
        assertTrue(repo.isFingerprintLockInvalidated())
        coVerify(exactly = 1) { houseKeyStore.isFingerprintLockInvalidated() }

        coEvery { houseKeyStore.isFingerprintLockInvalidated() } returns false
        assertFalse(repo.isFingerprintLockInvalidated())
    }

    // ===== 2) rearm =====

    @Test
    fun `标记待重装_只写标记不重包`() = runTest {
        // ★ 本批最核心的取舍：**当场不能重包**（auth-per-use 下没有已授权的 cipher）。
        // 若这里顺手调 rearmFingerprintLock，真机上会拿到一个无人授权的 cipher，
        // 表现是「标记了却重不平」，比不标记更糟。
        repo.markRearmPending()

        coVerify(exactly = 1) { houseKeyStore.markFingerprintRearmPending() }
        coVerify(exactly = 0) { houseKeyStore.rearmFingerprintLock(any()) }
    }

    @Test
    fun `重装成功_标记由 HouseKeyStore 清除`() = runTest {
        coEvery { houseKeyStore.rearmFingerprintLock(cipher) } returns true

        assertTrue(repo.rearmFingerprintLock(cipher))
        coVerify(exactly = 1) { houseKeyStore.rearmFingerprintLock(cipher) }
        // 清标记的责任在 HouseKeyStore（重装成功与清标记必须同一个原子动作，
        // 分开做会出现「标记清了但信封没换成」的假成功）。
        coVerify(exactly = 0) { houseKeyStore.clearFingerprintRearmPending() }
    }

    @Test
    fun `重装失败_不得清标记`() = runTest {
        // 房钥匙不在内存 / cipher 不可用 ⇒ 失败。此时清标记等于**放弃重装**：
        // 用户即便之后过了 PIN（钥匙回到内存）也不会再触发补写。
        coEvery { houseKeyStore.rearmFingerprintLock(cipher) } returns false

        assertFalse(repo.rearmFingerprintLock(cipher))
        coVerify(exactly = 0) { houseKeyStore.clearFingerprintRearmPending() }
    }

    // ===== 3) 降级 =====

    @Test
    fun `降级_还有别的门锁时_不动房间信封`() = runTest {
        // 还有 PIN 锁 ⇒ `trimRoomsIfNoLocksRemain` 会原样保留房间信封，
        // 用户的库**不受影响**，提示语也只该说「指纹解锁已关闭」。
        coEvery { houseKeyStore.hasFingerprintEnvelope() } returns true
        coEvery { houseKeyStore.roomVaultIds() } returns listOf("v1", "v2")
        coEvery { houseKeyStore.hasAnyLock() } returns true

        val report = repo.degradeFingerprintLock()

        assertTrue(report.degraded)
        assertEquals("有别的门锁在，房间信封一个都不该少", 0, report.roomsRemoved)
        assertTrue("还剩门锁 ⇒ UI 可以说「请用 PIN」", report.remainingLocks)
        coVerify(exactly = 1) { houseKeyStore.disableFingerprintLock() }
    }

    @Test
    fun `降级_最后一把门锁时_如实上报连带清掉的房间数`() = runTest {
        // ★ 硬约束 #5「绝不静默」：这把是最后一把锁 ⇒ 房间信封全成孤儿被清掉，
        // 用户会看到「库都不在快速解锁范围内」。数字必须带出来给 UI 说清楚，
        // 否则用户只会觉得"我的设置被重置了"。
        coEvery { houseKeyStore.hasFingerprintEnvelope() } returns true
        coEvery { houseKeyStore.roomVaultIds() } returns listOf("v1", "v2", "v3")
        coEvery { houseKeyStore.hasAnyLock() } returns false

        val report = repo.degradeFingerprintLock()

        assertTrue(report.degraded)
        assertEquals("连带清掉的房间信封数必须如实上报", 3, report.roomsRemoved)
        assertFalse(report.remainingLocks)
    }

    @Test
    fun `降级_没有指纹信封时_是空操作`() = runTest {
        // 用户根本没开过指纹锁（或刚关掉）⇒ 无信封可降级。
        // 此时**绝不能**调 disableFingerprintLock —— 它会走 trimRoomsIfNoLocksRemain，
        // 万一这是「只剩房间信封、门锁刚被别处删掉」的中间态，就会连带清掉房间。
        coEvery { houseKeyStore.hasFingerprintEnvelope() } returns false
        coEvery { houseKeyStore.hasAnyLock() } returns true

        val report = repo.degradeFingerprintLock()

        assertFalse(report.degraded)
        assertEquals(0, report.roomsRemoved)
        coVerify(exactly = 0) { houseKeyStore.disableFingerprintLock() }
        // 连房间枚举都不该发生（没必要读盘）。
        coVerify(exactly = 0) { houseKeyStore.roomVaultIds() }
    }

    @Test
    fun `清标记_直接透传`() = runTest {
        repo.clearRearmPending()
        coVerify(exactly = 1) { houseKeyStore.clearFingerprintRearmPending() }
    }
}
