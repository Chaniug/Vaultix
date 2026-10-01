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
    return when (error) {
        UnlockUiError.FieldsMissing -> stringResource(R.string.error_fields_missing)
        UnlockUiError.InvalidServer -> stringResource(R.string.error_invalid_server)
        // ★ KDBX 的 `InvalidCredentials` 单独一句话（见本函数 KDoc 的 forKdbx 说明）。
        //   ⚠️ 写在这个分支**里面**，不要写成 `when` 之外的提前 return ——
        //   那样会多出一个分支，把圈复杂度顶到 15（上限 14，2026-10-01 实测）。
        UnlockUiError.InvalidCredentials -> stringResource(
            if (forKdbx) R.string.error_kdbx_password else R.string.error_invalid_credentials,
        )
        UnlockUiError.AccountNotFound -> stringResource(R.string.error_account_not_found)
        UnlockUiError.TwoFactorInvalid -> stringResource(R.string.error_two_factor_invalid)
        UnlockUiError.Network -> stringResource(R.string.error_network)
        UnlockUiError.KeyUnavailable -> stringResource(R.string.error_key_unavailable)
        UnlockUiError.VaultMissing -> stringResource(R.string.error_vault_missing)
        is UnlockUiError.Unknown -> stringResource(R.string.error_unknown, error.detail.orEmpty())
        // 来源已经把它翻译成人话了 ⇒ 原样展示，不再套前缀（见该类型的 KDoc）。
        is UnlockUiError.Detail -> error.message
        // 本层自己写的表单校验文案：走资源（见 UnlockUiError.Validation 的 KDoc）。
        is UnlockUiError.Validation -> stringResource(validationTextRes(error))
    }
}

/**
 * [UnlockUiError.Validation] → 资源 id。
 *
 * ⚠️ **必须**抽成独立函数：这五个分支留在 [unlockErrorText] 里会把它的
 * 圈复杂度顶到 20（detekt `CyclomaticComplexMethod` 上限 14，2026-10-01 实测报红）。
 * ⇒ 加新的校验类型时**加在这个 `when` 里**，不要往上层堆。
 *
 * 返回资源 id（而不是直接 `stringResource`）是刻意的：这样本函数是**纯函数**，
 * 能在没有 Compose 环境的单测里直接断言"哪个错误对应哪条文案"，
 * 而不必起 Android 测试环境。
 */
fun validationTextRes(error: UnlockUiError.Validation): Int = when (error) {
    UnlockUiError.Validation.VaultNameEmpty -> R.string.add_kdbx_create_name_empty
    UnlockUiError.Validation.PasswordEmpty -> R.string.add_kdbx_create_password_empty
    UnlockUiError.Validation.PasswordMismatch -> R.string.add_kdbx_create_password_mismatch
    UnlockUiError.Validation.FileNotPicked -> R.string.add_kdbx_pick_file_missing
    UnlockUiError.Validation.SaveLocationUnavailable -> R.string.add_kdbx_create_no_persist
}
