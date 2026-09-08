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
 * OTP 算法（RFC 4226 HOTP / RFC 6238 TOTP：Base32 解码 → HMAC → 动态截断）、
 * mOTP（MD5(epoch/10 + secret + pin) 取数字前 6 位）、Yandex OTP（标准 TOTP）
 * 与 otpauth / motp URI 解析思路移植自 Bastion 项目（GPL-3.0，
 * Copyright 2025 JoyinJoester）的 util/TotpGenerator.kt 与 util/TotpUriParser.kt，
 * 按 Vaultix 架构重写为独立、无 Android 依赖的纯算法模块；数值与 RFC 一致。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 验证码类型（对齐 Bastion OtpType 与 Google Authenticator 迁移格式）。
 *
 * - [TOTP]：RFC 6238 时间型（默认）；
 * - [HOTP]：RFC 4226 计数器型（`otpauth://hotp`，携带 counter）；
 * - [STEAM]：Steam Guard（Base64 密钥 + 25 字符专属字母表，5 位）；
 * - [YANDEX]：Yandex OTP（标准 TOTP，`otpauth://yaotp`，仅类型标记不同）；
 * - [MOTP]：Mobile-OTP（MD5 型，`motp://`，步长固定 10 秒、需 PIN）。
 */
enum class OtpType { TOTP, HOTP, STEAM, YANDEX, MOTP }

/** Steam Guard 字母表（25 字符，区别于标准 Base32）。 */
private const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"

/** Steam 验证码固定码长。 */
private const val STEAM_DIGITS = 5

/** Steam 验证码固定步长（秒）。 */
private const val STEAM_PERIOD = 30

/** TOTP 默认步长（秒）与码长（buildUri 省略默认段用）。 */
private const val DEFAULT_PERIOD = 30
private const val DEFAULT_DIGITS = 6

/** mOTP 固定步长（秒）与码长。 */
private const val MOTP_PERIOD = 10
private const val MOTP_DIGITS = 6

// URI 编解码辅助常量
private const val HEX_DIGIT_OFFSET = 10
private const val HEX_BITS_PER_DIGIT = 4
private const val ESCAPE_SEQUENCE_LENGTH = 3
private const val BYTE_MASK = 0xFF

@Suppress("MagicNumber")
object TotpGenerator {

    /**
     * 按当前时间步长生成 TOTP 验证码。
     *
     * @param secret Base32 编码的密钥（大小写不敏感，忽略空白与连字符）
     * @param timeSeconds 当前 Unix 秒（默认取系统时间，便于测试注入）
     * @param period 时间步长（秒），默认 30
     * @param digits 验证码位数，1–10，默认 6
     * @param algorithm HMAC 算法名：SHA1 / SHA256 / SHA512，默认 SHA1
     */
    fun generateTotp(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1",
    ): String {
        val safeDigits = digits.coerceIn(1, 10)
        return try {
            val timeStep = timeSeconds / period
            val key = decodeBase32(secret)
            val hmac = generateHmac(key, timeStep, algorithm)
            truncateHmac(hmac, safeDigits)
        } catch (_: Exception) {
            "0".repeat(safeDigits)
        }
    }

    /**
     * 生成 HOTP 验证码（RFC 4226），基于计数器而非时间。
     * 与 [generateTotp] 共享 HMAC + 动态截断实现，仅输入是显式 counter。
     */
    fun generateHotp(
        secret: String,
        counter: Long,
        digits: Int = 6,
        algorithm: String = "SHA1",
    ): String {
        val safeDigits = digits.coerceIn(1, 10)
        return try {
            val key = decodeBase32(secret)
            val hmac = generateHmac(key, counter, algorithm)
            truncateHmac(hmac, safeDigits)
        } catch (_: Exception) {
            "0".repeat(safeDigits)
        }
    }

    /**
     * 生成 Yandex OTP 验证码。Yandex 使用标准 TOTP 算法（与 Google Authenticator
     * 兼容），仅类型标记不同（otpauth://yaotp），故直接委托 [generateTotp]。
     */
    fun generateYandexCode(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = DEFAULT_PERIOD,
        digits: Int = DEFAULT_DIGITS,
        algorithm: String = "SHA1",
    ): String = generateTotp(secret, timeSeconds, period, digits, algorithm)

