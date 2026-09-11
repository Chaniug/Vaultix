/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 * You should have received a copy of the GNU General Public License along with Vaultix.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规，**不可删除**）
 * 本文件衍生自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的：
 *   bastion/Bastion/app/src/main/java/com/bastion/app/bitwarden/crypto/BitwardenCrypto.kt
 * 并依赖其配套文件：
 *   .../crypto/BitwardenArgon2MemoryGuard.kt
 *   .../crypto/BitwardenKdfMemoryException.kt
 * 原文件本身参考 Keyguard 项目实现。Bastion 以 GPL-3.0 发布，本衍生文件同样以
 * GPL-3.0 发布并保持署名。
 *
 * 相对 Bastion 的改造清单：
 *   1. 包名 `com.bastion.app.bitwarden.crypto` → `io.vaultix.crypto`；
 *   2. `object BitwardenCrypto` → `@Singleton class VaultixCrypto @Inject constructor()`
 *      （Hilt 化，见 di/CryptoModule.kt），纯算法下沉为 internal 顶层函数；
 *   3. `android.util.Base64` → `java.util.Base64`（见 Base64Codec.kt），
 *      消除 android.* 依赖，使模块可在纯 JVM 上单测；
 *   4. 密钥载体 `ByteArray` → `SecureBytes`（Docs/03 第 4 节）；
 *   5. 新增 AES-256-GCM 能力（见 AesGcm.kt）；
 *   6. 保留 Argon2 的健壮性设计：native 失败回退 BouncyCastle、内存护栏、
 *      CancellationException / ThreadDeath 透传。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import io.vaultix.crypto.di.CryptoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException as KotlinxCancellationException

/**
 * Vaultix 加密核心。
 *
 * 密钥派生流程（Docs/03 第 2 节）：
 * 1. `MasterKey = PBKDF2-SHA256(password, salt=email.lowercase(), iterations, 32B)`
 *    或 `Argon2id(password, salt=SHA256(email), m, t, p, 32B)`；
 * 2. `StretchedMasterKey = HKDF-Expand(MasterKey, "enc") ‖ HKDF-Expand(MasterKey, "mac")`；
 * 3. `MasterPasswordHash = PBKDF2-SHA256(MasterKey, password, 1)` → Base64；
 * 4. 用 StretchedMasterKey 解包 64 字节账号对称密钥；
 * 5. 用账号对称密钥加解密条目（EncString type 2，encrypt-then-MAC）。
 *
 * 线程安全：本类无共享可变状态（[nativeArgon2] 惰性初始化且 Argon2Kt 自身线程安全）。
 *
 * @property dispatcher KDF 这类 CPU 密集运算的默认执行调度器，由 Hilt 注入
 *                      （[io.vaultix.crypto.di.CryptoModule]），避免硬编码 `Dispatchers.Default`。
 */
