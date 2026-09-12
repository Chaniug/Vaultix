package io.vaultix.vaultix.ui.items

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.repository.BitwardenSyncOrchestrator
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.SyncTrigger
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.ui.common.ItemFilter
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val sessionRepository: VaultSessionRepository,
    private val itemRepository: ItemRepository,
    private val syncOrchestrator: BitwardenSyncOrchestrator,
    private val folderRepository: FolderRepository,
    private val activeVaultStore: ActiveVaultStore,
    private val preferences: VaultixPreferences,
) : ViewModel() {

    /**
     * 路由显式携带的库 id（二级直达场景，如搜索 / 快捷方式）。
     *
     * 有值 → 本页固定在该库；为 null（主界面 Tab 内嵌）→ 跟随 [ActiveVaultStore]
     * （main-shell-migration 阶段 2，A4「vaultId 参数 → 筛选状态」降级）。
     */
    private val routedVaultId: String? = savedStateHandle[ARG_VAULT_ID]

    /**
     * 库 id 源：路由参数优先且**固定**（二级直达场景）；无参数时跟随 [ActiveVaultStore]
     * ——**切换活跃库后本页内容会自动跟着变**（设置页「库管理」切换后无需重建页面）。
     */
    private val vaultIdSource: Flow<String> = routedVaultId
        ?.let { id -> flowOf(id) }
        ?: activeVaultStore.activeVaultId.map { it.orEmpty() }

    private val vaultIdState: StateFlow<String> = vaultIdSource.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = routedVaultId ?: activeVaultStore.current() ?: "",
    )

    /** 当前库（供一次性动作读取：同步 / 锁定 / 新建）。 */
    val vaultId: String get() = vaultIdState.value

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

        /** 「按住后滑动删除」成功：提示已移入回收站（可恢复）。 */
        data class Deleted(val title: String) : SaveEvent
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
        // 由路由参数进入时把该库登记为活跃库：保证主界面 Tab、autofill、
        // Credential Provider 后续读到的是同一个「当前库」（单一活跃库语义）。
        routedVaultId?.let(activeVaultStore::select)
        viewModelScope.launch {
            combine(vaultIdState, vaultRepository.observeVaults()) { id, vaults ->
                vaults.firstOrNull { v -> v.id == id }
            }.collect { vault -> _state.update { it.copy(vault = vault) } }
        }
        viewModelScope.launch {
            vaultIdState
                .flatMapLatest { id -> itemRepository.observeItems(id) }
                .collect { items -> _state.update { it.copy(items = items) } }
        }
        // 同步状态 → 页内提示条（Bastion 语义：静默结果不打扰）
        viewModelScope.launch {
            combine(vaultIdState, syncOrchestrator.statusByVault) { id, statuses ->
                toSyncNote(statuses[id])
            }.collect { note -> _state.update { it.copy(syncNote = note) } }
        }
        // 2026-09-08（用户反馈）：进入页面**不再自动同步**——自动同步只随本地
        // 修改（保存/删除 → flush 推送）触发；拉取统一走 retrySync()（顶栏按钮 /
        // 下拉刷新）。编排器 PERIODIC/解锁门卫保留备用。
    }

    /** 该库的文件夹列表（未解锁 / 尚无文件夹时为空，UI 据此隐藏下拉）。 */
    val folders: StateFlow<List<VaultFolder>> = vaultIdState
        .flatMapLatest { id -> folderRepository.observeFolders(id) }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    /** 同步进行中（下拉刷新指示器用；手动触发后 Orchestrator 状态流转驱动）。 */
    val isSyncing: StateFlow<Boolean> =
        combine(vaultIdState, syncOrchestrator.statusByVault) { id, statuses ->
            statuses[id]?.isRunning == true
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /** 设置搜索词（匹配标题 / 用户名 / 网址，空词 = 不过滤）。 */
    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /**
     * 条目分组方式（偏好持久化；默认不分组，保持历史观感）。
     *
     * 分组维度取模型里真实存在的三种（类型 / 文件夹 / 首字母），详见 [ItemsGroupMode]。
     */
    val groupMode: StateFlow<ItemsGroupMode> = preferences.itemsGroupMode
        .map(ItemsGroupMode::from)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ItemsGroupMode.None,
        )

    fun setGroupMode(mode: ItemsGroupMode) {
        viewModelScope.launch { preferences.setItemsGroupMode(mode.storageKey) }
    }

    /** 卡片信息密度（全部 / 标题+用户名 / 仅标题），对齐 Bastion `PasswordCardDisplayMode`。 */
    val cardDisplayMode: StateFlow<ItemsCardDisplayMode> = preferences.itemsCardDisplayMode
        .map(ItemsCardDisplayMode::from)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ItemsCardDisplayMode.All,
        )

    fun setCardDisplayMode(mode: ItemsCardDisplayMode) {
        viewModelScope.launch { preferences.setItemsCardDisplayMode(mode.storageKey) }
    }

    /** 卡片是否显示左侧图标（对齐 Bastion `iconCardsEnabled`）。 */
    val showIcon: StateFlow<Boolean> = preferences.itemsShowIcon
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    fun setShowIcon(enabled: Boolean) {
        viewModelScope.launch { preferences.setItemsShowIcon(enabled) }
    }

    /**
     * 列表内「按住后滑动删除」：走**软删除**（进回收站，可恢复 / 也可在回收站永久删除）。
     *
     * 与详情页的删除同一路径（`ItemRepository.softDeleteItem`），不新增数据语义；
     * 列表侧只在成功后提示一句，避免用户以为条目凭空消失。
     */
    fun deleteItem(item: VaultItem) {
        viewModelScope.launch {
            val result = itemRepository.softDeleteItem(vaultId, item.id)
            val message = if (result.isSuccess) {
                SaveEvent.Deleted(item.title)
            } else {
                SaveEvent.Failed(result.exceptionOrNull()?.message ?: "未知错误")
            }
            _saveEvents.send(message)
        }
    }

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

    /**
     * 主页锁按钮：锁**查看层**（不清内存密钥）。
     *
     * 用户明确要求（`.ai/ISSUES.md` #60）：
     * 「主页密码条目上方的锁按钮应该**只锁生物验证那一层，解锁密钥不应该被清除**」。
     *
     * 因此这里**不再**调 [VaultRepository.lockVault]（那会清零密钥，恢复时必须联网
     * 重登 + 可能的 2FA —— 用户反馈的「频繁解锁」正是这条链路）。改为只置内存标记：
     * 根导航立刻把界面收回解锁页，一次生物识别即回到原界面。
     *
     * 「从不自动锁定」档位不受影响：本动作由用户显式触发，与超时策略无关
     * （超时策略只管「不请自来」的锁定）——但用户主动按锁就该锁。
     *
     * @param onLocked 无副作用保留位（真锁路径已由根导航接管）。
     */
    fun lockNow(onLocked: () -> Unit) {
        viewModelScope.launch {
            sessionRepository.viewLock(vaultId)
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
