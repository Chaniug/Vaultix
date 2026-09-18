/*
 * Vaultix — app / 网盘账号
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **网盘账号的「连接器」** —— 「保存凭据 / 自检 / 构造来源 / 登录 / 注销」的**唯一实现**。
 *
 * ## ★ 为什么要有这一层（它解决的是一个真实的漂移风险）
 *
 * 网盘账号现在有**两个宿主**：
 *
 * | 页面 | 用途 | 生命周期 |
 * |---|---|---|
 * | 设置 → 「网盘账号」 | **管理**账号：配置 / 自检 / 换号 / 注销 | 持久页 |
 * | 「添加密码库 → 从网盘添加」 | **使用**账号：选账号 → 选文件 → 入库 | 导航路由，一返回即销毁 |
 *
 * 两边都要做同一件事：**把凭据写进 `WebDavCredentialStore` 再自检**。
 * 若各自实现一遍，就会出现两条"保存凭据"的支路 ——
 * 而本项目反复被咬的正是**同一件事有两处真相、然后必然漂移**
 * （`.ai/decisions/设置页信息架构-定稿.md` §11.7 明确要求：
 * 「`WebDavCredentialStore` 的**写入必须仍只有一处入口**」）。
 *
 * ⇒ 把那几个**纯动作**收在这里，两个 ViewModel 都只调它。
 * 本类**不持有任何 UI 状态**，也不管目录栈 —— 那些是各自页面的事。
 *
 * ## 边界：什么**不**归这里
 *
 * - 目录栈、选中文件、主密码 ⇒ 那是"选库"这条流程的状态，只有添加页有；
 * - 「列目录后要不要回滚凭据」⇒ **调用方**决定（本类只提供 [removeWebDavCredential]）；
 * - 账号清单 ⇒ [CloudAccountInventory]（从库的 origin 反推）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.remote

import android.app.Activity
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.repository.kdbx.WebDavKdbxFileSource
import io.vaultix.data.repository.kdbx.WebDavUrlBuilder
import io.vaultix.vaultix.remote.onedrive.OneDriveAccountSession
import io.vaultix.vaultix.remote.onedrive.OneDriveAuthManager
import io.vaultix.vaultix.remote.onedrive.OneDriveKdbxFileSource
import io.vaultix.vaultix.remote.onedrive.OneDriveVaultOrigin
import io.vaultix.vaultix.remote.webdav.WebDavCredentialStore
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * 网盘账号的连接动作。
 *
 * @param okHttp 给 WebDAV 来源用 —— 复用 `data:bitwarden` 装配的那一份单例
 *   （自带 Cloudflare 兼容指纹与拦截器）。**不要**在这里新建：
 *   多一份连接池、两套超时行为迟早漂移。
 */
