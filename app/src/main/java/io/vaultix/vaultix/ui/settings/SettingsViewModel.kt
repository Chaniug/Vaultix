package io.vaultix.vaultix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.datastore.VaultixPreferencesDefaults
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.KdbxEnrollOutcome
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.security.AutoLockController
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import io.vaultix.vaultix.ui.items.ItemsCardDisplayMode
import io.vaultix.vaultix.ui.items.ItemsGroupMode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * 设置页（最小版）。偏好值全部来自 [VaultixPreferences]（DataStore，非敏感），
 * 保存即实时生效（主题 / 防截屏由 MainActivity 收集，锁定由 AutoLockController
 * 收集，剪贴板清除由详情页收集）。
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    private val preferences: VaultixPreferences,
    private val vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
    private val autoLockController: AutoLockController,
    private val activeVaultStore: ActiveVaultStore,
    private val sessionRepository: VaultSessionRepository,
) : ViewModel() {
    data class UiState(
        val vaultTimeout: VaultTimeout = VaultTimeout.DEFAULT,
        val clipboardClearMs: Long = 30_000L,
        val dynamicColor: Boolean = true,
        val screenSecurity: Boolean = true,
    )

    /**
     * 本地快速解锁「生效范围」列表项（每库一行）。
     *
     * `kind` 用于区分两条**截然不同**的登记流程（定稿 §4）：
     * Bitwarden 包裹会话密钥（当场无需再输密码）；KDBX 只能包裹
     * 「主密码 + keyfile」⇒ **必须先向用户再要一次主密码**（§4.5）。
     * 这个差异必须在 UI 上可见，否则用户不明白为什么点 KDBX 会弹密码框。
     */
    data class QuickUnlockVaultUi(
        val vaultId: String,
        val name: String,
        val enabled: Boolean,
        val kind: VaultKind,
    )

    val state: StateFlow<UiState> = combine(
        preferences.vaultTimeout,
        preferences.clipboardClearMs,
        preferences.dynamicColor,
        preferences.screenSecurity,
    ) { timeout, clearMs, dynamic, secure ->
        UiState(
            vaultTimeout = timeout,
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
                                kind = vault.kind,
                            )
                        }
                    },
                ) { items -> items.toList() }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /** 主题模式原始值（system / light / dark；UI 侧用 ThemeMode.from 解析显示与回传）。 */
    val themeMode: StateFlow<String> = preferences.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VaultixPreferencesDefaults.THEME_MODE,
        )

    /** OLED 纯黑（深色模式 surface/background 纯黑）。 */
    val oledPureBlack: StateFlow<Boolean> = preferences.oledPureBlack
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** 回收站自动清理档位（天；0 = 不自动清空），与回收站页顶栏入口共用同一偏好。 */
    val trashAutoDeleteDays: StateFlow<Int> = preferences.trashAutoDeleteDays
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VaultixPreferencesDefaults.TRASH_AUTO_DELETE_DAYS,
        )

    /** 自动填充保存提示（登录成功后询问保存 / 更新凭据，默认开）。 */
    val autofillSavePrompt: StateFlow<Boolean> = preferences.autofillSavePrompt
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setAutofillSavePrompt(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutofillSavePrompt(enabled) }
    }

    /** 自动填充后自动复制验证码（条目带 TOTP 而页面没有验证码框时）。 */
    val autoCopyTotp: StateFlow<Boolean> = preferences.autoCopyTotp
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setAutoCopyTotp(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoCopyTotp(enabled) }
    }

    /** 域匹配：允许基域 / 子域名命中（默认开，对齐 Bitwarden）。 */
    val autofillBaseDomainMatch: StateFlow<Boolean> = preferences.autofillBaseDomainMatch
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setAutofillBaseDomainMatch(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutofillBaseDomainMatch(enabled) }
    }

    /** 域匹配：仅精确域（默认关）。关掉「严格匹配」是浏览器填不出来时的首选排查动作。 */
    val autofillExactDomainOnly: StateFlow<Boolean> = preferences.autofillExactDomainOnly
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun setAutofillExactDomainOnly(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutofillExactDomainOnly(enabled) }
    }

    /**
     * 「填充辅助」（对齐 Bitwarden 自动填充设置页的 `FillAssistSwitch`，默认开）。
     *
     * 关掉后不再按站点规则覆盖启发式识别；规则表缓存仍保留（下次打开立即生效，
     * 不必等 6 小时节流）。
     */
    val fillAssistEnabled: StateFlow<Boolean> = preferences.fillAssistEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setFillAssistEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setFillAssistEnabled(enabled) }
    }

    // ---- 条目列表显示选项（与密码 Tab 的「显示选项」弹层共用同一份偏好）----

    /** 分组方式（不分组 / 类型 / 文件夹 / 首字母）。 */
    val itemsGroupMode: StateFlow<ItemsGroupMode> = preferences.itemsGroupMode
        .map(ItemsGroupMode::from)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ItemsGroupMode.None,
        )

    fun setItemsGroupMode(mode: ItemsGroupMode) {
        viewModelScope.launch { preferences.setItemsGroupMode(mode.storageKey) }
    }

    /** 卡片信息密度（全部 / 标题+用户名 / 仅标题）。 */
    val itemsCardDisplayMode: StateFlow<ItemsCardDisplayMode> = preferences.itemsCardDisplayMode
        .map(ItemsCardDisplayMode::from)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ItemsCardDisplayMode.All,
        )

    fun setItemsCardDisplayMode(mode: ItemsCardDisplayMode) {
        viewModelScope.launch { preferences.setItemsCardDisplayMode(mode.storageKey) }
    }

    /** 卡片是否显示左侧图标。 */
    val itemsShowIcon: StateFlow<Boolean> = preferences.itemsShowIcon
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setItemsShowIcon(enabled: Boolean) {
        viewModelScope.launch { preferences.setItemsShowIcon(enabled) }
    }

    /**
     * **当前活跃库**（null = 全锁）。
     *
     * Vaultix 是「单活跃库」语义（Docs/progress/main-shell-migration.md §0）：主界面 Tab、
     * autofill 候选、Credential Provider 候选、保存回写目标**全部**只认这一项。
     * 设置页是它唯一的对外切换入口。
     */
    val activeVault: StateFlow<VaultSummary?> = combine(
        activeVaultStore.activeVaultId,
        vaultRepository.observeVaults(),
    ) { id, vaults -> vaults.firstOrNull { it.id == id } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * 可**切换**目标 = 已解锁的库 ∪ 当前默认库。
     *
     * ⚠️ 2026-09-14（`.ai/decisions/库选择与快速解锁-逻辑定稿.md` §7 任务 2 / issue #96）：
     * 原先**只列已解锁库** ⇒ 未解锁的 KDBX 根本不在列表里，用户「看不到我的库」，
     * 以为库丢了。现在把全部库都列出来（见 [allVaults]），由 UI 对未解锁项标注
     * 「未解锁，点击输入密码」并跳解锁页 —— 「找得到」优先于「点得动」。
     */
    val switchableVaults: StateFlow<List<VaultSummary>> = vaultRepository.observeVaults()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * **默认库**（冷启动先开哪个；null = 未设置 → 退回「`createdAt` 最早」的既有逻辑）。
     *
     * ⚠️ 与 [activeVault] 是两个概念：活跃库是「本次会话在看谁」（切库即变），
     * 默认库是「下次冷启动先开谁」（**只在本页明确修改时才变**）。
     * 二者写入点分离见 `ActiveVaultStore` 的类注释。
     */
    val defaultVault: StateFlow<VaultSummary?> = combine(
        preferences.defaultVaultId,
        vaultRepository.observeVaults(),
    ) { id, vaults -> vaults.firstOrNull { it.id == id } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * 切换活跃库：主界面 Tab / autofill / CP 三处**同时**生效（单一活跃库语义）。
     *
     * ⚠️ **不写默认库** —— 临时切库不该改掉冷启动默认库（见 `ActiveVaultStore.select`）。
     */
    fun selectVault(vaultId: String) = activeVaultStore.select(vaultId)

    /**
     * 设某库为**默认库**（「冷启动先开这个」）。
     *
     * 同时把活跃库切过去：用户点这一项时意图就是「以后开这个」，若只改默认库而不切，
     * 界面上会立刻出现「活跃库 A、默认库 B」的不一致观感，用户无法判断哪句话算数。
     */
    fun setDefaultVault(vaultId: String) {
        activeVaultStore.select(vaultId)
        activeVaultStore.setDefault(vaultId)
    }

    /**
     * **活跃库**中的通行密钥总数（设置页「通行密钥」分组展示）。
     *
     * ⚠️ 口径必须与填充侧一致：autofill / CP 只查活跃库，这里若跨库累加就会出现
     * 「设置页显示 5 个，填充时一个都不弹」。统计也只认解锁库 —— 锁定库的密文读不出来，
     * 硬统计只会得到 0 并误导用户「我没存过通行密钥」，故副标题写明口径。
     */
    val passkeyCount: StateFlow<Int> = activeVaultStore.activeVaultId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) {
                flowOf(0)
            } else {
                itemRepository.observeItems(id).map { items -> items.sumOf { it.fido2Credentials.size } }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    fun setVaultTimeout(timeout: VaultTimeout) {
        viewModelScope.launch { preferences.setVaultTimeout(timeout) }
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

    /** 主题模式（system / light / dark），MainActivity 收集后实时切换。 */
    fun setThemeMode(mode: String) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    /** OLED 纯黑开关。 */
    fun setOledPureBlack(enabled: Boolean) {
        viewModelScope.launch { preferences.setOledPureBlack(enabled) }
    }

    /** 回收站自动清理档位（天；0 = 不自动清空）。 */
    fun setTrashAutoDeleteDays(days: Int) {
        viewModelScope.launch { preferences.setTrashAutoDeleteDays(days) }
    }

    /** 关闭某库的本地快速解锁（删除包裹密钥与开关）。 */
    fun disableQuickUnlock(vaultId: String) {
        viewModelScope.launch { vaultRepository.disableLocalUnlock(vaultId) }
    }

    /**
     * 设置页**启用**某库的快速解锁（消除入口死角）。
     *
     * 背景：此前启用入口**只有**库列表页横幅（`QuickUnlockBanner`），而横幅点
     * 「以后再说」会置位 `isQuickUnlockPromptDismissed` → 横幅永不再现，
     * 用户就此**彻底失去启用路径**（设置页对话框只能关不能开）。
     * 此处补上对称入口，复用与横幅完全相同的 enroll 流程。
     *
     * 设备无可用认证方式时静默返回（不弹无意义的认证框）；
     * 否则准备 ENCRYPT cipher 并通知 UI 弹 BiometricPrompt。
     */
    fun startQuickUnlockEnroll(vaultId: String) {
        viewModelScope.launch {
            val cipher = vaultRepository.prepareLocalEnroll() ?: return@launch
            _events.send(Event.PromptForEnroll(vaultId, cipher))
        }
    }

    /** BiometricPrompt 认证通过：用本次 cipher 包裹当前会话密钥并落盘。 */
    fun enrollWithCipher(vaultId: String, cipher: Cipher) {
        viewModelScope.launch { vaultRepository.enrollLocalUnlock(vaultId, cipher) }
    }

    // ---- KDBX 快速解锁（`.ai/ISSUES.md` #93 / 定稿 §4）----

    /**
     * 用户勾选了一个 **KDBX** 库的快速解锁：先要主密码。
     *
     * 为什么不能像 Bitwarden 那样「勾了就去弹指纹」（定稿 §4.5）：
     * KDBX 会话里**没有可包裹的密钥** —— `KdbxSession` 不持主密码，
     * `Kdbx.unlock()` 用完即弃。所以唯一的包裹物是「主密码 + keyfile」，
     * 而这个主密码只存在于用户脑子里 ⇒ **必须当场再问一次**。
     *
     * 这里只负责「打开输入框」；真正的校验与包裹在 [confirmKdbxPassword]。
     */
    fun startKdbxQuickUnlock(vaultId: String) {
        _events.trySend(Event.PromptForKdbxPassword(vaultId))
    }

    /**
     * 用户提交 KDBX 主密码：**先校验、后包裹**。
     *
     * 「宽松」取向（定稿 §4.4）的落地：输错时**不发关闭事件**，只回一条错误状态，
     * 输入框留在原地让用户直接重输 —— 不掉出流程、不用重新点一遍勾选。
     *
     * 校验成功后才创建 ENCRYPT cipher 并请 UI 弹 BiometricPrompt（与 Bitwarden
     * 侧同一条收尾路径）；用户取消指纹时不会留下半成品（cipher 未用即弃）。
     */
    fun confirmKdbxPassword(vaultId: String, password: String) {
        viewModelScope.launch {
            val keyFileUri = runCatching { preferences.kdbxKeyFileUri(vaultId).first() }.getOrNull()
            val cipher = vaultRepository.prepareLocalEnroll()
                ?: return@launch _kdbxEnrollState.emit(KdbxEnrollState.Unavailable)
            when (val outcome = vaultRepository.enrollLocalUnlockKdbx(vaultId, password, keyFileUri, cipher)) {
                is KdbxEnrollOutcome.Enrolled -> {
                    _kdbxEnrollState.emit(KdbxEnrollState.Ready)
                    _events.send(Event.PromptForEnroll(vaultId, cipher))
                }
                is KdbxEnrollOutcome.InvalidCredentials ->
                    // ★ 宽松：只报错，不关框
                    _kdbxEnrollState.emit(KdbxEnrollState.WrongPassword)
                is KdbxEnrollOutcome.SourceUnavailable ->
                    _kdbxEnrollState.emit(KdbxEnrollState.Failed(outcome.detail))
                is KdbxEnrollOutcome.Failed ->
                    _kdbxEnrollState.emit(KdbxEnrollState.Failed(outcome.detail))
            }
        }
    }

    /** 用户取消 KDBX 主密码输入框。 */
    fun dismissKdbxPassword() {
        _kdbxEnrollState.value = KdbxEnrollState.Idle
    }

    /** KDBX 主密码输入对话框的状态（UI 据此显示错误 / 收起）。 */
    sealed interface KdbxEnrollState {
        data object Idle : KdbxEnrollState

        /** 校验通过、已请 UI 弹指纹。 */
        data object Ready : KdbxEnrollState

        /** 主密码不对 —— 输入框保留，就地重输。 */
        data object WrongPassword : KdbxEnrollState

        /** 无可用认证方式。 */
        data object Unavailable : KdbxEnrollState

        /** 其它失败（文件读不到等）。 */
        data class Failed(val detail: String) : KdbxEnrollState
    }

    private val _kdbxEnrollState = MutableStateFlow<KdbxEnrollState>(KdbxEnrollState.Idle)
    val kdbxEnrollState: StateFlow<KdbxEnrollState> = _kdbxEnrollState.asStateFlow()

    sealed interface Event {
        /** UI 收到后弹 BiometricPrompt（cipher 已 init，等待用户认证）。 */
        data class PromptForEnroll(val vaultId: String, val cipher: Cipher) : Event

        /** UI 收到后弹 KDBX 主密码输入框（KDBX 必须当场要密码，定稿 §4.5）。 */
        data class PromptForKdbxPassword(val vaultId: String) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** 立即锁定全部库：AutoLockController 会自增锁定代次，导航壳自动回库列表。 */
    fun lockAllNow() = autoLockController.lockAllNow()

    /**
     * **退出数据库**（取代原来的「立即锁定」）。
     *
     * 用户原话：「设置里的『立即锁定』应该改成**退出数据库**（清本地缓存，不动远程）」。
     *
     * 与 [lockAllNow] 的差别：
     * - [lockAllNow] 只清内存密钥（真锁）→ 下次解锁要重输主密码，但离线缓存还在；
     * - 本方法连**本地缓存**一起清（快速解锁凭据 / token / 待推送队列 / 密文条目与文件夹
     *   / 同步基线），保留库行 —— 下次点一下重新登录即可，条目从服务端重新拉。
     *
     * ⚠️ 只清本地，**不碰远程**；但**本地未上传的改动会丢**（待推送队列属缓存），
     * 所以 UI 必须先弹确认对话框（[SettingsScreen] 的 `ExitDatabaseDialog`）。
     *
     * 清完之后库里已无任何已解锁会话 → `RootNavState` 自动收敛到解锁页，
     * 不需要额外导航（对齐 `lockAllNow` 的现有做法）。
     */
    fun exitDatabase() {
        viewModelScope.launch {
            val ids = vaultRepository.observeVaults().first().map { it.id }
            ids.forEach { vaultId ->
                runCatching { vaultRepository.signOut(vaultId) }
            }
            // 查看层标记一并清：密钥已经没了，标记留着只会让根导航停在一个
            // 「只有生物识别按钮、但密钥不在内存」的死角。
            runCatching { sessionRepository.clearAllViewLocks() }
        }
    }

    companion object {
        /** 剪贴板清除候选（ms）：0 = 关闭。默认 30s 与详情页一致。 */
        val CLIPBOARD_PRESETS_MS: List<Long> = listOf(0L, 10_000L, 30_000L, 60_000L, 300_000L)
    }
}
