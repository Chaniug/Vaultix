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
 * 映射目标结构对齐 Bitwarden 官方导出器（bitwarden/sdk-internal →
 * crates/bitwarden-exporters/src/{json.rs,models.rs}）。官方 `models.rs` 把
 * `CipherView` 逐类型拆成 `Login`/`Card`/`Identity`/`SecureNote`/`SshKey`，
 * 本文件做同样的拆解，只是源侧为 Vaultix 的 [VaultItem]。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.export

import io.vaultix.model.CustomFieldType
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultReprompt
import io.vaultix.model.VaultUri

/**
 * 把 Vaultix 领域条目映射为 Bitwarden 导出条目。
 *
 * ## 省略规则（与官方逐条对齐，勿凭直觉改）
 * - 非当前 `type` 的载荷字段一律 `null` ⇒ 序列化时整个字段省略；
 * - [VaultItem.customFields] 为空时传 `null` ⇒ 省略 `fields`（官方
 *   `skip_serializing_if = "Vec::is_empty"`）；
 * - `login.uris` / `login.fido2Credentials` **恒传数组**（可空集），官方不省略；
 * - `organizationId` / `collectionIds` / `favorite` / `reprompt` 恒输出。
 *
 * @param item 已解密的领域条目（明文仅在内存，函数不做任何持久化）。
 */
internal fun VaultItem.toExportCipher(): BitwardenExportCipher {
    // 各类型载荷的「非本类型则 null」判定集中在一处，避免主流程被 5 个三元分支撑爆复杂度
    val loginPayload = takeIf { type == VaultItemType.Login }?.toExportLogin()
    val identityPayload = takeIf { type == VaultItemType.Identity }?.toExportIdentity()
    val cardPayload = takeIf { type == VaultItemType.Card }?.toExportCard()
    val secureNotePayload = takeIf { type == VaultItemType.SecureNote }?.toExportSecureNote()
    val sshKeyPayload = takeIf { type == VaultItemType.SshKey }?.toExportSshKey()

    return BitwardenExportCipher(
        id = id,
        folderId = folderId,
        name = title,
        notes = notes.ifEmpty { null },
        type = type.toExportTypeCode(),
        login = loginPayload,
        identity = identityPayload,
        card = cardPayload,
        secureNote = secureNotePayload,
        sshKey = sshKeyPayload,
        favorite = favorite,
        reprompt = reprompt.toExportCode(),
        // 空集合传 null ⇒ 该字段整体省略（官方 skip_serializing_if = Vec::is_empty）
        fields = customFields.takeIf { it.isNotEmpty() }?.map { it.toExportField() },
        // 领域模型暂不承载时间戳与密码历史；字段留空即省略，官方导入器按可选处理。
        passwordHistory = null,
        revisionDate = null,
        creationDate = null,
        deletedDate = null,
    )
}

/** 领域条目类型 → Bitwarden `CipherType` 编号。 */
private fun VaultItemType.toExportTypeCode(): Int = when (this) {
    VaultItemType.Login -> BitwardenCipherTypeCode.LOGIN
    VaultItemType.SecureNote -> BitwardenCipherTypeCode.SECURE_NOTE
    VaultItemType.Card -> BitwardenCipherTypeCode.CARD
    VaultItemType.Identity -> BitwardenCipherTypeCode.IDENTITY
    VaultItemType.SshKey -> BitwardenCipherTypeCode.SSH_KEY
}

/** 重申密码策略 → Bitwarden `RepromptType` 编号（仅需区分「需要」）。 */
private fun VaultReprompt.toExportCode(): Int =
    if (this == VaultReprompt.Password) BitwardenRepromptCode.PASSWORD else BitwardenRepromptCode.NONE

/** 自定义字段 → Bitwarden 导出字段。 */
private fun VaultCustomField.toExportField(): BitwardenExportField = BitwardenExportField(
    name = name,
    value = value,
    type = type.toExportCode(),
    linkedId = linkedId,
)

/** 自定义字段类型 → Bitwarden `FieldType` 编号。 */
private fun CustomFieldType.toExportCode(): Int = when (this) {
    CustomFieldType.Text -> BitwardenFieldTypeCode.TEXT
    CustomFieldType.Hidden -> BitwardenFieldTypeCode.HIDDEN
    CustomFieldType.Boolean -> BitwardenFieldTypeCode.BOOLEAN
    CustomFieldType.Linked -> BitwardenFieldTypeCode.LINKED
}

/** 安全笔记载荷（官方仅一个 `type` 字段，缺省 0 = 普通笔记）。 */
private fun VaultItem.toExportSecureNote(): BitwardenExportSecureNote =
    BitwardenExportSecureNote(type = secureNote?.type ?: BitwardenSecureNoteTypeCode.GENERIC)