    /**
     * 生成 Mobile-OTP（mOTP）验证码。
     *
     * 算法：MD5(epoch + secret + pin) 的十六进制串中依次取数字字符，凑满 6 位；
     * 不足 6 位右侧补 0。其中 epoch = Unix 秒 / 10（mOTP 步长固定 10 秒），
     * secret 为原始字符串（非 Base32）。
     */
    fun generateMobileOtp(
        secret: String,
        pin: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        return try {
            val epoch = timeSeconds / MOTP_PERIOD
            val data = "$epoch$secret$pin"
            val digest = MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString(separator = "") { String.format(Locale.US, "%02x", it) }
            val digitsOnly = hex.filter { it.isDigit() }
            if (digitsOnly.length >= MOTP_DIGITS) {
                digitsOnly.take(MOTP_DIGITS)
            } else {
                digitsOnly.padEnd(MOTP_DIGITS, '0')
            }
        } catch (_: Exception) {
            "0".repeat(MOTP_DIGITS)
        }
    }

    /** 当前验证码的剩余有效秒数（向上取整到步长边界）。 */
    fun remainingSeconds(period: Int = 30, timeSeconds: Long = System.currentTimeMillis() / 1000): Int {
        val remainder = (timeSeconds % period).toInt()
        return period - remainder
    }

    /** 当前时间步长的进度（0.0 刚刷新 → 1.0 即将刷新），用于倒计时进度条。 */
    fun progress(period: Int = 30, timeSeconds: Long = System.currentTimeMillis() / 1000): Float {
        val remaining = remainingSeconds(period, timeSeconds)
        return 1.0f - (remaining.toFloat() / period)
    }

    private fun generateHmac(key: ByteArray, counter: Long, algorithm: String): ByteArray {
        val algorithmName = "Hmac$algorithm"
        val mac = Mac.getInstance(algorithmName)
        mac.init(SecretKeySpec(key, algorithmName))
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(counter)
        return mac.doFinal(buffer.array())
    }

    /**
     * 按 [TotpConfig] 生成当前验证码（五类型统一入口）。
     * HOTP 不依赖时间（用 config.counter）；mOTP 需 config.pin。
     */
    fun generate(
        config: TotpConfig,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
    ): String = when (config.type) {
        OtpType.STEAM -> generateSteamTotp(config.secret, timeSeconds, config.period)
        OtpType.HOTP -> generateHotp(config.secret, config.counter, config.digits, config.algorithm)
        OtpType.YANDEX -> generateYandexCode(
            config.secret,
            timeSeconds,
            config.period,
            config.digits,
            config.algorithm,
        )
        OtpType.MOTP -> generateMobileOtp(config.secret, config.pin, timeSeconds)
        OtpType.TOTP -> generateTotp(
            config.secret,
            timeSeconds,
            config.period,
            config.digits,
            config.algorithm,
        )
    }

    /**
     * 生成 Steam Guard 验证码（5 位，Steam 专属 25 字符字母表）。
     *
     * 与 Bitwarden / Bastion / Keyguard 的 Steam 兼容实现一致：
     * - 密钥为 **Base64**（区别于标准 TOTP 的 Base32），解码为 20 字节；
     * - HMAC-SHA1（period 固定 30s）；
     * - 动态截断为 31 位整数后，依次对 25 字符字母表取模得 5 位码。
     *
     * @param secret Base64 编码的 Steam 共享密钥（忽略空白；自动补齐 Base64 填充位）。
     */
    fun generateSteamTotp(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = STEAM_PERIOD,
    ): String {
        val safePeriod = if (period <= 0) STEAM_PERIOD else period
        return try {
            val timeStep = timeSeconds / safePeriod
            val key = decodeBase64(secret)
            val hmac = generateHmac(key, timeStep, "SHA1")
            val offset = hmac[hmac.size - 1].toInt() and 0x0F
            val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
                ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                (hmac[offset + 3].toInt() and 0xFF)
            var code = binary.toLong() and 0x7FFFFFFF
            buildString {
                repeat(STEAM_DIGITS) {
                    append(STEAM_ALPHABET[(code % STEAM_ALPHABET.length).toInt()])
                    code /= STEAM_ALPHABET.length
                }
            }
        } catch (_: Exception) {
            "0".repeat(STEAM_DIGITS)
        }
    }

