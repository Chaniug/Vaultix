package io.vaultix.data.repository.kdbx

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FileKdbxFileCache] 的落盘纪律（批次 B1，2026-09-30）。
 *
 * ⚠️ 最要紧的一条是**半截文件**：被 Honor 杀进程是这台设备的常态（实测单轮 4 连杀），
 * 缓存写一半完全可能。没有尺寸校验的话，下次读到的就是一段截断字节，
 * KDBX 会报「文件损坏 / 密码错」——用户无从判断，而真因只是缓存没写完。
 */
class FileKdbxFileCacheTest {

    private val root = Files.createTempDirectory("kdbx-cache-test").toFile()

    private val key = "webdav:cred-1:https://dav.example.com/valkjin.kdbx"

    /** 覆盖两个方向：有令牌 / 无令牌（后者表示该来源不提供新鲜度判据）。 */
    @Test
    fun `存取往返_字节与令牌都还原`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), "etag-1"))

        val loaded = cache.load(key)

        assertArrayEquals("payload".toByteArray(), loaded?.bytes)
        assertEquals("etag-1", loaded?.versionToken)
    }

    @Test
    fun `令牌为null时往返仍是null`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), null))

        val loaded = cache.load(key)

        assertArrayEquals("payload".toByteArray(), loaded?.bytes)
        assertNull(loaded?.versionToken)
    }

    @Test
    fun `字节数对不上_当作未命中并清掉`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), "etag-1"))
        // 模拟"写了一半被杀"：把数据文件截短，侧车的尺寸仍是完整值。
        root.listFiles()!!.first { it.name.endsWith(".kdbx") }.writeBytes("pay".toByteArray())

        assertNull("截断的缓存必须当作没有（宁慢一次，不说谎一次）", cache.load(key))
        assertNull("不可信的缓存应被清掉，避免每次白读一遍", cache.load(key))
    }

    @Test
    fun `删除后读不到`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), "etag-1"))

        cache.remove(key)

        assertNull(cache.load(key))
    }
}
