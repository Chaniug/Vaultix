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
) {
    /**
     * ★ **按内容**比较（2026-10-10 修）。
     *
     * ## 修的是什么
     *
     * `data class` 自动生成的 `equals` 对 `ByteArray` 字段退化成**引用比较** ——
     * 两个 iv / 密文逐字节相同的实例会被判为"不相等"。这个坑对引用类型字段是通用的，
     * 只是 `ByteArray` 最容易踩（它长得像个值）。
     *
     * ## 为什么这个文件里格外要命
     *
     * 本类**没有任何相等性依赖**（生产代码只用它的字段），所以"引用比较"本身不直接出错。
     * 真正的风险在**测试**：断言 `assertEquals(parsed, expected)` 会**恒失败**（两个实例
     * 永远是不同引用），于是后来的人只能退而写 `assertEquals(expected.iv, parsed.iv)`
     * 这类逐字段比较 —— 看起来是"写得更细"，实际是**语义丢了**：
     * 逐字段只覆盖写出来的那几个字段，将来给本类**加字段**时，新字段自动逃过断言。
     * 而 `assertEquals(整个对象, ...)` 会强制实现者补齐 `equals`。
     *
     * ## 与项目内既有约定对齐
     *
     * [io.vaultix.common.WebAuthn.GeneratedKey] 早就是这么做的（一个 `data class`
     * 里四个 `ByteArray` 字段 + 手写 `equals`/`hashCode`），本类此前是**唯一没跟上的**。
     * [SymmetricCryptoKey] 的处置不同（改成普通 `class`）是因为它还要求 `SecureBytes`
     * 的清零语义，属于另一类需求 —— 不要因为那里改了 class 就把这里的 data class 也拆掉：
     * 本类是**纯数据载体**，`copy` / 解构都有用。
     */
    override fun equals(other: Any?): Boolean =
        other is ParsedCipherString && type == other.type &&
            iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext) &&
            (mac?.contentEquals(other.mac) ?: (other.mac == null))

    /** 与 [equals] 同口径：用**内容**哈希，且对 `mac == null` 给一个确定值。 */
    override fun hashCode(): Int {
        var result = type
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + (mac?.contentHashCode() ?: 0)
        return result
    }
}

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
