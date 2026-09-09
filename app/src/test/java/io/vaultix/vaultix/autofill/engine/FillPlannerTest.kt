/*
 * Vaultix — app:autofill · engine 单测
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.engine

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.autofill.model.AutofillCredential
import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.model.FillCategory
import io.vaultix.vaultix.autofill.model.FillContext
import org.junit.Test

class FillPlannerTest {

    private fun loginCred(
        id: String,
        username: String = "user$id",
        password: String = "pass$id",
        totp: String = "",
    ): AutofillCredential = AutofillCredential(
        vaultId = "v",
        itemId = id,
        name = "Login $id",
        username = username,
        password = password,
        totp = totp,
    )

    private fun loginContext(hasUser: Boolean = true, hasPass: Boolean = true, extra: Set<FieldHint> = emptySet()) =
        FillContext(
            packageName = "com.example",
            webDomain = "example.com",
            webUri = "https://example.com",
            hasUsernameField = hasUser,
            hasPasswordField = hasPass,
            presentHints = setOf(FieldHint.USERNAME, FieldHint.PASSWORD) + extra,
        )

    private val noTotp: (String) -> String? = { null }

    @Test
    fun `login context fills username and password`() {
        val plan = FillPlanner.plan(loginContext(), listOf(loginCred("1")), emptyList(), emptyList(), noTotp)
        assertThat(plan.suggestions).hasSize(1)
        val s = plan.suggestions[0]
        assertThat(s.category).isEqualTo(FillCategory.LOGIN)
        assertThat(s.fields[FieldHint.USERNAME]).isEqualTo("user1")
        assertThat(s.fields[FieldHint.PASSWORD]).isEqualTo("pass1")
    }

    @Test
    fun `missing username field omits username`() {
        val plan = FillPlanner.plan(
            loginContext(hasUser = false),
            listOf(loginCred("1")),
            emptyList(),
            emptyList(),
            noTotp,
        )
        val s = plan.suggestions[0]
        assertThat(s.fields).doesNotContainKey(FieldHint.USERNAME)
        assertThat(s.fields[FieldHint.PASSWORD]).isEqualTo("pass1")
    }

    @Test
    fun `otp field fills computed code`() {
        val plan = FillPlanner.plan(
            loginContext(extra = setOf(FieldHint.OTP)),
            listOf(loginCred("1", totp = "JBSWY3DPEHPK3PXP")),
            emptyList(),
            emptyList(),
        ) { "123456" }
        val s = plan.suggestions[0]
        assertThat(s.fields[FieldHint.OTP]).isEqualTo("123456")
    }

    @Test
    fun `otp field without provider yields no otp`() {
        val plan = FillPlanner.plan(
            loginContext(extra = setOf(FieldHint.OTP)),
            listOf(loginCred("1", totp = "JBSWY3DPEHPK3PXP")),
            emptyList(),
            emptyList(),
            noTotp,
        )
        val s = plan.suggestions[0]
        assertThat(s.fields).doesNotContainKey(FieldHint.OTP)
    }

    @Test
    fun `card context fills number cvc and expiry`() {
        val card = VaultItem(
            id = "c1",
            title = "My Card",
            type = VaultItemType.Card,
            card = VaultCard(
                cardholderName = "Jane Doe",
                brand = "Visa",
                number = "4111111111111111",
                expMonth = "09",
                expYear = "2029",
                code = "123",
            ),
        )
        val ctx = FillContext(
            packageName = null,
            webDomain = null,
            webUri = null,
            hasUsernameField = false,
            hasPasswordField = false,
            presentHints = setOf(
                FieldHint.CARD_NUMBER,
                FieldHint.CARD_CVC,
                FieldHint.CARD_EXPIRY,
                FieldHint.NAME,
            ),
        )
        val plan = FillPlanner.plan(ctx, emptyList(), listOf(card), emptyList(), noTotp)
        assertThat(plan.suggestions).hasSize(1)
        val s = plan.suggestions[0]
        assertThat(s.category).isEqualTo(FillCategory.CARD)
        assertThat(s.fields[FieldHint.CARD_NUMBER]).isEqualTo("4111111111111111")
        assertThat(s.fields[FieldHint.CARD_CVC]).isEqualTo("123")
        assertThat(s.fields[FieldHint.CARD_EXPIRY]).isEqualTo("09/29")
        assertThat(s.fields[FieldHint.NAME]).isEqualTo("Jane Doe")
    }

    @Test
    fun `identity context fills name email phone postal`() {
        val identity = VaultItem(
            id = "i1",
            title = "Jane",
            type = VaultItemType.Identity,
            identity = VaultIdentity(
                firstName = "Jane",
                lastName = "Doe",
                email = "jane@example.com",
                phone = "5551234",
                postalCode = "12345",
            ),
        )
        val ctx = FillContext(
            packageName = null,
            webDomain = null,
            webUri = null,
            hasUsernameField = false,
            hasPasswordField = false,
            presentHints = setOf(
                FieldHint.NAME,
                FieldHint.EMAIL_ADDRESS,
                FieldHint.PHONE_NUMBER,
                FieldHint.POSTAL_CODE,
            ),
        )
        val plan = FillPlanner.plan(ctx, emptyList(), emptyList(), listOf(identity), noTotp)
        assertThat(plan.suggestions).hasSize(1)
        val s = plan.suggestions[0]
        assertThat(s.category).isEqualTo(FillCategory.IDENTITY)
        assertThat(s.fields[FieldHint.NAME]).isEqualTo("Jane Doe")
        assertThat(s.fields[FieldHint.EMAIL_ADDRESS]).isEqualTo("jane@example.com")
        assertThat(s.fields[FieldHint.PHONE_NUMBER]).isEqualTo("5551234")
        assertThat(s.fields[FieldHint.POSTAL_CODE]).isEqualTo("12345")
    }

    @Test
    fun `no matching context yields empty plan`() {
        val ctx = FillContext(
            packageName = "com.x",
            webDomain = "x.com",
            webUri = "https://x.com",
            hasUsernameField = false,
            hasPasswordField = false,
            presentHints = setOf(FieldHint.SEARCH),
        )
        val plan = FillPlanner.plan(
            ctx,
            listOf(loginCred("1")),
            emptyList(),
            emptyList(),
            noTotp,
        )
        assertThat(plan.suggestions).isEmpty()
    }

    @Test
    fun `username-only page with credible username suggests login`() {
        // 多步登录第一步：用户名框带标准 hint（Chromium autocomplete=username 等）→ 出候选
        val ctx = FillContext(
            packageName = "com.x",
            webDomain = "x.com",
            webUri = "https://x.com",
            hasUsernameField = true,
            hasPasswordField = false,
            presentHints = setOf(FieldHint.USERNAME),
            hasCredibleUsernameField = true,
        )
        val plan = FillPlanner.plan(ctx, listOf(loginCred("1")), emptyList(), emptyList(), noTotp)
        assertThat(plan.suggestions).hasSize(1)
        assertThat(plan.suggestions[0].fields[FieldHint.USERNAME]).isEqualTo("user1")
        assertThat(plan.suggestions[0].fields).doesNotContainKey(FieldHint.PASSWORD)
    }

    @Test
    fun `weak username without password does not suggest login`() {
        // 搜索栏 / 孤立文本框：仅文本启发式命中 USERNAME（弱信号）且页面无密码框
        // → 不得弹密码条目（Bastion 京东搜索栏误弹同根因）
        val ctx = FillContext(
            packageName = "com.x",
            webDomain = "x.com",
            webUri = "https://x.com",
            hasUsernameField = true,
            hasPasswordField = false,
            presentHints = setOf(FieldHint.USERNAME),
            hasCredibleUsernameField = false,
        )
        val plan = FillPlanner.plan(ctx, listOf(loginCred("1")), emptyList(), emptyList(), noTotp)
        assertThat(plan.suggestions).isEmpty()
    }

    @Test
    fun `weak username with password field still suggests login`() {
        // 密码框在场是登录强信号：此时用户名框信号弱也照常填充（注册/登录页常见）
        val ctx = FillContext(
            packageName = "com.x",
            webDomain = "x.com",
            webUri = "https://x.com",
            hasUsernameField = true,
            hasPasswordField = true,
            presentHints = setOf(FieldHint.USERNAME, FieldHint.PASSWORD),
            hasCredibleUsernameField = false,
        )
        val plan = FillPlanner.plan(ctx, listOf(loginCred("1")), emptyList(), emptyList(), noTotp)
        assertThat(plan.suggestions).hasSize(1)
        assertThat(plan.suggestions[0].fields[FieldHint.USERNAME]).isEqualTo("user1")
        assertThat(plan.suggestions[0].fields[FieldHint.PASSWORD]).isEqualTo("pass1")
    }
}
