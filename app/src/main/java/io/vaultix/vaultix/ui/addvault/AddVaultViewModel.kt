package io.vaultix.vaultix.ui.addvault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.ui.common.TwoFactorProvider
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.toUnlockUiError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 添加 Bitwarden 库（Docs/08 S4 / 5.1）。
 *
 * 状态机：表单（服务器/邮箱/主密码）→ 登录；若账号开启 2FA（服务器返回
 * two_factor_required）则切入验证码步骤 [TwoFactorUi]（主密码保留在内存，
 * 完成或放弃时清除）→ 提交验证码完成登录 → 成功事件。
 */
@HiltViewModel
class AddVaultViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    /** 2FA 步骤状态（providers 由服务器下发的挑战决定）。 */
    data class TwoFactorUi(
        val providers: List<Int>,
        val provider: Int,
    )

    data class UiState(
        val server: String = DEFAULT_SERVER,
        val email: String = "",
        val password: String = "",
        val passwordVisible: Boolean = false,
        val submitting: Boolean = false,
        val error: UnlockUiError? = null,
        val twoFactor: TwoFactorUi? = null,
    )

    sealed interface Event {
        data object VaultAdded : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onServerChange(value: String) = _state.update { it.copy(server = value) }
    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(passwordVisible = visible) }

    fun selectTwoFactorProvider(provider: Int) = _state.update { state ->
        val tf = state.twoFactor ?: return@update state
        state.copy(twoFactor = tf.copy(provider = provider), error = null)
    }

    /** 放弃 2FA 回到表单（主密码保留，用户可重新提交）。 */
    fun backToForm() = _state.update {
        it.copy(twoFactor = null, error = null)
    }

    fun submit() {
        val current = _state.value
        val error = when {
            current.email.isBlank() || current.password.isBlank() -> UnlockUiError.FieldsMissing
            !current.server.startsWith("http://") && !current.server.startsWith("https://") ->
                UnlockUiError.InvalidServer
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        if (current.submitting) return // 防重复点击（KDF 耗时）

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = vaultRepository.addBitwardenVault(
                server = current.server,
                email = current.email,
                masterPassword = current.password,
            )
            handleSubmitResult(result, submitTwoFactor = false)
        }
    }

    /** 2FA 步骤：提交验证码完成登录。 */
    fun submitCode(code: String) {
        val current = _state.value
        val tf = current.twoFactor ?: return
        if (current.submitting || code.isBlank()) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = vaultRepository.addBitwardenVaultWithTwoFactor(
                server = current.server,
                email = current.email,
                masterPassword = current.password,
                provider = tf.provider,
                code = code.trim(),
            )
            handleSubmitResult(result, submitTwoFactor = true)
        }
    }

    private suspend fun handleSubmitResult(result: UnlockResult, submitTwoFactor: Boolean) {
        when (result) {
            UnlockResult.Success -> {
                // 密码已用完即弃（不留在 UiState 快照里）
                _state.update {
                    it.copy(submitting = false, password = "", twoFactor = null, error = null)
                }
                _events.send(Event.VaultAdded)
            }
            is UnlockResult.TwoFactorRequired -> {
                // 切到验证码步骤；主密码保留在内存直到完成/放弃
                _state.update {
                    it.copy(
                        submitting = false,
                        error = null,
                        twoFactor = TwoFactorUi(
                            providers = result.providers,
                            provider = TwoFactorProvider.defaultOf(result.providers),
                        ),
                    )
                }
            }
            UnlockResult.TwoFactorInvalid -> {
                // 停留在 2FA 步骤提示重输（仅 submitTwoFactor 路径会出现）
                _state.update { it.copy(submitting = false, error = UnlockUiError.TwoFactorInvalid) }
            }
            else -> {
                if (submitTwoFactor &&
                    (result == UnlockResult.Network || result is UnlockResult.Unknown)
                ) {
                    // 网络等瞬时错误：留在 2FA 步骤可重试，密码保留在内存
                    _state.update { it.copy(submitting = false, error = result.toUnlockUiError()) }
                } else {
                    // 凭据等错误：回到表单（密码不留存）
                    _state.update {
                        it.copy(
                            submitting = false,
                            password = "",
                            twoFactor = null,
                            error = result.toUnlockUiError(),
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_SERVER = "https://vault.bitwarden.com"
    }
}
