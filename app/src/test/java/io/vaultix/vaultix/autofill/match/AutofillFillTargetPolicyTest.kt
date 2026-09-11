/*
 * Vaultix — app:autofill · match（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.match

import com.google.common.truth.Truth.assertThat
import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.parser.HintClassifier
import org.junit.Test

/**
 * 填充目标判定（**误弹治理**）。
 *
 * 背景：此前 `onFillRequest` 只判断「页面有没有可见字段」，任何页面都能凑出 id 集合，
 * 于是搜索框 / 昵称框 / 订阅框也会把填充 UI 勾出来。本测试锁死三层规则：
 * ① 非凭据语义（SEARCH / UNKNOWN）永不算数；② 密码 / 验证码本身即证据；
 * ③ 账号类必须由 hint 或 inputType 判出（≥ MEDIUM），弱启发式不得单独触发。
 */
class AutofillFillTargetPolicyTest {

    private fun target(
        hint: FieldHint,
        strength: HintClassifier.SignalStrength,
        isVisible: Boolean = true,
    ) = AutofillFillTargetPolicy.isFillTarget(hint = hint, strength = strength, isVisible = isVisible)

    @Test
    fun searchAndUnknownFields_neverTriggerFillUi() {
        // 搜索框：即便 Chromium 明确下发 autofillHints=search（HIGH），也绝不能弹
        assertThat(target(FieldHint.SEARCH, HintClassifier.SignalStrength.HIGH)).isFalse()
        assertThat(target(FieldHint.SEARCH, HintClassifier.SignalStrength.LOW)).isFalse()
        // 完全没识别出来的普通输入框同理
        assertThat(target(FieldHint.UNKNOWN, HintClassifier.SignalStrength.HIGH)).isFalse()
        assertThat(target(FieldHint.UNKNOWN, HintClassifier.SignalStrength.LOW)).isFalse()
    }

    @Test
    fun passwordAndOtp_areSelfEvident_evenFromWeakSignal() {
        // 验证码框常常既无 autofillHints 也无特殊 inputType，只能靠 id/placeholder 文本识别
        // → 弱信号也必须算数，否则验证码填充整体失效
        assertThat(target(FieldHint.PASSWORD, HintClassifier.SignalStrength.LOW)).isTrue()
        assertThat(target(FieldHint.NEW_PASSWORD, HintClassifier.SignalStrength.LOW)).isTrue()
        assertThat(target(FieldHint.OTP, HintClassifier.SignalStrength.LOW)).isTrue()
    }

    @Test
    fun usernameField_requiresMediumOrStrongerSignal() {
        // 标准 autofillHints / inputType → 算数
        assertThat(target(FieldHint.USERNAME, HintClassifier.SignalStrength.HIGH)).isTrue()
        assertThat(target(FieldHint.USERNAME, HintClassifier.SignalStrength.MEDIUM)).isTrue()
        // ★ 弱信号单独命中账号 → 不算数：这正是「id 含 login 的孤立搜索框」
        //   被文本启发式误判成 USERNAME、进而把填充 UI 勾出来的场景（Bastion 京东搜索栏同根因）
        assertThat(target(FieldHint.USERNAME, HintClassifier.SignalStrength.LOW)).isFalse()
    }

    @Test
    fun emailAddressField_followsTheSameStrengthRule() {
        assertThat(target(FieldHint.EMAIL_ADDRESS, HintClassifier.SignalStrength.HIGH)).isTrue()
        assertThat(target(FieldHint.EMAIL_ADDRESS, HintClassifier.SignalStrength.LOW)).isFalse()
    }

    @Test
    fun cardAndIdentityFields_areFillableWhenSignalled() {
        assertThat(target(FieldHint.CARD_NUMBER, HintClassifier.SignalStrength.HIGH)).isTrue()
        assertThat(target(FieldHint.CARD_CVC, HintClassifier.SignalStrength.HIGH)).isTrue()
        assertThat(target(FieldHint.NAME, HintClassifier.SignalStrength.MEDIUM)).isTrue()
        assertThat(target(FieldHint.PHONE_NUMBER, HintClassifier.SignalStrength.MEDIUM)).isTrue()
        // 弱启发式一律不单独触发
        assertThat(target(FieldHint.NAME, HintClassifier.SignalStrength.LOW)).isFalse()
    }

    @Test
    fun invisibleFields_neverTriggerFillUi() {
        // 即使语义强（密码框），不可见字段也不参与填充
        assertThat(
            target(FieldHint.PASSWORD, HintClassifier.SignalStrength.HIGH, isVisible = false),
        ).isFalse()
    }
}
