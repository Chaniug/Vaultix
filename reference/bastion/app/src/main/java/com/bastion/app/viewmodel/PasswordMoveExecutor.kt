package com.bastion.app.viewmodel

import android.content.Context
import android.util.Log
import com.bastion.app.attachments.AttachmentContainer
import com.bastion.app.attachments.model.AttachmentSource
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.data.PasswordEntry
import com.bastion.app.keepass.KeePassCrossDatabaseTransfer
import com.bastion.app.keepass.KeePassPasswordDeleteExecutor
import com.bastion.app.keepass.KeePassPasswordUpdateExecutor
import com.bastion.app.logging.runCatchingObserved
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.utils.KeePassCustomFieldData

/**
 * 密码条目「跨存储迁移」（move*）编排器（Phase B.3 集群 5c）。
 *
 * 从 `PasswordViewModel` 逐行搬迁而来，**未改动任何业务逻辑**：
 * 函数体与原实现保持一致，只把原先直接访问的 ViewModel 成员改成构造期注入。
 *
 * ## 注入策略（与集群 6 `PasswordArchiveOrchestrator` 一致）
 *
 * - **实例注入**：`repository` / `keepassPasswordUpdateExecutor` /
 *   `keepassPasswordDeleteExecutor` / `bitwardenRepository` / `appContext` ——
 *   executor 是 VM 构造时已 new 好的实例，直接传入。
 * - **函数引用注入**（仍留在 VM）：
 *   - `resolveKeePassCustomFieldsForSync`：被 VM 其他 4 处逻辑复用（非 move* 专属）；
 *   - `decodePasswordOrNull`：被 VM 8 处复用，且带解密副作用；
 *   - `canWriteKeePassDatabase`：依赖 VM 构造参数 `localKeePassDatabaseDao`。
 *
 * ## 行为测试网
 *
 * `PasswordMoveBehaviorTest`（12 个 mockk 用例）覆盖本类的全部入口与分支语义
 * （本地迁移清绑定 / KeePass 目标绑定 / 迁出清空 / Bitwarden 仓库不可用必须失败 /
 * 本地行绝不 `deletePasswordEntry` 等），抽取后依然应当全绿。
 */
