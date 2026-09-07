package io.vaultix.vaultix.ui.addvault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.vaultix.vaultix.ui.common.TwoFactorStep
import io.vaultix.vaultix.ui.error.unlockErrorText

/**
 * 连接 Bitwarden 表单（Docs/08 S4 / 流程 5.1）+ 2FA 步骤。
 *
 * 服务器地址默认官方实例（可改自托管）；账号开启两步验证时自动切入
 * [TwoFactorStep]（验证码）完成登录；成功发一次性事件返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVaultScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit,
    viewModel: AddVaultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AddVaultViewModel.Event.VaultAdded -> onAdded()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_vault_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        AddVaultScreenContent(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.padding(padding),
        )
    }
}

/** 内容区：无 2FA 挑战时显示表单，否则显示验证码步骤。 */
@Composable
private fun AddVaultScreenContent(
    state: AddVaultViewModel.UiState,
    viewModel: AddVaultViewModel,
    modifier: Modifier,
) {
    val twoFactor = state.twoFactor
    if (twoFactor != null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.two_factor_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            TwoFactorStep(
                providers = twoFactor.providers,
                selectedProvider = twoFactor.provider,
                submitting = state.submitting,
                error = state.error,
                onProviderSelected = viewModel::selectTwoFactorProvider,
                onSubmit = viewModel::submitCode,
                onBack = viewModel::backToForm,
            )
        }
        return
    }
    VaultConnectForm(state = state, viewModel = viewModel, modifier = modifier)
}

/** 服务器 / 邮箱 / 主密码表单。 */
@Composable
private fun VaultConnectForm(
    state: AddVaultViewModel.UiState,
    viewModel: AddVaultViewModel,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.add_vault_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.server,
            onValueChange = viewModel::onServerChange,
            label = { Text(stringResource(R.string.add_vault_server)) },
            placeholder = { Text(stringResource(R.string.add_vault_server_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(stringResource(R.string.add_vault_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(stringResource(R.string.add_vault_password)) },
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

        unlockErrorText(state.error)?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (state.submitting) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.add_vault_working),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
        FilledTonalButton(
            onClick = {
                focusManager.clearFocus()
                viewModel.submit()
            },
            enabled = !state.submitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.add_vault_submit))
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
