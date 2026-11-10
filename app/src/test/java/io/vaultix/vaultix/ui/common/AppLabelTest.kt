/*
 * Vaultix — app:test · common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import io.vaultix.model.VaultCustomField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「关联应用」显示中文名的解析规则（2026-11-10 真机截图：`com.tdx.AndroidNew`）。
 *
 * 只测**纯 Kotlin** 的 [AppLabel.fromCustomFields] —— `PackageManager` 那一层需要
 * Context，且行为由系统决定，不是本项目的规则。
 */
class AppLabelTest {

    private fun field(name: String, value: String) = VaultCustomField(name = name, value = value)

    @Test
    fun `自定义字段里的中文应用名优先于包名`() {
        val fields = listOf(
            field("appName", "通达信"),
            field("bastion_app_package", "com.tdx.AndroidNew"),
        )
        assertEquals("通达信", AppLabel.fromCustomFields(fields, "com.tdx.AndroidNew"))
    }

    @Test
    fun `别名表覆盖 KeePassDX 与 Bastion 的各种写法`() {
        val packageName = "com.tdx.AndroidNew"
        listOf(
            "appName", "App Name", "APP_NAME", "app_name",
            "bastion_app_name", "BastionAppName", "ApplicationName", "androidAppName",
        ).forEach { key ->
            assertEquals(
                "别名 $key 没认出来",
                "通达信",
                AppLabel.fromCustomFields(listOf(field(key, "通达信")), packageName),
            )
        }
    }

    @Test
    fun `包名字段与当前包名不符时不许把名字安错`() {
        val fields = listOf(
            field("appName", "微信"),
            field("bastion_app_package", "com.tencent.mm"),
        )
        assertNull(AppLabel.fromCustomFields(fields, "com.tdx.AndroidNew"))
    }

    @Test
    fun `包名字段命中时才认名字`() {
        val fields = listOf(
            field("appName", "通达信"),
            field("App Package Name", "com.tdx.AndroidNew"),
            field("PackageName", "com.tdx.AndroidNew"),
        )
        assertEquals("通达信", AppLabel.fromCustomFields(fields, "com.tdx.AndroidNew"))
    }

    @Test
    fun `值是包名时不当作显示名`() {
        val fields = listOf(field("appName", "com.tdx.AndroidNew"))
        assertNull(AppLabel.fromCustomFields(fields, "com.tdx.AndroidNew"))
    }

    @Test
    fun `没有应用名字段时返回空交给 PackageManager 兜底`() {
        assertNull(AppLabel.fromCustomFields(emptyList(), "com.tdx.AndroidNew"))
        assertNull(
            AppLabel.fromCustomFields(
                listOf(field("bastion_app_package", "com.tdx.AndroidNew")),
                "com.tdx.AndroidNew",
            ),
        )
    }
}
