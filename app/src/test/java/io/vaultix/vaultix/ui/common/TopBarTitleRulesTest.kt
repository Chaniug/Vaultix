/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #285 第 3 / 6 项顶栏标题的**三条可机检规范**（2026-11-12）。
 *
 * 两处都是真机才看得出的观感 / 语义缺陷，编译过、detekt 查不出、CI 全绿：
 *
 * - **第 3 项**：详情页条目名稍长时左上角标题会折成 `accounts.spotify.` / `com`
 *   的孤字行，还与右侧编辑 / 删除图标错位。修法是给 [TopBarTitle] 加 `singleLine`，
 *   详情页传 `true` 只走「缩字号 → 省略号」，库名顶栏仍允许两行。
 * - **第 6 项**：KDBX 库条目页左上角显示 `xxx.kdbx`，用户要求统一显示格式名
 *   `KDBX`。判据必须只在 `VaultOriginLabel.vaultTopBarTitle` 一处（#263 / #264
 *   两轮真机反馈都是「同一份库信息在两处各判一遍」漂移出来的）。
 *
 * ⚠️ 第 2 项（域名分组「组内紧 / 组间松」+ 分组头无背景）由 `ItemsRowStyleTest`
 *    的规则 3 / 6 覆盖，本文件不重复。
 * ⚠️ `check_kdbx_origin_single_source.py` 守的是「来源标签」那三个资源名，
 *    本文件补的是「顶栏标题」这条新判据，两者互补、不重叠。
 */
class TopBarTitleRulesTest {

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private fun source(relative: String): String =
        stripComments(File(repoRoot, "java/io/vaultix/vaultix/$relative").readText())

    private val strings: String = File(repoRoot, "res/values/strings.xml").readText()

    // ------------------------------------------------------------------ 规则 1

