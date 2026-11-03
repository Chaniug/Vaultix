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
import io.vaultix.model.VaultAttachment
import io.vaultix.model.VaultAttachmentSource
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultPasswordHistoryEntry
import io.vaultix.model.VaultReprompt
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri
import java.time.Instant
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
    // 库内二进制：`meta.binaries` 是 `hash -> BinaryData`，名字不在这里 —— 名字挂在
    // 条目的 `BinaryReference` 上（KDBX 4 按内容哈希去重，同名不同内容会是两条）。
    // 这里收集"hash -> 名字"只为**展示**；字节在导出时按 hash 现取（见 Kdbx.binaryOf）。
    val binaryNames = linkedMapOf<String, String>()

    // 自定义图标的字节按 UUID 存一份（条目只带 id）：同一定义常被多条共用，
    // 放进 `VaultItem` 就是 N 份副本。见 `KdbxCustomIconStore`。
    meta.customIcons.forEach { (uuid, icon) -> KdbxCustomIconStore.put(uuid.toString(), icon.data) }

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
                    item = entry.toVaultItem(folderId = folderId, binaryNames = binaryNames),
                    lastModifiedAtMillis = entry.times?.lastModificationTime?.toEpochMilli(),
                )
            } else {
                items += entry.toVaultItem(folderId = folderId, binaryNames = binaryNames)
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
        binaryNames = binaryNames,
        rootGroupUuid = root.uuid,
    )
}

/** 是否位于回收站：优先按 meta 的 recycleBinUuid，其次按分组名兜底（老库常没设 meta）。 */
private fun isRecycleBin(group: Group, meta: Meta, recycleBinUuid: UUID?): Boolean {
    if (meta.recycleBinEnabled && recycleBinUuid != null) return group.uuid == recycleBinUuid
    return group.name.lowercase() in RECYCLE_BIN_NAMES
}

private val RECYCLE_BIN_NAMES = setOf("recyclebin", "recycle bin", "trash", "回收站")

