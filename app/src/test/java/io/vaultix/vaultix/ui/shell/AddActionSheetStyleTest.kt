/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.shell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「+」新建面板剪影卡片的**三条可机检规范**（2026-11-12 #285，整体反转 #283）。
 *
 * 用户反馈「按安卓最新设计规范重新配色，不要奇怪颜色，浅色 / 深色都兼顾，同时每张卡
 * 有视觉记忆点」。#283 那三条规则钉的**正是被淘汰的做法**：`xxxContainer` 交叉铺开 +
 * `isDarkSurface` 判深浅 + 两档 alpha（浅 0.68/0.62、深 0.94/0.92）+ `onXxxContainer`。
 * 只要还靠随主题翻转的 `xxxContainer` 配半透明分档，深浅两档就得各调一遍 —— 这正是
 * 用户说的「奇怪颜色」的来源。
 *
 * ⇒ #285 换 **M3 fixed accent roles**：`xxxFixed` / `xxxFixedDim` / `onXxxFixed` /
 *   `onXxxFixedVariant` 在浅色与深色主题下取到同一组固定色值，卡底直接画纯色，
 *   一套配对走到底，不需要判档、不需要任何 alpha 常量。
 *
 * ⚠️ 判据会随用户反馈**反转**，别把它当誓言：#282 加交叉色系、#283 补深色档、
 *    #285 把两者一起作废（同 `ItemsRowStyleTest` 那条「判据反转」留痕）。
 * ⚠️ fixed 色值本身（饱和度 / 对比度 / 浅深同值）由 `VaultixBrandColorTest` 逐项钉住，
 *    本文件只钉「接线关系」——用了哪些角色、有没有残留旧角色、5 型有没有铺开。
 *
 * 三条对应三个最容易「改一处漏一处」的点：
 * 1. 卡底 / 前景一律 fixed 家族，不许残留随主题翻转的 `xxxContainer`；
 * 2. 浅深同值 ⇒ 不许判深浅档、不许配色分档 alpha 常量；
 * 3. 5 种类型靠「3 色相 × Fixed / FixedDim 两档」区分，前景全用配对 `onXxxFixed`。
 */
class AddActionSheetStyleTest {

    private val mainRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private val source: String = stripComments(
        File(mainRoot, "java/io/vaultix/vaultix/ui/shell/AddActionSheet.kt").readText(),
    )

    // ------------------------------------------------------------------ 规则 1

