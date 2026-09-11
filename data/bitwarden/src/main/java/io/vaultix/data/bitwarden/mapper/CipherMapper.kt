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
import io.vaultix.data.bitwarden.model.CustomFieldDto
import io.vaultix.data.bitwarden.model.Fido2CredentialDto
import io.vaultix.data.bitwarden.model.IdentityDto
import io.vaultix.data.bitwarden.model.LoginDto
import io.vaultix.data.bitwarden.model.SecureNoteDto
import io.vaultix.data.bitwarden.model.SshKeyDto
import io.vaultix.data.bitwarden.model.UriDto
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
                // 身份信息：type=4 时映射全量 17 字段（解密失败降级空串，不丢字段）。
                // 对齐 Bitwarden canonical，覆盖 Bastion 仅映射少数字段的兼容缺陷。
                identity = if (dto.type == TYPE_IDENTITY) {
                    dto.identity?.let { mapIdentity(it, key, itemKey) }
                } else {
                    null
                },
                // 自定义字段：name/value 解密；type/linkedId 原样承载（只读展示，写回复用原密文）
                customFields = dto.fields.mapNotNull { f ->
                    val name = decryptToString(f.name, key, itemKey)
                    if (name.isBlank() && decryptToString(f.value, key, itemKey).isBlank()) {
                        null
                    } else {
                        VaultCustomField(
                            name = name,
                            value = decryptToString(f.value, key, itemKey),
                            type = mapCustomFieldType(f.type),
                            linkedId = f.linkedId,
                        )
                    }
                },
                // 2026-09-08 补入：此前这四个字段未读取 → 拉取后文件夹/收藏/
                // 主密码二次验证/安全笔记子类型全部丢失（服务端有，本端内存没有）。
                folderId = dto.folderId,
                favorite = dto.favorite,
                reprompt = mapReprompt(dto.reprompt),
                secureNote = if (dto.type == TYPE_SECURE_NOTE) {
                    dto.secureNote?.let { mapSecureNote(it) }
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
        login = newLogin(item, key),
        card = if (item.type == VaultItemType.Card) item.card?.let { mapCardRequest(it, key) } else null,
        sshKey = if (item.type == VaultItemType.SshKey) item.sshKey?.let { mapSshKeyRequest(it, key) } else null,
        // 身份信息：type=Identity 时整体加密写回（非身份条目不写 identity 段，防类型漂移）
        identity = if (item.type == VaultItemType.Identity) {
            item.identity?.let { mapIdentityRequest(it, key) }
        } else {
            null
        },
        // 2026-09-08 补入：新建时必须带上，否则条目永远落在根目录/未收藏/
        // 不二次验证；安全笔记（type=2）也必须带 secureNote 段，服务端才认。
        folderId = item.folderId,
        favorite = item.favorite,
        reprompt = repromptToInt(item.reprompt),
        secureNote = newSecureNote(item),
        // 自定义字段：由表单决定（可增删改 + 4 种类型），逐字段加密写回
        fields = item.customFields.map { mapCustomFieldRequest(it, key) },
    )

    /** 新建条目的 login 段（非 Login 类型不给该段）。 */
    private fun newLogin(item: VaultItem, key: SymmetricCryptoKey): LoginDto? {
        if (item.type != VaultItemType.Login) return null
        return LoginDto(
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
    }

    /** 新建条目的 secureNote 段：仅 SecureNote 类型，缺省补通用子类型 0。 */
    private fun newSecureNote(item: VaultItem): SecureNoteDto? =
        if (item.type == VaultItemType.SecureNote) {
            item.secureNote?.let { mapSecureNoteRequest(it) } ?: SecureNoteDto()
        } else {
            null
        }

    /**
     * 更新用的**合并上传体**（防数据丢失，Bastion 语义：编辑只覆盖可编辑明文，
     * 未编辑段沿用服务端原密文，绝不整条重写）：
     *
     * - name/notes 用表单明文重新加密；
     * - login 条目的 username/password/uri/totp/fido2Credentials 全部按表单意图重新加密
     *   （[VaultItem.fido2Credentials] 已包含服务端原值，保存流程即通过替换该列表来
     *   新增/删除「绑定到本登录条目的通行密钥」；passwordRevisionDate 原密文保留）；
     * - card / identity / sshKey：本类型条目按表单明文重新加密覆盖（可编辑），
     *   非本类型条目沿用服务端原密文；
     * - custom fields：按表单意图整体写回（可增删改 + 4 种类型）；
     * - secureNote 段：仅 SecureNote 类型写入，其余沿用原值；
     * - 条目独立密钥（per-item key）与本方法无关：保留段直接复用服务端密文，
     *   不重新加密。
     */
    fun toUpdateRequest(
        item: VaultItem,
        stored: CipherDto,
        key: SymmetricCryptoKey,
    ): CipherRequest {
        return CipherRequest(
            type = mapTypeToInt(item.type),
            name = crypto.encryptString(item.title, key),
            notes = item.notes.takeIf { it.isNotBlank() }?.let { crypto.encryptString(it, key) },
            // 2026-09-08：改为按**表单意图**写入（此前固定沿用 stored，等于用户在
            // Vaultix 里根本无法改文件夹 / 收藏 / 主密码二次验证）
            favorite = item.favorite,
            folderId = item.folderId,
            reprompt = repromptToInt(item.reprompt),
            login = overlayLogin(item, stored, key),
            card = overlayCard(item, stored, key),
            identity = overlayIdentity(item, stored, key),
            secureNote = overlaySecureNote(item, stored),
            sshKey = overlaySshKey(item, stored, key),
            // 自定义字段改为按**表单意图**写回（此前固定沿用 stored.fields，
            // 等于用户在 Vaultix 里无法增删改自定义字段）
            fields = item.customFields.map { mapCustomFieldRequest(it, key) },
        )
    }

    /**
     * 登录段：服务端有 login 时按表单明文重新加密**可编辑**字段
     * （username / password / uri / totp），其余字段（如 passwordRevisionDate）
     * 沿用服务端原值。`fido2Credentials` 由 [mergeFido2Credentials] 单独处理 ——
     * 默认原样沿用服务端密文，不重加密。
     */
    private fun overlayLogin(
        item: VaultItem,
        stored: CipherDto,
        key: SymmetricCryptoKey,
    ): LoginDto? {
        val storedLogin = stored.login ?: return null
        return storedLogin.copy(
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
            // fido2Credentials：**默认原样沿用服务端密文**，仅在用户确实改动集合时按
            // credentialId 合并（见 [mergeFido2Credentials] 的 P0 说明）。
            fido2Credentials = mergeFido2Credentials(
                modelList = item.fido2Credentials,
                storedList = storedLogin.fido2Credentials.orEmpty(),
                storedDto = stored,
                key = key,
            ),
        )
    }

    /**
     * 通行密钥集合的合并写回（**默认原样沿用服务端密文**）。
     *
     * ⚠️ **P0 数据破坏事故**（2026-09-11 真机定位；完整审计见
     * `Docs/progress/audit/passkey-keyvalue-destruction.md`）：
     * 此处原为 `item.fido2Credentials.map { mapFido2Request(it, key) }` —— **整表按领域模型
     * 重新加密**。而 [mapFido2Request] 里 `keyValue = encryptOpt(c.keyValue.orEmpty(), key)`
     * 会把空值写成 `null`。两者相乘的后果：**只要模型里某条凭据的 `keyValue` 为空，
     * 一次登录条目更新就把服务端已存的私钥材料覆写成 `null`** —— 官方客户端与其它设备
     * 此后同样无法签名，且**不可恢复**。
     *
     * 本文件其余段（card / identity / sshKey / secureNote）一律 `?: stored.X` 沿用原密文，
     * 只有 login 的 fido2 破例；`decisions.md` 第 38 条、[ItemDetailViewModel] 与
     * [ItemRepository] 的 KDoc、以及 [Fido2CredentialDto] 自身注释都明写「未编辑段沿用
     * 服务端原密文」—— 本函数即把代码拉回该承诺。
     *
     * 合并规则（对齐 Bastion `Fido2CredentialCodec.mergeByCredentialId` 的
     * 「其余既有条目全部保留，避免覆盖丢失」原则）：
     * - model 命中 stored（按 credentialId）→ **沿用 stored 原文，密文一字不改**
     * - stored 中未被命中 → 用户删除，剔除
     * - model 中未命中 stored → 新增，按明文加密
     *
     * **失败一律保守**：任一条 stored 的 credentialId 解不开，或 model 里出现空
     * credentialId ⇒ 直接返回 stored —— 宁可不改，也绝不冒险抹除用户数据。
     */
    private fun mergeFido2Credentials(
        modelList: List<VaultFido2Credential>,
        storedList: List<Fido2CredentialDto>,
        storedDto: CipherDto,
        key: SymmetricCryptoKey,
    ): List<Fido2CredentialDto> {
        if (storedList.isEmpty()) return modelList.map { mapFido2Request(it, key) }
        if (modelList.isEmpty()) return emptyList()

        val itemKey = resolveItemKey(storedDto, key)
        // stored 侧的明文 credentialId；任一条解不开 → 放弃合并（保守）
        val storedIds = try {
            storedList.map { d ->
                decryptToString(d.credentialId, key, itemKey)
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        } finally {
            itemKey?.clear()
        }
        if (storedIds.any { it == null }) return storedList

        val modelIds = modelList.map { it.credentialId.trim() }
        if (modelIds.any { it.isBlank() }) return storedList

        val keep = modelIds.toSet()
        val storedSet = storedIds.filterNotNull().toSet()
        val merged = storedList.filterIndexed { i, _ -> storedIds[i] in keep }.toMutableList()
        modelList
            .filter { it.credentialId.trim() !in storedSet }
            .forEach { merged += mapFido2Request(it, key) }
        return merged
    }

    /** 银行卡段：Card 类型按表单明文重加密；其余类型沿用服务端原密文（防丢载荷）。 */
    private fun overlayCard(
        item: VaultItem,
        stored: CipherDto,
        key: SymmetricCryptoKey,
    ): CardDto? = if (item.type == VaultItemType.Card) {
        item.card?.let { mapCardRequest(it, key) } ?: stored.card
    } else {
        stored.card
    }

    /** 身份段：Identity 类型按表单明文重加密；其余类型沿用服务端原密文。 */
    private fun overlayIdentity(
        item: VaultItem,
        stored: CipherDto,
        key: SymmetricCryptoKey,
    ): IdentityDto? = if (item.type == VaultItemType.Identity) {
        item.identity?.let { mapIdentityRequest(it, key) } ?: stored.identity
    } else {
        stored.identity
    }

    /** SSH 段：SshKey 类型按表单明文重加密；其余类型沿用服务端原密文。 */
    private fun overlaySshKey(
        item: VaultItem,
        stored: CipherDto,
        key: SymmetricCryptoKey,
    ): SshKeyDto? = if (item.type == VaultItemType.SshKey) {
        item.sshKey?.let { mapSshKeyRequest(it, key) } ?: stored.sshKey
    } else {
        stored.sshKey
    }

    /**
     * 安全笔记段：SecureNote 类型按表单子类型写入（缺省补通用子类型 0，
     * 服务端对 type=2 条目期望有该段）；其余类型沿用服务端原值。
     */
    private fun overlaySecureNote(item: VaultItem, stored: CipherDto): SecureNoteDto? =
        if (item.type == VaultItemType.SecureNote) {
            item.secureNote?.let { mapSecureNoteRequest(it) } ?: SecureNoteDto()
        } else {
            stored.secureNote
        }

    /** 服务端 reprompt（0/1）→ 领域枚举；未知值按 [VaultReprompt.None] 降级。 */
    private fun mapReprompt(value: Int): VaultReprompt = when (value) {
        REPROMPT_PASSWORD -> VaultReprompt.Password
        else -> VaultReprompt.None
    }

    /** 领域枚举 → 服务端 reprompt（0/1）。 */
    private fun repromptToInt(value: VaultReprompt): Int = when (value) {
        VaultReprompt.Password -> REPROMPT_PASSWORD
        VaultReprompt.None -> REPROMPT_NONE
    }

    /** 服务端 secureNote 段 → 领域模型（仅子类型号，无需解密）。 */
    private fun mapSecureNote(dto: SecureNoteDto): VaultSecureNote = VaultSecureNote(type = dto.type)

    /** 领域模型 → 服务端 secureNote 段。 */
    private fun mapSecureNoteRequest(note: VaultSecureNote): SecureNoteDto = SecureNoteDto(type = note.type)

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

    /** Bitwarden cipher.fields[i].type（0/1/2/3）→ 领域类型。未知值降级 Text。 */
    private fun mapCustomFieldType(type: Int): CustomFieldType = when (type) {
        TYPE_FIELD_HIDDEN -> CustomFieldType.Hidden
        TYPE_FIELD_BOOLEAN -> CustomFieldType.Boolean
        TYPE_FIELD_LINKED -> CustomFieldType.Linked
        else -> CustomFieldType.Text
    }

    /** 领域类型 → Bitwarden cipher.fields[i].type（0/1/2/3）。 */
    private fun customFieldTypeToInt(type: CustomFieldType): Int = when (type) {
        CustomFieldType.Hidden -> TYPE_FIELD_HIDDEN
        CustomFieldType.Boolean -> TYPE_FIELD_BOOLEAN
        CustomFieldType.Linked -> TYPE_FIELD_LINKED
        CustomFieldType.Text -> TYPE_FIELD_TEXT
    }

    /**
     * 自定义字段 → 上传密文体（逐字段加密）。
     *
     * [VaultCustomField.linkedId] 沿用官方**分段编码**（100 登录 / 300 卡 /
     * 400 身份，见 [io.vaultix.model.VaultLinkedId]），不做任何转换。
     */
    private fun mapCustomFieldRequest(
        field: VaultCustomField,
        key: SymmetricCryptoKey,
    ): CustomFieldDto = CustomFieldDto(
        name = encryptOpt(field.name, key),
        value = encryptOpt(field.value, key),
        type = customFieldTypeToInt(field.type),
        linkedId = field.linkedId,
    )

    private fun matchOf(match: Int?): UriMatch? = when (match) {
        TYPE_URI_MATCH_DOMAIN -> UriMatch.Domain
        TYPE_URI_MATCH_HOST -> UriMatch.Host
        TYPE_URI_MATCH_STARTS_WITH -> UriMatch.StartsWith
        TYPE_URI_MATCH_EXACT -> UriMatch.Exact
        TYPE_URI_MATCH_REGEX -> UriMatch.RegularExpression
        TYPE_URI_MATCH_NEVER -> UriMatch.Never
        else -> null
    }

    private fun matchToInt(match: UriMatch?): Int? = when (match) {
        UriMatch.Domain -> TYPE_URI_MATCH_DOMAIN
        UriMatch.Host -> TYPE_URI_MATCH_HOST
        UriMatch.StartsWith -> TYPE_URI_MATCH_STARTS_WITH
        UriMatch.Exact -> TYPE_URI_MATCH_EXACT
        UriMatch.RegularExpression -> TYPE_URI_MATCH_REGEX
        UriMatch.Never -> TYPE_URI_MATCH_NEVER
        null -> null
    }

    /**
     * 通行密钥密文 → 领域模型。
     *
     * ⚠️ **必须 13 字段全读**：此前只读 8 个，写回时（`mapFido2Request`）缺的字段用默认值顶上，
     * 等于**把服务端已存的密钥材料（keyValue）、签名计数器、可发现性覆盖掉**——
     * 编辑一次条目，官方端/其它设备上的这条通行密钥就再也签不了名（不可逆数据破坏）。
     *
     * [creationDate] 例外：Bitwarden 用**明文** DateTime（不加密），故走 [decryptOrPlain]
     * 兼容两种形态（Vaultix 历史版本可能写成密文）。
     */
    private fun mapFido2(
        list: List<Fido2CredentialDto>,
        accountKey: SymmetricCryptoKey,
        itemKey: SymmetricCryptoKey?,
    ): List<VaultFido2Credential> = list.map { d ->
        // ⚠️ **每个字段都必须 `.trim()`**（2026-09-11 实证，通行密钥「找不到候选」的直接根因）：
        //  - `rpId` 按**精确字符串**与请求比对，多一个前导空格 / 尾随空白即全部失配；
        //  - `credentialId` 用于与 `allowCredentials` 逐字节比对，同理；
        //  - `counter` / `discoverable` 走 `toLongOrNull` / `toBooleanStrictOrNull`，
        //    空白会让解析**静默回落到默认值**（counter=0 / discoverable=true），
        //    默认值本身安全，但会把真实数据悄悄改写。
        // 官方 Bitwarden 客户端读取这些字段时一律 `.trim()`，此处对齐。
        VaultFido2Credential(
            credentialId = decryptToString(d.credentialId, accountKey, itemKey).trim(),
            rpId = decryptToString(d.rpId, accountKey, itemKey).trim(),
            rpName = decryptToString(d.rpName, accountKey, itemKey).trim(),
            userName = decryptToString(d.userName, accountKey, itemKey).trim(),
            userDisplayName = decryptToString(d.userDisplayName, accountKey, itemKey).trim(),
            userHandle = decryptToString(d.userHandle, accountKey, itemKey).trim().takeIf { it.isNotBlank() },
            keyAlgorithm = decryptToString(d.keyAlgorithm, accountKey, itemKey).trim().takeIf { it.isNotBlank() },
            keyType = decryptToString(d.keyType, accountKey, itemKey).trim().takeIf { it.isNotBlank() },
            keyCurve = decryptToString(d.keyCurve, accountKey, itemKey).trim().takeIf { it.isNotBlank() },
            keyValue = decryptToString(d.keyValue, accountKey, itemKey).trim().takeIf { it.isNotBlank() },
            counter = decryptToString(d.counter, accountKey, itemKey).trim().toLongOrNull() ?: 0,
            discoverable = decryptToString(d.discoverable, accountKey, itemKey)
                .trim()
                .takeIf { it.isNotBlank() }
                ?.toBooleanStrictOrNull()
                ?: true,
            creationDate = decryptOrPlain(d.creationDate, accountKey, itemKey).takeIf { it.isNotBlank() },
        )
    }

    /**
     * 兼容解密：明文（Bitwarden 的 creationDate 是可解析 DateTime）原样返回，密文才解密。
     * 判断口径照 Bastion `Fido2CredentialCodec.looksLikeCipherString`（`类型.密文` 前缀）。
     */
    private fun decryptOrPlain(
        raw: String?,
        accountKey: SymmetricCryptoKey,
        itemKey: SymmetricCryptoKey?,
    ): String {
        val value = raw ?: return ""
        val head = value.substringBefore('.')
        val looksLikeCipher = value.contains('.') && head.toIntOrNull() != null
        if (!looksLikeCipher) return value
        return decryptToString(value, accountKey, itemKey)
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
            keyValue = encryptOpt(c.keyValue.orEmpty(), key),
            rpId = encryptOpt(c.rpId, key),
            rpName = encryptOpt(c.rpName, key),
            counter = encryptOpt(c.counter.toString(), key),
            userHandle = encryptOpt(c.userHandle.orEmpty(), key),
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

    /**
     * 身份信息密文 → 领域模型（全量 17 字段，逐字段解密，失败降级空串）。
     * 对齐 Bitwarden `CipherIdentityData`，覆盖 Bastion 仅映射少数字段的兼容缺陷。
     */
    private fun mapIdentity(
        dto: IdentityDto,
        accountKey: SymmetricCryptoKey,
        itemKey: SymmetricCryptoKey?,
    ): VaultIdentity = VaultIdentity(
        title = decryptToString(dto.title, accountKey, itemKey),
        firstName = decryptToString(dto.firstName, accountKey, itemKey),
        middleName = decryptToString(dto.middleName, accountKey, itemKey),
        lastName = decryptToString(dto.lastName, accountKey, itemKey),
        address1 = decryptToString(dto.address1, accountKey, itemKey),
        address2 = decryptToString(dto.address2, accountKey, itemKey),
        address3 = decryptToString(dto.address3, accountKey, itemKey),
        city = decryptToString(dto.city, accountKey, itemKey),
        state = decryptToString(dto.state, accountKey, itemKey),
        postalCode = decryptToString(dto.postalCode, accountKey, itemKey),
        country = decryptToString(dto.country, accountKey, itemKey),
        company = decryptToString(dto.company, accountKey, itemKey),
        email = decryptToString(dto.email, accountKey, itemKey),
        phone = decryptToString(dto.phone, accountKey, itemKey),
        ssn = decryptToString(dto.ssn, accountKey, itemKey),
        username = decryptToString(dto.username, accountKey, itemKey),
        passportNumber = decryptToString(dto.passportNumber, accountKey, itemKey),
        licenseNumber = decryptToString(dto.licenseNumber, accountKey, itemKey),
    )

    /** 领域模型身份信息 → 上传密文体（仅非空字段加密；对齐 Bitwarden identity 载荷）。 */
    private fun mapIdentityRequest(identity: VaultIdentity, key: SymmetricCryptoKey): IdentityDto = IdentityDto(
        title = encryptOpt(identity.title, key),
        firstName = encryptOpt(identity.firstName, key),
        middleName = encryptOpt(identity.middleName, key),
        lastName = encryptOpt(identity.lastName, key),
        address1 = encryptOpt(identity.address1, key),
        address2 = encryptOpt(identity.address2, key),
        address3 = encryptOpt(identity.address3, key),
        city = encryptOpt(identity.city, key),
        state = encryptOpt(identity.state, key),
        postalCode = encryptOpt(identity.postalCode, key),
        country = encryptOpt(identity.country, key),
        company = encryptOpt(identity.company, key),
        email = encryptOpt(identity.email, key),
        phone = encryptOpt(identity.phone, key),
        ssn = encryptOpt(identity.ssn, key),
        username = encryptOpt(identity.username, key),
        passportNumber = encryptOpt(identity.passportNumber, key),
        licenseNumber = encryptOpt(identity.licenseNumber, key),
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
        // 主密码二次验证（cipher.reprompt）
        const val REPROMPT_NONE = 0
        const val REPROMPT_PASSWORD = 1
        const val TYPE_URI_MATCH_DOMAIN = 0
        const val TYPE_URI_MATCH_HOST = 1
        const val TYPE_URI_MATCH_STARTS_WITH = 2
        const val TYPE_URI_MATCH_EXACT = 3
        const val TYPE_URI_MATCH_REGEX = 4
        const val TYPE_URI_MATCH_NEVER = 5
        const val TYPE_FIELD_TEXT = 0
        const val TYPE_FIELD_HIDDEN = 1
        const val TYPE_FIELD_BOOLEAN = 2
        const val TYPE_FIELD_LINKED = 3
        // 通行密钥字段默认值（对齐 Bitwarden login.fido2Credentials）
        const val KEY_TYPE_PUBLIC = "public-key"
        const val KEY_ALGORITHM_ECDSA = "ECDSA"
        const val KEY_CURVE_P256 = "P-256"
    }
}
