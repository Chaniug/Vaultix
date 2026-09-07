package io.vaultix.vaultix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.security.AutoLockController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页（最小版）。偏好值全部来自 [VaultixPreferences]（DataStore，非敏感），
 * 保存即实时生效（主题 / 防截屏由 MainActivity 收集，锁定由 AutoLockController
 * 收集，剪贴板清除由详情页收集）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: VaultixPreferences,
    private val vaultRepository: VaultRepository,
    private val autoLockController: AutoLockController,
) : ViewModel() {

    data class UiState(
        val autoLockMinutes: Int = 5,
        val clipboardClearMs: Long = 30_000L,
        val dynamicColor: Boolean = true,
        val screenSecurity: Boolean = true,
    )

    /** 本地快速解锁管理列表（每库：是否已启用）。 */
    data class QuickUnlockVaultUi(
        val vaultId: String,
        val name: String,
        val enabled: Boolean,
    )

    val state: StateFlow<UiState> = combine(
        preferences.autoLockMinutes,
        preferences.clipboardClearMs,
        preferences.dynamicColor,
        preferences.screenSecurity,
    ) { minutes, clearMs, dynamic, secure ->
        UiState(
            autoLockMinutes = minutes,
            clipboardClearMs = clearMs,
            dynamicColor = dynamic,
            screenSecurity = secure,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(),
    )

    val quickUnlockVaults: StateFlow<List<QuickUnlockVaultUi>> =
        vaultRepository.observeVaults()
            .flatMapLatest { vaults ->
                combine(
                    vaults.map { vault ->
                        vaultRepository.localUnlockAvailable(vault.id).map { enabled ->
                            QuickUnlockVaultUi(
                                vaultId = vault.id,
                                name = vault.name,
                                enabled = enabled,
                            )
                        }
                    },
                ) { items -> items.toList() }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    fun setAutoLockMinutes(minutes: Int) {
        viewModelScope.launch { preferences.setAutoLockMinutes(minutes) }
    }

    fun setClipboardClearMs(ms: Long) {
        viewModelScope.launch { preferences.setClipboardClearMs(ms) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferences.setDynamicColor(enabled) }
    }

    fun setScreenSecurity(enabled: Boolean) {
        viewModelScope.launch { preferences.setScreenSecurity(enabled) }
    }

    /** 关闭某库的本地快速解锁（删除包裹密钥与开关）。 */
    fun disableQuickUnlock(vaultId: String) {
        viewModelScope.launch { vaultRepository.disableLocalUnlock(vaultId) }
    }

    /** 立即锁定全部库：AutoLockController 会自增锁定代次，导航壳自动回库列表。 */
    fun lockAllNow() = autoLockController.lockAllNow()

    companion object {
        /** 剪贴板清除候选（ms）：0 = 关闭。默认 30s 与详情页一致。 */
        val CLIPBOARD_PRESETS_MS: List<Long> = listOf(0L, 10_000L, 30_000L, 60_000L, 300_000L)
    }
}
