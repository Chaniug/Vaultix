package io.vaultix.vaultix.ui.items

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.repository.BitwardenSyncOrchestrator
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.KdbxSyncReport
import io.vaultix.domain.KdbxSyncRepository
import io.vaultix.domain.OrganizationRepository
import io.vaultix.domain.SyncTrigger
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultOrganization
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.ItemFilter
import io.vaultix.vaultix.ui.common.SyncStatusDot
import io.vaultix.vaultix.ui.common.isSyncUploading
import io.vaultix.vaultix.ui.common.resolveSyncDot
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
 * - 手动同步**按库类型分派**（2026-11-08）：Bitwarden 走 [BitwardenSyncOrchestrator]，
 *   网盘 KDBX 走 [KdbxSyncRepository]。此前一律丢给编排器，而 `syncVault` 对 KDBX 直接
 *   返回 `Unsupported`、编排器对 Unsupported 只把 `isRunning` 置回 false ⇒ 点「同步」
 *   三个反馈通道同时沉默（`.ai` 已知未修项 B）。进页仍**不自动同步**（2026-09-08）。
 * - 提示条按优先级合成三源：KDBX 手动同步结果 → KDBX 库级上传态（保存后那几秒）→
 *   Bitwarden 编排器状态。静默自动同步成功不打扰（Bastion 语义）。
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
    /** 网盘 KDBX 同步：与 [syncOrchestrator] 两套，冲突语义不同，见类 KDoc。 */
    private val kdbxSyncRepository: KdbxSyncRepository,
    private val folderRepository: FolderRepository,
    /** 组织 / 集合（只读，2026-10-21）：供筛选条与条目归属展示。 */
    private val organizationRepository: OrganizationRepository,
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
        /** 库级上传中（**仅 KDBX**）：列表页也要看得见，不等详情页的状态通知。 */
        data object Uploading : SyncNote
        data class Warning(val message: String) : SyncNote
        /**
         * KDBX 手动同步的一次性结果。
         *
         * ⚠️ 文案走 `@StringRes` 而不是 `String`：ViewModel 不该持 Context，中文也不该
         *   抄进 Kotlin（审计 §3.4）。[detail] 是底层已翻译好的失败原因，原样展示。
         *
         * @param warning true = 失败 / 需用户处理，横幅常驻并给「重试」；false = 自动消失。
         */
        data class Notice(
            @StringRes val resId: Int,
            val detail: String? = null,
            val warning: Boolean = false,
        ) : SyncNote
    }

    sealed interface SaveEvent {
        data object SavedSynced : SaveEvent
        data object SavedQueued : SaveEvent
        /**
         * 保存失败（含批量）。
         *
         * ⚠️ `message` 可空、且**不在这里填兜底文案** —— 理由见
         * `ItemDetailViewModel.UiEvent.SaveFailed` 的 KDoc（同一轮 §3.4 修的同一件事）。
         */
        data class Failed(val message: String?) : SaveEvent

        /** 「按住后滑动删除」成功：提示已移入回收站（可恢复）。 */
        data class Deleted(val title: String) : SaveEvent
    }

    data class UiState(
        val vault: VaultSummary? = null,
        val items: List<VaultItem> = emptyList(),
        val query: String = "",
        /** 顶栏点库名展开的那一排快捷筛选中，当前选中的维度。 */
        val quickFilter: ItemsQuickFilter = ItemsQuickFilter.All,
        /** 按组织筛选（`null` = 不限）；与 [quickFilter] 互斥，见 [setOrganizationFilter]。 */
        val organizationFilter: String? = null,
        val syncNote: SyncNote? = null,
        /** ⋮ 菜单「同步」项的常态状态点：网盘库用持久状态，Bitwarden 库用编排器运行时状态。 */
        val syncDot: SyncStatusDot? = null,
        val saving: Boolean = false,
        /**
         * 条目 id → 是否已同步上云（列表行尾小云图标的数据源）。
         *
         * 缺省空 map ⇒ 读侧 `map[id] ?: true`：新建后立即可见、还没入队的那一瞬间，
         * 不该闪一下「未同步」。
         */
        val syncStates: Map<String, Boolean> = emptyMap(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** KDBX 手动同步运行态（Bitwarden 的运行态由编排器自己驱动）。 */
    private val _kdbxManualRunning = MutableStateFlow(false)
    /** KDBX 手动同步的一次性结果；null = 交回三源合成。 */
    private val _manualSyncNote = MutableStateFlow<SyncNote?>(null)

    private val _saveEvents = Channel<SaveEvent>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    init {
        // 由路由参数进入时把该库登记为活跃库：保证主界面 Tab、autofill、
        // Credential Provider 后续读到的是同一个「当前库」（单一活跃库语义）。
        routedVaultId?.let(activeVaultStore::select)
        viewModelScope.launch {
            // ★ 切库即锁旧库（`.ai/ISSUES.md` #60 第 4 批）：KDBX 的「密钥」是内存里的
            // **整库明文**，多库同时解锁会让「同时只能进一个库」的产品约束在内存层面失效。
            // 放在这里而不是 `ActiveVaultStore`：切换由路由/设置页两处触发，而**真正进入
            // 某个库**必然经过本页初始化 —— 在这里做，「切过去才生效」与「切过去才读盘」
            // 的懒打开语义自然一致。
            runCatching { vaultRepository.lockOtherKdbxVaults(vaultId) }
        }
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
        // 云同步状态 → 行尾小云图标（待推送的条目显示云加斜杠）。
        // 与条目流并行收集而不是 combine 到一起：两者的发射频率无关，合并会让
        // 「队列里少了一条」这种高频小变化带着整张列表重新组装。
        viewModelScope.launch {
            vaultIdState
                .flatMapLatest { id -> itemRepository.observeSyncStates(id) }
                .collect { states -> _state.update { it.copy(syncStates = states) } }
        }
        // 同步状态 → 页内提示条（Bastion 语义：静默结果不打扰）。
        // ⚠️ 2026-11-08 三源按优先级合成，补上 KDBX 保存后 / 手动同步期间的反馈空窗：
        //   ① 手动同步的一次性结果 ② KDBX 库级上传态 ③ Bitwarden 编排器。
        //   `observeVaults()` 是库级状态的唯一真源；`isSyncUploading` 与详情页共用判据。
        viewModelScope.launch {
            combine(
                vaultIdState,
                vaultRepository.observeVaults(),
                syncOrchestrator.statusByVault,
                _manualSyncNote,
            ) { id, vaults, statuses, manual ->
                val uploadStatus = vaults.firstOrNull { v -> v.id == id }?.syncStatus
                when {
                    manual != null -> manual
                    isSyncUploading(uploadStatus) -> SyncNote.Uploading
                    else -> toSyncNote(statuses[id])
                }
            }.collect { note -> _state.update { it.copy(syncNote = note) } }
        }
        // 同步状态 → ⋮ 菜单的四色状态点。
        // ⚠️ 与提示条**分开收集**：提示条是「一次性结果，成功就消失」，
        //    状态点是「菜单里随时可见的常态」。合并会让绿点跟着提示条一起闪没，
        //    用户实测反馈就是「零星显示」。
        viewModelScope.launch {
            combine(vaultIdState, vaultRepository.observeVaults(), syncOrchestrator.statusByVault) {
                    id, vaults, statuses ->
                resolveSyncDot(vaults.firstOrNull { v -> v.id == id }, statuses[id])
            }.collect { dot -> _state.update { it.copy(syncDot = dot) } }
        }
        // 2026-09-08（用户反馈）：进入页面**不再自动同步**——自动同步只随本地
        // 修改（保存/删除 → flush 推送）触发；拉取统一走 retrySync()（顶栏按钮 /
        // 下拉刷新）。编排器 PERIODIC/解锁门卫保留备用。
    }

    /** 该库的文件夹列表（未解锁 / 尚无文件夹时为空，UI 据此隐藏下拉）。 */
    val folders: StateFlow<List<VaultFolder>> = vaultIdState
        .flatMapLatest { id -> folderRepository.observeFolders(id) }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    /** 该库所属组织列表（未解锁 / 无组织时为空 —— UI 据此整块隐藏筛选区）。 */
    val organizations: StateFlow<List<VaultOrganization>> = vaultIdState
        .flatMapLatest { id -> organizationRepository.observeOrganizations(id) }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    /** 同步进行中（下拉刷新指示器用）：Bitwarden 编排器 ∪ KDBX 手动同步 ∪ KDBX 库级上传。 */
    val isSyncing: StateFlow<Boolean> =
        combine(
            vaultIdState,
            vaultRepository.observeVaults(),
            syncOrchestrator.statusByVault,
            _kdbxManualRunning,
        ) { id, vaults, statuses, kdbxRunning ->
            // ★ 11-08：整文件上传 5–60s，此前既不在 Bitwarden 编排器状态里，
            //    也不算"手动同步"⇒ 下拉刷新全程转不起来，用户以为点了没反应。
            val uploadStatus = vaults.firstOrNull { v -> v.id == id }?.syncStatus
            statuses[id]?.isRunning == true || kdbxRunning || isSyncUploading(uploadStatus)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /** 设置搜索词（匹配标题 / 用户名 / 网址，空词 = 不过滤）。 */
    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /**
     * 设置快捷筛选（顶栏点库名展开的那一排 chip）。
     *
     * **刻意不持久化**：筛选是「我这一会儿想看什么」的临时状态。落盘会让用户
     * 下次打开 App 对着一个空列表发呆（条目都在，只是被上次的筛选挡着），
     * 而顶部那排 chip 收起时看不见 —— 这就是「条目莫名其妙不见了」类投诉的来源。
     */
    fun setQuickFilter(filter: ItemsQuickFilter) =
        _state.update { it.copy(quickFilter = filter, organizationFilter = null) }

    /**
     * 按组织筛选（`null` = 不限组织）。
     *
     * ⚠️ 与 [setQuickFilter] **互相清空**：两套筛选叠加时列表会变成
     * "组织 A 里的验证码条目"这种极难回到全集的组合；顶栏标题也只能显示一种。
     * ⇒ 同一时刻只有一种"范围筛选"生效（搜索框是独立维度，可叠加）。
     */
    fun setOrganizationFilter(organizationId: String?) =
        _state.update {
            it.copy(
                organizationFilter = organizationId,
                quickFilter = if (organizationId != null) ItemsQuickFilter.All else it.quickFilter,
            )
        }

    /** 当前筛选维度（UI 收集；[ItemsQuickFilter.All] = 不筛选）。 */
    val quickFilter: StateFlow<ItemsQuickFilter> = _state
        .map { it.quickFilter }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ItemsQuickFilter.All,
        )

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
     * 展开快捷筛选面板时是否把列表拉回顶端（默认 **false** = 保住滚动位置）。
     *
     * 见 `VaultixPreferences.itemsFilterJumpToTop` 的 KDoc：`#182` 的"滚到顶端"实测后
     * 被用户判定为"不友好"，因此改为**可选项**且默认关闭。
     */
    val filterJumpToTop: StateFlow<Boolean> = preferences.itemsFilterJumpToTop
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    fun setFilterJumpToTop(enabled: Boolean) {
        viewModelScope.launch { preferences.setItemsFilterJumpToTop(enabled) }
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
                SaveEvent.Failed(result.exceptionOrNull()?.message)
            }
            _saveEvents.send(message)
        }
    }

    /**
     * 按当前搜索词 + 快捷筛选过滤后的可见条目（空词 + All = 全部）。
     *
     * 顺序固定为「先筛选再搜索」：两者都是 `filter`，交换顺序结果相同，
     * 但筛选在前可以让搜索只在子集上跑（快捷筛选之后通常只剩几条）。
     */
    val visibleItems: StateFlow<List<VaultItem>> = _state
        .map { state ->
            ItemFilter.filter(
                state.items
                    .applyOrganizationFilter(state.organizationFilter)
                    .applyQuickFilter(state.quickFilter),
                state.query,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /**
     * 手动同步：**按库类型分派**（2026-11-08）。
     *
     * 此前无论什么库都丢给 [syncOrchestrator]；`VaultRepositoryImpl.syncVault` 对非
     * Bitwarden 库返回 `Unsupported`，编排器对 Unsupported 只 `isRunning = false`，
     * **不写成功、不写失败、不写错误** ⇒ 提示条 / 下拉指示器 / 菜单状态点三个通道
     * 同时沉默，用户觉得「点了没反应」。
     */
    fun retrySync() {
        if (_state.value.vault?.kind == VaultKind.KDBX) {
            retrySyncKdbx()
        } else {
            // 手动同步：force 跳过节流；运行中请求会按优先级合并
            syncOrchestrator.requestSync(vaultId, SyncTrigger.MANUAL, force = true)
        }
    }

    /**
     * 网盘 KDBX 的手动同步：判据**现查**（不能用 UI 快照，见 `KdbxSyncRepository` KDoc），
     * 结果一律映射成可见的 [SyncNote]。
     *
     * ⚠️ 冲突在这里**不收敛**：列表页没有三选项对话框，强行收敛会静默丢改动。
     *   如实提示并引导到「密码库」页拍板（那条路有完整对话框），不编造「已同步」。
     */
    private fun retrySyncKdbx() {
        if (_kdbxManualRunning.value) return
        if (_state.value.vault?.unlocked != true) {
            _manualSyncNote.value = SyncNote.Notice(
                resId = R.string.kdbx_sync_needs_reload,
                warning = true,
            )
            return
        }
        viewModelScope.launch {
            _kdbxManualRunning.value = true
            // ★ 11-08：此前无条件写 Uploading ⇒ 用户点「同步」第一眼就是
            //    「正在上传改动到云端…」，可这一步其实只是**检查**，方向要等结果才知道。
            //    复用中性的 InProgress（波浪进度条 + 说明），由结果 Notice 说出真实方向。
            _manualSyncNote.value = SyncNote.InProgress
            val report = runCatching {
                val changed = kdbxSyncRepository.localChangedSinceLastSync(vaultId)
                kdbxSyncRepository.sync(vaultId, localChangedSinceLastSync = changed)
            }.getOrElse { KdbxSyncReport.Failed(it.message.orEmpty()) }
            _kdbxManualRunning.value = false
            _manualSyncNote.value = when (report) {
                is KdbxSyncReport.InSync -> SyncNote.Notice(R.string.kdbx_sync_in_sync)
                is KdbxSyncReport.Uploaded -> SyncNote.Notice(R.string.kdbx_sync_uploaded)
                is KdbxSyncReport.Downloaded -> SyncNote.Notice(R.string.kdbx_sync_downloaded)
                is KdbxSyncReport.NeedsReload ->
                    SyncNote.Notice(R.string.kdbx_sync_needs_reload, warning = true)
                is KdbxSyncReport.NoCloudSource ->
                    SyncNote.Notice(R.string.kdbx_sync_no_cloud_source, warning = true)
                is KdbxSyncReport.StaleBaseline ->
                    SyncNote.Notice(R.string.kdbx_sync_stale_baseline, warning = true)
                is KdbxSyncReport.Conflict ->
                    SyncNote.Notice(R.string.items_sync_conflict_go_to_vaults, warning = true)
                is KdbxSyncReport.Failed -> SyncNote.Notice(
                    resId = R.string.kdbx_sync_failed,
                    detail = report.reason,
                    warning = true,
                )
            }
        }
    }

    /** 提示条已展示（或已超时）后清除：同时清掉手动同步结果，否则会被三源合成立刻重放。 */
    fun dismissSyncNote() {
        _manualSyncNote.value = null
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
                onFailure = { error -> SaveEvent.Failed(error.message) },
            )
            _saveEvents.send(event)
        }
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"
    }
}
