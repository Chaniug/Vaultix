/*
 * Vaultix — app
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）：交互结构参照 Bastion（GPL-3.0，Copyright 2025
 * JoyinJoester）AddEditPasswordScreen 的生成器面板；本文件按 Vaultix 表单架构
 * 独立实现，数据源为 core:common 的 PasswordGenerator。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.vaultix.common.PasswordGenerator
import io.vaultix.vaultix.R

/**
 * 随机密码生成对话框（添加/编辑密码条目时使用，Bastion 同款能力）。
 *
 * 选项：长度 + 四类字符集开关 + 排除易混淆/歧义字符；「使用」把当前预览回填表单。
 * 每次打开 / 点「重新生成」都会产出新密码。
 */
@Composable
fun PasswordGeneratorDialog(
    onUse: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var length by rememberSaveable { mutableStateOf(16) }
    var uppercase by rememberSaveable { mutableStateOf(true) }
    var lowercase by rememberSaveable { mutableStateOf(true) }
    var numbers by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var excludeSimilar by rememberSaveable { mutableStateOf(false) }
    var excludeAmbiguous by rememberSaveable { mutableStateOf(false) }
    // remember（非 saveable）：配置变化时即时刷新预览即可，无需跨进程恢复
    var preview by remember {
        mutableStateOf(generate(length, uppercase, lowercase, numbers, symbols, excludeSimilar, excludeAmbiguous))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.item_generate_password)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = preview,
                    onValueChange = { preview = it },
                    label = { Text(stringResource(R.string.gen_preview)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.gen_length, length),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = length.toFloat(),
                    onValueChange = { length = it.toInt() },
                    valueRange = 8f..64f,
                )
                GenToggle(stringResource(R.string.gen_uppercase), uppercase) { uppercase = it }
                GenToggle(stringResource(R.string.gen_lowercase), lowercase) { lowercase = it }
                GenToggle(stringResource(R.string.gen_numbers), numbers) { numbers = it }
                GenToggle(stringResource(R.string.gen_symbols), symbols) { symbols = it }
                GenToggle(stringResource(R.string.gen_exclude_similar), excludeSimilar) {
                    excludeSimilar = it
                }
                GenToggle(stringResource(R.string.gen_exclude_ambiguous), excludeAmbiguous) {
                    excludeAmbiguous = it
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onUse(preview)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.gen_use)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    preview = generate(
                        length, uppercase, lowercase, numbers, symbols, excludeSimilar, excludeAmbiguous,
                    )
                },
            ) { Text(stringResource(R.string.gen_regenerate)) }
        },
    )
}

private fun generate(
    length: Int,
    uppercase: Boolean,
    lowercase: Boolean,
    numbers: Boolean,
    symbols: Boolean,
    excludeSimilar: Boolean,
    excludeAmbiguous: Boolean,
): String = PasswordGenerator.generatePassword(
    length = length,
    uppercase = uppercase,
    lowercase = lowercase,
    numbers = numbers,
    symbols = symbols,
    // 每类至少 1 个，避免「四个开关全开却随机出纯数字」的弱结果
    uppercaseMin = if (uppercase) 1 else 0,
    lowercaseMin = if (lowercase) 1 else 0,
    numbersMin = if (numbers) 1 else 0,
    symbolsMin = if (symbols) 1 else 0,
    excludeSimilar = excludeSimilar,
    excludeAmbiguous = excludeAmbiguous,
)

@Composable
private fun GenToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
