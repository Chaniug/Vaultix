/*
 * Vaultix — app / 网盘账号
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「网盘账号」清单** —— 设置里那一块独立入口的数据源（2026-09-17）。
 *
 * ## ★ 为什么"账号清单"是从**库的 origin** 反推出来的，而不是另存一份
 *
 * 起因：用户要求把 OneDrive/WebDAV 从「添加密码库」里独立出来。写页面时撞到一个事实 ——
 * `WebDavCredentialStore` **只有按键读写**（它连"我配过哪些账号"都答不出来，
 * 因为键是 `credentialIdFor(服务器, 账号)` 哈希出来的，**不可反查**）。
 *
 * 于是两条路：
 * | | 做法 | 代价 |
 * |---|---|---|
 * | a | 新增一份"账号注册表"（DataStore） | **多一处持久化真相**，还得与凭据、与库的 origin 保持同步 —— 而"两处真相必然漂移"正是本项目反复被咬的那条 |
 * | b | ★ **从库的 origin 反推** | 列不出"配了但还没建库"的账号 |
 *
 * 2026-09-17 **选 b**，理由不只是省事，而是它表达了一个更准的语义：
 *
 * > **一个网盘账号的"现实意义"，就是它被哪些库在用。**
 *
 * ## ⚠️ ★ 2026-09-18 修正：b 的代价在「账号页可配置」之后变成了**阻断性**的
 *
 * b 的代价原本"极小"，因为当时账号**只能**在添加库时顺带配置 —— 配完立刻建库，
 * 中间态短到看不见。但定稿 §11.7 要求把配置搬到「网盘账号」页之后，
 * 这个中间态就成了**常态**：用户在账号页配完凭据，回头一看清单里**没有它**
 * ⇒ 只会得出一个结论："没配上"。
 *
 * ⇒ 修正做法：**两个来源合并**（见 [list]）。
 *   - **已保存的凭据 / 已登录的会话** —— 回答"我配过哪些账号"（[WebDavCredentialStore.listConfigured] /
 *     `OneDriveAuthManager.listCachedSessions()`）；
 *   - **库的 origin** —— 回答"每个账号被哪些库在用"（[CloudAccount.vaultIds] 与 [CloudAccount.browseRoot]）。
 *
 * 二者是**同一件事的两个面**，不是两份真相：凭据存储自己维护了一份幂等索引
 * （索引与凭据在同一处调用里同步增删，见 `WebDavCredentialStore` 的说明）。
 *
 * ## 这也让「注销账号」的后果可以算出来
 *
 * 凭据是**账号级**的、被多个库共用 ⇒ 注销会连带影响同账号的所有库。
 * [CloudAccount.vaultIds] 就是那句警告要用的答案（"会影响这 2 个库"），
 * 而不是让用户自己猜。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.remote

import io.vaultix.vaultix.remote.onedrive.OneDriveVaultOrigin
import io.vaultix.data.repository.kdbx.WebDavVaultOrigin
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.remote.onedrive.OneDriveAuthManager
import io.vaultix.vaultix.remote.onedrive.matchesStoredAccountId
import io.vaultix.vaultix.remote.webdav.WebDavCredentialStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** 一类网盘账号。 */
enum class CloudAccountKind { WEBDAV, ONEDRIVE }

/**
 * 一个**被库用到的**网盘账号。
 *
 * @param storedId 它在 origin 里的标识（WebDAV = `credentialId`；OneDrive = `accountId`）。
 * @param label 展示名（WebDAV = 服务器；OneDrive = 邮箱）。
 * @param vaultIds ★ **它被哪些库在用**。既是最有用的信息，也是「注销会影响谁」的答案。
 * @param connected 凭据 / 登录态现在还在不在（**如实报告**：不在就是不在，不要显示成"已连接"）。
 */