    /** Base64 解码（自动补齐填充位；Steam 密钥常缺 pad）。 */
    private fun decodeBase64(encoded: String): ByteArray {
        val clean = encoded.trim().replace(Regex("[\\s]"), "")
        if (clean.isEmpty()) return ByteArray(0)
        val pad = clean.length % 4
        val padded = if (pad != 0) clean + "=".repeat(4 - pad) else clean
        return java.util.Base64.getDecoder().decode(padded)
    }

    private fun truncateHmac(hmac: ByteArray, digits: Int): String {
        val safeDigits = digits.coerceIn(1, 10)
        val offset = hmac[hmac.size - 1].toInt() and 0x0F
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
            ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
            (hmac[offset + 3].toInt() and 0xFF)
        val otp = binary.toLong() and 0x7FFFFFFF
        return String.format(Locale.US, "%0${safeDigits}d", otp % powersOfTen(safeDigits))
    }

    private fun powersOfTen(digits: Int): Long {
        var v = 1L
        repeat(digits) { v *= 10 }
        return v
    }

    /** Base32 解码（RFC 4648 字母表，忽略空白与连字符）。 */
    fun decodeBase32(encoded: String): ByteArray {
        val clean = encoded.replace(Regex("[\\s\\-]"), "").uppercase(Locale.US)
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (char in clean) {
            val value = base32Chars.indexOf(char)
            if (value == -1) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                output.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return output.toByteArray()
    }
}

/**
 * OTP 配置（归一化后的可计算参数，五类型统一模型）。
 *
 * @param type 验证码类型（见 [OtpType]）；Steam 兼容旧标记：`type=STEAM`。
 * @param counter HOTP 计数器（仅 HOTP 使用）。
 * @param pin mOTP PIN 码（仅 mOTP 计算；Yandex URI 若携带也在此透传）。
 */
data class TotpConfig(
    val secret: String,
    val period: Int = 30,
    val digits: Int = 6,
    val algorithm: String = "SHA1",
    val type: OtpType = OtpType.TOTP,
    val counter: Long = 0,
    val pin: String = "",
) {
    /** 是否为 Steam Guard（type 为 STEAM；保留旧字段便于调用方逐步迁移）。 */
    val steam: Boolean get() = type == OtpType.STEAM
}

/**
 * 归一化后可展示的 OTP（供 UI 实时计算验证码、展示发行方/账号）。
 */
data class ParsedTotp(
    val secret: String,
    val period: Int,
    val digits: Int,
    val algorithm: String,
    val type: OtpType = OtpType.TOTP,
    val counter: Long = 0,
    val pin: String = "",
    /** otpauth label 中的发行方（issuer）；裸密钥时无。 */
    val issuer: String,
    /** otpauth label 中的账号（冒号后的部分）；裸密钥时无。 */
    val account: String,
    /** 展示用标题：优先 issuer，其次 account，再次 secret 截断。 */
    val label: String,
) {
    /** 是否为 Steam Guard（type 为 STEAM；保留旧字段便于调用方逐步迁移）。 */
    val steam: Boolean get() = type == OtpType.STEAM
}

/**
 * OTP URI 解析（兼容裸 base32 密钥降级）。
 *
 * - `otpauth://totp|hotp|yaotp/Label?secret=...&issuer=...&period=...&digits=...&algorithm=...`
 *   （hotp 附 `counter=`；`encoder=steam` 或 issuer 含 steam/yandex 时自动识别类型）
 * - `motp://Issuer:Account?secret=...&pin=...`（mOTP，步长固定 10s / 6 位）
 * - 裸 base32 串（无 scheme 前缀）→ 按默认 TOTP（6 位 / 30s / SHA1）处理，
 *   对齐 Bitwarden 实际存储（login.totp 常以裸密钥形式出现）。
 * - 解析失败返回 null（调用方降级展示原始串）。
 */
object OtpUriParser {

    private val base32Alphabet = Regex("^[A-Z2-7]+=*$")

    private val motpPattern = Regex("^motp://(.*?):(.*?)\\?(.*)$", RegexOption.IGNORE_CASE)

