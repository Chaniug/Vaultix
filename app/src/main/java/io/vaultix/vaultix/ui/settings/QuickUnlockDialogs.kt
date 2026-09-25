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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
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
import io.vaultix.vaultix.ui.common.deviceCanAuthenticate
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 「解锁方式」的三行设置项（**内联在密码库管理页**，不再是一个对话框）。
 *
 * ## 为什么从对话框改成内联（2026-09-17）
 *
 * 旧形态 =「二级页里一行『快速解锁』→ 点进去的对话框里两个开关 + 一张逐库勾选的范围表」。
 * 那层层级有两个毛病，而且都不是"不好看"这种主观问题：
 * 1. 组名（「解锁方式」）与组内唯一的内容（「快速解锁」一行）**语义重复**；
 * 2. 开关前面白多一次导航 —— 用户要改的正是"哪种方式"本身。
 *
 * 定稿 §11.11 的目标形态也是把开关直接画在卡片里。⇒ 本轮内联，并**只留一行汇总**
 * （「已对 N 个库生效」），不再逐库列行；逐库勾选挪进 [ConfigureDialog]
 * （默认全勾，取消勾选是可选动作）。
 *
 * ## 开关的三种呈现（对应 [QuickUnlockController.CapabilityState]）
 *
 * | 状态 | 尾部控件 | 副标题 |
 * |---|---|---|
 * | `On` | 开着的开关 | 「已对 N 个库生效」 |
 * | `Partial(n)` | **「继续」按钮**（不是开关） | 「有 n 个库未完成，点此继续」 |
 * | `Off` | 关着的开关 | 未启用（或设备不支持） |
 *
 * ⚠️ `Partial` **不允许**硬撑成"开" —— 开关说开着但有的库其实打不开，
 * 就是 #93 那种「谎报状态」；但也不能简单画成"关"（见下）。
 *
 * ⚠️ 2026-09-26 修正：`Partial` **不再渲染成关着的开关，而是渲染成一个「继续」按钮**。
 * 原实现把 `Partial` 画成"关"（`checked = state is On`），于是用户看到关、点一下，
 * 预期是"打开"，实际发生的却是"进入配置向导接着配"。这个错位在真机上表现为
 * 「点了开关没打开，反而弹了个框」——注释里写清了"点 Partial = 继续"，
 * 但**注释不是 UI，用户看不到**。
 *
 * 为什么不直接用三态开关（半开）：Material 3 的 `Switch` 只有开/关两态，
 * 没有"半开"这一形态；自己画一个半开的 knob 属于自造控件，可读性与无障碍都更差。
 * 而 `Partial` 的本质压根不是"开关的位置"，是**"一个还没做完的动作"**——
 * 用一个明确写着「继续」的按钮来表达，比任何开关形态都准确，也不会让人误以为
 * 它是"当前处于关闭状态"。
 *
 * ⚠️ 两行开关**互不联动**：点一个不会顺手改另一个（各有各的信封，验收清单里单列了这一条）。
 */
@Composable
internal fun QuickUnlockSettingsRows(
    state: QuickUnlockController.UiState,
    canAuthenticate: Boolean,
    onToggleBiometric: () -> Unit,
    onTogglePin: () -> Unit,
    onManage: () -> Unit,
) {
    SettingsRow(
        icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
        title = stringResource(R.string.quick_unlock_section_biometric),
        subtitle = biometricSummary(state, canAuthenticate),
        enabled = canAuthenticate,
        // 整行可点（热区比开关本身大得多）；开关自带处理，点开关不会双触发。
        onClick = onToggleBiometric,
        trailing = {
            CapabilityToggle(
                capability = state.biometric,
                enabled = canAuthenticate,
                // 设备不支持认证时不给点：点了也走不完流程，允许点等于给出一个必然失败的承诺。
                onToggle = onToggleBiometric,
            )
        },
    )
    SettingsDivider()
    SettingsRow(
        icon = { Icon(Icons.Filled.Password, contentDescription = null) },
        title = stringResource(R.string.pin_section_title),
        subtitle = pinSummary(state),
        onClick = onTogglePin,
        trailing = {
            CapabilityToggle(
                capability = state.pin,
                enabled = true,
                onToggle = onTogglePin,
            )
        },
    )
    SettingsDivider()
    SettingsRow(
        icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
        title = stringResource(R.string.quick_unlock_manage_action),
        subtitle = stringResource(R.string.settings_quick_unlock_manage_desc),
        onClick = onManage,
    )
}

