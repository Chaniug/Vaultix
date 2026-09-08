/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * otpauth-migration:// 批量导入（Google Authenticator 导出格式）的 protobuf
 * 解析（手工 wire format 读 OtpParameters 7 字段）、Base64 容错解码与
 * base32 编码移植自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * util/TotpUriParser.kt（ProtoReader / decodeMigrationPayload / base32Encode），
 * 按 Vaultix 架构重写为无 Android 依赖的纯算法模块。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * 单条导入项：归一化配置 + 展示用发行方/账号（不含 UI 状态）。
 */
data class ImportedOtp(
    val config: TotpConfig,
    val issuer: String,
    val account: String,
)

/**
 * 导入解析结果（对齐 Bastion TotpScanParseResult 的四种分支）：
 * - [Single]：单条（含普通 otpauth / motp / 裸密钥 / 恰含一条的 migration）；
 * - [Multiple]：migration 批量（≥2 条）；
 * - [UnsupportedPhoneFactor]：Microsoft Authenticator 导出（phonefactor://）暂不支持；
 * - [InvalidFormat]：无法识别。
 */
sealed interface OtpScanResult {
    data class Single(val item: ImportedOtp) : OtpScanResult
    data class Multiple(val items: List<ImportedOtp>) : OtpScanResult
    data object UnsupportedPhoneFactor : OtpScanResult
    data object InvalidFormat : OtpScanResult
}

/**
 * 粘贴 / 扫码内容导入解析（统一入口 [parse]）。
 *
 * 支持：
 * - `otpauth-migration://offline?data=<base64>`（Google Authenticator 批量导出）；
 * - `otpauth://totp|hotp|yaotp/...`、`motp://...`、裸 Base32 密钥（单条）；
 * - `phonefactor://` 显式标记为不支持。
 */
object OtpImportParser {

    private const val MIGRATION_PREFIX = "otpauth-migration://"
    private const val PHONEFACTOR_PREFIX = "phonefactor://"
    private const val OTPAUTH_PREFIX = "otpauth://"
    private const val MOTP_PREFIX = "motp://"

    /** migration 外层重复消息的字段号（repeated OtpParameters otp_parameters = 1）。 */
    private const val MIGRATION_PAYLOAD_FIELD = 1

    // OtpParameters 字段号（Google Authenticator migration.proto）
    private const val FIELD_SECRET = 1
    private const val FIELD_NAME = 2
    private const val FIELD_ISSUER = 3
    private const val FIELD_ALGORITHM = 4
    private const val FIELD_DIGITS = 5
    private const val FIELD_TYPE = 6
    private const val FIELD_COUNTER = 7

    // 枚举值
    private const val ALGORITHM_SHA1 = 1
    private const val ALGORITHM_SHA256 = 2
    private const val ALGORITHM_SHA512 = 3
    private const val TYPE_HOTP = 1
    private const val TYPE_TOTP = 2
    private const val DIGITS_ENUM_SIX = 1
    private const val DIGITS_ENUM_EIGHT = 2
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun parse(content: String): OtpScanResult {
        val normalized = content.trim()
        val lower = normalized.lowercase(Locale.US)
        return when {
            lower.startsWith(MIGRATION_PREFIX) -> parseMigration(normalized)
            lower.startsWith(OTPAUTH_PREFIX) || lower.startsWith(MOTP_PREFIX) ->
                parseSingleUri(normalized)
            lower.startsWith(PHONEFACTOR_PREFIX) -> OtpScanResult.UnsupportedPhoneFactor
            else -> parseBareSecret(normalized)
        }
    }

    private fun parseSingleUri(uri: String): OtpScanResult {
        val parsed = OtpUriParser.parseToDisplay(uri) ?: return OtpScanResult.InvalidFormat
        return OtpScanResult.Single(
            ImportedOtp(
                config = TotpConfig(
                    secret = parsed.secret,
                    period = parsed.period,
                    digits = parsed.digits,
                    algorithm = parsed.algorithm,
                    type = parsed.type,
                    counter = parsed.counter,
                    pin = parsed.pin,
                ),
                issuer = parsed.issuer,
                account = parsed.account,
            ),
        )
    }

