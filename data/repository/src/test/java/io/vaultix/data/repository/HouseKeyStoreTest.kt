package io.vaultix.data.repository

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.vaultix.crypto.PinKeyWrapper
import io.vaultix.crypto.PinUnwrapResult
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.datastore.AutoUnlockKeyStore
import io.vaultix.datastore.LocalUnlockKekStatus
import io.vaultix.datastore.LocalUnlockKeyStore
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.domain.PIN_MAX_ATTEMPTS
import java.util.Base64
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「房子钥匙」层的钥匙学门禁（快速解锁房子化批次 1 单测，定稿 2026-09-28）。
 *
 * ## 钉死的不变量（工作单批次 1 的验收项）
 * 1. **两把门锁解出同一把房钥匙**：指纹登记时生成、PIN 登记时复用；进程重启
 *    （`lock` 模拟）后两条路径都能解开**同一份**房间信封 —— 若两把锁包了不同的
 *    钥匙，这里立刻红（H1/H2 修复的前提）。
 * 2. **房间信封篡改必败**（GCM tag）：密文被改 / 信封被塞进错的 vaultId 槽位
 *    （AAD 绑定）都必须报 `Damaged`，绝不能吐出「像明文的东西」。
 * 3. **明文用完即擦**：`sealRoom` 的 payload 所有权转移（调用方数组全零）。
 * 4. **顺序约束**（定稿 §5）：内存无钥匙 + 已有门锁信封 ⇒ 开新锁被拒绝
 *    （`HouseKeyUnavailable`），而不是凭空生成第二把钥匙。
 * 5. **孤儿清理**：最后一把门锁删除 ⇒ 房间信封全部连带清掉。
 * 6. **auto 信封生命周期**（2026-09-29，Never 档对齐 Bitwarden）：
 *    写入幂等；进程重启后免认证恢复**同一把**钥匙；信封损坏 ⇒ 就地自愈删除；
 *    门锁全删 ⇒ 信封连带清掉。
 *
 * ## 测试基建说明
 * Keystore 不可入纯 JVM 测试，故 [SecureCredentialStore] 用内存 map 模拟、
 * [LocalUnlockKeyStore] / [PinKeyWrapper] 用「base64 恒等封装」假实现 ——
 * 门锁信封的**钥匙学正确性**由各自的模块测试守，这里守的是**编排不变式**：
 * 「两把锁包的是不是同一把钥匙」只取决于 HouseKeyStore 的逻辑，与封装算法无关。
 * 房间信封用**真的** [VaultixCrypto] AES-GCM（纯 JVM 可用），篡改用例才有效。
 */
class HouseKeyStoreTest {

    /** 内存版凭据仓（putString/getString/remove/keysWithPrefix 全走它）。 */
    private val store = mutableMapOf<String, String>()

    private val credentials = mockk<SecureCredentialStore>()
    private val localUnlockKeyStore = mockk<LocalUnlockKeyStore>()
    private val pinKeyWrapper = mockk<PinKeyWrapper>()

    /**
     * 「从不」档恢复锁的假实现（base64 恒等，与 [localUnlockKeyStore] 同风格）：
     * 现有 6 用例不碰 auto 信封；留着恒等实现是为了后续 auto 用例（信封生命周期）
     * 不必再动基建。Keystore 钥匙学正确性由平台守，这里同前两把门锁的处理。
     */
    private val autoUnlockKeyStore = mockk<AutoUnlockKeyStore>()

    /** 真实 AES-GCM（房间信封的 AEAD 校验必须是真的，篡改用例才不是自欺）。 */
    private val crypto = VaultixCrypto(Dispatchers.Unconfined)

    private lateinit var house: HouseKeyStore

    /** 任意 cipher（假封装实现忽略它；只作「本次认证」的标识）。 */
    private val cipher = mockk<Cipher>()

