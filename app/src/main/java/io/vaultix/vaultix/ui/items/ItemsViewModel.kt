package io.vaultix.vaultix.ui.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.repository.BitwardenSyncOrchestrator
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.SyncTrigger
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.ui.common.ItemFilter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 条目列表（S7）。
 *
 * - 同步统一走 [BitwardenSyncOrchestrator]：进页 = PAGE_ENTER 静默同步（90s 节流），
 *   手动刷新 = MANUAL（force，跳节流），失败自动指数退避重试；
 * - 提示条由 orchestrator 的 per-vault 状态派生：仅非静默结果（成功/跳过）与
 *   运行/错误展示；静默自动同步成功不打扰（Bastion 语义）；
 * - 新建走 [ItemRepository.createItem]：本地密文行 + dirty 队列 + 轻量推送。
 */
@HiltViewModel
class ItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
    private val syncOrchestrator: BitwardenSyncOrchestrator,
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
        val query: String = "",
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
        // 同步状态 → 页内提示条（Bastion 语义：静默结果不打扰）
        viewModelScope.launch {
            syncOrchestrator.statusByVault.collect { statuses ->
                _state.update { it.copy(syncNote = toSyncNote(statuses[vaultId])) }
            }
        }
        // 2026-09-08（用户反馈）：进入页面**不再自动同步**——自动同步只随本地
        // 修改（保存/删除 → flush 推送）触发；拉取统一走 retrySync()（顶栏按钮 /
        // 下拉刷新）。编排器 PERIODIC/解锁门卫保留备用。
    }

    /** 同步进行中（下拉刷新指示器用；手动触发后 Orchestrator 状态流转驱动）。 */
    val isSyncing: StateFlow<Boolean> =
        syncOrchestrator.statusByVault.map { statuses ->
            statuses[vaultId]?.isRunning == true
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /** 设置搜索词（匹配标题 / 用户名 / 网址，空词 = 不过滤）。 */
    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** 按当前搜索词过滤后的可见条目（空词 = 全部）。 */
    val visibleItems: StateFlow<List<VaultItem>> = _state
        .map { state -> ItemFilter.filter(state.items, state.query) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun retrySync() {
        // 手动同步：force 跳过节流；运行中请求会按优先级合并
        syncOrchestrator.requestSync(vaultId, SyncTrigger.MANUAL, force = true)
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

    /**
     * 状态 → 提示条（StateFlow 去重：状态不变不重发，无需消费游标）：
     * - 运行中 → InProgress（细进度条）；
     * - 最近完成是错误 → Warning（常驻到下次同步覆盖或用户重试）；
     * - 最近完成是成功：仅手动触发才提示（静默自动同步不打扰，Bastion 语义）。
     */
    private fun toSyncNote(status: VaultSyncStatus?): SyncNote? {
        val s = status ?: return null
        if (s.isRunning) return SyncNote.InProgress

        val successAt = s.lastSuccessAt ?: Long.MIN_VALUE
        val errorAt = s.lastErrorAt ?: Long.MIN_VALUE
        if (errorAt > successAt) {
            return s.lastError?.let { SyncNote.Warning(it) }
        }
        if (successAt != Long.MIN_VALUE) {
            if (s.trigger != SyncTrigger.MANUAL) return null
            return s.lastSuccessCipherCount?.let { SyncNote.Success(it) } ?: SyncNote.Skipped
        }
        return null
    }

    /**
     * 新建条目：名称必填；成功后经 [saveEvents] 提示落点。
     *
     * 入参是表单回传的**完整条目快照**，因此类型由表单决定
     * （登录 / 银行卡 / 身份 / 安全笔记 / SSH 均可新建），不再固定为登录条目。
     */
    fun createItem(item: VaultItem) {
        if (_state.value.saving || item.title.isBlank()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = itemRepository.createItem(
                vaultId = vaultId,
                item = item.copy(id = ""), // id 由 data 层分配本地 uuid
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
