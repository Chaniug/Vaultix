/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 网盘库的「保存后自动上传」**（施工单 S1 + S6）。
 *
 * ## 为什么单独立一个类
 *
 * 触发点是 `KdbxItemRepository.persist`（每次条目写回），但"上传"这件事有自己的
 * 生命周期：**它比一次保存长得多**（网盘一次写入 5–60 s），而且要**串行**、要**收敛**。
 * 把这些塞进 `persist` 会让它同时承担"落盘"与"排队调度"两种职责，
 * 而调度是要单测的（见文末）⇒ 独立成类，与既有 `KdbxSync*` / `KdbxItemRepository` 一致。
 *
 * ## 五条设计要点（每条都对应一个真实的坑）
 *
 * 1. **绝不阻塞保存**：[enqueue] 只 `launch`，保存路径一行都不等
 *    —— 网盘一次写入 5–60 s，同步调会把"保存"卡成转圈。
 * 2. **每库一把锁**：同一库的两次上传**串行**（否则后一次会拿着过期令牌条件写，
 *    要么报冲突、要么盖掉前一次）；不同库互不阻塞（[locks] 按 vaultId 分）。
 * 3. **本地 SAF 库不触发**：[KdbxCloudSyncCoordinator.hasCloudSource] 为假 ⇒ 直接收工。
 *    它的文件就是存储本身，没有"上传"这回事（标了会留下一个永远传不上去的「待上传」）。
 * 4. **失败后台有限重试**：网络/IO 失败（`KdbxSyncResult.Failed`）按退避重试，
 *    最多 [MAX_RETRIES] 次；冲突 / 远端有更新这类需要用户决策的终态仍然一次收工。
 *    ★ 2026-11-08 真机：一次 72s 超时后状态停在 `FAILED`，而 `enqueue` 只在
 *    `persist` 里调用 ⇒ 用户必须再编辑一次或手动点同步才能把改动推上去。
 *    重试全程在后台、不阻塞任何界面；有上限，离线时不会无限空转。
 * 5. **异常不许逸出**：`SupervisorJob` + `runCatching` 双保险 —— 一个库上传炸了
 *    不能带塌整个上传器（否则后续所有库的自动上传都静默失效）。
 *
 * ## 为什么要"多轮"而不是"一次"
 *
 * 上传那 5–60 s 里用户完全可以再改一笔（`persist` → `markLocalEdited` ⇒ 状态回到
 * `PENDING_UPLOAD`）。只跑一次的话那笔改动就**永远推不上去** ——
 * 这正是 `KdbxSyncOrchestrator.notifyLocalChangedDuringUpload` 的 KDoc 点名的竞态。
 * ⇒ 每轮结束**现查一次状态**，还有待上传就再跑一轮，直到状态收敛或到达 [MAX_ROUNDS]。
 *
 * ⚠️ 上限是必须的：它保证"持续有人改"这种场景下不会无限排队（每轮仍受锁串行约束，
 * 但无界循环会让一个手快的用户把上传器占满）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.common.logging.VaultixLog
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.database.dao.VaultDao
import io.vaultix.model.KdbxCloudSyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保存后的自动上传器。
 *
 * @param vaultDao 现查 `origin`（判别网盘 / 本地）与 `syncStatus`（判别还有没有待上传）。
 * @param coordinator 真正的同步入口（条件写、冲突翻译都在这条链上）。
 */
