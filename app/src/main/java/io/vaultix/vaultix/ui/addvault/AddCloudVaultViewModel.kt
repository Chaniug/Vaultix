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
 * ## ★ 顺序不可颠倒：**先存凭据，再自检**
 *
 * ```
 * 保存凭据 → testConnection → listChildren → 选文件 → addKdbxVault
 * ```
 *
 * 为什么不能"先测通再存"：`KdbxCloudSyncCoordinator.fileSourceFor` 解析
 * `webdav:` 来源时**立刻就要按 credentialId 取凭据**（它把取凭据做成来源对象上的
 * 一个挂起回调）⇒ 凭据不在，连 `testConnection` 都构造不出来。
 *
 * 反过来说：既然凭据**必须**先落盘，那"连不上"时就必须把它**擦掉**
 * （见 [rollbackWebDavCredential]）—— 否则用户每试一次错密码，
 * 就在加密存储里留一格永远用不到的密文。
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

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.repository.kdbx.WebDavKdbxFileSource
import io.vaultix.data.repository.kdbx.WebDavUrlBuilder
import io.vaultix.data.repository.kdbx.WebDavVaultOrigin
import io.vaultix.domain.KdbxAddOutcome
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountInventory
import io.vaultix.vaultix.remote.CloudAccountKind
import io.vaultix.vaultix.remote.onedrive.OneDriveAuthManager
import io.vaultix.vaultix.remote.onedrive.OneDriveKdbxFileSource
import io.vaultix.vaultix.remote.onedrive.OneDriveVaultOrigin
import io.vaultix.vaultix.remote.onedrive.isOneDriveAuthTemporarilyUnavailable
import io.vaultix.vaultix.remote.onedrive.toOneDriveUserMessage
import io.vaultix.vaultix.remote.webdav.WebDavCredentialStore
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** 从哪种网盘添加。 */
enum class CloudProvider { WEBDAV, ONEDRIVE }

/**
 * 从网盘添加 KDBX 库。
 *
 * @param okHttp 给 WebDAV 来源用 —— 复用 `data:bitwarden` 装配的那一份单例
 *   （自带 Cloudflare 兼容指纹与拦截器）。**不要**在这里新建：
 *   多一份连接池、两套超时行为迟早漂移。
 */
