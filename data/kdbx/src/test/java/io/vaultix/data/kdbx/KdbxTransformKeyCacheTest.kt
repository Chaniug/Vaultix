/*
 * Vaultix — data:kdbx (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */
package io.vaultix.data.kdbx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 派生密钥缓存的门禁（#246 第五批）。
 *
 * ## 它守的是「缓存只能在**确实同一版文件 + 同一组凭据**时命中」
 *
 * 答错的两个方向代价完全不对称：
 *
 * | 答错 | 后果 |
 * |---|---|
 * | 该命中却没命中 | 多跑一次 Argon2 —— 用户多等几百毫秒，数据安全 |
 * | 不该命中却命中 | 拿旧密钥解新文件 ⇒ **「密码明明对却说密码错」**，甚至写回时把库写坏 |
 *
 * ⇒ 内容 / 凭据 / 开关 / 清空，四条「必须重算」的路径都要有用例顶住。
 */
class KdbxTransformKeyCacheTest {

    private val fileDigest = byteArrayOf(1, 2, 3, 4)
    private val credentialDigest = byteArrayOf(9, 8, 7, 6)

    @Before
    fun setUp() {
        KdbxTransformKeyCache.clear()
        KdbxTransformKeyCache.enabled = true
    }

    @Test
    fun `同一份字节与同一凭据_第二次不再执行KDF`() {
        var computed = 0
        val compute = { computed++; byteArrayOf(42, 43) }

        val first = KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        val hitsBefore = KdbxTransformKeyCache.hits()
        val second = KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)

        assertEquals("只该真的派生一次", 1, computed)
        assertArrayEquals(first, second)
        assertEquals("第二次必须记成命中", 1L, KdbxTransformKeyCache.hits() - hitsBefore)
    }

    @Test
    fun `文件内容变了_必须重新派生`() {
        var computed = 0
        val compute = { computed++; byteArrayOf(computed.toByte()) }

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        val other = KdbxTransformKeyCache.getOrCompute(byteArrayOf(1, 2, 3, 5), credentialDigest, compute)

        assertEquals(2, computed)
        assertArrayEquals(byteArrayOf(2), other)
    }

    @Test
    fun `凭据变了_必须重新派生`() {
        var computed = 0
        val compute = { computed++; byteArrayOf(computed.toByte()) }

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        // 输错密码 / 换了 keyfile ⇒ compositeKey 不同，绝不能复用上一把派生密钥。
        val other = KdbxTransformKeyCache.getOrCompute(fileDigest, byteArrayOf(9, 8, 7, 5), compute)

        assertEquals(2, computed)
        assertArrayEquals(byteArrayOf(2), other)
    }

    @Test
    fun `开关关闭_每次都真的执行KDF`() {
        KdbxTransformKeyCache.enabled = false
        var computed = 0
        val compute = { computed++; byteArrayOf(1) }

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)

        assertEquals("关闭后不得命中缓存", 2, computed)
    }

    @Test
    fun `clear之后_重新派生`() {
        var computed = 0
        val compute = { computed++; byteArrayOf(1) }

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        KdbxTransformKeyCache.clear()
        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)

        assertEquals(2, computed)
    }

    @Test
    fun `命中返回的是副本_调用方改动不会污染缓存`() {
        val compute = { byteArrayOf(7, 7) }

        val first = KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        first[0] = 99
        val second = KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)

        assertArrayEquals(byteArrayOf(7, 7), second)
    }

    @Test
    fun `真实KDBX连续解锁两次_第二次命中派生密钥缓存`() {
        // 这条守的是「provider 真的被接到 decode 上了」——纯逻辑用例全绿、
        // 但 decode 没传 kdfProvider 的话，上面 6 条形同虚设。
        val created = KdbxCreator.createEmpty(name = "缓存验证", password = PASSWORD)
        val hitsBefore = KdbxTransformKeyCache.hits()

        val first = KdbxOpener.open(created.bytes, PASSWORD, keyFileBytes = null)
        val second = KdbxOpener.open(created.bytes, PASSWORD, keyFileBytes = null)

        assertTrue("第一次必须能解开：${first.exceptionOrNull()}", first.isSuccess)
        assertTrue("第二次必须能解开：${second.exceptionOrNull()}", second.isSuccess)
        assertTrue(
            "第二次应当跳过 KDF（命中数没涨说明 provider 没接上）",
            KdbxTransformKeyCache.hits() - hitsBefore >= 1,
        )
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
