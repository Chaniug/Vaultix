/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.common

import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Test

/**
 * 私钥 DER 形态兼容性回归（真机「Cannot parse passkey key」的根因）。
 *
 * `parseEcPrivateKey` 原先只喂标准 PKCS#8 给 provider；这两种真实形态会直接失败：
 * - **SEC1**（RFC 5915，`-----BEGIN EC PRIVATE KEY-----` 的 DER 形态）
 * - **PKCS#8 但 AlgorithmIdentifier 不带 namedCurve 参数**（WebCrypto `exportKey("pkcs8")`
 *   在部分实现的输出形态）
 */
class WebAuthnPrivateKeyDerTest {

    @Test
    fun `SEC1 ECPrivateKey 可解析`() {
        val scalar = scalar32()
        val key = WebAuthn.parseEcPrivateKey(base64(sec1(scalar)))

        assertThat(key).isNotNull()
        assertThat((key as ECPrivateKey).s.toByteArray32()).isEqualTo(scalar)
    }

    @Test
    fun `PKCS8 缺 namedCurve 参数也可解析`() {
        val scalar = scalar32()
        val wrapped = pkcs8(algorithmIdentifier(includeCurveParam = false), sec1(scalar))

        val key = WebAuthn.parseEcPrivateKey(base64(wrapped))

        assertThat(key).isNotNull()
        assertThat((key as ECPrivateKey).s.toByteArray32()).isEqualTo(scalar)
    }

    @Test
    fun `标准 PKCS8 带参数仍可解析`() {
        val wrapped = pkcs8(algorithmIdentifier(includeCurveParam = true), sec1(scalar32()))

        assertThat(WebAuthn.parseEcPrivateKey(base64(wrapped))).isNotNull()
    }

    @Test
    fun `非法输入返回 null 且诊断串不含密钥内容`() {
        assertThat(WebAuthn.parseEcPrivateKey("not-a-key!!")).isNull()
        assertThat(WebAuthn.parseEcPrivateKey(null)).isNull()

        val diagnosis = WebAuthn.describeEcPrivateKeyFailure("not-a-key!!")
        assertThat(diagnosis).isEqualTo("base64-failed")
        assertThat(WebAuthn.describeEcPrivateKeyFailure(null)).isEqualTo("blank")
    }

    /**
     * 2026-11-15 真机根因：老 KDBX 里的私钥 PEM 头/体/尾挤在**同一行**，
     * 电脑 KeePassXC 能正常登录 GitHub，Vaultix 却报 `Cannot parse passkey key`。
     * 旧实现按行剥头尾 ⇒ 唯一一行被整行丢弃 ⇒ `decode("")` 静默得到 0 字节。
     */
    @Test
    fun `单行 PEM 头体尾挤在同一行也可解析`() {
        val scalar = scalar32()
        val body = base64(pkcs8(algorithmIdentifier(includeCurveParam = true), sec1(scalar)))
        val pem = "-----BEGIN PRIVATE KEY-----$body-----END PRIVATE KEY-----"

        val key = WebAuthn.parseEcPrivateKey(pem)

        assertThat(key).isNotNull()
        assertThat((key as ECPrivateKey).s.toByteArray32()).isEqualTo(scalar)
        assertThat(WebAuthn.describeEcPrivateKeyFailure(pem)).doesNotContain("len=0")
    }

    @Test
    fun `多行 PEM 仍可解析`() {
        val scalar = scalar32()
        val body = base64(pkcs8(algorithmIdentifier(includeCurveParam = true), sec1(scalar)))
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"

        val key = WebAuthn.parseEcPrivateKey(pem)

        assertThat(key).isNotNull()
        assertThat((key as ECPrivateKey).s.toByteArray32()).isEqualTo(scalar)
    }

