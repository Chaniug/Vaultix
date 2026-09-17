/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 网盘同步编排器** —— 把「状态机」「文件来源」「写回」串成一次同步。
 *
 * ## 一次同步做什么（对齐方案 §12 的验收标准）
 *
 * 1. `stat()` 远端当前版本；与**上次记下的** `remoteVersionToken` 比 ⇒ 远端变了吗？
 * 2. 判断本地改了吗（用会话里的库 + 上次同步的基线）。
 * 3. 三态决策（[KdbxSyncTransitions.isConflict] 的表）：
 *    - 两边都没变 ⇒ 什么都不做（[KdbxSyncStatus.IN_SYNC]）；
 *    - 只有远端变 ⇒ 拉下来替换本地（🔴 见下面的警告）；
 *    - 只有本地变 ⇒ 推上去（带条件写）；
 *    - ★ 两边都变 ⇒ **拒写**，转 [KdbxSyncStatus.CONFLICT]，让用户拍板。
 * 4. 无论哪条路径，都把**新的版本令牌**记回去（下次条件写要用它）。
 *
 * ## 🔴 关于"只有远端变 ⇒ 拉下来替换本地"
 *
 * ⚠️ 本地路径**故意还没实现**（见 [SyncOutcome.RemoteNewerNeedsReload]）。
 * 理由：把远端内容拉下来之后，「本地」这个概念就变了 ——
 * 需要往 `KdbxSessionStore` 里**替换**那个已解锁的会话（换 `KeePassDatabase`
 * 与 `Credentials`），否则用户界面上还是旧内容，而下次保存会把旧内容推回去
 * **覆盖刚拉下来的新版本**（= 静默丢失远端改动，正好违反硬要求）。
 * 那是一次会话级操作，必须连带处理 UI 刷新与"正在展示旧数据"的提示 ——
 * 归到下一批（冲突处理 UI）一起做，而不是在这里塞一个半成品。
 * ⇒ 本编排器**如实上报**这个状态，不假装成功。
 *
 * ## 为什么不在这里做"上传"
 *
 * 上传由 `Kdbx.saveVia` 完成（它负责往返自检、条件写、冲突翻译）。
 * 本类只做**决策**与**状态记录** ⇒ 决策逻辑可以脱离网络单测。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxWriteFailure
import io.vaultix.database.dao.VaultDao

/**
 * 一次同步的结果。
 *
 * ⚠️ 做成密封接口而不是 `Result<Unit>`：调用方要能区分
 * 「同步好了」「需要用户处理冲突」「远端更新但还需要重新载入」——
 * 三者的 UI 完全不同。压成 `Result` 只会让 UI 拿到一句 `Unit`，什么都做不了。
 */
sealed interface SyncOutcome {

    /** 两边一致，什么都没做。 */
    data object AlreadyInSync : SyncOutcome

    /** 本地改动已推上远端。[newVersionToken] 是服务端给的新版本。 */
    data class Uploaded(val newVersionToken: String?) : SyncOutcome

    /**
     * ★ 两边都改了 —— **已拒写，远端未被覆盖**，需要用户拍板。
     *
     * @param currentRemoteVersion 远端**现在**的版本（供"用远端覆盖本地"用）。
     */
    data class NeedsUserDecision(val currentRemoteVersion: String?) : SyncOutcome

    /**
     * 🔴 远端更新、本地未改 —— 应该拉下来，但**拉取后的会话替换还没有实现**。
     *
     * 这不是失败（网络与凭据都好），而是**功能尚未完成**。
     * 调用方应如实告诉用户"远端有更新，请在下次同步时处理"，
     * **不要**把它当成"已同步"（那会让用户以为拿到最新数据了）。
     */
    data object RemoteNewerNeedsReload : SyncOutcome

