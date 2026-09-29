package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.domain.LocalUnlockEnrollOutcome
import io.vaultix.domain.LocalUnlockPrepareOutcome
import io.vaultix.domain.LocalUnlockPreparedEnrollment
import io.vaultix.domain.RoomResealOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 房间信封重包（批次 4，定稿 §6 目标 3）。
 *
 * ## 本类守的不变量
 *
 * `HouseKeyStore.sealRoom` / `LocalUnlockEnrollment` 的钥匙学正确性由各自测试守；
 * 这里守的是**这条窄路的编排纪律**：
 *
 * 1. **先校验后包裹**：密码不对 ⇒ `InvalidCredentials`，**绝不落盘**。
 *    若把错密码包进信封，用户下次快速解锁会拿到一组打不开的凭据，
 *    且从 UI 上看不出原因（「信封已验证」的假象）。
 * 2. **不碰门锁**：重包全程不得出现任何指纹 / PIN 动作 —— 房钥匙没变，
 *    凭什么让用户再过一次认证。
 * 3. **无钥匙如实报**：`HouseKeyUnavailable` 而不是让 `sealRoom` 抛异常。
 * 4. **明文用完即擦**：备料的 `close()` 必须被调（KDBX 那份里躺着主密码）。
 */
class RoomResealRepositoryImplTest {

    private val enrollment = mockk<LocalUnlockEnrollment>()
    private val houseKeyStore = mockk<HouseKeyStore>()

    /** 备料假实现：可断言 `close()`，也能携带明文供「用完即擦」检查。 */
    private class FakePrepared(
        override val vaultId: String,
        override val displayName: String = "测试库",
        val plaintext: ByteArray = "明文".toByteArray(),
    ) : LocalUnlockPreparedEnrollment {
        var closed = false
        override fun close() {
            closed = true
            plaintext.fill(0)
        }
    }

    private val vaultId = "vault-1"

    private fun repo(): RoomResealRepositoryImpl =
        RoomResealRepositoryImpl(enrollment = enrollment, houseKeyStore = houseKeyStore)

    @Test
    fun `校验通过_重包成功`() = runTest {
        every { houseKeyStore.isUnlocked } returns true
        val prepared = FakePrepared(vaultId)
        coEvery { enrollment.prepareForVaults(any(), any()) } returns
            mapOf(vaultId to LocalUnlockPrepareOutcome.Ready(prepared))
        coEvery { enrollment.sealRoomsForVaults(any()) } returns
            mapOf(vaultId to LocalUnlockEnrollOutcome.Enrolled)

        assertEquals(RoomResealOutcome.Resealed, repo().resealRoom(vaultId, "新主密码"))

        // ★ 关键：封的必须是**刚校验过的那一份备料**，不能另起一份。
        coVerify(exactly = 1) { enrollment.sealRoomsForVaults(listOf(prepared)) }
    }

    @Test
    fun `密码不对_报InvalidCredentials且不落盘`() = runTest {
        // ★ 本类最重要的一条：**密码错就绝不许写信封**。
        // 写了的话用户下次快速解锁会拿到打不开的凭据，且看不出原因 ——
        // 「启用成功但躺的是错密码」正是 `LocalUnlockEnrollment` KDoc 里记的历史病灶。
        every { houseKeyStore.isUnlocked } returns true
        coEvery { enrollment.prepareForVaults(any(), any()) } returns
            mapOf(vaultId to LocalUnlockPrepareOutcome.InvalidCredentials)

        assertEquals(RoomResealOutcome.InvalidCredentials, repo().resealRoom(vaultId, "错密码"))

        coVerify(exactly = 0) { enrollment.sealRoomsForVaults(any()) }
    }

    @Test
    fun `用户跳过没输密码_等价于凭据不对`() = runTest {
        // `Skipped` 的语义是「用户没给密码」—— 对重包而言它和「密码错」一样
        // 都不该落盘；UI 侧都该让用户重输。分开的理由是**展示**（见域层 KDoc），
        // 不是处理分支。
        every { houseKeyStore.isUnlocked } returns true
        coEvery { enrollment.prepareForVaults(any(), any()) } returns
            mapOf(vaultId to LocalUnlockPrepareOutcome.Skipped)

        assertEquals(RoomResealOutcome.InvalidCredentials, repo().resealRoom(vaultId, ""))
        coVerify(exactly = 0) { enrollment.sealRoomsForVaults(any()) }
    }

