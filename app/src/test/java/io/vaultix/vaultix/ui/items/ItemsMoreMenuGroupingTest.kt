/*
 * Vaultix — app:test · items
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * ⋮ 菜单**结构 / 配色**的源码门禁（#301 → #302，2026-11-17）。
 *
 * ## 钉死的是什么
 *
 * #301 把「一整块 DropdownMenu + 两条分隔线」换成「Popup + 三块独立分组」；
 * #302 再按主次重排成「身份带（淡底）+ 动作区（白卡，内含分隔线）」两段，并把
 * 状态灯从三处去重到一处、把 UPLOADING 从 SYNCING 的琥珀色里拆出来、让 CONFLICT
 * 退出品牌 primary 蓝。
 *
 * 这类改动有个共同的天敌：**下一个人为了"顺手"把它改回去**。
 * 改回去不会编译失败、不会让单测变红、界面上也不会报错 —— 只会默默退回"堆、碎、
 * 没主次"，而那时没人记得当初为什么这么写。所以这些要钉在源码上：
 *
 * 1. 外层必须是 `DropdownMenuPopup`（`DropdownMenu` 自带整块底，会把两段糊回去）；
 * 2. 身份带 / 动作区都要包在 `MenuGroup` 里，正好 2 段、1 个 gap；
 * 3. 主次靠**底色**：身份带 `tinted = true` → `surfaceContainerHigh`，动作区默认白卡；
 * 4. 动作区内是 `HorizontalDivider` 子分区，不是第二张卡；
 * 5. 状态灯**只画一次**：身份带有、动作区无；
 * 6. 状态灯五色映射：UPLOADING ≠ SYNCING、CONFLICT 不用 `primary`；
 * 7. 宽度恒定、功能项一个不少、菜单内无 subcompose 节点（#301.1 闪退）。
 *
 * ## 为什么是源码文本扫描
 *
 * 布局结构和配色都是**写法**，不是数值：Compose instrumentation test 在 CI 无 SDK 时
 * 跑不了，detekt 也不查"这一层有没有该有的容器 / 这态用的是哪支色"。可机检的只有
 * 写法本身 —— 与 `DialogStyleRulesTest` / `TotpRowMenuAlignmentTest` 同族。
 *
 * ⚠️ 所有断言都读**剥掉 `//` 注释之后**的源码，并且只查 `bodyOf` 截取的**函数体**：
 * KDoc 以 `*` 开头不会被 `//` 过滤剥掉，里面又恰恰写着本测试要查的词
 * （`DropdownMenuPopup` / `MenuGroup` / `primary` / Circle 图标 …），
 * 不截函数体就会「注释命中、代码其实没写」。这是本仓库反复踩的同款坑。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.items

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemsMoreMenuGroupingTest {

    /** 拆开写，避免门禁测试文件自己被 check_compile_smells 当成漏 import 的图标引用。 */
    private val circleIconToken = "Icons.Filled." + "Circle"

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val screenFile = File(repoRoot, "java/io/vaultix/vaultix/ui/items/ItemsScreen.kt")
    /** #302：状态灯配色在 common 层，另读一份。 */
    private val dotFile = File(repoRoot, "java/io/vaultix/vaultix/ui/common/VaultOriginLabel.kt")

    /** 剥掉整行 `//` 注释后的全文（KDoc 用 `*` 开头，靠 bodyOf 截函数体来排除）。 */
    private fun stripped(file: File): String = file.readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    private val source: String by lazy { stripped(screenFile) }
    private val dotSource: String by lazy { stripped(dotFile) }

    /**
     * 取 `fun <name>(` 的函数体（**按花括号配平**，不按"下一个 fun"截；也兼容 private）。
     *
     * ⚠️ 必须配平：按"下一个 `fun`"截会把下个函数的 KDoc 一起算进来，而 KDoc 里恰好
     * 也写着本测试要查的词 —— 那会让"删掉代码里的写法"这种变异检测不出来。
     */
    private fun bodyOf(name: String, src: String = source): String {
        // common 层的 syncStatusDotColor 是 @Composable public fun，ItemsScreen 里的都是 private fun，
        // 两种都要能取到。
        val start = src.indexOf("private fun $name(").takeIf { it >= 0 }
            ?: src.indexOf("fun $name(")
        assertTrue("找不到 $name 的定义", start >= 0)
        val braceStart = src.indexOf('{', start)
        assertTrue("$name 没有函数体（表达式体？本测试只支持块体）", braceStart >= 0)
        var depth = 0
        for (i in braceStart until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        error("$name 的函数体花括号不配平")
    }

    private val moreMenu: String by lazy { bodyOf("ItemsMoreMenu") }
    private val group: String by lazy { bodyOf("MenuGroup") }
    private val navItems: String by lazy { bodyOf("MenuNavItems") }
    private val syncLockRow: String by lazy { bodyOf("MenuSyncLockRow") }
    private val vaultHeader: String by lazy { bodyOf("MenuVaultHeader") }
    /** 状态灯配色函数体（common 层）。 */
    private val dotColorBody: String by lazy { bodyOf("syncStatusDotColor", dotSource) }

    /** 从 `SyncStatusDot.<state> -> <Token>` 里取出颜色 token。 */
    private fun colorTokenOf(state: String): String? =
        Regex("""SyncStatusDot\.$state\s*->\s*([A-Za-z0-9_.]+)""")
            .find(dotColorBody)?.groupValues?.get(1)

    @Test
    fun `外层必须是 DropdownMenuPopup 而不是自带整块底的 DropdownMenu`() {
        assertTrue(
            "ItemsMoreMenu 必须用 DropdownMenuPopup —— DropdownMenu 自带一整块 " +
                "containerColor 底，会把两段重新糊成一整片",
            moreMenu.contains("DropdownMenuPopup("),
        )
        assertTrue(
            "ItemsMoreMenu 不能出现 DropdownMenu( —— 那是「一整块」的旧形态",
            !Regex("""\bDropdownMenu\s*\(""").containsMatchIn(moreMenu),
        )
    }

    @Test
    fun `身份带与动作区都在 MenuGroup 里且正好两段`() {
        listOf(
            "MenuVaultHeader(" to "身份带",
            "MenuNavItems(" to "导航项",
            "MenuSyncLockRow(" to "同步 / 锁定行",
        ).forEach { (call, label) ->
            assertTrue(
                "$label（$call）必须在 ItemsMoreMenu 里被 MenuGroup 包起来",
                moreMenu.contains(call),
            )
        }
        // 两个 MenuGroup 调用点：身份带一段 + 动作区一段。
        val groups = Regex("""\bMenuGroup\s*\(""").findAll(moreMenu).count()
        assertTrue("ItemsMoreMenu 里应有 2 个 MenuGroup 调用（两段），实际 $groups", groups == 2)
    }

    @Test
    fun `两段之间必须有 MenuGroupGap`() {
        val gaps = Regex("""\bMenuGroupGap\s*\(\s*\)""").findAll(moreMenu).count()
        assertTrue(
            "身份带与动作区之间要有 1 个 MenuGroupGap()（8dp 真实间隙），实际 $gaps",
            gaps == 1,
        )
    }

    @Test
    fun `分组容器必须带描边`() {
        // 浅色下 surfaceContainer 与 surface 只差约 4% 亮度：没有描边就分不出浮块。
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
    fun `两段共用固定宽度以免切库时变形`() {
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
        // 重排只允许换形态，不允许顺手删功能。
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

    @Test
    fun `身份带用淡底动作区用默认色主次靠底色`() {
        // MenuGroup 有 tinted 开关，身份带传 true，底色用 surfaceContainerHigh。
        assertTrue(
            "MenuGroup 必须有 tinted 参数（true=淡底身份带 / false=默认白卡动作区）",
            Regex("""tinted\s*:\s*Boolean""").containsMatchIn(group),
        )
        assertTrue(
            "MenuGroup 必须按 tinted 选底色 surfaceContainerHigh",
            group.contains("surfaceContainerHigh"),
        )
        assertTrue(
            "身份带调用必须传 tinted = true",
            Regex("""tinted\s*=\s*true""").containsMatchIn(moreMenu),
        )
    }

    @Test
    fun `动作区内用分隔线分区而不是第二张卡`() {
        // 导航（去别处）与同步/锁定（对本库操作）都是动作，用 1dp 线分区即可，
        // 不拆成第二张 MenuGroup —— 否则又退回三张等权白卡。
        assertTrue(
            "ItemsMoreMenu 的动作区内必须有 HorizontalDivider 做子分区",
            moreMenu.contains("HorizontalDivider("),
        )
    }

    @Test
    fun `状态灯只画一次身份带有动作区没有`() {
        // #302 去重：同一个 syncDot 不能在身份带和动作区各画一遍。
        assertTrue(
            "身份带 MenuVaultHeader 必须画状态灯（syncStatusDotColor）",
            vaultHeader.contains("syncStatusDotColor("),
        )
        assertTrue(
            "身份带 MenuVaultHeader 的状态圆点必须带读屏描述",
            vaultHeader.contains("syncStatusDotDescription("),
        )
        assertTrue(
            "动作区 MenuSyncLockRow 不得再画状态灯（#302 去重，一个状态只说一次）",
            !syncLockRow.contains("syncStatusDotColor("),
        )
        assertTrue(
            "动作区 MenuSyncLockRow 不得再画 Circle 状态点",
            !syncLockRow.contains(circleIconToken),
        )
        assertTrue(
            "动作区 MenuSyncLockRow 不得再引用 syncDot",
            !syncLockRow.contains("syncDot"),
        )
    }

    @Test
    fun `状态灯五色映射上传不等于同步且冲突退出primary`() {
        listOf("SYNCING", "UPLOADING", "CONFLICT", "FAILED", "SUCCESS").forEach { state ->
            assertTrue(
                "syncStatusDotColor 必须有 $state 分支",
                colorTokenOf(state) != null,
            )
        }
        val syncing = colorTokenOf("SYNCING")
        val uploading = colorTokenOf("UPLOADING")
        val conflict = colorTokenOf("CONFLICT")
        assertTrue(
            "UPLOADING 与 SYNCING 不得同色：源码是两个枚举、文案也不同（#302）",
            syncing != uploading,
        )
        assertTrue(
            "CONFLICT 不得用 primary 品牌蓝（那是可点色，冲突是要人拍板）",
            conflict != "primary" && conflict?.contains("colorScheme") != true,
        )
        // 四个固定色常量都要是明确的 Color(0xFF…)，且色值两两不同（防"两个常量同色"）。
        val colorLiterals = listOf(
            "SyncDotSyncing",
            "SyncDotSuccess",
            "SyncDotUploading",
            "SyncDotConflict",
        ).map { name ->
            val match = Regex("""private\s+val\s+$name\s*=\s*Color\(0xFF([0-9A-F]{6})\)""")
                .find(dotSource)
            assertTrue("状态灯常量 $name 必须是固定 Color(0xFF…)", match != null)
            match!!.groupValues[1]
        }
        assertTrue(
            "四个状态灯色值必须两两不同，实际 $colorLiterals",
            colorLiterals.toSet().size == colorLiterals.size,
        )
    }

    /**
     * ★ 2026-11-17 真机闪退（`#301.1`）：菜单里**不许出现 subcompose 组件**。
     *
     * `DropdownMenuPopup` 内部是 `Column(modifier.width(IntrinsicSize.Max))`
     * （`AndroidMenu.android.kt`），开菜单时会向子树索取 **intrinsic 宽度**；
     * 而 `BoxWithConstraints` 这类 `SubcomposeLayout` **不支持 intrinsic 测量**，被问到时直接抛
     * `IllegalStateException: Intrinsic measurements are not currently supported by
     * SubcomposeLayout`。
     *
     * 崩溃发生在**首次组合 + 测量**阶段 ⇒ 用户看到的是"点一下 App 就没了、菜单根本没出现"，
     * 而不是"菜单闪一下"。编译、detekt、其它单测**全都查不出** —— 只有真机能看见。
     *
     * 所以这条要钉死：菜单里用中间省略必须走 `MiddleEllipsizedTextInIntrinsicScope`
     * （内部用 `Modifier.layout` 回读约束，不参与 subcompose）。
     */
    @Test
    fun `菜单内不允许出现不支持 intrinsic 测量的 subcompose 组件`() {
        val offenders = listOf(
            "BoxWithConstraints",
            "SubcomposeLayout",
            "LazyColumn",
            "LazyRow",
            "rememberTextMeasurer", // 只有 BoxWithConstraints 版才需要
        )
        val menuBodies = listOf(
            "ItemsMoreMenu" to moreMenu,
            "MenuVaultHeader" to vaultHeader,
            "MenuNavItems" to navItems,
            "MenuSyncLockRow" to syncLockRow,
            "MenuVaultBadge" to bodyOf("MenuVaultBadge"),
        )
        menuBodies.forEach { (name, body) ->
            // 逐行剥 `//` 注释（块注释里的说明文字同样会造成假绿/假红）
            val code = body.lines()
                .map { it.substringBefore("//") }
                .joinToString("\n")
            offenders.forEach { bad ->
                assertTrue(
                    "$name 里不能出现 $bad：DropdownMenuPopup 的 Column 用了 " +
                        "IntrinsicSize.Max，subcompose 节点会在测量期抛异常（真机直接闪退）",
                    !Regex("""\b$bad\b""").containsMatchIn(code),
                )
            }
        }
        // 正向钉住：必须改用 intrinsic-safe 变体
        assertTrue(
            "MenuVaultHeader 的来源行必须用 MiddleEllipsizedTextInIntrinsicScope",
            vaultHeader.contains("MiddleEllipsizedTextInIntrinsicScope("),
        )
        assertTrue(
            "MenuVaultHeader 不能用普通的 MiddleEllipsizedText（内部是 BoxWithConstraints）",
            !vaultHeader.contains("MiddleEllipsizedText("),
        )
    }
}
