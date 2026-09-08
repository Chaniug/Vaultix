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
    @SerialName("revisionDate") val revisionDate: String = "",
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
)

/**
 * 把「已加密的上传请求体」还原成可本地落库的 [CipherDto]。
 *
 * 用途：新建条目经 `POST /ciphers` 成功后，服务端会分配新 id（请求体本身
 * 不含 id 字段），本地临时行需要按服务端 id 重建。请求里的字段全部是 EncString
 * 密文，与服务端存的一致（服务端不会二次加密），因此无需再拉一次全量条目。
 */
fun CipherRequest.toStoredCipherDto(id: String, revisionDate: String): CipherDto = CipherDto(
    id = id,
    type = type,
    name = name,
    notes = notes,
    favorite = favorite,
    folderId = folderId,
    revisionDate = revisionDate,
    reprompt = reprompt,
    login = login,
    card = card,
    identity = identity,
    secureNote = secureNote,
    sshKey = sshKey,
    fields = fields,
)
