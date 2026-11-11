/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **主题 / 动态取色默认值的四条可机检规范**（2026-11-12 #285 第 5 项）。
 *
 * 用户反馈「默认的浅色配色有点不符合标准，动态取色疑似有 bug」。反编译取证后确定
 * 两个根因：① `VaultixBrandColor` 一个 fixed 角色都没传 ⇒ 关动态取色时回退基线紫；
 * ② `dynamicColor` 默认 `true` ⇒ 首次启动直接吃壁纸派生色，绕过两轮调校的品牌色板。
 * 这两类缺陷**编译过、detekt 查不出、CI 全绿、只有真机才看得出来**，与
 * `VaultixBrandColorTest` / `DialogStyleRulesTest` 同口径，扫源码把写法钉住。
 *
 * ⚠️ 色值本身（fixed roles 饱和度 / 对比度 / 浅深同值）由 [VaultixBrandColorTest] 验；
 *    本文件只钉「默认值 / 版本门控 / 禁用接线」这些**跨文件的接线关系** —— 它们
 *    散在 4 个文件里，改一处漏三处是最容易复发的形态。
 */
class ThemeDefaultRulesTest {

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    /** 从 app 的 src/main 向上找到含 settings.gradle 的项目根（测试 cwd 可能是仓库根或 app）。 */
    private val projectRoot: File = findProjectRoot(repoRoot.canonicalFile)

    private fun appSource(relative: String): String =
        stripComments(File(repoRoot, "java/io/vaultix/vaultix/$relative").readText())

    private fun preferencesSource(): String = stripComments(
        File(projectRoot, "core/datastore/src/main/java/io/vaultix/datastore/VaultixPreferences.kt")
            .readText(),
    )

    // ------------------------------------------------------------------ 规则 1