    @Before
    fun setUp() {
        every { credentials.putString(any(), any()) } answers { store[firstArg()] = secondArg() }
        every { credentials.getString(any()) } answers { store[firstArg()] }
        every { credentials.remove(any()) } answers { store.remove(firstArg<String>()); Unit }
        every { credentials.keysWithPrefix(any()) } answers {
            store.keys.filter { it.startsWith(firstArg<String>()) }
        }
        // 假 KEK 封装：base64 恒等（记录调用即可，钥匙学正确性不在本测试范围）。
        every { localUnlockKeyStore.wrap(any(), any()) } answers {
            Base64.getEncoder().encodeToString(secondArg<ByteArray>().copyOf())
        }
        every { localUnlockKeyStore.unwrap(any(), any()) } answers {
            Base64.getDecoder().decode(secondArg<String>())
        }
        // 假 PIN 封装：前缀 + base64（解封时校验 PIN 标记，模拟「换 PIN 解不开」）。
        every { pinKeyWrapper.wrap(any(), any()) } answers {
            "PINENV:${firstArg<String>()}:" +
                Base64.getEncoder().encodeToString(secondArg<ByteArray>().copyOf())
        }
        every { pinKeyWrapper.unwrap(any(), any()) } answers {
            val envelope = secondArg<String>()
            val parts = envelope.split(":", limit = 3)
            if (parts.size != 3 || parts[0] != "PINENV" || parts[1] != firstArg<String>()) {
                PinUnwrapResult.WrongPin
            } else {
                PinUnwrapResult.Opened(Base64.getDecoder().decode(parts[2]))
            }
        }
        // auto 恢复锁：base64 恒等（现有用例不碰；后续信封生命周期用例零成本接入）。
        every { autoUnlockKeyStore.encrypt(any()) } answers {
            Base64.getEncoder().encodeToString(firstArg<ByteArray>().copyOf())
        }
        every { autoUnlockKeyStore.decrypt(any()) } answers {
            runCatching { Base64.getDecoder().decode(firstArg<String>()) }.getOrNull()
        }
        every { autoUnlockKeyStore.deleteKey() } just Runs
        house = HouseKeyStore(
            credentials = credentials,
            localUnlockKeyStore = localUnlockKeyStore,
            pinKeyWrapper = pinKeyWrapper,
            crypto = crypto,
            autoUnlockKeyStore = autoUnlockKeyStore,
        )
    }

    // ===== 1) 两把门锁解出同一把房钥匙 =====

    @Test
    fun `两把门锁先后登记_重启后各自解出同一把钥匙`() = runTest {
        // 登记：先指纹（生成钥匙）再 PIN（必须复用同一把 —— 顺序约束的正向路径）。
        assertEquals(LockEnrollResult.Enrolled, house.enrollFingerprintLock(cipher))
        assertEquals(LockEnrollResult.Enrolled, house.enrollPinLock("123456"))
        val secret = "房间里的秘密凭据".toByteArray()
        house.sealRoom("vault-1", secret.copyOf())
        assertTrue(house.isUnlocked)

        // 「进程重启」：内存钥匙消失。
        house.lock()
        assertFalse(house.isUnlocked)

        // 指纹路径解回钥匙 ⇒ 能开同一份房间信封。
        assertTrue(house.completeFingerprintUnlock(cipher))
        val viaFingerprint = house.openRoom("vault-1")
        assertTrue(viaFingerprint is RoomOpen.Opened)
        assertArrayEquals(secret, (viaFingerprint as RoomOpen.Opened).payload)

        // PIN 路径也解回**同一把**（房间信封是指纹登记时封的，PIN 能解开
        // ⇒ 两把门锁包的是同一把钥匙 —— 本类最重要的不变式）。
        house.lock()
        assertEquals(PinOpen.Opened, house.openWithPin("123456"))
        val viaPin = house.openRoom("vault-1")
        assertTrue(viaPin is RoomOpen.Opened)
        assertArrayEquals(secret, (viaPin as RoomOpen.Opened).payload)
    }

    // ===== 2) 房间信封篡改必败 =====

    @Test
    fun `房间信封密文被改_开房必Damaged`() = runTest {
        house.enrollFingerprintLock(cipher)
        house.sealRoom("vault-1", "凭据".toByteArray())
        val sealed = store.getValue("house_room::vault-1")

        // 篡改密文尾部（GCM tag 区）：base64 字符集内换一个不重复的字符。
        val last = sealed.last()
        val replacement = if (last == 'A') 'B' else 'A'
        store["house_room::vault-1"] = sealed.dropLast(1) + replacement

        house.lock()
        assertTrue(house.completeFingerprintUnlock(cipher))
        // 必须报 Damaged（AEAD tag 校验失败），而不是吐出错误明文或抛异常。
        assertTrue(house.openRoom("vault-1") is RoomOpen.Damaged)
    }

