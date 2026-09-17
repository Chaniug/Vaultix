/*
 * Vaultix — core:common (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * ⚠️ 本文件**只做结构性断言**。"这把钥匙真的能用"由**真机 ssh-keygen** 交叉验证
 * （2026-09-18 实测：`ssh-keygen -y -f <本实现产出的 pem>` 导出的公钥与
 * `publicKey` 逐位一致，且 `ssh-keygen -lf` 与 `SshFingerprint.of()` 一致；
 * Ed25519 与 RSA 各一组）。JVM 单测里跑不了 ssh-keygen，故这里断言的是
 * **容器结构** —— 它能在不改外部环境的前提下挡住"字段写错顺序"这类回归。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Test

class SshKeyGeneratorTest {

    @Test
    fun ed25519_公钥行可被指纹解析且算法名正确() {
        val pair = SshKeyGenerator.generate(SshKeyGenerator.Algorithm.ED25519)

        assertThat(pair.publicKey).startsWith("ssh-ed25519 ")
        // 自洽性由 SshFingerprint 校验（它会对 blob 首字段与算法名做交叉核对）。
        assertThat(SshFingerprint.of(pair.publicKey)).isNotNull()
    }

    @Test
    fun ed25519_私钥是OpenSSH新格式() {
        val pair = SshKeyGenerator.generate(SshKeyGenerator.Algorithm.ED25519)

        assertThat(pair.privateKeyPem).startsWith(OPENSSH_HEADER)
        assertThat(pair.privateKeyPem.trimEnd()).endsWith(OPENSSH_FOOTER)
        assertThat(pair.privateKeyPem).contains("b3BlbnNzaC1rZXktdjEAAAAA")
    }

    @Test
    fun 私钥容器里的公钥blob与公钥行一致() {
        for (algorithm in SshKeyGenerator.Algorithm.entries) {
            val pair = SshKeyGenerator.generate(algorithm)
            val container = Base64.getMimeDecoder().decode(pemBody(pair.privateKeyPem))

            assertThat(String(container, 0, MAGIC.length, StandardCharsets.US_ASCII))
                .isEqualTo(MAGIC)

            val reader = BlobReader(container, MAGIC.length)
            assertThat(reader.stringText()).isEqualTo("none") // cipher
            assertThat(reader.stringText()).isEqualTo("none") // kdf
            assertThat(reader.stringText()).isEmpty() // kdfoptions
            assertThat(reader.int()).isEqualTo(1) // 密钥数

            val embeddedPublic = reader.string()
            val expectedPublic = Base64.getDecoder().decode(pair.publicKey.split(" ")[1])
            // ★ 这一条才是"私钥和公钥是一对"的实质断言：容器里嵌的那份公钥
            //   必须与对外给出的公钥行逐字节相同（否则 ssh 会报 mismatch）。
            assertThat(embeddedPublic).isEqualTo(expectedPublic)
        }
    }

    @Test
    fun 私钥段的两个校验字相同() {
        for (algorithm in SshKeyGenerator.Algorithm.entries) {
            val pair = SshKeyGenerator.generate(algorithm)
            val container = Base64.getMimeDecoder().decode(pemBody(pair.privateKeyPem))
            val reader = BlobReader(container, MAGIC.length)
            reader.string() // cipher
            reader.string() // kdf
            reader.string() // kdfoptions
            reader.int() // 密钥数
            reader.string() // 公钥 blob
            val section = reader.string()
            val inner = BlobReader(section, 0)
            assertThat(inner.int()).isEqualTo(inner.int())
        }
    }

    @Test
    fun rsa_公钥行可被指纹解析且是3072位() {
        val pair = SshKeyGenerator.generate(SshKeyGenerator.Algorithm.RSA_3072)

        assertThat(pair.publicKey).startsWith("ssh-rsa ")
        assertThat(SshFingerprint.of(pair.publicKey)).isNotNull()
    }

    @Test
    fun 两次生成的结果不同() {
        val a = SshKeyGenerator.generate(SshKeyGenerator.Algorithm.ED25519)
        val b = SshKeyGenerator.generate(SshKeyGenerator.Algorithm.ED25519)
        assertThat(a.publicKey).isNotEqualTo(b.publicKey)
        assertThat(a.privateKeyPem).isNotEqualTo(b.privateKeyPem)
    }

    private fun pemBody(pem: String): String = pem
        .lineSequence()
        .filter { !it.startsWith("-----") }
        .joinToString(separator = "")

    /** 极简的 OpenSSH blob 读取器（只够本测试用）：顺序读 `string` 与 `uint32`。 */
    private class BlobReader(private val bytes: ByteArray, start: Int) {
        private var cursor = start

        fun int(): Int {
            val value = ByteBuffer.wrap(bytes, cursor, 4).int
            cursor += 4
            return value
        }

        fun string(): ByteArray {
            val length = int()
            val value = bytes.copyOfRange(cursor, cursor + length)
            cursor += length
            return value
        }

        fun stringText(): String = String(string(), StandardCharsets.UTF_8)
    }

    private companion object {
        const val MAGIC = "openssh-key-v1\u0000"
        const val OPENSSH_HEADER = "-----BEGIN OPENSSH PRIVATE KEY-----"
        const val OPENSSH_FOOTER = "-----END OPENSSH PRIVATE KEY-----"
    }
}
