/*
 * Vaultix — core:database
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.PendingOpEntity

/**
 * 「本地行 + 待推送队列」的**原子写**（2026-09-16 新增）。
 *
 * ## 为什么需要它（这是一个真实的数据丢失缺口）
 *
 * 本地改动要记两处：
 * 1. `ciphers` 表 —— 让列表**立刻**能看到改动（离线也安全）；
 * 2. `pending_ops` 表 —— 作为「待推送」凭据，同步时据此上云。
 *
 * 此前这两写是**两个独立的 suspend 调用**，中间没有事务。若第二次写失败
 * （进程被杀 / IO 异常），就会出现「**行在、队列不在**」或「**队列在、行不在**」的中间态：
 *
 * - 该行不在 `pendingIds` 里 ⇒ `pruneRemovedRows` 把它当成「服务端已删除」⇒ **删掉**（新建的条目凭空消失）；
 * - 或 `persistCiphers` 用服务端旧版本**覆盖**它（编辑的内容丢失）；
 * - 软删除的条目则会被服务端版本**复活**；
 * - 恢复（restore）时若「行已清 deletedDate、队列没记」⇒ 下次同步被服务端旧版**再次打回回收站**
 *   —— 用户看到的是「恢复成功」但条目又消失了。
 *
 * ⇒ 两次写必须**同生共死**。跨 DAO 的事务需要数据库级 `withTransaction`，
 *   而 `room-ktx` 在 `core:database` 里是 `implementation`（不向依赖方传递）——
 *   与其让上层为此加依赖，不如把这对写操作收进本 DAO 的 `@Transaction` 方法。
 *
 * ## ⚠️ 2026-10-10 补齐删除型原语（第二次一致性缺口）
 *
 * 首版只有 [upsertCipherAndEnqueue] 一个方法，而 `ItemRepositoryImpl` 里
 * 「行+队列」的组合共有 **5 处**，其余两处（`restoreItem` 用 upsert、
 * `permanentDeleteItem` / `cleanupExpiredTrash` 用 delete）仍是两个独立调用。
 * 其中 `restoreItem` 的中间态**危险方向最重**（见上）。
 *
 * ⇒ 本 DAO 现覆盖"行+队列"的全部三种形态：
 *   | 场景 | 方法 |
 *   |---|---|
 *   | 新增 / 编辑 / 软删除 | [upsertCipherAndEnqueue] |
 *   | 恢复（清 `deletedDate`） | [upsertCipherAndEnqueue]（同一入口，行内容不同） |
 *   | 永久删除 / 清理过期 | [deleteCipherAndEnqueue] / [deleteCiphersAndEnqueue] |
 *
 * ⚠️ 本 DAO **只做这一件事**：不要往里加与"行+队列"无关的方法，
 *    否则 `ItemRepositoryImpl` 的写路径会分散到两个地方。
 *
 * ⚠️ **新增任何"写 ciphers + 写 pending_ops"的组合时，必须走本 DAO。**
 *    直接调 `cipherDao.upsertAll` + `pendingOpDao.enqueue` 会被
 *    `ItemRepositoryImplTest.allSixWritePathsGoThroughAtomicDao` 拦下 ——
 *    那条测试就是为此存在的。
 *
 * ## 结构：`@Transaction` 壳 + `*InTransaction` 编排核心
 *
 * 生产实现里 `@Transaction` 由 Room 在编译期生成（真回滚）；而**单测里没有 Room**，
 * 于是上面那个 `@Transaction` 具体方法**在单测中只是一层普通调用**（不会回滚）。
 * 这意味着「把三条写原语都 override 成内存实现」的办法**验证不了原子性** ——
 * 恰好复刻了 S1 潜伏的机制（行为全绿、性质已破）。
 *
 * ⇒ 把语义拆成两层：
 *
 * | 层 | 方法 | 生产（Room） | 单测 |
 * |---|---|---|---|
 * | 事务壳 | 三个 `@Transaction` 具体方法 | 真事务 | **被子类 override**，塞进内存事务 |
 * | 编排核心 | 三个 `*InTransaction` protected 方法 | 被壳调用 | 被子类原样继承（业务编排只有一份） |
 *
 * 两个原语 [upsertCipher] / [upsertPendingOp] 是落点的**语义接口**：
 * 单测只需把它们指向内存 Map，就能测到"半途失败会不会整体回滚"。
 */
