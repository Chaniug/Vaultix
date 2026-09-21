package io.vaultix.vaultix.ui.totp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.common.ImportedOtp
import io.vaultix.common.OtpImportParser
import io.vaultix.common.OtpScanResult
import io.vaultix.common.OtpType
import io.vaultix.common.OtpUriParser
import io.vaultix.common.TotpConfig
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.session.ActiveVaultStore
import io.vaultix.vaultix.util.VaultixClipboard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 验证码统一界面（对齐 Bitwarden 的 TOTP 总览 + Bastion 的独立验证器视图）。
 *
 * 数据来源：所有 [VaultItemType.Login] 中 `totp` 非空的条目，分两种：
 * - **已绑定**：该登录条目同时有用户名/密码（TOTP 随密码条目一起保存）；
 * - **独立**：`password` 为空的登录条目（仅 `login.totp` 有值），即 Bastion 的
 *   独立验证器形态——这是 Bitwarden 官方兼容的存储方式，避免引入额外条目类型导致
 *   跨客户端数据不匹配。
 *
 * 操作（均按 Bitwarden 字段语义）：
 * - 编辑：重写该登录条目的 `login.totp`（独立项同时更新标题）；
 * - 删除：独立项整体软删除；已绑定项仅清空其 `totp`，不动密码；
 * - 绑定：把独立 TOTP 合并进所选密码条目的 `totp`，并删除独立项；
 * - 导入：支持 otpauth / motp / 裸密钥（单条，预填编辑确认）与
 *   otpauth-migration:// 批量导出（多条，直接创建）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TotpCodesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
    private val clipboard: VaultixClipboard,
    private val preferences: VaultixPreferences,
    private val activeVaultStore: ActiveVaultStore,
) : ViewModel() {

    /**
     * 路由显式携带的库 id（二级直达场景）。
     *
     * 有值 → 本页固定在该库；为 null（主界面 Tab 内嵌）→ 跟随 [ActiveVaultStore]
     * （main-shell-migration 阶段 2，A4「vaultId 参数 → 筛选状态」降级）。
     */
    private val routedVaultId: String? = savedStateHandle[ARG_VAULT_ID]

    /**
     * 库 id 源：路由参数优先且固定；无参数时跟随 [ActiveVaultStore]
     * ——**切换活跃库后本页内容自动跟着变**（与 `ItemsViewModel` 同口径）。
     */
    private val vaultIdSource: Flow<String> = routedVaultId
        ?.let { id -> flowOf(id) }
        ?: activeVaultStore.activeVaultId.map { it.orEmpty() }

    private val vaultIdState: StateFlow<String> = vaultIdSource.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = routedVaultId ?: activeVaultStore.current() ?: "",
    )

    /** 当前库（供一次性动作读取：保存 / 删除 / 绑定 / 导入）。 */
    val vaultId: String get() = vaultIdState.value

    data class UiState(
        val vaultName: String = "",
        val items: List<VaultItem> = emptyList(),
        val query: String = "",
        val saving: Boolean = false,
        /** 库的服务器地址（图标端点基址；见 [io.vaultix.common.SiteIconUrl]）。 */
        val serverOrigin: String? = null,
        /** 条目 id → 是否已同步上云（行尾云图标；缺省视为已同步，见 `.ai/ISSUES.md` #76）。 */
        val syncStates: Map<String, Boolean> = emptyMap(),
        /**
         * 条目流**还没发首帧**（活跃库 id 尚未解析出 / 首次读盘未回）。
         *
         * ⚠️ 必须与「真的没有验证码」分开（2026-09-13 用户报「冷启动瞬间点验证码页，
         * 有 1~2 秒空白，像是数据没加载好」）：
         * `vaultIdState` 初始是 `""`，而 `observeItems("")` 会**立刻发一个空列表** ——
         * 界面把那个空列表当成真实空态，于是显示「还没有验证码」达 1~2 秒。这既不是加载提示，
         * 也不是真实状态，用户只能读成"坏了/空白"。
         */
        val loading: Boolean = true,
        /**
         * 活跃库**是否已解锁**（`null` = 库信息还没到）。
         *
         * ⚠️ 2026-09-15 加：库里条目全在、只是密文读不出来时，`items` 同样是空的。
         * 若不区分，界面会显示「还没有验证码」——与密码页的「还没有保存的密码」是
         * **同一个假状态**（用户报的「切到未解锁的 KDBX 后两页都空白」）。
         * 注意 [loading] 管不了这件事：它只表示"流还没发首帧"，而锁定库的流**已经发了**
         * 一个（真实的）空列表。
         */
        val unlocked: Boolean? = null,
        /**
         * 验证码页是否隐藏数字（2026-09-21：点顶栏标题切换，**跨重启保持**）。
         *
         * ⚠️ 只影响**渲染**：复制与自动填充一律用原始码（[io.vaultix.common.TotpGenerator.mask]
         * 的 KDoc 写明了这条纪律）。
         */
        val codesHidden: Boolean = false,
        /** 临期（剩余 ≤ `TOTP_HOT_WARNING_SECONDS`）时复制**下一个**码。默认开。 */
        val copyNextOnExpiring: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // 两个**展示偏好**（隐藏数字 / 临期换码）由偏好层单向下发到 UI 状态。
        // ⚠️ 集中在这里收，而不是让界面各处自己读偏好：那会出现"同一开关两个读法"的漂移
        // （本项目已有过先例：开关说开着、实际没生效）。
        viewModelScope.launch {
            combine(
                preferences.totpCodesHidden,
                preferences.totpCopyNextOnExpiring,
            ) { hidden, copyNextOnExpiring ->
                hidden to copyNextOnExpiring
            }.collect { (hidden, copyNextOnExpiring) ->
                _state.update {
                    it.copy(codesHidden = hidden, copyNextOnExpiring = copyNextOnExpiring)
                }
            }
        }
        // 与 ItemsViewModel 一致：由路由参数进入时把该库登记为活跃库
        routedVaultId?.let(activeVaultStore::select)
        if (routedVaultId == null) {
            // Tab 内嵌模式：冷启动 / 锁屏恢复后 `activeVaultId` 可能仍是 null（其 init
            // 协程异步填充），导致 `vaultIdState` 初始为 ""、`observeItems("")` 返回空，
            // 验证码页呈现「有密码条目但验证码列表空白」。这里同步权威解析一次，把
            // 真实 vaultId 写回 activeVaultId，从而触发后续数据加载。
            viewModelScope.launch { activeVaultStore.resolve() }
        }
        viewModelScope.launch {
            combine(vaultIdState, vaultRepository.observeVaults()) { id, vaults ->
                vaults.firstOrNull { v -> v.id == id }
            }.collect { vault ->
                _state.update {
                    it.copy(
                        vaultName = vault?.name.orEmpty(),
                        serverOrigin = vault?.origin,
                        unlocked = vault?.unlocked,
                    )
                }
            }
        }
        viewModelScope.launch {
            vaultIdState
                .flatMapLatest { id ->
                    // ⚠️ 活跃库 id 还没解析出来时**不要**去 observeItems("")：那会立刻发一个
                    // 空列表，被界面当成"真的没有验证码"（冷启动切过来就是 1~2 秒假空态）。
                    // 这里改发 `null`，由下游把它翻译成 `loading = true`。
                    if (id.isBlank()) {
                        flowOf<List<VaultItem>?>(null)
                    } else {
                        itemRepository.observeItems(id).map<List<VaultItem>, List<VaultItem>?> { it }
                    }
                }
                .collect { items ->
                    _state.update {
                        it.copy(items = items.orEmpty(), loading = items == null)
                    }
                }
        }
        // 云同步状态 → 行尾小云图标（与 ItemsViewModel 同口径）。
        viewModelScope.launch {
            vaultIdState
                .flatMapLatest { id -> itemRepository.observeSyncStates(id) }
                .collect { states -> _state.update { it.copy(syncStates = states) } }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /**
     * 切换验证码数字的隐藏。
     *
     * ⚠️ 必须**写偏好**（不是只改内存状态）：用户要求「不再点击，重开 App 也保持隐藏」。
     * 写回后由 init 里的偏好收集器把新值推回 [UiState]，形成单向回流，避免两处各持一份状态。
     */
    fun setCodesHidden(hidden: Boolean) {
        viewModelScope.launch { preferences.setTotpCodesHidden(hidden) }
    }

    /** 临期（剩余 ≤ 警示阈值）时是否复制**下一个**码。 */
    fun setCopyNextOnExpiring(enabled: Boolean) {
        viewModelScope.launch { preferences.setTotpCopyNextOnExpiring(enabled) }
    }

    /**
     * 手动刷新：重新权威解析活跃库 id 并触发数据流重新订阅。
     *
     * 用于 Tab 内嵌模式在「锁屏恢复 / 同步完成 / 从后台切回」后 `activeVaultId` 可能
     * 仍是 null 导致验证码页空白的场景。
     */
    fun refresh() {
        viewModelScope.launch {
            if (routedVaultId == null) activeVaultStore.resolve()
        }
    }

    /**
     * 复制验证码：走 [VaultixClipboard]（安全剪贴板）。
     *
     * 此前界面直接 `LocalClipboardManager.setText`——既没有 API 33+ 的 `IS_SENSITIVE`
     * 标记（系统剪贴板预览/输入法会明文显示验证码），也不遵守 `clipboardClearMs` 自动清除。
     */
    fun copyCode(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            clipboard.copy(
                text = code,
                label = CLIPBOARD_LABEL,
                autoClearMs = preferences.clipboardClearMs.first(),
            )
        }
    }

    /**
     * 当前所有含 TOTP 的条目，按搜索过滤（issuer/account/标题）。
     *
     * ⚠️ 仅供需要直接读 StateFlow 的少数场景使用；界面应改用基于已收集 `state` 快照的
     * 派生（见 [TotpCodesScreen]），否则不感知 Compose 快照，Tab 切换时偶发不刷新。
     */
    fun filteredEntries(): List<TotpEntry> =
        _state.value.items.toTotpEntries().filter { it.matches(_state.value.query) }

    /** 供「绑定到密码条目」选择器使用的登录条目（排除自身）。 */
    fun loginCandidates(excludeItemId: String): List<VaultItem> =
        _state.value.items.filter { it.type == VaultItemType.Login && it.id != excludeItemId }

    fun deleteTotp(entry: TotpEntry) {
        val item = _state.value.items.firstOrNull { it.id == entry.itemId } ?: return
        viewModelScope.launch {
            if (entry.bound) {
                // 已绑定：仅清空该登录条目的 TOTP，保留密码/用户名
                itemRepository.updateItem(vaultId, item.copy(totp = null))
            } else {
                itemRepository.softDeleteItem(vaultId, entry.itemId)
            }
        }
    }

    /**
     * 保存（新增或编辑）验证码条目。
     * @param entryId null = 新增独立条目；非 null = 编辑既有条目（按 id 取回原条目重写）。
     */
    fun saveTotp(entryId: String?, issuer: String, account: String, config: TotpConfig) {
        val trimmedIssuer = issuer.trim()
        val trimmedAccount = account.trim()
        val raw = OtpUriParser.buildUri(config, issuer = trimmedIssuer, account = trimmedAccount)
        val title = trimmedIssuer.takeIf { it.isNotBlank() }
            ?: trimmedAccount.takeIf { it.isNotBlank() }
            ?: FALLBACK_TITLE
        viewModelScope.launch {
            if (entryId == null) {
                // 新增独立验证码：password 为空的登录条目（Bitwarden 兼容形态）
                itemRepository.createItem(
                    vaultId = vaultId,
                    item = VaultItem(
                        id = "",
                        title = title,
                        username = trimmedAccount,
                        password = "",
                        type = VaultItemType.Login,
                        totp = raw,
                    ),
                )
            } else {
                val item = _state.value.items.firstOrNull { it.id == entryId } ?: return@launch
                itemRepository.updateItem(vaultId, item.copy(title = title, totp = raw))
            }
        }
    }

    /**
     * 粘贴内容导入。单条返回预填条目（UI 打开编辑对话框供确认）；
     * 批量直接创建并在刷新后出现在列表中；其余返回失败态供 UI 提示。
     */
    fun importTotp(raw: String): ImportOutcome {
        return when (val result = OtpImportParser.parse(raw)) {
            is OtpScanResult.Single -> ImportOutcome.Single(result.item.toStandaloneEntry())
            is OtpScanResult.Multiple -> {
                val items = result.items
                viewModelScope.launch {
                    items.forEach { imported ->
                        itemRepository.createItem(vaultId = vaultId, item = imported.toStandaloneItem())
                    }
                }
                ImportOutcome.Multiple(count = items.size)
            }
            OtpScanResult.UnsupportedPhoneFactor -> ImportOutcome.Unsupported
            OtpScanResult.InvalidFormat -> ImportOutcome.Invalid
        }
    }

    /** 把独立验证码合并进所选登录条目，并删除独立条目。 */
    fun bindStandaloneToLogin(entry: TotpEntry, loginId: String) {
        val standalone = _state.value.items.firstOrNull { it.id == entry.itemId } ?: return
        val login = _state.value.items.firstOrNull { it.id == loginId } ?: return
        viewModelScope.launch {
            itemRepository.updateItem(vaultId, login.copy(totp = standalone.totp))
            itemRepository.softDeleteItem(vaultId, standalone.id)
        }
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"
        private const val FALLBACK_TITLE = "验证码"
    }
}

