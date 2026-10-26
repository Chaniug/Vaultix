/*
 * Vaultix — app
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SensitiveCache] 的行为锁定（#193）。
 *
 * ⚠️ 用**真实临时目录**，不用 mock 文件系统：本类的全部价值就在于"真的删掉了"，
 * 把 `File` 换成 mock 之后，测的就只是"我们有没有调用自己写的方法"。
 * `@ApplicationContext`
 * 是唯一被 mock 的东西，`cacheDir` 指向 `TemporaryFolder`。
 */
class SensitiveCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cache(): SensitiveCache {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.cacheDir } returns temp.root
        return SensitiveCache(context)
    }

    private fun writeFile(dir: File, name: String, content: String = "secret"): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    @Test
    fun `dirForItem_按条目隔离且自动创建`() {
        val cache = cache()
        val dir = cache.dirForItem(SensitiveCache.KIND_ATTACHMENTS, "item-1")

        assertTrue("目录必须已创建", dir.isDirectory)
        assertTrue("必须落在敏感缓存根下，否则 clearAll 覆盖不到", dir.path.contains("vaultix-sensitive"))
    }

    @Test
    fun `dirForItem_条目id带分隔符不做路径穿越`() {
        val cache = cache()
        val dir = cache.dirForItem(SensitiveCache.KIND_ATTACHMENTS, "../../etc")

        // 关键断言：结果必须是 root/attachments/<扁平名>，**不能再多一层**。
        val parent = dir.parentFile!!
        assertEquals(SensitiveCache.KIND_ATTACHMENTS, parent.name)
        assertFalse("分隔符必须被替换掉", dir.name.contains("/"))
        assertTrue("不能逃出 cacheDir", dir.canonicalPath.startsWith(temp.root.canonicalPath))
    }

    @Test
    fun `invalidateFor_只清该条目的所有用途`() {
        val cache = cache()
        val mineA = writeFile(cache.dirForItem(SensitiveCache.KIND_ATTACHMENTS, "item-1"), "a.pdf")
        val mineT = writeFile(cache.dirForItem(SensitiveCache.KIND_THUMBS, "item-1"), "t.png")
        val otherA = writeFile(cache.dirForItem(SensitiveCache.KIND_ATTACHMENTS, "item-2"), "b.pdf")

        cache.invalidateFor("item-1")

        assertFalse("本条目附件应被删", mineA.exists())
        assertFalse("本条目缩略图应被删（KIND 列表覆盖）", mineT.exists())
        assertTrue("别的条目不能动", otherA.exists())
    }

    @Test
    fun `clearAll_清掉整个根目录`() {
        val cache = cache()
        val a = writeFile(cache.dirForItem(SensitiveCache.KIND_ATTACHMENTS, "item-1"), "a.pdf")
        val t = writeFile(cache.dirForItem(SensitiveCache.KIND_THUMBS, "item-2"), "t.png")

        cache.clearAll()

        assertFalse(a.exists())
        assertFalse(t.exists())
        assertFalse("根目录本身也应删掉", cache.root().exists())
    }

    @Test
    fun `clearAll_不碰导出日志目录`() {
        // ⚠️ 这条是**反向锁定**：`cache/dev_logs/` 是用户主动导出的排查日志，
        //   与库内容无关。哪天有人把清除范围从"敏感根目录"扩成"整个 cacheDir"，
        //   这条会立刻变红 —— 那等于用户刚导出的日志在锁库那一刻消失。
        val cache = cache()
        val log = writeFile(File(temp.root, "dev_logs"), "vaultix-2026.log")

        cache.clearAll()

        assertTrue("导出日志不能被敏感缓存清理波及", log.exists())
    }

    @Test
    fun `clearAll_目录不存在时不抛`() {
        // 锁库路径不能被"没什么可删"打断。
        val cache = cache()
        cache.clearAll()
        cache.clearAll()
        assertFalse(cache.root().exists())
    }
}
