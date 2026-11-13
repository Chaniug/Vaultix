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
 *
 * ## ★ 2026-11-13（#289）：连接成功后闪退，重进账号却已添加
 *
 * 当时的定性：凭据落盘成功 ⇒ 崩点在**成功之后**；工程里没有全局
 * `CoroutineExceptionHandler`，本页所有动作原先都是裸 `viewModelScope.launch`
 * ⇒ 清单读取链路里任一处抛出都会**杀进程**。据此把动作收口到 [launchGuarded]
 * （异常转可见错误态、重 IO 显式下 IO）。**这套协程异常边界本身仍必要，保留。**
 *
 * ## ★★ 2026-11-13（#290）：真机日志 + 字节码反转了上面这条归因
 *
 * 用户「点注销就闪退」的 logcat 指向 `NoticeSnackbar` → `stringResource(0)` 抛
 * `Resources$NotFoundException`，栈里是 `Recomposer.performRecompose` /
 * `Choreographer` ——**Compose 重组期异常，不经过 `viewModelScope`，
 * [launchGuarded] 根本兜不住**。真因是下面这批 `SETTINGS_CLOUD_*` 原先写成
 * `const val = R.string.*`：AGP 编译期 R 类里资源 id 是 `0x0` 占位、并非运行时真值，
 * Kotlin `const val` 要求编译期常量 ⇒ 把 **0 内联死**，于是 `notice = 0` /
 * `CloudError.Res(0)` → UI 变量式 `stringResource(0)` → 杀进程。
 * `probeWebDav()` 成功同样设 `notice = SETTINGS_CLOUD_PROBE_OK`，所以 #289
 * 「连上就闪退」极可能也是同一个 0，而不是"成功之后某处 IO 抛异常"。
 *
 * ⇒ 修法：① 资源 id **一律不准写进 `const val`**（改普通 `val`，运行时读真值）；
 * ② UI 侧两个变量式解析点（`NoticeSnackbar` / `CloudError.Res`）加
 * `Resources.ID_NULL` 守卫，任何路径再漏下 0 也只降级、不崩。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.common.logging.VaultixLog
import io.vaultix.vaultix.R
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountConnector
import io.vaultix.vaultix.remote.CloudAccountInventory
import io.vaultix.vaultix.remote.CloudAccountKind
import io.vaultix.vaultix.remote.onedrive.isOneDriveAuthTemporarilyUnavailable
import io.vaultix.vaultix.remote.onedrive.toOneDriveUserMessage
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 连接自检的结果（4 态，刻意不塌成 `Boolean` —— "没测过"和"测过但失败"是两件事）。 */
enum class ProbeState { IDLE, PROBING, OK, FAILED }

