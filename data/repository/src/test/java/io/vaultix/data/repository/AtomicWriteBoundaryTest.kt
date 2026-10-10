/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.database.dao.AtomicWriteDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.PendingOpEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地行 + 待推送队列」**原子性边界**的回归测试（2026-10-10 新增）。
 *
 * ## 为什么需要单独一个文件
 *
 * 姊妹文件 [ItemRepositoryImplTest] 用 **mock** 验证写路径的**行为**
 * （行写了什么、队列里放了什么 op）。但 mock 有个致命局限：
 * **两个 mock 是独立桩，不会一起回滚** ⇒ 它**测不到**「第二次写失败时第一次写是否被撤销」。
 *
 * 于是修复前就出现了一个盲区：
 * > `restoreItem` 拆成两个独立调用，行为测试**全绿**，而原子性**已经破了**。
 *
 * 这正是 S1 能潜伏至今的机制 —— **测试验证了结果，却没验证"要么都成、要么都不成"这个性质本身**。
 *
 * ## 怎么在无 Room 的前提下验证事务语义
 *
 * 生产中 `@Transaction` 由 Room 在编译期生成实现（真回滚）。单测里没有 Room，
 * 本文件就用一个**内存版两张表 + 显式回滚**来复刻这个语义：
 * 在事务方法里捕获异常 ⇒ 把两张表**恢复到快照** ⇒ 再抛出。
 *
 * ⚠️ **关键在"替哪一层"**：本文件 **override 三个 `@Transaction` 壳**（单测里它们
 *    本来就只是普通调用），而**照原样继承** `*InTransaction` 编排核心。
 *    ⇒ 于是跑的是**真实生产编排**（`super.xxxAndEnqueue` 内部仍然走那份代码），
 *      而不是测试自己抄的一份"看起来很像"的逻辑。
 *
 *    若反过来（把落点 `upsertCipher` / `upsertPendingOp` 指向内存 Map、让壳自己跑），
 *    单测里回滚**根本不会发生** —— 因为 `@Transaction` 的实现是 Room 生成的，
 *    单测里没有它，壳只是一层普通调用 ⇒ 那样的写法只能测出"写了什么"，测不出"回没回滚"。
 *
 * ## 与 [ItemRepositoryImplTest] 的分工
 *
 * | 文件 | 验证 | 手段 |
 * |---|---|---|
 * | `ItemRepositoryImplTest` | 行为对不对（写了什么、走没走原子入口） | mock + 探针子类 |
 * | **本文件** | **边界对不对（半途失败会怎样）** | 内存表 + 显式回滚 |
 */
class AtomicWriteBoundaryTest {

    // ------------------------------------------------------------------
    // 内存版"两张表" + 事务语义
    // ------------------------------------------------------------------

    /** 极简内存表：key = id，模拟 `ciphers` / `pending_ops` 两行。 */
    private class InMemoryTables {
        val ciphers = LinkedHashMap<String, String>()
        val pendingOps = mutableListOf<String>()

        fun snapshot() = Pair(LinkedHashMap(ciphers), pendingOps.toList())

        fun restore(snap: Pair<Map<String, String>, List<String>>) {
            ciphers.clear()
            ciphers.putAll(snap.first)
            pendingOps.clear()
            pendingOps.addAll(snap.second)
        }
    }

    private class TableException(message: String) : RuntimeException(message)

