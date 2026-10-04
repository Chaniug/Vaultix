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

    // ---------- 无域名降级（2026-10-04 Firefox Android）----------

    /**
     * 🔴 回归闸：拿不到域名时**必须**返回候选，而不是空列表。
     *
     * Firefox（GeckoView）实测 `webDomain` 为空串、地址栏/结构文本兜底也落空
     * ⇒ `match` 全量 0 分 ⇒ 216 个候选被过滤成 `datasets=0`
     * ⇒ 用户体感「能看到 Vaultix 的提示，但密码条目匹配不出来」。
     */
    @Test
    fun `match 在无域名时全灭 但降级仍能给出候选`() {
        val creds = listOf(
            cred("bank", listOf("https://bank.com")),
            cred("shop", listOf("https://shop.com")),
        )
        // 前提自证：无域名时 match 确实一条都匹配不出来（这就是 bug 的成因）
        assertThat(BitwardenLikeAutofillMatcher.match(creds, "org.mozilla.firefox", null)).isEmpty()
        // 但降级要给出全部候选
        assertThat(
            BitwardenLikeAutofillMatcher.orderWithoutDomain(creds).map { it.itemId },
        ).containsExactly("bank", "shop").inOrder()
    }

    @Test
    fun `降级按收藏优先再名称升序`() {
        val creds = listOf(
            cred("zebra", listOf("https://z.com"), favorite = false),
            cred("alpha", listOf("https://a.com"), favorite = false),
            cred("fav", listOf("https://f.com"), favorite = true),
        )
        assertThat(
            BitwardenLikeAutofillMatcher.orderWithoutDomain(creds).map { it.itemId },
        ).containsExactly("fav", "alpha", "zebra").inOrder()
    }

    @Test
    fun `降级与匹配共用同一排序键`() {
        // 不变量：降级顺序必须等于「同分候选在 match 里的顺序」，
        // 否则用户会看到同一个条目在 Firefox / Chrome 下位置不一致，像是数据乱了。
        val creds = listOf(
            cred("b", listOf("https://b.com")),
            cred("a", listOf("https://a.com"), favorite = true),
            cred("c", listOf("https://c.com")),
        )
        val all = BitwardenLikeAutofillMatcher.orderWithoutDomain(creds).map { it.itemId }
        // 用同一个域名匹配全部候选（构造全同分的局面）后顺序应与降级一致
        val matched = BitwardenLikeAutofillMatcher
            .match(creds.map { it.copy(uris = listOf(AutofillUri("https://same.com"))) }, null, "same.com")
            .map { it.itemId }
        assertThat(matched).containsExactly("a", "b", "c").inOrder()
        assertThat(all).containsExactlyElementsIn(matched).inOrder()
    }

    @Test
    fun `降级保留收藏条目 即使它排在最后输入`() {
        val creds = listOf(
            cred("first", listOf("https://f.com")),
            cred("second", listOf("https://s.com")),
            cred("star", listOf("https://t.com"), favorite = true),
        )
        assertThat(
            BitwardenLikeAutofillMatcher.orderWithoutDomain(creds).first().itemId,
        ).isEqualTo("star")
    }

    @Test
    fun `降级对空候选列表安全`() {
        assertThat(BitwardenLikeAutofillMatcher.orderWithoutDomain(emptyList())).isEmpty()
    }

    @Test
    fun `降级不丢弃任何候选 包括无 uri 的条目`() {
        // 无 uri 的条目在 match 里永远 0 分，但降级要保留它们 —— 用户仍可能想手动选
        val creds = listOf(cred("no-uri", emptyList()), cred("with-uri", listOf("https://x.com")))
        assertThat(BitwardenLikeAutofillMatcher.orderWithoutDomain(creds)).hasSize(2)
    }
}
