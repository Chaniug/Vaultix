/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.items

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目列表「M3 质感」的**五条可机检规范**（2026-11-12 第八轮 / #282 第 5 项）。
 *
 * 用户反馈：「按域名显示之类的分组还不错，但是 UI 之类的可以稍微做的好看一点吗……
 * 参考一下 material3 的设计风格，**在不增加太多性能开销的情况下**，提升条目的质感，
 * 在外观和字体字号，显示效果，条目上的同步，验证码，通行密钥之类的小点缀上下点功夫」。
 *
 * 与 `LiquidGlassDockRulesTest` 同理：这些全是「编译过、detekt 查不到、真机才看得出来」
 * 的写法问题 —— 副标题截断、列表/详情两页一致性、点缀是否成组、有没有每行开销。
 * CI 没有真机，也跑不了 instrumented test，只能扫源码把写法钉住。
 *
 * ⚠️ 本轮刻意**不动**的运行时判据（云状态三态、按域名分组）由
 *    `CloudSyncBadgeTest` / `ItemsGroupingSiteTest` 负责；本文件只钉「显示层怎么写」。
 */
class ItemsRowStyleTest {

    private val mainRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val itemsDir = File(mainRoot, "java/io/vaultix/vaultix/ui/items")

    private fun itemsSource(): String =
        stripComments(File(itemsDir, "ItemsScreen.kt").readText())

    // ------------------------------------------------------------------ 规则 1

