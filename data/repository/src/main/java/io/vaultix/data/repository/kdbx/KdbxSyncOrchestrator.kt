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
 * ## ★ 关于"只有远端变 ⇒ 拉下来替换本地"（2026-10-01 已打通）
 *
 * 拉下来之后「本地」这个概念就变了：必须往会话里**替换**掉已解锁的那份
 * （换 `KeePassDatabase`），否则界面上还是旧内容，而下次保存会把旧内容推回去、
 * **覆盖刚拉下来的新版本**（= 静默丢失远端改动，正好违反硬要求）。
 *
 * 这一步此前一直缺着，2026-10-01 由 [io.vaultix.data.kdbx.Kdbx.replaceSession] 补上
 * （免密：复用会话里已有的那组凭据，详见那里的 KDoc）。
 *
 * ⚠️ 但**编排器自己仍然不拉** —— 它只**如实上报** [SyncOutcome.RemoteNewerNeedsReload]，
 *    由调用方接着调 `pullRemote`。理由：拉取成功后还要 bump 让界面重读，
 *    那是会话集合级的一次通知，属于调用方（`KdbxSyncRepositoryImpl`）的职责；
 *    编排器自己不碰会话，才能保持"纯决策、可脱离网络单测"。
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
     * 远端更新、本地未改 —— **该拉一次**（调用方接着调 `pullRemote` 即可）。
     *
     * 这不是失败（网络与凭据都好），也不是"功能没做完"：拉取路径本身是通的。
     * 它只是**编排器不代为执行**的那一类结果 —— 拉取成功后还要 bump 让界面重读，
     * 那是调用方的事（见文件头）。
     *
     * ⚠️ 调用方**不要**把它当成"已同步"：此刻本地会话还是旧的，
     *    当成已同步会让用户以为拿到最新数据了（而他并没有）。
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
            // 本地没改、远端改了 ⇒ 该拉（由调用方接着调 `pullRemote`）。
            //
            // ⚠️ ★★ 下面这行**故意不传 `remoteNow`**（2026-10-01 修）。
            //    此前传了，后果是一条已经发生过的数据丢失链：
            //      ① 远端变到 T1，本机报 REMOTE_CHANGED，并把基线记成 T1；
            //      ② 用户在本机改一笔 ⇒ 状态降为 PENDING_UPLOAD ⇒ 自动上传触发；
            //      ③ 上传时拿 T1 当 expectedVersion，服务端当前也正好是 T1
            //         ⇒ 条件写通过、写入成功；
            //      ④ 而本机会话**还是旧的**（根本没拉过）⇒ 推上去的是「旧内容 + 新改动」，
            //         远端 T1 上另一台设备的新内容被**静默覆盖**。
            //    不推进 ⇒ 第 ③ 步的 `remoteChanged` 判定**仍然为真**，与"本地也改了"
            //    合成 CONFLICT ⇒ 拒写、交用户拍板。那才是正确的结局。
            //
            //    一句话：**基线只在"本地真的跟上了远端"之后才推进**
            //    （拉取成功见 `markResolved`，强推成功见 `resolveUsingLocal`）。
            markStatus(vaultId, KdbxSyncTransitions.markRemoteChanges())
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

    /**
     * **本地内容被改过**（条目写回成功后由仓储调用）—— 把同步状态降级为「待上传」。
     *
     * ## 为什么必须有这一步（不是"锦上添花"）
     *
     * 同步的 `localChangedSinceLastSync` **由调用方从持久化的 `vaults.syncStatus` 读出来**
     * （见 `VaultActionsController.localChanged`）。⇒ 本地改完不标记的话，状态还停在
     * `IN_SYNC`，下一次同步会判定"两边都没变"直接返回 `AlreadyInSync` ——
     * **那笔改动永远推不上去**：换台设备看不到，用户还以为已经同步了。
     *
     * ⚠️ **冲突态不许降级**：`CONFLICT` 表达的是"要用户拍板"，把它覆盖成 `PENDING_UPLOAD`
     * 会让冲突对话框失去依据（用户再也不会被问到，而重试又推不上去）。
     * ⚠️ 只对**有网盘来源**的库调用（判断在 [KdbxCloudSyncCoordinator.markLocalEdited]）——
     * 本地 SAF 库标了会让 UI 在一个根本没有云端的库上渲染「待上传」角标。
     */
    suspend fun markLocalEdited(vaultId: String) {
        val current = vaultDao.get(vaultId) ?: return
        if (current.syncStatus == KdbxSyncStatus.CONFLICT.name) return
        markStatus(vaultId, KdbxSyncTransitions.markLocalChanges())
    }

    /**
     * **后台新鲜度校验发现远端已变**（2026-10-05）—— 标成 [KdbxSyncStatus.REMOTE_CHANGED]。
     *
     * ## 为什么需要它（"折中"方案的第二半）
     *
     * 快速解锁改成了「先用本地缓存解密、`stat()` 丢后台」（见 [CachedKdbxFileSource]）。
     * 代价是**解锁瞬间可能展示旧内容** —— 这一点用户已知并接受，但"知道可能过期"
     * 必须变成"看得见"，否则就退化成静默给一份过期数据。
     *
     * ⇒ 复用**已有的** [KdbxSyncStatus.REMOTE_CHANGED]（"远端有改动，本地还没拉"）语义**恰好吻合**：
     *   UI 侧（`VaultOriginLabel` 的 `SyncBadge.REMOTE_CHANGED` + 文案 + 配色）**早已完整实现**，
     *   不必新造一个提示控件，也不必改任何 UI 代码。
     *
     * ⚠️ **不覆盖冲突态**（同 [markLocalEdited] 的理由）：`CONFLICT` 表达的是"要用户拍板"，
     *   把它降级成 `REMOTE_CHANGED` 会让已有的冲突对话框失去依据。
     * ⚠️ **不覆盖待上传态**：本地有未推的改动时，报"远端变了"会盖掉更需要提示的那件事
     *   （那笔改动推不上去比"远端有更新"更紧急），交给下一次 sync 自己去判。
     */
    suspend fun markRemoteChanged(vaultId: String) {
        val current = vaultDao.get(vaultId) ?: return
        if (current.syncStatus == KdbxSyncStatus.CONFLICT.name) return
        if (current.syncStatus == KdbxSyncStatus.PENDING_UPLOAD.name) return
        if (current.syncStatus == KdbxSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES.name) return
        markStatus(vaultId, KdbxSyncTransitions.markRemoteChanges())
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
