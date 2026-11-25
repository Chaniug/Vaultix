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

/**
 * #307 UNKNOWN 字段诊断串（[UnknownFieldDiagnostics]）。
 *
 * 核心锁两件事：① 只含控件元数据、不含用户输入值（安全红线）；② 信号齐全时能从串里
 * 一眼看出「className / inputType / html 属性」——这正是定位夸克内核的关键证据。
 */
class UnknownFieldDiagnosticsTest {

    private fun probe() = UnknownFieldDiagnostics.Probe(
        className = "com.uc.webview.export.WebView",
        inputType = 129, // TYPE_CLASS_TEXT(1) or TYPE_TEXT_VARIATION_PASSWORD(128)
        autofillType = 1,
        htmlTag = "input",
        htmlAttributes = listOf("type" to "password", "id" to "passwd"),
        idEntry = "quark_input",
        viewHint = "密码",
        contentDescription = "请输入密码",
    )

    @Test
    fun `信号齐全时串里带 className 与十六进制 inputType`() {
        val line = UnknownFieldDiagnostics.format(probe())
        assertThat(line).contains("cls=com.uc.webview.export.WebView")
        assertThat(line).contains("inputType=0x81(129)")
        assertThat(line).contains("a11yType=1")
        assertThat(line).contains("tag=input")
    }

    @Test
    fun `html 属性逐项列出`() {
        val line = UnknownFieldDiagnostics.format(probe())
        assertThat(line).contains("attrs=[type=password,id=passwd]")
    }

    @Test
    fun `无属性输出横线占位`() {
        val line = UnknownFieldDiagnostics.format(probe().copy(htmlAttributes = emptyList()))
        assertThat(line).contains("attrs=[-]")
    }

    @Test
    fun `null 元数据降级为横线不抛异常`() {
        val line = UnknownFieldDiagnostics.format(
            UnknownFieldDiagnostics.Probe(
                className = null, inputType = 0, autofillType = 0,
                htmlTag = null, htmlAttributes = null,
                idEntry = null, viewHint = null, contentDescription = null,
            ),
        )
        assertThat(line).contains("cls=-")
        assertThat(line).contains("tag=-")
        assertThat(line).contains("attrs=[-]")
        assertThat(line).contains("id=-")
    }

    @Test
    fun `超长属性被截断且换行被压平`() {
        val long = "x".repeat(200) + "\n密码"
        val line = UnknownFieldDiagnostics.format(
            probe().copy(htmlAttributes = listOf("autocomplete" to long)),
        )
        assertThat(line).doesNotContain("\n")
        assertThat(line).contains("…")
        assertThat(line.length).isLessThan(400)
    }

    @Test
    fun `Probe 没有 text 字段_编译期杜绝输入值入日志`() {
        // 反射断言：Probe 的构造参数里不得有任何名为 text / value / password 的字段。
        // 这是安全红线的结构性证据——即使以后有人想加，也会在这里被拦。
        val names = UnknownFieldDiagnostics.Probe::class.java.declaredFields.map { it.name }
        assertThat(names).doesNotContain("text")
        assertThat(names).doesNotContain("value")
        assertThat(names).doesNotContain("password")
    }
}
