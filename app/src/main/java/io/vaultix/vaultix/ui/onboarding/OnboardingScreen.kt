/*
 * Vaultix — app:ui · onboarding
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件是**原创实现**。版式依据 `Docs/08-界面设计规格.md` §2 的 S2「引导（3 页）」，
 * 用 Material 3 的 `HorizontalPager` 与按钮组件实现，未复用任何上游源码或素材。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize
import kotlinx.coroutines.launch

/**
 * 首次启动引导（`Docs/08` §2 的 S2：**3 页**）。
 *
 * ## 准入条件（**不是"第一次装 App"**）
 *
 * 由 [io.vaultix.vaultix.ui.VaultixApp] 判定：**「一个库都没有」且「没看过引导」**。
 * 两个条件都必要，理由写在 `OnboardingPreferences` 的 KDoc 里 —— 简言之：
 * 老用户删光库之后不该再被引导一遍；而全新安装时"有没有库"这件事本身无法区分
 * "没看过"和"看过了但还没建库"。
 *
 * ## 三页各答一个问题
 *
 * | 页 | 它替用户回答的疑问 | 插画 |
 * |---|---|---|
 * | 1 | 我的密码放在哪、安全吗 | 盾牌 + 锁孔 |
 * | 2 | 我要接哪个库 / 能不能两个都接 | 两块**等大**的库 |
 * | 3 | 它除了存密码还能干什么 | 页面框 + 指纹 |
 *
 * ⚠️ 第 2 页插画里两块石头**刻意一样大**：文案说"两个地位一样"，图不能自己唱反调。
 *
 * ## 两条行为约定
 *
 * 1. **跳过 = 看完**：两者都写同一个标记。区分开会让"跳过"变成一个要用第二页弹窗
 *    再问一次的坑，而这页没有任何需要补问的东西；
 * 2. **第 3 页的「去开启自动填充」是可选动作**：点了只跳系统页，**不阻断**回主页。
 *    绝大多数 ROM 会跳到"选择自动填充服务"，用户在那里可能犹豫更久 —— 把他扣在
 *    引导页里等一个系统页面回来，是最典型的"引导变成路障"。
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == PAGES.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // 底部操作区自己留 24dp，上方内容区不需要再加 —— 两处都加会出现 40dp+ 的空洞。
            .padding(bottom = Spacing.xl),
    ) {
        // 顶栏只有一颗「跳过」，放在右上：这是引导页的通行位置（iOS / Android 均如此），
        // 用户在任何一个"被拦下来看说明"的场景里都会先往右上角找。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onFinished) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            // 每页内容自己可滚动：2.0x 字体下标题会折到 3 行，固定版式必然被裁。
            verticalAlignment = Alignment.CenterVertically,
        ) { index ->
            OnboardingPage(page = PAGES[index])
        }

        OnboardingIndicator(
            count = PAGES.size,
            current = pagerState.currentPage,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.lg))

        // 第 3 页在"下一步"上方多给一颗**可选**的次按钮；前两页没有它。
        if (isLastPage) {
            OutlinedButton(
                onClick = onOpenAutofillSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl),
            ) {
                Text(stringResource(R.string.onboarding_fill_setup))
            }
            Spacer(Modifier.height(Spacing.sm))
        }

        Button(
            onClick = {
                if (isLastPage) {
                    onFinished()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl)
                .height(UiSize.TouchTarget + Spacing.md),
        ) {
            Text(
                stringResource(
                    // 最后一页不写「下一步」：它已经是终点，写"下一步"会让人等第四个页面。
                    if (isLastPage) R.string.onboarding_start else R.string.action_next,
                ),
            )
        }
    }
}

/**
 * 一页的版式：插画 → 标题 → 正文。
 *
 * 三页共用同一个 `Column`，间距全部来自 `Spacing` 标尺 —— 每页各写一套间距是
 * "观感廉价"的头号来源（8.4），而且翻页时会看出文字块的基准线在跳。
 */
@Composable
private fun OnboardingPage(page: OnboardingPageSpec) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OnboardingArtworkImage(artwork = page.artwork)
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            // ⚠️ 不设 maxLines：2.0x 下中文标题可能折到 4 行，截断等于把话说不完。
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            // 正文宽度上限 320dp：平板 / 折叠屏展开时一行拉满 600dp 会读不下去。
            modifier = Modifier.widthIn(max = PAGE_TEXT_MAX_WIDTH),
        )
    }
}

/**
 * 翻页指示点：当前页是一条**拉长的胶囊**，其余是小圆点。
 *
 * 为什么不用 M3 的 `PrimaryTabRow` 那类：那是有文字标签的页签，三页引导不需要
 * 让用户点回去。胶囊 + 点是最省事的"你现在在第几页"表达。
 *
 * ⚠️ 整个指示器对无障碍服务**只说一句话**（"第 2 页，共 3 页"），
 * 而不是三个空盒子 —— 后者会被读成三个无标签元素。
 */
@Composable
private fun OnboardingIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.onboarding_page_indicator, current + 1, count)
    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(
                targetValue = if (selected) INDICATOR_ACTIVE_WIDTH else INDICATOR_DOT,
                label = "indicatorWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                label = "indicatorColor",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = INDICATOR_GAP / 2)
                    .size(width = width, height = INDICATOR_DOT)
                    .background(color = color, shape = CircleShape),
            )
        }
    }
}

/** 一页的静态描述（标题 / 正文 / 插画）。抽成数据是为了让「页数」只有一个真源。 */
private data class OnboardingPageSpec(
    val artwork: OnboardingArtwork,
    val titleRes: Int,
    val bodyRes: Int,
)

/**
 * 三页的内容表。
 *
 * ⚠️ **顺序即语义**："先知道这是什么 → 再决定接哪个库 → 最后才谈自动填充"。
 * 自动填充放最后一页是有意的：它是**装完 App 之后才有意义**的一步，
 * 放在第一页会让"还没接库"的用户去配一个空的填充服务。
 *
 * ⚠️ 列表长度 = 指示点个数 = `pageCount`，三者都从这里取，**不要另写数字 3**。
 */
private val PAGES = listOf(
    OnboardingPageSpec(
        artwork = OnboardingArtwork.Vault,
        titleRes = R.string.onboarding_title_vault,
        bodyRes = R.string.onboarding_body_vault,
    ),
    OnboardingPageSpec(
        artwork = OnboardingArtwork.TwoVaults,
        titleRes = R.string.onboarding_title_two_vaults,
        bodyRes = R.string.onboarding_body_two_vaults,
    ),
    OnboardingPageSpec(
        artwork = OnboardingArtwork.Autofill,
        titleRes = R.string.onboarding_title_fill,
        bodyRes = R.string.onboarding_body_fill,
    ),
)

/** 正文最大宽度（约 30 个汉字一行，中文的舒适行长）。 */
private val PAGE_TEXT_MAX_WIDTH = 320.dp

/** 指示点直径。 */
private val INDICATOR_DOT = 8.dp

/** 当前页胶囊的长度。 */
private val INDICATOR_ACTIVE_WIDTH = 24.dp

/** 相邻两点的间隙（实际留白是它的一半，因为每点两侧各加一半）。 */
private val INDICATOR_GAP = Spacing.sm
