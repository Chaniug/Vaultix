package io.vaultix.vaultix.ui.unlock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultSummary
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
 * 解锁页（Docs/08 S6 最小版）：输入主密码 → 联网认证 → 解包密钥进会话。
 *
 * 库信息（服务器 / 邮箱标签）从 [VaultRepository.observeVaults] 里按 vaultId 取，
 * 不额外开数据通道。
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    data class UiState(
        val vault: VaultSummary? = null,
        val password: String = "",
        val passwordVisible: Boolean = false,
        val submitting: Boolean = false,
        val error: UnlockUiError? = null,
    )

    sealed interface Event {
        data object Unlocked : Event
    }

    val vaultId: String = checkNotNull(savedStateHandle[ARG_VAULT_ID])

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                _state.update { it.copy(vault = vaults.firstOrNull { v -> v.id == vaultId }) }
            }
        }
    }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(passwordVisible = visible) }

    fun submit() {
        val current = _state.value
        if (current.password.isBlank()) {
            _state.update { it.copy(error = UnlockUiError.FieldsMissing) }
            return
        }
        if (current.submitting) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = vaultRepository.unlockVault(vaultId, current.password)
            when (result) {
                UnlockResult.Success -> {
                    _state.update { it.copy(submitting = false, password = "") }
                    _events.send(Event.Unlocked)
                }
                else -> _state.update {
                    it.copy(submitting = false, password = "", error = result.toUnlockUiError())
                }
            }
        }
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"
    }
}
