/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「表达力顶栏 + 沉浸式状态栏」的规格移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）
 * 的 `ui/components/ExpressiveTopBar.kt`（其中滚动收起部分）：
 *   - **快照式**收起：调用方按「首个可见项偏移 > 8dp」判定 0/1，内部用 200ms 补间动画过渡
 *     （不是按滚动距离连续缩放）；
 *   - 标题字号 32sp → 16sp、栏最小高度 72dp → 48dp、上下内边距 8dp → 4dp、
 *     内容下移 8dp → 0dp；
 *   - 栏背景 = `surface` 的 alpha 在**收起后变 0**（内容从栏下方穿过 = 沉浸），
 *     且背景在 `statusBarsPadding()` **之前**绘制 → 覆盖状态栏区域（状态栏沉浸）；
 *   - 右侧动作按钮**不套容器**（2026-09-21 去掉了上游的 `Surface` 胶囊 —— 理由见
 *     [VaultixExpressiveTopBar] 里那段说明）；收起时整组缩放 1.0 → 0.85，
 *     内容色在收起时向 `onSurfaceVariant` 过渡；
 *   - 标题过长时在**组合期一次算好排布**（2026-10-01 起：一行放不下就**折两行**
 *     —— 施工单 L2 —— 两行仍放不下才等比缩字号，下限 0.72；再放不下才由
 *     `TextOverflow.Ellipsis` 兜底）。⚠️ 旧实现在 `onTextLayout` 里发现溢出再改 state 缩一档
 *     ⇒ 必然"先按满字号画一帧、下一帧才变小"（用户 2026-09-30 反馈的"变大变小"）。
 * 本文件为独立实现（去掉了上游与搜索框、左右滑手势、标题点击展开耦合的部分 ——
 * Vaultix 的搜索态走独立的固定高度顶栏，见 `VaultixSearchTopAppBar`）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import io.vaultix.vaultix.ui.theme.Spacing

/** 收起/展开的判定阈值（首个可见项偏移超过它即视为「已收起」）。 */
private val COLLAPSE_THRESHOLD = Spacing.sm

/** 动画时长：与上游一致（200ms；按钮胶囊走 spring）。 */
private const val ANIM_MS = 200

/**
 * 展开态标题字号（sp）。
 *
 * ⚠️ 2026-09-14 从 **32sp 收到 26sp**（用户反馈「Bitwarden 这个文字和右边 3 个按钮隔太近了」）。
 * 成因是**同一天的另一处改动**：顶栏动作胶囊从 2 个图标变成 3 个（加了「显示选项」），
 * 胶囊变宽 ⇒ 标题可用宽度变小 ⇒ 32sp 的库名一路顶到胶囊边上。
 * 收字号而不是裁字数：库名对用户是「我在看哪个库」的唯一线索（KDBX 侧更是文件名），
 * 不能靠省略号牺牲它。
 */
private const val TITLE_EXPANDED_SP = 26f

/** 收起态标题字号（sp）。 */
private const val TITLE_COLLAPSED_SP = 16f

/**
 * **两行**排布时的字号上限（施工单 L2，2026-10-01）。
 *
 * ⚠️ 为什么两行要单独一个更小的上限：栏高是**固定**的（`BAR_EXPANDED` = 72dp），
 * 而列表顶部让位的高度也是按它算的（`rememberImmersiveBarPadding`）。
 * 26sp 排两行 ≈ 62dp 文字 + 16dp 上下内边距 = 78dp ⇒ **超出预留 6dp**，
 * 表现就是第一条内容被顶栏压住一点点（很隐蔽，只有长库名的库才有）。
 * 20sp 两行 ≈ 48dp + 16dp = 64dp ⇒ 留在 72dp 里，不需要动栏高、不影响别的页面。
 */
private const val TITLE_TWO_LINE_SP = 20f

/** 标题缩放的下限（低于它就宁可走省略号，也不再继续缩 —— 再小就不好读了）。 */
private const val TITLE_MIN_SCALE = 0.72f

