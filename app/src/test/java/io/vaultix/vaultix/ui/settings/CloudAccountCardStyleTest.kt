/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「网盘账号」卡片的三条**可机检规范**（2026-11-12 #285 第 1 项）。
 *
 * 用户反馈「网盘卡片大小、颜色、UI 要更精美好看、与页面和谐一致」。真机截图上是三个
 * 纯观感缺陷，编译过、detekt 查不出、CI 全绿、只有真机才看得出来 ⇒ 与
 * `AddActionSheetStyleTest` / `ItemsRowStyleTest` 同口径，扫源码把写法钉住：
 *
 * 1. 卡片过高：套了设置页「**一组多行**」的 `SettingsGroupCard` + `SettingsRow`
 *    （72dp 行 + 20dp 行内距 + 一整行按钮），一个账号占掉一大块空板；
 * 2. 「已连接」飘在**卡片外**单独一行，读起来像另一段正文；
 * 3. 主操作是 48dp 满宽 `PrimaryActionButton` 胶囊，「注销」却是 40dp `OutlinedButton`，
 *    中间还被 `Spacer(weight(1f))` 推到屏幕两端，两枚按钮一大一小、一重一轻。
 *
 * ⇒ 判据：一个账号 = 一张自洽紧凑卡（沿用 `surfaceContainerLow` + `VaultixCorner.Panel`
 *   的设置页表面语言），状态收成卡内行尾徽标，动作区两枚同规格 M3 按钮。
 *
 * ⚠️ 色值 / 对比度由 `VaultixBrandColorTest` 管；硬编码中文与资源存在性由
 *    `SettingsStyleRulesTest` 规则 3/4 管。本文件只钉**结构接线**。
 */
class CloudAccountCardStyleTest {

    private val mainRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private val source: String = stripComments(
        File(mainRoot, "java/io/vaultix/vaultix/ui/settings/CloudAccountsScreen.kt").readText(),
    )

    // ------------------------------------------------------------------ 规则 1

    /** **一个账号 = 一张自洽紧凑卡**：外壳沿用设置页色阶，表头 / 状态 / 动作全在卡内。 */
    @Test
    fun `账号卡自洽且外壳沿用设置页表面语言`() {
        val card = functionBody(source, "CloudAccountCard")
        val header = functionBody(source, "AccountCardHeader")
        val missing = buildList {
            if (card.isEmpty()) add("找不到 CloudAccountCard 函数体")
            if (!card.contains("surfaceContainerLow")) {
                add("卡片没用 surfaceContainerLow（与设置页色阶不一致，会浮出怪色块）")
            }
            if (!card.contains("VaultixCorner.Panel")) add("卡片没用 Panel 圆角")
            if (card.contains("SettingsGroupCard") || card.contains("SettingsRow(")) {
                add("又套回设置页「一组多行」的壳（72dp 行 + 20dp 内距 ⇒ 卡片过高）")
            }
            if (!card.contains("AccountCardHeader(")) add("表头没抽进卡内")
            if (!card.contains("AccountCardActions(")) add("动作区没抽进卡内")
            if (!card.contains("AccountIssueLine(")) add("断开原因没收进卡内")
            if (!header.contains("ConnectionBadge(")) add("连接状态没做成卡内行尾徽标")
            if (card.contains("cloud_accounts_connected") ||
                card.contains("cloud_accounts_disconnected")
            ) {
                add("连接状态又散到卡片主体里（应只在 ConnectionBadge 内，否则飘到卡外）")
            }
        }
        assertTrue("账号卡自洽结构：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **动作区两枚同规格，且不再被 `weight(1f)` 推到两端。**
     *
     * 主次靠 `FilledTonal` / `Outlined` 的填充区分，不靠尺寸。WebDAV 已连接时
     * `else -> Spacer(mainModifier)` 是「没有主操作」的占位，不算推开 —— 只精确
     * 点名 `Spacer(Modifier.weight(1f))` 这一种写法。
     */
    @Test
    fun `动作区两枚同规格且不再被weight推开`() {
        val actions = functionBody(source, "AccountCardActions")
        val missing = buildList {
            if (actions.isEmpty()) add("找不到 AccountCardActions 函数体")
            if (!actions.contains("FilledTonalButton(")) add("主操作没用 FilledTonalButton")
            if (!actions.contains("OutlinedButton(")) add("破坏性没用 OutlinedButton")
            if (actions.contains("PrimaryActionButton")) {
                add("又用了 48dp 满宽主 CTA（与 40dp outlined 不同规格）")
            }
            if (actions.contains("Spacer(Modifier.weight(1f))")) {
                add("又用 Spacer(weight(1f)) 把两枚按钮推到屏幕两端")
            }
            if (!actions.contains("ButtonDefaults.outlinedButtonColors")) {
                add("移除按钮没统一 error 内容色（只给 Text 上色会漏掉边框）")
            }
            if (!actions.contains("AccountActionContent(")) add("主操作进行中没有可见反馈")
        }
        assertTrue("账号卡动作区规格：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 3

    /** **长账号名单行省略、状态徽标配对前景、全宽按钮与卡片同款圆角。** */
    @Test
    fun `账号名单行省略且徽标与按钮形状统一`() {
        val header = functionBody(source, "AccountCardHeader")
        val badge = functionBody(source, "ConnectionBadge")
        val missing = buildList {
            if (!header.contains("maxLines = 1")) add("账号名没限单行（长邮箱会撑破卡）")
            if (!header.contains("TextOverflow.Ellipsis")) add("账号名没省略号")
            if (!badge.contains("onSecondaryContainer") || !badge.contains("onErrorContainer")) {
                add("状态徽标没用 on-container 配对前景")
            }
            if (!badge.contains("VaultixCorner.Pill")) add("状态徽标没用 Pill 形状")
            val panelShapes = Regex("""shape = VaultixCorner\.Panel""").findAll(source).count()
            if (panelShapes < 3) {
                add("卡片 / 添加账号 / 表单取消没统一 Panel 圆角（实得 $panelShapes 处）")
            }
        }
        assertTrue("账号卡排版细节：\n" + missing.joinToString("\n"), missing.isEmpty())
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

    /**
     * 去掉行注释与块注释、保留换行。KDoc 里会复述被淘汰的旧写法
     * （如 `SettingsGroupCard` / `PrimaryActionButton`），不剥掉会把说明当成真实调用。
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
