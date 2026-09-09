/*
 * Vaultix — core:common 单测
 * Copyright (C) 2026 Vaultix contributors
 *
 * 验证 WebAuthn 模块 crypto 正确性：断言签名可被对应公钥校验、CBOR attestation 结构正确、
 * 各种 keyValue 形态可解析。纯 JVM（java.security），无需 Android。
 */
package io.vaultix.common

import com.google.common.truth.Truth.assertThat
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import org.junit.Test

class WebAuthnTest {

    private fun ecParams(): java.security.spec.ECParameterSpec {
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        return (kpg.genKeyPair().private as java.security.interfaces.ECPrivateKey).params
    }

    @Test
    fun `get assertion signs and verifies against public key`() {
        val key = WebAuthn.generateKeyPair()
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val clientData = WebAuthn.buildClientDataJson("webauthn.get", challenge, "https://example.com")
        val authData = WebAuthn.buildAuthenticatorData(
            rpId = "example.com",
            userPresent = true,
            userVerified = true,
            counter = 0,
            withAttested = false,
        )
        val sig = WebAuthn.signAssertion(authData, clientData, WebAuthn.parseEcPrivateKey(
            WebAuthn.base64Url(key.privateKeyPkcs8),
        )!!)

        // 用 (x,y) 重建公钥并校验签名
        val params = ecParams()
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(toBigInt(key.publicX), toBigInt(key.publicY)), params),
        )
        val message = authData + MessageDigest.getInstance("SHA-256").digest(clientData)
        val ok = Signature.getInstance("SHA256withECDSA").also {
            it.initVerify(pub); it.update(message)
        }.verify(sig)
        assertThat(ok).isTrue()

        // userHandle 参与响应
        val json = WebAuthn.buildGetResponseJson(
            key.credentialId, clientData, authData, sig, "user-handle".toByteArray(),
        )
        assertThat(json).contains("\"type\":\"public-key\"")
        assertThat(json).contains("\"userHandle\"")
    }

    @Test
    fun `parseEcPrivateKey handles pkcs8 base64url and raw scalar`() {
        val key = WebAuthn.generateKeyPair()
        val fromPkcs8 = WebAuthn.parseEcPrivateKey(WebAuthn.base64Url(key.privateKeyPkcs8))
        assertThat(fromPkcs8).isNotNull()

        // 裸私钥标量（ECPrivateKey.getS() 原始字节，可能不足 32）
        val sBytes = run {
            val priv = KeyFactory.getInstance("EC").generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(key.privateKeyPkcs8),
            ) as java.security.interfaces.ECPrivateKey
            priv.s.toByteArray()
        }
        val fromScalar = WebAuthn.parseEcPrivateKey(WebAuthn.base64Url(sBytes))
        assertThat(fromScalar).isNotNull()

        // 两种来源签名都应被同一公钥校验通过
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(toBigInt(key.publicX), toBigInt(key.publicY)), ecParams()),
        )
        for (pk in listOfNotNull(fromPkcs8, fromScalar)) {
            val cd = WebAuthn.buildClientDataJson("webauthn.get", ByteArray(16), "https://x.test")
            val ad = WebAuthn.buildAuthenticatorData("x.test", true, true, 0, false)
            val s = WebAuthn.signAssertion(ad, cd, pk)
            val msg = ad + MessageDigest.getInstance("SHA-256").digest(cd)
            assertThat(
                Signature.getInstance("SHA256withECDSA").also { it.initVerify(pub); it.update(msg) }.verify(s),
            ).isTrue()
        }
    }

    @Test
    fun `none attestation object is well-formed CBOR`() {
        val key = WebAuthn.generateKeyPair()
        val cose = WebAuthn.encodeCoseP256(key.publicX, key.publicY)
        val authData = WebAuthn.buildAuthenticatorData(
            rpId = "example.com", userPresent = true, userVerified = true,
            counter = 0, withAttested = true, credentialId = key.credentialId, cosePublicKey = cose,
        )
        val attObj = WebAuthn.buildNoneAttestationObject(authData)
        // map(3) 开头
        assertThat(attObj[0].toInt() and 0xff).isEqualTo(0xa3)
        // 含 "fmt" / "none" / "authData" 文本键
        val text = String(attObj, Charsets.UTF_8)
        assertThat(text).contains("fmt")
        assertThat(text).contains("none")
        assertThat(text).contains("authData")
        assertThat(text).contains("attestationStatement")

        // create 响应可构造且含 attestationObject
        val createJson = WebAuthn.buildCreateResponseJson(key.credentialId, ByteArray(8), attObj)
        assertThat(createJson).contains("\"attestationObject\"")
        assertThat(createJson).contains("\"type\":\"public-key\"")
    }

    @Test
    fun `authenticatorData rpIdHash and flags correct`() {
        val ad = WebAuthn.buildAuthenticatorData("example.com", true, false, 5, false)
        assertThat(ad.size).isEqualTo(37) // 32 + 1 + 4
        val expectedHash = MessageDigest.getInstance("SHA-256").digest("example.com".toByteArray())
        assertThat(ad.copyOfRange(0, 32)).isEqualTo(expectedHash)
        // flags: UP=0x01, counter=5 → 末 4 字节大端
        assertThat(ad[32].toInt() and 0xff).isEqualTo(0x01)
        assertThat(ad.copyOfRange(33, 37)).isEqualTo(byteArrayOf(0, 0, 0, 5))
    }

    private fun toBigInt(b: ByteArray): BigInteger = BigInteger(1, b)
}
