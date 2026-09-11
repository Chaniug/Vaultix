/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * WebAuthn 断言 / 证明（attestation）构造：Credential Provider 在系统层把 Vaultix 的通行密钥
 * 暴露给 Chrome / Edge 时，被选中后必须返回一份可被依赖方校验的签名结果。本模块为纯 Kotlin
 * （只依赖 java.security，可在 JVM 单测），刻意不引入 androidx.credentials，便于离线单测
 * 验证 crypto 正确性（CBOR 结构、authenticatorData、ECDSA 签名）。
 *
 * 覆盖：
 *  - get：clientDataJSON + authenticatorData + 对 SHA256(authData‖clientData) 的 P-256 ECDSA 签名
 *  - create：生成 P-256 密钥对 + `none` 证明（attestationObject，含 COSE 公钥）
 *
 * 注：仅实现 ES256 / P-256（Bitwarden 通行密钥事实标准）；其它算法（EdDSA/RSA）不在范围内。
 */
package io.vaultix.common

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.util.Base64

/** WebAuthn / FIDO 常量与纯函数。全部抛 [IllegalArgumentException] / [WebAuthnException] 指明失败原因。 */
object WebAuthn {

    /** 标识使用的曲线（目前固定 P-256 / secp256r1）。 */
    const val COSE_ALG_ES256: Int = -7

    // ---- WebAuthn / FIDO2 / CBOR / COSE 协议常量（集中定义，避免散落魔法数）----
    // detekt MagicNumber 默认仅排除 -1/0/1/2；其余协议字节集中在此命名，便于审计。
    /** P-256 坐标 / 裸私钥标量固定 32 字节。 */
    private const val P256_FIELD_BYTES = 32
    /** 自建提供方无 AAGUID，固定 16 字节全 0。 */
    private const val AAGUID_BYTES = 16

    // authenticatorData 标志位（WebAuthn §6.1）
    private const val FLAG_USER_PRESENT = 0x01
    private const val FLAG_USER_VERIFIED = 0x04
    private const val FLAG_BACKUP_ELIGIBLE = 0x08  // BE
    private const val FLAG_BACKUP_STATE = 0x10     // BS
    private const val FLAG_ATTESTED = 0x40

    // COSE_Key 顶层结构（CBOR 头字节）
    private const val CBOR_MAP_5 = 0xa5
    private const val CBOR_MAP_3 = 0xa3
    private const val CBOR_EMPTY_MAP = 0xa0
    private const val CBOR_TEXT_HEAD = 0x60      // major type 3（文本）短字符串头
    private const val CBOR_BYTES_HEAD = 0x40     // major type 2（字节串）短格式头

    // COSE 键标签与值
    private const val COSE_KEY_KTY = 0x01        // kty
    private const val COSE_KTY_EC2 = 0x02        // EC2
    private const val COSE_KEY_ALG = 0x03        // alg
    private const val COSE_ALG_ES256_CBOR = 0x26 // -7 的 CBOR 负映射（0x20 | 7）
    private const val COSE_KEY_CRV = 0x20        // -1（crv）
    private const val COSE_CRV_P256 = 0x01      // P-256
    private const val COSE_KEY_X = 0x21         // -2（x）
    private const val COSE_KEY_Y = 0x22         // -3（y）

    // CBOR 字节串长度头与阈值
    private const val CBOR_BYTES_LEN1 = 0x58     // 1 字节长度
    private const val CBOR_BYTES_LEN2 = 0x59     // 2 字节长度
    private const val CBOR_SHORT_MAX = 23        // 短格式最大长度（< 24）
    private const val CBOR_MED_MAX = 255         // 1 字节长度格式最大（< 256）
    private const val CBOR_LONG_MAX = 65535      // 2 字节长度格式最大（< 65536）
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xff

    private val secureRandom = SecureRandom()

    /** 把 [bytes] 编码为 URL-safe Base64（无 padding），WebAuthn 字段统一形态。 */
    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** 容错解码：先试 URL-safe，再试标准 Base64（Bitwarden 库里两种都存在）。 */
    fun decodeBase64UrlOrStandard(text: String): ByteArray = runCatching {
        Base64.getUrlDecoder().decode(text)
    }.getOrElse { Base64.getDecoder().decode(text) }

