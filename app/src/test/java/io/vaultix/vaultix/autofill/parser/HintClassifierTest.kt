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

    @Test
    fun `standard hint username maps to USERNAME`() {
        assertThat(HintClassifier.classify(listOf("username"), 0, null))
            .isEqualTo(FieldHint.USERNAME)
    }

    @Test
    fun `standard hint password maps to PASSWORD`() {
        assertThat(HintClassifier.classify(listOf("password"), 0, null))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `standard hint emailAddress maps to EMAIL_ADDRESS`() {
        assertThat(HintClassifier.classify(listOf("emailAddress"), 0, null))
            .isEqualTo(FieldHint.EMAIL_ADDRESS)
    }

    @Test
    fun `standard hint newPassword maps to NEW_PASSWORD`() {
        assertThat(HintClassifier.classify(listOf("newPassword"), 0, null))
            .isEqualTo(FieldHint.NEW_PASSWORD)
    }

    @Test
    fun `standard hint phone maps to PHONE_NUMBER`() {
        assertThat(HintClassifier.classify(listOf("phone"), 0, null))
            .isEqualTo(FieldHint.PHONE_NUMBER)
    }

    @Test
    fun `standard hint creditCardNumber maps to CARD_NUMBER`() {
        assertThat(HintClassifier.classify(listOf("creditCardNumber"), 0, null))
            .isEqualTo(FieldHint.CARD_NUMBER)
    }

    @Test
    fun `inputType password variation maps to PASSWORD`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertThat(HintClassifier.classify(null, inputType, null))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `inputType web password variation maps to PASSWORD`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertThat(HintClassifier.classify(null, inputType, null))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `inputType email variation maps to EMAIL_ADDRESS`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertThat(HintClassifier.classify(null, inputType, null))
            .isEqualTo(FieldHint.EMAIL_ADDRESS)
    }

    @Test
    fun `inputType phone class maps to PHONE_NUMBER`() {
        assertThat(HintClassifier.classify(null, InputType.TYPE_CLASS_PHONE, null))
            .isEqualTo(FieldHint.PHONE_NUMBER)
    }

    @Test
    fun `inputType person name variation maps to NAME`() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        assertThat(HintClassifier.classify(null, inputType, null))
            .isEqualTo(FieldHint.NAME)
    }

    @Test
    fun `text heuristic chinese password maps to PASSWORD`() {
        assertThat(HintClassifier.classify(null, 0, "密码"))
            .isEqualTo(FieldHint.PASSWORD)
    }

    @Test
    fun `text heuristic chinese username maps to USERNAME`() {
        assertThat(HintClassifier.classify(null, 0, "用户名"))
            .isEqualTo(FieldHint.USERNAME)
    }

    @Test
    fun `text heuristic chinese email maps to EMAIL_ADDRESS`() {
        assertThat(HintClassifier.classify(null, 0, "邮箱"))
            .isEqualTo(FieldHint.EMAIL_ADDRESS)
    }

    @Test
    fun `no signal maps to UNKNOWN`() {
        assertThat(HintClassifier.classify(null, 0, null))
            .isEqualTo(FieldHint.UNKNOWN)
    }

    @Test
    fun `hints take priority over text heuristic`() {
        // 即便文本像密码，标准 username hint 仍优先。
        assertThat(HintClassifier.classify(listOf("username"), 0, "密码"))
            .isEqualTo(FieldHint.USERNAME)
    }

    @Test
    fun `inputType takes priority over text heuristic`() {
        // 即便文本像用户名，密码 inputType 仍优先。
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertThat(HintClassifier.classify(null, inputType, "用户名"))
            .isEqualTo(FieldHint.PASSWORD)
    }
}
