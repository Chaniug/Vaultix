/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WebDomainResolver] 单测。
 *
 * 🔴 这组测试的价值全在 `collect` / `resolve` 的**空白串**用例上：
 * 2026-10-04 用户真机日志里 Firefox 登录页的 `fillRequest` 是 `webDomain=`
 * （**空字符串**）而 `fallback=null`。旧实现 `webDomains.firstOrNull()` 会把空串当域名，
 * 随后的 `webDomain ?: fallbackWebDomain` 因 `?:` 只对 null 短路而把兜底白白丢掉。
 * 这些用例就是那道回归闸 —— 把 `isNotBlank()` 改回原样，本文件必红。
 */
class WebDomainResolverTest {

    // ---------- collect：收集期空白防御 ----------

    @Test
    fun `collect 收下正常域名`() {
        assertEquals("example.com", WebDomainResolver.collect("example.com"))
    }

    @Test
    fun `collect 把空字符串视同没上报`() {
        // ⚠️ 这是 GeckoView 在 Firefox 登录页上的实测形态
        assertNull(WebDomainResolver.collect(""))
    }

    @Test
    fun `collect 把纯空白视同没上报`() {
        assertNull(WebDomainResolver.collect("   "))
    }

    @Test
    fun `collect 放行 null`() {
        assertNull(WebDomainResolver.collect(null))
    }

    @Test
    fun `collect 不改变大小写`() {
        // 大小写归一由 UriMatcher 统一做，这里保持原样，避免与既有匹配行为产生差异
        assertEquals("Example.COM", WebDomainResolver.collect("Example.COM"))
    }

    // ---------- resolve：兜底优先级 ----------

    @Test
    fun `resolve 优先用结构里的权威域名`() {
        val r = WebDomainResolver.resolve(
            collected = listOf("example.com"),
            urlBarHosts = listOf("addressbar.com"),
            structureTextHost = "text.com",
        )
        assertEquals("example.com", r.webDomain)
        assertNull(r.fallbackWebDomain)
        assertEquals("example.com", r.effective)
    }

    @Test
    fun `resolve 结构没域名时用地址栏`() {
        val r = WebDomainResolver.resolve(
            collected = emptyList(),
            urlBarHosts = listOf("addressbar.com"),
            structureTextHost = "text.com",
        )
        assertNull(r.webDomain)
        assertEquals("addressbar.com", r.fallbackWebDomain)
        assertEquals("addressbar.com", r.effective)
    }

    @Test
    fun `resolve 地址栏也没有时用结构文本`() {
        val r = WebDomainResolver.resolve(
            collected = emptyList(),
            urlBarHosts = emptyList(),
            structureTextHost = "text.com",
        )
        assertNull(r.webDomain)
        assertEquals("text.com", r.fallbackWebDomain)
        assertEquals("text.com", r.effective)
    }

    /**
     * 🔴 核心回归闸：收集列表里混进空串时**不能**让它赢掉兜底。
     *
     * 旧实现（`webDomains.firstOrNull()` + `webDomain ?: fallbackWebDomain`）在这里
     * 会返回 `effective == ""` —— 空串穿透到匹配器后 `hostOf("")` 返回 null，
     * 216 个候选全变成 0 分，表现为「有提示但匹配不出条目」。
     */
    @Test
    fun `resolve 列表里的空串不会赢掉真正的兜底`() {
        val r = WebDomainResolver.resolve(
            collected = listOf(""),
            urlBarHosts = listOf("addressbar.com"),
            structureTextHost = "text.com",
        )
        assertNull("空串不得被当成权威域名", r.webDomain)
        assertEquals("addressbar.com", r.fallbackWebDomain)
        assertEquals("addressbar.com", r.effective)
    }

    @Test
    fun `resolve 空串在前真域名在后时取到真域名`() {
        // 整棵树里第一个节点是 GeckoView 的容器节点（空串），真正的域名在后面的 iframe 根上
        val r = WebDomainResolver.resolve(
            collected = listOf("", "  ", "real.com"),
            urlBarHosts = emptyList(),
            structureTextHost = null,
        )
        assertEquals("real.com", r.webDomain)
        assertEquals("real.com", r.effective)
    }

    @Test
    fun `resolve 地址栏里的空串也会被跳过`() {
        val r = WebDomainResolver.resolve(
            collected = emptyList(),
            urlBarHosts = listOf("", "  ", "real.com"),
            structureTextHost = "text.com",
        )
        assertEquals("real.com", r.fallbackWebDomain)
        assertEquals("real.com", r.effective)
    }

    @Test
    fun `resolve 结构文本是空白时兜底为 null`() {
        val r = WebDomainResolver.resolve(
            collected = emptyList(),
            urlBarHosts = emptyList(),
            structureTextHost = "   ",
        )
        assertNull(r.fallbackWebDomain)
        assertNull(r.effective)
    }

    /**
     * 🔴 Firefox 的真实形态：**什么都拿不到**。
     *
     * 这是 2026-10-04 日志里那 13 次 `fillRequest` 的状态（`webDomain=` / `fallback=null`），
     * 此时 `effective` 为 null，由调用方走「无域名降级」
     * （见 `AutofillCandidateSource.matchLogins`），而不是返回空候选列表。
     */
    @Test
    fun `resolve 什么都拿不到时 effective 为 null`() {
        val r = WebDomainResolver.resolve(
            collected = listOf(""),
            urlBarHosts = emptyList(),
            structureTextHost = null,
        )
        assertNull(r.webDomain)
        assertNull(r.fallbackWebDomain)
        assertNull("拿不到域名必须返回 null 而不是空串", r.effective)
    }

    @Test
    fun `resolve effective 恒不等于空串`() {
        // 不变量断言：无论输入多怪，effective 要么是有意义域名，要么 null，绝不是 ""
        val inputs = listOf(
            Triple(listOf(""), emptyList(), null),
            Triple(listOf("", ""), listOf(""), ""),
            Triple(emptyList(), emptyList(), ""),
            Triple(listOf(" "), listOf(" "), " "),
        )
        for ((collected, bars, text) in inputs) {
            val r = WebDomainResolver.resolve(collected, bars, text)
            assertNull("effective 不得为空白: '$r'", r.effective?.takeIf { it.isBlank() })
        }
    }
}
