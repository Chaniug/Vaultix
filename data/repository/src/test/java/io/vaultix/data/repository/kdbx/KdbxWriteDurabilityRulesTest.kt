/*
 * Vaultix — data:repository（单测 / 源码文本门禁）
 * Copyright (C) 2026 Vaultix contributors
 *
 * ---------------------------------------------------------------------------
 * #287（2026-11-12）：**「会话已换好」到「落盘完成」之间不许有可取消的窗口**。
 *
 * 真机可复现（用户原话）：
 *
 * ```
 * kdbx 条目改完 → 本地立刻看得到 → 立刻返回 → 点同步 → 云端旧版本盖回来
 * 若保存后停 5 秒，看到「已保存到本地 · 正在同步到云端」才真的生效
 * ```
 *
 * 根因不是 #286 修的同步闸门，而是**更早的一段**：`Kdbx.mutateLocked` 先把新会话
 * 换进内存，紧接着 `persist()` 里的 `Kdbx.verifyWrite()` 是一次 KDF 往返自检
 * （真机 1~5 s）。用户在这几秒返回 ⇒ `viewModelScope` 取消 ⇒ 脏缓存 /
 * `markLocalEdited` / `autoUploader.enqueue` 全没跑 ⇒ DB 仍是 `IN_SYNC`、
 * 缓存没有 `pendingLocalEdits` ⇒ #286 的双真源判据两个都干净 ⇒ 同步拉远端旧版
 * `replaceSession`，改动彻底消失。
 *
 * 编译过、detekt 查不出、真机才看得出来 ⇒ 与 #286 / #285 同款，用源码文本把
 * 「不可取消」「脏缓存先落」「失败要恢复」三处契约钉住。规则一律**精确点名**，
 * 不写粗放的「不许出现某个词」（见 part23 踩坑 6）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KdbxWriteDurabilityRulesTest {

    /** 从测试工作目录往上找项目根，避免依赖「Gradle 把 cwd 设成哪个模块」。 */
    private val root: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(relative: String): String = codeOnly(File(root, relative).readText())

    private fun itemRepo(): String = source(
        "data/repository/src/main/java/io/vaultix/data/repository/KdbxItemRepository.kt",
    )

    private fun detailVm(): String = source(
        "app/src/main/java/io/vaultix/vaultix/ui/detail/ItemDetailViewModel.kt",
    )

    /**
     * ★ 八个写动作共用的一道闸门必须**整笔不可取消**，且包在 `withLock` 外面。
     *
     * 只判「有 NonCancellable」不够：若写在 `withLock` 里面，等锁期间仍会被取消；
     * 若某个写动作绕过 `serialWrite`，那条路径就完全没有保护。
     */
    @Test
    fun `八个写动作全部收口在不可取消的serialWrite`() {
        val src = itemRepo()
        val body = functionBody(src, "serialWrite")
        assertTrue(
            "serialWrite 必须用 NonCancellable 包住整笔写回（#287 真机：改完立刻返回就丢）",
            body.contains("withContext(Dispatchers.IO + NonCancellable)"),
        )
        assertTrue(
            "withContext 必须包在 withLock 外面：等锁期间也不许被取消",
            body.indexOf("withContext") >= 0 &&
                body.indexOf("withContext") < body.indexOf("writeGate.withLock"),
        )
        assertEquals(
            "八个写动作都必须走 serialWrite；新增写动作时同步改这里并确认它也受保护",
            8,
            Regex("""serialWrite\s*\{""").findAll(src).count(),
        )
    }

    /**
     * ★ 脏缓存必须在几秒的往返自检**之前**落盘。
     *
     * 顺序反了就重新打开 #287 那个窗口：`verifyWrite` 期间「本地有改动」这件事
     * 对同步判据完全不可见，而会话已经换好、UI 已经显示改过了。
     */
    @Test
    fun `脏缓存必须先落再做几秒的往返自检`() {
        val src = itemRepo()
        val save = src.indexOf("fileCache.save(")
        val verify = src.indexOf("Kdbx.verifyWrite(")
        assertTrue("找不到写脏缓存的调用", save >= 0)
        assertTrue("找不到往返自检的调用", verify >= 0)
        assertTrue(
            "fileCache.save 必须排在 Kdbx.verifyWrite 之前（否则同步判据有 1~5 s 盲窗）",
            save < verify,
        )
        assertTrue("脏缓存必须带 pendingLocalEdits = true", src.contains("pendingLocalEdits = true"))
        assertTrue("必须沿用旧版本令牌作为条件写基线", src.contains("previous?.versionToken"))
    }

    /**
     * ★ 自检 / 落盘失败时，刚写的脏缓存必须收回。
     *
     * 否则一份**没通过往返自检**的字节会留在缓存里：下次解锁直接拿它解密（坏库），
     * 或把它当成「本地有改动」推上远端。取消则必须单独重抛，不能被记成保存失败。
     */
    @Test
    fun `写回失败必须恢复缓存快照且取消不当成失败`() {
        val src = itemRepo()
        assertTrue("必须有 try 外可见的回滚快照", src.contains("var cacheRestore: (suspend () -> Unit)? = null"))
        assertTrue("写完脏缓存后必须登记恢复动作", src.contains("cacheRestore = {"))
        assertTrue("原本没有缓存时要能删掉刚写的那份", src.contains("fileCache.remove(origin)"))
        assertTrue("失败路径必须调用恢复动作", src.contains("runCatching { cacheRestore?.invoke() }"))
        assertTrue(
            "CancellationException 必须单独处理：只重抛、不记 ERROR、不走回滚链",
            src.contains("if (error !is CancellationException)"),
        )
        assertTrue(
            "恢复缓存必须排在回滚会话之前",
            src.indexOf("runCatching { cacheRestore?.invoke() }") <
                src.indexOf("Kdbx.rollbackMutation(vaultId, write)"),
        )
    }

    /** 标记待上传与排自动上传都不许在重排中丢失，且顺序不能反。 */
    @Test
    fun `标记待上传早于排自动上传且两者都还在persist里`() {
        val src = itemRepo()
        val mark = src.indexOf("cloudSync.markLocalEdited(vaultId)")
        val enqueue = src.indexOf("autoUploader.enqueue(vaultId)")
        assertTrue("找不到 markLocalEdited", mark >= 0)
        assertTrue("找不到 autoUploader.enqueue（改动将永远推不上云端）", enqueue >= 0)
        assertTrue("先标记待上传、再排上传队列", mark < enqueue)
    }

    /**
     * ★ UI 层第二道：编辑与删除的写回协程都必须不可取消。
     *
     * `deleteItem` 早在 11-08 就修过，编辑没跟上才有了 #287。两条都钉住，
     * 防止以后有人「统一简化」时把其中一条改回普通 `launch`。
     */
    @Test
    fun `编辑与删除的写回协程都必须不可取消`() {
        val vm = detailVm()
        val update = functionBody(vm, "updateItem")
        val delete = functionBody(vm, "deleteItem")
        assertTrue(
            "updateItem 必须 NonCancellable（#287 真机：改完立刻返回就丢）",
            update.contains("withContext(NonCancellable)"),
        )
        assertTrue(
            "deleteItem 既有的 NonCancellable 不许回退",
            delete.contains("withContext(NonCancellable)"),
        )
        assertFalse(
            "不许用已 deprecated 的 launch(NonCancellable)（破坏结构化并发，未来是编译错误）",
            vm.contains("launch(NonCancellable)"),
        )
    }

    /**
     * 括号配平取「签名 + 函数体」（含泛型函数 `fun <T> …`）。
     *
     * ⚠️ 必须从**签名起点**截，不能从第一个 `{` 截：`serialWrite` 是表达式体
     *    `= withContext(Dispatchers.IO + NonCancellable) { … }`，第一个 `{` 属于
     *    `withContext` 的 lambda —— 从那里截会把要判的 `withContext` 本身排除掉。
     */
    private fun functionBody(src: String, name: String): String {
        val signature = Regex("""fun\s+(?:<[^>]+>\s+)?${Regex.escape(name)}\s*\(""").find(src)
        assertTrue("找不到函数 $name", signature != null)
        val start = signature!!.range.first
        val open = src.indexOf('{', start)
        assertTrue("$name 没有函数体", open >= 0)
        var depth = 0
        for (index in open until src.length) {
            when (src[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, index + 1)
                }
            }
        }
        return ""
    }

    /** 去掉 KDoc / 行注释，避免注释里出现的示例代码把「.indexOf」判据带偏。 */
    private fun codeOnly(text: String): String {
        var inBlock = false
        return text.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            when {
                inBlock -> {
                    if (trimmed.contains("*/")) inBlock = false
                    null
                }
                trimmed.startsWith("/*") -> {
                    inBlock = !trimmed.contains("*/")
                    null
                }
                trimmed.startsWith("*") || trimmed.startsWith("//") -> null
                else -> line
            }
        }.joinToString("\n")
    }
}
