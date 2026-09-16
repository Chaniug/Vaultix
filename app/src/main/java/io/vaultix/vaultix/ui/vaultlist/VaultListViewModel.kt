package io.vaultix.vaultix.ui.vaultlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.repository.BitwardenSyncOrchestrator
import io.vaultix.data.repository.LocalUnlockEnrollment
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.ui.settings.QuickUnlockController
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
    /**
     * 快速解锁（能力级）备料器。
     *
     * ⚠️ 库列表横幅与设置页**共用同一个控制器**（[QuickUnlockController]）。
     * 2026-09-16 之前这里是**第三份**单库实现（与设置页那份互为复制品），
     * 注释还自称"与设置页同一条路径" —— 实际是两套代码，只可能修好一边。
     */
    private val localUnlockEnrollment: LocalUnlockEnrollment,
) : ViewModel() {

    sealed interface Event {
        data object Removed : Event
        data class RemoveFailed(val message: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events

    /**
     * 「快速解锁」登记控制器（**与设置页共用同一实现**）。
     *
     * 库列表页只用到它的一件事：横幅的「启用」——把该库纳入生效范围并立刻配指纹。
     * 其余（范围管理 / PIN / 关闭）都在「设置 → 密码库管理 → 快速解锁」里。
     */
    val quickUnlock: QuickUnlockController by lazy {
        QuickUnlockController(
            vaultRepository = vaultRepository,
            enrollment = localUnlockEnrollment,
            preferences = preferences,
            scope = viewModelScope,
        )
    }

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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
