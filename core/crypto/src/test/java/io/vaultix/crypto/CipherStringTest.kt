/*
 * Vaultix — core:crypto 单元测试
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * EncString（CipherString）解析测试：正常形态 + 畸形输入的拒绝路径。
 * 对应 Bastion `BitwardenCrypto.parseCipherString` 的行为契约，含其长度上限保护。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class CipherStringTest {

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private val iv = ByteArray(16) { 1 }
    private val data = ByteArray(32) { 2 }
    private val mac = ByteArray(32) { 3 }

    @Test
    fun parsesType2IntoIvCiphertextAndMac() {
        val parsed = parseCipherString("2.${b64(iv)}|${b64(data)}|${b64(mac)}")
        assertEquals(CipherType.AES_CBC_256_HMAC_SHA256_B64, parsed.type)
        assertEquals(iv.toHex(), parsed.iv.toHex())
        assertEquals(data.toHex(), parsed.ciphertext.toHex())
        assertEquals(mac.toHex(), parsed.mac!!.toHex())
    }

    @Test
    fun parsesType0WithoutMac() {
        val parsed = parseCipherString("0.${b64(iv)}|${b64(data)}")
        assertEquals(CipherType.AES_CBC_256_B64, parsed.type)
        assertEquals(iv.toHex(), parsed.iv.toHex())
        assertEquals(data.toHex(), parsed.ciphertext.toHex())
        assertNull(parsed.mac)
    }

    /** 与 Bastion 行为一致：无类型前缀时按遗留 type 0 处理。 */
    @Test
    fun missingTypePrefixDefaultsToLegacyType0() {
        val parsed = parseCipherString("${b64(iv)}|${b64(data)}")
        assertEquals(CipherType.AES_CBC_256_B64, parsed.type)
    }

    @Test
    fun rejectsBlankInput() {
        assertThrows<IllegalArgumentException> { parseCipherString("") }
        assertThrows<IllegalArgumentException> { parseCipherString("   ") }
    }

    @Test
    fun rejectsType2WithMissingMacPart() {
        assertThrows<IllegalArgumentException> { parseCipherString("2.${b64(iv)}|${b64(data)}") }
    }

    @Test
    fun rejectsType0WithTooFewParts() {
        assertThrows<IllegalArgumentException> { parseCipherString("0.${b64(iv)}") }
    }

    @Test
    fun rejectsNonNumericType() {
        assertThrows<IllegalArgumentException> { parseCipherString("x.${b64(iv)}|${b64(data)}") }
    }

    /** 长度上限保护：防止恶意/损坏数据导致超大内存分配。 */
    @Test
    fun rejectsOversizedInput() {
        val oversized = "2." + "A".repeat(1_100_000)
        assertThrows<IllegalArgumentException> { parseCipherString(oversized) }
    }

    @Test
    fun cipherTypeConstantsMatchBitwardenNumbers() {
        assertEquals(0, CipherType.AES_CBC_256_B64)
        assertEquals(2, CipherType.AES_CBC_256_HMAC_SHA256_B64)
    }
}
