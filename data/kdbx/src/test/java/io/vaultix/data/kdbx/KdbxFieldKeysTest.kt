/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * 字段键区判定的单元测试（W1 · JSON⇄KDBX 无损互转）。
 * 纯 JVM（kotpass 不参与），只验证 `KdbxFieldKeys` 的保留区判定。
 */
package io.vaultix.data.kdbx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KdbxFieldKeysTest {

    @Test
    fun `standard five keys are reserved`() {
        for (key in listOf("Title", "UserName", "Password", "Url", "Notes")) {
            assertThat(KdbxFieldKeys.isReserved(key)).isTrue()
        }
    }

    @Test
    fun `standard keys are reserved case-insensitively`() {
        // kotpass 键名大小写敏感，但 R1 判区必须自己折叠大小写，
        // 否则 "title" / "URL" 会与 "Title" / "Url" 并存两条、甚至覆盖标准字段。
        for (key in listOf(
            "title", "TITLE", "Title",
            "username", "USERNAME", "UserName",
            "password", "PASSWORD", "Password",
            "url", "URL", "uRl", "Url",
            "notes", "NOTES", "Notes",
        )) {
            assertThat(KdbxFieldKeys.isReserved(key)).isTrue()
        }
    }

    @Test
    fun `otp field names are reserved`() {
        assertThat(KdbxFieldKeys.isReserved("otp")).isTrue()
        assertThat(KdbxFieldKeys.isReserved("TOTP Seed")).isTrue()
        assertThat(KdbxFieldKeys.isReserved("TimeOtp-Secret-Hex")).isTrue()
        assertThat(KdbxFieldKeys.isReserved("TOTP Settings")).isTrue()
    }

    @Test
    fun `passkey field names are reserved`() {
        assertThat(KdbxFieldKeys.isReserved("KPEX_PASSKEY_CREDENTIAL_ID")).isTrue()
        assertThat(KdbxFieldKeys.isReserved("KPEX_PASSKEY_PRIVATE_KEY_PEM")).isTrue()
        assertThat(KdbxFieldKeys.isReserved("Passkey")).isTrue()
    }

    @Test
    fun `ordinary custom field names are not reserved`() {
        for (key in listOf(
            "MyNote",
            "API Key",
            "Recovery Code",
            "2FA Backup",
            "VPX_BW_ID", // 工具前缀属于本应用生成，不是保留区
        )) {
            assertThat(KdbxFieldKeys.isReserved(key)).isFalse()
        }
    }

    @Test
    fun `blank name is not reserved`() {
        assertThat(KdbxFieldKeys.isReserved("")).isFalse()
    }
}