@Singleton
class KdbxAutoUploader @Inject constructor(
    private val vaultDao: VaultDao,
    private val coordinator: KdbxCloudSyncCoordinator,
) {

    /**
     * 上传用的 scope：**应用级**（与 `ItemRepositoryImpl.shareScope` 同款写法）。
     *
     * ⚠️ `SupervisorJob` 不是可选项：用普通 `Job` 时，一个子协程失败会**连带取消
     * 父 Job**，于是后续所有 `enqueue` 都会立刻被取消 —— 表现为"第一次上传失败之后
     * 自动上传再也没发生过"，而且没有任何报错。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每库一把锁（S6）。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 排一次上传（**立即返回，不等结果**）。
     *
     * @return 发起的 [Job] —— 单测靠 `join()` 等它跑完（没有它，测试只能靠 `sleep` 赌时序）。
     */
    fun enqueue(vaultId: String): Job = scope.launch {
        VaultixLog.d(TAG) { "自动上传排队（$vaultId）" }
        // ⚠️ 异常在这里就地吸收：逸出到 scope 会取消 SupervisorJob 的这个子协程，
        // 而我们要的是"这次失败留下 FAILED 状态，下次还能再试"。
        // ⚠️ 11-07：但**不能静默吞** —— 真机上「保存后有时没上传」查了很久，
        //    就是因为这个 runCatching 把原因吃得一点不剩。至少留一条可过滤的日志。
        runCatching { drain(vaultId) }
            .onFailure { error ->
                VaultixLog.w(TAG) { "自动上传异常退出（$vaultId）：${error.message}" }
            }
    }

    /**
     * ★★ 冷启动 / 恢复后**补传**所有还躺在缓存里的改动（#286，2026-11-12）。
     *
     * ## 为什么必须有它
     *
     * [enqueue] 的唯一调用点是 `KdbxItemRepository.persist`（保存那一刻）。
     * 上传跑在应用级后台协程里，页面返回不会取消它，但**进程被杀**，或**断网后
     * 后台重试到上限**，就再没有任何人排第二次队：改动以 `pendingLocalEdits`
     * 一直留在缓存里，本地看得到、云端永远没有，直到用户再编辑一次或手动点同步。
     * 真机表现正是用户说的「同步过程中返回了 / 断网了，相当于没同步」。
     *
     * ⇒ 进程创建时扫一次全部库。`drain` 内部的 `hasWork` 会自己判
     *   「DB 状态 + 缓存脏标记」，干净库直接收工，不会多发一次网络请求。
     *
     * ⚠️ 只扫**有云端来源**的库：本地 SAF 的文件就是存储本身，没有"上传"这回事。
     * ⚠️ 全程在应用级 scope、不阻塞启动；单个库失败只留痕，绝不影响其它库。
     *
     * ★★ #286 补正：**冷启动时库通常还锁着**，而上传链路
     *   （`KdbxSyncOrchestrator.sync` → `Kdbx.saveVia`）要 `KdbxSessionStore` 里有会话，
     *   没解锁必然返回 `NotUnlocked`。不加过滤地扫全表 ⇒ 每个脏库都空转一轮、
     *   写一条假 `FAILED`、再退避重试 3 次，改动仍然一个字节没上去。
     *   ⇒ [resumePendingUploadsNow] 只处理**已解锁**的库；锁着的库由
     *     [startUnlockObserver] 在用户解锁成功的那一刻补传。
     */
    fun resumePendingUploads(): Job = scope.launch {
        runCatching { resumePendingUploadsNow() }
            .onFailure { error ->
                VaultixLog.w(TAG) { "补传扫描异常退出：${error.message}" }
            }
    }

    /**
     * [resumePendingUploads] 的可单测本体。
     *
     * ⚠️ 用 [coroutineScope] 而不是逐个 `launch` 完就返回：前者让本挂起函数
     *   等所有库的补传跑完，测试直接调一次就能断言全部结果；后者会在子协程
     *   还在跑时就返回，测试只能靠 `sleep` 赌时序。
     *
     * @param sessionReady 「这个库此刻有没有可用会话」的判据。生产默认
     *   [Kdbx.isUnlocked]；单测传 `{ true }` 才不会被进程里真实的锁态干扰。
     *
     * ## ★ 为什么必须过滤锁着的库（#286，2026-11-12）
     *
     * 上传字节由 `Kdbx.saveVia` 从**内存会话**现编码，会话是解锁才有的进程内明文。
     * 冷启动时库还锁着 ⇒ 那次 `sync` 必然以 `NotUnlocked` 失败，编排器顺手写
     * `FAILED`、本类再退避重试 [MAX_RETRIES] 次 —— 全是空转，而改动仍留在脏缓存里。
     * ⇒ 锁态库**先跳过**，解锁事件到来时由 [startUnlockObserver] 精准补传。
     * 这与「改动不丢」是两件事：刷新闸门（双真源）已经保证锁着期间不会被云端覆盖。
     */
    suspend fun resumePendingUploadsNow(sessionReady: (String) -> Boolean = Kdbx::isUnlocked) {
        val rows = runCatching { vaultDao.observeAll().first() }
            .onFailure { error -> VaultixLog.w(TAG) { "补传扫描读库表失败：${error.message}" } }
            .getOrDefault(emptyList())
        val ids = rows
            .filter { coordinator.hasCloudSource(it.origin) }
            .filter { sessionReady(it.id) }
            .map { it.id }
        VaultixLog.d(TAG) { "补传扫描：${ids.size} 个已解锁的网盘库待检查" }
        coroutineScope {
            ids.forEach { id ->
                launch {
                    runCatching { drain(id) }
                        .onFailure { error ->
                            VaultixLog.w(TAG) { "补传异常退出（$id）：${error.message}" }
                        }
                }
            }
        }
    }

    /**
     * ★★ #286：订阅「会话解锁」，**新解锁的库立刻补传一次**。
     *
     * ## 为什么必须有它（补上冷启动补传的最后一段）
     *
     * [resumePendingUploads] 在 `Application.onCreate` 里跑，那时库通常是锁着的，
     * 被 [resumePendingUploadsNow] 的会话判据过滤掉。而全仓的解锁路径
     * （主密码 / 快速解锁 fanout / autofill / 「从不锁定」自动恢复）**都没有**
     * 同步或补传钩子；`ItemsViewModel.init` 只登记活跃库，手动同步要点按钮。
     * ⇒ 不订阅解锁事件，那笔改动就会一直躺在脏缓存里，直到用户再编辑一次。
     *
     * ⚠️ 不用「每次会话变化都扫全表」：`Kdbx.sessionRevision()` 在**每次条目编辑**
     *    （`mutateLocked` 提交新会话）也会发值，全表扫描会无谓地重读所有库。
     *    改成只取 [newlyUnlockedIds] 的差集 ⇒ 只有「从无到有」的会话才触发。
     *    `drain` 内部仍有 `hasWork` / `hasCloudSource` 两道短路，干净库零网络请求。
     *
     * ⚠️ **显式启动而不是放进 `init`**：单测每次 `new KdbxAutoUploader(...)` 都会
     *    挂一条常驻订阅到全局静态流上，别的用例解锁库时会意外打到它的 mock。
     *    生产只在 app 层的 `VaultixApplication.onCreate()` 调一次。
     * ⚠️ 应用级 scope 常驻是**预期**的：`Kdbx.sessionRevision()` 是 StateFlow，
     *    `collect` 不完成，进程在就一直监听；单个库失败就地吸收。
     */
    fun startUnlockObserver(): Job = scope.launch {
        var known = Kdbx.unlockedIds()
        Kdbx.sessionRevision().collect {
            val now = Kdbx.unlockedIds()
            val newly = newlyUnlockedIds(known, now)
            known = now
            newly.forEach { id ->
                runCatching { drain(id) }
                    .onFailure { error ->
                        VaultixLog.w(TAG) { "解锁补传异常退出（$id）：${error.message}" }
                    }
            }
        }
    }

    /**
     * 把这个库的待上传改动推干净（**串行**，由调用方所在的锁保证）。
     *
     * ⚠️ 这是 `suspend` 而不是 `fun`：单测要能直接调它并断言"跑完之后状态是什么"。
     */
    suspend fun drain(vaultId: String, retryDelaysMs: LongArray = RETRY_DELAYS_MS) {
        val row = vaultDao.get(vaultId) ?: return
        // 本地 SAF 库：文件即存储，没有"上传"这回事（要点 3）。
        if (!coordinator.hasCloudSource(row.origin)) return

        locks.getOrPut(vaultId) { Mutex() }.withLock {
            var rounds = 0
            var failures = 0
            // ★★ 11-07：DB 状态不是「有没有活」的唯一真源。
            //    `sync` 一开头就 markStatus(SYNCING) 冲掉 PENDING_UPLOAD，而
            //    impliesLocalChanges(SYNCING)=false；并发/紧随的另一次同步再把它
            //    洗成 IN_SYNC 时，这里 while 条件直接为假 ⇒ **整轮上传根本不跑**。
            //    缓存里的 pendingLocalEdits 只在 write() 成功后才清，是更稳的真源。
            while (rounds < MAX_ROUNDS && hasWork(vaultId)) {
                rounds++
                VaultixLog.d(TAG) { "自动上传起跑：第 $rounds 轮" }
                val result = coordinator.sync(vaultId, localChangedSinceLastSync = true)
                // ★ 2026-11-08：以前只记「可否继续」，把 Failed(reason) / Conflict 全吞了，
                //    真机 72s 超时后日志里查不出任何原因。现在记结果类型与失败原因。
                VaultixLog.d(TAG) {
                    "第 $rounds 轮结果=${result.describe()}，可继续=${result.canContinueDraining()}"
                }
                val failed = result is KdbxSyncResult.Failed
                failures = if (failed) failures + 1 else 0
                // ⚠️ 循环里只允许一个 break（detekt LoopWithTooManyJumpStatements）：
                //    把「要不要再试一轮」算成布尔，最后统一决定收工。
                val retry = failed && failures <= MAX_RETRIES && hasWork(vaultId)
                if (retry) {
                    // 退避在后台协程里等，界面一行都不等；测试可传零延迟避免真实等待。
                    val waitMs = if (failures <= retryDelaysMs.size) {
                        retryDelaysMs[failures - 1]
                    } else {
                        RETRY_DELAY_THIRD_MS
                    }
                    VaultixLog.d(TAG) { "后台重试：第 $failures 次，${waitMs}ms 后再推" }
                    delay(waitMs)
                }
                // 冲突 / 远端有更新 / 重试到上限 ⇒ 收工：这些需要用户决策，重试解决不了。
                if (!retry && !result.canContinueDraining()) {
                    VaultixLog.w(TAG) {
                        "自动上传收工：${result.describe()}，连续失败 $failures 次"
                    }
                    break
                }
            }
        }
    }

    /** DB 状态 + 缓存脏标记，任一为真就还有活要干。 */
    private suspend fun hasWork(vaultId: String): Boolean =
        hasPendingUpload(vaultId) || coordinator.hasCachedLocalEdits(vaultId)

    private suspend fun hasPendingUpload(vaultId: String): Boolean =
        KdbxCloudSyncStatus.fromName(vaultDao.get(vaultId)?.syncStatus)?.hasPendingUpload == true

    /** 只有"确实推上去了 / 本来就没差异"才值得再看一轮。 */
    private fun KdbxSyncResult.canContinueDraining(): Boolean = when (this) {
        is KdbxSyncResult.Uploaded,
        is KdbxSyncResult.InSync,
        -> true

        else -> false
    }

    /** 结果类型 + 失败原因（只记原因的前一段，绝不把整条异常栈刷进 logcat）。 */
    private fun KdbxSyncResult.describe(): String = when (this) {
        is KdbxSyncResult.Uploaded -> "Uploaded"
        is KdbxSyncResult.InSync -> "InSync"
        is KdbxSyncResult.Downloaded -> "Downloaded"
        is KdbxSyncResult.Conflict -> "Conflict"
        is KdbxSyncResult.StaleBaseline -> "StaleBaseline"
        is KdbxSyncResult.NeedsReload -> "NeedsReload"
        is KdbxSyncResult.NoCloudSource -> "NoCloudSource"
        is KdbxSyncResult.Failed -> "Failed(${reason.take(MAX_REASON_LENGTH)})"
    }
}

