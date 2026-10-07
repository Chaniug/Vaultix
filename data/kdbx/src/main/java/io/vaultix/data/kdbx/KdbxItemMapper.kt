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
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import java.util.UUID

/** 把已解密的 kotpass 数据库映射为 Vaultix 领域模型。 */
internal fun KeePassDatabase.toMappedContent(): KdbxMappedContent {
    val root = content.group
    val meta = content.meta
    val recycleBinUuid = meta.recycleBinUuid
    val items = mutableListOf<VaultItem>()
    val trash = mutableListOf<KdbxTrashItem>()
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
            val folderId = if (isRoot) null else folderIdOf(group.uuid)
            if (nowInRecycleBin) {
                recycleBinCount++
                // 回收站条目也要映射（施工单 S5）：回收站页与"恢复"都要靠它。
                // ⚠️ 时间取 lastModificationTime —— KDBX 没有独立的删除时间，
                //    详见 KdbxTrashItem 的 KDoc（**不许当成删除时间展示**）。
                trash += KdbxTrashItem(
                    item = entry.toVaultItem(folderId = folderId),
                    lastModifiedAtMillis = entry.times?.lastModificationTime?.toEpochMilli(),
                )
            } else {
                items += entry.toVaultItem(folderId = folderId)
            }
        }
        group.groups.forEach { child -> walk(child, currentPath, nowInRecycleBin) }
    }

    walk(root, parentPath = "", inRecycleBin = false)
    return KdbxMappedContent(
        items = items,
        folders = folders,
        recycleBinCount = recycleBinCount,
        trashItems = trash,
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
    // URI（W4）：标准 `Url` + `VPX_URL_n` 合回一个列表。此前只读第一条，
    // 「一条登录挂 3 个网址」在KDBX 侧会**只显示一个**（往返即丢数据）。
    val uris = fields.allUris(url)
    // 通行密钥（KeePassDX 的 KPEX_PASSKEY_* 约定，见 KdbxPasskeyCodec）：映射为领域凭证后
    // 与 Bitwarden 侧同构 —— 「通行密钥」页 / 设置页统计 / 凭据提供商三条链路对 KDBX 库天然可用。
    return VaultItem(
        id = itemIdOf(uuid),
        title = title.ifBlank { DEFAULT_TITLE },
        username = username,
        password = password,
        notes = notes,
        type = if (isNote) VaultItemType.SecureNote else VaultItemType.Login,
        uris = uris,
        totp = KdbxTotpCodec.toOtpAuthUri(fields.toOtpFields(), title = title, account = username),
        fido2Credentials = fields.passkeyCredentials(title),
        customFields = custom,
        folderId = folderId,
        // 2026-09-30 补入：KDBX 的 `<Times>`（此前**整块没读** ⇒ 详情页无从显示时间）。
        // ⚠️ 取值是 `Instant?`：老库 / 第三方工具写的条目可能缺 `CreationTime`，
        //    `?.toEpochMilli()` 之后仍是 null ⇒ UI 不显示该行（而不是兜一个默认时间）。
        // ⚠️ `lastAccessTime` 刻意**不映射**：它的语义是"上次查看"，KeePass 每开一次都会刷新
        //    （`Group.modifyEntry` 里就会写），把它当"最近修改"显示会**天天变**、完全误导。
        //    用户要的"最近修改"= `lastModificationTime`。
        createdAt = times?.creationTime?.toEpochMilli(),
        updatedAt = times?.lastModificationTime?.toEpochMilli(),
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

/**
 * KeePass 字段 → 通行密钥凭证**列表**（W2：支持 `_n` 后缀的多凭证）。
 *
 * 拆解走 [KdbxPasskeyCodec.groupPasskeyFields]：第 0 条不带后缀、其余按 `_n` 升序，
 * 与 bw2keepass 落法一致 —— 此前只读得出第一条，
 * 「一条登录挂 2 个通行密钥」会**静默丢凭证**（施工单 §1.3 末）。
 */
private fun EntryFields.passkeyCredentials(title: String): List<VaultFido2Credential> {
    val raw = entries
        .filter { (key, _) -> KdbxPasskeyCodec.isPasskeyFieldName(key) }
        .associate { (key, value) -> key to value.content }
    return KdbxPasskeyCodec.groupPasskeyFields(raw).mapNotNull { group ->
        KdbxPasskeyCodec.toCredential(KdbxPasskeyCodec.fromFieldMap(group), title = title)
    }
}

private const val DEFAULT_TITLE = "（未命名）"

/**
 * 全部 URI（W4 读方向）：标准 `Url` + `VPX_URL_1..n` + 包名字段合回一个列表。
 *
 * ⚠️ 三条来源的**顺序**即写侧的对称：标准 `Url` 在前、`VPX_URL_1..n` 按下标升序在后、
 *   应用 URI 追加在末。顺序变了不会报错，但自动填充会按用户看到的第一条去匹配
 *   ⇒ **"最可能对的那个"必须是第一条**，所以顺序本身是数据的一部分。
 *
 * ⚠️ 应用 URI 只在**合出来的列表里一条都没有**时才补（判据是 `none { isAndroidAppUri }`，
 *   不是"标准 `Url` 是否为空"）—— 用户手改过条目、`VPX_URL_1` 里就是一条应用 URI 时，
 *   两个判据会给出不同答案，而"补出一条重复的"症状是详情页同一个包名出现两次。
 *
 * @param standardUrl 标准 `Url` 字段的明文（可能为空）。
 */
private fun EntryFields.allUris(standardUrl: String): List<VaultUri> {
    val all = LinkedHashMap<String, String>()
    entries.forEach { (key, value) -> all[key] = value.content }
    val tool = all.filterKeys { KdbxToolFields.isToolFieldName(it) }
    val out = ArrayList(KdbxToolFields.toUris(standardUrl, tool))
    val appUri = KdbxToolFields.appUriOf(all)
    if (appUri != null && out.none { KdbxToolFields.isAndroidAppUri(it.uri) }) {
        out.add(VaultUri(uri = appUri))
    }
    return out
}

/**
 * 自定义字段：除 5 个标准字段、OTP / 通行密钥 / `VPX_` 工具字段外的全部字段。
 *
 * 受保护（`Protected="True"`）的字段映射为 [CustomFieldType.Hidden]，其余为 Text
 * ——与 Bitwarden 的 `fields[].type` 语义对齐（Hidden 在 UI 上默认掩码）。
 *
 * ⚠️ OTP 与通行密钥字段**必须排除**：它们在领域模型里已有专属载体
 * （`VaultItem.totp` / `fido2Credentials`），再以自定义字段出现一次就会出现
 * 「详情页明文展示私钥 PEM / TOTP 密钥」这种既重复又泄密的展示。
 * 排除判定统一走两个码本的 `isXxxFieldName`，不在本文件再抄一份字段名清单（抄一份就会漂移）。
 *
 * ⚠️⚠️ `VPX_` 工具字段（W4）**同样排除**，理由比前两者更强一层：
 *   1. **重复展示**：`VPX_URL_1` 的值同时进 `uris` 和 `customFields` ⇒ 详情页
 *      同一个网址出现两次（一次在"网址"区、一次在"自定义字段"区）。
 *   2. **两个写通道抢同一个键**：[KdbxItemWriter.applyUris] 产出 `VPX_URL_1`，
 *      [KdbxItemWriter.applyCustomFields] 若也把它当自定义字段写一遍，两条通道
 *      各自判"值没变就不动"——**谁先跑谁说了算**，后跑的还会用错误的类型
 *      （Text / Hidden）覆盖前一个。⚠️ 而 R1 铁律只挡"标准/OTP/通行密钥"，
 *      挡不住工具字段自己撞自己。
 *   ⇒ 与其堵两条通道，不如让 `customFields` 只承载"用户自己的字段"，
 *      工具字段一律走 [allUris] / `VPX_BW_*` 的专属旁路。
 *   （用户仍然能在 KeePassXC / KDBX 编辑器里看到 `VPX_URL_1` —— 黑箱的是
 *   Vaultix 自己的 UI，不是用户的库文件。W1 里"工具字段要可见"的诉求指后者。）
 */
private fun customFieldsOf(fields: EntryFields): List<VaultCustomField> =
    fields.entries
        .filter { (key, _) -> !KdbxFieldKeys.isReserved(key) && !KdbxToolFields.isToolFieldName(key) }
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
