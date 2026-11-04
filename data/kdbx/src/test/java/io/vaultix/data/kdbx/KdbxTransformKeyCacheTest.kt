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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        KdbxTransformKeyCache.attachDisk(null)
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

    // ------------------------------------------------------------ #247 第六批：冷启动 / 并发

    @Test
    fun `冷启动后落盘命中_不再执行KDF`() {
        val disk = FakeDiskCache()
        KdbxTransformKeyCache.attachDisk(disk)
        var computed = 0
        val compute = { computed++; byteArrayOf(3, 4) }

        // 第一次（上一次进程）真跑一遍并把结果写盘。
        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        assertTrue("结果必须落盘", disk.store.isNotEmpty())
        // 进程死亡：内存 LRU 没了，但盘上的密文还在（clear 默认不删盘）。
        KdbxTransformKeyCache.clear()
        val hitsBefore = KdbxTransformKeyCache.hits()

        val restored = KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)

        assertEquals("冷启动不该再付 30 秒 Argon2", 1, computed)
        assertArrayEquals(byteArrayOf(3, 4), restored)
        assertEquals(1L, KdbxTransformKeyCache.hits() - hitsBefore)
    }

    @Test
    fun `全量锁定只清内存_不删落盘条目`() {
        val disk = FakeDiskCache()
        KdbxTransformKeyCache.attachDisk(disk)
        var computed = 0
        val compute = { computed++; byteArrayOf(computed.toByte()) }

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        KdbxTransformKeyCache.clear() // Kdbx.lockAll()（超时全锁）走这一条

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        assertEquals("超时全锁后重新解锁必须仍能秒开", 1, computed)
        assertEquals(0, disk.cleared)
    }

    @Test
    fun `彻底忘记_连落盘条目一起删`() {
        val disk = FakeDiskCache()
        KdbxTransformKeyCache.attachDisk(disk)
        var computed = 0
        val compute = { computed++; byteArrayOf(computed.toByte()) }

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        KdbxTransformKeyCache.clear(includeDisk = true) // Kdbx.forgetDerivedKeys() 走这一条
        assertTrue("盘上条目必须当场清空", disk.store.isEmpty())

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        assertEquals("清空后必须真的重算 KDF", 2, computed)
        assertEquals(1, disk.cleared)
    }

    @Test
    fun `落盘条目解不开_老实重算KDF并覆盖`() {
        val disk = FakeDiskCache()
        KdbxTransformKeyCache.attachDisk(disk)
        var computed = 0
        val compute = { computed++; byteArrayOf(computed.toByte()) }

        KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        KdbxTransformKeyCache.clear()
        disk.broken = true // 模拟 Keystore 密钥丢失 / 密文被改坏

        val again = KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
        assertEquals("坏条目绝不能被当成 transformedKey", 2, computed)
        assertArrayEquals(byteArrayOf(2), again)
    }

    @Test
    fun `同一key并发_只跑一次KDF_另一条复用结果`() {
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        val gate = CountDownLatch(1)
        val computed = AtomicInteger(0)
        val compute = {
            computed.incrementAndGet()
            // 模拟真机 30 秒 Argon2：让两条线程有足够时间同时撞进 miss 分支。
            Thread.sleep(COMPUTE_DELAY_MS)
            byteArrayOf(5, 6)
        }
        val futures = (0 until CONCURRENCY).map {
            pool.submit(Callable<ByteArray> {
                gate.await()
                KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
            })
        }
        gate.countDown()
        val results = futures.map { it.get(TEST_TIMEOUT_S, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals("同一 key 只允许一条线程真跑 KDF", 1, computed.get())
        results.forEach { assertArrayEquals(byteArrayOf(5, 6), it) }
    }

    @Test
    fun `并发算KDF失败_等待者拿到异常而不是永久挂住`() {
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        val gate = CountDownLatch(1)
        val compute = {
            Thread.sleep(COMPUTE_DELAY_MS)
            throw IllegalStateException("kdf boom")
        }
        val futures = (0 until CONCURRENCY).map {
            pool.submit(Callable<ByteArray> {
                gate.await()
                KdbxTransformKeyCache.getOrCompute(fileDigest, credentialDigest, compute)
            })
        }
        gate.countDown()
        val failures = futures.map { future ->
            runCatching { future.get(TEST_TIMEOUT_S, TimeUnit.SECONDS) }.exceptionOrNull()
        }
        pool.shutdownNow()

        assertTrue(
            "两条都必须失败（且不能超时挂死）：$failures",
            failures.all { it != null },
        )
    }

    /**
     * 纯内存的假落盘缓存：单测不碰 Android Keystore / `android.util.Base64`
     * （那些在 JVM 上是 "Method not mocked"）。
     */
    private class FakeDiskCache : DerivedKeyDiskCache {
        val store = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        @Volatile
        var cleared = 0

        @Volatile
        var broken = false

        override fun load(key: String): ByteArray? =
            if (broken) byteArrayOf() else store[key]?.copyOf()

        override fun save(key: String, derivedKey: ByteArray) {
            store[key] = derivedKey.copyOf()
        }

        override fun clear() {
            cleared++
            store.clear()
        }
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val CONCURRENCY = 2
        const val COMPUTE_DELAY_MS = 150L
        const val TEST_TIMEOUT_S = 10L
    }
}
