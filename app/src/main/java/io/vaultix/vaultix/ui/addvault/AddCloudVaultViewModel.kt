/*
 * Vaultix — app:ui:addvault
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 从**网盘**添加 KDBX 库（WebDAV / OneDrive）。
 *
 * ## 为什么 WebDAV 与 OneDrive 共用一个 ViewModel
 *
 * 两者只有**第一步**不同（怎么拿到一个可用的 [KdbxFileSource]），
 * 之后完全一样：列目录 → 选 `.kdbx` → 输主密码 → 交给 `addKdbxVault`。
 * 拆成两个 ViewModel 会把"选库 + 主密码 + 入库"这套尾巴抄两遍 ——
 * 而它恰恰是最容易抄歪的部分（主密码用完即弃、成功/覆盖两态反馈）。
 *
 * ## ★ 本页**不负责**配置账号（2026-09-18，定稿 §11.7 第 2 步落地）
 *
 * 账号的**全生命周期**（配置 / 自检 / 换号 / 注销）都在设置 →「网盘账号」，
 * 本页只做「**选**一个已配置账号 → 选文件 → 入库」。
 *
 * 这不是"少画几个输入框"，而是生命周期的硬约束：
 *
 * | | 生命周期 |
 * |---|---|
 * | OneDrive 登录态 / WebDAV 凭据 | **长**（跨进程跨页面） |
 * | 本页 | **短** —— 导航路由，**一返回 ViewModel 即销毁** |
 *
 * ⇒ 把长寿命状态的操作放在短寿命宿主里，必然"返回就没了"（用户实测过的那个 bug）。
 * ⇒ 本页**没有**服务器地址 / 账号 / 密码 / 登录按钮；一个账号都没有时，
 *   给"去设置里配置"的引导（见 `AddCloudVaultScreen`）。
 *
 * ## 为什么要能进子目录
 *
 * 库文件放在网盘根目录是**例外**而不是常态（用户通常会有 `Vaultix/`、`Keepass/`
 * 之类的目录，OneDrive 上尤其如此）。只能列根目录等于"这个功能对多数人不可用"。
 * 两个来源的实现方式不同，但都靠同一个手法：
 * - WebDAV：把**目录 URL**（尾部带 `/`）当 `fileUrl` 传给来源 ——
 *   `directoryUrl()` 对已带尾斜杠的 URL 是幂等的，`PROPFIND` 因此正好打在目录上；
 * - OneDrive：把 `父路径/<一个占位文件名>` 当 `path` ——
 *   `listChildren()` 取的是 `path` 的**父目录**，占位名不会被用上。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.addvault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.repository.kdbx.WebDavUrlBuilder
import io.vaultix.data.repository.kdbx.WebDavVaultOrigin
import io.vaultix.domain.KdbxAddOutcome
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountConnector
import io.vaultix.vaultix.remote.CloudAccountInventory
import io.vaultix.vaultix.remote.CloudAccountKind
import io.vaultix.vaultix.remote.onedrive.OneDriveVaultOrigin
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.toUnlockUiError
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 从哪种网盘添加。 */
enum class CloudProvider { WEBDAV, ONEDRIVE }

/**
 * 从网盘添加 KDBX 库 —— **只负责"选文件 + 入库"**（账号本身在设置里配）。
 *
 * @param connector 构造来源、以及本页唯一会用到的凭据读写入口
 *   （WebDAV 凭据的写入必须只有一处，见该类的文件头）。
 */
