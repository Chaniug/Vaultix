package io.vaultix.vaultix.ui.passkeys

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultFido2Credential
import io.vaultix.vaultix.autofill.AutofillLogger
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * 通行密钥列表（从验证码界面的「通行密钥」按钮进入）。
 *
 * 数据来源：拉平所有 [VaultItemType.Login] 条目的 [VaultItem.fido2Credentials]，
 * 每个凭证成为一行（标注其绑定的密码条目）。
 *
 * 对齐 Bitwarden / Keyguard：通行密钥**永远绑定到密码条目**，不存在独立的通行密钥条目。
 * 因此：
 * - 删除 = 从所属登录条目的 fido2 列表中移除该 credentialId（本地视图收敛；
 *   服务端另存的密钥材料下次同步可能重新下发，与官方行为一致）；
 * - 新增 = 在所选登录条目上追加一个 fido2 凭证（保存流程即「绑定到密码条目」）。
 *
 * 通行密钥只读：详情界面仅可查看与删除，不可编辑（密钥由服务器 / 平台管理）。
 */
@HiltViewModel
class PasskeysViewModel @Inject constructor(
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
        /**
         * 库的服务器地址（站点图标的取值前缀；KDBX 本地库为 null）。
         *
         * 2026-09-15 加：列表行改用 [io.vaultix.vaultix.ui.common.SiteIconByHost] 后，
         * 需要它才能拼出 `<服务器>/icons/<域名>/icon.png` —— 与验证码页同口径
         * （`TotpCodesViewModel.UiState.serverOrigin`）。
         */
        val serverOrigin: String? = null,
        /**
         * 活跃库**是否已解锁**（`null` = 库信息还没到）。
         *
         * 与密码 / 验证码页同一类坑：库里凭证全在、只是密文读不出来时 `items` 同样是空的。
         * 若不区分，界面会显示「还没有通行密钥」——**假状态**。
         */
        val unlocked: Boolean? = null,
        /**
         * 条目流是否还没发首帧。
         *
         * ⚠️ 与 [unlocked] 管的是两件事：本页的库 id 来自路由参数（固定），
         * 所以不存在验证码页那种"id 还没解析出来"的中间态，但**首帧**仍然存在 ——
         * 首帧期间 `items` 也是空的，同样不能说「还没有通行密钥」。
         */
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                val vault = vaults.firstOrNull { v -> v.id == vaultId }
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
            itemRepository.observeItems(vaultId).collect { items ->
                // 诊断（仅数量，不含任何字段值）：区分「服务端没数据」与「解析/解密失败」
                AutofillLogger.d(
                    "passkeys loaded items=${items.size} " +
                        "withFido2=${items.count { it.fido2Credentials.isNotEmpty() }}",
                )
                _state.update { it.copy(items = items, loading = false) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** 供「保存到哪个密码条目」选择器使用的登录条目。 */
    fun loginCandidates(): List<VaultItem> =
        _state.value.items.filter { it.type == VaultItemType.Login }

    fun deleteCredential(row: PasskeyRow) {
        viewModelScope.launch {
            itemRepository.removeFido2Credential(vaultId, row.itemId, row.credential.credentialId)
        }
    }

    /**
     * 保存（绑定）一个通行密钥到所选登录条目。
     * 在已有 fido2 列表上追加，整体写回（保证不丢其它凭证）。
     */
    fun savePasskey(loginId: String, credential: VaultFido2Credential) {
        val login = _state.value.items.firstOrNull { it.id == loginId } ?: return
        viewModelScope.launch {
            val merged = login.fido2Credentials + credential
            itemRepository.updateFido2Credentials(vaultId, loginId, merged)
        }
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"
    }
}

/** 通行密钥列表的一行（标注其绑定的密码条目）。 */
data class PasskeyRow(
    val itemId: String,
    val loginTitle: String,
    val credential: VaultFido2Credential,
) {
    /**
     * 稳定唯一键（`条目 id : 凭证 id`）。
     *
     * 抽出来给 `LazyColumn(key = ...)` 与多选集合共用：同一凭证的字符串只在一处拼，
     * 不会出现「列表用 A 拼、多选集合用 B 拼」导致勾选对不上的漂移
     * （验证码页是就地拼的，本页因为多了多选状态，值得收成一个属性）。
     */
    val key: String get() = "$itemId:${credential.credentialId}"

    /** 是否命中搜索词（rpId / rpName / userName 任一，忽略大小写）。 */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return credential.rpId.lowercase().contains(q) ||
            credential.rpName.lowercase().contains(q) ||
            credential.userName.lowercase().contains(q)
    }
}

/**
 * 拉平所有登录条目的 [VaultItem.fido2Credentials]，每个凭证成为一行。
 *
 * ⚠️ 做成**纯函数**（不读 `_state.value`）是必须的：界面侧要用 `remember(state.items, ...)`
 * 绑定到**已收集的 Compose State 快照**。此前这里是一份读裸 StateFlow 的
 * `PasskeysViewModel.passkeyRows()`，**不感知快照** ⇒ 首帧数据未到时算出空列表，
 * 且不等下一次重组，界面就停在空态「还没有通行密钥」，**必须点一下搜索**
 * （改变 `searchActive` 强制重组）条目才出现 —— 2026-09-15 用户真机报的正是这一条。
 *
 * 与验证码页 `rememberTotpEntries` 是**同一个坑**，那边早已修过并留了注释
 * （`TotpCodesScreen.kt`「不感知快照」）。函数式调用不会订阅 Flow：
 * **凡「界面要用的派生数据」，一律从已收集的 state 快照算，不要回 ViewModel 读 `_state.value`。**
 */
fun List<VaultItem>.toPasskeyRows(): List<PasskeyRow> = flatMap { item ->
    item.fido2Credentials.map { cred ->
        PasskeyRow(itemId = item.id, loginTitle = item.title, credential = cred)
    }
}

/** 构造一个待保存的通行密钥（默认值对齐 Bitwarden；时间戳不加密）。 */
fun newPasskeyCredential(
    credentialId: String,
    rpId: String,
    rpName: String,
    userName: String,
    userDisplayName: String,
    keyValue: String = "",
): VaultFido2Credential = VaultFido2Credential(
    credentialId = credentialId,
    rpId = rpId,
    rpName = rpName,
    userName = userName,
    userDisplayName = userDisplayName,
    keyValue = keyValue.ifBlank { null },
    creationDate = Instant.now().toString(),
)
