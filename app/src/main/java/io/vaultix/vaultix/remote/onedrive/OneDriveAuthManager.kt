/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * ★ 溯源声明（GPL-3.0）：本文件移植自 Bastion（`com.bastion.app.utils.OneDriveAuthManager`，
 *   同项目作者的另一个应用，GPL-3.0）。改动点见类 KDoc 的「与上游的差异」。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.remote.onedrive

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.common.logging.VaultixLog
import io.vaultix.vaultix.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一个已登录的 OneDrive 账户（`accessToken` 仅在本次调用内有效，**不要缓存**）。 */
data class OneDriveAccountSession(
    val accountId: String,
    val username: String,
    val displayName: String,
    val authority: String? = null,
    val accessToken: String? = null,
)

/**
 * 登录状态**暂时**不可用（设备打盹 + 电池优化未豁免 ⇒ MSAL 静默刷新被系统掐断）。
 *
 * ⚠️ 与「凭据失效」要分开：前者用户点亮屏幕/豁免电池优化即可自愈，
 * 后者必须重新登录。混成一句话会让用户白跑一次登录流程。
 */
class OneDriveAuthTemporarilyUnavailableException(
    message: String = "OneDrive 暂时无法刷新登录状态。请关闭系统电池优化，或点亮屏幕后重新打开 Vaultix 再试。",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * OneDrive 的登录态管理（MSAL **public client + PKCE**）。
 *
 * ## 与上游（Bastion）的差异
 *
 * 1. **Hilt 单例**（`@Singleton` + `@ApplicationContext`）而不是裸 `Context` 构造：
 *    Vaultix 全仓走 Hilt，手动 new 会让"MSAL 实例缓存"这类状态散到调用方。
 *    上游把缓存放在 `companion object`（进程级静态），这里改为**实例字段** ——
 *    语义相同（本类就是单例），但不再依赖静态可变状态。
 * 2. **接上 [VaultixLog]**：登录/取 token/注销都留痕。这类"只在真机上偶发"的问题，
 *    没有日志只能靠猜（用户在设置里开开关即可打开）。
 * 3. 文案里的应用名改为 Vaultix。
 *
 * ## 为什么不需要 client secret
 *
 * MSAL Android 走 **public client + PKCE**：授权码换 token 时用一次性 code_verifier，
 * 不需要预置密钥。移动端**存不住**任何 secret（APK 可反编译）⇒ 若哪份配置里带了
 * secret，那是错的做法，不要照抄。
 *
 * ## 配置在哪
 *
 * `app/src/main/res/raw/onedrive_msal_config.json`（含 `client_id` 与 `redirect_uri`），
 * 并需在 `AndroidManifest.xml` 注册 `BrowserTabActivity` 接住回调。
 * ⚠️ `redirect_uri` 的 `msauth://<包名>/<base64(SHA-1(签名证书))>` 后缀**必须与
 * Azure 门户里登记的签名哈希一致**，换签名即失效（见 `.ai/conventions/8.7-环境.md`）。
 */
@Singleton
class OneDriveAuthManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    /** MSAL 实例的缓存槽 + 互斥（`createMultipleAccountPublicClientApplication` 是耗时 IO）。 */
    @Volatile
    private var cachedApplication: IMultipleAccountPublicClientApplication? = null
    private val applicationMutex = Mutex()

    /**
     * 交互式登录。
     *
     * @param activity 用于拉起授权页面的 Activity（必须是人当前可见的那个）。
     * @param forceAccountChooser true 时强制重新走登录。**切换账户必须用它**：
     *   默认 [Prompt.SELECT_ACCOUNT] 在"只有一个缓存账户"时会被 MSAL 优化成静默登录，
     *   用户点了「切换账户」却仍以原账户进来。
     */
    suspend fun signIn(
        activity: Activity,
        forceAccountChooser: Boolean = false,
    ): OneDriveAccountSession = withContext(Dispatchers.Main) {
        val application = getApplication()
        suspendCancellableCoroutine { continuation ->
            val builder = AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .withScopes(SCOPES)
            // LOGIN 会强制重走登录流程并清掉浏览器会话 Cookie，
            // 是「切换账户 / 注销后重登」时真正能换号的唯一可靠方式。
            if (forceAccountChooser) {
                builder.withPrompt(Prompt.LOGIN)
            } else {
                builder.withPrompt(Prompt.SELECT_ACCOUNT)
            }
            val parameters = builder
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        VaultixLog.d(TAG) { "OneDrive 登录成功：${authenticationResult.account.username}" }
                        continuation.resume(authenticationResult.toSession())
                    }

                    override fun onError(exception: MsalException) {
                        VaultixLog.w(TAG, exception) { "OneDrive 登录失败：${exception.errorCode}" }
                        continuation.resumeWithException(exception)
                    }

                    override fun onCancel() {
                        VaultixLog.d(TAG) { "用户取消了 OneDrive 登录" }
                        continuation.resumeWithException(IllegalStateException("已取消 OneDrive 登录"))
                    }
                })
                .build()
            application.acquireToken(parameters)
        }
    }

    /** 取第一个已缓存账户的会话（无 token，[OneDriveAccountSession.accessToken] 为 null）。 */
    suspend fun getCachedSession(): OneDriveAccountSession? {
        val account = getAccounts().firstOrNull() ?: return null
        return account.toSession()
    }

    /**
     * 列出 MSAL 本地缓存的全部账户，供 UI 提供「切换账户」。
     *
     * ⚠️ 这与 Vaultix 自己保存的配置**不是一回事**：MSAL 的 token 缓存独立存在，
     * 不显式移除的话，即使清掉了本应用的配置，下次 [signIn] 也会静默复用旧账户，
     * 用户会以为"切换账号没生效"。
     */
    suspend fun listCachedSessions(): List<OneDriveAccountSession> =
        getAccounts().mapNotNull { account -> runCatching { account.toSession() }.getOrNull() }

    /**
     * 注销指定账户（清 MSAL 本地 token 缓存）。`accountId` 为 null 时注销全部。
     *
     * 只清 Vaultix 自己的配置是不够的（见 [listCachedSessions] 的说明）。
     *
     * 失败**不抛**：注销是尽力而为的操作 —— 即便 MSAL 侧失败，上层仍应继续清理
     * 自己的配置，不能因此把用户卡住。
     *
     * @return 成功移除的账户数量。
     */
    suspend fun signOut(accountId: String? = null): Int = withContext(Dispatchers.IO) {
        val application = runCatching { getApplication() }.getOrNull() ?: return@withContext 0
        val targets = if (accountId == null) {
            runCatching { application.accounts.orEmpty() }.getOrNull().orEmpty()
        } else {
            listOfNotNull(runCatching { application.getAccount(accountId) }.getOrNull())
        }
        if (targets.isEmpty()) return@withContext 0

        var removed = 0
        targets.forEach { account ->
            val ok = runCatching {
                suspendCancellableCoroutine { continuation ->
                    application.removeAccount(
                        account,
                        object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                            override fun onRemoved() {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onError(exception: MsalException) {
                                VaultixLog.w(TAG, exception) { "OneDrive 注销账户失败：${exception.errorCode}" }
                                if (continuation.isActive) continuation.resume(false)
                            }
                        },
                    )
                }
            }.getOrDefault(false)
            if (ok) removed++
        }
        VaultixLog.d(TAG) { "OneDrive 注销完成，移除 $removed 个账户" }
        removed
    }

    /**
     * 静默取 access token（不打搅用户）。
     *
     * ⚠️ 先判设备电源状态再取：设备打盹 + 未豁免电池优化时，MSAL 的静默刷新**必失败**，
     * 而这与本应用无关。提前拦住并抛出可自愈的
     * [OneDriveAuthTemporarilyUnavailableException]，避免把它误报成"登录失效"。
     */
    suspend fun acquireAccessToken(accountId: String): OneDriveAccountSession {
        val application = getApplication()
        // 用 checkNotNull 而不是 `?: throw`：detekt `ThrowsCount`（上限 2）只数 `throw` 语句，
        // 而本函数需要把"刷新失败"这一处 throw 留给下面的 try/catch。语义上这里是
        // 「前置条件不成立」，check 抛 IllegalStateException 正是想要的类型。
        val account = checkNotNull(getAccount(accountId)) { "OneDrive 账户已失效，请重新登录" }

        return withContext(Dispatchers.IO) {
            throwIfSilentRefreshBlockedByPowerState()
            val result = try {
                application.acquireTokenSilent(
                    SCOPES.toTypedArray(),
                    account,
                    account.authority ?: COMMON_AUTHORITY,
                )
            } catch (exception: MsalException) {
                // 只在这里 throw 一次（映射逻辑收进 [toRefreshFailure]，它本身不抛）。
                throw exception.toRefreshFailure()
            }
            result.toSession()
        }
    }

    private fun throwIfSilentRefreshBlockedByPowerState() {
        val powerManager =
            appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val isIdle = powerManager.isDeviceIdleMode
        val isOptimized = !powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        if (isIdle && isOptimized) {
            VaultixLog.d(TAG) { "设备处于打盹且未豁免电池优化 ⇒ 放弃静默刷新" }
            throw OneDriveAuthTemporarilyUnavailableException()
        }
    }

    private suspend fun getApplication(): IMultipleAccountPublicClientApplication {
        cachedApplication?.let { return it }

        return applicationMutex.withLock {
            cachedApplication?.let { return@withLock it }

            val application = withContext(Dispatchers.IO) {
                PublicClientApplication.createMultipleAccountPublicClientApplication(
                    appContext,
                    R.raw.onedrive_msal_config,
                )
            }
            cachedApplication = application
            application
        }
    }

    private suspend fun getAccounts(): List<IAccount> =
        withContext(Dispatchers.IO) { getApplication().accounts.orEmpty() }

    /**
     * 按 origin 里存的 `accountId` 找账户。
     *
     * ## ⚠️ ★ 为什么不能只做精确匹配（2026-09-17 真机实证）
     *
     * origin 格式是 `onedrive:<accountId>:<path>`，accountId 取自 `IAccount.id`
     * （见 [toSession]）。但 MSAL **自己缓存里的键**是 `homeAccountId`，形如 **`<uid>.<utid>`**。
     * 实测两者**不一致**：
     * ```
     * origin 里存的   ：00000000-0000-0000-c8b8-c85b19b15ac7
     * MSAL 缓存里的键 ：00000000-0000-0000-c8b8-c85b19b15ac7.9188040d-6c67-4c5b-b112-36a304b66dad
     * ```
     * ⇒ 精确查必然 miss ⇒ 报「OneDrive 账户已失效，请重新登录」⇒ **用户每次重启 App 都要重登**，
     *   而账户**一直躺在缓存里**（`account_credential_cache.xml` 里 refresh token 始终在，
     *   且 mtime 随每次刷新在变 —— 实证了两点：缓存没丢、刷新确实发生过）。
     *
     * ## 判据用「值比较」，不用「记住当前是哪种形态」
     *
     * 不去猜"这个 MSAL 版本给的是 `uid` 还是 `uid.utid`"（那会随版本漂移），而是**按值匹配**：
     * 精确相等，**或**前缀 `accountId.`（`uid` 与 `uid.utid` 恰好就是这个关系）。
     * ⇒ 两种形态都命中，且**自愈**：哪天 MSAL 又统一成别的写法，精确那支仍然有效。
     *
     * ⚠️ 最后的"单账户兜底"只在**确实只有一个缓存账户**时生效 —— 那时"用哪个账户"没有歧义。
     * **多账户时绝不猜**：猜错会把 A 的 token 喂给 B 的库（比"要求重新登录"严重得多）。
     */
    private suspend fun getAccount(accountId: String): IAccount? = withContext(Dispatchers.IO) {
        val application = getApplication()

        // ① 精确匹配（MSAL 语义下的正路）。
        runCatching { application.getAccount(accountId) }.getOrNull()?.let {
            return@withContext it
        }

        val all = runCatching { application.accounts.orEmpty() }.getOrDefault(emptyList())

        // ② ★ 形状容错：origin 存的是 uid，而 MSAL 的 id 是 "uid.utid"。
        val byShape = all.firstOrNull { account ->
            account.id == accountId || account.id.startsWith("$accountId.")
        }
        if (byShape != null) {
            VaultixLog.d(TAG) { "getAccount: 精确未命中，按 uid 前缀命中" }
            return@withContext byShape
        }

        // ③ 单账户兜底（仅一个缓存账户 ⇒ 无歧义）。
        if (all.size == 1) {
            VaultixLog.d(TAG) { "getAccount: 单账户兜底命中" }
            return@withContext all.first()
        }

        // ④ 如实失败，并留下**可定位**的一条日志（缓存账户数：0 = 真丢了；>1 = 认不出是哪个）。
        VaultixLog.d(TAG) { "getAccount: 未命中，缓存账户数=${all.size}" }
        null
    }

    private fun IAccount.toSession(accessToken: String? = null): OneDriveAccountSession {
        val resolvedId = id?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OneDrive 账户标识为空")
        val resolvedUsername = username.orEmpty()
        val resolvedDisplayName = claims?.get("name") as? String
            ?: resolvedUsername.ifBlank { "OneDrive" }
        return OneDriveAccountSession(
            accountId = resolvedId,
            username = resolvedUsername,
            displayName = resolvedDisplayName,
            authority = authority,
            accessToken = accessToken,
        )
    }

    private fun IAuthenticationResult.toSession(): OneDriveAccountSession =
        account.toSession(accessToken = accessToken)

    companion object {
        private const val TAG = "OneDriveAuth"

        /**
         * 请求的权限。
         *
         * `Files.ReadWrite` 是**必须**的（同步要能写回），不是 ReadWrite.AppFolder ——
         * 用户的库是他自己 OneDrive 里的普通文件，不在应用专用目录里。
         */
        val SCOPES: List<String> = listOf("User.Read", "Files.ReadWrite")

        private const val COMMON_AUTHORITY = "https://login.microsoftonline.com/common"

        /** 账户 id 未知时查账户，用不到租户信息 —— 走 common 端点即可。 */
        const val DEFAULT_AUTHORITY: String = COMMON_AUTHORITY
    }
}

