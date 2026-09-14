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
 * 本文件的数据形状（字段名 / 类型 / 大小写）严格对齐 Bitwarden 官方导出器：
 *   bitwarden/sdk-internal → crates/bitwarden-exporters/src/json.rs（明文 JSON 结构）
 *   bitwarden/sdk-internal → crates/bitwarden-exporters/src/encrypted_json.rs（加密信封）
 * 官方以 Rust `serde(rename_all = "camelCase")` 序列化；Kotlin 侧用
 * `@SerialName` 逐字段钉死，**不依赖全局命名策略**——导出格式是互操作契约，
 * 必须显式声明，改一个字段名都会让官方客户端导入失败。
 *
 * ⚠️ 未知字段一律忽略、空集合按官方规则省略（见各字段注释）。这些细节决定
 * 官方客户端 / CLI 能否读回本文件，不能凭直觉调整。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 明文导出信封（对齐官方 `JsonExport`）。
 *
 * 官方 `json.rs`:
 * ```rust
 * struct JsonExport { encrypted: bool, folders: Vec<JsonFolder>, items: Vec<JsonCipher> }
 * ```
 *
 * @property encrypted 明文导出恒为 `false`；加密导出的明文载荷里同样带这个字段
 *   （官方先构造明文结构再整体加密）。
 * @property folders 文件夹列表（可为空数组，官方**不省略**该字段）。
 * @property items 条目列表（可为空数组，官方**不省略**该字段）。
 */
@Serializable
data class BitwardenPlainExport(
    @SerialName("encrypted") val encrypted: Boolean = false,
    @SerialName("folders") val folders: List<BitwardenExportFolder> = emptyList(),
    @SerialName("items") val items: List<BitwardenExportCipher> = emptyList(),
)

/**
 * 文件夹（对齐官方 `JsonFolder`）。
 *
 * @property id 文件夹 UUID 字符串。官方为 `Uuid` 类型，**必须**是合法 UUID 形态，
 *   否则官方导入器会在反序列化阶段直接报错（不做兜底）。
 * @property name 文件夹名（明文）。
 */
