/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.mapper

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.LoginDto
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden Cipher 与领域模型 [VaultItem] 的双向映射（Docs/02）。
 *
 * 方向说明：
 * - [toDomain]：密文 DTO → 明文领域模型，**必须传入账号对称密钥**；
 * - [toRequest]：明文领域模型 → 密文请求体，落库/上传前加密。
 *
 * ⚠️ 条目独立密钥（per-item key）：官方客户端 / 网页新建的条目，其字段用**条目
 * 自身密钥**加密（`CipherDto.key` = 该密钥被账号密钥包裹的 EncString），与老数据
 * （直接用账号密钥加密）并存。真机库中两者混存（2026-09-08 抓库确认，219 条中
 * 31 条带 key），因此解密必须：先解包 `key`（若存在）得到条目密钥 → 用条目密钥
 * 解字段；失败回退账号密钥；都不行才降级空串。个别损坏条目不抛异常（列表可浏览
 * 优先，与既有降级策略一致）。
 */
@Singleton
class CipherMapper @Inject constructor(
    private val crypto: VaultixCrypto,
) {

    fun toDomain(
        dto: CipherDto,
        key: SymmetricCryptoKey,
    ): VaultItem {
        // 条目独立密钥：解包后与账号密钥同构；用毕即清
        val itemKey = resolveItemKey(dto, key)
        return try {
            VaultItem(
                id = dto.id,
                title = decryptToString(dto.name, key, itemKey),
                username = decryptToString(dto.login?.username, key, itemKey),
                password = decryptToString(dto.login?.password, key, itemKey),
                notes = decryptToString(dto.notes, key, itemKey),
                type = mapType(dto.type),
            )
        } finally {
            itemKey?.clear()
        }
    }

    fun toDomainList(
        dtos: List<CipherDto>,
        key: SymmetricCryptoKey,
    ): List<VaultItem> = dtos.map { dto -> toDomain(dto, key) }

    fun toRequest(
        item: VaultItem,
        key: SymmetricCryptoKey,
    ): CipherRequest = CipherRequest(
        type = mapTypeToInt(item.type),
        name = crypto.encryptString(item.title, key),
        notes = item.notes.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
        login = LoginDto(
            username = item.username.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
            password = item.password.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
        ),
    )

    /**
     * 解包条目独立密钥；null = 该条目直接用账号密钥加密。
     * 解包失败也按 null 处理（回退账号密钥 + 空串兜底）。
     */
    private fun resolveItemKey(dto: CipherDto, accountKey: SymmetricCryptoKey): SymmetricCryptoKey? {
        val wrapped = dto.key ?: return null
        return runCatching { crypto.decryptSymmetricKey(wrapped, accountKey) }.getOrNull()
    }

    /**
     * 解密单字段：条目密钥优先 → 账号密钥回退 → 空串降级（不抛异常）。
     */
    private fun decryptToString(
        encString: String?,
        accountKey: SymmetricCryptoKey,
        itemKey: SymmetricCryptoKey?,
    ): String {
        if (encString.isNullOrBlank()) return ""
        if (itemKey != null) {
            runCatching { crypto.decryptToString(encString, itemKey) }
                .getOrNull()?.let { return it }
        }
        return runCatching { crypto.decryptToString(encString, accountKey) }
            .getOrDefault("")
    }

    private fun mapType(type: Int): VaultItemType = when (type) {
        TYPE_LOGIN -> VaultItemType.Login
        TYPE_SECURE_NOTE -> VaultItemType.SecureNote
        TYPE_CARD -> VaultItemType.Card
        TYPE_IDENTITY -> VaultItemType.Identity
        else -> VaultItemType.Login
    }

    private fun mapTypeToInt(type: VaultItemType): Int = when (type) {
        VaultItemType.Login -> TYPE_LOGIN
        VaultItemType.SecureNote -> TYPE_SECURE_NOTE
        VaultItemType.Card -> TYPE_CARD
        VaultItemType.Identity -> TYPE_IDENTITY
    }

    private companion object {
        const val TYPE_LOGIN = 1
        const val TYPE_SECURE_NOTE = 2
        const val TYPE_CARD = 3
        const val TYPE_IDENTITY = 4
    }
}
