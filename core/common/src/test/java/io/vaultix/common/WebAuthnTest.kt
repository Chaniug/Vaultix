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
     * 断言侧与登录成败相关的是 **signCount 恒 0** 与 **clientDataJSON 用占位符**
     * （后者见 `browser flow signs provided hash and returns placeholder clientDataJSON`）。
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

    /**
     * **浏览器流程必须回传真实 clientDataJSON 的回归锁（2026-09-12）**。
     *
     * 这是"Security key authentication failed"的根因修复。上一版（2026-09-11）在浏览器流程
     * 回传 `ByteArray(0)` 占位符，依据是 Android 官方文档那句 "set a placeholder value for
     * clientDataJSON"。该句有**前置条件** `If you retrieve an origin`——特指经
     * `CallingAppInfo.getOrigin(privilegedAllowlist)` + 特权应用名单拿到 origin 的场景
     * （Google Password Manager）。Vaultix 的 `CallingAppOrigin` 走「自证式读取」、
     * **不用特权名单**，故不适用。
     *
     * W3C WebAuthn Level 2 §7.2 规定 RP **解析 clientDataJSON 明文**并逐项校验：
     * `C.type` = `webauthn.get`、`C.challenge` = base64url(options.challenge)、
     * `C.origin` 匹配 RP origin。空字节数组连 JSON 解析都过不了 ⇒ 必然失败。
     *
     * 本测试同时锁住浏览器流程的两个契约：
     * 1. **签名**覆盖 `authData ‖ clientDataHash`（系统给的哈希，即浏览器那份 JSON 的 SHA-256）；
     * 2. **回传**的 clientDataJSON 是自建的真实 JSON，且含有正确的 challenge / type / origin。
     */
    @Test
    fun `browser flow signs provided hash and returns real clientDataJSON`() {
        val key = WebAuthn.generateKeyPair()
        val priv = WebAuthn.parseEcPrivateKey(WebAuthn.base64Url(key.privateKeyPkcs8))!!
        val authData = WebAuthn.buildAuthenticatorData(
            rpId = "example.com", userPresent = true, userVerified = true,
            counter = 0, withAttested = false,
        )
        // 模拟浏览器：它自己拼了一份 JSON，只把 SHA-256 交给系统
        val browserJson = (
            "{\"type\":\"webauthn.get\",\"challenge\":\"Zm9v\",\"origin\":\"https://example.com\"}"
            ).toByteArray(Charsets.UTF_8)
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(browserJson)

        // 1) 签名必须能被 RP 用 (浏览器 JSON + 系统哈希) 验通 —— 即验签的真实场景
        val sig = WebAuthn.signAssertionHash(authData, clientDataHash, priv)
        val message = authData + MessageDigest.getInstance("SHA-256").digest(browserJson)
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(toBigInt(key.publicX), toBigInt(key.publicY)), ecParams()),
        )
        assertThat(
            Signature.getInstance("SHA256withECDSA").also { it.initVerify(pub); it.update(message) }.verify(sig),
        ).isTrue()

        // 2) **回传的 clientDataJSON 必须是真实 JSON**，不得为空占位符。
        //    浏览器流程下 androidPackageName 必须为 null（浏览器那份 JSON 里没有该字段）。
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val clientData = WebAuthn.buildGetClientDataJson(
            challenge = challenge,
            origin = "https://example.com",
            androidPackageName = null,
        )
        assertThat(clientData).isNotEmpty()
        assertThat(WebAuthn.clientDataJsonMatchesHash(clientData, clientDataHash)).isFalse()

        // 3) 解回明文逐项核对 §7.2 三项校验会读到的字段
        val text = String(clientData, Charsets.UTF_8)
        assertThat(text).contains("\"type\":\"webauthn.get\"")
        assertThat(text).contains("\"challenge\":\"${WebAuthn.base64Url(challenge)}\"")
        assertThat(text).contains("\"origin\":\"https://example.com\"")
        assertThat(text).doesNotContain("androidPackageName")

        // 4) 响应里的 clientDataJSON 就是这份真实 JSON（base64url 反解后逐字节相等）
        val json = WebAuthn.buildGetResponseJson(
            credentialId = key.credentialId,
            clientDataJson = clientData,
            authData = authData,
            signature = sig,
            userHandle = null,
        )
        assertThat(json).doesNotContain("\"clientDataJSON\":\"\"")
        val b64 = json.substringAfter("\"clientDataJSON\":\"").substringBefore("\"")
        assertThat(java.util.Base64.getUrlDecoder().decode(b64)).isEqualTo(clientData)
        assertThat(json).contains("\"type\":\"public-key\"")
        assertThat(json).contains("\"clientExtensionResults\":{}")
    }

    /**
     * 原生 App 流程的对照锁：**回传的 JSON 必须与签名的 JSON 是同一份字节**。
     *
     * 与上面那条浏览器流程的差异只在于**签名覆盖哪份哈希**：
     * 浏览器流程用系统给的 `clientDataHash`，原生流程用 `sha256(自建 JSON)`。
     * 两条流程回传的 clientDataJSON 都是真实 JSON。
     */
    @Test
    fun `native flow returns the very clientDataJSON it signed over`() {
        val key = WebAuthn.generateKeyPair()
        val priv = WebAuthn.parseEcPrivateKey(WebAuthn.base64Url(key.privateKeyPkcs8))!!
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val authData = WebAuthn.buildAuthenticatorData("example.com", true, true, 0, false)

        val clientData = WebAuthn.buildGetClientDataJson(challenge, "https://example.com")
        val sig = WebAuthn.signAssertion(authData, clientData, priv)
        val json = WebAuthn.buildGetResponseJson(key.credentialId, clientData, authData, sig, null)

        // 回传的 JSON 反解回字节后必须能验签通过（RP 侧同样用它计算哈希）
        val b64 = json.substringAfter("\"clientDataJSON\":\"").substringBefore("\"")
        val roundTrip = java.util.Base64.getUrlDecoder().decode(b64)
        assertThat(roundTrip).isEqualTo(clientData)
        val message = authData + MessageDigest.getInstance("SHA-256").digest(roundTrip)
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(toBigInt(key.publicX), toBigInt(key.publicY)), ecParams()),
        )
        assertThat(
            Signature.getInstance("SHA256withECDSA").also { it.initVerify(pub); it.update(message) }.verify(sig),
        ).isTrue()
    }

    /**
     * create 路径同样**不区分流程**：两条路都回传真实 clientDataJSON，
     * 只有 `androidPackageName` 的有无会随流程变化（浏览器流程必须为 null）。
     */
    @Test
    fun `create response always carries real clientDataJSON`() {
        val key = WebAuthn.generateKeyPair()
        val cose = WebAuthn.encodeCoseP256(key.publicX, key.publicY)
        val authData = WebAuthn.buildAuthenticatorData(
            "example.com", true, true, 0, true, key.credentialId, cose,
        )
        val attObj = WebAuthn.buildNoneAttestationObject(authData)
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // 浏览器流程：androidPackageName = null
        val browserJson = WebAuthn.buildCreateClientDataJson(
            challenge = challenge, origin = "https://example.com", androidPackageName = null,
        )
        val browser = WebAuthn.buildCreateResponseJson(key.credentialId, browserJson, attObj)
        assertThat(browser).doesNotContain("\"clientDataJSON\":\"\"")

        // 原生流程：可带 androidPackageName
        val nativeJson = WebAuthn.buildCreateClientDataJson(
            challenge = challenge, origin = "https://example.com", androidPackageName = "com.example.app",
        )
        val native = WebAuthn.buildCreateResponseJson(key.credentialId, nativeJson, attObj)
        // 回传的是 base64url，需反解比对（明文不会出现在 JSON 里）
        val nativeB64 = native.substringAfter("\"clientDataJSON\":\"").substringBefore("\"")
        assertThat(nativeB64).isNotEmpty()
        assertThat(java.util.Base64.getUrlDecoder().decode(nativeB64)).isEqualTo(nativeJson)

        // 两条流程回传的 JSON 都含真实 challenge（§7.1 第一/二项校验会读的字段）
        listOf(browserJson, nativeJson).forEach { bytes ->
            val text = String(bytes, Charsets.UTF_8)
            assertThat(text).contains("\"type\":\"webauthn.create\"")
            assertThat(text).contains("\"challenge\":\"${WebAuthn.base64Url(challenge)}\"")
            assertThat(text).contains("\"origin\":\"https://example.com\"")
        }
        assertThat(String(browserJson, Charsets.UTF_8)).doesNotContain("androidPackageName")
        assertThat(String(nativeJson, Charsets.UTF_8)).contains("\"androidPackageName\":\"com.example.app\"")
    }

    /**
     * **rawId / userHandle 存储态回归锁（2026-09-12）** —— 修复真机「候选能列出、能选、
     * 最后一步校验报错」的那条改动。
     *
     * 缺陷形态：注册时把 32 字节随机 ID 经 [WebAuthn.base64Url] 写成 b64url 文本落库；
     * 登录时却把这份文本**又解码一次**、再让 [WebAuthn.buildGetResponseJson] 编码回去。
     * 该路径在两处会与 RP 持有的 ID **逐字节失配**：
     *  - 标准 Base64（`+` `/`）存的 ID 被规范化成 b64url（`-` `_`）→ 文本不同；
     *  - 解码失败时被兜底成 `text.toByteArray(UTF_8)` → 把 Base64 文本本身当成 ID 发出。
     *
     * 本测试同时锁住「正常 b64url」与「标准 Base64」两种形态都必须**原样回传**，
     * 并验证标准 Base64 那条会与 RP 的 ID 比对失配（旧行为，不得回归）。
     *
     * 追加（同日补丁）：**UUID 文本**形态（Bitwarden 同步来的 16 字节 credentialId）
     * 必须归一为 `base64url(16 字节)`（22 字符）。UUID 文本恰好能通过 base64url 解码，
     * 是「误判为可解码 → 原样发出 → 与 RP 存的 16 字节失配」的根源（见用例 ④）。
     */
    @Test
    fun `stored credential id is echoed verbatim as rawId`() {
        // ① 常规 b64url：库里就是注册侧写的文本，必须一字不改地出现在 id / rawId 里
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val storedB64Url = WebAuthn.base64Url(raw)
        val json = WebAuthn.buildGetResponseJsonFromStoredId(
            storedCredentialId = storedB64Url,
            clientDataJson = ByteArray(8),
            authData = ByteArray(37),
            signature = ByteArray(64),
            userHandleText = "dXNlci1oYW5kbGU",
        )
        assertThat(WebAuthn.rawIdFromStored(storedB64Url)).isEqualTo(storedB64Url)
        assertThat(json).contains("\"id\":\"$storedB64Url\"")
        assertThat(json).contains("\"rawId\":\"$storedB64Url\"")
        assertThat(json).contains("\"userHandle\":\"dXNlci1oYW5kbGU\"")

        // ② 标准 Base64（含 `+` `/`）形态：必须原样保留，不得被规范化成 b64url 文本。
        //    这正是旧实现 `base64Url(decode(stored))` 会踩的坑——文本级比对失配。
        val storedStandard = java.util.Base64.getEncoder().encodeToString(raw)
        assertThat(WebAuthn.rawIdFromStored(storedStandard)).isEqualTo(storedStandard)
        // RP 手里的 ID 与回传 rawId 解码后的字节相同，但若 RP 做字符串比对，标准形态必须原样
        assertThat(WebAuthn.decodeBase64UrlOrStandard(WebAuthn.rawIdFromStored(storedStandard)))
            .isEqualTo(raw)

        // ③ 非 Base64 的历史脏数据：不崩，退化为按 UTF-8 编码（可诊断地失败，而非抛异常）
        val junk = "not a base64 string!!!"
        assertThat(WebAuthn.rawIdFromStored(junk)).isNotEmpty()
        assertThat(WebAuthn.decodeBase64UrlOrStandard(WebAuthn.rawIdFromStored(junk)))
            .isEqualTo(junk.toByteArray(Charsets.UTF_8))

        // ④ **UUID 文本形态**（Bitwarden 同步来的 16 字节 credentialId）：
        //    必须归一为 `base64url(16 字节)`（22 字符），**不得**把 UUID 文本原样当 rawId 发出。
        //    UUID 文本（`0-9a-f-`，长 36）恰好能通过 base64url 解码且长度为 4 的倍数，
        //    是「被误判为可解码 → 原样发出 → RP 解出 27 字节 ≠ 其存的 16 字节」的陷阱源头。
        val uuidText = "5698f18a-41e0-e865-0104-c989aee7dc18"
        val uuid = java.util.UUID.fromString(uuidText)
        val uuidBytes = java.nio.ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        val uuidRawId = WebAuthn.rawIdFromStored(uuidText)
        assertThat(uuidRawId).isNotEqualTo(uuidText) // 旧行为（原样发出 GUID 文本）不得回归
        assertThat(uuidRawId).isEqualTo(WebAuthn.base64Url(uuidBytes))
        assertThat(uuidRawId).hasLength(22) // 16 字节 → base64url 无填充 22 字符
        assertThat(java.util.Base64.getUrlDecoder().decode(uuidRawId)).isEqualTo(uuidBytes)

        // 形态诊断须把 uuid 与 base64 分开（现场日志据此定位，不再把 UUID 误报成 base64）
        assertThat(WebAuthn.describeStoredIdForm(uuidText)).isEqualTo("uuid")
        assertThat(WebAuthn.describeStoredIdForm(storedB64Url)).isEqualTo("base64")
    }

    /** 空 credentialId 不得抛异常（防御：调用方可能拿到脏数据）。 */
    @Test
    fun `rawIdFromStored tolerates blank input`() {
        assertThat(WebAuthn.rawIdFromStored("")).isEmpty()
        assertThat(WebAuthn.rawIdFromStored("   ")).isEmpty()
    }

    private fun toBigInt(b: ByteArray): BigInteger = BigInteger(1, b)
}
