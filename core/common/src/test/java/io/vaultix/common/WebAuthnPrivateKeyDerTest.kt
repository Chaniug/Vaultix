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