    /**
     * 内存版 `AtomicWriteDao`：`@Transaction` 语义由 [transaction] 手工实现。
     *
     * @param failOnPending 为 true 时，**写队列表**会抛异常 —— 用来模拟
     *                      "行写成功了、队列写失败了"这个最危险的中间态。
     */
    private class InMemoryAtomicWriteDao(
        val tables: InMemoryTables,
        var failOnPending: Boolean = false,
    ) : AtomicWriteDao() {

        /** 与 Room `@Transaction` 同语义：异常即回滚到进入前快照。 */
        private inline fun <T> transaction(body: () -> T): T {
            val snap = tables.snapshot()
            return try {
                body()
            } catch (t: Throwable) {
                tables.restore(snap)
                throw t
            }
        }

        override suspend fun upsertCipher(row: CipherEntity) {
            tables.ciphers[row.id] = row.encryptedPayload
        }

        override suspend fun upsertPendingOp(op: PendingOpEntity) {
            if (failOnPending) throw TableException("pending_ops 写失败（模拟进程被杀 / IO 异常）")
            tables.pendingOps.add("${op.op}:${op.cipherId}")
        }

        override suspend fun deleteCiphers(ids: List<String>) {
            ids.forEach { tables.ciphers.remove(it) }
        }

        // ★ 只替“事务壳”这一层；编排核心（*InTransaction）原样继承自生产代码，
        //   ⇒ 下面 `super.xxx()` 跑的就是真实业务编排，本文件才真正在测它。
        override suspend fun upsertCipherAndEnqueue(row: CipherEntity, op: PendingOpEntity) =
            transaction { super.upsertCipherAndEnqueue(row, op) }

        override suspend fun deleteCipherAndEnqueue(cipherId: String, op: PendingOpEntity) =
            transaction { super.deleteCipherAndEnqueue(cipherId, op) }

        override suspend fun deleteCiphersAndEnqueue(ids: List<String>, ops: List<PendingOpEntity>) =
            transaction { super.deleteCiphersAndEnqueue(ids, ops) }
    }

    private fun cipher(id: String, deletedDate: String? = null) = CipherEntity(
        id = id,
        vaultId = "v1",
        type = 1,
        encryptedPayload = """{"id":"$id"}""",
        revisionDate = "rev",
        deletedDate = deletedDate,
    )

    private fun op(kind: String, cipherId: String) = PendingOpEntity(
        vaultId = "v1",
        cipherId = cipherId,
        op = kind,
        payload = null,
        createdAt = 0L,
    )

    // ------------------------------------------------------------------
    // 判据 1：★ 半途失败必须整体回滚（这是修复 S1 的核心）
    // ------------------------------------------------------------------

    /**
     * ★ **"行写成功、队列写失败" 必须把行也回滚掉**。
     *
     * 修复前 `restoreItem` 的顺序是"先改行、后入队"，两个独立调用之间没有事务：
     * 若入队失败，行留在内存里（已清 `deletedDate`），队列里却没有 `RESTORE`
     * ⇒ 下次同步被服务端旧版**打回回收站**，而用户看到"恢复成功"。
     *
     * 本条模拟那个失败点，断言**行没有被改** —— 也就是说，
     * 用户会明确地看到"恢复失败"（而不是被骗）。
     *
     * 判据：失败后 `ciphers` 与进入前**逐字节相同**（容器不同但内容相同，故取量再比）。
     */
    @Test
    fun whenPendingWriteFails_rowChangeIsRolledBack() = runTest {
        val tables = InMemoryTables().apply {
            ciphers["c1"] = """{"before":true}""" // 恢复前的形态（仍在回收站）
        }
        val dao = InMemoryAtomicWriteDao(tables, failOnPending = true)
        val beforeCiphers = LinkedHashMap(tables.ciphers)
        val beforeOps = tables.pendingOps.toList()

        var threw = false
        try {
            // 恢复：把 deletedDate 清掉 + 入队 RESTORE
            dao.upsertCipherAndEnqueue(
                row = cipher("c1", deletedDate = null),
                op = op("RESTORE", "c1"),
            )
        } catch (_: TableException) {
            threw = true
        }

        assertTrue("队列写失败必须抛出（让上层看到失败）", threw)
        assertEquals(
            "★ 行必须被回滚 —— 不许留下『已恢复、但没队列』的中间态",
            beforeCiphers,
            tables.ciphers,
        )
        assertEquals("队列里不该有残留", beforeOps, tables.pendingOps)
    }

