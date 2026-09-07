package io.vaultix.vaultix.ui.addvault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
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
 * 添加 Bitwarden 库（Docs/08 S4 / 5.1 最小版）。
 *
 * 状态机：输入校验 → 登录（KDF 派生耗时，按钮防重入）→ 成功发一次性事件。
 * 2FA / 凭据错误 / 网络错误映射成可执行的错误文案（见 [AddVaultError]）。
 */
@HiltViewModel
class AddVaultViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    data class UiState(
        val server: String = DEFAULT_SERVER,
        val email: String = "",
        val password: String = "",
        val passwordVisible: Boolean = false,
        val submitting: Boolean = false,
        val error: UnlockUiError? = null,
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
            when (result) {
                UnlockResult.Success -> {
                    // 密码已用完即弃（不留在 UiState 快照里）
                    _state.update { it.copy(submitting = false, password = "") }
                    _events.send(Event.VaultAdded)
                }
                else -> _state.update {
                    it.copy(submitting = false, password = "", error = result.toUnlockUiError())
                }
            }
        }
    }

    companion object {
        const val DEFAULT_SERVER = "https://vault.bitwarden.com"
    }
}
