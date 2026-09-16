package io.vaultix.vaultix.ui.unlock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.common.TwoFactorStep
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.common.VaultixWavyProgress
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.unlockErrorText
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.width
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.model.VaultKind
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 解锁页（Docs/08 S6）。
 *
 * 内容居中：库图标位（首字母）、库名 + 账号、主密码输入、解锁按钮；
 * 已启用「本地快速解锁」时顶部出现生物识别 / 设备 PIN 按钮：认证通过即
 * 解封本地密钥（离线、免 2FA）；失败/取消回退主密码。
 */
@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    /**
     * 确认没有可解锁的库时的逃生通道（回库列表 / 回上一层）。
     *
     * 没有它，解锁页在"库列表已到达但没有目标库"时只能永远转圈
     * （[UnlockViewModel.UiState.vault] 永远为 null，且没有任何出口）。
     */
    onNoVault: () -> Unit = {},
    /**
     * 「换一个库」出口（null = 只有一个库，无需显示）。
     *
     * issue #96：多库并存时，用户冷启动落在解锁页后**没有任何办法**切到另一个库 ——
     * 只能先解锁当前这个、进主界面、再摸到设置页。「默认库」决定第一屏显示谁，
     * 这个出口保证「显示的不是我想开的那个」时有路可走。
     */
    onSwitchVault: (() -> Unit)? = null,
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val activity = rememberFragmentActivity()
    // PIN 模式把键盘压到拇指区所需的顶部让位（主密码模式恒为 0dp）。
    // 条件与下方「换一个库」的显示条件保持一致，否则会漏算 / 多算它的高度。
    val switchVisible = onSwitchVault != null && state.twoFactor == null && !state.viewLocked
    val layout = unlockColumnLayout(pinMode = state.pinMode, switchVisible = switchVisible)
    // 认证对话框文案（LaunchedEffect 内不可直接 stringResource，先取好）
    val biometricTitle = stringResource(R.string.quick_unlock_biometric_title)
    val biometricSubtitle = stringResource(R.string.quick_unlock_biometric_subtitle)
    val cancelText = stringResource(R.string.action_cancel)

    // BiometricPrompt 事件：收到 cipher 即弹认证，成功回调回传 VM 完成解封。
    // `forViewLock` 由**本次事件发起时的状态**决定（不是回调时的状态）：
    // 认证对话框弹出期间状态可能被其它流改写，用它去判分支会走错成功路径。
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                UnlockViewModel.Event.Unlocked -> onUnlocked()
                is UnlockViewModel.Event.PromptForUnlock -> {
                    val forViewLock = viewModel.state.value.viewUnlockStarted
                    val host = activity ?: run {
                        viewModel.onBiometricPromptDismissed()
                        return@collect
                    }
                    BiometricPrompter(host).authenticate(
                        cipher = event.cipher,
                        title = biometricTitle,
                        subtitle = biometricSubtitle,
                        cancelText = cancelText,
                        onSuccess = { cipher ->
                            // ★ `event.rest` 原样带上：本次认证要顺带解封的库在**发起时**
                            //   就定好了（见 Event.PromptForUnlock 的 KDoc），认证期间库列表
                            //   若变化不应改变这次的解封范围。
                            viewModel.completeLocalUnlock(cipher, forViewLock, event.rest)
                        },
                        // ⚠️ **任何**错误都要复位 submitting，不能只处理「用户取消」：
                        // ERROR_TIMEOUT / ERROR_CANCELED / ERROR_HW_UNAVAILABLE / ERROR_LOCKOUT
                        // 都不在取消白名单里，一旦落进来而无人复位，submitting 永远为真 ⇒
                        // 指纹按钮与主密码按钮双双置灰、转圈不散、后续点击被守卫直接 return
                        // ⇒ **整页死锁只能杀进程**。这正是用户反馈的「重启后打开 APP，
                        // 指纹解锁按钮不生效」：新构建会在进入解锁页时自动弹一次认证，
                        // 而重启后首次发起（生物识别 HAL 尚未就绪 / 宿主还没 RESUMED）
                        // 很容易被系统以非「用户取消」的错误码结束。
                        onError = viewModel::onBiometricPromptError,
                    )
                }
            }
        }
    }

    // 进入解锁页自动弹一次认证：查看层锁 → 仅证明「是本人」；真锁 → 本地快速解锁。
    // 抽成独立 composable：守卫条件与相关状态一并移出本函数，避免 UnlockScreen 的
    // CyclomaticComplexMethod / ComplexCondition 越界（CI detekt 质量门会拦，2026-09-12 实测）。
    AutoPromptQuickUnlock(
        localUnlockAvailable = state.localUnlockAvailable,
        viewLocked = state.viewLocked,
        hasTwoFactor = state.twoFactor != null,
        submitting = state.submitting,
        autoPromptAborts = state.autoPromptAborts,
        onPrompt = viewModel::startLocalUnlock,
        onViewPrompt = viewModel::startViewUnlock,
    )

    Scaffold { padding ->
        val vault = state.vault
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 纵骨架（排列方式 + PIN 让位）由 [unlockColumnLayout] 一次决定：
            // PIN 模式顶对齐 + 让位把键盘压到拇指区，主密码模式维持居中（见其 KDoc）。
            verticalArrangement = layout.arrangement,
        ) {
            if (vault == null) {
                if (state.noVaultToUnlock) {
                    // 已确认无库可解锁：立刻离开（不要再转圈）
                    LaunchedEffect(Unit) { onNoVault() }
                } else {
                    // 首帧未到：短暂 loading（库列表一到就有结论）
                    VaultixWavyProgress()
                }
                return@Column
            }
            val twoFactor = state.twoFactor
            if (twoFactor != null) {
                // ---- 2FA 步骤（主密码已通过校验）----
                TwoFactorStep(
                    providers = twoFactor.providers,
                    selectedProvider = twoFactor.provider,
                    submitting = state.submitting,
                    error = state.error,
                    onProviderSelected = viewModel::selectTwoFactorProvider,
                    onSubmit = viewModel::submitCode,
                    onBack = viewModel::backToPassword,
                )
                return@Column
            }
            // ---- 查看层锁（主页锁按钮）：只认证，不重登 ----
            // 密钥仍在内存，所以**不渲染主密码 / 2FA 区块** —— 渲染了就是在骗用户
            // 「你的密钥被清了」（用户原话：解锁完还要再验证一次，逻辑太稀烂）。
            if (state.viewLocked) {
                ViewLockedContent(
                    kind = vault.kind,
                    vaultName = vault.name,
                    account = vault.account,
                    submitting = state.submitting,
                    error = state.error,
                    onAuthenticate = viewModel::startViewUnlock,
                )
                return@Column
            }
            VaultHeader(kind = vault.kind, name = vault.name, account = vault.account)
            // 让位量：PIN 模式把键盘压到下半屏（拇指区），主密码模式恒为 0dp
            // —— 它的表单一屏放得下，居中观感保持不变（见 [rememberPinTopSpacer]）。
            if (layout.topSpacer > 0.dp) {
                Spacer(Modifier.height(layout.topSpacer))
            }
            UnlockInputSection(
                state = state,
                viewModel = viewModel,
                focusManager = focusManager,
            )
            // 「换一个库」：多库时才有意义。放在最底下、用轻量文字按钮 ——
            // 它是**次要出口**，不能与主解锁按钮抢视觉焦点（同 2026-09-13 指纹图标的取舍）。
            // ⚠️ 条件与 `switchVisible` 同源（不重复写一遍）：它的高度已经算进上方的
            // PIN 让位，两处漂移就会出现「算进去了却没显示」或反之。
            // `switchVisible` 只用来决定"要不要画"，取回调仍走 `onSwitchVault?.let` ——
            // 函数参数是 `val` 但不做智能转换，`if (switchVisible)` 里直接当非空用编译不过。
            if (switchVisible) {
                Spacer(Modifier.height(Spacing.sm))
                onSwitchVault?.let { switch ->
                    TextButton(onClick = switch, enabled = !state.submitting) {
                        Icon(
                            Icons.Filled.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.unlock_switch_vault))
                    }
                }
            }
        }
    }
}

