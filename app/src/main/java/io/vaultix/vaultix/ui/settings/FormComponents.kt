/*
 * Vaultix — app:ui:settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 「数据管理」链路共用的表单三件套（密码框 / 提交按钮 / 字段提示）。
 *
 * ## 为什么抽出来
 *
 * 它们原是 [ImportExportScreen] 里的 private 组件。格式转换页（ConversionScreen，
 * 施工单 W5）要用的是一模一样的三件套 —— 直接复制一份就会有两份实现，
 * 而这类组件**改一处漏一处**的代价极高（比如某天给密码框加「粘贴保护」，
 * 只改一份 = 另一个页面静默少一层防护）。
 *
 * 与 SettingsComponents.kt 的分界：那个文件是「设置行」体系（分组标题 / 行 /
 * 分组卡片 / 分隔线），本文件是「表单控件」体系，互不依赖。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize

/** 密码输入框（带「显示 / 隐藏」切换），导出 / 导入 / 转换共用。 */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: (Boolean) -> Unit,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        trailingIcon = {
            IconButton(onClick = { onToggleVisible(!visible) }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = UiSize.TightGap),
    )
}

/** 提交按钮（带忙碌态）。 */
@Composable
internal fun ActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    busy: Boolean,
    busyText: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(UiSize.InlineProgress),
                strokeWidth = UiSize.InlineProgressStroke,
            )
            Text(busyText, modifier = Modifier.padding(start = Spacing.sm))
        } else {
            Icon(icon, contentDescription = null)
            Text(text, modifier = Modifier.padding(start = Spacing.sm))
        }
    }
}

/** 字段级错误提示（红色小字）。 */
@Composable
internal fun FieldHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = UiSize.HairGap),
    )
}
