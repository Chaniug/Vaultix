/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 液态玻璃底栏的**十二条可机检规范**（第四轮建 1–6，#278 补 7，#280 补 8–9，#281 补 10，#282 补 11，#283 补 12）。
 *
 * 用户这轮反馈「点击效果和玻璃的效果都没有做完善，注意底栏的边界和大小」。
 * 对应的三类缺陷都是「编译过、detekt 查不到、真机上才看得出来」：
 *
 * 1. `drawRect` 画高光 / 描边 ⇒ 被 `clip(Pill)` 裁掉两端半圆 ⇒ **边界断在圆角**；
 * 2. 模糊不提饱和度 ⇒ 背后内容糊成灰雾 ⇒ **玻璃像蒙了层雾**；
 * 3. 玻璃态与实色态各写一份布局 / 写死高度 ⇒ 两种外观悄悄分叉、甚至高度塌 0（#205）。
 *
 * ⚠️ 与 `DialogStyleRulesTest` 同理：这些是**源码写法**问题，只能扫文本钉住；
 *    CI 没有真机，也跑不了 instrumented test。
 */
class LiquidGlassDockRulesTest {

    private val mainRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val uiDir = File(mainRoot, "java/io/vaultix/vaultix/ui")
    private val glassDir = File(uiDir, "glass")

    private fun glassSource(name: String): String = stripComments(File(glassDir, name).readText())

    private fun dockSource(): String =
        stripComments(File(uiDir, "shell/VaultixBottomDock.kt").readText())

    // ------------------------------------------------------------------ 规则 1

