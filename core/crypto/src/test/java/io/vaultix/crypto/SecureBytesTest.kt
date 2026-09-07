/*
 * Vaultix — core:crypto 单元测试
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * [SecureBytes] 语义测试：拷贝语义、清零、防时序比较与防泄漏。
 * 这些是 Docs/03 第 4 节「密钥材料生命周期」的硬性要求。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBytesTest {

    @Test
    fun ofCopiesSourceByDefault() {
        val source = byteArrayOf(1, 2, 3)
        val bytes = SecureBytes.of(source)
        try {
            source[0] = 0x41
            assertEquals("默认拷贝，外部修改源数组不应影响内部", "010203", bytes.toByteArray().toHex())
        } finally {
            bytes.zero()
        }
    }

    @Test
    fun ofWithWipeSourceZeroesSourceArray() {
        val source = byteArrayOf(1, 2, 3)
        val bytes = SecureBytes.of(source, wipeSource = true)
        try {
            assertEquals(zerosHex(3), source.toHex())
            assertEquals("010203", bytes.toByteArray().toHex())
        } finally {
            bytes.zero()
        }
    }

    @Test
    fun randomHonoursRequestedSize() {
        val bytes = SecureBytes.random(24)
        try {
            assertEquals(24, bytes.size)
        } finally {
            bytes.zero()
        }
    }

    @Test
    fun emptyHasZeroSize() {
        val bytes = SecureBytes.empty()
        try {
            assertEquals(0, bytes.size)
        } finally {
            bytes.zero()
        }
    }

    @Test
    fun copyOfIsIndependentOfOriginal() {
        val original = SecureBytes.of(utf8("secret"))
        val copy = original.copyOf()
        try {
            assertEquals(original.toByteArray().toHex(), copy.toByteArray().toHex())
            original.zero()
            assertEquals("清零原对象不得影响副本", utf8("secret").toHex(), copy.toByteArray().toHex())
        } finally {
            copy.zero()
        }
    }

    @Test
    fun toByteArrayReturnsDefensiveCopy() {
        val bytes = SecureBytes.of(utf8("secret"))
        try {
            val leaked = bytes.toByteArray()
            leaked[0] = 0x41
            assertEquals(utf8("secret").toHex(), bytes.toByteArray().toHex())
        } finally {
            bytes.zero()
        }
    }

    @Test
    fun zeroFillsContentWithZeroes() {
        val bytes = SecureBytes.of(utf8("secret"))
        bytes.zero()
        assertEquals(zerosHex(6), bytes.toByteArray().toHex())
    }

    @Test
    fun zeroIsIdempotent() {
        val bytes = SecureBytes.of(utf8("secret"))
        bytes.zero()
        bytes.zero()
        assertEquals(zerosHex(6), bytes.toByteArray().toHex())
    }

    @Test
    fun equalsComparesContentNotIdentity() {
        val a = SecureBytes.of(utf8("same"))
        val b = SecureBytes.of(utf8("same"))
        val c = SecureBytes.of(utf8("diff"))
        try {
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
            assertNotEquals(a, c)
        } finally {
            a.zero()
            b.zero()
            c.zero()
        }
    }

    @Test
    fun toStringDoesNotLeakContent() {
        val bytes = SecureBytes.of(utf8("supersecret"))
        try {
            val text = bytes.toString()
            assertTrue("toString 不得包含明文，实际为: $text", !text.contains("supersecret"))
        } finally {
            bytes.zero()
        }
    }

    @Test
    fun useBytesExposesContentWithinScope() {
        val bytes = SecureBytes.of(utf8("scoped"))
        val hex = bytes.useBytes { it.toHex() }
        bytes.zero()
        assertEquals(utf8("scoped").toHex(), hex)
    }
}