    @Test
    fun `A库信封塞进B库槽位_开房必Damaged`() = runTest {
        house.enrollFingerprintLock(cipher)
        house.sealRoom("vault-a", "甲库凭据".toByteArray())
        // 把 A 的信封原样挪到 B 的槽位：AAD 绑 vaultId，B 的开房必须失败
        //（防「信封错位后静默解出别库凭据」）。
        store["house_room::vault-b"] = store.getValue("house_room::vault-a")
        store.remove("house_room::vault-a")

        assertTrue(house.openRoom("vault-b") is RoomOpen.Damaged)
    }

    // ===== 3) 明文用完即擦 =====

    @Test
    fun `sealRoom后入参数组全零`() = runTest {
        house.enrollFingerprintLock(cipher)
        val payload = ByteArray(64) { it.toByte() }
        house.sealRoom("vault-1", payload)
        // 所有权转移契约：本方法返回前明文必须就地清零。
        assertTrue(payload.all { it == 0.toByte() })
    }

    // ===== 4) 顺序约束（定稿 §5）=====

    @Test
    fun `内存无钥匙且已有门锁_开新锁被拒绝`() = runTest {
        // 只有指纹锁、从未解锁（内存无钥匙）⇒ 开 PIN 锁必须被拒，
        // 否则两把门锁会包不同的钥匙（撕裂不变式的守门人）。
        house.enrollFingerprintLock(cipher)
        house.lock()
        assertEquals(LockEnrollResult.HouseKeyUnavailable, house.enrollPinLock("123456"))
        // 反向同理：只有 PIN 锁、内存无钥匙 ⇒ 开指纹锁被拒。
        house.disableFingerprintLock()
        assertEquals(LockEnrollResult.Enrolled, house.enrollPinLock("123456"))
        house.lock()
        assertEquals(LockEnrollResult.HouseKeyUnavailable, house.enrollFingerprintLock(cipher))
    }

    // ===== 5) 孤儿房间清理 =====

    @Test
    fun `最后一把门锁删除_房间信封全清`() = runTest {
        house.enrollFingerprintLock(cipher)
        house.enrollPinLock("123456")
        house.sealRoom("vault-1", "一".toByteArray())
        house.sealRoom("vault-2", "二".toByteArray())
        assertTrue(house.hasRoom("vault-1") && house.hasRoom("vault-2"))

        // 还剩一把锁 ⇒ 房间保留。
        house.disableFingerprintLock()
        assertTrue(house.hasRoom("vault-1"))

        // 最后一把也没了 ⇒ 房间全清（否则进程一死全是永远解不开的孤儿）+ 内存钥匙锁掉。
        house.disablePinLock()
        assertFalse(house.hasRoom("vault-1"))
        assertFalse(house.hasRoom("vault-2"))
        assertTrue(house.roomVaultIds().isEmpty())
        assertFalse(house.isUnlocked)
    }

    // ===== 6) 「从不」档自动恢复信封（2026-09-29，对齐 Bitwarden autoUnlockKey）=====

    @Test
    fun `auto 信封_进程重启后免认证恢复同一把钥匙`() = runTest {
        // Never 档完整生命周期：登记（生钥匙）→ 封房间 → 写 auto 信封 →「重启」→
        // 免认证恢复 ⇒ 房间照样能开（恢复的是**同一把**钥匙，不是新造一把）。
        house.enrollFingerprintLock(cipher)
        val secret = "never 档的房间凭据".toByteArray()
        house.sealRoom("vault-1", secret.copyOf())

        assertTrue(house.enrollAutoEnvelope())
        assertTrue(house.hasAutoEnvelope())
        // 幂等：再次 enroll 不重写（Bitwarden storeUserAutoUnlockKeyIfNecessary 同款）。
        val envelopeBefore = store.getValue("house_lock_auto")
        assertTrue(house.enrollAutoEnvelope())
        assertEquals(envelopeBefore, store.getValue("house_lock_auto"))

        // 「进程重启」：内存钥匙消失，auto 信封还在盘上。
        house.lock()
        assertFalse(house.isUnlocked)
        assertTrue(house.hasAutoEnvelope())

        // 免认证恢复：解出钥匙 ⇒ 同一份房间信封可开（同一把钥匙的证明）。
        assertTrue(house.openAutoEnvelope())
        assertTrue(house.isUnlocked)
        val restored = house.openRoom("vault-1")
        assertTrue(restored is RoomOpen.Opened)
        assertArrayEquals(secret, (restored as RoomOpen.Opened).payload)
    }