    /**
     * **副标题必须恒定一行，且类型徽标与详情页同款。**
     *
     * 长用户名不截断会把卡片撑成两行（一屏条目数跟着变）；标题的 `maxLines = 1` 是**本轮
     * 才补齐的行为变更**（见规则 5），此前密码页会折行，不是「早就如此」；
     * 类型此前是 `primary` 纯文字，与详情页 `ItemDetailScreen` 的
     * `TypeBadge(..., BadgeTone.NEUTRAL)` 不是一套语言 —— 8.4 点名的「同一规格在不同页
     * 长得不一样」。同时钉住「不许靠放大字号 / 加粗字重来提质感」（项目 8.8 §5）。
     */
    @Test
    fun `副标题恒定一行且类型徽标与详情页一致`() {
        val body = functionBody(itemsSource(), "ItemRowSubtitle")
        assertTrue("ItemRowSubtitle 函数体没找到", body.isNotEmpty())
        val textCalls = Regex("""\bText\s*\(""").findAll(body).count()
        val singleLine = Regex("""maxLines\s*=\s*1""").findAll(body).count()
        val missing = buildList {
            if (textCalls != 2) add("用户名 Text 应 2 处，实际 $textCalls")
            if (singleLine != textCalls) {
                add("每个 Text 都要 maxLines = 1，实际 $singleLine / $textCalls")
            }
            if (!body.contains("overflow = TextOverflow.Ellipsis")) add("缺 Ellipsis")
            if (!body.contains("TypeBadge(")) add("类型没走 TypeBadge（列表与详情会分叉）")
            if (!body.contains("BadgeTone.NEUTRAL")) add("类型徽标 tone 与详情页不一致")
            if (body.contains("fontSize")) add("不许改字号")
            if (body.contains("fontWeight")) add("不许改字重")
            if (body.contains("Brush.")) add("不许引入渐变（每行绘制开销）")
        }
        assertTrue("副标题规范：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **行尾点缀整组一个 tonal 底，不是每枚图标各包一个。**
     *
     * 用户点名「同步 / 验证码 / 通行密钥之类的小点缀」。做法必须守住「不增加太多
     * 性能开销」：整组一个 `Modifier.background` = 一次绘制、零新增 composable 层级、
     * 零状态、零动画；四枚圆点各包一层既吵，也违背 `CapabilityIcon` 的克制结论。
     *
     * 「没有点缀时早退」同样重要：本地 KDBX 的普通条目不该每行多一颗空灰点。
     * 三个共享组件（详情页 / 库列表也在用）不许被就地替换掉。
     */
    @Test
    fun `行尾点缀整组一个底且无点缀时早退`() {
        val body = functionBody(itemsSource(), "ItemRowTrailing")
        assertTrue("ItemRowTrailing 函数体没找到", body.isNotEmpty())
        val backgrounds = Regex("""\.background\s*\(""").findAll(body).count()
        val missing = buildList {
            if (backgrounds != 1) {
                add("应只有 1 个容器底（整组），实际 $backgrounds —— 每图标各包一个会加噪与开销")
            }
            if (!body.contains("CircleShape")) add("容器不是胶囊")
            if (!Regex("""if\s*\(!hasRowTrailing\(item, cloud\)\)\s*return""")
                    .containsMatchIn(body)
            ) {
                add("没有「无点缀 ⇒ 不画胶囊」的早退")
            }
            listOf("CloudSyncIcon(", "VaultSourceCloudIcon(", "CapabilityIcon(").forEach { call ->
                if (!body.contains(call)) add("共享组件 $call 被换掉了（详情页会跟着变）")
            }
            listOf("fontSize", "fontWeight", "animate", "derivedStateOf", "Brush.", "graphicsLayer")
                .forEach { token ->
                    if (body.contains(token)) add("行尾不许出现 $token（每行开销 / 放大字号）")
                }
        }
        assertTrue("行尾点缀规范：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **分组头成块，计数必须低调、不许有可点击暗示（#283 反转）。**
     *
     * #282 曾把计数收进 `tertiaryContainer` + `CircleShape` 小胶囊，理由是「与类型徽标
     * 同一套标记语言」。#283 用户反馈「这个数字的 UI 很不好看，给人一种要点击的感觉，
     * 不要这么显眼」—— 高对比彩色圆点在 M3 里读起来就是 chip / badge，会制造第二个
     * 假目标（整条 Row 才是唯一可点区域）。⇒ **反转判据**：计数只许是
     * `onSurfaceVariant` 的纯文字，不许有容器底 / 形状 / 内边距。
     *
     * 整条分组头的低档容器底（恰好 1 个 `.background(`）保留：它与下面有描边的卡片
     * 分层，是「分节」而不是「一行飘着的字」，这一条 #283 没有推翻。
     * ⚠️ 标题的 `weight(1f)` 右对齐、`stateDescription` 读屏态、不放大字号字重，
     *    这些既有约束继续钉住。
     */
    @Test
    fun `分组头成块且计数低调无点击暗示`() {
        val body = functionBody(itemsSource(), "GroupHeader")
        assertTrue("GroupHeader 函数体没找到", body.isNotEmpty())
        // 「count.toString()」之后就是计数 Text 的剩余参数与函数收尾，足够单独判定
        // 计数有没有被套进 chip 外壳。
        val countBlock = body.substringAfter("count.toString()", "")
        val missing = buildList {
            if (Regex("""\.background\s*\(""").findAll(body).count() != 1) {
                add("分组头应只有整条 1 个容器底（计数不许再有底）")
            }
            if (countBlock.contains("CircleShape") || countBlock.contains(".background(")) {
                add("计数仍是胶囊 / 带底（#283 已作废，会读作可点击 chip）")
            }
            if (countBlock.contains(".padding(")) add("计数仍带内边距，会读成 chip")
            if (body.contains("tertiaryContainer")) add("计数仍用 tertiary 高对比色")
            if (!body.contains("colorScheme.onSurfaceVariant")) add("计数没降到次要文字色")
            if (!body.contains("colorScheme.onSurface")) add("标题没降到正文色")
            if (!body.contains("weight(1f)")) add("标题没占权重，计数不会右对齐")
            if (!body.contains("stateDescription")) add("读屏展开态丢了")
            if (body.contains("fontSize") || body.contains("fontWeight")) add("不许放大字号/加粗")
        }
        assertTrue("分组头规范：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 4

    /**
     * **美化不许改判据。**
     *
     * #282 只动「怎么画」：`ItemRowSubtitle` 的三种 `displayMode` 分支、
     * `hasRowTrailing` 的四项判据都属于 #278 之后钉过的逻辑，不许在美化里被顺手合并
     * （例如只判 `cloud != null` ⇒ 收藏 / TOTP 条目会行尾空白却仍画胶囊，或反之）。
     *
     * ⚠️ 云状态三态与分组逻辑的运行时行为另有 `CloudSyncBadgeTest` /
     *    `ItemsGroupingSiteTest`，本条不重复钉，避免两处规则漂移。
     */
    @Test
    fun `美化不改显示偏好与点缀判据`() {
        val source = itemsSource()
        // `hasRowTrailing` 是表达式体函数（没有 `{`），不能用 functionBody 取，
        // 直接截到下一个 private fun 之前 —— 它后面紧跟的就是 ItemRowTrailing 的 KDoc。
        val trailing = source
            .substringAfter("private fun hasRowTrailing", "")
            .substringBefore("private fun ItemRowTrailing", "")
        val subtitle = functionBody(source, "ItemRowSubtitle")
        val missing = buildList {
            listOf(
                "cloud != null",
                "item.favorite",
                "item.totp.isNullOrBlank()",
                "fido2Credentials",
            ).forEach { token ->
                if (!trailing.contains(token)) add("hasRowTrailing 漏了 $token")
            }
            if (!subtitle.contains("ItemsCardDisplayMode.All")) add("All 分支丢了")
            if (!subtitle.contains("ItemsCardDisplayMode.TitleUsername")) add("TitleUsername 分支丢了")
            if (!subtitle.contains("VaultItemType.Login")) add("Login 判断丢了")
        }
        assertTrue("判据被改动：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 5

    /**
     * **标题恒定一行 —— #282 唯一的【显示行为变更】，必须单列一条钉住。**
     *
     * 密码页 `ItemRow` 此前标题会折行，而 TOTP / 卡包 / 通行密钥三个列表共用的
     * `EntryCard` 早已 `maxLines = 1`（见 `EntryCard.kt` 的 `EntryCardTitleLineHeight`
     * KDoc）—— 密码页是唯一例外。8.4 点名的「同一规格在不同页长得不一样」正是这类
     * 廉价感来源，故本轮把漏掉的这一处补齐：长标题由折行改为 Ellipsis 截断，
     * 并用统一行高收窄文本块。
     *
     * ⚠️ 这不是纯美化：若哪天要恢复折行，必须四个列表一起改，别只把这里删回去。
     *    规则 1 的 KDoc 曾误写「标题早就 maxLines = 1」，与代码注释矛盾，已订正。
     */
    @Test
    fun `标题恒定一行且四页规格一致`() {
        val body = functionBody(itemsSource(), "ItemRow")
        assertTrue("ItemRow 函数体没找到", body.isNotEmpty())
        val missing = buildList {
            if (!body.contains("style = MaterialTheme.typography.titleMedium")) {
                add("标题字号被改（8.8 §5：不靠放大字号提质感）")
            }
            if (!body.contains("maxLines = 1")) {
                add("标题丢了 maxLines = 1 —— 会退回折行，与其余三个列表分叉")
            }
            if (!body.contains("TextOverflow.Ellipsis")) add("标题缺 Ellipsis")
            if (!body.contains("lineHeight = EntryCardTitleLineHeight")) {
                add("标题没用统一行高（默认 24sp 余量会把文本块往下推）")
            }
            if (body.contains("fontSize")) add("不许改字号")
        }
        assertTrue("标题规范：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ----------------------------------------------------------------- helpers

    /** 取顶层函数的**函数体**（大括号配平，跳过字符串里的括号）。 */
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
     * 去掉行注释与块注释，**保留换行**。
     *
     * 与 `LiquidGlassDockRulesTest.stripComments` 同一套写法：KDoc 里常写反例，
     * 不剥掉会把说明文字当成真实调用。本文件扫描的三个函数体里没有含注释符的
     * 字符串字面量，故省掉「字符串字面量原样复制」那一步。
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                text.startsWith("/*", i) -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) {
                        if (text[i] == '\n') out.append('\n')
                        i++
                    }
                    i += 2
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
