/*
 * Vaultix — core:crypto 单元测试：Base64 编解码（Docs/03 §5）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 覆盖目标：与 java.util.Base64 行为一致（替换 android.util.Base64 的等价性）、
 * 标准/URL-safe 字母表互认、有无 padding 均可解析、畸形输入的拒绝路径。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64

class Base64CodecTest {

    // =======================================================================
    // 编码
    // =======================================================================

    @Test
    fun encode_matchesJavaUtilBase64StandardEncoder() {
        val payload = ByteArray(64) { it.toByte() }
        assertThat(payload.encodeStandardBase64())
            .isEqualTo(Base64.getEncoder().encodeToString(payload))
    }

    @Test
    fun encode_hasNoLineWraps() {
        // android.util.Base64.NO_WRAP 的等价要求：长输入也不得插入换行
        val payload = ByteArray(300) { it.toByte() }
        assertThat(payload.encodeStandardBase64()).doesNotContain("\n")
        assertThat(payload.encodeStandardBase64()).doesNotContain("\r")
    }

    @Test
    fun encode_usesStandardAlphabetWithPadding() {
        // 0xFB 0xFF → "+/8="（标准字母表），而非 URL-safe 的 "-_8="
        assertThat(byteArrayOf(0xFB.toByte(), 0xFF.toByte()).encodeStandardBase64()).isEqualTo("+/8=")
    }

    @Test
    fun encode_emptyArrayYieldsEmptyString() {
        assertThat(ByteArray(0).encodeStandardBase64()).isEmpty()
    }

    // =======================================================================
    // 解码
    // =======================================================================

    @Test
    fun decode_matchesJavaUtilBase64Decoder() {
        val encoded = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
        val expected = Base64.getDecoder().decode(encoded)
        assertThat(decodeStandardBase64(encoded, "payload").toHex()).isEqualTo(expected.toHex())
    }

    @Test
    fun decode_acceptsUrlSafeAlphabet() {
        val payload = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0x01, 0x02)
        val urlSafe = Base64.getUrlEncoder().encodeToString(payload)
        assertThat(urlSafe).contains("_")
        assertThat(decodeStandardBase64(urlSafe, "payload").toHex()).isEqualTo(payload.toHex())
    }

    @Test
    fun decode_acceptsUnpaddedInput() {
        val payload = "vaultix".toByteArray()
        val padded = Base64.getEncoder().encodeToString(payload)
        val unpadded = padded.trimEnd('=')

        assertThat(unpadded).doesNotContain("=")
        assertThat(decodeStandardBase64(unpadded, "payload").toHex()).isEqualTo(payload.toHex())
    }

    @Test
    fun decode_acceptsUnpaddedUrlSafeInput() {
        val payload = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0x7F.toByte())
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        assertThat(decodeStandardBase64(encoded, "payload").toHex()).isEqualTo(payload.toHex())
    }

    @Test
    fun decode_trimsSurroundingWhitespace() {
        val payload = "vaultix".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(payload)
        assertThat(decodeStandardBase64("  $encoded\n", "payload").toHex()).isEqualTo(payload.toHex())
    }

    @Test
    fun decode_roundTripsPayloadsOfEveryPaddingRemainder() {
        // 覆盖 length % 4 == 1/2/3 三种补 padding 的分支。
        // 不含 size == 0：空明文编码为空串，而 decodeStandardBase64 明确拒绝空分段
        // （见 decode_rejectsEmptyPart），故从 1 开始。
        for (size in 1..12) {
            val payload = ByteArray(size) { (it * 7).toByte() }
            val encoded = payload.encodeStandardBase64()
            assertThat(decodeStandardBase64(encoded, "payload").toHex()).isEqualTo(payload.toHex())
        }
    }

    // =======================================================================
    // 畸形输入
    // =======================================================================

    @Test
    fun decode_rejectsEmptyPart() {
        assertThrows<IllegalArgumentException> { decodeStandardBase64("", "iv") }
    }

    @Test
    fun decode_rejectsWhitespaceOnlyPart() {
        assertThrows<IllegalArgumentException> { decodeStandardBase64("   ", "iv") }
    }

    @Test
    fun decode_rejectsOversizedPart() {
        assertThrows<IllegalArgumentException> {
            decodeStandardBase64("A".repeat(MAX_BASE64_PART_LENGTH + 1), "data")
        }
    }

    @Test
    fun decode_rejectsIllegalCharacters() {
        val error = assertThrows<IllegalArgumentException> { decodeStandardBase64("ab**cd", "mac") }
        assertThat(error.message).contains("mac")
    }

    @Test
    fun decode_errorMessageNamesOffendingPart() {
        val error = assertThrows<IllegalArgumentException> { decodeStandardBase64("###", "ciphertext") }
        assertThat(error.message).contains("ciphertext")
    }

    @Test
    fun decode_reportsLengthInErrorMessage() {
        val error = assertThrows<IllegalArgumentException> { decodeStandardBase64("!!!!", "iv") }
        assertThat(error.message).contains("4")
    }
}
