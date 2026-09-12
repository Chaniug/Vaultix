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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // 与 ItemsViewModel 一致：由路由参数进入时把该库登记为活跃库
        routedVaultId?.let(activeVaultStore::select)
        viewModelScope.launch {
            combine(vaultIdState, vaultRepository.observeVaults()) { id, vaults ->
                vaults.firstOrNull { v -> v.id == id }?.name.orEmpty()
            }.collect { name -> _state.update { it.copy(vaultName = name) } }
        }
        viewModelScope.launch {
            vaultIdState
                .flatMapLatest { id -> itemRepository.observeItems(id) }
                .collect { items -> _state.update { it.copy(items = items) } }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

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

    /** 当前所有含 TOTP 的条目，按搜索过滤（issuer/account/标题）。 */
    fun filteredEntries(): List<TotpEntry> {
        val q = _state.value.query.trim().lowercase()
        return _state.value.items
            .mapNotNull { it.toTotpEntry() }
            .filter { e ->
                q.isEmpty() ||
                    e.title.lowercase().contains(q) ||
                    e.issuer.lowercase().contains(q) ||
                    e.account.lowercase().contains(q) ||
                    e.label.lowercase().contains(q)
            }
    }

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
    )
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
