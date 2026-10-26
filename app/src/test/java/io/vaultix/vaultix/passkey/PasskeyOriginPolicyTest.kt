/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PasskeyOriginPolicy] 的回归锁（`.ai/ISSUES.md` #196）。
 *
 * 表驱动，覆盖三条最容易写错的边界：
 *  - **兄弟子域**（`a.example.com` vs `b.example.com`）必须拒绝 —— 这正是
 *    `UriMatcher.sameSite`（Cookie 口径）会放行、而 WebAuthn 口径必须拒绝的情形；
 *  - **后缀撞名**（`evilexample.com` vs `example.com`）必须拒绝 —— 裸 `endsWith` 会误判；
 *  - **公共后缀本身**（`co.uk`）能当 rpId 必须拒绝 —— 否则任何站点都能自称 `co.uk`。
 */
class PasskeyOriginPolicyTest {

    private fun assertOk(origin: String?, rpId: String?) {
        val verdict = PasskeyOriginPolicy.check(origin, rpId)
        assertEquals(
            "期望通过：origin=$origin rpId=$rpId，实际=$verdict",
            PasskeyOriginPolicy.Verdict.Ok,
            verdict,
        )
    }

    private fun assertRejected(origin: String?, rpId: String?) {
        val verdict = PasskeyOriginPolicy.check(origin, rpId)
        assertTrue(
            "期望拒绝：origin=$origin rpId=$rpId，实际=$verdict",
            verdict is PasskeyOriginPolicy.Verdict.Rejected,
        )
        // 拒绝必须带可读原因（只进日志），否则线上排查无从下手。
        assertTrue(
            "Rejected 必须带 reason",
            (verdict as PasskeyOriginPolicy.Verdict.Rejected).reason.isNotBlank(),
        )
    }

    @Test
    fun `origin 主机与 rpId 精确相等时通过`() {
        assertOk("https://example.com", "example.com")
        assertOk("https://example.com:8443", "example.com")
        assertOk("https://example.com/login?x=1", "example.com")
        // 大小写与根点差异由归一化吃掉。
        assertOk("https://Example.COM", "example.com.")
    }

    @Test
    fun `子域 origin 可以声明父域 rpId`() {
        assertOk("https://login.example.com", "example.com")
        assertOk("https://a.b.example.com", "example.com")
        // rpId 也可以正好是子域本身。
        assertOk("https://a.b.example.com", "b.example.com")
    }

    @Test
    fun `兄弟子域必须拒绝_这是与 Cookie 同站点口径的分界`() {
        // `UriMatcher.sameSite` 会放行这一对（可注册域相等），WebAuthn 必须拒绝。
        assertRejected("https://b.example.com", "a.example.com")
        assertRejected("https://a.example.com", "b.example.com")
    }

    @Test
    fun `后缀撞名必须拒绝_裸 endsWith 会误判`() {
        assertRejected("https://evilexample.com", "example.com")
        assertRejected("https://notexample.com", "example.com")
        assertRejected("https://example.com.evil.net", "example.com")
    }

    @Test
    fun `公共后缀本身不能当 rpId`() {
        // `a.b.co.uk` 以 `.co.uk` 结尾，但 `co.uk` 不属于任何单方。
        assertRejected("https://a.b.co.uk", "co.uk")
        assertRejected("https://anything.github.io", "github.io")
    }

    @Test
    fun `空值与无法解析的 origin 一律拒绝`() {
        assertRejected(null, "example.com")
        assertRejected("", "example.com")
        assertRejected("   ", "example.com")
        assertRejected("https://example.com", null)
        assertRejected("https://example.com", "")
        assertRejected("https://example.com", "   ")
        // 只有 scheme、没有 host。
        assertRejected("https://", "example.com")
    }

    @Test
    fun `无 scheme 的 origin 按 https 解析主机`() {
        assertEquals("example.com", PasskeyOriginPolicy.hostOfOrigin("example.com"))
        assertEquals("example.com", PasskeyOriginPolicy.hostOfOrigin("example.com/path"))
        assertEquals("example.com", PasskeyOriginPolicy.hostOfOrigin("https://Example.com:8443/x"))
        assertOk("example.com", "example.com")
    }

    @Test
    fun `非回环与非 http 方案的主机同样按主机名比对`() {
        // 策略层只做语法校验，不区分 scheme；`http://` 是否可作 origin 由 RP 决定。
        assertOk("http://example.com", "example.com")
        assertOk("https://127.0.0.1:8080", "127.0.0.1")
        // ⚠️ 记录现状：本策略**不**挡 `localhost` 之外的 IP 直连，
        // 也**不**做资产链接（assetlink）校验 —— 后者在 CredentialProvider 层。
        assertOk("https://192.168.1.5", "192.168.1.5")
    }
}
