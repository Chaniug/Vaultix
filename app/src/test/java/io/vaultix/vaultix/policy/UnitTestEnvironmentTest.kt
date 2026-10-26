/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.policy

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住"单测里 `org.json` 是**真实实现**"这条基建假设（`.ai/ISSUES.md` #197 副产物）。
 *
 * ## 为什么值得单独写一条测试
 *
 * AGP 的 `mockable-android.jar` 会把 `org.json.JSONObject` 的每个方法替换成
 * "返回默认值"：`has()` 恒 false、`getJSONObject()` 恒 null、连
 * `JSONObject(String)` 构造器都是空实现。于是：
 *
 *  - 任何"解析 JSON"的被测代码在单测里**只会走解析失败分支**；
 *  - 测试失败时看到的现象是"被测代码有 bug"，而真正坏的是测试基建；
 *  - 更糟的是**反向**情况：一个恒返回默认值的实现，会让"应该失败的用例"通过。
 *
 * 这个坑在本仓库真实踩过（2026-10-26，通行密钥 `authenticatorSelection` 解析）。
 * 修法是在 `app/build.gradle.kts` 里加 `testRuntimeOnly(libs.org.json)`。
 *
 * 这条测试不测业务逻辑，只测**环境**：如果哪天有人删掉那个依赖、或者升级 AGP
 * 改了 mockable 策略，这里会立刻红，而不是等到某个业务断言莫名其妙失败。
 */
class UnitTestEnvironmentTest {

    @Test
    fun `单测里的 org_json 是真实实现而不是 mockable 空壳`() {
        val obj = org.json.JSONObject("""{"a":1,"nested":{"b":"c"}}""")
        assertTrue("JSONObject(String) 解析后应当有键 'a'（mockable 空壳会返回 false）", obj.has("a"))
        assertTrue("length() 应当大于 0（mockable 空壳恒为 0）", obj.length() > 0)
        assertTrue("toString() 不应为 null", obj.toString().contains("\"a\""))

        val nested = obj.optJSONObject("nested")
        assertTrue("嵌套对象不应为 null（mockable 空壳 getJSONObject 恒返回 null）", nested != null)
        assertTrue("嵌套对象的字段应可读", nested?.optString("b") == "c")
    }

    @Test
    fun `org_json 对缺失键与显式 null 的语义符合预期`() {
        // 这两条是 #197 里真实踩到的边界，锁住它们免得以后改实现时又绕回去。
        val obj = org.json.JSONObject("""{"present":"x","explicitNull":null}""")
        assertTrue("缺失键：has 应为 false", !obj.has("missing"))
        assertTrue("缺失键：isNull 应为 true", obj.isNull("missing"))
        assertTrue("显式 null：isNull 应为 true", obj.isNull("explicitNull"))
        assertTrue("present 的 isNull 应为 false", !obj.isNull("present"))
    }
}
