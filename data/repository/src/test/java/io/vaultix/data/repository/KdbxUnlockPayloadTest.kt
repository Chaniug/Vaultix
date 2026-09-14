/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 * ---------------------------------------------------------------------------
 */

package io.vaultix.data.repository

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KDBX 包裹物编解码（`.ai/ISSUES.md` #93 / 定稿 §4.3）。
 *
 * 重点不在「正常往返」，而在**歧义边界**：这个编解码器的存在理由就是
 * 「裸拼字节会让两组不同凭据产生同一串字节」，所以必须把这些组合逐一固化。
 */
class KdbxUnlockPayloadTest {

    @Test
    fun `roundtrips password with keyfile`() {
        val keyFile = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val decoded = KdbxUnlockPayload.decode(KdbxUnlockPayload.encode("s3cret", keyFile))

        val ok = decoded as KdbxUnlockPayload.DecodeResult.Ok
        assertEquals("s3cret", ok.masterPassword)
        assertArrayEquals(keyFile, ok.keyFileBytes)
    }

    @Test
    fun `roundtrips password without keyfile as null not empty array`() {
        val ok = KdbxUnlockPayload.decode(KdbxUnlockPayload.encode("pw", null))
            as KdbxUnlockPayload.DecodeResult.Ok

        assertEquals("pw", ok.masterPassword)
        // 「无 keyfile」与「空 keyfile」语义不同：前者不参与派生，后者参与。
        assertNull(ok.keyFileBytes)
    }

    @Test
    fun `roundtrips empty password with keyfile`() {
        val keyFile = byteArrayOf(0x7F)
        val ok = KdbxUnlockPayload.decode(KdbxUnlockPayload.encode("", keyFile))
            as KdbxUnlockPayload.DecodeResult.Ok

        assertEquals("", ok.masterPassword)
        assertArrayEquals(keyFile, ok.keyFileBytes)
    }

    /**
     * ★ 核心不变量：密码尾部字节与 keyfile 首字节相同，**不得**与
     * 「更长的密码 + 无 keyfile」混淆 —— 这正是裸拼会出错的组合
     * （`'a'+'b'` + `[0x63]` 裸拼 == `'a'+'b'+'c'`）。
     */
    @Test
    fun `does not conflate trailing password bytes with keyfile prefix`() {
        val withKeyFile = KdbxUnlockPayload.decode(
            KdbxUnlockPayload.encode("ab", byteArrayOf(0x63)),
        ) as KdbxUnlockPayload.DecodeResult.Ok
        val longerPassword = KdbxUnlockPayload.decode(
            KdbxUnlockPayload.encode("abc", null),
        ) as KdbxUnlockPayload.DecodeResult.Ok

        assertEquals("ab", withKeyFile.masterPassword)
        assertArrayEquals(byteArrayOf(0x63), withKeyFile.keyFileBytes)
        assertEquals("abc", longerPassword.masterPassword)
        assertNull(longerPassword.keyFileBytes)

        // 两组凭据的字节表示必须不同（这是长度前缀存在的全部意义）。
        assertFalse(
            "不同凭据的编码不得相同",
            KdbxUnlockPayload.encode("ab", byteArrayOf(0x63))
                .contentEquals(KdbxUnlockPayload.encode("abc", null)),
        )
    }

    @Test
    fun `roundtrips multibyte utf8 password`() {
        val password = "密码🔐abc"
        val ok = KdbxUnlockPayload.decode(KdbxUnlockPayload.encode(password, null))
            as KdbxUnlockPayload.DecodeResult.Ok

        assertEquals(password, ok.masterPassword)
    }

    /**
     * ★ keyfile 含 0x0A / 0x00 等任意字节不得被截断 —— 与项目历史上
     * 「`adb shell cat` 把 LF 转换掉」的坑同源（见用户记忆里的记录）。
     */
    @Test
    fun `roundtrips keyfile containing arbitrary bytes`() {
        val keyFile = byteArrayOf(0x0A, 0x00, 0x0D, 0x0A, 0xFF.toByte())
        val ok = KdbxUnlockPayload.decode(KdbxUnlockPayload.encode("pw", keyFile))
            as KdbxUnlockPayload.DecodeResult.Ok

        assertArrayEquals(keyFile, ok.keyFileBytes)
    }