/** 粘贴导入的结果（UI 据此决定预填编辑 / 提示批量完成 / 报错）。 */
sealed interface ImportOutcome {
    /** 单条：预填后的编辑条目（未落库，保存走 [TotpCodesViewModel.saveTotp]）。 */
    data class Single(val entry: TotpEntry) : ImportOutcome

    /** 批量：已直接创建 count 条。 */
    data class Multiple(val count: Int) : ImportOutcome

    /** Microsoft Authenticator 导出（phonefactor://）暂不支持。 */
    data object Unsupported : ImportOutcome

    /** 内容无法识别。 */
    data object Invalid : ImportOutcome
}

/** VaultItem → 验证码条目（无 TOTP 返回 null）。 */
fun VaultItem.toTotpEntry(): TotpEntry? {
    val raw = totp ?: return null
    val parsed = OtpUriParser.parseToDisplay(raw) ?: return null
    val bound = username.isNotBlank() || password.isNotBlank()
    // 对齐 Bastion TotpDataResolver.fromAuthenticatorKey：otpauth 解析出的 issuer/account
    // 为空时，回退到密码条目的名称（标题）与用户名（账号）。Bitwarden 的 login.totp
    // 常以裸 base32 密钥存储（无 issuer/account），此时必须以条目名/用户名兜底，
    // 否则验证码界面只剩一串密钥前缀、看不到「这是哪个网站的验证码」。
    val displayTitle = title.ifBlank { parsed.issuer.ifBlank { parsed.label } }
    val displayAccount = username.ifBlank {
        parsed.account.ifBlank { if (parsed.issuer != displayTitle) parsed.issuer else "" }
    }
    return TotpEntry(
        itemId = id,
        title = displayTitle,
        issuer = parsed.issuer,
        account = displayAccount,
        label = parsed.label,
        totpRaw = raw,
        secret = parsed.secret,
        period = parsed.period,
        digits = parsed.digits,
        algorithm = parsed.algorithm,
        type = parsed.type,
        counter = parsed.counter,
        pin = parsed.pin,
        bound = bound,
        boundLoginTitle = if (bound) displayTitle else null,
        // 站点图标用：条目挂的网站域名（`androidapp://` 之类会被 hostOfItemUri 过滤掉）
        domain = io.vaultix.common.SiteIconUrl.hostOfItemUris(uris.map { it.uri }),
    )
}

