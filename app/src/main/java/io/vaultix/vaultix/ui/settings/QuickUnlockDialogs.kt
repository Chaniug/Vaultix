/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.common.DialogActions
import io.vaultix.vaultix.ui.common.DialogBackButton
import io.vaultix.vaultix.ui.common.DialogEmptyBody
import io.vaultix.vaultix.ui.common.DialogHeader
import io.vaultix.vaultix.ui.common.DialogSectionTitle
import io.vaultix.vaultix.ui.common.DialogSurface
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 「快速解锁」的**能力级**设置对话框（2026-09-16 重构）。
 *
 * ## 心智模型（为什么长这样）
 *
 * 上面两个**并列开关**（指纹 / PIN），下面一份**统一生效范围**。用户只需回答两个问题：
 * 「用哪些方式？」和「对哪些库生效？」。
 *
 * ⚠️ 与旧版「每库一张卡、卡内三选一」的关键差别：**选项不再是每库独占的**。
 * 一个开关就能覆盖一批库 —— 这正是用户的诉求（原话：「这两个设置应该覆盖多个库」），
 * 也回到了定稿 §4.7 原本就写过的「增加一个生效范围」。
 *
 * ⚠️ **不做互斥**：指纹与 PIN 可以同时开。旧版那套「选指纹就关 PIN」是本次纠正的错误
 * （它还会造成"用户取消后指纹没了、PIN 也没设成"的静默数据丢失）。
 *
 * ## 开关的三种呈现（对应 [QuickUnlockController.CapabilityState]）
 *
 * | 状态 | 开关 | 副标题 |
 * |---|---|---|
 * | `On` | 开 | 已生效的说明 |
 * | `Partial(n)` | 关 | 「有 n 个库未完成，点此继续」 |
 * | `Off` | 关 | 默认说明（或设备不支持） |
 *
 * ⚠️ `Partial` 用**关**的开关 + 明确提示，而不是硬撑成"开" ——
 * 开关说开着但有的库其实打不开，就是 #93 那种「谎报状态」。
 * 点 `Partial` 的开关 = 继续把没配完的补上（不是关闭）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickUnlockSettingsDialog(
    state: QuickUnlockController.UiState,
    canAuthenticate: Boolean,
    onToggleBiometric: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleScope: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.settings_quick_unlock))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                CapabilityRow(
                    title = stringResource(R.string.quick_unlock_section_biometric),
                    summary = biometricSummary(state.biometric, canAuthenticate),
                    checked = state.biometric is QuickUnlockController.CapabilityState.On,
                    // 设备不支持认证时不给点：点了也走不完流程，允许点等于给出一个必然失败的承诺。
                    enabled = canAuthenticate,
                    onToggle = onToggleBiometric,
                )
                CapabilityRow(
                    title = stringResource(R.string.pin_section_title),
                    summary = pinSummary(state.pin),
                    checked = state.pin is QuickUnlockController.CapabilityState.On,
                    enabled = true,
                    onToggle = onTogglePin,
                )

                Spacer(Modifier.height(Spacing.sm))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.sm))

                DialogSectionTitle(
                    title = stringResource(R.string.quick_unlock_scope_title),
                    hint = stringResource(R.string.quick_unlock_scope_hint),
                )

                if (state.rows.isEmpty()) {
                    DialogEmptyBody(stringResource(R.string.quick_unlock_manage_none))
                } else {
                    state.rows.forEach { row ->
                        ScopeRow(row = row, onToggle = { onToggleScope(row.vaultId) })
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            DialogActions {
                DialogBackButton(onDismiss)
            }
        }
    }
}

/** 一个能力开关 + 副标题。 */
@Composable
private fun CapabilityRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, onValueChange = { onToggle() })
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

/**
 * 范围列表里的一行。
 *
 * 右侧标出**已经配好的能力**（指纹 / PIN）—— 用户需要知道这个库到底配成了没有，
 * 否则「在范围内」与「真的能用」会混为一谈。
 */