private fun Entry.toVaultItem(
    folderId: String?,
    binaryNames: MutableMap<String, String> = mutableMapOf(),
): VaultItem {
    // ⚠️ `fields.title` 等是 `EntryValue?`（不是 String）：取值必须过 `.content`
    // —— 它对受保护字段（Protected="True"）会自动解密，是 kotpass 提供的唯一明文入口。
    val title = fields.title?.content.orEmpty()
    val username = fields.userName?.content.orEmpty()
    val password = fields.password?.content.orEmpty()
    val notes = fields.notes?.content.orEmpty()
    val url = fields.url?.content.orEmpty()
    val custom = customFieldsOf(fields)
    // 安全笔记类型判定（A4 · 2026-10-16）：显式标记优先，缺失才回落启发式。
    // 此前纯启发式 ⇒ 「空备注的安全笔记」三字段全空 ⇒ 存盘再读回**静默翻成登录**。
    // ⚠️ 三态语义：`secureNoteMarker()` 返回 null 表示"没有标记"（回落启发式，
    //    兼容第三方条目），而不是"标记为非笔记"——二者不可压成一个布尔（#121 同款事故）。
    val isNote = fields.secureNoteMarker()
        ?: (username.isBlank() && password.isBlank() && notes.isNotBlank())
    // URI（W4）：标准 `Url` + `VPX_URL_n` 合回一个列表。此前只读第一条，
    // 「一条登录挂 3 个网址」在KDBX 侧会**只显示一个**（往返即丢数据）。
    val uris = fields.allUris(url)
    // ★ 载荷区（2026-11-01）：卡 / 身份 / SSH / 密码历史 / 二次验证。
    //   这五块此前**读侧整块没有** ⇒ 写侧不敢写（写了也读不回来，等于静默丢）。
    //   现在两侧成对接上，Bitwarden ⇄ KDBX 的这三类条目才真的"无损"。
    val payload = fields.payloadTable()
    // 时间戳的毫秒余数：`<Times>` 在加密 v4 里只有整秒，补存见 `KdbxToolFields.TIME_MS`。
    val millisRemainder = KdbxToolFields.decodeTimeMillis(
        fields.valueByKey(KdbxToolFields.TIME_MS),
    )
    val card = payload.toVaultCard()
    val identity = payload.toVaultIdentity()
    val sshKey = payload.toVaultSshKey()
    // 类型判定（2026-11-01 顺序化）：先看载荷，再看显式标记，最后才回落启发式。
    // ⚠️ 顺序**不能**反过来：一条银行卡条目若卡号为空（用户只填了卡名），
    //    启发式会把它判成安全笔记（无账号密码、备注可能有），从而丢掉 `card` 载荷 ——
    //    下次转换回 Bitwarden 就变成一条笔记。载荷存在本身就是**最强证据**。
    val type = when {
        card != null -> VaultItemType.Card
        identity != null -> VaultItemType.Identity
        sshKey != null -> VaultItemType.SshKey
        isNote -> VaultItemType.SecureNote
        else -> VaultItemType.Login
    }
    // 通行密钥（KeePassDX 的 KPEX_PASSKEY_* 约定，见 KdbxPasskeyCodec）：映射为领域凭证后
    // 与 Bitwarden 侧同构 —— 「通行密钥」页 / 设置页统计 / 凭据提供商三条链路对 KDBX 库天然可用。
    return VaultItem(
        id = itemIdOf(uuid),
        title = title.ifBlank { DEFAULT_TITLE },
        username = username,
        password = password,
        notes = notes,
        type = type,
        uris = uris,
        totp = KdbxTotpCodec.toOtpAuthUri(fields.toOtpFields(), title = title, account = username),
        fido2Credentials = fields.passkeyCredentials(title),
        card = card,
        identity = identity,
        sshKey = sshKey,
        passwordHistory = payload.toPasswordHistory(),
        reprompt = if (payload.toRepromptFlag()) VaultReprompt.Password else VaultReprompt.None,
        customFields = custom,
        folderId = folderId,
        // 收藏（A1 · 2026-10-13）：KDBX 无标准键 ⇒ `VPX_FAVORITE` 工具字段，
        // 缺失即非收藏。此前整块没读 ⇒ KDBX 库的星标是**静默 no-op**：
        // 表单里点了星、退出再进星没了，"收藏"快捷筛选对 KDBX 库恒为空。
        favorite = fields.favoriteFlag(),
        // 2026-09-30 补入：KDBX 的 `<Times>`（此前**整块没读** ⇒ 详情页无从显示时间）。
        // ⚠️ 取值是 `Instant?`：老库 / 第三方工具写的条目可能缺 `CreationTime`，
        //    `?.toEpochMilli()` 之后仍是 null ⇒ UI 不显示该行（而不是兜一个默认时间）。
        // ⚠️ `lastAccessTime` 刻意**不映射**：它的语义是"上次查看"，KeePass 每开一次都会刷新
        //    （`Group.modifyEntry` 里就会写），把它当"最近修改"显示会**天天变**、完全误导。
        //    用户要的"最近修改"= `lastModificationTime`。
        // ⚠️ 毫秒余数（2026-11-20）：加密 v4 文件的 `<Times>` 只存**整秒**（见
        //    `KdbxToolFields.TIME_MS` 的 KDoc），`toEpochMilli()` 必然是 `...000`。
        //    转换器写过的条目额外有 `VPX_TIME_MS`，把秒以下余数补回来；
        //    KDBX 原生条目没有这个键 ⇒ 保持整秒（解码失败也保持整秒，绝不归零）。
        createdAt = times?.creationTime?.withRemainder(millisRemainder?.first),
        updatedAt = times?.lastModificationTime?.withRemainder(millisRemainder?.second),
        // 2026-10-21 补入：附件元信息。此前 **整块没读** ⇒ 库里挂着附件的条目在界面上
        // 完全看不出来（底层数据完好，只是没人展示），用户会以为"附件丢了"。
        //
        // ⚠️ **只做"可见"，暂不做"导出"** —— 不是偷懒，是 kotpass 0.13.0 的结构限制：
        //    `Entry.binaries` 只是 `BinaryReference`（hash + name），字节在
        //    `Meta.binaries` 里，而**那个属性是 `@PublishedApi internal`**（已用 javap
        //    核对字节码），从模块外拿不到；`KeePassDatabase` 也没有任何公开的
        //    "按 hash 取二进制"的扩展函数。⇒ 想导出只能反射，那在密码库里不可接受。
        //    解锁路径见 `.ai/issues/05` 的对应条目（升级 kotpass / 上游加公开 API）。
        // ⚠️ 名字优先取引用上的 `name`（KDBX 允许同一个二进制在不同条目上叫不同名字），
        //    没有才回落到库里记的名字，再没有就给占位名 —— **不丢这一条**。
        attachments = binaries.mapNotNull { ref ->
            val hash = ref.hash.hex()
            val name = ref.name?.takeIf { it.isNotBlank() }
                ?: binaryNames[hash]
                ?: KDBX_FALLBACK_ATTACHMENT_NAME
            binaryNames[hash] = name
            VaultAttachment(
                id = hash,
                fileName = name,
                // ⚠️ 尺寸不在这里算：要拿到 `BinaryData.content` 就得解压/解密整份附件，
                //    读列表时做这件事等于"打开库就把所有附件读一遍"。导出时再报真实大小。
                size = 0L,
                source = VaultAttachmentSource.Kdbx,
            )
        },
        // 自定义图标（2026-11-03）：**只带 UUID**，字节在 `KdbxCustomIconStore` 里。
        // ⚠️ `customIconUuid` 为 null 表示"没设"，与预定义图标（`entry.icon`）是两回事：
        //    前者是用户上传的 PNG，后者是 KeePass 的 69 个内置图标。两条都保留语义。
        customIconId = customIconUuid?.toString(),
    )
}

