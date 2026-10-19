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
 * 本文件是**原创实现**。规格依据 Material 3 的按钮尺寸指引（40/48/56dp 三档视觉高）
 * 与 `minimumInteractiveComponentSize` 的触摸目标规则，未复用任何上游源码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 主 CTA 的**默认视觉高度**（48dp），M3 的 `Button` 中号档。
 *
 * ⚠️ 这是本文件存在的**核心理由**：2026-10-19 审计发现同一个"提交"动作，
 * 全项目写了 **5 种**尺寸：
 *
 * | 位置 | 改前 |
 * |---|---|
 * | `UnlockScreen` ×2、`AddVaultScreen` | `.height(48.dp)` 字面量 |
 * | `AddCloudVaultScreen` ×2、`CloudAccountsScreen` ×2 | `.height(BUTTON_HEIGHT)`，常量 = 48dp |
 * | `CloudAccountsScreen.ProbeButton` | 同上，但它用的是 **filled** `Button` |
 * | `AddKdbxScreen.SubmitButton` | `.height(56.dp)` |
 * | `TwoFactorStep`、`FormComponents.ActionButton` | **不设高**（吃 M3 的 40dp 视觉高） |
 *
 * 40 / 48 / 56 三个值本身都在 M3 的尺寸表里，问题不在值，而在**同一类动作没有统一取值**：
 * 用户从"输主密码"走到"两步验证"，按钮会突然矮 8dp。
 *
 * ⇒ 主 CTA 的规格**写死成一条**：视觉 48dp、满宽。需要 56dp（如 KDBX 新建这种
 * "重决定"页）时用 [PrimaryActionButton] 的 `height` 参数显式传 [PRIMARY_ACTION_HEIGHT_TALL]，
 * **不许**在调用点再写 `.height()` —— 那会把规格重新打散。
 */
internal val PRIMARY_ACTION_HEIGHT: Dp = 48.dp

/**
 * 更高的主 CTA（56dp）：给"不可逆 / 重决定"页用（新建 KDBX，主密码无找回途径）。
 *
 * 存成常量而不是让调用点写字面量，是为了让一次 `grep PRIMARY_ACTION_HEIGHT_TALL` 就能找全。
 */
internal val PRIMARY_ACTION_HEIGHT_TALL: Dp = 56.dp

/**
 * **主操作按钮**：一屏的终点动作，全项目唯一实现。
 *
 * ## 用法
 *
 * ```kotlin
 * PrimaryActionButton(
 *     text = stringResource(R.string.add_vault_submit),
 *     onClick = viewModel::submit,
 *     busy = state.submitting,
 * )
 * ```
 *
 * 忙碌态由本组件统一表达（内联转圈 + 文案），调用方**不要**再各写一份
 * `if (busy) CircularProgressIndicator(...) else Text(...)` —— 审计里那 9 处
 * 就是各写各的，转圈直径有 18/20dp 两种、颜色有继承 / 显式 `onPrimary` 两种。
 *
 * ## 强调级别只有两档（不是四档）
 *
 * M3 提供 `Button` / `FilledTonalButton` / `OutlinedButton` / `TextButton` 四种，
 * 但**主操作**（一屏里"完成这件事"的那个按钮）只需要两档语义：
 *
 * | `toned` | M3 组件 | 用在哪 |
 * |---|---|---|
 * | `false`（默认） | `Button`（filled，主色底） | 一屏的**唯一**终点动作：新建 KDBX、测试连接 |
 * | `true` | `FilledTonalButton`（次级底） | 那一屏还有**别的**同权动作，终点需要区分度时：解锁、保存云库 |
 *
 * `Outlined` / `Text` 不进这两个档位 —— 它们是"次要动作 / 取消"，调用方直接用 M3 组件，
 * 不该借主 CTA 的规格（否则又回到"到处是按钮"）。
 *
 * ## 无障碍
 *
 * - 忙碌时按钮 `enabled = false`，读屏会念"已停用"；**同时**把状态写进
 *   `stateDescription`，否则用户只知道"按不动"，不知道"它在忙"。
 * - 忙碌文案走 [busyText]（如"正在创建…"）：读屏用户看不到转圈，
 *   只靠"已停用"分不清"没填完"和"已经提交了"。
 *
 * @param text 常态文案。
 * @param onClick 点击。
 * @param modifier 外部修饰（**不要**再套 `.height()`，用 [height] 参数）。
 * @param enabled 业务可用性（不填完之类）。忙碌时自动叠加禁用。
 * @param busy 进行中：显示转圈 + [busyText]，并禁用重复点击。
 * @param busyText 进行中文案；为 `null` 时沿用 [text]（转圈本身已表达"在忙"）。
 * @param icon 前置图标（可选）。
 * @param toned 主操作是否用**次级底**（`FilledTonalButton`）：`false` = 主色底 `Button`。
 *   两档的含义见上方「强调级别只有两档」表。
 * @param height 视觉高度，默认 [PRIMARY_ACTION_HEIGHT]。
 * @param fillMaxWidth 是否满宽（默认 `true`；并排两枚时传 `false`）。
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
    icon: ImageVector? = null,
    toned: Boolean = false,
    height: Dp = PRIMARY_ACTION_HEIGHT,
    fillMaxWidth: Boolean = true,
) {
    // 图标 / 转圈与文案之间的间隙统一在这里给，调用方不再各写 `Spacer(width = ...)`。
    val content: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PROGRESS_SIZE),
                    strokeWidth = PROGRESS_STROKE,
                    // ⚠️ 必须显式给色：内联转圈默认取 `primary`，落在主色底上就是
                    //    主色压主色，看起来像"按钮上有个洞"。
                    color = progressColorFor(toned),
                )
                Text(text = busyText ?: text)
            } else {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null)
                }
                Text(text)
            }
        }
    }

    // 忙碌 / 禁用的**原因**必须能被读屏听到。
    // 只靠 `enabled = false` 时，读屏只会念"已停用" —— 分不清"没填完"与"已经提交了"。
    val stateText = stringResource(
        if (busy) R.string.action_in_progress else R.string.action_unavailable,
    )
    val buttonModifier = modifier
        .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
        .heightIn(min = height)
        .semantics {
            if (!enabled || busy) this.stateDescription = stateText
        }

    if (toned) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled && !busy,
            modifier = buttonModifier,
            contentPadding = BUTTON_CONTENT_PADDING,
        ) {
            content()
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled && !busy,
            modifier = buttonModifier,
            contentPadding = BUTTON_CONTENT_PADDING,
        ) {
            content()
        }
    }
}

/** 图标与文案之间的间隙（走标尺，别写字面量）。 */
private val BUTTON_CONTENT_PADDING = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.sm)

/** 内联转圈直径：18dp —— 与 2dp 描边配，在 48dp 高的按钮里不顶上下边。 */
private val PROGRESS_SIZE = 18.dp

/** 内联转圈描边。 */
private val PROGRESS_STROKE = 2.dp

/** 转圈颜色：主色底上用 `onPrimary`，次级底上用 `onSecondaryContainer`。 */
@Composable
private fun progressColorFor(toned: Boolean) = if (toned) {
    MaterialTheme.colorScheme.onSecondaryContainer
} else {
    MaterialTheme.colorScheme.onPrimary
}

/**
 * 并排的两枚主 CTA（一屏两个动作、且需要区分主次时）。
 *
 * 为什么不直接让调用方写 `Row`：并排时**必须**两侧权重相等，否则"取消"会被挤成一条细条 ——
 * 这类"看起来能点但很难点"的按钮是最容易被忽略的可用性缺陷。
 */
@Composable
fun PrimaryActionRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
