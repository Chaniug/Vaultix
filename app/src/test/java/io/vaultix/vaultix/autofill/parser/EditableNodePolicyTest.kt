/*
 * Vaultix — app:autofill · parser（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 节点准入判定（[isEditableNode]）。
 *
 * 回归锁：**浏览器 WebView 的展示节点不得进入字段表**。
 * 真机实证（`build/adb-capture/device-log.txt`，Edge + github.com）：一个页面被解析出
 * 221 个「字段」，`<label>Password</label>` 这类节点被启发式判成 PASSWORD / USERNAME，
 * 占掉真账号框的语义位 → 账号框既不出候选也填不进去。
 */
class EditableNodePolicyTest {

    @Test
    fun `web input elements are accepted`() {
        assertThat(isEditableNode(htmlTag = "input", className = null, hints = null)).isTrue()
        // Chromium 的 tag 大小写不保证
        assertThat(isEditableNode(htmlTag = "INPUT", className = null, hints = null)).isTrue()
    }

    @Test
    fun `web display nodes are rejected`() {
        // label / div / a / button / span：有 htmlInfo 就说明是 HTML 元素，tag 不是 input 即非可填。
        listOf("label", "div", "a", "button", "span", "form").forEach { tag ->
            assertThat(isEditableNode(htmlTag = tag, className = null, hints = null)).isFalse()
        }
    }

    @Test
    fun `html tag wins over className`() {
        // 浏览器节点即便 className 是 EditText，只要 htmlInfo.tag 不是 input 也不算可填控件。
        assertThat(
            isEditableNode(
                htmlTag = "div",
                className = "android.widget.EditText",
                hints = null,
            ),
        ).isFalse()
    }

    @Test
    fun `native edit text families are accepted`() {
        listOf(
            "android.widget.EditText",
            "androidx.appcompat.widget.AppCompatEditText",
            "com.google.android.material.textfield.TextInputEditText",
            "android.widget.AutoCompleteTextView",
            "androidx.appcompat.widget.SearchView",
        ).forEach { className ->
            assertThat(isEditableNode(htmlTag = null, className = className, hints = null)).isTrue()
        }
    }

    @Test
    fun `native non-editable widgets are rejected`() {
        listOf("android.widget.TextView", "android.widget.Button", "android.widget.FrameLayout")
            .forEach { className ->
                assertThat(isEditableNode(htmlTag = null, className = className, hints = null)).isFalse()
            }
    }

    @Test
    fun `unknown nodes are accepted to avoid killing native apps`() {
        // 既没有 htmlInfo 也没有 className（原生 App 的罕见形态）→ 判不出来时必须放行，
        // 否则会把本来能填的原生输入框一起误杀。
        assertThat(isEditableNode(htmlTag = null, className = null, hints = null)).isTrue()
    }

    @Test
    fun `standard autofill hints always qualify a node`() {
        assertThat(
            isEditableNode(htmlTag = "div", className = null, hints = listOf("username")),
        ).isTrue()
        // 不认识的 hint 不构成准入理由
        assertThat(
            isEditableNode(htmlTag = "div", className = null, hints = listOf("noSuchHint")),
        ).isFalse()
    }
}
