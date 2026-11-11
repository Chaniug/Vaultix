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
 * 「+」新建面板剪影卡片的**三条可机检规范**（2026-11-12，#283）。
 *
 * 用户反馈「深色模式下……添加条目里面的剪影卡片显示效果不太好，色彩搭配有点怪，
 * 很多文案都显示不清晰，按深色模式的规范进行优化」。根因是纯观感 / 源码写法问题，
 * CI 没有真机也跑不了 instrumented test ⇒ 与 `DialogStyleRulesTest`、
 * `LiquidGlassDockRulesTest` 同口径，扫源码钉住。
 *
 * 三条对应三个最容易「改一处漏一处」的点：
 * 1. M3 容器底必须配 `onXxxContainer` 前景，不能拿 `onSurface` 顶替；
 * 2. 深浅判定必须看**实际底色亮度**（本 App 有独立主题模式 + OLED 纯黑 + 动态取色），
 *    且深色档要把 container 画纯、线条从配对前景取色；
 * 3. 深色档叠色 / 线条强度必须强于浅色档，同时**浅色档沿用 #282 一个数值没动**。
 */
class AddActionSheetStyleTest {

    private val mainRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private val source: String = stripComments(
        File(mainRoot, "java/io/vaultix/vaultix/ui/shell/AddActionSheet.kt").readText(),
    )

    // ------------------------------------------------------------------ 规则 1

    /**
     * **容器底必须配 `onContainer` 前景。**
     *
     * #282 把卡底换成 `primaryContainer` / `secondaryContainer` / `tertiaryContainer`
     * 让配色活泼，但 `contentColor` 仍是 `onSurface` —— M3 规定二者必须成对，
     * 深色下两者亮度分叉最大，卡上的类型名与剪影线条同时发灰，正是用户说的「文案不清晰」。
     */
    @Test
    fun `剪影卡片容器底配对onContainer前景`() {
        val tile = functionBody(source, "AddTypeTile")
        val generator = functionBody(source, "AddGeneratorTile")
        val palette = functionBody(source, "glyphPalette")
        val missing = buildList {
            if (tile.isEmpty()) add("AddTypeTile 函数体没找到")
            if (generator.isEmpty()) add("AddGeneratorTile 函数体没找到")
            if (!source.contains("val onTint: Color")) add("GlyphPalette 缺 onTint")
            if (!source.contains("val onWash: Color")) add("GlyphPalette 缺 onWash")
            if (!tile.contains("contentColor = palette.onTint")) {
                add("类型卡 contentColor 没配 onTint（不能用 onSurface 顶替容器底）")
            }
            if (tile.contains("contentColor = MaterialTheme.colorScheme.onSurface")) {
                add("类型卡仍用 onSurface 配 xxxContainer 底")
            }
            if (!generator.contains("contentColor = scheme.onTertiaryContainer")) {
                add("生成器卡丢了 onTertiaryContainer 配对")
            }
            if (!palette.contains("onPrimaryContainer") ||
                !palette.contains("onSecondaryContainer") ||
                !palette.contains("onTertiaryContainer")
            ) {
                add("glyphPalette 没为每种类型给出配对的 on-color")
            }
        }
        assertTrue("剪影卡 container/onContainer 配对：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **深浅取实际底色，深色档把 container 画纯、线条从配对前景取色。**
     *
     * ⚠️ 不许用 `isSystemInDarkTheme()`：App 设深色、系统浅色时会判错档。
     * ⚠️ 深色下 0.68 + 0.62 两层半透明叠在近黑 surface 上会把三个色系洗成浑浊灰
     *    （用户说的「色彩搭配有点怪」）；`onSurfaceVariant × 0.34` 的线条则几乎看不见。
     */
    @Test
    fun `深浅按底色亮度判定且深色档画纯叠色`() {
        val tile = functionBody(source, "AddTypeTile")
        val glyph = functionBody(source, "ItemFieldGlyph")
        val glyphSignature = functionSignature(source, "ItemFieldGlyph")
        val generator = functionBody(source, "AddGeneratorTile")
        val missing = buildList {
            if (!source.contains("isDarkSurface(")) add("没复用 isDarkSurface 按底色判深浅")
            if (source.contains("isSystemInDarkTheme")) add("用了系统主题判定，自定义主题下会错档")
            if (!tile.contains("if (dark) TILE_ALPHA_DARK else TILE_ALPHA_LIGHT")) {
                add("类型卡底色没分深浅档")
            }
            // ⚠️ 参数在签名里，不在函数体里 —— functionBody() 从 `{` 起，查不到。
            if (!glyphSignature.contains("dark: Boolean")) add("ItemFieldGlyph 没接深浅档参数")
            if (!tile.contains("dark = dark")) add("类型卡没把深浅档传给剪影")
            if (!glyph.contains("palette.onWash")) add("深色剪影线条没从 onWash 取色")
            if (!glyph.contains("if (dark) WASH_ALPHA_DARK else WASH_ALPHA_LIGHT")) {
                add("剪影小卡底色没分深浅档")
            }
            if (!generator.contains("onTertiaryContainer.copy")) {
                add("生成器卡深色线条没从 onTertiaryContainer 取色")
            }
        }
        assertTrue("深色剪影卡配色：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **深色档强度更大，浅色档保持 #282 原值不动。**
     *
     * 这一条防的是「为了修深色顺手把浅色也调了」—— #282 刚按用户反馈把浅色调活泼，
     * 本轮只处理深色，浅色的 0.68 / 0.62 / 0.34 / 0.58 一个都不该变。
     */
    @Test
    fun `深色叠色强于浅色且浅色档保持282原值`() {
        val tileLight = constFloat("TILE_ALPHA_LIGHT")
        val tileDark = constFloat("TILE_ALPHA_DARK")
        val washLight = constFloat("WASH_ALPHA_LIGHT")
        val washDark = constFloat("WASH_ALPHA_DARK")
        val lineLight = constFloat("GLYPH_LINE_ALPHA_LIGHT")
        val lineDark = constFloat("GLYPH_LINE_ALPHA_DARK")
        val generatorLight = constFloat("GENERATOR_ALPHA_LIGHT")

        assertTrue("深色卡底应比浅色更实（$tileDark > $tileLight）", tileDark > tileLight)
        assertTrue("深色剪影底应比浅色更实（$washDark > $washLight）", washDark > washLight)
        assertTrue("深色线条应比浅色更清晰（$lineDark > $lineLight）", lineDark > lineLight)
        assertTrue("深色档仍是半透明，不许直接画成不透明板", tileDark < 1f && washDark < 1f)
        assertTrue(
            "浅色档沿用 #282：0.68 / 0.62 / 0.34 / 0.58（实得 $tileLight / $washLight / " +
                "$lineLight / $generatorLight）",
            tileLight == 0.68f && washLight == 0.62f &&
                lineLight == 0.34f && generatorLight == 0.58f,
        )
    }

    // ----------------------------------------------------------------- helpers

    /** 读一个私有 `const val … = 0.xxf` 的数值；读不到返回 -1 让断言明确失败。 */
    private fun constFloat(name: String): Float =
        Regex("""private const val """ + Regex.escape(name) + """\s*=\s*([\d.]+)f""")
            .find(source)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f

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
     * `` `onSurface` `` 举反例，不剥掉会把说明文字当成真实调用。
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
