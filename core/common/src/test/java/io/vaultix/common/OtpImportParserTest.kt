/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.common

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * otpauth-migration:// 批量导入解析：测试内手工构造 protobuf 字节
 * （wire format 编码），验证解析数值与分发的双向保真。
 */
@Suppress("MagicNumber")
class OtpImportParserTest {

    // ---- 手工 protobuf 编码（测试专用，独立于被测实现）----

    private fun varint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.add(v.toByte())
                break
            }
            out.add(((v and 0x7F) or 0x80L).toByte())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    private fun lengthDelimited(field: Int, payload: ByteArray): ByteArray =
        varint(((field shl 3) or WIRE_LENGTH_DELIMITED).toLong()) +
            varint(payload.size.toLong()) + payload

    private fun varintField(field: Int, value: Long): ByteArray =
        varint((field shl 3).toLong()) + varint(value)

    private fun migrationUri(vararg params: ByteArray): String {
        // 外层 payload 为重复的 field-1 消息（每条 OtpParameters 独立 tag+length）
        val payload = params
            .map { lengthDelimited(MIGRATION_FIELD, it) }
            .reduce { acc, bytes -> acc + bytes }
        val b64 = Base64.getEncoder().encodeToString(payload)
        return "otpauth-migration://offline?data=$b64"
    }

    /** 一条标准 TOTP 参数：secret="Hello"、name="Picasa:user@google.com"、issuer="Picasa"。 */
    private fun totpParams(): ByteArray {
        val secret = "Hello".toByteArray(Charsets.UTF_8)
        val name = "Picasa:user@google.com".toByteArray(Charsets.UTF_8)
        val issuer = "Picasa".toByteArray(Charsets.UTF_8)
        return lengthDelimited(FIELD_SECRET, secret) +
            lengthDelimited(FIELD_NAME, name) +
            lengthDelimited(FIELD_ISSUER, issuer) +
            varintField(FIELD_ALGORITHM, ALG_SHA1.toLong()) +
            varintField(FIELD_DIGITS, DIGITS_SIX.toLong()) +
            varintField(FIELD_TYPE, TYPE_TOTP.toLong()) +
            varintField(FIELD_COUNTER, 1L)
    }

    /** 一条 HOTP 参数：secret="Key"、SHA256、8 位、counter=2^35（多字节 varint）。 */
    private fun hotpParams(): ByteArray {
        val secret = "Key".toByteArray(Charsets.UTF_8)
        val name = "Counter:hotp@google.com".toByteArray(Charsets.UTF_8)
        return lengthDelimited(FIELD_SECRET, secret) +
            lengthDelimited(FIELD_NAME, name) +
            lengthDelimited(FIELD_ISSUER, "Counter".toByteArray(Charsets.UTF_8)) +
            varintField(FIELD_ALGORITHM, ALG_SHA256.toLong()) +
            varintField(FIELD_DIGITS, DIGITS_EIGHT.toLong()) +
            varintField(FIELD_TYPE, TYPE_HOTP.toLong()) +
            varintField(FIELD_COUNTER, 34359738368L)
    }

    // ---- 单条 / 批量 ----

    @Test
    fun migrationSingleTotp() {
        val result = OtpImportParser.parse(migrationUri(totpParams()))
        val item = (result as OtpScanResult.Single).item
        assertEquals(OtpType.TOTP, item.config.type)
        assertEquals("JBSWY3DP", item.config.secret) // "Hello" 的 Base32
        assertEquals("Picasa", item.issuer)
        assertEquals("user@google.com", item.account)
        assertEquals("SHA1", item.config.algorithm)
        assertEquals(SIX_DIGITS, item.config.digits)
    }

    @Test
    fun migrationMultipleItems() {
        val result = OtpImportParser.parse(migrationUri(totpParams(), hotpParams()))
        val items = (result as OtpScanResult.Multiple).items
        assertEquals(2, items.size)
        val hotp = items[1]
        assertEquals(OtpType.HOTP, hotp.config.type)
        assertEquals(34359738368L, hotp.config.counter)
        assertEquals("SHA256", hotp.config.algorithm)
        assertEquals(EIGHT_DIGITS, hotp.config.digits)
        // Base32 密钥可无损还原原始字节（"Key"）
        assertEquals("Key", String(TotpGenerator.decodeBase32(hotp.config.secret), Charsets.UTF_8))
    }

    @Test
    fun migrationToleratesUrlSafeBase64Variants() {
        // 部分导出工具用 URL-safe base64（- _）且缺 padding，必须容错解码
        val outer = lengthDelimited(MIGRATION_FIELD, totpParams())
        val urlSafe = Base64.getEncoder().encodeToString(outer)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
        val result = OtpImportParser.parse("otpauth-migration://offline?data=$urlSafe")
        val item = (result as OtpScanResult.Single).item
        assertEquals("Picasa", item.issuer)
    }

    @Test
    fun migrationBadDataIsInvalidFormat() {
        assertEquals(
            OtpScanResult.InvalidFormat,
            OtpImportParser.parse("otpauth-migration://offline?data=%%%<bad>"),
        )
        assertEquals(
            OtpScanResult.InvalidFormat,
            OtpImportParser.parse("otpauth-migration://offline"),
        )
    }

    // ---- 其他分发分支 ----

    @Test
    fun phoneFactorExplicitlyUnsupported() {
        assertEquals(
            OtpScanResult.UnsupportedPhoneFactor,
            OtpImportParser.parse("phonefactor://x?data=1"),
        )
    }

    @Test
    fun garbageIsInvalidFormat() {
        assertEquals(OtpScanResult.InvalidFormat, OtpImportParser.parse("hello world !!!"))
    }

    @Test
    fun bareSecretIsSingleTotp() {
        val result = OtpImportParser.parse("JBSWY3DPEHPK3PXP")
        val item = (result as OtpScanResult.Single).item
        assertEquals(OtpType.TOTP, item.config.type)
        assertEquals("JBSWY3DPEHPK3PXP", item.config.secret)
        assertEquals("", item.issuer)
    }

    @Test
    fun otpauthUriIsSingle() {
        val result = OtpImportParser.parse(
            "otpauth://totp/ACME:alice?secret=JBSWY3DPEHPK3PXP&issuer=ACME",
        )
        val item = (result as OtpScanResult.Single).item
        assertEquals("ACME", item.issuer)
        assertEquals("alice", item.account)
    }

    @Test
    fun motpUriIsSingleWithPin() {
        val result = OtpImportParser.parse(
            "motp://Example:alice?secret=0123456789abcdef&pin=1234",
        )
        val item = (result as OtpScanResult.Single).item
        assertEquals(OtpType.MOTP, item.config.type)
        assertEquals("1234", item.config.pin)
    }

    private companion object {
        const val MIGRATION_FIELD = 1
        const val FIELD_SECRET = 1
        const val FIELD_NAME = 2
        const val FIELD_ISSUER = 3
        const val FIELD_ALGORITHM = 4
        const val FIELD_DIGITS = 5
        const val FIELD_TYPE = 6
        const val FIELD_COUNTER = 7
        const val WIRE_LENGTH_DELIMITED = 2
        const val ALG_SHA1 = 1
        const val ALG_SHA256 = 2
        const val DIGITS_SIX = 1
        const val DIGITS_EIGHT = 2
        const val TYPE_HOTP = 1
        const val TYPE_TOTP = 2
        const val SIX_DIGITS = 6
        const val EIGHT_DIGITS = 8
    }
}
