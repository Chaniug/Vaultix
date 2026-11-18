/*
 * Vaultix — app:passkey (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.passkey

import com.google.common.truth.Truth.assertThat
import io.vaultix.common.WebAuthn
import io.vaultix.model.VaultFido2Credential
import org.json.JSONObject
import org.junit.Test

/**
 * allowCredentials 的两条口径（2026-11-18 对齐 Bitwarden PM-44191）。
 *
 * 1. descriptor 按 **id + type** 匹配，非 `public-key` 必须忽略；
 * 2. 同一 ID 的 UUID 文本 / `b64.` 前缀 / 裸 base64url 三种形态必须等价 ——
 *    否则「库里存 UUID、RP 发 base64url」时允许列表形同虚设。
 */
class PasskeyResolutionTest {

    private fun match(credentialId: String, rpId: String = "example.com") = PasskeyMatch(
        vaultId = "v",
        itemId = "i",
        credential = VaultFido2Credential(credentialId = credentialId, rpId = rpId),
        loginTitle = "Example",
    )

    @Test
    fun `allowCredentials ignores non public-key descriptors`() {
        val json = JSONObject(
            """{"allowCredentials":[
                {"id":"AAA","type":"other"},
                {"id":"BBB","type":"public-key"},
                {"id":"CCC"}
            ]}""",
        )
        assertThat(parseAllowedCredentialIds(json)).containsExactly("BBB", "CCC").inOrder()
    }

    @Test
    fun `applyAllowedFilter matches across credential id encodings`() {
        // 库里存 UUID 文本（Bitwarden 同步形态），RP 下发同一 ID 的 base64url。
        val uuidText = "5698f18a-41e0-e865-0104-c989aee7dc18"
        val asB64 = WebAuthn.rawIdFromStored(uuidText)
        val result = applyAllowedFilter(listOf(match(uuidText)), listOf(asB64)) {}
        assertThat(result).hasSize(1)
    }

    @Test
    fun `applyAllowedFilter keeps fallback when nothing matches`() {
        // 严格过滤为空 ⇒ 必须回退到 rpId-only（allowCredentials 是提示不是授权门）。
        val result = applyAllowedFilter(listOf(match("AAA")), listOf("ZZZ")) {}
        assertThat(result).hasSize(1)
    }
}
