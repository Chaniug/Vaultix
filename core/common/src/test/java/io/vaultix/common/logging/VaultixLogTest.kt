/*
 * Vaultix — core:common (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.common.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * 日志门面的测试。
 *
 * 这些用例能存在，本身就是选型的结果：门面**不依赖 Android**（sink 由外部注入），
 * 所以不需要 Robolectric / mock `android.util.Log` 就能断言"到底记了什么"。
 * 若当初直接在 core:common 里引 `android.util.Log`，这里一条都写不了。
 */
class VaultixLogTest {

    private val collected = mutableListOf<Triple<String, LogLevel, String>>()

    private fun installCollector(enabled: Boolean = true) {
        VaultixLog.install(enabled) { tag, level, message, _ -> collected += Triple(tag, level, message) }
    }

    @After
    fun tearDown() {
        VaultixLog.reset()
        collected.clear()
    }

    @Test
    fun `默认关闭时不写出任何日志`() {
        installCollector(enabled = false)

        VaultixLog.d("T") { "hello" }

        assertTrue(collected.isEmpty())
    }

    @Test
    fun `关闭时消息 lambda 根本不会被求值`() {
        installCollector(enabled = false)
        var evaluated = false

        VaultixLog.d("T") { evaluated = true; "x" }

        // 这是"release 零开销"的关键：不是"算了不打印"，而是**压根不算**。
        assertFalse(evaluated)
    }

    @Test
    fun `开启后按 tag 与级别写出`() {
        installCollector()

        VaultixLog.w("VaultixAuth") { "refresh transient" }

        assertEquals(1, collected.size)
        assertEquals("VaultixAuth", collected[0].first)
        assertEquals(LogLevel.WARN, collected[0].second)
        assertEquals("refresh transient", collected[0].third)
    }

    @Test
    fun `协程取消保持静默 —— 它是控制流不是故障`() {
        installCollector()

        VaultixLog.w("T", CancellationException("scope closed")) { "should be dropped" }

        assertTrue("一次会话结束会连打十几条取消日志，必须丢掉", collected.isEmpty())
    }

    @Test
    fun `超过限频上限后丢弃`() {
        installCollector()

        repeat(200) { VaultixLog.d("spam") { "n=$it" } }

        // RATE_MAX = 50：防循环里的日志刷爆 logcat、掩盖真问题。
        assertEquals(50, collected.size)
    }

    @Test
    fun `sink 自身抛异常也不会拖垮调用方`() {
        VaultixLog.install(enabled = true) { _, _, _, _ -> throw IllegalStateException("sink boom") }

        // 不抛出即通过：日志设施绝不能把"记日志"变成"制造崩溃"。
        VaultixLog.d("T") { "still fine" }
    }

    @Test
    fun `redact 保留首尾并在过短时整体打码`() {
        assertEquals("", redact(""))
        assertEquals("***", redact("abc"))
        // 长度 8 > keep*2+2 = 6 ⇒ 保留首尾各 2
        assertEquals("ab***yz", redact("abcdefyz"))
    }
}
