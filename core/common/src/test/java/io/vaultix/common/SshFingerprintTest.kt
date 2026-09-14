/*
 * Vaultix — core:common 单元测试
 * Copyright (C) 2026 Vaultix contributors
 *
 * 本文件为 Vaultix 项目的一部分，基于 GNU GPL-3.0 许可发布。
 */
package io.vaultix.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SshFingerprint] 与 `ssh-keygen -lf` 的**对照测试**。
 *
 * ⚠️ 三组期望值**全部抄自 `ssh-keygen -lf` 的真实输出**，不是用本实现算出来
 * 再回填的 —— 否则测试只能证明「代码没改」，证明不了「算法对不对」。
 * 复现：`ssh-keygen -lf id_ed25519.pub`。
 *
 * 覆盖三种算法族（ed25519 / RSA / ECDSA）：它们的 blob 结构不同（尤其 RSA 的
 * 密钥材料长得多），能顺带验证「不是只对某一种密钥碰巧算对」。
 */
class SshFingerprintTest {

    // 以下三把是测试专用的一次性密钥，无任何真实用途。
    private val ed25519PublicKey =
        "ssh-ed25519 " +
            "AAAAC3NzaC1lZDI1NTE5AAAAINjWdAG2egXV4ToTYHJa0mZQdLMehOxakRzm" +
            "694r4Fyn" +
            " vaultix-test"

    private val rsaPublicKey =
        "ssh-rsa " +
            "AAAAB3NzaC1yc2EAAAADAQABAAABAQCfLBItPAppZZsRGM4QxhdHpCnGLlW9" +
            "AFZoMBTuGZ765FBjYCXrgMjccnbfpVNmXHg8THLdsYd0gF6hFxvu3k+jbXqS" +
            "piIr1Vn68IzrT1xickv75UpdmtmdAElnO+JCmLheL1u3es6z8olQQneq7CQ7" +
            "qJdKcuNveO7Dd/uiUlqsp2CdNLT4cFPfNofvMIRXfEpFcnUxVS6gaDbJu/kp" +
            "aBG0h547pzGXrZjbaha/aDLzaiAmnKmVtHP+ki6rvJLPE5D8xlDkIf0M1kJa" +
            "D+QexKPDGMqtcBuXlu2pCmJ6rYmSEY2tNWxH5TT+xT7aiFzuDHALRQrhxG5q" +
            "bI6mPiqZjmyL" +
            " vaultix-rsa"

    private val ecdsaPublicKey =
        "ecdsa-sha2-nistp256 " +
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBA0WTGgC" +
            "EYSIzEUUivVasXMN81Apj2mn4WON+NwCkXq9Q7J0B7jomKqMK2gWkN04Lv/c" +
            "8z8RPkj0sdOvEx/NUd4=" +
            " vaultix-ecdsa"

    @Test
    fun ed25519MatchesSshKeygen() {
        assertEquals(
            "SHA256:8gQgmsoPYszLtQT8qR165HjWs5prluGD9UzZXNNAjco",
            SshFingerprint.of(ed25519PublicKey),
        )
    }

    @Test
    fun rsaMatchesSshKeygen() {
        assertEquals(
            "SHA256:GSHGQ79vsm5eosJSQhK8VegihWhzLzPysl1oyoIYcKE",
            SshFingerprint.of(rsaPublicKey),
        )
    }

    @Test
    fun ecdsaMatchesSshKeygen() {
        assertEquals(
            "SHA256:qNPUoC22o2QDhdFORockWgA0bKgokZsOFConnx/8j1Y",
            SshFingerprint.of(ecdsaPublicKey),
        )
    }

    @Test
    fun fingerprintIgnoresTrailingComment() {
        // 注释是给人看的，不参与指纹 —— 改注释不应改变指纹
        val withoutComment = ed25519PublicKey.split(" ").take(2).joinToString(" ")
        assertEquals(SshFingerprint.of(withoutComment), SshFingerprint.of(ed25519PublicKey))
    }

    @Test
    fun fingerprintTolerantOfSurroundingWhitespace() {
        // 从终端复制常带首尾空白/换行，不应影响结果
        assertEquals(
            SshFingerprint.of(ed25519PublicKey),
            SshFingerprint.of("  \n " + ed25519PublicKey + " \n "),
        )
    }

    @Test
    fun returnsNullWhenAlgorithmNameDisagreesWithBlob() {
        // blob 内部自带算法名；行首写错算法名 ⇒ 不是合法公钥，应拒绝而不是硬算
        val mismatched = ed25519PublicKey.replaceFirst("ssh-ed25519", "ssh-rsa")
        assertNull(SshFingerprint.of(mismatched))
    }

    @Test
    fun returnsNullForBlankInput() {
        assertNull(SshFingerprint.of(""))
        assertNull(SshFingerprint.of("   "))
    }

    @Test
    fun returnsNullForSingleToken() {
        // 只有算法名、没有 blob
        assertNull(SshFingerprint.of("ssh-ed25519"))
    }

    @Test
    fun returnsNullForNonBase64Blob() {
        assertNull(SshFingerprint.of("ssh-ed25519 not-base64!!!"))
    }

    @Test
    fun returnsNullForBase64ThatIsNotAnOpenSshBlob() {
        // 能解码但不是 OpenSSH blob：首 4 字节被当成长度后会越界/对不上算法名
        assertNull(SshFingerprint.of("ssh-ed25519 aGVsbG8="))
    }

    @Test
    fun returnsNullForPrivateKeyPem() {
        assertNull(SshFingerprint.of("-----BEGIN OPENSSH PRIVATE KEY-----"))
    }
}