// ================================================================ 载荷区读回（2026-11-01）

/**
 * 全部字段转成「键 → 明文」表（**受保护字段已解密**）。
 *
 * ⚠️ 载荷区解析必须走 `.content`：卡号 / CVV / SSN 在写侧是 `EntryValue.Encrypted`，
 *   直接 `toString()` 拿到的是 `Encrypted{...}` 字面量（正是"看起来读到了其实是乱码"那类坑）。
 */
private fun EntryFields.payloadTable(): Map<String, String> =
    entries.associate { (key, value) -> key to value.content }

/**
 * 载荷表 → [VaultCard]；**一个非空字段都没有就返回 null**（= 不是银行卡条目）。
 *
 * ⚠️ 判据是"有没有任何一个字段被写过"，不是"卡号是否非空"：用户完全可能只记了
 *   持卡人姓名与品牌，卡号留给别处；那也是一条银行卡条目，不该被降级成笔记。
 */
private fun Map<String, String>.toVaultCard(): VaultCard? {
    // ⚠️ 两套前缀都要认：`card_*` 是 KeePassXC 的 Bitwarden 导入器写的（生态标准），
    //    `VPX_CARD_*` 是 2026-11-01 之前本应用写的（旧库不能读丢）。
    val payload = KdbxToolFields.payloadOf(
        this, KdbxToolFields.CARD_PREFIX, KdbxToolFields.LEGACY_CARD_PREFIX,
    )
    if (payload.isEmpty()) return null
    val values = KdbxToolFields.orderedValues(payload, KdbxToolFields.cardKeys())
    if (values.all { it.isEmpty() }) return null
    return VaultCard(
        cardholderName = values[0],
        brand = values[1],
        number = values[2],
        expMonth = values[3],
        expYear = values[4],
        code = values[5],
    )
}

/**
 * 载荷表 → [VaultIdentity]；判据同 [toVaultCard]。
 *
 * ⚠️ KeePassXC 的 Bitwarden 导入器会**额外**写两个合并字段（`BitwardenReader.cpp`
 *   `writeIdentity` 段，本应用照抄成读侧兼容）：
 *   - `identity_name` = `title + " " + firstName + " " + middleName + " " + lastName`
 *     （空格拼接、跳过空段；**分段字段同时也写了**，所以这里只在分段全空时兜底）
 *   - `identity_address` = 多行地址（`address1\naddress2\naddress3\n城市, 州 邮编\n国家`）
 *   只认 18 个分段键的读法会让这两个字段**静默漏在自定义字段区**：用户从 KeePassXC
 *   迁过来，看到一条「身份」条目下面挂着两行莫名其妙的 `identity_address`。
 */
private fun Map<String, String>.toVaultIdentity(): VaultIdentity? {
    val payload = KdbxToolFields.payloadOf(
        this, KdbxToolFields.IDENTITY_PREFIX, KdbxToolFields.LEGACY_IDENTITY_PREFIX,
    )
    if (payload.isEmpty()) return null
    val values = KdbxToolFields.orderedValues(payload, KdbxToolFields.identityKeys())
    if (values.all { it.isEmpty() }) return null
    val merged = mergeKeePassXcIdentityName(payload, values)
    val address = kxCascadeAddress(payload, merged)
    return VaultIdentity(
        title = merged[0],
        firstName = merged[1],
        middleName = merged[2],
        lastName = merged[3],
        address1 = address[0],
        address2 = address[1],
        address3 = address[2],
        city = address[3],
        state = address[4],
        postalCode = address[5],
        country = address[6],
        company = merged[11],
        email = merged[12],
        phone = merged[13],
        ssn = merged[14],
        username = merged[15],
        passportNumber = merged[16],
        licenseNumber = merged[17],
    )
}

