/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「KeePass 的 OTP 字段有多种历史约定」这一事实与字段清单参考 Bastion
 * （GPL-3.0，Copyright 2025 JoyinJoester）的 `keepass/KeePassTotpCodec.kt`：
 *   - 密钥字段：`otp`（KeePassXC） / `TOTP Seed`（KeePass 2.x + 插件） /
 *     `TimeOtp-Secret-Base32|Hex|Base64`（KeePass 2.47+ 官方）；
 *   - 参数既有**独立字段**（`TOTP Period` / `TOTP Digits` / `TOTP Algorithm` /
 *     `HOTP Counter` / `OTP Type`），也有**位置式合并字段**
 *     `TOTP Settings = "30;6;SHA1"`（KeePassXC / KeePassOTP 惯用），两者都要认；
 *   - 位置式 token 里 `HOTP` 与 counter 的写法也要认（否则 HOTP 条目会被当 TOTP 算错）。
 * 本文件为独立实现：只做「KeePass 字段集 → `otpauth://` URI」的归一，
 * 真正的解析与算码复用项目既有的 `OtpUriParser` / `TotpGenerator`（core:common）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import io.vaultix.common.OtpType
import io.vaultix.common.OtpUriParser
import io.vaultix.common.TotpConfig
import java.util.Base64
import java.util.Locale

/**
 * KeePass 侧 OTP 字段集（键名大小写不敏感；值已由调用方 `.content` 解密）。
 *
 * 用「显式字段」而不是 `Map<String, String>`：KeePass 的字段名有七八种拼法，
 * 让调用方去找键会把这份知识散到各处；集中成一个入参对象后，
 * 码本只在这里维护（见 [KdbxTotpCodec.fromFields]）。
 */
data class KdbxOtpFields(
    val otp: String = "",
    val seed: String = "",
    val settings: String = "",
    val period: String = "",
    val digits: String = "",
    val algorithm: String = "",
    val counter: String = "",
    val type: String = "",
    /** 密钥的**编码**（`TimeOtp-Secret-Hex` / `-Base64` 两种非 base32 形态）。 */
    val secretEncoding: SecretEncoding = SecretEncoding.Base32,
) {
    /** 密钥编码：KeePass 2.47+ 用三个不同字段名表达，这里归一成枚举。 */
    enum class SecretEncoding { Base32, Hex, Base64 }
}

/**
 * KeePass OTP 字段 → `otpauth://` URI（无法识别返回 null）。
 *
 * 为什么输出 URI 而不是直接算码：Vaultix 的 UI、验证码页、自动填充**全部**只认
 * `VaultItem.totp`（即 otpauth / 裸密钥串），转成同一个中间表示后
 * 「KDBX 的验证码」与「Bitwarden 的验证码」走完全相同的下游链路。
 */
object KdbxTotpCodec {

    /** KeePassXC / KeePassOTP 的密钥字段。 */
    const val FIELD_OTP = "otp"

    /** KeePass 2.x「TOTP Seed」约定（KeePass2Android 等插件）。 */
    const val FIELD_TOTP_SEED = "TOTP Seed"

    /** 位置式参数合并字段：`period;digits;algorithm[;HOTP;counter]`。 */
    const val FIELD_TOTP_SETTINGS = "TOTP Settings"

    const val FIELD_TOTP_PERIOD = "TOTP Period"
    const val FIELD_TOTP_DIGITS = "TOTP Digits"
    const val FIELD_TOTP_ALGORITHM = "TOTP Algorithm"
    const val FIELD_OTP_TYPE = "OTP Type"
    const val FIELD_HOTP_COUNTER = "HOTP Counter"

    /** KeePass 2.47+ 官方字段（三种密钥编码各一个字段名）。 */
    const val FIELD_TIMEOTP_BASE32 = "TimeOtp-Secret-Base32"
    const val FIELD_TIMEOTP_HEX = "TimeOtp-Secret-Hex"
    const val FIELD_TIMEOTP_BASE64 = "TimeOtp-Secret-Base64"
    const val FIELD_TIMEOTP_PERIOD = "TimeOtp-Period"
    const val FIELD_TIMEOTP_LENGTH = "TimeOtp-Length"
    const val FIELD_TIMEOTP_ALGORITHM = "TimeOtp-Algorithm"