/** 一批 VaultItem → 仅含 TOTP 的验证码条目（投影后丢弃无 TOTP 的）。 */
fun List<VaultItem>.toTotpEntries(): List<TotpEntry> = mapNotNull { it.toTotpEntry() }

/**
 * 验证码条目是否匹配搜索词（title/issuer/account/label，大小写不敏感、忽略首尾空白）。
 *
 * 抽成纯函数有两个目的：
 * 1. 让 [TotpCodesScreen] 直接基于已 `collectAsStateWithLifecycle` 的 [TotpCodesViewModel.UiState]
 *    派生条目列表（读 `state.items` / `state.query` 两个 Compose State），而不是调
 *    `filteredEntries()` 去直接读 `_state.value`——后者不感知快照，Tab 切换 /
 *    `SaveableStateProvider` 恢复时偶发不刷新（用户反馈「新建含验证码的条目，验证码界面搜不到」）；
 * 2. 与界面解耦，便于单测。
 */
fun TotpEntry.matches(rawQuery: String): Boolean {
    val q = rawQuery.trim().lowercase()
    if (q.isEmpty()) return true
    return title.lowercase().contains(q) ||
        issuer.lowercase().contains(q) ||
        account.lowercase().contains(q) ||
        label.lowercase().contains(q)
}

