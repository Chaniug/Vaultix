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
import androidx.compose.material.icons.filled.Upgrade
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
import io.vaultix.domain.PIN_MAX_ATTEMPTS
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
 * 「解锁方式」的设置行（**内联在密码库管理页**，不再是一个对话框）。
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
 * ## ★ 行与开关的分工（2026-09-29 删第三行，真机反馈"三行冗余"）
 *
 * | 点哪里 | 干什么 |
 * |---|---|
 * | **整行**（热区远大于开关） | 进 [ConfigureDialog] 向导：改库范围 / 补配另一种方式 |
 * | **开关本体** | `On` ⇒ 关掉这把门锁；`Off` ⇒ 同样进向导（与点行等效） |
 *
 * 旧形态有第三行「管理解锁方式」专做进向导的入口 —— 但两个开关行**本来就可点**
 * （热区大得多），再放一个功能相同的第三行，用户看到的只是"三行说一件事"
 * （2026-09-29 真机验收原话："看起来有点冗余"）。⇒ 把"管理"并进整行点击，
 * 第三行删除。向导标题仍叫「管理解锁方式」（[R.string.quick_unlock_manage_action]）。
 *
 * ⚠️ [onManage] 不是第三行的遗物：顶部「旧模型残留需重新登记」的提示行还在用它
 * （那行的语义是"两种方式都不预选"，与按方式进向导不同）。
 *
 * ## 开关只有**两种**呈现（批次 3，2026-09-29 删 `Partial`）
 *
 * | 状态 | 尾部控件 | 副标题 |
 * |---|---|---|
 * | `On` | 开着的开关 | 「已对 N 个库生效」 |
 * | `Off` | 关着的开关 | 未启用（或设备不支持） |
 *
 * ### 为什么曾经有第三种、现在没有了
 *
 * 旧模型是「每库各一份信封」，于是"范围内 8 个库里配好了 5 个"是一个真实存在的状态，
 * 那时要把它画成开关的哪一档都不对：画"开"是谎报（#93 同族），画"关"又会让用户
 * 以为点一下就能开全（实际是接着配剩下 3 个）⇒ 2026-09-26 把它画成了「继续」按钮。
 *
 * 房子化之后**这个状态在结构上不存在了**：开关只对应**一把全局门锁**，
 * 开门锁 = 一次 wrap，要么成功要么不变。⇒ 三态渲染成两种控件的那段设计整体作废，
 * 开关重新变回一个**普通的二值开关**。
 *
 * ⚠️ 这段历史留着不是怀旧：它解释的是"**为什么不能再按范围进度推导开关**"。
 * 哪天有人为了"显示得更精细"又把按库进度接回来，#93 会原样复发。
 *
 * ⚠️ 两行开关**互不联动**：点一个不会顺手改另一个（各有各的信封，验收清单里单列了这一条）。
 */
@Composable
internal fun QuickUnlockSettingsRows(
    state: QuickUnlockController.UiState,
    canAuthenticate: Boolean,
    /**
     * 设备上是否还残留旧「每库信封」模型的垃圾数据。
     *
     * `true` ⇒ 顶部多一行「需重新登记」的提示：老用户的旧信封**无法自动升级**
     * （定稿 §8 不写兼容层），不给这句话，用户看到的就是"升级之后快速解锁莫名不能用了"。
     */
    legacyRemains: Boolean,
    /** 点「指纹」**整行**：进向导并预选指纹（改范围 / 补配，见类 KDoc 的分工表）。 */
    onManageBiometric: () -> Unit,
    /** 点「PIN」**整行**：进向导并预选 PIN。 */
    onManagePin: () -> Unit,
    /** 拨「指纹」**开关**：`On` ⇒ 关门锁；`Off` ⇒ 进向导（与点行等效）。 */
    onToggleBiometric: () -> Unit,
    /** 拨「PIN」**开关**：语义同 [onToggleBiometric]。 */
    onTogglePin: () -> Unit,
    /** 旧模型残留提示行的点击（两种方式都不预选的向导）。 */
    onManage: () -> Unit,
) {
    if (legacyRemains) {
        SettingsRow(
            icon = { Icon(Icons.Filled.Upgrade, contentDescription = null) },
            title = stringResource(R.string.quick_unlock_legacy_title),
            subtitle = stringResource(R.string.quick_unlock_legacy_desc),
            onClick = onManage,
        )
        SettingsDivider()
    }
    SettingsRow(
        icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
        title = stringResource(R.string.quick_unlock_section_biometric),
        subtitle = biometricSummary(state, canAuthenticate),
        enabled = canAuthenticate,
        // 整行 = 管理（进向导）：热区比开关本身大得多，且 On 态下"想改范围"远比
        // "想关掉"高频 —— 关闭这个低频破坏性动作留给开关本体，避免误触整行即关闭。
        onClick = onManageBiometric,
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
        onClick = onManagePin,
        trailing = {
            CapabilityToggle(
                capability = state.pin,
                enabled = true,
                onToggle = onTogglePin,
            )
        },
    )
}