    /**
     * **动态取色三处默认值必须一致为 `false`。**
     *
     * 三者各自的角色：
     * - [VaultixTheme] 的参数默认值 —— 预览 / 独立组合 / 任何没显式传的调用点；
     * - `MainActivity.collectAsStateWithLifecycle(initialValue)` —— **冷启动首帧**占位；
     * - `VaultixPreferences.dynamicColor` —— DataStore 里没有值时的回退。
     *
     * ⚠️ 首帧占位尤其不能与其它两处分叉：偏好 Flow 首帧还没 emit 时，占位值就是
     *    用户看到的第一帧；旧代码 `true` 会「先吃壁纸色、第二帧才回品牌色」闪一下。
     *    `LIQUID_GLASS_DOCK` 换默认值时踩过同一个坑（见 MainActivity 的 KDoc）。
     *
     * ⚠️ 只影响**从未动过开关**的用户：DataStore 已有显式值的一律以用户选择为准。
     */
    @Test
    fun `动态取色三处默认值一致为false`() {
        val theme = appSource("ui/theme/Theme.kt")
        val activity = appSource("MainActivity.kt")
        val prefs = preferencesSource()
        val missing = buildList {
            if (!theme.contains("dynamicColor: Boolean = false")) {
                add("VaultixTheme 的参数默认值不是 false")
            }
            // MainActivity 里另有 oledPureBlack 的 initialValue = false，故必须锚定
            // preferences.dynamicColor 这一句，不能全文件扫 initialValue。
            if (!Regex(
                    """preferences\.dynamicColor\s*\.collectAsStateWithLifecycle\(initialValue = false\)""",
                ).containsMatchIn(activity)
            ) {
                add("MainActivity 首帧占位不是 false（冷启动会闪动态色）")
            }
            if (!prefs.contains("it[DYNAMIC_COLOR] ?: false")) {
                add("VaultixPreferences 回退值不是 false")
            }
        }
        assertTrue("动态取色三处默认值：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **版本门控只有 [supportsDynamicColor] 一个真源，且门槛是 S（Android 12）。**
     *
     * 取色分支与设置页禁用必须共用同一个函数：各写一遍 `SDK_INT >= S` 会漂移成
     * 「一个禁了、另一个还生效」。显式 `sdkInt` 入参让门槛能在 JVM 单测里验。
     */
    @Test
    fun `动态取色版本门控单一真源且门槛为S`() {
        val theme = appSource("ui/theme/Theme.kt")
        val settings = appSource("ui/settings/SettingsScreen.kt")
        val display = functionBody(settings, "DisplaySection")
        val missing = buildList {
            if (!theme.contains("fun supportsDynamicColor(sdkInt: Int = Build.VERSION.SDK_INT)")) {
                add("缺 supportsDynamicColor 纯函数（门槛要可单测）")
            }
            if (!theme.contains("sdkInt >= Build.VERSION_CODES.S")) add("门槛不是 API 31 / S")
            if (!theme.contains("dynamicColor && supportsDynamicColor()")) {
                add("取色分支没走单一真源（可能又写了裸 SDK_INT 比较）")
            }
            if (!display.contains("supportsDynamicColor()")) {
                add("设置页没复用 supportsDynamicColor 判禁用")
            }
            if (display.contains("SDK_INT")) {
                add("设置页 DisplaySection 出现裸 SDK_INT 比较（版本事实应只有一处）")
            }
        }
        assertTrue("动态取色版本门控：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **低版本必须「禁用开关 + 说明」，不许静默降级。**
     *
     * `dynamicXxxColorScheme` 只在 S+ 存在；开关能点却不生效就是坏掉的开关。
     * [androidx.compose.material3.Switch] 的 `enabled` 与 `SettingsRow.enabled` 是两码事
     * （后者管不到 trailing 槽），两边都要接上，否则低版本用户照样能拨动。
     */
    @Test
    fun `低版本禁用动态取色开关并给出说明`() {
        val settings = appSource("ui/settings/SettingsScreen.kt")
        val components = appSource("ui/settings/SettingsComponents.kt")
        val display = functionBody(settings, "DisplaySection")
        val switch = functionBody(components, "SettingsSwitch")
        // ⚠️ `enabled: Boolean = true` 是**参数声明**，在签名里、不在函数体里：
        //    functionBody() 从 `{` 起，捞不到它（#283 AddActionSheetStyleTest 的 `dark`
        //    参数当年就是这么漏的）。判「有没有这个参数」必须走签名。
        val switchSignature = functionSignature(components, "SettingsSwitch")
        val missing = buildList {
            // 行与 trailing 的开关是**两处**独立接线：后者不受前者 enabled 约束。
            val enabledWiring = Regex("""enabled = dynamicColorSupported""").findAll(display).count()
            if (enabledWiring < 2) {
                add("SettingsRow 与 SettingsSwitch 都要接 enabled，实际只有 $enabledWiring 处")
            }
            if (!display.contains("R.string.setting_dynamic_color_unsupported")) {
                add("低版本没换说明文案（用户不知道为什么拨不动）")
            }
            if (!switchSignature.contains("enabled: Boolean = true")) {
                add("SettingsSwitch 缺 enabled 参数（签名里）")
            }
            if (!switch.contains("enabled = enabled")) add("Switch 自身没消费 enabled")
            if (!switch.contains("if (enabled) Modifier.clickable")) {
                add("占位分支没在禁用时停止接收点击（会绕过禁用写偏好）")
            }
        }
        assertTrue("低版本动态取色禁用：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 4

    /**
     * **OLED 纯黑要压整套 surface 家族，不是只改 background / surface 两个角色。**
     *
     * #283 修了玻璃绘制层的光照，但色板层仍只 `copy(background, surface)`；底栏 / 卡片 /
     * 分组头用的 `surfaceContainerLow ~ Highest` 与 `surfaceBright` 还是深灰 ⇒ 纯黑背景上
     * 浮出一块块灰面，观感就是「脏」。这九档必须一起压到纯黑起算的中性灰阶。
     */
    @Test
    fun `OLED纯黑覆盖整套surface家族`() {
        val theme = appSource("ui/theme/Theme.kt")
        // ⚠️ applyPureBlack 是**表达式体**函数（`= base.copy(...)`），没有花括号，
        //    不能用 functionBody() 取 —— 那会一路配平到下一个函数的 `{`。改为截到
        //    下一个顶层声明之前，正好覆盖整个 copy 参数块。
        val pureBlack = theme
            .substringAfter("private fun applyPureBlack", "")
            .substringBefore("private val DarkColorScheme", "")
        val missing = buildList {
            if (pureBlack.isEmpty()) add("找不到 applyPureBlack 参数块")
            listOf(
                "background", "surface", "surfaceDim", "surfaceBright",
                "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
                "surfaceContainerHigh", "surfaceContainerHighest",
            ).forEach { role ->
                if (!pureBlack.contains("$role =")) add("纯黑没覆写 $role")
            }
            if (!theme.contains("applyPureBlack(base)")) add("VaultixTheme 没调用 applyPureBlack")
        }
        assertTrue("OLED 纯黑 surface 家族：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ----------------------------------------------------------------- helpers

    private fun findProjectRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        return start
    }

    /** 取顶层函数的函数体（大括号配平，跳过字符串里的括号）。 */
    private fun functionBody(text: String, name: String): String {
        val start = Regex("""fun\s+""" + Regex.escape(name) + """\s*\(""")
            .find(text) ?: return ""
        val brace = text.indexOf('{', start.range.last)
        if (brace < 0) return ""
        return text.substring(brace, balancedBraceEnd(text, brace))
    }

    /**
     * 取函数**签名**（`fun Xxx(` 到函数体 `{` 之前，含参数列表与默认值）。
     *
     * ⚠️ [functionBody] 从 `{` 起，**参数声明查不到** —— 与 #283 `AddActionSheetStyleTest`
     *    踩过的同一个坑（当时 `dark: Boolean` 就是这么漏判的）。凡是判「有没有某个参数 /
     *    默认值是多少」都必须走这里，别指望在函数体里捞到。
     */
    private fun functionSignature(text: String, name: String): String {
        val start = Regex("""fun\s+""" + Regex.escape(name) + """\s*\(""")
            .find(text) ?: return ""
        val brace = text.indexOf('{', start.range.last)
        if (brace < 0) return ""
        return text.substring(start.range.first, brace)
    }

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
     * 去掉行注释与块注释、保留换行。KDoc 里常写反例（例如「别再各写一遍
     * `SDK_INT >= S`」），不剥掉会把说明文字当成真实调用。
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

    private fun skipLineComment(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        while (i < text.length && text[i] != '\n') i++
        out.append('\n')
        return i
    }

    private fun skipBlockComment(text: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) {
            if (text[i] == '\n') out.append('\n')
            i++
        }
        return i + 2
    }
}