@Singleton
class CloudAccountConnector @Inject constructor(
    private val webDavCredentials: WebDavCredentialStore,
    private val oneDriveAuth: OneDriveAuthManager,
    private val oneDriveFactory: OneDriveKdbxFileSource.Factory,
    private val okHttp: OkHttpClient,
) {

    // ---------------------------------------------------------------- WebDAV

    /**
     * 本次配置对应的凭据 id（`服务器 + 账号` 的哈希，**幂等**）。
     *
     * ⚠️ 同一个「服务器 + 账号」永远得到同一格 —— 所以"重配一次"是覆盖而不是新增，
     * 也不会在加密存储里留下一堆永远用不到的密文。
     */
    fun webDavCredentialIdFor(serverUrl: String, username: String): String =
        webDavCredentials.credentialIdFor(serverUrl, username)

    /**
     * 保存（覆盖）WebDAV 凭据，返回它的 id。
     *
     * ⚠️ **必须**在 [probeWebDav] 之前调用 —— 来源对象取凭据是一个挂起回调，
     * 凭据不在连自检都构造不出来（这是顺序上的硬约束，不是偏好）。
     *
     * ⚠️ 失败时调用方**有责任**调 [removeWebDavCredential] 擦掉：
     * 否则用户每试一次错密码，就在加密存储里留一格永远用不到的密文。
     */
    fun saveWebDavCredential(serverUrl: String, username: String, password: String): String =
        webDavCredentials.save(serverUrl, username, password)

    /** 删除一格 WebDAV 凭据（连接没走通 / 用户主动放弃时调用）。 */
    fun removeWebDavCredential(credentialId: String?) {
        credentialId ?: return
        webDavCredentials.remove(credentialId)
    }

    /**
     * 读回账号名（**不含密码**）。
     *
     * 用途：让用户"重新配置"一个已连不上的账号时，不必连账号名一起重打 ——
     * 多数情况下改的只是密码。
     *
     * ⚠️ 刻意**不返回密码**：把已保存的密码搬进 UI 状态，等于让它多一处停留
     * （会进 Compose 的快照、可能被截图/无障碍服务读到）。密码只该由用户重新输入。
     */
    fun readWebDavUsername(credentialId: String): String? =
        webDavCredentials.read(credentialId)?.username

    /**
     * 构造一个 WebDAV 来源（**不自检**，只给对象）。
     *
     * @param directoryUrl 目录 URL（尾部带 `/`）。见 [normalizeServerUrl]。
     */
    fun webDavSource(credentialId: String, directoryUrl: String): WebDavKdbxFileSource =
        WebDavKdbxFileSource(
            client = okHttp,
            fileUrl = directoryUrl,
            credentialProvider = {
                // ⚠️ 现取而不是缓存：用户可能刚在设置页改过密码，
                //    缓存旧值会让之后**所有**请求 401 —— 而界面只会说"连不上"。
                webDavCredentials.read(credentialId)
                    ?: throw IllegalStateException(WEBDAV_CREDENTIAL_MISSING)
            },
        )

    /**
     * 规范化服务器地址：补 scheme、去尾斜杠，**再补一个尾斜杠**得到目录 URL。
     *
     * ⚠️ 两步看似矛盾，其实是两件事：
     * ① [`WebDavUrlBuilder.normalizeServer`] 的"去尾斜杠"是为了**拼接时幂等**
     *    （带尾斜杠的 base 会让 `addPathSegment` 产出双斜杠）；
     * ② 这里的"补尾斜杠"是因为它同时要当**目录 URL** 用 ——
     *    目录 URL 必须以 `/` 结尾，否则 `listChildren` 会去列上一级。
     */
    fun normalizeServerUrl(raw: String): String {
        val normalized = WebDavUrlBuilder.normalizeServer(raw)
        return if (normalized.isEmpty() || normalized.endsWith('/')) normalized else "$normalized/"
    }

    // ---------------------------------------------------------------- OneDrive

    /**
     * 登录 Microsoft 账号。
     *
     * @param activity **必须是人当前可见的那个 Activity**：MSAL 要拉起授权页面，
     *   传 `applicationContext` 会直接失败。
     * @param forceAccountChooser 换号/重登必须为 true —— 默认的 `SELECT_ACCOUNT`
     *   在"只有一个缓存账户"时会被 MSAL 优化成**静默登录**，用户点了「切换账号」
     *   却仍以原账户进来。
     */
    suspend fun signInOneDrive(
        activity: Activity,
        forceAccountChooser: Boolean,
    ): Result<OneDriveAccountSession> = runCatching {
        oneDriveAuth.signIn(activity, forceAccountChooser = forceAccountChooser)
    }

    /**
     * 注销（清 MSAL token 缓存）。
     *
     * ⚠️ 尽力而为：MSAL 侧失败也**不该**卡住调用方的流程 ——
     * 这里把它包成 `Result`，由调用方决定要不要把失败说给用户听。
     * 「换号」尤其如此：注销失败也要继续走登录页，否则用户就卡死了。
     */
    suspend fun signOutOneDrive(accountId: String?): Result<Int> = runCatching {
        oneDriveAuth.signOut(accountId)
    }

    /** MSAL 缓存里的第一个账户（没登录过返回 null —— 那是**如实**的，不要猜）。 */
    suspend fun cachedOneDriveSession(): OneDriveAccountSession? =
        runCatching { oneDriveAuth.getCachedSession() }.getOrNull()

    /** MSAL 缓存里的全部账户。 */
    suspend fun cachedOneDriveSessions(): List<OneDriveAccountSession> =
        runCatching { oneDriveAuth.listCachedSessions() }.getOrDefault(emptyList())

    /** 构造一个 OneDrive 来源（**不自检**）。找不到账户返回 null。 */
    fun oneDriveSource(accountId: String?, filePath: String): KdbxFileSource? {
        val id = accountId ?: return null
        return oneDriveFactory.create(OneDriveVaultOrigin.build(id, filePath))
    }

    private companion object {
        const val WEBDAV_CREDENTIAL_MISSING = "WebDAV 凭据已不存在，请在「网盘账号」里重新配置"
    }
}
