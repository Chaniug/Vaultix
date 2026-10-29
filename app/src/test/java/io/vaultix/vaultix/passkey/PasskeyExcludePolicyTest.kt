/*
 * Vaultix — app:passkey (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.passkey

import io.vaultix.model.VaultFido2Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PasskeyExcludePolicy]（#208）。
 *
 * 三条必须钉住的边界：
 * 1. 命中 ⇒ 必须拦（不拦就是"同一站点注册出两把"）；
 * 2. **解析不出来不得拦**（`excludeCredentials` 是增益语义，解析失败挡住注册代价更大）；
 * 3. **别的 rpId 的凭证不算命中**（作用域就是本次请求的 rpId）。
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
}
