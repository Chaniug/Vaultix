/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 中央「+」的动作面板（架构方案 A，2026-11-09）。
 *
 * 为什么不扩 Tab：便签是「条目类型」（`VaultItemType.SecureNote`，已有筛选器），
 * 生成器是「动作」（`PasswordGeneratorDialog`，已在表单里用），卡包是「集合视图」。
 * 三者层级不同，并列成 Tab 会把「目的地 / 内容类型 / 动作」混成一层。
 * ⇒ 卡包位置与身份完全不动，`currentTab` / `SaveableStateProvider` / `contentKey`
 *   一行不改；新增能力全部从「+」的动作面板进入。
 *
 * ## 2026-11-09 第二轮精装修（本文件布局重写）
 *
 * - **主卡**：仍是当前 Tab 的默认新建动作，走 [VaultixNavItem.addTarget] 的原分发。
 *   「密码页 → 新建登录 / 验证码页 → 新建验证码 / 卡包页 → 新建银行卡」**一条未改**，
 *   只是从一条 Button 升成一张主色卡，作为面板里唯一的视觉焦点与唯一的 Filled。
 * - **网格**：其余条目类型改成 2 列 tonal 小卡，每格画该类型的**字段剪影**
 *   （磁条 / 文本行 / 头像 / 等宽字符），不是抽象图标。M3 对
 *   `ModalBottomSheet` 的内容布局没有规定，网格在规范内且零 alpha API。
 * - **工具条**：生成器从「第 7 个列表项」独立成底部胶囊 —— 它是动作不是条目类型。
 *
 * ⚠️ 用稳定的 `ModalBottomSheet`，不用 M3E 的 `FloatingActionButtonMenu`
 *    （8.8 §6①：alpha API 只用于叶子组件，不承载导航级动作）。
 * ⚠️ 面板里放 `Surface` 卡片，不是 Popup / DropdownMenu 选择器，
 *    不违反 8.4「Dialog / BottomSheet 里不做 Popup 选择器」。
 * ⚠️ 独立生成器对话框由**宿主**渲染（[StandalonePasswordGeneratorDialog]）：
 *    若把开关留在本 composable 里，收起面板后它随组合一起销毁，对话框根本弹不出来。
 * ⚠️ 每个视觉块都抽成顶层 private composable：`AddActionSheet()` 本身要留足
 *    detekt `LongMethod ≤150` 的余量，且字段剪影各分支独立才不会互相改坏。
 *
 * ## 下一批（本轮明确不做）
 * 用户 21:11 拍板：身份 / 个人信息（以及将来的证照）条目以后要**在卡包页看得到**，
 * 卡包页原有内容不动、密码页仍是数据源、远程与 KDBX/Bitwarden 映射一概不动
 * ⇒ 纯客户端展示筛选（`CardWalletScreen` 从只筛 Card 放宽到 Card + Identity）
 * + 卡包页补类型筛选 chip。**本面板不做组合卡**，银行卡与身份仍是两个独立直达项。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.PasswordGeneratorDialog
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize
import io.vaultix.vaultix.ui.theme.VaultixCorner
import kotlinx.coroutines.launch

/**
 * 「+」动作面板。
 *
 * @param currentTab 决定主卡是哪一项（复用 [VaultixNavItem.addTarget]），
 *   保住「在验证码页按 + 就是新建验证码」这条既有高频路径。
 * @param onDefaultAction 执行当前 Tab 原来的分发逻辑（切 Tab + 递增对应 addRequest）。
 * @param onPickType 新建指定类型的条目；宿主切到密码 Tab 并把类型透传给表单。
 * @param onGeneratePassword 打开独立生成器（由宿主渲染，见文件头）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddActionSheet(
    currentTab: VaultixNavItem,
    onDefaultAction: () -> Unit,
    onPickType: (VaultItemType) -> Unit,
    onGeneratePassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 先收起弹层再执行动作：否则选中态会在关闭动画里跳一下
    // （与 `DisplayOptionsSheet` 同款处理，照抄已编译过的写法）。
    fun pick(apply: () -> Unit) {
        scope.launch {
            if (sheetState.isVisible) sheetState.hide()
            onDismiss()
            apply()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.dock_add_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            )

            // 8.4：同一屏只有一个 Filled 面 —— 主卡。它是当前 Tab 的默认新建动作，
            // 也是方案 A「1 步变 2 步」里那第二步的落点（拇指几乎在原处）。
            val defaultLabel = when (currentTab.addTarget) {
                VaultixAddTarget.Login -> R.string.dock_add_default_login
                VaultixAddTarget.Totp -> R.string.dock_add_default_totp
                VaultixAddTarget.Card -> R.string.dock_add_default_card
            }
            AddHeroCard(
                label = stringResource(defaultLabel),
                onClick = { pick(onDefaultAction) },
            )

            Text(
                text = stringResource(R.string.dock_add_other_types),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.md),
            )
            AddTypeGrid(
                types = standaloneTypes(currentTab),
                onPick = { type -> pick { onPickType(type) } },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            )
            AddToolRow(onClick = { pick(onGeneratePassword) })
        }
    }
}

/**
 * 当前 Tab 的默认动作对应的条目类型；验证码没有 [VaultItemType] 对应项，
 * 返回 null 表示「主卡不占用网格里的任何一格」。
 *
 * ⚠️ 不在面板里按库类型过滤：`ItemFormDialog` 已有 `fallbackFormType`
 * （把不在 selectableTypes 里的初始类型兜到该库支持的第一种），
 * 面板过滤反而要与库摘要加载时序做同步，是多余的第二个真源。
 */