@Composable
private fun ScopeRow(row: QuickUnlockController.VaultUi, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = row.inScope, onValueChange = { onToggle() })
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.inScope, onCheckedChange = { onToggle() })
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = readyLabel(row),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 该库已配好的能力标签（都没配好时留空）。 */
@Composable
private fun readyLabel(row: QuickUnlockController.VaultUi): String {
    val parts = buildList {
        if (row.biometricReady) add(stringResource(R.string.quick_unlock_cap_biometric))
        if (row.pinReady) add(stringResource(R.string.quick_unlock_cap_pin))
    }
    return parts.joinToString(separator = " · ")
}

/**
 * 指纹开关的副标题。
 *
 * ⚠️ 顺序有意义：**设备不支持**优先于其它说明（反正点不了，先说原因）；
 * `Partial` 其次（它是最需要用户行动的状态）。
 */
@Composable
private fun biometricSummary(
    state: QuickUnlockController.CapabilityState,
    canAuthenticate: Boolean,
): String = when {
    !canAuthenticate -> stringResource(R.string.quick_unlock_option_biometric_unsupported)
    state is QuickUnlockController.CapabilityState.Partial ->
        stringResource(R.string.quick_unlock_partial_hint, state.pending)
    state is QuickUnlockController.CapabilityState.On ->
        stringResource(R.string.quick_unlock_option_biometric_on)
    else -> stringResource(R.string.quick_unlock_option_biometric_summary)
}

/** PIN 开关的副标题。 */
@Composable
private fun pinSummary(state: QuickUnlockController.CapabilityState): String = when (state) {
    is QuickUnlockController.CapabilityState.Partial ->
        stringResource(R.string.quick_unlock_partial_hint, state.pending)
    is QuickUnlockController.CapabilityState.On ->
        stringResource(R.string.quick_unlock_option_pin_on, PIN_MIN_LENGTH)
    else -> stringResource(R.string.quick_unlock_option_pin_summary, PIN_MIN_LENGTH)
}

/**
 * 流程宿主：认证副作用 + 四个步骤对话框。
 *
 * 与主对话框分开：主对话框是"设置界面"，这里是"进行中的流程"
 * （输 PIN → 逐库问主密码 → 认证 → 结果），生命周期完全不同。
 *
 * ⚠️ 参数是**控制器本身**而不是某个 ViewModel：设置页与库列表页各持一个实例
 * （两者都要能发起登记），收 ViewModel 会让其中一个用不了。
 */
@Composable
internal fun QuickUnlockHost(controller: QuickUnlockController) {
    val activity = rememberFragmentActivity()
    val cipher by controller.pendingCipher.collectAsStateWithLifecycle()
    val dialog by controller.dialog.collectAsStateWithLifecycle()
    val enrollTitle = stringResource(R.string.quick_unlock_enroll_title)
    val cancelText = stringResource(R.string.action_cancel)

    // cipher 一到就弹认证。⚠️ 先 onPromptHandled 清掉待认证标记，避免重组时重复弹。
    LaunchedEffect(cipher) {
        val current = cipher ?: return@LaunchedEffect
        controller.onPromptHandled()
        val host = activity ?: return@LaunchedEffect
        BiometricPrompter(host).authenticate(
            cipher = current,
            title = enrollTitle,
            cancelText = cancelText,
            onSuccess = { authenticated -> controller.onAuthenticated(authenticated) },
            onError = { _, _, _ -> controller.onAuthenticationFailed() },
        )
    }

    when (val current = dialog) {
        QuickUnlockController.Dialog.Idle -> Unit
        QuickUnlockController.Dialog.Authenticating -> AuthenticatingDialog()
        is QuickUnlockController.Dialog.PinEntry -> PinEntryDialog(current, controller)
        is QuickUnlockController.Dialog.KdbxPassword -> KdbxPasswordDialog(current, controller)
        is QuickUnlockController.Dialog.Report -> ReportDialog(current, controller)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinEntryDialog(
    state: QuickUnlockController.Dialog.PinEntry,
    controller: QuickUnlockController,
) {
    BasicAlertDialog(onDismissRequest = controller::dismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.pin_section_title))
            DialogSectionTitle(
                title = stringResource(R.string.pin_set_title, PIN_MIN_LENGTH),
                hint = stringResource(R.string.pin_section_hint),
            )
            PinField(
                value = state.pin,
                labelRes = R.string.pin_field_new,
                onValueChange = controller::onPinChange,
            )
            PinField(
                value = state.confirm,
                labelRes = R.string.pin_field_confirm,
                onValueChange = controller::onPinConfirmChange,
            )
            state.error?.let { DialogErrorText(it) }
            Spacer(Modifier.height(Spacing.sm))
            DialogActions {
                DialogBackButton(controller::dismiss)
                TextButton(onClick = controller::submitPin) {
                    Text(stringResource(R.string.action_continue))
                }
            }
        }
    }
}

