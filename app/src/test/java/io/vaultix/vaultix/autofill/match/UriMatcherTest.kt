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
}
