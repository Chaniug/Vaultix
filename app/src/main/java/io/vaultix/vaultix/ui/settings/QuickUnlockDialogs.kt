/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
 * （「已对 N 个库生效」），不再逐库列行。
 *
 * ## ★ 2026-09-30 晚：**"拨开关 = 开始配置"**（用户要求"只需要一个打开的按钮"）
 *
 * 用户真机反馈：点开的那张**配置向导弹窗**"做的不好看、逻辑混乱、让人摸不着头脑"。
 * ⇒ 向导**整体删除**（`Dialog.Configure` / `ConfigureDialog` / `ConfigureMethodRow`
 * 一并删），现在：
 *
 * | 点哪里 | 干什么 |
 * |---|---|
 * | **整行** | 与拨开关**完全等效** —— 向导没了，"点行"不再有第二种含义 |
 * | **开关本体** | `Off` ⇒ 开始这一种方式的登记流程；`On` ⇒ 关掉这把门锁 |
 *
 * 于是"注册"这件事只剩一个入口、一个动作：**打开这个开关**。流程本身仍是后台编排的
 * （可能依次问你：PIN → 各 KDBX 库主密码 → 指纹认证 → 结果），但**不再有那张要先做选择的向导页**。
 *
 * ⚠️ 之前"先过向导挑方式"的一个副作用是"两种方式一起开时主密码只收一次"。
 *   删掉向导后这一点**没有退步**：房间信封是**每库一份、与方式无关**的，
 *   所以先开哪一种就把房间都封好了，再开另一种时 `Session.pendingRooms()` 为空
 *   ⇒ **不会再问一遍主密码**（实测口径见 `QuickUnlockController.pendingRooms`）。
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
 * ⚠️ 两行开关**互不联动**：拨一个不会顺手改另一个（各有各的信封 —— 用户明确要的
 * "可以都开或者只开一种"）。
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
    /** 拨「指纹」开关（**点整行等价**）。 */
    onToggleBiometric: () -> Unit,
    /** 拨「PIN」开关。 */
    onTogglePin: () -> Unit,
    /**
     * 点「修改 PIN」（**只有 PIN 已启用时那一行为可点**）。
     *
     * ⚠️ #161：设置页曾向用户承诺「忘记它不影响数据——用主密码解锁后在设置里重设即可」，
     * 而设置里只有开关两态，拨一下是**关掉** PIN，重设无处可去。
     *
     * ⚠️ 为什么是**这一行**而不是把开关变成「改」：开关那一路的"开 / 关"语义
     * 是 2026-09-30 用户拍板过的（"只需要一个打开的按钮"），把它改掉会同时
     * 推翻下面那段「点整行 = 拨开关」的历史约定；在它旁开一行更省事也更稳。
     */
    onStartChangePin: () -> Unit,
) {
    if (legacyRemains) {
        SettingsRow(
            icon = { Icon(Icons.Filled.Upgrade, contentDescription = null) },
            title = stringResource(R.string.quick_unlock_legacy_title),
            subtitle = stringResource(R.string.quick_unlock_legacy_desc),
            // ⚠️ **刻意不可点**（2026-09-30 晚，向导删除后）：它的动作与下面两个开关
            //    完全重合，留着 onClick 只会暗示"这里还有一个入口"。
            //    文案已改成"打开下面的开关即可"，读起来是解释而不是入口。
        )
        SettingsDivider()
    }
    SettingsRow(
        icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
        title = stringResource(R.string.quick_unlock_section_biometric),
        // ⚠️ 状态没读出来时**不给副标题**（见 UiState.loaded）——写"未启用"与写"已启用"
        //    一样都是猜测，而这一行的副标题恰恰在说"开没开"。
        subtitle = if (state.loaded) biometricSummary(state, canAuthenticate) else null,
        enabled = canAuthenticate,
        // ★ 2026-09-30 晚（用户要求"只需要一个打开的按钮"）：**整行 = 拨开关**。
        //   向导删掉之后，"点行"不再有第二种含义，于是整行就是那个按钮
        //   （热区比开关本身大得多，避免"点了没反应"）。
        onClick = if (canAuthenticate) onToggleBiometric else null,
        trailing = {
            CapabilityToggle(
                capability = state.biometric,
                loaded = state.loaded,
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
        subtitle = if (state.loaded) pinSummary(state) else null,
        onClick = onTogglePin,
        trailing = {
            CapabilityToggle(
                capability = state.pin,
                loaded = state.loaded,
                enabled = true,
                onToggle = onTogglePin,
            )
        },
    )
    // ★ 2026-10-04（#161）：PIN 已启用时，补一行「修改 PIN」。
    //
    // ⚠️ 用 when 分派出一个布尔，而不是直接写 `state.pin is CapabilityState.On`：
    //   后者属于把多态状态压成二值（#121 的教训），类型检查 / detekt / when 穷尽性
    //   全都查不出，只会在真机上表现为"该出现的一行没出现"。
    val pinEnabled = when (state.pin) {
        is QuickUnlockController.CapabilityState.On -> true
        is QuickUnlockController.CapabilityState.Off -> false
    }
    if (state.loaded && pinEnabled) {
        SettingsRow(
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            title = stringResource(R.string.pin_change),
            onClick = onStartChangePin,
        )
        SettingsDivider()
    }
}

/**
 * 一个「能力」的尾部控件：**就是一个普通开关**；状态未知时给**同尺寸占位**。
 *
 * ## ★ 为什么会有"未知"这一态（2026-09-30，用户报的闪烁）
 *
 * `UiState` 的初值是两个 `Off`，而 `Off` **是一个确定的答案**：磁盘上真实为 `On` 时，
 * 开关会先画成"关"、再跳到"开" —— 用户看到的就是「一瞬间从关闭变成打开」。
 * 这与 `.ai/ISSUES.md` #84「三种空」是同一族问题（同一个 SettingsViewModel 里
 * 已为普通布尔开关写过同样一段话）。
 *
 * ⇒ 修法与那边一致：**不认识就什么都不说**。占位**特意不可点**（而 `SettingsSwitch`
 * 的占位是可点的），因为两者的"点一下"代价不同：那个只是写一个布尔（幂等），
 * 这里的开关是 `toggle` —— 若真实值是 `On`，占位期的一下点击会**把已启用的锁关掉**，
 * 正是要避免的那个方向。占位期的点击**落到整行**（进向导），既没吞掉点击、
 * 也不会误关。
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
    loaded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    if (!loaded) {
        // 同尺寸占位（尺寸见 SettingsComponents 的 SWITCH_VISUAL_*，与 SettingsSwitch 一致）
        // ⇒ 数据到达时既没有"假位置"、也不会整行跳动。
        Box(modifier = Modifier.size(SWITCH_VISUAL_WIDTH, SWITCH_VISUAL_HEIGHT))
        return
    }
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
            // ★ 批次 4（定稿 §6）：门锁开着但**信封已解不开**（用户重录过指纹）。
            //   此时谎报 `summary_on`（「已对 N 个库生效」）是 #93 那类假状态；
            //   而说成「已关闭」又会让用户以为得从头配一遍。真相是第三种：
            //   **开着、暂时用不了、下次过指纹时自动补写**。
            if (state.biometricRearmPending) {
                stringResource(R.string.quick_unlock_summary_rearm_pending)
            } else {
                stringResource(R.string.quick_unlock_summary_on, readyCount(state, biometric = true))
            }
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
 * 与设置项分开：设置项是"拨开关"（内联在页面里），这里是"一次进行中的流程"
 * （输 PIN → 逐库问主密码 → 认证 → 结果），生命周期完全不同。
 *
 * ⚠️ 参数是**控制器本身**而不是某个 ViewModel：设置页与库列表页各持一个实例
 * （两者都要能发起登记），收 ViewModel 会让其中一个用不了。
 *
 * ⚠️ 2026-09-30 晚：**配置向导弹窗已删除**（用户反馈"弹出页面做的不好看、逻辑混乱"）。
 * 现在拨开关就是流程本身，这里也就不再需要 `canAuthenticate`（那本来是给向导里
 * "指纹"那一项做禁用判据的）。
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
        QuickUnlockController.Dialog.PinChangeDone -> PinChangeDoneDialog(controller)
    }
}

/**
 * 「修改 PIN 完成」的一次性确认。
 *
 * ⚠️ 为什么它值得占一个对话框：改完 PIN 之后，**开关、副标题、库列表全都没有可见变化**
 * （副标题本来就写"已启用 · 6 位"）。对话框一关等于什么都没说，用户只能猜
 * "到底换了吗、要不要重启才生效" —— 那正是 #161 的反面：设置页承诺过一件事却没兑现，
 * 比当初不承诺更伤信任。这里兑现一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinChangeDoneDialog(controller: QuickUnlockController) {
    BasicAlertDialog(onDismissRequest = controller::dismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.pin_change))
            DialogSectionTitle(
                title = stringResource(R.string.pin_change_done),
                // 「忘记不影响数据、用主密码解锁后可重设」：改 PIN 之后这句话依然成立，
                // 顺带把 #161 那条承诺的落点再点一遍（它现在是真的了）。
                hint = stringResource(R.string.pin_section_hint),
            )
            Spacer(Modifier.height(Spacing.sm))
            DialogActions {
                DialogBackButton(controller::dismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinEntryDialog(
    state: QuickUnlockController.Dialog.PinEntry,
    controller: QuickUnlockController,
) {
    // mode 是 enum（本来就是二值），直接比 == 不存在"压成二值"的问题
    // —— 那条纪律管的是 sealed 的多态状态（见 CapabilityToggle 的 KDoc）。
    val isChange = state.mode == QuickUnlockController.PinMode.Change
    val headerRes = when (state.mode) {
        QuickUnlockController.PinMode.Enroll -> R.string.pin_section_title
        QuickUnlockController.PinMode.Change -> R.string.pin_change
    }
    BasicAlertDialog(onDismissRequest = controller::dismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(headerRes))
            DialogSectionTitle(
                title = stringResource(R.string.pin_set_title, PIN_MIN_LENGTH),
                // 「忘记不影响数据、用主密码解锁后可重设」—— 修改模式下的 hint 依然是这句，
                // 它本来说的就是**重设**，这条承诺以前无处落地，现在落到这一行上。
                hint = stringResource(R.string.pin_section_hint),
            )
            // ⚠️ 2026-10-04 观感：三个输入框原先是**零间距**堆叠的 ——
            //   [DialogSurface] 的 Column 没有 `verticalArrangement`，[PinField] 也没带
            //   任何外边距 ⇒ 三个 `OutlinedTextField` 的描边直接贴合，
            //   看上去是"一个大框被横线切成三段"，而不是三个独立字段。
            //   ⇒ 12dp（[Spacing.md]）：与条目表单里"字段 ↔ 字段"那一档一致，
            //   全 App 表单从此只有一套字段间距。
            //
            // ★ 修改模式：第一个框是**验证当前 PIN**，不是装饰。
            //   不验就重包 = 把门锁拆下来换个别家的密码（#161 真正的病灶）。
            if (isChange) {
                PinField(
                    value = state.old,
                    labelRes = R.string.pin_field_current,
                    onValueChange = controller::onPinOldChange,
                )
                Spacer(Modifier.height(Spacing.md))
            }
            PinField(
                value = state.pin,
                labelRes = R.string.pin_field_new,
                onValueChange = controller::onPinChange,
            )
            Spacer(Modifier.height(Spacing.md))
            PinField(
                value = state.confirm,
                labelRes = R.string.pin_field_confirm,
                onValueChange = controller::onPinConfirmChange,
            )
            state.error?.let { DialogErrorText(it) }
            Spacer(Modifier.height(Spacing.sm))
            DialogActions {
                // ⚠️ 2026-10-04 排版：破坏性动作与主操作**必须分开站位**（8.4「动作区两端对齐」）。
                //   「关闭 PIN 解锁」删掉的是门锁本身（不可逆），此前它与「返回」「继续」
                //   一起右对齐、彼此紧挨 —— 用户在"想退出"与"想继续"之间极易误触。
                //   ⇒ 塞一个 `weight(1f)` 的空隙把它推到最左，右边只留返回/继续。
                //   （`DialogActions` 的 content 是 `RowScope`，故这里能直接用 weight。）
                if (isChange) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = controller::disablePinFromChange) {
                        Text(
                            text = stringResource(R.string.pin_disable),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                }
                DialogBackButton(controller::dismiss)
                TextButton(
                    onClick = if (isChange) controller::submitChangePin else controller::submitPin,
                ) {
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
                label = {
                    // ⚠️ 2026-10-04 观感：与 [PinField] 同一规格（`bodyMedium`）。
                    //   两个弹窗都是"一个密码框+ 错误文案"，字段名却一大一小 ⇒ 观感廉价。
                    Text(
                        text = stringResource(R.string.quick_unlock_kdbx_password_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                // ⚠️ 2026-10-04：与 [PinField] 同一处缺失（`DialogSurface` 不给水平内边距），
                //   这个弹窗的输入框同样贴到了面板左右边缘 ⇒ 补同一档 [Spacing.xl]。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl),
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
        // ⚠️ 2026-10-04 观感：显式降到 `bodyMedium`
        //   —— `OutlinedTextField` 的 label 默认走 M3 的 `bodyLarge`(16sp)，
        //   比「锁与安全」设置行副标题（`bodyMedium`）还大一号，
        //   ⇒ 一屏里字段名比它的说明文字更显眼，**层级倒挂**。
        //   不覆盖 color：M3 在聚焦/有值时会把它换成 primary / onSurfaceVariant，
        //   那是状态提示，必须保留。
        label = { Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        // ⚠️ 2026-10-04 用户反馈「点修改 PIN 时输入框顶到了弹窗边缘」。
        //   根因：[DialogSurface] 的 Column **只给了 `padding(vertical = …)`，没有水平 padding**
        //   —— 水平内边距是靠每个子元素**各自**带的（[DialogHeader] / [DialogSectionTitle] /
        //   [DialogActions] 都是 `start/end = Spacing.xl`）。
        //   而本函数原先是裸的 `fillMaxWidth()` ⇒ 输入框成了**唯一贴到面板左右边缘的元素**，
        //   与上方标题、下方按钮的内缘对不上，看起来就是"顶到边框了"。
        //   ⇒ 显式补上同一档 `Spacing.xl`，四类子元素的内缘从此对齐。
        //   ⚠️ 不要改成 `DialogSurface` 统一给水平 padding：那是 25 处弹窗的共用外壳，
        //   各调用点现有的内边距值并不统一（有的用 xl、有的用 lg），改外壳要重调全部调用点。
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl),
    )
}

@Composable
private fun DialogErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        // ⚠️ 2026-10-04 观感：原先是 `Spacing.xs`(4dp)。
        //   错误文案是**紧跟在出错的那个输入框下面**的，4dp 贴得太近，
        //   看起来像输入框的一部分（描边外的"第四个框"）。
        //   提到 [Spacing.sm](8dp) 后：上边 8dp（脱离字段）、下边由调用方的
        //   [Spacing.sm] 收口⇒ 字段与错误文案合成一"组"，与下一个字段再分开。
        modifier = Modifier.padding(top = Spacing.sm),
    )
}
