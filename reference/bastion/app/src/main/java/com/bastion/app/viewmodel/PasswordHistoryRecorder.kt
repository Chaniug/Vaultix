package com.bastion.app.viewmodel

import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.service.BitwardenSyncSnapshotPreviewParser
import com.bastion.app.data.PasswordHistoryEntry
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.security.SecurityManager
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * `PasswordViewModel` 密码历史簇的抽取（B.3 集群 7）。
 *
 * ## 为什么需要这个类
 *
 * 集群 7（主密码 / 历史）是 B.3 剩余簇之一。行为测试网
 * `PasswordMasterAndHistoryBehaviorTest`（8 个用例）锁定语义后，本类承接
 * 历史快照写入 / 历史解码 / 历史展示流 / Bitwarden 同步原始历史流四块职责，
 * `PasswordViewModel` 只保留薄委托。
 *
 * ## 注入策略（与集群 5c `PasswordMoveExecutor` / 集群 6 `PasswordArchiveOrchestrator` 一致）
 *
 * - **实例注入**：`repository` / `securityManager` / `bitwardenRepository`（可空）/
 *   `bitwardenSnapshotPreviewParser`（无依赖，集群 8 可注入项）。
 * - **函数引用注入**：`decryptForDisplay` / `decodePasswordOrNull` —— 二者在 VM 内被
 *   10+ 处复用且涉及 `decryptLock` / `_isAuthenticated` / `hasLoggedDecryptAuthStateWarning`
 *   等实例状态，实现留在 VM，仅把方法引用传入。
 *
 * 注意：Kotlin 禁止对函数类型参数使用命名参数（§7.10 踩坑），调用函数引用一律用位置参数。
 */
class PasswordHistoryRecorder(
    private val repository: PasswordRepository,
    private val securityManager: SecurityManager,
    private val bitwardenRepository: BitwardenRepository?,
    private val bitwardenSnapshotPreviewParser: BitwardenSyncSnapshotPreviewParser,
    private val decryptForDisplay: (String) -> String,
    private val decodePasswordOrNull: (String) -> String?
) {

    companion object {
        private const val PASSWORD_HISTORY_LIMIT = 10
    }

    /**
     * 保存密码历史快照（去重 / trim / 加密）。
     *
     * 语义（自 `PasswordViewModel.savePasswordHistorySnapshot` 逐字节搬迁）：
     * 明文为空直接跳过；与最新一条历史解密后相同则跳过；否则以
     * `encryptDataLegacyCompat` 加密落库，并 trim 到上限。
     */
    suspend fun savePasswordHistorySnapshot(entryId: Long, plainPassword: String) {
        if (plainPassword.isBlank()) return

        val latestHistory = repository.getPasswordHistoryByEntryIdSync(entryId).firstOrNull()
        val latestPassword = latestHistory?.let { decryptForDisplay(it.password) }
        if (latestPassword == plainPassword) return

        repository.insertPasswordHistory(
            PasswordHistoryEntry(
                entryId = entryId,
                password = securityManager.encryptDataLegacyCompat(plainPassword),
                lastUsedAt = Date()
            )
        )
        repository.trimPasswordHistory(entryId, PASSWORD_HISTORY_LIMIT)
    }

    /**
     * 解码单条历史密码：解密后若与稳定编码（`encryptDataLegacyCompat`）不一致，
     * 回写规范编码。返回明文，解码失败返回空串。
     */
    suspend fun decodeHistoryPasswordForDisplay(entry: PasswordHistoryEntry): String {
        val decoded = decryptForDisplay(entry.password)
        if (decoded.isBlank()) return ""

        val stableEncoded = securityManager.encryptDataLegacyCompat(decoded)
        if (stableEncoded != entry.password) {
            repository.updatePasswordHistoryPassword(entry.id, stableEncoded)
        }
        return decoded
    }

    /**
     * 历史展示流：解码密码，解码失败的条目从列表中剔除。
     */
    fun getPasswordHistoryFlow(passwordId: Long): Flow<List<PasswordHistoryEntry>> {
        return repository.getPasswordHistoryByEntryId(passwordId)
            .map { entries ->
                entries.mapNotNull { entry ->
                    val decoded = decodeHistoryPasswordForDisplay(entry)
                    if (decoded.isBlank()) {
                        null
                    } else {
                        entry.copy(password = decoded)
                    }
                }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Bitwarden 同步原始历史流：只保留 `SYNC_RESPONSE` 载荷，并解码出明文 payload。
     * 空 cipherId 直接短路为空流。
     */
    fun getBitwardenSyncRawHistoryFlow(
        vaultId: Long,
        cipherId: String
    ): Flow<List<BitwardenSyncRawHistoryItem>> {
        if (cipherId.isBlank()) return flowOf(emptyList())
        return repository.getBitwardenSyncRawRecords(vaultId, cipherId)
            .map { entries ->
                entries.map { entry ->
                    val payload = decodePasswordOrNull(entry.payloadCipherText)
                    BitwardenSyncRawHistoryItem(
                        id = entry.id,
                        operation = entry.operation,
                        endpoint = entry.endpoint,
                        payloadSource = entry.payloadSource,
                        payloadDigest = entry.payloadDigest,
                        responseCode = entry.responseCode,
                        success = entry.success,
                        capturedAt = entry.capturedAt,
                        payload = payload,
                        preview = bitwardenSnapshotPreviewParser.parse(
                            payload = payload,
                            symmetricKey = bitwardenRepository?.getCachedSymmetricKey(vaultId)
                        )
                    )
                }.filter { it.payloadSource == "SYNC_RESPONSE" }
            }
            .flowOn(Dispatchers.IO)
    }
}
