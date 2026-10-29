/*
 * Vaultix — app:ui:settings (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogScrubber]（#206）。
 *
 * 覆盖两件事，都是**不报错但会漏**的那类：
 * - [LogScrubber.scrub]：四类敏感形态必须被遮掉（屏幕显示此前走的是原文）；
 * - [LogScrubber.shouldKeep]：`E/F` 兜底**不得**架空白名单（旧的 `|| level in "EF"` 就是洞）。
 */
class LogScrubberTest {

    // ---- scrub ------------------------------------------------------------

    @Test
    fun scrub_masksSensitiveKeyValue() {
        val out = LogScrubber.scrub("12:00:00.001  VaultixAutofill: body password=hunter2")
        assertFalse("明文密码不得出现在输出里：\n$out", out.contains("hunter2"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun scrub_masksTokenAndKeyRegardlessOfCase() {
        val out = LogScrubber.scrub("Vaultix: Token=abc123 private_key: ZZZ")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("ZZZ"))
    }

    @Test
    fun scrub_masksEmailAndPhone() {
        val out = LogScrubber.scrub("Vaultix: user=alice@example.com tel=13800138000")
        assertFalse(out.contains("alice@example.com"))
        assertFalse(out.contains("13800138000"))
    }

    @Test
    fun scrub_masksLongBlob() {
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5"
        val out = LogScrubber.scrub("Vaultix: secret material $blob")
        assertFalse("超长 base64/hex 串必须被遮：\n$out", out.contains(blob))
    }

    @Test
    fun scrub_keepsOrdinaryTextUntouched() {
        // 反向用例：脱敏不能"顺手把有用信息也吃掉"，否则排查时一片 <redacted>。
        val line = "12:00:00.001  VaultixAutofill: fill ok items=3"
        assertEquals(line, LogScrubber.scrub(line))
    }

    // ---- shouldKeep -------------------------------------------------------

    @Test
    fun shouldKeep_keepsWhitelistedTagRegardlessOfLevel() {
        assertTrue(LogScrubber.shouldKeep("01-01 00:00:00.000 1 1 D Vaultix: ping", 'D'))
        assertTrue(LogScrubber.shouldKeep("01-01 00:00:00.000 1 1 I CredMan: query", 'I'))
    }

    @Test
    fun shouldKeep_dropsNonWhitelistedNoise() {
        // ★ 白名单存在的唯一理由：挡噪声。普通级别必须挡住。
        assertFalse(LogScrubber.shouldKeep("01-01 00:00:00.000 1 1 D afehal: something", 'D'))
        assertFalse(LogScrubber.shouldKeep("01-01 00:00:00.000 1 1 W WifiEnhance: x", 'W'))
    }

    @Test
    fun shouldKeep_errorLevelWithoutSelfHintIsAlsoDropped() {
        // ★★ 本文件的**核心**用例：旧的 `|| level in "EF"` 会让任何 E/F 级噪声长驱直入，
        //   白名单在错误级别上完全失效（Honor 机型实测几十秒淹没有效记录）。
        //   修法是兜底必须**同时**要求"属于本应用"。
        assertFalse(
            "E 级但不含 vaultix 的噪声必须被挡住",
            LogScrubber.shouldKeep("01-01 00:00:00.000 1 1 E afehal: fail", 'E'),
        )
        assertFalse(
            "F 级同理",
            LogScrubber.shouldKeep("01-01 00:00:00.000 1 1 F SomeSdk: fatal", 'F'),
        )
    }

    @Test
    fun shouldKeep_errorLevelOfOwnProcessIsKept() {
        // 自家崩溃不该因为 TAG 不在白名单里被丢掉（包名/进程名都含 vaultix）。
        assertTrue(
            LogScrubber.shouldKeep("01-01 00:00:00.000 1 1 E io.vaultix.vaultix: boom", 'E'),
        )
        assertTrue(
            // 刻意全小写：不落在白名单的 `Vaultix`（大小写敏感）上，走的是兜底那一条。
            LogScrubber.shouldKeep("01-01 00:00:00.000 1 1 F vaultix.internal: fatal", 'F'),
        )
    }
}
