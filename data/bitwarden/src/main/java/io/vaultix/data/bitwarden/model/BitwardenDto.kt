/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 说明：以下为 Bitwarden API 的**传输对象（DTO）**，字段名与服务端 JSON 严格一致。
 * 它们不是领域模型——向 VaultItem 的转换由 Mapper 负责（见 Docs/02 canonical 规范）。
 * 全部字段设为可空或带默认值，配合 ignoreUnknownKeys，确保服务端新增/缺失字段时不崩。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncResponse(
    @SerialName("folders") val folders: List<FolderDto> = emptyList(),
    @SerialName("ciphers") val ciphers: List<CipherDto> = emptyList(),
    /**
     * 组织（2026-10-21）：`[{id, name(EncString), ...}]`。
     *
     * ⚠️ 官方 sync 响应的 `profile.organizations` 与顶层 `organizations` 两处都出现过
     * （客户端版本不同）；Vaultwarden 顶层给。本 DTO 取顶层，缺失即空列表 ——
     * 空列表的语义是"没有组织或服务器没给"，UI 据此整块隐藏，不会误报。
     */
    @SerialName("organizations") val organizations: List<OrganizationDto> = emptyList(),
    /** 集合（每个 belong 到某个组织）。 */
    @SerialName("collections") val collections: List<CollectionDto> = emptyList(),
    /**
     * 账号档案（含 `organizations`）：组织密钥（包裹态）在这里。
     *
     * ⚠️ 顶层 `organizations` 给的是**名字/状态**，密钥在 profile 里 —— 两处都要读，
     * 少一个就会出现"组织列表有了、名字全是乱码/解密失败"。
     */
    @SerialName("profile") val profile: ProfileDto? = null,
)

/** sync 响应里的 `profile`：本轮只用到 [organizations]（组织密钥）。 */
@Serializable
data class ProfileDto(
    @SerialName("id") val id: String = "",
    @SerialName("organizations") val organizations: List<OrganizationProfileDto> = emptyList(),
)

/**
 * 组织（共享库的顶层分组）。
 *
 * [name] 是 EncString，用**组织密钥**解；组织密钥又要先由 `profile.organizations`
 * 里的 `key`（被账号密钥包裹）解出来 —— 见 `OrganizationKeyResolver`。
 */
@Serializable
data class OrganizationDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("status") val status: Int = 0,
    @SerialName("key") val key: String? = null,
)

/**
 * 集合（组织内的二级分组）。
 *
 * [name] 用组织密钥解。`externalId` 只有 SSO 场景有，本客户端不用。
 * ⚠️ `readOnly` / `manage` 是**权限位**：只读集合里的条目改不动，
 * 本轮只做展示与筛选，不做写入，因此不建模权限。
 */
@Serializable
data class CollectionDto(
    @SerialName("id") val id: String,
    @SerialName("organizationId") val organizationId: String = "",
    @SerialName("name") val name: String? = null,
)

/**
 * 组织密钥条目（`profile.organizations[i]`）。
 *
 * ⚠️ 与 [OrganizationDto] **分开建模**：`profile` 里的结构只有 id + 加密的 key，
 * 没有 name/status。硬塞进同一个类会让那三个字段在 profile 路径上恒为默认值，
 * 读的人会误以为"服务器没给"。
 */
@Serializable
data class OrganizationProfileDto(
    @SerialName("id") val id: String,
    /** 组织密钥的包裹态（EncString，用账号密钥解）。 */
    @SerialName("key") val key: String? = null,
    /** 0 = Invited / 1 = Accepted / 2 = Confirmed / 3 = Revoked…；只有 >=2 才是正式成员。 */
    @SerialName("status") val status: Int = 0,
)


@Serializable
data class FolderDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("revisionDate") val revisionDate: String = "",
)

@Serializable
data class FolderRequest(
    @SerialName("name") val name: String,
)

@Serializable
data class FolderListResponse(
    @SerialName("data") val data: List<FolderDto> = emptyList(),
)

@Serializable
data class CipherResponse(
    @SerialName("id") val id: String = "",
    @SerialName("revisionDate") val revisionDate: String = "",
)

