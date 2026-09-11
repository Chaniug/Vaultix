/*
 * Vaultix — app:autofill · match 单测
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.match

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.UriMatch
import org.junit.Test

class UriMatcherTest {

    @Test
    fun `hostMatch exact`() {
        assertThat(UriMatcher.hostMatch("a.com", "a.com")).isTrue()
    }

    @Test
    fun `hostMatch subdomain is false`() {
        assertThat(UriMatcher.hostMatch("a.com", "sub.a.com")).isFalse()
    }

    @Test
    fun `baseDomainMatch ignores subdomain`() {
        assertThat(UriMatcher.baseDomainMatch("sub.a.com", "a.com")).isTrue()
    }

    @Test
    fun `startsWithMatch`() {
        assertThat(UriMatcher.startsWithMatch("https://a.com", "https://a.com/login")).isTrue()
    }

    @Test
    fun `exactMatch`() {
        assertThat(UriMatcher.exactMatch("a.com", "a.com")).isTrue()
    }

    @Test
    fun `regexMatch`() {
        assertThat(UriMatcher.regexMatch("a\\.com", "https://a.com")).isTrue()
    }

    @Test
    fun `matchByRule Domain ignores subdomain`() {
        assertThat(UriMatcher.matchByRule("https://a.com", "https://sub.a.com", UriMatch.Domain)).isTrue()
    }

    @Test
    fun `matchByRule Exact requires full equality`() {
        assertThat(UriMatcher.matchByRule("a.com", "a.com", UriMatch.Exact)).isTrue()
        assertThat(UriMatcher.matchByRule("a.com", "sub.a.com", UriMatch.Exact)).isFalse()
    }

    @Test
    fun `matchByRule StartsWith`() {
        assertThat(UriMatcher.matchByRule("https://a.com", "https://a.com/x", UriMatch.StartsWith)).isTrue()
    }

    @Test
    fun `matchByRule RegularExpression`() {
        assertThat(UriMatcher.matchByRule("a\\.com", "https://a.com", UriMatch.RegularExpression)).isTrue()
    }

    @Test
    fun `hostOf strips scheme and lowercases`() {
        assertThat(UriMatcher.hostOf("HTTPS://A.COM")).isEqualTo("a.com")
    }

    @Test
    fun `hostOf androidapp returns null`() {
        assertThat(UriMatcher.hostOf("androidapp://com.example")).isNull()
    }

    @Test
    fun `matchByRule Never never matches`() {
        assertThat(UriMatcher.matchByRule("https://a.com", "https://a.com", UriMatch.Never)).isFalse()
    }

    // ---- 逐字段站点校验（sameSite）----

    @Test
    fun `sameSite true for same host and for subdomain of same base domain`() {
        assertThat(UriMatcher.sameSite("github.com", "github.com")).isTrue()
        assertThat(UriMatcher.sameSite("www.github.com", "github.com")).isTrue()
        assertThat(UriMatcher.sameSite("https://github.com/login", "github.com")).isTrue()
    }

    @Test
    fun `sameSite false for a third-party frame domain`() {
        // 页面嵌了别的域名的 iframe（外挂登录 / 支付组件）→ 不应被填
        assertThat(UriMatcher.sameSite("evil.com", "github.com")).isFalse()
        assertThat(UriMatcher.sameSite("cdn.jsdelivr.net", "github.com")).isFalse()
    }

    @Test
    fun `sameSite is permissive when either side is unknown`() {
        // 原生 App 字段没有 webDomain；无法判定时放行，宁可多填也不误伤
        assertThat(UriMatcher.sameSite(null, "github.com")).isTrue()
        assertThat(UriMatcher.sameSite("github.com", null)).isTrue()
        assertThat(UriMatcher.sameSite("  ", "github.com")).isTrue()
        assertThat(UriMatcher.sameSite(null, null)).isTrue()
    }
}
