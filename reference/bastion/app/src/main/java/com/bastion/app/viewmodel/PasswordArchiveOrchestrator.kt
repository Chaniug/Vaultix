package com.bastion.app.viewmodel

import com.bastion.app.data.PasswordArchiveSyncMeta
import com.bastion.app.data.PasswordEntry
import com.bastion.app.domain.provider.PasswordCommandPolicy
import com.bastion.app.domain.provider.PasswordCommandStateFactory
import com.bastion.app.repository.PasswordRepository
import java.util.Date

/**
 * 密码条目「归档 / 取消归档」编排器（Phase B.3 集群 6）。
 *
 * 从 `PasswordViewModel` 逐行搬迁而来，**未改动任何业务逻辑**：
 * 函数体与原实现保持一致，只把原先直接访问的 ViewModel 成员改成构造期注入的协作者/lambda。
 *
 * ## 为什么用 lambda 注入而不是直接传实例
 *
 * `ensureArchiveGroupPath` / `resolveRestorePathOrRoot` / `moveEntryGroupPath` 三者在
 * ViewModel 里依赖 `keepassBridge`，而 `keepassBridge` 又依赖 `context` + `localKeePassDatabaseDao`
 * + `securityManager` 的组合构造，且其内部还要复用 ViewModel 的
 * `resolvePlainPasswordForKeePass` / `resolveKeePassCustomFieldsForSync`（都带解密副作用）。
 * 把这些整体搬过来会牵动 KeePass 与 TOTP 投影链路 —— 那是集群 3 的范围，且用户明令
 * 不得回归。因此这里只注入**函数引用**，KeePass 侧实现继续留在 ViewModel。
 *
 * 该模式与集群 2 的 `BitwardenOfflineSecretCacheFacade`（不持有解密逻辑，只做纯委托）一致，
 * 已经过真机验证。
 *
 * ## 回归网
 *
 * `PasswordArchiveBehaviorTest`（9 个 mockk 行为测试）覆盖本类的归档/取消归档语义，
 * 其中包括最易在重构中写坏的一条：**取消归档必须以归档元数据中记录的来源为准，
 * 而不是以当前条目的分组字段重新推断**（条目在归档期间可能被同步逻辑改写过）。
 */