/** Bitwarden `Cipher`。所有业务字段均为 EncString 密文（type 2 为主）。 */
@Serializable
data class CipherDto(
    @SerialName("id") val id: String = "",
    @SerialName("type") val type: Int = 1,
    @SerialName("name") val name: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("favorite") val favorite: Boolean = false,
    @SerialName("folderId") val folderId: String? = null,
    @SerialName("organizationId") val organizationId: String? = null,
    /** 所属集合 id 列表（组织条目才有；个人条目为空）。用途：集合筛选 + 写回透传。 */
    @SerialName("collectionIds") val collectionIds: List<String> = emptyList(),
    @SerialName("revisionDate") val revisionDate: String = "",
    /**
     * 条目**创建时间**（ISO-8601 明文串，服务端原样返回）。
     *
     * ⚠️ 2026-09-30 补声明：服务端**一直**在返回这个字段，而本 DTO 此前没有它
     * ⇒ 被 kotlinx.serialization 的 `ignoreUnknownKeys` **静默丢掉**，
     * 于是「条目的创建时间」在 Vaultix 里根本无处可来（用户报「详情页看不到创建时间」）。
     * 这类「服务端有、DTO 没声明」的字段是**静默**丢失的典型 —— 加字段时对照服务端响应核一遍。
     *
     * ⚠️ 与 [revisionDate] 一样是**明文**（Bitwarden 不对时间字段加密），直接解析即可。
     */
    @SerialName("creationDate") val creationDate: String? = null,
    @SerialName("deletedDate") val deletedDate: String? = null,
    @SerialName("reprompt") val reprompt: Int = 0,
    @SerialName("login") val login: LoginDto? = null,
    @SerialName("card") val card: CardDto? = null,
    @SerialName("identity") val identity: IdentityDto? = null,
    @SerialName("secureNote") val secureNote: SecureNoteDto? = null,
    @SerialName("sshKey") val sshKey: SshKeyDto? = null,
    @SerialName("fields") val fields: List<CustomFieldDto> = emptyList(),
    @SerialName("passwordHistory") val passwordHistory: List<PasswordHistoryDto> = emptyList(),
    @SerialName("attachments") val attachments: List<AttachmentDto> = emptyList(),
    @SerialName("key") val key: String? = null,
)

@Serializable
data class LoginDto(
    @SerialName("username") val username: String? = null,
    @SerialName("password") val password: String? = null,
    @SerialName("totp") val totp: String? = null,
    @SerialName("uris") val uris: List<UriDto> = emptyList(),
    @SerialName("passwordRevisionDate") val passwordRevisionDate: String? = null,
    @SerialName("fido2Credentials") val fido2Credentials: List<Fido2CredentialDto> = emptyList(),
)

/** type 3 银行卡载荷（字段均为 EncString 密文；对齐 Bastion CipherCardApiData 字段集）。 */
@Serializable
data class CardDto(
    @SerialName("cardholderName") val cardholderName: String? = null,
    @SerialName("brand") val brand: String? = null,
    @SerialName("number") val number: String? = null,
    @SerialName("expMonth") val expMonth: String? = null,
    @SerialName("expYear") val expYear: String? = null,
    @SerialName("code") val code: String? = null,
)

/** type 4 身份载荷（对齐 Bastion CipherIdentityApiData 字段集）。 */
@Serializable
data class IdentityDto(
    @SerialName("title") val title: String? = null,
    @SerialName("firstName") val firstName: String? = null,
    @SerialName("middleName") val middleName: String? = null,
    @SerialName("lastName") val lastName: String? = null,
    @SerialName("address1") val address1: String? = null,
    @SerialName("address2") val address2: String? = null,
    @SerialName("address3") val address3: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("postalCode") val postalCode: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("company") val company: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("ssn") val ssn: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("passportNumber") val passportNumber: String? = null,
    @SerialName("licenseNumber") val licenseNumber: String? = null,
)

/** type 2 安全笔记载荷：目前只有子类型号（对齐 Bastion CipherSecureNoteApiData）。 */
@Serializable
data class SecureNoteDto(
    @SerialName("type") val type: Int = 0,
)

/** type 5 SSH 密钥载荷（对齐 Bastion CipherSshKeyApiData 字段集）。 */
@Serializable
data class SshKeyDto(
    @SerialName("privateKey") val privateKey: String? = null,
    @SerialName("publicKey") val publicKey: String? = null,
    @SerialName("keyFingerprint") val keyFingerprint: String? = null,
)

@Serializable
data class UriDto(
    @SerialName("uri") val uri: String? = null,
    @SerialName("match") val match: Int? = null,
)

