/*
 * Vaultix — app:test · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页「按钮 / 开关 / 弹窗 / 提示」四条**可机检**的约束（2026-11-10）。
 *
 * 为什么这些值得写成单测：四条全都是"肉眼看不出问题、真机上才露馅"的观感缺陷 ——
 * 而且其中三条正是本次用户报告的症状（「通知」竖排、「取消」两个字摞起来）。
 * 靠人复查必然漏，靠 `detekt` 查不出（它不是代码异味），只能钉成断言。
 *
 * ⚠️ 本测试直接读**源文件文本**（不是编译产物）：
 * - `androidx.compose.ui.res.stringResource` 对**不存在的资源 id** 会原样返回字符串
 *   `"ResString(id=0x…)"`，**不抛异常、不崩溃** —— 所以「硬编码中文」与「引用不存在的
 *   字符串」在运行期都只是"界面上一段怪字样"，不会有人报 crash；
 * - 因此这一类只能静态查，且查的是 `res/values/strings.xml` 里**真的有没有**。
 */
class SettingsStyleRulesTest {

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private val settingsDir = File(repoRoot, "java/io/vaultix/vaultix/ui/settings")
    private val uiDir = File(repoRoot, "java/io/vaultix/vaultix/ui")
    private val stringsFile = File(repoRoot, "res/values/strings.xml")

    // ------------------------------------------------------------------ 规则 1

    /**
     * **行的副标题最多两行**（`SETTINGS_SUBTITLE_MAX_LINES = 2`，见 `SettingsComponents`）。
     *
     * 这次不是"超长被截断"这么简单：权限页那四行是**自绘的**（`PermissionRow` 没有
     * `maxLines`），副标题不限行数。于是「指纹 / 设备密码」这种较短的一行拿到
     * 112dp 的行尾框后，中间只剩约 185dp —— 而它的行尾此时是「设备不支持」5 个字，
     * 同一行的标题于是被挤成「指纹」/「设备」/「密码」四行，看上去就是竖排。
     *
     * 
     */
    @Test
    fun `权限页副标题必须自己限行数`() {
        val src = File(repoRoot, "java/io/vaultix/vaultix/ui/permissions/PermissionsScreen.kt")
        assertTrue("权限页文件不存在：$src", src.isFile)
        val text = src.readText()

        // 用途说明那段 Text 必须带 maxLines —— 它是本页唯一不受共享行组件约束的长文本。
        val usageBlock = text.substringAfter("text = stringResource(entry.purposeRes)")
            .substringBefore("PermissionTrailing(")
        assertTrue(
            "PermissionsScreen 的副标题缺少 maxLines：超过两行时会把标题挤成竖排",
            usageBlock.contains("maxLines") && usageBlock.contains("TextOverflow"),
        )
    }

    @Test
    fun `权限页标题不与行尾挤在同一行`() {
        val src = File(repoRoot, "java/io/vaultix/vaultix/ui/permissions/PermissionsScreen.kt")
        val text = src.readText()
        // 标题行必须是「标题 weight(1f) + 行尾」的两列布局，标题自身要有 weight。
        val titleBlock = text.substringAfter("text = stringResource(entry.titleRes)")
            .substringBefore("PermissionTrailing(")
        assertTrue(
            "权限页标题缺少 weight(1f)：行尾宽度一变大就会把标题压成竖排",
            titleBlock.contains("weight(1f)"),
        )
    }

