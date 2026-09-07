/*
 * Vaultix — core:crypto 单元测试：AES-256-CBC + HMAC-SHA256（EncString type 2）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 规范依据：Docs/03-密码学与密钥管理.md §2.4
 *   - 解密必须「先验 MAC、再解密」（encrypt-then-MAC），禁止先解密后判断；
 *   - type 0（无 MAC）默认拒绝，仅 allowLegacyWithoutMac 显式放行；
 *   - 3/5/7 M0 未实现，必须抛 UnsupportedCipherTypeException。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesCbcHmacTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)
    private val key = SymmetricCryptoKey.random()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun flipFirstByte(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

    private fun ParsedCipherString.reencode(): String = buildString {
        append(type)
        append('.')
        append(b64(iv))
        append('|')
        append(b64(ciphertext))
        if (mac != null) {
            append('|')
            append(b64(mac))
        }
    }

    /** 手工构造无 MAC 的遗留 type 0 密文（用 JCE 直接加密，不经过被测的加密路径）。 */
    private fun legacyType0(plaintext: ByteArray, symmetricKey: SymmetricCryptoKey): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secret = symmetricKey.encKey.useBytes { SecretKeySpec(it, "AES") }
        cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)
        return "0.${b64(iv)}|${b64(ciphertext)}"
    }

    // =======================================================================
    // 往返
    // =======================================================================

    @Test
    fun encrypt_producesType2WithThreeParts() {
        val encoded = crypto.encryptString("hello vaultix", key)
        assertThat(encoded).startsWith("2.")

        val parsed = crypto.parseCipherString(encoded)
        assertThat(parsed.type).isEqualTo(CipherType.AES_CBC_256_HMAC_SHA256_B64)
        assertThat(parsed.iv.size).isEqualTo(16)
        assertThat(parsed.mac).isNotNull()
        assertThat(parsed.mac!!.size).isEqualTo(32)
        // 13 字节明文 → PKCS#7 填充到 16 字节
        assertThat(parsed.ciphertext.size).isEqualTo(16)
    }

    @Test
    fun decrypt_recoversStringPayload() {
        val plaintext = "correct horse battery staple"
        assertThat(crypto.decryptToString(crypto.encryptString(plaintext, key), key)).isEqualTo(plaintext)
    }

    @Test
    fun decrypt_recoversBinaryPayload() {
        val payload = ByteArray(200) { it.toByte() }
        assertThat(crypto.decrypt(crypto.encrypt(payload, key), key).toHex()).isEqualTo(payload.toHex())
    }

    @Test
    fun decrypt_recoversUtf8MultibytePayload() {
        val plaintext = "密码管理器 · 密码學 🔐"
        assertThat(crypto.decryptToString(crypto.encryptString(plaintext, key), key)).isEqualTo(plaintext)
    }

    @Test
    fun decrypt_recoversEmptyPlaintext() {
        val encoded = crypto.encrypt(ByteArray(0), key)
        // 空明文经 PKCS#7 填充后仍为 16 字节密文，不是空串
        assertThat(crypto.decrypt(encoded, key)).isEmpty()
    }

    @Test
    fun encrypt_usesFreshIvEachTime() {
        val first = crypto.encryptString("same plaintext", key)
        val second = crypto.encryptString("same plaintext", key)
        assertThat(first).isNotEqualTo(second)

        val firstIv = crypto.parseCipherString(first).iv
        val secondIv = crypto.parseCipherString(second).iv
        assertThat(firstIv.toHex()).isNotEqualTo(secondIv.toHex())
    }

    // =======================================================================
    // MAC 结构与完整性
    // =======================================================================

    /** MAC 必须是 `HMAC-SHA256(macKey, iv ‖ ciphertext)`，且长度 32 字节。 */
    @Test
    fun encrypt_macCoversIvThenCiphertext() {
        val parsed = crypto.parseCipherString(crypto.encryptString("mac coverage", key))

        val expected = key.macKey.useBytes { macKeyBytes ->
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(macKeyBytes, "HmacSHA256"))
                update(parsed.iv)
                update(parsed.ciphertext)
            }.doFinal()
        }
        assertThat(parsed.mac!!.toHex()).isEqualTo(expected.toHex())
    }

    @Test
    fun computeCbcMac_agreesWithJceHmac() {
        val iv = ByteArray(16) { 1 }
        val data = ByteArray(48) { 2 }
        val macKey = ByteArray(32) { 3 }

        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(macKey, "HmacSHA256"))
            update(iv)
            update(data)
            doFinal()
        }
        assertThat(computeCbcMac(iv, data, macKey).toHex()).isEqualTo(expected.toHex())
    }

    @Test
    fun decrypt_rejectsTamperedCiphertext() {
        val parsed = crypto.parseCipherString(crypto.encryptString("integrity", key))
        val tampered = parsed.copy(ciphertext = flipFirstByte(parsed.ciphertext)).reencode()

        assertThrows<MacVerificationException> { crypto.decrypt(tampered, key) }
    }

    @Test
    fun decrypt_rejectsTamperedMac() {
        val parsed = crypto.parseCipherString(crypto.encryptString("integrity", key))
        val tampered = parsed.copy(mac = flipFirstByte(parsed.mac!!)).reencode()

        assertThrows<MacVerificationException> { crypto.decrypt(tampered, key) }
    }

    @Test
    fun decrypt_rejectsTamperedIv() {
        val parsed = crypto.parseCipherString(crypto.encryptString("integrity", key))
        val tampered = parsed.copy(iv = flipFirstByte(parsed.iv)).reencode()

        assertThrows<MacVerificationException> { crypto.decrypt(tampered, key) }
    }

    @Test
    fun decrypt_rejectsWrongKey() {
        val encoded = crypto.encryptString("integrity", key)
        val otherKey = SymmetricCryptoKey.random()
        assertThrows<MacVerificationException> { crypto.decrypt(encoded, otherKey) }
    }

    @Test
    fun decrypt_rejectsSwappedEncAndMacKeys() {
        val encoded = crypto.encryptString("integrity", key)
        val swapped = SymmetricCryptoKey(encKey = key.macKey, macKey = key.encKey)
        assertThrows<MacVerificationException> { crypto.decrypt(encoded, swapped) }
    }

    // =======================================================================
    // type 0（遗留无 MAC）：默认放行（与官方 / Bastion 一致，历史数据兼容），
    // 显式 allowLegacyWithoutMac=false 仍可拒绝
    // =======================================================================

    @Test
    fun decrypt_acceptsLegacyType0ByDefault() {
        val plaintext = "legacy data".toByteArray(StandardCharsets.UTF_8)
        val legacy = legacyType0(plaintext, key)

        val recovered = crypto.decrypt(legacy, key)
        assertThat(recovered.toHex()).isEqualTo(plaintext.toHex())
    }

    @Test
    fun decrypt_rejectsLegacyType0WhenExplicitlyForbidden() {
        val legacy = legacyType0("legacy data".toByteArray(StandardCharsets.UTF_8), key)
        val error = assertThrows<LegacyCipherTypeException> {
            crypto.decrypt(legacy, key, allowLegacyWithoutMac = false)
        }
        assertThat(error.message).contains("outdated")
    }

    @Test
    fun decrypt_acceptsLegacyType0WithoutPrefixByDefault() {
        // 无类型前缀同样按 type 0 处理，默认放行（历史导入数据无前缀）
        val plaintext = "legacy".toByteArray(StandardCharsets.UTF_8)
        val legacy = legacyType0(plaintext, key).removePrefix("0.")
        assertThat(crypto.decrypt(legacy, key).toHex()).isEqualTo(plaintext.toHex())
    }

    // =======================================================================
    // 未实现类型
    // =======================================================================

    @Test
    fun decrypt_rejectsType3AsUnsupported() {
        val encoded = "3.${b64(ByteArray(16))}|${b64(ByteArray(32))}|${b64(ByteArray(32))}"
        assertThrows<UnsupportedCipherTypeException> { crypto.decrypt(encoded, key) }
    }

    @Test
    fun decrypt_rejectsType7AsUnsupported() {
        val encoded = "7.${b64(ByteArray(16))}|${b64(ByteArray(32))}|${b64(ByteArray(32))}"
        assertThrows<UnsupportedCipherTypeException> { crypto.decrypt(encoded, key) }
    }

    // =======================================================================
    // 账号对称密钥解包
    // =======================================================================

    /** 用 StretchedMasterKey 包裹 64 字节账号对称密钥，再解包，必须逐字节一致。 */
    @Test
    fun decryptSymmetricKey_roundTripsAccountKey() {
        val masterKey = crypto.deriveMasterKeyPbkdf2("asdfasdf", "nuser@example.com", 1_000)
        val stretched = crypto.stretchMasterKey(masterKey)
        val accountKey = SymmetricCryptoKey.random()

        val protected = crypto.encrypt(
            accountKey.encKey.toByteArray() + accountKey.macKey.toByteArray(),
            stretched,
        )
        val recovered = crypto.decryptSymmetricKey(protected, stretched)

        assertThat(recovered.encKey.toByteArray().toHex())
            .isEqualTo(accountKey.encKey.toByteArray().toHex())
        assertThat(recovered.macKey.toByteArray().toHex())
            .isEqualTo(accountKey.macKey.toByteArray().toHex())
    }
}