    /**
     * 全部与 OTP 有关的字段名（**大小写不敏感**比较）。
     *
     * 用途有二：① 映射自定义字段时把它们排除（否则「TOTP Seed」会作为一个自定义字段
     * 出现在详情页，还带着明文密钥）；② 判定「这个条目有没有验证码」。
     */
    val OTP_FIELD_NAMES: Set<String> = setOf(
        FIELD_OTP,
        FIELD_TOTP_SEED,
        FIELD_TOTP_SETTINGS,
        FIELD_TOTP_PERIOD,
        FIELD_TOTP_DIGITS,
        FIELD_TOTP_ALGORITHM,
        FIELD_OTP_TYPE,
        FIELD_HOTP_COUNTER,
        FIELD_TIMEOTP_BASE32,
        FIELD_TIMEOTP_HEX,
        FIELD_TIMEOTP_BASE64,
        FIELD_TIMEOTP_PERIOD,
        FIELD_TIMEOTP_LENGTH,
        FIELD_TIMEOTP_ALGORITHM,
        "period",
        "digits",
        "algorithm",
    ).map { it.lowercase(Locale.ROOT) }.toSet()

    /** 该字段名是否属于 OTP 家族（大小写不敏感）。 */
    fun isOtpFieldName(name: String): Boolean = name.lowercase(Locale.ROOT) in OTP_FIELD_NAMES

    /**
     * 字段集 → `otpauth://` URI（无可用密钥返回 null）。
     *
     * @param fields KeePass 字段值（见 [KdbxOtpFields]）。
     * @param title 条目名（otpauth label 的兜底发行方）。
     * @param account 条目用户名（otpauth label 的账号部分）。
     */
    fun toOtpAuthUri(fields: KdbxOtpFields, title: String = "", account: String = ""): String? {
        val secret = normalizeSecret(fields) ?: return null
        // `otp` 字段可能**直接就是一条完整 URI**（KeePassXC 支持这么存）。
        // 必须在归一化**之前**分流：否则会把整条 URI 当成密钥再包一层 otpauth
        //（得到 `secret=otpauth%3A%2F%2F...` 这种必然算不出码的结果）。
        if (secret.startsWith("otpauth://", ignoreCase = true) ||
            secret.startsWith("motp://", ignoreCase = true)
        ) {
            return secret
        }
        val settings = resolveSettings(fields)
        val issuer = title.trim()
        val label = when {
            issuer.isBlank() -> account.trim()
            account.isBlank() -> issuer
            else -> "$issuer:${account.trim()}"
        }
        return OtpUriParser.buildUri(
            config = TotpConfig(
                secret = secret,
                period = settings.period,
                digits = settings.digits,
                algorithm = settings.algorithm,
                type = settings.type,
                counter = settings.counter,
            ),
            issuer = issuer,
            account = account.trim().ifBlank { label },
        )
    }

    /** 归一化后的参数（默认值与 KeePass 一致：30s / 6 位 / SHA1 / TOTP）。 */
    data class Settings(
        val period: Int = DEFAULT_PERIOD,
        val digits: Int = DEFAULT_DIGITS,
        val algorithm: String = DEFAULT_ALGORITHM,
        val type: OtpType = OtpType.TOTP,
        val counter: Long = 0L,
    )