/**
 * 副标题（小字）字号（sp）与它与库名之间的间距。
 *
 * ⚠️ 2026-10-04：顶栏方案 β —— **库名保持 26sp 不折行，筛选名单独一行小字**。
 *
 * ## 为什么是「两行小字」而不是「一行里缩字号」
 *
 * 此前是拼成一个 26sp 的串（`库名 · 筛选名`），放不下就折两行（`Bitwarden · 验证` / `码`）。
 * 真机观感差有两个原因，且**都不是字号问题**：
 * ① 折行后第二行往往只剩一两个字（孤字），读起来像"标题坏了"；
 * ② 为了塞进两行，字号从 26sp 掉到 20sp，**库名这个最重要的信息反而被降级**。
 *
 * ⇒ 改成两级信息：**库名 26sp 一行**（不折）+ **筛选名 14sp 独立一行**。
 *   主次分明，且两个字号**都不用缩**。
 *
 * ## 为什么 14sp 不会把顶栏撑破（曾担心要加高栏，此处已算清）
 *
 * 栏高预留是固定的 [BAR_EXPANDED] = 72dp，而实际内容高 =
 * `26 × 1.2`（[LINE_HEIGHT_RATIO]）+ `2`（间距）+ `14 × 1.2` + `上下内边距 2 × 8`
 * = `31.2 + 2 + 16.8 + 16` = **66dp ≤ 72dp**（余 6dp）。
 * ⇒ **不需要动 [BAR_EXPANDED]，也不需要动 [rememberImmersiveBarPadding]** ——
 *   列表顶部让位那条链（`itemsTopInset` / `filterRowInset` / `QUICK_FILTER_ROW_HEIGHT`）
 *   一行都不用改，"加高 18dp 导致列表让位错位"这个风险因此不存在。
 */
private const val TITLE_SUBTITLE_SP = 14f

private val TITLE_SUBTITLE_SPACING = 2.dp

/**
 * 顶栏标题：**主标题 + 可选副标题**（上下两行，不拼接）。
 *
 * ## 为什么不直接给 [VaultixExpressiveTopBar] 加一个 `titleSubtitle` 参数
 *
 * 加了就是 9 个参数，越过 detekt `LongParameterList` 的 8 个硬上限（见 `config/detekt/detekt.yml`）。
 * 而「标题本来就是两级信息」用类型表达更准确 ⇒ 收成 data class：
 * ① 参数数**不变**（仍 8 个）；
 * ② 其余 5 个页面只需把 `title = stringResource(…)` 包成 `title = TopBarTitle(stringResource(…))`，
 * 语义零变化（副标题缺省即 null = 只有一行，与今天完全等价）。
 *
 * @property text 主标题（库名 / 页面名），渲染为 26sp。
 * @property subtitle 副标题（当前筛选名），渲染为 14sp 独立一行；null = 不渲染第二行。
 */
data class TopBarTitle(
    val text: String,
    val subtitle: String? = null,
)

/**
 * 副标题的读屏文案：`库名, 筛选名, 操作提示`。
 *
 * ⚠️ 与过去拼串不同：过去无障碍描述是 `"Bitwarden · 验证, 点按展开或收起分类筛选"`，
 * 筛选名被 `·` 混在库名里；现在拆开报，读屏用户能听清"这是哪个库 / 现在在看哪一类"。
 */
private fun TopBarTitle.spokenLabel(hint: String): String =
    if (subtitle == null) "$text, $hint" else "$text, $subtitle, $hint"

/** 可点标题时给右侧展开箭头预留的宽度（测量标题时要从可用宽度里扣掉）。 */
private val TITLE_CHEVRON_RESERVE = 30.dp

/**
 * 折叠动画期间字号是连续的（26sp → 16sp），而**测量是逐次的**。
 * 按 0.5sp 分桶做缓存键：整段动画约 20 次测量（可忽略），又足够平滑（相邻桶只差 0.5sp）。
 */
private const val FONT_BUCKET_DIVISOR = 2f

/**
 * 标题的排布结论：字号 + 行数。
 *
 * ⚠️ 为什么要带行数（2026-10-01，施工单 L2）：用户反馈「左边的标题 `bitwarden` 或者
 * `xxxx.kdbx` 显示很长，**滑动缩小时可以分成两栏显示**」。
 * 此前唯一的应对是**一路缩字号**（下限 0.72），本质是在**牺牲可读性**换"放得下" ——
 * 而"我在哪个库"恰恰是顶栏唯一不能丢的信息（KDBX 侧就是文件名）。
 * ⇒ 一行放不下时改为**折成两行**（同一份信息换个排布，不丢内容），
 *   两行仍放不下才继续缩字号，最后才交给省略号。
 *
 * ⚠️ 「两栏」在手机上落地就是「两行」：6 寸屏左右各占一半只会把两栏**都**压窄，
 *   结果是两边都读不清。施工单 L2 的三个候选项里取的是 ①（标题两行）。
 */
