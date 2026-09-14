/*
 * Vaultix — core:crypto 单元测试
 * Copyright (C) 2026 Vaultix contributors
 *
 * 本文件为 Vaultix 项目的一部分，基于 GNU GPL-3.0 许可发布。
 */
package io.vaultix.crypto

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/** 测试用盐长度（与生产的 16 字节一致；提成常量以免被 Detekt MagicNumber 拦下）。 */
private const val TEST_SALT_BYTES = 16

/**
 * [PinKeyWrapper] 单测。
 *
 * ⚠️ Argon2id 参数**刻意调小**（`t=1, m=8, p=1`）：本文件被测的是**信封格式与三态语义**，
 * 不是 KDF 强度 —— 强度由 `KdfTest` 的参考向量负责。用生产参数（m=64MiB）会让每条
 * 用例都真吃 64MiB，把这一整个文件拖到几十秒；**跑得够快才会被经常跑**。
 */
class PinKeyWrapperTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)

    private val wrapper = PinKeyWrapper(crypto).apply {
        useParams(PinArgon2Params(iterations = 1, memoryMb = 8, parallelism = 1))
    }

    private val payload = utf8("bitwarden-enc||mac-64-byte-secret")

    /** 断言解包成功并取出明文（失败时给出可读信息，而不是 ClassCastException）。 */
    private fun opened(result: PinUnwrapResult): ByteArray {
        assertThat(result).isInstanceOf(PinUnwrapResult.Opened::class.java)
        return (result as PinUnwrapResult.Opened).payload
    }

    @Test
    fun wrapThenUnwrapReturnsOriginalPayload() {
        val envelope = wrapper.wrap("123456", payload)
        assertThat(opened(wrapper.unwrap("123456", envelope))).isEqualTo(payload)
    }

    @Test
    fun envelopeCarriesVersionPrefix() {
        // 前缀是将来换派生参数时识别旧格式的**唯一**依据，必须存在
        assertThat(wrapper.wrap("123456", payload)).startsWith("PV1:")
    }

    @Test
    fun samePinProducesDifferentEnvelopeEachTime() {
        // 每次新盐 ⇒ 同一 PIN 两次包裹的密文必须不同，
        // 否则「两个库共用同一个 PIN」会被落盘数据一眼看出
        val first = wrapper.wrap("123456", payload)
        val second = wrapper.wrap("123456", payload)
        assertThat(first).isNotEqualTo(second)
        // 但两者都应能用同一个 PIN 解开（盐变了不影响解开）
        assertThat(opened(wrapper.unwrap("123456", first))).isEqualTo(payload)
        assertThat(opened(wrapper.unwrap("123456", second))).isEqualTo(payload)
    }

    @Test
    fun envelopeDoesNotLeakPlaintext() {
        val envelope = wrapper.wrap("123456", payload)
        assertThat(envelope).doesNotContain(String(payload, Charsets.UTF_8))
    }

    @Test
    fun wrongPinIsReportedAsWrongPin() {
        val envelope = wrapper.wrap("123456", payload)
        assertThat(wrapper.unwrap("654321", envelope)).isEqualTo(PinUnwrapResult.WrongPin)
    }

    @Test
    fun longerPinSharingPrefixIsStillWrong() {
        // 防「前缀匹配」这类实现错误：123456 与 1234567 都不得互相打开
        val envelope = wrapper.wrap("123456", payload)
        assertThat(wrapper.unwrap("1234567", envelope)).isEqualTo(PinUnwrapResult.WrongPin)
    }

    @Test
    fun tamperedCiphertextIsReportedAsWrongPin() {
        // GCM 标签保证「密文被改过」不会静默通过
        val parts = wrapper.wrap("123456", payload).split(":")
        val sealed = GcmSealed.decode(parts[2])
        val flipped = sealed.ciphertext.copyOf()
        flipped[0] = (flipped[0].toInt() xor 0x01).toByte()
        val tampered = parts[0] + ":" + parts[1] + ":" +
            GcmSealed.of(sealed.nonce, flipped).encode()
        assertThat(wrapper.unwrap("123456", tampered)).isEqualTo(PinUnwrapResult.WrongPin)
    }

    @Test
    fun tamperedSaltIsReportedAsWrongPin() {
        // 改盐 ⇒ 派生出不同密钥 ⇒ 标签不过（而不是静默解出垃圾）
        val parts = wrapper.wrap("123456", payload).split(":")
        val otherSalt = CryptoRandom.nextBytes(TEST_SALT_BYTES).encodeStandardBase64()
        val tampered = parts[0] + ":" + otherSalt + ":" + parts[2]
        assertThat(wrapper.unwrap("123456", tampered)).isEqualTo(PinUnwrapResult.WrongPin)
    }

    @Test
    fun emptyPayloadRoundTrips() {
        val envelope = wrapper.wrap("123456", ByteArray(0))
        assertThat(opened(wrapper.unwrap("123456", envelope))).isEmpty()
    }

    @Test
    fun malformedEnvelopesAreNotReportedAsWrongPin() {
        // ★ 数据坏 ≠ PIN 错：UI 对两者的动作完全不同（走重设 vs 提示重试），
        //   塌进同一个失败分支就是「假状态」。
        val malformed = listOf(
            "",
            "   ",
            "VG1.YWFh|YmJi",                     // GCM 前缀，不是 PIN 信封
            "PV1:只有两段",
            "PV1:!!!不是base64!!!:VG1.YWFh|YmJi", // 盐不是合法 base64
            "PV1:YWJj:不是GCM信封",               // 内层信封坏
        )
        malformed.forEach { raw ->
            assertThat(wrapper.unwrap("123456", raw))
                .isInstanceOf(PinUnwrapResult.Malformed::class.java)
        }
    }

    @Test
    fun malformedDetailIsNotBlank() {
        // detail 是给排查用的，空串等于没诊断
        val result = wrapper.unwrap("123456", "不是信封")
        assertThat(result).isInstanceOf(PinUnwrapResult.Malformed::class.java)
        assertThat((result as PinUnwrapResult.Malformed).detail).isNotEmpty()
    }

    @Test
    fun productionParametersAreNotDowngraded() {
        // 回归点：PIN 档位不得悄悄低于主密码档位
        assertThat(PinArgon2Params.PRODUCTION.iterations)
            .isEqualTo(VaultixCrypto.DEFAULT_ARGON2_ITERATIONS)
        assertThat(PinArgon2Params.PRODUCTION.memoryMb)
            .isEqualTo(VaultixCrypto.DEFAULT_ARGON2_MEMORY_MB)
        assertThat(PinArgon2Params.PRODUCTION.parallelism)
            .isEqualTo(VaultixCrypto.DEFAULT_ARGON2_PARALLELISM)
    }
}
