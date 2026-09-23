/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「网盘账号」页** —— 账号的**全生命周期**都在这里（2026-09-18 补齐"可操作"）。
 *
 * ## ★ 这一页为什么必须能操作（不只是列出来）
 *
 * 2026-09-17 只做了"读"（列出账号），文件头写着「注销 / 换号**随后补**」。
 * 但定稿 §11.7 第 1 步要的是把**账号配置整段搬到这里** —— 理由是生命周期：
 *
 * | | 生命周期 |
 * |---|---|
 * | OneDrive 登录态 / WebDAV 凭据 | **长**（跨进程跨页面，活在 MSAL 缓存 / `SecureCredentialStore`） |
 * | 「添加密码库」那一页 | **短** —— 导航路由，**一返回 ViewModel 即销毁** |
 *
 * ⇒ 长寿命状态的操作入口，必须在长寿命的宿主里。只读清单 = 用户看到账号有问题
 * 却在本页什么都做不了，只能去别处碰运气。
 *
 * ## 动作与它们的"影响面"
 *
 * 凭据是**账号级**的、被多个库共用 ⇒ **注销会连带影响同账号的所有库**。
 * 所以注销**必须先算出影响面**（[CloudAccount.vaultIds]）并让用户确认，
 * 而不是点了就删 —— 那正是当初"随后补"时特意留下的理由。
 *
 * ## 自检（probe）与"连接并浏览"的区别
 *
 * - 本页的 [probe]：**只验证凭据还能不能用**，成功后凭据**留在原地**（它的目的就是配好账号）；
 * - 添加页的 `connectWebDav`：验证完立刻列目录选文件，走完会有一个库用上它。
 *
 * ⇒ 同一句"凭据先落盘再自检"，两处的**后续**不同，因此不能共用一条流程。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountConnector
import io.vaultix.vaultix.remote.CloudAccountInventory
import io.vaultix.vaultix.remote.CloudAccountKind
import io.vaultix.vaultix.remote.onedrive.isOneDriveAuthTemporarilyUnavailable
import io.vaultix.vaultix.remote.onedrive.toOneDriveUserMessage
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 连接自检的结果（4 态，刻意不塌成 `Boolean` —— "没测过"和"测过但失败"是两件事）。 */
enum class ProbeState { IDLE, PROBING, OK, FAILED }