    @Test
    fun `auto 信封损坏_恢复失败且就地自愈删除`() = runTest {
        house.enrollFingerprintLock(cipher)
        house.sealRoom("vault-1", "凭据".toByteArray())
        assertTrue(house.enrollAutoEnvelope())

        // 模拟信封损坏（解不出密文）⇒ 恢复必须失败，且信封就地删除 ——
        // 否则每次启动都白跑一次注定失败的解密（永不自愈的坏味道）。
        house.lock()
        store["house_lock_auto"] = "@@@不是合法的base64@@@"
        assertFalse(house.openAutoEnvelope())
        assertFalse(house.hasAutoEnvelope())
        assertFalse(house.isUnlocked)
    }

    @Test
    fun `门锁全删_auto 信封一并清掉`() = runTest {
        house.enrollFingerprintLock(cipher)
        house.sealRoom("vault-1", "凭据".toByteArray())
        assertTrue(house.enrollAutoEnvelope())

        // 快速解锁体系整体下线 ⇒ 恢复基建没有单独存活的理由（否则每次启动
        // 「解出钥匙 → 无房可开」白跑一遍）。
        house.disableFingerprintLock()
        assertFalse(house.hasAutoEnvelope())
        assertFalse(house.hasRoom("vault-1"))
    }

    // ===== 7) 失效矩阵：待重装标记（2026-09-29 批次 4，定稿 §6）=====

    @Test
    fun `待重装标记_重装成功后自动清除`() = runTest {
        house.enrollFingerprintLock(cipher)
        house.sealRoom("vault-1", "凭据".toByteArray())

        // 开门态检测到可重装失效 ⇒ 打标记（房钥匙仍在内存）。
        house.markFingerprintRearmPending()
        assertTrue(house.hasFingerprintRearmPending())
        // 幂等：重复标记不炸。
        house.markFingerprintRearmPending()
        assertTrue(house.hasFingerprintRearmPending())

        // 下一次认证拿到新 cipher ⇒ 重装：信封被新 cipher 覆盖 + 标记清除。
        assertTrue(house.rearmFingerprintLock(cipher))
        assertFalse(house.hasFingerprintRearmPending())
        // 重装后房间仍能开（重包的还是同一把房钥匙 —— 这是"重装"与"降级"的分水岭）。
        val opened = house.openRoom("vault-1")
        assertTrue(opened is RoomOpen.Opened)
    }

    @Test
    fun `房钥匙不在内存时_重装失败且不清标记`() = runTest {
        house.enrollFingerprintLock(cipher)
        house.markFingerprintRearmPending()

        // 「进程重启」式局面：钥匙没了 —— 重装无从下手（没有可包裹的房钥匙）。
        house.lock()
        assertFalse(house.rearmFingerprintLock(cipher))
        assertTrue(
            "重装失败不得清标记：钥匙回来后（用户过 PIN）仍应能重装",
            house.hasFingerprintRearmPending(),
        )
    }

    @Test
    fun `关掉指纹锁_待重装标记一并清掉`() = runTest {
        house.enrollFingerprintLock(cipher)
        house.markFingerprintRearmPending()

        // 锁都关了，「待重装」失去意义；留着会让 UI 显示一个永不兑现的承诺。
        house.disableFingerprintLock()
        assertFalse(house.hasFingerprintRearmPending())
    }

    @Test
    fun `信封还在但KEK永久失效_才算失效`() = runTest {
        // ②是失效：信封在 + KEK 被平台永久失效（用户重录指纹）—— 报「已启用」
        // 是谎报（#93），直接删信封又是「钥匙丢了顺手砸锁」（钥匙还能换新的）。
        house.enrollFingerprintLock(cipher)
        every { localUnlockKeyStore.kekStatus } returns LocalUnlockKekStatus.INVALIDATED
        assertTrue(house.isFingerprintLockInvalidated())
    }