/** login 的 WebAuthn 通行凭证元数据（保留段，Vaultix 不修改）。 */
@Serializable
data class Fido2CredentialDto(
    @SerialName("credentialId") val credentialId: String? = null,
    @SerialName("keyType") val keyType: String? = null,
    @SerialName("keyAlgorithm") val keyAlgorithm: String? = null,
    @SerialName("keyCurve") val keyCurve: String? = null,
    @SerialName("keyValue") val keyValue: String? = null,
    @SerialName("rpId") val rpId: String? = null,
    @SerialName("rpName") val rpName: String? = null,
    @SerialName("counter") val counter: String? = null,
    @SerialName("userHandle") val userHandle: String? = null,
    @SerialName("userName") val userName: String? = null,
    @SerialName("userDisplayName") val userDisplayName: String? = null,
    @SerialName("discoverable") val discoverable: String? = null,
    @SerialName("creationDate") val creationDate: String? = null,
)

/** type: 0 Text / 1 Hidden / 2 Boolean / 3 Linked */
@Serializable
data class CustomFieldDto(
    @SerialName("name") val name: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("type") val type: Int = 0,
    @SerialName("linkedId") val linkedId: Int? = null,
)

@Serializable
data class PasswordHistoryDto(
    @SerialName("password") val password: String = "",
    @SerialName("lastUsedDate") val lastUsedDate: String = "",
)

@Serializable
data class AttachmentDto(
    @SerialName("id") val id: String = "",
    @SerialName("fileName") val fileName: String? = null,
    @SerialName("size") val size: Long = 0,
    @SerialName("key") val key: String? = null,
)

/**
 * 附件下载地址响应（`GET /ciphers/{id}/attachment/{attachmentId}`）。
 *
 * 官方服务器返回**短时效签名 URL**（Azure/AWS S3）；Vaultwarden 返回指向
 * 自身的直链。`url` 为空 = 服务器不支持该端点（过老的 Vaultwarden）——
 * 调用方按"附件不可下载"处理，**不要**回退去猜其它路径。
 */
@Serializable
data class AttachmentDownloadResponse(
    @SerialName("url") val url: String? = null,
)

/**
 * 创建 / 更新条目请求体。
 *
 * login/card/identity/secureNote/sshKey/fields 均为**可空保留段**：
 * 上传时含值即随请求体提交，null 即省略（服务器对缺省段按官方语义处理）。
 * 更新路径由 CipherMapper.toUpdateRequest 负责把「未编辑的密文段」原样并入，
 * 防止整条重写清掉用户看不到的载荷（详见 CipherMapper 注释）。
 */
@Serializable
data class CipherRequest(
    @SerialName("type") val type: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("favorite") val favorite: Boolean = false,
    @SerialName("folderId") val folderId: String? = null,
    @SerialName("reprompt") val reprompt: Int = 0,
    @SerialName("login") val login: LoginDto? = null,
    @SerialName("card") val card: CardDto? = null,
    @SerialName("identity") val identity: IdentityDto? = null,
    @SerialName("secureNote") val secureNote: SecureNoteDto? = null,
    @SerialName("sshKey") val sshKey: SshKeyDto? = null,
    @SerialName("fields") val fields: List<CustomFieldDto> = emptyList(),

    // ---------------------------------------------------------------------
    // ★ 以下三段是**纯透传字段**（2026-09-23 新增），Vaultix 领域模型里没有对应
    //   概念，新增它们**不是为了支持**这些能力，而是为了**不让一次无关的编辑
    //   把它们抹掉**。判据来自 Bitwarden 服务端
    //   `src/Api/Vault/Models/Request/CipherRequestModel.cs`：
    //
    //   · PasswordHistory：`PasswordHistory = PasswordHistory?.Select(...)`
    //     ⇒ **不带即清空**。且服务端是把整个 `Data` 块重建的
    //       （序列化时 `IgnoreWritingNull`），所以"不传"= 置空，不是"不改动"。
    //
    //   · OrganizationId：`IsOrganizationCipher => !IsNullOrWhiteSpace(OrganizationId)`
    //     ⇒ **不带即被当成个人条目**，组织成员再也看不到这条（对用户来说
    //       等同于"条目从共享库里消失了"）。
    //
    //   · LastKnownRevisionDate：一路传给 `SaveDetailsAsync(...)` 做冲突检测
    //     ⇒ **不带即放弃检测**，多设备并发编辑会静默互相覆盖。
    //
    //   ⚠️ 附件（attachments）**刻意不在此列**：服务端
    //     `if (!hasAttachments2 && !hasAttachments) return existingCipher;`
    //     ⇒ 不带附件时服务端**原样保留**，无需透传——而且透传反而危险，
    //       因为附件的提交语义是"整体替换"，一旦塞回去就可能被当成一次
    //       附件改写。所以这里什么都不做才是最安全的。
    //
    //   这三段只在**更新**路径（CipherMapper.toUpdateRequest）填值；新建路径
    //   不填——那时服务端还没有这条记录，没有"原值"可言。
    // ---------------------------------------------------------------------
    @SerialName("passwordHistory")
    val passwordHistory: List<PasswordHistoryDto> = emptyList(),
    @SerialName("organizationId") val organizationId: String? = null,
    /**
     * 集合归属（组织条目）。
     *
     * ⚠️ **必须透传**（2026-10-21）：服务端 `CipherRequestModel` 对 collectionIds
     * 的处理是整体替换 —— 不带 = 清空。组织条目在 Vaultix 里被编辑一次，
     * 就会从所有集合里掉出来（组织成员在原客户端看不到它）。
     */
    @SerialName("collectionIds") val collectionIds: List<String> = emptyList(),
    @SerialName("lastKnownRevisionDate") val lastKnownRevisionDate: String? = null,
)