private const val TAG = "VaultixKdbxUpload"

/**
 * 一次 [KdbxAutoUploader.enqueue] 最多跑几轮 `sync`。
 *
 * 取 6：正常 1 轮就收敛；额外轮次服务两件事 —— 上传期间用户又改了一笔，
 * 以及网络失败后的最多 [MAX_RETRIES] 次后台重试。再多就没有新信息了，
 * 交给下一次保存去触发更合适，而不是让上传器一直转。
 */
private const val MAX_ROUNDS = 6

/** 网络 / IO 失败后的后台重试次数上限（不含首次尝试）。 */
private const val MAX_RETRIES = 3

private const val RETRY_DELAY_FIRST_MS = 3_000L
private const val RETRY_DELAY_SECOND_MS = 8_000L
private const val RETRY_DELAY_THIRD_MS = 20_000L

/** 退避：3s → 8s → 20s，全程在后台，用户无感。 */
private val RETRY_DELAYS_MS = longArrayOf(
    RETRY_DELAY_FIRST_MS,
    RETRY_DELAY_SECOND_MS,
    RETRY_DELAY_THIRD_MS,
)

private const val MAX_REASON_LENGTH = 120

/**
 * [KdbxAutoUploader.startUnlockObserver] 的可单测纯判据：只取**这次新解锁**的库。
 *
 * ⚠️ 差集而不是"当前已解锁全集"：`sessionRevision` 在每次条目编辑时也会发值，
 *    用全集会让一次普通的编辑把所有已解锁库全部重新排一次队。
 *    锁库再解锁会重新出现在差集里 —— 那正是我们想补传的时刻。
 */
internal fun newlyUnlockedIds(before: Set<String>, after: Set<String>): Set<String> = after - before
