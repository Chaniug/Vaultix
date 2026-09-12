/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * KeePass 条目/分组 → Vaultix 领域模型（[VaultItem] / [VaultFolder]）。
 *
 * 保真度取向（Docs/02「保真度三级」）：本阶段（M2 阶段 A，只读）**只做无损 + 约定承载**，
 * 不做有损猜测：
 *   - 无损：Title / UserName / Password / URL / Notes / 自定义字段（含是否受保护）/
 *     分组归属 / TOTP / 二进制附件数量（只统计，不读内容）；
 *   - 约定承载：TOTP 统一**转成 `otpauth://` URI** 后交给既有 `OtpUriParser` 解析
 *     （KeePass 侧字段名有 `otp` / `TimeOtp-Secret-Base32` / `TOTP Seed` 等多种约定，
 *     这里都认，转换集中在本文件，不污染 UI）；
 *   - 有损：**类型**（KDBX 没有 Bitwarden 的 type 概念）。本阶段一律映射为 Login，
 *     仅当「无用户名、无密码、有备注」时映射为 SecureNote（KeePass 里安全笔记的写法）。
 *     其余有损字段在 `VaultCapabilities` 登记（阶段 B 随写回一起补）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import java.util.UUID

/** 把已解密的 kotpass 数据库映射为 Vaultix 领域模型。 */
internal fun KeePassDatabase.toMappedContent(): KdbxMappedContent {
    val root = content.group
    val meta = content.meta
    val recycleBinUuid = meta.recycleBinUuid
    val items = mutableListOf<VaultItem>()
    val folders = mutableListOf<VaultFolder>()
    val groupPaths = mutableMapOf<UUID, String>()
    var recycleBinCount = 0

    fun walk(group: Group, parentPath: String, inRecycleBin: Boolean) {
        val isRoot = group.uuid == root.uuid
        // 根分组是**数据库根**、不是用户建的文件夹：根的直属子分组才是顶层文件夹，
        // 因此根的路径为空串（否则每个文件夹都会带上「库名/」前缀）。
        val currentPath = when {
            isRoot -> ""
            parentPath.isEmpty() -> group.name
            else -> "$parentPath/${group.name}"
        }
        groupPaths[group.uuid] = currentPath
        // 回收站整棵子树都不进列表（对齐 Bitwarden 的 deletedDate 语义：回收站是独立视图）。
        val nowInRecycleBin = inRecycleBin || (!isRoot && isRecycleBin(group, meta, recycleBinUuid))
        if (!isRoot && !nowInRecycleBin) {
            folders += VaultFolder(id = folderIdOf(group.uuid), name = currentPath)
        }
        group.entries.forEach { entry ->
            if (nowInRecycleBin) {
                recycleBinCount++
            } else {
                items += entry.toVaultItem(
                    folderId = if (isRoot) null else folderIdOf(group.uuid),
                )
            }
        }
        group.groups.forEach { child -> walk(child, currentPath, nowInRecycleBin) }
    }

    walk(root, parentPath = "", inRecycleBin = false)
    return KdbxMappedContent(
        items = items,
        folders = folders,
        recycleBinCount = recycleBinCount,
        groupPaths = groupPaths,
    )
}

/** 是否位于回收站：优先按 meta 的 recycleBinUuid，其次按分组名兜底（老库常没设 meta）。 */
private fun isRecycleBin(group: Group, meta: Meta, recycleBinUuid: UUID?): Boolean {
    if (meta.recycleBinEnabled && recycleBinUuid != null) return group.uuid == recycleBinUuid
    return group.name.lowercase() in RECYCLE_BIN_NAMES
}

private val RECYCLE_BIN_NAMES = setOf("recyclebin", "recycle bin", "trash", "回收站")

private fun Entry.toVaultItem(folderId: String?): VaultItem {
    // ⚠️ `fields.title` 等是 `EntryValue?`（不是 String）：取值必须过 `.content`
    // —— 它对受保护字段（Protected="True"）会自动解密，是 kotpass 提供的唯一明文入口。
    val title = fields.title?.content.orEmpty()
    val username = fields.userName?.content.orEmpty()
    val password = fields.password?.content.orEmpty()
    val notes = fields.notes?.content.orEmpty()
    val url = fields.url?.content.orEmpty()
    val custom = customFieldsOf(fields)
    val isNote = username.isBlank() && password.isBlank() && notes.isNotBlank()
    // 通行密钥（KeePassDX 的 KPEX_PASSKEY_* 约定，见 KdbxPasskeyCodec）：映射为领域凭证后
    // 与 Bitwarden 侧同构 —— 「通行密钥」页 / 设置页统计 / 凭据提供商三条链路对 KDBX 库天然可用。
    val passkey = KdbxPasskeyCodec.toCredential(fields.toPasskeyFields(), title = title)
    return VaultItem(
        id = itemIdOf(uuid),
        title = title.ifBlank { DEFAULT_TITLE },
        username = username,
        password = password,
        notes = notes,
        type = if (isNote) VaultItemType.SecureNote else VaultItemType.Login,
        uris = url.takeIf { it.isNotBlank() }?.let { listOf(VaultUri(uri = it)) }.orEmpty(),
        totp = KdbxTotpCodec.toOtpAuthUri(fields.toOtpFields(), title = title, account = username),
        fido2Credentials = listOfNotNull(passkey),
        customFields = custom,
        folderId = folderId,
    )
}