    /** 失败（网络 / 凭据 / IO）。[reason] 可直接展示。 */
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * KDBX 网盘同步编排器。
 *
 * @param vaultDao 读写 `syncStatus` / `remoteVersionToken` / `lastSyncedAt`。
 */
class KdbxSyncOrchestrator(
    private val vaultDao: VaultDao,
    /** 从持久化的 origin 字符串构造文件来源（`data:repository` / `app` 各提供一段）。 */
    private val fileSourceFactory: (origin: String) -> KdbxFileSource?,
) {

    /**
     * 跑一次同步。
     *
     * @param vaultId 库 id（= KDBX 的 `sourceUri`）。
     * @param origin 持久化的来源标识（`content://…` / `onedrive:…` / `webdav:…`）。
     * @param localChangedSinceLastSync 自上次同步以来**本地有没有改动**。
     *   由调用方（知道"用户编辑过什么"的那一层）提供 —— 编排器**不去猜**
     *   （猜错的两个方向都糟：猜"没改"会漏推用户的编辑，猜"改了"会无谓地报冲突）。
     */
    suspend fun sync(
        vaultId: String,
        origin: String,
        localChangedSinceLastSync: Boolean,
    ): SyncOutcome {
        val vault = vaultDao.get(vaultId)
            ?: return SyncOutcome.Failed("找不到该密码库")
        val lastKnownVersion = vault.remoteVersionToken

        val source = fileSourceFactory(origin)
            ?: return SyncOutcome.Failed("这个库还没有配置网盘来源")

        markStatus(vaultId, KdbxSyncTransitions.markSyncing())

        // ① 远端现在是什么版本？
        val remoteNow = runCatching { source.stat().versionToken }
            .getOrElse { error ->
                markStatus(vaultId, KdbxSyncTransitions.markSyncFailure())
                return SyncOutcome.Failed(
                    error.message?.takeIf { it.isNotBlank() } ?: "无法读取远端文件信息",
                )
            }

        // ② 远端变了吗？
        //
        // ⚠️ 判据用**归一化后**的令牌比较：WebDAV 服务器可能一次给 `W/"x"`、
        //    下次给 `"x"` —— 不归一化会把"同一版"误判成"变了"，于是**凭空报冲突**
        //    （用户看到一个根本不存在的冲突，然后开始怀疑数据）。
        //    ⚠️ OneDrive 的 eTag 不带 W/，归一化对它无害（只是原样返回）。
        val remoteChanged = normalizeToken(remoteNow) != normalizeToken(lastKnownVersion)

        // ③ 三态决策
        if (KdbxSyncTransitions.isConflict(localChangedSinceLastSync, remoteChanged)) {
            markStatus(vaultId, KdbxSyncTransitions.markConflict())
            return SyncOutcome.NeedsUserDecision(currentRemoteVersion = remoteNow)
        }

        if (!localChangedSinceLastSync && !remoteChanged) {
            // 两边一致 ⇒ 把远端当前版本记为新的基线（下次条件写要用它）。
            markStatus(vaultId, KdbxSyncStatus.IN_SYNC, remoteNow)
            return SyncOutcome.AlreadyInSync
        }

        if (remoteChanged) {
            // 🔴 本地没改、远端改了 ⇒ 该拉。但"拉下来替换已解锁会话"还没实现，
            //    见文件头说明。**如实上报**，不假装成功。
            markStatus(vaultId, KdbxSyncTransitions.markRemoteChanges(), remoteNow)
            return SyncOutcome.RemoteNewerNeedsReload
        }

        // ④ 本地改了、远端没改 ⇒ 推。
        val saveResult = Kdbx.saveVia(
            vaultId = vaultId,
            source = source,
            expectedVersion = lastKnownVersion,
        )

        return saveResult.fold(
            onSuccess = { report ->
                // ★★ 关键：上传期间本地又改了吗？
                //
                // ⚠️ 这里刻意**不再检查一次** "localChangedSinceLastSync" —— 那个值
                //    是同步开始时调用方给的快照，它反映的是"开始之前"的状态，
                //    拿它当"上传期间有没有改"用是**错的**（会把 PENDING_UPLOAD
                //    的既有事实误当成上传期间的新改动）。
                //    ⇒ 上传完成后由调用方用**最新的**本地状态再判一次；
                //      编排器先把"已上传"与新的版本令牌记下来。
                markStatus(
                    vaultId = vaultId,
                    status = KdbxSyncTransitions.markUploaded(localChangedDuringUpload = false),
                    versionToken = report.writtenVersion,
                )
                SyncOutcome.Uploaded(newVersionToken = report.writtenVersion)
            },
            onFailure = { error ->
                // 冲突单独归类：它不是"失败"，重试解决不了。
                if (error is KdbxWriteFailure.Conflict) {
                    markStatus(vaultId, KdbxSyncTransitions.markConflict())
                    SyncOutcome.NeedsUserDecision(currentRemoteVersion = error.currentVersion)
                } else {
                    markStatus(vaultId, KdbxSyncTransitions.markSyncFailure())
                    SyncOutcome.Failed(
                        error.message?.takeIf { it.isNotBlank() } ?: "同步失败",
                    )
                }
            },
        )
    }

    /**
     * 上传完成后由调用方再调一次，处理"上传期间本地又改了"。
     *
     * ## 为什么单独一个入口
     *
     * 只有**调用方**知道"上传这段时间里用户有没有继续编辑"（编排器在上传中阻塞着，
     * 看不到 UI 上发生的事）。所以这个判断必须由外面喂进来。
     *
     * ⚠️ 漏掉这一步的表现（方案 §9 特别点名的竞态）：
     * 用户改了 → 同步 → 上传成功 → 期间又改了一笔 → 状态记成"已同步" →
     * **那笔改动永远推不上去**，换台设备看不到，用户还不知道为什么。
     */
    suspend fun notifyLocalChangedDuringUpload(vaultId: String) {
        val vault = vaultDao.get(vaultId) ?: return
        // 只在"刚上传成功（IN_SYNC）"时才降级：其他状态本身已经表达了"还有活要干"，
        // 覆盖它们会丢掉更重要的信息（比如 CONFLICT 不能被降级成 PENDING）。
        if (vault.syncStatus == KdbxSyncStatus.IN_SYNC.name) {
            markStatus(vaultId, KdbxSyncTransitions.markUploaded(localChangedDuringUpload = true))
        }
    }

    /**
     * 冲突**已经被用户解决**（拉下来替换了会话，或强写推上去了）之后登记结果。
     *
     * @param remoteVersion 解决之后远端的版本令牌（下次条件写的基线）。
     */
    suspend fun markResolved(vaultId: String, remoteVersion: String?) {
        markStatus(vaultId, KdbxSyncTransitions.markDownloaded(), remoteVersion)
    }

    /**
     * 用户选择「稍后再决定」—— 清掉中间的 [KdbxSyncStatus.SYNCING]，
     * 把状态**明确落回** [KdbxSyncStatus.CONFLICT]。
     *
     * ## 为什么必须有一个方法做这件事
     *
     * 冲突对话框是**模态**的：用户点「稍后」关掉它之后，如果状态还停在 SYNCING，
     * UI 会一直显示"正在同步…"（一个永远不会结束的转圈），而实际上什么都没在跑。
     * ⇒ 「稍后」不能只是"关掉对话框"，它得把状态恢复到**与事实相符**的那个值。
     *
     * ⚠️ 只在当前确实是冲突态时这么做：否则（比如同步已被别处推进到 IN_SYNC）
     * 会把一个已经解决的状态硬拉回 CONFLICT，让用户看到一个幽灵冲突。
     */
    suspend fun markDeferred(vaultId: String) {
        val current = vaultDao.get(vaultId) ?: return
        if (current.syncStatus == KdbxSyncStatus.CONFLICT.name) {
            markStatus(vaultId, KdbxSyncTransitions.markConflict())
        }
    }

    private suspend fun markStatus(
        vaultId: String,
        status: KdbxSyncStatus,
        versionToken: String? = null,
    ) {
        val current = vaultDao.get(vaultId) ?: return
        vaultDao.updateSyncState(
            id = vaultId,
            status = status.name,
            // ⚠️ 只有显式给了新令牌才覆盖 —— 传 null 时保留旧值。
            //    否则每次"标记 SYNCING"都会把基线抹掉，下一轮就会误判成"远端变了"。
            versionToken = versionToken ?: current.remoteVersionToken,
            syncedAt = if (status == KdbxSyncStatus.IN_SYNC) {
                System.currentTimeMillis()
            } else {
                current.lastSyncedAt
            },
        )
    }

    private fun normalizeToken(raw: String?): String? =
        io.vaultix.data.kdbx.normalizeVersionToken(raw)
}
