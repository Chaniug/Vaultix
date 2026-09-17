/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 网盘同步协调器** —— [KdbxSyncOrchestrator]（纯决策）与
 * `VaultRepository`（对 UI 的门面）之间的那一层。
 *
 * ## 为什么需要这一层（而不是把逻辑塞进 `VaultRepositoryImpl`）
 *
 * 1. 🔴 `VaultRepositoryImpl` 的函数数**正好卡在 detekt `TooManyFunctions` 的 40 上限**
 *    （方案 §2 已记录；之前加 `LocalUnlockEnrollment` / `PinEnrollmentCoordinator`
 *    就是为了避这个）。再往里加 4 个方法是必爆的。
 * 2. 这一层要**构造**文件来源（SAF / WebDAV），而 `VaultRepositoryImpl` 已经够长。
 * 3. 「把远端内容替换进已解锁会话」是一次**会话级**操作，与库生命周期是两件事。
 *
 * ## 来源是怎么解析出来的
 *
 * 只认 `VaultEntity.origin` 的**前缀** —— `VaultEntity` 没有 sourceType 列
 * （方案 §2 已核实），刻意不加：加了就有两处真相（列 + 前缀），迟早不一致。
 * - `content://…` ⇒ [SafKdbxFileSource]（本地文件，也算"来源"，便于统一走一条同步链）
 * - `webdav:<credentialId>:<url>` ⇒ [WebDavKdbxFileSource]
 * - `onedrive:<accountId>:<path>` ⇒ 由 app 侧注册的工厂（见 [registerFactory]）
 *
 * ⚠️ OneDrive 的构造需要 MSAL token（在 app 层），所以这里开一个**注册口** ——
 * 而不是让 `data:repository` 依赖 app（那会形成反向依赖）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxWriteFailure
import io.vaultix.database.dao.VaultDao
import okhttp3.OkHttpClient

/**
 * 「把远端字节替换进已解锁会话」的钩子。
 *
 * ## 为什么是钩子而不是在这里直接调 `Kdbx.unlock`
 *
 * 直接调也行，但那**要求调用方有主密码**。而"远端更新了，请重新解锁"是一条
 * 用户可见的提示；如果每次拉取都要用户重新输密码，自动同步就没意义了。
 *
 * ⇒ 会话替换由**持有解锁凭据的那一层**（app 侧的解锁流程 / `VaultRepositoryImpl`）
 * 提供：它知道该库的快解锁凭据能不能免密重开。这里只负责"该拉到哪一步"。
 */
fun interface KdbxSessionReplacer {
    /**
     * 用 [remoteBytes] 替换 [vaultId] 的会话。
     *
     * @return 成功 = 新会话已就位（此后保存会写远端版本）；失败 = 原因（可展示）。
     */
    suspend fun replace(vaultId: String, remoteBytes: ByteArray): Result<Unit>
}

/** WebDAV 凭据来源（按 `credentialId` 现取；**不缓存**，见 `WebDavKdbxFileSource` 的说明）。 */
fun interface WebDavCredentialLookup {
    suspend fun credentials(credentialId: String): WebDavCredentials?
}

/**
 * KDBX 网盘同步协调器。
 *
 * @param vaultDao 读 origin、写同步状态。
 * @param orchestrator 纯决策层（可脱离网络单测）。
 * @param safFactory `content://` URI ⇒ 来源（需要 `Context`，故从构造点传进来）。
 * @param sessionReplacer 拉取后替换已解锁会话（见 [KdbxSessionReplacer]）。
 * @param okHttp 给 WebDAV 来源用（构造点在 app 的 DI，那边已有共享的 `OkHttpClient`）。
 * @param webDavCredentials 按 credentialId 取 WebDAV 账号密码。
 */
