package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * 3. **没有缓存时 `stat()` 失败 ⇒ 如实抛**（没有可回退的东西，不能静默返回空字节）；
 * 4. ★ **`readRemoteCurrent()` 必须绕过缓存**（2026-11-03 修）：`read()` 允许由缓存
 *    答复，而"远端现在是什么"必须问远端 —— 否则对账/拉取都会拿到旧字节，
 *    表现为"提示已同步/已下载，条目还是旧的"（见下方第 4 组用例的说明）。
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

        /** 调用**顺序**（`"stat"` / `"read"`）—— 顺序本身是有契约的，见 `readRemoteCurrent` 的用例。 */
        val calls = mutableListOf<String>()

        override suspend fun stat(): KdbxFileStat {
            calls += "stat"
            statCount++
            if (statFails) throw IllegalStateException("网络不可用")
            return KdbxFileStat(versionToken = token)
        }

        override suspend fun read(): ByteArray {
            calls += "read"
            readCount++
            return bytes
        }

        override suspend fun write(
            bytes: ByteArray,
            expectedVersion: String?,
            force: Boolean,
            failIfExists: Boolean,
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
            failIfExists: Boolean,
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

    // ---- 4. 远端当前字节：必须绕过缓存（2026-11-03 修）----
    //
    // ## 这组用例防的是什么（真机表现：点同步提示"已下载/已同步"，条目还是旧的）
    //
    // 父接口给 `readRemoteCurrent()` 的默认实现是 `read()`，而本类的 `read()`
    // **有缓存就 return 缓存**。于是"远端现在是什么"这个问题被缓存答了：
    //  - 内容对账拿旧字节跟旧会话比 ⇒ 必然相同 ⇒ 判「已同步」并把基线推进到新令牌
    //    ⇒ 远端那版再也不会被拉；
    //  - 拉取路径拉回旧字节 ⇒ 会话没变、基线却记成新令牌 ⇒ 永远判不出远端有更新。
    //
    // ⇒ 必须覆写：**绕开缓存 + 回写缓存 + 先取令牌再读字节**。三者缺一都不行，
    //   所以断言也分三条，且都咬在"可观测的值"上（返回的字节 / 缓存 / 调用顺序）。

    @Test
    fun `readRemoteCurrent必须绕过缓存`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("stale".toByteArray(), "etag-1"))
        val delegate = source("fresh".toByteArray(), token = "etag-2")

        val bytes = CachedKdbxFileSource(delegate, cache, key, backgroundScope).readRemoteCurrent()

        assertArrayEquals(
            "问的是『远端现在是什么』，缓存不能替它回答",
            "fresh".toByteArray(),
            bytes,
        )
        assertEquals("必须真的下载一次", 1, delegate.readCount)
    }

    @Test
    fun `缓存已是远端当前版本时跳过整份下载`() = runTest {
        // 后台校验已经把新版本字节连同令牌写进缓存；点同步时令牌仍一致
        // ⇒ 缓存就是「远端此刻」，再下一次纯属浪费（真机一次同步下了 3 遍）。
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("fresh".toByteArray(), "etag-2"))
        val delegate = source("fresh".toByteArray(), token = "etag-2")

        val bytes = CachedKdbxFileSource(delegate, cache, key, backgroundScope).readRemoteCurrent()

        assertArrayEquals("令牌一致 ⇒ 缓存可当作远端当前字节", "fresh".toByteArray(), bytes)
        assertEquals("不该再下载一次", 0, delegate.readCount)
    }

    /**
     * ★★ 11-07 真机事故：手机改了条目、云端一个字节没变，却直接显示"已同步"。
     *
     * `persist` 把**本地新字节**写进缓存、沿用旧令牌；令牌一致时旧逻辑短路返回缓存，
     * 内容对账于是拿"本地改动"冒充"远端当前" ⇒ 恒等 ⇒ 状态洗成 IN_SYNC ⇒ 上传被跳过。
     *
     * ⇒ 带 `pendingLocalEdits` 的缓存：不许短路、必须真读远端、且不许回写缓存
     *   （回写会用远端旧字节盖掉用户刚改的内容）。
     */
    @Test
    fun `缓存含本地未上传改动时不许短路_必须真读远端且不覆盖缓存`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("local-edit".toByteArray(), "etag-1", pendingLocalEdits = true))
        val delegate = source("real-remote".toByteArray(), token = "etag-1")

        val bytes = CachedKdbxFileSource(delegate, cache, key, backgroundScope).readRemoteCurrent()

        assertArrayEquals("必须拿到真远端字节，不能是本地改动", "real-remote".toByteArray(), bytes)
        assertEquals("必须真的下载一次", 1, delegate.readCount)
        assertArrayEquals("缓存里的本地改动不能被远端旧字节覆盖", "local-edit".toByteArray(), cache.peek(key)!!.bytes)
        assertEquals(true, cache.peek(key)!!.pendingLocalEdits)
    }

    @Test
    fun `缓存令牌为空时不许短路，必须真去远端读`() = runTest {
        // fail-closed：令牌是唯一能证明「缓存 == 远端」的证据，没有证据就不能省下载。
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("fresh".toByteArray(), null))
        val delegate = source("fresh".toByteArray(), token = "etag-2")

        val bytes = CachedKdbxFileSource(delegate, cache, key, backgroundScope).readRemoteCurrent()

        assertArrayEquals("fresh".toByteArray(), bytes)
        assertEquals(1, delegate.readCount)
    }

    @Test
    fun `readRemoteCurrent必须把缓存换成这一版`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("stale".toByteArray(), "etag-1"))
        val delegate = source("fresh".toByteArray(), token = "etag-2")

        CachedKdbxFileSource(delegate, cache, key, backgroundScope).readRemoteCurrent()

        assertEquals(
            "不回写的话：拉取成功后会话是新的、缓存还是旧的 ⇒ 下次解锁又读回旧内容",
            "fresh",
            String(cache.peek(key)!!.bytes),
        )
        assertEquals("etag-2", cache.peek(key)?.versionToken)
    }

    @Test
    fun `readRemoteCurrent先取令牌再读字节`() = runTest {
        val cache = FakeCache()
        val delegate = source("fresh".toByteArray(), token = "etag-2")

        CachedKdbxFileSource(delegate, cache, key, backgroundScope).readRemoteCurrent()

        assertEquals(
            "令牌必须**先于**字节取：反过来的话，读到字节之后远端又变了一版，" +
                "就会拿更新的令牌配旧字节写进缓存 —— 那一版从此再也拉不下来。" +
                "偏旧只是多拉一次，偏新是静默丢改动。",
            listOf("stat", "read"),
            delegate.calls,
        )
    }

    @Test
    fun `readRemoteCurrent取不到令牌也必须把字节读回来`() = runTest {
        val cache = FakeCache()
        val delegate = source("fresh".toByteArray(), token = "etag-2", statFails = true)

        val bytes = CachedKdbxFileSource(delegate, cache, key, backgroundScope).readRemoteCurrent()

        assertArrayEquals(
            "stat 只是给缓存配对令牌，拿不到不该连字节一起放弃",
            "fresh".toByteArray(),
            bytes,
        )
        assertNull(
            "令牌拿不到时缓存令牌为 null（下次后台校验会重下一次，安全）",
            cache.peek(key)?.versionToken,
        )
    }

    // ---- 6. 上传成功后的收敛（#233）----

    @Test
    fun `上传成功后回调必须把新令牌一起带出去`() = runTest {
        val cache = FakeCache()
        cache.save(key, CachedKdbxFile("old".toByteArray(), "etag-1"))
        val delegate = source("remote".toByteArray(), token = "etag-1")
        val reported = mutableListOf<Pair<String, String?>>()
        val sut = CachedKdbxFileSource(
            delegate = delegate,
            cache = cache,
            cacheKey = key,
            scope = backgroundScope,
            onUploaded = { bytes, newVersion -> reported += String(bytes) to newVersion },
        )

        sut.write("new".toByteArray(), expectedVersion = "etag-1")

        assertEquals(
            "上传成功必须正好上报一次（多一次就是重复收敛）",
            1,
            reported.size,
        )
        assertEquals("new", reported.single().first)
        assertEquals(FakeSource.WRITE_TOKEN, reported.single().second)
    }

    @Test
    fun `上传失败不得触发收敛回调`() = runTest {
        val cache = FakeCache()
        val delegate = object : KdbxFileSource by source("remote".toByteArray(), token = "etag-1") {
            override suspend fun write(
                bytes: ByteArray,
                expectedVersion: String?,
                force: Boolean,
                failIfExists: Boolean,
            ): KdbxFileWriteResult = throw io.vaultix.data.kdbx.KdbxFileConflictException(
                currentVersion = "etag-other",
                message = "远端已被改",
            )
        }
        var reported = 0
        val sut = CachedKdbxFileSource(
            delegate = delegate,
            cache = cache,
            cacheKey = key,
            scope = backgroundScope,
            onUploaded = { _, _ -> reported++ },
        )

        runCatching { sut.write("new".toByteArray(), expectedVersion = "etag-1") }

        assertEquals(
            "冲突就是没推上去 —— 收敛回调会把'本机==远端'这个假事实写进缓存",
            0,
            reported,
        )
    }

    @Test
    fun `收敛回调抛错不得让写成功变成失败`() = runTest {
        val cache = FakeCache()
        val delegate = source("remote".toByteArray(), token = "etag-1")
        val sut = CachedKdbxFileSource(
            delegate = delegate,
            cache = cache,
            cacheKey = key,
            scope = backgroundScope,
            onUploaded = { _, _ -> throw IllegalStateException("上层炸了") },
        )

        val result = sut.write("new".toByteArray(), expectedVersion = "etag-1")

        assertEquals(
            "字节已经上去了，收敛是**优化** —— 让它把成功翻成失败，会诱导用户再传一次",
            FakeSource.WRITE_TOKEN,
            result.versionToken,
        )
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
