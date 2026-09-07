/*
 * Vaultix — core:database
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.FolderEntity
import io.vaultix.database.entity.PendingOpEntity
import io.vaultix.database.entity.VaultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vaults ORDER BY createdAt")
    fun observeAll(): Flow<List<VaultEntity>>

    @Query("SELECT * FROM vaults WHERE id = :id")
    suspend fun get(id: String): VaultEntity?

    @Upsert suspend fun upsert(vault: VaultEntity)

    @Query("UPDATE vaults SET revisionDate = :revision WHERE id = :id")
    suspend fun updateRevision(id: String, revision: String?)

    @Query("DELETE FROM vaults WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CipherDao {
    @Query("SELECT * FROM ciphers WHERE vaultId = :vaultId AND deletedDate IS NULL ORDER BY id")
    fun observeByVault(vaultId: String): Flow<List<CipherEntity>>

    /** 回收站行（deletedDate 非空，最近删除在前）。 */
    @Query("SELECT * FROM ciphers WHERE vaultId = :vaultId AND deletedDate IS NOT NULL ORDER BY deletedDate DESC")
    fun observeTrashByVault(vaultId: String): Flow<List<CipherEntity>>

    @Query("SELECT * FROM ciphers WHERE vaultId = :vaultId ORDER BY id")
    suspend fun listByVault(vaultId: String): List<CipherEntity>

    @Query("SELECT * FROM ciphers WHERE id = :id")
    suspend fun get(id: String): CipherEntity?

    /** 单条目流（详情页用；删除/换 id 后自动重发）。 */
    @Query("SELECT * FROM ciphers WHERE id = :id")
    fun observe(id: String): Flow<CipherEntity?>

    @Upsert suspend fun upsertAll(items: List<CipherEntity>)

    @Query("DELETE FROM ciphers WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM ciphers WHERE vaultId = :vaultId")
    suspend fun clearVault(vaultId: String)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE vaultId = :vaultId ORDER BY id")
    fun observeByVault(vaultId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId")
    suspend fun listByVault(vaultId: String): List<FolderEntity>

    @Upsert suspend fun upsertAll(items: List<FolderEntity>)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM folders WHERE vaultId = :vaultId")
    suspend fun clearVault(vaultId: String)
}

@Dao
interface PendingOpDao {
    /** 按入队顺序取待推送操作。 */
    @Query("SELECT * FROM pending_ops WHERE vaultId = :vaultId ORDER BY localId")
    suspend fun listByVault(vaultId: String): List<PendingOpEntity>

    @Upsert suspend fun enqueue(op: PendingOpEntity)

    @Query("DELETE FROM pending_ops WHERE localId = :localId")
    suspend fun remove(localId: Long)

    @Query("UPDATE pending_ops SET retryCount = retryCount + 1 WHERE localId = :localId")
    suspend fun incrementRetry(localId: Long)

    @Query("DELETE FROM pending_ops WHERE vaultId = :vaultId")
    suspend fun clearVault(vaultId: String)
}
