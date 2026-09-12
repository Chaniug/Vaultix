package io.vaultix.vaultix.ui.unlock

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.common.TwoFactorStep
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.error.unlockErrorText
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.foundation.layout.width

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
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val activity = rememberFragmentActivity()
    // 认证对话框文案（LaunchedEffect 内不可直接 stringResource，先取好）
    val biometricTitle = stringResource(R.string.quick_unlock_biometric_title)
    val biometricSubtitle = stringResource(R.string.quick_unlock_biometric_subtitle)
    val cancelText = stringResource(R.string.action_cancel)

    // BiometricPrompt 事件：收到 cipher 即弹认证，成功回调回传 VM 完成解封
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                UnlockViewModel.Event.Unlocked -> onUnlocked()
                is UnlockViewModel.Event.PromptForUnlock -> {
                    val host = activity ?: run {
                        viewModel.onBiometricPromptDismissed()
                        return@collect
                    }
                    BiometricPrompter(host).authenticate(
                        cipher = event.cipher,
                        title = biometricTitle,
                        subtitle = biometricSubtitle,
                        cancelText = cancelText,
                        onSuccess = viewModel::completeLocalUnlock,
                        onError = { _, cancelled ->
                            if (cancelled) viewModel.onBiometricPromptDismissed()
                        },
                    )
                }
            }
        }
    }

    // 进入解锁页后**自动弹一次**本地快速解锁（生物识别 / 指纹）：只要该库已启用快速解锁，
    // 就不必再让用户找按钮点一下（对齐 Bitwarden 「解锁界面直接弹生物识别」的体验）。
    // 只自动触发一次——用户取消后不再反复弹（尊重「改用主密码」的意图），仍可手动点按钮再触发。
    // autoPrompted 走 rememberSaveable：配置变更 / 重组都不会重弹。
    val autoPrompted = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.localUnlockAvailable, state.twoFactor, state.submitting) {
        if (!autoPrompted.value &&
            state.localUnlockAvailable &&
            state.twoFactor == null &&
            !state.submitting
        ) {
            autoPrompted.value = true
            viewModel.startLocalUnlock()
        }
    }

    Scaffold { padding ->
        val vault = state.vault
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (vault == null) {
                if (state.noVaultToUnlock) {
                    // 已确认无库可解锁：立刻离开（不要再转圈）
                    LaunchedEffect(Unit) { onNoVault() }
                } else {
                    // 首帧未到：短暂 loading（库列表一到就有结论）
                    CircularProgressIndicator()
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
            Text(
                text = vault.name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .size(64.dp)
                    .padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(text = vault.name, style = MaterialTheme.typography.titleLarge)
            vault.account?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            PasswordForm(
                state = state,
                viewModel = viewModel,
                focusManager = focusManager,
                quickUnlockVisible = state.localUnlockAvailable && state.twoFactor == null,
                quickUnlockEnabled = !state.submitting,
                onQuickUnlock = viewModel::startLocalUnlock,
            )
        }
    }
}

/** 本地快速解锁入口（生物识别 / 设备 PIN）+ 主密码 fallback 提示。 */
@Composable
private fun QuickUnlockEntry(
    visible: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
) {
    if (!visible) {
        Spacer(Modifier.height(24.dp))
        return
    }
    FilledTonalButton(
        onClick = onStart,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Fingerprint, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.quick_unlock_biometric_button))
    }
    Text(
        text = stringResource(R.string.unlock_password_label_fallback),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
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

    unlockErrorText(state.error)?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (state.submitting) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.add_vault_working),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    Spacer(Modifier.height(24.dp))
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