private fun standaloneTypes(currentTab: VaultixNavItem): List<VaultItemType> {
    val mainType = when (currentTab.addTarget) {
        VaultixAddTarget.Login -> VaultItemType.Login
        VaultixAddTarget.Card -> VaultItemType.Card
        VaultixAddTarget.Totp -> null
    }
    return VaultItemType.entries.filter { it != mainType }
}

/** 主卡：当前 Tab 的默认新建动作，全屏唯一的主色面。 */
@Composable
private fun AddHeroCard(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .heightIn(min = 76.dp),
        shape = VaultixCorner.Panel,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(VaultixCorner.Panel)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.dock_add_hero_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                )
            }
        }
    }
}

/** 2 列网格；末行只有一格时用 Spacer 补位，保持两列对齐而不拉出一张突兀的宽卡。 */
@Composable
private fun AddTypeGrid(
    types: List<VaultItemType>,
    onPick: (VaultItemType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        types.chunked(2).forEach { rowTypes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowTypes.forEach { type ->
                    AddTypeTile(
                        type = type,
                        onPick = { onPick(type) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTypes.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 单个类型卡：字段剪影 + 类型名。颜色走 tonal 档，不抢主卡的焦点。 */
@Composable
private fun AddTypeTile(
    type: VaultItemType,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onPick,
        modifier = modifier.heightIn(min = 104.dp),
        shape = VaultixCorner.Panel,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            ItemFieldGlyph(
                type = type,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(itemTypeLabelRes(type)),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** 生成器：独立胶囊工具条，与「新建什么」拉开层级。 */
@Composable
private fun AddToolRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .heightIn(min = 52.dp),
        shape = VaultixCorner.Pill,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Casino,
                contentDescription = null,
                modifier = Modifier.size(UiSize.InlineIcon),
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dock_add_generate_password),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.dock_add_tool_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 条目类型的**字段剪影**：不画抽象图标，画这一类型条目长什么样。
 *
 * 银行卡画磁条 + 四位点、安全笔画三行文本、身份画头像 + 两行、SSH 画等宽字符块、
 * 登录画站点块 + 两行。扫一眼就知道要建的是哪种，比读文字快，也是密码管理器
 * 独有的表达。全部用 Compose 基础形状绘制，**不新增任何 vector 资源**。
 */
@Composable
private fun ItemFieldGlyph(type: VaultItemType, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f)
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        when (type) {
            VaultItemType.Login -> GlyphLogin(accent = accent, line = line)
            VaultItemType.SecureNote -> GlyphNote(line = line)
            VaultItemType.Card -> GlyphCard(accent = accent, line = line)
            VaultItemType.Identity -> GlyphIdentity(accent = accent, line = line)
            VaultItemType.SshKey -> GlyphSsh(accent = accent, line = line)
        }
    }
}

/** 剪影里的一条「文字」：固定高 3dp，宽度由调用方给，模拟字段行的长短。 */
@Composable
private fun GlyphLine(width: Dp, color: Color) {
    Box(
        modifier = Modifier
            .width(width)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun GlyphLogin(accent: Color, line: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(accent),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            GlyphLine(width = 48.dp, color = line)
            GlyphLine(width = 32.dp, color = line)
        }
    }
}

@Composable
private fun GlyphNote(line: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        GlyphLine(width = 64.dp, color = line)
        GlyphLine(width = 52.dp, color = line)
        GlyphLine(width = 38.dp, color = line)
    }
}

@Composable
private fun GlyphCard(accent: Color, line: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(accent.copy(alpha = 0.16f))
            .padding(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(accent),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // 命名参数：detekt MagicNumber 只对未命名位置参数报错（见 config/detekt/detekt.yml）。
            repeat(times = 4) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(line),
                )
            }
        }
    }
}

@Composable
private fun GlyphIdentity(accent: Color, line: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(VaultixCorner.Pill)
                .background(accent),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            GlyphLine(width = 46.dp, color = line)
            GlyphLine(width = 30.dp, color = line)
        }
    }
}

@Composable
private fun GlyphSsh(accent: Color, line: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
        GlyphLine(width = 18.dp, color = line)
        GlyphLine(width = 12.dp, color = line)
        GlyphLine(width = 16.dp, color = line)
    }
}

/**
 * 独立生成器（不在条目表单里）。复用现有 `PasswordGeneratorDialog`，
 * 「使用」在没有表单可回填时改为**复制到剪贴板**并给一次可见反馈。
 *
 * ⚠️ 用 Android 原生 ClipboardManager / Toast，不引入任何 Compose alpha API；
 *    生成逻辑本身一行未改，仍是表单里那一份。
 */
@Composable
fun StandalonePasswordGeneratorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    PasswordGeneratorDialog(
        onUse = { generated ->
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Vaultix password", generated))
            Toast.makeText(
                context,
                R.string.dock_add_password_copied,
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