    /**
     * **行尾不许写死最小宽度** —— 2026-11-10「通知」二字竖排的真凶。
     *
     * 一行里放不下时，Compose 先压缩 `weight(1f)` 的标题列，而不是撑宽卡片。
     * 行尾固定 112dp 时，一个 2 字的标题只剩 1 个字的宽度 ⇒ 一个字一行。
     * 判据：`TrailingSlot` 外框里出现 `widthIn(min =` 就报。
     */
    @Test
    fun `权限页行尾不许固定最小宽度`() {
        val src = File(repoRoot, "java/io/vaultix/vaultix/ui/permissions/PermissionsScreen.kt")
        val text = src.readText()
        // 只判**函数体**里的 widthIn：KDoc 里正解释"为什么删掉 widthIn(min = …)"，
        // 那行文字不能被当成违规。
        val signature = text.indexOf("private fun TrailingSlot")
        assertTrue("找不到 TrailingSlot", signature >= 0)
        val brace = text.indexOf('{', text.indexOf(')', text.indexOf(')', signature) + 1))
        val body = stripComments(balancedBlock(text, brace))
        assertTrue(
            "TrailingSlot 又写死了最小宽度：行尾是硬约束、标题是软约束，" +
                "固定宽度会把短标题挤成竖排（见该函数 KDoc 的宽度账）",
            !body.contains("widthIn(min"),
        )
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **对话框动作按钮不要裸用** —— 必须走 `DialogTextButton` / `DialogOutlinedActionButton`。
     *
     * ⚠️ **2026-11-12 #284 勘误**：下面 #273 那段「`indentChar` + `IntrinsicSize.Max`
     *    测得 0 宽」的归因**已被反编译证伪** —— 本仓 `material3 1.5.0-alpha16` 的
     *    `AlertDialogFlowRow` 是纯 `FlowRow`、全部 Dialog 类无 `IntrinsicSize`，且
     *    #273 提交时 BOM 与现在完全相同（不是版本变了）。
     *    **规则本身仍然有效**，理由换成：动作槽必须走同一个共用组件，规格（宽度
     *    下限 / M3 内边距 / #282 边框判据）才只有一处真源；裸按钮各自拿 M3 默认值，
     *    共用组件一按用户反馈调整（#282 → #283 → #284）这些点就会悄悄掉队，
     *    重新出现「和其它按钮不一样」。
     *
     * 历史（原文保留，仅用于追溯当时怎么想歪的）：曾认为裸按钮放进动作槽会继承
     * `indentChar`（"horizontal"）并参与 `width(IntrinsicSize.Max)` 测量，`Row` 不实现
     * intrinsic ⇒ 测得 0 ⇒ 被 `widthIn(58dp)` 兜住 ⇒ 两字各占 29dp 逐字竖排。
     * 现象出现过，但归因不成立；那个"修复" `width(IntrinsicSize.Min)` 本身在 #284
     * 被移除 —— 它把 59 个按钮全钉成固定 64dp 小方块，正是用户第三次反馈的点。
     *
     * 扫描守住：凡是在 `confirmButton` / `dismissButton` 里直接写
     * `TextButton(` / `OutlinedButton(` 的，一律报出来。
     */
    @Test
    fun `对话框动作槽里不许直接写裸按钮`() {
        val offenders = mutableListOf<String>()
        val slotStart = Regex("""\b(confirmButton|dismissButton)\s*=\s*\{""")

        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // 定义这两个封装的文件自身当然会用到裸按钮。
            .filterNot { it.name == "DialogActionButtons.kt" || it.name == "DialogShell.kt" }
            .forEach { file ->
                val text = file.readText()
                slotStart.findAll(text).forEach { match ->
                    // 取从槽位开始、到括号配平为止的那一段。
                    val body = balancedBlock(text, match.range.last)
                    val naked = Regex("""(?<![\w.])(TextButton|OutlinedButton|Button)\s*\(""")
                        .find(body)
                    if (naked != null) {
                        val line = text.take(match.range.first).count { it == '\n' } + 1
                        offenders += "${file.path}:$line → 裸 ${naked.groupValues[1]}"
                    }
                }
            }

        assertTrue(
            "这些对话框动作按钮没走 DialogTextButton / DialogOutlinedActionButton，" +
                "会脱离共用组件的 M3 规格（#284）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **设置页里不许出现硬编码中文**（必须 `stringResource`）。
     *
     * `stringResource` 只在真正调用时才读资源，写成字面量一样编译通过、真机一样显示 ——
     * 但那样的文案进不了翻译流程，也无法被 `check_orphan_strings.py` 统计。
     * 判据：`Text("…中文…")` / `title = "…中文…"` 这类**引号里含汉字**的字符串字面量。
     * 注释与 KDoc 不计（先剥掉行注释与块注释）。
     */
    @Test
    fun `设置页的界面文案不许硬编码中文`() {
        // 不可翻译的日志文本：`Log.w` / `logcat` / 导出给开发者看的纯文本。
        // 这几处只在 Logcat 与调试导出里出现，进翻译表反而是噪音。
        val nonUiFiles = setOf("LogScrubber.kt")
        val nonUiCallSites = listOf(
            "Log.w(", "Log.d(", "Log.e(", "TAG", "buildExportText",
            // #291：日志统一收敛到 VaultixLog 门面后，设置页的中文日志文案不再带 `Log.` 前缀。
            // 不加这个标记，`VaultixLog.w(TAG) { "…中文…" }` 会被误判成硬编码界面文案。
            "VaultixLog.",
        )

        val cjk = Regex("[\u4e00-\u9fff]")
        val quoted = Regex("\"([^\"\\\\]|\\\\.)*\"")
        val offenders = mutableListOf<String>()

        settingsDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name in nonUiFiles }
            .forEach { file ->
                val code = stripComments(file.readText())
                quoted.findAll(code).forEach { m ->
                    if (!cjk.containsMatchIn(m.value)) return@forEach
                    val line = code.take(m.range.first).count { it == '\n' } + 1
                    // ⚠️ 判"是不是日志"必须看**整个调用块**，不能只看这一行：
                    //   `Log.w(TAG, "第一句" + "第二句")` 的多行写法里，后半句所在的
                    //   那一行看不到 `Log.w`，按行判会漏。
                    val isLog = nonUiCallSites.any { marker ->
                        code.lastIndexOf(marker, m.range.first) >
                            code.lastIndexOf('}', m.range.first)
                    }
                    if (!isLog) offenders += "${file.name}:$line → ${m.value.take(40)}"
                }
            }

        assertTrue(
            "设置页出现硬编码中文（应改为 stringResource）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 4

    /**
     * **`stringResource` 引用的字符串 id 必须真的存在**。
     *
     * 见类 KDoc：引用不存在的资源**不会崩**，只会在界面上显示 `"ResString(id=0x…)"`，
     * 而 Android Studio 的检查在 CI（无 SDK）里跑不到。这条静态扫描补上这一格。
     */
    @Test
    fun `引用到的字符串资源都存在`() {
        assertTrue("找不到 strings.xml：$stringsFile", stringsFile.isFile)
        val declared = Regex("""<string name="([^"]+)"""")
            .findAll(stringsFile.readText())
            .map { it.groupValues[1] }
            .toSet()

        val missing = mutableListOf<String>()
        settingsDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val code = stripComments(file.readText())
                Regex("""R\.string\.([A-Za-z0-9_]+)""").findAll(code).forEach { m ->
                    val name = m.groupValues[1]
                    if (name !in declared) {
                        val line = code.take(m.range.first).count { it == '\n' } + 1
                        missing += "${file.name}:$line → $name"
                    }
                }
            }

        assertTrue(
            "引用了不存在的字符串资源（运行期只会显示 ResString(id=0x…)）：\n" +
                missing.distinct().joinToString("\n"),
            missing.isEmpty(),
        )
    }

    // ----------------------------------------------------------------- helpers

    /** 从 `open`（指向 `{`）开始，返回括号配平的那一段（含花括号）。 */
    private fun balancedBlock(text: String, open: Int): String {
        var depth = 0
        var i = open
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                // 跳过字符串字面量，避免把里面的花括号算进配对。
                c == '\\' && inString -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        return text.substring(open)
    }

    /**
     * 去掉行注释与块注释（保留换行，行号才不会错位）。
     *
     * ⚠️ 不追求完美的词法分析：KDoc 里出现引号中文是**正常**的（注释不是界面文案），
     * 所以必须先剥注释再判字面量，否则满屏误报。
     *
     * 拆成三个小函数是为了过 detekt `CyclomaticComplexMethod`（≤14）—— 单函数
     * 把"字符串态 / 行注释 / 块注释 / 转义"四种情形全写进一个 when 会有 20 的分支数。
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

    /** 原样复制一个字符串字面量（含转义对），返回它之后的下标。 */
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

    /** 跳过 `//` 到行尾，补一个换行保住行号。 */
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