/**
 * 一个「能力」的尾部控件：**就是一个普通开关**。
 *
 * ⚠️ 门禁（2026-09-26 #121 的教训，别再犯）：`checked` 必须是 `when` 分派出来的
 * **常量**，不能写成 `capability is CapabilityState.On` —— 后者把多态状态压成二值，
 * 类型检查 / detekt / `when` 穷尽性**全都查不出**，只在真机上表现为
 * "点了没反应"。探针 `check_state_flattening.py` 就是守这一条的。
 *
 * 无障碍：[Switch] 自带 `Role.Switch` 语义与最小触摸尺寸，不需要额外 `semantics`。
 */
@Composable
private fun CapabilityToggle(
    capability: QuickUnlockController.CapabilityState,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val checked = when (capability) {
        is QuickUnlockController.CapabilityState.On -> true
        QuickUnlockController.CapabilityState.Off -> false
    }
    Switch(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
    )
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

/**
 * 向导里的一行库 = **一个纯复选框 + 库名**（批次 3：删掉了行尾的「指纹 · PIN」标记）。
 *
 * ⚠️ 别再加回"这个库已配好哪些方式"的角标：房子化后门锁是**全局**的，
 * 一个库的快速解锁状态只有一个事实（房间信封在不在），而"在不在"用户**看不见也不需要看见**
 * —— 向导本来就默认全勾，已配好的库会被自动跳过，标出来只是把已经不存在的区分画给他看。
 */
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

/**
 * 指纹行的副标题（**一行汇总**，不再逐库列行）。
 *
 * ⚠️ 顺序有意义：**设备不支持**优先于其它说明（反正点不了，先说原因）。
 */
@Composable
private fun biometricSummary(
    state: QuickUnlockController.UiState,
    canAuthenticate: Boolean,
): String {
    if (!canAuthenticate) {
        return stringResource(R.string.quick_unlock_option_biometric_unsupported)
    }
    return when (state.biometric) {
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
 *
 * ⚠️ 熔断次数取自 [PIN_MAX_ATTEMPTS] 而不是写死在文案里：文案里的数字
 * 一旦与代码里的阈值分叉，用户就会被告知一个**不成立的承诺**
 * （"输错 5 次锁定"其实 3 次就锁了 —— 谎报状态那一族）。
 */
@Composable
private fun pinSummary(state: QuickUnlockController.UiState): String =
    when (state.pin) {
        is QuickUnlockController.CapabilityState.On ->
            stringResource(R.string.quick_unlock_summary_on, readyCount(state, biometric = false))
        QuickUnlockController.CapabilityState.Off ->
            stringResource(
                R.string.quick_unlock_option_pin_summary,
                PIN_MIN_LENGTH,
                PIN_MAX_ATTEMPTS,
            )
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
 * 结果页：**数量给结论、逐条给出路**（批次 3；定稿 §5.1）。
 *
 * - 「已纳入 N 个库」/「跳过 M 个」报**数字**（逐库列名对刚勾完 8 个库的用户是噪音）；
 * - 「未成功」**逐条列库名 + 原因** —— 失败必须可行动，只知道"有 2 个失败了"，
 *   用户唯一的出路就是全部重来一遍。
 *
 * ⚠️ 「跳过」不能并进失败：那是用户的选择，并进去会让他以为自己操作错了；
 * 也不能省略 —— 省了就是"假成功"。
 *
 * ⚠️ [QuickUnlockController.Dialog.Report.scopeOnly] / `lockOnly` 时两个数字都是 0，
 * 必须**另给一句话**说明"只更新了范围 / 只开了锁"；否则用户看到的是一个空结果页，
 * 读起来像"点坏了"。
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
                if (state.lockOnly) {
                    ConfigureHint(stringResource(R.string.quick_unlock_report_lock_only))
                }
                // 0 不显示：空段只会把"这次其实没动库"演成"纳入了 0 个"（噪音），
                // 那种情况已由上面的 scopeOnly / lockOnly 说明句接住。
                if (state.enrolledCount > 0) {
                    ReportCount(
                        stringResource(R.string.quick_unlock_report_enrolled, state.enrolledCount),
                    )
                }
                if (state.skippedCount > 0) {
                    ReportCount(
                        stringResource(R.string.quick_unlock_report_skipped, state.skippedCount),
                    )
                }
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

/** 结果页里的一句计数（「已纳入 N 个库」/「跳过 M 个」）。 */
@Composable
private fun ReportCount(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )
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