    /**
     * 解析 Bitwarden 存储的 [keyValue] 为 P-256 私钥。
     *
     * Bitwarden 在不同来源下 keyValue 形态不一：PEM(PKCS8) / 标准 Base64 的 PKCS8 DER /
     * 32 字节裸私钥标量。逐项尝试，全部失败返回 null（调用方据此给出「无法签名」提示，而非崩溃）。
     */
    fun parseEcPrivateKey(keyValue: String?): PrivateKey? {
        if (keyValue.isNullOrBlank()) return null
        val trimmed = keyValue.trim()
        // 1) PEM（-----BEGIN PRIVATE KEY----- 或 EC PRIVATE KEY）
        if (trimmed.startsWith("-----BEGIN", ignoreCase = true)) {
            val der = pemToDer(trimmed) ?: return null
            return ecPrivateFromDer(der)
        }
        val raw = runCatching { decodeBase64UrlOrStandard(trimmed) }.getOrNull() ?: return null
        // 2) 裸私钥标量：**必须先归一化长度**。BigInteger.toByteArray() 在高位为 1 时多一个
        //    前导 0x00（33 字节）、值较小时又会丢掉前导 0x00（<32 字节），两种形态在真实
        //    数据里都存在。若只认「恰好 32 字节」，同一份密钥会随机解析失败（此前
        //    WebAuthnTest 的随机键用例约半数概率失败，即此因）。
        normalizeScalar(raw)?.let { return ecPrivateFromScalar(it) }
        // 3) 疑似 PKCS8 DER（SEQUENCE 开头）
        if (raw.size > 2 && raw[0] == 0x30.toByte()) return ecPrivateFromDer(raw)
        // 4) 既非标量也非 DER：放弃
        return null
    }

