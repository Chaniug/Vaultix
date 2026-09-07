/*
 * Vaultix — core:crypto 单元测试
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 分工说明：
 * - 加解密算法细节由 AesCbcHmacTest / AesGcmTest 覆盖；
 * - KDF 内部实现由 KdfTest 覆盖；
 * - 本文件只验证「编排层」：VaultixCrypto 各步骤的组合、参数传递与密钥生命周期（清零）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultixCryptoTest {

    private val crypto = VaultixCrypto(Dispatchers.Unconfined)

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val SALT = "user@example.com"
        const val ITERATIONS = 1000
    }

    // ========== 密钥派生编排 ==========

    @Test
    fun deriveMasterKeyPbkdf2IsDeterministicAnd32Bytes() {
        val first = crypto.deriveMasterKeyPbkdf2(PASSWORD, SALT, ITERATIONS)
        val second = crypto.deriveMasterKeyPbkdf2(PASSWORD, SALT, ITERATIONS)
        try {
            assertEquals(32, first.size)
            assertEquals(first.toByteArray().toHex(), second.toByteArray().toHex())
        } finally {
            first.zero()
            second.zero()
        }
    }

    @Test
    fun deriveMasterKeyPbkdf2VariesWithSalt() {
        val withA = crypto.deriveMasterKeyPbkdf2(PASSWORD, "a@x.com", ITERATIONS)
        val withB = crypto.deriveMasterKeyPbkdf2(PASSWORD, "b@x.com", ITERATIONS)
        try {
            assertNotEquals(withA.toByteArray().toHex(), withB.toByteArray().toHex())
        } finally {
            withA.zero()
            withB.zero()
        }
    }

    /**
     * 交叉校验：生产路径（BouncyCastle）与 JCE 标准实现必须逐字节一致。
     * 二者算法同为 PBKDF2-HMAC-SHA256，互为黄金值。
     */
    @Test
    fun deriveMasterKeyPbkdf2MatchesJceImplementation() {
        val jce = crypto.pbkdf2Sha256Jce(utf8(PASSWORD), utf8(SALT), 2048, 32)
        val production = crypto.deriveMasterKeyPbkdf2(PASSWORD, SALT, 2048)
        try {
            assertEquals(jce.toHex(), production.toByteArray().toHex())
        } finally {
            jce.fill(0)
            production.zero()
        }
    }

    @Test
    fun deriveMasterKeyArgon2IsDeterministicAnd32Bytes() {
        val first = crypto.deriveMasterKeyArgon2(PASSWORD, SALT, 1, 8, 1)
        val second = crypto.deriveMasterKeyArgon2(PASSWORD, SALT, 1, 8, 1)
        try {
            assertEquals(32, first.size)
            assertEquals(first.toByteArray().toHex(), second.toByteArray().toHex())
        } finally {
            first.zero()
            second.zero()
        }
    }

    @Test
    fun deriveMasterKeyArgon2VariesWithSalt() {
        val withA = crypto.deriveMasterKeyArgon2(PASSWORD, "a@x.com", 1, 8, 1)
        val withB = crypto.deriveMasterKeyArgon2(PASSWORD, "b@x.com", 1, 8, 1)
        try {
            assertNotEquals(withA.toByteArray().toHex(), withB.toByteArray().toHex())
        } finally {
            withA.zero()
            withB.zero()
        }
    }

    @Test
    fun deriveMasterKeyArgon2RejectsNonPositiveMemory() {
        assertThrows<IllegalArgumentException> {
            crypto.deriveMasterKeyArgon2(PASSWORD, SALT, 1, 0, 1)
        }
    }

    @Test
    fun deriveMasterKeyArgon2AcceptsDefaultMemoryBudget() {
        val key = crypto.deriveMasterKeyArgon2(PASSWORD, SALT, 1, 64, 1)
        try {
            assertEquals(32, key.size)
        } finally {
            key.zero()
        }
    }

    // ========== StretchedMasterKey ==========

    @Test
    fun stretchMasterKeyProducesDistinctEncAndMacHalves() {
        val masterKey = crypto.deriveMasterKeyPbkdf2(PASSWORD, SALT, ITERATIONS)
        val stretched = crypto.stretchMasterKey(masterKey)
        try {
            assertEquals(32, stretched.encKey.size)
            assertEquals(32, stretched.macKey.size)
            assertNotEquals(stretched.encKey.toByteArray().toHex(), stretched.macKey.toByteArray().toHex())
        } finally {
            stretched.clear()
            masterKey.zero()
        }
    }

    @Test
    fun stretchMasterKeyIsDeterministic() {
        val masterKey = crypto.deriveMasterKeyPbkdf2(PASSWORD, SALT, ITERATIONS)
        val first = crypto.stretchMasterKey(masterKey)
        val second = crypto.stretchMasterKey(masterKey)
        try {
            assertEquals(first.encKey.toByteArray().toHex(), second.encKey.toByteArray().toHex())
            assertEquals(first.macKey.toByteArray().toHex(), second.macKey.toByteArray().toHex())
        } finally {
            first.clear()
            second.clear()
            masterKey.zero()
        }
    }

    @Test
    fun masterPasswordHashIsStableSingleLineBase64() {
        val masterKey = crypto.deriveMasterKeyPbkdf2(PASSWORD, SALT, ITERATIONS)
        val first = crypto.deriveMasterPasswordHash(masterKey, PASSWORD)
        val second = crypto.deriveMasterPasswordHash(masterKey, PASSWORD)
        masterKey.zero()
        assertEquals(first, second)
        assertTrue("不得包含换行", !first.contains("\n"))
        assertEquals("32 字节 → 44 字符 Base64", 44, first.length)
    }

    // ========== 条目加解密编排（type 2 主路径）==========

    @Test
    fun encryptThenDecryptRoundTripsThroughOrchestration() {
        val key = SymmetricCryptoKey.random()
        try {
            val plaintext = utf8("Hello, Vaultix!")
            val encoded = crypto.encrypt(plaintext, key)
            assertTrue("写入恒为 type 2", encoded.startsWith("2."))
            assertEquals(plaintext.toHex(), crypto.decrypt(encoded, key).toHex())
        } finally {
            key.clear()
        }
    }

    @Test
    fun encryptStringThenDecryptToStringRoundTrips() {
        val key = SymmetricCryptoKey.random()
        try {
            val encoded = crypto.encryptString("vault entry", key)
            assertEquals("vault entry", crypto.decryptToString(encoded, key))
        } finally {
            key.clear()
        }
    }

    @Test
    fun repeatedEncryptionOfSamePlaintextDiffersByIv() {
        val key = SymmetricCryptoKey.random()
        try {
            assertNotEquals(crypto.encryptString("same", key), crypto.encryptString("same", key))
        } finally {
            key.clear()
        }
    }

    /** encrypt-then-MAC：密钥不对时必须在解密前被拦截。 */
    @Test
    fun decryptWithWrongKeyIsRejectedByMac() {
        val key = SymmetricCryptoKey.random()
        val other = SymmetricCryptoKey.random()
        try {
            val encoded = crypto.encryptString("secret", key)
            assertThrows<MacVerificationException> { crypto.decrypt(encoded, other) }
        } finally {
            key.clear()
            other.clear()
        }
    }

    // ========== 遗留 type 0（默认放行，与官方 / Bastion 一致；可显式拒绝）==========

    @Test
    fun legacyType0IsDecryptableByDefault() {
        val key = SymmetricCryptoKey.random()
        try {
            val encoded = crypto.encryptString("legacy", key)
            val body = encoded.substringAfter('.').split('|')
            val legacy = "0.${body[0]}|${body[1]}"
            val decrypted = crypto.decrypt(legacy, key)
            assertEquals("legacy", String(decrypted))
        } finally {
            key.clear()
        }
    }

    @Test
    fun legacyType0IsRejectedWhenExplicitlyForbidden() {
        val key = SymmetricCryptoKey.random()
        try {
            val encoded = crypto.encryptString("legacy", key)
            val body = encoded.substringAfter('.').split('|')
            val legacy = "0.${body[0]}|${body[1]}"
            assertThrows<LegacyCipherTypeException> {
                crypto.decrypt(legacy, key, allowLegacyWithoutMac = false)
            }
        } finally {
            key.clear()
        }
    }

    @Test
    fun unsupportedCipherTypeIsRejected() {
        val key = SymmetricCryptoKey.random()
        try {
            val unsupported = "3.AAAA|BBBB|CCCC"
            val error = assertThrows<UnsupportedCipherTypeException> { crypto.decrypt(unsupported, key) }
            assertEquals(3, error.type)
        } finally {
            key.clear()
        }
    }

    // ========== 账号对称密钥解包 ==========

    @Test
    fun decryptSymmetricKeyRecoversBothHalves() {
        val masterKey = crypto.deriveMasterKeyPbkdf2(PASSWORD, SALT, ITERATIONS)
        val stretched = crypto.stretchMasterKey(masterKey)
        val accountKey = SymmetricCryptoKey.random()
        val fullKey = ByteArray(64).also {
            System.arraycopy(accountKey.encKey.toByteArray(), 0, it, 0, 32)
            System.arraycopy(accountKey.macKey.toByteArray(), 0, it, 32, 32)
        }
        val protected = crypto.encrypt(fullKey, stretched)
        val recovered = crypto.decryptSymmetricKey(protected, stretched)
        try {
            assertEquals(accountKey.encKey.toByteArray().toHex(), recovered.encKey.toByteArray().toHex())
            assertEquals(accountKey.macKey.toByteArray().toHex(), recovered.macKey.toByteArray().toHex())
        } finally {
            fullKey.fill(0)
            recovered.clear()
            accountKey.clear()
            stretched.clear()
            masterKey.zero()
        }
    }

    // ========== Bitwarden Send ==========

    @Test
    fun sendKeyMaterialIsRandom16Bytes() {
        val first = crypto.generateSendKeyMaterial()
        val second = crypto.generateSendKeyMaterial()
        try {
            assertEquals(16, first.size)
            assertNotEquals(first.toByteArray().toHex(), second.toByteArray().toHex())
        } finally {
            first.zero()
            second.zero()
        }
    }

    @Test
    fun deriveSendKeyIsDeterministicFromSameMaterial() {
        val material = crypto.generateSendKeyMaterial()
        val first = crypto.deriveSendKey(material)
        val second = crypto.deriveSendKey(material)
        try {
            assertEquals(first.encKey.toByteArray().toHex(), second.encKey.toByteArray().toHex())
            assertEquals(first.macKey.toByteArray().toHex(), second.macKey.toByteArray().toHex())
        } finally {
            first.clear()
            second.clear()
            material.zero()
        }
    }

    @Test
    fun deriveSendKeyRejectsWrongMaterialSize() {
        val bad = SecureBytes.random(8)
        try {
            assertThrows<IllegalArgumentException> { crypto.deriveSendKey(bad) }
        } finally {
            bad.zero()
        }
    }

    @Test
    fun hashSendPasswordIsStableSingleLineBase64() {
        val material = crypto.generateSendKeyMaterial()
        val first = crypto.hashSendPassword("access-pw", material)
        val second = crypto.hashSendPassword("access-pw", material)
        material.zero()
        assertEquals(first, second)
        assertEquals(44, first.length)
    }

    // ========== GCM 编排（算法细节见 AesGcmTest）==========

    @Test
    fun gcmStringEnvelopeRoundTrips() {
        val key = SymmetricCryptoKey.random()
        try {
            val envelope = crypto.encryptGcmToString(utf8("gcm payload"), key)
            assertTrue(envelope.startsWith("VG1."))
            assertEquals("gcm payload", String(crypto.decryptGcmFromString(envelope, key)))
        } finally {
            key.clear()
        }
    }

    @Test
    fun gcmRejectsMismatchedAad() {
        val key = SymmetricCryptoKey.random()
        val sealed = crypto.encryptGcm(utf8("data"), key, aad = utf8("vault-1"))
        try {
            assertThrows<Exception> { crypto.decryptGcm(sealed, key, aad = utf8("vault-2")) }
        } finally {
            sealed.wipe()
            key.clear()
        }
    }

    // ========== 辅助能力 ==========

    @Test
    fun constantTimeEqualsComparesContentAndLength() {
        val a = byteArrayOf(1, 2, 3)
        assertTrue(crypto.constantTimeEquals(a, byteArrayOf(1, 2, 3)))
        assertTrue(!crypto.constantTimeEquals(a, byteArrayOf(1, 2, 4)))
        assertTrue(!crypto.constantTimeEquals(a, byteArrayOf(1, 2)))
    }

    @Test
    fun wipeZeroesEveryArray() {
        val a = byteArrayOf(1, 1, 1)
        val b = byteArrayOf(2, 2, 2)
        crypto.wipe(a, b)
        assertEquals(zerosHex(3), a.toHex())
        assertEquals(zerosHex(3), b.toHex())
    }

    @Test
    fun generateRandomBytesHonoursRequestedSize() {
        val bytes = crypto.generateRandomBytes(24)
        try {
            assertEquals(24, bytes.size)
        } finally {
            bytes.zero()
        }
    }

    // ========== 挂起封装（在注入的调度器上执行）==========

    @Test
    fun suspendingPbkdf2MatchesBlockingCounterpart() = runBlocking {
        val blocking = crypto.deriveMasterKeyPbkdf2(PASSWORD, SALT, ITERATIONS)
        val suspending = crypto.deriveMasterKeyPbkdf2Suspending(PASSWORD, SALT, ITERATIONS)
        try {
            assertEquals(blocking.toByteArray().toHex(), suspending.toByteArray().toHex())
        } finally {
            blocking.zero()
            suspending.zero()
        }
    }

    @Test
    fun suspendingArgon2Returns32Bytes() = runBlocking {
        val key = crypto.deriveMasterKeyArgon2Suspending(PASSWORD, SALT, 1, 8, 1)
        try {
            assertEquals(32, key.size)
        } finally {
            key.zero()
        }
    }

    // ========== 解析入口 ==========

    @Test
    fun parseCipherStringDelegatesToSharedParser() {
        val parsed = crypto.parseCipherString("2.AAAA|BBBB|CCCC")
        assertEquals(CipherType.AES_CBC_256_HMAC_SHA256_B64, parsed.type)
    }
}
