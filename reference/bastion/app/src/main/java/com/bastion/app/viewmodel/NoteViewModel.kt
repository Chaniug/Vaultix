package com.bastion.app.viewmodel

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.bastion.app.keepass.KeePassCrossDatabaseTransfer
import com.bastion.app.keepass.KeePassSecureItemCreateExecutor
import com.bastion.app.keepass.KeePassSecureItemDeleteExecutor
import com.bastion.app.keepass.KeePassSecureItemUpdateExecutor
import com.bastion.app.bitwarden.SecureItemBitwardenTransitionResolver
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.data.ItemType
import com.bastion.app.data.LocalKeePassDatabaseDao
import com.bastion.app.data.SecureItem
import com.bastion.app.data.OperationLogItemType
import com.bastion.app.data.SecureItemOwnership
import com.bastion.app.data.asBastionLocalCopy
import com.bastion.app.data.hasOwnershipConflict
import com.bastion.app.data.resolveOwnership
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.model.StorageTarget
import com.bastion.app.data.model.toStorageTarget
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.repository.KeePassCompatibilityBridge
import com.bastion.app.repository.KeePassWorkspaceRepository
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.repository.SecureItemRepository
import com.bastion.app.security.SecurityManager
import com.bastion.app.sync.SyncDiagnostics
import com.bastion.app.sync.SyncItemKind
import com.bastion.app.sync.SyncMode
import com.bastion.app.sync.SyncPriority
import com.bastion.app.sync.SyncRequest
import com.bastion.app.sync.SyncTarget
import com.bastion.app.sync.SyncTaskRunner
import com.bastion.app.sync.SyncTrigger
import com.bastion.app.util.ImageManager
import com.bastion.app.utils.OperationLogger
import com.bastion.app.utils.FieldChange
import com.bastion.app.utils.KeePassKdbxService
import java.util.Locale
import java.util.Date
import java.util.UUID

data class NoteDraftStorageTarget(
    val categoryId: Long? = null,
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null
)