/** KeePass 字段 → OTP 字段集（键名大小写不敏感；同时判定密钥的编码形态）。 */
private fun EntryFields.toOtpFields(): KdbxOtpFields {
    fun value(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> this[key]?.content?.takeIf { it.isNotBlank() } }.orEmpty()

    // KeePass 2.47+ 用三个不同字段名表达密钥编码；先判编码再取密钥，
    // 否则「Hex 密钥」会被当成 base32 直接算错码（详见 KdbxTotpCodec 的说明）。
    val secretEncoding = when {
        value(KdbxTotpCodec.FIELD_TIMEOTP_HEX).isNotEmpty() -> KdbxOtpFields.SecretEncoding.Hex
        value(KdbxTotpCodec.FIELD_TIMEOTP_BASE64).isNotEmpty() -> KdbxOtpFields.SecretEncoding.Base64
        else -> KdbxOtpFields.SecretEncoding.Base32
    }
    val seed = when (secretEncoding) {
        KdbxOtpFields.SecretEncoding.Base32 ->
            value(KdbxTotpCodec.FIELD_TOTP_SEED, KdbxTotpCodec.FIELD_TIMEOTP_BASE32)

        KdbxOtpFields.SecretEncoding.Hex -> value(KdbxTotpCodec.FIELD_TIMEOTP_HEX)
        KdbxOtpFields.SecretEncoding.Base64 -> value(KdbxTotpCodec.FIELD_TIMEOTP_BASE64)
    }
    return KdbxOtpFields(
        otp = value(KdbxTotpCodec.FIELD_OTP),
        seed = seed,
        settings = value(KdbxTotpCodec.FIELD_TOTP_SETTINGS),
        period = value(KdbxTotpCodec.FIELD_TOTP_PERIOD, KdbxTotpCodec.FIELD_TIMEOTP_PERIOD),
        digits = value(KdbxTotpCodec.FIELD_TOTP_DIGITS, KdbxTotpCodec.FIELD_TIMEOTP_LENGTH),
        algorithm = value(KdbxTotpCodec.FIELD_TOTP_ALGORITHM, KdbxTotpCodec.FIELD_TIMEOTP_ALGORITHM),
        counter = value(KdbxTotpCodec.FIELD_HOTP_COUNTER),
        type = value(KdbxTotpCodec.FIELD_OTP_TYPE),
        secretEncoding = secretEncoding,
    )
}

/** KeePass 字段 → 通行密钥字段集。 */
private fun EntryFields.toPasskeyFields(): KdbxPasskeyFields = KdbxPasskeyFields(
    username = this[KdbxPasskeyCodec.FIELD_USERNAME]?.content.orEmpty(),
    privateKeyPem = this[KdbxPasskeyCodec.FIELD_PRIVATE_KEY]?.content.orEmpty(),
    credentialId = this[KdbxPasskeyCodec.FIELD_CREDENTIAL_ID]?.content.orEmpty(),
    userHandle = this[KdbxPasskeyCodec.FIELD_USER_HANDLE]?.content.orEmpty(),
    relyingParty = this[KdbxPasskeyCodec.FIELD_RELYING_PARTY]?.content.orEmpty(),
    flagBe = this[KdbxPasskeyCodec.FIELD_FLAG_BE]?.content.orEmpty(),
    flagBs = this[KdbxPasskeyCodec.FIELD_FLAG_BS]?.content.orEmpty(),
)

private const val DEFAULT_TITLE = "（未命名）"

/**
 * 自定义字段：除 5 个标准字段、OTP 相关字段、通行密钥字段外的全部字段。
 *
 * 受保护（`Protected="True"`）的字段映射为 [CustomFieldType.Hidden]，其余为 Text
 * ——与 Bitwarden 的 `fields[].type` 语义对齐（Hidden 在 UI 上默认掩码）。
 *
 * ⚠️ OTP 与通行密钥字段**必须排除**：它们在领域模型里已有专属载体
 * （`VaultItem.totp` / `fido2Credentials`），再以自定义字段出现一次就会出现
 * 「详情页明文展示私钥 PEM / TOTP 密钥」这种既重复又泄密的展示。
 * 排除判定统一走两个码本的 `isXxxFieldName`，不在本文件再抄一份字段名清单（抄一份就会漂移）。
 */
private fun customFieldsOf(fields: EntryFields): List<VaultCustomField> =
    fields.entries
        .filter { (key, _) ->
            key !in STANDARD_FIELD_KEYS &&
                !KdbxTotpCodec.isOtpFieldName(key) &&
                !KdbxPasskeyCodec.isPasskeyFieldName(key)
        }
        .mapNotNull { (key, value) ->
            if (key.isBlank()) {
                null
            } else {
                VaultCustomField(
                    name = key,
                    value = value.content,
                    type = if (value is EntryValue.Encrypted) CustomFieldType.Hidden else CustomFieldType.Text,
                )
            }
        }

private val STANDARD_FIELD_KEYS = setOf("Title", "UserName", "Password", "URL", "Notes")

