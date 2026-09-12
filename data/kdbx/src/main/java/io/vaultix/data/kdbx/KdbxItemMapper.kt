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
    return VaultItem(
        id = itemIdOf(uuid),
        title = title.ifBlank { DEFAULT_TITLE },
        username = username,
        password = password,
        notes = notes,
        type = if (isNote) VaultItemType.SecureNote else VaultItemType.Login,
        uris = url.takeIf { it.isNotBlank() }?.let { listOf(VaultUri(uri = it)) }.orEmpty(),
        totp = buildOtpAuthUri(fields),
        customFields = custom,
        folderId = folderId,
    )
}

private const val DEFAULT_TITLE = "（未命名）"

/**
 * 自定义字段：除 5 个标准字段与 TOTP 相关字段外的全部字段。
 *
 * 受保护（`Protected="True"`）的字段映射为 [CustomFieldType.Hidden]，其余为 Text
 * ——与 Bitwarden 的 `fields[].type` 语义对齐（Hidden 在 UI 上默认掩码）。
 */
private fun customFieldsOf(fields: EntryFields): List<VaultCustomField> =
    fields.entries
        .filter { (key, _) -> key !in STANDARD_FIELD_KEYS && !isOtpFieldKey(key) }
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

// ---- TOTP：把 KeePass 的多种约定统一成 otpauth:// URI ----

/**
 * TOTP 字段的候选键（按优先级）。
 *
 * 来源：KeePass 2.47+ 官方 `TimeOtp-*`、KeePassXC 的 `otp`、
 * 以及 KeePass 1.x/KeePass2Android 时代流传的 `TOTP Seed` / `TOTP Settings`。
 */
private val OTP_SECRET_KEYS = listOf(
    "TimeOtp-Secret-Base32",
    "TimeOtp-Secret-Hex",
    "TimeOtp-Secret-Base64",
    "otp",
    "TOTP Seed",
)

private val OTP_PERIOD_KEYS = listOf("TimeOtp-Period", "TOTP Settings", "period")
private val OTP_DIGITS_KEYS = listOf("TimeOtp-Length", "digits")
private val OTP_ALGORITHM_KEYS = listOf("TimeOtp-Algorithm", "algorithm")

private fun isOtpFieldKey(key: String): Boolean =
    OTP_SECRET_KEYS.any { it.equals(key, ignoreCase = true) } ||
        OTP_PERIOD_KEYS.any { it.equals(key, ignoreCase = true) } ||
        OTP_DIGITS_KEYS.any { it.equals(key, ignoreCase = true) } ||
        OTP_ALGORITHM_KEYS.any { it.equals(key, ignoreCase = true) }

/**
 * 组出 `otpauth://totp/...`（无密钥返回 null）。
 *
 * 复用既有 `OtpUriParser` 的好处：HOTP/TOTP、period/digits/algorithm 的解析、以及
 * 「裸 base32 密钥」的兜底都已实现并测过，KDBX 侧不必再造一套。
 */
private fun buildOtpAuthUri(fields: EntryFields): String? {
    val secret = OTP_SECRET_KEYS.firstNotNullOfOrNull { key ->
        fields[key]?.content?.takeIf { it.isNotBlank() }
    } ?: return null
    val label = fields.title?.content.orEmpty()
    val account = fields.userName?.content.orEmpty()
    val labelText = when {
        label.isBlank() -> account
        account.isBlank() -> label
        else -> "$label:$account"
    }
    val params = buildList {
        add("secret=${encodeQuery(secret)}")
        OTP_PERIOD_KEYS.firstNotNullOfOrNull { fields[it]?.content?.toIntOrNull() }
            ?.let { add("period=$it") }
        OTP_DIGITS_KEYS.firstNotNullOfOrNull { fields[it]?.content?.toIntOrNull() }
            ?.let { add("digits=$it") }
        OTP_ALGORITHM_KEYS.firstNotNullOfOrNull { fields[it]?.content?.takeIf { text -> text.isNotBlank() } }
            ?.let { add("algorithm=${encodeQuery(it)}") }
    }
    return "otpauth://totp/${encodeQuery(labelText)}?${params.joinToString("&")}"
}

/** 最小百分号编码（只处理 URI 里必须转义的字符，够 otpauth 用）。 */
/** 十六进制百分号编码的位宽（detekt MagicNumber）。 */
private const val HEX_PAD_WIDTH = 2

/** 百分号编码使用的十六进制基数（detekt MagicNumber）。 */
private const val HEX_RADIX = 16

private fun encodeQuery(value: String): String = buildString {
    value.forEach { c ->
        when {
            c.isLetterOrDigit() || c in "-._~" -> append(c)
            c == ' ' -> append("%20")
            else -> append('%').append(c.code.toString(HEX_RADIX).uppercase().padStart(HEX_PAD_WIDTH, '0'))
        }
    }
}
