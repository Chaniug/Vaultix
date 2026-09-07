package io.vaultix.vaultix.ui.vaultlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
 * - quickUnlockSuggest：登录后引导横幅对象——「已解锁但未启用本地快速解锁」
 *   且用户未点过「以后再说」时出现（设备能力由 UI 层判定）
 * - [Event.PromptForEnroll]：UI 收到即弹 BiometricPrompt，认证成功回调
 *   [enrollWithCipher] 完成密钥包裹
 */
@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val preferences: VaultixPreferences,
) : ViewModel() {

    sealed interface Event {
        data class PromptForEnroll(val vaultId: String, val cipher: Cipher) : Event
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

    /** 用户点「启用」：准备包装 Cipher 并交给 UI 弹认证。 */
    fun startQuickUnlockEnroll(vaultId: String) {
        viewModelScope.launch {
            val cipher = vaultRepository.prepareLocalEnroll()
            if (cipher == null) {
                // 设备无可用认证方式：视同已提示，避免循环打扰
                preferences.setQuickUnlockPromptDismissed(true)
            } else {
                _events.emit(Event.PromptForEnroll(vaultId, cipher))
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