    /**
     * **卡底 / 前景一律走 fixed 家族，不碰随主题翻转的 `xxxContainer`。**
     *
     * #282/#283 的 `primaryContainer` 等角色浅色是浅色、深色变暗色，配上 0.68/0.94
     * 两档 alpha 后，近黑 surface 上几个色相被洗成浑浊灰。fixed 角色浅深同值，
     * 卡底直接 `color = palette.container`（纯色）、`contentColor = palette.onContainer`。
     */
    @Test
    fun `剪影卡一律走fixed角色不碰随主题翻转的container`() {
        val palette = functionBody(source, "glyphPalette")
        val tile = functionBody(source, "AddTypeTile")
        val generator = functionBody(source, "AddGeneratorTile")
        val cards = tile + generator + palette
        val missing = buildList {
            listOf("primaryFixed", "secondaryFixed", "tertiaryFixed").forEach { role ->
                if (!palette.contains(role)) add("glyphPalette 没用 $role")
            }
            listOf("onPrimaryFixed", "onSecondaryFixed", "onTertiaryFixed").forEach { role ->
                if (!palette.contains(role)) add("glyphPalette 没用 $role 前景")
            }
            listOf(
                "primaryContainer", "secondaryContainer", "tertiaryContainer",
                "onPrimaryContainer", "onSecondaryContainer", "onTertiaryContainer",
            ).forEach { role ->
                if (cards.contains(role)) add("卡片绘制里还残留 $role（#285 已废弃）")
            }
            if (!tile.contains("color = palette.container")) add("类型卡卡底没画纯 fixed 色")
            if (!tile.contains("contentColor = palette.onContainer")) add("类型卡没配 onXxxFixed 前景")
            if (!generator.contains("color = scheme.tertiaryFixed")) add("生成器卡底没走 tertiaryFixed")
            if (!generator.contains("contentColor = scheme.onTertiaryFixed")) {
                add("生成器没配 onTertiaryFixed 前景")
            }
        }
        assertTrue("剪影卡 fixed 角色接线：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **浅深同值 ⇒ 不判深浅档、不留配色分档 alpha 常量。**
     *
     * `isDarkSurface` / `dark: Boolean` / `TILE_ALPHA_*` / `WASH_ALPHA_*` 全是
     * 「`xxxContainer` 随主题翻转」的补丁；换成 fixed 角色后一个都不该剩。
     *
     * ⚠️ 主卡水印的 `HERO_GLYPH_*_ALPHA` 是出血透明度、`GlyphCard` / `GlyphGenerator`
     *    形状内部的 `copy(alpha)` 是绘图层次，都不属于配色分档，不在本规则禁列 ——
     *    所以这里只精确点名 4 组旧常量，且 `.copy(alpha)` 只扫三处卡片绘制函数体。
     */
    @Test
    fun `fixed浅深同值不再判深浅档也不再叠alpha分档`() {
        val tile = functionBody(source, "AddTypeTile")
        val glyph = functionBody(source, "ItemFieldGlyph")
        val glyphSignature = functionSignature(source, "ItemFieldGlyph")
        val generator = functionBody(source, "AddGeneratorTile")
        val missing = buildList {
            if (source.contains("isDarkSurface")) add("还在判深浅档（fixed 角色浅深同值）")
            if (source.contains("isSystemInDarkTheme")) add("用了系统主题判定，自定义主题下会错档")
            listOf("TILE_ALPHA_", "WASH_ALPHA_", "GLYPH_LINE_ALPHA_", "GENERATOR_ALPHA_")
                .forEach { prefix ->
                    if (source.contains(prefix)) add("还残留配色分档常量 $prefix（卡底应画纯色）")
                }
            if (glyphSignature.contains("dark: Boolean")) add("ItemFieldGlyph 还接 dark 参数")
            if (tile.contains("dark")) add("类型卡还残留深浅档接线")
            if (glyph.contains("onSurfaceVariant")) add("剪影线条还在用 onSurfaceVariant 顶替")
            if (!glyph.contains(".background(palette.wash)")) add("剪影小卡没画纯 wash 色")
            if (Regex("""\.copy\s*\(\s*alpha""").containsMatchIn(tile + glyph + generator)) {
                add("卡底 / 剪影还在叠 alpha")
            }
        }
        assertTrue("fixed 浅深同值：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **5 种类型各有记忆点，且前景一律用 fixed 配对。**
     *
     * 3 色相 × Fixed / FixedDim 两档铺满 5 型；`wash` 与 `container` 同色系（不再交叉
     * 色系）。前景保留 #283「容器底配 on-container」的结论，只是换成 `onXxxFixed` 家族：
     * 拿 `onSurface` / `onSurfaceVariant` 顶替依旧是错的。
     */
    @Test
    fun `五型各有记忆点且前景一律用fixed配对`() {
        val palette = functionBody(source, "glyphPalette")
        val glyph = functionBody(source, "ItemFieldGlyph")
        val missing = buildList {
            listOf("container", "wash", "onContainer", "onContainerVariant").forEach { field ->
                if (!source.contains("val $field: Color")) add("GlyphPalette 缺 $field 字段")
            }
            val branches = Regex("""GlyphPalette\s*\(""").findAll(palette).count()
            if (branches != 5) add("glyphPalette 应有 5 个类型分支，实际 $branches")
            val dims = Regex("""FixedDim""").findAll(palette).count()
            if (dims < 5) add("FixedDim 两档没铺开（实际 $dims 次），卡片会撞色")
            if (!glyph.contains("accent = palette.onContainer")) add("重点形状没用 onXxxFixed")
            if (!glyph.contains("line = palette.onContainerVariant")) add("线条没用 onXxxFixedVariant")
            if (source.contains("val onTint: Color") || source.contains("val onWash: Color")) {
                add("旧交叉表字段 onTint / onWash 又回来了")
            }
            if (!source.contains("onTertiaryFixedVariant")) add("生成器线条没用 onTertiaryFixedVariant")
        }
        assertTrue("五型 fixed 配对：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ----------------------------------------------------------------- helpers

    /** 取一个顶层 / Composable 函数的**函数体**（大括号配平，跳过字符串里的括号）。 */
    private fun functionBody(text: String, name: String): String {
        val start = Regex("""fun\s+""" + Regex.escape(name) + """\s*\(""")
            .find(text) ?: return ""
        val brace = text.indexOf('{', start.range.last)
        if (brace < 0) return ""
        return text.substring(brace, balancedBraceEnd(text, brace))
    }

    /** 取函数签名（`fun name(` 到函数体 `{` 之前），用于检查参数是否接线。 */
    private fun functionSignature(text: String, name: String): String {
        val start = Regex("""fun\s+""" + Regex.escape(name) + """\s*\(""")
            .find(text) ?: return ""
        val brace = text.indexOf('{', start.range.last)
        if (brace < 0) return ""
        return text.substring(start.range.first, brace)
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

    /**
     * 去掉行注释与块注释，**保留换行**（行号才不会错位）。
     *
     * 与 `DialogStyleRulesTest.stripComments` 同一套写法：KDoc 里常写
     * `` `onSurface` `` 举反例，也会复述被淘汰的旧做法，不剥掉会把说明文字当成真实调用。
     * 拆成小函数是为了过 detekt `CyclomaticComplexMethod ≤14`。
     */
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
