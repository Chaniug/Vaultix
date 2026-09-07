package com.bastion.app.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.bastion.app.R
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OneDriveAccountSession(
    val accountId: String,
    val username: String,
    val displayName: String,
    val authority: String? = null,
    val accessToken: String? = null
)

class OneDriveAuthTemporarilyUnavailableException(
    message: String = "OneDrive 暂时无法刷新登录状态。请关闭系统电池优化，或点亮屏幕并重新打开 Bastion 后再试。",
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class OneDriveAuthManager(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 交互式登录。
     *
     * @param activity 用于拉起授权页面的 Activity。
     * @param forceAccountChooser true 时强制弹出账户选择框。切换账户必须用它：
     *   默认 [Prompt.SELECT_ACCOUNT] 在仅有一个缓存账户时会被 MSAL 优化成静默登录，
     *   用户点了「切换账户」却仍以原账户进入。
     */
    suspend fun signIn(
        activity: Activity,
        forceAccountChooser: Boolean = false
    ): OneDriveAccountSession = withContext(Dispatchers.Main) {
        val application = getApplication()
        suspendCancellableCoroutine { continuation ->
            val builder = AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .withScopes(SCOPES)
            // LOGIN 会强制重新走登录流程并清掉浏览器会话 Cookie，
            // 是「切换账户 / 注销后重登」时能真正换号的唯一可靠方式。
            if (forceAccountChooser) {
                builder.withPrompt(Prompt.LOGIN)
            } else {
                builder.withPrompt(Prompt.SELECT_ACCOUNT)
            }
            val parameters = builder
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        continuation.resume(authenticationResult.toSession())
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resumeWithException(exception)
                    }

                    override fun onCancel() {
                        continuation.resumeWithException(IllegalStateException("已取消 OneDrive 登录"))
                    }
                })
                .build()
            application.acquireToken(parameters)
        }
    }

    suspend fun getCachedSession(): OneDriveAccountSession? {
        val account = getAccounts().firstOrNull() ?: return null
        return account.toSession()
    }

    /**
     * 列出 MSAL 本地缓存的全部账户，供 UI 提供「切换账户」能力。
     *
     * 注意：这与 Bastion 自己保存的凭据不是一回事。MSAL 的 token 缓存独立于
     * 业务数据库配置存在，若不显式移除，即使清掉了本地配置，
     * 下次 [signIn] 也会被静默复用旧账户，导致用户无法真正换号。
     */
    suspend fun listCachedSessions(): List<OneDriveAccountSession> {
        return getAccounts().mapNotNull { account ->
            runCatching { account.toSession() }.getOrNull()
        }
    }

    /**
     * 注销指定账户：清除 MSAL 本地 token 缓存。
     *
     * 只清 Bastion 侧配置是不够的 —— MSAL 缓存仍在的话，
     * 下次 [signIn] 会直接复用旧账户（不弹选择框），
     * 表现为「点了切换账号却还是原账号」。因此这里必须显式
     * 调用 [IMultipleAccountPublicClientApplication.removeAccount]。
     *
     * 失败不抛出：注销是尽力而为的操作。即便 MSAL 侧失败，
     * 上层仍应继续清理 Bastion 自己的配置，不能卡住用户。
     *
     * @param accountId 要注销的账户 ID；为 null 时注销全部缓存账户。
     * @return 成功移除的账户数量。
     */
    suspend fun signOut(accountId: String? = null): Int = withContext(Dispatchers.IO) {
        val application = runCatching { getApplication() }.getOrNull() ?: return@withContext 0
        val targets = if (accountId == null) {
            runCatching { application.getAccounts().orEmpty() }.getOrNull().orEmpty()
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
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                    )
                }
            }.getOrDefault(false)
            if (ok) removed++
        }
        removed
    }

    suspend fun acquireAccessToken(accountId: String): OneDriveAccountSession {
        val application = getApplication()
        val account = getAccount(accountId)
            ?: throw IllegalStateException("OneDrive 账户已失效，请重新登录")

        return withContext(Dispatchers.IO) {
            throwIfSilentRefreshBlockedByPowerState()
            val result = try {
                application.acquireTokenSilent(
                    SCOPES.toTypedArray(),
                    account,
                    account.authority ?: COMMON_AUTHORITY
                )
            } catch (exception: MsalException) {
                if (exception.isPowerOptimizationRefreshFailure()) {
                    throw OneDriveAuthTemporarilyUnavailableException(cause = exception)
                }
                throw exception
            }
            result.toSession()
        }
    }

    private fun throwIfSilentRefreshBlockedByPowerState() {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val isIdle = powerManager.isDeviceIdleMode
        val isOptimized = !powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        if (isIdle && isOptimized) {
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
                    R.raw.onedrive_msal_config
                )
            }
            cachedApplication = application
            application
        }
    }

    private suspend fun getAccounts(): List<IAccount> {
        val application = getApplication()
        return withContext(Dispatchers.IO) {
            application.getAccounts().orEmpty()
        }
    }

    private suspend fun getAccount(accountId: String): IAccount? {
        val application = getApplication()
        return withContext(Dispatchers.IO) {
            application.getAccount(accountId)
        }
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
            accessToken = accessToken
        )
    }

    private fun IAuthenticationResult.toSession(): OneDriveAccountSession {
        return account.toSession(accessToken = accessToken)
    }

    companion object {
        val SCOPES: List<String> = listOf(
            "User.Read",
            "Files.ReadWrite"
        )

        private const val COMMON_AUTHORITY = "https://login.microsoftonline.com/common"
        @Volatile
        private var cachedApplication: IMultipleAccountPublicClientApplication? = null
        private val applicationMutex = Mutex()
    }
}

fun Throwable.isOneDriveAuthTemporarilyUnavailable(): Boolean {
    return generateSequence(this) { it.cause }.any { error ->
        error is OneDriveAuthTemporarilyUnavailableException ||
            error.message.orEmpty().contains("Connection is not available to refresh token", ignoreCase = true) ||
            error.message.orEmpty().contains("power optimization", ignoreCase = true) ||
            error.message.orEmpty().contains("doze mode", ignoreCase = true) ||
            error.message.orEmpty().contains("app is standby", ignoreCase = true)
    }
}

fun Throwable.toOneDriveUserMessage(fallback: String = "OneDrive 操作失败"): String {
    if (isOneDriveAuthTemporarilyUnavailable()) {
        return "OneDrive 暂时无法刷新登录状态。请关闭系统电池优化，或点亮屏幕并重新打开 Bastion 后再试。"
    }
    return message?.takeIf { it.isNotBlank() } ?: fallback
}

private fun Throwable.isPowerOptimizationRefreshFailure(): Boolean {
    return isOneDriveAuthTemporarilyUnavailable()
}
