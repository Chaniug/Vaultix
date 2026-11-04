package io.vaultix.data.repository.kdbx

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileKdbxFileCache] 的落盘纪律（批次 B1，2026-09-30；2026-11-04 改单文件）。
 *
 * ## 两条最要紧的
 *
 * 1. **半截文件**必须当作未命中：被 Honor 杀进程是这台设备的常态（实测单轮 4 连杀），
 *    截断字节喂给 KDBX 会报「文件损坏 / 密码错」，用户无从判断。
 * 2. **内容与令牌必须同生同死**：旧实现分两个文件写，中间那段窗口被杀 ⇒ 新的内容
 *    配旧的元数据 ⇒ 被判成"半截" ⇒ **把一份完好的缓存删掉** ⇒ 下次重新下载整库。
 *    表现就是「锁屏回来特别慢」，且时好时坏。单文件 + 一次 rename 才消掉这个窗口。
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

    /**
     * ★ 一个 key **只落一个文件**（2026-11-04）。
     *
     * 这条是"原子写"的结构性判据：只要还存在 `数据 + 侧车` 两个文件，
     * 就必然存在"写完一个、另一个还没写"的窗口，而那正是 #246 里
     * 「缓存莫名失效、解锁重新下载」的来源。
     *
     * ⚠️ 断言"文件个数"而不是"有没有 .meta"：后者会放过任何新起的侧车命名。
     */
    @Test
    fun `一个库只落一个文件_不存在数据和元数据分开写`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), "etag-1"))

        val files = root.listFiles()!!.filter { it.isFile }
        assertEquals("缓存必须单文件落盘（否则存在内容/令牌不一致的窗口）：$files", 1, files.size)
        assertTrue("落盘的是 .kdbx 数据文件：$files", files.single().name.endsWith(".kdbx"))
        // 不能留临时文件：留着就说明有一次 save 在中途失败且没清理。
        assertTrue("不得残留 .tmp：$files", files.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `文件被截断_当作未命中并清掉`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), "etag-1"))
        val file = root.listFiles()!!.single { it.name.endsWith(".kdbx") }
        val full = file.readBytes()
        // 模拟"写到一半被杀"：砍掉尾部（内容不完整）。
        file.writeBytes(full.copyOf(full.size - 3))

        assertNull("截断的缓存必须当作没有（宁慢一次，不说谎一次）", cache.load(key))
        assertNull("不可信的缓存应被清掉，避免每次白读一遍", cache.load(key))
        assertTrue("清掉之后文件不应再存在", root.listFiles()!!.none { it.name.endsWith(".kdbx") })
    }

    /**
     * ★ 令牌段**声明长度超过剩余字节**时必须判损坏。
     *
     * 只砍尾部还不够狠：如果只把 header 里的 tagLen 改大（模拟"头写了、体没写完"），
     * 朴素实现会 `ByteArray(tagLength)` 越界抛异常或读出垃圾令牌 ——
     * 前者让解锁直接崩，后者让"远端是否变了"的判据永久失真。
     */
    @Test
    fun `令牌长度声明超出剩余字节_当作未命中`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), "etag-1"))
        val file = root.listFiles()!!.single { it.name.endsWith(".kdbx") }
        val bytes = file.readBytes()
        // header = [magic:int][tagLen:int]，把 tagLen 改成 9999。
        val patched = bytes.copyOf()
        java.nio.ByteBuffer.wrap(patched).putInt(Int.SIZE_BYTES, 9999)
        file.writeBytes(patched)

        assertNull("声明长度超出剩余字节必须当作未命中，不能抛异常也不能读垃圾", cache.load(key))
    }

    /** 旧格式（裸 KDBX 字节、无 magic）必须被判为未命中并清掉，不需要迁移逻辑。 */
    @Test
    fun `旧版的裸字节格式_当作未命中`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), "etag-1"))
        val file = root.listFiles()!!.single { it.name.endsWith(".kdbx") }
        // 旧实现写的就是裸内容。
        file.writeBytes("payload".toByteArray())

        assertNull("没有 magic 的文件一律不可信（旧格式 / 被改过）", cache.load(key))
    }

    @Test
    fun `删除后读不到`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("payload".toByteArray(), "etag-1"))

        cache.remove(key)

        assertNull(cache.load(key))
    }

    /** 覆盖写：后一次必须完全取代前一次（不能残留旧令牌）。 */
    @Test
    fun `覆盖写后读到的是新内容与新令牌`() = runTest {
        val cache = FileKdbxFileCache(root)
        cache.save(key, CachedKdbxFile("old-payload".toByteArray(), "etag-old"))
        cache.save(key, CachedKdbxFile("new-payload".toByteArray(), "etag-new"))

        val loaded = cache.load(key)

        assertNotNull(loaded)
        assertArrayEquals("new-payload".toByteArray(), loaded?.bytes)
        assertEquals("etag-new", loaded?.versionToken)
    }
}
