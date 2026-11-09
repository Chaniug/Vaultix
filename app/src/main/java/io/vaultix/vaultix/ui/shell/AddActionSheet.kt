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
 * ⚠️ 用稳定的 `ModalBottomSheet`，不用 M3E 的 `FloatingActionButtonMenu`
 *    （8.8 §6①：alpha API 只用于叶子组件，不承载导航级动作）。
 * ⚠️ 面板里放 `ListItem` 列表，不是 Popup / DropdownMenu 选择器，
 *    不违反 8.4「Dialog / BottomSheet 里不做 Popup 选择器」。
 * ⚠️ 独立生成器对话框由**宿主**渲染（[StandalonePasswordGeneratorDialog]）：
 *    若把开关留在本 composable 里，收起面板后它随组合一起销毁，对话框根本弹不出来。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.PasswordGeneratorDialog
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize
import kotlinx.coroutines.launch

/**
 * 「+」动作面板。
 *
 * @param currentTab 决定面板**默认高亮哪一项**（复用 [VaultixNavItem.addTarget]），
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

            // 8.4：同一屏只有一个 Filled 按钮 —— 它是当前 Tab 的默认新建动作，
            // 也是方案 A「1 步变 2 步」里那第二步的落点（拇指几乎在原处）。
            val defaultLabel = when (currentTab.addTarget) {
                VaultixAddTarget.Login -> R.string.dock_add_default_login
                VaultixAddTarget.Totp -> R.string.dock_add_default_totp
                VaultixAddTarget.Card -> R.string.dock_add_default_card
            }
            Button(
                onClick = { pick(onDefaultAction) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(UiSize.InlineIcon),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(defaultLabel))
            }

            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.dock_add_other_types),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            )
            // 不在这里按库类型过滤：`ItemFormDialog` 已有 `fallbackFormType`
            // （把不在 selectableTypes 里的初始类型兜到该库支持的第一种），
            // 面板过滤反而要与库摘要加载时序做同步，是多余的第二个真源。
            VaultItemType.entries.forEach { type ->
                ListItem(
                    headlineContent = { Text(stringResource(itemTypeLabelRes(type))) },
                    leadingContent = {
                        Icon(
                            imageVector = type.addIcon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(UiSize.InlineIcon),
                        )
                    },
                    modifier = Modifier.clickable { pick { onPickType(type) } },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.xl))
            ListItem(
                headlineContent = { Text(stringResource(R.string.dock_add_generate_password)) },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Casino,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(UiSize.InlineIcon),
                    )
                },
                modifier = Modifier.clickable { pick(onGeneratePassword) },
            )
        }
    }
}

/** 条目类型 → 面板图标。只做映射，不参与任何业务判断。 */
private fun VaultItemType.addIcon(): ImageVector = when (this) {
    VaultItemType.Login -> Icons.Filled.Password
    VaultItemType.SecureNote -> Icons.Filled.Description
    VaultItemType.Card -> Icons.Filled.CreditCard
    VaultItemType.Identity -> Icons.Filled.Badge
    VaultItemType.SshKey -> Icons.Filled.Key
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