data class CloudAccount(
    val kind: CloudAccountKind,
    val storedId: String,
    val label: String,
    val vaultIds: List<String>,
    val connected: Boolean,
    /**
     * 列目录的**起点** —— "选完这个账号该列哪儿"。
     *
     * ⚠️ 语义**按来源不同**（刻意不强行统一成一种，那只会逼出一层无意义的转换）：
     * - WebDAV：一个**目录 URL**（如 `https://nas/dav/Vaultix/`）
     * - OneDrive：一个**相对路径**（如 `Keepass`；空串 = 根）
     *
     * ★ 它**不新增持久化**：从该账号**任一库**的 origin 反推 ——
     * 账号的现实意义就是"它被哪些库在用"，起点自然也来自那些库。
     *
     * ⚠️ null = 没有任何库指向它（那就无从"列目录" —— **如实为 null，不要猜一个**；
     * 顺手拼 `server + "/"` 正是今天那类"看起来能跑、实际 404"的错误来源）。
     */
    val browseRoot: String? = null,
) {
    val vaultCount: Int get() = vaultIds.size
}

/** 从库列表反推「网盘账号」（见文件头：为什么不另存一份）。 */
@Singleton
class CloudAccountInventory @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val webDavCredentials: WebDavCredentialStore,
    private val oneDriveAuth: OneDriveAuthManager,
    /** 只为复用它的 `normalizeServerUrl`（目录 URL 的规则必须只有一处）。 */
    private val connector: CloudAccountConnector,
) {

    /**
     * 当前被库用到的网盘账号。
     *
     * ⚠️ 全部走 `runCatching` 兜住：这是**只读的展示路径**，任何一处失败都只应让
     * 那个账号显示成"未连接"，**不该让整个页面崩或空白**（一个库坏掉不该拖垮清单）。
     */
    suspend fun list(): List<CloudAccount> {
        val rows = runCatching { vaultRepository.observeVaults().first() }.getOrDefault(emptyList())
        val sessions = runCatching { oneDriveAuth.listCachedSessions() }.getOrDefault(emptyList())

        // ① 先登记"配过哪些账号"（凭据 / 登录态）—— 哪怕一个库都还没用它。
        //    ⚠️ 这是 2026-09-18 的修正：账号页能配置之后，"配了没建库"是常态而非中间态。
        val vaultsOf = linkedMapOf<Pair<CloudAccountKind, String>, MutableList<String>>()
        val labels = mutableMapOf<Pair<CloudAccountKind, String>, String>()
        // ⚠️ 只 putIfAbsent：来自**库**的起点比"服务器根"更准（用户可能填的是子目录）。
        val roots = mutableMapOf<Pair<CloudAccountKind, String>, String>()

        fun register(kind: CloudAccountKind, storedId: String, label: String) {
            val key = kind to storedId
            vaultsOf.putIfAbsent(key, mutableListOf())
            labels.putIfAbsent(key, label)
        }

        runCatching { webDavCredentials.listConfigured() }
            .getOrDefault(emptyList())
            .forEach { configured ->
                register(CloudAccountKind.WEBDAV, configured.credentialId, serverOf(configured.serverUrl))
                // ★ 兜底起点：还没有任何库用它时，**用户填的那个地址**就是能列目录的地方。
                //   没有它，"刚配好还没建库"的账号会显示成"没有可用目录"，点不动
                //   —— 而用户刚填完地址，最自然的下一步正是"看看那上面有什么"。
                //   ⚠️ putIfAbsent 保证来自**库**的起点优先（那才是库真实所在的位置）。
                roots.putIfAbsent(
                    CloudAccountKind.WEBDAV to configured.credentialId,
                    connector.normalizeServerUrl(configured.serverUrl),
                )
            }
        sessions.forEach { session ->
            register(
                CloudAccountKind.ONEDRIVE,
                session.accountId,
                session.username.takeIf { it.isNotBlank() } ?: session.accountId,
            )
        }

        // ② 再挂上"被哪些库在用" + 浏览起点。
        for (vault in rows) {
            val webDav = WebDavVaultOrigin.parse(vault.origin)
            if (webDav != null) {
                // 同一个 credentialId 上的多个库归到**一个账号**（展示用服务器地址）。
                val key = CloudAccountKind.WEBDAV to webDav.credentialId
                vaultsOf.getOrPut(key) { mutableListOf() } += vault.id
                labels.putIfAbsent(key, serverOf(webDav.fileUrl))
                // 起点 = 这些库所在的**目录**（不是服务器根 —— 用户当初填的可能是子目录）。
                roots.putIfAbsent(key, webDavDirectoryOf(webDav.fileUrl))
                continue
            }
            val oneDrive = OneDriveVaultOrigin.parse(vault.origin)
            if (oneDrive != null) {
                val key = CloudAccountKind.ONEDRIVE to oneDrive.accountId
                vaultsOf.getOrPut(key) { mutableListOf() } += vault.id
                labels.putIfAbsent(key, oneDriveLabel(oneDrive.accountId, sessions))
                // OneDrive 的起点是**相对路径**（listChildren 取 path 的父目录）：
                // 去掉文件名即所在目录，空串 = 根。
                roots.putIfAbsent(key, oneDrive.path.substringBeforeLast('/', missingDelimiterValue = ""))
            }
        }

        return vaultsOf.map { (key, vaultIds) ->
            val (kind, storedId) = key
            CloudAccount(
                kind = kind,
                storedId = storedId,
                label = labels[key] ?: storedId,
                vaultIds = vaultIds,
                browseRoot = roots[key],
                connected = when (kind) {
                    CloudAccountKind.WEBDAV -> webDavCredentials.read(storedId) != null
                    // ⚠️ 与 getAccount 共用同一条形状容错规则（见 matchesStoredAccountId）。
                    CloudAccountKind.ONEDRIVE ->
                        sessions.any { matchesStoredAccountId(storedId, it.accountId) }
                },
            )
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
    }

    /**
     * 该文件的**所在目录**（保留尾部 `/`）。
     *
     * ⚠️ 规则与 [io.vaultix.data.repository.kdbx.WebDavKdbxFileSource] 的 `directoryUrl`
     * **必须一致**，但那一份在 `private companion object` 里、外部拿不到 ⇒ 这里重复一行。
     * 允许这一处重复的理由：它是一条**机械的字符串规则**（不是语义规则，不像 accountId
     * 的匹配那样会随外部实现漂移）；且方向单一 —— 真源改了这里会立刻在浏览时暴露（列错目录）。
     * ⚠️ 尾 `/` 不能省：`listChildren()` 内部还要用它砍最后一段，不带尾斜杠会砍错。
     */
    private fun webDavDirectoryOf(fileUrl: String): String {
        val withoutQuery = fileUrl.substringBefore('?')
        return withoutQuery.substringBeforeLast('/', missingDelimiterValue = withoutQuery) + "/"
    }

    /** OneDrive 的展示名优先用邮箱（拿不到就退回 accountId）。 */
    private fun oneDriveLabel(
        storedId: String,
        sessions: List<io.vaultix.vaultix.remote.onedrive.OneDriveAccountSession>,
    ): String {
        val session = sessions.firstOrNull { matchesStoredAccountId(storedId, it.accountId) }
            ?: return storedId
        return session.username.takeIf { it.isNotBlank() } ?: storedId
    }

    /**
     * 从文件 URL 推出**服务器**（`scheme://authority`）。
     *
     * 用途：同一台 NAS 上的多个库应归到**一个**账号 —— 用户心智里的"账号"是那台服务器，
     * 不是某个具体文件路径。不解析时原样返回（宁可显示得啰嗦，也不要显示成空）。
     */
    private fun serverOf(fileUrl: String): String {
        val scheme = fileUrl.substringBefore("://", missingDelimiterValue = "")
        val authority = fileUrl.substringAfter("://", missingDelimiterValue = "").substringBefore('/')
        return if (scheme.isBlank() || authority.isBlank()) fileUrl else "$scheme://$authority"
    }
}
