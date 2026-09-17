package io.vaultix.vaultix.ui.vaultlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.repository.BitwardenSyncOrchestrator
import io.vaultix.data.repository.LocalUnlockEnrollment
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.KdbxSyncReport
import io.vaultix.domain.KdbxSyncRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.ui.common.KdbxConflictChoice
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
    /**
     * KDBX 网盘同步（OneDrive / WebDAV）。
     *
     * ⚠️ 与 [syncOrchestrator] 是**两套**：那个是 Bitwarden 的账号全量同步。
     * 两者的冲突语义不同（服务端 revision 仲裁 vs 条件写 + 用户拍板），
     * 不合并（见 `KdbxSyncRepository` 的说明）。
     */
    private val kdbxSyncRepository: KdbxSyncRepository,
) : ViewModel() {

    sealed interface Event {
        data object Removed : Event
        data class RemoveFailed(val message: String) : Event

        /** KDBX 网盘同步结束（成功路径）；[message] 可直接展示。 */
        data class KdbxSynced(val message: String) : Event

        /**
         * ★ 需要用户拍板的同步冲突 —— UI **必须**弹三个选项的对话框
         * （见 `KdbxConflictDialog`）。
         *
         * ⚠️ 它不是"错误"，不能混进 [KdbxSyncFailed] 用一句 toast 带过：
         * 冲突要用户做一次**有代价的**选择，一句提示会把它变成"哦，失败了啊"。
         */
        data class KdbxConflict(val vaultId: String, val vaultName: String) : Event

        /** KDBX 网盘同步失败（网络 / 凭据 / IO）；可重试。 */
        data class KdbxSyncFailed(val message: String) : Event
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

    /**
     * 手动触发一次 KDBX 网盘同步。
     *
     * ## `localChangedSinceLastSync` 传什么（这是本方法的唯一难点）
     *
     * ⚠️ 我们**不知道**"本地改没改" —— 条目级编辑发生在别处（条目页 / 自动填充回写），
     * 没往这里上报。所以这里走保守路线：**传 true**（"本地可能改过"）。
     *
     * 两种传法的后果对比：
     * - 传 `false` 而本地其实改过 ⇒ 编排器判定"两边都没变"，直接 `AlreadyInSync`
     *   ⇒ **用户的编辑永远推不上去**（换台设备看不到，且没有任何提示）。**这是数据丢失**。
     * - 传 `true` 而本地其实没改 ⇒ 若远端恰好也变了，会报一次冲突让用户拍板。
     *   **这只是一次多余的确认**，用户点"用远端覆盖"即可，没有数据丢失。
     *
     * ⇒ 两种误判的代价完全不对等，必须选代价小的那个。
     *   真正的"精确判定"要在条目编辑处埋一个 dirty 标记（后续批次）。
     */
    fun syncKdbxVault(vaultId: String) {
        viewModelScope.launch {
            when (val report = kdbxSyncRepository.sync(vaultId, localChangedSinceLastSync = true)) {
                is KdbxSyncReport.InSync ->
                    _events.emit(Event.KdbxSynced("已是最新版本"))

                is KdbxSyncReport.Uploaded ->
                    _events.emit(Event.KdbxSynced("已上传到云端"))

                is KdbxSyncReport.Downloaded ->
                    _events.emit(Event.KdbxSynced("已从云端更新"))

                // ★ 冲突：交给 UI 弹三选项对话框，这里不发 toast。
                is KdbxSyncReport.Conflict -> {
                    val name = vaultRepository.observeVaults().first()
                        .firstOrNull { it.id == vaultId }?.name ?: "该密码库"
                    _events.emit(Event.KdbxConflict(vaultId = vaultId, vaultName = name))
                }

                is KdbxSyncReport.NeedsReload ->
                    _events.emit(Event.KdbxSynced("云端有更新，重新解锁后会拉取"))

                is KdbxSyncReport.NoCloudSource ->
                    _events.emit(Event.KdbxSyncFailed("这个库还没有配置云端来源"))

                is KdbxSyncReport.Failed ->
                    _events.emit(Event.KdbxSyncFailed(report.reason))
            }
        }
    }

    /**
     * 用户在冲突对话框里做了选择。
     *
     * ⚠️ [KdbxConflictChoice.DecideLater] **也必须**走到仓储（`defer`）而不是
     * 单纯关掉对话框：状态还停在 `SYNCING` 的话，界面上是一个**永远转不完的圈**。
     */
    fun resolveKdbxConflict(vaultId: String, choice: KdbxConflictChoice) {
        viewModelScope.launch {
            when (choice) {
                KdbxConflictChoice.KeepLocalUpload ->
                    emitResolve(kdbxSyncRepository.resolveUsingLocal(vaultId), "已用本地覆盖云端")

                KdbxConflictChoice.KeepRemoteDownload ->
                    emitResolve(kdbxSyncRepository.resolveUsingRemote(vaultId), "已用云端覆盖本地")

                KdbxConflictChoice.DecideLater -> {
                    kdbxSyncRepository.defer(vaultId)
                    // 不提示：用户明确选了"稍后"，弹一句"已稍后处理"是噪音。
                }
            }
        }
    }

    private suspend fun emitResolve(report: KdbxSyncReport, successMessage: String) {
        when (report) {
            is KdbxSyncReport.Uploaded,
            is KdbxSyncReport.Downloaded,
            is KdbxSyncReport.InSync,
            -> _events.emit(Event.KdbxSynced(successMessage))

            is KdbxSyncReport.Failed -> _events.emit(Event.KdbxSyncFailed(report.reason))

            // 用户在对话框里选了覆盖，但远端在这期间又变了（极端竞态）。
            // ⚠️ 不能报"成功了" —— 那会让用户以为已经覆盖，实际根本没写进去。
            is KdbxSyncReport.Conflict -> _events.emit(Event.KdbxSyncFailed("云端又有了新改动，请重新同步"))

            // 理论上到不了：这两个分支只在"还没开始同步"时出现。
            is KdbxSyncReport.NeedsReload -> _events.emit(Event.KdbxSyncFailed("需要重新解锁后再试"))
            is KdbxSyncReport.NoCloudSource -> _events.emit(Event.KdbxSyncFailed("这个库还没有配置云端来源"))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