private data class TitleLayout(val fontSp: Float, val maxLines: Int)

/**
 * 标题在 [availablePx] 内怎么排（字号 + 行数；字号下限 `fontSp * TITLE_MIN_SCALE`）。
 *
 * @param allowTwoLines 是否允许**折两行**（见参数说明）。
 *
 * ## ⚠️ `allowTwoLines` 为什么由调用方决定（2026-10-04，方案 β）
 *
 * 「折两行」这套应对是为**单串标题**设计的（一个 26sp 字符串放不下 ⇒ 换行）。
 * 一旦有了 [TopBarTitle.subtitle]，第二行已经被副标题占了 —— 此时再让主标题折行，
 * 栏高就变成 `库名两行 + 副标题一行 = 三行`（66dp → 97dp），**直接撑破 [BAR_EXPANDED]**，
 * 顶栏会压到第一条内容上。
 *
 * ⇒ 有副标题时传 `false`：库名要么原样、要么**缩字号**（下限 [TITLE_MIN_SCALE]）、
 *   最后才省略，**任何情况下都不折行**。
 *
 * ## 为什么在**组合期**算，而不是"画完发现溢出再缩"
 *
 * 旧实现在 `onTextLayout` 回调里判断 `hasVisualOverflow` 然后改 state 缩一档：
 * 那是**布局之后**才知道溢出，于是必然按原字号先画一帧、下一帧才变小。
 * 用户看到的就是标题"闪一下再缩"（2026-09-30 反馈「左侧有个变大变小的情况」），
 * 而且一次布局只缩一档 ⇒ 特别长的标题要连跳好几帧才稳住。
 *
 * ⇒ 改成组合期一次算到底：**同一帧内**就决定字号，没有任何可见的中间态。
 *   代价是一次文本测量（几十微秒），换来的是确定性 —— 同样的输入永远得到同样的字号。
 *
 * ## 为什么不直接换成省略号（像 [MiddleEllipsizedText] 那样）
 *
 * 顶栏标题的**完整可读**比"字号绝对稳定"更重要：它是用户确认"我在哪个库"的第一眼信息，
 * 折成 `我的密…码库` 反而认不出。
 *
 * ⇒ 三级应对（2026-10-01 起的顺序，**先换行、再缩字、最后才省略**）：
 * ① 一行放得下 ⇒ 原样；② 一行放不下 ⇒ **折两行**（见 [TitleLayout]）；
 * ③ 两行仍放不下 ⇒ 缩字号（下限 [TITLE_MIN_SCALE]）；
 * ④ 连最小字号都放不下 ⇒ 交给 `TextOverflow.Ellipsis`（那时至少说明"后面还有"）。
 *
 * ## ⚠️ 两行那一步是**估算**，不是真换行
 *
 * 判定用的是 `文本总宽 ≤ 可用宽 × 2`，即"**假设两行都填满**"。
 * 真换行是贪心的：除最后一行外每行未必 100% 填满，所以理论上存在
 * "总宽刚好卡在 2 倍以内、但实际要 3 行"的边界情况（断点很不巧时）。
 *
 * ⇒ 该情况的后果是**第 ④ 级兜底**（第二行尾部出现省略号），不会溢出、不会崩、不会压到按钮。
 * 之所以不改成"按 constraints 真测一次"：真测要引入 `Constraints(maxWidth = …)` 参数，
 * 与同项目 [MiddleEllipsizedText] 的宽度比较写法不再一致，而收益只是消掉一个很少命中的边界。
 */
