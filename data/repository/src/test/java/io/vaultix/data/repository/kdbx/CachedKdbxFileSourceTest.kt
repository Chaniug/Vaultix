package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远端来源的**本地缓存装饰器**（多库锁模型定稿 **批次 B1**，2026-09-30；
 * 2026-10-05 改「有缓存即返回、`stat()` 丢后台」）。
 *
 * ## 守的是什么
 *
 * B1 的价值是「把下载压成一次元数据请求」；但 **2026-10-05 真机实测证明那个前提不成立**：
 * OneDrive 的 Graph 元数据往返要 1–2.8 s，与下载 30 KB 差不多慢
 * （实测 `read=1096–2837ms` 而缓存**一直命中**）。
 * ⇒ 改成**有缓存就先用它解密**、`stat()` 丢后台（用户拍板的「折中」：
 * 允许瞬间看到旧内容，但必须让这个事实**可见**）。
 *
 * ## 最要紧的三条
 *
 * 1. **有缓存时不等待 `stat()`** —— 由 [HangingStatSource] 把这条钉死（回归即超时）；
 * 2. **后台发现远端变了 ⇒ 换新缓存 + 上报**（否则用户手里的旧内容没有任何提示）；
 * 3. **没有缓存时 `stat()` 失败 ⇒ 如实抛**（没有可回退的东西，不能静默返回空字节）。
 *
 * ⚠️ 原 B1 的「令牌相同 ⇒ 一次都不下载」不再是**同步**契约：令牌比较已挪进后台校验。
 *   现在的契约是「有缓存 ⇒ 这一次的 `read()` 不等网络」。
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
    private class FakeCache : KdbxFileCache {        private val map = mutableMapOf<String, CachedKdbxFile>()

        override suspend fun load(key: String): CachedKdbxFile? = map[key]

        override suspend fun save(key: String, file: CachedKdbxFile) {
            map[key] = file
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }

        fun peek(key: String): CachedKdbxFile? = map[key]
    }

    /**
     * `stat()` **永远挂住**的来源 —— 用来证明"有缓存时 read 根本不等 stat"。
     *
     * ⚠️ 这是最贵也最直接的一条：2026-10-05 真机实测 OneDrive 的 Graph 元数据往返要
     * 1–2.8 s，而缓存命中时**旧实现仍在同步等它**（那 95% 的 read 时间就花在那）。
     * 若哪天有人把"先返回缓存"改回"先 stat 再返回"，本类会让那条测试**超时**，
     * 而不是安静地通过 —— 这就是它存在的理由。
     */
    private class HangingStatSource(
        private val bytes: ByteArray,
        private val token: String?,
    ) : KdbxFileSource {
        var readCount = 0
            private set

        override suspend fun stat(): KdbxFileStat = kotlinx.coroutines.awaitCancellation()

        override suspend fun read(): ByteArray {
            readCount++
            return bytes
        }

        override suspend fun write(
            bytes: ByteArray,
            expectedVersion: String?,
            force: Boolean,
        ): KdbxFileWriteResult = KdbxFileWriteResult(versionToken = token)

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)
    }

    private fun source(
        bytes: ByteArray,
        token: String?,
        statFails: Boolean = false,
    ) = FakeSource(bytes, token, statFails)

    // ---- 1. 命中：先用缓存，stat 丢后台（2026-10-05 折中）----

    @Test
    fun `有缓存_直接返回缓存且当前这次不等待stat`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("cached".toByteArray(), "etag-1"))
        val delegate = source("remote".toByteArray(), token = "etag-1")

        val bytes = CachedKdbxFileSource(delegate, cache, key, backgroundScope).read()

        assertArrayEquals("cached".toByteArray(), bytes)
        assertEquals("命中时不该发生下载", 0, delegate.readCount)
    }

    @Test
    fun `有缓存时read不等待stat_这正是秒开的来源`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("cached".toByteArray(), "etag-1"))
        // stat 挂住：若 read 还在等它，本测试会超时 ⇒ 直接锁住"不等 stat"这个契约。
        val delegate = HangingStatSource("remote".toByteArray(), token = "etag-1")

        val bytes = CachedKdbxFileSource(delegate, cache, key, backgroundScope).read()

        assertArrayEquals("cached".toByteArray(), bytes)
        assertEquals("命中时不该发生下载", 0, delegate.readCount)
    }

    @Test
    fun `后台校验发现远端变了_换新缓存并置为STALE且上报`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("stale".toByteArray(), "etag-1"))
        val delegate = source("fresh".toByteArray(), token = "etag-2")
        var reported = 0
        val sut = CachedKdbxFileSource(
            delegate = delegate,
            cache = cache,
            cacheKey = key,
            scope = backgroundScope,
            onRemoteChanged = { reported++ },
        )

        // 当前这次返回的是**旧**缓存（用户已知的取舍：不阻塞解锁）。
        assertArrayEquals("stale".toByteArray(), sut.read())
        assertEquals("当前会话仍应是旧内容", "stale", String(cache.peek(key)!!.bytes))

        // 后台校验跑完后：缓存换新 + 状态上报（折中的第二半）。
        runCurrent()
        assertEquals("缓存应换新", "fresh", String(cache.peek(key)!!.bytes))
        assertEquals("应上报一次「远端变了」", 1, reported)
    }

    @Test
    fun `后台校验发现远端没变_不换缓存也不上报`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("cached".toByteArray(), "etag-1"))
        val delegate = source("remote".toByteArray(), token = "etag-1")
        var reported = 0
        val sut = CachedKdbxFileSource(delegate, cache, key, backgroundScope, onRemoteChanged = { reported++ })

        sut.read()
        runCurrent()

        assertEquals("不该换缓存", "cached", String(cache.peek(key)!!.bytes))
        assertEquals("没变就不该上报", 0, reported)
    }

    // ---- 2/3. 未命中：下载并更新缓存 ----

    @Test
    fun `没有缓存_下载并写入缓存`() = runTest {
        val cache = FakeCache()
        val delegate = source("remote".toByteArray(), token = "etag-1")

        CachedKdbxFileSource(delegate, cache, key, backgroundScope).read()

        assertEquals(1, delegate.readCount)
        assertEquals("etag-1", cache.peek(key)?.versionToken)
    }

    @Test
    fun `没有缓存时stat失败_如实抛而不是返回空`() = runTest {
        val cache = FakeCache()
        val delegate = source("remote".toByteArray(), token = "etag-1", statFails = true)

        // ⚠️ 用 `runCatching` 而不是 `assertThrows`：后者要同步 lambda，
        //    而 `read()` 是挂起的（在 `runTest` 里套 `runBlocking` 会与测试调度器打架）。
        val error = runCatching {
            CachedKdbxFileSource(delegate, cache, key, backgroundScope).read()
        }.exceptionOrNull()

        assertEquals(IllegalStateException::class.java, error?.javaClass)
        assertEquals("没有缓存时不能静默返回空字节", 0, delegate.readCount)
    }

    // ---- 5. 写后刷新缓存 ----

    @Test
    fun `写成功后缓存换成写后的令牌`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("old".toByteArray(), "etag-1"))
        val delegate = source("remote".toByteArray(), token = "etag-1")
        val source = CachedKdbxFileSource(delegate, cache, key, backgroundScope)

        source.write("new".toByteArray(), expectedVersion = "etag-1")

        assertEquals(
            "写后必须用**服务端回给的**新令牌，否则下一次读会误判成过期",
            FakeSource.WRITE_TOKEN,
            cache.peek(key)?.versionToken,
        )
    }
}
