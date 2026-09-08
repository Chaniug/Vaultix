/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.crypto.VaultixCrypto
import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.entity.FolderEntity
import io.vaultix.domain.FolderRepository
import io.vaultix.model.VaultFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件夹读取实现。
 *
 * 与 [ItemRepositoryImpl.observeItems] 同一链路形态：Room 密文快照 ×
 * 解锁状态 → 会话密钥解密。解密在注入的 crypto 调度器上执行；
 * 个别损坏的名称跳过（列表可浏览优先），未解锁返回空列表。
 */
@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: FolderDao,
    private val sessions: VaultSessionManager,
    private val crypto: VaultixCrypto,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : FolderRepository {

    override fun observeFolders(vaultId: String): Flow<List<VaultFolder>> =
        combine(folderDao.observeByVault(vaultId), sessions.unlockedIds) { rows, unlocked ->
            rows to (vaultId in unlocked)
        }
            .map { (rows, isUnlocked) ->
                if (!isUnlocked) emptyList() else decodeAll(vaultId, rows)
            }
            .flowOn(cryptoDispatcher)

    /** 解密当前快照；名称解密失败或为空白的行跳过。 */
    private suspend fun decodeAll(vaultId: String, rows: List<FolderEntity>): List<VaultFolder> {
        val key = sessions.keyOf(vaultId) ?: return emptyList()
        return rows.mapNotNull { row ->
            runCatching { crypto.decryptToString(row.encryptedName, key) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { VaultFolder(id = row.id, name = it) }
        }
    }
}