/**
 * 登录载荷。
 *
 * ⚠️ `uris` 与 `fido2Credentials` 传空列表而非 null（官方恒输出数组），
 * 且 [VaultItem.fido2Credentials] → [BitwardenExportFido2] 时 `counter`/`discoverable`
 * 必须转成字符串（官方字段类型为 `String`，见 `json.rs` 注释「for parity with web exporter」）。
 */
private fun VaultItem.toExportLogin(): BitwardenExportLogin = BitwardenExportLogin(
    username = username.ifEmpty { null },
    password = password.ifEmpty { null },
    uris = uris.map { it.toExportUri() },
    totp = totp?.ifEmpty { null },
    fido2Credentials = fido2Credentials.map { credential ->
        BitwardenExportFido2(
            credentialId = credential.credentialId,
            keyType = credential.keyType.orEmpty(),
            keyAlgorithm = credential.keyAlgorithm.orEmpty(),
            keyCurve = credential.keyCurve.orEmpty(),
            keyValue = credential.keyValue.orEmpty(),
            rpId = credential.rpId,
            userHandle = credential.userHandle,
            userName = credential.userName.ifEmpty { null },
            counter = credential.counter.toString(),
            rpName = credential.rpName.ifEmpty { null },
            userDisplayName = credential.userDisplayName.ifEmpty { null },
            discoverable = credential.discoverable.toString(),
            creationDate = credential.creationDate.orEmpty(),
        )
    },
)

/**
 * 网址映射。
 *
 * ⚠️ [VaultUri.match] 为 `null` 时**保持 null**（官方 `JsonLoginUri.match: Option<u8>`，
 * null 表示「服务端未指定，按基域匹配」）。不要自作主张填 0——那会改变语义。
 */
private fun VaultUri.toExportUri(): BitwardenExportUri = BitwardenExportUri(
    uri = uri.ifEmpty { null },
    match = match?.toBitwardenCode(),
)

/** 网址匹配规则 → Bitwarden 编号（对齐 `UriMatchType`）。 */
private fun UriMatch.toBitwardenCode(): Int = when (this) {
    UriMatch.Domain -> BitwardenUriMatchCode.DOMAIN
    UriMatch.Host -> BitwardenUriMatchCode.HOST
    UriMatch.StartsWith -> BitwardenUriMatchCode.STARTS_WITH
    UriMatch.Exact -> BitwardenUriMatchCode.EXACT
    UriMatch.RegularExpression -> BitwardenUriMatchCode.REGULAR_EXPRESSION
    UriMatch.Never -> BitwardenUriMatchCode.NEVER
}

/** 卡片载荷（全 6 字段直译，空串转 null）。 */
private fun VaultItem.toExportCard(): BitwardenExportCard {
    val payload = requireNotNull(card) { "VaultItem(type=Card) must carry a card payload" }
    return BitwardenExportCard(
        cardholderName = payload.cardholderName.ifEmpty { null },
        expMonth = payload.expMonth.ifEmpty { null },
        expYear = payload.expYear.ifEmpty { null },
        code = payload.code.ifEmpty { null },
        brand = payload.brand.ifEmpty { null },
        number = payload.number.ifEmpty { null },
    )
}

/** 身份载荷（全 17 字段直译，不做子集裁剪）。 */
private fun VaultItem.toExportIdentity(): BitwardenExportIdentity {
    val payload = requireNotNull(identity) { "VaultItem(type=Identity) must carry an identity payload" }
    return BitwardenExportIdentity(
        title = payload.title.ifEmpty { null },
        firstName = payload.firstName.ifEmpty { null },
        middleName = payload.middleName.ifEmpty { null },
        lastName = payload.lastName.ifEmpty { null },
        address1 = payload.address1.ifEmpty { null },
        address2 = payload.address2.ifEmpty { null },
        address3 = payload.address3.ifEmpty { null },
        city = payload.city.ifEmpty { null },
        state = payload.state.ifEmpty { null },
        postalCode = payload.postalCode.ifEmpty { null },
        country = payload.country.ifEmpty { null },
        company = payload.company.ifEmpty { null },
        email = payload.email.ifEmpty { null },
        phone = payload.phone.ifEmpty { null },
        ssn = payload.ssn.ifEmpty { null },
        username = payload.username.ifEmpty { null },
        passportNumber = payload.passportNumber.ifEmpty { null },
        licenseNumber = payload.licenseNumber.ifEmpty { null },
    )
}

/** SSH 密钥载荷（三字段直译）。官方同为必填字符串。 */
private fun VaultItem.toExportSshKey(): BitwardenExportSshKey {
    val payload = requireNotNull(sshKey) { "VaultItem(type=SshKey) must carry an sshKey payload" }
    return BitwardenExportSshKey(
        privateKey = payload.privateKey,
        publicKey = payload.publicKey,
        keyFingerprint = payload.keyFingerprint,
    )
}