@Dao
abstract class AtomicWriteDao {

    /** 与 [CipherDao.upsertAll] 同款注解（`@Upsert` = 有则覆盖、无则插入）。 */
    @Upsert
    protected abstract suspend fun upsertCipher(row: CipherEntity)

    /** 与 [PendingOpDao.enqueue] 同款注解。 */
    @Upsert
    protected abstract suspend fun upsertPendingOp(op: PendingOpEntity)

    /** 与 [CipherDao.deleteByIds] 同口径。 */
    @Query("DELETE FROM ciphers WHERE id IN (:ids)")
    protected abstract suspend fun deleteCiphers(ids: List<String>)

    /**
     * ★ 落库 + 入队，**原子**。
     *
     * 两者要么都成功，要么都不生效 —— 杜绝上面描述的「行在、队列不在」中间态。
     * 新增 / 编辑 / 软删除 / 恢复**都用这一个入口**（区别只在行内容）。
     */
    @Transaction
    open suspend fun upsertCipherAndEnqueue(row: CipherEntity, op: PendingOpEntity) =
        upsertCipherAndEnqueueInTransaction(row, op)

    /**
     * ★ 删行 + 入队，**原子**（永久删除单个条目）。
     *
     * 为什么删除也需要原子：这里的危险方向与 upsert 相反 ——
     * 若「行删了、队列没记」，服务端会保留该条目，下次同步 `persistCiphers`
     * 又把它**拉回来**（用户以为永久删了，结果复活）。
     * 而「队列记了、行没删」只是回收站里多留一行，用户可重试，危害小得多。
     */
    @Transaction
    open suspend fun deleteCipherAndEnqueue(cipherId: String, op: PendingOpEntity) =
        deleteCipherAndEnqueueInTransaction(cipherId, op)

    /**
     * ★ 批量删行 + 批量入队，**原子**（回收站到期清理）。
     *
     * 语义与 [deleteCipherAndEnqueue] 相同，只是作用于一批。
     * 一次性把整批行删除 + 整批队列写入包在**同一个**事务里 ——
     * 而不是每行一个事务，避免"清到一半崩了"留下半清理状态。
     */
    @Transaction
    open suspend fun deleteCiphersAndEnqueue(ids: List<String>, ops: List<PendingOpEntity>) =
        deleteCiphersAndEnqueueInTransaction(ids, ops)

    /**
     * [upsertCipherAndEnqueue] 的**编排核心**：不含事务注解，只负责调用顺序。
     *
     * 拆出来的理由见类 KDoc：单测要能**替掉事务壳**、但**沿用这份编排**。
     * 若把编排抄进测试的 override 里，将来生产代码改了顺序、测试还按旧顺序跑，
     * 就会"测试绿、真实行为不同"—— 拆分就是为了杜绝这种漂移。
     */
    protected open suspend fun upsertCipherAndEnqueueInTransaction(row: CipherEntity, op: PendingOpEntity) {
        upsertCipher(row)
        upsertPendingOp(op)
    }

    /** [deleteCipherAndEnqueue] 的编排核心（同上）。 */
    protected open suspend fun deleteCipherAndEnqueueInTransaction(cipherId: String, op: PendingOpEntity) {
        deleteCiphers(listOf(cipherId))
        upsertPendingOp(op)
    }

    /** [deleteCiphersAndEnqueue] 的编排核心（同上）。 */
    protected open suspend fun deleteCiphersAndEnqueueInTransaction(ids: List<String>, ops: List<PendingOpEntity>) {
        deleteCiphers(ids)
        ops.forEach { upsertPendingOp(it) }
    }
}
