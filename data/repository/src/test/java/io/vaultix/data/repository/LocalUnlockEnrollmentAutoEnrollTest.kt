package io.vaultix.data.repository

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.data.repository.kdbx.KdbxFileSourceResolver
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.model.VaultKind
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新增库「自动纳入快速解锁」（#222 P0）。
 *
 * ## 修的是什么
 *
 * 此前 `addBitwardenVault` 只做「登录 + 写库行」，从不建**该库的房间信封** ——
 * 而房间信封正是「库在快速解锁范围内」的**真源**。于是新库永远无法快解，
 * 用户只能关掉开关再打开（那会拿当前全部库重算一次名单）来补齐。
 *
 * ## 本类守的不变量
 *
 * 1. **三道守卫各自生效**：房钥匙不在内存 / 一把门锁都没装 / 范围为**空集**，
 *    任一成立 ⇒ 一个动作都不做（连库都不查）。
 * 2. **能封就一定封**：会话密钥在 + 房钥匙在 + 门锁在 ⇒ 建信封**并同步范围镜像**，
 *    且镜像必须是**并集**（不能把老库挤出去）。
 * 3. **全程不碰门锁**：这是本次修复的意义所在 —— 新库不该让用户再按一次指纹。
 * 4. **失败如实**：会话里没密钥 / 封装抛异常 ⇒ 返回 `false`，不留假成功。
 * 5. **不猜 KDBX**：自动路径没有密码可问 ⇒ KDBX 一律如实跳过，绝不假装封好。
 */
class LocalUnlockEnrollmentAutoEnrollTest {

    private val sessions = mockk<VaultSessionManager>()
    private val house = mockk<HouseKeyStore>()
    private val vaultDao = mockk<VaultDao>()
    private val preferences = mockk<VaultixPreferences>()

    /**
     * fun interface 且**带默认方法**（`readBytes`）⇒ 按项目纪律不用 mockk，
     * 直接给一个返回 null 的 lambda（Bitwarden 路径根本不会调它）。
     */
    private val sources = KdbxFileSourceResolver { null }

    private val vaultId = "https://vault.example.com"
    private val existingScope = setOf("https://other.example.com")

    private fun enrollment() = LocalUnlockEnrollment(
        sessions = sessions,
        houseKeyStore = house,
        vaultDao = vaultDao,
        preferences = preferences,
        kdbxFileSources = sources,
    )

    /**
     * 让全部门禁通过，进入「真去封信封」的分支。
     *
     * `hasRoom = false` 是默认前提（"还没封过"）—— 已有信封时本方法会短路，
     * 那条单独有用例守。
     */
    private fun givenGuardPassed() {
        every { house.isUnlocked } returns true
        coEvery { house.hasAnyLock() } returns true
        coEvery { house.hasRoom(any()) } returns false
        every { preferences.quickUnlockScope() } returns flowOf(existingScope)
        every { preferences.isQuickUnlockScopeConfirmed() } returns flowOf(false)
    }

    private fun bitwardenRow() = VaultEntity(
        id = vaultId,
        kind = VaultKind.BITWARDEN.name,
        displayName = "新库",
        origin = vaultId,
        account = "alice@example.com",
        createdAt = 0L,
    )

    private fun freshKey() = SymmetricCryptoKey(
        ByteArray(32) { 0x11 },
        ByteArray(32) { 0x22 },
    )

    @Test
    fun `房钥匙不在内存_直接放弃且连库都不查`() = runTest {
        // 冷启动后还没解锁任何库就新增库 —— 最常见的"做不了"场景。
        every { house.isUnlocked } returns false

        assertFalse(enrollment().autoEnrollNewVault(vaultId))

        // 连 `hasAnyLock`（一次 Keystore 往返）都不该跑，更不该读库。
        coVerify(exactly = 0) { house.hasAnyLock() }
        coVerify(exactly = 0) { vaultDao.get(any()) }
    }

    @Test
    fun `一把门锁都没装_不替用户擅自启用`() = runTest {
        // 快速解锁从未启用时，新增库不该把它"顺手开起来"。
        //
        // ⚠️ `hasRoom = false` 是**必须**的前置（#246：没有信封才需要往下判）——
        //   少了它本方法会在第一步就短路返回 true，这条用例会静默变成"假通过"，
        //   而它的意义正是"一把锁都没有时绝不替用户开启"。
        every { house.isUnlocked } returns true
        coEvery { house.hasRoom(any()) } returns false
        coEvery { house.hasAnyLock() } returns false

        assertFalse(enrollment().autoEnrollNewVault(vaultId))

        coVerify(exactly = 0) { vaultDao.get(any()) }
    }

