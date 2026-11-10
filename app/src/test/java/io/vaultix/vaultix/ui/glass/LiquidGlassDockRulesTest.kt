/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 液态玻璃底栏的**六条可机检规范**（2026-11-10 第四轮）。
 *
 * 用户这轮反馈「点击效果和玻璃的效果都没有做完善，注意底栏的边界和大小」。
 * 对应的三类缺陷都是「编译过、detekt 查不到、真机上才看得出来」：
 *
 * 1. `drawRect` 画高光 / 描边 ⇒ 被 `clip(Pill)` 裁掉两端半圆 ⇒ **边界断在圆角**；
 * 2. 模糊不提饱和度 ⇒ 背后内容糊成灰雾 ⇒ **玻璃像蒙了层雾**；
 * 3. 玻璃态与实色态各写一份布局 / 写死高度 ⇒ 两种外观悄悄分叉、甚至高度塌 0（#205）。
 *
 * ⚠️ 与 `DialogStyleRulesTest` 同理：这些是**源码写法**问题，只能扫文本钉住；
 *    CI 没有真机，也跑不了 instrumented test。
 */
class LiquidGlassDockRulesTest {

    private val mainRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val uiDir = File(mainRoot, "java/io/vaultix/vaultix/ui")
    private val glassDir = File(uiDir, "glass")

    private fun glassSource(name: String): String = stripComments(File(glassDir, name).readText())

    private fun dockSource(): String =
        stripComments(File(uiDir, "shell/VaultixBottomDock.kt").readText())

    // ------------------------------------------------------------------ 规则 1

