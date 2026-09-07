package io.vaultix.vaultix.ui.error

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.vaultix.vaultix.R

/** 把 [UnlockUiError] 翻译成用户可见文案（stringResource 集中映射）。 */
@Composable
fun unlockErrorText(error: UnlockUiError?): String? {
    if (error == null) return null
    return when (error) {
        UnlockUiError.FieldsMissing -> stringResource(R.string.error_fields_missing)
        UnlockUiError.InvalidServer -> stringResource(R.string.error_invalid_server)
        UnlockUiError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
        UnlockUiError.AccountNotFound -> stringResource(R.string.error_account_not_found)
        UnlockUiError.TwoFactorInvalid -> stringResource(R.string.error_two_factor_invalid)
        UnlockUiError.Network -> stringResource(R.string.error_network)
        UnlockUiError.KeyUnavailable -> stringResource(R.string.error_key_unavailable)
        UnlockUiError.VaultMissing -> stringResource(R.string.error_vault_missing)
        is UnlockUiError.Unknown -> stringResource(R.string.error_unknown, error.detail ?: "")
    }
}
