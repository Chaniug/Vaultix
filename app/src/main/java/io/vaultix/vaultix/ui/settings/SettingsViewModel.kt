package io.vaultix.vaultix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.repository.LocalUnlockEnrollment
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.datastore.VaultixPreferencesDefaults
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.KdbxSyncRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.security.AutoLockController
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import io.vaultix.vaultix.ui.items.ItemsCardDisplayMode
import io.vaultix.vaultix.ui.items.ItemsGroupMode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /**
     * 快速解锁（生物识别）登记备料器。
     *
     * ⚠️ 注入具体实现而非 `VaultRepository` 的接口方法：多库备料入口故意**不在**
     * [VaultRepository] 上，否则 `VaultRepositoryImpl` 会突破 detekt `TooManyFunctions`
     * 的 40 上限（它本就顶格）。详见 [QuickUnlockController] 的 KDoc。
     */
    private val localUnlockEnrollment: LocalUnlockEnrollment,
    /**
     * KDBX 网盘同步（逐库「同步」动作要用）。
     *
     * ⚠️ 与 [vaultRepository] 分开注入：两者的仲裁语义根本不同
     * （服务端 revision 仲裁 vs 条件写 + 用户拍板），见 [VaultActionsController]。
     */
    private val kdbxSyncRepository: KdbxSyncRepository,
) : ViewModel() {
    data class UiState(
        val vaultTimeout: VaultTimeout = VaultTimeout.DEFAULT,
        val clipboardClearMs: Long = 30_000L,
        /**
         * 动态取色 / 防截屏。
         *
         * ⚠️ **可空，null = 偏好还没从磁盘读出来**（`.ai/ISSUES.md` #84「三种空」）。
         * 为什么不能给个默认布尔值顶上：这两个值驱动的是**开关**，而默认值
         * （true）只是「键不存在时的兜底」，不是用户的设置 —— 拿它渲染第一帧，
         * 用户会看到开关**先开后关**（2026-09-14 真机报告：进 App 后立刻点设置，
         * 防截屏从开启突然变成关闭）。「不确定」必须与「是/否」区分开。
         */
        val dynamicColor: Boolean? = null,
        val screenSecurity: Boolean? = null,
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

    /** 主题模式原始值（system / light / dark；UI 侧用 ThemeMode.from 解析显示与回传）。 */
    val themeMode: StateFlow<String> = preferences.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VaultixPreferencesDefaults.THEME_MODE,
        )

    // ---- 布尔型设置项：**一律是 `Boolean?`，初值 `null`** ----------------------
    //
    // 下面这一组（OLED 纯黑 / 各类开关）的 `Boolean?` 不是可选用法，是**契约**：
    //
    //   null  = 「偏好还没从磁盘读出来」（.ai/ISSUES.md #84「三种空」）
    //   true  = 明确的"开"
    //   false = 明确的"关"
    //
    // ⚠️ **不要把 `initialValue` 改回 `true`/`false`**。那等于替用户先答一次题：
    //   冷启动后快速进设置页，开关会从"假值"跳到"真值"，看上去像在闪烁
    //   （2026-09-16 真机报告，动态取色开关）。反方向（`?: true`）同样错，
    //   那正是 2026-09-14「防截屏先开后关」的成因。**两个方向都试过，都错**：
    //   问题不在默认值取什么，而在**不该在不知道的时候给答案**。
    //
    // 渲染侧由 `SettingsSwitch(value: Boolean?)` 接住：拿到 null 就用同尺寸占位撑住
    // 布局（且占位仍可点，避免点击被吞 —— 见该组件 KDoc）。
    // 因此**新加开关时初值一律写 `null`**，别写具体值。

    /** OLED 纯黑（深色模式 surface/background 纯黑）。 */
    val oledPureBlack: StateFlow<Boolean?> = preferences.oledPureBlack
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /** 回收站自动清理档位（天；0 = 不自动清空），与回收站页顶栏入口共用同一偏好。 */
    val trashAutoDeleteDays: StateFlow<Int> = preferences.trashAutoDeleteDays
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VaultixPreferencesDefaults.TRASH_AUTO_DELETE_DAYS,
        )

    /** 自动填充保存提示（登录成功后询问保存 / 更新凭据，默认开）。 */
    val autofillSavePrompt: StateFlow<Boolean?> = preferences.autofillSavePrompt
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun setAutofillSavePrompt(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutofillSavePrompt(enabled) }
    }

    /** 自动填充后自动复制验证码（条目带 TOTP 而页面没有验证码框时）。 */
    val autoCopyTotp: StateFlow<Boolean?> = preferences.autoCopyTotp
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun setAutoCopyTotp(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoCopyTotp(enabled) }
    }

    /**
     * 验证码**临期**（剩余 ≤ 警示阈值）时，复制的是**下一个**码（2026-09-21 新增开关）。
     *
     * ⚠️ 语义是「你点复制的那一刻给哪个码」，**不是**定时器自动写剪贴板
     * （参考实现 Bastion 也是如此：其 `codeToCopy` 只决定"复制哪一个"）。
     * 默认 `true` = 保持既有行为（该行为 2026-09-18 就在验证码页里了），
     * 加开关只为让用户能关掉，不静默改变已有观感。
     */
    val totpCopyNextOnExpiring: StateFlow<Boolean?> = preferences.totpCopyNextOnExpiring
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun setTotpCopyNextOnExpiring(enabled: Boolean) {
        viewModelScope.launch { preferences.setTotpCopyNextOnExpiring(enabled) }
    }

    /** 域匹配：允许基域 / 子域名命中（默认开，对齐 Bitwarden）。 */
    val autofillBaseDomainMatch: StateFlow<Boolean?> = preferences.autofillBaseDomainMatch
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun setAutofillBaseDomainMatch(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutofillBaseDomainMatch(enabled) }
    }

    /** 域匹配：仅精确域（默认关）。关掉「严格匹配」是浏览器填不出来时的首选排查动作。 */
    val autofillExactDomainOnly: StateFlow<Boolean?> = preferences.autofillExactDomainOnly
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
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
    val fillAssistEnabled: StateFlow<Boolean?> = preferences.fillAssistEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
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
    val itemsShowIcon: StateFlow<Boolean?> = preferences.itemsShowIcon
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
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

    // ---- 快速解锁（能力级：两个开关 + 统一生效范围）----

    /**
     * 「快速解锁」设置交互（2026-09-16 从「每库三选一」重构为**能力级**）。
     *
     * 本类是**唯一**的快速解锁设置入口：指纹与 PIN 两个**并列**开关 + 一份统一生效范围。
     *
     * ⚠️ 旧的单库入口（`startQuickUnlockEnroll` / `confirmKdbxPassword` / `enrollWithCipher`）
     * 与那两个控制器（`BiometricEnrollController` / `PinSettingsController`）**已删除**：
     * 它们各自演化出了重复实现（`VaultListViewModel` 里甚至复制了一份），
     * 收敛到一处才能避免"只修好一边"。
     */
    val quickUnlock: QuickUnlockController by lazy {
        QuickUnlockController(
            vaultRepository = vaultRepository,
            enrollment = localUnlockEnrollment,
            preferences = preferences,
            scope = viewModelScope,
        )
    }

    /** 立即锁定全部库：AutoLockController 会自增锁定代次，导航壳自动回库列表。 */
    fun lockAllNow() = autoLockController.lockAllNow()

    /**
     * **逐库动作**（锁定 / 同步 / 退出 / 移除）—— 2026-09-18 取代全局「退出数据库」。
     *
     * ★ 为什么删掉全局「退出数据库」（定稿 §11.10）：它是**全局**动作、**范围不可见**，
     * 误点一次用户就以为数据丢了。改成逐库之后，范围就写在被点的那一行上，
     * 不可能再误伤别的库；「退出 / 移除」也只在**有退出这回事**的库上出现。
     */
    val vaultActions: VaultActionsController by lazy {
        VaultActionsController(
            vaultRepository = vaultRepository,
            kdbxSyncRepository = kdbxSyncRepository,
            scope = viewModelScope,
        )
    }

    companion object {
        /** 剪贴板清除候选（ms）：0 = 关闭。默认 30s 与详情页一致。 */
        val CLIPBOARD_PRESETS_MS: List<Long> = listOf(0L, 10_000L, 30_000L, 60_000L, 300_000L)
    }
}
