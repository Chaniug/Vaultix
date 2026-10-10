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
import org.junit.Assert.assertTrue
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
    // ==================================================================
    // ★ 相等性必须按内容（2026-10-10 修 —— data class + ByteArray 的经典坑）
    // ==================================================================

    /**
     * ★ 内容相同的两个实例必须 `equals`（`data class` 自动实现给的是**引用比较**）。
     *
     * 修之前：自动生成的 `equals` 对 `ByteArray` 字段退化成引用比较 ⇒ 两个逐字节相同的
     * 实例被判"不相等"。生产代码只用字段，所以不直接出错；真正被卡住的是**测试** ——
     * `assertEquals(parsed, expected)` 恒失败，后来的人只能退而逐字段比较，
     * 于是**将来给本类加字段时新字段会自动逃过断言**。
     */
    @Test
    fun equalContentInstancesAreEqual_notReferenceComparison() {
        val a = parseCipherString("2.${b64(iv)}|${b64(data)}|${b64(mac)}")
        val b = parseCipherString("2.${b64(iv)}|${b64(data)}|${b64(mac)}")

        // 前置确认：这**确实**是两个不同引用，否则本用例什么都没测到
        assertTrue("测试前提：两次 parse 必须是不同实例", a !== b)

        assertEquals("★ 内容相同必须相等（引用比较会在这里红）", a, b)
        assertEquals("equals 相等则 hashCode 必须相等", a.hashCode(), b.hashCode())
    }

    /** 任一分段不同就必须不相等（防"equals 永远返回 true"这种假修复）。 */
    @Test
    fun differingContentInstancesAreNotEqual() {
        val base = parseCipherString("2.${b64(iv)}|${b64(data)}|${b64(mac)}")

        val otherIv = parseCipherString("2.${b64(ByteArray(16) { 9 })}|${b64(data)}|${b64(mac)}")
        val otherCiphertext = parseCipherString("2.${b64(iv)}|${b64(ByteArray(32) { 9 })}|${b64(mac)}")
        val otherMac = parseCipherString("2.${b64(iv)}|${b64(data)}|${b64(ByteArray(32) { 9 })}")
        val otherType = parseCipherString("0.${b64(iv)}|${b64(data)}")

        assertTrue("iv 不同必须不相等", base != otherIv)
        assertTrue("密文不同必须不相等", base != otherCiphertext)
        assertTrue("mac 不同必须不相等", base != otherMac)
        assertTrue("type 不同必须不相等", base != otherType)
    }

    /**
     * ★ `mac == null` 与 `mac != null` 之间必须不相等 —— 两向都验。
     *
     * 只验一个方向会漏掉 `equals` 里 `(mac?.contentEquals(other.mac) ?: (other.mac == null))`
     * 写反的情况（比如误写成 `other.mac != null`），而那种错会让 type 0 与 type 2
     * 被判相等 —— type 0 是**无 MAC 的遗留格式**，两者混同会直接影响解密路径的判据。
     */
    @Test
    fun nullMacIsNotEqualToNonNullMac_inBothDirections() {
        val withMac = parseCipherString("2.${b64(iv)}|${b64(data)}|${b64(mac)}")
        val withoutMac = ParsedCipherString(type = 2, iv = iv, ciphertext = data, mac = null)

        assertTrue("mac=null 与 mac!=null 必须不相等", withoutMac != withMac)
        assertTrue("（反向）mac!=null 与 mac=null 必须不相等", withMac != withoutMac)

        // 两个 mac 都为 null 且其余相同 ⇒ 必须相等
        val alsoWithoutMac = ParsedCipherString(type = 2, iv = iv, ciphertext = data, mac = null)
        assertEquals("都为 null 时内容相同应相等", withoutMac, alsoWithoutMac)
        assertEquals("hashCode 也要一致", withoutMac.hashCode(), alsoWithoutMac.hashCode())
    }

    /**
     * 可作集合键（`hashCode` 与 `equals` 一致 ⇒ `HashSet` 能去重）。
     *
     * 这条是 `hashCode` 写错最常见的暴露方式：若它漏掉某个字段或用了 `mac == null` 之外
     * 的分支导致两个相等对象散列不同，`HashSet` 就会**同时留下两份**。
     */
    @Test
    fun canBeUsedAsHashSetKey_andDeduplicatesByContent() {
        val set = mutableSetOf(
            parseCipherString("2.${b64(iv)}|${b64(data)}|${b64(mac)}"),
            parseCipherString("2.${b64(iv)}|${b64(data)}|${b64(mac)}"), // 内容相同
            parseCipherString("2.${b64(iv)}|${b64(ByteArray(32) { 9 })}|${b64(mac)}"), // 不同
        )
        assertEquals("内容相同的应被去重，只剩 2 个", 2, set.size)

        val withoutMac = mutableSetOf(
            ParsedCipherString(type = 2, iv = iv, ciphertext = data, mac = null),
            ParsedCipherString(type = 2, iv = iv, ciphertext = data, mac = null),
        )
        assertEquals("mac=null 的两份也应去重", 1, withoutMac.size)
    }
}
