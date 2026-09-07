package io.vaultix.vaultix.ui.trash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - 恢复 / 永久删除均走 dirty 队列 + 轻量推送（离线本地先行，联网补推）。
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle[ARG_VAULT_ID])

    sealed interface UiEvent {
        data class Restored(val synced: Boolean) : UiEvent
        data class DeletedForever(val synced: Boolean) : UiEvent
        data class Failed(val message: String) : UiEvent
    }

    /** busy = 恢复/永久删除请求进行中（行按钮禁用防连点）。 */
    data class UiState(val busy: Boolean = false)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val trashItems: StateFlow<List<VaultItem>> = itemRepository.observeTrash(vaultId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

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
    }
}
