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
 * 4. **失败不重试**：一次失败就收工，状态留给编排器记 `FAILED`（角标已能显示）。
 *    离线时反复重试没有意义，只会把 CPU 和电量烧在一个必然失败的请求上。
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

import io.vaultix.database.dao.VaultDao
import io.vaultix.model.KdbxCloudSyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        // ⚠️ 异常在这里就地吸收：逸出到 scope 会取消 SupervisorJob 的这个子协程，
        // 而我们要的是"这次失败留下 FAILED 状态，下次还能再试"。
        runCatching { drain(vaultId) }
    }

    /**
     * 把这个库的待上传改动推干净（**串行**，由调用方所在的锁保证）。
     *
     * ⚠️ 这是 `suspend` 而不是 `fun`：单测要能直接调它并断言"跑完之后状态是什么"。
     */
    suspend fun drain(vaultId: String) {
        val row = vaultDao.get(vaultId) ?: return
        // 本地 SAF 库：文件即存储，没有"上传"这回事（要点 3）。
        if (!coordinator.hasCloudSource(row.origin)) return

        locks.getOrPut(vaultId) { Mutex() }.withLock {
            var rounds = 0
            while (rounds < MAX_ROUNDS && hasPendingUpload(vaultId)) {
                rounds++
                val result = coordinator.sync(vaultId, localChangedSinceLastSync = true)
                // 失败 / 冲突 / 远端有更新 / 无云端 ⇒ 收工：状态已由编排器记下，
                // 用户能从角标看见，重试解决不了它们（要点 4）。
                if (!result.canContinueDraining()) break
            }
        }
    }

    private suspend fun hasPendingUpload(vaultId: String): Boolean =
        KdbxCloudSyncStatus.fromName(vaultDao.get(vaultId)?.syncStatus)?.hasPendingUpload == true

    /** 只有"确实推上去了 / 本来就没差异"才值得再看一轮。 */
    private fun KdbxSyncResult.canContinueDraining(): Boolean = when (this) {
        is KdbxSyncResult.Uploaded,
        is KdbxSyncResult.InSync,
        -> true

        else -> false
    }
}

/**
 * 一次 [KdbxAutoUploader.enqueue] 最多跑几轮上传。
 *
 * 取 3：正常情况 1 轮就收敛（状态回到 `IN_SYNC`），多出来的两轮只服务
 * "上传期间用户又改了"这种真竞态；再多就没有新信息了 —— 那说明写入方在持续改动，
 * 交给下一次保存去触发更合适，而不是让上传器一直转。
 */
private const val MAX_ROUNDS = 3
