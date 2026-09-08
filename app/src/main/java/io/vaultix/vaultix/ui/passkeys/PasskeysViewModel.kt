package io.vaultix.vaultix.ui.passkeys

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultFido2Credential
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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                val name = vaults.firstOrNull { v -> v.id == vaultId }?.name.orEmpty()
                _state.update { it.copy(vaultName = name) }
            }
        }
        viewModelScope.launch {
            itemRepository.observeItems(vaultId).collect { items ->
                _state.update { it.copy(items = items) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** 拉平所有登录条目的 fido2Credentials，按搜索过滤（rpId/rpName/userName）。 */
    fun passkeyRows(): List<PasskeyRow> {
        val q = _state.value.query.trim().lowercase()
        return _state.value.items.flatMap { item ->
            item.fido2Credentials.map { cred ->
                PasskeyRow(itemId = item.id, loginTitle = item.title, credential = cred)
            }
        }.filter { row ->
            q.isEmpty() ||
                row.credential.rpId.lowercase().contains(q) ||
                row.credential.rpName.lowercase().contains(q) ||
                row.credential.userName.lowercase().contains(q)
        }
    }

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
)

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
