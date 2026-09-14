/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 反向映射目标为 Vaultix 统一领域模型（Docs/02）；来源结构对齐 Bitwarden 官方导出行
 * （bitwarden/sdk-internal → crates/bitwarden-exporters/src/json.rs）。
 * 与 CipherMapper 同一约定：**逐字段映射、绝不静默丢字段**（保真度三级）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.export

import io.vaultix.model.CustomFieldType
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultReprompt
import io.vaultix.model.VaultSecureNote
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri

/**
 * 把 Bitwarden 导出条目映射回 Vaultix 领域模型。
 *
 * 导入结果**不含 folder 归属的解析**——调用方需先建立 folder 映射
 * （导出文件的 folderId 是源端 UUID，导入到本库时需重建对应关系）。
 *
 * 未知 `type`（如未来的 bankAccount=6）返回 `null`：宁可丢弃未知类型，
 * 也不猜测映射（避免把银行账户错当登录条目导入）。
 */
internal fun BitwardenExportCipher.toVaultItem(): VaultItem? {
    val itemType = when (type) {
        BitwardenCipherTypeCode.LOGIN -> VaultItemType.Login
        BitwardenCipherTypeCode.SECURE_NOTE -> VaultItemType.SecureNote
        BitwardenCipherTypeCode.CARD -> VaultItemType.Card
        BitwardenCipherTypeCode.IDENTITY -> VaultItemType.Identity
        BitwardenCipherTypeCode.SSH_KEY -> VaultItemType.SshKey
        else -> return null
    }

    return VaultItem(
        id = id,
        title = name,
        username = login?.username.orEmpty(),
        password = login?.password.orEmpty(),
        notes = notes.orEmpty(),
        type = itemType,
        uris = login?.uris.orEmpty().map { it.toVaultUri() },
        totp = login?.totp?.ifEmpty { null },
        fido2Credentials = login?.fido2Credentials.orEmpty().map { it.toVaultFido2() },
        card = card?.toVaultCard(),
        sshKey = sshKey?.toVaultSshKey(),
        identity = identity?.toVaultIdentity(),
        customFields = fields.orEmpty().map { it.toVaultCustomField() },
        folderId = folderId,
        favorite = favorite,
        reprompt = if (reprompt == 1) VaultReprompt.Password else VaultReprompt.None,
        secureNote = if (itemType == VaultItemType.SecureNote) {
            VaultSecureNote(type = secureNote?.type ?: 0)
        } else {
            null
        },
    )
}

/** 网址：`match` 编号 → [UriMatch]；未知编号按 null（基域匹配）处理，不猜测。 */
private fun BitwardenExportUri.toVaultUri(): VaultUri = VaultUri(
    uri = uri.orEmpty(),
    match = when (match) {
        0 -> UriMatch.Domain
        1 -> UriMatch.Host
        2 -> UriMatch.StartsWith
        3 -> UriMatch.Exact
        4 -> UriMatch.RegularExpression
        5 -> UriMatch.Never
        else -> null
    },
)

/** 通行密钥：`counter`/`discoverable` 从字符串还原（官方为字符串形态）。 */
private fun BitwardenExportFido2.toVaultFido2(): VaultFido2Credential = VaultFido2Credential(
    credentialId = credentialId,
    rpId = rpId,
    rpName = rpName.orEmpty(),
    userName = userName.orEmpty(),
    userDisplayName = userDisplayName.orEmpty(),
    userHandle = userHandle,
    keyAlgorithm = keyAlgorithm,
    creationDate = creationDate.ifEmpty { null },
    keyType = keyType,
    keyCurve = keyCurve,
    keyValue = keyValue,
    counter = counter.toLongOrNull() ?: 0L,
    discoverable = discoverable.toBooleanStrictOrNull() ?: true,
)

private fun BitwardenExportCard.toVaultCard(): VaultCard = VaultCard(
    cardholderName = cardholderName.orEmpty(),
    brand = brand.orEmpty(),
    number = number.orEmpty(),
    expMonth = expMonth.orEmpty(),
    expYear = expYear.orEmpty(),
    code = code.orEmpty(),
)

private fun BitwardenExportSshKey.toVaultSshKey(): VaultSshKey = VaultSshKey(
    privateKey = privateKey,
    publicKey = publicKey,
    keyFingerprint = keyFingerprint,
)

private fun BitwardenExportIdentity.toVaultIdentity(): VaultIdentity = VaultIdentity(
    title = title.orEmpty(),
    firstName = firstName.orEmpty(),
    middleName = middleName.orEmpty(),
    lastName = lastName.orEmpty(),
    address1 = address1.orEmpty(),
    address2 = address2.orEmpty(),
    address3 = address3.orEmpty(),
    city = city.orEmpty(),
    state = state.orEmpty(),
    postalCode = postalCode.orEmpty(),
    country = country.orEmpty(),
    company = company.orEmpty(),
    email = email.orEmpty(),
    phone = phone.orEmpty(),
    ssn = ssn.orEmpty(),
    username = username.orEmpty(),
    passportNumber = passportNumber.orEmpty(),
    licenseNumber = licenseNumber.orEmpty(),
)

/** 自定义字段：type 编号 → [CustomFieldType]；越界降级 Text（与官方导入器宽松策略一致）。 */
private fun BitwardenExportField.toVaultCustomField(): VaultCustomField = VaultCustomField(
    name = name.orEmpty(),
    value = value.orEmpty(),
    type = when (type) {
        1 -> CustomFieldType.Hidden
        2 -> CustomFieldType.Boolean
        3 -> CustomFieldType.Linked
        else -> CustomFieldType.Text
    },
    linkedId = linkedId,
)
