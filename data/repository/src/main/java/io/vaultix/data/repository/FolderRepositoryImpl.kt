/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.crypto.VaultixCrypto
import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.FolderEntity
import io.vaultix.domain.FolderRepository
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
 *
 * ## 2026-10-13 起 KDBX 库也走这里（A2）
 *
 * 此前本类**只绑 Room** ⇒ KDBX 库的分组整体不可见：表单的文件夹下拉框恒为空、
 * 「按文件夹分组」视图里所有条目都落"无文件夹"、JSON 导出丢分组、
 * 导入时全部落根组。而 `KdbxMappedContent.folders` 其实早就映射好了，
 * 只差一个消费方 —— 于是补上按库类型的分流（读侧判据与
 * [ItemRepositoryImpl] 逐字同构：每次订阅重新解析种类，不引入缓存真相源）。
 */
@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: FolderDao,
    private val vaultDao: VaultDao,
    private val sessions: VaultSessionManager,
    private val kdbxSessions: KdbxSessionFlow,
    private val crypto: VaultixCrypto,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : FolderRepository {

    override fun observeFolders(vaultId: String): Flow<List<VaultFolder>> =
        vaultDao.observeAll()
            .map { vaults -> VaultKind.fromName(vaults.firstOrNull { it.id == vaultId }?.kind) }
            .distinctUntilChanged()
            .flatMapLatest { kind ->
                if (kind == VaultKind.KDBX) {
                    kdbxFolders(vaultId)
                } else {
                    combine(folderDao.observeByVault(vaultId), sessions.unlockedIds) { rows, unlocked ->
                        rows to (vaultId in unlocked)
                    }
                        .map { (rows, isUnlocked) ->
                            if (!isUnlocked) emptyList() else decodeAll(vaultId, rows)
                        }
                        .flowOn(cryptoDispatcher)
                }
            }

    /**
     * KDBX 库的文件夹流（A2 · 2026-10-13）。
     *
     * 会话是纯内存结构（没有可订阅的 Flow）⇒ 同 [ItemRepositoryImpl.kdbxItems]
     * 用 [KdbxSessionFlow] 当触发器：解锁 / 锁定 / 移除库都会 bump 一次，
     * 届时重读会话即可。未解锁时 `contentOf` 返回 null ⇒ 空列表
     * （与 Bitwarden 侧"未解锁返回空列表"同口径，不泄露"这个库有几个组"）。
     */
    private fun kdbxFolders(vaultId: String): Flow<List<VaultFolder>> =
        kdbxSessions.asSignal()
            .map { Kdbx.contentOf(vaultId)?.folders.orEmpty() }

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