    /**
     * 对照组：队列写**成功**时，两条写都要生效。
     *
     * 没有这条，"全都回滚"这种明显错误的实现也能骗过上面那条测试。
     */
    @Test
    fun whenPendingWriteSucceeds_bothChangesApply() = runTest {
        val tables = InMemoryTables().apply { ciphers["c1"] = """{"old":true}""" }
        val dao = InMemoryAtomicWriteDao(tables, failOnPending = false)

        dao.upsertCipherAndEnqueue(
            row = cipher("c1", deletedDate = null),
            op = op("RESTORE", "c1"),
        )

        assertEquals("""{"id":"c1"}""", tables.ciphers["c1"])
        assertEquals(listOf("RESTORE:c1"), tables.pendingOps)
    }

    // ------------------------------------------------------------------
    // 判据 2：删除型的危险方向相反，同样必须原子
    // ------------------------------------------------------------------

    /**
     * ★ 永久删除：**"队列写失败"时行不许被删**。
     *
     * 为什么这条的方向与 upsert 相反但同样要命：
     * 若「行删了、队列没记」，服务端会保留该条目，
     * 下次同步 `persistCiphers` 又把它**拉回来** —— 用户以为永久删了，结果复活。
     *
     * ⇒ 断言：失败后行**还在**。
     */
    @Test
    fun whenPendingWriteFails_onPermanentDelete_rowIsNotRemoved() = runTest {
        val tables = InMemoryTables().apply { ciphers["c9"] = """{"id":"c9"}""" }
        val dao = InMemoryAtomicWriteDao(tables, failOnPending = true)

        var threw = false
        try {
            dao.deleteCipherAndEnqueue(cipherId = "c9", op = op("DELETE", "c9"))
        } catch (_: TableException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals(
            "★ 行不许被删 —— 否则会被服务端拉回来（永久删除却『复活』）",
            mapOf("c9" to """{"id":"c9"}"""),
            tables.ciphers,
        )
        assertEquals("队列里不该有残留", emptyList<String>(), tables.pendingOps)
    }

    /** 批量清理：任一条入队失败，整批都不许动（避免"清到一半"）。 */
    @Test
    fun whenPendingWriteFails_onBatchCleanup_noRowIsRemoved() = runTest {
        val tables = InMemoryTables().apply {
            ciphers["a"] = "{}"
            ciphers["b"] = "{}"
            ciphers["c"] = "{}"
        }
        val dao = InMemoryAtomicWriteDao(tables, failOnPending = true)

        var threw = false
        try {
            dao.deleteCiphersAndEnqueue(
                ids = listOf("a", "b", "c"),
                ops = listOf(op("DELETE", "a"), op("DELETE", "b"), op("DELETE", "c")),
            )
        } catch (_: TableException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals("★ 整批必须整体回滚，不许留下『清到一半』", 3, tables.ciphers.size)
        assertEquals("队列里不该有残留", emptyList<String>(), tables.pendingOps)
    }

    /** 对照组：批量清理成功时，整批都被清掉（防止"实现成什么都没做"也能过）。 */
    @Test
    fun whenPendingWriteSucceeds_onBatchCleanup_allRowsRemoved() = runTest {
        val tables = InMemoryTables().apply {
            ciphers["a"] = "{}"
            ciphers["b"] = "{}"
        }
        val dao = InMemoryAtomicWriteDao(tables, failOnPending = false)

        dao.deleteCiphersAndEnqueue(
            ids = listOf("a", "b"),
            ops = listOf(op("DELETE", "a"), op("DELETE", "b")),
        )

        assertEquals("行应被整批删除", 0, tables.ciphers.size)
        assertEquals(listOf("DELETE:a", "DELETE:b"), tables.pendingOps)
    }

    // ------------------------------------------------------------------
    // 判据 3：三条原语都存在
    // ------------------------------------------------------------------

    /**
     * 结构判据：`AtomicWriteDao` 必须提供**三种**原语。
     *
     * 首版只有 `upsertCipherAndEnqueue`，而"行+队列"的组合形态有三种
     * （upsert / delete / batch delete）—— 缺哪一种，调用点就会退化成
     * 两个独立调用（S1 就是这么发生的）。
     *
     * 这条用反射断言三个公开方法都在，防止有人"精简 API"时删掉删除型原语。
     */
    @Test
    fun atomicWriteDaoExposesAllThreePrimitives() {
        val names = AtomicWriteDao::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()

        for (required in listOf(
            "upsertCipherAndEnqueue",
            "deleteCipherAndEnqueue",
            "deleteCiphersAndEnqueue",
        )) {
            assertTrue(
                "AtomicWriteDao 缺少原语 $required —— 缺了它，调用点就会退化成两个独立调用（S1 的成因）",
                required in names,
            )
        }
    }

    /**
     * ★★ 守护判据：三个 `@Transaction` 壳必须**委托**给对应的 `*InTransaction` 编排。
     *
     * 本文件其余用例靠 override 壳来实现回滚 —— 这带来一个副作用：
     * 只要壳存在（哪怕它**什么都不做**），测试就不会红。
     * ⇒ 用一个**不 override** 的朴素子类（落点指向内存表）直接调壳，
     *   断言**编排确实被执行**（两张表都被改到）。
     *
     * 这条守的是"壳与核心别脱钩"：若有人把壳改成空实现、或忘记委托，
     * 出现的就是"测试全绿、生产什么都不写"这种最坏的结局。
     */
    @Test
    fun transactionShellsDelegateToRealOrchestration() = runTest {
        val tables = InMemoryTables()
        // ⚠️ 不 override 任何壳方法 —— 让生产代码的壳 + 核心原样跑。
        val dao = object : AtomicWriteDao() {
            override suspend fun upsertCipher(row: CipherEntity) {
                tables.ciphers[row.id] = row.encryptedPayload
            }

            override suspend fun upsertPendingOp(op: PendingOpEntity) {
                tables.pendingOps.add("${op.op}:${op.cipherId}")
            }

            override suspend fun deleteCiphers(ids: List<String>) {
                ids.forEach { tables.ciphers.remove(it) }
            }
        }

        // upsert 壳 → upsertCipherAndEnqueueInTransaction
        dao.upsertCipherAndEnqueue(cipher("x"), op("CREATE", "x"))
        assertEquals("""{"id":"x"}""", tables.ciphers["x"])
        assertEquals(listOf("CREATE:x"), tables.pendingOps)

        // delete 壳 → deleteCipherAndEnqueueInTransaction
        dao.deleteCipherAndEnqueue(cipherId = "x", op = op("DELETE", "x"))
        assertEquals(0, tables.ciphers.size)
        assertEquals(listOf("CREATE:x", "DELETE:x"), tables.pendingOps)

        // batch 壳 → deleteCiphersAndEnqueueInTransaction
        //
        // ⚠️ 这里**不能**写 `dao.upsertCipher(...)` 来预置数据：`upsertCipher` 是
        //    `protected`，而 `dao` 的静态类型是 `AtomicWriteDao` ⇒ 从**类外**不可见
        //    （override 成 public 也没用，静态类型决定可见性）。CI 就是这么红的：
        //    "Cannot access 'suspend fun upsertCipher(...)': it is protected"。
        //    预置数据直接写内存表即可 —— 本用例关心的是**壳是否委托到核心**，
        //    不关心行是怎么进去的。
        tables.ciphers["y"] = """{"id":"y"}"""
        tables.ciphers["z"] = """{"id":"z"}"""
        dao.deleteCiphersAndEnqueue(
            ids = listOf("y", "z"),
            ops = listOf(op("DELETE", "y"), op("DELETE", "z")),
        )
        assertEquals(0, tables.ciphers.size)
        assertEquals(4, tables.pendingOps.size)
    }
}
