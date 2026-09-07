package io.vaultix.vaultix.ui.error

import io.vaultix.domain.UnlockResult

/**
 * 解锁 / 添加库失败的 UI 错误分类（由 domain [UnlockResult] 映射而来，
 * 与数据层解耦：domain 不懂 UI 文案，这里负责翻译）。
 */
sealed interface UnlockUiError {
    data object FieldsMissing : UnlockUiError
    data object InvalidServer : UnlockUiError
    data object InvalidCredentials : UnlockUiError
    data object TwoFactorRequired : UnlockUiError
    data object Network : UnlockUiError
    data object KeyUnavailable : UnlockUiError
    data object VaultMissing : UnlockUiError
    data class Unknown(val detail: String?) : UnlockUiError
}

fun UnlockResult.toUnlockUiError(): UnlockUiError = when (this) {
    UnlockResult.Success -> error("Success 无需映射为 UI 错误；调用方应先处理 Success 分支")
    UnlockResult.InvalidCredentials -> UnlockUiError.InvalidCredentials
    UnlockResult.TwoFactorRequired -> UnlockUiError.TwoFactorRequired
    UnlockResult.Network -> UnlockUiError.Network
    UnlockResult.KeyUnavailable -> UnlockUiError.KeyUnavailable
    UnlockResult.VaultMissing -> UnlockUiError.VaultMissing
    is UnlockResult.Unknown -> UnlockUiError.Unknown(detail)
}
