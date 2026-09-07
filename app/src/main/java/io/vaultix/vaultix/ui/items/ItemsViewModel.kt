package io.vaultix.vaultix.ui.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.domain.VaultSyncReport
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 条目列表（S7 最小版）。
 *
 * - 进入页面后若库已解锁，先静默同步一次（推送 dirty + revision 预检，无变化即跳过）；
 * - 同步状态作为页内提示条展示（不打断操作）；
 * - 新建走 [ItemRepository.createItem]：本地密文行 + dirty 队列 + 轻量推送。
 */
@HiltViewModel
class ItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle[ARG_VAULT_ID])

    /** 同步提示条内容（一次性展示语义，由 UI 消费后调用 [consumeSyncNote] 清除）。 */
    sealed interface SyncNote {
        data class Success(val cipherCount: Int) : SyncNote
        data object Skipped : SyncNote
        data object InProgress : SyncNote
        data class Warning(val message: String) : SyncNote
    }

    sealed interface SaveEvent {
        data object SavedSynced : SaveEvent
        data object SavedQueued : SaveEvent
        data class Failed(val message: String) : SaveEvent
    }

    data class UiState(
        val vault: VaultSummary? = null,
        val items: List<VaultItem> = emptyList(),
        val syncNote: SyncNote? = null,
        val saving: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _saveEvents = Channel<SaveEvent>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                _state.update { it.copy(vault = vaults.firstOrNull { v -> v.id == vaultId }) }
            }
        }
        viewModelScope.launch {
            itemRepository.observeItems(vaultId).collect { items ->
                _state.update { it.copy(items = items) }
            }
        }
        // 进入页面即静默同步：先等会话解锁（正常导航已保证），再发起
        viewModelScope.launch {
            vaultRepository.observeUnlockedVaultIds().first { vaultId in it }
            syncInternal()
        }
    }

    fun retrySync() {
        if (_state.value.syncNote == SyncNote.InProgress) return
        viewModelScope.launch { syncInternal() }
    }

    /** 提示条已展示（或已超时）后清除。 */
    fun dismissSyncNote() {
        _state.update { it.copy(syncNote = null) }
    }

    /** 锁定当前库（清零内存密钥）并回调导航。 */
    fun lockNow(onLocked: () -> Unit) {
        viewModelScope.launch {
            vaultRepository.lockVault(vaultId)
            onLocked()
        }
    }

    private suspend fun syncInternal() {
        _state.update { it.copy(syncNote = SyncNote.InProgress) }
        when (val report = vaultRepository.syncVault(vaultId)) {
            is VaultSyncReport.Success ->
                _state.update { it.copy(syncNote = SyncNote.Success(report.cipherCount)) }
            VaultSyncReport.Skipped -> _state.update { it.copy(syncNote = SyncNote.Skipped) }
            is VaultSyncReport.Blocked ->
                _state.update { it.copy(syncNote = SyncNote.Warning(report.reason)) }
            is VaultSyncReport.Retryable ->
                _state.update { it.copy(syncNote = SyncNote.Warning(report.reason)) }
            is VaultSyncReport.Fatal ->
                _state.update { it.copy(syncNote = SyncNote.Warning(report.reason)) }
            VaultSyncReport.Unsupported -> _state.update { it.copy(syncNote = null) }
        }
    }

    /** 新建条目：名称必填；成功后经 [saveEvents] 提示落点。 */
    fun createItem(name: String, username: String, password: String, notes: String) {
        if (_state.value.saving || name.isBlank()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = itemRepository.createItem(
                vaultId = vaultId,
                item = VaultItem(
                    id = "", // id 由 data 层分配本地 uuid
                    title = name.trim(),
                    username = username.trim(),
                    password = password,
                    notes = notes.trim(),
                ),
            )
            _state.update { it.copy(saving = false) }
            val event = outcome.fold(
                onSuccess = { saved ->
                    when (saved) {
                        VaultSaveOutcome.Synced -> SaveEvent.SavedSynced
                        VaultSaveOutcome.Queued -> SaveEvent.SavedQueued
                    }
                },
                onFailure = { error -> SaveEvent.Failed(error.message ?: "未知错误") },
            )
            _saveEvents.send(event)
        }
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"
    }
}