/**
 * 把「已加密的上传请求体」还原成可本地落库的 [CipherDto]。
 *
 * 用途：新建条目经 `POST /ciphers` 成功后，服务端会分配新 id（请求体本身
 * 不含 id 字段），本地临时行需要按服务端 id 重建。请求里的字段全部是 EncString
 * 密文，与服务端存的一致（服务端不会二次加密），因此无需再拉一次全量条目。
 *
 * ## ⚠️ 2026-10-02 审计修复：请求体表达不了的字段必须**顺延**
 *
 * [CipherRequest] 只携带"要提交给服务端"的字段（见它那三个透传字段的注释），
 * 而本地落库的 [CipherDto] 里还有几个**服务端有、请求体没有**的字段。
 * 本函数此前只拷 14 个字段 ⇒ **每一次编辑都会把下面这些悄悄抹掉**：
 *
 * | 字段 | 抹掉后的后果 |
 * |---|---|
 * | `organizationId` | 组织成员条目从本地看**降级为个人条目**（"共享库里的东西不见了"） |
 * | `passwordHistory` | 密码历史被清空（`toUpdateRequest` 费劲把它带去服务端，回来本地还是丢了） |
 * | `creationDate` | 详情页的"创建于"消失 |
 * | `attachments` / `key` | 附件元信息与条目级密钥被清空 |
 *
 * ⇒ 解决办法不是"把这些字段塞进请求体"（那会改变提交语义，见 `CipherRequest`
 *   关于附件的注释），而是 **[previous] 顺延**：这些值在一次编辑里**本来就不变**，
 *   原样带过去即可。缺省为 null（= 新建，本来就没有原值）。
 *
 * @param previous 改动前的那一行；**编辑路径必须传**，新建路径不传。
 */
fun CipherRequest.toStoredCipherDto(
    id: String,
    revisionDate: String,
    previous: CipherDto? = null,
): CipherDto = CipherDto(
    id = id,
    type = type,
    name = name,
    notes = notes,
    favorite = favorite,
    folderId = folderId,
    organizationId = organizationId ?: previous?.organizationId,
    // 集合归属同理顺延（2026-10-21）：请求体里带了就是"按它整体替换"，
    // 没带就是"一条都不属于" —— 与上面的 organizationId 是同一类静默丢失。
    collectionIds = collectionIds.ifEmpty { previous?.collectionIds.orEmpty() },
    revisionDate = revisionDate,
    creationDate = previous?.creationDate,
    // deletedDate 同样顺延：编辑一条被软删的行**不该**让它从回收站"复活"。
    deletedDate = previous?.deletedDate,
    reprompt = reprompt,
    login = login,
    card = card,
    identity = identity,
    secureNote = secureNote,
    sshKey = sshKey,
    fields = fields,
    passwordHistory = passwordHistory.ifEmpty { previous?.passwordHistory.orEmpty() },
    attachments = previous?.attachments.orEmpty(),
    key = previous?.key,
)
