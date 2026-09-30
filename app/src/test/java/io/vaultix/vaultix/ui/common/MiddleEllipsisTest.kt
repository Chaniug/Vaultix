/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 中间省略的**折叠逻辑**（测量那半在 Compose 里，不进单测）。
 *
 * 为什么要单独钉：路径的头尾各留几个字符是**有取向的决策**，不是随手 `take`。
 * 尾部省略会把「打开的是哪个库」先吃掉 —— 而用户看这一行最想确认的往往正是它。
 */
class MiddleEllipsisTest {

    @Test
    fun `放得下时原样返回`() {
        assertThat(foldMiddle("vault.example.com", keep = 99)).isEqualTo("vault.example.com")
        // 恰好等长也必须原样（否则会平白多一个省略号）。
        assertThat(foldMiddle("abc", keep = 3)).isEqualTo("abc")
    }

    @Test
    fun `折叠时保头保尾`() {
        // 46 个字符的 WebDAV 路径：keep=30 ⇒ 头 15 尾 15。
        val text = "WebDAV · nas.local:5006/vaultix-dav/我的密码库.kdbx"

        val folded = foldMiddle(text, keep = 30)

        assertThat(folded).isEqualTo("WebDAV · nas.lo…-dav/我的密码库.kdbx")
        // 头：来源标签完整保留（知道是哪台服务器）；尾：文件名完整保留（知道是哪个库）。
        assertThat(folded).startsWith("WebDAV")
        assertThat(folded).endsWith("我的密码库.kdbx")
    }

    @Test
    fun `极窄时先丢中间目录，尾部扩展名最后才丢`() {
        val text = "WebDAV · nas.local:5006/vaultix-dav/我的密码库.kdbx"

        // keep=14 ⇒ 头 7 尾 7：来源仍认得出，扩展名仍在。
        assertThat(foldMiddle(text, keep = 14)).isEqualTo("WebDAV …码库.kdbx")
        // keep=10 ⇒ 头 5 尾 5：只够 ".kdbx" —— 这不是"好看"，但至少说明"是个文件"。
        assertThat(foldMiddle(text, keep = 10)).isEqualTo("WebDA….kdbx")
    }

    @Test
    fun `头部比尾部多分一个字符（主机名比文件名更怕缺字）`() {
        // keep=5 ⇒ 头 3 尾 2。
        assertThat(foldMiddle("abcdefghij", keep = 5)).isEqualTo("abc…ij")
    }

    @Test
    fun `极端宽度下只剩省略号，不会崩`() {
        assertThat(foldMiddle("abcdefghij", keep = 0)).isEqualTo(ELLIPSIS)
        assertThat(foldMiddle("abcdefghij", keep = 1)).isEqualTo("a" + ELLIPSIS)
        assertThat(foldMiddle("", keep = 0)).isEqualTo("")
    }
}
