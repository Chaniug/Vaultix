/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 同步编排的分阶段思路与失败分类参考 Bastion 项目（GPL-3.0，Copyright 2025
 * JoyinJoester）的 sync/BitwardenSyncOrchestrator.kt 与其同步文档；本文件按其
 * String 型 vaultId 与 Vaultix 存储层独立编写，并规避其文档中记录的事故。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.sync

import io.vaultix.common.logging.VaultixLog
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.CipherResponse
import io.vaultix.data.bitwarden.model.FolderDto
import io.vaultix.data.bitwarden.model.SyncResponse
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.FolderEntity
import io.vaultix.database.entity.PendingOpEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步链路的日志 tag：`adb logcat -s VaultixSync` 可单独看一次同步的全过程。
 *
 * ⚠️ 本链路只记**数量与结果类别**，不记条目 id / 明文 / 密文（见 `VaultixLog` 的铁律）。
 */
private const val TAG = "VaultixSync"

/**
 * 推送连续失败多少次后开始报警（2026-09-16 新增加）。
 *
 * ⚠️ 只用于**报警**，不用来自动淘汰 —— 见 [BitwardenSyncService.flushPending] 末尾的说明。
 */
private const val STUCK_RETRY_THRESHOLD = 10

/**
 * Bitwarden 同步执行（M1）。
 *
 * 流程：推送本地待上传 → revision 预检 → 全量拉取 → 安全校验 → 落库 →
 * 清理（收敛服务端已移除的本地行，Bastion deleteNotIn 语义）。
 *
 * 触发分类/节流/失败退避/per-vault 状态流由 data:repository 的
 * BitwardenSyncOrchestrator（Bastion 编排语义移植版）统一管理，本类只负责
 * 一次执行的原子步骤。上传单条失败不中断；HTTP 4xx（408/429 除外）视为
 * 服务端目标已不存在或不可执行 → 永久弃单（防毒丸条目卡死队列）。
 */