    @Test
    fun `范围为空集且已确认_尊重用户一个都不要`() = runTest {
        // ★ 空集有**两**种含义，这一条守的是「已确认」的那一种：
        //   用户手动取消全部勾选后，不该被一次新增库悄悄改回来。
        //   （未确认的空集 = 默认全部库，见下面那一条 —— 两者必须分开守。）
        every { house.isUnlocked } returns true
        coEvery { house.hasAnyLock() } returns true
        coEvery { house.hasRoom(any()) } returns false
        every { preferences.quickUnlockScope() } returns flowOf(emptySet())
        every { preferences.isQuickUnlockScopeConfirmed() } returns flowOf(true)

        assertFalse(enrollment().autoEnrollNewVault(vaultId))

        coVerify(exactly = 0) { vaultDao.get(any()) }
        coVerify(exactly = 0) { preferences.setQuickUnlockScope(any()) }
    }

    @Test
    fun `范围为空集但从未确认过_默认全部库_该封`() = runTest {
        // ★ #243 的第二道死锁：只看「范围是不是空集」会把**只有这一个库**的用户
        //   永久挡在门外 —— 他没有别的库可纳入，范围就永远是空集。
        //   语义真源：未确认 ⇒ 向导默认全勾 ⇒ "全部库都在范围内"（该封）；
        //   已确认 + 空集 ⇒ "一个都不要"（不封，见上一条）。
        every { house.isUnlocked } returns true
        coEvery { house.hasAnyLock() } returns true
        coEvery { house.hasRoom(any()) } returns false
        every { preferences.quickUnlockScope() } returns flowOf(emptySet())
        every { preferences.isQuickUnlockScopeConfirmed() } returns flowOf(false)
        coEvery { vaultDao.get(vaultId) } returns bitwardenRow()
        coEvery { sessions.keyOf(vaultId) } returns freshKey()
        coEvery { house.sealRoom(any(), any()) } just Runs
        coEvery { preferences.setQuickUnlockScope(any()) } just Runs

        assertTrue(enrollment().autoEnrollNewVault(vaultId))

        coVerify(exactly = 1) { house.sealRoom(vaultId, any()) }
        coVerify(exactly = 1) { preferences.setQuickUnlockScope(setOf(vaultId)) }
    }

    @Test
    fun `已经有房间信封_直接短路_不再烧一次KDF`() = runTest {
        // ★ #243 的必需项（不是优化）：本方法会在**每次主密码解锁成功后**被调用，
        //   而 KDBX 备料要先跑一次完整 KDF（真机 64 MiB / 46 轮）。没有短路的话，
        //   用户每输一次主密码都要再烧一遍同样量级的 Argon2 —— 解锁时间凭空翻倍。
        every { house.isUnlocked } returns true
        coEvery { house.hasAnyLock() } returns true
        coEvery { house.hasRoom(vaultId) } returns true

        assertTrue(enrollment().autoEnrollUnlocked(vaultId))

        // 连库都不读，更不备料 / 不重封。
        coVerify(exactly = 0) { vaultDao.get(any()) }
        coVerify(exactly = 0) { house.sealRoom(any(), any()) }
        coVerify(exactly = 0) { preferences.setQuickUnlockScope(any()) }
    }

    /**
     * ★ **主密码解锁成功后那条路径（`enrollJustAuthenticated` 默认 force=false）
     * 也必须有这条短路**（#246，2026-11-04）。
     *
     * 这条缺陷的形状：`enrollJustAuthenticated` 曾把 `force` 写死成 `true`，
     * 而它唯一的"成功路径"调用点是 `unlockKdbxInternal` —— 一次主密码解锁**必然**
     * 走一遍 `prepareKdbx` → `Kdbx.verify` → 完整 Argon2，才能发现"信封本来就在"。
     * 用户原话：「我输入了密码，依然很慢很慢」。
     *
     * ⚠️ 上面那条 `autoEnrollUnlocked` 的短路用例**挡不住**这个回归：两个方法名不同，
     * 把 `force=true` 写回去时它照样绿。必须单独钉住 `enrollJustAuthenticated`。
     *
     * ⚠️ 变异验证：给 `enrollJustAuthenticated` 的 `force` 改回写死 `true`，
     * 本用例必须在 `vaultDao.get` 那次断言上报红（说明它真的去备料了）。
     */
    @Test
    fun `刚认证过_信封已在_不得再备料烧一遍KDF`() = runTest {
        every { house.isUnlocked } returns true
        coEvery { house.hasAnyLock() } returns true
        coEvery { house.hasRoom(vaultId) } returns true

        assertTrue(enrollment().enrollJustAuthenticated(vaultId) { "master" })

        coVerify(exactly = 0) { vaultDao.get(any()) }
        coVerify(exactly = 0) { house.sealRoom(any(), any()) }
    }

