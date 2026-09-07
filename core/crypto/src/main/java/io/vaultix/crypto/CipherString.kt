/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件的 [ParsedCipherString] 解析逻辑由 Bastion 项目
 * （GPL-3.0，Copyright 2025 JoyinJoester）的
 *   bastion/Bastion/app/src/main/java/com/bastion/app/bitwarden/crypto/BitwardenCrypto.kt
 * 中的 `ParsedCipherString` / `parseCipherString` / `parseCipherStringParts` 搬运改造而来。
 * 改造点：
 *   1) 字段 `data` 重命名为 `ciphertext`，避免与 Kotlin 的 `data class` 语义混淆；
 *   2) 解析仍支持 type 0/2，但按 Vaultix Docs/03 第 2.4 节，type 0 在解密路径默认拒绝；
 *   3) Base64 解码改用 java.util.Base64（见 Base64Codec.kt）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

/**
 * Bitwarden EncString（CipherString）类型常量。
 *
 * 完整类型表见 Docs/03 第 2.4 节；M0 仅实现 type 2。
 */
object CipherType {
    /** `AesCbc256_B64`：遗留无 MAC 格式。Docs/03 明确不支持（仅解析，解密默认拒绝）。 */
    const val AES_CBC_256_B64 = 0

    /** `AesCbc256_HmacSha256_B64`：当前标准，读写均支持，写入恒为此类型。 */
    const val AES_CBC_256_HMAC_SHA256_B64 = 2

    /** `Rsa2048_OaepSha256_B64`：组织密钥解包。M0 未实现。 */
    const val RSA_2048_OAEP_SHA256_B64 = 3

    /** `Rsa2048_OaepSha256_HmacSha256_B64`：组织密钥解包。M0 未实现。 */
    const val RSA_2048_OAEP_SHA256_HMAC_B64 = 5

    /** `CoseEncrypt0`（XChaCha20-Poly1305）。M0 未实现。 */
    const val COSE_ENCRYPT_0 = 7
}

/**
 * 解析后的 EncString。
 *
 * 格式：`<type>.<b64(iv)>|<b64(ciphertext)>|<b64(mac)>`
 * （type 0 无 mac 段；type 7 为 COSE/CBOR 编码，格式不同，M0 不支持）。
 *
 * @property type EncString 类型，见 [CipherType]
 * @property iv 初始化向量（AES-CBC 为 16 字节）
 * @property ciphertext 密文
 * @property mac HMAC-SHA256 值；type 0 为 null
 */
data class ParsedCipherString(
    val type: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val mac: ByteArray? = null,
)

/** EncString 分段数量：type 0 为 `iv|data`，type 2 为 `iv|data|mac`。 */
private const val TYPE0_PART_COUNT = 2
private const val TYPE2_PART_COUNT = 3

/**
 * 解析 EncString。
 *
 * 无类型前缀（不含 '.'）时按遗留 type 0 处理，与 Bastion 行为一致。
 *
 * @throws IllegalArgumentException 空串、超长、类型非法或分段不足
 * @throws UnsupportedCipherTypeException 尚不支持的类型（3 / 5 / 7 等）
 */
internal fun parseCipherString(cipherString: String): ParsedCipherString {
    require(cipherString.isNotBlank()) { "Cipher string is blank" }
    require(cipherString.length <= MAX_CIPHER_STRING_LENGTH) {
        "Cipher string too large: ${cipherString.length}"
    }

    val dotIndex = cipherString.indexOf('.')
    if (dotIndex == -1) {
        // 无类型前缀：按遗留 type 0 解析
        return parseCipherStringParts(CipherType.AES_CBC_256_B64, cipherString)
    }

    val rawType = cipherString.substring(0, dotIndex)
    val type = rawType.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid cipher type: $rawType")
    val rest = cipherString.substring(dotIndex + 1)

    return parseCipherStringParts(type, rest)
}

private fun parseCipherStringParts(type: Int, data: String): ParsedCipherString {
    val parts = data.split('|')

    return when (type) {
        CipherType.AES_CBC_256_B64 -> {
            require(parts.size >= TYPE0_PART_COUNT) {
                "EncString type 0 requires at least iv|ciphertext, got ${parts.size}"
            }
            ParsedCipherString(
                type = type,
                iv = decodeStandardBase64(parts[0], "iv"),
                ciphertext = decodeStandardBase64(parts[1], "ciphertext"),
                mac = null,
            )
        }

        CipherType.AES_CBC_256_HMAC_SHA256_B64 -> {
            require(parts.size >= TYPE2_PART_COUNT) {
                "EncString type 2 requires iv|ciphertext|mac, got ${parts.size}"
            }
            ParsedCipherString(
                type = type,
                iv = decodeStandardBase64(parts[0], "iv"),
                ciphertext = decodeStandardBase64(parts[1], "ciphertext"),
                mac = decodeStandardBase64(parts[2], "mac"),
            )
        }

        else -> throw UnsupportedCipherTypeException(type)
    }
}
