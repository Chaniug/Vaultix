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
 * 条目**卡片外框**的规格移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `ui/password/PasswordEntryCard.kt`：
 *   - 容器用 Material3 `Card`，圆角 `RoundedCornerShape(12.dp)`
 *     （上游「稀疏列表卡片」取值；仅单卡态用 16dp）；
 *   - 内边距 16dp 四边等宽（上游 `padding(if (isSingleCard) 20.dp else 16.dp)`）；
 *   - 点击：先 `clip(shape)` 再 `clickable`，水波纹被裁进圆角内（上游同款写法）；
 *   - 标题字重 SemiBold、标题与副标题之间 `spacedBy(6.dp)`（上游列表态取值）。
 *
 * ⚠️ **2026-10-05 刻意偏离上游一处**：上游用 `CardDefaults.cardColors()` 默认底色，
 *   即 `surfaceContainerHighest`。本项目改为显式 `surfaceContainerLow` ——
 *   依据 `Docs/07-Material3设计系统.md` §1「条目卡片用 surface-container-low」。
 *   详见 [EntryCard] 函数上方的「色档违规」说明。
 * 本文件为独立实现，不含其代码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 卡片圆角。
 *
 * ⚠️ 2026-09-16 **从 12dp 提到 16dp**（用户要求「好看一点，不用那么理性克制」）。
 * 这是**有意偏离**上游 Bastion 的「稀疏列表卡片 = 12dp」规格：
 * 12dp 在列表里偏方正、显得"平"，16dp 与设置页卡片（20dp）更接近，观感更柔和成套。
 */
private val CARD_CORNER = 16.dp

/** 卡片内边距（保持 Bastion 列表态 = 16dp 四边等宽）。 */
private val CARD_PADDING = Spacing.lg

/** 卡片描边宽度。 */
private val CARD_BORDER_WIDTH = 0.5.dp