private fun fitTitleLayout(
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    title: String,
    availablePx: Int,
    fontSp: Float,
    allowTwoLines: Boolean = true,
): TitleLayout {
    if (availablePx <= 0 || title.isEmpty()) return TitleLayout(fontSp, maxLines = 1)
    val measured = measurer
        .measure(text = AnnotatedString(title), style = baseStyle)
        .size.width
    if (measured <= availablePx) return TitleLayout(fontSp, maxLines = 1)

    // ⚠️ 收起态（16sp）**不折两行**：小字号折两行看着像"标题换行了"而不是"分栏"，
    //    且收起态栏高只有 48dp，两行会把右侧按钮组挤下去。⇒ 收起态维持老行为（缩字号）。
    //    有副标题时同理（第二行已被占用）—— 见 KDoc 的 `allowTwoLines`。
    if (allowTwoLines && fontSp > TITLE_COLLAPSED_SP) {
        val twoLineSp = minOf(fontSp, TITLE_TWO_LINE_SP)
        val twoLineStyle = baseStyle.copy(fontSize = twoLineSp.sp)
        val measuredTwoLine = measurer
            .measure(text = AnnotatedString(title), style = twoLineStyle)
            .size.width
        val twoLineAvailablePx = availablePx * 2
        if (measuredTwoLine <= twoLineAvailablePx) return TitleLayout(twoLineSp, maxLines = 2)
        // 两行也放不下 ⇒ 才继续缩（下限同单行）：此时至少已经把两行用完。
        val ratio = twoLineAvailablePx.toFloat() / measuredTwoLine.toFloat()
        return TitleLayout(
            fontSp = (twoLineSp * ratio).coerceAtLeast(twoLineSp * TITLE_MIN_SCALE),
            maxLines = 2,
        )
    }

    // 按比例缩放（不是逐档试）：一次到位，且不同长度的标题各自得到合适的字号。
    val ratio = availablePx.toFloat() / measured.toFloat()
    return TitleLayout(
        fontSp = (fontSp * ratio).coerceAtLeast(fontSp * TITLE_MIN_SCALE),
        maxLines = 1,
    )
}

/** 行高相对字号的倍数（上游同款：`lineHeight = fontSize * 1.2`）。 */
private const val LINE_HEIGHT_RATIO = 1.2f

/** 展开态 / 收起态栏高（不含状态栏内边距）。 */
private val BAR_EXPANDED = 72.dp
private val BAR_COLLAPSED = 48.dp

/**
 * 滚动收起的**快照**进度：0 = 展开，1 = 收起（对齐 Bastion 的 `derivedStateOf` 写法）。
 *
 * 快照而非连续：连续缩放会让标题在滚动过程中一直在抖，而「越过一点就整体切换」的观感
 * 更干净（切换本身由 [VaultixExpressiveTopBar] 内部 200ms 动画完成）。
 */
@Composable
fun rememberScrollCollapseFraction(listState: LazyListState): Float {
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) {
        COLLAPSE_THRESHOLD.toPx()
    }
    val collapsed by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > threshold
        }
    }
    return if (collapsed) 1f else 0f
}

/** 同上，供 `Column + verticalScroll` 的页面（设置页）使用。 */
@Composable
fun rememberScrollCollapseFraction(scrollState: ScrollState): Float {
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) {
        COLLAPSE_THRESHOLD.toPx()
    }
    val collapsed by remember(scrollState) {
        derivedStateOf { scrollState.value > threshold }
    }
    return if (collapsed) 1f else 0f
}

/**
 * 列表顶部要让出的高度 = 状态栏内边距 + 当前栏高（随收起动画一起变）。
 *
 * 展开时首条内容不被顶栏遮住；收起后让出的高度缩到 48dp，内容随之**滑到半透明顶栏下方**，
 * 这正是「沉浸」的来源（对齐 Bastion 的 `listTopPadding`）。
 */
@Composable
fun rememberImmersiveBarPadding(collapseFraction: Float): Dp {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight by animateDpAsState(
        targetValue = lerp(BAR_EXPANDED, BAR_COLLAPSED, collapseFraction),
        animationSpec = tween(ANIM_MS),
        label = "immersive_bar_height",
    )
    return statusBar + barHeight
}

/**
 * 顶栏标题块：**库名（26sp）+ 可选副标题（14sp）上下两行**（2026-10-04 方案 β）。
 *
 * ## 抽出来的理由（detekt `LongMethod` ≤150）
 *
 * 之前这段直接内联在 [VaultixExpressiveTopBar] 里，加了副标题后函数体 159 行越界。
 * 顺带的好处是**标题的排版契约被收在一处**：测量 / 折行判定 / 副标题出现条件
 * 只此一份，其它页面（`title.subtitle == null`）走的是完全相同的旧路径。
 *
 * @param subtitleAlpha 副标题透明度（随收起 1→0，见调用处）。
 * @param reserveChevron 标题右侧是否还有展开箭头（有则要从可用宽度里扣掉 [TITLE_CHEVRON_RESERVE]）。
 */
