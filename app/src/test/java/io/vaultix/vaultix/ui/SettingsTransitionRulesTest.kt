/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置二级页转场的**四条可机检规范**（2026-11-12「过渡不自然」）。
 *
 * 这次的缺陷是「编译过、detekt 查不到、帧统计也查不到」：设置首页 → 子页在相邻
 * 两帧内硬切，`dumpsys gfxinfo` 三轮 99th 仅 18~27ms，日志也没有 `Skipped frames`。
 * ⇒ 只能把决策钉在源码写法上，防止以后新增设置路由时漏配、或把动效调回被否掉的形态。
 *
 * 与 `LiquidGlassDockRulesTest` 同款：纯文本扫描，CI 无需真机。
 */
class SettingsTransitionRulesTest {

    private val mainRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val uiDir = File(mainRoot, "java/io/vaultix/vaultix/ui")

    private fun navSource(): String = File(uiDir, "VaultixApp.kt").readText()
    private fun transitionsSource(): String = File(uiDir, "shell/SettingsTransitions.kt").readText()

    /** 取出 settingsGraph 的函数体（到下一个顶格 `}` 为止）。 */
    private fun settingsGraphBody(source: String): String {
        val start = source.indexOf("private fun NavGraphBuilder.settingsGraph(")
        assertTrue("找不到 settingsGraph 函数", start >= 0)
        val end = source.indexOf("\n}\n", start)
        assertTrue("settingsGraph 没有结束括号", end > start)
        return source.substring(start, end)
    }

    // ------------------------------------------------------------------ 规则 1

    /**
     * **设置组每个目的地都必须走 [settingsComposable]。**
     *
     * 裸 `composable<X>` 会继承 NavHost 的 Tab 转场（几乎无动画），于是新加的那一页
     * 又变成硬切 —— 正是「某几页有动画、某几页硬切」的历史坑。
     */
    @Test
    fun `设置组不允许裸 composable_必须一处收口`() {
        val body = settingsGraphBody(navSource())
        assertFalse(
            "settingsGraph 里出现裸 composable<；请改成 settingsComposable<",
            Regex("""\bcomposable<""").containsMatchIn(body),
        )
        assertTrue(
            "settingsGraph 应当有多个 settingsComposable 调用",
            Regex("""settingsComposable<""").findAll(body).count() >= 2,
        )
    }

    // ------------------------------------------------------------------ 规则 2

    /** Tab 切换那套**不许被这次改动带跑**：它两页共享底栏，硬切本来就不突兀。 */
    @Test
    fun `NavHost 的 Tab 转场保持原样`() {
        val source = navSource()
        listOf(
            "enterTransition = { tabSwitchEnter() }",
            "exitTransition = { tabSwitchExit() }",
            "popEnterTransition = { tabSwitchEnter() }",
            "popExitTransition = { tabSwitchExit() }",
        ).forEach { assertTrue("NavHost 缺少或被改动：$it", source.contains(it)) }
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **尺寸补间必须关掉**。导航库默认给目的地套 `SizeTransform`，两页测量尺寸
     * 差一档就会把容器补间成中间尺寸，表现为「整页缩小一帧」（历史 bug）。
     */
    @Test
    fun `包装函数与 NavHost 都关闭 sizeTransform`() {
        val source = navSource()
        assertTrue("NavHost 必须 sizeTransform = null", source.contains("sizeTransform = null,"))
        val body = source.substring(
            source.indexOf("private inline fun <reified T : Any> NavGraphBuilder.settingsComposable("),
        ).substringBefore("\n)\n")
        assertTrue("settingsComposable 必须 sizeTransform = null", body.contains("sizeTransform = null"))
    }

    // ------------------------------------------------------------------ 规则 4

    /**
     * **动效必须留在「轻」的边界内**：alpha 下限不低于 0.9（避免全屏淡入漏背景 / 掉帧），
     * 位移不超过 1/16 屏宽（避免回到被用户否掉的「整屏滑走」）。
     */
    @Test
    fun `设置转场不得退回全屏淡入或整屏滑动`() {
        val source = transitionsSource()
        assertTrue(
            "alpha 下限被调低到 0.9 以下，会退回全屏淡入",
            Regex("""SETTINGS_ALPHA_FLOOR\s*=\s*0\.(9\d+|9)f""").containsMatchIn(source),
        )
        assertTrue(
            "位移除数小于 16，会接近整屏滑走",
            Regex("""ENTER_SLIDE_DIVISOR\s*=\s*(1[6-9]|[2-9]\d+)""").containsMatchIn(source),
        )
        assertTrue("必须四个方向都定义", listOf(
            "fun settingsEnter()", "fun settingsExit()",
            "fun settingsPopEnter()", "fun settingsPopExit()",
        ).all { source.contains(it) })
        assertTrue("push/pop 必须互为镜像", source.contains("-it / EXIT_SLIDE_DIVISOR"))
    }
}