@HiltViewModel
class CloudAccountsViewModel @Inject constructor(
    private val inventory: CloudAccountInventory,
    private val connector: CloudAccountConnector,
    // ★ 2026-10-20：只为把**我们自己的**资源 id 解析成一句话（校验文案 / probe 兜底）。
    //   来源层已翻译好的 `error.message` 不经过它，原样展示。
    @ApplicationContext private val context: Context,
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
        /**
         * 表单/动作错误。
         *
         * ⚠️ 是**密封类型**而不是 `String?`：两类错误的来源不同，混成 String 就是
         * 审计 §3.4 那个洞（我们自己写的中文进了 Kotlin，`strings.xml` 里没有它）。
         * 见 [CloudError]。
         */
        val error: CloudError? = null,
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
        /** 刚做完的动作的成功提示（一次性，由 UI 读后清）。⚠️ 同样是**资源 id**。 */
        @StringRes val notice: Int? = null,
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
        launchGuarded("refreshAccounts") {
            // ★ #289：清单读取是 Room Flow + Keystore 解密 + MSAL 会话枚举，
            //   既不能占主线程，更不能因为其中一处抛异常就让整个进程退出。
            val accounts = withContext(Dispatchers.IO) { inventory.list() }
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
            // ⚠️ `probeMessage` 仍是 String（它同时承载来源层那句已翻译好的话），
            //   而校验文案是我们自己的资源 id ⇒ 在这里就地解析成一句话再塞进去。
            //   UI 侧对 [UiState.error] 走 `stringResource`，两条路径最终显示的是同一句话。
            val text = context.getString(problem)
            _state.update {
                it.copy(error = CloudError.Res(problem), probe = ProbeState.FAILED, probeMessage = text)
            }
            return
        }
        _state.update { it.copy(busy = true, error = null, probe = ProbeState.PROBING, probeMessage = null) }
        launchGuarded("probeWebDav") {
            // ★ #289：整块挪到 IO。凭据写入是 Keystore AES-GCM 加密 +
            //   SharedPreferences 写、自检是网络往返，原先全压在 viewModelScope
            //   默认的主线程上（ANR / 卡顿），且 Keystore 的 checked exception
            //   会直接冒泡成进程退出 —— 正是真机「账号已保存却闪退」的形状。
            withContext(Dispatchers.IO) {
                val directoryUrl = connector.normalizeServerUrl(current.serverUrl)
                // ① 先存凭据（否则第 ② 步的来源对象构造时就取不到凭据）
                val credentialId = connector.saveWebDavCredential(
                    serverUrl = current.serverUrl,
                    username = current.username,
                    password = current.password,
                )
                // ② 自检。⚠️ 取消必须原样重抛：旧写法用 runCatching 把
                //    CancellationException 也吞成「失败」，会误删刚写好的凭据。
                val outcome = runCatching {
                    connector.webDavSource(credentialId, directoryUrl).testConnection()
                }.getOrElse { error ->
                    // 取消 / Error 原样重抛；其余才转成「自检失败」并回滚凭据。
                    if (error is CancellationException || error is Error) throw error
                    Result.failure(error)
                }

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
                    return@withContext
                }
                // ③ 失败 ⇒ 擦掉刚写的凭据。⚠️ 这里没有"库已用上它"的可能：
                //    本页只做配置，不会走到建库那一步。
                //    ★ #289：回滚本身也可能抛（Keystore 故障），不能让它盖掉原始原因。
                runCatching { connector.removeWebDavCredential(credentialId) }
                    .onFailure { rollback ->
                        VaultixLog.w(TAG) { "回滚 WebDAV 凭据失败：${rollback.message}" }
                    }
                val message = outcome.exceptionOrNull()?.message
                    ?: context.getString(SETTINGS_CLOUD_PROBE_FAILED)
                _state.update {
                    it.copy(busy = false, probe = ProbeState.FAILED, probeMessage = message)
                }
                refresh()
            }
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
        launchGuarded("signInOneDrive") {
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
                    // 来源层已经把原因翻译成人话了 ⇒ 走 [CloudError.Detail]，套一句前缀再展示。
                    _state.update { it.copy(busy = false, error = CloudError.Detail(oneDriveMessage(error))) }
                }
        }
    }

    /** 拿不到 Activity 时的如实上报（不静默禁用按钮 —— 那样用户只看到"点了没反应"）。 */
    fun reportMissingActivity() {
        _state.update { it.copy(error = CloudError.Res(SETTINGS_CLOUD_ACTIVITY_MISSING)) }
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
        launchGuarded("probeOneDriveHealth") {
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
        launchGuarded("confirmRemoval") {
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

    /**
     * 本页所有 `viewModelScope.launch` 的**唯一出口**（#289）。
     *
     * ## 为什么必须有这一层
     *
     * 工程里**没有**全局 `CoroutineExceptionHandler`（`.ai/issues/05-KDBX本地库-part2.md`
     * 的 #98 实测记录）：`viewModelScope.launch` 里任何未捕获异常都会冒泡成**进程退出** ——
     * 用户看到的就是「点完测试连接、账号明明存进去了，App 却闪退」。
     *
     * 连接成功之后仍要跑 [refresh]：Room Flow + Keystore 解密 + MSAL 会话枚举，
     * 这条链路上任何一处抛出都会杀掉进程；而凭据此时**已经落盘**
     * ⇒ 重进后账号还在，与真机现象完全吻合。
     *
     * ⇒ 异常一律转成**看得见的错误态**：写日志（logcat 可取证）、把 `busy` /
     * `PROBING` 拉回终态、把原因放进 `probeMessage` / `error`。**绝不重抛非取消异常**。
     *
     * ⚠️ [CancellationException] 必须原样重抛：页面返回导致的取消不是失败，
     * 记 ERROR 或把 UI 改成「失败」都是假的，且取消态下每个挂起点都会立刻再抛。
     *
     * ⚠️ 刻意**不**在这里改 dispatcher：`signInOneDrive` 走 MSAL，必须在主线程调用。
     * 真正重的 IO（凭据读写 / 清单读取）由各调用点自己 [withContext] 到 [Dispatchers.IO]。
     */
    private fun launchGuarded(label: String, block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch {
            // ⚠️ 用 runCatching 而不是 `catch (Exception)`：detekt 的
            //   TooGenericExceptionCaught 会拦宽泛 catch，但这个边界**本来就该宽** ——
            //   它的职责正是「来源层抛什么都别杀进程」。与 #242 的写法一致。
            runCatching { block() }.onFailure { error ->
                // 取消与 Error 原样重抛：页面返回不是失败，Error 也不该被一句红字吞掉。
                if (error is CancellationException || error is Error) throw error
                val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                VaultixLog.e(TAG, error) { "$label 失败：$message" }
                _state.update {
                    it.copy(
                        loading = false,
                        busy = false,
                        probe = if (it.probe == ProbeState.PROBING) ProbeState.FAILED else it.probe,
                        probeMessage = it.probeMessage ?: message,
                        error = it.error ?: CloudError.Detail(message),
                    )
                }
            }
        }

    @StringRes
    private fun validateWebDav(current: UiState): Int? {
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
            context.getString(SETTINGS_CLOUD_ONEDRIVE_TEMP_UNAVAILABLE)
        } else {
            error.toOneDriveUserMessage(context.getString(SETTINGS_CLOUD_ONEDRIVE_FAILED))
        }

    private companion object {
        const val TAG = "CloudAccounts"

        // ★ 2026-10-20（审计 §3.4）：常量从**中文串**改成 **资源 id**。
        //
        // 改前的注释写的是「文案常量而不是 stringResource：ViewModel 拿不到 Context」——
        // 前半句对，后半句推出了错误结论：ViewModel 拿不到 Context，**所以**应该给
        // `@StringRes Int` 让 UI 侧去取，而不是把中文抄进 Kotlin。两条后果：
        // ① 这批句子在 `strings.xml` 里不存在 ⇒ 将来要出 `values-en` 时**翻不到**；
        // ② 孤儿串检查器看不见它们（那个脚本查的是"资源没人用"），于是**永远没人发现**。
        //
        // 这些句子与添加页的 `add_cloud_*` 同义但**场景不同**（那是"连上就能选库"，
        // 这里是"配好一个账号"），刻意不复用同一条 —— 混用会让一处的改动咬到另一处。
        //
        // ★★ 2026-11-13（#290）：上面这批原来 11 个都是 `const val`，真机上全部变成 **0**。
        //   AGP 编译期 R 类（`compile_symbol_list/.../R.txt`、`compile_r_class_jar/.../R.jar`）
        //   里资源 id 是 `0x0` 占位；`const val` 必须是编译期常量 ⇒ Kotlin 直接把 0
        //   内联进每个调用点（`javap -constants` 可见 `SETTINGS_CLOUD_* = 0`）。
        //   运行时真值只存在于 `runtime_symbol_list` / `compile_and_runtime_r_class_jar`
        //   （`0x7f0f01xx`）。于是 `notice = 0` → UI 变量式 `stringResource(0)` →
        //   `Resources$NotFoundException` 在重组期杀进程（#289 / #290 两次闪退同源）。
        //   ⇒ **资源 id 只能用普通 `val`（或直接写 `R.string.*`），永不 `const val`**：
        //     `val` 在运行时读 `R$string.*` 的真值，不会被编译期内联。
        //   配套：`CloudAccountsScreen.safeStringRes()` 对 `Resources.ID_NULL` 再兜一层。
        //   ⚠️ `TAG` 是普通字符串常量，`const val` 无害；别连它一起改。
        val SETTINGS_CLOUD_FIELDS_MISSING = R.string.cloud_msg_fields_missing
        val SETTINGS_CLOUD_INVALID_SERVER = R.string.cloud_msg_invalid_server
        val SETTINGS_CLOUD_CREDENTIALS_IN_URL = R.string.cloud_msg_credentials_in_url
        val SETTINGS_CLOUD_PROBE_OK = R.string.cloud_msg_probe_ok
        val SETTINGS_CLOUD_PROBE_FAILED = R.string.cloud_msg_probe_failed
        val SETTINGS_CLOUD_ONEDRIVE_SIGNED_IN = R.string.cloud_msg_onedrive_signed_in
        val SETTINGS_CLOUD_ONEDRIVE_FAILED = R.string.cloud_msg_onedrive_failed
        val SETTINGS_CLOUD_ONEDRIVE_TEMP_UNAVAILABLE =
            R.string.cloud_msg_onedrive_temp_unavailable
        val SETTINGS_CLOUD_ACTIVITY_MISSING = R.string.cloud_msg_activity_missing
        val SETTINGS_CLOUD_REMOVED_WEBDAV = R.string.cloud_msg_removed_webdav
        val SETTINGS_CLOUD_REMOVED_ONEDRIVE = R.string.cloud_msg_removed_onedrive
    }
}
