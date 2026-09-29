package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远端来源的**本地缓存装饰器**（多库锁模型定稿 **批次 B1**，2026-09-30）。
 *
 * ## 守的是什么
 *
 * 真机实测 KDBX 解锁 3.58 s 里 ≈3.4 s 花在「读文件」（库在 OneDrive、设备无副本）。
 * 本装饰器的全部价值就是**把那次下载压成一次元数据请求** —— 所以下面每条测试
 * 都盯着"到底有没有真的下载"（`readCount`），而不是只看返回值。
 *
 * ## 最要紧的两条
 *
 * 1. **令牌相同 ⇒ 一次都不下载**（否则等于白做）；
 * 2. **令牌拿不到（null）⇒ 必须下载**（拿不到判据却声称命中，就会把一个**可能过期**的
 *    库静默交给用户，进而覆盖远端 —— 宁可慢一次，不可说谎一次）。
 */
class CachedKdbxFileSourceTest {

    private val key = "webdav:cred-1:https://dav.example.com/valkjin.kdbx"

    // ---- 夹具 ----

    /** 假来源：只记录"被读了几次 / 被 stat 了几次"，并可按需抛错。 */
    private class FakeSource(
        private val bytes: ByteArray,
        private var token: String?,
        private val statFails: Boolean = false,
    ) : KdbxFileSource {

        var readCount = 0
            private set
        var statCount = 0
            private set

        override suspend fun stat(): KdbxFileStat {
            statCount++
            if (statFails) throw IllegalStateException("网络不可用")
            return KdbxFileStat(versionToken = token)
        }

        override suspend fun read(): ByteArray {
            readCount++
            return bytes
        }

        override suspend fun write(
            bytes: ByteArray,
            expectedVersion: String?,
            force: Boolean,
        ): KdbxFileWriteResult {
            token = WRITE_TOKEN
            return KdbxFileWriteResult(versionToken = WRITE_TOKEN)
        }

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        companion object {
            const val WRITE_TOKEN = "etag-after-write"
        }
    }

    /** 内存缓存（接口本来就是为"可测"而拆出来的）。 */
    private class FakeCache : KdbxFileCache {
        private val map = mutableMapOf<String, CachedKdbxFile>()

        override suspend fun load(key: String): CachedKdbxFile? = map[key]

        override suspend fun save(key: String, file: CachedKdbxFile) {
            map[key] = file
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }

        fun peek(key: String): CachedKdbxFile? = map[key]
    }

    private fun source(
        bytes: ByteArray,
        token: String?,
        statFails: Boolean = false,
    ) = FakeSource(bytes, token, statFails)

    // ---- 1. 命中：不下载 ----

    @Test
    fun `令牌相同_命中缓存_一次都不下载`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("cached".toByteArray(), "etag-1"))
        val delegate = source("remote".toByteArray(), token = "etag-1")

        val bytes = CachedKdbxFileSource(delegate, cache, key).read()

        assertArrayEquals("cached".toByteArray(), bytes)
        assertEquals("命中时不该发生下载", 0, delegate.readCount)
        assertEquals("只应发生一次元数据请求", 1, delegate.statCount)
    }

    // ---- 2/3. 未命中：下载并更新缓存 ----

    @Test
    fun `令牌变了_重新下载并更新缓存`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("stale".toByteArray(), "etag-1"))
        val delegate = source("fresh".toByteArray(), token = "etag-2")

        val bytes = CachedKdbxFileSource(delegate, cache, key).read()

        assertArrayEquals("fresh".toByteArray(), bytes)
        assertEquals(1, delegate.readCount)
        assertEquals("etag-2", cache.peek(key)?.versionToken)
    }

    @Test
    fun `没有缓存_下载并写入缓存`() = runTest {
        val cache = FakeCache()
        val delegate = source("remote".toByteArray(), token = "etag-1")

        CachedKdbxFileSource(delegate, cache, key).read()

        assertEquals(1, delegate.readCount)
        assertEquals("etag-1", cache.peek(key)?.versionToken)
    }

    @Test
    fun `来源不给令牌_老实下载不冒充命中`() = runTest {
        val cache = FakeCache()
        // ★ 关键：缓存里那份**也必须是 null 令牌**。
        //   若写成 "etag-1"，那么"漏掉 `token != null` 检查"的缺陷会让
        //   `null == "etag-1"` 为假 ⇒ 仍然下载 ⇒ **测试照样绿，缺陷抓不到**（弱测试）。
        //   只有两边都是 null 时，`null == null` 才会成立 —— 那才是要挡的误判命中。
        cache.save(key, CachedKdbxFile("cached".toByteArray(), versionToken = null))
        val delegate = source("remote".toByteArray(), token = null)

        val bytes = CachedKdbxFileSource(delegate, cache, key).read()

        assertArrayEquals("拿不到新鲜度判据就必须下载", "remote".toByteArray(), bytes)
        assertEquals(1, delegate.readCount)
    }

    // ---- 4. 离线：回退缓存 ----

    @Test
    fun `元数据失败_有缓存则回退缓存`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("cached".toByteArray(), "etag-1"))
        val delegate = source("remote".toByteArray(), token = "etag-1", statFails = true)

        val bytes = CachedKdbxFileSource(delegate, cache, key).read()

        assertArrayEquals("离线也应能解锁（顺带获得的能力）", "cached".toByteArray(), bytes)
        assertEquals(0, delegate.readCount)
    }

    @Test
    fun `元数据失败且没有缓存_如实抛而不是返回空`() = runTest {
        val cache = FakeCache()
        val delegate = source("remote".toByteArray(), token = "etag-1", statFails = true)

        // ⚠️ 用 `runCatching` 而不是 `assertThrows`：后者要同步 lambda，
        //    而 `read()` 是挂起的（在 `runTest` 里套 `runBlocking` 会与测试调度器打架）。
        val error = runCatching { CachedKdbxFileSource(delegate, cache, key).read() }.exceptionOrNull()

        assertEquals(IllegalStateException::class.java, error?.javaClass)
        assertEquals("没有缓存时不能静默返回空字节", 0, delegate.readCount)
    }

    // ---- 5. 写后刷新缓存 ----

    @Test
    fun `写成功后缓存换成写后的令牌`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("old".toByteArray(), "etag-1"))
        val delegate = source("remote".toByteArray(), token = "etag-1")
        val source = CachedKdbxFileSource(delegate, cache, key)

        source.write("new".toByteArray(), expectedVersion = "etag-1")

        assertEquals(
            "写后必须用**服务端回给的**新令牌，否则下一次读会误判成过期",
            FakeSource.WRITE_TOKEN,
            cache.peek(key)?.versionToken,
        )
    }
}