internal class PasswordMoveExecutor(
    private val repository: PasswordRepository,
    private val keepassPasswordUpdateExecutor: KeePassPasswordUpdateExecutor,
    private val keepassPasswordDeleteExecutor: KeePassPasswordDeleteExecutor,
    private val bitwardenRepository: BitwardenRepository?,
    private val appContext: Context?,
    private val resolveKeePassCustomFieldsForSync: suspend (
        entryId: Long,
        customFieldsOverride: List<com.bastion.app.data.CustomFieldDraft>?
    ) -> List<KeePassCustomFieldData>,
    private val decodePasswordOrNull: (String) -> String?,
    private val canWriteKeePassDatabase: suspend (Long) -> Boolean
) {

    suspend fun movePasswordsToCategoryAwait(ids: List<Long>, categoryId: Long?) {
        if (ids.isEmpty()) return
        val entries = repository.getPasswordsByIds(ids)
        repository.updateCategoryForPasswords(ids, categoryId)
        // Moving a password to a Bastion category must stay local-only.
        // Category linkage may be used by other sync workflows, but it must not
        // silently convert password ownership during a local move action.
        repository.updateKeePassDatabaseForPasswords(ids, null)
        deleteMovedKeePassPasswordSources(entries, "category")
    }

    suspend fun moveKeePassPasswordsToBastionCategoryAwait(
        ids: List<Long>,
        categoryId: Long?
    ): Result<Int> {
        if (ids.isEmpty()) return Result.success(0)
        return runCatchingObserved {
            val entries = repository.getPasswordsByIds(ids)
            val keepassEntries = entries.filter { it.keepassDatabaseId != null }
            if (keepassEntries.isEmpty()) return@runCatchingObserved 0

            repository.updateCategoryForPasswords(keepassEntries.map { it.id }, categoryId)
            repository.updateKeePassDatabaseForPasswords(keepassEntries.map { it.id }, null)

            val sourceDeleted = deleteMovedKeePassPasswordSources(keepassEntries, "bastion_local")
            if (!sourceDeleted) {
                throw IllegalStateException("KeePass source cleanup failed after moving password to Bastion local")
            }
            keepassEntries.size
        }
    }

    suspend fun movePasswordsToKeePassDatabaseAwait(ids: List<Long>, databaseId: Long?) {
        if (ids.isEmpty()) return
        if (databaseId != null && !canWriteKeePassDatabase(databaseId)) {
            Log.w("PasswordViewModel", "movePasswordsToKeePassDatabase blocked because KeePass target is unavailable")
            return
        }
        movePasswordsToKeePassInternal(
            ids = ids,
            buildUpdatedEntry = { entry ->
                if (databaseId == null) {
                    entry.copy(
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        keepassEntryUuid = null,
                        keepassGroupUuid = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null,
                        bitwardenCipherId = null,
                        bitwardenRevisionDate = null,
                        bitwardenLocalModified = false,
                        updatedAt = java.util.Date()
                    )
                } else {
                    KeePassCrossDatabaseTransfer.bindPasswordToTarget(
                        entry = entry,
                        databaseId = databaseId,
                        groupPath = null
                    ).copy(updatedAt = java.util.Date())
                }
            }
        )
    }

    suspend fun movePasswordsToKeePassGroupAwait(ids: List<Long>, databaseId: Long, groupPath: String) {
        if (ids.isEmpty()) return
        if (!canWriteKeePassDatabase(databaseId)) {
            Log.w("PasswordViewModel", "movePasswordsToKeePassGroup blocked because KeePass target is unavailable")
            return
        }
        movePasswordsToKeePassInternal(
            ids = ids,
            buildUpdatedEntry = { entry ->
                KeePassCrossDatabaseTransfer.bindPasswordToTarget(
                    entry = entry,
                    databaseId = databaseId,
                    groupPath = groupPath
                ).copy(updatedAt = java.util.Date())
            }
        )
    }

    private suspend fun movePasswordsToKeePassInternal(
        ids: List<Long>,
        buildUpdatedEntry: (PasswordEntry) -> PasswordEntry
    ) {
        val entries = repository.getPasswordsByIds(ids)
        entries.forEach { entry ->
            val updatedEntry = buildUpdatedEntry(entry)
            val customFields = resolveKeePassCustomFieldsForSync(
                entry.id,
                null
            )
            val keepassSync = keepassPasswordUpdateExecutor.syncUpdatedEntry(
                existingEntry = entry,
                updatedEntry = updatedEntry,
                resolvePassword = { candidate ->
                    decodePasswordOrNull(candidate.password) ?: candidate.password
                },
                customFields = customFields,
                persistUpdate = { persistedEntry ->
                    repository.updatePasswordEntry(persistedEntry)
                }
            )
            if (keepassSync.isFailure) {
                Log.e(
                    "PasswordViewModel",
                    "KeePass password move failed before local update: ${keepassSync.exceptionOrNull()?.message}"
                )
                return@forEach
            }

            if (entry.hasBitwardenCipherBinding()) {
                val vaultId = entry.bitwardenVaultId
                val cipherId = entry.bitwardenCipherId
                if (vaultId == null || cipherId.isNullOrBlank()) return@forEach

                val queueResult = bitwardenRepository?.queueCipherDelete(
                    vaultId = vaultId,
                    cipherId = cipherId,
                    entryId = entry.id
                ) ?: Result.failure(IllegalStateException("Bitwarden 仓库不可用"))
                if (queueResult.isFailure) {
                    throw queueResult.exceptionOrNull()
                        ?: IllegalStateException("排队删除 Bitwarden 条目失败")
                }
            }
        }
    }

    suspend fun movePasswordsToBitwardenFolderAwait(ids: List<Long>, vaultId: Long, folderId: String) {
        if (ids.isEmpty()) return
        val entries = repository.getPasswordsByIds(ids)
        // Clear KeePass binding first so the same entry can switch storage target.
        repository.updateKeePassDatabaseForPasswords(ids, null)
        repository.bindPasswordsToBitwardenFolder(ids, vaultId, folderId)
        deleteMovedKeePassPasswordSources(entries, "bitwarden")
    }

    private suspend fun deleteMovedKeePassPasswordSources(
        entries: List<PasswordEntry>,
        target: String
    ): Boolean {
        val keepassEntries = entries.filter { it.keepassDatabaseId != null }
        if (keepassEntries.isEmpty()) return true
        val attachmentsReady = runCatchingObserved {
            materializeMovedKeePassAttachments(keepassEntries)
        }.onFailure { error ->
            Log.e(
                "PasswordViewModel",
                "KeePass source delete blocked after password move to $target because attachments are not local-safe: ${error.message}"
            )
        }.isSuccess
        if (!attachmentsReady) return false

        val deleted = keepassPasswordDeleteExecutor.deleteBatch(
            entries = keepassEntries,
            useRecycleBin = false
        )
        if (!deleted) {
            Log.e(
                "PasswordViewModel",
                "KeePass source delete failed after password move to $target; target data was kept"
            )
        }
        return deleted
    }

    private suspend fun materializeMovedKeePassAttachments(entries: List<PasswordEntry>) {
        if (entries.isEmpty()) return
        val context = appContext ?: return
        val facade = AttachmentContainer.facade(context)
        val attachmentRepository = AttachmentContainer.repository(context)
        entries.forEach { entry ->
            val databaseId = entry.keepassDatabaseId ?: return@forEach
            val entryUuid = entry.keepassEntryUuid
            if (entryUuid.isNullOrBlank()) {
                val hasKeePassAttachments = attachmentRepository
                    .listByParentAndSource(entry.id, AttachmentSource.KEEPASS)
                    .isNotEmpty()
                if (hasKeePassAttachments) {
                    throw IllegalStateException("KeePass attachment transfer requires entry uuid")
                }
                return@forEach
            }
            facade.materializeKeePassAttachmentsForLocal(
                passwordId = entry.id,
                databaseId = databaseId,
                entryUuid = entryUuid
            )
        }
    }
}
