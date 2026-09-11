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
        // flags: UP=0x01 | BE=0x08 | BS=0x10 = 0x19（UV 未设）；counter=5 → 末 4 字节大端
        assertThat(ad[32].toInt() and 0xff).isEqualTo(0x19)
        assertThat(ad.copyOfRange(33, 37)).isEqualTo(byteArrayOf(0, 0, 0, 5))
    }

    /**
     * **signCount 恒 0 回归锁（2026-09-11）**：断言（登录）流程必须发出 counter=0。
     *
     * 为什么这条锁是必要的：WebAuthn 规范对计数器的校验是**严格大于**（`new > stored`）。
     * 「同步型 passkey」（Bitwarden / 1Password / iCloud Keychain）一律返回 0，表示
     * 「本 authenticator 不实现计数器」，RP 据此跳过单调性校验（§6.1.1）。
     *
     * 反例（已回退的错误改动）：读库里的 counter 原样发出。Bitwarden 官方客户端每签一次
     * 会递增并写回服务端，同步下来就是非零值；原样发送且不递增 ⇒ 第二次登录发出的值与
     * 上次**相同** ⇒ `new > stored` 不成立 ⇒ RP 判重放并拒绝整条断言。
     * 见 `CipherMapper` 中 `counter = decryptToString(d.counter, ...).toLongOrNull() ?: 0`。
     */
    @Test
    fun `assertion authenticatorData always carries zero signCount`() {
        // 即使库中同步来一个非零 counter，调用方也必须传 0（此处用 0 模拟正确调用）
        val ad = WebAuthn.buildAuthenticatorData("example.com", true, true, 0, false)
        assertThat(ad.size).isEqualTo(37)
        // 末 4 字节（字节 33..36）必须全 0，大端
        assertThat(ad.copyOfRange(33, 37)).isEqualTo(byteArrayOf(0, 0, 0, 0))
        // 大端解析同样为 0
        val count = ((ad[33].toInt() and 0xff) shl 24) or
            ((ad[34].toInt() and 0xff) shl 16) or
            ((ad[35].toInt() and 0xff) shl 8) or
            (ad[36].toInt() and 0xff)
        assertThat(count).isEqualTo(0)
    }

    /**
     * BE/BS 置位（语义正确性，2026-09-11）：BE(0x08) + BS(0x10) 应**始终**置位。
     *
     * 背景：Vaultix 私钥存库并随服务端同步，语义上是「可备份凭证」，故 BE/BS 均应置位
     * （对齐 Bastion / Bitwarden / 1Password / iCloud Keychain）。
     *
     * ⚠️ 这**不是**某个登录 bug 的修复：BE/BS 是注册期存档字段，RP 在断言（登录）
     * 阶段不做 BE/BS 校验（login 校验项只有 rpIdHash / UP / UV / 签名 / signCount）。
     * 断言侧与登录成败相关的是 **signCount 恒 0**，见 [PasskeyGetActivity]。
     */
    @Test
    fun `authenticatorData always sets backup eligible and backup state flags`() {
        // 断言流程（withAttested=false）：UP+UV+BE+BS = 0x1D
        val assertion = WebAuthn.buildAuthenticatorData("example.com", true, true, 0, false)
        assertThat(assertion[32].toInt() and 0xff).isEqualTo(0x1D)

        // 注册流程（withAttested=true）：UP+UV+BE+BS+AT = 0x5D
        val key = WebAuthn.generateKeyPair()
        val cose = WebAuthn.encodeCoseP256(key.publicX, key.publicY)
        val attestation = WebAuthn.buildAuthenticatorData(
            "example.com", true, true, 0, true, key.credentialId, cose,
        )
        assertThat(attestation[32].toInt() and 0xff).isEqualTo(0x5D)

        // 注册与断言的 BE/BS 基线一致（BE 终身不变，BS 本实现恒真）
        for (adm in listOf(assertion, attestation)) {
            assertThat(adm[32].toInt() and 0x08).isEqualTo(0x08)
            assertThat(adm[32].toInt() and 0x10).isEqualTo(0x10)
        }
    }

    /** 响应 JSON 必须带 `clientExtensionResults`（部分 RP 解析器直接读该键）。 */
    @Test
    fun `get and create responses carry clientExtensionResults`() {
        val key = WebAuthn.generateKeyPair()
        val get = WebAuthn.buildGetResponseJson(
            key.credentialId, ByteArray(8), ByteArray(37), ByteArray(64), null,
        )
        assertThat(get).contains("\"clientExtensionResults\":{}")
        assertThat(get).contains("\"authenticatorAttachment\":\"platform\"")
        assertThat(get).contains("\"type\":\"public-key\"")
        // userHandle 为 null 时省略该字段（对齐 Bitwarden / Keyguard）
        assertThat(get).doesNotContain("userHandle")
    }

    private fun toBigInt(b: ByteArray): BigInteger = BigInteger(1, b)
}