    fun parse(input: String): TotpConfig? {
        val normalized = input.trim()
        if (normalized.isEmpty()) return null
        return when {
            normalized.startsWith("otpauth://", ignoreCase = true) -> parseOtpAuth(normalized)
            normalized.startsWith("motp://", ignoreCase = true) -> parseMotp(normalized)
            else -> parseBareSecret(normalized)
        }
    }

    private fun parseOtpAuth(uri: String): TotpConfig? {
        val lower = uri.lowercase(Locale.US)
        if (!lower.startsWith("otpauth://")) return null
        // 结构（scheme/authority）按小写解析以忽略大小写；query 保留原串以正确还原 secret
        val authority = lower.substringAfter("otpauth://").substringBefore("/")
        if (authority != "totp" && authority != "hotp" && authority != "yaotp") return null
        val labelRaw = uriDecode(uri.substringAfter("otpauth://").substringBefore("?").substringAfter("/"))
        // label 的发行方用于 Steam / Yandex 识别；账号部分由 parseToDisplay 使用
        val labelIssuer = splitLabel(labelRaw).first
        val params = parseQuery(uri.substringAfter("?", ""))
        val secret = params["secret"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val issuerParam = params["issuer"]?.trim().orEmpty()
        val finalIssuer = issuerParam.ifBlank { labelIssuer }
        val algorithmRaw = (params["algorithm"] ?: "SHA1").uppercase(Locale.US)
        val type = detectOtpType(authority, finalIssuer, labelIssuer, algorithmRaw, params["encoder"].orEmpty())
        val counter = if (type == OtpType.HOTP) params["counter"]?.toLongOrNull() ?: 0L else 0L
        val pin = if (type == OtpType.YANDEX) params["pin"].orEmpty() else ""
        val period = params["period"]?.toIntOrNull() ?: DEFAULT_PERIOD
        val digits = if (type == OtpType.STEAM) {
            STEAM_DIGITS
        } else {
            params["digits"]?.toIntOrNull() ?: DEFAULT_DIGITS
        }
        val algorithm = if (type == OtpType.STEAM) "SHA1" else algorithmRaw
        return TotpConfig(
            secret = secret,
            period = period,
            digits = digits,
            algorithm = algorithm,
            type = type,
            counter = counter,
            pin = pin,
        )
    }

    /** 解析 `motp://Issuer:Account?secret=...&pin=...`（mOTP 固定 10s / 6 位）。 */
    private fun parseMotp(uri: String): TotpConfig? {
        val match = motpPattern.matchEntire(uri) ?: return null
        val queryParams = parseQuery(match.groupValues[3])
        val secret = queryParams["secret"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TotpConfig(
            secret = secret,
            period = MOTP_PERIOD,
            digits = MOTP_DIGITS,
            algorithm = "SHA1",
            type = OtpType.MOTP,
            pin = queryParams["pin"].orEmpty(),
        )
    }

    /**
     * 检测 OTP 类型（对齐 Bastion detectOtpType）：authority 优先（hotp/yaotp），
     * 其次 issuer/label/encoder 的 Steam、issuer 的 Yandex 特征，默认 TOTP。
     */
    private fun detectOtpType(
        authority: String,
        issuer: String,
        labelPart: String,
        algorithm: String,
        encoder: String,
    ): OtpType = when {
        authority == "hotp" -> OtpType.HOTP
        authority == "yaotp" -> OtpType.YANDEX
        isSteam(issuer, labelPart, algorithm, encoder) -> OtpType.STEAM
        issuer.contains("yandex", ignoreCase = true) -> OtpType.YANDEX
        else -> OtpType.TOTP
    }

    /** 拆 otpauth label（可能形如 `Issuer:account` 或仅 `account`）。 */
    private fun splitLabel(label: String): Pair<String, String> {
        val trimmed = label.trim()
        if (!trimmed.contains(":")) return "" to trimmed
        val issuer = trimmed.substringBefore(":").trim()
        val account = trimmed.substringAfter(":").trim()
        return issuer to account
    }

    /**
     * Steam Guard 识别：满足其一即判定为 Steam 验证码（密钥为 Base64，非 Base32）。
     * 与 Bitwarden / Bastion 的识别口径一致（encoder=steam 为 Bastion 的显式标记）。
     */
    private fun isSteam(issuer: String, labelPart: String, algorithm: String, encoder: String): Boolean {
        val s = "steam"
        return encoder.equals(s, ignoreCase = true) ||
            issuer.contains(s, ignoreCase = true) ||
            labelPart.contains(s, ignoreCase = true) ||
            algorithm.equals(s, ignoreCase = true)
    }

    /**
     * 解析并归一为可展示结构（含发行方/账号/标题）。无效输入返回 null。
     * UI 统一走此入口，无需关心裸密钥 / otpauth / motp 的差异。
     */
    fun parseToDisplay(input: String): ParsedTotp? {
        val config = parse(input) ?: return null
        val normalized = input.trim()
        val (issuer, account) = when {
            normalized.startsWith("otpauth://", ignoreCase = true) -> {
                val labelRaw = uriDecode(
                    normalized.substringAfter("otpauth://").substringBefore("?").substringAfter("/"),
                )
                val (labelIssuer, labelAccount) = splitLabel(labelRaw)
                val paramIssuer = parseQuery(normalized.substringAfter("?", ""))["issuer"]?.trim().orEmpty()
                (paramIssuer.ifBlank { labelIssuer }) to labelAccount
            }
            normalized.startsWith("motp://", ignoreCase = true) -> {
                val match = motpPattern.matchEntire(normalized)
                if (match == null) {
                    "" to ""
                } else {
                    val issuerRaw = uriDecode(match.groupValues[1]).trim()
                    val accountRaw = uriDecode(match.groupValues[2]).trim()
                    // Bastion 口径：issuer 空时回退 account，再空回退 "mOTP"
                    (issuerRaw.ifBlank { accountRaw.ifBlank { "mOTP" } }) to accountRaw
                }
            }
            else -> "" to ""
        }
        val label = issuer.takeIf { it.isNotBlank() }
            ?: account.takeIf { it.isNotBlank() }
            ?: config.secret.take(8)
        return ParsedTotp(
            secret = config.secret,
            period = config.period,
            digits = config.digits,
            algorithm = config.algorithm,
            type = config.type,
            counter = config.counter,
            pin = config.pin,
            issuer = issuer,
            account = account,
            label = label,
        )
    }

    /**
     * 由归一化 [TotpConfig] 构造可长期存储的 URI（五类型统一出口）：
     * - TOTP：`otpauth://totp/<issuer:account>?secret=...`（Bitwarden 兼容格式）；
     * - HOTP：`otpauth://hotp/...?counter=...`；
     * - YANDEX：`otpauth://yaotp/...`（pin 非空时透传）；
     * - STEAM：保留 `issuer=Steam` 并附 `encoder=steam` 标记；
     * - MOTP：`motp://issuer:account?secret=...&pin=...`。
     * 全部值按 RFC 3986 编码（Base64 密钥中的 `+ / =` 不会被截断或误解码）。
     */
    fun buildUri(config: TotpConfig, issuer: String = "", account: String = ""): String {
        return if (config.type == OtpType.MOTP) {
            buildMotpUri(config, issuer, account)
        } else {
            buildOtpAuth(config, issuer, account)
        }
    }

    private fun buildMotpUri(config: TotpConfig, issuer: String, account: String): String {
        val query = buildString {
            append("secret=").append(uriEncode(config.secret))
            if (config.pin.isNotBlank()) append("&pin=").append(uriEncode(config.pin))
        }
        return "motp://${uriEncode(issuer)}:${uriEncode(account)}?$query"
    }

    private fun buildOtpAuth(config: TotpConfig, issuer: String, account: String): String {
        val effectiveIssuer = if (config.steam) "Steam" else issuer
        val label = buildString {
            if (effectiveIssuer.isNotBlank()) append(effectiveIssuer)
            if (effectiveIssuer.isNotBlank() && account.isNotBlank()) append(":")
            append(account)
        }.ifBlank { effectiveIssuer.ifBlank { config.secret.take(8) } }
        val authority = when (config.type) {
            OtpType.HOTP -> "hotp"
            OtpType.YANDEX -> "yaotp"
            else -> "totp"
        }
        return "otpauth://$authority/${uriEncode(label)}?${otpAuthQuery(config, effectiveIssuer)}"
    }

    /** otpauth query 段（secret/issuer/counter/period/digits/algorithm/encoder/pin）。 */
    private fun otpAuthQuery(config: TotpConfig, effectiveIssuer: String): String = buildString {
        append("secret=").append(uriEncode(config.secret))
        if (effectiveIssuer.isNotBlank()) {
            append("&issuer=").append(uriEncode(effectiveIssuer))
        }
        if (config.type == OtpType.HOTP) append("&counter=").append(config.counter)
        if (config.period != DEFAULT_PERIOD && config.type != OtpType.HOTP) {
            append("&period=").append(config.period)
        }
        if (config.digits != DEFAULT_DIGITS) append("&digits=").append(config.digits)
        if (config.algorithm != "SHA1") append("&algorithm=").append(config.algorithm)
        if (config.steam) append("&encoder=steam")
        if (config.type == OtpType.YANDEX && config.pin.isNotBlank()) {
            append("&pin=").append(uriEncode(config.pin))
        }
    }

    /**
     * 旧签名兼容入口（TOTP / Steam 两用）。新代码请用 [buildUri]。
     */
    fun buildOtpAuthUri(
        secret: String,
        issuer: String = "",
        account: String = "",
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1",
        steam: Boolean = false,
    ): String = buildUri(
        TotpConfig(
            secret = secret,
            period = period,
            digits = digits,
            algorithm = algorithm,
            type = if (steam) OtpType.STEAM else OtpType.TOTP,
        ),
        issuer = issuer,
        account = account,
    )

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (segment in query.split("&")) {
            if (segment.isBlank()) continue
            val key = uriDecode(segment.substringBefore("="))
            val rawValue = segment.substringAfter("=", "")
            if (key.isNotBlank()) {
                result[key] = uriDecode(rawValue)
            }
        }
        return result
    }

    private fun parseBareSecret(raw: String): TotpConfig? {
        // 裸 base32 密钥：去除常见前缀/分隔，校验是否为合法 base32
        val candidate = raw.trim().replace(Regex("[\\s:;,]+"), "").uppercase(Locale.US)
        if (candidate.isEmpty() || !base32Alphabet.matches(candidate)) return null
        return TotpConfig(secret = candidate)
    }
}

/** 是否为 RFC 3986 unreserved 字符（uriEncode 无需转义的部分）。 */
private fun isUnreserved(byte: Int): Boolean {
    val c = byte.toChar()
    return c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~'
}

/**
 * RFC 3986 编码（unreserved 之外全部 %XX，含 `+ / =`）。
 * 对齐 android.net.Uri.encode 的语义（Base64 密钥可安全入 query）。
 */
internal fun uriEncode(raw: String): String {
    val out = StringBuilder(raw.length)
    for (byte in raw.toByteArray(Charsets.UTF_8)) {
        val value = byte.toInt() and 0xFF
        if (isUnreserved(value)) {
            out.append(value.toChar())
        } else {
            out.append('%').append(String.format(Locale.US, "%02X", value))
        }
    }
    return out.toString()
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + HEX_DIGIT_OFFSET
    in 'A'..'F' -> char - 'A' + HEX_DIGIT_OFFSET
    else -> -1
}

/**
 * RFC 3986 解码（%XX → 字节；`+` 按字面保留）。
 * 对齐 android.net.Uri.decode 的语义——注意与 URLDecoder 不同：
 * Base64 密钥中的 `+` 不会被误转为空格。
 */
internal fun uriDecode(raw: String): String {
    if (!raw.contains('%')) return raw
    val out = ByteArrayOutputStream(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        // 合法 %XX：'%' 后至少还有两个字符且均为十六进制位
        val validEscape = char == '%' && index + 2 < raw.length
        val high = if (validEscape) hexValue(raw[index + 1]) else -1
        val low = if (validEscape) hexValue(raw[index + 2]) else -1
        if (high >= 0 && low >= 0) {
            out.write((high shl HEX_BITS_PER_DIGIT) or low)
            index += ESCAPE_SEQUENCE_LENGTH
        } else {
            out.write(char.code and BYTE_MASK)
            index += 1
        }
    }
    return out.toString("UTF-8")
}
