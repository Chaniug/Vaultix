/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.database.dao.CollectionDao
import io.vaultix.database.dao.OrganizationDao
import io.vaultix.database.entity.CollectionEntity
import io.vaultix.database.entity.OrganizationEntity
import io.vaultix.domain.OrganizationRepository
import io.vaultix.model.VaultCollection
import io.vaultix.model.VaultOrganization
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 组织 / 集合读取实现（2026-10-21，P1 组织视图）。
 *
 * ## 三层钥匙（与条目解密同构）
 *
 * ```
 * 账号密钥（会话里）--解开 profile.organizations[i].key--> 组织密钥
 * 组织密钥 --解开 organizations[i].name--> 组织名（明文）
 * 组织密钥 --解开 collections[i].name--> 集合名（明文）
 * ```
 *
 * 组织密钥**每次读都重新解包**（不缓存跨调用）：组织数量是个位数、每次读只发生在
 * 列表重组时（Flow 本身有 distinctUntilChanged 语义），省下的那点算力不值得
 * 引入"缓存密钥什么时候该清"这个新问题。
 *
 * ## ⚠️ 解不出来时的降级，必须可区分
 *
 * - 组织密钥解不开（没密钥 / 密钥不对）⇒ **仍然返回这条组织**，名字用占位文案；
 *   否则 UI 上组织整块消失，用户会以为"共享库里的东西全丢了"。
 * - 名字解不开 ⇒ 同样保留，用占位名。
 * 两者都在不泄露任何异常细节的前提下留一条 debug 日志。
 */
@Singleton
class OrganizationRepositoryImpl @Inject constructor(
    private val organizationDao: OrganizationDao,
    private val collectionDao: CollectionDao,
    private val sessions: VaultSessionManager,
    private val crypto: VaultixCrypto,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : OrganizationRepository {

    override fun observeOrganizations(vaultId: String): Flow<List<VaultOrganization>> =
        combine(
            organizationDao.observeByVault(vaultId),
            sessions.unlockedIds,
        ) { rows, unlocked -> rows to (vaultId in unlocked) }
            .combine(collectionDao.observeByVault(vaultId)) { (rows, unlocked), _ ->
                if (!unlocked) emptyList<VaultOrganization>() else decodeOrganizations(vaultId, rows)
            }
            .flowOn(cryptoDispatcher)

    override fun observeCollections(vaultId: String): Flow<List<VaultCollection>> =
        combine(
            collectionDao.observeByVault(vaultId),
            sessions.unlockedIds,
            organizationDao.observeByVault(vaultId),
        ) { collectionRows, unlocked, orgRows ->
            if (vaultId !in unlocked) {
                emptyList()
            } else {
                decodeCollections(vaultId, collectionRows, orgRows)
            }
        }.flowOn(cryptoDispatcher)

    private suspend fun decodeOrganizations(
        vaultId: String,
        rows: List<OrganizationEntity>,
    ): List<VaultOrganization> {
        if (rows.isEmpty()) return emptyList()
        val accountKey = sessions.keyOf(vaultId) ?: return emptyList()
        return rows.map { row ->
            val orgKey = unwrapOrgKey(row, accountKey)
            try {
                VaultOrganization(
                    id = row.id,
                    name = orgKey
                        ?.let { decryptName(row.encryptedName, it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: FALLBACK_ORGANIZATION_NAME,
                )
            } finally {
                orgKey?.clear()
            }
        }
    }

    private suspend fun decodeCollections(
        vaultId: String,
        rows: List<CollectionEntity>,
        orgRows: List<OrganizationEntity>,
    ): List<VaultCollection> {
        if (rows.isEmpty()) return emptyList()
        val accountKey = sessions.keyOf(vaultId) ?: return emptyList()
        // 组织密钥按 orgId 解一次，供该组织下所有集合复用（一个组织几十个集合是常态）。
        val orgKeys = HashMap<String, SymmetricCryptoKey?>()
        try {
            return rows.map { row ->
                val orgKey = orgKeys.getOrPut(row.organizationId) {
                    orgRows.firstOrNull { it.id == row.organizationId }
                        ?.let { unwrapOrgKey(it, accountKey) }
                }
                VaultCollection(
                    id = row.id,
                    organizationId = row.organizationId,
                    name = orgKey
                        ?.let { decryptName(row.encryptedName, it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: FALLBACK_COLLECTION_NAME,
                )
            }
        } finally {
            orgKeys.values.forEach { it?.clear() }
        }
    }

    /** 账号密钥 → 组织密钥；解不开返回 null（不抛：一条坏组织不该拖垮整个列表）。 */
    private fun unwrapOrgKey(
        row: OrganizationEntity,
        accountKey: SymmetricCryptoKey,
    ): SymmetricCryptoKey? {
        val wrapped = row.wrappedKey?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { crypto.decryptSymmetricKey(wrapped, accountKey) }.getOrNull()
    }

    /** 解一个 EncString 名字；失败返回 null（由调用方给占位名）。 */
    private fun decryptName(enc: String?, key: SymmetricCryptoKey): String? {
        if (enc.isNullOrBlank()) return null
        return runCatching { crypto.decryptToString(enc, key) }.getOrNull()
    }

    private companion object {
        /**
         * 占位名（**不丢条目**优于显示空行）。
         * 与 CipherMapper 的附件占位名同一取舍，文案不进 strings.xml：
         * 它属于数据层的降级常量，不是 UI 文案（UI 层的组织名由数据层给）。
         */
        const val FALLBACK_ORGANIZATION_NAME = "（未命名组织）"
        const val FALLBACK_COLLECTION_NAME = "（未命名集合）"
    }
}