/**
 * 逐库问主密码。
 *
 * ⚠️ 「跳过」是必需的出口：用户可能确实不知道某个库的密码，
 * 不该被一个库卡死整批登记。跳过**不算失败**（结果页单列）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KdbxPasswordDialog(
    state: QuickUnlockController.Dialog.KdbxPassword,
    controller: QuickUnlockController,
) {
    BasicAlertDialog(onDismissRequest = controller::dismiss) {
        DialogSurface {
            DialogHeader(title = state.vaultName)
            DialogSectionTitle(
                title = stringResource(R.string.quick_unlock_kdbx_password_title),
                hint = stringResource(R.string.quick_unlock_kdbx_password_hint, state.remaining),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = controller::onPasswordChange,
                label = { Text(stringResource(R.string.quick_unlock_kdbx_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { DialogErrorText(it) }
            Spacer(Modifier.height(Spacing.sm))
            DialogActions {
                TextButton(onClick = controller::skipCurrentVault) {
                    Text(stringResource(R.string.quick_unlock_kdbx_skip))
                }
                TextButton(onClick = controller::submitPassword) {
                    Text(stringResource(R.string.action_continue))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatingDialog() {
    BasicAlertDialog(onDismissRequest = {}) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.quick_unlock_enroll_title))
            DialogSectionTitle(
                title = stringResource(R.string.quick_unlock_authenticating),
                hint = stringResource(R.string.quick_unlock_authenticating_hint),
            )
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

/**
 * 结果页。
 *
 * 三段分开列：**成功 / 跳过 / 失败**。「跳过」不能并进失败 —— 那是用户的选择，
 * 并进去会让他以为自己操作错了；也不能省略 —— 省了就是"假成功"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportDialog(
    state: QuickUnlockController.Dialog.Report,
    controller: QuickUnlockController,
) {
    BasicAlertDialog(onDismissRequest = controller::dismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.quick_unlock_report_title))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                ReportSection(
                    titleRes = R.string.quick_unlock_report_succeeded,
                    names = state.succeeded,
                )
                ReportSection(
                    titleRes = R.string.quick_unlock_report_skipped,
                    names = state.skipped,
                )
                if (state.failed.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.quick_unlock_report_failed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.failed.forEach { item ->
                        Text(
                            text = "${item.vaultName} · ${item.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            DialogActions {
                DialogBackButton(controller::dismiss)
            }
        }
    }
}

/** 结果页里的一段（成功 / 跳过）。空段不占地方。 */
@Composable
private fun ReportSection(titleRes: Int, names: List<String>) {
    if (names.isEmpty()) return
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = names.joinToString(separator = "、"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.xs))
}

/** 数字输入框（PIN 用；掩码 + 数字键盘）。 */
@Composable
private fun PinField(value: String, labelRes: Int, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DialogErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}
