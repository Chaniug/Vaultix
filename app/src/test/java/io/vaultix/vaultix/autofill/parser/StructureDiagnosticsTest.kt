/*
 * Vaultix — app:autofill · parser（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #307 结构级诊断（[StructureDiagnostics]）：fields=0 时看清树里到底有什么。 */
class StructureDiagnosticsTest {

    private fun node() = StructureDiagnostics.NodeProbe(
        className = "com.uc.webview.export.b",
        autofillType = 1,
        inputType = 129,
        htmlTag = "input",
        idEntry = "user_name",
        hints = listOf("username", "textField"),
        isFocused = true,
        childCount = 3,
    )

    @Test
    fun `摘要串带全树计数`() {
        val line = StructureDiagnostics.formatSummary(
            StructureDiagnostics.Summary(
                windows = 2, nodes = 137, withAutofillId = 45,
                textFillable = 0, focused = 1,
            ),
        )
        assertThat(line).isEqualTo("structSummary windows=2 nodes=137 withId=45 textFillable=0 focused=1")
    }

    @Test
    fun `节点串带 className_十六进制 inputType_与 hints`() {
        val line = StructureDiagnostics.formatNode(node())
        assertThat(line).contains("cls=com.uc.webview.export.b")
        assertThat(line).contains("a11yType=1")
        assertThat(line).contains("inputType=0x81(129)")
        assertThat(line).contains("tag=input")
        assertThat(line).contains("id=user_name")
        assertThat(line).contains("hints=[username,textField]")
        assertThat(line).contains("focus=true")
        assertThat(line).contains("children=3")
    }

    @Test
    fun `null 元数据降级为横线不抛异常`() {
        val line = StructureDiagnostics.formatNode(
            StructureDiagnostics.NodeProbe(
                className = null, autofillType = 0, inputType = 0,
                htmlTag = null, idEntry = null, hints = null,
                isFocused = false, childCount = 0,
            ),
        )
        assertThat(line).contains("cls=-")
        assertThat(line).contains("tag=-")
        assertThat(line).contains("id=-")
        assertThat(line).contains("hints=[-]")
    }

    @Test
    fun `采样判据_有 autofillId 或 focused 必收`() {
        assertThat(StructureDiagnostics.isWorthSampling(true, false, null)).isTrue()
        assertThat(StructureDiagnostics.isWorthSampling(false, true, null)).isTrue()
    }

    @Test
    fun `采样判据_可疑类名收_无关类名不收`() {
        assertThat(StructureDiagnostics.isWorthSampling(false, false, "com.uc.webview.export.WebView")).isTrue()
        assertThat(StructureDiagnostics.isWorthSampling(false, false, "android.widget.TextView")).isTrue()
        assertThat(StructureDiagnostics.isWorthSampling(false, false, "android.widget.ImageView")).isFalse()
        assertThat(StructureDiagnostics.isWorthSampling(false, false, null)).isFalse()
    }

    @Test
    fun `超长元数据被截断且换行被压平`() {
        val long = "x".repeat(200) + "\n密码"
        val line = StructureDiagnostics.formatNode(node().copy(className = long))
        assertThat(line).doesNotContain("\n")
        assertThat(line).contains("…")
        assertThat(line.length).isLessThan(400)
    }

    @Test
    fun `样本上限是 16_防刷屏`() {
        assertThat(StructureDiagnostics.MAX_SAMPLES).isEqualTo(16)
    }

    @Test
    fun `NodeProbe 没有 text 字段_编译期杜绝输入值入日志`() {
        val names = StructureDiagnostics.NodeProbe::class.java.declaredFields.map { it.name }
        assertThat(names).doesNotContain("text")
        assertThat(names).doesNotContain("value")
        assertThat(names).doesNotContain("password")
    }
}
