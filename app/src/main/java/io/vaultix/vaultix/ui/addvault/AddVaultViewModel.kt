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

    /**
     * Bitwarden 服务器区域。
     *
     * 引入它的原因（2026-09-14 用户反馈）：「服务器地址居然是预先填好的，我还要一步一步
     * 删除，而且没有官方地址的内容，比如美国服务器、欧洲服务器的地址」。
     *
     * ⇒ 直接把官方**区域**摆成选项，选官方区域时地址由我们给出（且只读展示），
     * 用户不必删也不必改；只有自托管才需要自己输入。
     *
     * @param url 官方地址；null = 自托管（地址由用户输入）。
     */
    enum class ServerRegion(val url: String?) {
        /** Bitwarden 官方美国区（官方文档：官方 web app 地址之一）。 */
        US("https://vault.bitwarden.com"),

        /** Bitwarden 官方欧盟区（2023 年起与美国区**是彼此独立的两套环境**，账号不通用）。 */
        EU("https://vault.bitwarden.eu"),

        /** 自托管（Vaultwarden / 官方自建）。 */
        SELF_HOSTED(null),
    }

    data class UiState(
        /**
         * 服务器地址。
         *
         * ⚠️ 由 [region] 驱动，**不是**一个让用户去删的预填值：
         * 选 US / EU 时它就是官方地址（界面只读展示），选 [ServerRegion.SELF_HOSTED]
         * 时被**清空**（否则上一个区域的地址会留在框里，用户又得先删掉它）。
         */
        val server: String = ServerRegion.US.url.orEmpty(),
        val region: ServerRegion = ServerRegion.US,
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

    /**
     * 切换服务器区域。
     *
     * ⚠️ 切到自托管时**必须清空**地址：留着上一个区域的官方 URL 会让用户以为
     * 「自托管也是填好的」，接着他会去删它 —— 那正是用户抱怨的那个多余动作。
     */
    fun selectRegion(region: ServerRegion) = _state.update {
        it.copy(region = region, server = region.url.orEmpty(), error = null)
    }

    fun onServerChange(value: String) = _state.update { it.copy(server = value, error = null) }
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

    // 注：原 `DEFAULT_SERVER` 常量已删除 —— 官方地址现在**只**存在于
    // [ServerRegion.US].url / [ServerRegion.EU].url，避免「两个地方各有一份地址」
    // 然后在某次改动里漂移（用户看到的区域名与真正连的服务器不一致）。
}