    private fun parseBareSecret(raw: String): OtpScanResult {
        val parsed = OtpUriParser.parseToDisplay(raw) ?: return OtpScanResult.InvalidFormat
        return OtpScanResult.Single(
            ImportedOtp(
                config = TotpConfig(secret = parsed.secret),
                issuer = "",
                account = "",
            ),
        )
    }

    // ---- otpauth-migration:// ----

    private fun parseMigration(uri: String): OtpScanResult {
        val encodedData = extractQueryParam(uri, "data") ?: return OtpScanResult.InvalidFormat
        val payload = decodeMigrationPayload(encodedData) ?: return OtpScanResult.InvalidFormat
        val items = parseMigrationPayload(payload).mapNotNull(::toImportedOtp)
        return when {
            items.isEmpty() -> OtpScanResult.InvalidFormat
            items.size == 1 -> OtpScanResult.Single(items.first())
            else -> OtpScanResult.Multiple(items)
        }
    }

    /**
     * 从 query 提取参数值并做 %XX 解码。migration 的 data 值按 RFC 3986 编码时
     * `+` 为字面加号（Base64 组成部分），此处不解码 `+`（与 android.net.Uri 语义一致）。
     * 取值保留首个 `=` 之后全部内容（Base64 密钥自身含 `=`）。
     */
    private fun extractQueryParam(uri: String, key: String): String? {
        val query = uri.substringAfter("?", "")
        return query.split("&")
            .map { segment -> segment.split("=", limit = 2) }
            .firstOrNull { parts -> parts.size == PARTS_WITH_VALUE && uriDecode(parts[0]) == key }
            ?.let { parts -> uriDecode(parts[1]) }
    }

    /**
     * Base64 容错解码：兼容 URL-safe 变体（- _ → + /）、form 编码误转的空格
     * （→ +），并自动补齐 padding。
     */
    private fun decodeMigrationPayload(encodedData: String): ByteArray? = runCatching {
        var base64 = encodedData
            .replace(' ', '+')
            .replace('-', '+')
            .replace('_', '/')
        val padding = (BASE64_BLOCK - (base64.length % BASE64_BLOCK)) % BASE64_BLOCK
        if (padding > 0) {
            base64 += "=".repeat(padding)
        }
        Base64.getDecoder().decode(base64)
    }.getOrNull()

