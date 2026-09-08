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
import io.vaultix.data.bitwarden.model.CardDto
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.Fido2CredentialDto
import io.vaultix.data.bitwarden.model.LoginDto
import io.vaultix.data.bitwarden.model.SshKeyDto
import io.vaultix.data.bitwarden.model.UriDto
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri
import java.time.Instant
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
            val login = dto.login
            VaultItem(
                id = dto.id,
                title = decryptToString(dto.name, key, itemKey),
                username = decryptToString(login?.username, key, itemKey),
                password = decryptToString(login?.password, key, itemKey),
                notes = decryptToString(dto.notes, key, itemKey),
                type = mapType(dto.type),
                uris = login?.uris?.map { u ->
                    VaultUri(decryptToString(u.uri, key, itemKey), matchOf(u.match))
                }.orEmpty(),
                totp = login?.totp?.let { decryptToString(it, key, itemKey) }
                    .takeIf { !it.isNullOrBlank() },
                fido2Credentials = login?.fido2Credentials
                    ?.let { mapFido2(it, key, itemKey) }.orEmpty(),
                card = if (dto.type == TYPE_CARD) {
                    dto.card?.let { mapCard(it, key, itemKey) }
                } else {
                    null
                },
                sshKey = if (dto.type == TYPE_SSH_KEY) {
                    dto.sshKey?.let { mapSshKey(it, key, itemKey) }
                } else {
                    null
                },
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
        login = if (item.type == VaultItemType.Login) {
            LoginDto(
                username = item.username.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
                password = item.password.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
                uris = item.uris.map { v ->
                    UriDto(
                        uri = v.uri.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
                        match = matchToInt(v.match),
                    )
                },
                totp = item.totp?.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
                // 通行密钥：始终绑定在登录条目上（与 Bitwarden 一致），随条目新建一并加密写回
                fido2Credentials = item.fido2Credentials.map { mapFido2Request(it, key) },
            )
        } else {
            null
        },
        card = if (item.type == VaultItemType.Card) item.card?.let { mapCardRequest(it, key) } else null,
        sshKey = if (item.type == VaultItemType.SshKey) item.sshKey?.let { mapSshKeyRequest(it, key) } else null,
    )

    /**
     * 更新用的**合并上传体**（防数据丢失，Bastion 语义：编辑只覆盖可编辑明文，
     * 未编辑段沿用服务端原密文，绝不整条重写）：
     *
     * - name/notes 用表单明文重新加密；
     * - login 条目的 username/password/uri/totp/fido2Credentials 全部按表单意图重新加密
     *   （[VaultItem.fido2Credentials] 已包含服务端原值，保存流程即通过替换该列表来
     *   新增/删除「绑定到本登录条目的通行密钥」；passwordRevisionDate 原密文保留）；
     * - card/identity/secureNote/sshKey/fields 段原样并入（Vaultix M1 不编辑它们，
     *   但必须随更新请求提交，否则服务端会清空这些载荷）；
     * - 条目独立密钥（per-item key）与本方法无关：保留段直接复用服务端密文，
     *   不重新加密。
     */
    fun toUpdateRequest(
        item: VaultItem,
        stored: CipherDto,
        key: SymmetricCryptoKey,
    ): CipherRequest {
        val storedLogin = stored.login
        val overlaidLogin = if (storedLogin != null) {
            storedLogin.copy(
                username = item.username.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
                password = item.password.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
                // uri/totp：用户可见段，按表单明文重新加密覆盖（item 反映最新意图）
                uris = item.uris.map { v ->
                    UriDto(
                        uri = v.uri.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
                        match = matchToInt(v.match),
                    )
                },
                totp = item.totp?.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
                // fido2Credentials：领域模型已加载全部凭证（含服务端原值），按表单意图完整重加密。
                // 保存流程（新增/删除通行密钥）正是通过替换此处列表实现「绑定到登录条目」。
                fido2Credentials = item.fido2Credentials.map { mapFido2Request(it, key) },
            )
        } else {
            null
        }
        return CipherRequest(
            type = mapTypeToInt(item.type),
            name = crypto.encryptString(item.title, key),
            notes = item.notes.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
            favorite = stored.favorite,
            folderId = stored.folderId,
            reprompt = stored.reprompt,
            login = overlaidLogin,
            card = stored.card,
            identity = stored.identity,
            secureNote = stored.secureNote,
            sshKey = stored.sshKey,
            fields = stored.fields,
        )
    }

    /** 领域类型 → 服务端 type 号（写路径类型守恒校验用）。 */
    fun serverTypeOf(type: VaultItemType): Int = mapTypeToInt(type)

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

    /**
     * type → 领域类型。**未知类型不映射到 Login**（写路径会因类型不守恒被
     * [serverTypeOf] 校验拦截，宁可不编辑也不漂移）；未知类型按 Identity 之外
     * 的通用可读类型处理：此处返回 Login 仅供列表展示通用字段（名称/备注），
     * 真正写回前必须过类型守恒校验。
     */
    private fun mapType(type: Int): VaultItemType = when (type) {
        TYPE_LOGIN -> VaultItemType.Login
        TYPE_SECURE_NOTE -> VaultItemType.SecureNote
        TYPE_CARD -> VaultItemType.Card
        TYPE_IDENTITY -> VaultItemType.Identity
        TYPE_SSH_KEY -> VaultItemType.SshKey
        else -> VaultItemType.Login
    }

    private fun mapTypeToInt(type: VaultItemType): Int = when (type) {
        VaultItemType.Login -> TYPE_LOGIN
        VaultItemType.SecureNote -> TYPE_SECURE_NOTE
        VaultItemType.Card -> TYPE_CARD
        VaultItemType.Identity -> TYPE_IDENTITY
        VaultItemType.SshKey -> TYPE_SSH_KEY
    }

    private fun matchOf(match: Int?): UriMatch? = when (match) {
        TYPE_URI_MATCH_DOMAIN -> UriMatch.Domain
        TYPE_URI_MATCH_HOST -> UriMatch.Host
        TYPE_URI_MATCH_STARTS_WITH -> UriMatch.StartsWith
        TYPE_URI_MATCH_EXACT -> UriMatch.Exact
        TYPE_URI_MATCH_REGEX -> UriMatch.RegularExpression
        else -> null
    }

    private fun matchToInt(match: UriMatch?): Int? = when (match) {
        UriMatch.Domain -> TYPE_URI_MATCH_DOMAIN
        UriMatch.Host -> TYPE_URI_MATCH_HOST
        UriMatch.StartsWith -> TYPE_URI_MATCH_STARTS_WITH
        UriMatch.Exact -> TYPE_URI_MATCH_EXACT
        UriMatch.RegularExpression -> TYPE_URI_MATCH_REGEX
        null -> null
    }

    private fun mapFido2(
        list: List<Fido2CredentialDto>,
        accountKey: SymmetricCryptoKey,
        itemKey: SymmetricCryptoKey?,
    ): List<VaultFido2Credential> = list.map { d ->
        VaultFido2Credential(
            credentialId = decryptToString(d.credentialId, accountKey, itemKey),
            rpId = decryptToString(d.rpId, accountKey, itemKey),
            rpName = decryptToString(d.rpName, accountKey, itemKey),
            userName = decryptToString(d.userName, accountKey, itemKey),
            userDisplayName = decryptToString(d.userDisplayName, accountKey, itemKey),
            userHandle = decryptToString(d.userHandle, accountKey, itemKey).takeIf { it.isNotBlank() },
            keyAlgorithm = decryptToString(d.keyAlgorithm, accountKey, itemKey).takeIf { it.isNotBlank() },
            creationDate = decryptToString(d.creationDate, accountKey, itemKey).takeIf { it.isNotBlank() },
        )
    }

    /** 银行卡密文 → 领域模型（解密失败降级空串）。 */
    private fun mapCard(
        dto: CardDto,
        accountKey: SymmetricCryptoKey,
        itemKey: SymmetricCryptoKey?,
    ): VaultCard = VaultCard(
        cardholderName = decryptToString(dto.cardholderName, accountKey, itemKey),
        brand = decryptToString(dto.brand, accountKey, itemKey),
        number = decryptToString(dto.number, accountKey, itemKey),
        expMonth = decryptToString(dto.expMonth, accountKey, itemKey),
        expYear = decryptToString(dto.expYear, accountKey, itemKey),
        code = decryptToString(dto.code, accountKey, itemKey),
    )

    /** SSH 密钥密文 → 领域模型（解密失败降级空串）。 */
    private fun mapSshKey(
        dto: SshKeyDto,
        accountKey: SymmetricCryptoKey,
        itemKey: SymmetricCryptoKey?,
    ): VaultSshKey = VaultSshKey(
        privateKey = decryptToString(dto.privateKey, accountKey, itemKey),
        publicKey = decryptToString(dto.publicKey, accountKey, itemKey),
        keyFingerprint = decryptToString(dto.keyFingerprint, accountKey, itemKey),
    )

    /** 领域模型银行卡 → 上传密文体（仅非空字段加密）。 */
    private fun mapCardRequest(card: VaultCard, key: SymmetricCryptoKey): CardDto = CardDto(
        cardholderName = encryptOpt(card.cardholderName, key),
        brand = encryptOpt(card.brand, key),
        number = encryptOpt(card.number, key),
        expMonth = encryptOpt(card.expMonth, key),
        expYear = encryptOpt(card.expYear, key),
        code = encryptOpt(card.code, key),
    )

    /**
     * 领域模型通行密钥 → 上传密文体（逐字段加密）。
     *
     * - [VaultFido2Credential.keyType]/[VaultFido2Credential.keyCurve]/[VaultFido2Credential.keyAlgorithm]
     *   有默认值（public-key / P-256 / ECDSA），落库后与 Bitwarden 官方字段一致；
     * - [VaultFido2Credential.creationDate] **不加密**：Bitwarden 期望可解析的 DateTime 形态
     *   （与 Bastion Fido2CredentialCodec 约定一致），故直接以明文写入。
     */
    private fun mapFido2Request(c: VaultFido2Credential, key: SymmetricCryptoKey): Fido2CredentialDto =
        Fido2CredentialDto(
            credentialId = encryptOpt(c.credentialId, key),
            keyType = encryptOpt(c.keyType ?: KEY_TYPE_PUBLIC, key),
            keyAlgorithm = encryptOpt(c.keyAlgorithm ?: KEY_ALGORITHM_ECDSA, key),
            keyCurve = encryptOpt(c.keyCurve ?: KEY_CURVE_P256, key),
            keyValue = encryptOpt(c.keyValue ?: "", key),
            rpId = encryptOpt(c.rpId, key),
            rpName = encryptOpt(c.rpName, key),
            counter = encryptOpt(c.counter.toString(), key),
            userHandle = encryptOpt(c.userHandle ?: "", key),
            userName = encryptOpt(c.userName, key),
            userDisplayName = encryptOpt(c.userDisplayName, key),
            discoverable = encryptOpt(c.discoverable.toString(), key),
            creationDate = c.creationDate ?: Instant.now().toString(),
        )

    /** 领域模型 SSH 密钥 → 上传密文体（仅非空字段加密）。 */
    private fun mapSshKeyRequest(sshKey: VaultSshKey, key: SymmetricCryptoKey): SshKeyDto = SshKeyDto(
        privateKey = encryptOpt(sshKey.privateKey, key),
        publicKey = encryptOpt(sshKey.publicKey, key),
        keyFingerprint = encryptOpt(sshKey.keyFingerprint, key),
    )

    /** 非空明文 → 加密密文；空串返回 null（对应 DTO 的 `= null` 默认）。 */
    private fun encryptOpt(text: String, key: SymmetricCryptoKey): String? =
        text.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) }

    private companion object {
        const val TYPE_LOGIN = 1
        const val TYPE_SECURE_NOTE = 2
        const val TYPE_CARD = 3
        const val TYPE_IDENTITY = 4
        const val TYPE_SSH_KEY = 5
        const val TYPE_URI_MATCH_DOMAIN = 0
        const val TYPE_URI_MATCH_HOST = 1
        const val TYPE_URI_MATCH_STARTS_WITH = 2
        const val TYPE_URI_MATCH_EXACT = 3
        const val TYPE_URI_MATCH_REGEX = 4
        // 通行密钥字段默认值（对齐 Bitwarden login.fido2Credentials）
        const val KEY_TYPE_PUBLIC = "public-key"
        const val KEY_ALGORITHM_ECDSA = "ECDSA"
        const val KEY_CURVE_P256 = "P-256"
    }
}