    /**
     * 解析参数：**先独立字段、再位置式合并字段**（与 Bastion 同序，理由同源 ——
     * 独立字段是显式写入的意图，位置式是历史遗留的兜底）。
     */
    fun resolveSettings(fields: KdbxOtpFields): Settings {
        // 位置式 token 的填充位：`30;6;SHA1` 里第一个数字是 period、第二个是 digits。
        // 用**局部**标记而不是「是否仍等于默认值」：后者在用户真的写了 `30` 时会串位
        //（把 6 当成 period 再填一遍），且 OBJECT 上的可变状态会被并发解锁的两个库互相污染。
        val positional = parsePositionalSettings(fields.settings)
        return Settings(
            period = (fields.period.toIntOrNull() ?: positional.period)
                .takeIf { it > 0 } ?: DEFAULT_PERIOD,
            digits = (fields.digits.toIntOrNull() ?: positional.digits)
                .coerceIn(MIN_DIGITS, MAX_DIGITS),
            algorithm = fields.algorithm.trim().takeIf { it.isNotEmpty() }
                ?.uppercase(Locale.ROOT) ?: positional.algorithm,
            type = resolveType(fields, positional),
            counter = (fields.counter.toLongOrNull() ?: positional.counter).coerceAtLeast(0L),
        )
    }

    /**
     * 位置式 `TOTP Settings` 解析（KeePassXC / KeePassOTP 的惯用写法）。
     *
     * token 可以是纯数字（前两个数字依次是 period / digits）、`key=value`、
     * `SHA*` 算法名、或 `HOTP` 标记 —— 四种写法混在同一个字段里，全部要认。
     */
    private fun parsePositionalSettings(settings: String): Settings {
        var period = DEFAULT_PERIOD
        var digits = DEFAULT_DIGITS
        var algorithm = DEFAULT_ALGORITHM
        var type = OtpType.TOTP
        var counter = 0L
        var periodFilled = false
        var digitsFilled = false

        settings.split(';', ',', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                if (token.contains('=')) {
                    val parsed = parseKeyValueToken(token)
                    parsed.period?.let {
                        period = it
                        periodFilled = true
                    }
                    parsed.digits?.let {
                        digits = it
                        digitsFilled = true
                    }
                    parsed.algorithm?.let { algorithm = it }
                    parsed.counter?.let {
                        counter = it
                        type = OtpType.HOTP
                    }
                } else {
                    val number = token.toIntOrNull()
                    when {
                        number != null && !periodFilled -> {
                            period = number
                            periodFilled = true
                        }

                        number != null && !digitsFilled -> {
                            digits = number
                            digitsFilled = true
                        }

                        token.startsWith("SHA", ignoreCase = true) ->
                            algorithm = token.uppercase(Locale.ROOT)

                        token.equals("HOTP", ignoreCase = true) -> type = OtpType.HOTP
                    }
                }
            }
        return Settings(period = period, digits = digits, algorithm = algorithm, type = type, counter = counter)
    }

    /** OTP 类型：独立字段 > 独立 counter 字段 > 位置式 token（显式意图优先）。 */
    private fun resolveType(fields: KdbxOtpFields, positional: Settings): OtpType = when {
        fields.type.equals("hotp", ignoreCase = true) -> OtpType.HOTP
        fields.counter.toLongOrNull() != null -> OtpType.HOTP
        else -> positional.type
    }

    /**
     * 取密钥并归一到 **base32**（[OtpUriParser] 只吃 base32 或 steam 的 base64）。
     *
     * `TimeOtp-Secret-Hex` / `-Base64` 两种形态必须真解码成字节再转 base32：
     * 直接把十六进制串塞进 base32 解析器会**静默算错码**（字符集恰好重叠一部分），
     * 这是本文件最值得写单测的一处。
     */
    private fun normalizeSecret(fields: KdbxOtpFields): String? {
        val raw = sequenceOf(fields.seed, fields.otp)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        // `otp` 字段可能直接就是 otpauth URI（KeePassXC 支持存完整 URI）——
        // 那种情况原样交给下游解析器，不要试图"归一化"它。
        if (raw.startsWith("otpauth://", ignoreCase = true) ||
            raw.startsWith("motp://", ignoreCase = true)
        ) {
            return raw
        }
        val bytes = when (fields.secretEncoding) {
            KdbxOtpFields.SecretEncoding.Base32 -> return stripSecretNoise(raw)
            KdbxOtpFields.SecretEncoding.Hex -> decodeHex(raw) ?: return null
            KdbxOtpFields.SecretEncoding.Base64 -> decodeBase64(raw) ?: return null
        }
        return base32Encode(bytes)
    }