/**
 * 条目卡片外框 —— 密码 / 验证码 / 卡包三个列表共用（保证三处观感完全一致）。
 *
 * ## 为什么需要一个共用组件（而不是各页各写一个 Surface）
 * 三个列表此前都是 `Surface(color = colorScheme.surface, shape = shapes.large)`：
 * **底色与页面背景同色、且没有任何高度** ⇒ 卡片边界几乎看不见，条目看起来只是
 * 「悬浮在背景上的一行字」。用户反馈的「密码条目外框不够精致」即指此处。
 *
 * 改成本组件后：
 * - 底色/高度随主题（含动态取色）自动分层 —— 这正是 M3 filled card 的语义；
 * - 三处列表自动统一，不会再出现「密码页是卡片、卡包页是裸行」这种不一致
 *   （卡包此前**完全没有**外框，只有 `clickable` 的 Row）。
 *
 * ⚠️ 这与「搬 Bastion 的壳」无关：壳（底部导航 / Tab 容器 / 转场）已迁移完毕，
 * 本条属于**组件级视觉规格**。
 *
 * ## 长按多选（2026-09-13，对齐 Bastion `TotpCodeCard` 的 `cardInteractionModifier`）
 * 上游把条目交互分成两态，本项目照此接线：
 * - **非选择态**：`combinedClickable(onClick = 打开/复制, onLongClick = 进入选择模式)`；
 * - **选择态**：`onLongClick = null` + `onClick = 勾选/取消`（长按不再有语义，避免与
 *   外层 [PressAndSwipeToDelete] 的手势抢事件）；
 * - 选中时卡片换成 `secondaryContainer` 底色 —— 不给视觉反馈的话，用户看不出哪几条被选中。
 *
 * ⚠️ 2026-09-16：**选择态现在还是「可左滑删除」的前置门槛**
 * （[PressAndSwipeToDelete] 的 `selectable`）。所以上面这条"选中给视觉反馈"不再只是
 * 多选的美观问题 —— 用户要**先看到哪条被选中**，才敢去滑它。
 * 底色高亮因此是功能性的，不能省。
 *
 * ## 色档：为什么是 `surfaceContainerLow`（2026-10-05 修配色 bug）
 *
 * 原先这里写的是 `CardDefaults.cardColors()` 默认值 ⇒ 底色落在
 * `surfaceContainerHighest`。这有两处问题：
 *
 * 1. **违反项目自己的设计文档**。`Docs/07-Material3设计系统.md` §1「清晰层级」
 *    明文写着「容器色（`surface-container*`）分层替代阴影；**条目卡片用
 *    `surface-container-low`**」—— 而 `EntryCard` 是全项目**唯一**用到最高档的卡片，
 *    其余卡片（`SettingsComponents.SettingsGroupCard`、`VaultListScreen.VaultCard`、
 *    `ItemDetailScreen`、`AddKdbxScreen`）全部是 `surfaceContainerLow`。
 * 2. **观感上确实不对**。实测（M3 亮色基线 vs `surface`）：
 *    `Low` = 1.049 · `High` = 1.164 · **`Highest` = 1.232**。
 *    1.232 是 M3 容器家族里与 `surface` 拉得最开的一档 ⇒ 列表里每张卡都是一块
 *    明显的色斑，用户反馈「默认浅色界面配色不对」即源于此（与当时基线色板偏紫叠加）。
 *
 * ⇒ 改为 `surfaceContainerLow`，与项目其余卡片对齐。
 *
 * ⚠️ **别照抄 `ItemFormDialog.FormGroupCard` 的 `surfaceContainerHighest`**：
 *    它用最高档是有**具体理由**的 —— 全屏对话框的顶栏「收起即透明」，
 *    卡片需要明显一档的底色才能"可见地"从栏下滑过（该文件 KDoc 有完整推导）。
 *    本卡片没有这个约束，跟着抄只会引入另一处不一致。
 *
 * @param onClick 点击条目（水波纹被裁进圆角内）。
 * @param onLongClick 长按条目；为 `null` 时不挂长按（选择态）。
 * @param selected 是否处于选中态（改底色，不画 Checkbox —— 勾选项由调用方决定放哪）。
 * @param content 卡片内容（纵向排列；需要横排时在里面再放一个 `Row` 即可）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CARD_CORNER)
    val baseColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (selected) {
            baseColors.copy(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            baseColors
        },
        elevation = CardDefaults.cardElevation(),
        shape = shape,
        // 2026-09-16 新增极细描边。
        // 原因：M3 filled card 的默认底色与页面背景的明度差本就小，在**动态取色**下
        // 更不可靠（底色由壁纸派生）⇒ 卡片的边界得靠"猜"。
        // 描边把边界**说死**，且它不吃底色、不与动态取色打架 —— 比反复调底色稳。
        //
        // ⚠️ 2026-10-05 底色降到 surfaceContainerLow（对比度 1.049）后，
        //    这道描边**更重要了**：卡片边界现在几乎全靠它。
        //    `ItemFormDialog.FormGroupCard` 那边明确写了「别指望卡片自己带边框来救」——
        //    那是**因为它不需要**（顶栏透明、卡片要"穿过"标题栏）。
        //    本卡片没有那个约束，描边是边界的主要来源，别删。
        border = BorderStroke(CARD_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(
                    if (onLongClick == null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    },
                )
                .padding(CARD_PADDING),
            content = content,
        )
    }
}

/**
 * 卡片内「标题 + 副标题」的**视觉**间距。
 *
 * ⚠️ 2026-10-01 **从 6dp 改成 4dp**，原因不是"想再紧一点"，而是**排印行高在骗人**：
 * `titleMedium` 的行高是 24sp、字面高约 16sp ⇒ 光标题自身上下就各留了 ~4dp 的
 * **行高余量**。所以"6dp 的 `spacedBy`"在屏幕上呈现出来的是
 * **6 + 4 = 10dp 的缝**，而`bodyMedium`（20sp 行高 / 14sp 字面）又贡献 ~3dp。
 * 两行文本之间的实际空隙因此接近 **13dp**，在一张 16dp 内边距的卡片里
 * 会读成"标题和副标题是两件不相干的事"。
 *
 * ⇒ 取 `Spacing.xs`(4dp) 与行高余量相抵，**落在屏幕上的视觉间距回到 ~8dp**
 * （正好是标尺的 `sm` 档 —— 这也是"视觉间距"与"布局间距"必须分开算的实例）。
 *
 * @see EntryCardTitleLineHeight
 */
val EntryCardTextSpacing = Spacing.xs

/**
 * 卡片内标题的**行高收窄**（`titleMedium` 的 24sp → 21sp）。
 *
 * ⚠️ 这是"左边标题部分排版"的另一半。原状是直接用 `typography.titleMedium`
 * （16sp / **24sp** 行高），24sp 是 M3 给"可能换行的正文"准备的，而条目卡片的
 * 标题在列表里**几乎永远是一行**、且被 `maxLines = 1` 截断 ⇒ 那份行高余量
 * 全部变成了**首行上方看不到的空白**，把标题在卡片里往**下**推、
 * 与左侧图标（40dp，垂直居中）错开，观感就是"文本块浮在卡片里"。
 *
 * 收到 21sp（≈ 1.3 倍字号，仍在可读区间）后：标题更贴卡片中轴、与图标的
 * 视觉重心对齐，且不影响 `maxLines` 的截断行为。
 *
 * ⚠️ 刻意**不**改字号、不动字重：见 `.ai/conventions/8.8` §5
 * 「不采纳强调排印（更大的 display 字号、更重的字重）」——
 * 顶栏标题刚从 32sp 收到 26sp，方向必须一致。
 */
val EntryCardTitleLineHeight = 21.sp

/** 卡片内左侧图标与文本区的间距（Bastion = 16dp）。 */
val EntryCardIconSpacing = Spacing.lg

/** 卡片内左侧图标尺寸（Bastion = 40dp）。 */
val EntryCardIconSize = 40.dp