@HiltViewModel
class AddCloudVaultViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val webDavCredentials: WebDavCredentialStore,
    private val oneDriveAuth: OneDriveAuthManager,
    private val oneDriveFactory: OneDriveKdbxFileSource.Factory,
    private val okHttp: OkHttpClient,
    /**
     * 已配置的网盘账号清单（**从库的 origin 反推**，见该类的文件头）。
     *
     * ⚠️ 它是"账号"这条数据流的**唯一来源** —— 本页的目标形态是
     * 「**选**一个账号 → 列目录 → 选文件」，而不再是"让用户在这里填凭据"。
     * （定稿 §11.7 第 2 步：登录与凭据归设置里的「网盘账号」，不归添加页。）
     */
    private val cloudAccounts: CloudAccountInventory,
) : ViewModel() {

    data class UiState(
        val provider: CloudProvider = CloudProvider.WEBDAV,
        // ---- WebDAV 配置（仅"未连接"阶段可见）----
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
        val passwordVisible: Boolean = false,
        // ---- OneDrive 登录态 ----
        val accountName: String? = null,
        // ---- 浏览文件 ----
        /** true = 已经连上并列过目录（此时配置区收起，改为文件列表）。 */
        val browsing: Boolean = false,
        /** 当前目录的展示名（根目录为空串）。 */
        val directoryLabel: String = "",
        /** 能不能往上走一层（根目录时不能 —— 那时"上"是重新配置，由 UI 另行处理）。 */
        val canGoUp: Boolean = false,
        val entries: List<KdbxFileEntry> = emptyList(),
        val selectedName: String = "",
        // ---- 主密码 + 提交 ----
        val masterPassword: String = "",
        val masterPasswordVisible: Boolean = false,
        /** 连接 / 列目录 / 登录 / 添加，任一在进行。 */
        val busy: Boolean = false,
        val error: UnlockUiError? = null,
        /**
         * 可选的网盘账号（空 = 还没配过 ⇒ UI 应给"去设置里配置"的引导，**不要**摊开登录表单）。
         *
         * ⚠️ 与 `browsing` 无关：这是"起点选择"，列目录之后它就不该再主导界面。
         */
        val configuredAccounts: List<CloudAccount> = emptyList(),
    ) {
        /** 当前输入的服务器地址是否是**明文** HTTP（局域网 NAS 的常见情形）。 */
        val insecureHttp: Boolean
            get() = serverUrl.trim().startsWith("http://", ignoreCase = true)

        /** 配置阶段：能不能发起连接。 */
        val canConnect: Boolean
            get() = when (provider) {
                CloudProvider.WEBDAV ->
                    serverUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !busy
                CloudProvider.ONEDRIVE -> !busy
            }

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

    /**
     * 本次 WebDAV 配置对应的凭据 id。
     *
     * ⚠️ **存下来而不是每次重算**：`credentialIdFor(serverUrl, username)` 虽然是纯函数，
     * 但"连上之后用户又改了输入框"时重算就会与**实际落盘的那一格**不是同一格 ——
     * 那会得到"配置看起来成功、解锁时说找不到凭据"。存一个字段就没有这种可能。
     */
    private var webDavCredentialId: String? = null

    /** 当前已登入的 OneDrive 账户 id。 */
    private var oneDriveAccountId: String? = null

    /**
     * 这次配置的 WebDAV 凭据**已被某个库用上**（库已成功入库）。
     *
     * 一旦为 true，任何"回滚"都必须停下 —— 见 [rollbackWebDavCredential]。
     */
    private var credentialCommitted = false

    /**
     * 目录栈。栈底是"根"：
     * - WebDAV：根 = 用户输入的服务器地址（尾部补 `/`）；
     * - OneDrive：根 = `""`（相对 OneDrive 根目录）。
     */
    private val dirStack = mutableListOf<String>()

    // ------------------------------------------------------------ 输入

    fun onProviderChange(provider: CloudProvider) {
        if (provider == _state.value.provider) return
        // 切来源等于放弃当前连接：先回滚可能已落盘的 WebDAV 凭据（连不上的那些不留痕）。
        rollbackWebDavCredential()
        dirStack.clear()
        _state.update {
            it.copy(provider = provider, browsing = false, entries = emptyList(), error = null)
        }
    }

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value, error = null) }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(passwordVisible = visible) }

    fun onMasterPasswordChange(value: String) =
        _state.update { it.copy(masterPassword = value, error = null) }

    fun onMasterPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(masterPasswordVisible = visible) }

    fun onSelectFile(entry: KdbxFileEntry) = _state.update {
        it.copy(selectedName = entry.name, error = null)
    }

    // ------------------------------------------------------------ 连接

    /**
     * WebDAV：保存凭据 → 自检 → 列目录。
     *
     * 三步的顺序是**硬约束**（见类 KDoc）：凭据先落盘，来源对象才构造得出来。
     * 任一步失败都把刚写的凭据擦掉（[rollbackWebDavCredential]）。
     */
    fun connectWebDav() {
        val current = _state.value
        validateWebDav(current)?.let { problem ->
            _state.update { it.copy(error = problem) }
            return
        }
        if (current.busy) return
        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            val serverUrl = normalizeServerUrl(current.serverUrl)
            val credentialId = webDavCredentials.credentialIdFor(serverUrl, current.username)
            webDavCredentialId = credentialId
            // ① 先写凭据（否则第 ② 步的来源对象构造时就取不到凭据）
            webDavCredentials.save(credentialId, current.username, current.password)
            dirStack.clear()
            dirStack += serverUrl

            val source = webDavSource(serverUrl)
            // ② 自检：失败即回滚，且**一条库行都不写**。
            val probe = source.testConnection()
            if (probe.isFailure) {
                rollbackWebDavCredential()
                failWith(probe.exceptionOrNull()?.message ?: "无法连接 WebDAV 服务器")
                return@launch
            }
            // ③ 列目录
            listInto(source, rootLabel(serverUrl))
        }
    }

    /**
     * OneDrive：登录 → 列根目录。
     *
     * ⚠️ [activity] **必须是人当前可见的那个 Activity**：MSAL 要拉起授权页面，
     * 传 `applicationContext` 会直接失败（拿不到 Activity 就抛，而不是静默降级）。
     *
     * @param forceAccountChooser 换号/重登必须为 true —— 默认的 `SELECT_ACCOUNT`
     *   在"只有一个缓存账户"时会被 MSAL 优化成**静默登录**，用户点了「切换账号」
     *   却仍以原账户进来（见 `OneDriveAuthManager.signIn` 的 KDoc）。
     */
    fun connectOneDrive(activity: Activity, forceAccountChooser: Boolean = false) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val session = runCatching {
                oneDriveAuth.signIn(activity, forceAccountChooser = forceAccountChooser)
            }.getOrElse { error ->
                failWith(oneDriveMessage(error))
                return@launch
            }
            oneDriveAccountId = session.accountId
            _state.update { it.copy(accountName = session.username.ifBlank { session.displayName }) }
            dirStack.clear()
            dirStack += ""
            val source = oneDriveSource(rootPlaceholderPath())
            if (source == null) {
                failWith("OneDrive 登录状态异常，请重试")
                return@launch
            }
            listInto(source, "")
        }
    }

    /** 注销当前 OneDrive 账户（清 MSAL token 缓存），随后回到配置阶段。 */
    fun signOutOneDrive() {
        val accountId = oneDriveAccountId
        viewModelScope.launch {
            oneDriveAuth.signOut(accountId)
            oneDriveAccountId = null
            dirStack.clear()
            _state.update {
                it.copy(
                    browsing = false,
                    accountName = null,
                    entries = emptyList(),
                    selectedName = "",
                    error = null,
                )
            }
        }
    }

    /**
     * **换号**：先注销旧账户，再强制走登录页。
     *
     * ## 为什么"注销 + 强制选账户"两件都要做（只做一件都不够）
     *
     * - 只传 `forceAccountChooser`：MSAL 的 token 缓存里旧账户还在，
     *   某些路径下仍会直接命中它 —— 用户点了"切换账号"却还是原账号；
     * - 只 `signOut` 不传 `forceAccountChooser`：缓存清干净后 MSAL 会走
     *   `Prompt.SELECT_ACCOUNT`，当**浏览器侧**还有登录会话时同样会静默复用。
     *
     * ⇒ 两件事一起做才是"真的能换号"。这也是上游 Bastion 的做法
     * （`LocalKeePassOneDriveBrowser` 里同一个按钮先 signOut 再 signIn）。
     */
    fun switchOneDriveAccount(activity: Activity) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            // 注销是尽力而为：MSAL 侧失败也**不该**卡住换号（`signOut` 自身不抛）。
            oneDriveAuth.signOut(oneDriveAccountId)
            oneDriveAccountId = null
            _state.update { it.copy(busy = false, accountName = null) }
            connectOneDrive(activity, forceAccountChooser = true)
        }
    }

    /**
     * 拿不到 Activity（宿主不是 `FragmentActivity`）时的如实上报。
     *
     * ⚠️ 不静默禁用按钮：那样用户看到的是一个"点了没反应的按钮"，
     * 既不知道原因也不知道能做什么。上游 Bastion 在同一处也是直接报错。
     */
    fun reportMissingActivity() {
        failWith("无法拉起 Microsoft 登录页（当前页面拿不到 Activity），请重启应用后重试")
    }

    /**
     * 进入页面时**恢复已缓存的 OneDrive 登录**（2026-09-17 修）。
     *
     * ## 为什么必须有这一步（用户实测："返回到添加密码库界面，登录状态就没了"）
     *
     * 本页是**导航路由**（`AddCloudVaultRoute`）。用户一返回，这个
     * `hiltViewModel()` 作用域就随 NavBackStackEntry 一起销毁 ⇒ 再进来是**全新的 ViewModel**
     * ⇒ [oneDriveAccountId] / `accountName` 都是 null ⇒ 界面显示"未登录"。
     *
     * ⚠️ **但那是假象**：MSAL 的账户与 refresh token 一直在它自己的缓存里
     * （实测 `account_credential_cache.xml` 里 refreshtoken 始终在）。
     * 也就是说 —— **界面说"没登录"，真源说"登录着"**。
     * 这与今天那条共同病根完全同族：*把"我没记住"渲染成"没发生过"*。
     *
     * ⇒ 进页面时主动向真源要一次：有缓存账户就**直接恢复**（并顺手列根目录，
     *   让用户回到他刚才那个浏览位置），而不是让用户重复走一遍授权页。
     *
     * ⚠️ 只在**没有进行中的操作**且**本 VM 还没有账户**时执行：否则会把用户
     * 正在进行的"换号"覆盖回旧账户（那正是 `forceAccountChooser` 要防的那件事）。
     */
    fun restoreOneDriveSessionIfAny() {
        val current = _state.value
        if (current.provider != CloudProvider.ONEDRIVE) return
        if (current.busy || current.browsing) return
        if (oneDriveAccountId != null) return
        viewModelScope.launch {
            // 取不到（没登录过 / 缓存被清）就安静返回，界面维持"未登录"——那是**如实**的。
            val cached = runCatching { oneDriveAuth.getCachedSession() }.getOrNull() ?: return@launch
            oneDriveAccountId = cached.accountId
            _state.update {
                it.copy(
                    busy = true,
                    accountName = cached.username.ifBlank { cached.displayName },
                    error = null,
                )
            }
            dirStack.clear()
            dirStack += ""
            val source = oneDriveSource(rootPlaceholderPath())
            if (source == null) {
                failWith("OneDrive 登录状态异常，请重试")
                return@launch
            }
            listInto(source, "")
        }
    }

    // ------------------------------------------------------------ 选账号（新流程）

    /** 重新读一遍已配置账号（进页面时调；账号可能刚在设置里配好）。 */
    fun refreshConfiguredAccounts() {
        viewModelScope.launch {
            val accounts = cloudAccounts.list()
            _state.update { it.copy(configuredAccounts = accounts) }
        }
    }

    /**
     * **选一个已配置账号**并直接列它的目录（新流程的核心动作）。
     *
     * 与旧的 `connectWebDav()` / `connectOneDrive()` 的区别：那两条要**当场收凭据**，
     * 这条只是"用已经有凭据的账号去读" ⇒ 不保存任何东西、也不会失败于"凭据不对"
     * （凭据对不对，在设置页配置时就验证过了）。
     *
     * ⚠️ 起点用 [CloudAccount.browseRoot]（从该账号的库反推）——
     * **不要**在这里拼 `server + "/"`：用户当初填的可能是子目录，
     * 拼出来的 URL 会 404，而界面只会说"找不到文件"（今天已经栽过两次）。
     * 起点为 null 时如实报错，不猜。
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

    /** 放弃当前连接，回到配置阶段（"重新配置"按钮）。 */
    fun resetConnection() {
        rollbackWebDavCredential()
        dirStack.clear()
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
                    // ⚠️ 先立"已用上"标记，再清可能与凭据相关的临时态：
                    //    顺序反了的话，任何在成功之后到达的回滚都会把凭据删掉。
                    credentialCommitted = true
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

    private fun validateWebDav(current: UiState): UnlockUiError? {
        if (current.serverUrl.isBlank() || current.username.isBlank() || current.password.isEmpty()) {
            // ⚠️ 不用 `UnlockUiError.FieldsMissing`：它的文案是"请填写邮箱和主密码"，
            //    而 WebDAV 没有邮箱。对用户来说那是指向一个不存在字段的提示。
            return asError(CLOUD_FIELDS_MISSING)
        }
        val parsed = current.serverUrl.trim().toHttpUrlOrNull() ?: return UnlockUiError.InvalidServer
        // ★ 提前拦「把凭据写进 URL」（`https://user:pass@nas/dav`）：
        //   那种 URL 会让密码进日志、崩溃报告、代理记录。
        //   `WebDavVaultOrigin.parse` 也会拒，但它要到**解锁时**才跑 ——
        //   那时用户早已相信"配置成功"了。
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return asError(CREDENTIALS_IN_URL_MESSAGE)
        }
        return null
    }

    /**
     * 规范化服务器地址：补 scheme、去尾斜杠，**再补一个尾斜杠**得到根目录 URL。
     *
     * ⚠️ 两步看似矛盾，其实是两件事：
     * ① [`WebDavUrlBuilder.normalizeServer`] 的"去尾斜杠"是为了**拼接时幂等**
     *    （见该文件的说明：带尾斜杠的 base 会让 `addPathSegment` 产出双斜杠）；
     * ② 这里的"补尾斜杠"是因为它同时要当**目录 URL** 用 ——
     *    而目录 URL 必须以 `/` 结尾，否则 `listChildren` 会去列上一级。
     */
    private fun normalizeServerUrl(raw: String): String {
        val normalized = WebDavUrlBuilder.normalizeServer(raw)
        return if (normalized.isEmpty() || normalized.endsWith('/')) normalized else "$normalized/"
    }

    private fun webDavSource(directoryUrl: String): KdbxFileSource {
        val credentialId = webDavCredentialId
            ?: throw IllegalStateException("WebDAV 凭据尚未就绪")
        return WebDavKdbxFileSource(
            client = okHttp,
            fileUrl = directoryUrl,
            credentialProvider = {
                // ⚠️ 现取而不是缓存：用户可能刚改过密码，缓存旧值只会让之后所有请求 401。
                webDavCredentials.read(credentialId)
                    ?: throw IllegalStateException(CREDENTIAL_MISSING_MESSAGE)
            },
        )
    }

    private fun oneDriveSource(filePath: String): KdbxFileSource? {
        val accountId = oneDriveAccountId ?: return null
        return oneDriveFactory.create(OneDriveVaultOrigin.build(accountId, filePath))
    }

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

    private fun currentDir(): String = dirStack.lastOrNull() ?: when (_state.value.provider) {
        CloudProvider.WEBDAV -> _state.value.serverUrl
        CloudProvider.ONEDRIVE -> ""
    }

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

    /**
     * 回滚刚写下的 WebDAV 凭据。
     *
     * 只在"连接没走通 / 用户主动放弃"时调用。
     *
     * ⚠️ **[credentialCommitted] 为 true 时直接返回**：库已经加进列表了，
     * 那份凭据正是它以后能解锁的理由（`webdav:<credentialId>:<url>` 靠它取账号密码）。
     * 删掉它 = 用户明明看到"添加成功"，下次解锁却说找不到凭据。
     *
     * 这个开关不能省：成功路径目前靠"导航离开页面"保证不会再调到这里，
     * 那是**调用顺序**上的保证，而调用顺序是最容易被后来的一次改动破坏的东西。
     */
    private fun rollbackWebDavCredential() {
        if (credentialCommitted) return
        val credentialId = webDavCredentialId ?: return
        webDavCredentials.remove(credentialId)
        webDavCredentialId = null
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

    /**
     * OneDrive 错误分流 —— **两类错误的用户动作不同，绝不能混成一句话**。
     *
     * - 「打盹期静默刷新被系统掐断」⇒ 点亮屏幕 / 关掉电池优化就能自愈，
     *   让用户去重新登录是**白跑一趟**；
     * - 其余（凭据失效、被撤销）⇒ 必须重新登录，只提电池优化没用。
     */
    private fun oneDriveMessage(error: Throwable): String {
        val human = error.toOneDriveUserMessage()
        return if (error.isOneDriveAuthTemporarilyUnavailable()) {
            human
        } else {
            "$human（请尝试重新登录）"
        }
    }

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

        const val CREDENTIALS_IN_URL_MESSAGE =
            "服务器地址里不要写账号密码（形如 user:pass@host）—— 那样密码会进日志。请把账号填在下面的输入框里。"

        const val CLOUD_FIELDS_MISSING = "请填写服务器地址、账号和密码"

        const val CREDENTIAL_MISSING_MESSAGE = "找不到该 WebDAV 账号的凭据，请重新填写"
    }
}