/**
 * 分段姓名全空时，把 `identity_name` 摊回 title / firstName / middleName / lastName。
 *
 * ⚠️ 只做**兜底**（分段有值就一律用分段）：`identity_name` 是空格拼接的，
 *   各段边界在合并后就**不可逆**了 —— 拿它去覆盖分段值等于用有损数据盖掉无损数据。
 *   摊不回去时（例如 `"Zhang San"`，分不清名/姓）整体塞进 `title`，**至少不丢字**。
 */
private fun mergeKeePassXcIdentityName(
    payload: Map<String, String>,
    values: List<String>,
): List<String> {
    if (values.slice(IDX_TITLE..IDX_LAST_NAME).any { it.isNotBlank() }) return values
    val merged = payload.entries
        .firstOrNull { (key, _) -> key.equals("name", ignoreCase = true) }
        ?.value?.trim().orEmpty()
    if (merged.isEmpty()) return values
    val parts = merged.split(' ').filter { it.isNotBlank() }
    val out = values.toMutableList()
    // 词数 → 归属：1 词进 title（Bitwarden 的 title 是称谓、也可能是名字本身，不分更好）；
    // 2 词最像「名 姓」；3 词最像「名 中间名 姓」；再多就分不清了，整体进 title（不丢字）。
    when (parts.size) {
        NAME_PARTS_GIVEN_ONLY -> out[IDX_TITLE] = merged
        NAME_PARTS_GIVEN_FAMILY -> {
            out[IDX_FIRST_NAME] = parts.readName(0)
            out[IDX_LAST_NAME] = parts.readName(1)
        }
        NAME_PARTS_WITH_MIDDLE -> {
            out[IDX_FIRST_NAME] = parts.readName(0)
            out[IDX_MIDDLE_NAME] = parts.readName(1)
            out[IDX_LAST_NAME] = parts.readName(2)
        }
        else -> out[IDX_TITLE] = merged
    }
    return out
}

/** 取第 [index] 段（缺失给空串，不抛异常）。 */
private fun List<String>.readName(index: Int): String = getOrNull(index).orEmpty()

/** `identity_name` 摊回时可能的词数（1 = 只有一个词，2 = 名+姓，3 = 名+中间名+姓）。 */
private const val NAME_PARTS_GIVEN_ONLY = 1
private const val NAME_PARTS_GIVEN_FAMILY = 2
private const val NAME_PARTS_WITH_MIDDLE = 3

/**
 * 把 KeePassXC 的 `identity_address` 多行块摊回 address1..country（**仅在该段全空时**）。
 *
 * 行序按 `BitwardenReader.cpp` 的写侧对称：
 * `address1` / `address2` / `address3` / `"城市, 州 邮编"` / `国家`（空行被跳过）。
 * 行数对不上时**逐行塞进 address1..3 与 city** —— 宁可错位也不吞掉内容。
 */
