package io.vaultix.vaultix.ui.totp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.common.OtpUriParser
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - 绑定：把独立 TOTP 合并进所选密码条目的 `totp`，并删除独立项。
 */
@HiltViewModel
class TotpCodesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle[ARG_VAULT_ID])

    data class UiState(
        val vaultName: String = "",
        val items: List<VaultItem> = emptyList(),
        val query: String = "",
        val saving: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                _state.update { it.copy(vaultName = vaults.firstOrNull { v -> v.id == vaultId }?.name ?: "") }
            }
        }
        viewModelScope.launch {
            itemRepository.observeItems(vaultId).collect { items ->
                _state.update { it.copy(items = items) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** 当前所有含 TOTP 的条目，按搜索过滤（issuer/account/标题）。 */
    fun filteredEntries(): List<TotpEntry> {
        val q = _state.value.query.trim().lowercase()
        return _state.value.items
            .mapNotNull { it.toTotpEntry() }
            .filter { e ->
                q.isEmpty() ||
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
    fun saveTotp(
        entryId: String?,
        issuer: String,
        account: String,
        secret: String,
        period: Int,
        digits: Int,
        algorithm: String,
        steam: Boolean,
    ) {
        val raw = OtpUriParser.buildOtpAuthUri(
            secret = secret.trim(),
            issuer = issuer.trim(),
            account = account.trim(),
            period = period,
            digits = digits,
            algorithm = algorithm,
            steam = steam,
        )
        val title = issuer.takeIf { it.isNotBlank() }
            ?: account.takeIf { it.isNotBlank() }
            ?: "验证码"
        viewModelScope.launch {
            if (entryId == null) {
                // 新增独立验证码：password 为空的登录条目（Bitwarden 兼容形态）
                itemRepository.createItem(
                    vaultId = vaultId,
                    item = VaultItem(
                        id = "",
                        title = title,
                        username = "",
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
    }
}

/** VaultItem → 验证码条目（无 TOTP 返回 null）。 */
fun VaultItem.toTotpEntry(): TotpEntry? {
    val raw = totp ?: return null
    val parsed = OtpUriParser.parseToDisplay(raw) ?: return null
    val bound = username.isNotBlank() || password.isNotBlank()
    return TotpEntry(
        itemId = id,
        title = title,
        issuer = parsed.issuer,
        account = parsed.account,
        label = parsed.label,
        totpRaw = raw,
        secret = parsed.secret,
        period = parsed.period,
        digits = parsed.digits,
        algorithm = parsed.algorithm,
        steam = parsed.steam,
        bound = bound,
        boundLoginTitle = if (bound) title else null,
    )
}

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
    val steam: Boolean,
    val bound: Boolean,
    val boundLoginTitle: String?,
) {
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
            steam = false,
            bound = false,
            boundLoginTitle = null,
        )
    }
}