@Composable
private fun TitleBlock(
    title: TopBarTitle,
    titleFontSize: Float,
    subtitleAlpha: Float,
    contentColor: Color,
    reserveChevron: Boolean,
) {
    // `BoxWithConstraints`：可用宽度**在组合期**就能拿到（不像 `onTextLayout`
    // 要等布局完），所以能一次算好字号、不产生"先大后小"的跳变。
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // 保留 base 样式（letterSpacing / 字重都在里面）：只量裸文本宽度会与
        // 实际渲染宽度差一截，而差在哪里恰恰是"文字多的语言"最容易出问题的地方。
        val baseStyle = MaterialTheme.typography.headlineSmall
        // 可点时标题右边还有个展开箭头，要从可用宽度里扣掉，否则标题会与箭头相撞。
        val reservePx = with(density) {
            (if (reserveChevron) TITLE_CHEVRON_RESERVE else 0.dp).roundToPx()
        }
        // 量化字号缓存键：折叠动画期间 `titleFontSize` 是**连续**变化的（26→16sp），
        // 直接用它会每帧都重算一次测量。按 0.5sp 粒度分桶 ⇒ 整段动画约 20 次测量，
        // 既便宜又足够平滑（每桶之间字号只差 0.5sp，看不出台阶）。
        val fontBucket = (titleFontSize * FONT_BUCKET_DIVISOR).roundToInt()
        val fitted = remember(
            title.text,
            constraints.maxWidth,
            reservePx,
            fontBucket,
            baseStyle,
        ) {
            fitTitleLayout(
                measurer = measurer,
                baseStyle = baseStyle.copy(fontSize = titleFontSize.sp),
                title = title.text,
                availablePx = constraints.maxWidth - reservePx,
                fontSp = titleFontSize,
                // ⚠️ 有副标题时第二行已被占用 ⇒ 库名**绝不折行**，只缩字号。
                allowTwoLines = title.subtitle == null,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(TITLE_SUBTITLE_SPACING)) {
            Text(
                text = title.text,
                style = baseStyle,
                fontSize = fitted.fontSp.sp,
                lineHeight = (fitted.fontSp * LINE_HEIGHT_RATIO).sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                // ⚠️ 1 或 2 行由 `fitTitleLayout` 在**组合期**定好：放得下就一行，
                //    放不下折两行（施工单 L2），不是"渲染后再补救"。
                maxLines = fitted.maxLines,
                // ⚠️ 兜底是省略号而**不是** `Clip`：连最小字号都放不下时，
                //    把尾巴裁掉会让用户以为标题就这么短；省略号至少说明"后面还有"。
                overflow = TextOverflow.Ellipsis,
                // ⚠️ 必须允许换行，否则两行排布根本不会发生（`softWrap = false`
                //    会把所有文本按一行量，再交给省略号）。
                softWrap = true,
            )
            if (title.subtitle != null) {
                Text(
                    text = title.subtitle,
                    style = MaterialTheme.typography.labelLarge,
                    // ⚠️ 副标题**字号不参与折叠动画**：它跟着库名一起消失，
                    //    不需要再从 14sp 缩到 16sp（那会让"消失"变成"缩小"，更乱）。
                    //    固定 14sp 是它作为副标题的语义身份。
                    fontSize = TITLE_SUBTITLE_SP.sp,
                    lineHeight = (TITLE_SUBTITLE_SP * LINE_HEIGHT_RATIO).sp,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true,
                    // ⚠️ 用 `graphicsLayer`（**绘制期**读 alpha）而不是 `alpha()`：
                    //    后者会让整个 Text 离开渲染快路径；折叠动画每帧都跑，不该付这个代价。
                    //    同时 alpha=0 时**仍占位** —— 这正是我们要的：栏高平滑收缩，不跳变。
                    modifier = Modifier.graphicsLayer { alpha = subtitleAlpha },
                )
            }
        }
    }
}

