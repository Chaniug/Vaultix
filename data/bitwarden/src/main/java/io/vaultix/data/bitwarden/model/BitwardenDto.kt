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
)

@Serializable
data class UriDto(
    @SerialName("uri") val uri: String? = null,
    @SerialName("match") val match: Int? = null,
)

/** type: 0 Text / 1 Hidden / 2 Boolean / 3 Linked */
@Serializable
data class CustomFieldDto(
    @SerialName("name") val name: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("type") val type: Int = 0,
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

/** 创建 / 更新条目请求体。 */
@Serializable
data class CipherRequest(
    @SerialName("type") val type: Int,
    @SerialName("name") val name: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("favorite") val favorite: Boolean = false,
    @SerialName("folderId") val folderId: String? = null,
    @SerialName("reprompt") val reprompt: Int = 0,
    @SerialName("login") val login: LoginDto? = null,
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
    fields = fields,
)
