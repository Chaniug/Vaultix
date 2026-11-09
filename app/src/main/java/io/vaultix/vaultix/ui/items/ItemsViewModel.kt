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
import io.vaultix.model.KdbxCloudSyncStatus
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultOrganization
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.ItemFilter
import io.vaultix.vaultix.ui.common.SyncBadge
import io.vaultix.vaultix.ui.common.SyncStatusDot
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

    /**
     * KDBX **持久**同步状态的异常态 → 常驻横幅（2026-11-08 晚，用户反馈驱动）。
     *
     * ## 为什么必须单独有一条通路
     *
     * 同日早些时候把「后台上传」的横幅整个去掉了（见 init 里那段注释）：它由库级
     * `syncStatus` 派生，会一直显示「正在上传改动到云端…」直到 30~70s 的网络请求结束，
     * 用户因此觉得「删除还没完成」。**但那次把失败提示也一起砍掉了** ——
     * 上传失败只剩 ⋮ 菜单里一个 6dp 的红点，菜单不展开就完全看不见。
     *
     * ⇒ 拆成两件事：
     * - **上传中**（PENDING_UPLOAD / SYNCING）**不挂横幅**（不打扰，也不假装"还在等"）；
     * - **需要用户动手**（失败 / 冲突 / 云端有新版本）挂**常驻**横幅，带「重试」。
     *
     * ⚠️ `PENDING` 不在其中：它是"正在自动传"，用户无需动作，挂横幅就是把
     *    "本地已完成"重新说成"还在等"。
     */
    private val _kdbxAttentionNote = MutableStateFlow<SyncNote?>(null)

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
        // ★ 2026-11-08：后台自动上传**不再挂「上传中」横幅**。它以前由库级 syncStatus 派生，
        //    会一直显示「正在上传改动到云端…」直到 30~70s 的网络请求结束，
        //    用户因此觉得「删除还没完成、要等」。现在本地落缓存即算完成
        //    （写动作的 Snackbar 已经提示），上传状态只由 ⋮ 状态点 / 详情页角标表达。
        // ★ 2026-11-08 晚：**但失败必须说话** —— 见 [_kdbxAttentionNote]。
        //    三源优先级：手动同步的一次性结果 > KDBX 持久异常 > Bitwarden 编排器状态。
        viewModelScope.launch {
            combine(
                vaultIdState,
                syncOrchestrator.statusByVault,
                _manualSyncNote,
                _kdbxAttentionNote,
            ) { id, statuses, manual, kdbxAttention ->
                manual ?: kdbxAttention ?: toSyncNote(statuses[id])
            }.collect { note -> _state.update { it.copy(syncNote = note) } }
        }
        // KDBX 持久异常态（失败 / 冲突 / 云端有新版本）→ 常驻横幅。
        // ⚠️ 与上面那条**分开收集**：这条只看持久状态，不看本次手动同步跑到哪一步，
        //    两者混在一个 combine 里会让「手动结果的优先级」被持久态反复顶回来。
        viewModelScope.launch {
            vaultIdState
                .flatMapLatest { id ->
                    vaultRepository.observeVaults()
                        .map { vaults -> vaults.firstOrNull { v -> v.id == id }?.syncStatus }
                }
                .collect { status -> _kdbxAttentionNote.value = kdbxAttentionNoteOf(status) }
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

    /**
     * 新建文件夹并回调新 id（2026-11-09，用户反馈「新建条目时选不到新建文件夹」）。
     *
     * 为什么放在这里而不是让表单自己去调仓储：表单是纯 Composable，没有仓储依赖，
     * 也不该有（它要被三个页面复用）。写能力由调用方的 ViewModel 提供 ——
     * 表单只声明"我要一个建文件夹的入口"，具体走哪条仓储是宿主的事。
     *
     * ⚠️ 失败**不回调**：`onCreated` 只在真的建成功时触发。建失败时表单会停在
     *    「无文件夹」，用户能看出没成功；若失败也回调一个空 id，就会悄悄把条目
     *    归到根组，用户还以为建好了。
     */
    fun createFolder(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = vaultIdState.value
            folderRepository.createFolder(id, name)
                .onSuccess { folder -> onCreated(folder.id) }
                // 失败复用既有的保存事件通道（Snackbar），不新开一套提示。
                .onFailure { error -> _saveEvents.send(SaveEvent.Failed(error.message)) }
        }
    }

    /** 该库所属组织列表（未解锁 / 无组织时为空 —— UI 据此整块隐藏筛选区）。 */
    val organizations: StateFlow<List<VaultOrganization>> = vaultIdState
        .flatMapLatest { id -> organizationRepository.observeOrganizations(id) }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    /** 同步进行中（下拉刷新指示器用）：Bitwarden 编排器 ∪ KDBX 手动同步。 */
    val isSyncing: StateFlow<Boolean> =
        combine(
            vaultIdState,
            syncOrchestrator.statusByVault,
            _kdbxManualRunning,
        ) { id, statuses, kdbxRunning ->
            // ★ 2026-11-08：把「库级后台上传」从指示器里拿掉 —— 它是 30~70s 的
            //    网络请求，让下拉刷新一直转只会把「本地已完成」误报成「还在等」。
            //    后台上传由角标表达；只有用户主动同步 / Bitwarden 编排器才转指示器。
            statuses[id]?.isRunning == true || kdbxRunning
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
        // ⚠️ 2026-11-08：这里原先有 `if (_state.value.vault?.unlocked != true)` 守卫，
        //    只弹「需要重新解锁后再试」就 return。那份 unlocked 是 **UI 快照**，
        //    与 KDBX 会话是否可用并不同源 ⇒ 用户点多少次重试都被同一句挡回来。
        //    是否真的要解锁，交给下面的 sync/pull 结果说话，别在入口猜。
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
            // ★ 2026-11-08 真机：NeedsReload 以前**只提示**、没人真的去拉远端，
            //    于是红色横幅点多少次「重试」都不收敛；只有切到「密码库」页点
            //    「立即同步」才好 —— 那里对同一结果调的是 `pull()`（见 VaultListViewModel）。
            //    ⇒ 这里补上同一个动作。`pull()` 自带「本地有未上传改动 ⇒ 返回 Conflict」
            //    的前置检查，不会把用户没传上去的改动盖掉。
            val settled = if (report is KdbxSyncReport.NeedsReload) {
                runCatching { kdbxSyncRepository.pull(vaultId) }
                    .getOrElse { KdbxSyncReport.Failed(it.message.orEmpty()) }
            } else {
                report
            }
            _kdbxManualRunning.value = false
            _manualSyncNote.value = when (settled) {
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
                    detail = settled.reason,
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
     * KDBX **持久**同步状态 → 常驻横幅：**只取需要用户动手的三种**。
     *
     * 纯函数（可 JVM 单测）。`PENDING`（正在自动传）与正常态一律 `null`：
     * 给上传中挂横幅会把「本地已完成」重新说成「还在等」—— 正是 11-08 早先
     * 被用户否掉的那个行为，不能因为补失败提示又把它捡回来。
     *
     * 三种文案各说"下一步做什么"，而不是复述状态：
     * - 失败 → 改动只在本机（暗示远端还是旧的，这句才是用户真正要知道的）；
     * - 冲突 → 去能拍板的那一页（列表页没有三选项对话框，强行收敛会丢改动）；
     * - 云端有新版本 → 点重试拉取（不用重新解锁）。
     */
    private fun kdbxAttentionNoteOf(status: KdbxCloudSyncStatus?): SyncNote? =
        when (SyncBadge.of(status)) {
            SyncBadge.FAILED -> SyncNote.Notice(R.string.kdbx_upload_failed_banner, warning = true)
            SyncBadge.CONFLICT ->
                SyncNote.Notice(R.string.items_sync_conflict_go_to_vaults, warning = true)
            SyncBadge.REMOTE_CHANGED ->
                SyncNote.Notice(R.string.kdbx_remote_changed_banner, warning = true)
            SyncBadge.PENDING, null -> null
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
