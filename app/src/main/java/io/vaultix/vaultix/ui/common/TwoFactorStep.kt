package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.unlockErrorText

/**
 * 两步验证步骤（Docs/08 S4 流程中的 2FA 一步；添加库与解锁共用）。
 *
 * - 服务器下发的 providers 多于一个时可切换验证方式；
 * - Email(1) 时提示服务端已自动发送验证码（Bastion 同款经典协议行为）；
 * - 验证码错误 / 网络错误由 [error] 统一展示（文案在资源中）。
 */
@Composable
fun TwoFactorStep(
    providers: List<Int>,
    selectedProvider: Int,
    submitting: Boolean,
    error: UnlockUiError?,
    onProviderSelected: (Int) -> Unit,
    onSubmit: (code: String) -> Unit,
    onBack: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var code by rememberSaveable { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.two_factor_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 仅在有多个可用验证方式时展示切换 chips（单方式直接进入）
        val selectable = providers.distinct().filter {
            it == TwoFactorProvider.AUTHENTICATOR || it == TwoFactorProvider.EMAIL
        }
        if (selectable.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row {
                selectable.forEach { provider ->
                    FilterChip(
                        selected = provider == selectedProvider,
                        onClick = { onProviderSelected(provider) },
                        label = { Text(stringResource(TwoFactorProvider.labelOf(provider))) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (selectedProvider == TwoFactorProvider.EMAIL) {
            Text(
                text = stringResource(R.string.two_factor_email_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() }.take(CODE_LENGTH) },
            label = { Text(stringResource(R.string.two_factor_code_label)) },
            placeholder = { Text(stringResource(R.string.two_factor_code_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                if (code.length == CODE_LENGTH) onSubmit(code)
            }),
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        )

        unlockErrorText(error)?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = {
                focusManager.clearFocus()
                onSubmit(code)
            },
            enabled = !submitting && code.length == CODE_LENGTH,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.two_factor_submit))
        }
        TextButton(onClick = onBack, enabled = !submitting) {
            Text(stringResource(R.string.two_factor_back))
        }
    }
}

/** TOTP / 邮箱验证码位数（Bitwarden 2FA 固定 6 位）。 */
private const val CODE_LENGTH = 6
