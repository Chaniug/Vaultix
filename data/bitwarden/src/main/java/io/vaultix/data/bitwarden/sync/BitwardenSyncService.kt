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

import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.CipherResponse
import io.vaultix.data.bitwarden.model.FolderDto
import io.vaultix.data.bitwarden.model.SyncResponse
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.FolderEntity
import io.vaultix.database.entity.PendingOpEntity
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden 同步编排（M1）。
 *
 * 流程：推送本地待上传 → 预检是否需要全量 → 拉取 → 安全校验 → 落库。
 *
 * 与 Bastion 的差异（刻意简化）：暂不实现节流、优先级队列与被动自动同步——
 * 这些服务的是高频自动同步场景，M1 以手动触发为主，过度设计反而增加维护成本。
 */
@Singleton
class BitwardenSyncService @Inject constructor(
    private val apiFactory: BitwardenApiFactory,
    private val vaultDao: VaultDao,
    private val cipherDao: CipherDao,
    private val folderDao: FolderDao,
    private val pendingOpDao: PendingOpDao,
    private val json: Json,
) {

    suspend fun sync(vaultId: String, server: String, force: Boolean = false): SyncOutcome =
        runCatching { executeSync(vaultId, server, force) }
            .getOrElse { error -> classifyError(error) }

    private suspend fun executeSync(vaultId: String, server: String, force: Boolean): SyncOutcome {
        // 1) 先推送本地改动：新建/修改不应被整库下载挡住（Bastion 事故结论之一）
        flushPending(vaultId, server)

        val localRevision = vaultDao.get(vaultId)?.revisionDate
        val remoteRevision = fetchRevision(server)

        // 2) 预检：Vaultwarden 会忽略 sinceRevisionDate 增量游标，必须显式比对
        if (!force && localRevision != null && remoteRevision != null &&
            remoteRevision.toString() == localRevision
        ) {
            return SyncOutcome.Skipped
        }

        val response = apiFactory.vault(server).sync()

        // 3) 安全校验：防止服务端故障返回空数据而清空本地
        val protection = checkProtection(vaultId, localRevision, response)
        if (protection != null) return protection

        // 4) 落库
        persistCiphers(vaultId, response.ciphers)
        persistFolders(vaultId, response.folders)
        vaultDao.updateRevision(vaultId, remoteRevision?.toString() ?: localRevision)

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
                .onFailure {
                    pendingOpDao.incrementRetry(op.localId)
                    firstError = firstError ?: it
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
                "服务端条目数（）远少于本地（），" +
                    "为保护数据已暂停同步，请确认后重试。",
            )
        }
        return null
    }

    private suspend fun persistCiphers(vaultId: String, ciphers: List<CipherDto>) {
        if (ciphers.isEmpty()) return
        cipherDao.upsertAll(ciphers.map { dto -> dto.toEntity(vaultId) })
    }

    private suspend fun persistFolders(vaultId: String, folders: List<FolderDto>) {
        if (folders.isEmpty()) return
        folderDao.upsertAll(folders.map { dto -> dto.toEntity(vaultId) })
    }

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

    /** 把异常映射成可执行的同步结果，避免上层只能笼统提示"同步失败"。 */
    private fun classifyError(error: Throwable): SyncOutcome = when (error) {
        is HttpException -> if (error.code() == HTTP_UNAUTHORIZED) {
            SyncOutcome.FatalError("登录已失效，请重新登录")
        } else {
            SyncOutcome.RetryableError("服务端返回 ")
        }
        is IOException -> SyncOutcome.RetryableError(error.message ?: "网络不可用")
        else -> SyncOutcome.FatalError(error.message ?: "未知错误")
    }

    private companion object {
        const val OP_CREATE = "CREATE"
        const val OP_UPDATE = "UPDATE"
        const val OP_SOFT_DELETE = "SOFT_DELETE"
        const val OP_DELETE = "DELETE"
        const val OP_RESTORE = "RESTORE"
        const val HTTP_UNAUTHORIZED = 401
    }
}