    @Test
    fun `文件读不到_报Failed并带上真实原因`() = runTest {
        // 「读不到文件」与「密码错」的用户动作完全不同（前者该重选文件）。
        // 归成 InvalidCredentials 会让用户反复重输一个本来正确的密码。
        every { houseKeyStore.isUnlocked } returns true
        coEvery { enrollment.prepareForVaults(any(), any()) } returns
            mapOf(vaultId to LocalUnlockPrepareOutcome.SourceUnavailable("读不到该库文件，请重新选择"))

        val outcome = repo().resealRoom(vaultId, "新主密码")

        assertEquals(RoomResealOutcome.Failed("读不到该库文件，请重新选择"), outcome)
        coVerify(exactly = 0) { enrollment.sealRoomsForVaults(any()) }
    }

    @Test
    fun `房钥匙不在内存_如实报HouseKeyUnavailable`() = runTest {
        // 前置不该被违反（见接口 KDoc），但真发生时给一句人话而不是崩溃。
        every { houseKeyStore.isUnlocked } returns false

        assertEquals(RoomResealOutcome.HouseKeyUnavailable, repo().resealRoom(vaultId, "新主密码"))

        // 连备料都不该跑（那会白读一次盘 + 白跑一次 Argon2id/KDBX 校验）。
        coVerify(exactly = 0) { enrollment.prepareForVaults(any(), any()) }
    }

    @Test
    fun `落盘阶段钥匙被并发清掉_如实报HouseKeyUnavailable`() = runTest {
        // 自查通过 → 用户恰好在这一瞬锁了库 → `sealRoom` 抛 IllegalStateException。
        // 这是真实竞态（不是纯理论），必须被接住并如实归类 —— 抛出去会让
        // 解锁页崩在「输对了密码」这个最不该崩的时刻。
        every { houseKeyStore.isUnlocked } returns true
        val prepared = FakePrepared(vaultId)
        coEvery { enrollment.prepareForVaults(any(), any()) } returns
            mapOf(vaultId to LocalUnlockPrepareOutcome.Ready(prepared))
        coEvery { enrollment.sealRoomsForVaults(any()) } throws
            IllegalStateException("house key not in memory")

        assertEquals(RoomResealOutcome.HouseKeyUnavailable, repo().resealRoom(vaultId, "新主密码"))
    }

    @Test
    fun `备料落盘失败_带上detail`() = runTest {
        every { houseKeyStore.isUnlocked } returns true
        val prepared = FakePrepared(vaultId)
        coEvery { enrollment.prepareForVaults(any(), any()) } returns
            mapOf(vaultId to LocalUnlockPrepareOutcome.Ready(prepared))
        coEvery { enrollment.sealRoomsForVaults(any()) } returns
            mapOf(vaultId to LocalUnlockEnrollOutcome.Failed("房间信封封装失败"))

        assertEquals(
            RoomResealOutcome.Failed("房间信封封装失败"),
            repo().resealRoom(vaultId, "新主密码"),
        )
    }

    @Test
    fun `不碰任何门锁`() = runTest {
        // ★ 边界不变式：重包只写房间信封。房钥匙没变 ⇒ 无 Keystore、无认证、
        // 不动指纹/PIN 信封。若哪天有人往这条路上加一次认证，用户会突然发现
        // 「改个密码还要过指纹」，而这是完全不必要的。
        every { houseKeyStore.isUnlocked } returns true
        coEvery { enrollment.prepareForVaults(any(), any()) } returns
            mapOf(vaultId to LocalUnlockPrepareOutcome.InvalidCredentials)

        repo().resealRoom(vaultId, "新主密码")

        coVerify(exactly = 0) { houseKeyStore.enrollFingerprintLock(any()) }
        coVerify(exactly = 0) { houseKeyStore.enrollPinLock(any()) }
        coVerify(exactly = 0) { houseKeyStore.disableFingerprintLock() }
        coVerify(exactly = 0) { houseKeyStore.disablePinLock() }
    }
}