/**
 * 查看层锁的页面内容：**只有一次身份认证**，没有主密码、没有 2FA、没有联网。
 *
 * 为什么值得单独一个 composable：这段是「锁按钮只锁生物验证那一层」这个需求的
 * 用户可见形态（`.ai/ISSUES.md` #60 第 1d 步）。混在 [UnlockScreen] 里会让主函数
 * 的行数与分支数双双越界（detekt `LongMethod` / `CyclomaticComplexMethod`）。
 *
 * 手动「再次认证」按钮必须保留：自动弹一次被用户取消后（去拿眼镜 / 手湿），
 * 没有它用户就只能杀进程。
 */
@Composable
private fun ViewLockedContent(
    kind: VaultKind,
    vaultName: String,
    account: String?,
    submitting: Boolean,
    error: UnlockUiError?,
    onAuthenticate: () -> Unit,
) {
    VaultHeader(kind = kind, name = vaultName, account = account)
    Spacer(Modifier.height(Spacing.sm))
    Text(
        text = stringResource(R.string.unlock_view_locked_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Spacing.xl))
    FilledTonalButton(
        onClick = onAuthenticate,
        enabled = !submitting,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Icon(Icons.Filled.Fingerprint, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(stringResource(R.string.quick_unlock_biometric_button))
    }
    if (submitting) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = Spacing.lg),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.add_vault_working),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
    }
    unlockErrorText(error)?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/** 指纹入口图标的尺寸。
 *
 * 2026-09-13 用户反馈「指纹的图标太大了，有点不合适」⇒ 从 64dp 收到 **44dp**。
 * 它仍是这一页唯一的主入口，但不必跟下方的实心主按钮抢体量 —— 44dp 与按钮的 48dp
 * 高度接近，视觉上落在同一层级，页面也不再头重脚轻。
 */
