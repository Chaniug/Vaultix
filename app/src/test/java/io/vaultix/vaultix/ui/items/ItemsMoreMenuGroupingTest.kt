/*
 * Vaultix — app:test · items
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * ⋮ 菜单**分组结构**的源码门禁（#301，2026-11-17）。
 *
 * ## 钉死的是什么
 *
 * 用户原话：「这个部分的 UI 该如何设计才好看呢？信息直观，功能完善，目前感觉这个看起来
 * 很老套的样子」。改版把「一整块 `DropdownMenu` + 两条分隔线」换成
 * 「`DropdownMenuPopup` + 三块 `DropdownMenuGroup`」。
 *
 * 这类改动有个共同的天敌：**下一个人为了"顺手"把它改回去**。
 * 改回去不会编译失败、不会让单测变红、界面上也不会报错 —— 只会默默变回"老套"，
 * 而那时没人记得当初为什么这么写。所以这四条要钉在源码上：
 *
 * 1. **外层必须是 `DropdownMenuPopup`**：`DropdownMenu` 自带一整块 `Surface`
 *    （`MenuDefaults.containerColor` + 4dp 圆角），套在分组外面就等于把三块卡片
 *    重新糊回一整片 —— 分组白做。
 * 2. **三组内容都必须在 `MenuGroup` 里**：身份卡 / 导航 / 动作各有自己的圆角与描边，
 *    任何一组被挪到组外，就少了那张卡片的边界。
 * 3. **组间要有 `MenuGroupGap()`**：三块之间的 8dp 是"分离感"的唯一来源（不是画线）。
 * 4. **`MenuGroup` 必须带 `border`**：浅色模式下 `surfaceContainer`（#F1F0EF）与
 *    `surface`（#FDFCFB）亮度只差约 4%，只靠间隙会重新糊成一片 —— 描边是"能不能
 *    分出三块"的关键，不是装饰。
 *
 * ## 为什么是源码文本扫描
 *
 * 这是**布局结构**，不是数值：Compose instrumentation test 在 CI 无 SDK 时跑不了，
 * detekt 也不查"这一层有没有该有的容器"。可机检的只有写法本身 ——
 * 与 `DialogStyleRulesTest` / `TotpRowMenuAlignmentTest` 同族。
 *
 * ⚠️ 所有断言都读**剥掉 `//` 注释之后**的源码：本轮 KDoc 里本来就写着
 * `DropdownMenuPopup` / `MenuGroup` / `border` 这些词，不剥注释就会「注释命中、
 * 代码其实没写」—— 与 #295 `@SerialName("key")`、#297 KDoc 同词、#300「36dp」
 * 是同一个坑，本仓库已经踩过三次。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.items

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemsMoreMenuGroupingTest {

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val screenFile = File(repoRoot, "java/io/vaultix/vaultix/ui/items/ItemsScreen.kt")

    /** 剥掉整行 `//` 注释后的全文（KDoc 用 `*` 开头，assert 只查语句，不必整段剥离）。 */
    private val source: String by lazy {
        screenFile.readText()
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    /**
     * 取 `private fun <name>(` 的函数体（**按花括号配平**，不按"下一个 fun"截）。
     *
     * ⚠️ 必须配平：按"下一个 `fun`"截会把下个函数的 KDoc 一起算进来，而 KDoc 里恰好
     * 也写着本测试要查的词 —— 那会让"删掉代码里的写法"这种变异检测不出来
     * （#297 的门禁就在这上面假绿过一次）。
     */
    private fun bodyOf(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue("找不到 $name 的定义", start >= 0)
        val braceStart = source.indexOf('{', start)
        assertTrue("$name 没有函数体（表达式体？本测试只支持块体）", braceStart >= 0)
        var depth = 0
        for (i in braceStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("$name 的函数体花括号不配平")
    }

    private val moreMenu: String by lazy { bodyOf("ItemsMoreMenu") }
    private val group: String by lazy { bodyOf("MenuGroup") }
    private val navItems: String by lazy { bodyOf("MenuNavItems") }
    private val syncLockRow: String by lazy { bodyOf("MenuSyncLockRow") }

    @Test
    fun `外层必须是 DropdownMenuPopup 而不是自带整块底的 DropdownMenu`() {
        assertTrue(
            "ItemsMoreMenu 必须用 DropdownMenuPopup —— DropdownMenu 自带一整块 " +
                "containerColor 底，会把三块分组重新糊成一整片",
            moreMenu.contains("DropdownMenuPopup("),
        )
        assertTrue(
            "ItemsMoreMenu 不能出现 DropdownMenu( —— 那是「一整块」的旧形态",
            !Regex("""\bDropdownMenu\s*\(""").containsMatchIn(moreMenu),
        )
    }

    @Test
    fun `三组内容都必须各自包在 MenuGroup 里`() {
        listOf(
            "MenuVaultHeader(" to "库身份卡",
            "MenuNavItems(" to "导航项",
            "MenuSyncLockRow(" to "同步 / 锁定行",
        ).forEach { (call, label) ->
            assertTrue(
                "$label（$call）必须在 ItemsMoreMenu 里被 MenuGroup 包起来",
                moreMenu.contains(call),
            )
        }
        // 三个 MenuGroup 调用点（一组一个）
        val groups = Regex("""\bMenuGroup\s*\(""").findAll(moreMenu).count()
        assertTrue("ItemsMoreMenu 里应有 3 个 MenuGroup 调用，实际 $groups", groups == 3)
    }

    @Test
    fun `组之间必须有 MenuGroupGap`() {
        val gaps = Regex("""\bMenuGroupGap\s*\(\s*\)""").findAll(moreMenu).count()
        assertTrue(
            "三块之间要有 2 个 MenuGroupGap()（8dp 真实间隙），实际 $gaps",
            gaps == 2,
        )
    }

    @Test
    fun `分组容器必须带描边`() {
        // 浅色下 surfaceContainer 与 surface 只差约 4% 亮度：没有描边就分不出三块。
        assertTrue(
            "MenuGroup 必须给 DropdownMenuGroup 传 border（1dp outlineVariant）",
            Regex("""\bborder\s*=""").containsMatchIn(group),
        )
        assertTrue(
            "描边色必须是 outlineVariant（与条目卡 / 设置卡同一支）",
            group.contains("outlineVariant"),
        )
    }

    @Test
    fun `三组共用固定宽度以免切库时变形`() {
        assertTrue(
            "MenuGroup 必须用 MENU_PANEL_WIDTH 固定宽度（2026-10-04 的切库变形教训）",
            group.contains("MENU_PANEL_WIDTH"),
        )
        assertTrue(
            "MENU_PANEL_WIDTH 常量必须存在",
            Regex("""private\s+val\s+MENU_PANEL_WIDTH\s*=\s*\d+\.dp""").containsMatchIn(source),
        )
    }

    @Test
    fun `导航项用 itemShape 而不是手写索引`() {
        // itemShape 决定组内首项上圆 / 尾项下圆；手写 index++ 漏一处就是一条直角边。
        assertTrue(
            "MenuNavItems 必须用 MenuDefaults.itemShape(index, count) 算圆角",
            navItems.contains("MenuDefaults.itemShape("),
        )
    }

    @Test
    fun `四个入口与两个动作一个都不能丢`() {
        // 改版只允许换形态，不允许顺手删功能。
        listOf(
            "R.string.items_switch_vault" to "切换密码库",
            "R.string.trash_title" to "回收站",
            "R.string.folders_menu_entry" to "文件夹管理",
        ).forEach { (res, label) ->
            assertTrue("$label（$res）必须在 MenuNavItems 里", navItems.contains(res))
        }
        assertTrue(
            "同步入口必须在 MenuSyncLockRow 里",
            syncLockRow.contains("R.string.items_sync"),
        )
        assertTrue(
            "锁定入口必须在 MenuSyncLockRow 里",
            syncLockRow.contains("R.string.items_lock"),
        )
    }
}
