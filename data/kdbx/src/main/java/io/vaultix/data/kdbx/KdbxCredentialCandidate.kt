/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「凭据候选 / 密码套件」的构成方式参考 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `utils/KeePassCredentialSupport.kt` 与 `utils/KeePassCodecSupport.kt`：
 *   - KeePass 的 keyfile 有**多种历史格式**（XML v1/v2 的 `<Data>`、32 字节裸数据、
 *     64 字符十六进制文本），只试一种会让「密码对、keyfile 也对」的库打不开 →
 *     生成多个候选依次尝试；
 *   - 空密码 + keyfile 的库需要同时尝试 `key-only` 与 `empty-password+key`（历史差异）；
 *   - 密码套件在基础套件之外补 **Twofish**（KDBX 3.1 的老库会用）。
 * 本文件为独立实现，不含其代码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** 一套可尝试的凭据（[label] 只用于诊断与错误文案，绝不进日志的敏感部分）。 */
internal data class KdbxCredentialCandidate(
    val label: String,
    val credentials: Credentials,
)

/**
 * 候选凭据生成（顺序即尝试顺序：密码优先，其次 keyfile 各形态）。
 *
 * @param password 主密码（可为空 = 仅 keyfile）。
 * @param keyFileBytes keyfile 原始字节（null = 不用 keyfile）。
 */
internal fun buildCredentialCandidates(
    password: String,
    keyFileBytes: ByteArray?,
): List<KdbxCredentialCandidate> {
    if (keyFileBytes == null) {
        return listOf(
            KdbxCredentialCandidate(
                label = LABEL_PASSWORD_ONLY,
                credentials = Credentials.from(EncryptedValue.fromString(password)),
            ),
        )
    }

    val candidates = mutableListOf<KdbxCredentialCandidate>()
    val seen = linkedSetOf<String>()
    keyMaterialVariants(keyFileBytes).forEach { (variantLabel, keyBytes) ->
        val fingerprint = sha256Hex(keyBytes)
        // 有密码：密码 + keyfile。
        if (password.isNotEmpty()) {
            if (seen.add("password+key:$fingerprint:${password.length}")) {
                candidates += KdbxCredentialCandidate(
                    label = "$variantLabel/password+key",
                    credentials = Credentials.from(EncryptedValue.fromString(password), keyBytes),
                )
            }
            return@forEach
        }
        // 空密码：key-only 与 empty-password+key 都要试（KeePass 两种存法都真实存在）。
        if (seen.add("key-only:$fingerprint")) {
            candidates += KdbxCredentialCandidate(
                label = "$variantLabel/key-only",
                credentials = Credentials.from(keyBytes),
            )
        }
        if (seen.add("empty-password+key:$fingerprint")) {
            candidates += KdbxCredentialCandidate(
                label = "$variantLabel/empty-password+key",
                credentials = Credentials.from(EncryptedValue.fromString(""), keyBytes),
            )
        }
    }
    return candidates
}

/** 凭据全部失败时的用户文案（列出试过的形态，便于用户判断是不是 keyfile 选错了）。 */
internal fun invalidCredentialMessage(attemptedLabels: List<String>): String {
    val distinct = attemptedLabels.distinct()
    if (distinct.isEmpty()) return "数据库密码或密钥文件不正确"
    val concise = distinct.take(4).joinToString(separator = ", ")
    val suffix = if (distinct.size > 4) " 等 ${distinct.size} 种组合" else ""
    return "数据库密码或密钥文件不正确（已尝试：$concise$suffix）"
}

private const val LABEL_PASSWORD_ONLY = "password-only"

/**
 * keyfile 的多种形态（按可能性排序，去重后返回）。
 *
 * KeePass 官方 keyfile 是 XML（`<Data>` 里放 base64/hex），但历史/第三方工具也写过
 * 「32 字节裸数据」「64 字符 hex 文本」，两种都要认。
 */
private fun keyMaterialVariants(rawBytes: ByteArray): List<Pair<String, ByteArray>> {
    val variants = linkedMapOf<String, Pair<String, ByteArray>>()
    fun put(label: String, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        variants.putIfAbsent(sha256Hex(bytes), label to bytes)
    }
    put("raw", rawBytes)
    runCatching { rawBytes.toString(Charsets.UTF_8) }.getOrNull()?.let { text ->
        put("xml-data", extractXmlDataKey(text))
        put("hex-text", extractHexTextKey(text))
    }
    put("sha256(raw)", sha256(rawBytes))
    return variants.values.toList()
}

/** 从 KeePass XML keyfile 里取 `<Data>` 内容（hex 或 base64 两种都支持）。 */
private fun extractXmlDataKey(content: String): ByteArray? {
    val regex = Regex("<Data>(.*?)</Data>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val value = regex.find(content)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (value.isEmpty()) return null
    val compact = value.filterNot { it.isWhitespace() }
    if (compact.length == HEX_KEY_CHARS && compact.all(::isHexChar)) return decodeHex(compact)
    return runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
}

/** 整文件就是 64 字符十六进制的情况。 */
private fun extractHexTextKey(content: String): ByteArray? {
    val compact = content.filterNot { it.isWhitespace() }
    if (compact.length != HEX_KEY_CHARS || !compact.all(::isHexChar)) return null
    return decodeHex(compact)
}

private const val HEX_KEY_CHARS = 64

/** 十六进制解析基数与字节掩码（detekt MagicNumber）。 */
private const val HEX_RADIX = 16

/** 一个十六进制位对应的位移（detekt MagicNumber）。 */
private const val NIBBLE_SHIFT = 4
private const val BYTE_MASK = 0xff
private const val HEX_BYTE_FORMAT = "%02x"

private fun isHexChar(c: Char): Boolean = c in '0'..'9' || c.lowercaseChar() in 'a'..'f'

private fun decodeHex(value: String): ByteArray? {
    val clean = value.lowercase(Locale.ROOT)
    if (clean.length % 2 != 0) return null
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val hi = clean[i * 2].digitToIntOrNull(HEX_RADIX) ?: return null
        val lo = clean[i * 2 + 1].digitToIntOrNull(HEX_RADIX) ?: return null
        out[i] = ((hi shl NIBBLE_SHIFT) or lo).toByte()
    }
    return out
}

private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

private fun sha256Hex(input: ByteArray): String =
    sha256(input).joinToString(separator = "") { HEX_BYTE_FORMAT.format(Locale.US, it.toInt() and BYTE_MASK) }