/** 导入解析结果 → 预填编辑条目（未落库）。 */
private fun ImportedOtp.toStandaloneEntry(): TotpEntry = TotpEntry(
    itemId = "",
    title = issuer.ifBlank { account },
    issuer = issuer,
    account = account,
    label = issuer.ifBlank { account.ifBlank { config.secret.take(TITLE_SECRET_PREVIEW) } },
    totpRaw = "",
    secret = config.secret,
    period = config.period,
    digits = config.digits,
    algorithm = config.algorithm,
    type = config.type,
    counter = config.counter,
    pin = config.pin,
    bound = false,
    boundLoginTitle = null,
)

/** 导入解析结果 → 独立验证码条目（Bitwarden 兼容形态，直接落库）。 */
private fun ImportedOtp.toStandaloneItem(): VaultItem = VaultItem(
    id = "",
    title = issuer.ifBlank { account.ifBlank { FALLBACK_TITLE_VALUE } },
    username = account,
    password = "",
    type = VaultItemType.Login,
    totp = OtpUriParser.buildUri(config, issuer = issuer, account = account),
)

private const val TITLE_SECRET_PREVIEW = 8
private const val FALLBACK_TITLE_VALUE = "验证码"
private const val CLIPBOARD_LABEL = "Vaultix"

/**
 * 验证码界面的一行数据（已归一化，便于实时计算与展示）。
 */
