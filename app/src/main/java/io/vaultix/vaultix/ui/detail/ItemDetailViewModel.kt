package io.vaultix.vaultix.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import androidx.core.content.FileProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.OrganizationRepository
import kotlinx.coroutines.flow.map
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultAttachment
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultFolder
import io.vaultix.vaultix.util.VaultixClipboard
import java.io.File
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
    /** 组织名（只读展示用，2026-10-21）：条目详情里标出"这条是共享库的"。 */
    private val organizationRepository: OrganizationRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle[ARG_VAULT_ID])
    val itemId: String = checkNotNull(savedStateHandle[ARG_ITEM_ID])

    /**
     * 组织 id → 组织名（未解锁 / 无组织时为空 map，2026-10-21）。
     *
     * ⚠️ **必须声明在 [vaultId] 之后**：Kotlin 属性按声明顺序初始化，
     * 放在前面会读到尚未赋值的 `vaultId`（null / 未初始化）。
     */
    val organizationNames: StateFlow<Map<String, String>> = organizationRepository
        .observeOrganizations(vaultId)
        .map { orgs -> orgs.associate { org -> org.id to org.name } }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyMap())

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
        /**
         * 保存 / 删除失败。
         *
         * ⚠️ `message` 可空，且**不要在 ViewModel 里填兜底文案**：异常常常没有
         * `message`（`Throwable()` 而非业务异常），而"没细节时说什么"是**呈现**决定，
         * 归 UI 层。此前这里写死 ``（审计 §3.4），后果是同一句话在
         * 三个 ViewModel 里各存一份、且都不在 strings.xml。
         * ⇒ 现在传 null，由 `ItemDetailScreen` 换成 `error_unknown_no_detail`。
         */
        data class SaveFailed(val message: String?) : UiEvent
        data object Deleted : UiEvent

        /**
         * 附件下载完成。`fileUri` 是 FileProvider 的 `content://` URI，
         * UI 用它发起 `ACTION_VIEW` 让用户选应用打开 / 另存。
         */
        data class DownloadDone(val fileName: String, val fileUri: String) : UiEvent

        /** 附件下载失败（解包失败 / MAC 不匹配 / 网络）。`message` 语义同 [SaveFailed]。 */
        data class DownloadFailed(val message: String?) : UiEvent
    }

    data class UiState(
        val item: VaultItem? = null,
        /**
         * 是否**已经收到过一次**条目流的发射（不论解出的是不是 null）。
         *
         * ⚠️ 2026-10-22（用户反馈：从条目列表点进详情时，闪过不到 1 帧的
         * 「条目不存在或已被删除」）：`item == null` 有**两种**含义 ——
         * "还没解出来"与"真的不存在"，而 [item] 的初始值就是 null ⇒ 两者在 UI 上
         * 无法区分。窗口很窄（ViewModel 的新实例、订阅尚在建立、或库类型/密钥尚未就绪），
         * 窄到只剩一两次重组 —— 但**恰恰是那一两帧**被用户看见了。
         *
         * 判据：**"未知"必须是一种显式状态，不能借用"空值"来表达**。
         */
        val itemLoaded: Boolean = false,
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
        /** 正在下载附件（同一时刻只允许一个，UI 据此禁用其它附件的下载按钮）。 */
        val downloadingAttachment: Boolean = false,
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
                // `itemLoaded` 一旦置真就不再回落：流重发（同步刷写 / 编辑后）时若短暂
                // 解不出条目，应当继续显示上一次的内容，而不是把页面闪回"不存在"。
                _state.update { it.copy(item = item, itemLoaded = true) }
            }
        }
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                val vault = vaults.firstOrNull { it.id == vaultId }
                _state.update {
                    it.copy(
                        vaultName = vault?.name.orEmpty(),
                        serverOrigin = vault?.origin,
                    )
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

    /**
     * 复制**历史密码**（密码历史分区）。
     *
     * 按敏感密码对待（`isPassword = true`）：同样走敏感剪贴板 + 自动清除，
     * 提示文案复用"已复制密码（N 秒后清除）"——它本来就是密码，不必为历史再开一套文案。
     */
    fun copyHistoryPassword(password: String) {
        if (password.isBlank()) return
        doCopy(password, isPassword = true)
    }

    /**
     * 下载一个附件到应用缓存并解密，完成后发 [UiEvent.DownloadDone]（携带
     * FileProvider URI，UI 发起 `ACTION_VIEW`）。
     *
     * - 落点 `cacheDir/attachments/<itemId>/`：只对本应用可读的私有目录，
     *   明文**不进公共下载目录**（用户要外发走系统"打开/另存"的分享链路）；
     * - 文件名把路径分隔符替换成下划线：`fileName` 是解密出来的服务端数据，
     *   不能直接拼进路径（`../../` 穿越）。
     */
    fun downloadAttachment(attachment: VaultAttachment) {
        if (_state.value.downloadingAttachment) return
        _state.update { it.copy(downloadingAttachment = true) }
        viewModelScope.launch {
            val result = runCatching {
                val dir = File(appContext.cacheDir, "attachments/$itemId")
                    .apply { mkdirs() }
                val safeName = attachment.fileName.replace(Regex("[/\\\\]"), "_")
                val file = File(dir, "${attachment.id}_$safeName")
                file.outputStream().use { sink ->
                    itemRepository
                        .downloadAttachment(vaultId, itemId, attachment.id, sink)
                        .getOrThrow()
                }
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file,
                )
            }
            _state.update { it.copy(downloadingAttachment = false) }
            result.fold(
                onSuccess = { uri ->
                    _events.trySend(UiEvent.DownloadDone(attachment.fileName, uri.toString()))
                },
                onFailure = { e ->
                    _events.trySend(UiEvent.DownloadFailed(e.message))
                },
            )
        }
    }

    /**
     * 复制并上报结果。
     *
     * ⚠️ **网址不算敏感内容**（#188，2026-10-26）：
     * 原来这里一律 `sensitive = true` + 自动清除，于是「复制网址」——
     * 一个用户马上要粘到微信/浏览器地址栏的东西——被 30 秒后清掉了。
     * 用户看到的现象是"提示说已复制，粘出来是空的"，而这条路径本身
     * 连提示文案都写着"（30 秒后自动清除）"，等于让用户以为网址也是秘密。
     *
     * 判据：**清除是为了防止「离开设备的人拿到密码」**。网址本来就明文显示在屏幕上、
     * 也存在于浏览器历史里，清它没有安全收益，只有"粘不上"的代价。
     * 所以网址走 `sensitive = false` + `autoClearMs = 0`（不排清除任务、不打 IS_SENSITIVE）。
     *
     * 注意 `isUri` 参数仍然保留：它决定的是**提示文案**（"已复制网址"而非"已复制密码"），
     * 两者是可正交的（网址的提示不带清除倒计时，正是因为这里不再自动清除）。
     */
    private fun doCopy(
        text: String,
        isPassword: Boolean = false,
        isUri: Boolean = false,
        isTotp: Boolean = false,
        isField: Boolean = false,
    ) {
        val sensitive = !isUri
        val clearMs = if (sensitive) clipboardClearMs else 0L
        runCatching {
            clipboard.copy(text = text, sensitive = sensitive, autoClearMs = clearMs)
        }.onSuccess {
            _events.trySend(
                UiEvent.CopyDone(
                    isPassword = isPassword,
                    clearSeconds = if (clearMs > 0) clearMs / 1000 else 0,
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
                    onFailure = { error -> UiEvent.SaveFailed(error.message) },
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
                    _events.send(UiEvent.SaveFailed(error.message))
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
