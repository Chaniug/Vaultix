/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.attachment

import com.google.common.truth.Truth.assertThat
import io.vaultix.crypto.SymmetricCryptoKey
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Bitwarden 附件密文的解密（2026-10-21）。
 *
 * ## 为什么用「手工按官方格式加密 → 用被测代码解」而不是 mock
 *
 * 这是密码学代码，**mock 什么都证明不了**。唯一有意义的验证是：按协议规格独立写出
 * 一份密文，看被测实现能否还原。所以下面的 [encryptByProtocol] 是**照协议重新实现**
 * 的加密侧，与被测代码不共用任何一行（否则就是"用 bug 验 bug"）。
 *
 * ## 每条断言都配了反证
 *
 * 「解出来对」有假绿风险（比如实现压根不校验、直接把密文当明文吐出来也能"长度对"）。
 * ⇒ 每条正向用例都同时钉住"篡改后必须失败"。
 */
class BitwardenAttachmentCryptoTest {

    // ------------------------------------------------------------ 正向

    @Test
    fun `多字节明文往返一致`() {
        val key = randomKey()
        val plain = "恢复码：A1B2-C3D4-E5F6\n第二行带中文".toByteArray()

        val out = ByteArrayOutputStream()
        val written = BitwardenAttachmentCrypto.decrypt(encryptByProtocol(plain, key), key, out)

        assertThat(out.toByteArray()).isEqualTo(plain)
        assertThat(written).isEqualTo(plain.size.toLong())
    }

    @Test
    fun `空文件也能往返`() {
        val key = randomKey()
        val out = ByteArrayOutputStream()

        BitwardenAttachmentCrypto.decrypt(encryptByProtocol(ByteArray(0), key), key, out)

        assertThat(out.size()).isEqualTo(0)
    }

    /**
     * 跨多个 16KB 缓冲块的明文（附件本来就可能很大）。
     *
     * ⚠️ 这条是**必须的**：单块的往返无法暴露"分块边界算错"这类 bug ——
     * 而分块正是 [BitwardenAttachmentCrypto.decrypt] 里唯一有状态的地方。
     */
    @Test
    fun `大于缓冲块的明文往返一致`() {
        val key = randomKey()
        val plain = ByteArray(16 * 1024 * 3 + 7).also(SecureRandom()::nextBytes)

        val out = ByteArrayOutputStream()
        BitwardenAttachmentCrypto.decrypt(encryptByProtocol(plain, key), key, out)

        assertThat(out.toByteArray()).isEqualTo(plain)
    }

    @Test
    fun `AES 块整数倍的明文也能正确去填充`() {
        val key = randomKey()
        val plain = ByteArray(32) { it.toByte() }

        val out = ByteArrayOutputStream()
        BitwardenAttachmentCrypto.decrypt(encryptByProtocol(plain, key), key, out)

        assertThat(out.toByteArray()).isEqualTo(plain)
    }

    // ------------------------------------------------------------ 反证：必须失败

    /**
     * **篡改密文一个字节 ⇒ 必须报认证失败，且一个字节都不许写出**。
     *
     * 这条钉的是「先验 MAC 再解密」这个选择：如果实现改成"边解边验"，
     * [out] 里就会先被写进一段未认证的内容。
     */
    @Test
    fun `篡改密文必须报认证失败且不写出任何字节`() {
        val key = randomKey()
        val plain = "很敏感的内容，绝不能在被篡改后落盘".repeat(10).toByteArray()
        val tampered = encryptByProtocol(plain, key)
        // 改密文区（跳过 16B IV，落在密文里）
        tampered[IV_SIZE_OFFSET + 3] = (tampered[IV_SIZE_OFFSET + 3] + 1).toByte()

        val out = ByteArrayOutputStream()
        assertThrows(AttachmentAuthException::class.java) {
            BitwardenAttachmentCrypto.decrypt(tampered, key, out)
        }
        assertThat(out.size()).isEqualTo(0)
    }

