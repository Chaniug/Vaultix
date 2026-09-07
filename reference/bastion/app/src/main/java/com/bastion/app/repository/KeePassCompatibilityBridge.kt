package com.bastion.app.repository

import com.bastion.app.data.ItemType
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.SecureItem
import com.bastion.app.utils.KeePassCustomFieldData
import com.bastion.app.utils.KeePassRestoreTarget

class KeePassCompatibilityBridge(
    private val workspaceRepository: KeePassWorkspaceRepository
) {

    suspend fun upsertLegacyPasswordEntries(
        databaseId: Long,
        entries: List<PasswordEntry>,
        resolvePassword: (PasswordEntry) -> String,
        forceSyncWrite: Boolean = false,
        customFieldsByEntryId: Map<Long, List<KeePassCustomFieldData>> = emptyMap()
    ) = workspaceRepository.addOrUpdatePasswordEntries(
        databaseId = databaseId,
        entries = entries,
        resolvePassword = resolvePassword,
        forceSyncWrite = forceSyncWrite,
        customFieldsByEntryId = customFieldsByEntryId
    )

    suspend fun upsertLegacySecureItems(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.addOrUpdateSecureItems(
        databaseId = databaseId,
        items = items,
        forceSyncWrite = forceSyncWrite
    )

    suspend fun loadLegacyWorkspace(
        databaseId: Long,
        allowedSecureItemTypes: Set<ItemType>? = null
    ) = workspaceRepository.loadWorkspace(
        databaseId = databaseId,
        allowedSecureItemTypes = allowedSecureItemTypes
    )

    suspend fun readLegacyPasswordEntries(databaseId: Long) = workspaceRepository.readPasswordEntries(databaseId)

    suspend fun readLegacySecureItems(
        databaseId: Long,
        allowedTypes: Set<ItemType>? = null
    ) = workspaceRepository.readSecureItems(databaseId, allowedTypes)

    suspend fun readLegacyPasskeys(databaseId: Long) =
        workspaceRepository.readPasskeyEntries(databaseId)

    suspend fun listLegacyGroups(
        databaseId: Long,
        includeRecycleBin: Boolean = false
    ) = workspaceRepository.listGroups(databaseId, includeRecycleBin)

    suspend fun createLegacyGroup(
        databaseId: Long,
        groupName: String,
        parentPath: String? = null
    ) = workspaceRepository.createGroup(databaseId, groupName, parentPath)

    suspend fun renameLegacyGroup(
        databaseId: Long,
        groupPath: String,
        newName: String
    ) = workspaceRepository.renameGroup(databaseId, groupPath, newName)

    suspend fun deleteLegacyGroup(
        databaseId: Long,
        groupPath: String
    ) = workspaceRepository.deleteGroup(databaseId, groupPath)

    suspend fun moveLegacyGroup(
        sourceDatabaseId: Long,
        groupPath: String,
        targetDatabaseId: Long,
        targetParentPath: String? = null
    ) = workspaceRepository.moveGroup(
        sourceDatabaseId = sourceDatabaseId,
        groupPath = groupPath,
        targetDatabaseId = targetDatabaseId,
        targetParentPath = targetParentPath
    )

    suspend fun updateLegacyPasswordEntry(
        databaseId: Long,
        entry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ) = workspaceRepository.updatePasswordEntry(databaseId, entry, resolvePassword, customFields)

    suspend fun upsertLegacyPasskeys(
        databaseId: Long,
        passkeys: List<PasskeyEntry>
    ) = workspaceRepository.addOrUpdatePasskeys(databaseId, passkeys)

    suspend fun deleteKeePassPasswordEntries(
        databaseId: Long,
        entries: List<PasswordEntry>
    ) = workspaceRepository.deletePasswordEntries(databaseId, entries)

    suspend fun updateLegacyPasskey(
        databaseId: Long,
        passkey: PasskeyEntry
    ) = workspaceRepository.updatePasskey(databaseId, passkey)

    suspend fun deleteLegacyPasskeys(
        databaseId: Long,
        passkeys: List<PasskeyEntry>
    ) = workspaceRepository.deletePasskeys(databaseId, passkeys)

    suspend fun moveLegacyPasswordEntriesToRecycleBin(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.movePasswordEntriesToRecycleBin(databaseId, entries, forceSyncWrite)

    suspend fun restoreKeePassPasswordEntriesFromRecycleBin(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.restorePasswordEntriesFromRecycleBin(databaseId, entries, forceSyncWrite)

    suspend fun resolveLegacyRestoreGroupPathForPassword(
        databaseId: Long,
        entry: PasswordEntry
    ) = workspaceRepository.resolveRestoreGroupPathForPassword(databaseId, entry)

    suspend fun resolveLegacyRestoreTargetForPassword(
        databaseId: Long,
        entry: PasswordEntry
    ): Result<KeePassRestoreTarget> = workspaceRepository.resolveRestoreTargetForPassword(databaseId, entry)

    suspend fun syncLegacyRemoteDatabase(databaseId: Long) =
        workspaceRepository.syncRemoteDatabase(databaseId)

    suspend fun updateLegacySecureItem(
        databaseId: Long,
        item: SecureItem
    ) = workspaceRepository.updateSecureItem(databaseId, item)

    suspend fun deleteKeePassSecureItems(
        databaseId: Long,
        items: List<SecureItem>
    ) = workspaceRepository.deleteSecureItems(databaseId, items)

    suspend fun moveLegacySecureItemsToRecycleBin(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.moveSecureItemsToRecycleBin(databaseId, items, forceSyncWrite)

    suspend fun restoreKeePassSecureItemsFromRecycleBin(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ) = workspaceRepository.restoreSecureItemsFromRecycleBin(databaseId, items, forceSyncWrite)

    suspend fun resolveLegacyRestoreGroupPathForSecureItem(
        databaseId: Long,
        item: SecureItem
    ) = workspaceRepository.resolveRestoreGroupPathForSecureItem(databaseId, item)

    suspend fun resolveLegacyRestoreTargetForSecureItem(
        databaseId: Long,
        item: SecureItem
    ): Result<KeePassRestoreTarget> = workspaceRepository.resolveRestoreTargetForSecureItem(databaseId, item)
}
