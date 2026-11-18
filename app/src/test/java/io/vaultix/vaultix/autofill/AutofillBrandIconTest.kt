/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 填充面板品牌标识（2026-11-18 方案 B）的三条可机检契约。
 *
 * 这类缺陷编译过、detekt 查不出、只有真机才看得出来：
 * ① 面板还在用旧的「盾牌 + 钥匙孔」而桌面是折纸 V —— 同品牌两套形态；
 * ② 认证行写死黑色且不上色 —— 深色面板对比度只有 1.23:1，图标直接消失；
 * ③ 换形时手画 path，与自适应图标的单色层真源漂移。
 *
 * 与 `ThemeDefaultRulesTest` 同口径：扫源码把**跨文件的接线关系**钉住，
 * 几何本身则从真源反算，不写死坐标。
 */
class AutofillBrandIconTest {

    private val mainDir: File = File("app/src/main").takeIf { it.isDirectory }
        ?: File("src/main")

    private fun res(relative: String): File = File(mainDir, "res/$relative")

    private val datasetsSource: File
        get() = File(mainDir, "java/io/vaultix/vaultix/autofill/engine/AutofillDatasets.kt")

    /** 从 vector drawable 里按 path 顺序取出全部顶点。 */
    private fun verticesOf(file: File): List<List<Pair<Double, Double>>> =
        PATH_REGEX.findAll(file.readText()).map { match ->
            COORD_REGEX.findAll(match.groupValues[1]).map { point ->
                point.groupValues[1].toDouble() to point.groupValues[2].toDouble()
            }.toList()
        }.toList()

    @Test
    fun `panel icon is derived from monochrome source by documented formula`() {
        val source = verticesOf(res("drawable/ic_launcher_monochrome.xml"))
        val panel = verticesOf(res("drawable/ic_autofill_vaultix.xml"))
        assertEquals("单色层应有两条 V 臂", 2, source.size)
        assertEquals("面板标识也应有两条 V 臂", 2, panel.size)

        val scale = PANEL_EXTENT / SOURCE_EXTENT
        source.zip(panel).forEachIndexed { pathIndex, (from, to) ->
            assertEquals("第 $pathIndex 条臂顶点数", from.size, to.size)
            from.zip(to).forEachIndexed { vertexIndex, (src, dst) ->
                val expectedX = (src.first - SOURCE_LEFT) * scale + PANEL_INSET
                val expectedY = (src.second - SOURCE_TOP) * scale + PANEL_INSET
                assertEquals("臂$pathIndex 顶点$vertexIndex 的 x", expectedX, dst.first, TOLERANCE)
                assertEquals("臂$pathIndex 顶点$vertexIndex 的 y", expectedY, dst.second, TOLERANCE)
            }
        }
    }

    @Test
    fun `panel icon has no shield keyhole leftovers`() {
        val text = res("drawable/ic_autofill_vaultix.xml").readText()
        assertFalse("钥匙孔靠 evenOdd 挖空，折纸 V 不应保留该写法", text.contains("evenOdd"))
        assertEquals("折纸 V 只有两笔，多出来的一笔就是旧形态残留", 2, PATH_REGEX.findAll(text).count())
    }

    @Test
    fun `authentication row opts into panel tint`() {
        val source = datasetsSource.readText()
        assertFalse("品牌图标不能再写死不上色", source.contains("tintIcon = false"))
        // ⚠️ 不要按「下一个 fun」截函数体：buildFallback 是文件最后一个函数，
        //    那样会截出空串（与 ThemeDefaultRulesTest 踩过的源码锚点坑同族）。
        //    直接锚定调用处的 iconRes 实参，截到 presentation(...) 的右括号。
        val call = source.substringAfter("iconRes = R.drawable.ic_autofill_vaultix", "")
            .substringBefore(")", "")
        assertTrue("品牌图标的 presentation 调用必须显式接上 tint", call.contains("tintIcon = true"))
    }

    @Test
    fun `panel tints clear non-text contrast floor in both modes`() {
        val source = datasetsSource.readText()
        val light = requireColor(source, "LIGHT_ICON_TINT")
        val dark = requireColor(source, "DARK_ICON_TINT")
        val lightContrast = contrastRatio(light, "#F2F2F7")
        val darkContrast = contrastRatio(dark, "#1C1C1E")
        assertTrue("浅色面板对比度 $lightContrast 未过 3:1", lightContrast >= 3.0)
        assertTrue("深色面板对比度 $darkContrast 未过 3:1", darkContrast >= 3.0)
    }

    private fun requireColor(source: String, name: String): String =
        Regex("$name: Long = 0xFF([0-9A-F]{6})")
            .find(source)?.groupValues?.get(1)
            ?.let { "#$it" }
            ?: error("找不到 $name，着色契约无法验证")

    private fun contrastRatio(a: String, b: String): Double {
        val first = relativeLuminance(a)
        val second = relativeLuminance(b)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(hex: String): Double {
        val channels = (1..5 step 2).map { hex.substring(it, it + 2).toInt(16) / 255.0 }
        val linear = channels.map {
            if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
    }

    private companion object {
        const val SOURCE_EXTENT = 34.0
        const val SOURCE_LEFT = 37.0
        const val SOURCE_TOP = 40.0
        const val PANEL_EXTENT = 16.0
        const val PANEL_INSET = 4.0
        const val TOLERANCE = 0.011
        val PATH_REGEX = Regex("""android:pathData="([^"]*)"""")
        val COORD_REGEX = Regex("""(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")

        /** java.lang.Math.pow 避免依赖 kotlin.math 之外的扩展（本测试须能独立 JVM 运行）。 */
        fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
    }
}