    @Test
    fun `reports malformed on truncated input`() {
        val full = KdbxUnlockPayload.encode("password", byteArrayOf(1, 2, 3, 4))

        // 砍掉尾部若干长度：应当明确报错，而不是返回「密码对但 keyfile 短了」。
        for (cut in 1..4) {
            val truncated = full.copyOf(full.size - cut)
            val result = KdbxUnlockPayload.decode(truncated)
            assertTrue(
                "砍掉 $cut 字节应报 Malformed，实际=$result",
                result is KdbxUnlockPayload.DecodeResult.Malformed,
            )
        }
    }

    @Test
    fun `reports malformed on bad magic and short input`() {
        assertTrue(
            KdbxUnlockPayload.decode(ByteArray(0)) is KdbxUnlockPayload.DecodeResult.Malformed,
        )
        assertTrue(
            KdbxUnlockPayload.decode(byteArrayOf(1, 2, 3)) is KdbxUnlockPayload.DecodeResult.Malformed,
        )
    }

    /**
     * 声明长度与实际内容不符 ⇒ 必须报错，**不得**静默返回半截数据。
     *
     * ⚠️ 注意区分两种「长度不对」：
     * 1. **尾部多余字节**（如 `copyOf(容量)` 补的 0）是**良性**的 —— 长度前缀已经
     *    明确定义了每个段的边界，后面多出来的字节无处可读、被忽略即可。
     *    真要有攻击者篡改，GCM 认证（`unwrap`）会在到达本编解码器**之前**就失败；
     * 2. **声明长度大于实际剩余**才是真正要拦的。
     */
    @Test
    fun `reports malformed when declared length exceeds remaining bytes`() {
        val valid = KdbxUnlockPayload.encode("pw", null) // 14 字节
        // 砍掉尾部 4 字节（keyfile 长度字段）⇒ 解码时已无长度字段可读。
        val truncated = valid.copyOf(valid.size - 4)
        assertTrue(
            "缺少 keyfile 长度字段应报 Malformed",
            KdbxUnlockPayload.decode(truncated) is KdbxUnlockPayload.DecodeResult.Malformed,
        )

        // 构造「keyfile 声明 8 字节、实际只跟 2 字节」。
        // ⚠️ 必须**整个 4 字节字段**改写：只改最低字节会把 -1（0xFFFFFFFF）变成
        // 0xFFFFFF08 = -248，虽同样报错但走的是「负数」分支，测不到「长度超出剩余」
        // 这一条真正要守的路径。
        val kfLenOffset = valid.size - Int.SIZE_BYTES
        val declaredEight = ByteArray(valid.size + 2)
        valid.copyInto(declaredEight)
        declaredEight[kfLenOffset] = 0
        declaredEight[kfLenOffset + 1] = 0
        declaredEight[kfLenOffset + 2] = 0
        declaredEight[kfLenOffset + 3] = 8 // kfLen = 8，后面只有 2 字节
        val result = KdbxUnlockPayload.decode(declaredEight)
        assertTrue(
            "声明长度超出实际应报 Malformed，实际=$result",
            result is KdbxUnlockPayload.DecodeResult.Malformed,
        )
    }

    /**
     * 尾部多余字节（良性）：长度前缀已界定边界，多出来的部分忽略即可。
     *
     * 固化这一条是为了**记录判断依据**（为什么不做严格等长校验）：
     * 本编解码器的输入来自 GCM 解封，完整性由 AEAD 保证；
     * 再叠一层等长校验只会拒绝未来可能的向后兼容扩展，收益为零。
     */
    @Test
    fun `ignores benign trailing bytes because aead guarantees integrity`() {
        val valid = KdbxUnlockPayload.encode("pw", null)
        val padded = valid.copyOf(valid.size + 6) // 补零

        val ok = KdbxUnlockPayload.decode(padded) as KdbxUnlockPayload.DecodeResult.Ok
        assertEquals("pw", ok.masterPassword)
        // 关键：补零**不得**把「无 keyfile」变成「空 keyfile」
        // （二者在 KDBX 派生中语义不同）。
        assertNull(ok.keyFileBytes)
    }
}