private fun kxCascadeAddress(payload: Map<String, String>, values: List<String>): List<String> {
    val address = values.slice(IDX_ADDRESS1..IDX_COUNTRY)
    if (address.any { it.isNotBlank() }) return address
    val block = payload.entries
        .firstOrNull { (key, _) -> key.equals("address", ignoreCase = true) }
        ?.value?.trim().orEmpty()
    val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return address
    val out = address.toMutableList()
    // 最后一行算「国家」的条件：至少 3 行地址 + 1 行城市 + 1 行国家。
    val hasCountry = lines.size >= KX_ADDRESS_MIN_LINES_WITH_COUNTRY
    if (hasCountry) out[ADDR_COUNTRY] = lines.last()
    // 前 3 行进 address1..3；第 4 行起是「城市, 州 邮编」。
    val body = if (hasCountry) lines.dropLast(1) else lines
    body.take(KX_ADDRESS_STREET_LINES).forEachIndexed { index, line -> out[index] = line }
    if (body.size > KX_ADDRESS_STREET_LINES) {
        val overflow = body.drop(KX_ADDRESS_STREET_LINES)
        val tokens = overflow.first().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        out[ADDR_CITY] = tokens.firstOrNull().orEmpty()
        val rest = tokens.getOrNull(1).orEmpty().split(' ').filter { it.isNotBlank() }
        // 「州 邮编」：最后一段是邮编，前面的是州（美国州名可能两词）。
        out[ADDR_STATE] =
            if (rest.size >= 2) rest.dropLast(1).joinToString(" ") else rest.joinToString(" ")
        out[ADDR_POSTAL_CODE] = if (rest.size >= 2) rest.last() else ""
        // 多出来的行接在 address3 后面（保留内容而不是丢弃）。
        val extra = overflow.drop(1).joinToString("\n").trim()
        if (extra.isNotEmpty()) {
            out[ADDR_ADDRESS3] = listOf(out[ADDR_ADDRESS3], extra)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
    }
    return out
}

/**
 * 地址段**局部**下标（0..6，对应 `address1`…`country`）。
 *
 * ⚠️ 与 [IDX_CITY] 等 18 键全局下标**不是一回事**：`slice(4..10)` 之后列表只有 7 项，
 *   混用会在运行期 `IndexOutOfBoundsException` —— 而且只在"有 `identity_address` 合并块"
 *   这条很少走的路径上炸，单测不覆盖就发不现。
 */
private const val ADDR_ADDRESS3 = 2
private const val ADDR_CITY = 3
private const val ADDR_STATE = 4
private const val ADDR_POSTAL_CODE = 5
private const val ADDR_COUNTRY = 6

/** 身份 18 键里的下标（避免满屏魔法数字；顺序由 `IDENTITY_KEYS` 锁定）。 */
private const val IDX_TITLE = 0
private const val IDX_FIRST_NAME = 1
private const val IDX_MIDDLE_NAME = 2
private const val IDX_LAST_NAME = 3
private const val IDX_ADDRESS1 = 4
private const val IDX_ADDRESS2 = 5
private const val IDX_ADDRESS3 = 6
private const val IDX_CITY = 7
private const val IDX_STATE = 8
private const val IDX_POSTAL_CODE = 9
private const val IDX_COUNTRY = 10

/** `identity_address` 里最多几行是街道/门牌（对齐 `BitwardenReader.cpp` 的 address1..3）。 */
private const val KX_ADDRESS_STREET_LINES = 3

/** 行数达到这个值才把最后一行当国家（3 街道 + 1 城市 + 1 国家）。 */
private const val KX_ADDRESS_MIN_LINES_WITH_COUNTRY = 5

/** 载荷表 → [VaultSshKey]；判据同 [toVaultCard]。 */
private fun Map<String, String>.toVaultSshKey(): VaultSshKey? {
    val payload = KdbxToolFields.payloadOf(this, KdbxToolFields.SSH_PREFIX)
    if (payload.isEmpty()) return null
    val values = KdbxToolFields.orderedValues(payload, KdbxToolFields.sshKeys())
    if (values.all { it.isEmpty() }) return null
    return VaultSshKey(privateKey = values[0], publicKey = values[1], keyFingerprint = values[2])
}

/**
 * 密码历史：`VPX_PW_HISTORY_<n>` 按 `n` **数值升序**（不是字符串序）。
 *
 * ⚠️ 字符串序会把 `_10` 排到 `_2` 前面 —— 顺序错了不会报错，只是"历史里第 7 条
 *   与第 8 条调了个儿"，用户几乎不可能发现。
 */
private fun Map<String, String>.toPasswordHistory(): List<VaultPasswordHistoryEntry> =
    entries
        .mapNotNull { (key, value) ->
            if (!key.startsWith(KdbxToolFields.PW_HISTORY_PREFIX, ignoreCase = true)) return@mapNotNull null
            val index = key.drop(KdbxToolFields.PW_HISTORY_PREFIX.length).toIntOrNull()
                ?: return@mapNotNull null
            val (stamp, password) = KdbxToolFields.decodeHistory(value)
            index to VaultPasswordHistoryEntry(password = password, lastUsedAt = stamp)
        }
        .sortedBy { it.first }
        .map { it.second }

/** `VPX_REPROMPT` 视为真 ⇒ 需要主密码二次验证（宽松真值，同收藏）。 */
private fun Map<String, String>.toRepromptFlag(): Boolean {
    val raw = entries
        .firstOrNull { (key, _) -> key.equals(KdbxToolFields.REPROMPT, ignoreCase = true) }
        ?.value
    return KdbxToolFields.isFavoriteTrue(raw)
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

/**
 * `VPX_FAVORITE` → 布尔（A1）。缺失 / 空值 = 非收藏。
 *
 * ⚠️ 键名比对**大小写不敏感**：kotpass 的 `EntryFields.get` 是大小写敏感的
 * `Map.get`（同 [KdbxToolFields.extras] 的已知坑），用户用别的工具改过大小写
 * 时也必须读得回来。真值判定走 [KdbxToolFields.isFavoriteTrue]（唯一口径）。
 */
private fun EntryFields.favoriteFlag(): Boolean =
    KdbxToolFields.isFavoriteTrue(
        entries.firstOrNull { (key, _) ->
            key.equals(KdbxToolFields.FAVORITE, ignoreCase = true)
        }?.value?.content,
    )

/**
 * `VPX_SECURE_NOTE` 工具字段 → 三态类型标记（A4 · 2026-10-16）。
 *
 * - `true`  = 显式安全笔记（写侧只产出 `"true"`，见 [KdbxToolFields.SECURE_NOTE_TRUE]）
 * - `false` = 标记显式为假值（用户把安全笔记**改成**了登录型，回落会误判，必须信标记）
 * - `null`  = 键不存在（第三方条目 / 老库）⇒ 调用方回落内容启发式
 *
 * ⚠️ 键名比对**大小写不敏感**（kotpass `EntryFields.get` 大小写敏感的已知坑，同 [favoriteFlag]）。
 * 真值判定走 [KdbxToolFields.isSecureNoteTrue]（唯一口径，宽松认 true/1/yes）。
 */
private fun EntryFields.secureNoteMarker(): Boolean? {
    val raw = entries.firstOrNull { (key, _) ->
        key.equals(KdbxToolFields.SECURE_NOTE, ignoreCase = true)
    }?.value?.content ?: return null
    return KdbxToolFields.isSecureNoteTrue(raw)
}

private const val DEFAULT_TITLE = "（未命名）"

/**
 * 附件名未知时的占位名。
 *
 * 为什么不直接跳过这条附件：`BinaryReference.name` 在 KDBX 规范里是**可选**的
 * （早期 KeePass 只写 hash），但这些字节确实挂在条目上 —— 跳过就是"用户看不到自己的文件"。
 */
private const val KDBX_FALLBACK_ATTACHMENT_NAME = "（附件）"

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
                // 类型还原（2026-11-01）：KDBX 只有「明文 / 保护」两态，装不下 Bitwarden 的
                // Boolean / Linked。缺了这一步，`Boolean` 会静默变成一行 "true" 的 Text，
                // `Linked` 的 `linkedId` 直接消失 —— 值都在，只有类型换了，
                // 所以"只比值不比类型"的测试永远抓不到。
                //
                // ⚠️ 优先级：**元数据 > 值形态**。受保护字段默认判 Hidden，但它也可能是
                //    一个被用户标了保护的 Boolean —— 有 meta 时以 meta 为准。
                val meta = KdbxToolFields.decodeFieldMeta(
                    fields.metaOf(key),
                )
                val byShape = if (value is EntryValue.Encrypted) {
                    CustomFieldType.Hidden
                } else {
                    CustomFieldType.Text
                }
                VaultCustomField(
                    name = key,
                    value = value.content,
                    type = meta?.first ?: byShape,
                    linkedId = meta?.second,
                )
            }
        }

/**
 * 按**完整键名**取一个字段的明文值（键名大小写不敏感）。
 *
 * 走 `EntryFields` 而不是转成 Map，是为了保住 `EntryValue.content` 的解密能力：
 * 这类工具字段本身恒为明文，但用同一入口取更不容易被后来的人写错。
 */
private fun EntryFields.valueByKey(fieldKey: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(fieldKey, ignoreCase = true) }?.value?.content

/** 取某自定义字段的类型元数据（`VPX_FIELD_META_<字段名>`，见 [valueByKey]）。 */
private fun EntryFields.metaOf(fieldName: String): String? =
    valueByKey(KdbxToolFields.fieldMetaKey(fieldName))

/**
 * KDBX 的整秒时间 + 我们补存的毫秒余数 = 原始毫秒时间戳。
 *
 * @param remainder 秒以下的毫秒（0..999）；null = 没有余数记录（KDBX 原生条目）⇒ 保持整秒。
 * ⚠️ 余数为 null 时**不能**当"错误"处理，也不能归零：绝大多数条目（第三方工具写的）
 *   本来就没有这个键，能给出整秒已经是完整信息。
 */
private fun Instant.withRemainder(remainder: Long?): Long =
    toEpochMilli() + (remainder ?: 0L)
