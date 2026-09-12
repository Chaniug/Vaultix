/*
 * Vaultix — app:autofill · parser 单测
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.parser

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import io.vaultix.vaultix.autofill.model.FieldHint
import org.junit.Test

/**
 * [HintClassifier.classify] 纯函数单测：验证「hints > inputType > 文本启发式」的优先级，
 * 以及各信号到 [FieldHint] 的映射（对齐 Bastion autofill_ng 的分类语义）。
 */
class HintClassifierTest {

    /** 分类并取语义（断言 hint 用；强度断言见 [strength*] 用例）。 */
    private fun hintOf(hints: List<String>?, inputType: Int, text: String?): FieldHint =
        HintClassifier.classify(hints, inputType, text).hint

    @Test
    fun `standard hint username maps to USERNAME`() {
        assertThat(hintOf(listOf("username"), 0, null))
            .isEqualTo(FieldHint.USERNAME)
    }

    @Test
    fun `standard hint password maps to PASSWORD`() {
        assertThat(hintOf(listOf("password"), 0, null))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `standard hint emailAddress maps to EMAIL_ADDRESS`() {
        assertThat(hintOf(listOf("emailAddress"), 0, null))
            .isEqualTo(FieldHint.EMAIL_ADDRESS)
    }

    @Test
    fun `standard hint newPassword maps to NEW_PASSWORD`() {
        assertThat(hintOf(listOf("newPassword"), 0, null))
            .isEqualTo(FieldHint.NEW_PASSWORD)
    }

    @Test
    fun `standard hint phone maps to PHONE_NUMBER`() {
        assertThat(hintOf(listOf("phone"), 0, null))
            .isEqualTo(FieldHint.PHONE_NUMBER)
    }

    @Test
    fun `standard hint creditCardNumber maps to CARD_NUMBER`() {
        assertThat(hintOf(listOf("creditCardNumber"), 0, null))
            .isEqualTo(FieldHint.CARD_NUMBER)
    }

    @Test
    fun `inputType password variation maps to PASSWORD`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertThat(hintOf(null, inputType, null))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `inputType web password variation maps to PASSWORD`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertThat(hintOf(null, inputType, null))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `inputType email variation maps to EMAIL_ADDRESS`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertThat(hintOf(null, inputType, null))
            .isEqualTo(FieldHint.EMAIL_ADDRESS)
    }

    @Test
    fun `inputType phone class maps to PHONE_NUMBER`() {
        assertThat(hintOf(null, InputType.TYPE_CLASS_PHONE, null))
            .isEqualTo(FieldHint.PHONE_NUMBER)
    }

    @Test
    fun `inputType person name variation maps to NAME`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        assertThat(hintOf(null, inputType, null))
            .isEqualTo(FieldHint.NAME)
    }

    @Test
    fun `text heuristic chinese password maps to PASSWORD`() {
        assertThat(hintOf(null, 0, "密码"))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `text heuristic chinese username maps to USERNAME`() {
        assertThat(hintOf(null, 0, "用户名"))
            .isEqualTo(FieldHint.USERNAME)
    }

    @Test
    fun `text heuristic chinese email maps to EMAIL_ADDRESS`() {
        assertThat(hintOf(null, 0, "邮箱"))
            .isEqualTo(FieldHint.EMAIL_ADDRESS)
    }

    @Test
    fun `no signal maps to UNKNOWN`() {
        assertThat(hintOf(null, 0, null))
            .isEqualTo(FieldHint.UNKNOWN)
    }

    @Test
    fun `hints take priority over text heuristic`() {
        // 即便文本像密码，标准 username hint 仍优先。
        assertThat(hintOf(listOf("username"), 0, "密码"))
            .isEqualTo(FieldHint.USERNAME)
    }

    @Test
    fun `inputType takes priority over text heuristic`() {
        // 即便文本像用户名，密码 inputType 仍优先。
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertThat(hintOf(null, inputType, "用户名"))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `chromium web hints map to username and password`() {
        // Chromium 内核浏览器（Chrome / Edge / Brave）在 WebView 表单里下发 web* hint
        assertThat(hintOf(listOf("webUsername"), 0, null))
            .isEqualTo(FieldHint.USERNAME)
        assertThat(hintOf(listOf("webPassword"), 0, null))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `any recognized hint in a multi hint node wins`() {
        // 一个节点可能同时带 web* 与未知 hint：不能只看第一个
        assertThat(hintOf(listOf("unknownHint", "webPassword"), 0, null))
            .isEqualTo(FieldHint.PASSWORD)
    }

    // ---- 信号强度（决定「无密码框时是否可独立触发密码候选」）----

    @Test
    fun `standard hint carries HIGH strength`() {
        val c = HintClassifier.classify(listOf("username"), 0, null)
        assertThat(c.hint).isEqualTo(FieldHint.USERNAME)
        assertThat(c.strength).isEqualTo(HintClassifier.SignalStrength.HIGH)
    }

    @Test
    fun `inputType variation carries MEDIUM strength`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val c = HintClassifier.classify(null, inputType, null)
        assertThat(c.hint).isEqualTo(FieldHint.PASSWORD)
        assertThat(c.strength).isEqualTo(HintClassifier.SignalStrength.MEDIUM)
    }

    @Test
    fun `text heuristic carries LOW strength`() {
        // 孤立文本框 / 搜索栏只靠文本命中 → 弱信号，不得单独触发密码候选
        val c = HintClassifier.classify(null, 0, "用户名")
        assertThat(c.hint).isEqualTo(FieldHint.USERNAME)
        assertThat(c.strength).isEqualTo(HintClassifier.SignalStrength.LOW)
    }

    @Test
    fun `no signal maps to UNKNOWN with LOW strength`() {
        val c = HintClassifier.classify(null, 0, null)
        assertThat(c.hint).isEqualTo(FieldHint.UNKNOWN)
        assertThat(c.strength).isEqualTo(HintClassifier.SignalStrength.LOW)
    }

    // ---- 对齐 Bitwarden 的「不误弹 / 精准」三处（2026-09-12）----

    @Test
    fun `ignored terms veto the text heuristic`() {
        // 对齐上游 IGNORED_RAW_HINTS：搜索 / 查找 / 收件人 / 编辑 一律不认
        assertThat(hintOf(null, 0, "login-search")).isEqualTo(FieldHint.UNKNOWN)
        assertThat(hintOf(null, 0, "search username")).isEqualTo(FieldHint.UNKNOWN)
        assertThat(hintOf(null, 0, "搜索账号")).isEqualTo(FieldHint.UNKNOWN)
    }

    @Test
    fun `english login keyword no longer triggers username`() {
        // 上游 SUPPORTED_RAW_USERNAME_HINTS 里**没有** login（这正是搜索框误弹的根源）；
        // 中文「用户名 / 账号」保留，用于覆盖中文站点。
        assertThat(hintOf(null, 0, "login")).isEqualTo(FieldHint.UNKNOWN)
        assertThat(hintOf(null, 0, "账号")).isEqualTo(FieldHint.USERNAME)
    }

    @Test
    fun `text heuristic normalizes ascii separators`() {
        // user_name / user-name 都应命中 username（上游会把非字母去掉后再比对）；中文不受影响
        assertThat(hintOf(null, 0, "user_name")).isEqualTo(FieldHint.USERNAME)
        assertThat(hintOf(null, 0, "user-name")).isEqualTo(FieldHint.USERNAME)
        assertThat(hintOf(null, 0, "e-mail")).isEqualTo(FieldHint.EMAIL_ADDRESS)
    }
}
