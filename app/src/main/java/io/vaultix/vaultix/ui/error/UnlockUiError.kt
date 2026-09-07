package io.vaultix.vaultix.ui.error

import io.vaultix.domain.UnlockResult

/**
 * 解锁 / 添加库失败的 UI 错误分类（由 domain [UnlockResult] 映射而来，
 * 与数据层解耦：domain 不懂 UI 文案，这里负责翻译）。
 *
 * 注意：[UnlockResult.TwoFactorRequired] 是**流程步骤**（转入验证码输入页），
 * 不是错误，不映射到这里，由 ViewModel 单独处理。
 */
sealed interface UnlockUiError {
    data object FieldsMissing : UnlockUiError
    data object InvalidServer : UnlockUiError
    data object InvalidCredentials : UnlockUiError
    data object AccountNotFound : UnlockUiError

    /** 验证码错误 / 已过期（发生在 2FA 步骤内）。 */
    data object TwoFactorInvalid : UnlockUiError
    data object Network : UnlockUiError
    data object KeyUnavailable : UnlockUiError
    data object VaultMissing : UnlockUiError
    data class Unknown(val detail: String?) : UnlockUiError
}

fun UnlockResult.toUnlockUiError(): UnlockUiError = when (this) {
    UnlockResult.Success -> error("Success 无需映射为 UI 错误；调用方应先处理 Success 分支")
    UnlockResult.InvalidCredentials -> UnlockUiError.InvalidCredentials
    UnlockResult.AccountNotFound -> UnlockUiError.AccountNotFound
    is UnlockResult.TwoFactorRequired ->
        error("TwoFactorRequired 是流程步骤，应由 ViewModel 转入验证码步骤而非错误提示")
    UnlockResult.TwoFactorInvalid -> UnlockUiError.TwoFactorInvalid
    UnlockResult.Network -> UnlockUiError.Network
    UnlockResult.KeyUnavailable -> UnlockUiError.KeyUnavailable
    UnlockResult.VaultMissing -> UnlockUiError.VaultMissing
    is UnlockResult.Unknown -> UnlockUiError.Unknown(detail)
}