@Singleton
class VaultixCrypto @Inject constructor(
    @CryptoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    private val nativeArgon2: Argon2Kt by lazy { Argon2Kt() }

    // ========== 密钥派生 ==========

    /**
     * PBKDF2-SHA256 派生 Master Key（Bitwarden KdfType=0）。
     *
     * @param password 用户主密码
     * @param salt 盐值，规范为 `email.trim().lowercase()`（调用方负责归一化，此处不再处理）
     * @param iterations 迭代次数；以服务端 prelogin 下发值为准
     * @return 32 字节 Master Key
     */
    fun deriveMasterKeyPbkdf2(
        password: String,
        salt: String,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    ): SecureBytes {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)
        return try {
            SecureBytes.adopt(
                pbkdf2Sha256(
                    seed = passwordBytes,
                    salt = saltBytes,
                    iterations = iterations,
                    lengthBytes = AES_KEY_SIZE,
                ),
            )
        } finally {
            passwordBytes.fill(0)
            saltBytes.fill(0)
        }
    }

    /**
     * Argon2id 派生 Master Key（Bitwarden KdfType=1，首选）。
     *
     * 健壮性设计（搬运自 Bastion，勿删）：
     * - 优先走 [Argon2Kt] native 实现（快 10x 以上，且不受 Android 堆限制）；
     * - native 失败时：
     *   - [CancellationException] / [ThreadDeath] 原样透传（协程取消与线程终止不得被吞）；
     *   - 请求内存 > [ARGON2_JVM_FALLBACK_MAX_MEMORY_MB] 时直接报错——JVM 回退会在
     *     Android 堆上分配同等大小的块，极易 OOM，不做这个闸门就是线上崩溃点；
     *   - 否则先过 [Argon2MemoryGuard] 内存护栏，再回退 BouncyCastle。
     *
     * @param password 用户主密码
     * @param salt 盐值（内部先做 SHA-256，对齐 Bitwarden/Keyguard 实现）
     * @param iterations 迭代次数 t
     * @param memoryMb 内存 m（MiB）
     * @param parallelism 并行度 p
     * @return 32 字节 Master Key
     */
    /**
     * @suppress TooGenericExceptionCaught：native Argon2 失败原因不可预知（so 缺失、
     * 链接错误、内存不足等），统一走 BC 回退；取消/线程死亡在 catch 内显式透传。
     */
    @Suppress("TooGenericExceptionCaught")
    fun deriveMasterKeyArgon2(
        password: String,
        salt: String,
        iterations: Int = DEFAULT_ARGON2_ITERATIONS,
        memoryMb: Int = DEFAULT_ARGON2_MEMORY_MB,
        parallelism: Int = DEFAULT_ARGON2_PARALLELISM,
    ): SecureBytes {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val saltBytes = salt.toByteArray(StandardCharsets.UTF_8)
        val saltHash = sha256(saltBytes)

        val hash = try {
            deriveArgon2Native(
                passwordBytes = passwordBytes,
                saltHash = saltHash,
                iterations = iterations,
                memoryMb = memoryMb,
                parallelism = parallelism,
            )
        } catch (error: Throwable) {
            when (error) {
                is CancellationException, is ThreadDeath -> throw error
            }
            if (memoryMb > ARGON2_JVM_FALLBACK_MAX_MEMORY_MB) {
                throw IllegalStateException(
                    "Vaultix Argon2id KDF requires ${memoryMb}MB memory; native Argon2 failed " +
                        "and the JVM fallback is disabled above " +
                        "${ARGON2_JVM_FALLBACK_MAX_MEMORY_MB}MB to avoid Android heap OOM.",
                    error,
                )
            }

            Argon2MemoryGuard.requireCanRun(memoryMb)
            deriveArgon2BouncyCastle(
                passwordBytes = passwordBytes,
                saltHash = saltHash,
                iterations = iterations,
                memoryMb = memoryMb,
                parallelism = parallelism,
            )
        } finally {
            passwordBytes.fill(0)
            saltBytes.fill(0)
            saltHash.fill(0)
        }

        return SecureBytes.of(hash, wipeSource = true)
    }

    /**
     * 从 Master Key 派生 Master Password Hash（用于 `POST /connect/token` 的 password 字段）。
     *
     * `PBKDF2-SHA256(seed = masterKey, salt = password, iterations = 1)` → 标准 Base64。
     *
     * @param masterKey 32 字节 Master Key（**不是** StretchedMasterKey）
     * @param password 用户主密码原文
     */
    fun deriveMasterPasswordHash(masterKey: SecureBytes, password: String): String {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val hash = masterKey.useBytes { keyBytes ->
            pbkdf2Sha256(
                seed = keyBytes,
                salt = passwordBytes,
                iterations = 1,
                lengthBytes = AES_KEY_SIZE,
            )
        }
        passwordBytes.fill(0)
        return try {
            hash.encodeStandardBase64()
        } finally {
            hash.fill(0)
        }
    }

    /**
     * Master Key → StretchedMasterKey（64 字节 = enc ‖ mac）。
     *
     * `enc = HKDF-Expand(MasterKey, "enc")`，`mac = HKDF-Expand(MasterKey, "mac")`。
     */
    fun stretchMasterKey(masterKey: SecureBytes): SymmetricCryptoKey {
        val encKey = masterKey.useBytes { hkdfExpand(it, ENC_INFO, AES_KEY_SIZE) }
        val macKey = masterKey.useBytes { hkdfExpand(it, MAC_INFO, MAC_KEY_SIZE) }
        return SymmetricCryptoKey(
            encKey = SecureBytes.adopt(encKey),
            macKey = SecureBytes.adopt(macKey),
        )
    }

    /** 生成 Bitwarden Send 的原始密钥材料（16 字节）。 */
    fun generateSendKeyMaterial(): SecureBytes = SecureBytes.random(SEND_KEY_MATERIAL_SIZE)

    /**
     * 由 Send 密钥材料派生 Send 对称密钥：
     * `HKDF(seed = keyMaterial, salt = "bitwarden-send", info = "send", 64B)`。
     */
    fun deriveSendKey(keyMaterial: SecureBytes): SymmetricCryptoKey {
        require(keyMaterial.size == SEND_KEY_MATERIAL_SIZE) {
            "Send key material must be $SEND_KEY_MATERIAL_SIZE bytes, got ${keyMaterial.size}"
        }

        val fullKey = keyMaterial.useBytes { bytes ->
            hkdf(
                seed = bytes,
                salt = SEND_SALT,
                info = SEND_INFO,
                length = SymmetricCryptoKey.FULL_KEY_SIZE,
            )
        }
        val key = SymmetricCryptoKey.fromFullKey(fullKey)
        fullKey.fill(0)
        return key
    }

    /** Send 访问密码哈希：`PBKDF2-SHA256(password, keyMaterial, 100_000)` → 标准 Base64。 */
    fun hashSendPassword(password: String, keyMaterial: SecureBytes): String {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val hash = keyMaterial.useBytes { material ->
            pbkdf2Sha256(
                seed = passwordBytes,
                salt = material,
                iterations = SEND_PASSWORD_HASH_ITERATIONS,
                lengthBytes = AES_KEY_SIZE,
            )
        }
        passwordBytes.fill(0)
        return try {
            hash.encodeStandardBase64()
        } finally {
            hash.fill(0)
        }
    }

    // ========== 协程封装（在注入的调度器上执行 CPU 密集的 KDF）==========

    /** [deriveMasterKeyPbkdf2] 的挂起版本，运行在注入的 [dispatcher] 上。 */
    suspend fun deriveMasterKeyPbkdf2Suspending(
        password: String,
        salt: String,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    ): SecureBytes = withContext(dispatcher) {
        deriveMasterKeyPbkdf2(password, salt, iterations)
    }

    /** [deriveMasterKeyArgon2] 的挂起版本，运行在注入的 [dispatcher] 上。 */
    suspend fun deriveMasterKeyArgon2Suspending(
        password: String,
        salt: String,
        iterations: Int = DEFAULT_ARGON2_ITERATIONS,
        memoryMb: Int = DEFAULT_ARGON2_MEMORY_MB,
        parallelism: Int = DEFAULT_ARGON2_PARALLELISM,
    ): SecureBytes = withContext(dispatcher) {
        deriveMasterKeyArgon2(password, salt, iterations, memoryMb, parallelism)
    }

    // ========== EncString 解析 ==========

    /** 解析 EncString，格式 `<type>.<b64(iv)>|<b64(data)>|<b64(mac)>`。 */
    fun parseCipherString(cipherString: String): ParsedCipherString =
        io.vaultix.crypto.parseCipherString(cipherString)

    // ========== 解密 ==========

    /**
     * 解密 EncString（encrypt-then-MAC 优先：有 MAC 先验 MAC，无 MAC 直接解密）。
     *
     * @param cipherString EncString
     * @param key 对称密钥
     * @param allowLegacyWithoutMac 是否放行无 MAC 的遗留 type 0。
     *        默认 true：与 Bitwarden 官方 / Bastion 行为一致——历史数据（旧客户端、
     *        导入）大量为 type 0，拒绝会让条目显示为空白（2026-09-08 真机复现）。
     *        严格场景（如未知来源密文）可显式传 false 拒绝。
     * @throws MacVerificationException MAC 不匹配
     * @throws LegacyCipherTypeException type 0 且显式禁止放行
     * @throws UnsupportedCipherTypeException 3 / 5 / 7 等 M0 未实现类型
     */
    fun decrypt(
        cipherString: String,
        key: SymmetricCryptoKey,
        allowLegacyWithoutMac: Boolean = true,
    ): ByteArray = decrypt(parseCipherString(cipherString), key, allowLegacyWithoutMac)

    /**
     * 解密并以 UTF-8 解析为字符串。
     *
     * ⚠️ **必须 `trim()`**（2026-09-11 实证，通行密钥「找不到候选」的直接原因之一）：
     *
     * Bitwarden 服务端的部分字段（尤其 `rpId`）存在**前导/尾随空白**，官方客户端读出来
     * 一律 `trim()` 后才使用。而 `rpId` 在候选发现阶段是按**精确字符串**与请求比对的
     * ——不 trim 则全部失配 ⇒ 候选列表为空、浏览器里「查不到任何通行密钥」。
     *
     * `trim()` 在此**还有一层意外的兜底作用**：Java 的 `String.trim()` 去除所有
     * `<= U+0020` 的字符，而 PKCS#7/PKCS5 的填充字节取值范围恰为 `0x01..0x10`
     * ——全部落在 `<= 0x20` 内。因此即便某个调用路径漏做了填充剥离，`trim()` 也能
     * 顺带把残留的填充字节一并去掉（此点已用真实 JCE 逐字节验证：`0x01..0x10` 16/16
     * 均可被 `trim()` 去除）。但这是**兜底而非契约**，[decrypt] 仍以 `PKCS5Padding`
     * 正常解填充为主路径。
     */
    fun decryptToString(
        cipherString: String,
        key: SymmetricCryptoKey,
        allowLegacyWithoutMac: Boolean = true,
    ): String {
        val raw = decrypt(cipherString, key, allowLegacyWithoutMac)
        return String(removePkcs7PaddingIfStrict(raw), StandardCharsets.UTF_8).trim()
    }

    /**
     * 解密已解析的 EncString。
     *
     * @suppress ThrowsCount：按密文类型分发时逐一抛出契约异常（见上方 @throws 文档），
     * 是显式 API 契约而非代码气味。
     */
    @Suppress("ThrowsCount")
    fun decrypt(
        parsed: ParsedCipherString,
        key: SymmetricCryptoKey,
        allowLegacyWithoutMac: Boolean = true,
    ): ByteArray {
        when (parsed.type) {
            CipherType.AES_CBC_256_HMAC_SHA256_B64 -> {
                val expectedMac = parsed.mac
                    ?: throw IllegalArgumentException("EncString type 2 is missing the mac part")
                val computedMac = key.macKey.useBytes { macKeyBytes ->
                    computeCbcMac(parsed.iv, parsed.ciphertext, macKeyBytes)
                }
                try {
                    if (!MessageDigest.isEqual(computedMac, expectedMac)) {
                        throw MacVerificationException()
                    }
                } finally {
                    computedMac.fill(0)
                }
            }

            CipherType.AES_CBC_256_B64 -> {
                if (!allowLegacyWithoutMac) {
                    throw LegacyCipherTypeException()
                }
            }

            else -> throw UnsupportedCipherTypeException(parsed.type)
        }

        val cipher = Cipher.getInstance(AES_CBC_TRANSFORMATION)
        val secretKey = key.encKey.useBytes { SecretKeySpec(it, "AES") }
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(parsed.iv))
        return cipher.doFinal(parsed.ciphertext)
    }

    /**
     * 去掉残留的严格 PKCS#7 填充字节（**兜底工具**，非主路径）。
     *
     * 主路径说明：[decrypt] 已用 `AES/CBC/PKCS5Padding` 的 `doFinal` 正常解填充，
     * 正常密文不会走到这里。本函数用于两类**防御性**场景：
     *  1. 某个调用方拿到的是 `NoPadding` 解出的原始块；
     *  2. 服务端实际填充方式与 `PKCS5` 存在差异（历史上 Bitwarden 服务端曾用
     *     ISO10126 等方案；此类密文在标准 JCE 上会 `BadPaddingException`，
     *     但在更宽松的实现上可能**不报错也不剥离**）。
     *
     * ⚠️ **实测边界（2026-09-11，用真实 JCE 验证，避免过度归因）**：
     *  - 标准 SunJCE 的 `PKCS5Padding` 对 ISO10126 密文**直接抛 `BadPaddingException`**，
     *    不会静默泄漏字节；
     *  - 反向：ISO10126 的末字节同样是填充长度，用「宽松 PKCS5」解析 **2000/2000 完全正确**；
     *  - 因此「ISO10126 残留」**不是**本项目的实际根因（本项目 `decrypt` 用的是标准
     *    `PKCS5Padding` + `doFinal`，本就正确解填充）。
     *  本函数保留为**纵深防御**：只在**严格 PKCS#7 成立**（`1 ≤ pad ≤ 16` 且最后 pad 个
     *  字节全等于 pad）时才剥离，否则原样返回。ISO10126/随机填充末尾恰好全等于 pad 的
     *  概率 < 1/256 ⇒ 几乎不会误剥。
     *
     * 注：即便某条路径漏做此剥离，[decryptToString] 的 `trim()` 也能兜底——Java `trim()`
     * 去除所有 `<= U+0020` 的字符，而 `0x01..0x10` 全部落在此范围内（已验证 16/16）。
     */
    fun removePkcs7PaddingIfStrict(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data[data.size - 1].toInt() and 0xff
        if (pad !in 1..AES_BLOCK_SIZE) return data
        // 短数据守护：填充长度不得超过数据长度，否则切片会越界。
        if (pad > data.size) return data
        for (i in data.size - pad until data.size) {
            if ((data[i].toInt() and 0xff) != pad) return data
        }
        return data.copyOfRange(0, data.size - pad)
    }

    /**
     * 解包账号对称密钥：用 StretchedMasterKey 解密 `userDecryption.masterPasswordUnlock`
     * 中的 64 字节受保护密钥。
     *
     * @param encryptedKey 受保护的对称密钥（EncString type 2）
     * @param stretchedMasterKey [stretchMasterKey] 的产物
     */
    fun decryptSymmetricKey(
        encryptedKey: String,
        stretchedMasterKey: SymmetricCryptoKey,
    ): SymmetricCryptoKey {
        val decrypted = decrypt(encryptedKey, stretchedMasterKey)
        val key = SymmetricCryptoKey.fromFullKey(decrypted)
        decrypted.fill(0)
        return key
    }

    // ========== 加密 ==========

    /**
     * 加密为 EncString type 2（AES-256-CBC + HMAC-SHA256，encrypt-then-MAC）。
     *
     * IV 每次由 SecureRandom 生成 16 字节；写入恒为 type 2（Docs/03：不允许降级到 type 0）。
     */
    fun encrypt(plaintext: ByteArray, key: SymmetricCryptoKey): String {
        val iv = CryptoRandom.nextBytes(IV_SIZE)

        val cipher = Cipher.getInstance(AES_CBC_TRANSFORMATION)
        val secretKey = key.encKey.useBytes { SecretKeySpec(it, "AES") }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)

        val mac = key.macKey.useBytes { macKeyBytes ->
            computeCbcMac(iv, ciphertext, macKeyBytes)
        }

        val encoded = buildString {
            append(CipherType.AES_CBC_256_HMAC_SHA256_B64)
            append('.')
            append(iv.encodeStandardBase64())
            append('|')
            append(ciphertext.encodeStandardBase64())
            append('|')
            append(mac.encodeStandardBase64())
        }

        iv.fill(0)
        ciphertext.fill(0)
        mac.fill(0)
        return encoded
    }

    /** 加密字符串（UTF-8）为 EncString。 */
    fun encryptString(plaintext: String, key: SymmetricCryptoKey): String =
        encrypt(plaintext.toByteArray(StandardCharsets.UTF_8), key)

    // ========== AES-256-GCM（Vaultix 新增，Docs/03 第 4 节）==========

    /**
     * AES-256-GCM 加密。用于本地密钥包裹（Keystore 之外的二次封装）与本地密文缓存。
     *
     * 使用 [SymmetricCryptoKey.gcmKey] 这一独立子密钥，不与 CBC 复用密钥材料。
     *
     * @param plaintext 明文
     * @param key 对称密钥
     * @param aad 附加认证数据（参与完整性校验但不加密），如库 ID / 记录 ID
     */
    fun encryptGcm(
        plaintext: ByteArray,
        key: SymmetricCryptoKey,
        aad: ByteArray? = null,
    ): GcmSealed = aesGcmEncrypt(key = key.gcmKey, plaintext = plaintext, aad = aad)

    /** [encryptGcm] 的字符串信封版本，便于直接落库。 */
    fun encryptGcmToString(
        plaintext: ByteArray,
        key: SymmetricCryptoKey,
        aad: ByteArray? = null,
    ): String = encryptGcm(plaintext, key, aad).encode()

    /** 以 [SecureBytes] 裸密钥做 AES-256-GCM 加密（Keystore 解封出的密钥适用）。 */
    fun encryptGcm(
        plaintext: ByteArray,
        key: SecureBytes,
        aad: ByteArray? = null,
    ): GcmSealed = aesGcmEncrypt(key = key, plaintext = plaintext, aad = aad)

    /**
     * AES-256-GCM 解密。标签校验由 JCE 完成，失败抛 `AEADBadTagException`（密文被篡改或密钥错误）。
     */
    fun decryptGcm(
        sealed: GcmSealed,
        key: SymmetricCryptoKey,
        aad: ByteArray? = null,
    ): ByteArray = aesGcmDecrypt(key = key.gcmKey, sealed = sealed, aad = aad)

    /** 从字符串信封解密。 */
    fun decryptGcmFromString(
        raw: String,
        key: SymmetricCryptoKey,
        aad: ByteArray? = null,
    ): ByteArray = decryptGcm(GcmSealed.decode(raw), key, aad)

    /** 以 [SecureBytes] 裸密钥做 AES-256-GCM 解密。 */
    fun decryptGcm(
        sealed: GcmSealed,
        key: SecureBytes,
        aad: ByteArray? = null,
    ): ByteArray = aesGcmDecrypt(key = key, sealed = sealed, aad = aad)

    // ========== 辅助 ==========

    /**
     * JCE 标准实现的 PBKDF2-SHA256，仅用于与 BouncyCastle 实现做交叉校验（诊断/QA 用）。
     *
     * ⚠️ 仅接受可安全映射为 `char[]` 的口令字节（即 ASCII/ISO-8859-1 文本）。
     * MasterKey 是任意二进制，不能走这条路径。
     */
    fun pbkdf2Sha256Jce(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        lengthBytes: Int,
    ): ByteArray {
        val passwordChars = password.map { (it.toInt() and 0xFF).toChar() }.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, iterations, lengthBytes * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            passwordChars.fill('\u0000')
            spec.clearPassword()
        }
    }

    /** 常量时间比较两个字节数组（Docs/03 第 5 节）。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        io.vaultix.crypto.constantTimeEquals(a, b)

    /** 批量清零字节数组（Bastion 的 `clearBytes`）。 */
    fun wipe(vararg arrays: ByteArray) {
        arrays.forEach { it.fill(0) }
    }

    /** 生成 [size] 字节密码学安全随机数，封装为 [SecureBytes]。 */
    fun generateRandomBytes(size: Int): SecureBytes = SecureBytes.random(size)

    private fun deriveArgon2Native(
        passwordBytes: ByteArray,
        saltHash: ByteArray,
        iterations: Int,
        memoryMb: Int,
        parallelism: Int,
    ): ByteArray {
        val passwordBuffer = ByteBuffer.allocateDirect(passwordBytes.size).apply {
            put(passwordBytes)
            flip()
        }
        val saltBuffer = ByteBuffer.allocateDirect(saltHash.size).apply {
            put(saltHash)
            flip()
        }

        return try {
            val result = nativeArgon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBuffer,
                salt = saltBuffer,
                tCostInIterations = iterations,
                mCostInKibibyte = argon2MemoryKiB(memoryMb),
                parallelism = parallelism,
                hashLengthInBytes = AES_KEY_SIZE,
                version = Argon2Version.V13,
            )
            val hash = result.rawHashAsByteArray()
            wipeDirectBuffer(result.rawHash)
            wipeDirectBuffer(result.encodedOutput)
            hash
        } finally {
            wipeDirectBuffer(passwordBuffer)
            wipeDirectBuffer(saltBuffer)
        }
    }

    /**
     * BouncyCastle Argon2id 回退实现（native 失败时启用，见 [deriveMasterKeyArgon2]）。
     *
     * @suppress SwallowedException：catch 里的 OOM 有意不链作 cause——它只是触发
     * 条件，替换后的异常已携带可执行诊断（请求/堆上限/安全建议），保留 OOM 链
     * 反而掩盖信息。
     */
    @Suppress("SwallowedException")
    private fun deriveArgon2BouncyCastle(
        passwordBytes: ByteArray,
        saltHash: ByteArray,
        iterations: Int,
        memoryMb: Int,
        parallelism: Int,
    ): ByteArray {
        val params = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
            org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id,
        )
            .withSalt(saltHash)
            .withIterations(iterations)
            .withMemoryAsKB(argon2MemoryKiB(memoryMb))
            .withParallelism(parallelism)
            .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
            .build()

        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(params)

        val hash = ByteArray(AES_KEY_SIZE)
        try {
            generator.generateBytes(passwordBytes, hash)
        } catch (error: OutOfMemoryError) {
            throw VaultixKdfMemoryException(
                requestedMemoryMb = memoryMb,
                maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L),
                safeLimitMb = Argon2MemoryGuard.safeLimitMb(
                    maxHeapBytes = Runtime.getRuntime().maxMemory(),
                    usedHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                ),
            )
        }
        return hash
    }

    private fun wipeDirectBuffer(buffer: ByteBuffer) {
        if (buffer.isReadOnly) return
        val duplicate = buffer.duplicate()
        duplicate.clear()
        while (duplicate.hasRemaining()) {
            duplicate.put(0.toByte())
        }
    }

    private fun argon2MemoryKiB(memoryMb: Int): Int {
        require(memoryMb > 0) { "Vaultix Argon2id KDF memory must be positive: $memoryMb" }
        return try {
            Math.multiplyExact(memoryMb, KIB_PER_MIB)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException(
                "Vaultix Argon2id KDF memory is too large: ${memoryMb}MB",
                error,
            )
        }
    }

    companion object {
        /** 1 MiB = 1024 KiB（Argon2 内存换算）。 */
        const val KIB_PER_MIB = 1024

        /** AES-CBC 变换名（PKCS5Padding 在 JCE 中即 PKCS#7）。 */
        private const val AES_CBC_TRANSFORMATION = "AES/CBC/PKCS5Padding"

        /** AES 分组长度（字节）：PKCS#7 填充长度上限，也是块大小。 */
        private const val AES_BLOCK_SIZE = 16

        /** AES-256 密钥长度（字节）。 */
        const val AES_KEY_SIZE = 32

        /** HMAC-SHA256 密钥长度（字节）。 */
        const val MAC_KEY_SIZE = 32

        /** AES-CBC IV 长度（字节）。 */
        const val IV_SIZE = 16

        /** Bitwarden Send 原始密钥材料长度（字节）。 */
        const val SEND_KEY_MATERIAL_SIZE = 16

        /** PBKDF2 默认迭代次数（OWASP 推荐下限；实际以服务端 prelogin 下发为准）。 */
        const val DEFAULT_PBKDF2_ITERATIONS = 600_000

        /** Argon2id 默认迭代次数 t。 */
        const val DEFAULT_ARGON2_ITERATIONS = 3

        /** Argon2id 默认内存 m（MiB）。 */
        const val DEFAULT_ARGON2_MEMORY_MB = 64

        /** Argon2id 默认并行度 p。 */
        const val DEFAULT_ARGON2_PARALLELISM = 4

        /** Send 访问密码哈希迭代次数。 */
        const val SEND_PASSWORD_HASH_ITERATIONS = 100_000

        /**
         * JVM 回退允许的最大 Argon2 内存（MiB）。
         * 超过该值一律拒绝回退：在 Android 堆上分配这么大连续内存必然 OOM。
         */
        private const val ARGON2_JVM_FALLBACK_MAX_MEMORY_MB = 64

        private val ENC_INFO: ByteArray = "enc".toByteArray(StandardCharsets.UTF_8)
        private val MAC_INFO: ByteArray = "mac".toByteArray(StandardCharsets.UTF_8)
        private val SEND_SALT: ByteArray = "bitwarden-send".toByteArray(StandardCharsets.UTF_8)
        private val SEND_INFO: ByteArray = "send".toByteArray(StandardCharsets.UTF_8)
    }
}
