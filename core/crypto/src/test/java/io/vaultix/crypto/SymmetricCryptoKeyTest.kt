/*
 * Vaultix — core:crypto 单元测试：SymmetricCryptoKey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 覆盖目标：64 字节拆分、GCM 子密钥派生（不得与 CBC 复用密钥材料）、
 * clear() 后密钥材料归零（Docs/03 §4 要求密钥可清零）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SymmetricCryptoKeyTest {

    private val knownEnc = hexToBytes("063a955a1c01b4e1aacb7cd359c919b2cb61041c0c05c94c0eb76fb9a9e144b3")
    private val knownMac = hexToBytes("fbeca074de1437a1a2330ad2833138abf3be2561a3a30ae1f33e15e1a2f7ca04")

    // =======================================================================
    // 构造校验
    // =======================================================================

    @Test
    fun constructor_rejectsWrongEncKeySize() {
        val error = assertThrows<IllegalArgumentException> {
            SymmetricCryptoKey(encKey = ByteArray(31), macKey = ByteArray(32))
        }
        assertThat(error.message).contains("encKey")
    }

    @Test
    fun constructor_rejectsWrongMacKeySize() {
        val error = assertThrows<IllegalArgumentException> {
            SymmetricCryptoKey(encKey = ByteArray(32), macKey = ByteArray(16))
        }
        assertThat(error.message).contains("macKey")
    }

    @Test
    fun byteArrayConstructor_copiesInput() {
        val enc = ByteArray(32) { 1 }
        val mac = ByteArray(32) { 2 }
        val key = SymmetricCryptoKey(encKey = enc, macKey = mac)

        enc.fill(0)
        mac.fill(0)

        assertThat(key.encKey.toByteArray().toHex()).isEqualTo("01".repeat(32))
        assertThat(key.macKey.toByteArray().toHex()).isEqualTo("02".repeat(32))
    }

    @Test
    fun random_producesTwoFreshThirtyTwoByteHalves() {
        val first = SymmetricCryptoKey.random()
        val second = SymmetricCryptoKey.random()

        assertThat(first.encKey.size).isEqualTo(32)
        assertThat(first.macKey.size).isEqualTo(32)
        assertThat(first.encKey.toByteArray().toHex())
            .isNotEqualTo(second.encKey.toByteArray().toHex())
    }

    // =======================================================================
    // fromFullKey
    // =======================================================================

    @Test
    fun fromFullKey_splitsIntoEncThenMac() {
        val key = SymmetricCryptoKey.fromFullKey(knownEnc + knownMac)

        assertThat(key.encKey.toByteArray().toHex()).isEqualTo(knownEnc.toHex())
        assertThat(key.macKey.toByteArray().toHex()).isEqualTo(knownMac.toHex())
    }

    @Test
    fun fromFullKey_rejectsWrongLength() {
        val error = assertThrows<IllegalArgumentException> {
            SymmetricCryptoKey.fromFullKey(ByteArray(63))
        }
        assertThat(error.message).contains("64")
    }

    @Test
    fun constants_matchBitwardenKeyLayout() {
        assertThat(SymmetricCryptoKey.KEY_SIZE).isEqualTo(32)
        assertThat(SymmetricCryptoKey.FULL_KEY_SIZE).isEqualTo(64)
    }

    // =======================================================================
    // GCM 子密钥
    // =======================================================================

    /** `gcmKey = HKDF-Expand(encKey, info="gcm", 32)`：参考值由 Python HMAC 独立计算。 */
    @Test
    fun gcmKey_matchesReferenceVector() {
        val key = SymmetricCryptoKey(encKey = knownEnc, macKey = knownMac)
        assertThat(key.gcmKey.toByteArray().toHex())
            .isEqualTo("e767130de5931b00245c3a67adf6a3381f3b3261319c842f84b248ba12b57bbf")
    }

    @Test
    fun gcmKey_isDerivedNotReusedFromEncKey() {
        val key = SymmetricCryptoKey.random()
        assertThat(key.gcmKey.toByteArray().toHex())
            .isNotEqualTo(key.encKey.toByteArray().toHex())
        assertThat(key.gcmKey.size).isEqualTo(32)
    }

    @Test
    fun gcmKey_isCachedAndStable() {
        val key = SymmetricCryptoKey.random()
        assertThat(key.gcmKey).isSameInstanceAs(key.gcmKey)
    }

    // =======================================================================
    // clear()
    // =======================================================================

    @Test
    fun clear_zeroesEncAndMacMaterial() {
        val key = SymmetricCryptoKey.random()
        key.clear()

        assertThat(key.encKey.toByteArray().toHex()).isEqualTo(zerosHex(32))
        assertThat(key.macKey.toByteArray().toHex()).isEqualTo(zerosHex(32))
    }

    @Test
    fun clear_zeroesCachedGcmSubKey() {
        val key = SymmetricCryptoKey.random()
        val cachedGcmKey = key.gcmKey // 先派生并缓存

        key.clear()

        assertThat(cachedGcmKey.toByteArray().toHex()).isEqualTo(zerosHex(32))
    }

    @Test
    fun clear_dropsTheGcmCacheSoItIsNotResurrected() {
        val key = SymmetricCryptoKey.random()
        val firstGcm = key.gcmKey
        key.clear()
        // clear 后缓存被丢弃，再次取值会重新派生（对象不可再用，此处只验证缓存已释放）
        assertThat(key.gcmKey).isNotSameInstanceAs(firstGcm)
    }

    @Test
    fun clear_isIdempotent() {
        val key = SymmetricCryptoKey.random()
        key.clear()
        key.clear()
        assertThat(key.encKey.toByteArray().toHex()).isEqualTo(zerosHex(32))
        assertThat(key.macKey.toByteArray().toHex()).isEqualTo(zerosHex(32))
    }

    // =======================================================================
    // equals / hashCode / toString
    // =======================================================================

    @Test
    fun equals_comparesKeyMaterial() {
        val first = SymmetricCryptoKey(encKey = knownEnc, macKey = knownMac)
        val same = SymmetricCryptoKey(encKey = knownEnc.copyOf(), macKey = knownMac.copyOf())
        val other = SymmetricCryptoKey.random()

        assertThat(first).isEqualTo(same)
        assertThat(first.hashCode()).isEqualTo(same.hashCode())
        assertThat(first).isNotEqualTo(other)
        assertThat(first).isNotEqualTo("not a key")
        assertThat(first).isEqualTo(first)
    }

    @Test
    fun toString_masksKeyMaterial() {
        val key = SymmetricCryptoKey(encKey = knownEnc, macKey = knownMac)
        val text = key.toString()

        assertThat(text).isEqualTo("SymmetricCryptoKey(enc=**, mac=**)")
        assertThat(text).doesNotContain(knownEnc.toHex())
        assertThat(text).doesNotContain(knownMac.toHex())
    }
}
