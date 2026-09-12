package io.vaultix.vaultix.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultFolder
import io.vaultix.vaultix.util.VaultixClipboard
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 条目详情（Docs/08 S9 最小版）。
 *
 * - 展示与复制走 Room 明文流（[ItemRepository.observeItem]），本地编辑后自动刷新；
 * - 复制经 [VaultixClipboard]（敏感标记 + 按偏好自动清空，UI 提示剩余秒数）；
 * - 编辑 = [ItemRepository.updateItem]；删除 = 软删除（回收站语义）。
 */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itemRepository: ItemRepository,
    private val vaultRepository: VaultRepository,
    private val prefs: VaultixPreferences,
    private val clipboard: VaultixClipboard,
    private val folderRepository: FolderRepository,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle[ARG_VAULT_ID])
    val itemId: String = checkNotNull(savedStateHandle[ARG_ITEM_ID])

    /** 一次性 UI 事件。 */
    sealed interface UiEvent {
        data class CopyDone(
            val isPassword: Boolean,
            val clearSeconds: Long,
            val isUri: Boolean = false,
            val isTotp: Boolean = false,
            /** 普通字段（卡号/SSH 密钥等）复制，文案走通用「已复制」。 */
            val isField: Boolean = false,
        ) : UiEvent
        data object CopyFailed : UiEvent
        data object SaveSynced : UiEvent
        data object SaveQueued : UiEvent
        data class SaveFailed(val message: String) : UiEvent
        data object Deleted : UiEvent
    }

    data class UiState(
        val item: VaultItem? = null,
        val vaultName: String = "",
        /**
         * 所属库的服务器地址。
         *
         * 供详情页头部的**站点图标**用：图标端点形如 `<服务器>/icons/<域名>/icon.png`，
         * 缺了它就只能退化成首字母头像（列表页一直有，详情页此前没有 ⇒ 同一个条目
         * 在列表里有真图标、点进去变成字母，观感割裂）。
         */
        val serverOrigin: String? = null,
        val saving: Boolean = false,
        val deleting: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 该库的文件夹列表（未解锁 / 尚无文件夹时为空，UI 据此隐藏下拉）。 */
    val folders: StateFlow<List<VaultFolder>> = folderRepository
        .observeFolders(vaultId)
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    @Volatile
    private var clipboardClearMs: Long = DEFAULT_CLIPBOARD_CLEAR_MS

    init {
        viewModelScope.launch {
            itemRepository.observeItem(vaultId, itemId).collect { item ->
                _state.update { it.copy(item = item) }
            }
        }
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                val vault = vaults.firstOrNull { it.id == vaultId }
                _state.update {
                    it.copy(vaultName = vault?.name.orEmpty(), serverOrigin = vault?.origin)
                }
            }
        }
        viewModelScope.launch {
            prefs.clipboardClearMs.collect { ms -> clipboardClearMs = ms }
        }
    }

    fun copyUsername() {
        val item = _state.value.item ?: return
        if (item.username.isBlank()) return
        doCopy(item.username, isPassword = false)
    }

    fun copyPassword() {
        val item = _state.value.item ?: return
        if (item.password.isBlank()) return
        doCopy(item.password, isPassword = true)
    }

    fun copyUri(uri: String) {
        if (uri.isBlank()) return
        doCopy(uri, isUri = true)
    }

    fun copyTotp(code: String) {
        if (code.isBlank()) return
        doCopy(code, isTotp = true)
    }

    /** 复制普通字段（卡号 / 卡面信息 / SSH 私钥·公钥·指纹等）。 */
    fun copyField(text: String) {
        if (text.isBlank()) return
        doCopy(text, isField = true)
    }

    private fun doCopy(
        text: String,
        isPassword: Boolean = false,
        isUri: Boolean = false,
        isTotp: Boolean = false,
        isField: Boolean = false,
    ) {
        runCatching {
            clipboard.copy(text = text, sensitive = true, autoClearMs = clipboardClearMs)
        }.onSuccess {
            _events.trySend(
                UiEvent.CopyDone(
                    isPassword = isPassword,
                    clearSeconds = if (clipboardClearMs > 0) clipboardClearMs / 1000 else 0,
                    isUri = isUri,
                    isTotp = isTotp,
                    isField = isField,
                ),
            )
        }.onFailure {
            _events.trySend(UiEvent.CopyFailed)
        }
    }

    /**
     * 保存编辑结果。
     *
     * 入参是表单回传的**完整条目快照**（[VaultItem] copy），因此类型专属段
     * （card / identity / sshKey / customFields / fido2Credentials）由表单决定：
     * 已编辑的段按明文重新加密上传，未编辑的段沿用服务端原密文（data 层
     * [io.vaultix.data.bitwarden.mapper.CipherMapper.toUpdateRequest] 保证）。
     */
    fun updateItem(item: VaultItem) {
        val current = _state.value
        if (current.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = itemRepository.updateItem(vaultId = vaultId, item = item)
            _state.update { it.copy(saving = false) }
            _events.send(
                outcome.fold(
                    onSuccess = { saved ->
                        when (saved) {
                            VaultSaveOutcome.Synced -> UiEvent.SaveSynced
                            VaultSaveOutcome.Queued -> UiEvent.SaveQueued
                        }
                    },
                    onFailure = { error -> UiEvent.SaveFailed(error.message ?: "未知错误") },
                ),
            )
        }
    }

    fun deleteItem() {
        val current = _state.value
        if (current.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val outcome = itemRepository.softDeleteItem(vaultId, itemId)
            _state.update { it.copy(deleting = false) }
            outcome.fold(
                onSuccess = { _events.send(UiEvent.Deleted) },
                onFailure = { error ->
                    _events.send(UiEvent.SaveFailed(error.message ?: "未知错误"))
                },
            )
        }
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"
        const val ARG_ITEM_ID = "itemId"
        const val DEFAULT_CLIPBOARD_CLEAR_MS = 30_000L
    }
}
