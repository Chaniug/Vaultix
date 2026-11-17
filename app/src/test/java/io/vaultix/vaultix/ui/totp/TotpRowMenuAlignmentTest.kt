/*
 * Vaultix — app:test · totp
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 验证码条目 ⋮ 按钮**对齐契约**的源码门禁（#300，2026-11-17）。
 *
 * 用户报「⋮ 偏左了一点，没和条目界面对齐」。根因：2026-10-19 的注释按 36dp 容器推算
 * 图标留白，而反编译 `material3-android:1.5.0-alpha16` 证实 M3 IconButton 容器恒 48dp
 * ⇒ 图标比设计意图左缩 6dp。
 *
 * ## 为什么是源码文本扫描
 *
 * 这是**两个 dp 的视觉偏移**，Compose instrumentation test 在 CI 无 SDK 时跑不了，
 * 而 detekt 也不查几何。可机检的只有源码写法本身 —— 与 `DialogStyleRulesTest` 同族。
 *
 * ## 钉死的两条（缺一就 regress）
 *
 * 1. **热区仍 ≥48dp**：`minimumInteractiveComponentSize()` 必须在；谁为了"对齐"把它
 *    换成 `size(36.dp)`，热区就塌回 36dp（2026-10-19 修过的护墙不能倒）。
 * 2. **图标视觉右移 6dp**：`offset(x = TotpRowMenuIconVisualNudge)` 必须与 20dp 图标同时
 *    在；只留热区不补偏移 = 用户看到的「偏左」原样复现。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.totp

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpRowMenuAlignmentTest {

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val screenFile = File(repoRoot, "java/io/vaultix/vaultix/ui/totp/TotpCodesScreen.kt")
    private val source: String by lazy { screenFile.readText() }

    /**
     * [TotpRowMenu] 的函数体（到下一个顶层声明为止），避免别的 IconButton 干扰断言。
     *
     * ⚠️ 必须剥掉 `//` 行注释：本函数 KDoc 里详细写了「36dp 容器」的推导过程，
     * 直接 `contains("36.dp")` 会命中注释而不是代码 —— 这是 #296/#297 门禁踩过的
     * 「注释与代码用词雷同」同款坑，判断一律只看有效代码行。
     */
    private val menuBody: String by lazy {
        val start = source.indexOf("private fun TotpRowMenu(")
        assertTrue("找不到 TotpRowMenu 定义", start >= 0)
        val end = source.indexOf("\n/**", start + 1).let { if (it < 0) source.length else it }
        source.substring(start, end)
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    @Test
    fun `热区必须保留 minimumInteractiveComponentSize`() {
        // 这是 2026-10-19 修好的 48dp 护墙：绝不能为了"贴右"退回 size(36.dp)。
        assertTrue(
            "TotpRowMenu 的 IconButton 必须保留 minimumInteractiveComponentSize()（≥48dp 热区）",
            menuBody.contains("Modifier.minimumInteractiveComponentSize()"),
        )
    }

    @Test
    fun `必须用 20dp 图标加视觉右移而非缩小热区`() {
        assertTrue(
            "⋮ 图标仍应是 UiSize.SecondaryIcon（20dp）",
            menuBody.contains(".size(UiSize.SecondaryIcon)"),
        )
        assertTrue(
            "⋮ 图标必须 offset 视觉右移，否则用户看到的「偏左」会复现",
            menuBody.contains("offset(x = TotpRowMenuIconVisualNudge)"),
        )
    }

    @Test
    fun `右移量常量必须是 6dp`() {
        // 14dp（48 容器居中留白）− 8dp（原设计右缘留白）= 6dp。
        // 改成别的值就等于换了一个设计意图，须连同 KDoc 一起重新论证。
        val constMatch = Regex(
            """internal\s+val\s+TotpRowMenuIconVisualNudge\s*=\s*(\d+)\.dp""",
        ).find(source)
        assertTrue("找不到 TotpRowMenuIconVisualNudge 常量定义", constMatch != null)
        assertTrue(
            "视觉右移量必须是 6dp，实际 ${constMatch!!.groupValues[1]}dp",
            constMatch.groupValues[1] == "6",
        )
    }

    @Test
    fun `不得用 size36 直接缩小按钮`() {
        // 回归护栏：若有人照着旧注释把整枚按钮改回 size(36.dp)，热区会塌到 36dp。
        assertTrue(
            "TotpRowMenu 不得出现 Modifier.size(36.dp)（会把热区从 48dp 缩到 36dp）",
            !Regex("""Modifier\s*\.\s*size\s*\(\s*36\s*\.dp\s*\)""").containsMatchIn(menuBody),
        )
    }
}