private val FINGERPRINT_ICON_SIZE = 44.dp

/**
 * 本地快速解锁入口：**一个大号指纹图标**，压在「主密码框」与「解锁按钮」之间。
 *
 * 为什么不是按钮（2026-09-13 用户反馈「指纹解锁的文案能否直接换成一个指纹的图标」）：
 * 这里原本是「灰色文字按钮 + 指纹小图标 + 一句长文案」，在那个位置既抢了主按钮的视觉焦点，
 * 又因为文案很长把页面撑得零碎。现在改成一个**无文字的图标入口**：
 * - 位置不变（仍在密码框与解锁按钮之间），因此「密码 → 指纹 → 解锁」的阅读顺序不变；
 * - 解锁按钮仍是页面唯一的实心主按钮、逻辑与置灰规则完全不变 ——
 *   主操作不能被一个同等体量的次操作稀释；
 * - 指纹可用时依旧**自动弹出**（见 [AutoPromptQuickUnlock]），图标是「取消后手动再来一次」
 *   的出口，所以它必须始终可见、可点。
 *
 * 不可用时保留 [Spacer] 占位：否则「有没有指纹」会让下方按钮的位置跳动。
 */
@Composable
private fun QuickUnlockEntry(
    visible: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
) {
    if (!visible) {
        Spacer(Modifier.height(Spacing.xl))
        return
    }
    IconButton(
        onClick = onStart,
        enabled = enabled,
        modifier = Modifier
            .padding(vertical = Spacing.sm)
            .size(FINGERPRINT_ICON_SIZE + Spacing.lg),
    ) {
        Icon(
            imageVector = Icons.Filled.Fingerprint,
            contentDescription = stringResource(R.string.quick_unlock_biometric_button),
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(FINGERPRINT_ICON_SIZE),
        )
    }
    Text(
        text = stringResource(R.string.unlock_fingerprint_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        textAlign = TextAlign.Center,
    )
}

/** 主密码输入 + 错误提示 + 提交；生物识别快捷入口内嵌于登录区域（密码框与解锁按钮之间）。 */
@Composable
private fun PasswordForm(
    state: UnlockViewModel.UiState,
    viewModel: UnlockViewModel,
    focusManager: androidx.compose.ui.focus.FocusManager,
    quickUnlockVisible: Boolean,
    quickUnlockEnabled: Boolean,
    onQuickUnlock: () -> Unit,
    onPinUnlock: () -> Unit,
) {
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text(stringResource(R.string.unlock_password)) },
        singleLine = true,
        visualTransformation = if (state.passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
            viewModel.submit()
        }),
        trailingIcon = {
            IconButton(onClick = {
                viewModel.onPasswordVisibleChange(!state.passwordVisible)
            }) {
                Icon(
                    imageVector = if (state.passwordVisible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = stringResource(
                        if (state.passwordVisible) {
                            R.string.add_vault_password_hidden
                        } else {
                            R.string.add_vault_password_visible
                        },
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    QuickUnlockEntry(
        visible = quickUnlockVisible,
        enabled = quickUnlockEnabled,
        onStart = onQuickUnlock,
    )

    // 应用内 PIN 入口：与指纹**并列**的另一种本地解锁手段。
    // - 只在**已启用**时出现（没启用却给入口 = 点了必然失败，那是骗人）；
    // - 2FA 步骤中不给：那一步的语义是「验验证码」，多一个出口只会让用户分神；
    // - 与指纹入口并列而非替换：两者可以同时启用，用户按当下条件挑。
    if (state.pinUnlockAvailable && state.twoFactor == null) {
        TextButton(onClick = onPinUnlock, enabled = !state.submitting) {
            Icon(
                imageVector = Icons.Filled.Pin,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.pin_unlock_enter))
        }
    }

    unlockErrorText(state.error)?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }

    if (state.submitting) {
        // ⚠️ 2026-09-13 用户反馈：冷启动自动走指纹时，这一页还挂着
        // 「正在派生密钥并解锁…（约需 1–3 秒）」那串小字，很影响观感。
        // 现在只留一个转圈：主密码派生确实要等，但**不需要一句解释**；而走本地
        // 快速解锁（指纹）时压根没有"派生密钥"这回事，那句文案更是误导。
        CircularProgressIndicator(
            modifier = Modifier
                .padding(top = Spacing.sm)
                .size(20.dp),
            strokeWidth = 2.dp,
        )
    }

    // ⚠️ 间距取 12dp 而不是原来的 24dp：指纹图标自己带 8dp 垂直留白，
    // 再叠 24dp 会让「密码框 → 指纹 → 解锁按钮」这一段松散成三截。
    Spacer(Modifier.height(Spacing.md))
    FilledTonalButton(
        onClick = {
            focusManager.clearFocus()
            viewModel.submit()
        },
        enabled = !state.submitting && state.password.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(stringResource(R.string.unlock_submit))
    }
}

/**
 * 进入解锁页后**自动弹一次**本地快速解锁（生物识别 / 指纹）。
 *
 * 只要该库已启用快速解锁，就不必再让用户找按钮点一下（对齐 Bitwarden 的解锁体验）。
 * 只自动触发一次：用户取消后不再反复弹（尊重「改用主密码」的意图），仍可手动点按钮再触发；
 * ⚠️ 2026-09-13 用户报告「生物验证不是 100% 能在覆盖安装后弹出」。根因就在这一段状态机：
 * `prompted` 是**一次性守卫**，却是在**弹窗真正出现之前**置位的 ⇒ 任何"没弹成功"都被
 * 永久记成"弹过了"。而 `BiometricPrompt` 在**冷启动首帧**（窗口尚未可见）会立刻以
 * `ERROR_CANCELED` 结束 —— 覆盖安装 / 重启后正是这个场景。
 * 现在把「用户放弃」与「系统终止」拆开：[autoPromptAborts] 一变就把机会**还回来**、允许再试
 * （有次数上限，避免硬件持续不可用时无限重试）；用户放弃则继续不再打扰。
 *
 * ⚠️ 守卫从 `rememberSaveable` 改成 `remember`：进程被杀后恢复时，saved state 会把
 * "已弹过"带回来，而那次可能压根没展示过 ⇒ 恢复后彻底不弹。改用 `remember` 后，
 * 进程内只弹一次、重建/恢复后允许再来一次（用户放弃过的除外）。
 *
 * 单独成函数而非内联在 [UnlockScreen]：把守卫条件与相关状态隔离在此，避免主函数的
 * CyclomaticComplexMethod / ComplexCondition 越界（CI detekt 质量门会拦）。
 */

/**
 * 解锁输入区：**PIN 模式与主密码模式二选一**。
 *
 * PIN 模式**整体替换**主密码表单，而不是叠在它下面：两种输入方式的键盘与提交时机
 * 都不同（PIN 满 6 位自动提交，主密码要按按钮），并存只会让用户不知道该按哪里。
 *
 * 抽成独立 composable 的直接原因是不让 [UnlockScreen] 越过 detekt
 * `CyclomaticComplexMethod`（加这一个分支就把它顶到 15，上限 14）——
 * 但这个二选一本来就是一个独立概念，抽出来两边都更清楚。
 */
@Composable
private fun UnlockInputSection(
    state: UnlockViewModel.UiState,
    viewModel: UnlockViewModel,
    focusManager: androidx.compose.ui.focus.FocusManager,
) {
    if (state.pinMode) {
        PinPad(state = state, viewModel = viewModel)
    } else {
        PasswordForm(
            state = state,
            viewModel = viewModel,
            focusManager = focusManager,
            quickUnlockVisible = state.localUnlockAvailable && state.twoFactor == null,
            quickUnlockEnabled = !state.submitting,
            onQuickUnlock = viewModel::startLocalUnlock,
            onPinUnlock = viewModel::enterPinMode,
        )
    }
}

/** PIN 圆点直径。 */
private val PIN_DOT_SIZE = Spacing.lg

/**
 * 解锁页头部：**库类型徽标 + 库名 + 账号**。
 *
 * 抽成独立 composable 有两个理由：
 * 1. 主密码 / PIN / 查看层锁三条路径都要这一块，且**顺序与间距必须一致**；
 * 2. `account?.let { … }` 会往 [UnlockScreen] 里加一个条件分支，而那个函数
 *    已经贴着 detekt `CyclomaticComplexMethod` 上限（≤14，2026-09-16 实测超限）。
 *
 * @param kind 库类型（决定徽标图标，见 [VaultKindBadge]）。
 * @param account 账号标签（Bitwarden = 邮箱；KDBX 为 null ⇒ 整行不画）。
 */
@Composable
private fun VaultHeader(kind: VaultKind, name: String, account: String?) {
    VaultKindBadge(kind = kind)
    Spacer(Modifier.height(Spacing.md))
    Text(text = name, style = MaterialTheme.typography.titleLarge)
    if (account != null) {
        Text(
            text = account,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 库类型徽标：**按库类型渲染官方图标**，取代此前的「取名称首字母」。
 *
 * ## 为什么不再用首字母
 *
 * 原来这里是 `vault.name.take(1).uppercase()` —— Bitwarden 库恒定显示一个「B」，
 * 既不像品牌标识（用户反馈「这个 B 字能换成 bitwarden 的图标吗」），又**不携带任何信息**：
 * 一个字母分不出「这是什么类型的库」。而解锁页恰恰是用户最需要确认
 * 「我现在开的是哪个库」的地方。
 *
 * ## 徽标规格
 *
 * 圆形底衬（`primaryContainer`，直径 [BADGE_SIZE]）+ 居中图标（[BADGE_ICON_SIZE]，
 * 用 `onPrimaryContainer`）。底衬保证图标在深浅主题下都有足够对比，
 * 也让它与下方的库名形成"头像 + 标题"的常规层级。
 *
 * ⚠️ 图标是**单色矢量**（见 `ic_vault_bitwarden` / `ic_vault_kdbx`），随主题 tint，
 * 因此不担心深色底上出现死白方块。商标归属见两个 drawable 的头注释。
 */
@Composable
private fun VaultKindBadge(kind: VaultKind) {
    val iconRes = when (kind) {
        VaultKind.BITWARDEN -> R.drawable.ic_vault_bitwarden
        VaultKind.KDBX -> R.drawable.ic_vault_kdbx
    }
    val label = when (kind) {
        VaultKind.BITWARDEN -> R.string.vault_kind_bitwarden
        VaultKind.KDBX -> R.string.vault_kind_kdbx
    }
    Box(
        modifier = Modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            // 内容描述给库类型名：读屏用户同样需要知道"这是哪个库"
            // （字母方案下这里是纯文本，反而"读"得出，改成图片后必须显式给）。
            contentDescription = stringResource(label),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(BADGE_ICON_SIZE),
        )
    }
}

/**
 * 徽标直径。
 *
 * 2026-09-16 从原来的 64dp（字母排版的 `headlineLarge` 视觉尺寸）收到 56dp：
 * 官方盾牌图标的**实心面积远大于一个字母**，同样尺寸下观感会"重"一圈；
 * 加上 PIN 模式要压缩头部（见 [rememberPinTopSpacer]），56dp 是两者的平衡点。
 */
private val BADGE_SIZE = 56.dp

/** 徽标内图标尺寸（留出底衬的呼吸感，约 1/2 直径）。 */
private val BADGE_ICON_SIZE = 28.dp
/**
 * 解锁页主列的**纵向骨架**：PIN 让位高度。
 *
 * ## 为什么要打包成一个返回值
 *
 * 这两件事**必须同时决定**，而且都要看 `state.pinMode`：PIN 模式要让位，
 * 主密码模式零让位。写成两个各自判断 `pinMode` 的表达式，就是往
 * [UnlockScreen] 里塞两个分支 —— 实测这会把它顶到 detekt
 * `CyclomaticComplexMethod` 15（上限 14），CI 会拦（2026-09-15 实测）。
 * 收成一个 helper 后主函数里只剩**一次解构赋值**，零新增分支。
 *
 * ## 排列方式（2026-09-16 修正）
 *
 * PIN 模式**改回 `Center`**（此前是 `Top`）。上一版让位是"贴底"值，配 `Top` 才能
 * 精确地把键盘推到最下；现在让位已封顶（见 [rememberPinTopSpacer]），
 * **剩下的空间交给 `Center` 均分到上下两边** —— 这正是"空白不再全堆在头部与键盘之间"
 * 的关键。两个模式现在共用同一种排列，只差让位量。
 *
 * ⚠️ `Center` 在内容超高时会退化为 `Top` + 可滚动（`verticalScroll` 的行为），
 * 不会把内容推出屏幕。
 *
 * @param switchVisible 是否有「换一个库」出口（只有多库时才有）——它的高度要算进让位。
 */
@Composable
private fun unlockColumnLayout(
    pinMode: Boolean,
    switchVisible: Boolean,
): UnlockColumnLayout = UnlockColumnLayout(
    // 主密码模式与 PIN 模式都是 Center：差别只在让位量（PIN 多让一截，整体偏下）。
    arrangement = Arrangement.Center,
    topSpacer = rememberPinTopSpacer(pinMode, switchVisible),
)

/**
 * [unlockColumnLayout] 的返回值：排列方式 + 顶部让位。
 *
 * 不是一个有行为的概念，纯粹是"一次决定两件事"的载体（见其 KDoc 的复杂度说明）。
 */
private data class UnlockColumnLayout(
    val arrangement: Arrangement.Vertical,
    val topSpacer: Dp,
)

/**
 * PIN 模式顶部让位高度：把键盘推向屏幕下方的拇指区。
 *
 * ## 要解决的问题（两轮）
 *
 * **第一轮（2026-09-13）**：用户反馈「PIN 输入时数字键盘太靠上，手指点击有点远」。
 * 根因是整列在可用高度里 [Arrangement.Center] 居中 —— 一屏内容约 520dp，
 * 键盘（4 × 76dp + 间距）落在屏幕中部偏上。
 *
 * **第二轮（2026-09-16，当前）**：用户拿着真机截图反馈
 * 「PIN 码解锁的时候，区域空白太大，上下不够紧凑」—— 图中头部（库名 + 邮箱）
 * 与圆点行之间是一大块空白，上半屏显得很空。
 *
 * ## 上一版错在哪
 *
 * 上一版的公式是「**让内容底边贴着屏幕底部**」：
 *
 * ```
 * 让位 = 屏高 − 头部高 − 键盘高 − 换库按钮高 − 底距     // ← 过头了
 * ```
 *
 * 它把**全部剩余空间**一次性塞进头部与圆点之间。这在"键盘靠下"这件事上确实到位，
 * 但代价是**上半屏被撑开成一片空白**：头像与库名孤零零挂在顶端，圆点被推到老远，
 * 视觉上断成两截 —— 正是用户截图里画框那块。
 *
 * ## 当前做法：让位封顶，头部与键盘作为一个整体居中偏下
 *
 * 不再"贴底"，而是给让位设一个**上限**：最多让出键盘上方 1/3 屏的余量。
 * 超出的空间由根 Column 的 `Arrangement.Center` 均分到上下两边 —— 于是
 * **头像与键盘之间的空白被压扁，而整体仍停在屏幕偏下的位置**。
 *
 * ```
 * 让位 = min(贴底所需让位, 屏高 × PIN_MAX_TOP_GAP_RATIO)
 * ```
 *
 * ⚠️ 保留"贴底所需让位"作为**小屏的下界参考**：屏矮时贴底值本来就小，
 * `min` 自然取它，键盘不会掉出屏幕。也就是：**大屏压扁留白、小屏仍然贴底**。
 *
 * ## 为什么不能简单改成 `weight(1f)` / `Arrangement.Bottom`
 *
 * 根 Column 带 `verticalScroll`（小屏 / 横屏必须能滚），它给子项的是**无界高度**：
 * - `weight(1f)` 要求父级有界 ⇒ 直接抛 `IllegalStateException`；
 * - `Arrangement.Bottom` 只在内容**低于**容器时才把内容推到底，内容一超高就退化成
 *   `Top`，小屏上键盘仍会被推到屏幕外（要滚动才够得着，比居中更糟）。
 *
 * ⇒ 只能「按容器高度反推让位量」，但**必须封顶**（本轮的修正）。
 *
 * ⚠️ 主密码模式恒返回 `0.dp`：它的表单一屏放得下，居中观感是刻意保留的
 * （2026-09-14 已定），本文的诉求只针对 PIN。
 *
 * @param switchVisible 是否有「换一个库」出口（只有多库时才有）。
 */
@Composable
private fun rememberPinTopSpacer(pinMode: Boolean, switchVisible: Boolean): Dp {
    if (!pinMode) return 0.dp
    // 容器高度取窗口高度（解锁页是全屏单页面，没有顶栏 / 底栏压着）。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    // 顶部信息区高：徽标 56dp + Spacing.md + 库名（titleLarge ≈ 28dp）+ Spacing.md。
    // 账号行是可选行（KDBX 库为 null），忽略它只会让让位略小、键盘略高一点点，
    // 不会把键盘推出屏幕 —— 而把它算进去会在有账号时把键盘顶得太低。
    val headerHeight = BADGE_SIZE + Spacing.md + 28.dp + Spacing.md
    // 键盘固有高度：圆点行 + 错误提示位 + 20dp 间隔 + 4 排按键（含排间距）+ 出口按钮。
    val keypadHeight = PIN_DOT_SIZE + Spacing.md + 20.dp +
        (PIN_KEY_SIZE + PIN_KEY_GAP) * PIN_DIGIT_ROWS.size +
        PIN_KEY_SIZE + PIN_KEY_GAP + Spacing.lg
    // 「换一个库」：Spacing.sm 间距 + 一个 TextButton 的最小高度（40dp）。
    val switchHeight = if (switchVisible) Spacing.sm + SWITCH_BUTTON_HEIGHT else 0.dp
    // ① 贴底所需让位（小屏的正确值）。
    val toBottom = screenHeight - headerHeight - keypadHeight - switchHeight - PIN_BOTTOM_MARGIN
    // ② 让位上限（大屏防止上半屏被撑成一片空白）。
    val capped = screenHeight * PIN_MAX_TOP_GAP_RATIO
    return minOf(toBottom, capped).coerceAtLeast(0.dp)
}

/**
 * PIN 模式让位量的上限比例：**不超过屏高的 1/6**。
 *
 * ## 怎么定出来的
 *
 * 先按"不设上限"复算了六档常见屏高（600/640/720/800/891/915），
 * 让位量分别是 20/60/140/220/311/335dp —— 也就是**屏越大、空白越大**，
 * 800dp 机型上头部与圆点之间要空出 220dp，正是用户截图里那块突兀的留白。
 *
 * 压到 1/6 后复算（键盘底部落在屏高比例）：
 *
 * | 屏高 | 让位 | 键盘底部 |
 * |---|---|---|
 * | 600 | 20dp | 88% |
 * | 640 | 60dp | 89% |
 * | 720 | 120dp | 87% |
 * | 800 | 133dp | 80% |
 * | 891 | 148dp | 73% |
 * | 915 | 152dp | 71% |
 *
 * 小屏（≤720）几乎不变（本来就贴底），大屏留白被收掉 100~180dp，
 * 键盘仍稳稳落在下 1/3 区 —— 拇指可达，上半屏也不再空旷。
 *
 * ⚠️ 这个比例只对**让位量**封顶，不改键盘与按键尺寸 —— 按键大小是 2026-09-14
 * 用户明确要求放大的（64dp → 76dp，理由是"太小太集中"），本轮不再动它。
 */
private const val PIN_MAX_TOP_GAP_RATIO = 1f / 6f

/** 「换一个库」文字按钮的高度（M3 `TextButton` 默认最小高 40dp）。 */
private val SWITCH_BUTTON_HEIGHT = 40.dp

/**
 * PIN 键盘底部保留的余地。
 *
 * 不让键盘**严丝合缝**贴到屏幕最下沿：那里是系统手势条 / 虚拟导航栏的地盘
 * （`imePadding()` 管的是 IME，管不到手势条），零余量时最下面一排按键与出口按钮
 * 会被手势区吃掉一部分触摸面积。24dp 是"够到但不压住"的平衡点。
 */
private val PIN_BOTTOM_MARGIN = Spacing.xl

/**
 * 单个按键直径。
 *
 * 2026-09-14 用户反馈「数字键盘好小好集中」⇒ 从 64dp 放大到 **76dp**，
 * 并把每行从 `Arrangement.Center` 改成 `SpaceEvenly`：
 * 三键 192dp 居中摆在 360dp 宽的屏幕上，两侧各留下 84dp 死白，
 * 读起来就是「挤在中间的一小坨」。空间均分后键盘撑满可用宽度、按起来也更容易。
 */
private val PIN_KEY_SIZE = 76.dp

/** 相邻两排按键的垂直间距（同理：不留缝会读成"一坨"）。 */
private val PIN_KEY_GAP = Spacing.sm

/** 数字键布局（3 行 9 键）；第 4 行「空 + 0 + 退格」在 [PinPad] 里单独拼。 */
private val PIN_DIGIT_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
)

/**
 * 应用内 PIN 输入面板（**自绘** 3×4 数字键盘）。
 *
 * **为什么自绘而不是用系统数字键盘**：PIN 的使用场景就是「想快点进去」——
 * 自绘键盘无需弹出 IME，省掉一次系统动画与焦点切换，也不会被输入法候选栏把页面顶起来。
 *
 * 满 [PIN_MIN_LENGTH] 位**自动提交**（见 `UnlockViewModel.onPinDigit`），因此这里
 * **没有「确认」键**：右下角是退格。少一个键 = 少一个要想「现在该按哪个」的时刻。
 *
 * 失败提示紧跟圆点，并且**保留「改用主密码」出口** —— 锁定或忘了 PIN 时，
 * 那条路是用户唯一的出路，不能藏在别处。
 */
@Composable
private fun PinPad(state: UnlockViewModel.UiState, viewModel: UnlockViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        PinDots(filled = state.pinLength, total = PIN_MIN_LENGTH)
        state.pinError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
            )
        }
        Spacer(Modifier.height(20.dp))
        // ⚠️ `SpaceEvenly` + `fillMaxWidth`：让键盘**撑满**可用宽度。
        // 用 `Center` 会把三键挤在正中间（用户实机反馈「太小、太集中」）。
        PIN_DIGIT_ROWS.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(vertical = PIN_KEY_GAP / 2),
            ) {
                row.forEach { digit ->
                    PinKey(
                        label = digit,
                        enabled = !state.pinSubmitting,
                        onClick = { viewModel.onPinDigit(digit.first()) },
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth().padding(vertical = PIN_KEY_GAP / 2),
        ) {
            // 左下留空：让 0 居中、退格落右，与系统拨号盘同款布局
            Spacer(Modifier.size(PIN_KEY_SIZE))
            PinKey(
                label = "0",
                enabled = !state.pinSubmitting,
                onClick = { viewModel.onPinDigit('0') },
            )
            IconButton(
                onClick = viewModel::onPinBackspace,
                enabled = !state.pinSubmitting,
                modifier = Modifier.size(PIN_KEY_SIZE),
            ) {
                Icon(
                    imageVector = Icons.Filled.Backspace,
                    contentDescription = stringResource(R.string.pin_unlock_backspace),
                )
            }
        }
        TextButton(onClick = viewModel::exitPinMode, enabled = !state.pinSubmitting) {
            Text(stringResource(R.string.pin_unlock_use_master))
        }
    }
}

/** 已输入位数指示：实心 = 已输，浅色 = 待输。⚠️ 只画点数，**不回显数字**。 */
@Composable
private fun PinDots(filled: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(PIN_DOT_SIZE)
                    .clip(CircleShape)
                    .background(
                        if (index < filled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PinKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(PIN_KEY_SIZE),
    ) {
        Text(text = label, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun AutoPromptQuickUnlock(
    localUnlockAvailable: Boolean,
    viewLocked: Boolean,
    hasTwoFactor: Boolean,
    submitting: Boolean,
    /** 系统侧终止的累计次数（见 [UnlockViewModel.UiState.autoPromptAborts]）。 */
    autoPromptAborts: Int,
    onPrompt: () -> Unit,
    onViewPrompt: () -> Unit,
) {
    val prompted = remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }
    var handledAborts by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // 条件刻意控制在 3 项以内（detekt ComplexCondition 上限为 3）。
    // 查看层锁：只要标记在就该弹（密钥已在内存，认证一次即可回来，不依赖快速解锁是否启用）。
    val eligible = (viewLocked || localUnlockAvailable) && !hasTwoFactor && !submitting
    LaunchedEffect(eligible, autoPromptAborts) {
        // 系统侧终止 ⇒ 归还这一次机会（次数封顶，硬件持续不可用时不至于无限重试）。
        if (autoPromptAborts > handledAborts) {
            handledAborts = autoPromptAborts
            if (attempts < MAX_AUTO_PROMPT_ATTEMPTS) prompted.value = false
        }
        if (prompted.value || !eligible) return@LaunchedEffect
        // ⚠️ 必须等宿主 RESUMED 之后再发起认证：进程冷启动（尤其设备重启后首次进入）时
        // BiometricPrompt 若在 Activity 尚未 RESUMED 时发起，会被系统立刻以
        // ERROR_CANCELED / ERROR_HW_UNAVAILABLE 结束 —— 这既是「重启后指纹解锁
        // 不生效」的触发点，也放大了 submitting 卡死的概率。
        lifecycleOwner.withResumed {
            if (prompted.value) return@withResumed
            prompted.value = true
            attempts++
            if (viewLocked) onViewPrompt() else onPrompt()
        }
    }
}

/** 自动弹出最多尝试几次（含系统侧终止后的重试）。超过就交给用户手动点指纹图标。 */
private const val MAX_AUTO_PROMPT_ATTEMPTS = 2
