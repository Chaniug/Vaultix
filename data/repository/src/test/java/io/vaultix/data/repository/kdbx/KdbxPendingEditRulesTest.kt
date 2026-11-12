/*
 * Vaultix — data:repository（单测 / 源码文本门禁）
 * Copyright (C) 2026 Vaultix contributors
 *
 * ---------------------------------------------------------------------------
 * #286（2026-11-12）：**「本地有没有未上传改动」的判据必须只有一份**。
 *
 * 这条修的是一条真机可复现的丢改动链路（OneDrive 上的 kdbx）：
 *
 * ```
 * 改条目 → 写脏缓存(pendingLocalEdits) + PENDING_UPLOAD → 自动上传起跑(SYNCING)
 *        → 返回 / 断网（状态停在 SYNCING 或 FAILED）
 *        → 刷新 → 旧闸门读 DB 判「没改」→ 拉远端旧字节替换会话 → 脏缓存被盖掉
 * ```
 *
 * 编译过、detekt 查不出、真机才看得出来 ⇒ 与项目既有观感 / 契约门禁同款，
 * 用源码文本把「哪里必须问谁」钉住。这类规则的关键是**精确点名**，
 * 别写粗放的「不许出现某个词」——本项目为此付过代价（见 part23 踩坑 6）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KdbxPendingEditRulesTest {

    /** 从测试工作目录往上找项目根，避免依赖「Gradle 把 cwd 设成哪个模块」。 */
    private val root: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(relative: String): String = codeOnly(File(root, relative).readText())

    private fun coordinatorSrc(): String = source(
        "data/repository/src/main/java/io/vaultix/data/repository/kdbx/KdbxCloudSyncCoordinator.kt",
    )

    private fun repositoryImplSrc(): String = source(
        "data/repository/src/main/java/io/vaultix/data/repository/kdbx/KdbxSyncRepositoryImpl.kt",
    )

    private fun uploaderSrc(): String = source(
        "data/repository/src/main/java/io/vaultix/data/repository/kdbx/KdbxAutoUploader.kt",
    )

    private fun cacheSrc(): String = source(
        "data/repository/src/main/java/io/vaultix/data/repository/kdbx/CachedKdbxFileSource.kt",
    )

    private fun appSrc(): String = source(
        "app/src/main/java/io/vaultix/vaultix/VaultixApplication.kt",
    )

    /**
     * ★ 刷新路径必须有**两道**闸门，且都走双真源。
     *
     * 只判「有没有 `hasUnsyncedLocalEdits`」不够：旧代码有一道闸门（read 之前），
     * 却挡不住下载那 1–10 s 里新产生的编辑。⇒ 数次数 + 检查第二道在 read 之后。
     */
    @Test
    fun `刷新闸门必须两道都走双真源且第二道在下载之后`() {
        val src = coordinatorSrc()
        val gates = Regex("""hasUnsyncedLocalEdits\(vaultId\)""").findAll(src).count()
        assertEquals(
            "resolveUsingRemote 必须有「下载前 + 下载后」两道双真源闸门（#286）",
            2,
            gates,
        )
        assertFalse(
            "不许退回只读 DB 状态的旧闸门（SYNCING / FAILED 会被误判成「本地没改」）",
            src.contains("&& localChangedSinceLastSync(vaultId)"),
        )
        assertTrue(
            "判据本身必须真的把缓存脏标记算进去",
            src.contains("persistedStatusSaysLocalChanges(vaultId) || hasCachedLocalEdits(vaultId)"),
        )
        val download = src.indexOf("source.readRemoteCurrent()")
        val secondGate = src.indexOf("hasUnsyncedLocalEdits(vaultId)", download + 1)
        assertTrue(
            "下载之后必须还有一道闸门，否则覆盖不了下载期间的编辑",
            download >= 0 && secondGate > download,
        )
    }

    /** 仓储层不许再抄一份「读 DB 状态」的判据（#233 / #264 同款教训）。 */
    @Test
    fun `仓储的localChangedSinceLastSync必须委托协调器`() {
        val src = repositoryImplSrc()
        assertTrue(
            "必须走 coordinator.hasUnsyncedLocalEdits（DB 状态 + 缓存脏标记）",
            src.contains("coordinator.hasUnsyncedLocalEdits(vaultId)"),
        )
        assertFalse(
            "不许在本层再读一次 syncStatus / impliesLocalChanges —— 同一事实存两份必然漂移",
            src.contains("KdbxCloudSyncStatus.fromName") || src.contains("impliesLocalChanges"),
        )
    }

    /** 补传入口不能只写在 data 层：没有启动钩子调用，它等于不存在。 */
    @Test
    fun `冷启动补传入口必须接线到Application`() {
        val uploader = uploaderSrc()
        assertTrue(
            "上传器必须提供批量补传入口（enqueue 只在保存那一刻调用）",
            uploader.contains("fun resumePendingUploads(): Job"),
        )
        assertTrue(
            "补传必须扫全库表快照",
            uploader.contains("vaultDao.observeAll().first()"),
        )
        val app = appSrc()
        assertTrue("Application 必须注入上传器", app.contains("lateinit var kdbxAutoUploader: KdbxAutoUploader"))
        assertTrue(
            "Application.onCreate 必须真的调一次补传（只注入不调用 = 功能没做）",
            app.contains("kdbxAutoUploader.resumePendingUploads()"),
        )
    }

    /**
     * ★★ #286 补正：**冷启动补传不能扫到锁着的库**。
     *
     * `KdbxSyncOrchestrator.sync` 的上传分支走 `Kdbx.saveVia`，它要求
     * `KdbxSessionStore` 里有会话；锁态下必然 `NotUnlocked` 失败 ⇒ 写假 `FAILED`
     * + 退避重试 3 次，改动一个字节都没上去。只加"启动扫一次"是不够的。
     */
    @Test
    fun `补传扫描必须过滤未解锁的库`() {
        val src = uploaderSrc()
        assertTrue(
            "默认判据必须是 Kdbx.isUnlocked（会话就绪才补传）",
            src.contains("sessionReady: (String) -> Boolean = Kdbx::isUnlocked"),
        )
        assertTrue(
            "扫描结果必须真的按会话判据过滤，而不是只声明一个参数",
            src.contains(".filter { sessionReady(it.id) }"),
        )
        assertTrue(
            "过滤必须发生在映射成 id 列表之前（否则过滤的是别的东西）",
            src.indexOf(".filter { sessionReady(it.id) }") < src.indexOf(".map { it.id }"),
        )
    }

    /** 锁态跳过的库必须有人接着补：订阅解锁事件，且由 Application 显式启动。 */
    @Test
    fun `解锁观察器必须订阅会话流并接进Application`() {
        val src = uploaderSrc()
        assertTrue(
            "上传器必须提供解锁观察器（订阅是显式启动，不放 init 以免污染单测）",
            src.contains("fun startUnlockObserver(): Job"),
        )
        assertTrue("必须订阅 Kdbx.sessionRevision()", src.contains("Kdbx.sessionRevision().collect"))
        assertTrue(
            "只补传新解锁的库，不能每次编辑都重扫全表",
            src.contains("newlyUnlockedIds(known, now)"),
        )
        assertFalse(
            "观察器不许放进 init（单测构造上传器会挂常驻订阅、被别的用例解锁事件误触发）",
            Regex("""init\s*\{[^}]*startUnlockObserver""").containsMatchIn(src),
        )
        val app = appSrc()
        assertTrue(
            "Application.onCreate 必须显式启动解锁观察器",
            app.contains("kdbxAutoUploader.startUnlockObserver()"),
        )
        assertTrue(
            "两行顺序：先订阅再扫存量，避免恢复会话落在窗口里",
            app.indexOf("kdbxAutoUploader.startUnlockObserver()") <
                app.indexOf("kdbxAutoUploader.resumePendingUploads()"),
        )
    }

    /** 装饰器回写缓存前必须现查脏标记，否则会把下载期间的编辑盖成远端旧字节。 */
    @Test
    fun `缓存回写前必须现查脏标记而不是用下载前的快照`() {
        val src = cacheSrc()
        assertTrue(
            "必须在 delegate.read() 之后再 load 一次缓存",
            src.contains("val loadNow = runCatching { cache.load(cacheKey) }"),
        )
        assertTrue(
            "现查结果必须参与回写守卫",
            src.contains("if (!hasLocalEdits && !dirtyNow && !loadFailed)"),
        )
        assertFalse(
            "不许退回「只看下载前的 hasLocalEdits」的旧守卫",
            src.contains("if (!hasLocalEdits) {"),
        )
    }

    /**
     * 只丢掉 KDoc / 行注释，保留代码行。
     *
     * ⚠️ 不做「按 `//` 截断整行」：源码里有 `content://` 这类合法字符串，
     *    一刀切会把代码本身切没。判据关键词都在独立代码行上，按行过滤足够。
     */
    private fun codeOnly(text: String): String {
        var inBlock = false
        // ⚠️ 用 `mapNotNull` 而不是 `for + continue`：detekt 的
        //    `LoopWithTooManyJumpStatements` 不允许一个循环里出现多个 break/continue，
        //    而块注释的三个分支恰好都要"跳过这一行"。返回 null 表达同一件事，
        //    且保留 `inBlock` 这个跨行状态（普通 `filter` 做不到）。
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