    /**
     * 把裸私钥标量归一到 P-256 的 32 字节。
     *
     * - 33 字节且首字节为 `0x00` → 去掉该前导零（BigInteger 正数补位形态）
     * - 不足 32 字节 → 左侧补 `0x00`
     * - 仍超过 32 字节（首字节非零）→ 不是合法 P-256 标量，返回 null 交给 DER 分支
     */
    private fun normalizeScalar(raw: ByteArray): ByteArray? {
        val stripped = if (raw.size > P256_FIELD_BYTES && raw[0] == 0x00.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        return when {
            stripped.isEmpty() -> null
            stripped.size > P256_FIELD_BYTES -> null
            stripped.size == P256_FIELD_BYTES -> stripped
            else -> ByteArray(P256_FIELD_BYTES - stripped.size) + stripped
        }
    }

    /**
     * 现场诊断：某个 clientDataJSON 是否与系统给的哈希一致。
     *
     * `null` 表示非浏览器流程（没有外部哈希可比）。
     *
     * ⚠️ 浏览器流程下这个值**恒为 false 属预期**：系统给的是浏览器那份 JSON 的哈希，
     * 而 provider 只能回传占位符（[BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER]），两者本就不该相等。
     * 不要再据此"反选"自造 JSON 的变体——那是 2026-09-11 修掉的那条错路（见 [buildClientDataJson]）。
     */
    fun clientDataJsonMatchesHash(json: ByteArray, expectedHash: ByteArray?): Boolean? =
        expectedHash?.let { sha256(json).contentEquals(it) }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun pemToDer(pem: String): ByteArray? {
        val cleaned = pem.lines()
            .filter { !it.startsWith("-----") }
            .joinToString("")
            .trim()
        return runCatching { Base64.getDecoder().decode(cleaned) }.getOrNull()
    }

    private fun ecPrivateFromDer(der: ByteArray): PrivateKey? {
        // 1) 标准 PKCS#8（带 namedCurve 参数）→ 交给 provider 直接解析
        runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) }
            .getOrNull()?.let { return it }
        // 2) 宽松回退：从 PKCS#8 或 SEC1(RFC 5915) 里抠出裸标量 s，用**显式 P-256 参数**重建。
        //    必要场景（真机实测「Cannot parse passkey key」的根因）：
        //      a) WebCrypto `exportKey("pkcs8")` 产出的 PKCS#8 可能**不带 namedCurve 参数**，
        //         SunEC 会抛 InvalidKeySpecException；
        //      b) Bitwarden/KeePass 侧也可能存 SEC1（`-----BEGIN EC PRIVATE KEY-----`），
        //         它不是 PKCS#8 结构，PKCS8EncodedKeySpec 必然失败。
        //    抠出标量后走 ECPrivateKeySpec + secp256r1，绕开一切 provider 宽容度差异。
        val scalar = extractEcScalar(der) ?: return null
        return ecPrivateFromScalar(scalar)
    }

    /** DER 标签（只需这三种）。 */
    private const val DER_TAG_INTEGER = 0x02
    private const val DER_TAG_OCTET_STRING = 0x04
    private const val DER_TAG_SEQUENCE = 0x30

    /** DER 长格式长度的最大字节数（0x84 = 4 字节；再长不可能是 256 位密钥）。 */
    private const val LONG_FORM_MAX_BYTES = 4

    /** DER 长格式长度标志位（length 字节最高位为 1 表示后续 N 字节才是长度）。 */
    private const val DER_LONG_FORM_FLAG = 0x80

    /** 长格式长度里「字节数」部分的掩码（去掉标志位）。 */
    private const val DER_LONG_FORM_LENGTH_MASK = 0x7F

    /** 一个合法 TLV 至少 2 字节（tag + 短格式长度）。 */
    private const val DER_MIN_TLV_BYTES = 2

    /** 十六进制格式化参数。 */
    private const val HEX_RADIX = 16
    private const val HEX_PAD_WIDTH = 2
    private const val HEX_PAD_CHAR = '0'

    /** 一个 TLV 的视图：[start, end) 为内容区间，[next] 为下一个 TLV 的偏移。 */
    private data class DerElement(val tag: Int, val start: Int, val end: Int, val next: Int)

    /**
     * 从 PKCS#8 / SEC1 结构中提取 EC 私钥标量（OCTET STRING 内容）。
     *
     * 最小 DER 解析，不依赖任何 provider：
     * - SEC1 `ECPrivateKey ::= SEQUENCE { version INTEGER, privateKey OCTET STRING, ... }`
     * - PKCS#8 `PrivateKeyInfo ::= SEQUENCE { version INTEGER, AlgorithmIdentifier SEQUENCE,
     *   privateKey OCTET STRING(内含 SEC1) , ... }`（递归一层）
     */
    private fun extractEcScalar(der: ByteArray, offset: Int = 0): ByteArray? {
        val root = derRead(der, offset) ?: return null
        if (root.tag != DER_TAG_SEQUENCE) return null
        val version = derRead(der, root.start) ?: return null
        if (version.tag != DER_TAG_INTEGER) return null
        val second = derRead(der, version.next) ?: return null
        return when (second.tag) {
            // SEC1：版本之后紧跟私钥 OCTET STRING
            DER_TAG_OCTET_STRING -> der.copyOfRange(second.start, second.end)
            // PKCS#8：AlgorithmIdentifier 之后是包着 SEC1 的 OCTET STRING → 递归
            DER_TAG_SEQUENCE -> {
                val inner = derRead(der, second.next) ?: return null
                if (inner.tag != DER_TAG_OCTET_STRING) return null
                extractEcScalar(der, inner.start)
            }
            else -> null
        }
    }

    /** 读取 [offset] 处的 TLV；越界或非法长度返回 null。 */
    private fun derRead(der: ByteArray, offset: Int): DerElement? {
        if (offset < 0 || offset + DER_MIN_TLV_BYTES > der.size) return null
        val tag = der[offset].toInt() and BYTE_MASK
        var pos = offset + 1
        var length = der[pos].toInt() and BYTE_MASK
        pos++
        if (length and DER_LONG_FORM_FLAG != 0) {
            val lengthBytes = length and DER_LONG_FORM_LENGTH_MASK
            if (lengthBytes == 0 || lengthBytes > LONG_FORM_MAX_BYTES || pos + lengthBytes > der.size) {
                return null
            }
            length = 0
            repeat(lengthBytes) {
                length = (length shl BYTE_BITS) or (der[pos].toInt() and BYTE_MASK)
                pos++
            }
        }
        if (length < 0 || pos + length > der.size) return null
        return DerElement(tag, pos, pos + length, pos + length)
    }

    /**
     * 解析失败时的**非敏感**诊断串（长度 / 形态 / 首字节），供现场日志定位。
     * ⚠️ 绝不返回密钥内容本身。
     */
    fun describeEcPrivateKeyFailure(keyValue: String?): String {
        if (keyValue.isNullOrBlank()) return "blank"
        val trimmed = keyValue.trim()
        if (trimmed.startsWith("-----BEGIN", ignoreCase = true)) {
            val der = pemToDer(trimmed) ?: return "pem-unreadable"
            return "pem-der(len=${der.size}, first=0x${firstHex(der)})"
        }
        val raw = runCatching { decodeBase64UrlOrStandard(trimmed) }.getOrNull() ?: return "base64-failed"
        return "der(len=${raw.size}, first=0x${firstHex(raw)})"
    }

    private fun firstHex(bytes: ByteArray): String =
        bytes.firstOrNull()
            ?.let { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_PAD_WIDTH, HEX_PAD_CHAR) }
            ?: "empty"

    private fun ecPrivateFromScalar(scalar: ByteArray): PrivateKey? = runCatching {
        val params = ecParameterSpec()
        val keySpec = ECPrivateKeySpec(BigInteger(1, scalar), params)
        KeyFactory.getInstance("EC").generatePrivate(keySpec)
    }.getOrNull()

    private fun ecParameterSpec(): ECParameterSpec =
        KeyPairGenerator.getInstance("EC").also { it.initialize(ECGenParameterSpec("secp256r1")) }
            .genKeyPair().private.let { (it as ECPrivateKey).params }

    /**
     * 生成一对 P-256 密钥（软件密钥，与 Bitwarden「密钥材料存于库而非 Android Keystore」一致）。
     * 返回：凭证 ID（随机）、私钥 PKCS8 字节、公钥原始 (x,y) 各 32 字节。
     */
    fun generateKeyPair(credentialIdLength: Int = 32): GeneratedKey {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp: KeyPair = kpg.genKeyPair()
        val priv = kp.private as ECPrivateKey
        val pub = kp.public as ECPublicKey
        val id = ByteArray(credentialIdLength).also { secureRandom.nextBytes(it) }
        val pkcs8 = priv.encoded // PKCS8 DER
        val w = pub.w
        return GeneratedKey(
            credentialId = id,
            privateKeyPkcs8 = pkcs8,
            publicX = fieldToBytes(w.affineX),
            publicY = fieldToBytes(w.affineY),
        )
    }

    /** 把有限域元素补成 32 字节大端（P-256 坐标固定 32 字节）。 */
    private fun fieldToBytes(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        val be = value.toByteArray()
        // BigInteger.toByteArray 可能带符号位前导 0 或长度不足 32，归一为 32 字节大端
        val srcStart = if (be.size > 1 && be[0] == 0.toByte()) 1 else 0
        val srcLen = be.size - srcStart
        System.arraycopy(be, srcStart, out, P256_FIELD_BYTES - srcLen, srcLen)
        return out
    }

    /**
     * 浏览器流程回传的 `clientDataJSON` 占位符。
     *
     * 官方要求（Android 凭据提供方文档 `developer.android.com/identity/sign-in/credential-provider`）：
     * > use the `clientDataHash` that's provided directly in `CreatePublicKeyCredentialRequest()`
     * > or `GetPublicKeyCredentialOption()` instead of assembling and hashing clientDataJSON
     * > during the signature request. To avoid JSON parsing issues, **set a placeholder value
     * > for `clientDataJSON` in the attestation and assertion response.**
     *
     * 原因是 provider 与 RP 看到的 `clientDataJSON` 不是同一份：
     * - provider 只拿到 32 字节 `clientDataHash`（浏览器那份 JSON 的 SHA-256），拿不到明文；
     * - 网页交给 RP 的是**浏览器自己那份** JSON，RP 用它重新哈希后与签名里的哈希比对。
     *
     * 因此 provider 自造 JSON 回传没有任何意义（永远逐字节不同），只会让"复刻浏览器 JSON"
     * 这种反推尝试变成误导。占位符是空字节数组：它不参与任何密码学校验，只为填满协议字段。
     * 参照 Bitwarden：Android 侧从不重建 JSON，整体交给 SDK 的
     * `ClientData.DefaultWithCustomHash(hash)` / `DefaultWithExtraData(androidPackageName)`。
     */
    val BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER: ByteArray = ByteArray(0)

    /** WebAuthn `clientDataJSON`：依赖方校验 origin 的关键字段。 */
    /**
     * 构造 clientDataJSON（**仅限原生 App 流程**使用）。
     *
     * 调用方拼的 JSON、签的哈希、回传的 JSON 必须是**同一份字节**，RP 才会验签通过。
     * 原生流程里系统没有给 `clientDataHash`，三者都由本模块产出，所以成立。
     *
     * ⚠️ **浏览器流程不要调这个函数**（2026-09-11 修正）。旧注释声称"浏览器流程必须逐字节
     * 复刻浏览器版 JSON，否则 RP 再哈希对不上"——这个因果是错的：provider 从一开始就拿不到
     * 浏览器那份 JSON 的明文（系统只给 32 字节哈希），RP 校验用的是**网页交给它的**那份
     * JSON。因此浏览器流程应当直接放 [BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER]，
     * 签名只覆盖 `authData ‖ clientDataHash`（见 [signAssertionHash]）。
     *
     * 反面教材：Bastion `PasskeyAuthActivity.createClientDataJson` 与 Keyguard
     * `PasskeyProviderGetRequest` 都还在重建 JSON，属同一类缺陷，不可作为依据。
     *
     * @param includeCrossOrigin 原生流程保留 `crossOrigin` 以贴近规格。
     */
    fun buildClientDataJson(
        type: String,
        challenge: ByteArray,
        origin: String,
        includeCrossOrigin: Boolean = true,
    ): ByteArray {
        val challengeB64 = base64Url(challenge)
        val json = buildString {
            append("{\"type\":")
            append(quote(type))
            append(",\"challenge\":")
            append(quote(challengeB64))
            append(",\"origin\":")
            append(quote(origin))
            if (includeCrossOrigin) append(",\"crossOrigin\":false")
            append("}")
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * 构造 authenticatorData。
     *
     * **BE / BS 置位**（对齐 Bastion / Bitwarden / 1Password / iCloud Keychain）：
     *
     * `BE`（Backup Eligibility, 0x08）声明「本凭证是否**可**被备份」；
     * `BS`（Backup State, 0x10）声明「本凭证**当前**是否处于备份状态」。
     * Vaultix 的通行密钥私钥存于库中并随 Bitwarden 服务端同步，语义上属于
     * 「可同步通行密钥」，两者都应置位。二者在凭证生命周期内**保持一致**：
     * BE 终身不变，BS 可变（本实现恒真）。
     *
     * ⚠️ **注意因果边界（2026-09-11 修正）**：BE/BS 是**注册期存档字段**，
     * 服务端在断言（登录）阶段**不做** BE/BS 校验——RPs 的 login 校验清单里
     * 只有 `rpIdHash` / `UP` / （按策略的）`UV` / 签名 / `signCount > stored`。
     * 因此**不能**把「登录时验签失败」归因于 BE/BS 缺失。本处置位的原因是
     * **语义正确**（Vaultix 的凭证确实可备份），不是为了修某个登录 bug。
     *
     * 与登录成败真正相关的是 [counter]：RP 对计数器做 `new > stored` 的**严格大于**
     * 校验，详见 [io.vaultix.vaultix.passkey.PasskeyGetActivity] 的调用点注释。
     *
     * @param withAttested 创建流程（AT 标志）为 true，需追加 aagid + credentialId + COSE 公钥。
     */
    fun buildAuthenticatorData(
        rpId: String,
        userPresent: Boolean,
        userVerified: Boolean,
        counter: Int,
        withAttested: Boolean,
        credentialId: ByteArray? = null,
        cosePublicKey: ByteArray? = null,
    ): ByteArray {
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))
        var flags = 0
        if (userPresent) flags = flags or FLAG_USER_PRESENT
        if (userVerified) flags = flags or FLAG_USER_VERIFIED
        // BE/BS 置位：私钥随库同步，属「可备份凭证」（语义正确性，非登录 bug 的修复）。
        // BE 终身不变、BS 可变；本实现两者恒置位。
        flags = flags or FLAG_BACKUP_ELIGIBLE
        flags = flags or FLAG_BACKUP_STATE
        if (withAttested) flags = flags or FLAG_ATTESTED
        val counterBytes = ByteArray(4) { i -> ((counter ushr (24 - 8 * i)) and BYTE_MASK).toByte() }
        val out = ByteArrayOutputStream()
        out.write(rpIdHash)
        out.write(flags)
        out.write(counterBytes)
        if (withAttested) {
            requireNotNull(credentialId) { "credentialId required when withAttested" }
            requireNotNull(cosePublicKey) { "cosePublicKey required when withAttested" }
            out.write(ByteArray(AAGUID_BYTES)) // aagid：自建提供方无 AAGUID，全 0
            out.write(
                byteArrayOf(
                    (credentialId.size ushr BYTE_BITS).toByte(),
                    (credentialId.size and BYTE_MASK).toByte(),
                ),
            )
            out.write(credentialId)
            out.write(cosePublicKey)
        }
        return out.toByteArray()
    }

    /** COSE_Key（EC2 / ES256）编码：{1:2, 3:-7, -1:1, -2:x, -3:y}。 */
    fun encodeCoseP256(publicX: ByteArray, publicY: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(CBOR_MAP_5) // map(5)
        // 1 (kty) : 2 (EC2)
        buf.write(COSE_KEY_KTY); buf.write(COSE_KTY_EC2)
        // 3 (alg) : -7 (ES256)
        buf.write(COSE_KEY_ALG); buf.write(COSE_ALG_ES256_CBOR)
        // -1 (crv) : 1 (P-256)
        buf.write(COSE_KEY_CRV); buf.write(COSE_CRV_P256)
        // -2 (x) : bytes(32)
        buf.write(COSE_KEY_X); writeByteString(buf, publicX)
        // -3 (y) : bytes(32)
        buf.write(COSE_KEY_Y); writeByteString(buf, publicY)
        return buf.toByteArray()
    }

    /** CBOR：attestationObject = {"fmt":"none","authData":bytes,"attestationStatement":{}}。 */
    fun buildNoneAttestationObject(authData: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(CBOR_MAP_3) // map(3)
        writeText(buf, "fmt"); writeText(buf, "none")
        writeText(buf, "authData"); writeByteString(buf, authData)
        writeText(buf, "attestationStatement"); buf.write(CBOR_EMPTY_MAP) // empty map
        return buf.toByteArray()
    }

    /**
     * 对 `authData ‖ SHA256(clientDataJSON)` 做 P-256 ECDSA 签名（DER 编码，WebAuthn 期望形态）。
     * 用于原生 App 流程（系统未提供 clientDataHash，由本模块自行对 clientDataJSON 取哈希）。
     */
    fun signAssertion(authData: ByteArray, clientDataJson: ByteArray, privateKey: PrivateKey): ByteArray {
        val message = authData + MessageDigest.getInstance("SHA-256").digest(clientDataJson)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(message)
        return sig.sign()
    }

    /**
     * 对 `authData ‖ clientDataHash` 直接做 P-256 ECDSA 签名。
     *
     * 用于浏览器（Chromium）流程：系统已把浏览器算好的 `clientDataHash` 交给我们，
     * 此时**不能再**自己对 clientDataJSON 取哈希（会与浏览器的对不上 → RP 报密钥登录失败）。
     * 直接拿系统给的 hash 拼接签名即可。
     */
    fun signAssertionHash(authData: ByteArray, clientDataHash: ByteArray, privateKey: PrivateKey): ByteArray {
        val message = authData + clientDataHash
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(message)
        return sig.sign()
    }

    /**
     * 组装 get 响应 JSON（PublicKeyCredential 的 `response` 形态）。
     *
     * 字段集对齐 Bitwarden / Keyguard / Bastion 三家：
     * - `authenticatorAttachment`：本 provider 是平台内置（软件密钥 + 系统生物识别），取 `platform`；
     * - `clientExtensionResults`：**必须存在**（哪怕空对象），部分 RP 的解析器直接读该键。
     */
    fun buildGetResponseJson(
        credentialId: ByteArray,
        clientDataJson: ByteArray,
        authData: ByteArray,
        signature: ByteArray,
        userHandle: ByteArray?,
    ): String {
        val id = base64Url(credentialId)
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":").append(quote(id))
        sb.append(",\"rawId\":").append(quote(id))
        sb.append(",\"type\":\"public-key\"")
        sb.append(",\"authenticatorAttachment\":\"platform\"")
        sb.append(",\"response\":{")
        sb.append("\"clientDataJSON\":").append(quote(base64Url(clientDataJson)))
        sb.append(",\"authenticatorData\":").append(quote(base64Url(authData)))
        sb.append(",\"signature\":").append(quote(base64Url(signature)))
        if (userHandle != null) {
            sb.append(",\"userHandle\":").append(quote(base64Url(userHandle)))
        }
        sb.append("}")
        sb.append(",\"clientExtensionResults\":{}")
        sb.append("}")
        return sb.toString()
    }

    /** 组装 create 响应 JSON（字段集与 [buildGetResponseJson] 同口径）。 */
    fun buildCreateResponseJson(
        credentialId: ByteArray,
        clientDataJson: ByteArray,
        attestationObject: ByteArray,
    ): String {
        val id = base64Url(credentialId)
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":").append(quote(id))
        sb.append(",\"rawId\":").append(quote(id))
        sb.append(",\"type\":\"public-key\"")
        sb.append(",\"authenticatorAttachment\":\"platform\"")
        sb.append(",\"response\":{")
        sb.append("\"clientDataJSON\":").append(quote(base64Url(clientDataJson)))
        sb.append(",\"attestationObject\":").append(quote(base64Url(attestationObject)))
        sb.append("}")
        sb.append(",\"clientExtensionResults\":{}")
        sb.append("}")
        return sb.toString()
    }

    // ---- CBOR 基础写入 ----

    private fun writeText(buf: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        buf.write(CBOR_TEXT_HEAD or bytes.size) // 短文本（长度 < 24）
        buf.write(bytes)
    }

    private fun writeByteString(buf: ByteArrayOutputStream, bytes: ByteArray) {
        when {
            bytes.size < CBOR_SHORT_MAX -> buf.write(CBOR_BYTES_HEAD or bytes.size)
            bytes.size < CBOR_MED_MAX -> {
                buf.write(CBOR_BYTES_LEN1); buf.write(bytes.size)
            }
            bytes.size < CBOR_LONG_MAX -> {
                buf.write(CBOR_BYTES_LEN2)
                buf.write((bytes.size ushr BYTE_BITS) and BYTE_MASK)
                buf.write(bytes.size and BYTE_MASK)
            }
            else -> error("byte string too long for CBOR writer")
        }
        buf.write(bytes)
    }

    private fun quote(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /** 生成密钥的产物（均为原始字节，调用方负责落库编码）。 */
    data class GeneratedKey(
        val credentialId: ByteArray,
        val privateKeyPkcs8: ByteArray,
        val publicX: ByteArray,
        val publicY: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is GeneratedKey && credentialId.contentEquals(other.credentialId) &&
                privateKeyPkcs8.contentEquals(other.privateKeyPkcs8) &&
                publicX.contentEquals(other.publicX) && publicY.contentEquals(other.publicY)
        override fun hashCode(): Int =
            credentialId.contentHashCode() xor privateKeyPkcs8.contentHashCode() xor
                publicX.contentHashCode() xor publicY.contentHashCode()
    }
}