/**
 * 一个「能力」的尾部控件：**三态渲染成两种控件**。
 *
 * - [QuickUnlockController.CapabilityState.On] / `Off` ⇒ 真正的 [Switch]（开 / 关）；
 * - [QuickUnlockController.CapabilityState.Partial] ⇒ 一个写着「继续」的按钮。
 *
 * ## 为什么 Partial 必须换控件
 *
 * 因为它**不是一个开关状态**。开关的两个位置（开/关）回答的是"这个功能现在生效吗"，
 * 而 `Partial` 回答的是另一个问题："这活儿干到一半，还剩几个库没配"。
 * 把它塞进开关的"关"位置，用户就会按"关 → 点一下就开"去理解它，
 * 于是得到的是"点了没开，弹了个框"的困惑。
 *
 * 换成按钮后，用户看到的是「继续」——一个动作提示，点它 = 把没配完的配完，
 * 与 [QuickUnlockController.toggleBiometric] 里 `Partial ⇒ showConfigure(...)` 的实际行为
 * 一一对应。**控件形态与行为语义对齐**，注释可以删掉，界面自己会说话。
 *
 * ## 无障碍
 *
 * 按钮用 [TextButton] 而非自绘，天然带 `Role.Button` 语义与最小触摸尺寸；
 * 开关走 [Switch] 自带语义。两者都不需要额外 `semantics` 标注。
 */
@Composable
private fun CapabilityToggle(
    capability: QuickUnlockController.CapabilityState,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    when (capability) {
        is QuickUnlockController.CapabilityState.Partial -> TextButton(
            onClick = onToggle,
            enabled = enabled,
        ) {
            Text(stringResource(R.string.quick_unlock_partial_action))
        }

        is QuickUnlockController.CapabilityState.On -> Switch(
            checked = true,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )

        QuickUnlockController.CapabilityState.Off -> Switch(
            checked = false,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
    }
}

/**
 * ★ **配置向导**：一次问清「对哪些库 + 用哪些方式」，确认后走完整流程。
 *
 * ## 为什么是这个形状（它替代了旧的三处入口）
 *
 * 旧交互是「逐库一行 + 两步对话框 + 每种方式各跑一遍」。其中"每种方式各跑一遍"是**有实际代价的**：
 * KDBX 的主密码两种方式都要用（见 `QuickUnlockController` 类 KDoc 里那条引文），
 * 分开跑就意味着**每个 KDBX 库要输两遍主密码**。合进一次流程、主密码只收一次，
 * 才是真正砍掉那个重复次数的做法。
 *
 * ## 三条必须写在界面上的话
 *
 * 1. ★ **默认全勾**（首次配置）—— 用户 99% 想要"所有库都能快速解锁"，逐个勾选是纯负担；
 * 2. ★ **PIN 必须一次做完** —— PIN 只在输入那一刻存在，包裹只能在同一流程里完成。
 *    界面**不能**让用户以为"可以事后再给某个库补 PIN"（那会得到一个永远解不开的信封）；
 * 3. ★ **取消勾选会清掉该库已保存的凭据** —— 写在动手**之前**：
 *    事后的提示追不回已经删掉的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureDialog(
    state: QuickUnlockController.Dialog.Configure,
    canAuthenticate: Boolean,
    controller: QuickUnlockController,
) {
    BasicAlertDialog(onDismissRequest = controller::dismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.quick_unlock_manage_action))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.rows.isEmpty()) {
                    DialogEmptyBody(stringResource(R.string.quick_unlock_manage_none))
                } else {
                    DialogSectionTitle(
                        title = stringResource(R.string.quick_unlock_configure_vaults_title),
                        hint = stringResource(R.string.quick_unlock_configure_vaults_hint),
                    )
                    state.rows.forEach { row ->
                        ConfigureVaultRow(
                            row = row,
                            onToggle = { controller.toggleConfigureVault(row.vaultId) },
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.sm))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.sm))

                DialogSectionTitle(
                    title = stringResource(R.string.quick_unlock_configure_methods_title),
                    hint = stringResource(R.string.quick_unlock_configure_methods_hint),
                )
                ConfigureMethodRow(
                    title = stringResource(R.string.quick_unlock_section_biometric),
                    checked = state.methodBiometric,
                    enabled = canAuthenticate,
                    onToggle = {
                        controller.toggleConfigureMethod(QuickUnlockController.UnlockMethod.BIOMETRIC)
                    },
                )
                ConfigureMethodRow(
                    title = stringResource(R.string.pin_section_title),
                    checked = state.methodPin,
                    enabled = true,
                    onToggle = {
                        controller.toggleConfigureMethod(QuickUnlockController.UnlockMethod.PIN)
                    },
                )
                if (state.methodPin) {
                    ConfigureHint(stringResource(R.string.quick_unlock_configure_pin_warning))
                }
                ConfigureHint(stringResource(R.string.quick_unlock_configure_uncheck_warning))
                state.error?.let { DialogErrorText(it) }
                Spacer(Modifier.height(Spacing.sm))
            }

            DialogActions {
                DialogBackButton(controller::dismiss)
                TextButton(onClick = controller::confirmConfigure) {
                    Text(stringResource(R.string.quick_unlock_configure_start))
                }
            }
        }
    }
}

/** 向导里的一行库：勾选框 + 库名 + 已配好的能力。 */
@Composable
private fun ConfigureVaultRow(row: QuickUnlockController.ConfigureRow, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = row.checked, onValueChange = { onToggle() })
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.checked, onCheckedChange = { onToggle() })
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = readyLabel(row.biometricReady, row.pinReady),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 向导里的一个方式勾选项。 */
@Composable
private fun ConfigureMethodRow(
    title: String,
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
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 向导里的说明句（约束 / 后果）。 */
@Composable
private fun ConfigureHint(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )
}

