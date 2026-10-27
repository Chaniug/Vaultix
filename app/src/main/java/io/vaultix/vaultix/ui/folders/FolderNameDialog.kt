/*
 * Vaultix — app:ui:folders
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.folders

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.vaultix.domain.FolderNamePolicy
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.DialogActions
import io.vaultix.vaultix.ui.common.DialogDismissButton
import io.vaultix.vaultix.ui.common.DialogFootnote
import io.vaultix.vaultix.ui.common.DialogHeader
import io.vaultix.vaultix.ui.common.DialogSurface
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 新建 / 重命名文件夹的**同一个**输入框。
 *
 * ## 为什么要合成一个组件而不是两个
 *
 * 两者的差别只有"标题文案"和"初始值"，其余（校验、错误提示、按钮可用性、
 * 回车即确认）完全相同。写成两个的话，校验规则会以两处代码出现 ——
 * 而"同一规则写在两处、只改一处"是本项目复现过四次的失败模型
 * （见 `.ai/conventions/8.6-工程质量.md`）。
 *
 * ## 校验放在**输入时**而不是提交后
 *
 * 非法名字（空 / 超长 / 含 `/` `\`）在输入框里当场变红、确认按钮置灰，
 * 用户不用点一次才知道不行。校验规则本身来自 [FolderNamePolicy] ——
 * 与写路径用的是**同一份**，UI 这边不另写一条（否则两处会漂移）。
 *
 * ⚠️ 注意这里**只做"提前告知"**：真正的闸门在仓储侧（`FolderRepositoryImpl` 里
 * 会再过一遍）。UI 的置灰是体验优化，不能当成安全性保证。
 *
 * @param initialName 非空 = 重命名（输入框预填并全选）；空 = 新建。
 * @param busy 写操作进行中：两个按钮都禁用，回车也不再触发。
 * @param onConfirm 收到的是**已 trim** 的名字（[FolderNamePolicy] 的 `Ok.name`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderNameDialog(
    title: String,
    initialName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // `rememberSaveable`：转屏 / 进程重建后用户已经打进去的字不该消失。
    var input by rememberSaveable { mutableStateOf(initialName) }
    val verdict = remember(input) { FolderNamePolicy.check(input) }
    val canConfirm = verdict is FolderNamePolicy.Verdict.Ok && !busy

    // 打开即聚焦：这一屏除了输入名字没别的事可做，多余的点一下纯属浪费。
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BasicAlertDialog(onDismissRequest = { if (!busy) onDismiss() }) {
        DialogSurface {
            DialogHeader(title = title)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl)
                    .focusRequester(focusRequester),
                singleLine = true,
                // 拒绝态才把错误提示亮出来；空输入刚打开时不红 —— 用户还什么都没做，
                // 一进来就先报错是在指责用户。
                isError = input.isNotEmpty() && verdict is FolderNamePolicy.Verdict.Rejected,
                supportingText = {
                    Text(
                        text = when {
                            verdict is FolderNamePolicy.Verdict.Rejected && input.isNotEmpty() ->
                                verdict.reason
                            // 平时给上限提示，不用等到撞线才发现有长度限制。
                            else -> stringResource(R.string.folder_name_hint, FolderNamePolicy.MAX_LENGTH)
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (canConfirm) onConfirm((verdict as FolderNamePolicy.Verdict.Ok).name) },
                ),
            )
            Spacer(Modifier.height(Spacing.md))
            DialogFootnote(text = stringResource(R.string.folder_name_footnote))
            DialogActions {
                DialogDismissButton(onClick = { if (!busy) onDismiss() })
                TextButton(
                    enabled = canConfirm,
                    onClick = {
                        // 只有 Ok 才可能按住 enter（canConfirm 已保证），这里再取一次是给编译器看。
                        (verdict as? FolderNamePolicy.Verdict.Ok)?.let { onConfirm(it.name) }
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
