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
 * 对话框动作与尺寸的**四条可机检规范**（2026-11-10 建 1–3，#283 补 4，#284 反转 4）。
 *
 * 起因：用户报「设置 → 外观与自定义 → 主题模式的关闭按钮不符合标准」，
 * 顺带排查发现同一类问题散在 7 处对话框里。这些都是「编译过、detekt 查不到、
 * 真机上才看得出来」的观感缺陷，只能扫源文件文本钉住。
 *
 * ⚠️ **2026-11-12（#282）规则 1 已被用户最新反馈反转**：用户报「剪切板自动清除
 *    设置里面，取消按钮没有边框效果」，于是「即选即存的单选项对话框」退出按钮
 *    从「禁止 Outlined」改成「必须有边框」。旧判据作废理由写在规则 1 的 KDoc 里，
 *    历史结论留存在 `.ai/issues/06-界面与交互-part20.md`，别再引用旧说法。
 *
 * ⚠️ **2026-11-12（#284）规则 4 已被用户最新反馈反转**：用户第三次报「设置里
 *    好几个地方的取消按钮大小和形态和其他正常按钮都不一样」（主题模式 / 锁与安全 /
 *    自动锁定）。反编译 `material3-android:1.5.0-alpha16` 证明 #273 的
 *    「动作槽 intrinsic 测量」推断**不成立**（`AlertDialogFlowRow` = 纯 `FlowRow`，
 *    全部 Dialog 类里无 `IntrinsicSize`），而 #283 补的 `widthIn` 只是把
 *    `width(IntrinsicSize.Min)` 造成的**固定小方块**托到 64dp —— 症状没消失。
 *    ⇒ 规则 4 从「必须同时有 widthIn + IntrinsicSize.Min + 显式 padding」反转为
 *    「**禁止**任何固定宽度 / intrinsic modifier，只留宽度下限 + 显式 padding」。
 *    #282 的边框判据（规则 1）**不动**。
 *
 * ⚠️ 为什么用文本扫描而不是 Compose UI 测试：`AlertDialog` 是窗口级弹层，
 *    CI 无 SDK 时跑不了 instrumented test；而这几条全是**源码写法**问题。
 */
class DialogStyleRulesTest {

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val uiDir = File(repoRoot, "java/io/vaultix/vaultix/ui")

    // ------------------------------------------------------------------ 规则 1

    /**
     * **即选即存的单选项对话框，退出按钮必须有边框（#282 反转）。**
     *
     * 2026-11-10 定的旧规则是「单动作退出按钮一律低强调 TextButton」，依据是 M3
     * 对 dialog 动作槽的默认强调级别。2026-11-12 用户反馈「剪切板自动清除设置里面，
     * 取消按钮没有边框效果」—— 三个「点档位即生效」的单选对话框（主题模式 /
     * 剪贴板清除 / 自动锁定）共用一个无边框「取消」，真机上既看不出是可点按钮，
     * 也与同页其它有边框的对话框动作不一致。
     *
     * ⇒ 用户最新反馈优先：这三处的退出按钮统一改用 [DialogOutlinedActionButton]，
     *    旧规则 1 作废。判据用 `SingleChoiceRow` 圈定「即选即存的单选项对话框」，
     *    不靠文件名 / 行号，也不会误伤纯展示的 `LongTextInfoDialog`（无选项行）
     *    与双动作的自定义时长对话框（有 `dismissButton`）。
     *
     * ⚠️ 反转后仍是**有约束力的正向门禁**：谁把三兄弟改回无边框，这条立刻红。
     */
    @Test
    fun `单选项对话框的退出按钮必须有边框`() {
        val offenders = dialogBlocks().filter { (_, block) ->
            val single = block.contains("confirmButton") && !block.contains("dismissButton")
            val choiceList = block.contains("SingleChoiceRow(")
            val exit = Regex("""action_(close|cancel|done)""").containsMatchIn(block)
            single && choiceList && exit &&
                !block.contains("DialogOutlinedActionButton")
        }.map { it.first }

        assertTrue(
            "即选即存的单选项对话框（主题 / 剪贴板 / 自动锁定）退出按钮必须有边框：\n" +
                "请改用 DialogOutlinedActionButton（#282 用户反馈）：\n" +
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

    // ------------------------------------------------------------------ 规则 4

    /**
     * **对话框动作按钮必须回归原生 M3 wrap-content 规格（#284 反转 #283）。**
     *
     * 用户第三次反馈「设置里好几个地方的取消按钮大小和形态和其他正常按钮都不一样」。
     * 根因不是缺边框（#282 已补、规则 1 **不动**），而是 `DialogActionButtons.kt`
     * 里的 `width(IntrinsicSize.Min)`：**它把按钮宽度固定成 `minIntrinsicWidth`**
     * （中文按单字断行 ⇒ 只够一个字 + 内边距），#283 的 `widthIn(64dp)` 只是把这个
     * 小方块托到 64dp。59 个动作槽按钮因此全是同一个 64dp 方块，不随文案 / 字号
     * 伸缩，而 `DialogShell` 家族是 wrap-content 的原生 M3 按钮 ⇒ 两套规格并排。
     *
     * ⚠️ #273 当初加 intrinsic 的理由（M3 动作槽 `indentChar` + `IntrinsicSize.Max`
     *    测得 0 宽）经反编译证伪：本仓 `material3 1.5.0-alpha16` 的
     *    `AlertDialogFlowRow` 是**纯 `FlowRow`**，全部 Dialog 类无 `IntrinsicSize`，
     *    且 #273 提交时 BOM 版本与现在完全相同 ⇒ 不是版本变了，是推断从一开始就错。
     *
     * 四件事必须同时成立：
     * 1. **禁止** `IntrinsicSize` / 固定 `width(...)` / 固定 `height(...)`；
     * 2. `widthIn(min = ButtonDefaults.MinWidth)` 保留（纯下限，不固定宽度）；
     * 3. 两个组件都显式给各自的 M3 `contentPadding`（跟着 M3 换档，不写死数值）；
     * 4. 扫的是**共用组件源码**，不是逐个调用点 —— 全项目 59 处动作槽都走它。
     */
    @Test
    fun `对话框动作按钮禁止固定宽度只允许原生wrap-content规格`() {
        val source = stripComments(File(uiDir, "common/DialogActionButtons.kt").readText())
        val violations = buildList {
            if (source.contains("IntrinsicSize")) {
                add("出现 IntrinsicSize：#273 的 intrinsic 修法已被反编译证伪，" +
                    "它把按钮钉成固定 minIntrinsicWidth（#284 修掉的 64dp 小方块）")
            }
            if (Regex("""\bwidth\s*\(""").containsMatchIn(source)) {
                add("出现固定 width(...)：按钮必须 wrap-content，随文案 / 字号伸缩")
            }
            if (Regex("""\bheight\s*\(""").containsMatchIn(source)) {
                add("出现固定 height(...)：M3 按钮高度由 ButtonDefaults 决定")
            }
            if (!source.contains("widthIn(min = ButtonDefaults.MinWidth)")) {
                add("丢了 M3 标准宽度下限 widthIn(min = ButtonDefaults.MinWidth)")
            }
            if (!source.contains("contentPadding = ButtonDefaults.ContentPadding")) {
                add("OutlinedButton 没显式声明 M3 内边距")
            }
            if (!source.contains("contentPadding = ButtonDefaults.TextButtonContentPadding")) {
                add("TextButton 没显式声明 M3 内边距")
            }
        }
        assertTrue("对话框按钮 M3 规格：\n" + violations.joinToString("\n"), violations.isEmpty())
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
