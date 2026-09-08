package io.vaultix.vaultix.ui.trash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.common.TrashCleanupPolicy
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.datastore.VaultixPreferencesDefaults
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 回收站（Docs/08 S19）：已软删除条目的查看 / 恢复 / 永久删除。
 *
 * - 数据源 = 编排器拉取 / 本地软删除落库的 deletedDate 非空行；服务端回收站
 *   保留 30 天，被服务端永久清除的行由下次成功全量同步收敛；
 * - 恢复 / 永久删除均走 dirty 队列 + 轻量推送（离线本地先行，联网补推）；
 * - 自动清理（批次③，对齐 Bastion TrashViewModel）：进入回收站即按
 *   [VaultixPreferences.trashAutoDeleteDays] 档位清理到期条目（走
 *   [ItemRepository.cleanupExpiredTrash]，DELETE 入队保证服务端同步删除），
 *   行内显示剩余天数倒计时；0 = 不自动清空。
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemRepository: ItemRepository,
    private val preferences: VaultixPreferences,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle[ARG_VAULT_ID])

    sealed interface UiEvent {
        data class Restored(val synced: Boolean) : UiEvent
        data class DeletedForever(val synced: Boolean) : UiEvent

        /** 进入回收站时的自动清理结果（仅实际删除时发送）。 */
        data class AutoCleaned(val count: Int) : UiEvent
        data class Failed(val message: String) : UiEvent
    }

    /** busy = 恢复/永久删除请求进行中（行按钮禁用防连点）。 */
    data class UiState(val busy: Boolean = false)

    /**
     * 行 UI 模型：[remainingDays] 为 null 表示未启用自动清理
     * （档位 0 或删除时间不可解析），行内不显示倒计时。
     */
    data class TrashRowUi(
        val item: VaultItem,
        val remainingDays: Int?,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 自动清理档位（天；0 = 不自动清空），设置对话框读写。 */
    val autoDeleteDays: StateFlow<Int> = preferences.trashAutoDeleteDays
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DEFAULT_AUTO_DELETE_DAYS,
        )

    val trashRows: StateFlow<List<TrashRowUi>> = combine(
        itemRepository.observeTrash(vaultId),
        preferences.trashAutoDeleteDays,
    ) { entries, autoDeleteDays ->
        val now = System.currentTimeMillis()
        entries.map { entry ->
            TrashRowUi(
                item = entry.item,
                remainingDays = TrashCleanupPolicy.remainingDays(entry.deletedDate, now, autoDeleteDays),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList(),
    )

    init {
        // 进入回收站即执行到期清理（Bastion cleanupExpiredItemsNow 同位）；
        // 清理失败静默（cleanupExpiredTrash 内部已兜底），不打断页面可用性
        viewModelScope.launch {
            val days = preferences.trashAutoDeleteDays.first()
            if (days <= 0) return@launch
            val removed = itemRepository.cleanupExpiredTrash(vaultId, days)
            if (removed > 0) _events.trySend(UiEvent.AutoCleaned(removed))
        }
    }

    fun restore(itemId: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = itemRepository.restoreItem(vaultId, itemId)
            _state.update { it.copy(busy = false) }
            sendOutcome(result) { synced -> UiEvent.Restored(synced) }
        }
    }

    fun deleteForever(itemId: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = itemRepository.permanentDeleteItem(vaultId, itemId)
            _state.update { it.copy(busy = false) }
            sendOutcome(result) { synced -> UiEvent.DeletedForever(synced) }
        }
    }

    /** 更新自动清理档位（天；0 = 不自动清空），保存即生效。 */
    fun setAutoDeleteDays(days: Int) {
        viewModelScope.launch { preferences.setTrashAutoDeleteDays(days) }
    }

    private fun sendOutcome(
        result: Result<VaultSaveOutcome>,
        toEvent: (synced: Boolean) -> UiEvent,
    ) {
        _events.trySend(
            result.fold(
                onSuccess = { outcome -> toEvent(outcome == VaultSaveOutcome.Synced) },
                onFailure = { error -> UiEvent.Failed(error.message ?: "未知错误") },
            ),
        )
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"

        /** 单一真值源在 core:datastore（与偏好层默认一致）。 */
        const val DEFAULT_AUTO_DELETE_DAYS = VaultixPreferencesDefaults.TRASH_AUTO_DELETE_DAYS
    }
}
