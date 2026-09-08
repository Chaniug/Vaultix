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
 * TOTP 算法（RFC 6238：Base32 解码 → HMAC → 动态截断）与 otpauth URI 解析思路
 * 移植自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * util/TotpGenerator.kt 与 util/TotpUriParser.kt，按 Vaultix 架构重写为独立、
 * 无 Android 依赖的纯算法模块（便于单元复用与测试）；数值与 RFC 6238 一致。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP 生成与 otpauth URI 解析（RFC 6238）。
 *
 * Bitwarden 的 `login.totp` 解密后是「otpauth://totp/...?secret=...」或裸 base32 密钥，
 * 经 [OtpUriParser] 归一为 [TotpConfig] 后由 [TotpGenerator] 实时算出当前验证码。
 */
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

    private fun truncateHmac(hmac: ByteArray, digits: Int): String {
        val safeDigits = digits.coerceIn(1, 10)
        val offset = hmac[hmac.size - 1].toInt() and 0x0F
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
            ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
            (hmac[offset + 3].toInt() and 0xFF)
        val otp = binary.toLong() and 0x7FFFFFFF
        return String.format(java.util.Locale.US, "%0${safeDigits}d", otp % powersOfTen(safeDigits))
    }

    private fun powersOfTen(digits: Int): Long {
        var v = 1L
        repeat(digits) { v *= 10 }
        return v
    }

    /** Base32 解码（RFC 4648 字母表，忽略空白与连字符）。 */
    fun decodeBase32(encoded: String): ByteArray {
        val clean = encoded.replace(Regex("[\\s\\-]"), "").uppercase(java.util.Locale.US)
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
 * TOTP 配置（归一化后的可计算参数）。
 */
data class TotpConfig(
    val secret: String,
    val period: Int = 30,
    val digits: Int = 6,
    val algorithm: String = "SHA1",
)

/**
 * otpauth URI 解析（兼容裸 base32 密钥降级）。
 *
 * - `otpauth://totp/Label?secret=...&issuer=...&period=...&digits=...&algorithm=...`
 * - 裸 base32 串（无 `otpauth://` 前缀）→ 按默认 TOTP（6 位 / 30s / SHA1）处理，
 *   对齐 Bitwarden 实际存储（login.totp 常以裸密钥形式出现）。
 * - 解析失败返回 null（调用方降级展示原始串）。
 */
object OtpUriParser {

    private val base32Alphabet = Regex("^[A-Z2-7]+=*$")

    fun parse(input: String): TotpConfig? {
        val normalized = input.trim()
        if (normalized.isEmpty()) return null
        return if (normalized.startsWith("otpauth://", ignoreCase = true)) {
            parseOtpAuth(normalized)
        } else {
            parseBareSecret(normalized)
        }
    }

    private fun parseOtpAuth(uri: String): TotpConfig? {
        val lower = uri.lowercase(java.util.Locale.US)
        if (!lower.startsWith("otpauth://")) return null
        // 结构（scheme/authority）按小写解析以忽略大小写；query 保留原串以正确还原 secret
        val authority = lower.substringAfter("otpauth://").substringBefore("/")
        if (authority != "totp" && authority != "hotp") return null
        val params = parseQuery(uri.substringAfter("?", ""))
        val secret = params["secret"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val period = params["period"]?.toIntOrNull() ?: 30
        val digits = params["digits"]?.toIntOrNull() ?: 6
        val algorithm = (params["algorithm"] ?: "SHA1").uppercase(java.util.Locale.US)
        return TotpConfig(secret = secret, period = period, digits = digits, algorithm = algorithm)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (segment in query.split("&")) {
            if (segment.isBlank()) continue
            val key = segment.substringBefore("=")
            val rawValue = segment.substringAfter("=", "")
            if (key.isNotBlank()) {
                result[key] = runCatching { java.net.URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
            }
        }
        return result
    }

    private fun parseBareSecret(raw: String): TotpConfig? {
        // 裸 base32 密钥：去除常见前缀/分隔，校验是否为合法 base32
        val candidate = raw.trim().replace(Regex("[\\s:;,]+"), "").uppercase(java.util.Locale.US)
        if (candidate.isEmpty() || !base32Alphabet.matches(candidate)) return null
        return TotpConfig(secret = candidate)
    }
}
