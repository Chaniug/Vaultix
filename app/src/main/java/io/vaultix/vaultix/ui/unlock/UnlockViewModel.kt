package io.vaultix.vaultix.ui.unlock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.ui.common.TwoFactorProvider
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.toUnlockUiError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 解锁页（Docs/08 S6）：主密码 →（若 2FA）验证码步骤 → 解锁。
 *
 * 2FA 期间主密码保留在内存（完成/放弃即清）；库信息从
 * [VaultRepository.observeVaults] 按 vaultId 取。
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    data class TwoFactorUi(
        val providers: List<Int>,
        val provider: Int,
    )

    data class UiState(
        val vault: VaultSummary? = null,
        val password: String = "",
        val passwordVisible: Boolean = false,
        val submitting: Boolean = false,
        val error: UnlockUiError? = null,
        val twoFactor: TwoFactorUi? = null,
        val localUnlockAvailable: Boolean = false,
    )

    sealed interface Event {
        data object Unlocked : Event

        /** UI 收到后立即弹 BiometricPrompt（cipher 已 init，等待用户认证）。 */
        data class PromptForUnlock(val cipher: javax.crypto.Cipher) : Event
    }

    /**
     * 要解锁的库 id。
     *
     * 两种来源（2026-09-11 起）：
     *  1. [UnlockRoute] 带参数进入（从库列表点某个锁定的库）→ 直接用该 id；
     *  2. [UnlockEntryRoute] 无参数进入（**根导航在锁定态直达**，对齐 Bitwarden
     *     `VaultUnlockRoute.Standard`）→ 自动选中第一个已锁定的库。
     *
     * 用 `var` 是因为第 2 种情况下库列表是异步到达的；在解析出之前保持空串，
     * 各入口方法会先判空。
     */
    var vaultId: String = savedStateHandle.get<String>(ARG_VAULT_ID).orEmpty()
        private set

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                // 无参数进入时（根导航直达）自动选中第一个已锁定的库。
                if (vaultId.isBlank()) {
                    vaults.firstOrNull { !it.unlocked }?.let { vaultId = it.id }
                }
                _state.update { it.copy(vault = vaults.firstOrNull { v -> v.id == vaultId }) }
            }
        }
        viewModelScope.launch {
            // vaultId 可能由上面那条流异步补上，故这里也随库列表变化重新订阅。
            vaultRepository.observeVaults()
                .map { vaults -> vaults.firstOrNull { it.id == vaultId }?.id.orEmpty() }
                .distinctUntilChanged()
                .collectLatest { id ->
                    if (id.isBlank()) return@collectLatest
                    vaultRepository.localUnlockAvailable(id).collect { available ->
                        _state.update { it.copy(localUnlockAvailable = available) }
                    }
                }
        }
    }

    /** 用户点了「生物识别 / 设备 PIN 解锁」：准备解密 Cipher 并交给 UI 弹认证。 */
    fun startLocalUnlock() {
        if (_state.value.submitting || _state.value.localUnlockAvailable.not()) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val cipher = vaultRepository.prepareLocalUnlock(vaultId)
            if (cipher == null) {
                _state.update {
                    it.copy(
                        submitting = false,
                        error = UnlockUiError.Unknown("本地解锁不可用，请用主密码登录"),
                    )
                }
            } else {
                _events.send(Event.PromptForUnlock(cipher))
            }
        }
    }

    /** BiometricPrompt 认证成功（携带本次 cipher）：解封本地密钥建立会话。 */
    fun completeLocalUnlock(cipher: javax.crypto.Cipher) {
        viewModelScope.launch {
            val result = vaultRepository.completeLocalUnlock(vaultId, cipher)
            if (result == UnlockResult.Success) {
                _state.update {
                    it.copy(submitting = false, password = "", twoFactor = null, error = null)
                }
                _events.send(Event.Unlocked)
            } else {
                val detail = (result as? UnlockResult.Unknown)?.detail
                _state.update {
                    it.copy(
                        submitting = false,
                        error = UnlockUiError.Unknown(detail ?: "本地解锁失败，请用主密码登录"),
                    )
                }
            }
        }
    }

    /** 认证对话框被系统错误终止（非用户取消）时收起 busy 态。 */
    fun onBiometricPromptDismissed() {
        _state.update { it.copy(submitting = false) }
    }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(passwordVisible = visible) }

    fun selectTwoFactorProvider(provider: Int) = _state.update { state ->
        val tf = state.twoFactor ?: return@update state
        state.copy(twoFactor = tf.copy(provider = provider), error = null)
    }

    /** 放弃 2FA 回到密码步骤（密码保留）。 */
    fun backToPassword() = _state.update { it.copy(twoFactor = null, error = null) }

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
            handleSubmitResult(result, submitTwoFactor = false)
        }
    }

    /** 2FA 步骤：提交验证码完成解锁。 */
    fun submitCode(code: String) {
        val current = _state.value
        val tf = current.twoFactor ?: return
        if (current.submitting || code.isBlank()) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = vaultRepository.unlockVaultWithTwoFactor(
                vaultId = vaultId,
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
                _state.update {
                    it.copy(submitting = false, password = "", twoFactor = null, error = null)
                }
                _events.send(Event.Unlocked)
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
                _state.update { it.copy(submitting = false, error = UnlockUiError.TwoFactorInvalid) }
            }
            else -> {
                if (submitTwoFactor &&
                    (result == UnlockResult.Network || result is UnlockResult.Unknown)
                ) {
                    // 网络等瞬时错误：留在 2FA 步骤可重试
                    _state.update { it.copy(submitting = false, error = result.toUnlockUiError()) }
                } else {
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
        const val ARG_VAULT_ID = "vaultId"
    }
}