@HiltViewModel
class AddCloudVaultViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val connector: CloudAccountConnector,
    /**
     * 已配置的网盘账号清单。
     *
     * ⚠️ 它是"账号"这条数据流的**唯一来源** —— 本页的目标形态是
     * 「**选**一个账号 → 列目录 → 选文件」，不再有"让用户在这里填凭据"这条路。
     * （定稿 §11.7 第 2 步：登录与凭据归设置里的「网盘账号」，不归添加页。）
     */
    private val cloudAccounts: CloudAccountInventory,
) : ViewModel() {

    data class UiState(
        val provider: CloudProvider = CloudProvider.WEBDAV,
        // ---- 浏览文件 ----
        /** true = 已经连上并列过目录（此时账号选择区收起，改为文件列表）。 */
        val browsing: Boolean = false,
        /** 当前目录的展示名（根目录为空串）。 */
        val directoryLabel: String = "",
        /** 能不能往上走一层（根目录时不能 —— 那时"上"是重新选账号，由 UI 另行处理）。 */
        val canGoUp: Boolean = false,
        val entries: List<KdbxFileEntry> = emptyList(),
        val selectedName: String = "",
        // ---- 主密码 + 提交 ----
        val masterPassword: String = "",
        val masterPasswordVisible: Boolean = false,
        /** 列目录 / 添加，任一在进行。 */
        val busy: Boolean = false,
        val error: UnlockUiError? = null,
        /**
         * 可选的网盘账号（空 = 还没配过 ⇒ UI 应给"去设置里配置"的引导，**不要**摊开登录表单）。
         *
         * ⚠️ 与 `browsing` 无关：这是"起点选择"，列目录之后它就不该再主导界面。
         */
        val configuredAccounts: List<CloudAccount> = emptyList(),
        /** 账号清单还在读（避免把"还没读完"渲染成"一个都没有"）。 */
        val loadingAccounts: Boolean = true,
    ) {
        /** 提交：选中了文件 + 填了主密码。 */
        val canSubmit: Boolean
            get() = selectedName.isNotBlank() && masterPassword.isNotEmpty() && !busy
    }

    sealed interface Event {
        /** 添加成功（[isUpdate] 区分"新增"与"重复添加同一文件覆盖了旧行"）。 */
        data class VaultAdded(val isUpdate: Boolean) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 所选账号的 WebDAV 凭据 id（来自 [CloudAccount.storedId]，本页**不写**凭据）。 */
    private var webDavCredentialId: String? = null

    /** 所选账号的 OneDrive 账户 id（同上）。 */
    private var oneDriveAccountId: String? = null

    /**
     * 目录栈。栈底是"根"：
     * - WebDAV：根 = 所选账号的浏览起点（目录 URL）；
     * - OneDrive：根 = `""`（相对 OneDrive 根目录）。
     */
    private val dirStack = mutableListOf<String>()

    // ------------------------------------------------------------ 输入

    fun onMasterPasswordChange(value: String) =
        _state.update { it.copy(masterPassword = value, error = null) }

    fun onMasterPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(masterPasswordVisible = visible) }

    fun onSelectFile(entry: KdbxFileEntry) = _state.update {
        it.copy(selectedName = entry.name, error = null)
    }

    // ------------------------------------------------------------ 选账号（新流程）

    /** 重新读一遍已配置账号（进页面时调；账号可能刚在设置里配好）。 */
    fun refreshConfiguredAccounts() {
        viewModelScope.launch {
            val accounts = cloudAccounts.list()
            _state.update { it.copy(configuredAccounts = accounts, loadingAccounts = false) }
        }
    }

    /**
     * **选一个已配置账号**并直接列它的目录（本页唯一的上手动作）。
     *
     * 它只"用已经有凭据的账号去读" ⇒ **不保存任何东西**，也不会失败于"凭据不对"
     * （凭据对不对，是在设置 →「网盘账号」配置时就验证过的）。
     *
     * ⚠️ 起点用 [CloudAccount.browseRoot] ——
     * **不要**在这里拼 `server + "/"`：用户当初填的可能是子目录，
     * 拼出来的 URL 会 404，而界面只会说"找不到文件"（已经栽过两次）。
     */
    fun pickAccount(account: CloudAccount) {
        if (_state.value.busy) return
        val root = account.browseRoot
        if (root.isNullOrBlank()) {
            _state.update { it.copy(error = asError("这个账号还没有可用的目录，请先在设置里重新配置")) }
            return
        }
        _state.update {
            it.copy(
                provider = when (account.kind) {
                    CloudAccountKind.WEBDAV -> CloudProvider.WEBDAV
                    CloudAccountKind.ONEDRIVE -> CloudProvider.ONEDRIVE
                },
                busy = true,
                error = null,
            )
        }
        viewModelScope.launch {
            // 把账号的身份装进现有字段，让列目录那段逻辑完全复用（不新开一条分支）。
            when (account.kind) {
                CloudAccountKind.WEBDAV -> {
                    webDavCredentialId = account.storedId
                    dirStack.clear()
                    dirStack += root
                    val source = runCatching { webDavSource(root) }.getOrNull()
                    if (source == null) {
                        failWith("这个库还没有可用的文件来源")
                        return@launch
                    }
                    listInto(source, rootLabel(root))
                }

                CloudAccountKind.ONEDRIVE -> {
                    oneDriveAccountId = account.storedId
                    dirStack.clear()
                    dirStack += root
                    val source = oneDriveSource(oneDriveBrowsePath())
                    if (source == null) {
                        failWith("OneDrive 登录状态异常，请重试")
                        return@launch
                    }
                    listInto(source, root)
                }
            }
        }
    }

    // ------------------------------------------------------------ 浏览

    /**
     * 进入子目录。
     *
     * ⚠️ 只对 `isDirectory` 的条目有意义 —— UI 保证，这里再判一次：
     * 误把文件当目录会得到一次莫名其妙的 `PROPFIND`（大概率 404），
     * 而用户看到的是"点了没反应"。
     */
    fun openDirectory(entry: KdbxFileEntry) {
        if (!entry.isDirectory || _state.value.busy) return
        val next = childDirectoryOf(entry) ?: return
        dirStack += next
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val source = currentSource() ?: run {
                failWith("这个来源已不可用，请重新连接")
                return@launch
            }
            listInto(source, labelOf(next))
        }
    }

    /** 回到上一层目录（根目录时无动作 —— 那时 UI 提供的是"重新配置"）。 */
    fun goUp() {
        if (dirStack.size <= ONE_LEVEL || _state.value.busy) return
        dirStack.removeAt(dirStack.lastIndex)
        _state.update { it.copy(busy = true, selectedName = "", error = null) }
        viewModelScope.launch {
            val source = currentSource() ?: run {
                failWith("这个来源已不可用，请重新连接")
                return@launch
            }
            listInto(source, labelOf(currentDir()))
        }
    }

    /** 放弃当前浏览，回到**选账号**阶段（"重新选账号"按钮）。 */
    fun resetConnection() {
        dirStack.clear()
        webDavCredentialId = null
        oneDriveAccountId = null
        _state.update {
            it.copy(
                browsing = false,
                entries = emptyList(),
                selectedName = "",
                masterPassword = "",
                error = null,
            )
        }
    }

    // ------------------------------------------------------------ 提交

    fun submit() {
        val current = _state.value
        if (current.selectedName.isBlank()) {
            _state.update { it.copy(error = asError("请先选中一个 .kdbx 文件")) }
            return
        }
        if (current.masterPassword.isEmpty() || current.busy) return
        val origin = buildOrigin() ?: run {
            _state.update { it.copy(error = asError("这个来源已不可用，请重新连接")) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            val outcome = vaultRepository.addKdbxVault(
                sourceUri = origin,
                displayName = current.selectedName,
                masterPassword = current.masterPassword,
                // ⚠️ WebDAV / OneDrive 的库**没有**本地 keyfile URI：
                //    keyfile 是"和库文件配对的本地字节"，放到网盘流程里会把
                //    "选一个 keyfile" 变成第二套需要持久授权的状态。
                //    真需要 keyfile 的库请用「打开 KDBX 文件」走本地路径。
                keyFileUri = null,
            )
            when (outcome) {
                KdbxAddOutcome.Added, KdbxAddOutcome.Updated -> {
                    _state.update { it.copy(busy = false, masterPassword = "", error = null) }
                    _events.send(Event.VaultAdded(isUpdate = outcome is KdbxAddOutcome.Updated))
                }

                is KdbxAddOutcome.Failed -> _state.update {
                    it.copy(busy = false, error = outcome.result.toUnlockUiError())
                }
            }
        }
    }

    // ------------------------------------------------------------ 内部

    /**
     * 列目录并进入"浏览"阶段。
     *
     * ⚠️ 只保留「目录 + `.kdbx`」：非 KDBX 的文件（`.txt` / `.jpg` …）列出来
     * 只会把真正的目标淹掉 —— 单测文件夹里的杂项文件往往有几十个。
     */
    private suspend fun listInto(source: KdbxFileSource, label: String) {
        val listed = runCatching { source.listChildren() }.getOrElse { error ->
            failWith(error.message ?: "无法列出目录")
            return
        }
        val visible = listed
            .filter { it.isDirectory || it.isKdbx }
            // 目录在前、同类按名称排。⚠️ 用显式 Locale：`lowercase()` 不带 locale
            // 会被 Android lint 的 `DefaultLocale` 拦下（土耳其语环境里 I/ı 会排错）。
            .sortedWith(
                compareByDescending<KdbxFileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
        _state.update {
            it.copy(
                busy = false,
                browsing = true,
                directoryLabel = label,
                canGoUp = dirStack.size > ONE_LEVEL,
                entries = visible,
                selectedName = "",
                error = null,
            )
        }
    }

    /** 构造 WebDAV 来源（凭据 id 来自所选账号；未选过账号 ⇒ null）。 */
    private fun webDavSource(directoryUrl: String): KdbxFileSource? {
        val credentialId = webDavCredentialId ?: return null
        return connector.webDavSource(credentialId, directoryUrl)
    }

    /** 构造 OneDrive 来源（账户 id 来自所选账号；未选过账号 ⇒ null）。 */
    private fun oneDriveSource(filePath: String): KdbxFileSource? =
        connector.oneDriveSource(oneDriveAccountId, filePath)

    /**
     * 当前目录对应的来源。
     *
     * 两种来源都要**假装成一个文件**（见类 KDoc 的"为什么这样打"）：
     * - WebDAV：目录 URL 自带尾 `/` ⇒ `directoryUrl()` 幂等，直接用；
     * - OneDrive：`父路径/占位名` ⇒ `listChildren()` 取父目录。
     */
    private fun currentSource(): KdbxFileSource? = when (_state.value.provider) {
        CloudProvider.WEBDAV -> runCatching { webDavSource(currentDir()) }.getOrNull()
        CloudProvider.ONEDRIVE -> oneDriveSource(oneDriveBrowsePath())
    }

    private fun rootPlaceholderPath(): String = BROWSE_PLACEHOLDER

    /**
     * 列 OneDrive **当前目录**时该用的 path。
     *
     * ★ 2026-09-17 修：此前只在**根目录**加了占位文件名，进了子目录就直接把
     * 目录路径（如 `Vaultix`）当 path 传下去 —— 而
     * [io.vaultix.vaultix.remote.onedrive.OneDriveKdbxFileSource.listChildren] 取的是
     * **path 的父目录**（`path.substringBeforeLast('/', "")`），
     * 对没有 `/` 的 `"Vaultix"` 会返回**空串** ⇒ **列的还是根目录**，
     * 而选中文件时 `selectedReference()` 却拼成 `"Vaultix/<名字>"` ⇒ Graph 404
     * （界面只显示"OneDrive 上找不到该文件"，文件明明在）。
     * ⇒ 与 WebDAV 那条路对齐：**任何一层目录都要带上占位文件名**。
     */
    private fun oneDriveBrowsePath(): String {
        val dir = currentDir()
        return if (dir.isBlank()) rootPlaceholderPath() else "$dir/$BROWSE_PLACEHOLDER"
    }

    private fun rootLabel(serverUrl: String): String =
        serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

    /** 目录的展示名（只留最后一段）。 */
    private fun labelOf(dir: String): String = when (_state.value.provider) {
        CloudProvider.WEBDAV -> rootLabel(dir)
        CloudProvider.ONEDRIVE -> dir
    }

    /**
     * 当前目录。
     *
     * ⚠️ 兜底只能是空串：目录栈**必然**由 [pickAccount] 先填好（没选账号就不会进浏览阶段），
     * 走到兜底说明状态机出了意外 —— 此时"列根目录"比"用一个凭空猜的地址"安全，
     * 而且猜不出服务器地址了（本页不再持有它，账号才是它的宿主）。
     */
    private fun currentDir(): String = dirStack.lastOrNull() ?: ""

    /**
     * 子目录在该来源下的表示。
     *
     * WebDAV：**不用** `entry.id`（那是服务器回显的 `href`，可能指向代理背后的真实路径）。
     * 用「当前目录 + 名字」拼接 —— 当前目录我们已经用 PROPFIND 证明过可用，
     * 拼出来的子目录一定打在那个入口上。理由见 `WebDavUrlBuilder` 的文件头。
     *
     * ⚠️ 拼不出完整 URL（服务器地址非法）就当这次点击无效 ——
     * 宁可没反应，也不要拼一个必然 404 的字符串让用户等一次超时。
     */
    private fun childDirectoryOf(entry: KdbxFileEntry): String? = when (_state.value.provider) {
        CloudProvider.WEBDAV -> WebDavUrlBuilder.joinDirectory(currentDir(), entry.name)

        CloudProvider.ONEDRIVE -> {
            val parent = currentDir()
            if (parent.isBlank()) entry.name else "$parent/${entry.name}"
        }
    }

    /**
     * 拼出要持久化的 origin。
     *
     * 两种来源的 origin 格式不同（各自由自己的 `*VaultOrigin` 负责编解码），
     * 但**都只放不含秘密的东西**：一个 credentialId / accountId 加一个路径。
     */
    private fun buildOrigin(): String? {
        val current = _state.value
        val reference = selectedReference() ?: return null
        return when (current.provider) {
            CloudProvider.WEBDAV -> webDavCredentialId
                ?.let { credentialId -> WebDavVaultOrigin.build(credentialId, reference) }

            CloudProvider.ONEDRIVE -> oneDriveAccountId
                ?.let { accountId -> OneDriveVaultOrigin.build(accountId, reference) }
        }
    }

    /**
     * 选中文件的"引用"。
     *
     * - WebDAV：**完整文件 URL**（`webdav:<credId>:<fileUrl>` 里躺的就是它）；
     * - OneDrive：**相对路径**（`onedrive:<accountId>:<path>`）。
     *
     * 两者都由「当前目录 + 文件名」推出，与 [childDirectoryOf] 同源 ——
     * 同一段路径知识只写一遍，不会出现"进目录用一套、选文件用另一套"的漂移。
     */
    private fun selectedReference(): String? {
        val current = _state.value
        val name = current.selectedName
        if (name.isBlank()) return null
        return when (current.provider) {
            CloudProvider.WEBDAV -> WebDavUrlBuilder.join(currentDir(), name)

            CloudProvider.ONEDRIVE -> {
                val parent = currentDir()
                if (parent.isBlank()) name else "$parent/$name"
            }
        }
    }

    private fun failWith(message: String) {
        _state.update { it.copy(busy = false, error = asError(message)) }
    }

    /**
     * 包成 [UnlockUiError.Detail] 而不是 `Unknown`。
     *
     * 这里进出的每一句话都**已经是人话**（来源自带的消息、或本类写死的指引），
     * 用 `Unknown` 会在前面多挂一个「出错了：」—— 把一句完整的话切碎。
     */
    private fun asError(message: String): UnlockUiError = UnlockUiError.Detail(message)

    private companion object {
        /** 目录栈只有一个元素 = 已在根目录。 */
        const val ONE_LEVEL = 1

        /**
         * 列目录用的占位文件名。
         *
         * 它**永远不会被真正读/写** —— 来源只用它推出"所在目录"。
         * 用 `.kdbx` 后缀是为了万一将来某个实现顺手 stat 它时，
         * 拿到的仍是一个语义正确的路径。
         */
        const val BROWSE_PLACEHOLDER = ".vaultix-browse.kdbx"
    }
}