/** 该库已配好的能力标签（都没配好时留空）。 */
@Composable
private fun readyLabel(biometricReady: Boolean, pinReady: Boolean): String {
    val parts = buildList {
        if (biometricReady) add(stringResource(R.string.quick_unlock_cap_biometric))
        if (pinReady) add(stringResource(R.string.quick_unlock_cap_pin))
    }
    return parts.joinToString(separator = " · ")
}

/**
 * 指纹行的副标题（**一行汇总**，不再逐库列行）。
 *
 * ⚠️ 顺序有意义：**设备不支持**优先于其它说明（反正点不了，先说原因）；
 * `Partial` 其次（它是最需要用户行动的状态）。
 */
@Composable
private fun biometricSummary(
    state: QuickUnlockController.UiState,
    canAuthenticate: Boolean,
): String {
    if (!canAuthenticate) {
        return stringResource(R.string.quick_unlock_option_biometric_unsupported)
    }
    return when (val capability = state.biometric) {
        is QuickUnlockController.CapabilityState.Partial ->
            stringResource(R.string.quick_unlock_partial_hint, capability.pending)
        is QuickUnlockController.CapabilityState.On ->
            stringResource(R.string.quick_unlock_summary_on, readyCount(state, biometric = true))
        QuickUnlockController.CapabilityState.Off ->
            stringResource(R.string.quick_unlock_option_biometric_summary)
    }
}

/**
 * PIN 行的副标题。
 *
 * ⚠️ 比指纹少一个分支：PIN 不依赖系统锁屏，所以没有"设备不支持"这一态。
 */
@Composable
private fun pinSummary(state: QuickUnlockController.UiState): String =
    when (val capability = state.pin) {
        is QuickUnlockController.CapabilityState.Partial ->
            stringResource(R.string.quick_unlock_partial_hint, capability.pending)
        is QuickUnlockController.CapabilityState.On ->
            stringResource(R.string.quick_unlock_summary_on, readyCount(state, biometric = false))
        QuickUnlockController.CapabilityState.Off ->
            stringResource(R.string.quick_unlock_option_pin_summary, PIN_MIN_LENGTH)
    }

/**
 * 副标题里那个数字：范围内**已生效**的库数。
 *
 * ⚠️ 必须与 [QuickUnlockController.CapabilityState] 的推导口径一致（都只看 `inScope` 的行）——
 * 两处口径不一致，就会出现"开关说已启用 3 个、数字写着 5 个"这种自相矛盾。
 */
private fun readyCount(state: QuickUnlockController.UiState, biometric: Boolean): Int =
    state.rows.count { row ->
        row.inScope && if (biometric) row.biometricReady else row.pinReady
    }

/**
 * 流程宿主：认证副作用 + 配置向导 + 四个步骤对话框。
 *
 * 与设置项分开：设置项是"改开关"（内联在页面里），这里是"一次进行中的流程"
 * （配置向导 → 输 PIN → 逐库问主密码 → 认证 → 结果），生命周期完全不同。
 *
 * ⚠️ 参数是**控制器本身**而不是某个 ViewModel：设置页与库列表页各持一个实例
 * （两者都要能发起登记），收 ViewModel 会让其中一个用不了。
 */
@Composable
internal fun QuickUnlockHost(controller: QuickUnlockController) {
    val activity = rememberFragmentActivity()
    val context = LocalContext.current
    // 向导里要不要禁用"指纹"那一项，与设置行用的是同一个判据（设备能否认证）。
    val canAuthenticate = deviceCanAuthenticate(context)
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
        is QuickUnlockController.Dialog.Configure -> ConfigureDialog(current, canAuthenticate, controller)
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
 *
 * ⚠️ [QuickUnlockController.Dialog.Report.scopeOnly] 时三段都是空的，必须**另给一句话**说明
 * "只更新了范围"；否则用户看到的是一个空结果页，读起来像"点坏了"。
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
                if (state.scopeOnly) {
                    ConfigureHint(stringResource(R.string.quick_unlock_report_scope_only))
                }
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