data class TotpEntry(
    val itemId: String,
    val title: String,
    val issuer: String,
    val account: String,
    val label: String,
    val totpRaw: String,
    val secret: String,
    val period: Int,
    val digits: Int,
    val algorithm: String,
    val type: OtpType = OtpType.TOTP,
    val counter: Long = 0,
    val pin: String = "",
    val bound: Boolean,
    val boundLoginTitle: String?,
    /**
     * 站点域名（来自所属条目的网址），供站点图标使用；无网址 / 非网站绑定为 null。
     *
     * 与 [TotpCodesViewModel.UiState.serverOrigin]（库的服务器地址）一起拼出
     * `<服务器>/icons/<域名>/icon.png`，见 [io.vaultix.common.SiteIconUrl]。
     */
    val domain: String? = null,
) {
    /** 是否为 Steam Guard（type 为 STEAM；保留旧字段便于调用方逐步迁移）。 */
    val steam: Boolean get() = type == OtpType.STEAM

    /** 还原为可计算的配置（供 [TotpGenerator.generate] 统一入口）。 */
    fun toConfig(): TotpConfig = TotpConfig(
        secret = secret,
        period = period,
        digits = digits,
        algorithm = algorithm,
        type = type,
        counter = counter,
        pin = pin,
    )

    companion object {
        /** 空条目（用于「新增独立验证码」对话框的初始态）。 */
        fun empty() = TotpEntry(
            itemId = "",
            title = "",
            issuer = "",
            account = "",
            label = "",
            totpRaw = "",
            secret = "",
            period = 30,
            digits = 6,
            algorithm = "SHA1",
            bound = false,
            boundLoginTitle = null,
        )
    }
}
