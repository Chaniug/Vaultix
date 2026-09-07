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
 * ⚠️ 解密失败**不得抛异常**：服务端可能返回个别损坏条目（或密钥不匹配），
 * 若直接抛出会让整个列表加载失败。这里降级为空串，保证其他条目正常显示。
 */
@Singleton
class CipherMapper @Inject constructor(
    private val crypto: VaultixCrypto,
) {

    fun toDomain(
        dto: CipherDto,
        key: SymmetricCryptoKey,
    ): VaultItem = VaultItem(
        id = dto.id,
        title = dto.name?.let { decryptToString(it, key) }.orEmpty(),
        username = dto.login?.username?.let { decryptToString(it, key) }.orEmpty(),
        notes = dto.notes?.let { decryptToString(it, key) }.orEmpty(),
        type = mapType(dto.type),
    )

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
        ),
    )

    private fun decryptToString(encString: String, key: SymmetricCryptoKey): String =
        runCatching { crypto.decryptToString(encString, key) }.getOrDefault("")

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