    /**
     * **玻璃的所有几何装饰必须按 Shape 的 Outline 画。**
     *
     * 旧实现用 `drawRect` 画顶部亮线、底部暗线和矩形内描边。层 B 虽然 `.clip(Pill)`，
     * 但被裁掉的恰恰是药丸两端的半圆 —— 圆角处既没高光也没描边，玻璃读起来像
     * 「上下有边、左右没边」的一块板。这就是用户说的「注意底栏的边界」。
     */
    @Test
    fun `玻璃装饰一律按轮廓绘制_不许退回矩形`() {
        val source = glassSource("LiquidGlass.kt")
        val offenders = listOf("drawGlass", "drawSpecularRim", "drawFocusSheen")
            .mapNotNull { name ->
                val body = functionBody(source, name)
                when {
                    body.isEmpty() -> "$name：找不到函数体"
                    body.contains("drawRect(") -> "$name：出现 drawRect"
                    !body.contains("drawOutline(") -> "$name：没有 drawOutline"
                    else -> null
                }
            }

        assertTrue(
            "玻璃装饰必须沿轮廓绘制（drawOutline + shape.createOutline），" +
                "退回 drawRect 会让药丸两端的高光与描边被裁掉：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **两种外观必须共用同一套形状与行布局。**
     *
     * #120 / #193 / #195 栽过三次「同一规则写两处、只改一处」：实色 `Surface` 与
     * 玻璃 `LiquidGlassLayer` 若各自写 shape / 高度，改一处就会静默分叉。
     * `VaultixCorner.Pill` 至少 3 处（layer shape / shadow shape / Surface shape），
     * `DockRow(` 至少 3 处（定义 + 两条分支各一次调用）。
     */
    @Test
    fun `两种外观共用形状与行布局`() {
        val dock = dockSource()
        val pills = Regex("""VaultixCorner\.Pill""").findAll(dock).count()
        val rows = Regex("""\bDockRow\s*\(""").findAll(dock).count()

        assertTrue(
            "玻璃态与实色态必须共用规格：VaultixCorner.Pill 出现 $pills 次（应 ≥3）、" +
                "DockRow( 出现 $rows 次（应 ≥3）",
            pills >= 3 && rows >= 3,
        )
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **玻璃层不许写死高度。**
     *
     * #205 的整条底栏消失，根因是层 B 也写 `matchParentSize()` ⇒ 父 Box 高度塌 0。
     * 现在层 B 只 `fillMaxWidth()`，高度**由 content 测量**（2.0x 字号下才不会被裁字）。
     * 谁再加回 `.height(...)`，要么重现塌 0，要么让大字号下的 tab 文字被切掉。
     */
    @Test
    fun `玻璃层不许写死高度_尺寸必须由内容测量`() {
        val layer = functionBody(glassSource("LiquidGlass.kt"), "LiquidGlassLayer")
        assertTrue("LiquidGlassLayer 函数体没找到", layer.isNotEmpty())
        assertTrue(
            "玻璃层出现固定高度：层 B 只能 fillMaxWidth() + 内容测量，" +
                "写死 height 会重现 #205 或裁掉大字号 tab 文字",
            !layer.contains(".height("),
        )
    }

    // ------------------------------------------------------------------ 规则 4

    /**
     * **按压联动只准进绘制参数，不准碰测量。**
     *
     * 第四轮给玻璃加了「按住变厚、光源跟着手指」的联动。它只能是颜色 alpha /
     * shader uniform / 径向渐变中心，一旦有人把 press 接进 `size/height/width/padding`，
     * 底栏就会在按下瞬间**改变边界与大小** —— 正是用户特意提醒的那一项。
     */
    @Test
    fun `按压联动只进绘制参数_不许改变边界与大小`() {
        val sizeCall = Regex("""\.(size|height|width|padding|aspectRatio|heightIn|widthIn)\s*\(""")
        val offenders = listOf(
            "LiquidGlass.kt" to glassSource("LiquidGlass.kt"),
            "LiquidGlassSource.kt" to glassSource("LiquidGlassSource.kt"),
            "VaultixBottomDock.kt" to dockSource(),
        ).flatMap { (name, text) ->
            text.lineSequence()
                .filter { line -> line.contains("press", ignoreCase = true) || line.contains("focusX") }
                .filter { line -> sizeCall.containsMatchIn(line) }
                .map { line -> "$name → ${line.trim().take(100)}" }
        }.toList()

        assertTrue(
            "按压相关代码里出现尺寸调用（press 只能调绘制参数，不能改变底栏边界与大小）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 5

    /**
     * **饱和度增强必须覆盖所有有模糊的档位。**
     *
     * Haze vibrancy / PrismalAGSL saturation / LiquidGlassBar 1.5× 都做同一件事：
     * 只模糊会把彩色条目洗成中性灰，玻璃就成了「雾」。用 ColorFilter 串在模糊之后，
     * 31-32（`plain`）与 33+（AGSL 链的 inner）**都必须拿到同一份 saturated**。
     * 少接一处就会出现「同一台机器换主题 / 换版本玻璃不一样」。
     */
    @Test
    fun `饱和度链覆盖有模糊的所有档位`() {
        val optics = glassSource("LiquidGlassOptics.kt")
        val missing = buildList {
            if (!optics.contains("createColorFilterEffect")) add("没有 createColorFilterEffect")
            if (!optics.contains("LiquidGlass.SATURATION")) add("没有引用 SATURATION 常量")
            if (!optics.contains("plain = saturated.asComposeRenderEffect()")) {
                add("31-32 的 plain 没走饱和度")
            }
            if (!optics.contains("blur = saturated")) add("33+ 的 AGSL 内层没走饱和度")
        }

        assertTrue(
            "饱和度链不完整：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 6

    /**
     * **降级档（无真模糊）不叠镜面与暗线。**
     *
     * 降级时 `style.fill` 已经是全不透明实色，再画镜面 rim / 下沿暗线，
     * 等于给一块塑料板描两道边。旧版靠 `if (blurAvailable)` 挡住，
     * 重排成 `drawOutline` 之后这个闸门必须留在 `drawGlass` 里并**提前 return**。
     */
    @Test
    fun `降级档只画填充与内描边_不叠镜面装饰`() {
        val body = functionBody(glassSource("LiquidGlass.kt"), "drawGlass")
        assertTrue("drawGlass 函数体没找到", body.isNotEmpty())
        assertTrue(
            "降级档必须 `if (!blurAvailable)` 后提前 return，" +
                "否则实色底栏会被叠上镜面高光与 rim 暗线",
            Regex("""if\s*\(!blurAvailable\)\s*\{[^}]*return""").containsMatchIn(body),
        )
    }

    // ----------------------------------------------------------------- helpers

    /** 取一个顶层 / DrawScope 扩展函数的**函数体**（大括号配平，跳过字符串里的括号）。 */
    private fun functionBody(text: String, name: String): String {
        val start = Regex("""fun\s+(?:DrawScope\.)?""" + Regex.escape(name) + """\s*\(""")
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

    /**
     * 去掉行注释与块注释，**保留换行**（行号才不会错位）。
     *
     * 与 `DialogStyleRulesTest.stripComments` 同一套写法：KDoc 里常写
     * `` `drawRect(...)` `` 举反例，不剥掉会把说明文字当成真实调用。
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
