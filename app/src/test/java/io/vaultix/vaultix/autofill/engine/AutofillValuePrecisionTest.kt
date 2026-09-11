/*
 * Vaultix — app:autofill · engine（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 填充值精度规则。
 *
 * 对齐 Bitwarden `fillLoginPartition`：填 Email 字段前先判
 * `autofillCipher.username.trim().isValidEmail()`，不合法就**跳过该字段**而不是硬填。
 * 否则「用手机号 / 昵称当账号」的条目会把手机号写进邮箱框。
 */
class AutofillValuePrecisionTest {

    @Test
    fun `real emails are accepted`() {
        assertThat(AutofillDatasets.isEmailLike("jane@example.com")).isTrue()
        assertThat(AutofillDatasets.isEmailLike(" a.b+tag@sub.example.co.uk ")).isTrue()
    }

    @Test
    fun `non-email usernames are rejected`() {
        // 手机号 / 昵称 / 工号这类账号不该被填进邮箱框
        assertThat(AutofillDatasets.isEmailLike("13800138000")).isFalse()
        assertThat(AutofillDatasets.isEmailLike("valkjin")).isFalse()
        assertThat(AutofillDatasets.isEmailLike("")).isFalse()
    }

    @Test
    fun `malformed values are rejected`() {
        assertThat(AutofillDatasets.isEmailLike("@example.com")).isFalse()
        assertThat(AutofillDatasets.isEmailLike("jane@")).isFalse()
        assertThat(AutofillDatasets.isEmailLike("a b@example.com")).isFalse()
        assertThat(AutofillDatasets.isEmailLike("a@b@example.com")).isFalse()
    }
}