@Serializable
data class BitwardenExportFolder(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

/**
 * 单个条目（对齐官方 `JsonCipher`）。
 *
 * 字段省略规则严格照搬官方 `#[serde(skip_serializing_if = ...)]`：
 * - 各类型载荷（`login`/`identity`/`card`/`secureNote`/`sshKey`）为 `null` 时**整个字段省略**；
 * - `fields` 为空时**整个字段省略**（官方 `skip_serializing_if = "Vec::is_empty"`）；
 * - `organizationId`/`collectionIds` 个人导出恒为 `null`，但官方**不省略**（照常输出 `null`）。
 *
 * @property type 条目类型：1=Login、2=SecureNote、3=Card、4=Identity（对齐官方 `CipherType`）。
 *   官方导出器会**过滤掉** BankAccount / Passport / DriversLicense（未来类型，当前不支持）。
 * @property reprompt 0=不需要、1=查看前需重输主密码（对齐 Bitwarden `cipher.reprompt`）。
 * @property fields 自定义字段；为空时字段整体省略（见类注释）。
 * @property passwordHistory 密码历史；Vaultix 领域模型暂不承载，恒为 `null`。
 */
@Serializable
data class BitwardenExportCipher(
    @SerialName("id") val id: String,
    @SerialName("organizationId") val organizationId: String? = null,
    @SerialName("folderId") val folderId: String? = null,
    @SerialName("collectionIds") val collectionIds: List<String>? = null,
    @SerialName("name") val name: String,
    @SerialName("notes") val notes: String? = null,
    @SerialName("type") val type: Int,
    @SerialName("login") val login: BitwardenExportLogin? = null,
    @SerialName("identity") val identity: BitwardenExportIdentity? = null,
    @SerialName("card") val card: BitwardenExportCard? = null,
    @SerialName("secureNote") val secureNote: BitwardenExportSecureNote? = null,
    @SerialName("sshKey") val sshKey: BitwardenExportSshKey? = null,
    @SerialName("favorite") val favorite: Boolean = false,
    @SerialName("reprompt") val reprompt: Int = 0,
    @SerialName("fields") val fields: List<BitwardenExportField>? = null,
    @SerialName("passwordHistory") val passwordHistory: List<BitwardenExportPasswordHistory>? = null,
    /**
     * 创建/修订时间，官方为 RFC 3339 **毫秒**精度（`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`）。
     * 见 `json.rs` 的 `rfc3339_millis_serialize`。传 null 时字段省略。
     */
    @SerialName("revisionDate") val revisionDate: String? = null,
    @SerialName("creationDate") val creationDate: String? = null,
    @SerialName("deletedDate") val deletedDate: String? = null,
)

/**
 * 登录载荷（对齐官方 `JsonLogin`）。
 *
 * ⚠️ 官方 `uris` / `fido2Credentials` **恒为数组（可为空数组），不省略字段**，
 * 与 Vaultix 领域模型的可空列表不同——映射时需 `?: emptyList()`。
 *
 * @property totp TOTP 原文。Bitwarden 接受 `otpauth://` URI，也接受裸 base32 密钥；
 *   Vaultix 存的是 [io.vaultix.model.VaultItem.totp] 原文，直接透传即可。
 */
@Serializable
data class BitwardenExportLogin(
    @SerialName("username") val username: String? = null,
    @SerialName("password") val password: String? = null,
    @SerialName("uris") val uris: List<BitwardenExportUri> = emptyList(),
    @SerialName("totp") val totp: String? = null,
    @SerialName("fido2Credentials") val fido2Credentials: List<BitwardenExportFido2> = emptyList(),
)

/**
 * 通行密钥（对齐官方 `JsonFido2Credential`）。
 *
 * ⚠️ 官方把 [counter] 与 [discoverable] **序列化为字符串**（注释写明是为了与 Web 导出器
 * 保持一致，`counter: String` / `discoverable: String`）。Kotlin 侧照做，否则官方导入器
 * 反序列化会因类型不符失败。
 */
@Serializable
data class BitwardenExportFido2(
    @SerialName("credentialId") val credentialId: String,
    @SerialName("keyType") val keyType: String,
    @SerialName("keyAlgorithm") val keyAlgorithm: String,
    @SerialName("keyCurve") val keyCurve: String,
    @SerialName("keyValue") val keyValue: String,
    @SerialName("rpId") val rpId: String,
    @SerialName("userHandle") val userHandle: String? = null,
    @SerialName("userName") val userName: String? = null,
    /** ⚠️ 字符串形态（官方与 Web 导出器对齐）。 */
    @SerialName("counter") val counter: String,
    @SerialName("rpName") val rpName: String? = null,
    @SerialName("userDisplayName") val userDisplayName: String? = null,
    /** ⚠️ 字符串形态（"true"/"false"）。 */
    @SerialName("discoverable") val discoverable: String,
    @SerialName("creationDate") val creationDate: String,
)

/**
 * 匹配网址（对齐官方 `JsonLoginUri`）。
 *
 * @property match 0=Domain、1=Host、2=StartsWith、3=Exact、4=RegularExpression、5=Never。
 */
@Serializable
data class BitwardenExportUri(
    @SerialName("uri") val uri: String? = null,
    @SerialName("match") val match: Int? = null,
)

/** 安全笔记载荷（对齐官方 `JsonSecureNote`）。目前只有通用子类型 0。 */
@Serializable
data class BitwardenExportSecureNote(
    @SerialName("type") val type: Int = 0,
)

/** 银行卡载荷（对齐官方 `JsonCard`）。 */
@Serializable
data class BitwardenExportCard(
    @SerialName("cardholderName") val cardholderName: String? = null,
    @SerialName("expMonth") val expMonth: String? = null,
    @SerialName("expYear") val expYear: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("brand") val brand: String? = null,
    @SerialName("number") val number: String? = null,
)

/**
 * 身份载荷（对齐官方 `JsonIdentity`，全量 17 字段）。
 *
 * 字段集与 Vaultix [io.vaultix.model.VaultIdentity] 一一对应，映射即直译、
 * 不做任何子集裁剪（Vaultix 以 Bitwarden 全字段为规范）。
 */
@Serializable
data class BitwardenExportIdentity(
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

/**
 * SSH 密钥载荷（对齐官方 `JsonSshKey`）。
 *
 * ⚠️ 官方字段名是 `keyFingerprint`（JsonProperty），而 Vaultix 领域模型叫
 * [io.vaultix.model.VaultSshKey.keyFingerprint]——两边一致，映射直译。
 */
@Serializable
data class BitwardenExportSshKey(
    @SerialName("privateKey") val privateKey: String,
    @SerialName("publicKey") val publicKey: String,
    @SerialName("keyFingerprint") val keyFingerprint: String,
)

/**
 * 自定义字段（对齐官方 `JsonField`）。
 *
 * @property type 0=Text、1=Hidden、2=Boolean、3=Linked（对齐 [io.vaultix.model.CustomFieldType]）。
 * @property linkedId 仅 Linked 类型有效，指向标准字段编号。
 */
@Serializable
data class BitwardenExportField(
    @SerialName("name") val name: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("type") val type: Int,
    @SerialName("linkedId") val linkedId: Int? = null,
)

/** 密码历史条目（对齐官方 `JsonPasswordHistory`；Vaultix 暂不承载，保留模型以对齐契约）。 */
@Serializable
data class BitwardenExportPasswordHistory(
    @SerialName("password") val password: String,
    @SerialName("lastUsedDate") val lastUsedDate: String,
)

/**
 * 加密导出信封（对齐官方 `EncryptedJsonExport`）。
 *
 * 官方 `encrypted_json.rs`:
 * ```rust
 * struct EncryptedJsonExport {
 *     encrypted: bool, password_protected: bool, salt: String,
 *     kdf_type: u32, kdf_iterations: u32, kdf_memory: Option<u32>, kdf_parallelism: Option<u32>,
 *     enc_key_validation: String,  // @serde(rename = "encKeyValidation_DO_NOT_EDIT")
 *     data: String,
 * }
 * ```
 *
 * ⚠️ [encKeyValidation] 的 JSON 名是**唯一一个不遵循 camelCase 的字段**：
 * `encKeyValidation_DO_NOT_EDIT`（官方显式 rename）。写错会导致官方客户端
 * 找不到校验字段而拒绝解密。
 *
 * @property salt 16 字节随机盐的 Base64 字符串。
 *   ⚠️ 派生密钥时用的是**这个 Base64 字符串的 UTF-8 字节**，不是解码后的原始 16 字节。
 * @property kdfType 0=PBKDF2_SHA256、1=Argon2id。
 * @property kdfMemory Argon2id 内存（MiB）；PBKDF2 时为 `null`（官方输出 `null`，不省略）。
 * @property kdfParallelism Argon2id 并行度；PBKDF2 时为 `null`。
 * @property encKeyValidation 用导出密钥加密的**随机 UUID v4 字符串**（EncString 形态）。
 *   ⚠️ 官方是 UUID 而非固定字符串；读取方校验「解密结果能解析为 UUID」即证明密码正确。
 * @property data 用导出密钥加密的明文 JSON（[BitwardenPlainExport]）的 EncString。
 */
@Serializable
data class BitwardenEncryptedExport(
    @SerialName("encrypted") val encrypted: Boolean = true,
    @SerialName("passwordProtected") val passwordProtected: Boolean = true,
    @SerialName("salt") val salt: String,
    @SerialName("kdfType") val kdfType: Int,
    @SerialName("kdfIterations") val kdfIterations: Int,
    @SerialName("kdfMemory") val kdfMemory: Int? = null,
    @SerialName("kdfParallelism") val kdfParallelism: Int? = null,
    @SerialName("encKeyValidation_DO_NOT_EDIT") val encKeyValidation: String,
    @SerialName("data") val data: String,
)

/**
 * Bitwarden KDF 类型（对齐官方 `kdf_type`）。
 *
 * 导出**沿用账号当前 KDF 设置**（官方行为）：账号用 Argon2 导出的文件就用 Argon2，
 * 这样官方客户端导入时能按文件内字段正确派生密钥。
 */
object BitwardenExportKdfType {
    /** PBKDF2-HMAC-SHA256（Bitwarden KdfType=0）。 */
    const val PBKDF2_SHA256 = 0

    /** Argon2id（Bitwarden KdfType=1）。 */
    const val ARGON2_ID = 1
}
