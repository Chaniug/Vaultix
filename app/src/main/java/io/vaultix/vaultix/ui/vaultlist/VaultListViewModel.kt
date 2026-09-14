package io.vaultix.vaultix.ui.vaultlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.repository.BitwardenSyncOrchestrator
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.KdbxEnrollOutcome
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * 库列表。
 *
 * - vaults：库列表（含锁定状态）
 * - syncStatuses：各库同步运行时状态（同步中 / 最近错误），供卡片行内轻提示
 * - quickUnlockSuggest：登录后引导横幅对象——「已解锁但未启用本地快速解锁」
 *   且用户未点过「以后再说」时出现（设备能力由 UI 层判定）
 * - [Event.PromptForEnroll]：UI 收到即弹 BiometricPrompt，认证成功回调
 *   [enrollWithCipher] 完成密钥包裹
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val preferences: VaultixPreferences,
    private val syncOrchestrator: BitwardenSyncOrchestrator,
) : ViewModel() {

    sealed interface Event {
        data class PromptForEnroll(val vaultId: String, val cipher: Cipher) : Event
        data object Removed : Event
        data class RemoveFailed(val message: String) : Event

        /** KDBX 横幅启用：请 UI 弹主密码输入框（定稿 §4.5，无法省略）。 */
        data class PromptForKdbxPassword(val vaultId: String) : Event

        /** KDBX 主密码校验未通过：UI 就地提示、**保留输入框**（宽松取向）。 */
        data class KdbxPasswordRejected(val detail: String?) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events

    /** 库列表；WhileSubscribed(5s)：切后台停止收集后保留最近值。 */
    val vaults: StateFlow<List<VaultSummary>> = vaultRepository.observeVaults()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /** 各库同步状态（编排器 per-vault；空 map = 从未同步过，不显示）。 */
    val syncStatuses: StateFlow<Map<String, VaultSyncStatus>> = syncOrchestrator.statusByVault
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyMap(),
        )

    /** 首个「已解锁但未启用快速解锁」的库（banner 对象；null = 不显示）。 */
    val quickUnlockSuggest: StateFlow<VaultSummary?> =
        combine(
            vaultRepository.observeVaults(),
            vaultRepository.observeUnlockedVaultIds(),
            preferences.isQuickUnlockPromptDismissed(),
        ) { vaults, unlocked, dismissed ->
            if (dismissed) null else vaults.firstOrNull { it.id in unlocked }
        }.flatMapLatest { vault ->
            if (vault == null) {
                flowOf(null)
            } else {
                vaultRepository.localUnlockAvailable(vault.id)
                    .map { available -> if (available) null else vault }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    /** 用户点「以后再说」：不再打扰（设置页仍可启用）。 */
    fun dismissQuickUnlockPrompt() {
        viewModelScope.launch { preferences.setQuickUnlockPromptDismissed(true) }
    }

    /**
     * 移除库：本地数据删除（repository 清会话/队列/行）→ 编排器清该库同步状态
     * （退避任务/状态流）。云端数据不受影响；失败发 [Event.RemoveFailed]。
     */
    fun removeVault(vaultId: String) {
        viewModelScope.launch {
            runCatching { vaultRepository.removeVault(vaultId) }
                .onSuccess {
                    syncOrchestrator.clearVault(vaultId)
                    _events.emit(Event.Removed)
                }
                .onFailure { error ->
                    _events.emit(Event.RemoveFailed(error.message ?: "未知错误"))
                }
        }
    }

    /** 用户点「启用」：准备包装 Cipher 并交给 UI 弹认证。 */
    fun startQuickUnlockEnroll(vaultId: String) {
        viewModelScope.launch {
            // ⚠️ 按库类型分流（定稿 §4.5）：KDBX 会话里没有可包裹的密钥，
            // 必须先向用户再要一次主密码 —— 走 Bitwarden 那条路会得到
            // 「认证成功但什么都没包上」的假成功（#93 的原始形态）。
            val kind = vaults.value.firstOrNull { it.id == vaultId }?.kind
            if (kind == VaultKind.KDBX) {
                _events.emit(Event.PromptForKdbxPassword(vaultId))
                return@launch
            }
            val cipher = vaultRepository.prepareLocalEnroll()
            if (cipher == null) {
                // 设备无可用认证方式：视同已提示，避免循环打扰
                preferences.setQuickUnlockPromptDismissed(true)
            } else {
                _events.emit(Event.PromptForEnroll(vaultId, cipher))
            }
        }
    }

    /**
     * KDBX 横幅启用：收到主密码 → **先校验后包裹** → 再请 UI 弹指纹。
     *
     * 与设置页同一条路径（[SettingsViewModel.confirmKdbxPassword]），
     * 保证「宽松重试」语义一致：输错只报错、不关框。
     */
    fun confirmKdbxPassword(vaultId: String, password: String) {
        viewModelScope.launch {
            val keyFileUri = runCatching { preferences.kdbxKeyFileUri(vaultId).first() }.getOrNull()
            val cipher = vaultRepository.prepareLocalEnroll()
            if (cipher == null) {
                _events.emit(Event.KdbxPasswordRejected(null))
                return@launch
            }
            val outcome = vaultRepository.enrollLocalUnlockKdbx(vaultId, password, keyFileUri, cipher)
            when (outcome) {
                is KdbxEnrollOutcome.Enrolled -> _events.emit(Event.PromptForEnroll(vaultId, cipher))
                is KdbxEnrollOutcome.InvalidCredentials ->
                    _events.emit(Event.KdbxPasswordRejected(null))
                is KdbxEnrollOutcome.SourceUnavailable ->
                    _events.emit(Event.KdbxPasswordRejected(outcome.detail))
                is KdbxEnrollOutcome.Failed ->
                    _events.emit(Event.KdbxPasswordRejected(outcome.detail))
            }
        }
    }

    /** BiometricPrompt 认证通过：包裹当前会话密钥并落盘。 */
    fun enrollWithCipher(vaultId: String, cipher: Cipher) {
        viewModelScope.launch {
            vaultRepository.enrollLocalUnlock(vaultId, cipher)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