    /**
     * 反面：`force = true` 是**补建**路径（`LocalUnlockFanout` 只在开门失败时叫它），
     * 信封"存在"不代表"能用"，必须真的重封一次。
     *
     * ⚠️ 与上一条成对：少了它，"把 force 一律改成 false"会让 `Damaged` 信封永远修不好，
     * 而所有用例仍然绿。
     */
    @Test
    fun `force为true_信封坏了也要重封_这是自愈路径`() = runTest {
        givenGuardPassed()
        coEvery { house.hasRoom(vaultId) } returns true
        coEvery { vaultDao.get(vaultId) } returns bitwardenRow()
        coEvery { sessions.keyOf(vaultId) } returns freshKey()
        coEvery { house.sealRoom(vaultId, any()) } just Runs
        coEvery { preferences.setQuickUnlockScope(any()) } just Runs

        assertTrue(enrollment().enrollJustAuthenticated(vaultId, force = true) { "master" })

        coVerify(exactly = 1) { house.sealRoom(vaultId, any()) }
    }

    @Test
    fun `密钥在_自动建信封并同步范围镜像_且不碰任何门锁`() = runTest {
        // ★ 本类最重要的一条：这就是用户要的"自动关联"。
        givenGuardPassed()
        coEvery { vaultDao.get(vaultId) } returns bitwardenRow()
        coEvery { sessions.keyOf(vaultId) } returns freshKey()
        coEvery { house.sealRoom(vaultId, any()) } just Runs
        coEvery { preferences.setQuickUnlockScope(any()) } just Runs

        assertTrue(enrollment().autoEnrollNewVault(vaultId))

        coVerify(exactly = 1) { house.sealRoom(vaultId, any()) }
        // 范围镜像必须并集而不是覆盖：老库不能被这次新增挤掉。
        coVerify(exactly = 1) { preferences.setQuickUnlockScope(existingScope + vaultId) }
        // ★ 核心承诺：全程零认证 —— 没有指纹、没有 PIN、没有门锁动作。
        coVerify(exactly = 0) { house.enrollFingerprintLock(any()) }
        coVerify(exactly = 0) { house.enrollPinLock(any()) }
        coVerify(exactly = 0) { house.prepareFingerprintUnlock() }
        coVerify(exactly = 0) { house.openWithPin(any()) }
    }

    @Test
    fun `会话里没有密钥_如实失败且不落盘`() = runTest {
        // 理论上不该发生（调用点刚登录成功），但真发生时不许糊一个空信封。
        givenGuardPassed()
        coEvery { vaultDao.get(vaultId) } returns bitwardenRow()
        coEvery { sessions.keyOf(vaultId) } returns null

        assertFalse(enrollment().autoEnrollNewVault(vaultId))

        coVerify(exactly = 0) { house.sealRoom(any(), any()) }
        coVerify(exactly = 0) { preferences.setQuickUnlockScope(any()) }
    }

    @Test
    fun `封装抛异常_吞成失败而不是让添加库崩掉`() = runTest {
        // 竞态：自查通过后房钥匙被并发清掉 ⇒ `sealRoom` 抛 IllegalStateException。
        // 这条路径挂在"添加库成功"之后，抛出去会让一次成功的添加变成崩溃。
        givenGuardPassed()
        coEvery { vaultDao.get(vaultId) } returns bitwardenRow()
        coEvery { sessions.keyOf(vaultId) } returns freshKey()
        coEvery { house.sealRoom(any(), any()) } throws
            IllegalStateException("房钥匙被并发清掉")

        assertFalse(enrollment().autoEnrollNewVault(vaultId))
    }

    @Test
    fun `KDBX库_没有可用主密码时不猜也不封`() = runTest {
        // 默认参数 `masterPasswordOf = { null }` 的含义：调用方**没给**密码。
        // 此时必须如实跳过 —— 不能拿空口令去试，更不能假装封好
        // （否则信封里躺的是一组解不开的凭据）。
        givenGuardPassed()
        coEvery { vaultDao.get(vaultId) } returns kdbxRow()

        assertFalse(enrollment().autoEnrollNewVault(vaultId))

        coVerify(exactly = 0) { house.sealRoom(any(), any()) }
    }