class NoteViewModel(
    private val repository: SecureItemRepository,
    private val passwordRepository: PasswordRepository? = null,
    context: Context? = null,
    private val localKeePassDatabaseDao: LocalKeePassDatabaseDao? = null,
    private val securityManager: SecurityManager? = null
) : ViewModel() {

    companion object {
        private const val TAG = "NoteViewModel"
    }

    private val keepassBridge = if (context != null && localKeePassDatabaseDao != null && securityManager != null) {
        KeePassCompatibilityBridge(
            KeePassWorkspaceRepository(
                context = context.applicationContext,
                dao = localKeePassDatabaseDao,
                securityManager = securityManager
            )
        )
    } else {
        null
    }
    private val keepassSecureItemCreateExecutor = KeePassSecureItemCreateExecutor(keepassBridge)
    private val keepassSecureItemDeleteExecutor = KeePassSecureItemDeleteExecutor(keepassBridge)
    private val keepassSecureItemUpdateExecutor = KeePassSecureItemUpdateExecutor(keepassBridge)
    private val imageManager = context?.let { ImageManager(it.applicationContext) }

    private val bitwardenRepository = context?.let { BitwardenRepository.getInstance(it.applicationContext) }

    private fun requestBitwardenMutationSync(vaultId: Long?) {
        vaultId?.let { bitwardenRepository?.requestLocalMutationSync(it) }
    }

    private fun decryptStoredSensitiveValue(value: String): String {
        return securityManager
            ?.let { manager -> runCatchingObserved { manager.decryptDataIfBastionCiphertext(value) }.getOrDefault(value) }
            ?: value
    }

    private data class KeePassMutationIdentity(
        val groupPath: String?,
        val entryUuid: String?,
        val groupUuid: String?
    )
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // 笔记列表布局偏好 (true = 网格, false = 列表)
    private val _isGridLayout = MutableStateFlow(true)
    val isGridLayout: StateFlow<Boolean> = _isGridLayout.asStateFlow()

    private val _draftStorageTarget = MutableStateFlow(NoteDraftStorageTarget())
    val draftStorageTarget: StateFlow<NoteDraftStorageTarget> = _draftStorageTarget.asStateFlow()

    init {
        viewModelScope.launch {
            repairLegacyDetachedKeePassItems()
        }
    }
    
    fun setGridLayout(isGrid: Boolean) {
        _isGridLayout.value = isGrid
    }

    fun setDraftStorageTarget(target: NoteDraftStorageTarget) {
        _draftStorageTarget.value = target
    }

    fun syncKeePassNotes(databaseId: Long) {
        val requestId = SyncDiagnostics.nextTaskId("kp-note")
        viewModelScope.launch {
            SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = requestId,
                    target = SyncTarget.KeePassCompatibilityIndex(
                        databaseId = databaseId,
                        itemTypes = setOf(SyncItemKind.NOTE)
                    ),
                    trigger = SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = SyncPriority.PAGE_VISIBLE,
                    mode = SyncMode.SILENT,
                    throttleMs = 30_000L
                )
            ) {
                syncKeePassNotesNow(databaseId, requestId)
            }
        }
    }

    private suspend fun syncKeePassNotesNow(databaseId: Long, taskId: String) {
        val target = "keepass_compat:note:$databaseId"
        val trigger = "NOTE_FILTER_ENTER"
        SyncDiagnostics.queued(taskId, target, trigger)
        val startedAt = SyncDiagnostics.start(taskId, target, trigger)
        try {
            val snapshots = keepassBridge
                ?.readLegacySecureItems(databaseId, setOf(ItemType.NOTE))
                ?.getOrNull()
                ?: run {
                    SyncDiagnostics.skipped(taskId, target, trigger, "bridge_or_read_unavailable", startedAt)
                    return
                }

            val existingNotes = repository.getItemsByType(ItemType.NOTE).first()
            snapshots.forEach { snapshot ->
                val incoming = snapshot.item
                val existingByUuid = incoming.keepassEntryUuid
                    ?.takeIf { it.isNotBlank() }
                    ?.let { repository.getItemByKeePassUuid(databaseId, it) }
                val existingBySource = snapshot.sourceBastionId
                    ?.takeIf { it > 0 }
                    ?.let { sourceId -> repository.getItemById(sourceId) }
                    ?.takeIf { it.itemType == ItemType.NOTE }

                val existing = existingByUuid ?: existingBySource ?: existingNotes.firstOrNull {
                    it.itemType == ItemType.NOTE &&
                        it.keepassDatabaseId == databaseId &&
                        it.keepassGroupPath == incoming.keepassGroupPath &&
                        it.title == incoming.title
                }

                if (existing == null) {
                    repository.insertItem(incoming)
                } else {
                    val isInRecycleBin = snapshot.isInRecycleBin
                    val updated = existing.copy(
                        title = incoming.title,
                        notes = incoming.notes,
                        itemData = incoming.itemData,
                        isFavorite = incoming.isFavorite,
                        imagePaths = incoming.imagePaths,
                        keepassDatabaseId = incoming.keepassDatabaseId,
                        keepassGroupPath = incoming.keepassGroupPath,
                        keepassEntryUuid = incoming.keepassEntryUuid,
                        keepassGroupUuid = incoming.keepassGroupUuid,
                        isDeleted = isInRecycleBin,
                        deletedAt = if (isInRecycleBin) (existing.deletedAt ?: Date()) else null,
                        updatedAt = Date()
                    )
                    if (!existing.matchesKeePassSecureItemImport(updated)) {
                        repository.updateItem(updated)
                    }
                }
            }
            SyncDiagnostics.success(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = startedAt,
                detail = "items=${snapshots.size}"
            )
        } catch (error: Exception) {
            SyncDiagnostics.failed(taskId, target, trigger, startedAt, error)
            throw error
        }
    }

    private fun SecureItem.matchesKeePassSecureItemImport(imported: SecureItem): Boolean {
        return copy(itemData = "", updatedAt = imported.updatedAt) == imported.copy(itemData = "") &&
            decryptStoredSensitiveValue(itemData) == decryptStoredSensitiveValue(imported.itemData)
    }
    
    // 获取所有笔记
    val allNotes: StateFlow<List<SecureItem>> = repository.getItemsByType(ItemType.NOTE)
        .onStart { _isLoading.value = true }
        .onEach { _isLoading.value = false }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // 根据ID获取笔记
    suspend fun getNoteById(id: Long): SecureItem? {
        val item = repository.getItemById(id) ?: return null
        return repository.normalizeLegacyDetachedKeePassItem(item, ::hasKeePassDatabase)
    }

    fun observeNoteById(id: Long): Flow<SecureItem?> {
        return repository.observeItemById(id)
            .map { item ->
                item?.takeIf { it.itemType == ItemType.NOTE }
            }
    }

    private suspend fun repairLegacyDetachedKeePassItems() {
        repository.repairLegacyDetachedKeePassItems(::hasKeePassDatabase)
    }

    private suspend fun hasKeePassDatabase(databaseId: Long): Boolean {
        return localKeePassDatabaseDao?.getDatabaseById(databaseId) != null
    }
    
    /**
     * 快速添加笔记（从底部导航栏快速添加）
     */
    fun quickAddNote(title: String, content: String) {
        if (title.isBlank() && content.isBlank()) return
        val fullContent = if (title.isNotBlank() && content.isNotBlank()) {
            "$title\n\n$content"
        } else if (title.isNotBlank()) {
            title
        } else {
            content
        }
        addNote(
            content = fullContent,
            title = title.takeIf { it.isNotBlank() }
        )
    }
    
    // 添加笔记
    fun addNote(
        content: String,
        title: String? = null,
        tags: List<String> = emptyList(),
        isMarkdown: Boolean = false,
        isFavorite: Boolean = false,
        categoryId: Long? = null,
        imagePaths: String = "",
        keepassDatabaseId: Long? = null,
        keepassGroupPath: String? = null,
        bitwardenVaultId: Long? = null,
        bitwardenFolderId: String? = null,
        replicaGroupId: String? = null
    ) {
        viewModelScope.launch {
            val (itemData, notesCache) = NoteContentCodec.encode(
                content = content,
                tags = tags,
                isMarkdown = isMarkdown
            )
            val resolvedTitle = NoteContentCodec.resolveTitle(title = title, content = content)
            val keepassIdentity = resolveKeePassMutationIdentity(
                existingItem = null,
                targetDatabaseId = keepassDatabaseId,
                requestedGroupPath = keepassGroupPath
            )
            
            val item = SecureItem(
                id = 0,
                itemType = ItemType.NOTE,
                title = resolvedTitle,
                notes = notesCache, // 保留 notes 搜索兼容
                itemData = itemData,
                isFavorite = isFavorite,
                imagePaths = imagePaths,
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassIdentity.groupPath,
                keepassEntryUuid = keepassIdentity.entryUuid,
                keepassGroupUuid = keepassIdentity.groupUuid,
                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId,
                syncStatus = if (bitwardenVaultId != null) "PENDING" else "NONE",
                replicaGroupId = replicaGroupId,
                createdAt = Date(),
                updatedAt = Date()
            )
            val newId = keepassSecureItemCreateExecutor.create(
                item = item,
                insertItem = repository::insertItem,
                rollbackItem = repository::deleteItemById
            ) ?: return@launch
            requestBitwardenMutationSync(bitwardenVaultId)
            
            // 记录创建操作
            OperationLogger.logCreate(
                itemType = OperationLogItemType.NOTE,
                itemId = newId,
                itemTitle = resolvedTitle
            )
        }
    }
    
    // 更新笔记
    fun updateNote(
        id: Long,
        content: String,
        title: String? = null,
        tags: List<String> = emptyList(),
        isMarkdown: Boolean = false,
        isFavorite: Boolean,
        createdAt: Date,
        categoryId: Long? = null,
        imagePaths: String = "",
        keepassDatabaseId: Long? = null,
        keepassGroupPath: String? = null,
        bitwardenVaultId: Long? = null,
        bitwardenFolderId: String? = null,
        replicaGroupId: String? = null
    ) {
        viewModelScope.launch {
            // 获取旧笔记以检测变化
            val existingItem = repository.getItemById(id)
            val transition = resolveBitwardenTransition(
                existingItem = existingItem,
                targetVaultId = bitwardenVaultId,
                targetFolderId = bitwardenFolderId,
                forcePendingWhenKeepingCipher = bitwardenVaultId != null &&
                    !existingItem?.bitwardenCipherId.isNullOrBlank(),
                abortOnQueueFailure = true
            ) ?: run {
                Log.e(TAG, "Skip note update $id: failed to queue Bitwarden delete")
                return@launch
            }
            
            val (itemData, notesCache) = NoteContentCodec.encode(
                content = content,
                tags = tags,
                isMarkdown = isMarkdown
            )
            val resolvedTitle = NoteContentCodec.resolveTitle(title = title, content = content)
            val keepassIdentity = resolveKeePassMutationIdentity(
                existingItem = existingItem,
                targetDatabaseId = keepassDatabaseId,
                requestedGroupPath = keepassGroupPath
            )
            
            val item = SecureItem(
                id = id,
                itemType = ItemType.NOTE,
                title = resolvedTitle,
                notes = notesCache,
                itemData = itemData,
                isFavorite = isFavorite,
                imagePaths = imagePaths,
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassIdentity.groupPath,
                keepassEntryUuid = keepassIdentity.entryUuid,
                keepassGroupUuid = keepassIdentity.groupUuid,
                bitwardenVaultId = bitwardenVaultId,
                bitwardenCipherId = transition.cipherId,
                bitwardenFolderId = bitwardenFolderId,
                bitwardenRevisionDate = transition.revisionDate,
                bitwardenLocalModified = transition.localModified,
                syncStatus = transition.syncStatus,
                replicaGroupId = replicaGroupId ?: existingItem?.replicaGroupId,
                createdAt = createdAt,
                updatedAt = Date()
            )
            val keepassSync = keepassSecureItemUpdateExecutor.syncUpdatedItem(
                existingItem = existingItem,
                updatedItem = item,
                persistUpdate = { persistedItem ->
                    repository.updateItem(persistedItem)
                }
            )
            if (keepassSync.isFailure) {
                Log.e(
                    "NoteViewModel",
                    "KeePass note update failed before local update: ${keepassSync.exceptionOrNull()?.message}"
                )
                return@launch
            }
            requestBitwardenMutationSync(bitwardenVaultId)
            val removedImageIds = existingItem
                ?.let { extractImageRefs(it) - extractImageRefs(item) }
                .orEmpty()
            cleanupUnreferencedNoteImagesInternal(removedImageIds)
            
            // 记录更新操作 - 始终记录，即使没有检测到字段变更
            val changes = mutableListOf<FieldChange>()
            existingItem?.let { oldItem ->
                val oldContent = NoteContentCodec.decodeFromItem(oldItem).content
                if (oldContent != content) {
                    changes.add(FieldChange("内容", oldContent, content))
                }
                // 检测标题变化
                if (oldItem.title != resolvedTitle) {
                    changes.add(FieldChange("标题", oldItem.title, resolvedTitle))
                }
            }
            // 即使没有变更也记录更新操作，以便追踪编辑行为
            OperationLogger.logUpdate(
                itemType = OperationLogItemType.NOTE,
                itemId = id,
                itemTitle = resolvedTitle,
                changes = if (changes.isEmpty()) {
                    listOf(
                        FieldChange(
                            "更新",
                            "编辑于",
                            java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        )
                    )
                } else {
                    changes
                },
                snapshotChanges = if (changes.isEmpty() || existingItem == null) {
                    emptyList()
                } else {
                    changes + listOf(
                        FieldChange(
                            com.bastion.app.data.TIMELINE_SNAPSHOT_FIELD_ITEM_DATA,
                            existingItem.itemData,
                            item.itemData
                        ),
                        FieldChange(
                            com.bastion.app.data.TIMELINE_SNAPSHOT_FIELD_NOTES,
                            existingItem.notes,
                            item.notes
                        )
                    )
                }
            )
        }
    }

    fun saveNotesAcrossTargets(
        id: Long?,
        content: String,
        title: String? = null,
        tags: List<String> = emptyList(),
        isMarkdown: Boolean = false,
        isFavorite: Boolean = false,
        createdAt: Date = Date(),
        imagePaths: String = "",
        targets: List<StorageTarget>
    ) {
        viewModelScope.launch {
            val distinctTargets = targets.distinctBy(StorageTarget::stableKey)
            if (distinctTargets.isEmpty()) return@launch

            val existingItem = id?.let { repository.getItemById(it) }?.takeIf { it.itemType == ItemType.NOTE }
            val selectedTargetKeys = distinctTargets.map(StorageTarget::stableKey).toSet()
            val currentTarget = existingItem
                ?.toStorageTarget()
                ?.takeIf { it.stableKey in selectedTargetKeys }
                ?: distinctTargets.first()
            val replicaGroupId = existingItem?.replicaGroupId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val existingReplicasByKey = if (existingItem != null) {
                repository.getAllItems().first()
                    .asSequence()
                    .filter {
                        it.itemType == ItemType.NOTE &&
                            it.replicaGroupId == replicaGroupId &&
                            it.id != existingItem.id &&
                            !it.isDeleted
                    }
                    .associateBy { it.toStorageTarget().stableKey }
            } else {
                emptyMap()
            }

            when (currentTarget) {
                is StorageTarget.BastionLocal -> {
                    if (existingItem == null) {
                        addNote(
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            categoryId = currentTarget.categoryId,
                            imagePaths = imagePaths,
                            replicaGroupId = replicaGroupId
                        )
                    } else {
                        updateNote(
                            id = existingItem.id,
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            createdAt = createdAt,
                            categoryId = currentTarget.categoryId,
                            imagePaths = imagePaths,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }
                is StorageTarget.KeePass -> {
                    if (existingItem == null) {
                        addNote(
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = currentTarget.databaseId,
                            keepassGroupPath = currentTarget.groupPath,
                            replicaGroupId = replicaGroupId
                        )
                    } else {
                        updateNote(
                            id = existingItem.id,
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            createdAt = createdAt,
                            imagePaths = imagePaths,
                            keepassDatabaseId = currentTarget.databaseId,
                            keepassGroupPath = currentTarget.groupPath,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }
                is StorageTarget.Bitwarden -> {
                    if (existingItem == null) {
                        addNote(
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = currentTarget.vaultId,
                            bitwardenFolderId = currentTarget.folderId,
                            replicaGroupId = replicaGroupId
                        )
                    } else {
                        updateNote(
                            id = existingItem.id,
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            createdAt = createdAt,
                            imagePaths = imagePaths,
                            bitwardenVaultId = currentTarget.vaultId,
                            bitwardenFolderId = currentTarget.folderId,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }
            }

            distinctTargets
                .filter { it.stableKey != currentTarget.stableKey }
                .forEach { target ->
                    val existingReplica = existingReplicasByKey[target.stableKey]
                    when (target) {
                        is StorageTarget.BastionLocal -> if (existingReplica == null) addNote(
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            categoryId = target.categoryId,
                            imagePaths = imagePaths,
                            replicaGroupId = replicaGroupId
                        ) else updateNote(
                            id = existingReplica.id,
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            createdAt = createdAt,
                            categoryId = target.categoryId,
                            imagePaths = imagePaths,
                            replicaGroupId = replicaGroupId
                        )
                        is StorageTarget.KeePass -> if (existingReplica == null) addNote(
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            keepassDatabaseId = target.databaseId,
                            keepassGroupPath = target.groupPath,
                            replicaGroupId = replicaGroupId
                        ) else updateNote(
                            id = existingReplica.id,
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            createdAt = createdAt,
                            imagePaths = imagePaths,
                            keepassDatabaseId = target.databaseId,
                            keepassGroupPath = target.groupPath,
                            replicaGroupId = replicaGroupId
                        )
                        is StorageTarget.Bitwarden -> if (existingReplica == null) addNote(
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            imagePaths = imagePaths,
                            bitwardenVaultId = target.vaultId,
                            bitwardenFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        ) else updateNote(
                            id = existingReplica.id,
                            content = content,
                            title = title,
                            tags = tags,
                            isMarkdown = isMarkdown,
                            isFavorite = isFavorite,
                            createdAt = createdAt,
                            imagePaths = imagePaths,
                            bitwardenVaultId = target.vaultId,
                            bitwardenFolderId = target.folderId,
                            replicaGroupId = replicaGroupId
                        )
                    }
                }

            repository.getAllItems().first()
                .filter {
                    it.itemType == ItemType.NOTE &&
                        it.replicaGroupId == replicaGroupId &&
                        it.id != existingItem?.id &&
                        !it.isDeleted &&
                        it.toStorageTarget().stableKey !in selectedTargetKeys
                }
                .forEach { repository.deleteItemById(it.id) }
        }
    }

    suspend fun moveNoteToStorage(
        item: SecureItem,
        categoryId: Long? = item.categoryId,
        keepassDatabaseId: Long? = item.keepassDatabaseId,
        keepassGroupPath: String? = item.keepassGroupPath,
        bitwardenVaultId: Long? = item.bitwardenVaultId,
        bitwardenFolderId: String? = item.bitwardenFolderId,
    ): Boolean {
        if (item.itemType != ItemType.NOTE) return false
        val target = when {
            bitwardenVaultId != null -> StorageTarget.Bitwarden(bitwardenVaultId, bitwardenFolderId)
            keepassDatabaseId != null -> StorageTarget.KeePass(keepassDatabaseId, keepassGroupPath)
            else -> StorageTarget.BastionLocal(categoryId)
        }
        if (hasReplicaTargetConflict(
                itemType = ItemType.NOTE,
                itemId = item.id,
                replicaGroupId = item.replicaGroupId,
                target = target
            )
        ) {
            return false
        }

        val transition = resolveBitwardenTransition(
            existingItem = item,
            targetVaultId = bitwardenVaultId,
            targetFolderId = bitwardenFolderId,
            forcePendingWhenKeepingCipher = item.bitwardenVaultId == bitwardenVaultId &&
                item.bitwardenFolderId != bitwardenFolderId &&
                bitwardenVaultId != null &&
                !item.bitwardenCipherId.isNullOrBlank(),
            abortOnQueueFailure = true
        ) ?: return false

        val keepassIdentity = resolveKeePassMutationIdentity(
            existingItem = item,
            targetDatabaseId = keepassDatabaseId,
            requestedGroupPath = keepassGroupPath
        )

        val updated = item.copy(
            categoryId = categoryId,
            keepassDatabaseId = keepassDatabaseId,
            keepassGroupPath = keepassIdentity.groupPath,
            keepassEntryUuid = keepassIdentity.entryUuid,
            keepassGroupUuid = keepassIdentity.groupUuid,
            bitwardenVaultId = bitwardenVaultId,
            bitwardenFolderId = bitwardenFolderId,
            bitwardenCipherId = transition.cipherId,
            bitwardenRevisionDate = transition.revisionDate,
            bitwardenLocalModified = transition.localModified,
            syncStatus = transition.syncStatus,
            updatedAt = Date()
        )
        val keepassSync = keepassSecureItemUpdateExecutor.syncUpdatedItem(
            existingItem = item,
            updatedItem = updated,
            persistUpdate = { persistedItem ->
                repository.updateItem(persistedItem)
            }
        )
        if (keepassSync.isFailure) {
            Log.e(
                "NoteViewModel",
                "KeePass note move failed before local update: ${keepassSync.exceptionOrNull()?.message}"
            )
            return false
        }
        requestBitwardenMutationSync(bitwardenVaultId)
        return true
    }

    suspend fun copyNoteToBastionLocal(
        item: SecureItem,
        categoryId: Long?
    ): Long? {
        if (item.itemType != ItemType.NOTE || item.hasOwnershipConflict()) return null
        val localCopy = item.asBastionLocalCopy(categoryId).copy(
            createdAt = Date(),
            updatedAt = Date()
        )
        return repository.insertItem(localCopy)
    }

    suspend fun moveNoteToBastionLocal(
        item: SecureItem,
        categoryId: Long?
    ): Result<Long> {
        if (item.itemType != ItemType.NOTE) {
            return Result.failure(IllegalArgumentException("仅支持笔记项目"))
        }
        if (item.hasOwnershipConflict()) {
            return Result.failure(IllegalStateException("笔记来源冲突，无法移动到 Bastion 本地"))
        }

        val newId = copyNoteToBastionLocal(item, categoryId)
            ?: return Result.failure(IllegalStateException("创建 Bastion 本地笔记副本失败"))

        val sourceDelete = when (val ownership = item.resolveOwnership()) {
            is SecureItemOwnership.Bitwarden -> {
                val vaultId = ownership.vaultId
                val cipherId = ownership.cipherId
                if (vaultId == null || cipherId.isNullOrBlank()) {
                    Result.failure(IllegalStateException("Bitwarden 笔记缺少同步标识"))
                } else {
                    bitwardenRepository?.queueCipherDelete(
                        vaultId = vaultId,
                        cipherId = cipherId,
                        entryId = item.id,
                        itemType = BitwardenPendingOperation.ITEM_TYPE_NOTE
                    ) ?: Result.failure(IllegalStateException("Bitwarden 仓库不可用"))
                }
            }
            is SecureItemOwnership.KeePass -> {
                if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = false)) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("KeePass 笔记源删除失败"))
                }
            }
            is SecureItemOwnership.BastionLocal -> Result.success(Unit)
            is SecureItemOwnership.Conflict -> Result.failure(IllegalStateException("笔记来源冲突，无法移动到 Bastion 本地"))
        }

        if (sourceDelete.isFailure) {
            Log.e(
                "NoteViewModel",
                "Note move to Bastion local kept target copy after source cleanup failed; sourceId=${item.id} targetId=$newId error=${sourceDelete.exceptionOrNull()?.message}"
            )
            return Result.failure(
                sourceDelete.exceptionOrNull() ?: IllegalStateException("删除源笔记失败")
            )
        }

        repository.deleteItem(item)
        return Result.success(newId)
    }

    private suspend fun hasReplicaTargetConflict(
        itemType: ItemType,
        itemId: Long,
        replicaGroupId: String?,
        target: StorageTarget
    ): Boolean {
        if (replicaGroupId.isNullOrBlank()) return false
        return repository.getAllItems().first()
            .asSequence()
            .filter { candidate ->
                candidate.itemType == itemType &&
                    candidate.id != itemId &&
                    candidate.replicaGroupId == replicaGroupId &&
                    !candidate.isDeleted
            }
            .any { candidate -> candidate.toStorageTarget().stableKey == target.stableKey }
    }

    private fun resolveKeePassMutationIdentity(
        existingItem: SecureItem?,
        targetDatabaseId: Long?,
        requestedGroupPath: String?
    ): KeePassMutationIdentity {
        if (targetDatabaseId == null) {
            return KeePassMutationIdentity(
                groupPath = null,
                entryUuid = null,
                groupUuid = null
            )
        }

        val sameDatabase = existingItem?.keepassDatabaseId == targetDatabaseId
        val resolvedGroupPath = requestedGroupPath ?: if (sameDatabase) existingItem?.keepassGroupPath else null
        val groupUnchanged = sameDatabase && resolvedGroupPath == existingItem?.keepassGroupPath

        return KeePassMutationIdentity(
            groupPath = resolvedGroupPath,
            entryUuid = KeePassCrossDatabaseTransfer.secureItemTargetEntryUuid(
                item = existingItem,
                databaseId = targetDatabaseId,
                groupPath = resolvedGroupPath
            ),
            groupUuid = if (groupUnchanged) existingItem?.keepassGroupUuid else null
        )
    }
    
    // 删除笔记
    // @param softDelete 是否软删除（移入回收站），默认为 true
    fun deleteNote(item: SecureItem, softDelete: Boolean = true) {
        viewModelScope.launch {
            val vaultId = item.bitwardenVaultId
            val cipherId = item.bitwardenCipherId
            val isBitwardenCipher = vaultId != null && !cipherId.isNullOrBlank()

            if (isBitwardenCipher) {
                val queueResult = bitwardenRepository?.queueCipherDelete(
                    vaultId = vaultId!!,
                    cipherId = cipherId!!,
                    entryId = item.id,
                    itemType = BitwardenPendingOperation.ITEM_TYPE_NOTE
                )
                if (queueResult?.isFailure == true) {
                    Log.e(TAG, "Queue Bitwarden note delete failed: ${queueResult.exceptionOrNull()?.message}")
                    return@launch
                }
            }

            if (!softDelete || isBitwardenCipher) {
                if (!keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = softDelete || isBitwardenCipher)) {
                    Log.e("NoteViewModel", "KeePass delete failed for note id=${item.id}")
                    return@launch
                }
            }

            if (isBitwardenCipher) {
                passwordRepository?.clearBoundNoteReferences(item.id)
                repository.updateItem(
                    item.copy(
                        isDeleted = true,
                        deletedAt = Date(),
                        updatedAt = Date(),
                        bitwardenLocalModified = true
                    )
                )
                OperationLogger.logDelete(
                    itemType = OperationLogItemType.NOTE,
                    itemId = item.id,
                    itemTitle = item.title,
                    detail = "移入回收站（待同步删除）"
                )
                return@launch
            }

            if (softDelete) {
                // 软删除：移动到回收站
                passwordRepository?.clearBoundNoteReferences(item.id)
                repository.softDeleteItem(item)
                // 记录移入回收站操作
                OperationLogger.logDelete(
                    itemType = OperationLogItemType.NOTE,
                    itemId = item.id,
                    itemTitle = item.title,
                    detail = "移入回收站"
                )

                if (!isBitwardenCipher && item.keepassDatabaseId != null) {
                    viewModelScope.launch keepassDeleteSync@{
                        if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = true)) {
                            Log.i(TAG, "KeePass trash delete synced for note id=${item.id}")
                            return@keepassDeleteSync
                        }

                        Log.e(TAG, "KeePass trash delete failed, reverting local trash state for note id=${item.id}")
                        repository.updateItem(item.copy(updatedAt = Date()))
                    }
                }
            } else {
                // 永久删除
                passwordRepository?.clearBoundNoteReferences(item.id)
                OperationLogger.logDelete(
                    itemType = OperationLogItemType.NOTE,
                    itemId = item.id,
                    itemTitle = item.title
                )
                repository.deleteItem(item)
                cleanupUnreferencedNoteImagesInternal(extractImageRefs(item))
            }
        }
    }

    // 批量删除笔记
    // @param softDelete 是否软删除（移入回收站），默认为 true
    fun deleteNotes(items: List<SecureItem>, softDelete: Boolean = true) {
        viewModelScope.launch {
            items.forEach { item ->
                val vaultId = item.bitwardenVaultId
                val cipherId = item.bitwardenCipherId
                val isBitwardenCipher = vaultId != null && !cipherId.isNullOrBlank()

                if (isBitwardenCipher) {
                    val queueResult = bitwardenRepository?.queueCipherDelete(
                        vaultId = vaultId!!,
                        cipherId = cipherId!!,
                        entryId = item.id,
                        itemType = BitwardenPendingOperation.ITEM_TYPE_NOTE
                    )
                    if (queueResult?.isFailure == true) {
                        Log.e(TAG, "Queue Bitwarden note delete failed: ${queueResult.exceptionOrNull()?.message}")
                        return@forEach
                    }
                }

                if (!softDelete || isBitwardenCipher) {
                    if (!keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = softDelete || isBitwardenCipher)) {
                        Log.e("NoteViewModel", "KeePass delete failed for note id=${item.id}")
                        return@forEach
                    }
                }

                if (isBitwardenCipher) {
                    passwordRepository?.clearBoundNoteReferences(item.id)
                    repository.updateItem(
                        item.copy(
                            isDeleted = true,
                            deletedAt = Date(),
                            updatedAt = Date(),
                            bitwardenLocalModified = true
                        )
                    )
                    OperationLogger.logDelete(
                        itemType = OperationLogItemType.NOTE,
                        itemId = item.id,
                        itemTitle = item.title,
                        detail = "移入回收站（待同步删除）"
                    )
                    return@forEach
                }

                if (softDelete) {
                    // 软删除：移动到回收站
                    passwordRepository?.clearBoundNoteReferences(item.id)
                    repository.softDeleteItem(item)
                    OperationLogger.logDelete(
                        itemType = OperationLogItemType.NOTE,
                        itemId = item.id,
                        itemTitle = item.title,
                        detail = "移入回收站"
                    )

                    if (!isBitwardenCipher && item.keepassDatabaseId != null) {
                        viewModelScope.launch keepassDeleteSync@{
                            if (keepassSecureItemDeleteExecutor.delete(item, useRecycleBin = true)) {
                                Log.i(TAG, "KeePass trash delete synced for note id=${item.id}")
                                return@keepassDeleteSync
                            }

                            Log.e(TAG, "KeePass trash delete failed, reverting local trash state for note id=${item.id}")
                            repository.updateItem(item.copy(updatedAt = Date()))
                        }
                    }
                } else {
                    // 永久删除
                    passwordRepository?.clearBoundNoteReferences(item.id)
                    OperationLogger.logDelete(
                        itemType = OperationLogItemType.NOTE,
                        itemId = item.id,
                        itemTitle = item.title
                    )
                    repository.deleteItem(item)
                    cleanupUnreferencedNoteImagesInternal(extractImageRefs(item))
                }
            }
        }
    }

    fun cleanupUnreferencedNoteImages(candidateImageIds: List<String>) {
        viewModelScope.launch {
            cleanupUnreferencedNoteImagesInternal(
                candidateImageIds
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            )
        }
    }

    private suspend fun cleanupUnreferencedNoteImagesInternal(candidateImageIds: Set<String>) {
        if (candidateImageIds.isEmpty()) return
        val manager = imageManager ?: return

        val activeNotes = repository.getItemsByType(ItemType.NOTE).first()
        val deletedNotes = repository.getDeletedItems().first().filter { it.itemType == ItemType.NOTE }
        val allNotes = activeNotes + deletedNotes
        val referencedImageIds = allNotes.flatMap { extractImageRefs(it) }.toSet()
        val shouldDelete = candidateImageIds.filterNot { referencedImageIds.contains(it) }
        if (shouldDelete.isNotEmpty()) {
            manager.deleteImages(shouldDelete)
        }
    }

    private fun extractImageRefs(item: SecureItem): Set<String> {
        val decoded = NoteContentCodec.decodeFromItem(item)
        val fromContent = NoteContentCodec.extractInlineImageIds(decoded.content)
        val fromLegacyPaths = NoteContentCodec.decodeImagePaths(item.imagePaths)
        return (fromContent + fromLegacyPaths).toSet()
    }

    private suspend fun resolveBitwardenTransition(
        existingItem: SecureItem?,
        targetVaultId: Long?,
        targetFolderId: String?,
        forcePendingWhenKeepingCipher: Boolean,
        abortOnQueueFailure: Boolean
    ) = SecureItemBitwardenTransitionResolver.resolve(
        tag = TAG,
        existingItem = existingItem,
        targetVaultId = targetVaultId,
        targetFolderId = targetFolderId,
        forcePendingWhenKeepingCipher = forcePendingWhenKeepingCipher,
        abortOnQueueFailure = abortOnQueueFailure
    ) { vaultId, cipherId, entryId ->
        bitwardenRepository?.queueCipherDelete(
            vaultId = vaultId,
            cipherId = cipherId,
            entryId = entryId,
            itemType = BitwardenPendingOperation.ITEM_TYPE_NOTE
        )
    }
}
