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
import io.vaultix.vaultix.autofill.model.AutofillCredential
import io.vaultix.vaultix.autofill.model.AutofillUri
import org.junit.Test

class BitwardenLikeAutofillMatcherTest {

    private fun cred(
        id: String,
        uris: List<String>,
        favorite: Boolean = false,
    ): AutofillCredential = AutofillCredential(
        vaultId = "v",
        itemId = id,
        name = id,
        username = "u$id",
        password = "p",
        uris = uris.map { AutofillUri(it) },
        isFavorite = favorite,
    )

    private fun credWith(
        id: String,
        uri: String,
        match: UriMatch,
    ): AutofillCredential = AutofillCredential(
        vaultId = "v",
        itemId = id,
        name = id,
        username = "u$id",
        password = "p",
        uris = listOf(AutofillUri(uri, match)),
    )

    @Test
    fun `exact domain match is returned`() {
        val creds = listOf(
            cred("other", listOf("https://unrelated.com")),
            cred("target", listOf("https://example.com/login")),
        )
        val result = BitwardenLikeAutofillMatcher.match(creds, null, "example.com")
        assertThat(result.map { it.itemId }).containsExactly("target").inOrder()
    }

    @Test
    fun `subdomain matches by base domain`() {
        val creds = listOf(cred("a", listOf("https://example.com")))
        val result = BitwardenLikeAutofillMatcher.match(creds, null, "sub.example.com")
        assertThat(result).hasSize(1)
    }

    @Test
    fun `package match via androidapp uri`() {
        val creds = listOf(cred("a", listOf("androidapp://com.example.app")))
        val result = BitwardenLikeAutofillMatcher.match(creds, "com.example.app", null)
        assertThat(result.map { it.itemId }).containsExactly("a")
    }

    @Test
    fun `no match returns empty`() {
        val creds = listOf(cred("a", listOf("https://example.com")))
        assertThat(BitwardenLikeAutofillMatcher.match(creds, null, "unrelated.com")).isEmpty()
    }

    @Test
    fun `favorite ranks above non-favorite on equal score`() {
        val creds = listOf(
            cred("plain", listOf("https://example.com")),
            cred("fav", listOf("https://example.com"), favorite = true),
        )
        val result = BitwardenLikeAutofillMatcher.match(creds, null, "example.com")
        assertThat(result.first().itemId).isEqualTo("fav")
    }

    @Test
    fun `equivalent domain matches`() {
        val creds = listOf(cred("a", listOf("https://youtube.com")))
        val result = BitwardenLikeAutofillMatcher.match(creds, null, "google.com")
        assertThat(result).hasSize(1)
    }

    @Test
    fun `exactDomainOnly excludes base domain match`() {
        val creds = listOf(cred("a", listOf("https://example.com")))
        val result = BitwardenLikeAutofillMatcher.match(
            creds,
            null,
            "sub.example.com",
            MatchConfig(exactDomainOnly = true),
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `exact domain outranks base domain`() {
        val creds = listOf(
            cred("sub", listOf("https://sub.target.com")),
            cred("root", listOf("https://target.com")),
        )
        val result = BitwardenLikeAutofillMatcher.match(creds, null, "target.com")
        assertThat(result.map { it.itemId }).containsExactly("root", "sub").inOrder()
    }

    @Test
    fun `package match disabled by config`() {
        val creds = listOf(cred("a", listOf("androidapp://com.example.app")))
        val result = BitwardenLikeAutofillMatcher.match(
            creds,
            "com.example.app",
            null,
            MatchConfig(allowPackageMatch = false),
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `host match requires exact host and excludes subdomain`() {
        val creds = listOf(credWith("a", "https://example.com", UriMatch.Host))
        assertThat(BitwardenLikeAutofillMatcher.match(creds, null, "example.com")).hasSize(1)
        // 子域不命中 Host 规则（区别于默认 Domain 基域匹配）。
        assertThat(BitwardenLikeAutofillMatcher.match(creds, null, "sub.example.com")).isEmpty()
    }

    @Test
    fun `exact match requires full uri equality`() {
        val creds = listOf(credWith("a", "https://accounts.example.com/login", UriMatch.Exact))
        // 页面主机 accounts.example.com 与保存的完整 URI 不等 → 不命中。
        assertThat(BitwardenLikeAutofillMatcher.match(creds, null, "accounts.example.com")).isEmpty()
        // 完整 URI 相等时命中。
        val exact = listOf(credWith("b", "https://accounts.example.com", UriMatch.Exact))
        assertThat(BitwardenLikeAutofillMatcher.match(exact, null, "accounts.example.com")).hasSize(1)
    }

    @Test
    fun `never match excludes the uri even when domain matches`() {
        val creds = listOf(credWith("a", "https://example.com", UriMatch.Never))
        assertThat(BitwardenLikeAutofillMatcher.match(creds, null, "example.com")).isEmpty()
    }
}