internal class PasswordArchiveOrchestrator(
    private val repository: PasswordRepository,
    private val stateFactory: PasswordCommandStateFactory,
    private val commandPolicyOf: (PasswordEntry) -> PasswordCommandPolicy,
    private val ensureArchiveGroupPath: suspend (Long?) -> String?,
    private val resolveRestorePathOrRoot: suspend (Long?, String?) -> String?,
    private val moveEntryGroupPath: suspend (PasswordEntry, String?) -> Result<Unit>
) {

    internal suspend fun archivePasswordsInternal(ids: List<Long>) {
        if (ids.isEmpty()) return
        val entries = repository.getPasswordsByIds(ids)
            .filter { !it.isDeleted }
        entries.forEach { entry ->
            archiveSingleEntry(entry)
        }
    }

    internal suspend fun unarchivePasswordsInternal(ids: List<Long>) {
        if (ids.isEmpty()) return
        val entries = repository.getPasswordsByIds(ids)
            .filter { !it.isDeleted }
        entries.forEach { entry ->
            unarchiveSingleEntry(entry)
        }
    }

    private suspend fun archiveSingleEntry(entry: PasswordEntry) {
        if (entry.isArchived || entry.isDeleted) return

        val now = Date()
        val commandPolicy = commandPolicyOf(entry)
        val providerType = commandPolicy.archiveProviderType
        val keepassDatabaseId = entry.keepassDatabaseId

        var archivedEntry = stateFactory.createArchivedEntry(
            entry = entry,
            now = now,
            commandPolicy = commandPolicy
        )
        repository.updatePasswordEntry(archivedEntry)

        val archiveResult = archiveEntryByProvider(
            entry = archivedEntry,
            keepassDatabaseId = keepassDatabaseId,
            providerType = providerType
        )
        archivedEntry = archiveResult.entry

        repository.upsertArchiveSyncMeta(buildArchiveSyncMeta(
            entry = entry,
            providerType = providerType,
            keepassDatabaseId = keepassDatabaseId,
            syncStatus = archiveResult.syncStatus,
            lastError = archiveResult.lastError
        ))
    }

    private suspend fun unarchiveSingleEntry(entry: PasswordEntry) {
        if (!entry.isArchived || entry.isDeleted) return

        val now = Date()
        val archiveMeta = repository.getArchiveSyncMeta(entry.id)
        val commandPolicy = commandPolicyOf(entry)
        val providerType = archiveMeta?.providerType ?: commandPolicy.archiveProviderType
        val keepassDatabaseId = entry.keepassDatabaseId

        var unarchivedEntry = stateFactory.createUnarchivedEntry(
            entry = entry,
            now = now,
            commandPolicy = commandPolicy
        )
        repository.updatePasswordEntry(unarchivedEntry)

        val unarchiveResult = unarchiveEntryByProvider(
            entry = unarchivedEntry,
            archiveMeta = archiveMeta,
            keepassDatabaseId = keepassDatabaseId,
            providerType = providerType
        )
        unarchivedEntry = unarchiveResult.entry

        repository.upsertArchiveSyncMeta(buildUnarchiveSyncMeta(
            entry = entry,
            archiveMeta = archiveMeta,
            providerType = providerType,
            keepassDatabaseId = keepassDatabaseId,
            syncStatus = unarchiveResult.syncStatus,
            lastError = unarchiveResult.lastError
        ))
    }

    private data class ArchiveOperationResult(
        val entry: PasswordEntry,
        val syncStatus: String,
        val lastError: String?
    )

    private suspend fun archiveEntryByProvider(
        entry: PasswordEntry,
        keepassDatabaseId: Long?,
        providerType: String
    ): ArchiveOperationResult {
        if (providerType != PasswordArchiveSyncMeta.PROVIDER_KEEPASS_GROUP) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = defaultArchiveSyncStatus(providerType),
                lastError = null
            )
        }

        val targetArchivePath = ensureArchiveGroupPath(keepassDatabaseId)
        if (targetArchivePath == null) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = PasswordArchiveSyncMeta.STATUS_FAILED,
                lastError = "KeePass archive group unavailable"
            )
        }
        val moveResult = moveEntryGroupPath(entry, targetArchivePath)
        if (moveResult.isFailure) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = PasswordArchiveSyncMeta.STATUS_FAILED,
                lastError = moveResult.exceptionOrNull()?.message ?: "KeePass archive move failed"
            )
        }

        val archivedEntry = entry.copy(keepassGroupPath = targetArchivePath, updatedAt = Date())
        repository.updatePasswordEntry(archivedEntry)
        return ArchiveOperationResult(
            entry = archivedEntry,
            syncStatus = PasswordArchiveSyncMeta.STATUS_SYNCED,
            lastError = null
        )
    }

    private suspend fun unarchiveEntryByProvider(
        entry: PasswordEntry,
        archiveMeta: PasswordArchiveSyncMeta?,
        keepassDatabaseId: Long?,
        providerType: String
    ): ArchiveOperationResult {
        if (providerType != PasswordArchiveSyncMeta.PROVIDER_KEEPASS_GROUP) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = defaultArchiveSyncStatus(providerType),
                lastError = null
            )
        }

        val preferredPath = archiveMeta?.originKeePassGroupPath
        val restorePath = resolveRestorePathOrRoot(keepassDatabaseId, preferredPath)
        val moveResult = moveEntryGroupPath(entry, restorePath)
        if (moveResult.isFailure) {
            return ArchiveOperationResult(
                entry = entry,
                syncStatus = PasswordArchiveSyncMeta.STATUS_FAILED,
                lastError = moveResult.exceptionOrNull()?.message ?: "KeePass unarchive move failed"
            )
        }

        val restoredEntry = entry.copy(keepassGroupPath = restorePath, updatedAt = Date())
        repository.updatePasswordEntry(restoredEntry)
        val lastError = if (preferredPath != null && preferredPath != restorePath) {
            "Origin group missing, restored to root"
        } else {
            null
        }
        return ArchiveOperationResult(
            entry = restoredEntry,
            syncStatus = PasswordArchiveSyncMeta.STATUS_SYNCED,
            lastError = lastError
        )
    }

    private fun defaultArchiveSyncStatus(providerType: String): String {
        return if (providerType == PasswordArchiveSyncMeta.PROVIDER_LOCAL) {
            PasswordArchiveSyncMeta.STATUS_SYNCED
        } else {
            PasswordArchiveSyncMeta.STATUS_PENDING
        }
    }

    private fun buildArchiveSyncMeta(
        entry: PasswordEntry,
        providerType: String,
        keepassDatabaseId: Long?,
        syncStatus: String,
        lastError: String?
    ): PasswordArchiveSyncMeta {
        return PasswordArchiveSyncMeta(
            entryId = entry.id,
            providerType = providerType,
            originKeePassDatabaseId = keepassDatabaseId,
            originKeePassGroupPath = entry.keepassGroupPath,
            originBitwardenFolderId = entry.bitwardenFolderId,
            syncStatus = syncStatus,
            lastError = lastError,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun buildUnarchiveSyncMeta(
        entry: PasswordEntry,
        archiveMeta: PasswordArchiveSyncMeta?,
        providerType: String,
        keepassDatabaseId: Long?,
        syncStatus: String,
        lastError: String?
    ): PasswordArchiveSyncMeta {
        return PasswordArchiveSyncMeta(
            entryId = entry.id,
            providerType = providerType,
            originKeePassDatabaseId = archiveMeta?.originKeePassDatabaseId ?: keepassDatabaseId,
            originKeePassGroupPath = archiveMeta?.originKeePassGroupPath,
            originBitwardenFolderId = archiveMeta?.originBitwardenFolderId ?: entry.bitwardenFolderId,
            syncStatus = syncStatus,
            lastError = lastError,
            updatedAt = System.currentTimeMillis()
        )
    }
}