    /**
     * **玻璃的所有几何装饰必须按 Shape 的 Outline 画。**
     *
     * 旧实现用 `drawRect` 画顶部亮线、底部暗线和矩形内描边。层 B 虽然 `.clip(Pill)`，
     * 但被裁掉的恰恰是药丸两端的半圆 —— 圆角处既没高光也没描边，玻璃读起来像
     * 「上下有边、左右没边」的一块板。这就是用户说的「注意底栏的边界」。
     */
    @Test
    fun `玻璃装饰一律按轮廓绘制_不许退回矩形`() {
        val source = glassSource("LiquidGlass.kt")
        val offenders = listOf(
            "drawGlass",
            "drawSpecularRim",
            "drawFocusSheen",
            // #280 新增的两层「填充类」光学也必须沿 Outline，否则药丸两端会缺光。
            "drawOpticalBody",
            "drawSurfaceSweep",
            // #281 沿轮廓的内阴影同理：用 drawRect 会让圆角两端缺暗部。
            "drawInsetShadow",
        )
            .mapNotNull { name ->
                val body = functionBody(source, name)
                when {
                    body.isEmpty() -> "$name：找不到函数体"
                    body.contains("drawRect(") -> "$name：出现 drawRect"
                    !body.contains("drawOutline(") -> "$name：没有 drawOutline"
                    else -> null
                }
            }

        assertTrue(
            "玻璃装饰必须沿轮廓绘制（drawOutline + shape.createOutline），" +
                "退回 drawRect 会让药丸两端的高光与描边被裁掉：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **三档外观必须共用同一套形状与行布局。**
     *
     * #120 / #193 / #195 栽过三次「同一规则写两处、只改一处」：实色 `Surface` 与
     * 两条 `LiquidGlassLayer` 分支（#281 的磨砂 / 液态玻璃）若各自写 shape / 高度，
     * 改一处就会静默分叉。
     * `VaultixCorner.Pill` 至少 3 处（layer shape / shadow shape / Surface shape），
     * `DockRow(` 至少 3 处（定义 + 三条分支各一次调用）。
     */
    @Test
    fun `两种外观共用形状与行布局`() {
        val dock = dockSource()
        val pills = Regex("""VaultixCorner\.Pill""").findAll(dock).count()
        val rows = Regex("""\bDockRow\s*\(""").findAll(dock).count()

        assertTrue(
            "玻璃态与实色态必须共用规格：VaultixCorner.Pill 出现 $pills 次（应 ≥3）、" +
                "DockRow( 出现 $rows 次（应 ≥3）",
            pills >= 3 && rows >= 3,
        )
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **玻璃层不许写死高度。**
     *
     * #205 的整条底栏消失，根因是层 B 也写 `matchParentSize()` ⇒ 父 Box 高度塌 0。
     * 现在层 B 只 `fillMaxWidth()`，高度**由 content 测量**（2.0x 字号下才不会被裁字）。
     * 谁再加回 `.height(...)`，要么重现塌 0，要么让大字号下的 tab 文字被切掉。
     */
    @Test
    fun `玻璃层不许写死高度_尺寸必须由内容测量`() {
        val layer = functionBody(glassSource("LiquidGlass.kt"), "LiquidGlassLayer")
        assertTrue("LiquidGlassLayer 函数体没找到", layer.isNotEmpty())
        assertTrue(
            "玻璃层出现固定高度：层 B 只能 fillMaxWidth() + 内容测量，" +
                "写死 height 会重现 #205 或裁掉大字号 tab 文字",
            !layer.contains(".height("),
        )
    }

    // ------------------------------------------------------------------ 规则 4

    /**
     * **按压联动只准进绘制参数，不准碰测量。**
     *
     * 第四轮给玻璃加了「按住变厚、光源跟着手指」的联动。它只能是颜色 alpha /
     * shader uniform / 径向渐变中心，一旦有人把 press 接进 `size/height/width/padding`，
     * 底栏就会在按下瞬间**改变边界与大小** —— 正是用户特意提醒的那一项。
     */
    @Test
    fun `按压联动只进绘制参数_不许改变边界与大小`() {
        val sizeCall = Regex("""\.(size|height|width|padding|aspectRatio|heightIn|widthIn)\s*\(""")
        val offenders = listOf(
            "LiquidGlass.kt" to glassSource("LiquidGlass.kt"),
            "LiquidGlassSource.kt" to glassSource("LiquidGlassSource.kt"),
            "VaultixBottomDock.kt" to dockSource(),
        ).flatMap { (name, text) ->
            text.lineSequence()
                .filter { line -> line.contains("press", ignoreCase = true) || line.contains("focusX") }
                .filter { line -> sizeCall.containsMatchIn(line) }
                .map { line -> "$name → ${line.trim().take(100)}" }
        }.toList()

        assertTrue(
            "按压相关代码里出现尺寸调用（press 只能调绘制参数，不能改变底栏边界与大小）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 5

    /**
     * **饱和度增强必须覆盖所有有模糊的档位。**
     *
     * Haze vibrancy / PrismalAGSL saturation / LiquidGlassBar 1.5× 都做同一件事：
     * 只模糊会把彩色条目洗成中性灰，玻璃就成了「雾」。用 ColorFilter 串在模糊之后，
     * 31-32（`plain`）与 33+（AGSL 链的 inner）**都必须拿到同一份 saturated**。
     * 少接一处就会出现「同一台机器换主题 / 换版本玻璃不一样」。
     */
    @Test
    fun `饱和度链覆盖有模糊的所有档位`() {
        val optics = glassSource("LiquidGlassOptics.kt")
        val missing = buildList {
            if (!optics.contains("createColorFilterEffect")) add("没有 createColorFilterEffect")
            if (!optics.contains("LiquidGlass.SATURATION")) add("没有引用 SATURATION 常量")
            if (!optics.contains("plain = saturated.asComposeRenderEffect()")) {
                add("31-32 的 plain 没走饱和度")
            }
            if (!optics.contains("blur = saturated")) add("33+ 的 AGSL 内层没走饱和度")
        }

        assertTrue(
            "饱和度链不完整：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 6

    /**
     * **降级档（无真模糊）不叠镜面与暗线。**
     *
     * 降级时 `style.fill` 已经是全不透明实色，再画镜面 rim / 下沿暗线，
     * 等于给一块塑料板描两道边。旧版靠 `if (blurAvailable)` 挡住，
     * 重排成 `drawOutline` 之后这个闸门必须留在 `drawGlass` 里并**提前 return**。
     */
    @Test
    fun `降级档只画填充与内描边_不叠镜面装饰`() {
        val body = functionBody(glassSource("LiquidGlass.kt"), "drawGlass")
        assertTrue("drawGlass 函数体没找到", body.isNotEmpty())
        assertTrue(
            "降级档必须 `if (!blurAvailable)` 后提前 return，" +
                "否则实色底栏会被叠上镜面高光与 rim 暗线",
            Regex("""if\s*\(!blurAvailable\)\s*\{[^}]*return""").containsMatchIn(body),
        )
    }

    // ------------------------------------------------------------------ 规则 7

    /**
     * **触摸光晕（#278）只进 shader uniform 与绘制，不许参与测量。**
     *
     * FlClash 的 "lights the bar from the finger" 依赖**精确触控位置**。位置只能
     * 折算成 `uGlow` 的 xy/strength/reach；谁把它接进尺寸 modifier，底栏就会
     * 跟着手指变大变小。这条钉三个事实：shader 里有 `uGlow` uniform、
     * backdrop 把 `focusX/focusY` 折算成光心、峰值常量走 [LiquidGlass.TOUCH_GLOW_PEAK]。
     */
    @Test
    fun `触摸光晕只进uniform_不许参与测量`() {
        val optics = glassSource("LiquidGlassOptics.kt")
        val backdrop = glassSource("LiquidGlassSource.kt")
        val missing = buildList {
            if (!optics.contains("uniform float4 uGlow")) add("shader 缺 uGlow uniform")
            if (!backdrop.contains("pressValue.focusX")) add("backdrop 没把 focusX 折算成光心")
            if (!backdrop.contains("pressValue.focusY")) add("backdrop 没把 focusY 折算成光心")
            if (!backdrop.contains("LiquidGlass.TOUCH_GLOW_PEAK")) add("强度没走常量")
        }
        assertTrue("触摸光晕接线不完整：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ------------------------------------------------------------------ 规则 8

    /**
     * **光晕量纲按半径，且「表面流光」必须走绘制层。**
     *
     * 两条都是 #280 修的「编译过、detekt 查不到、真机才看得出来」：
     *
     * 1. #278 的 shader 写 `exp(-d² / uGlow.w)`，把**半径**当方差 ⇒ 有效半径缩成
     *    `√w ≈12px`；必须改成 `uGlow.w * uGlow.w`。AGSL 是运行时编译、CI 覆盖不到，
     *    只能扫源码把这条钉住，否则下一位"顺手简化"就退回去了。
     * 2. #277/#278 把折射 / 色散 / 光晕全放进 API 33+ 的 AGSL ⇒ 31-32 点按时玻璃
     *    毫无光学反应，且层 A 的光晕还被层 B 的实色填充盖掉一层。现在
     *    `drawOpticalBody` / `drawSurfaceSweep` 画在层 B，31+ 全档可见。
     */
    @Test
    fun `光晕量纲按半径且表面流光走绘制层`() {
        val glass = glassSource("LiquidGlass.kt")
        val optics = glassSource("LiquidGlassOptics.kt")
        val missing = buildList {
            if (!optics.contains("uGlow.w * uGlow.w")) {
                add("shader 没按 w² 衰减，光晕会缩成 √w")
            }
            if (!optics.contains("sdfNormal")) add("折射法线没走 SDF 梯度")
            if (!optics.contains("uClarity")) add("缺中心通透提亮 uniform")
            if (!optics.contains("uLens")) add("缺按压透镜 uniform")
            val body = functionBody(glass, "drawGlass")
            if (body.isEmpty()) add("drawGlass 函数体没找到")
            if (!body.contains("drawOpticalBody(")) add("drawGlass 没接通透厚度")
            if (!body.contains("drawSurfaceSweep(")) add("drawGlass 没接表面流光")
            val sweep = functionBody(glass, "drawSurfaceSweep")
            if (!sweep.contains("press.focusX")) add("表面光斑没跟随手指 X")
            if (!sweep.contains("press.focusY")) add("表面光斑没跟随手指 Y")
            // 新绘制层不许自带 API 门槛；门槛只在 Optics 里，否则 31-32 又丢效果。
            listOf("drawOpticalBody", "drawSurfaceSweep").forEach { name ->
                if (functionBody(glass, name).contains("SDK_INT")) {
                    add("$name 里有 SDK_INT 门槛，31-32 会丢效果")
                }
            }
        }
        assertTrue(
            "光晕量纲 / 绘制层接线不完整：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 9

    /**
     * **按压要真的跟手，且亮灭时序非对称。**
     *
     * #279 的记录写「亮 120ms / 灭 360ms 非对称」，代码却让同一条 spring 管两个方向；
     * `DockRow` 也只在 down 上报一次位置，名字叫「跟随手指」其实钉在按下点。
     * 本轮两处都补上，同时用量化上报守住 #277 那条「动画期间零重组」。
     */
    @Test
    fun `按压持续跟手且亮灭时序非对称`() {
        val dock = dockSource()
        val missing = buildList {
            if (!dock.contains("DOCK_TRACKING_QUANTUM")) {
                add("位置上报没量化，逐事件写 state 会重现按压期重组")
            }
            if (!dock.contains("event.changes.firstOrNull { it.pressed }")) {
                add("手势循环没持续读取手指位置（还钉在按下点）")
            }
            if (!dock.contains("onPress(nx, ny)")) add("移动位置没回传宿主")
            if (!dock.contains("if (pressing) GlassPressInSpec else GlassPressOutSpec")) {
                add("intensity 没按方向切 spec，非对称时序没接上")
            }
            if (!dock.contains("durationMillis = 120")) add("亮起不是 120ms")
            if (!dock.contains("durationMillis = 360")) add("熄灭不是 360ms")
        }
        assertTrue(
            "跟手 / 时序接线不完整：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 10

    /**
     * **常态磨砂必须与液态玻璃共用同一条采样链路，且跳过 AGSL。**
     *
     * #281 用户要「不开液态玻璃时，平常状态的底栏也能通透磨砂、隐约看到下方内容」。
     * 三个最容易静默出错、编译与 detekt 都查不到的点：
     *
     * 1. 源层仍写成 `liquidGlassDock && isLiquidGlassBlurSupported()` ⇒ 关开关时
     *    `contentLayer` 没登记，磨砂糊到的是**空背景**，真机表现为"底栏变灰了一块"；
     * 2. 在 `ui/shell` 另起一套采样 / 另画半透明蒙层 ⇒ 与真模糊打架，
     *    也重演 #120/#193/#195「同一规则写两处」；
     * 3. 磨砂也走 [glassRenderEffect] ⇒ 每帧编译/更新 AGSL uniform，
     *    而常态本该安静且零额外开销。
     *
     * 同时钉住 API < 31 的实色降级仍在 —— 半透明 + 无模糊 = 列表文字穿栏。
     */
    @Test
    fun `常态磨砂共用采样链路且跳过AGSL`() {
        val glass = glassSource("LiquidGlass.kt")
        val optics = glassSource("LiquidGlassOptics.kt")
        val source = glassSource("LiquidGlassSource.kt")
        val dock = dockSource()
        val shell = stripComments(File(uiDir, "shell/MainShellScreen.kt").readText())
        val missing = buildList {
            if (!glass.contains("enum class DockMaterial")) add("没定义两档材质")
            if (!glass.contains("val opticsEnabled = material == DockMaterial.Liquid")) {
                add("LiquidGlassLayer 没按材质决定是否走 AGSL")
            }
            if (!functionBody(glass, "drawGlass").contains("drawInsetShadow(")) {
                add("drawGlass 没接 #281 的内阴影")
            }
            if (!dock.contains("dockMaterialOf(liquidGlass)")) add("底栏没按开关选材质")
            if (!dock.contains("material == DockMaterial.Liquid")) add("液态玻璃分支没接上")
            if (!dock.contains("material = DockMaterial.Frosted")) add("磨砂分支没接上")
            if (!dock.contains("Surface(")) add("API<31 的实色降级分支丢了")
            if (!optics.contains("frostedRenderEffect")) add("磨砂没有独立效果入口")
            if (!source.contains("if (!opticsEnabled)")) add("backdrop 没按材质切效果")
            if (!source.contains("frostedRenderEffect(blurRadiusPx = radiusPx)")) {
                add("backdrop 没接磨砂效果")
            }
            if (!shell.contains("val glassSourceActive = isLiquidGlassBlurSupported()")) {
                add("源层没对两档材质统一开启")
            }
            if (shell.contains("glassSourceActive = liquidGlassDock")) {
                add("源层仍乘开关，关掉液态玻璃时磨砂会糊到空背景")
            }
        }
        assertTrue(
            "常态磨砂链路不完整：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    // ----------------------------------------------------------------- 规则 11

    /**
     * **真模糊两档不许再画整圈细描边（#282）。**
     *
     * 用户反馈「通常模式和液态玻璃下，底栏外框都有很小很小的一圈边线，视觉效果不好」。
     * 根因是旧第 ⑧ 层：沿 Outline 用 `style.stroke` 描一圈 1dp 线，两档共有。
     * 现在边界交给 ③ 内阴影（3dp 厚边）与 ⑥ 镜面 rim —— 「有厚度的受光/暗部」，
     * 不是一条细线。降级实色档（`drawGlass` 里 `if (!blurAvailable)` 提前 return 之前）
     * 仍允许画一圈给不透明板收边；那句在 `return` **之前**。
     *
     * ⇒ 钉法：取 `drawGlass` 函数体，第一个 `return`（降级档）之后不许再出现
     *    `style.stroke`。谁把那圈细线加回真模糊路径，这条立刻红。
     */
    @Test
    fun `真模糊两档不画整圈细描边`() {
        val body = functionBody(glassSource("LiquidGlass.kt"), "drawGlass")
        assertTrue("drawGlass 函数体没找到", body.isNotEmpty())
        val afterFallbackReturn = body.substringAfter("return", "")
        assertTrue(
            "#282：真模糊两档不画 1dp 整圈 `style.stroke` 细描边；" +
                "降级实色档那一圈必须在 `if (!blurAvailable) { … return }` 之前。",
            !afterFallbackReturn.contains("style.stroke"),
        )
    }

    // ----------------------------------------------------------------- 规则 12

    /**
     * **深色光照档必须一处定义、全链路生效（#283）。**
     *
     * 用户反馈深色底栏「外围有点脏脏的、周围光照与深色模式对比有问题」。根因是
     * 白高光 + 黑内阴影 + 8dp 黑外影三者全开满，而三者散落在 6 个绘制函数与布局层里。
     * 这类改动最容易出现「改了 7 处、漏了第 8 处」—— 漏掉的那处会继续用满强度，
     * 在深色下重新长出一道刺眼白边或一圈脏黑晕，编译、detekt、既有单测全查不出来。
     *
     * ⇒ 钉三件事：
     * 1. 绘制层不许出现任何绕开缩放的 `style.sheen.copy(` / `Color.Black.copy(` /
     *    已作废的 `RimLineColor`，白色一律走 `sheenAt`、黑色一律走 `shadeAt`；
     * 2. 三个档位由 `glassLighting(darkTheme)` **一处定义**并全链路接进 style
     *    （散成三个 `if` 既容易漏改，也会撞 detekt 的复杂度上限），style 自己提供入口函数；
     * 3. 布局层 `.shadow(...)` 读 `glassStyle.shadowElevation`，不许写死 8dp 常量
     *    （Material 深色规范：elevation 靠表面色阶，不靠自定义 drop shadow）。
     */
    @Test
    fun `深色光照全链路走统一入口且阴影由style携带`() {
        val glass = glassSource("LiquidGlass.kt")
        val dock = dockSource()
        val drawn = listOf(
            "drawGlass",
            "drawInsetShadow",
            "drawOpticalBody",
            "drawSurfaceSweep",
            "drawSpecularRim",
            "drawFocusSheen",
        ).joinToString("\n") { name -> functionBody(glass, name) }
        // ⚠️ 拆成两个小函数：detekt `CyclomaticComplexMethod ≤14`，一条条堆 if 会把
        //    **用例本身**判红（本轮实测 18），与本文件 stripComments 拆小函数同理。
        val missing = lightingDrawIssues(drawn) + lightingWiringIssues(glass, dock)
        assertTrue(
            "深色光照档接线不完整：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    /** 规则 12 上半：绘制层不许留任何绕开深色缩放的写法。 */
    private fun lightingDrawIssues(drawn: String): List<String> = buildList {
        listOf(
            "style.sheen.copy(" to "仍有白色高光直接 style.sheen.copy，深色档不会收敛",
            "Color.Black.copy(" to "仍有黑色暗部直接 Color.Black.copy，深色档不会收敛",
            "RimLineColor" to "还在引用已作废的 RimLineColor",
        ).forEach { (needle, reason) ->
            if (drawn.contains(needle)) add(reason)
        }
        listOf("style.sheenAt(", "style.shadeAt(").forEach { entry ->
            if (!drawn.contains(entry)) add("绘制层没走 $entry")
        }
    }

    /** 规则 12 下半：三档一处定义、全链路接进 style，底栏阴影读 style 不写死。 */
    private fun lightingWiringIssues(glass: String, dock: String): List<String> = buildList {
        listOf(
            "fun sheenAt(" to "LiquidGlassStyle 没提供 sheenAt 统一入口",
            "fun shadeAt(" to "LiquidGlassStyle 没提供 shadeAt 统一入口",
            "fun glassLighting(darkTheme: Boolean)" to "缺深色光照档的单一定义点",
            "val lighting = glassLighting(darkTheme)" to "liquidGlassStyle 没取光照档",
            "lighting.sheenScale" to "sheenScale 没接进 style",
            "lighting.shadeScale" to "shadeScale 没接进 style",
            "lighting.shadowElevation" to "shadowElevation 没接进 style",
            "LiquidGlass.DARK_SHEEN_SCALE" to "深色档没引用 DARK_SHEEN_SCALE",
            "LiquidGlass.DARK_SHADE_SCALE" to "深色档没引用 DARK_SHADE_SCALE",
            "LiquidGlass.DarkShadowElevation" to "深色档没引用 DarkShadowElevation",
        ).forEach { (needle, reason) ->
            if (!glass.contains(needle)) add(reason)
        }
        if (!dock.contains(".shadow(elevation = glassStyle.shadowElevation")) {
            add("底栏阴影没读 style 的档位值")
        }
        if (dock.contains(".shadow(elevation = LiquidGlass.ShadowElevation")) {
            add("底栏阴影仍写死浅色档 8dp，深色下会重现脏边")
        }
    }

    // ----------------------------------------------------------------- helpers

    /** 取一个顶层 / DrawScope 扩展函数的**函数体**（大括号配平，跳过字符串里的括号）。 */
    private fun functionBody(text: String, name: String): String {
        val start = Regex("""fun\s+(?:DrawScope\.)?""" + Regex.escape(name) + """\s*\(""")
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
     * 去掉行注释与块注释，**保留换行**（行号才不会错位）。
     *
     * 与 `DialogStyleRulesTest.stripComments` 同一套写法：KDoc 里常写
     * `` `drawRect(...)` `` 举反例，不剥掉会把说明文字当成真实调用。
     * 拆成小函数是为了过 detekt `CyclomaticComplexMethod ≤14`。
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