    /** 去掉 base32 里常见的分隔与空白（`JBSW Y3DP` / `JBSW-Y3DP` 都合法）。 */
    private fun stripSecretNoise(raw: String): String =
        raw.replace(Regex("[\\s\\-]"), "").uppercase(Locale.ROOT)

    /**
     * 解析一个 `key=value` token（位置式字段里的键值写法的兼容层）。
     *
     * @return `(period, digits, algorithm, counter)`，未出现的项为 null。
     */
    private fun parseKeyValueToken(token: String): KeyValueToken {
        val key = token.substringBefore('=').trim().lowercase(Locale.ROOT)
        val value = token.substringAfter('=', "").trim()
        return when (key) {
            "period", "step", "time_step" -> KeyValueToken(period = value.toIntOrNull())
            "digits", "length" -> KeyValueToken(digits = value.toIntOrNull())
            "algorithm", "algo", "digest" -> KeyValueToken(
                algorithm = value.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
            )

            "counter" -> KeyValueToken(counter = value.toLongOrNull())
            else -> KeyValueToken()
        }
    }

    /** [parseKeyValueToken] 的四元组结果（用具名 data class 而非 Pair 套 Pair）。 */
    private data class KeyValueToken(
        val period: Int? = null,
        val digits: Int? = null,
        val algorithm: String? = null,
        val counter: Long? = null,
    )

    private const val DEFAULT_PERIOD = 30
    private const val DEFAULT_DIGITS = 6
    private const val DEFAULT_ALGORITHM = "SHA1"
    private const val MIN_DIGITS = 1
    private const val MAX_DIGITS = 10

    /** base32（RFC 4648）字母表。 */
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8

    /** 一个 base32 字符取 5 位 → 掩码 `0b11111`（detekt MagicNumber）。 */
    private const val BASE32_CHAR_MASK = 0x1F

    /** 单字节掩码（detekt MagicNumber）。 */
    private const val BYTE_MASK = 0xFF

    /** base64 的块大小（补填充位用；detekt MagicNumber）。 */
    private const val BASE64_BLOCK = 4
    private const val HEX_RADIX = 16
    private const val NIBBLE_SHIFT = 4

    private fun decodeHex(value: String): ByteArray? {
        val clean = value.filterNot { it.isWhitespace() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = clean[i * 2].digitToIntOrNull(HEX_RADIX) ?: return null
            val lo = clean[i * 2 + 1].digitToIntOrNull(HEX_RADIX) ?: return null
            out[i] = ((hi shl NIBBLE_SHIFT) or lo).toByte()
        }
        return out
    }

    private fun decodeBase64(value: String): ByteArray? = runCatching {
        val clean = value.filterNot { it.isWhitespace() }
        // base64 的填充：长度补齐到 4 的倍数（KeePass 里存的常不带 `=`）
        val missingPad = (BASE64_BLOCK - clean.length % BASE64_BLOCK) % BASE64_BLOCK
        Base64.getDecoder().decode(clean + "=".repeat(missingPad))
    }.getOrNull()

    /** 字节 → 无填充 base32（OTP 密钥的通用表示）。 */
    internal fun base32Encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in bytes) {
            buffer = (buffer shl BITS_PER_BYTE) or (byte.toInt() and BYTE_MASK)
            bitsLeft += BITS_PER_BYTE
            while (bitsLeft >= BITS_PER_CHAR) {
                out.append(BASE32_ALPHABET[(buffer shr (bitsLeft - BITS_PER_CHAR)) and BASE32_CHAR_MASK])
                bitsLeft -= BITS_PER_CHAR
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET[(buffer shl (BITS_PER_CHAR - bitsLeft)) and BASE32_CHAR_MASK])
        }
        return out.toString()
    }
}
