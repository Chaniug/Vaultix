/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * `KdbxSyncRepository` 的实现 —— 把 [KdbxCloudSyncCoordinator] 的
 * `KdbxSyncResult` 翻译成领域层的 `KdbxSyncReport`。
 *
 * ## 为什么不直接让协调器实现领域接口
 *
 * 协调器在 `data:repository`、领域接口在 `domain`：**domain 不能依赖 data**
 * （依赖方向是 data → domain）。而且协调器的方法名是 data 风格（`sync` /
 * `resolveUsingRemote`），领域接口的名字要更明确地表达"这是用户的拍板"
 * （同样 `resolveUsingRemote`，但语义上必须能被单独读懂）。
 *
 * ⚠️ 这一层**只有翻译**，没有任何决策 —— 决策在协调器与编排器里（都可单测）。
 * 放在这里是为了让 `VaultRepositoryImpl` 不用再加第 41 个函数（见领域接口的说明）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.domain.KdbxSyncReport
import io.vaultix.domain.KdbxSyncRepository
import io.vaultix.database.dao.VaultDao
import io.vaultix.model.KdbxCloudSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KdbxSyncRepositoryImpl @Inject constructor(
    private val coordinator: KdbxCloudSyncCoordinator,
    private val vaultDao: VaultDao,
    /**
     * 会话替换后要让界面重读（施工单 S2）。
     *
     * ⚠️ 少了这一次 bump：「拉取成功」而列表**还是旧内容** —— 用户以为拉坏了，
     * 更糟的是他可能就此再保存一次，把旧内容推回远端。
     */
    private val kdbxSessions: KdbxSessionFlow,
) : KdbxSyncRepository {

    override suspend fun sync(vaultId: String, localChangedSinceLastSync: Boolean): KdbxSyncReport =
        coordinator.sync(vaultId, localChangedSinceLastSync).toReport()

    override suspend fun resolveUsingRemote(vaultId: String): KdbxSyncReport {
        val result = coordinator.resolveUsingRemote(vaultId)
        if (result is KdbxSyncResult.Downloaded) kdbxSessions.bump()
        return result.toReport()
    }

    /**
     * 拉取远端更新（施工单 S2）：复用 [KdbxCloudSyncCoordinator.resolveUsingRemote]
     * 那条**已有**的路径（拉字节 → 替换会话 → 记状态），**不另写一条拉取** ——
     * 两条路必然漂移，而漂移在这里的代价是"一边拉了、一边没拉"的静默不一致。
     *
     * 多出来的只有两件事：① 前置的"本地没改"检查；② 成功后 bump 让界面重读。
     */
    override suspend fun pull(vaultId: String): KdbxSyncReport {
        // ⚠️ 本地有未上传的改动 ⇒ 拉下来会**永久丢掉**那笔改动，且没有任何报错。
        //    ⇒ 转冲突，让用户在「用远端 / 用本地」之间拍板（绝不自动替他选）。
        if (localChangedSinceLastSync(vaultId)) return KdbxSyncReport.Conflict(currentRemoteVersion = null)
        return resolveUsingRemote(vaultId)
    }

    /**
     * 现查本地有没有改动（施工单 S3）。
     *
     * 判据在 `KdbxCloudSyncStatus.impliesLocalChanges`（**只此一份**），
     * 这里只负责"现查"——读的是库里**此刻**的 `syncStatus`，不是 UI 传进来的快照。
     */
    override suspend fun localChangedSinceLastSync(vaultId: String): Boolean =
        KdbxCloudSyncStatus.fromName(vaultDao.get(vaultId)?.syncStatus)?.impliesLocalChanges == true

    override suspend fun resolveUsingLocal(vaultId: String): KdbxSyncReport =
        coordinator.resolveUsingLocal(vaultId).toReport()

    override suspend fun defer(vaultId: String) = coordinator.defer(vaultId)

    /**
     * ⚠️ 用 `flow { … }` 而不是 `flowOf(…)`：判断要读一次 DB（挂起），
     * 而 `cloudSyncAvailable` 是**纯本地查表**（不做网络自检 —— 每次进设置页
     * 都去 ping 一次网盘会让页面卡顿，且离线时整页都用不了）。
     *
     * 代价：用户在别处改了来源（如重新配置了 OneDrive 文件夹）后，本 Flow
     * 不会自动重发。当前唯一的调用点是"进入设置页时决定要不要显示同步入口"，
     * 每次进入都会重新 collect ⇒ 实际影响为零。
     */
    override fun cloudSyncAvailable(vaultId: String): Flow<Boolean> = flow {
        val row = vaultDao.get(vaultId)
        emit(row != null && coordinator.fileSourceFor(row.origin) != null)
    }

    override suspend fun markLocalEdited(vaultId: String) {
        coordinator.markLocalEdited(vaultId)
    }

    override suspend fun notifyLocalChangedDuringUpload(vaultId: String) {
        coordinator.notifyLocalChangedDuringUpload(vaultId)
    }

    private fun KdbxSyncResult.toReport(): KdbxSyncReport = when (this) {
        is KdbxSyncResult.InSync -> KdbxSyncReport.InSync
        is KdbxSyncResult.Uploaded -> KdbxSyncReport.Uploaded(newVersionToken)
        is KdbxSyncResult.Downloaded -> KdbxSyncReport.Downloaded
        is KdbxSyncResult.Conflict -> KdbxSyncReport.Conflict(currentRemoteVersion)
        is KdbxSyncResult.NeedsReload -> KdbxSyncReport.NeedsReload
        is KdbxSyncResult.NoCloudSource -> KdbxSyncReport.NoCloudSource
        is KdbxSyncResult.Failed -> KdbxSyncReport.Failed(reason)
    }
}
