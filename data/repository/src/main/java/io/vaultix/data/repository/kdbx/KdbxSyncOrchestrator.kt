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

import io.vaultix.common.logging.VaultixLog
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
     * 这个库**只有本地那一份**（SAF 文件），没有可同步的远端。
     *
     * ⚠️ 与 [Failed] 刻意分开：它不是错误，是**配置事实**。
     *   混进 `Failed` 会让 UI 弹一句"同步失败"，而用户手里什么都没坏。
     * ⚠️ 编排器走到这里时**没有做任何 IO**（见 `sync` 开头的闸门）。
     */
    data object NoCloudSource : SyncOutcome

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

    /** [localOnlyGate] 的三种结局。 */
    enum class LocalOnlyGate(val outcome: SyncOutcome) {
        /** 只有本地那一份 ⇒ 没有"同步"可言。 */
        LOCAL(SyncOutcome.NoCloudSource),

        /** 来源解析不出来 ⇒ 这是**配置问题**，如实说，不要当成"本地库"糊过去。 */
        NO_SOURCE(SyncOutcome.Failed("这个库还没有配置网盘来源")),
    }

    /**
     * 同步入口的第一道闸：**这个库有没有"另一端"**？
     *
     * 抽成方法不只是为了 de-tekt 的圈复杂度，而是因为这两条判据（前缀兜底 + 来源自述）
     * 在语义上是**同一个问题**，和后面"远端变了吗 / 本地改了吗"不是一类。
     *
     * @return `null` = 可以往下走（此时 [source] 必然非空且 `isRemote`）。
     */
    private fun localOnlyGate(origin: String, source: KdbxFileSource?): LocalOnlyGate? = when {
        // ① 前缀兜底：即使来源解析不出来，`content://` 也已经说明了这是本地文件。
        isLocalOnlyOrigin(origin) -> LocalOnlyGate.LOCAL
        // ② 来源自述：`WebDav` / `OneDrive` 覆写为 true，其余默认 false（见 `KdbxFileSource.isRemote`）。
        source == null -> LocalOnlyGate.NO_SOURCE
        !source.isRemote -> LocalOnlyGate.LOCAL
        else -> null
    }

    /**
     * 这个 origin 是不是**只有本地那一份**（没有远端）。
     *
     * 判据只有一条：SAF 的 `content://`。**不**采用"工厂解析得出来就算有远端" ——
     * 本地文件的工厂**恰恰解析得出来**（就是文件自己），那正是这一轮 bug 的来源。
     *
     * ⚠️ 前缀常量与 `KdbxCloudSyncCoordinator` / `KdbxFileSourceResolver` 同值，
     *    它是 Android 定死的 scheme，不会增长；真正的白名单式判据在协调器里
     *    （`isCloudOrigin`），此处只需要"本地"这一支的反向判断。
     */
    private fun isLocalOnlyOrigin(origin: String): Boolean =
        origin.startsWith(SAF_ORIGIN_PREFIX)

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

        // ★★ 2026-11-10 晚：**本地 SAF 库没有"同步"这件事**，在这里就说清楚，别继续往下走。
        //
        // ## 修的是什么（真机可复现）
        //
        // 「本地 kdbx 打开 → 新建一个条目 → 点一下同步」⇒ 界面上出现「已同步」。
        // 而那个文件根本没有远端：`fileSourceFactory("content://…")` 会返回一个
        // **能 stat / 能 read 的本地来源**（= 文件自己），于是往下走一遍：
        //
        //   ① `remoteNow` = 文件当前内容的哈希（`SafKdbxFileSource.stat`）；
        //   ② `remoteChanged` = `remoteNow != 基线` —— 刚写的条目让文件变了 ⇒ true；
        //   ③ `localChanged` = true（`markLocalEdited` 已置 PENDING_UPLOAD）；
        //   ④ 两边都"变" ⇒ 走冲突判定 ⇒ 内容对账发现一致 ⇒ **写 IN_SYNC**。
        //
        // 于是「一个新库刚建的条目」被记成"与远端一致"，并在 UI 上显示「已同步」。
        // 这不是同步算错了，是**这个问题对它没有意义** —— 拿文件跟文件自己做条件写。
        //
        // ⇒ 在入口挡住，并**顺手纠正**已被写歪的状态（见 [KdbxSyncTransitions.markLocalOnly]）。
        //   只挡不纠的话，上一轮已经写进 Room 的 IN_SYNC 会一直在界面上说谎
        //   （展示侧也有一道闸，但那是纵深防御，不是"数据可以不修"的理由）。
        //
        // ⚠️ 放在 `markStatus(SYNCING)` **之前**：本地库连一瞬间的「同步中」都不该有。
        //
        // ⚠️ 判据问的是**来源自己**（`isRemote`），不是"工厂解析得出来" ——
        //   本地文件的工厂**恰恰解析得出来**（就是文件自己），那正是这一轮 bug 的来源。
        //   前缀 `content://` 作为**兜底**保留：来源解析不出来时也要挡。
        val gate = localOnlyGate(origin, fileSourceFactory(origin))
        if (gate != null) {
            // 本地库：先纠正持久化状态（旧数据可能已被写成 IN_SYNC），再如实上报。
            if (gate == LocalOnlyGate.LOCAL) markStatus(vaultId, KdbxSyncTransitions.markLocalOnly())
            return gate.outcome
        }
        // gate 为 null ⇒ 来源必然解析成功且自认是远端（见 [localOnlyGate]）。
        val source = fileSourceFactory(origin)!!

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
        val remoteChanged = !io.vaultix.data.kdbx.isSameVersionToken(remoteNow, lastKnownVersion)

        // ③ 三态决策
        if (KdbxSyncTransitions.isConflict(localChangedSinceLastSync, remoteChanged)) {
            return resolveConflictByContent(vaultId, source, remoteNow)
        }

        if (!localChangedSinceLastSync && !remoteChanged) {
            // 两边一致 ⇒ 把远端当前版本记为新的基线（下次条件写要用它）。
            markStatus(vaultId, KdbxSyncStatus.IN_SYNC, remoteNow)
            // ★ 11-07：补内容基线，解开「保存被拦 → 点同步 → 仍被拦」的死锁。
            //
            // 版本令牌是持久化的，而写回基线（内容指纹）是**内存态**：冷启动 / 锁库后
            // 令牌还在、基线已丢。此前沿这条捷径直接返回，基线永远补不回来，
            // 于是主写路径每次都把「不知道」读成「过期」，用户反复同步也无解。
            //
            // ★★ 11-08：条件从「没登记」放宽到「没对齐」。此前只要基线**登记过**，
            //    哪怕它已经落后于本机会话（连续编辑时基线没跟着推进）就再也不补对账，
            //    于是真机出现「已登记但与改动前内容不符」+ 状态绿色 + 同步毫无动作，
            //    只能靠锁库/冷启动清掉内存基线。现在只要不对齐就跑一次内容对账：
            //    远端内容与会话一致 ⇒ 采纳基线，死锁当场解开。
            //
            // ⚠️ 会话不在时不跑 —— `adoptBaselineIfMatches` 未解锁必然返回 false，
            //    白下载一整份；也让编排器单测（无真实会话）行为完全不变。
            // ⚠️ 对账结果不改变本分支结论：令牌层已判「两边都没改」，
            //    内容若碰巧不同，保守起见仍由条件写（412 → CONFLICT）去处理。
            val baseline = Kdbx.baselineStateOf(vaultId)
            if (baseline != null && !baseline.aligned) {
                adoptRemoteIfSameContent(vaultId, source)
            }
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
            //
            // ⚠️ 上面这半（版本令牌）只是**两道防线里的第一道**，而且它有两处天生够不着：
            //
            //  1. **进程重启之后它就不成立了**：`remoteVersionToken` 是持久化的，而"会话里
            //     那份内容来自哪一版"是内存态 ⇒ 用户看不到角标也会继续编辑；
            //  2. **它管不住主写路径**：主写路径的 `expectedVersion` 取自本地缓存里的旧令牌，
            //     "内容与令牌配套"这个前提从一开始就不满足（`.ai/ISSUES.md` #185）。
            //
            //   ⇒ 第二道防线是**内容对账**（下面这段）：手里若有远端当前字节，就比一比
            //     "本机内容 == 远端内容吗"。相同 ⇒ 安全，顺手把令牌采纳成远端现在的值
            //     （这不算"提前推进"—— 内容确实已在远端那一版上，采纳只是让它变成可用基线）；
            //     不同 ⇒ **保持 REMOTE_CHANGED、一个字节都不写**，直到用户真的拉下来。
            markStatus(vaultId, KdbxSyncTransitions.markRemoteChanges())
            return if (adoptRemoteIfSameContent(vaultId, source)) {
                // 内容本来就等于远端那一版（典型：本机刚打开时读到过它）⇒ 从"远端有改动"
                // 降级为"一致"。持久化状态由上面的 markStatus 先写，这里再补一次真相。
                markStatus(vaultId, KdbxSyncStatus.IN_SYNC, remoteNow)
                SyncOutcome.AlreadyInSync
            } else {
                SyncOutcome.RemoteNewerNeedsReload
            }
        }

        // ④ 本地改了、远端没改 ⇒ 推。
        //
        // ⚠️ `baselineAligned = null` 是刻意的：编发器推的是**会话当前内容**，
        //    而它可能是"上次编辑后一直没推上去"的旧货（这时基线本来就已经对不上）。
        //    这里不做内容闸门，真正的判断交给条件写（`lastKnownVersion`）——
        //    远端若在期间变过，服务端会拒写并转 CONFLICT，那才是这条路径的正解。
        //    写回基线只服务于**条目编辑的即时保存**（`KdbxItemRepository.persist`）。
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
     * **上传成功后收敛缓存里的版本令牌**（#233）。
     *
     * ## 为什么必须有这一步
     *
     * 同一个事实被存了两份：`vaults.remoteVersionToken`（服务端最新版）与
     * 远端缓存的 `CachedKdbxFile.versionToken`（这份字节对应哪一版）。
     * 条目保存那次缓存写**刻意沿用旧令牌**（那是条件写的基线），随后自动上传把字节推上去、
     * 本方法以外的路径只更新了 `vaults` 那一列 ⇒ 两份**永久漂移**。
     *
     * 漂移的表现不是报错，而是：每次解锁都判"远端已变" ⇒ 白下一次整库 +
     * 挂一个用户什么都没做就出现的「远端有更新」角标。
     *
     * @param origin 持久化的来源标识（`onedrive:…` / `webdav:…`）。**不是 vaultId** ——
     *   两者在生产里同值（`KdbxCreateRepositoryImpl` 建库时 `id = resolvedOrigin`），
     *   但那是**巧合**，不是契约：拿 vaultId 去查表取其 origin，会在
     *   "表里还没这行 / 行被删了" 时**安静地 return**（什么都不做）。
     *   调用点本来就有 origin（它刚从 `vaultDao.get(vaultId)` 拿到），直接传来即可。
     * @param bytes 刚写上去的字节。
     * @param newVersion 服务端给的新版本令牌。
     */
    suspend fun recordUploaded(origin: String, bytes: ByteArray, newVersion: String?) {
        // ⚠️ 重新解析一次来源，而不是把 `source` 传进来：解析是**幂等**的（同一 origin 同一张表），
        //    而把 source 透传出去会让本方法依赖"调用方手上那个恰好是缓存装饰器"这一偶然事实 ——
        //    换成裸来源时 `recordUploaded` 会被静默跳过，正是本轮要修的那类"不报错的失效"。
        val source = fileSourceFactory(origin) ?: return
        if (source !is CachedKdbxFileSource) {
            // 非远端缓存来源（本地 SAF 直连）：没有"缓存令牌"这回事，什么都不用做。
            return
        }
        source.recordUploaded(bytes, newVersion)
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
        // ⚠️ 两个"有待办"的状态都不许降级，理由与 [markRemoteChanged] 那边完全一致：
        //  - CONFLICT：表达"要用户拍板"，覆盖成 PENDING_UPLOAD 会让冲突对话框失去依据；
        //  - PENDING_UPLOAD_WITH_LOCAL_CHANGES：表达"已上传一份，本地又攒了改动"。
        //    它同样是 PENDING_UPLOAD 的**上位状态**（信息更多），降级只会丢掉
        //    "上一份已经上去了"这条事实。2026-11-02 前这里只拦了 CONFLICT，
        //    与 [markRemoteChanged] 不对称 —— 同一个语义在两条路径上有两种行为，
        //    将来读代码的人必然会踩。
        if (current.syncStatus in NON_DOWNGRADABLE) return
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
        if (current.syncStatus in NON_DOWNGRADABLE_FROM_REMOTE) return
        markStatus(vaultId, KdbxSyncTransitions.markRemoteChanges())
    }

    /**
     * **内容对账**：本机内容是不是**已经**等于远端当前那一版？（#185 的第二道防线）
     *
     * ## 为什么不能只看版本令牌
     *
     * 令牌回答"远端有没有动过"，回答不了"我手里这份是不是它"。两者分开之后才有正确结论：
     *
     * | 令牌变了 | 内容相同 | 真相 |
     * |---|---|---|
     * | 是 | 是 | 远端换了编码/换了个 eTag，**内容没变** ⇒ 该采纳，不该逼用户拉一次 |
     * | 是 | 否 | 远端真的有改动 ⇒ 必须 `REMOTE_CHANGED`（写回会被基线拦下） |
     *
     * @return `true` = 内容一致，基线已可用（调用方可以把它当成"已同步"）。
     *
     * ⚠️ 读远端是**整份下载**的代价，所以只在"令牌变了"这一支才会走到 ——
     *   那是原本就要用户手动拉一次的情形，多读一次不改变量级；而**省掉的是**
     *   "本机明明就是最新版却要用户点一次同步"（真机上最容易让人怀疑数据的一步）。
     * ⚠️ ★★ 必须用 [KdbxFileSource.readRemoteCurrent] 而**不是** `read()`（2026-11-03 修）：
     *   `read()` 的契约只是"把这个库给我"，生产里远端来源套了缓存装饰器，有缓存时
     *   它**一个网络请求都不发**。拿缓存字节当"远端当前字节"会得到必然相同的结论 ⇒
     *   误判「已同步」并把基线推进到新令牌 ⇒ 远端那版**再也不会被拉**（还会被旧内容盖回去）。
     *   这个缺陷的表现是"点同步提示已同步，条目还是旧的"，不报错、不崩，极难自查。
     * ⚠️ 任何读取/解码失败一律返回 `false`：对账失败要**退回保守判定**，
     *   绝不能因为"读不到"就假定"一样"（那正是静默覆盖的入口）。
     */

    private suspend fun adoptRemoteIfSameContent(
        vaultId: String,
        source: KdbxFileSource,
    ): Boolean = runCatching {
        Kdbx.adoptBaselineIfMatches(vaultId, source.readRemoteCurrent())
    }.getOrElse { error ->
        // 只记原因类别，不记内容（VaultixLog 铁律）。
        error.message?.let { reason -> VaultixLog.d(TAG) { "对账失败（按未对齐处理）：$vaultId $reason" } }
        false
    }

    /**
     * ★★ 两边的"改过"标记都为真时，**先用内容验一遍**再决定要不要报冲突
     * （#244，2026-11-04 真机报障）。
     *
     * ## 修的是什么
     *
     * 用户原话：「打开 onedrive 上的 kdbx 就是冲突，实际上我根本没有新建条目和修改条目」。
     * 两个布尔都判成 true，可至少有一个是**假的**：
     *
     * - `remoteChanged` 假阳性：OneDrive 的 `eTag` 是**整个 item**（元数据 + 内容）的
     *   版本，改权限、服务端处理、甚至一次元数据更新都会让它变，而内容一个字节没动；
     * - `localChanged` 假阳性：`PENDING_UPLOAD` 只表达"本地有东西没推上去"
     *   （新接库、上一次上传没跑完），但只要本机内容**已经等于**远端那一版，
     *   就没有任何东西需要用户拍板。
     *
     * ⇒ 令牌只回答"有没有动过"，回答不了"内容是不是同一份"。冲突是**要求用户做
     * 有损决策**的强结论，落它之前必须用内容把两个布尔各验一遍 —— 而
     * [adoptRemoteIfSameContent] 此前只在 `localChanged == false` 那条分支里跑，
     * 于是"两边都判 true"时**从来没人验过**（真机就是卡在这里）。
     *
     * ## 为什么这样做不丢数据
     *
     * 对账只认一个事实：**本机内容与远端当前字节逐字节相同**。相同 ⇒ 没有任何一方
     * "多了东西"，对齐基线即可；不同（或读不到 / 解不开）⇒ 原样报冲突，一个字都不写。
     * 也就是说它只会**消掉假冲突**，不会把真冲突判成没事。
     *
     * ⚠️ 代价：真冲突时多下载一次整库（对账读的就是远端当前字节）。换掉的是
     *   "用户明明没改却被拦下来赌自己删过什么"，这个交换明显划算。
     *   想彻底省掉这一次，得把 `remoteChanged` 本身算准（cTag / 哈希），那是同方向的另一半。
     */
    private suspend fun resolveConflictByContent(
        vaultId: String,
        source: KdbxFileSource,
        remoteNow: String?,
    ): SyncOutcome {
        if (adoptRemoteIfSameContent(vaultId, source)) {
            // 内容本来就一致 ⇒ "两边都改了"不成立。状态回到干净，基线采成远端当前版本。
            markStatus(vaultId, KdbxSyncStatus.IN_SYNC, remoteNow)
            VaultixLog.d(TAG) { "对账通过：本机内容 == 远端内容，冲突不成立（$vaultId）" }
            return SyncOutcome.AlreadyInSync
        }
        markStatus(vaultId, KdbxSyncTransitions.markConflict())
        return SyncOutcome.NeedsUserDecision(currentRemoteVersion = remoteNow)
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

    private companion object {
        /** logcat 里按它过滤（`adb logcat -s VaultixKdbxSync`）。 */
        const val TAG = "VaultixKdbxSync"

        /**
         * 任何"降级标记"都**不许**覆盖的状态：它们都在表达"有事要用户处理"。
         *
         * ⚠️ 刻意做成一个集合而不是到处写 `if (x == A) return; if (x == B) return`：
         * 两个入口（[markLocalEdited] / [markRemoteChanged]）此前各自漏了不同的状态，
         * 同一个语义在两条路径上有两种行为。集中一处之后，"要不要拦"这件事只有一份答案。
         */
        val NON_DOWNGRADABLE = setOf(
            KdbxSyncStatus.CONFLICT.name,
            KdbxSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES.name,
        )

        /**
         * [markRemoteChanged] 额外还要拦 [KdbxSyncStatus.PENDING_UPLOAD]。
         *
         * ## 为什么这一条**只**给远端路径
         *
         * `PENDING_UPLOAD` = "本地有东西没推上去"。此时若报"远端也变了"，会**盖掉**
         * 更需要提示的那件事（推不上去比"远端有更新"更紧急）。但这不构成"不许写"，
         * 它只是"先别说远端" ⇒ 只拦远端方向的标记。
         *
         * ⚠️ 反过来（本地编辑了而状态是 REMOTE_CHANGED）**必须**允许降级成
         * `PENDING_UPLOAD`：那时（远端有更新 + 本地也改了）就是冲突的成因，
         * 交给下一次 sync 去判 CONFLICT 才是正解。
         */
        val NON_DOWNGRADABLE_FROM_REMOTE = NON_DOWNGRADABLE + KdbxSyncStatus.PENDING_UPLOAD.name

        /** 本地 SAF 来源的 origin 前缀（见 [isLocalOnlyOrigin] 的说明：与协调器同值）。 */
        const val SAF_ORIGIN_PREFIX = "content://"
    }
}
