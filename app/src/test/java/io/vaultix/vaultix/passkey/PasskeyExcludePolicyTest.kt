/*
 * Vaultix — app:passkey (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.passkey

import io.vaultix.common.WebAuthn
import io.vaultix.model.VaultFido2Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PasskeyExcludePolicy]（#208）。
 *
 * 四条必须钉住的边界：
 * 1. 命中 ⇒ 必须拦（不拦就是"同一站点注册出两把"）；
 * 2. **解析不出来不得拦**（`excludeCredentials` 是增益语义，解析失败挡住注册代价更大）；
 * 3. **别的 rpId 的凭证不算命中**（作用域就是本次请求的 rpId）；
 * 4. **同一个 ID 的不同文本形态必须判为同一把**（UUID 文本 / `b64.` 前缀 / 裸 base64url）。
 */
class PasskeyExcludePolicyTest {

    private fun cred(credentialId: String, rpId: String) = VaultFido2Credential(
        credentialId = credentialId,
        rpId = rpId,
    )

    // ---- excludedIds ------------------------------------------------------

    @Test
    fun excludedIds_readsAllIdsFromTheArray() {
        val json = """{"excludeCredentials":[{"id":"AAA","type":"public-key"},{"id":"BBB"}]}"""
        assertEquals(setOf("AAA", "BBB"), PasskeyExcludePolicy.excludedIds(json))
    }

    @Test
    fun excludedIds_returnsEmptyWhenFieldMissing() {
        assertEquals(emptySet<String>(), PasskeyExcludePolicy.excludedIds("""{"rp":{"id":"x"}}"""))
        assertEquals(emptySet<String>(), PasskeyExcludePolicy.excludedIds(null))
        assertEquals(emptySet<String>(), PasskeyExcludePolicy.excludedIds(""))
    }

    @Test
    fun excludedIds_returnsEmptyForBrokenJson() {
        // ★ 解析失败**不得**变成"排除一切"或"抛异常阻断注册" —— 空集合即"无排除项"。
        assertEquals(emptySet<String>(), PasskeyExcludePolicy.excludedIds("{not json"))
    }

    @Test
    fun excludedIds_skipsBlankIds() {
        val json = """{"excludeCredentials":[{"id":"  "},{"id":"CCC"},{}]}"""
        assertEquals(setOf("CCC"), PasskeyExcludePolicy.excludedIds(json))
    }

    @Test
    fun excludedIds_ignoresNonPublicKeyDescriptors() {
        // 规范：descriptor 按 id + type 匹配，type != public-key 的必须忽略。
        val json = """{"excludeCredentials":[
            {"id":"AAA","type":"other"},
            {"id":"BBB","type":"public-key"},
            {"id":"CCC"}
        ]}"""
        assertEquals(setOf("BBB", "CCC"), PasskeyExcludePolicy.excludedIds(json))
    }

    // ---- firstConflict ----------------------------------------------------

    @Test
    fun firstConflict_hitsWhenSameRpIdHoldsAnExcludedCredential() {
        val existing = listOf(cred("AAA", "example.com"), cred("ZZZ", "example.com"))
        val hit = PasskeyExcludePolicy.firstConflict(setOf("AAA"), existing, "example.com")
        assertNotNull("已存在的凭证必须被判定为冲突", hit)
        assertEquals("AAA", hit?.credentialId)
    }

    @Test
    fun firstConflict_ignoresCredentialsOfAnotherRpId() {
        // ★ 作用域就是本次 rpId：别处的同名 id 与本次注册无关，拦了就是误伤。
        val existing = listOf(cred("AAA", "other.example"))
        assertNull(PasskeyExcludePolicy.firstConflict(setOf("AAA"), existing, "example.com"))
    }

    @Test
    fun firstConflict_returnsNullWhenNothingExcluded() {
        val existing = listOf(cred("AAA", "example.com"))
        assertNull(PasskeyExcludePolicy.firstConflict(emptySet(), existing, "example.com"))
        // rpId 为空 ⇒ 无从谈"本次请求的 rpId"，不得判定冲突。
        assertNull(PasskeyExcludePolicy.firstConflict(setOf("AAA"), existing, ""))
    }

    @Test
    fun firstConflict_matchesSameIdAcrossEncodingForms() {
        // 库里存 UUID 文本（Bitwarden 同步形态），请求里发 base64url —— 同一个 16 字节 ID。
        val uuidText = "5698f18a-41e0-e865-0104-c989aee7dc18"
        val asB64 = WebAuthn.rawIdFromStored(uuidText)
        val existing = listOf(cred(uuidText, "example.com"))
        assertNotNull(
            "同一 ID 的两种文本形态必须判为冲突",
            PasskeyExcludePolicy.firstConflict(setOf(asB64), existing, "example.com"),
        )
    }

    @Test
    fun firstConflict_matchesB64PrefixedStoredId() {
        // Bitwarden SDK 2026.10+ 把 ID 存成 `b64.<base64url>`。
        val body = WebAuthn.base64Url(ByteArray(32) { index -> index.toByte() })
        val existing = listOf(cred("b64.$body", "example.com"))
        assertNotNull(
            "b64. 前缀形态必须与裸 base64url 对齐",
            PasskeyExcludePolicy.firstConflict(setOf(body), existing, "example.com"),
        )
    }
}