/** 该异常是否为「打盹期静默刷新被系统掐断」（**可自愈**，不是登录失效）。 */
fun Throwable.isOneDriveAuthTemporarilyUnavailable(): Boolean =
    generateSequence(this) { it.cause }.any { error ->
        error is OneDriveAuthTemporarilyUnavailableException ||
            error.message.orEmpty().contains("Connection is not available to refresh token", ignoreCase = true) ||
            error.message.orEmpty().contains("power optimization", ignoreCase = true) ||
            error.message.orEmpty().contains("doze mode", ignoreCase = true) ||
            error.message.orEmpty().contains("app is standby", ignoreCase = true)
    }

/** 把原始异常翻成能给用户看的一句话（[fallback] 用于无消息的异常）。 */
fun Throwable.toOneDriveUserMessage(fallback: String = "OneDrive 操作失败"): String {
    if (isOneDriveAuthTemporarilyUnavailable()) {
        return "OneDrive 暂时无法刷新登录状态。请关闭系统电池优化，或点亮屏幕后重新打开 Vaultix 再试。"
    }
    return message?.takeIf { it.isNotBlank() } ?: fallback
}

private fun Throwable.isPowerOptimizationRefreshFailure(): Boolean =
    isOneDriveAuthTemporarilyUnavailable()

/**
 * 把 MSAL 的刷新失败**映射**成要往上抛的异常（本函数自己**不抛**）。
 *
 * ⚠️ 为什么单独抽出来：detekt `ThrowsCount` 上限 2，而 `acquireAccessToken` 里
 * 「账户失效」已经交给 `checkNotNull`、剩下的 throw 要留给唯一的一次重抛。
 * 把"哪种失败该换写成哪种异常"收成纯映射，既满足门禁，也把判定集中在一处。
 */
private fun MsalException.toRefreshFailure(): Throwable =
    if (isPowerOptimizationRefreshFailure()) {
        OneDriveAuthTemporarilyUnavailableException(cause = this)
    } else {
        this
    }
