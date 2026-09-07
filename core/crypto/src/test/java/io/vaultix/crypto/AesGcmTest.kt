/*
 * Vaultix — core:crypto 单元测试：AES-256-GCM（Vaultix 新增，Docs/03 §4）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 覆盖目标：
 *   - 加解密往返（含 AAD）；
 *   - 篡改 nonce / 密文 / AAD / 密钥必须失败（GCM 标签校验由 JCE 完成，抛 AEADBadTagException）；
 *   - 信封编解码 `VG1.<b64(nonce)>|<b64(ciphertext)>` 与畸形输入的拒绝路径。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

class AesGcmTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)
    private val key = SymmetricCryptoKey.random()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun flipFirstByte(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

    // =======================================================================
    // 往返
    // =======================================================================

    @Test
    fun gcm_roundTripsPlaintextWithoutAad() {
        val plaintext = "vaultix gcm".toByteArray()
        val sealed = crypto.encryptGcm(plaintext, key)
        assertThat(crypto.decryptGcm(sealed, key).toHex()).isEqualTo(plaintext.toHex())
    }

    @Test
    fun gcm_roundTripsPlaintextWithAad() {
        val plaintext = "vaultix gcm with aad".toByteArray()
        val aad = "vault-id-42".toByteArray()
        val sealed = crypto.encryptGcm(plaintext, key, aad)
        assertThat(crypto.decryptGcm(sealed, key, aad).toHex()).isEqualTo(plaintext.toHex())
    }

    @Test
    fun gcm_roundTripsEmptyPlaintext() {
        val sealed = crypto.encryptGcm(ByteArray(0), key)
        assertThat(crypto.decryptGcm(sealed, key)).isEmpty()
    }

    @Test
    fun gcm_usesFreshNonceEachTime() {
        val plaintext = "same".toByteArray()
        val first = crypto.encryptGcm(plaintext, key)
        val second = crypto.encryptGcm(plaintext, key)

        assertThat(first.nonce.toHex()).isNotEqualTo(second.nonce.toHex())
        // nonce 相同 + 密钥相同才会导致 GCM 灾难性失败，故必须每次重新生成
        assertThat(first.nonce.size).isEqualTo(GCM_NONCE_SIZE)
    }

    @Test
    fun gcm_ciphertextCarriesSixteenByteTag() {
        val plaintext = ByteArray(10) { it.toByte() }
        val sealed = crypto.encryptGcm(plaintext, key)
        // GCM/NoPadding：密文长度 = 明文长度 + 16 字节认证标签
        assertThat(sealed.ciphertext.size).isEqualTo(plaintext.size + GCM_TAG_BYTES)
    }

    @Test
    fun gcm_roundTripsWithRawSecureBytesKey() {
        val rawKey = SecureBytes.random(32)
        val plaintext = "raw key path".toByteArray()

        val sealed = crypto.encryptGcm(plaintext, rawKey)
        assertThat(crypto.decryptGcm(sealed, rawKey).toHex()).isEqualTo(plaintext.toHex())
    }

    // =======================================================================
    // 完整性 / 认证失败路径
    // =======================================================================

    @Test
    fun decryptGcm_rejectsTamperedCiphertext() {
        val sealed = crypto.encryptGcm("integrity".toByteArray(), key)
        val tampered = GcmSealed.of(
            nonce = sealed.nonce,
            ciphertext = flipFirstByte(sealed.ciphertext),
        )
        assertThrows<AEADBadTagException> { crypto.decryptGcm(tampered, key) }
    }

    @Test
    fun decryptGcm_rejectsTamperedNonce() {
        val sealed = crypto.encryptGcm("integrity".toByteArray(), key)
        val tampered = GcmSealed.of(
            nonce = flipFirstByte(sealed.nonce),
            ciphertext = sealed.ciphertext,
        )
        assertThrows<AEADBadTagException> { crypto.decryptGcm(tampered, key) }
    }

    @Test
    fun decryptGcm_rejectsWrongKey() {
        val sealed = crypto.encryptGcm("integrity".toByteArray(), key)
        assertThrows<AEADBadTagException> { crypto.decryptGcm(sealed, SymmetricCryptoKey.random()) }
    }

    @Test
    fun decryptGcm_rejectsMismatchedAad() {
        val sealed = crypto.encryptGcm("integrity".toByteArray(), key, aad = "correct-aad".toByteArray())
        assertThrows<AEADBadTagException> { crypto.decryptGcm(sealed, key, aad = "wrong-aad".toByteArray()) }
    }

    @Test
    fun decryptGcm_rejectsMissingAadWhenEncryptedWithAad() {
        val sealed = crypto.encryptGcm("integrity".toByteArray(), key, aad = "correct-aad".toByteArray())
        assertThrows<AEADBadTagException> { crypto.decryptGcm(sealed, key, aad = null) }
    }

    @Test
    fun decryptGcm_rejectsUnexpectedAadWhenEncryptedWithoutAad() {
        val sealed = crypto.encryptGcm("integrity".toByteArray(), key)
        assertThrows<AEADBadTagException> { crypto.decryptGcm(sealed, key, aad = "unexpected".toByteArray()) }
    }

    // =======================================================================
    // 信封编解码
    // =======================================================================

    @Test
    fun envelope_roundTripsThroughString() {
        val plaintext = "envelope".toByteArray()
        val aad = "vault-1".toByteArray()

        val encoded = crypto.encryptGcmToString(plaintext, key, aad)
        assertThat(encoded).startsWith("VG1.")
        assertThat(encoded).contains("|")

        val recovered = crypto.decryptGcmFromString(encoded, key, aad)
        assertThat(recovered.toHex()).isEqualTo(plaintext.toHex())
    }

    @Test
    fun envelope_decodeRejectsBlankInput() {
        assertThrows<IllegalArgumentException> { GcmSealed.decode(" ") }
    }

    @Test
    fun envelope_decodeRejectsOversizedInput() {
        assertThrows<IllegalArgumentException> {
            GcmSealed.decode("VG1." + "A".repeat(MAX_CIPHER_STRING_LENGTH + 1))
        }
    }

    @Test
    fun envelope_decodeRejectsMissingTypePrefix() {
        assertThrows<IllegalArgumentException> {
            GcmSealed.decode("${b64(ByteArray(GCM_NONCE_SIZE))}|${b64(ByteArray(32))}")
        }
    }

    @Test
    fun envelope_decodeRejectsWrongPrefix() {
        assertThrows<IllegalArgumentException> {
            GcmSealed.decode("XX9.${b64(ByteArray(GCM_NONCE_SIZE))}|${b64(ByteArray(32))}")
        }
    }

    @Test
    fun envelope_decodeRejectsWrongPartCount() {
        assertThrows<IllegalArgumentException> {
            GcmSealed.decode("VG1.${b64(ByteArray(GCM_NONCE_SIZE))}")
        }
    }

    @Test
    fun envelope_decodeRejectsInvalidBase64() {
        assertThrows<IllegalArgumentException> {
            GcmSealed.decode("VG1.%%%invalid%%%|${b64(ByteArray(32))}")
        }
    }

    // =======================================================================
    // GcmSealed 值语义
    // =======================================================================

    @Test
    fun sealed_rejectsWrongNonceSize() {
        assertThrows<IllegalArgumentException> { GcmSealed.of(ByteArray(8), ByteArray(32)) }
    }

    @Test
    fun sealed_rejectsCiphertextWithoutTag() {
        assertThrows<IllegalArgumentException> {
            GcmSealed.of(ByteArray(GCM_NONCE_SIZE), ByteArray(GCM_TAG_BYTES - 1))
        }
    }

    @Test
    fun sealed_ofCopiesInputsAndCanWipeSources() {
        val nonce = ByteArray(GCM_NONCE_SIZE) { 1 }
        val ciphertext = ByteArray(32) { 2 }

        GcmSealed.of(nonce, ciphertext, wipeSources = true)
        assertThat(nonce.toHex()).isEqualTo(zerosHex(GCM_NONCE_SIZE))
        assertThat(ciphertext.toHex()).isEqualTo(zerosHex(32))
    }

    @Test
    fun sealed_ofDoesNotWipeSourcesByDefault() {
        val nonce = ByteArray(GCM_NONCE_SIZE) { 1 }
        GcmSealed.of(nonce, ByteArray(32) { 2 })
        assertThat(nonce.toHex()).isNotEqualTo(zerosHex(GCM_NONCE_SIZE))
    }

    @Test
    fun sealed_wipeClearsBothArrays() {
        val sealed = GcmSealed.of(ByteArray(GCM_NONCE_SIZE) { 7 }, ByteArray(32) { 8 })
        sealed.wipe()
        assertThat(sealed.nonce.toHex()).isEqualTo(zerosHex(GCM_NONCE_SIZE))
        assertThat(sealed.ciphertext.toHex()).isEqualTo(zerosHex(32))
    }

    @Test
    fun sealed_equalsAndHashCodeUseConstantTimeComparison() {
        val nonce = ByteArray(GCM_NONCE_SIZE) { 3 }
        val ciphertext = ByteArray(32) { 4 }

        val first = GcmSealed.of(nonce, ciphertext)
        val second = GcmSealed.of(nonce, ciphertext)
        val different = GcmSealed.of(nonce, ciphertext.also { it[0] = 0 })

        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
        assertThat(first).isNotEqualTo(different)
        assertThat(first).isNotEqualTo("not a sealed value")
        assertThat(first).isEqualTo(first)
    }

    @Test
    fun sealed_toStringDoesNotLeakMaterial() {
        val sealed = GcmSealed.of(ByteArray(GCM_NONCE_SIZE) { 9 }, ByteArray(48) { 10 })
        val text = sealed.toString()
        assertThat(text).doesNotContain(sealed.ciphertext.toHex())
        assertThat(text).contains("48")
    }
}