    @Test
    fun `KDBX库_给了主密码就封房间_这是241的核心用例`() = runTest {
        // ★ #241：KDBX 的两条添加路径过去从不传密码（旧签名写死 `{ _ -> null }`），
        //   于是新加的 KDBX 库永远进不了 scope ⇒ 每次解锁都要输主密码。
        //   现在把「本次添加用的主密码」交进来，走与 Bitwarden 同一套房间机制。
        givenGuardPassed()
        coEvery { vaultDao.get(vaultId) } returns kdbxRow()
        // ⚠️ 不要 `coEvery { sources.fileSourceFor(any()) } returns null`：
        //    `sources` 是 `fun interface` 的 lambda 实现（第 51 行），不是 mockk ——
        //    MockK 在这里只会报 "Failed matching mocking signature"。它本来就返回 null。

        // 文件来源不可用 ⇒ prepareKdbx 如实报 SourceUnavailable ⇒ 不封。
        // 这一条同时钉住"没有来源时绝不降级成仅主密码"的老纪律。
        assertFalse(enrollment().autoEnrollNewVault(vaultId) { "master-pw" })

        coVerify(exactly = 0) { house.sealRoom(any(), any()) }
    }

    // ---- #243：门锁刚开之后补建（autoEnrollUnlocked / enrollJustAuthenticated） ----

    @Test
    fun `autoEnrollUnlocked_不要求房钥匙_只留门锁与范围两道守卫`() = runTest {
        // ★ #243 的核心：旧 `autoEnrollNewVault` 见 `isUnlocked == false` 就静默返回，
        //   而冷启动主密码解锁那一刻房钥匙**必然**不在内存 ⇒ 库永远进不了范围。
        //   `autoEnrollUnlocked` 把这条守卫交还调用方（只有它知道刚有没有解开门锁）。
        every { house.isUnlocked } returns false
        coEvery { house.hasAnyLock() } returns true
        coEvery { house.hasRoom(any()) } returns false
        every { preferences.quickUnlockScope() } returns flowOf(existingScope)
        every { preferences.isQuickUnlockScopeConfirmed() } returns flowOf(false)
        coEvery { vaultDao.get(vaultId) } returns bitwardenRow()
        coEvery { sessions.keyOf(vaultId) } returns freshKey()
        coEvery { house.sealRoom(any(), any()) } just Runs
        coEvery { preferences.setQuickUnlockScope(any()) } just Runs

        assertTrue(enrollment().autoEnrollUnlocked(vaultId))

        // 镜像必须是**并集**（不能把老库挤出去）。
        coVerify(exactly = 1) { preferences.setQuickUnlockScope(existingScope + vaultId) }
    }

    @Test
    fun `enrollJustAuthenticated_房钥匙不在内存就不封`() = runTest {
        // 反向门禁：这道入口是「门锁**应该**刚开」的调用点用的，
        // 房钥匙真不在时**不能**去封 —— 那会落一个谁也解不开的孤儿信封。
        every { house.isUnlocked } returns false

        assertFalse(enrollment().enrollJustAuthenticated(vaultId) { null })

        coVerify(exactly = 0) { house.sealRoom(any(), any()) }
    }

    @Test
    fun `removeRoom_删信封并从范围镜像剔除`() = runTest {
        // 2026-11-04 从 `VaultRepositoryImpl` 下沉过来（那里函数数顶在 detekt 上限）。
        // 两件事必须**同时**发生：真源（房间信封）与镜像（范围键）不能只剩一边。
        every { preferences.quickUnlockScope() } returns flowOf(existingScope + vaultId)
        coEvery { house.removeRoom(vaultId) } just Runs
        coEvery { preferences.setQuickUnlockScope(any()) } just Runs

        enrollment().removeRoom(vaultId)

        coVerify(exactly = 1) { house.removeRoom(vaultId) }
        coVerify(exactly = 1) { preferences.setQuickUnlockScope(existingScope) }
    }

    @Test
    fun `removeRoom_不在范围里就不写镜像`() = runTest {
        // 幂等：重复删 / 删一个本来没纳入的库，不该产生一次无意义的写入。
        every { preferences.quickUnlockScope() } returns flowOf(existingScope)
        coEvery { house.removeRoom(vaultId) } just Runs

        enrollment().removeRoom(vaultId)

        coVerify(exactly = 1) { house.removeRoom(vaultId) }
        coVerify(exactly = 0) { preferences.setQuickUnlockScope(any()) }
    }

    private fun kdbxRow() = VaultEntity(
        id = vaultId,
        kind = VaultKind.KDBX.name,
        displayName = "本地库",
        origin = vaultId,
        createdAt = 0L,
    )
}