    @Test
    fun `读不到KEK不算失效_稍后可用不是已废弃`() = runTest {
        // UNKNOWN = 设备刚启动 / Keystore 瞬时读不到 / 本次未认证。判成失效会把
        // 「稍后可用」误报成「已废弃」，正是 2026-09-12 修过的那次真实回归。
        house.enrollFingerprintLock(cipher)
        every { localUnlockKeyStore.kekStatus } returns LocalUnlockKekStatus.UNKNOWN
        assertFalse(house.isFingerprintLockInvalidated())

        // KEK 从未启用（或已被删除）也一样：那是常态，不是失效。
        every { localUnlockKeyStore.kekStatus } returns LocalUnlockKekStatus.MISSING
        assertFalse(house.isFingerprintLockInvalidated())
    }

    @Test
    fun `没开过指纹锁_谈不上失效`() = runTest {
        // 信封不在 ⇒ 这是「用户没开锁」不是「锁坏了」；KEK 因为别的原因失效
        // （例如他自己刚关掉锁）不该被报成一把废锁 —— 否则设置页会冒出一个
        // 用户根本没启用过、也就无从「重新启用」的行。
        every { localUnlockKeyStore.kekStatus } returns LocalUnlockKekStatus.INVALIDATED
        assertFalse(house.isFingerprintLockInvalidated())
    }

    // ===== 8) PIN 全局熔断（定稿 §6：全局一份 5 次）=====
    //
    // ⚠️ 断言用 [PinOpen]（仓库层）而非 `PinUnlockOutcome`（domain 层）：
    //    `HouseKeyStore.openWithPin` 的返回类型就是前者，domain 的那个是给
    //    `VaultRepositoryImpl.openHouseWithPin` 转手用 `toFailureOutcome()` 产出的。
    //    两者字段一一对应（`toFailureOutcome()` 有单测守），这里测的是**计数逻辑**。

    @Test
    fun `PIN 计数是全局一份_不随库数漂移`() = runTest {
        house.enrollPinLock("123456")

        // 连错 3 次 ⇒ 计数 3（返回值里的 remaining 由同一份计数推出）。
        repeat(3) { i ->
            assertEquals(
                "第 ${i + 1} 次错误应剩 ${PIN_MAX_ATTEMPTS - i - 1} 次",
                PinOpen.WrongPin(remainingAttempts = PIN_MAX_ATTEMPTS - i - 1),
                house.openWithPin("000000"),
            )
        }
        // 计数不因"换库"而重置：门锁是全局的，计数也是全局的。
        // ⚠️ 不断言 `isUnlocked`：那是**门锁开没开**的状态，与计数是两码事 ——
        //    `enrollPinLock` 生钥匙时已把房钥匙放进内存（首启路径无钥匙可复用 ⇒
        //    生成一把），此后连败 3 次并不会把已在内存的钥匙抽走（那是 `lock()` 的事）。
        val fourth = house.openWithPin("111111")
        assertEquals(
            "第 4 次错误应剩 1 次（全局计数），而不是各自从 5 起算",
            PIN_MAX_ATTEMPTS - 4,
            (fourth as PinOpen.WrongPin).remainingAttempts,
        )
    }

    @Test
    fun `PIN 连错 5 次熔断_主密码可进`() = runTest {
        house.enrollPinLock("123456")

        repeat(PIN_MAX_ATTEMPTS - 1) { i ->
            assertEquals(
                PinOpen.WrongPin(remainingAttempts = PIN_MAX_ATTEMPTS - i - 1),
                house.openWithPin("000000"),
            )
        }
        // 第 5 次错误当场熔断（`registerPinFailure` 越过阈值即返回 LockedOut）。
        assertEquals(PinOpen.LockedOut, house.openWithPin("000000"))
        // 即使输对也不行（熔断语义：达到上限即锁死，须主密码解锁后重设）。
        // 这一条是熔断的要害：正确的 PIN 也拿不到钥匙 —— 计数检查在 unwrap 之前。
        assertEquals(PinOpen.LockedOut, house.openWithPin("123456"))
    }

    @Test
    fun `换新 PIN_计数归零`() = runTest {
        house.enrollPinLock("123456")
        repeat(3) { house.openWithPin("000000") }

        // 改 PIN（覆盖旧信封）⇒ 计数从 0 起，用户不会被上一次的失败锁住。
        assertEquals(LockEnrollResult.Enrolled, house.enrollPinLock("654321"))
        val outcome = house.openWithPin("000000")
        assertEquals(
            "换 PIN 后应回到满额 5 次",
            PIN_MAX_ATTEMPTS - 1,
            (outcome as PinOpen.WrongPin).remainingAttempts,
        )
    }
}