@Singleton
class BitwardenSyncService @Inject constructor(
    private val apiFactory: BitwardenApiFactory,
    private val vaultDao: VaultDao,
    private val cipherDao: CipherDao,
    private val folderDao: FolderDao,
    private val pendingOpDao: PendingOpDao,
    private val authRepository: BitwardenAuthRepository,
    private val json: Json,
    /** 存「待确认删除」的指纹（见 [VaultixPreferences.pendingPruneFingerprint]）。 */
    private val preferences: VaultixPreferences,
) {

    suspend fun sync(vaultId: String, server: String, force: Boolean = false): SyncOutcome =
        runCatching { executeSync(vaultId, server, force) }
            .getOrElse { error -> classifyError(error, server) }

    private suspend fun executeSync(vaultId: String, server: String, force: Boolean): SyncOutcome {
        // 1) 先推送本地改动：新建/修改不应被整库下载挡住（Bastion 事故结论之一）
        //
        // ⚠️ 失败**不能忽略、也不能拦路**（2026-09-16 隐患排查 ①，第一次改就踩了半边）：
        //    - 不能忽略：若推送失败而结果仍报成功/Skipped，UI 会说「刚刚同步成功」、
        //      清零 retryAttempt、不安排重试，而用户的改动还躺在队列里（漏 + 误报）；
        //    - 也不能"失败就不拉取"：那会让**一条**持久失败的条目阻塞**整个库**的同步
        //      （而队列没有重试上限，那条毒丸永远修不掉 ⇒ 库永久卡死）。
        //    ⇒ 正确做法：拉取照常进行（本地未推送的改动由 `pendingIds` 保护），
        //      但**最终结论不算成功**，如实报成可重试。
        val flushFailure = flushPending(vaultId, server).exceptionOrNull()
        if (flushFailure != null) {
            VaultixLog.w(TAG) {
                "flushPending 部分失败：${flushFailure::class.simpleName}（拉取继续，本地改动受 pendingIds 保护）"
            }
        }

        val localRevision = vaultDao.get(vaultId)?.revisionDate
        val remoteRevision = fetchRevision(server)
        val remoteMatchesLocal = remoteRevision != null &&
            remoteRevision.toString() == localRevision

        // 2) 预检：Vaultwarden 会忽略 sinceRevisionDate 增量游标，必须显式比对
        if (!force && localRevision != null && remoteMatchesLocal) {
            // ⚠️ 推送有失败时**不能**报 `Skipped`：上层把 Skipped 当成功处理
            //    （`BitwardenSyncOrchestrator` 的 Skipped 分支会记 `lastSuccessAt`、
            //    清零 `retryAttempt`、不排重试），于是"改动没上云"被显示成"刚刚同步成功"。
            if (flushFailure != null) {
                return SyncOutcome.RetryableError(
                    "本地改动未能推送（${flushFailure::class.simpleName}），稍后将重试",
                )
            }
            VaultixLog.d(TAG) { "sync skipped: revision 未变（$localRevision）" }
            return SyncOutcome.Skipped
        }

        val response = apiFactory.vault(server).sync()
        VaultixLog.d(TAG) { "sync pulled: cipher ${response.ciphers.size} / folder ${response.folders.size}" }

        // 3) 安全校验：防止服务端故障返回空数据而清空本地
        val protection = checkProtection(vaultId, localRevision, response)
        if (protection != null) {
            // ★ 阻断是**保护性事件**，必须留痕：用户报"同步没动静"时，
            //   这条能立刻分辨「被空库保护挡了」还是「网络问题」。
            VaultixLog.w(TAG) { "sync BLOCKED: $protection" }
            return protection
        }

        // 4) 落库 + 清理服务端已移除的本地行
        persistCiphers(vaultId, response.ciphers)
        persistFolders(vaultId, response.folders)
        pruneRemovedRows(vaultId, response)
        vaultDao.updateRevision(vaultId, remoteRevision?.toString() ?: localRevision)

        // 拉取本身成功；但若推送仍有失败，**不能报 Success** —— 那等于告诉用户「已同步」，
        // 而他的改动还没上云（这正是隐患排查 ① 要消灭的那句谎话）。
        if (flushFailure != null) {
            return SyncOutcome.RetryableError(
                "已拉取服务端变更，但有改动未能推送（${flushFailure::class.simpleName}），稍后将重试",
            )
        }
        VaultixLog.d(TAG) { "sync ok: cipher ${response.ciphers.size} / folder ${response.folders.size}" }
        return SyncOutcome.Success(response.ciphers.size, response.folders.size)
    }

    /**
     * 推送该库全部待上传操作（新建走轻量 `POST /ciphers`，不等整库下载）。
     *
     * - 单条失败**不中断**：条目保留在队列并累计重试次数，供下次 flush / sync 再试；
     * - 新建条目推送成功后服务端会分配新 id（请求体不含 id 字段），若与本地临时 id
     *   不一致，本地行按服务端 id 重建（密文来自请求体，服务端不会二次加密，
     *   无需额外拉取），避免下次全量同步后出现同内容双行。
     *
     * @return 全部推送成功为 Success；有失败条目时为 failure（队列中仍保留）。
     */
    suspend fun flushPending(vaultId: String, server: String): Result<Unit> {
        val ops = pendingOpDao.listByVault(vaultId)
        if (ops.isEmpty()) return Result.success(Unit)

        val api = apiFactory.vault(server)
        var firstError: Throwable? = null
        for (op in ops) {
            runCatching { executeOperation(api, op) }
                .onSuccess { response ->
                    if (op.op == OP_CREATE) remapCreatedLocalRow(op, response)
                    pendingOpDao.remove(op.localId)
                }
                .onFailure { error ->
                    if (error.isDefinitiveHttpFailure()) {
                        // 4xx（408/429 除外）：目标在服务端已不存在或不可执行。
                        // 永久弃单防毒丸卡死队列。
                        //
                        // ⚠️ 弃单后必须**立刻**处理本地行，否则会静默丢数据：
                        // 队列一旦移除这条 op，紧接着的全量同步里它既不在服务端集合、
                        // 也不在 pendingIds 中 ⇒ pruneRemovedRows 判定为「服务端已删除」
                        // ⇒ 直接删掉本地行，用户看到的是「新建的条目凭空消失且无任何提示」。
                        //   - OP_CREATE：服务端从未接受它（4xx 即拒绝），本地这行注定无法同步，
                        //     立即删除并记日志，行为由「静默消失」变为「即刻、可追溯」；
                        //   - 其余 op（UPDATE/DELETE 等）：本地行本身是用户数据，**不能删**，
                        //     保留它会由正常全量同步按服务端版本覆盖收敛（此时服务端版本更权威）。
                        if (op.op == OP_CREATE) {
                            cipherDao.deleteByIds(listOf(op.cipherId))
                        }
                        pendingOpDao.remove(op.localId)
                    } else {
                        pendingOpDao.incrementRetry(op.localId)
                        firstError = firstError ?: error
                    }
                }
        }
        // ⚠️ 隐患 ③（2026-09-16 排查）：`retryCount` 此前**只写不读** —— 一条持续失败的
        //    条目会永远留在队列里，让 `flushPending` 永远返回 failure，用户只看到
        //    「永远有待推送」却不知卡在哪条、卡了多久。
        //
        // ⚠️ 这里**只报警、不改行为**，是刻意的：自动淘汰该 op 会连带影响本地行
        //    （op 一旦离开队列，该行就不在 `pendingIds` 里 ⇒ 会被 `pruneRemovedRows`
        //    删掉、或被服务端旧版覆盖），那比"卡住"更坏。
        //    真要淘汰，得先加 `failed` 标记位（需数据库迁移）并明确 UI 怎么呈现，另行评估。
        val stuck = pendingOpDao.listByVault(vaultId).count { it.retryCount >= STUCK_RETRY_THRESHOLD }
        if (stuck > 0) {
            VaultixLog.w(TAG) {
                "有 $stuck 条改动连续失败 $STUCK_RETRY_THRESHOLD 次以上仍未推送成功（保留在队列，未丢弃）"
            }
        }
        return firstError?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * 新建条目被服务端分配新 id 后，把本地临时行迁移到服务端 id：
     * 删除临时行 → 用请求密文（已加密字段与服务端一致）重建正式行。
     */
    private suspend fun remapCreatedLocalRow(op: PendingOpEntity, response: CipherResponse?) {
        val serverId = response?.id?.takeIf { it.isNotBlank() } ?: return
        if (serverId == op.cipherId) return // 服务端保留了客户端 id（少见），无需处理
        val temp = cipherDao.get(op.cipherId) ?: return
        val request = op.payload?.let { payload ->
            runCatching { json.decodeFromString<CipherRequest>(payload) }.getOrNull()
        } ?: return

        val dto = request.toStoredCipherDto(serverId, response.revisionDate)
        cipherDao.deleteByIds(listOf(op.cipherId))
        cipherDao.upsertAll(listOf(dto.toEntity(temp.vaultId)))
    }

    private suspend fun executeOperation(api: BitwardenVaultApi, op: PendingOpEntity): CipherResponse? {
        val body = op.payload?.let { json.decodeFromString<CipherRequest>(it) }
        return when (op.op) {
            OP_CREATE -> if (body != null) api.createCipher(body) else null
            OP_UPDATE -> if (body != null) api.updateCipher(op.cipherId, body) else null
            OP_SOFT_DELETE -> {
                api.softDeleteCipher(op.cipherId)
                null
            }
            OP_DELETE -> {
                api.permanentDeleteCipher(op.cipherId)
                null
            }
            OP_RESTORE -> api.restoreCipher(op.cipherId)
            else -> null // 未知 op 类型：按成功移除（防毒丸条目卡死整条队列）
        }
    }

    /** 取服务端 revision；失败返回 null（交由上层决定是否继续）。 */
    private suspend fun fetchRevision(server: String): Long? =
        runCatching { apiFactory.vault(server).revisionDate() }.getOrNull()

    private suspend fun checkProtection(
        vaultId: String,
        localRevision: String?,
        response: SyncResponse,
    ): SyncOutcome? {
        val isFirstSync = localRevision == null
        val localCount = cipherDao.listByVault(vaultId).size

        val result = EmptyVaultProtection.checkSyncAllowed(
            localCipherCount = localCount,
            serverCipherCount = response.ciphers.size,
            isFirstSync = isFirstSync,
        )
        if (result is EmptyVaultProtection.CheckResult.Blocked) {
            return SyncOutcome.Blocked(result.reason)
        }

        if (!isFirstSync && EmptyVaultProtection.hasSignificantDataLoss(localCount, response.ciphers.size)) {
            return SyncOutcome.Blocked(
                "服务端条目数（${response.ciphers.size}）远少于本地（$localCount），" +
                    "为保护数据已暂停同步，请确认后重试。",
            )
        }

        return checkDeleteConfirmation(vaultId, localCount, response)
    }

    /**
     * 删除前确认（2026-09-16 新增）：补上 [EmptyVaultProtection.hasSignificantDataLoss]
     * 与 [pruneRemovedRows] 之间那条缝。
     *
     * ## 缝在哪
     *
     * 骤减检查比的是「服务端总数 vs 本地总数」，阈值 50%。**服务端若只返回 60%，
     * 检查放行，而那 40% 的本地行会被 prune 直接删掉**；`pendingIds` 在这里帮不上忙
     * —— 它只保护「有本地未推送改动」的条目，保护不了「已同步过、用户从未删过」的。
     *
     * ## 做法：两次一致才删
     *
     * 先算「本次要删多少」，可疑就**不删**，只把这一批的指纹存下来并阻断；
     * 下次同步若仍是**同一批**，才认为服务端确实如此、放行。
     *
     * 这能把服务端侧**表现完全相同**的两种情况分开：
     * - 用户在官方网页端真的删了 ⇒ 下次同步仍是同一批 ⇒ 放行；
     * - 服务端瞬时故障返回不完整数据 ⇒ 下次就恢复了 ⇒ 不会两次相同。
     *
     * ⚠️ 指纹**必须持久化**：只放内存的话，进程重启后会重新判为"首次发现"，
     * 用户怎么同步都删不掉那批行（卡死）。
     */
    private suspend fun checkDeleteConfirmation(
        vaultId: String,
        localCount: Int,
        response: SyncResponse,
    ): SyncOutcome? {
        val removable = computeRemovableCipherIds(vaultId, response)
        if (!EmptyVaultProtection.requiresDeleteConfirmation(removable.size, localCount)) {
            // 没有可疑删除 ⇒ 清掉可能残留的旧指纹，否则下次会被误判成"已确认过"。
            if (preferences.pendingPruneFingerprint(vaultId).first() != null) {
                preferences.setPendingPruneFingerprint(vaultId, null)
            }
            return null
        }
        val fingerprint = removable.sorted().hashCode()
        if (preferences.pendingPruneFingerprint(vaultId).first() == fingerprint) {
            VaultixLog.w(TAG) {
                "delete-confirm 通过：${removable.size} 条（连续两次一致，判定为真实删除）"
            }
            return null
        }
        preferences.setPendingPruneFingerprint(vaultId, fingerprint)
        VaultixLog.w(TAG) {
            "sync BLOCKED(delete-confirm): 将删除 ${removable.size} / 本地 $localCount 条，需再同步一次确认"
        }
        return SyncOutcome.Blocked(
            "本次同步将删除 ${removable.size} 条本地记录（本地共 $localCount 条）。" +
                "为排除服务器故障，已暂停删除；请再同步一次以确认。",
        )
    }

    /**
     * 本次同步**将要删除**的本地 cipher id（已排除有待推送改动的）。
     *
     * ⚠️ 抽出来是为了让 [checkDeleteConfirmation]（删除**前**判定）与
     * [pruneRemovedRows]（真正执行）用**同一份**计算 —— 两处各算一遍迟早漂移，
     * 而漂移的后果是「判定放行了一批、实际删的却是另一批」，正是要防的事。
     */
    private suspend fun computeRemovableCipherIds(
        vaultId: String,
        response: SyncResponse,
    ): List<String> {
        val pendingIds = pendingOpDao.listByVault(vaultId).map { op -> op.cipherId }.toSet()
        val serverIds = response.ciphers.map { it.id }.toSet()
        return cipherDao.listByVault(vaultId)
            .map { row -> row.id }
            .filter { id -> id !in serverIds && id !in pendingIds }
    }

    /**
     * 落库服务端条目。
     *
     * ⚠️ **必须跳过仍在推送队列里的条目**（`.ai/ISSUES.md` #91）：
     * 队列里意味着这条有**尚未成功推送的本地改动**（上一轮 `flushPending` 失败留在队列）。
     * 若无条件 `upsertAll`，服务端版本会**覆盖掉本地改动** ⇒ 用户改了密码/备注，
     * 一次同步后变回旧值，且无任何提示。
     *
     * 与 [pruneRemovedRows] 的 `pendingIds` 是同一保护思路的两面：
     * 那里防「被误删」，这里防「被覆盖」。
     *
     * 代价：队列条目在推送成功前拿到的是本地版本（正确——本地才是用户最新意图）；
     * 推送成功后队列清空，下一次同步即取服务端版本收敛。
     */
    private suspend fun persistCiphers(vaultId: String, ciphers: List<CipherDto>) {
        if (ciphers.isEmpty()) return
        val pendingIds = pendingOpDao.listByVault(vaultId).map { op -> op.cipherId }.toSet()
        val incoming = ciphers
            .filter { dto -> dto.id !in pendingIds }
            .map { dto -> dto.toEntity(vaultId) }
        if (incoming.isEmpty()) return
        cipherDao.upsertAll(incoming)
        // 「跳过几条」比「落库几条」更能说明问题：跳过 > 0 意味着本地有未推送改动
        // 被 #91 的保护挡住了覆盖 —— 这正是用户改了东西又"变回去"的反面证据。
        VaultixLog.d(TAG) {
            "persist cipher: 落库 ${incoming.size} 条，" +
                "跳过 ${ciphers.size - incoming.size} 条（本地待推送，受 #91 保护）"
        }
    }

    private suspend fun persistFolders(vaultId: String, folders: List<FolderDto>) {
        if (folders.isEmpty()) return
        folderDao.upsertAll(folders.map { dto -> dto.toEntity(vaultId) })
    }

    /**
     * 全量拉取成功后，收敛服务端已移除（永久删除 / 回收站 30 天到期清除等）的本地行：
     * - cipher：排除仍带 pending ops 的 id——离线新建/编辑尚未推送成功，服务端
     *   全量列表里没有它们是正常状态，删除会丢本地数据；
     * - folder：Vaultix 尚无本地文件夹待推送操作，直接按服务端集合收敛
     *   （Bastion deleteNotIn 同款语义）。
     */
    private suspend fun pruneRemovedRows(vaultId: String, response: SyncResponse) {
        val removedCiphers = computeRemovableCipherIds(vaultId, response)
        if (removedCiphers.isNotEmpty()) {
            // ★ 删除本地行是**不可逆**动作，必须留痕（在此之前这里一条日志都没有）。
            //   排查「条目凭空消失」（#92）时，第一个要回答的问题就是
            //   **「到底删了几条」** —— 只记数量，不记 id。
            VaultixLog.w(TAG) {
                "prune cipher: 删除本地 ${removedCiphers.size} 条（服务端 ${response.ciphers.size} 条）"
            }
            cipherDao.deleteByIds(removedCiphers)
        }

        val serverFolderIds = response.folders.map { it.id }.toSet()
        val removedFolders = folderDao.listByVault(vaultId)
            .map { row -> row.id }
            .filter { id -> id !in serverFolderIds }
        if (removedFolders.isNotEmpty()) {
            VaultixLog.w(TAG) { "prune folder: 删除本地 ${removedFolders.size} 条（服务端 ${serverFolderIds.size} 条）" }
            folderDao.deleteByIds(removedFolders)
        }
    }

    /** 服务端确定性失败（目标不存在/不可执行）；408/429 属可重试不在此列。 */
    private fun Throwable.isDefinitiveHttpFailure(): Boolean = this is HttpException &&
        this.code() in DEFINITIVE_HTTP_CODES

    private fun CipherDto.toEntity(vaultId: String) = CipherEntity(
        id = id,
        vaultId = vaultId,
        type = type,
        encryptedPayload = json.encodeToString(this),
        revisionDate = revisionDate,
        deletedDate = deletedDate,
        folderId = folderId,
        favorite = favorite,
    )

    private fun FolderDto.toEntity(vaultId: String) = FolderEntity(
        id = id,
        vaultId = vaultId,
        encryptedName = name,
        revisionDate = revisionDate,
    )

    /**
     * 把异常映射成可执行的同步结果，避免上层只能笼统提示"同步失败"。
     *
     * 401 需结合最近一次刷新结果归类（对齐 Bastion：只有 400/401 拒绝 refresh
     * 才认定凭据失效；403/429/5xx/网络瞬断是 Transient，绝不误报「登录失效」）：
     * - 刷新为 [BitwardenAuthRepository.RefreshFailure.Invalid] 或未知 → 登录已失效；
     * - 刷新为 [BitwardenAuthRepository.RefreshFailure.Transient] → 可稍后重试。
     */
    private fun classifyError(error: Throwable, server: String): SyncOutcome {
        if (error is HttpException && error.code() == HTTP_UNAUTHORIZED) {
            val failure = authRepository.refreshFailureOf(server)
            return if (failure == BitwardenAuthRepository.RefreshFailure.Transient) {
                SyncOutcome.RetryableError("登录状态校验暂时失败，请稍后重试")
            } else {
                SyncOutcome.FatalError("登录已失效，请重新登录")
            }
        }
        return when (error) {
            is HttpException -> SyncOutcome.RetryableError("服务端返回 ${error.code()}")
            is IOException -> SyncOutcome.RetryableError(error.message ?: "网络不可用")
            else -> SyncOutcome.FatalError(error.message ?: "未知错误")
        }
    }

    private companion object {
        const val OP_CREATE = "CREATE"
        const val OP_UPDATE = "UPDATE"
        const val OP_SOFT_DELETE = "SOFT_DELETE"
        const val OP_DELETE = "DELETE"
        const val OP_RESTORE = "RESTORE"
        const val HTTP_UNAUTHORIZED = 401

        // 4xx 中 401（登录失效，须重新登录后重推）/408/429（可重试）除外，
        // 其余视为确定性失败（@suppress MagicNumber：HTTP 状态码区间）
        @Suppress("MagicNumber")
        private val DEFINITIVE_HTTP_CODES = (400..499).filter { it !in setOf(401, 408, 429) }
    }
}
