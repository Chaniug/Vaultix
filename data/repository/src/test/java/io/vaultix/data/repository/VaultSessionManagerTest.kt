package io.vaultix.data.repository

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.vaultix.crypto.SymmetricCryptoKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话密钥生命周期（Docs/10 §4 / Docs/01 §5）：
 * - unlock 后可取密钥、解锁集合含该库；
 * - lock 幂等且**清零密钥材料**（对象不可再用）；
 * - lockAll 清空全部；覆盖 unlock 先清旧密钥。
 */
class VaultSessionManagerTest {

    private fun freshKey(seed: Byte): SymmetricCryptoKey = SymmetricCryptoKey(
        encKey = ByteArray(SymmetricCryptoKey.KEY_SIZE) { seed },
        macKey = ByteArray(SymmetricCryptoKey.KEY_SIZE) { (seed + 1).toByte() },
    )

    private fun snapshot(key: SymmetricCryptoKey): Pair<ByteArray, ByteArray> =
        key.encKey.useBytes { it.copyOf() } to key.macKey.useBytes { it.copyOf() }

    @Test
    fun unlock_then_keyAvailable_and_unlockedIdVisible() = runTest {
        val manager = VaultSessionManager(KdbxSessionFlow())
        val key = freshKey(1)

        manager.unlock("v1", key)

        assertEquals(key, manager.keyOf("v1"))
        assertTrue(manager.isUnlocked("v1"))
        assertFalse(manager.isUnlocked("v2"))
    }

    @Test
    fun unlockedIds_emitsChanges() = runTest {
        val manager = VaultSessionManager(KdbxSessionFlow())

        manager.unlockedIds.test {
            assertEquals(emptySet<String>(), awaitItem())
            manager.unlock("v1", freshKey(1))
            assertEquals(setOf("v1"), awaitItem())
            manager.unlock("v2", freshKey(2))
            assertEquals(setOf("v1", "v2"), awaitItem())
            manager.lock("v1")
            assertEquals(setOf("v2"), awaitItem())
            manager.lockAll()
            assertEquals(emptySet<String>(), awaitItem())
        }
    }

    @Test
    fun lock_zeroesKeyMaterial() = runTest {
        val manager = VaultSessionManager(KdbxSessionFlow())
        val key = freshKey(7)
        manager.unlock("v1", key)
        val (encBefore, _) = snapshot(key)
        assertTrue(encBefore.any { it != 0.toByte() })

        manager.lock("v1")

        assertNull(manager.keyOf("v1"))
        assertFalse(manager.isUnlocked("v1"))
        // 密钥材料已被清零：再读全是 0
        val (encAfter, macAfter) = snapshot(key)
        assertTrue(encAfter.all { it == 0.toByte() })
        assertTrue(macAfter.all { it == 0.toByte() })
        // 幂等：重复 lock 不崩溃、状态不变
        manager.lock("v1")
        assertFalse(manager.isUnlocked("v1"))
    }

    @Test
    fun lockAll_zeroesAllKeys() = runTest {
        val manager = VaultSessionManager(KdbxSessionFlow())
        val k1 = freshKey(1)
        val k2 = freshKey(2)
        manager.unlock("v1", k1)
        manager.unlock("v2", k2)

        manager.lockAll()

        assertNull(manager.keyOf("v1"))
        assertNull(manager.keyOf("v2"))
        assertTrue(snapshot(k1).first.all { it == 0.toByte() })
        assertTrue(snapshot(k2).first.all { it == 0.toByte() })
    }

    @Test
    fun reUnlock_clearsPreviousKeyFirst() = runTest {
        val manager = VaultSessionManager(KdbxSessionFlow())
        val oldKey = freshKey(3)
        manager.unlock("v1", oldKey)

        manager.unlock("v1", freshKey(4))

        // 旧密钥已被清零，不会残留两份材料
        assertTrue(snapshot(oldKey).first.all { it == 0.toByte() })
        assertTrue(manager.isUnlocked("v1"))
    }

    // ---------------------------------------------------------------- 查看层锁（ViewLocked）

    /** 造一个 KDBX 判据可替换的管理器（见 `KdbxSessionFlow.isUnlocked` 的 KDoc）。 */
    private fun managerWith(kdbxUnlocked: Set<String> = emptySet()): VaultSessionManager {
        val kdbx = mockk<KdbxSessionFlow>()
        every { kdbx.isUnlocked(any()) } answers { firstArg<String>() in kdbxUnlocked }
        return VaultSessionManager(kdbx)
    }

    @Test
    fun viewLock_对Bitwarden会话生效_真锁后标记被清() = runTest {
        val manager = managerWith()
        manager.unlock("bw", freshKey(9))

        manager.viewLock("bw")
        assertTrue(manager.isViewLocked("bw"))
        // 查看锁**不清密钥**（恢复只需一次生物识别）
        assertTrue(manager.isUnlocked("bw"))

        // 真锁（密钥清零）必须一并清掉标记：否则根导航会停在"已解锁但查看锁定"的分支，
        // 用户输完主密码又被弹回解锁页（.ai/ISSUES.md #60 第 2 步）。
        manager.lock("bw")
        assertFalse(manager.isViewLocked("bw"))
    }

    @Test
    fun viewLock_对KDBX会话同样生效() = runTest {
        // ★ 2026-09-30 回归门禁：KDBX 的会话是"内存整库明文"，**没有 SymmetricCryptoKey**
        //   ⇒ 它从不登记进 [VaultSessionManager] 的密钥表。此前判据只看那张表，
        //   于是 `viewLock(kdbxId)` 恒为 no-op ⇒ 条目页右上角「锁定」**点了没反应**
        //   （用户真机反馈：「kdbx 好像在密码条目页面，右上角点击锁定，无法锁定」）。
        val manager = managerWith(kdbxUnlocked = setOf("kdbx"))
        manager.viewLock("kdbx")
        assertTrue(manager.isViewLocked("kdbx"))
    }

    @Test
    fun viewLock_两种会话都没有时是noOp() = runTest {
        val manager = managerWith()
        manager.viewLock("ghost")
        // 没有会话就没有可保护的东西；标记一律设上会让真锁的库被当成查看锁
        // （先白按一次生物识别、再被弹回解锁页 —— 比不设更坏）。
        assertFalse(manager.isViewLocked("ghost"))
    }
}
