package io.vaultix.vaultix.ui.unlock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultKind
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
 *
 * 两条**语义完全不同**的路径（`.ai/ISSUES.md` #60）：
 * | 来源 | 状态 | 页面内容 | 恢复成本 |
 * |---|---|---|---|
 * | 真锁（超时 / 冷启动 / 退出数据库）| [UiState.viewLocked] = false | 主密码（+2FA）+ 生物识别 | 联网重登 |
 * | 查看层锁（主页锁按钮）| [UiState.viewLocked] = true | **只有生物识别** | 一次认证 |
 *
 * ⚠️ 查看层锁分支**绝不能**渲染主密码 / 2FA 区块：那会让用户以为密钥被清了，
 * 而实际上密钥就在内存里 —— 用户原话「填充时解锁完还要再验证一次，逻辑太稀烂」。
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val sessionRepository: VaultSessionRepository,
) : ViewModel() {

    data class TwoFactorUi(
        val providers: List<Int>,
        val provider: Int,
    )

    data class UiState(
        val vault: VaultSummary? = null,
        /**
         * 库列表**已经到达**但解析不出可解锁的库（一个库都没有 / 该库已被移除）。
         *
         * 与 `vault == null` 的区别：`vault == null` 也可能是"首帧还没到"（此时要继续
         * loading）；本值为 true 表示"确认没有可解锁的库"，UI 必须立刻离开解锁页，
         * 否则就是永久转圈（2026-09-12 修复的启动死锁）。
         */
        val noVaultToUnlock: Boolean = false,
        val password: String = "",
        val passwordVisible: Boolean = false,
        val submitting: Boolean = false,
        val error: UnlockUiError? = null,
        val twoFactor: TwoFactorUi? = null,
        val localUnlockAvailable: Boolean = false,
        /**
         * 目标库处于**查看层锁**（密钥仍在内存）。
         *
         * true 时页面走「仅认证」分支：只弹生物识别，不渲染主密码 / 2FA，
         * 认证通过即 `clearViewLock` 回主界面。
         */
        val viewLocked: Boolean = false,
        /** 本次生物识别是为查看层锁发起的（成功分支据此只清标记、不重建会话）。 */
        val viewUnlockStarted: Boolean = false,
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

    /**
     * 构造期就定下查看锁：路由带 vaultId 时（查看层锁场景）必须**同步**读标记。
     *
     * 为什么不能只靠下面的流订阅：自动弹认证的判定（[UnlockScreen]）在首帧就生效，
     * 若 `viewLocked` 要等一帧才到，首帧会按「真锁」分支去弹本地快速解锁 ——
     * 没启用快速解锁的用户在那一步什么都点不出来。
     */
    private val _state = MutableStateFlow(
        UiState(
            viewLocked = vaultId.isNotBlank() && sessionRepository.isViewLocked(vaultId),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                // 无参数进入时（根导航直达）自动选中目标库。
                //
                // ⚠️ 顺序有意义：**先看查看层锁**。查看锁的库在会话层面是「已解锁」
                // （密钥在内存），`!it.unlocked` 在它身上为 false —— 若先按「第一个未解锁」
                // 选，用户按了主页锁按钮却会被要求解锁**另一个**库。
                if (vaultId.isBlank()) {
                    vaultId = vaults.firstOrNull { sessionRepository.isViewLocked(it.id) }?.id
                        ?: vaults.firstOrNull { !it.unlocked }?.id
                        .orEmpty()
                }
                val target = vaults.firstOrNull { v -> v.id == vaultId }
                _state.update {
                    it.copy(
                        vault = target,
                        viewLocked = target != null && sessionRepository.isViewLocked(target.id),
                        // 首帧未到（vaults 尚未发射）时不会走到这里，故此处可判定为"确认无库"
                        noVaultToUnlock = target == null,
                    )
                }
            }
        }
        // 查看层锁标记变化：**只更新、不新建** viewLocked（用户认证成功清掉标记时这里会归位）。
        viewModelScope.launch {
            sessionRepository.observeViewLockedVaultIds().collect { locked ->
                _state.update { it.copy(viewLocked = vaultId.isNotBlank() && vaultId in locked) }
            }
        }
        viewModelScope.launch {
            // vaultId 可能由上面那条流异步补上，故这里也随库列表变化重新订阅。
            vaultRepository.observeVaults()
                .map { vaults -> vaults.firstOrNull { it.id == vaultId }?.id.orEmpty() }
                .distinctUntilChanged()
                .collectLatest { id ->
                    if (id.isBlank()) return@collectLatest
                    // 目标库确定后再校正一次查看锁（标记流可能先于选库到达）。
                    _state.update { it.copy(viewLocked = sessionRepository.isViewLocked(id)) }
                    vaultRepository.localUnlockAvailable(id).collect { available ->
                        _state.update { it.copy(localUnlockAvailable = available) }
                    }
                }
        }
    }

    /** 用户点了「生物识别 / 设备 PIN 解锁」：准备解密 Cipher 并交给 UI 弹认证。 */
    fun startLocalUnlock() = startBiometricUnlock(viewLock = false)

    /**
     * 查看层锁的认证入口：**同一套生物识别，语义只是"证明是本人"**。
     *
     * 与 [startLocalUnlock] 的唯一区别是成功分支不同（见 [completeLocalUnlock]）：
     * 这里不清会话、不重建密钥，只把查看锁标记抹掉。
     */
    fun startViewUnlock() = startBiometricUnlock(viewLock = true)

    private fun startBiometricUnlock(viewLock: Boolean) {
        val current = _state.value
        if (current.submitting) return
        if (viewLock && !current.viewLocked) return
        if (!viewLock && current.localUnlockAvailable.not()) return
        _state.update { it.copy(submitting = true, viewUnlockStarted = viewLock, error = null) }
        viewModelScope.launch {
            val cipher = vaultRepository.prepareLocalUnlock(vaultId)
            if (cipher == null) {
                // 密钥包不可用（KEK 被指纹变更失效 / 从未启用）。
                // ⚠️ 查看锁分支要**摘掉查看锁**再落回主密码表单：否则页面会停在
                // 「只有生物识别按钮、但按钮必然失败」的死角（用户点不出任何出路）。
                _state.update {
                    it.copy(
                        submitting = false,
                        viewUnlockStarted = false,
                        viewLocked = if (viewLock) false else it.viewLocked,
                        error = UnlockUiError.Unknown("本地解锁不可用，请用主密码登录"),
                    )
                }
            } else {
                _events.send(Event.PromptForUnlock(cipher))
            }
        }
    }

    /**
     * BiometricPrompt 认证成功（携带本次 cipher）：解封本地密钥建立会话。
     *
     * @param forViewLock 本次认证是为查看层锁发起的 → 只清标记，**不重新解封密钥**
     *   （密钥本来就在会话里；再解封一次等于把同一把密钥写第二遍，白做一轮 KDF 派生）。
     */
    fun completeLocalUnlock(cipher: javax.crypto.Cipher, forViewLock: Boolean) {
        viewModelScope.launch {
            if (forViewLock) {
                sessionRepository.clearViewLock(vaultId)
                _state.update {
                    it.copy(submitting = false, viewUnlockStarted = false, error = null)
                }
                _events.send(Event.Unlocked)
                return@launch
            }
            val result = vaultRepository.completeLocalUnlock(vaultId, cipher)
            if (result == UnlockResult.Success) {
                _state.update {
                    it.copy(
                        submitting = false,
                        viewUnlockStarted = false,
                        password = "",
                        twoFactor = null,
                        error = null,
                    )
                }
                _events.send(Event.Unlocked)
            } else {
                val detail = (result as? UnlockResult.Unknown)?.detail
                _state.update {
                    it.copy(
                        submitting = false,
                        viewUnlockStarted = false,
                        error = UnlockUiError.Unknown(detail ?: "本地解锁失败，请用主密码登录"),
                    )
                }
            }
        }
    }

    /** 认证对话框被系统错误终止（非用户取消）时收起 busy 态。 */
    fun onBiometricPromptDismissed() {
        _state.update { it.copy(submitting = false, viewUnlockStarted = false) }
    }

    /**
     * BiometricPrompt 报错（用户取消 / 超时 / 硬件不可用 / 被系统撤销…）。
     *
     * **无论哪种错误都必须把 submitting 复位**——它是「认证中」的唯一标志，
     * 与指纹按钮、主密码按钮的 enabled 状态、转圈指示器都直接挂钩；
     * 漏复位就会把整页锁死（用户体感：指纹解锁按钮点了没反应）。
     *
     * 用户/系统取消保持安静（那是「改用主密码」的正常路径），其余错误给出原因，
     * 否则用户只会看到按钮毫无反应。
     */
    fun onBiometricPromptError(message: String, cancelled: Boolean) {
        _state.update {
            it.copy(
                submitting = false,
                viewUnlockStarted = false,
                error = if (cancelled) it.error else UnlockUiError.Unknown(message),
            )
        }
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
            // ★ KDBX 与 Bitwarden 的解锁是**两条完全不同的路**（M2 阶段 A）：
            // KDBX 认的是文件（离线、无账号、无 2FA），Bitwarden 认的是账号（联网 + 可能 2FA）。
            // 走错一条的后果不是「报错」而是「报错信息完全对不上」（如 KDBX 库被拿去联网 prelogin）。
            val target = _state.value.vault
            val result = if (target?.kind == VaultKind.KDBX) {
                vaultRepository.unlockKdbxVault(vaultId, current.password)
            } else {
                vaultRepository.unlockVault(vaultId, current.password)
            }
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
                // 成功解锁 = 密钥已在内存 → 查看锁标记失去意义（真锁 / 退出数据库后
                // 残留的标记若不清，根导航会立刻把用户弹回解锁页）。
                sessionRepository.clearViewLock(vaultId)
                _state.update {
                    it.copy(
                        submitting = false,
                        viewLocked = false,
                        viewUnlockStarted = false,
                        password = "",
                        twoFactor = null,
                        error = null,
                    )
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
