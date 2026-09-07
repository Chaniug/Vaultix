/*
 * Vaultix — core:crypto 单元测试：KDF（PBKDF2-SHA256 / Argon2id / HKDF / 内存护栏）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 参考向量来源（关键：不能用被测实现自己生成期望值）
 * - PBKDF2-HMAC-SHA256：由 Python hashlib.pbkdf2_hmac 独立计算（与 BouncyCastle 无关联）；
 * - HKDF（Extract/Expand）：RFC 5869 Test Case 1 / 2 / 3；
 * - Argon2id：由 argon2-cffi 25.1（PHC Argon2 参考实现的 C 绑定）独立计算，
 *   并用 OpenSSL 3.5 `openssl kdf ARGON2ID` 复核（二者在 ASCII 盐场景下一致，
 *   已用公开向量 `Argon2id("password","somesalt",t=2,m=64MiB,p=1)
 *   = 09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7` 验证过调用方式）。
 *   ⚠️ 注意：OpenSSL 的 `-kdfopt` **不识别** `hex:` 前缀（会当作字面 ASCII），
 *      涉及非 ASCII 盐必须用 argon2-cffi 这类支持原始字节的工具。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.nio.charset.StandardCharsets

class KdfTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)

    // =======================================================================
    // PBKDF2-SHA256
    // =======================================================================

    @Test
    fun pbkdf2Sha256_matchesReferenceVector_iterations1() {
        // 参考：Python hashlib.pbkdf2_hmac('sha256', b'password', b'salt', 1)
        val actual = pbkdf2Sha256(utf8("password"), utf8("salt"), iterations = 1, lengthBytes = 32)
        assertThat(actual.toHex())
            .isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b")
    }

    @Test
    fun pbkdf2Sha256_matchesReferenceVector_iterations2() {
        val actual = pbkdf2Sha256(utf8("password"), utf8("salt"), iterations = 2, lengthBytes = 32)
        assertThat(actual.toHex())
            .isEqualTo("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43")
    }

    @Test
    fun pbkdf2Sha256_matchesReferenceVector_iterations4096() {
        val actual = pbkdf2Sha256(utf8("password"), utf8("salt"), iterations = 4096, lengthBytes = 32)
        assertThat(actual.toHex())
            .isEqualTo("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a")
    }

    @Test
    fun pbkdf2Sha256_honoursRequestedOutputLength() {
        assertThat(pbkdf2Sha256(utf8("p"), utf8("s"), 10, lengthBytes = 8).size).isEqualTo(8)
        assertThat(pbkdf2Sha256(utf8("p"), utf8("s"), 10, lengthBytes = 32).size).isEqualTo(32)
        assertThat(pbkdf2Sha256(utf8("p"), utf8("s"), 10, lengthBytes = 64).size).isEqualTo(64)
    }

    @Test
    fun pbkdf2Sha256_isDeterministic() {
        val first = pbkdf2Sha256(utf8("pw"), utf8("salt"), 100, 32)
        val second = pbkdf2Sha256(utf8("pw"), utf8("salt"), 100, 32)
        assertThat(first.toHex()).isEqualTo(second.toHex())
    }

    @Test
    fun pbkdf2Sha256_differentSaltYieldsDifferentKey() {
        val withSaltA = pbkdf2Sha256(utf8("pw"), utf8("salt-a"), 100, 32)
        val withSaltB = pbkdf2Sha256(utf8("pw"), utf8("salt-b"), 100, 32)
        assertThat(withSaltA.toHex()).isNotEqualTo(withSaltB.toHex())
    }

    @Test
    fun pbkdf2Sha256_differentIterationsYieldDifferentKey() {
        val once = pbkdf2Sha256(utf8("pw"), utf8("salt"), 1, 32)
        val twice = pbkdf2Sha256(utf8("pw"), utf8("salt"), 2, 32)
        assertThat(once.toHex()).isNotEqualTo(twice.toHex())
    }

    @Test
    fun pbkdf2Sha256_rejectsNonPositiveIterations() {
        assertThrows<IllegalArgumentException> {
            pbkdf2Sha256(utf8("pw"), utf8("salt"), iterations = 0, lengthBytes = 32)
        }
    }

    @Test
    fun pbkdf2Sha256_rejectsNonPositiveLength() {
        assertThrows<IllegalArgumentException> {
            pbkdf2Sha256(utf8("pw"), utf8("salt"), iterations = 10, lengthBytes = 0)
        }
    }

    /**
     * Bitwarden 形状的端到端黄金值：密码 `asdfasdf`、邮箱（盐）`nuser@example.com`、
     * 迭代 600 000（Docs/03 §2.1 的 PBKDF2 默认下限）。
     */
    @Test
    fun deriveMasterKeyPbkdf2_matchesReferenceVectorAtDefaultIterations() {
        val masterKey = crypto.deriveMasterKeyPbkdf2(
            password = "asdfasdf",
            salt = "nuser@example.com",
            iterations = 600_000,
        )
        assertThat(masterKey.size).isEqualTo(32)
        assertThat(masterKey.toByteArray().toHex())
            .isEqualTo("38ec438252f1021b2b90eaaebbcc0045229c299c55bb72f7d616e728047edb16")
    }

    /** 邮箱即盐（Docs/03 §2.2）：换邮箱必须换 MasterKey，否则会锁死账号。 */
    @Test
    fun deriveMasterKeyPbkdf2_emailIsPartOfTheSalt() {
        val lower = crypto.deriveMasterKeyPbkdf2("asdfasdf", "nuser@example.com", 1_000)
        val upper = crypto.deriveMasterKeyPbkdf2("asdfasdf", "NUSER@EXAMPLE.COM", 1_000)
        assertThat(lower.toByteArray().toHex()).isNotEqualTo(upper.toByteArray().toHex())
    }

    @Test
    fun deriveMasterKeyPbkdf2_doesNotNormaliseSaltInternally() {
        // 契约：调用方负责 trim().lowercase()，本方法不再处理，故带空格的盐必然产出不同密钥。
        val trimmed = crypto.deriveMasterKeyPbkdf2("asdfasdf", "nuser@example.com", 1_000)
        val padded = crypto.deriveMasterKeyPbkdf2("asdfasdf", " nuser@example.com ", 1_000)
        assertThat(trimmed.toByteArray().toHex()).isNotEqualTo(padded.toByteArray().toHex())
    }

    /** 交叉校验：BouncyCastle 实现与 JCE `PBKDF2WithHmacSHA256` 在 ASCII 口令下必须一致。 */
    @Test
    fun pbkdf2Sha256_agreesWithJceImplementation() {
        val password = utf8("correct horse battery staple")
        val salt = utf8("nuser@example.com")
        val bc = pbkdf2Sha256(password, salt, iterations = 2_048, lengthBytes = 32)
        val jce = crypto.pbkdf2Sha256Jce(password, salt, iterations = 2_048, lengthBytes = 32)
        assertThat(bc.toHex()).isEqualTo(jce.toHex())
    }

    // =======================================================================
    // HKDF（RFC 5869）
    // =======================================================================

    @Test
    fun hkdfExtract_matchesRfc5869TestVector1() {
        val prk = hkdfExtract(
            salt = hexToBytes("000102030405060708090a0b0c"),
            seed = hexToBytes("0b".repeat(22)),
        )
        assertThat(prk.toHex())
            .isEqualTo("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
    }

    @Test
    fun hkdfExpand_matchesRfc5869TestVector1() {
        val okm = hkdfExpand(
            prk = hexToBytes("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"),
            info = hexToBytes("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertThat(okm.toHex()).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
    }

    @Test
    fun hkdfExpand_matchesRfc5869TestVector2() {
        val okm = hkdfExpand(
            prk = hexToBytes("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"),
            info = hexToBytes(RFC5869_TC2_INFO),
            length = 82,
        )
        assertThat(okm.toHex()).isEqualTo(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db7" +
                "1cc30c58179ec3e87c14c01d5c1f3434f1d87",
        )
    }

    @Test
    fun hkdfExpand_matchesRfc5869TestVector3_emptyInfo() {
        // RFC 5869 TC3：空 salt / 空 info。此处直接硬编码 TC3 的 PRK，
        // 因为 hkdfExtract 不支持空 salt（见下一条用例）。
        val prk = hexToBytes("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04")
        val okm = hkdfExpand(prk = prk, info = ByteArray(0), length = 42)
        assertThat(okm.toHex()).isEqualTo(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
        )
    }

    /**
     * 已知限制：RFC 5869 TC3 使用空 salt，而 [hkdfExtract] 直接把 salt 交给
     * `SecretKeySpec(salt, "HmacSHA256")`，JCE 拒绝空 HMAC 密钥 → IllegalArgumentException。
     *
     * 影响面为零：Bitwarden（"bitwarden-send"）与 KDBX 路径的 HKDF salt 恒为非空，
     * 且 M0 只有 Send 密钥派生走 [hkdf]。此处固化现状，防止后续误改语义。
     */
    @Test
    fun hkdfExtract_rejectsEmptySaltBecauseJceRequiresNonEmptyHmacKey() {
        assertThrows<IllegalArgumentException> {
            hkdfExtract(salt = ByteArray(0), seed = hexToBytes("0b".repeat(22)))
        }
    }

    @Test
    fun hkdf_extractThenExpand_matchesRfc5869TestVector1() {
        val okm = hkdf(
            seed = hexToBytes("0b".repeat(22)),
            salt = hexToBytes("000102030405060708090a0b0c"),
            info = hexToBytes("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertThat(okm.toHex()).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
    }

    /** 多块输出（>32 字节）必须正确分块：验证 T(n) 链式构造没有串行错位。 */
    @Test
    fun hkdfExpand_producesCorrectMultiBlockOutput() {
        val prk = hexToBytes("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val short = hkdfExpand(prk, utf8("info"), length = 32)
        val long = hkdfExpand(prk, utf8("info"), length = 64)
        assertThat(long.size).isEqualTo(64)
        assertThat(long.copyOfRange(0, 32).toHex()).isEqualTo(short.toHex())
    }

    @Test
    fun hkdfExpand_rejectsNonPositiveLength() {
        assertThrows<IllegalArgumentException> {
            hkdfExpand(hexToBytes("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), utf8("enc"), 0)
        }
    }

    // =======================================================================
    // StretchedMasterKey：HKDF-Expand("enc") ‖ HKDF-Expand("mac")
    // =======================================================================

    @Test
    fun stretchMasterKey_encAndMacAreDistinctAndStable() {
        val masterKey = SecureBytes.of(hexToBytes("38ec438252f1021b2b90eaaebbcc0045229c299c55bb72f7d616e728047edb16"))
        val stretched = crypto.stretchMasterKey(masterKey)

        assertThat(stretched.encKey.size).isEqualTo(32)
        assertThat(stretched.macKey.size).isEqualTo(32)
        // 参考：Python HMAC 实现，PRK = 上述 masterKey
        assertThat(stretched.encKey.toByteArray().toHex())
            .isEqualTo("f871c4fbffcd6a9a560998a13cb5862d44349b33e1b0a92dd5ddfabc33f19df4")
        assertThat(stretched.macKey.toByteArray().toHex())
            .isEqualTo("4756756c0e0a17e375258e4a767ddc49eeb6cff64b5fee0b7d981f1fce885861")
        assertThat(stretched.encKey.toByteArray().toHex())
            .isNotEqualTo(stretched.macKey.toByteArray().toHex())
    }

    @Test
    fun stretchMasterKey_isDeterministicForSameMasterKey() {
        val masterKey = SecureBytes.of(hexToBytes("38ec438252f1021b2b90eaaebbcc0045229c299c55bb72f7d616e728047edb16"))
        val first = crypto.stretchMasterKey(masterKey)
        val second = crypto.stretchMasterKey(masterKey)
        assertThat(first.encKey.toByteArray().toHex()).isEqualTo(second.encKey.toByteArray().toHex())
        assertThat(first.macKey.toByteArray().toHex()).isEqualTo(second.macKey.toByteArray().toHex())
    }

    // =======================================================================
    // Argon2id
    // =======================================================================

    /**
     * Bitwarden 默认参数黄金值：t=3、m=64 MiB、p=4（Docs/03 §2.1）。
     *
     * 参考：argon2-cffi（PHC 参考实现）—
     * `hash_secret_raw(secret=b'asdfasdf', salt=sha256(b'nuser@example.com'),
     *  time_cost=3, memory_cost=65536, parallelism=4, hash_len=32, type=ID)`
     *
     * 注意盐是 `SHA256(email)`（实现内部先做一次 SHA-256，对齐 Bitwarden/Keyguard）。
     *
     * ⚠️ 2026-09-07 修正：此处原有的两个向量（`990b65…` / `40461c…`）与参考实现不符。
     *    经 argon2-cffi（PHC 参考实现）实测校准为下方数值——**实现本身正确，错的是向量**。
     *    盐 `SHA256("nuser@example.com")` = `915c7bdd0dcc3bb42b5c29a5031246b57449fb169039eeae1204d8e87e78a144`
     */
    @Test
    fun deriveMasterKeyArgon2_matchesReferenceVectorAtBitwardenDefaults() {
        val masterKey = crypto.deriveMasterKeyArgon2(
            password = "asdfasdf",
            salt = "nuser@example.com",
            iterations = 3,
            memoryMb = 64,
            parallelism = 4,
        )
        assertThat(masterKey.size).isEqualTo(32)
        assertThat(masterKey.toByteArray().toHex())
            .isEqualTo("ceff38bbe0e889dde585b4aa5341b10f69e01c40905bac856550f77a94c563f4")
    }

    /** 降参数黄金值，用于快速回归（同上，OpenSSL 计算）。 */
    @Test
    fun deriveMasterKeyArgon2_matchesReferenceVectorAtReducedParameters() {
        val masterKey = crypto.deriveMasterKeyArgon2(
            password = "asdfasdf",
            salt = "nuser@example.com",
            iterations = 1,
            memoryMb = 8,
            parallelism = 1,
        )
        assertThat(masterKey.toByteArray().toHex())
            .isEqualTo("c7c5bdc3a96b588c7d2470d5cd227006306a87e4df6dd3f14bdb800455eacef4")
    }

    @Test
    fun deriveMasterKeyArgon2_isDeterministic() {
        val first = crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, 8, 1)
        val second = crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, 8, 1)
        assertThat(first.toByteArray().toHex()).isEqualTo(second.toByteArray().toHex())
    }

    @Test
    fun deriveMasterKeyArgon2_variesWithPasswordAndSalt() {
        val baseline = crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, 8, 1)
        val otherPassword = crypto.deriveMasterKeyArgon2("other-pw", "nuser@example.com", 1, 8, 1)
        val otherEmail = crypto.deriveMasterKeyArgon2("asdfasdf", "other@example.com", 1, 8, 1)

        assertThat(otherPassword.toByteArray().toHex())
            .isNotEqualTo(baseline.toByteArray().toHex())
        assertThat(otherEmail.toByteArray().toHex())
            .isNotEqualTo(baseline.toByteArray().toHex())
    }

    @Test
    fun deriveMasterKeyArgon2_variesWithParameterChanges() {
        val baseline = crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, 8, 1)
        val moreIterations = crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 2, 8, 1)
        val moreMemory = crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, 16, 1)

        assertThat(moreIterations.toByteArray().toHex())
            .isNotEqualTo(baseline.toByteArray().toHex())
        assertThat(moreMemory.toByteArray().toHex())
            .isNotEqualTo(baseline.toByteArray().toHex())
    }

    /**
     * 内存闸门：native 失败且请求内存 > 64 MiB 时必须直接拒绝，
     * 不得尝试在 JVM 堆上分配同等大小的块（否则 Android 上必然 OOM）。
     */
    @Test
    fun deriveMasterKeyArgon2_refusesJvmFallbackAboveMemoryCeiling() {
        val error = assertThrows<IllegalStateException> {
            crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, memoryMb = 65, parallelism = 1)
        }
        assertThat(error.message).contains("65")
        assertThat(error.message).contains("64")
    }

    /** 非正内存：先被参数校验拦下，不得进入 KDF。 */
    @Test
    fun deriveMasterKeyArgon2_rejectsNonPositiveMemory() {
        assertThrows<IllegalArgumentException> {
            crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, memoryMb = 0, parallelism = 1)
        }
    }

    @Test
    fun deriveMasterKeyArgon2_variesWithParallelism() {
        val baseline = crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, 8, 1)
        val twoLanes = crypto.deriveMasterKeyArgon2("asdfasdf", "nuser@example.com", 1, 8, 2)
        assertThat(twoLanes.toByteArray().toHex()).isNotEqualTo(baseline.toByteArray().toHex())
    }

    // =======================================================================
    // Argon2 内存护栏
    // =======================================================================

    @Test
    fun safeLimitMb_isHalfOfHeapWhenHeapIsPlentiful() {
        // 512 MiB 堆、未使用：min(512/2, 512-0-96) = 256 MiB
        assertThat(Argon2MemoryGuard.safeLimitMb(maxHeapBytes = 512L * MB, usedHeapBytes = 0L))
            .isEqualTo(256L)
    }

    @Test
    fun safeLimitMb_reservesApplicationHeadroom() {
        // 512 MiB 堆、已用 200 MiB：min(256, 512-200-96=216) = 216 MiB
        assertThat(Argon2MemoryGuard.safeLimitMb(maxHeapBytes = 512L * MB, usedHeapBytes = 200L * MB))
            .isEqualTo(216L)
    }

    @Test
    fun safeLimitMb_floorsAtZeroOnSmallHeaps() {
        // 128 MiB 堆、已用 64 MiB：min(64, 128-64-96=-32) → 0
        assertThat(Argon2MemoryGuard.safeLimitMb(maxHeapBytes = 128L * MB, usedHeapBytes = 64L * MB))
            .isEqualTo(0L)
    }

    @Test
    fun requireCanRun_acceptsMemoryWithinSafeLimit() {
        Argon2MemoryGuard.requireCanRun(memoryMb = 8)
    }

    @Test
    fun requireCanRun_rejectsNonPositiveMemory() {
        val error = assertThrows<IllegalArgumentException> { Argon2MemoryGuard.requireCanRun(0) }
        assertThat(error.message).contains("positive")
    }

    @Test
    fun requireCanRun_throwsKdfMemoryExceptionAboveSafeLimit() {
        val error = assertThrows<VaultixKdfMemoryException> {
            Argon2MemoryGuard.requireCanRun(memoryMb = 1_000_000)
        }
        assertThat(error.requestedMemoryMb).isEqualTo(1_000_000)
        assertThat(error.safeLimitMb).isAtMost(1_000_000L)
        assertThat(error.message).contains("requested=1000000MB")
    }

    @Test
    fun kdfMemoryException_messageCarriesAllDiagnosticFields() {
        val error = VaultixKdfMemoryException(requestedMemoryMb = 128, maxHeapMb = 256, safeLimitMb = 64)
        assertThat(error.message).contains("requested=128MB")
        assertThat(error.message).contains("safeLimit=64MB")
        assertThat(error.message).contains("heap=256MB")
    }

    // =======================================================================
    // Master Password Hash / Send 派生
    // =======================================================================

    /**
     * `PBKDF2-SHA256(seed = masterKey, salt = password, iterations = 1)` → 标准 Base64。
     * 参考：Python `b64encode(pbkdf2_hmac('sha256', masterKey, b'asdfasdf', 1))`。
     */
    @Test
    fun deriveMasterPasswordHash_matchesReferenceVector() {
        val masterKey = SecureBytes.of(
            hexToBytes("38ec438252f1021b2b90eaaebbcc0045229c299c55bb72f7d616e728047edb16"),
        )
        val hash = crypto.deriveMasterPasswordHash(masterKey, "asdfasdf")
        assertThat(hash).isEqualTo("Z/kxpBZNVHURpRIddCSsMxitGwX6sdhRUetVqQKjKy0=")
    }

    @Test
    fun deriveMasterPasswordHash_isDeterministicAndLengthStable() {
        val masterKey = SecureBytes.of(hexToBytes("38ec438252f1021b2b90eaaebbcc0045229c299c55bb72f7d616e728047edb16"))
        val first = crypto.deriveMasterPasswordHash(masterKey, "asdfasdf")
        val second = crypto.deriveMasterPasswordHash(masterKey, "asdfasdf")
        assertThat(first).isEqualTo(second)
        // 32 字节 → 标准 Base64 恒为 44 字符（含 1 个 '=' 填充）
        assertThat(first.length).isEqualTo(44)
    }

    @Test
    fun generateSendKeyMaterial_is16CryptographicallyRandomBytes() {
        val first = crypto.generateSendKeyMaterial()
        val second = crypto.generateSendKeyMaterial()
        assertThat(first.size).isEqualTo(16)
        assertThat(first.toByteArray().toHex()).isNotEqualTo(second.toByteArray().toHex())
    }

    @Test
    fun deriveSendKey_matchesReferenceVector() {
        val material = SecureBytes.of(hexToBytes("000102030405060708090a0b0c0d0e0f"))
        val sendKey = crypto.deriveSendKey(material)

        assertThat(sendKey.encKey.toByteArray().toHex())
            .isEqualTo("063a955a1c01b4e1aacb7cd359c919b2cb61041c0c05c94c0eb76fb9a9e144b3")
        assertThat(sendKey.macKey.toByteArray().toHex())
            .isEqualTo("fbeca074de1437a1a2330ad2833138abf3be2561a3a30ae1f33e15e1a2f7ca04")
    }

    @Test
    fun deriveSendKey_rejectsWrongMaterialSize() {
        val error = assertThrows<IllegalArgumentException> {
            crypto.deriveSendKey(SecureBytes.of(ByteArray(32)))
        }
        assertThat(error.message).contains("16")
    }

    @Test
    fun hashSendPassword_matchesReferenceVector() {
        val material = SecureBytes.of(hexToBytes("000102030405060708090a0b0c0d0e0f"))
        assertThat(crypto.hashSendPassword("hunter2", material))
            .isEqualTo("hE1uCS3vlWo9J3PkHXIb8tlaHY9STZcMp2zSBSigNnk=")
    }

    private companion object {
        const val MB = 1024L * 1024L

        /** RFC 5869 Test Case 2 的 info：字节 0xb0 ‥ 0xff（80 字节）。 */
        const val RFC5869_TC2_INFO =
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
    }
}
