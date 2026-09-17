package io.vaultix.vaultix.ui.error

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.vaultix.vaultix.R

/**
 * 把 [UnlockUiError] 翻译成用户可见文案（stringResource 集中映射）。
 *
 * @param forKdbx 该错误来自 **KDBX** 库（KeePass 文件）而不是 Bitwarden 账号。
 *
 *   ⚠️ 有这个参数是因为**同一种失败在两类库上的说法不同**：
 *   `InvalidCredentials` 对 Bitwarden 是"邮箱或主密码不正确"，
 *   但 KDBX **根本没有邮箱这个概念** —— 用户读到的是一句指向不存在字段的话，
 *   只能怀疑自己填错了什么。KDBX 的凭据只有"主密码（+ 可选 keyfile）"。
 *
 *   默认 false 是刻意的：所有既有调用点都是 Bitwarden / 未区分的场景，
 *   新加参数不该悄悄改变它们的行为。
 */
@Composable
fun unlockErrorText(error: UnlockUiError?, forKdbx: Boolean = false): String? {
    if (error == null) return null
    if (forKdbx && error == UnlockUiError.InvalidCredentials) {
        return stringResource(R.string.error_kdbx_password)
    }
    return when (error) {
        UnlockUiError.FieldsMissing -> stringResource(R.string.error_fields_missing)
        UnlockUiError.InvalidServer -> stringResource(R.string.error_invalid_server)
        UnlockUiError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
        UnlockUiError.AccountNotFound -> stringResource(R.string.error_account_not_found)
        UnlockUiError.TwoFactorInvalid -> stringResource(R.string.error_two_factor_invalid)
        UnlockUiError.Network -> stringResource(R.string.error_network)
        UnlockUiError.KeyUnavailable -> stringResource(R.string.error_key_unavailable)
        UnlockUiError.VaultMissing -> stringResource(R.string.error_vault_missing)
        is UnlockUiError.Unknown -> stringResource(R.string.error_unknown, error.detail.orEmpty())
        // 来源已经把它翻译成人话了 ⇒ 原样展示，不再套前缀（见该类型的 KDoc）。
        is UnlockUiError.Detail -> error.message
    }
}