    @Test
    fun `空 PEM 壳被判为显式失败且带输入形态`() {
        val shell = "-----BEGIN PRIVATE KEY----------END PRIVATE KEY-----"

        assertThat(WebAuthn.parseEcPrivateKey(shell)).isNull()
        val diagnosis = WebAuthn.describeEcPrivateKeyFailure(shell)
        assertThat(diagnosis).contains("pem-empty-body")
        assertThat(diagnosis).contains("shape=pem-inline,lines=1")
        assertThat(diagnosis).doesNotContain("len=0")
    }

    @Test
    fun `诊断串报告输入形态且不含密钥内容`() {
        val scalar = scalar32()
        val body = base64(pkcs8(algorithmIdentifier(includeCurveParam = true), sec1(scalar)))
        val single = "-----BEGIN PRIVATE KEY-----$body-----END PRIVATE KEY-----"
        val multi = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"

        assertThat(WebAuthn.describeEcPrivateKeyFailure(single))
            .contains("shape=pem-inline,lines=1")
        assertThat(WebAuthn.describeEcPrivateKeyFailure(multi))
            .contains("shape=pem-block,lines=3")
        assertThat(WebAuthn.describeEcPrivateKeyFailure(single))
            .doesNotContain(body.take(BODY_PREFIX_LEN))
    }

    @Test
    fun `退化输入一律不得伪装成 0 字节 DER`() {
        val degenerate = listOf(
            "-----BEGIN PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----\n\n-----END PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----!!!!-----END PRIVATE KEY-----",
        )
        for (value in degenerate) {
            assertThat(WebAuthn.parseEcPrivateKey(value)).isNull()
            val diagnosis = WebAuthn.describeEcPrivateKeyFailure(value)
            assertThat(diagnosis).doesNotContain("len=0")
            assertThat(diagnosis).contains("shape=")
        }
    }

    // ---- 测试用 DER 构造（只处理 <128 字节的短格式长度，够用）----

    private fun scalar32(): ByteArray {
        val kp = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .genKeyPair()
        return (kp.private as ECPrivateKey).s.toByteArray32()
    }

    private fun java.math.BigInteger.toByteArray32(): ByteArray {
        val raw = toByteArray()
        val stripped = if (raw.size > SCALAR_BYTES && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        return ByteArray(SCALAR_BYTES - stripped.size) + stripped
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** SEC1 ECPrivateKey ::= SEQUENCE { version INTEGER 1, privateKey OCTET STRING } */
    private fun sec1(scalar: ByteArray): ByteArray =
        tlv(DER_SEQUENCE, tlv(DER_INTEGER, byteArrayOf(1)) + tlv(DER_OCTET_STRING, scalar))

    /** PKCS#8 PrivateKeyInfo ::= SEQUENCE { version INTEGER 0, algId, privateKey OCTET STRING } */
    private fun pkcs8(algorithmIdentifier: ByteArray, innerSec1: ByteArray): ByteArray =
        tlv(
            DER_SEQUENCE,
            tlv(DER_INTEGER, byteArrayOf(0)) + algorithmIdentifier + tlv(DER_OCTET_STRING, innerSec1),
        )

    /** AlgorithmIdentifier ::= SEQUENCE { id-ecPublicKey [, prime256v1] } */
    private fun algorithmIdentifier(includeCurveParam: Boolean): ByteArray {
        val content = OID_EC_PUBLIC_KEY + if (includeCurveParam) OID_PRIME256V1 else ByteArray(0)
        return tlv(DER_SEQUENCE, content)
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte(), content.size.toByte()) + content

    private companion object {
        const val SCALAR_BYTES = 32
        /** 断言"诊断串不含密钥内容"时截取的 body 前缀长度。 */
        const val BODY_PREFIX_LEN = 12
        const val DER_SEQUENCE = 0x30
        const val DER_INTEGER = 0x02
        const val DER_OCTET_STRING = 0x04

        /** OID 1.2.840.10045.2.1（id-ecPublicKey）的完整 DER 编码。 */
        val OID_EC_PUBLIC_KEY = byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)

        /** OID 1.2.840.10045.3.1.7（prime256v1 / secp256r1）的完整 DER 编码。 */
        val OID_PRIME256V1 = byteArrayOf(
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07,
        )
    }
}
