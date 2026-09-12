/*
 * Vaultix — app:autofill · match（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.match

import com.google.common.truth.Truth.assertThat
import io.vaultix.vaultix.autofill.model.FieldHint
import org.junit.Test

/**
 * 填充目标判定（**误弹治理**）。
 *
 * 背景：此前 `onFillRequest` 只判断「页面有没有可见字段」，任何页面都能凑出 id 集合，
 * 于是搜索框 / 昵称框 / 订阅框也会把填充 UI 勾出来。
 *
 * ⚠️ **2026-09-12 对齐 Bitwarden 后，判定收敛为一条**：
 * ① 非凭据语义（SEARCH / UNKNOWN）→ 永不算数；② 其余凭据语义 + 可见 → 算数。
 *
 * 曾经的「弱信号（LOW）不算数」这一层**已撤销** —— 上游 Bitwarden 的判定是
 * 「分类结果即证据」（`Unused` 直接剔除），并不存在第二层强度闸。
 * 当初靠它拦的「搜索框被误判成账号框」，如今改由 `HintClassifier` 的**否定词**在
 * **分类阶段**拦掉（对齐上游 `IGNORED_RAW_HINTS`）——在更靠前、更准的位置解决。
 * 该前提由 `HintClassifierTest.ignored terms veto the text heuristic` 锁住。
 */
class AutofillFillTargetPolicyTest {

    private fun target(hint: FieldHint, isVisible: Boolean = true) =
        AutofillFillTargetPolicy.isFillTarget(hint = hint, isVisible = isVisible)

    @Test
    fun searchAndUnknownFields_neverTriggerFillUi() {
        // 搜索框：即便 Chromium 明确下发 autofillHints=search，也绝不能弹
        assertThat(target(FieldHint.SEARCH)).isFalse()
        // 完全没识别出来的普通输入框同理（搜索框经否定词否决后正是落在这里）
        assertThat(target(FieldHint.UNKNOWN)).isFalse()
    }

    @Test
    fun credentialFields_areFillableRegardlessOfSignalStrength() {
        // 对齐上游「分类结果即证据」：不再按信号强度分级（此前 LOW 一律不算数）
        assertThat(target(FieldHint.USERNAME)).isTrue()
        assertThat(target(FieldHint.EMAIL_ADDRESS)).isTrue()
        assertThat(target(FieldHint.PASSWORD)).isTrue()
        assertThat(target(FieldHint.NEW_PASSWORD)).isTrue()
        assertThat(target(FieldHint.OTP)).isTrue()
        assertThat(target(FieldHint.CARD_NUMBER)).isTrue()
        assertThat(target(FieldHint.CARD_CVC)).isTrue()
        assertThat(target(FieldHint.NAME)).isTrue()
        assertThat(target(FieldHint.PHONE_NUMBER)).isTrue()
    }

    @Test
    fun invisibleFields_neverTriggerFillUi() {
        // 即使语义强（密码框），不可见字段也不参与填充
        assertThat(target(FieldHint.PASSWORD, isVisible = false)).isFalse()
        assertThat(target(FieldHint.USERNAME, isVisible = false)).isFalse()
    }
}
