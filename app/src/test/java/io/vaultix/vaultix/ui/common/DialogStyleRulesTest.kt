/*
 * Vaultix — app:test · common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话框动作与尺寸的**三条可机检规范**（2026-11-10）。
 *
 * 起因：用户报「设置 → 外观与自定义 → 主题模式的关闭按钮不符合标准」，
 * 顺带排查发现同一类问题散在 7 处对话框里。这些都是「编译过、detekt 查不到、
 * 真机上才看得出来」的观感缺陷，只能扫源文件文本钉住。
 *
 * ⚠️ 为什么用文本扫描而不是 Compose UI 测试：`AlertDialog` 是窗口级弹层，
 *    CI 无 SDK 时跑不了 instrumented test；而这几条全是**源码写法**问题。
 */
class DialogStyleRulesTest {

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val uiDir = File(repoRoot, "java/io/vaultix/vaultix/ui")

    // ------------------------------------------------------------------ 规则 1

    /**
     * **单个退出动作的对话框，必须用低强调 TextButton。**
     *
     * M3 给 dialog 动作槽传的是 `ActionLabelTextColor` / `ActionLabelTextFont`，
     * 默认强调级别就是 TextButton（`DialogTextButton` 的 KDoc 写得很清楚）。
     * `DialogOutlinedActionButton` 是页面级动作，放进「关闭 / 取消 / 完成」这种
     * 退出动作上会多一圈描边，读起来像「确定」—— 用户报的就是这个。
     *
     * 判据：只有 `confirmButton`、没有 `dismissButton`（＝单动作），
     * 且文案是退出类 ⇒ 不许出现 Outlined 封装。
     */
    @Test
    fun `单动作对话框的退出按钮不许用 Outlined 强调`() {
        val offenders = dialogBlocks().filter { (_, block) ->
            val single = block.contains("confirmButton") && !block.contains("dismissButton")
            val exit = Regex("""action_(close|cancel|done)""").containsMatchIn(block)
            single && exit && block.contains("DialogOutlinedActionButton")
        }.map { it.first }

        assertTrue(
            "这些对话框只有「关闭 / 取消 / 完成」一个动作，却用了中强调的 " +
                "DialogOutlinedActionButton（应改为 DialogTextButton）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **不许留空动作槽。**
     *
     * `dismissButton = { }` 曾经被当作「我不想放按钮」的占位。M3 的 `confirmButton`
     * 是**必需参数**（只给 dismissButton 会解析到 `content` 重载而编译失败），
     * 于是有人反过来塞一个空槽。空 composable 不报错也不显示，只是纯噪音，
     * 而且会让人误以为这里"本来该有个按钮"。
     */
    @Test
    fun `对话框不许留空动作槽`() {
        val offenders = dialogBlocks().filter { (_, block) ->
            Regex("""(confirmButton|dismissButton)\s*=\s*\{\s*\}""").containsMatchIn(block)
        }.map { it.first }

        assertTrue(
            "对话框里出现空的动作槽占位：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **弹窗里的滚动列表不许写死 `height(N.dp)`。**
     *
     * `LoginPickerDialog` 曾给候选列表写 `.height(240.dp)`：只有 1~2 个候选时
     * 弹窗底部仍撑着一大块黑空白，用户看到的就是「弹窗大小不对」。
     * 应该是 `heightIn(max = …)` —— 内容少就收缩，内容多才滚动。
     *
     * ⚠️ `Spacer(Modifier.height(...))` 是合法的固定间距，不在禁止之列。
     */
    @Test
    fun `弹窗里的列表高度只允许 heightIn 上限`() {
        val offenders = mutableListOf<String>()
        dialogBlocks().forEach { (where, block) ->
            block.lineSequence().forEach { line ->
                if (line.contains(".height(") && !line.trimStart().startsWith("Spacer(")) {
                    offenders += "$where → ${line.trim().take(90)}"
                }
            }
        }

        assertTrue(
            "弹窗里出现固定 height 的容器（应改为 heightIn(max = …)）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ----------------------------------------------------------------- helpers

    /** 收集所有 `AlertDialog(` / `BasicAlertDialog(` 调用块（括号配平，跳过字符串里的括号）。 */
    private fun dialogBlocks(): List<Pair<String, String>> =
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> blocksInFile(file) }
            .toList()

    /** 单文件：先剥注释，再逐个调用做括号配平。 */
    private fun blocksInFile(file: File): Sequence<Pair<String, String>> {
        // ⚠️ 必须先剥注释：KDoc 里常写 `` `AlertDialog(text = { … })` `` 作示例，
        //    不剥掉会把整段说明文字当成一次调用，括号配平吞掉后面的真实代码。
        val text = stripComments(file.readText())
        return Regex("""\b(?:Basic)?AlertDialog\s*\(""").findAll(text).map { match ->
            val close = balancedCallEnd(text, match.range.last + 1)
            val line = text.take(match.range.first).count { it == '\n' } + 1
            "${file.name}:$line" to text.substring(match.range.first, close)
        }
    }

    /** 从 `(` 之后开始配平，返回右括号**之后**的下标。 */
    private fun balancedCallEnd(text: String, start: Int): Int {
        var depth = 1
        var i = start
        var inString = false
        while (i < text.length && depth > 0) {
            val c = text[i]
            if (c == '"' && (i == 0 || text[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                if (c == '(') depth++
                if (c == ')') depth--
            }
            i++
        }
        return i
    }

    /**
     * 去掉行注释与块注释，**保留换行**（行号才不会错位）。
     *
     * 与 `SettingsStyleRulesTest.stripComments` 同一套写法：不追求完整词法分析，
     * 只要保证 KDoc 里的示例代码不参与扫描。拆成小函数是为了过 detekt
     * `CyclomaticComplexMethod ≤14`。
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