    /**
     * **详情页标题单行，库名顶栏仍允许两行。**
     *
     * `singleLine` 默认值必须是 `false` —— 它只是详情页专用的开关，不能顺手把
     * 库名 / 设置页那些顶栏也一起压成一行。`allowTwoLines` 仍要保留
     * `title.subtitle == null`（有副标题时第二行已被占用，旧契约不破）。
     */
    @Test
    fun `详情页长标题单行且库名顶栏仍可两行`() {
        val topBar = source("ui/common/ExpressiveTopBar.kt")
        val detail = source("ui/detail/ItemDetailScreen.kt")
        // 精确切出详情页那一次 TopBarTitle(...) 调用（到 collapseFraction 为止）。
        val detailTitle = detail
            .substringAfter("title = TopBarTitle(", "")
            .substringBefore("collapseFraction", "")
        val missing = buildList {
            if (!topBar.contains("val singleLine: Boolean = false")) {
                add("TopBarTitle 缺默认 false 的 singleLine（会误伤库名顶栏）")
            }
            if (!topBar.contains("allowTwoLines = title.subtitle == null && !title.singleLine")) {
                add("TitleBlock 没把 singleLine 接进 allowTwoLines，也没保留 subtitle 契约")
            }
            if (detailTitle.isEmpty()) add("详情页找不到 TopBarTitle 调用块")
            if (!detailTitle.contains("singleLine = true")) {
                add("详情页顶栏没要求单行（长域名会折出孤字行）")
            }
        }
        assertTrue("详情页长标题排版：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **KDBX 顶栏标题统一显示格式名，判据只在 `VaultOriginLabel` 一处。**
     *
     * `ItemsScreen` 里另有一个合法的 `VaultKind.KDBX ->`（`cloudSyncBadgeOrNull`
     * 的云同步角标，#263 判据），所以不能粗放地禁掉整个文件的 KDBX 分支 ——
     * 精确钉住的是「`vault_title_kdbx` 这个资源不许出现在别处」。
     */
    @Test
    fun `KDBX顶栏标题统一显示格式名且判据只在单一真源`() {
        val label = source("ui/common/VaultOriginLabel.kt")
        val items = source("ui/items/ItemsScreen.kt")
        val missing = buildList {
            if (!label.contains("fun vaultTopBarTitle(vault: VaultSummary?): String")) {
                add("缺 vaultTopBarTitle 单一真源")
            }
            if (!label.contains("VaultKind.KDBX -> stringResource(R.string.vault_title_kdbx)")) {
                add("KDBX 分支没显示格式名（又变回 xxx.kdbx）")
            }
            if (!label.contains("else -> vault?.name.orEmpty()")) {
                add("非 KDBX 没回退库名（Bitwarden 顶栏会空掉）")
            }
            if (!Regex("""<string name="vault_title_kdbx">KDBX</string>""").containsMatchIn(strings)) {
                add("字符串资源 vault_title_kdbx 的值不是 KDBX")
            }
            if (!items.contains("vaultName = vaultTopBarTitle(state.vault)")) {
                add("条目页顶栏没走单一真源")
            }
            if (items.contains("R.string.vault_title_kdbx")) {
                add("顶栏标题判据扩散到了 ItemsScreen（应只在 VaultOriginLabel）")
            }
        }
        assertTrue("KDBX 顶栏标题：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **只改顶栏，列表状态行仍带库文件名。**
     *
     * `VaultStatusRow` 是用户核对「我打开的是哪个文件」的地方，语义与顶栏不同；
     * #285 第 6 项刻意不动它。这条防的是后来者把 `vaultTopBarTitle` 顺手复用过去，
     * 把文件名一起抹掉。
     */
    @Test
    fun `条目列表状态行仍带库文件名不被顶栏改动抹掉`() {
        val items = source("ui/items/ItemsScreen.kt")
        val statusRow = functionBody(items, "VaultStatusRow")
        val missing = buildList {
            if (!items.contains("vaultCardInfoOf(")) add("列表状态行的库信息源被删了")
            if (!items.contains("VaultStatusRow(card = vaultCard)")) add("列表状态行没渲染")
            if (statusRow.contains("vaultTopBarTitle(") ||
                statusRow.contains("vault_title_kdbx")
            ) {
                add("顶栏的 KDBX 判据扩散到了列表状态行（那里要显示文件名）")
            }
        }
        assertTrue("列表状态行边界：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ----------------------------------------------------------------- helpers

    /** 取顶层 / Composable 函数的函数体（大括号配平，跳过字符串里的括号）。 */
    private fun functionBody(text: String, name: String): String {
        val start = Regex("""fun\s+""" + Regex.escape(name) + """\s*\(""")
            .find(text) ?: return ""
        val brace = text.indexOf('{', start.range.last)
        if (brace < 0) return ""
        return text.substring(brace, balancedBraceEnd(text, brace))
    }

    /** 从 `{` 起配平，返回右括号**之后**的下标。 */
    private fun balancedBraceEnd(text: String, start: Int): Int {
        var depth = 0
        var i = start
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (c == '"' && (i == 0 || text[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                if (c == '{') depth++
                if (c == '}' && --depth == 0) return i + 1
            }
            i++
        }
        return text.length
    }

    /** 去掉行注释与块注释、保留换行（KDoc 里会复述旧写法，不剥掉会误判）。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            i = when {
                text[i] == '"' -> copyStringLiteral(text, i, out)
                text.startsWith("//", i) -> skipLineComment(text, i, out)
                text.startsWith("/*", i) -> skipBlockComment(text, i, out)
                else -> {
                    out.append(text[i])
                    i + 1
                }
            }
        }
        return out.toString()
    }

    /** 原样复制字符串字面量（含转义对），返回它之后的下标。 */
    private fun copyStringLiteral(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(text[i])
        i++
        while (i < text.length) {
            val c = text[i]
            out.append(c)
            i++
            if (c == '\\' && i < text.length) {
                out.append(text[i])
                i++
            } else if (c == '"') {
                return i
            }
        }
        return i
    }

    /** 跳过 `//` 到行尾，补换行保住行号。 */
    private fun skipLineComment(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        while (i < text.length && text[i] != '\n') i++
        out.append('\n')
        return i
    }

    /** 跳过 `/* … */`，把其中的换行补回去。 */
    private fun skipBlockComment(text: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) {
            if (text[i] == '\n') out.append('\n')
            i++
        }
        return i + 2
    }
}