@HiltViewModel
class CloudAccountsViewModel @Inject constructor(
    private val inventory: CloudAccountInventory,
    private val connector: CloudAccountConnector,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val accounts: List<CloudAccount> = emptyList(),
        // ---- 「添加账号」表单（默认收起；没有任何账号时直接展开）----
        val formVisible: Boolean = false,
        val kind: CloudAccountKind = CloudAccountKind.WEBDAV,
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
        val passwordVisible: Boolean = false,
        /**
         * 自检状态。
         *
         * ⚠️ [ProbeState.FAILED] 时凭据**已经回滚擦掉了**（见 `probe`）——
         * 界面要说清"没保存"，别让用户以为配好了只是连不上。
         */
        val probe: ProbeState = ProbeState.IDLE,
        val probeMessage: String? = null,
        /** 任一动作在进行（登录 / 自检 / 注销）。 */
        val busy: Boolean = false,
        val error: String? = null,
        /**
         * OneDrive 的**断开原因**（按账号存）。
         *
         * ⚠️ 为什么不能只用 [error] / [notice]：那两个是**一次性 snackbar**，两秒就没了。
         * 而账号卡上的"未连接"是**持续状态** —— 用户隔天回来看见"未连接"，却想不起
         * 当时那句一闪而过的原因，只能靠猜。这里把原因**挂在账号上**，让它跟着状态一起显示。
         *
         * ⚠️ 取值必须区分两类（见 `oneDriveMessage`）：**临时不可用**要"稍后重试"，
         * **真失败**要"重新登录" —— 混成一句话会让用户白跑一次登录流程。
         * 这正是 [io.vaultix.vaultix.remote.onedrive.OneDriveAuthTemporarilyUnavailableException]
         * 存在的理由，UI 侧不能把它丢掉。
         */
        val oneDriveIssues: Map<String, String> = emptyMap(),
        /** 待确认注销的账号（弹出确认框，且**先算好影响面**）。 */
        val pendingRemoval: CloudAccount? = null,
        /** 刚做完的动作的成功提示（一次性，由 UI 读后清）。 */
        val notice: String? = null,
    ) {
        /** 当前输入的服务器地址是否是**明文** HTTP（局域网 NAS 的常见情形）。 */
        val insecureHttp: Boolean
            get() = serverUrl.trim().startsWith("http://", ignoreCase = true)

        /** WebDAV 表单能不能提交自检。 */
        val canProbe: Boolean
            get() = serverUrl.isNotBlank() && username.isNotBlank() &&
                password.isNotEmpty() && !busy && probe != ProbeState.PROBING
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    // ---------------------------------------------------------------- 读

    /**
     * 重新读一遍账号清单。
     *
     * ⚠️ 每次进页面都重读（而不是只读一次就缓存）：账号的**连接状态会变**
     * （token 过期、凭据被清、库被移除），缓存下来的"已连接"会变成一句假话。
     */
    fun refresh() {
        viewModelScope.launch {
            val accounts = inventory.list()
            _state.update {
                it.copy(
                    loading = false,
                    accounts = accounts,
                    // 一个账号都没有 ⇒ 表单直接展开（否则用户无从下手）。
                    formVisible = it.formVisible || accounts.isEmpty(),
                )
            }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    // ---------------------------------------------------------------- 表单

    fun showAddForm() = _state.update {
        it.copy(formVisible = true, error = null, probe = ProbeState.IDLE, probeMessage = null)
    }

    fun hideAddForm() = _state.update {
        it.copy(
            formVisible = false,
            error = null,
            probe = ProbeState.IDLE,
            probeMessage = null,
            serverUrl = "",
            username = "",
            password = "",
            passwordVisible = false,
        )
    }

    /**
     * **重新配置**一个已连不上的 WebDAV 账号：预填地址与账号名，只让用户重输密码。
     *
     * ## 为什么必须有这条（改密码是网盘库最常见的故障）
     *
     * 没有它，用户对一个"未连接"的账号只有一条路：注销 → 重新添加。
     * 那会把**账号名也一起丢掉** —— 而改密码时账号名通常没变，
     * 逼用户重打一遍是纯粹的额外负担（域账号 `DOMAIN\user` 还特别容易打错）。
     *
     * ## 起点为什么用 [CloudAccount.browseRoot]
     *
     * 它是该账号**真实可用的目录 URL**（含路径，不只是 scheme://authority）——
     * 而账号卡上的 `label` 是 `serverOf()` 的结果，**砍掉了路径**。
     * 用 label 预填会让"库在 `https://nas/dav/Vaultix/`"变成 `https://nas`，
     * 列目录直接 404。
     *
     * ⚠️ 刻意**不预填密码**（见 `CloudAccountConnector.readWebDavUsername`）：
     * 已保存的密码不该搬进 UI 状态。
     */
    fun startReconfigure(account: CloudAccount) {
        val root = account.browseRoot
        _state.update {
            it.copy(
                formVisible = true,
                kind = CloudAccountKind.WEBDAV,
                serverUrl = root.orEmpty(),
                username = connector.readWebDavUsername(account.storedId).orEmpty(),
                password = "",
                passwordVisible = false,
                probe = ProbeState.IDLE,
                probeMessage = null,
                error = null,
            )
        }
    }

    fun onKindChange(kind: CloudAccountKind) =
        _state.update { it.copy(kind = kind, error = null, probe = ProbeState.IDLE, probeMessage = null) }

    fun onServerUrlChange(value: String) =
        _state.update { it.copy(serverUrl = value, error = null, probe = ProbeState.IDLE) }

    fun onUsernameChange(value: String) =
        _state.update { it.copy(username = value, error = null, probe = ProbeState.IDLE) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, error = null, probe = ProbeState.IDLE) }

    fun onPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(passwordVisible = visible) }

    // ---------------------------------------------------------------- WebDAV 自检

    /**
     * 保存凭据 → 自检 → 失败**回滚擦掉**。
     *
     * ⚠️ 顺序不可颠倒（凭据必须先落盘，来源对象才构造得出来 —— 见 `CloudAccountConnector`）。
     * 但本页与添加页的**结尾**不同：这里成功后凭据**留在原地**
     * （配账号就是本页的目的），失败则必须擦掉，否则用户每试一次错密码
     * 就在加密存储里留一格永远用不到的密文。
     */
    fun probeWebDav() {
        val current = _state.value
        val problem = validateWebDav(current)
        if (problem != null) {
            _state.update { it.copy(error = problem, probe = ProbeState.FAILED, probeMessage = problem) }
            return
        }
        _state.update { it.copy(busy = true, error = null, probe = ProbeState.PROBING, probeMessage = null) }
        viewModelScope.launch {
            val directoryUrl = connector.normalizeServerUrl(current.serverUrl)
            // ① 先存凭据（否则第 ② 步的来源对象构造时就取不到凭据）
            val credentialId = connector.saveWebDavCredential(
                serverUrl = current.serverUrl,
                username = current.username,
                password = current.password,
            )
            // ② 自检
            val outcome = runCatching {
                connector.webDavSource(credentialId, directoryUrl).testConnection()
            }.getOrElse { Result.failure(it) }

            if (outcome.isSuccess) {
                _state.update {
                    it.copy(
                        busy = false,
                        probe = ProbeState.OK,
                        probeMessage = null,
                        password = "",
                        notice = SETTINGS_CLOUD_PROBE_OK,
                    )
                }
                refresh()
                return@launch
            }
            // ③ 失败 ⇒ 擦掉刚写的凭据。⚠️ 这里没有"库已用上它"的可能：
            //    本页只做配置，不会走到建库那一步。
            connector.removeWebDavCredential(credentialId)
            val message = outcome.exceptionOrNull()?.message ?: SETTINGS_CLOUD_PROBE_FAILED
            _state.update {
                it.copy(busy = false, probe = ProbeState.FAILED, probeMessage = message)
            }
            refresh()
        }
    }

    // ---------------------------------------------------------------- OneDrive

    /**
     * 登录 / 换号。
     *
     * @param forceAccountChooser 换号时**必须**为 true：默认的 `SELECT_ACCOUNT`
     *   在"只有一个缓存账户"时会被 MSAL 优化成静默登录，用户点了「切换账号」
     *   却仍以原账户进来（与 Bastion 同一处的做法一致：先 signOut 再 signIn）。
     */
    fun signInOneDrive(activity: Activity, forceAccountChooser: Boolean = false) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            if (forceAccountChooser) {
                // 注销是尽力而为：MSAL 侧失败也**不该**卡住换号。
                connector.signOutOneDrive(null)
            }
            connector.signInOneDrive(activity, forceAccountChooser)
                .onSuccess {
                    // 登录成功 ⇒ 清掉该账号的断开原因（它已经不成立了）。
                    //
                    // ⚠️ 这里必须把**整张表清掉**，不能按 `session.accountId` 精确删：
                    // 写入侧用的键是 `account.storedId`（取自库的 origin），
                    // 而 `session.accountId` 是 MSAL 侧的 id —— 二者**形状不同**
                    // （差 `.utid` 后缀，见 `matchesStoredAccountId` 的 KDoc）。
                    // 按 MSAL id 删会静默删不掉，表现为"登录成功了但红字还挂着"。
                    // 表本身很小（账号数个位数），清空是最简单且不会错的做法。
                    _state.update {
                        it.copy(
                            busy = false,
                            error = null,
                            notice = SETTINGS_CLOUD_ONEDRIVE_SIGNED_IN,
                            oneDriveIssues = emptyMap(),
                        )
                    }
                    refresh()
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = oneDriveMessage(error)) }
                }
        }
    }

    /** 拿不到 Activity 时的如实上报（不静默禁用按钮 —— 那样用户只看到"点了没反应"）。 */
    fun reportMissingActivity() {
        _state.update { it.copy(error = SETTINGS_CLOUD_ACTIVITY_MISSING) }
    }

    /**
     * 探测某个 OneDrive 账号**当前**能不能刷新出登录态，并把结果记进 [UiState.oneDriveIssues]。
     *
     * ## 为什么必须真去探一次，而不是只看 `account.connected`
     *
     * `connected` 只回答"MSAL 缓存里还有没有这个账户"，**回答不了"这个账户现在还能不能用"**。
     * 而 OneDrive 最恼人的故障恰恰是这个组合：**账户还在（connected = true）、
     * 但静默刷新被系统掐断**（设备打盹 + 电池优化未豁免）。此时界面显示"已连接"，
     * 直到用户去读库才失败 —— 那时人已经在另一个页面了，看不到任何解释。
     *
     * ⚠️ **只在 connected 时探**：未连接说明登录态本来就没了，那是"需要重新登录"
     * （界面已经如实写着），再探一次是浪费一次网络往返，还会把真正的"未连接"盖成
     * "暂时不可用"。
     * ⚠️ 探测失败**不写成 [error]**：它是**背景探测**，不是用户刚触发的动作 ——
     * 弹一条 snackbar 出来会让人以为是自己的操作出了问题。只记进账号状态即可。
     */
    fun probeOneDriveHealth(account: CloudAccount) {
        if (account.kind != CloudAccountKind.ONEDRIVE || !account.connected) return
        viewModelScope.launch {
            connector.probeOneDriveToken(account.storedId)
                .onSuccess {
                    _state.update { it.copy(oneDriveIssues = it.oneDriveIssues - account.storedId) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(oneDriveIssues = it.oneDriveIssues + (account.storedId to oneDriveMessage(error)))
                    }
                }
        }
    }

    // ---------------------------------------------------------------- 注销 / 移除

    /**
     * 请求注销一个账号 —— **只弹确认**，真正删除在 [confirmRemoval]。
     *
     * ⚠️ 确认框里必须写出**影响面**（会牵连几个库），这是当初把注销留到"随后补"的理由：
     * 凭据是账号级的，删掉它 = 同账号的**所有**库下次都打不开。
     * 用户不该在不知道这一点的情况下按下确认。
     */
    fun requestRemoval(account: CloudAccount) =
        _state.update { it.copy(pendingRemoval = account, error = null) }

    fun dismissRemoval() = _state.update { it.copy(pendingRemoval = null) }

    /**
     * 执行注销。
     *
     * ⚠️ **只删凭据/登录态，不删库行**：库还在列表里，只是变成"需重新连接"。
     * 这是刻意的 —— 删凭据是"撤销授权"，删库是"放弃数据"，后者该由用户在
     * 「密码库管理」里单独决定（定稿 §11.5「移除库之后远程数据仍在」是同一条原则）。
     */
    fun confirmRemoval() {
        val account = _state.value.pendingRemoval ?: return
        _state.update { it.copy(busy = true, error = null, pendingRemoval = null) }
        viewModelScope.launch {
            when (account.kind) {
                CloudAccountKind.WEBDAV -> connector.removeWebDavCredential(account.storedId)
                CloudAccountKind.ONEDRIVE -> connector.signOutOneDrive(account.storedId)
            }
            _state.update {
                it.copy(
                    busy = false,
                    notice = if (account.kind == CloudAccountKind.WEBDAV) {
                        SETTINGS_CLOUD_REMOVED_WEBDAV
                    } else {
                        SETTINGS_CLOUD_REMOVED_ONEDRIVE
                    },
                )
            }
            refresh()
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun validateWebDav(current: UiState): String? {
        if (current.serverUrl.isBlank() || current.username.isBlank() || current.password.isEmpty()) {
            return SETTINGS_CLOUD_FIELDS_MISSING
        }
        val parsed = current.serverUrl.trim().toHttpUrlOrNull()
            ?: return SETTINGS_CLOUD_INVALID_SERVER
        // ★ 提前拦「把凭据写进 URL」（`https://user:pass@nas/dav`）：
        //   那种 URL 会让密码进日志、崩溃报告、代理记录。
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return SETTINGS_CLOUD_CREDENTIALS_IN_URL
        }
        return null
    }

    /**
     * OneDrive 错误分流 —— **两类错误的用户动作不同，绝不能混成一句话**：
     * 临时不可用（系统 WebView/浏览器正在更新）要"稍后重试"，真失败要"重新登录"。
     */
    private fun oneDriveMessage(error: Throwable): String =
        if (error.isOneDriveAuthTemporarilyUnavailable()) {
            SETTINGS_CLOUD_ONEDRIVE_TEMP_UNAVAILABLE
        } else {
            error.toOneDriveUserMessage(SETTINGS_CLOUD_ONEDRIVE_FAILED)
        }

    private companion object {
        // ⚠️ 文案常量而不是 stringResource：ViewModel 拿不到 Context。
        //   这些句子与添加页的 `add_cloud_*` 同义但**场景不同**（那是"连上就能选库"，
        //   这里是"配好一个账号"），刻意不复用同一条 —— 混用会让一处的改动咬到另一处。
        const val SETTINGS_CLOUD_FIELDS_MISSING = "请填写服务器地址、账号和密码"
        const val SETTINGS_CLOUD_INVALID_SERVER = "服务器地址不是有效的 URL"
        const val SETTINGS_CLOUD_CREDENTIALS_IN_URL =
            "请把账号密码填在下面两个框里，不要写进服务器地址（写在地址里会泄露到日志）"
        const val SETTINGS_CLOUD_PROBE_OK = "连接成功，账号已保存"
        const val SETTINGS_CLOUD_PROBE_FAILED = "无法连接该服务器"
        const val SETTINGS_CLOUD_ONEDRIVE_SIGNED_IN = "已登录 Microsoft 账号"
        const val SETTINGS_CLOUD_ONEDRIVE_FAILED = "Microsoft 登录失败"
        const val SETTINGS_CLOUD_ONEDRIVE_TEMP_UNAVAILABLE =
            "系统登录组件正忙（可能在更新浏览器），请稍后重试"
        const val SETTINGS_CLOUD_ACTIVITY_MISSING =
            "无法拉起 Microsoft 登录页（当前页面拿不到 Activity），请重启应用后重试"
        const val SETTINGS_CLOUD_REMOVED_WEBDAV = "已删除该 WebDAV 账号的凭据"
        const val SETTINGS_CLOUD_REMOVED_ONEDRIVE = "已注销该 Microsoft 账号"
    }
}