/**
 * 沉浸式顶栏（大标题随滚动缩小、栏背景收起后透明、右侧按钮胶囊悬浮）。
 *
 * ⚠️ 必须**浮在内容之上**使用（`Box { 列表; 本顶栏 }`），并且列表顶部留白取
 * [rememberImmersiveBarPadding]：否则「收起后透明、内容从下方穿过」不成立。
 *
 * @param collapseFraction 0 = 展开（大标题、栏不透明），1 = 收起（小标题、栏透明）。
 * @param navigationIcon 左侧返回等（tab 内页不传）。
 * @param actions 右侧动作按钮（自动装进胶囊并跟随时机缩放）。
 * @param onTitleClick 非空时标题可点（Vaultix 用于「点库名展开快捷筛选」，对齐 Bastion
 *   `ExpressiveTopBar(onTitleClick, titleExpanded)`）；此时标题后追加一个展开/收起箭头。
 * @param titleExpanded 箭头方向（true = 已展开显示收起箭头）。仅当 [onTitleClick] 非空时渲染。
 * @param titleClickHint 标题可点的无障碍提示（拼在标题后，供读屏用户知道「点它有东西」）。
 */
@Composable
fun VaultixExpressiveTopBar(
    title: TopBarTitle,
    collapseFraction: Float,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    titleExpanded: Boolean = false,
    titleClickHint: String = "",
) {
    // 所有过渡量走同一条 200ms 补间，保证「标题、栏高、按钮组」同步到位（上游同款取舍）。
    val progress by animateFloatAsState(
        targetValue = collapseFraction,
        animationSpec = tween(ANIM_MS),
        label = "topbar_collapse_progress",
    )
    val titleFontSize by animateFloatAsState(
        targetValue = TITLE_EXPANDED_SP + (TITLE_COLLAPSED_SP - TITLE_EXPANDED_SP) * collapseFraction,
        animationSpec = tween(ANIM_MS),
        label = "topbar_title_size",
    )
    val verticalPadding by animateDpAsState(
        targetValue = lerp(Spacing.sm, Spacing.xs, collapseFraction),
        animationSpec = tween(ANIM_MS),
        label = "topbar_vpadding",
    )
    val contentOffset by animateDpAsState(
        targetValue = lerp(Spacing.sm, 0.dp, collapseFraction),
        animationSpec = tween(ANIM_MS),
        label = "topbar_content_offset",
    )
    // 栏背景：展开不透明（并覆盖状态栏区域），收起透明 → 内容从下方穿过。
    val barBackgroundAlpha by animateFloatAsState(
        targetValue = if (collapseFraction < 0.5f) 1f else 0f,
        animationSpec = tween(ANIM_MS),
        label = "topbar_bg_alpha",
    )
    val contentColor by animateColorAsState(
        targetValue = lerp(
            MaterialTheme.colorScheme.onBackground,
            MaterialTheme.colorScheme.onSurfaceVariant,
            collapseFraction,
        ),
        animationSpec = tween(ANIM_MS),
        label = "topbar_content_color",
    )
    // ⚠️ 副标题随**收起**淡出（不是随展开淡入 —— 展开态它就该在）。
    //    用 `1 - collapseFraction` 而不是布尔切换：布尔切换会让第二行在 200ms 动画里
    //    "啪"地一下出现/消失，栏高也跟着一跳；淡出则与字号、栏高、按钮组同一条补间。
    //    展开态恒为 1，收起态趋近 0 ⇒ 用户看不到"第二行去哪了"。
    val subtitleAlpha by animateFloatAsState(
        targetValue = (1f - collapseFraction).coerceIn(0f, 1f),
        animationSpec = tween(ANIM_MS),
        label = "topbar_subtitle_alpha",
    )
    // ⚠️ 标题的"过长怎么办"**不在渲染之后再补救** —— 见 [fitTitleLayout]。
    //    旧写法是在 `onTextLayout` 里发现溢出就改 state 缩一档、下一帧再量再缩（上限 0.72）。
    //    那必然先按满字号画一帧、再往下跳，用户看到的就是"左侧标题先大后小地动了一下"
    //    （2026-09-30 用户反馈：「左侧有个变大变小的情况，视觉体验不太好」）。
    //    ⇒ 改成在**组合期一次算好**：不产生跳变，也不依赖"布局回调改状态"这种脆弱链路。

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BAR_COLLAPSED)
            // ⚠️ 背景必须在 statusBarsPadding 之前 → 覆盖到状态栏区域（状态栏沉浸）。
            .background(MaterialTheme.colorScheme.surface.copy(alpha = barBackgroundAlpha))
            .statusBarsPadding()
            .padding(horizontal = Spacing.lg, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(y = contentOffset)
                .padding(end = ACTIONS_RESERVE),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            navigationIcon?.invoke()
            val titleText: @Composable () -> Unit = {
                TitleBlock(
                    title = title,
                    titleFontSize = titleFontSize,
                    subtitleAlpha = subtitleAlpha,
                    contentColor = contentColor,
                    reserveChevron = onTitleClick != null,
                )
            }
            if (onTitleClick == null) {
                titleText()
            } else {
                // 可点标题：整块（标题 + 箭头）是一个按钮，箭头方向反映展开态。
                // 对齐 Bastion —— 标题就是「展开快捷筛选」的开关，不必再多一个图标按钮。
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(TITLE_CLICK_CORNER_DP.dp))
                        .clickable(role = Role.Button, onClick = onTitleClick)
                        .padding(horizontal = Spacing.xs, vertical = 2.dp)
                        .semantics { contentDescription = title.spokenLabel(titleClickHint) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    titleText()
                    Icon(
                        imageVector = if (titleExpanded) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = null,
                        modifier = Modifier.size(TITLE_CHEVRON_SIZE),
                        tint = contentColor,
                    )
                }
            }
        }

        // 右侧动作按钮：**不套任何容器**。
        //
        // ⚠️ 2026-09-21 用户反馈：「（条目列表）往下滑动的时候，右上角三个按钮的胶囊是
        // 深色的，很不好看，直接透明的呗。不要这些效果。」
        //
        // 此前这里是一个 `Surface` 胶囊（`shape` = 50% 药丸、`color` = `surface` 同色、
        // `shadowElevation` = 1dp、`tonalElevation` = 0），且已经返工过两轮 ——
        // 但两轮都只在调"阴影何时变"，没人注意到更隐蔽的那半：
        //
        // **收起态下胶囊的填充 `alpha` = 0（本意是"透明"），可 `shadowElevation` 照画。**
        // 于是「全透明填充 + 1dp 阴影」合成出一圈**比背景更深的灰**
        // —— 在浅色列表上这就是一个实实在在的"深色胶囊"，不是"阴影太淡"的问题。
        // 用户报的"深色胶囊"就是这个：**不是颜色算错，是阴影在没有底色垫着的地方单独可见**。
        //
        // ⇒ 删掉整个容器（而不是把阴影调成 0）：用户要的是"按钮直接浮着"。
        // 展开态按钮与栏底色同色（本来就是同一个 `surface`）、收起态栏与按钮一起全透明
        // —— 这才与 [VaultixExpressiveTopBar] 的"收起即隐身"语义自洽。
        // 按钮组保留 0.85 缩放与 `contentColor` 过渡：收起这件事仍有反馈，
        // 且两者都不产生额外图层（不像 `Surface` 的阴影要重建轮廓缓存）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = contentOffset),
            contentAlignment = Alignment.CenterEnd,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Row(
                    modifier = Modifier
                        .graphicsLayer {
                            val scale = 1f + (0.85f - 1f) * progress
                            scaleX = scale
                            scaleY = scale
                        }
                        .padding(horizontal = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/**
 * 顶栏右侧为动作按钮预留的宽度（避免长标题压到按钮上）。
 *
 * ⚠️ 2026-09-14：`144.dp` → **`156.dp`**。144dp 正好等于「3 个 48dp 触控目标」的宽度
 * （实测按钮组跨度 504px @3.5x = 144dp，当时外面还套着胶囊），也就是说标题**紧贴**
 * 按钮边缘、两者之间没有任何余量 —— 用户看到的「隔太近」正是这 0 余量。
 * 多出的 12dp 是留给标题与按钮之间的呼吸位。
 *
 * ⚠️ 2026-09-21 胶囊删除后这个值**不需要变**：按钮的 48dp 触控目标没动，
 * 变的只是它们外面的那层壳。
 */
private val ACTIONS_RESERVE = 156.dp

/** 可点标题的圆角（dp）与箭头尺寸 —— 与 Bastion 的 8dp / 18-22dp 对齐。 */
private const val TITLE_CLICK_CORNER_DP = 8
private val TITLE_CHEVRON_SIZE = 20.dp
