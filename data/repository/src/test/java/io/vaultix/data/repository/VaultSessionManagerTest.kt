package io.vaultix.data.repository

import app.cash.turbine.test
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
        val manager = VaultSessionManager()
        val key = freshKey(1)

        manager.unlock("v1", key)

        assertEquals(key, manager.keyOf("v1"))
        assertTrue(manager.isUnlocked("v1"))
        assertFalse(manager.isUnlocked("v2"))
    }

    @Test
    fun unlockedIds_emitsChanges() = runTest {
        val manager = VaultSessionManager()

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
        val manager = VaultSessionManager()
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
        val manager = VaultSessionManager()
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
        val manager = VaultSessionManager()
        val oldKey = freshKey(3)
        manager.unlock("v1", oldKey)

        manager.unlock("v1", freshKey(4))

        // 旧密钥已被清零，不会残留两份材料
        assertTrue(snapshot(oldKey).first.all { it == 0.toByte() })
        assertTrue(manager.isUnlocked("v1"))
    }
}