    /** 外层 MigrationPayload：反复读字段 1（OtpParameters 消息），其余跳过。 */
    private fun parseMigrationPayload(payload: ByteArray): List<MigrationOtpRaw> {
        val reader = ProtoReader(payload)
        val result = mutableListOf<MigrationOtpRaw>()
        while (!reader.isAtEnd()) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag ushr VARINT_TAG_BITS
            val wireType = tag and WIRE_TYPE_MASK
            if (fieldNumber == MIGRATION_PAYLOAD_FIELD && wireType == WIRE_LENGTH_DELIMITED) {
                val messageBytes = reader.readBytes() ?: return emptyList()
                parseMigrationAuthenticator(messageBytes)?.let { result += it }
            } else if (!reader.skipField(wireType)) {
                return emptyList()
            }
        }
        return result
    }

    /** 单条 OtpParameters：7 字段手工解析（secret/name/issuer/algorithm/digits/type/counter）。 */
    private fun parseMigrationAuthenticator(bytes: ByteArray): MigrationOtpRaw? {
        val reader = ProtoReader(bytes)
        val fields = OtpFields()
        while (!reader.isAtEnd()) {
            val tag = reader.readTag() ?: return null
            val consumed = consumeOtpField(reader, tag ushr VARINT_TAG_BITS, tag and WIRE_TYPE_MASK, fields)
            if (!consumed) return null
        }
        val secret = fields.secret
        if (secret == null || secret.isEmpty()) return null
        return MigrationOtpRaw(
            secret = secret,
            name = fields.name,
            issuer = fields.issuer,
            algorithm = fields.algorithm,
            digits = fields.digits,
            type = fields.type,
            counter = fields.counter,
        )
    }

    /** 按字段号消费一个字段；wire type 不匹配 / 读取失败返回 false（整条作废）。 */
    private fun consumeOtpField(reader: ProtoReader, fieldNumber: Int, wireType: Int, fields: OtpFields): Boolean {
        return when (fieldNumber) {
            FIELD_SECRET -> readBytesField(reader, wireType) { fields.secret = it }
            FIELD_NAME -> readStringField(reader, wireType) { fields.name = it }
            FIELD_ISSUER -> readStringField(reader, wireType) { fields.issuer = it }
            FIELD_ALGORITHM -> readVarIntField(reader, wireType) { fields.algorithm = it }
            FIELD_DIGITS -> readVarIntField(reader, wireType) { fields.digits = it }
            FIELD_TYPE -> readVarIntField(reader, wireType) { fields.type = it }
            FIELD_COUNTER -> readVarInt64Field(reader, wireType) { fields.counter = it }
            else -> reader.skipField(wireType)
        }
    }

    private fun readBytesField(reader: ProtoReader, wireType: Int, setter: (ByteArray) -> Unit): Boolean {
        if (wireType != WIRE_LENGTH_DELIMITED) return false
        val value = reader.readBytes() ?: return false
        setter(value)
        return true
    }

    private fun readStringField(reader: ProtoReader, wireType: Int, setter: (String) -> Unit): Boolean {
        if (wireType != WIRE_LENGTH_DELIMITED) return false
        val value = reader.readString() ?: return false
        setter(value)
        return true
    }

    private fun readVarIntField(reader: ProtoReader, wireType: Int, setter: (Int) -> Unit): Boolean {
        if (wireType != WIRE_VARINT) return false
        val value = reader.readVarInt32() ?: return false
        setter(value)
        return true
    }

    private fun readVarInt64Field(reader: ProtoReader, wireType: Int, setter: (Long) -> Unit): Boolean {
        if (wireType != WIRE_VARINT) return false
        val value = reader.readVarInt64() ?: return false
        setter(value)
        return true
    }

    /** 枚举 → 归一化配置（对齐 Bastion toParseResult 的清理与回退口径）。 */
    private fun toImportedOtp(raw: MigrationOtpRaw): ImportedOtp? {
        val type = when (raw.type) {
            TYPE_HOTP -> OtpType.HOTP
            TYPE_TOTP -> OtpType.TOTP
            else -> return null
        }
        val algorithm = when (raw.algorithm) {
            ALGORITHM_SHA1 -> "SHA1"
            ALGORITHM_SHA256 -> "SHA256"
            ALGORITHM_SHA512 -> "SHA512"
            else -> return null
        }
        val digits = if (raw.digits == DIGITS_ENUM_EIGHT) OTP_DIGITS_EIGHT else OTP_DIGITS_SIX

        // Google Authenticator 的 name 常为 "Issuer:account"（或 "Issuer: account"）；
        // issuer 缺失时回退 name 兜底展示
        var issuer = raw.issuer.trim()
        var account = raw.name.trim()
        if (issuer.isBlank()) {
            issuer = account
            account = ""
        } else if (account.startsWith("$issuer:")) {
            account = account.removePrefix("$issuer:").trim()
        }
        if (issuer.isBlank()) {
            return null
        }

        val config = TotpConfig(
            secret = base32Encode(raw.secret),
            period = DEFAULT_TOTP_PERIOD,
            digits = digits,
            algorithm = algorithm,
            type = type,
            counter = if (type == OtpType.HOTP) raw.counter else 0L,
        )
        return ImportedOtp(config = config, issuer = issuer, account = account)
    }

    /** RFC 4648 Base32 编码（无 padding，与 otpauth URI 密钥惯例一致）。 */
    private fun base32Encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val output = StringBuilder((data.size * BITS_PER_BYTE + BITS_PER_GROUP - 1) / BITS_PER_GROUP)
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl BITS_PER_BYTE) or (byte.toInt() and BYTE_MASK)
            bitsLeft += BITS_PER_BYTE
            while (bitsLeft >= BITS_PER_GROUP) {
                val index = (buffer shr (bitsLeft - BITS_PER_GROUP)) and BITS_GROUP_MASK
                output.append(BASE32_ALPHABET[index])
                bitsLeft -= BITS_PER_GROUP
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (BITS_PER_GROUP - bitsLeft)) and BITS_GROUP_MASK
            output.append(BASE32_ALPHABET[index])
        }
        return output.toString()
    }

    /** protobuf wire type 常量。 */
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5
    private const val WIRE_TYPE_MASK = 0x07
    private const val VARINT_TAG_BITS = 3
    private const val VARINT_GROUP_BITS = 7
    private const val VARINT_MAX_SHIFTS = 64
    private const val VARINT_CONTINUATION = 0x80
    private const val VARINT_PAYLOAD_MASK = 0x7F
    private const val BASE64_BLOCK = 4
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_GROUP = 5
    private const val BITS_GROUP_MASK = 0x1F
    private const val BYTE_MASK = 0xFF
    private const val DEFAULT_TOTP_PERIOD = 30
    private const val PARTS_WITH_VALUE = 2

    /** 归一化后的码长（digits 枚举 → 实际位数）。 */
    private const val OTP_DIGITS_SIX = 6
    private const val OTP_DIGITS_EIGHT = 8

    /** OtpParameters 字段的缺省值与读取容器（protobuf 缺省：SHA1 / 6 位 / TOTP）。 */
    private class OtpFields {
        var secret: ByteArray? = null
        var name: String = ""
        var issuer: String = ""
        var algorithm: Int = ALGORITHM_SHA1
        var digits: Int = DIGITS_ENUM_SIX
        var type: Int = TYPE_TOTP
        var counter: Long = 0L
    }

    /** migration 中间结构（枚举原值，由 [toImportedOtp] 归一化）。 */
    private data class MigrationOtpRaw(
        val secret: ByteArray,
        val name: String,
        val issuer: String,
        val algorithm: Int,
        val digits: Int,
        val type: Int,
        val counter: Long,
    )

    /**
     * 手工 protobuf wire format 读取器（对齐 Bastion ProtoReader）。
     * 仅支持本项目需要的最小子集：varint / 长度定界 / 跳过未知字段。
     */
    @Suppress("MagicNumber")
    private class ProtoReader(private val data: ByteArray) {
        private var position: Int = 0

        fun isAtEnd(): Boolean = position >= data.size

        fun readTag(): Int? {
            if (isAtEnd()) return null
            return readVarInt32()
        }

        fun readVarInt32(): Int? {
            return readVarInt64()?.toInt()
        }

        fun readVarInt64(): Long? {
            var shift = 0
            var result = 0L
            while (shift < VARINT_MAX_SHIFTS) {
                if (position >= data.size) return null
                val byte = data[position++].toInt() and 0xFF
                result = result or ((byte and VARINT_PAYLOAD_MASK).toLong() shl shift)
                if (byte and VARINT_CONTINUATION == 0) {
                    return result
                }
                shift += VARINT_GROUP_BITS
            }
            return null
        }

        fun readBytes(): ByteArray? {
            val length = readVarInt32() ?: return null
            if (length < 0 || position + length > data.size) return null
            val result = data.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun readString(): String? {
            val bytes = readBytes() ?: return null
            return String(bytes, StandardCharsets.UTF_8)
        }

        fun skipField(wireType: Int): Boolean {
            return when (wireType) {
                WIRE_VARINT -> readVarInt64() != null
                WIRE_FIXED64 -> skipBytes(BYTES_FIXED64)
                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarInt32() ?: return false
                    skipBytes(length)
                }
                WIRE_FIXED32 -> skipBytes(BYTES_FIXED32)
                else -> false
            }
        }

        private fun skipBytes(count: Int): Boolean {
            if (count < 0 || position + count > data.size) {
                return false
            }
            position += count
            return true
        }

        private companion object {
            const val BYTES_FIXED64 = 8
            const val BYTES_FIXED32 = 4
        }
    }
}