class KdbxCloudSyncCoordinator(
    private val vaultDao: VaultDao,
    private val orchestrator: KdbxSyncOrchestrator,
    private val safFactory: (String) -> KdbxFileSource?,
    private val sessionReplacer: KdbxSessionReplacer,
    private val okHttp: () -> OkHttpClient?,
    private val webDavCredentials: WebDavCredentialLookup,
) {

    /**
     * app 侧注册的额外来源工厂（目前只有 OneDrive）。
     *
     * ⚠️ 单值而不是列表：**同时注册两个工厂去认同一个前缀**是配置错误，
     * 不是一个特性。用单值让这件事在**编译期**就说不出口。
     */
    @Volatile
    private var extraFactory: ((String) -> KdbxFileSource?)? = null

    /** 由 app 侧的 DI 在启动时调用一次（见 `OneDriveModule`）。 */
    fun registerFactory(factory: (String) -> KdbxFileSource?) {
        extraFactory = factory
    }

    /**
     * 解析 origin ⇒ 来源。
     *
     * @return null = 这个库没有可同步的网盘来源（UI 据此隐藏同步入口）。
     */
    fun fileSourceFor(origin: String): KdbxFileSource? = when {
        origin.startsWith("content://") -> safFactory(origin)

        WebDavVaultOrigin.matches(origin) -> {
            val parsed = WebDavVaultOrigin.parse(origin) ?: return null
            val client = okHttp() ?: return null
            WebDavKdbxFileSource(
                client = client,
                fileUrl = parsed.fileUrl,
                credentialProvider = {
                    webDavCredentials.credentials(parsed.credentialId)
                        ?: throw IllegalStateException("找不到该 WebDAV 账号的凭据，请重新填写")
                },
            )
        }

        else -> extraFactory?.invoke(origin)
    }

    /**
     * 跑一次同步。
     *
     * @param localChangedSinceLastSync 见 `KdbxSyncOrchestrator.sync`。
     */
    suspend fun sync(vaultId: String, localChangedSinceLastSync: Boolean): KdbxSyncResult {
        val row = vaultDao.get(vaultId) ?: return KdbxSyncResult.Failed("找不到该密码库")
        return orchestrator.sync(vaultId, row.origin, localChangedSinceLastSync).toResult()
    }

    /**
     * 用户拍板「用远端覆盖本地」。
     *
     * ## 这里**必须**真的把字节拉下来替换会话
     *
     * ⚠️ 只改状态不拉字节是**最坏**的伪实现：状态显示「已用远端覆盖」，而本地
     * 会话里还是旧内容 ⇒ 用户下一次保存就把旧内容推回去，**远端的新版本被静默覆盖**。
     * 那正好违反用户的硬要求（「同步不丢」）。
     *
     * ⇒ 顺序是：拉字节 → 替换会话 → **才**记状态。任一步失败都不记"成功"。
     */
    suspend fun resolveUsingRemote(vaultId: String): KdbxSyncResult {
        val row = vaultDao.get(vaultId) ?: return KdbxSyncResult.Failed("找不到该密码库")
        val source = fileSourceFor(row.origin) ?: return KdbxSyncResult.NoCloudSource

        val bytes = runCatching { source.read() }.getOrElse { error ->
            return KdbxSyncResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "无法读取远端文件")
        }
        sessionReplacer.replace(vaultId, bytes).exceptionOrNull()?.let { error ->
            // ⚠️ 会话没换成功 ⇒ **不能**记"已同步"。留在 CONFLICT 上，让用户还能再选一次。
            orchestrator.markDeferred(vaultId)
            return KdbxSyncResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "无法替换本地会话")
        }

        // 只有会话真的换好了才记"已同步"，并把远端的**当前**版本记为基线。
        val remoteToken = runCatching { source.stat().versionToken }.getOrNull()
        orchestrator.markResolved(vaultId, remoteToken)
        return KdbxSyncResult.Downloaded
    }

    /**
     * 用户拍板「用本地覆盖远端」—— **唯一**会绕过条件写的路径。
     *
     * ⚠️ 远端这一刻的内容会被永久丢弃。UI 必须明确告知（见 `KdbxConflictDialog`）。
     *
     * ⚠️ 同时把 [expectedVersion] 显式传 `null` —— `Kdbx.saveVia` 见到 `null`
     * 会**先 stat 一次**拿当前版本，那是错的（我们要的就是"不看远端、直接盖"）。
     * 所以走 `force` 开关而不是靠 `null` 语义。
     */
    suspend fun resolveUsingLocal(vaultId: String, force: Boolean = true): KdbxSyncResult {
        val row = vaultDao.get(vaultId) ?: return KdbxSyncResult.Failed("找不到该密码库")
        val source = fileSourceFor(row.origin) ?: return KdbxSyncResult.NoCloudSource

        return Kdbx.saveVia(vaultId = vaultId, source = source, force = force).fold(
            onSuccess = { report ->
                orchestrator.markResolved(vaultId, report.writtenVersion)
                KdbxSyncResult.Uploaded(report.writtenVersion)
            },
            onFailure = { error ->
                when (error) {
                    is KdbxWriteFailure.Conflict -> KdbxSyncResult.Conflict(error.currentVersion)
                    else -> KdbxSyncResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "覆盖远端失败")
                }
            },
        )
    }

    /** 用户拍板「稍后再决定」：只标状态，不做 IO。 */
    suspend fun defer(vaultId: String) {
        orchestrator.markDeferred(vaultId)
    }

    /**
     * 上报「上传期间本地又改了」（转发给编排器，见其 KDoc）。
     *
     * ⚠️ 方案 §9 点名的**最容易漏的竞态**：漏掉它那笔改动永远推不上去。
     */
    suspend fun notifyLocalChangedDuringUpload(vaultId: String) {
        orchestrator.notifyLocalChangedDuringUpload(vaultId)
    }

    private fun SyncOutcome.toResult(): KdbxSyncResult = when (this) {
        is SyncOutcome.AlreadyInSync -> KdbxSyncResult.InSync
        is SyncOutcome.Uploaded -> KdbxSyncResult.Uploaded(newVersionToken)
        is SyncOutcome.NeedsUserDecision -> KdbxSyncResult.Conflict(currentRemoteVersion)
        is SyncOutcome.RemoteNewerNeedsReload -> KdbxSyncResult.NeedsReload
        is SyncOutcome.Failed -> KdbxSyncResult.Failed(reason)
    }
}

/** 协调器层面的结果（形状与领域层 `KdbxSyncReport` 一致，少一次映射）。 */
sealed interface KdbxSyncResult {
    data object InSync : KdbxSyncResult
    data class Uploaded(val newVersionToken: String?) : KdbxSyncResult
    data object Downloaded : KdbxSyncResult
    data class Conflict(val currentRemoteVersion: String?) : KdbxSyncResult
    data object NeedsReload : KdbxSyncResult
    data object NoCloudSource : KdbxSyncResult
    data class Failed(val reason: String) : KdbxSyncResult
}