    @Test
    fun `篡改 IV 必须报认证失败`() {
        val key = randomKey()
        val tampered = encryptByProtocol("x".repeat(64).toByteArray(), key)
        tampered[0] = (tampered[0] + 1).toByte()

        assertThrows(AttachmentAuthException::class.java) {
            BitwardenAttachmentCrypto.decrypt(tampered, key, ByteArrayOutputStream())
        }
    }

    @Test
    fun `篡改 MAC 尾缀必须报认证失败`() {
        val key = randomKey()
        val tampered = encryptByProtocol("y".repeat(64).toByteArray(), key)
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        assertThrows(AttachmentAuthException::class.java) {
            BitwardenAttachmentCrypto.decrypt(tampered, key, ByteArrayOutputStream())
        }
    }

    @Test
    fun `换了密钥必须报认证失败而不是解出垃圾`() {
        val encrypted = encryptByProtocol("内容".toByteArray(), randomKey())

        assertThrows(AttachmentAuthException::class.java) {
            BitwardenAttachmentCrypto.decrypt(encrypted, randomKey(), ByteArrayOutputStream())
        }
    }

    @Test
    fun `长度不足 48 字节必须报格式错误`() {
        assertThrows(AttachmentFormatException::class.java) {
            BitwardenAttachmentCrypto.decrypt(ByteArray(47), randomKey(), ByteArrayOutputStream())
        }
    }

    // ------------------------------------------------------------ 密钥解包

    @Test
    fun `附件密钥按前 32 字节加密后半段校验拆成 encKey 与 macKey`() {
        val raw = ByteArray(64) { it.toByte() }

        val key = BitwardenAttachmentCrypto.unwrapAttachmentKey("irrelevant") { raw.copyOf() }

        // ① 拆分位置必须与协议一致：enc=0..31、mac=32..63。
        // ⚠️ 直接比 ByteArray：转成 `List<Int>` 会因 Byte/Int 类型不同而永远不等
        //    （第一版就是这么写的，断言"看着合理"但必然红）。
        assertThat(key.encKey.useBytes { it.copyOf() })
            .isEqualTo(ByteArray(32) { it.toByte() })
        assertThat(key.macKey.useBytes { it.copyOf() })
            .isEqualTo(ByteArray(32) { (it + 32).toByte() })
    }

    /**
     * 长度不对时**必须报错而不是截断/补零**。
     *
     * 反证思路：截断出来的密钥也能"解开"某些密文（长度碰巧对上），
     * 那样失败会推迟到 MAC 校验、甚至表现为"文件内容乱码"，排查成本高得多。
     */
    @Test
    fun `附件密钥长度不对必须报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            BitwardenAttachmentCrypto.unwrapAttachmentKey("x") { ByteArray(32) }
        }
    }

    // ------------------------------------------------------------ 协议侧（独立实现）

    /**
     * 按 Bitwarden 附件格式**独立**加密：`IV ‖ AES-256-CBC-PKCS7 ‖ HMAC-SHA256(IV ‖ 密文)`。
     *
     * 刻意不复用被测代码里的任何常量/函数 —— 共用就变成"自己验自己"。
     */
    private fun encryptByProtocol(plain: ByteArray, key: SymmetricCryptoKey): ByteArray {
        val iv = ByteArray(16).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        key.encKey.useBytes { enc ->
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"), IvParameterSpec(iv))
        }
        val ciphertext = cipher.doFinal(plain)

        val mac = Mac.getInstance("HmacSHA256")
        key.macKey.useBytes { macKey -> mac.init(SecretKeySpec(macKey, "HmacSHA256")) }
        mac.update(iv)
        mac.update(ciphertext)
        val tag = mac.doFinal()

        return iv + ciphertext + tag
    }

    private fun randomKey(): SymmetricCryptoKey {
        val rng = SecureRandom()
        return SymmetricCryptoKey(
            encKey = ByteArray(32).also(rng::nextBytes),
            macKey = ByteArray(32).also(rng::nextBytes),
        )
    }

    private companion object {
        /** IV 在密文里的起始偏移恒为 0；单独命名是为了让"改的是密文不是 IV"这句自解释。 */
        const val IV_SIZE_OFFSET = 16
    }
}
