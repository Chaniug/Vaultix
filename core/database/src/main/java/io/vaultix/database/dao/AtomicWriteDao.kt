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
 * （进程被杀 / IO 异常），就会出现「**行在、队列不在**」的状态：
 *
 * - 该行不在 `pendingIds` 里 ⇒ `pruneRemovedRows` 把它当成「服务端已删除」⇒ **删掉**（新建的条目凭空消失）；
 * - 或 `persistCiphers` 用服务端旧版本**覆盖**它（编辑的内容丢失）；
 * - 软删除的条目则会被服务端版本**复活**。
 *
 * ⇒ 两次写必须**同生共死**。跨 DAO 的事务需要数据库级 `withTransaction`，
 *   而 `room-ktx` 在 `core:database` 里是 `implementation`（不向依赖方传递）——
 *   与其让上层为此加依赖，不如把这对写操作收进本 DAO 的一个 `@Transaction` 方法。
 *
 * ⚠️ 本 DAO **只做这一件事**：不要往里加与"行+队列"无关的方法，
 *    否则 `ItemRepositoryImpl` 的写路径会分散到两个地方。
 */
@Dao
abstract class AtomicWriteDao {

    /** 与 [CipherDao.upsertAll] 同款注解（`@Upsert` = 有则覆盖、无则插入）。 */
    @Upsert
    protected abstract suspend fun upsertCipher(row: CipherEntity)

    /** 与 [PendingOpDao.enqueue] 同款注解。 */
    @Upsert
    protected abstract suspend fun upsertPendingOp(op: PendingOpEntity)

    /**
     * ★ 落库 + 入队，**原子**。
     *
     * 两者要么都成功，要么都不生效 —— 杜绝上面描述的「行在、队列不在」中间态。
     */
    @Transaction
    open suspend fun upsertCipherAndEnqueue(row: CipherEntity, op: PendingOpEntity) {
        upsertCipher(row)
        upsertPendingOp(op)
    }
}
