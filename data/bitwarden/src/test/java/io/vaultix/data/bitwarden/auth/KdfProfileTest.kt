package io.vaultix.data.bitwarden.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * KDF 参数快照的落盘 / 回读（主密码**本地解锁**的前置条件）。
 *
 * 关键点：解析失败必须返回 `null` 而不是抛异常 —— 本地快照被破坏时应当**退回联网取参数**，
 * 而不是让解锁流程直接崩掉。
 */
class KdfProfileTest {

    @Test
    fun pbkdf2_roundTrip() {
        val profile = KdfProfile(type = 0, iterations = 600_000, memoryMb = null, parallelism = null)

        val parsed = KdfProfile.parse(profile.serialize())

        assertEquals(profile, parsed)
        assertNull(parsed?.memoryMb)
        assertNull(parsed?.parallelism)
    }

    @Test
    fun argon2id_roundTrip() {
        val profile = KdfProfile(type = 1, iterations = 3, memoryMb = 64, parallelism = 4)

        assertEquals(profile, KdfProfile.parse(profile.serialize()))
    }

    @Test
    fun malformedSnapshot_isNull() {
        assertNull(KdfProfile.parse(""))
        assertNull(KdfProfile.parse("0|600000"))
        assertNull(KdfProfile.parse("a|b|c|d"))
        assertNull(KdfProfile.parse("0|600000|-1|-1|extra"))
    }
}
