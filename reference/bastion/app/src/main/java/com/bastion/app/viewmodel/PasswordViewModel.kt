package com.bastion.app.viewmodel

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bastion.app.bitwarden.BitwardenMutationStateHelper
import com.bastion.app.bitwarden.cache.BitwardenOfflineSecretCache
import com.bastion.app.bitwarden.service.BitwardenSyncSnapshotPreviewParser
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.domain.provider.BitwardenPasswordProvider
import com.bastion.app.domain.provider.DefaultPasswordProvider
import com.bastion.app.domain.provider.KeePassPasswordProvider
import com.bastion.app.domain.provider.PasswordCommandStateFactory
import com.bastion.app.domain.provider.PasswordProviderRegistry
import com.bastion.app.domain.provider.PasswordSource
import com.bastion.app.keepass.KeePassPasswordCreateExecutor
import com.bastion.app.keepass.KeePassPasswordUpdateExecutor
import com.bastion.app.keepass.KeePassPasswordDeleteExecutor
import com.bastion.app.keepass.KeePassTotpProjectionMatcher
import com.bastion.app.data.Category
import com.bastion.app.data.CustomField
import com.bastion.app.data.CustomFieldDraft
import com.bastion.app.data.LocalKeePassDatabaseDao
import com.bastion.app.data.PasswordOwnership
import com.bastion.app.data.PasswordArchiveSyncMeta
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.PasswordGenerationHistory
import com.bastion.app.data.PasswordHistoryEntry
import com.bastion.app.data.PasswordHistoryManager
import com.bastion.app.data.SecureItem
import com.bastion.app.data.resolveOwnership
import com.bastion.app.data.writeOperationAvailability
import com.bastion.app.repository.KeePassCompatibilityBridge
import com.bastion.app.repository.KeePassWorkspaceRepository
import com.bastion.app.repository.CustomFieldRepository
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.repository.SecureItemRepository
import com.bastion.app.security.SecurityManager
import com.bastion.app.security.SessionManager
import com.bastion.app.data.model.TotpData
import com.bastion.app.data.ItemType
import com.bastion.app.utils.KeePassCustomFieldData
import com.bastion.app.utils.KeePassEntryData
import com.bastion.app.utils.KeePassKdbxService
import com.bastion.app.utils.KeePassSecureItemData
import com.bastion.app.utils.buildKeePassPathKey
import com.bastion.app.data.model.StorageTarget
import com.bastion.app.data.model.applyToPasswordEntry
import com.bastion.app.data.model.toStorageTarget
import com.bastion.app.ui.model.SecretValueState
import com.bastion.app.ui.model.plainValueOrEmpty
import com.bastion.app.sync.SyncDiagnostics
import com.bastion.app.sync.SyncItemKind
import com.bastion.app.sync.SyncMode
import com.bastion.app.sync.SyncPriority
import com.bastion.app.sync.SyncRequest
import com.bastion.app.sync.SyncTarget
import com.bastion.app.sync.SyncTaskRunner
import com.bastion.app.sync.SyncTrigger
import com.bastion.app.util.TotpDataResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Date
import java.util.Locale
import java.util.UUID

import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.sync.syncForUserVisibleRequest
import com.bastion.app.data.bitwarden.BitwardenFolder

private const val PASSWORD_SCROLL_LOG_TAG = "PasswordScrollDebug"

private data class KeePassCustomFieldFingerprint(
    val title: String,
    val value: String,
    val isProtected: Boolean,
    val sortOrder: Int
)
private const val PASSWORD_SCROLL_DEBUG_LOGS_ENABLED = false

/**
 * ViewModel for password management
 */
class PasswordViewModel(
    private val repository: PasswordRepository,
    private val securityManager: SecurityManager,
    private val secureItemRepository: SecureItemRepository? = null,
    private val customFieldRepository: CustomFieldRepository? = null,
    context: Context? = null,
    private val localKeePassDatabaseDao: LocalKeePassDatabaseDao? = null,
    // B.3 集群 8：无依赖协作者改为构造参数注入（默认值保持调用方零改动）。
    private val bitwardenSnapshotPreviewParser: BitwardenSyncSnapshotPreviewParser = BitwardenSyncSnapshotPreviewParser(),
    private val passwordCommandStateFactory: PasswordCommandStateFactory = PasswordCommandStateFactory(),
    private val archiveFilterController: PasswordArchiveFilterController = PasswordArchiveFilterController()
) : ViewModel() {
    private val decryptLock = Any()
    private val appContext: Context? = context?.applicationContext

    companion object {
        // 过滤器持久化类型字面量的唯一真源在 CategoryFilterCodec（B.3 集群 4）。
        // 此处保留同名别名，使 persistCategoryFilter 的分支文本与调用点保持不变。
        private const val SAVED_FILTER_ALL = CategoryFilterCodec.SAVED_FILTER_ALL
        private const val SAVED_FILTER_LOCAL = CategoryFilterCodec.SAVED_FILTER_LOCAL
        private const val SAVED_FILTER_LOCAL_ONLY = CategoryFilterCodec.SAVED_FILTER_LOCAL_ONLY
        private const val SAVED_FILTER_STARRED = CategoryFilterCodec.SAVED_FILTER_STARRED
        private const val SAVED_FILTER_UNCATEGORIZED = CategoryFilterCodec.SAVED_FILTER_UNCATEGORIZED
        private const val SAVED_FILTER_LOCAL_STARRED = CategoryFilterCodec.SAVED_FILTER_LOCAL_STARRED
        private const val SAVED_FILTER_LOCAL_UNCATEGORIZED = CategoryFilterCodec.SAVED_FILTER_LOCAL_UNCATEGORIZED
        private const val SAVED_FILTER_CUSTOM = CategoryFilterCodec.SAVED_FILTER_CUSTOM
        private const val SAVED_FILTER_KEEPASS_DATABASE = CategoryFilterCodec.SAVED_FILTER_KEEPASS_DATABASE
        private const val SAVED_FILTER_KEEPASS_GROUP = CategoryFilterCodec.SAVED_FILTER_KEEPASS_GROUP
        private const val SAVED_FILTER_KEEPASS_DATABASE_STARRED = CategoryFilterCodec.SAVED_FILTER_KEEPASS_DATABASE_STARRED
        private const val SAVED_FILTER_KEEPASS_DATABASE_UNCATEGORIZED = CategoryFilterCodec.SAVED_FILTER_KEEPASS_DATABASE_UNCATEGORIZED
        private const val SAVED_FILTER_BITWARDEN_VAULT = CategoryFilterCodec.SAVED_FILTER_BITWARDEN_VAULT
        private const val SAVED_FILTER_BITWARDEN_FOLDER = CategoryFilterCodec.SAVED_FILTER_BITWARDEN_FOLDER
        private const val SAVED_FILTER_BITWARDEN_VAULT_STARRED = CategoryFilterCodec.SAVED_FILTER_BITWARDEN_VAULT_STARRED
        private const val SAVED_FILTER_BITWARDEN_VAULT_UNCATEGORIZED = CategoryFilterCodec.SAVED_FILTER_BITWARDEN_VAULT_UNCATEGORIZED
        private const val MONICA_MANUAL_STACK_GROUP_FIELD_TITLE = "__bastion_manual_stack_group"
        private const val MONICA_NO_STACK_FIELD_TITLE = "__bastion_no_stack"
        private const val MONICA_KEEPASS_ARCHIVE_ROOT_GROUP_NAME = ".Bastion"
        private const val MONICA_KEEPASS_ARCHIVE_GROUP_NAME = "Archive"
        private const val KEEPASS_BATCH_DELETE_CHUNK_SIZE = 40
    }

    enum class ManualStackMode {
        STACK,
        AUTO_STACK,
        NEVER_STACK
    }
    
    private val passwordHistoryManager: PasswordHistoryManager? = context?.let { PasswordHistoryManager(it) }
    private val settingsManager: com.bastion.app.utils.SettingsManager? = context?.let { com.bastion.app.utils.SettingsManager(it) }
    private val bitwardenRepository: BitwardenRepository? = context?.let { BitwardenRepository.getInstance(it.applicationContext) }
    private val bitwardenOfflineSecretCacheFacade = BitwardenOfflineSecretCacheFacade(
        cache = context?.applicationContext?.let {
            BitwardenOfflineSecretCache(it, securityManager)
        }
    )
    private val keepassBridge = if (context != null && localKeePassDatabaseDao != null) {
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
    private val keepassPasswordDeleteExecutor = KeePassPasswordDeleteExecutor(keepassBridge)
    private val keepassPasswordCreateExecutor = KeePassPasswordCreateExecutor(keepassBridge)
    private val keepassPasswordUpdateExecutor = KeePassPasswordUpdateExecutor(keepassBridge)
    // 跨存储迁移编排（B.3 集群 5c）。依赖 VM 内带解密副作用/DAO 的私有逻辑，
    // 以函数引用注入，实现留在本类。
    private val passwordMoveExecutor = PasswordMoveExecutor(
        repository = repository,
        keepassPasswordUpdateExecutor = keepassPasswordUpdateExecutor,
        keepassPasswordDeleteExecutor = keepassPasswordDeleteExecutor,
        bitwardenRepository = bitwardenRepository,
        appContext = appContext,
        resolveKeePassCustomFieldsForSync = ::resolveKeePassCustomFieldsForSync,
        decodePasswordOrNull = ::decodePasswordOrNull,
        canWriteKeePassDatabase = ::canWriteKeePassDatabase
    )
    private val defaultPasswordProvider = DefaultPasswordProvider(
        decodePassword = ::decodePasswordOrNull,
        encryptPassword = securityManager::encryptData
    )
    // 主密码 / 历史编排（B.3 集群 7）。依赖 VM 内被 10+ 处复用的带解密副作用
    // 私有逻辑（decryptForDisplay / decodePasswordOrNull），以函数引用注入，实现留在本类。
    private val passwordHistoryRecorder = PasswordHistoryRecorder(
        repository = repository,
        securityManager = securityManager,
        bitwardenRepository = bitwardenRepository,
        bitwardenSnapshotPreviewParser = bitwardenSnapshotPreviewParser,
        decryptForDisplay = ::decryptForDisplay,
        decodePasswordOrNull = ::decodePasswordOrNull
    )
    private val masterPasswordOps = MasterPasswordOps(
        repository = repository,
        securityManager = securityManager,
        decryptForDisplay = ::decryptForDisplay
    )
    private val passwordProviderRegistry = PasswordProviderRegistry(
        providers = listOf(
            KeePassPasswordProvider(
                decodePassword = ::decodePasswordOrNull,
                encryptPassword = securityManager::encryptData
            ),
            BitwardenPasswordProvider(
                decodePassword = ::decodePasswordOrNull,
                encryptPassword = securityManager::encryptData,
                loadOfflineCachedSecret = ::loadBitwardenOfflineCachedSecret,
                rememberOfflineCachedSecret = ::rememberBitwardenOfflineCachedSecret
            )
        ),
        fallbackProvider = defaultPasswordProvider
    )

    private fun decryptStoredSensitiveValue(value: String): String {
        return runCatchingObserved {
            securityManager.decryptDataIfBastionCiphertext(value)
        }.getOrDefault(value)
    }

    private fun looksLikeStoredSensitiveCiphertext(value: String): Boolean {
        return securityManager.looksLikeBastionCiphertext(value)
    }

    private fun encodeStoredSensitiveValueForCopy(originalValue: String?, plainValue: String): String {
        return if (!originalValue.isNullOrBlank() && looksLikeStoredSensitiveCiphertext(originalValue)) {
            securityManager.encryptDataLegacyCompat(plainValue)
        } else {
            plainValue
        }
    }

    private fun encodeStoredSensitiveValueForNewWrite(plainValue: String): String {
        if (plainValue.isBlank()) return plainValue
        return securityManager.encryptDataLegacyCompat(plainValue)
    }

    private fun encodeAuthenticatorKeyForStorage(value: String): String {
        if (value.isBlank()) return ""
        val plainValue = decryptStoredSensitiveValue(value)
        return encodeStoredSensitiveValueForNewWrite(plainValue)
    }

    private fun parseStoredTotpData(item: SecureItem): TotpData? {
        return TotpDataResolver.parseStoredItemData(
            itemData = item.itemData,
            fallbackIssuer = item.title,
            decryptIfNeeded = ::decryptStoredSensitiveValue
        )
    }

    private fun parseStoredAuthenticatorKey(
        password: PasswordEntry,
        fallbackIssuer: String = password.website.takeIf { it.isNotBlank() } ?: password.title,
        fallbackAccountName: String = password.username.takeIf { it.isNotBlank() } ?: password.title
    ): TotpData? {
        return TotpDataResolver.fromAuthenticatorKey(
            rawKey = decryptStoredSensitiveValue(password.authenticatorKey),
            fallbackIssuer = fallbackIssuer,
            fallbackAccountName = fallbackAccountName
        )
    }
    
    // Trash settings
    private val trashSettings = settingsManager?.settingsFlow?.map { 
        it.trashEnabled to it.trashAutoDeleteDays 
    }?.stateIn(viewModelScope, SharingStarted.Eagerly, true to 30)

    // Smart Deduplication setting
    private val smartDeduplicationEnabled = settingsManager?.settingsFlow?.map { 
        it.smartDeduplicationEnabled 
    }?.stateIn(viewModelScope, SharingStarted.Eagerly, true) ?: kotlinx.coroutines.flow.MutableStateFlow(true)
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _categoryFilter = MutableStateFlow<CategoryFilter>(CategoryFilter.All)
    val categoryFilter = _categoryFilter.asStateFlow()

    // 归档/取消归档编排（B.3 集群 6）。KeePass 侧的三个操作以函数引用注入，
    // 实现仍留在本类——它们依赖 keepassBridge 与解密副作用，属集群 3 范围。
    private val archiveOrchestrator = PasswordArchiveOrchestrator(
        repository = repository,
        stateFactory = passwordCommandStateFactory,
        commandPolicyOf = passwordProviderRegistry::commandPolicy,
        ensureArchiveGroupPath = ::ensureKeePassArchiveGroupPath,
        resolveRestorePathOrRoot = ::resolveKeePassRestorePathOrRoot,
        moveEntryGroupPath = ::moveKeePassEntryGroupPath
    )

    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    fun toggleExpandedGroup(groupKey: String) {
        _expandedGroups.value = if (_expandedGroups.value.contains(groupKey)) {
            _expandedGroups.value - groupKey
        } else {
            _expandedGroups.value + groupKey
        }
    }

    fun clearExpandedGroups() {
        _expandedGroups.value = emptySet()
    }

    private val _fastScrollRequestKey = MutableStateFlow(0)
    val fastScrollRequestKey: StateFlow<Int> = _fastScrollRequestKey.asStateFlow()
    private val _fastScrollProgress = MutableStateFlow(0f)
    val fastScrollProgress: StateFlow<Float> = _fastScrollProgress.asStateFlow()
    private val _passwordListScrollIndex = MutableStateFlow(0)
    val passwordListScrollIndex: StateFlow<Int> = _passwordListScrollIndex.asStateFlow()
    private val _passwordListScrollOffset = MutableStateFlow(0)
    val passwordListScrollOffset: StateFlow<Int> = _passwordListScrollOffset.asStateFlow()
    private val _passwordListScrollAnchorKey = MutableStateFlow<String?>(null)
    val passwordListScrollAnchorKey: StateFlow<String?> = _passwordListScrollAnchorKey.asStateFlow()

    private val categoriesSource = repository.getAllCategories()
        .distinctUntilChanged()
    val categoriesReady: StateFlow<Boolean> = categoriesSource
        .map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val categories = categoriesSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()
    private var hasLoggedDecryptAuthStateWarning = false

    init {
        restoreLastCategoryFilter()
        observeInvalidCustomCategoryFilter()
        viewModelScope.launch(Dispatchers.IO) {
            runCatchingObserved {
                // 只做一次全表扫描。此前「修复 1 + 修复 2 + 离线缓存预热」共触发 3 次
                // getAllPasswordEntries().first()，其中预热还会对整个密码库逐条解密
                // （每条最多 3 轮 decryptData，且都串在 decryptLock 全局锁上），与
                // Bitwarden 恢复解锁态所需的 3 次 Keystore 解密争抢 Keystore，
                // 造成隔夜冷启动出现肉眼可见的加载过程。
                // 现在解密改为按需发生（对齐 Bitwarden 官方与 Keyguard 的
                // 「本地 Flow + 解锁门控 + 按需解密」），冷启动路径不再有任何解密。
                val startedAt = System.currentTimeMillis()
                val entries = repository.getAllPasswordEntries().first()
                repairLegacyDetachedKeePassEntries(entries)
                repairLegacyOwnershipConflicts(entries)
                Log.i(
                    "PasswordViewModel",
                    "Startup maintenance done: entries=${entries.size}, " +
                        "costMs=${System.currentTimeMillis() - startedAt}"
                )
            }.onFailure { error ->
                Log.w("PasswordViewModel", "Password startup maintenance failed", error)
            }
        }
    }
    
    fun getBitwardenFolders(vaultId: Long): Flow<List<BitwardenFolder>> {
        return repository.getBitwardenFoldersByVaultId(vaultId)
    }

    fun requestFastScroll(progress: Float) {
        val safeProgress = progress.coerceIn(0f, 1f)
        val nextRequestKey = _fastScrollRequestKey.value + 1
        _fastScrollProgress.value = safeProgress
        _fastScrollRequestKey.value = nextRequestKey
        if (PASSWORD_SCROLL_DEBUG_LOGS_ENABLED) {
            Log.d(
                PASSWORD_SCROLL_LOG_TAG,
                "source=v1_request_fast_scroll progress=$safeProgress requestKey=$nextRequestKey"
            )
        }
    }

    fun updateFastScrollProgress(progress: Float) {
        _fastScrollProgress.value = progress.coerceIn(0f, 1f)
    }

    fun updatePasswordListScrollPosition(
        index: Int,
        offset: Int,
        anchorKey: String? = null,
        source: String = "unknown"
    ) {
        val safeIndex = index.coerceAtLeast(0)
        val safeOffset = offset.coerceAtLeast(0)
        val previousIndex = _passwordListScrollIndex.value
        val previousOffset = _passwordListScrollOffset.value
        val previousAnchorKey = _passwordListScrollAnchorKey.value
        val indexChanged = previousIndex != safeIndex
        val offsetChanged = previousOffset != safeOffset
        val anchorChanged = previousAnchorKey != anchorKey
        if (indexChanged) {
            _passwordListScrollIndex.value = safeIndex
        }
        if (offsetChanged) {
            _passwordListScrollOffset.value = safeOffset
        }
        if (anchorChanged) {
            _passwordListScrollAnchorKey.value = anchorKey
        }
        if (PASSWORD_SCROLL_DEBUG_LOGS_ENABLED && (indexChanged || offsetChanged || anchorChanged)) {
            Log.d(
                PASSWORD_SCROLL_LOG_TAG,
                "source=$source old=$previousIndex/$previousOffset anchor=$previousAnchorKey new=$safeIndex/$safeOffset anchor=$anchorKey"
            )
        }
    }

    private val debouncedSearchQuery: Flow<String> = searchQuery
        .debounce(300)
        .distinctUntilChanged()

    private val passwordEntriesSource: Flow<List<PasswordEntry>> = combine(
        debouncedSearchQuery,
        _categoryFilter
    ) { query, filter ->
        query to filter
    }
        .distinctUntilChanged()
        .flatMapLatest { (query, filter) ->
            val baseFlow: Flow<List<PasswordEntry>> = if (query.isNotBlank()) {
                // Extended search: query + custom fields, then apply current category filter in-memory.
                val searchFlow = repository.searchPasswordEntriesListItem(query).map { baseResults ->
                    val customFieldMatchIds = try {
                        customFieldRepository?.searchEntryIdsByFieldContent(query) ?: emptyList()
                    } catch (e: Exception) {
                        Log.w("PasswordViewModel", "Custom field search failed", e)
                        emptyList()
                    }
                    
                    if (customFieldMatchIds.isEmpty()) {
                        baseResults
                    } else {
                        val baseIds = baseResults.map { it.id }.toSet()
                        val additionalIds = customFieldMatchIds.filter { it !in baseIds }
                        
                        if (additionalIds.isEmpty()) {
                            baseResults
                        } else {
                            val additionalEntries = try {
                                repository.getActivePasswordsByIds(additionalIds)
                            } catch (e: Exception) {
                                Log.w("PasswordViewModel", "Failed to fetch custom field matched entries", e)
                                emptyList()
                            }
                            (baseResults + additionalEntries).distinctBy { it.id }
                        }
                    }
                }

                when (filter) {
                    is CategoryFilter.Archived -> repository.getArchivedEntriesListItem().map { archivedEntries ->
                        val byText = archivedEntries.filter { matchesSearchQuery(it, query) }
                        val customFieldMatchIds = try {
                            customFieldRepository?.searchEntryIdsByFieldContent(query)?.toSet() ?: emptySet()
                        } catch (e: Exception) {
                            Log.w("PasswordViewModel", "Custom field search failed in archived view", e)
                            emptySet()
                        }
                        if (customFieldMatchIds.isEmpty()) {
                            byText
                        } else {
                            val existingIds = byText.map { it.id }.toHashSet()
                            byText + archivedEntries.filter { it.id in customFieldMatchIds && it.id !in existingIds }
                        }
                    }
                    is CategoryFilter.LocalOnly -> combine(
                        searchFlow,
                        repository.getAllPasswordEntriesListItem()
                    ) { searchResults, allEntries ->
                        val localOnlyIds = filterLocalOnlyComparedToBitwarden(allEntries)
                            .asSequence()
                            .map { it.id }
                            .toHashSet()
                        searchResults.filter { it.id in localOnlyIds }
                    }
                    else -> searchFlow.map { searchResults ->
                        applyCategoryFilterInMemory(searchResults, filter)
                    }
                }
            } else {

                when (filter) {
                    is CategoryFilter.All -> repository.getAllPasswordEntriesListItem()
                    is CategoryFilter.Archived -> repository.getArchivedEntriesListItem()
                    is CategoryFilter.Local -> repository.getAllPasswordEntriesListItem().map { list ->
                        list.filter { it.isLocalOnlyEntry() }
                    }
                    is CategoryFilter.LocalOnly -> repository.getAllPasswordEntriesListItem().map { list ->
                        filterLocalOnlyComparedToBitwarden(list)
                    }
                    is CategoryFilter.Starred -> repository.getFavoritePasswordEntriesListItem()
                    is CategoryFilter.Uncategorized -> repository.getUncategorizedPasswordEntriesListItem()
                    is CategoryFilter.LocalStarred -> repository.getAllPasswordEntriesListItem().map { list ->
                        list.filter { it.isLocalOnlyEntry() && it.isFavorite }
                    }
                    is CategoryFilter.LocalUncategorized -> repository.getAllPasswordEntriesListItem().map { list ->
                        list.filter { it.isLocalOnlyEntry() && it.categoryId == null }
                    }
                    is CategoryFilter.Custom -> repository.getPasswordEntriesByCategoryListItem(filter.categoryId)
                        .map { list -> list.filter { it.isLocalOnlyEntry() } }
                    is CategoryFilter.KeePassDatabase -> repository.getPasswordEntriesByKeePassDatabaseListItem(filter.databaseId)
                    is CategoryFilter.KeePassGroupFilter -> repository.getPasswordEntriesByKeePassGroupListItem(filter.databaseId, filter.groupPath)
                    is CategoryFilter.KeePassDatabaseStarred -> repository.getAllPasswordEntriesListItem().map { list ->
                        list.filter { it.keepassDatabaseId == filter.databaseId && it.isFavorite }
                    }
                    is CategoryFilter.KeePassDatabaseUncategorized -> repository.getAllPasswordEntriesListItem().map { list ->
                        list.filter { it.keepassDatabaseId == filter.databaseId && it.keepassGroupPath.isNullOrBlank() }
                    }
                    is CategoryFilter.BitwardenVault -> repository.getPasswordEntriesByBitwardenVaultListItem(filter.vaultId)
                    is CategoryFilter.BitwardenFolderFilter -> repository.getPasswordEntriesByBitwardenFolderListItem(
                        filter.vaultId,
                        filter.folderId
                    )
                    is CategoryFilter.BitwardenVaultStarred -> repository.getAllPasswordEntriesListItem().map { list ->
                        list.filter { it.bitwardenVaultId == filter.vaultId && it.isFavorite }
                    }
                    is CategoryFilter.BitwardenVaultUncategorized -> repository.getAllPasswordEntriesListItem().map { list ->
                        list.filter { it.bitwardenVaultId == filter.vaultId && it.bitwardenFolderId == null }
                    }
                }
            }
            // Combine with settings for smart deduplication logic
            combine(baseFlow, smartDeduplicationEnabled) { entries, smartDedupe ->
                // Dedupe logic:
                // 1. If searching, or explicit Local/KeePass/Bitwarden filter -> NO dedupe (show raw data).
                // 2. If "All" or other categories -> Apply Smart Dedupe if enabled.
                val isExplicitSourceView = when (filter) {
                    is CategoryFilter.BitwardenVault -> true
                    is CategoryFilter.BitwardenFolderFilter -> true // Explicit folder view
                    is CategoryFilter.KeePassDatabase -> true
                    is CategoryFilter.KeePassGroupFilter -> true
                    is CategoryFilter.KeePassDatabaseStarred -> true
                    is CategoryFilter.KeePassDatabaseUncategorized -> true
                    is CategoryFilter.Local -> true // Local view shows all local entries
                    is CategoryFilter.LocalOnly -> true
                    is CategoryFilter.LocalStarred -> true
                    is CategoryFilter.LocalUncategorized -> true
                    is CategoryFilter.BitwardenVaultStarred -> true
                    is CategoryFilter.BitwardenVaultUncategorized -> true
                    else -> false
                }
                
                // Smart dedupe is only for non-search "All" view and does not mutate source data.
                val shouldDedupe = query.isBlank() && !isExplicitSourceView && smartDedupe && filter is CategoryFilter.All
                val shouldKeepRawDisplay = query.isNotBlank() || isExplicitSourceView
                
                val filtered = if (shouldDedupe) {
                    dedupeSmart(entries)
                } else {
                    entries
                }
                val exactDeduped = if (shouldKeepRawDisplay) {
                    filtered
                } else {
                    dedupeExactEntries(filtered)
                }
                // 列表只吃密文，不再逐条解密。
                //
                // 静态审计已确认：列表渲染路径（PasswordEntryCard / PasswordListRows /
                // PasswordListContent 等 9 个文件）零处读取 entry.password，点击进详情
                // 也只用 entry.id（明文当场丢弃，详情页自己按 id 查库解密）。
                // 也就是说这里的逐行解密在首屏是完全没人消费的纯浪费：每条都是
                // 「1 次解密 + 1 次加密 + 1 次 apply() 写盘」，100~500 条即 3~5 秒。
                //
                // 对齐官方 Bitwarden / Keyguard：解密只发生在用户点开某一条、或按需管道里，
                // 绝不为了「列表上有没有密码」做全量解密。此处比两者更彻底——它们的
                // cipher 连 name/username 都是加密的，不解密连标题都渲染不出来，
                // 而 Bastion 的展示字段是本地明文列，列表零解密即可渲染。
                //
                // 代价：此前 inspectSecret 顺手做的 Bitwarden 离线缓存预热不再发生，
                // 由 BitwardenViewModel.warmBitwardenOfflineSecretCacheForVault() 兜底。
                if (shouldKeepRawDisplay) {
                    exactDeduped
                } else {
                    filterGhostEntriesForDisplay(exactDeduped)
                }
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
    val passwordEntriesReady: StateFlow<Boolean> = passwordEntriesSource
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    val passwordEntries: StateFlow<List<PasswordEntry>> = passwordEntriesSource
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 与 passwordEntriesSource 同理：列表只吃密文，不做任何解密。
    // Room DAO 生成的 Flow 自带 IO 线程调度，故不再需要 flowOn。
    private val allPasswordsSource: Flow<List<PasswordEntry>> = repository.getAllPasswordEntries()
    val allPasswordsReady: StateFlow<Boolean> = allPasswordsSource
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    val allPasswords: StateFlow<List<PasswordEntry>> = allPasswordsSource
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Lightweight stream for list metadata/lookup use-cases.
    // Keep password blank to avoid redundant decrypt work and avoid exposing ciphertext to UI consumers.
    private val allPasswordsForUiSource: Flow<List<PasswordEntry>> = repository.getAllPasswordEntries()
        .map { entries ->
            entries.map { entry ->
                if (entry.password.isEmpty()) entry else entry.copy(password = "")
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
    val allPasswordsForUiReady: StateFlow<Boolean> = allPasswordsForUiSource
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    val allPasswordsForUi: StateFlow<List<PasswordEntry>> = allPasswordsForUiSource
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Lightweight archive stream for Vault V2. Password contents stay out of list state.
    private val archivedPasswordsForUiSource: Flow<List<PasswordEntry>> = repository.getArchivedEntries()
        .map { entries ->
            entries.map { entry ->
                if (entry.password.isEmpty()) entry else entry.copy(password = "")
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
    val archivedPasswordsForUiReady: StateFlow<Boolean> = archivedPasswordsForUiSource
        .map { true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )
    val archivedPasswordsForUi: StateFlow<List<PasswordEntry>> = archivedPasswordsForUiSource
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val archivedPasswords: StateFlow<List<PasswordEntry>> = repository.getArchivedEntries()
        .map { entries ->
            entries.map { entry ->
                entry.copy(password = inspectSecretState(entry).plainValueOrEmpty())
            }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Smart Deduplication Logic
     * Display-layer dedupe for "All" view:
     * 1) merge same account across sources
     * 2) then keep one entry per unique password value within that account
     */
    private fun dedupeSmart(entries: List<PasswordEntry>): List<PasswordEntry> {
        if (entries.size <= 1) return entries

        val indexById = entries.mapIndexed { index, entry -> entry.id to index }.toMap()
        val accountGroups = entries.groupBy { buildDedupeKey(it) }
        val deduped = mutableListOf<PasswordEntry>()

        for ((_, groupEntries) in accountGroups) {
            if (groupEntries.size <= 1) {
                deduped.addAll(groupEntries)
                continue
            }

            val decrypted = groupEntries.map { entry ->
                entry to runCatchingObserved { securityManager.decryptData(entry.password) }.getOrNull()
            }

            val hasAnyDecrypted = decrypted.any { (_, password) -> password != null }
            if (!hasAnyDecrypted) {
                // When auth/MDK is unavailable, still collapse source-duplicates by account key.
                pickBestEntry(groupEntries)?.let { deduped.add(it) }
                continue
            }

            val knownPasswordBuckets = decrypted
                .filter { (_, password) -> password != null }
                .groupBy(
                    keySelector = { (entry, password) -> "${password.orEmpty()}|totp:${entry.authenticatorKey.isNotBlank()}" },
                    valueTransform = { (entry, _) -> entry }
                )

            for ((_, candidates) in knownPasswordBuckets) {
                pickBestEntry(candidates)?.let { deduped.add(it) }
            }
        }

        return deduped.sortedBy { indexById[it.id] ?: Int.MAX_VALUE }
    }

    private fun applyCategoryFilterInMemory(
        entries: List<PasswordEntry>,
        filter: CategoryFilter
    ): List<PasswordEntry> = PasswordEntryMatching.applyCategoryFilterInMemory(entries, filter)

    /**
     * 判断条目是否「持有可读密码」，**不解密**。
     *
     * 列表流改为只吃密文后，这里只能用「密文是否为空」近似「是否有密码」。
     * 与原先「解密后判空」的语义对照：
     *
     * - MDK 正常：密文非空 ⟺ 有密码，与解密结果一致。
     * - MDK 包装丢失（需重输主密码）：[shouldSkipDecryptAttempt] 为 true，
     *   所有密文都读不出来 —— 与解密路径（全部解成空串）判定一致。
     *
     * 已知的唯一差异：个别条目密文损坏 / 密钥不匹配时，解密路径会得到空串并把它
     * 当幽灵条目过滤掉，本路径会保留它。这属异常数据，让条目可见（密码显示为
     * 不可读）比静默丢失一条更正确。
     */
    private fun hasReadablePassword(entry: PasswordEntry): Boolean {
        if (entry.password.isBlank()) return false
        return !shouldSkipDecryptAttempt()
    }

    private fun filterGhostEntriesForDisplay(entries: List<PasswordEntry>): List<PasswordEntry> {
        if (entries.size <= 1) return entries

        val groups = entries.groupBy { buildGhostGroupKey(it) }
        val ghostIds = mutableSetOf<Long>()

        groups.values.forEach { group ->
            if (group.size <= 1) return@forEach
            if (!group.any { hasReadablePassword(it) }) return@forEach

            group.forEach { entry ->
                val isPasswordMode = entry.loginType.equals("PASSWORD", ignoreCase = true)
                val shouldFilterGhost = !entry.isLocalOnlyEntry() || entry.hasOwnershipConflict()
                // 仅当密码为空「且」没有任何 TOTP 绑定时才视为幽灵条目过滤；
                // 只绑定了 TOTP 验证器（密码为空）的条目必须保留，否则 TOTP 会消失。
                if (isPasswordMode && !hasReadablePassword(entry) && entry.authenticatorKey.isBlank() && shouldFilterGhost) {
                    ghostIds += entry.id
                }
            }
        }

        if (ghostIds.isEmpty()) return entries
        return entries.filterNot { it.id in ghostIds }
    }

    /**
     * Collapse exact duplicated entries caused by repeated sync/import records.
     * We keep one best row for each identical source+account+password signature.
     */
    private fun dedupeExactEntries(entries: List<PasswordEntry>): List<PasswordEntry> {
        if (entries.size <= 1) return entries

        val indexById = entries.mapIndexed { index, entry -> entry.id to index }.toMap()
        val deduped = entries
            .groupBy { buildExactDisplayKey(it) }
            .values
            .mapNotNull { pickBestEntry(it) }

        return deduped.sortedBy { indexById[it.id] ?: Int.MAX_VALUE }
    }

    private fun buildExactDisplayKey(entry: PasswordEntry): String {
        val sourceKey = when (val ownership = entry.resolveOwnership()) {
            is PasswordOwnership.KeePass -> "kp:${ownership.databaseId}:${entry.keepassEntryUuid.orEmpty()}:${entry.keepassGroupPath.orEmpty()}"
            is PasswordOwnership.Bitwarden -> "bw:${ownership.vaultId}:${entry.bitwardenCipherId.orEmpty()}:${entry.bitwardenFolderId.orEmpty()}"
            is PasswordOwnership.Conflict -> "conflict:${entry.keepassDatabaseId}:${entry.bitwardenVaultId}:${entry.keepassEntryUuid.orEmpty()}:${entry.bitwardenCipherId.orEmpty()}"
            PasswordOwnership.BastionLocal -> "local:${entry.categoryId ?: -1}"
        }

        return listOf(
            sourceKey,
            normalizeComparableText(entry.title),
            normalizeComparableText(entry.username),
            normalizeWebsiteForGhostGrouping(entry.website),
            entry.password
        ).joinToString("|")
    }

    private fun buildGhostGroupKey(entry: PasswordEntry): String {
        val sourceKey = when (val ownership = entry.resolveOwnership()) {
            is PasswordOwnership.Conflict ->
                "conflict:${entry.keepassDatabaseId}:${entry.bitwardenVaultId}:${entry.keepassEntryUuid.orEmpty()}:${entry.bitwardenCipherId.orEmpty()}"
            is PasswordOwnership.Bitwarden ->
                if (!entry.bitwardenCipherId.isNullOrBlank()) {
                    "bw:${ownership.vaultId}:${entry.bitwardenCipherId}"
                } else {
                    "bw-local:${ownership.vaultId}:${entry.bitwardenFolderId.orEmpty()}"
                }
            is PasswordOwnership.KeePass ->
                "kp:${ownership.databaseId}:${entry.keepassGroupPath.orEmpty()}"
            PasswordOwnership.BastionLocal -> "local"
        }

        val title = normalizeComparableText(entry.title)
        val username = normalizeComparableText(entry.username)
        val website = normalizeWebsiteForGhostGrouping(entry.website)
        return "$sourceKey|$title|$website|$username"
    }

    // ── 以下为 PasswordEntryMatching 的薄委托（B.3 集群 5a）────────────────
    // 实现已搬迁到同包的 PasswordEntryMatching.kt（纯函数、无状态）。
    // 此处保留同名私有函数作为委托，使全部既有调用点的文本形态保持不变。

    private fun normalizeWebsiteForGhostGrouping(value: String): String =
        PasswordEntryMatching.normalizeWebsiteForGhostGrouping(value)

    private fun pickBestEntry(candidates: List<PasswordEntry>): PasswordEntry? =
        PasswordEntryMatching.pickBestEntry(candidates)

    private fun filterLocalOnlyComparedToBitwarden(entries: List<PasswordEntry>): List<PasswordEntry> =
        PasswordEntryMatching.filterLocalOnlyComparedToBitwarden(entries)

    private fun normalizeComparableText(value: String): String =
        PasswordEntryMatching.normalizeComparableText(value)

    private fun matchesSearchQuery(entry: PasswordEntry, query: String): Boolean =
        PasswordEntryMatching.matchesSearchQuery(entry, query)

    private fun extractComparableDomain(value: String): String =
        PasswordEntryMatching.extractComparableDomain(value)

    private fun buildDedupeKey(entry: PasswordEntry): String =
        PasswordEntryMatching.buildDedupeKey(entry)

    private fun normalizeDedupeText(value: String): String =
        PasswordEntryMatching.normalizeDedupeText(value)

    private fun normalizeWebsiteForDedupe(value: String): String =
        PasswordEntryMatching.normalizeWebsiteForDedupe(value)

    private fun decryptForDisplay(encryptedPassword: String): String {
        return decodePasswordOrNull(encryptedPassword).orEmpty()
    }

    fun inspectSecretState(entry: PasswordEntry): SecretValueState {
        return passwordProviderRegistry.inspectSecret(entry)
    }

    fun hasOwnershipConflict(entry: PasswordEntry): Boolean = entry.hasOwnershipConflict()

    suspend fun getRawPasswordEntryById(id: Long): PasswordEntry? {
        val entry = repository.getPasswordEntryById(id) ?: return null
        return normalizeLegacyOwnershipMetadata(entry)
    }

    suspend fun getRawActivePasswordEntries(): List<PasswordEntry> {
        val entries = repository.getAllPasswordEntries().first()
        val normalizedEntries = ArrayList<PasswordEntry>(entries.size)
        entries.forEach { entry ->
            normalizedEntries += normalizeLegacyOwnershipMetadata(entry)
        }
        return normalizedEntries
    }

    suspend fun getSecretValueStates(ids: List<Long>): Map<Long, SecretValueState> {
        return repository.getPasswordsByIds(ids).associate { entry ->
            entry.id to inspectSecretState(entry)
        }
    }

    /**
     * 生成器历史「是否已存进密码库」的批量判定。
     *
     * 列表流改为只吃密文后，`entry.password` 是密文、永远不等于明文，
     * 原先 `entry.password == historyItem.password` 的判重会静默失效，
     * 因此改为**按需解密**后比较明文。
     *
     * 这里是**一次批量查询**而非每条历史各查一遍：历史可能有几十条，
     * 逐条触发全表扫描会把 O(n) 放大成 O(n×m)。先用 website / packageName
     * 建索引筛出候选，再对候选解密，且同一条目只解一次
     * （[inspectSecretState] 对 Bitwarden 条目带加密 + 写盘副作用，去重是顺带的收益）。
     *
     * 仅由生成器历史这种用户主动触达的页面调用，**不在列表首屏路径上**。
     *
     * @return 已存在于密码库的历史记录 timestamp 集合
     */
    suspend fun findAlreadySavedHistoryTimestamps(
        historyItems: List<PasswordGenerationHistory>
    ): Set<Long> = withContext(Dispatchers.Default) {
        if (historyItems.isEmpty()) return@withContext emptySet()
        val entries = repository.getAllPasswordEntries().first()
        if (entries.isEmpty()) return@withContext emptySet()

        val byWebsite = entries.groupBy { it.website }
        val byPackage = entries.groupBy { it.appPackageName }
        val plainCache = HashMap<Long, String>()
        val savedTimestamps = HashSet<Long>()

        historyItems.forEach { item ->
            val candidates = LinkedHashSet<PasswordEntry>().apply {
                byWebsite[item.domain]?.let { addAll(it) }
                byPackage[item.packageName]?.let { addAll(it) }
            }
            val hit = candidates.any { entry ->
                val plain = plainCache.getOrPut(entry.id) {
                    inspectSecretState(entry).plainValueOrEmpty()
                }
                plain == item.password
            }
            if (hit) savedTimestamps += item.timestamp
        }
        savedTimestamps
    }

    suspend fun getBitwardenVaultCacheRiskSummary(vaultId: Long): BitwardenRepository.VaultCacheRiskSummary {
        val repositoryInstance = bitwardenRepository
            ?: throw IllegalStateException("Bitwarden repository unavailable")
        return repositoryInstance.getVaultCacheRiskSummary(vaultId)
    }

    suspend fun clearBitwardenVaultLocalCache(
        vaultId: Long,
        mode: BitwardenRepository.CacheClearMode
    ): BitwardenRepository.CacheClearResult {
        val repositoryInstance = bitwardenRepository
            ?: throw IllegalStateException("Bitwarden repository unavailable")
        val beforeEntryIds = repositoryInstance.getPasswordEntries(vaultId)
            .mapTo(linkedSetOf()) { it.id }
        val result = repositoryInstance.clearVaultLocalCache(vaultId, mode)
        if (beforeEntryIds.isNotEmpty()) {
            val afterEntryIds = repositoryInstance.getPasswordEntries(vaultId)
                .asSequence()
                .map { it.id }
                .toHashSet()
            beforeEntryIds
                .filterNot(afterEntryIds::contains)
                .forEach { entryId ->
                    bitwardenOfflineSecretCacheFacade.clear(entryId)
                }
        }
        return result
    }

    private fun decodePasswordOrNull(rawPassword: String): String? {
        if (rawPassword.isEmpty()) return ""
        if (shouldSkipDecryptAttempt()) {
            // 注定解密失败且会被判成「需要重新认证」的状态：整体短路，避免逐条触发
            // forceVaultReauthentication 把会话击穿（详见 shouldSkipDecryptAttempt 注释）。
            if (!hasLoggedDecryptAuthStateWarning) {
                Log.w(
                    "PasswordViewModel",
                    "Skip decrypt: MDK wrapper needs password re-entry"
                )
                hasLoggedDecryptAuthStateWarning = true
            }
            return null
        }
        return try {
            unwrapPasswordLayersForDisplay(rawPassword)
        } catch (error: Exception) {
            val forcedReauth = securityManager.handleVaultDecryptFailure(error)
            if (forcedReauth) {
                _isAuthenticated.value = false
            }
            if (!hasLoggedDecryptAuthStateWarning) {
                Log.w(
                    "PasswordViewModel",
                    "Skip decrypt due to auth/key state: ${error.message}, forcedReauth=$forcedReauth"
                )
                hasLoggedDecryptAuthStateWarning = true
            }
            null
        }
    }

    private fun loadBitwardenOfflineCachedSecret(entry: PasswordEntry): String? {
        return bitwardenOfflineSecretCacheFacade.recall(entry)
    }

    private fun rememberBitwardenOfflineCachedSecret(entry: PasswordEntry, plainSecret: String) {
        bitwardenOfflineSecretCacheFacade.remember(entry, plainSecret)
    }

    suspend fun clearBitwardenOfflineSecretCacheForVault(vaultId: Long): Int {
        if (!bitwardenOfflineSecretCacheFacade.isAvailable()) return 0
        val entries = bitwardenRepository?.getPasswordEntries(vaultId).orEmpty()
        bitwardenOfflineSecretCacheFacade.clearAll(entries.map { entry -> entry.id })
        return entries.size
    }

    /**
     * 冷启动解密门控。
     *
     * 与 [SecurityManager.shouldForceVaultReauthenticationAfterDecryptFailure] 的最后一条
     * 判定同构：MDK 的 Keystore 包装已丢失（需要用户重输主密码）时，任何解密都注定失败，
     * 且失败会被判成「需要重新认证」。逐条尝试（历史批量预热 / 批量校验）会把会话反复击穿，
     * 因此这里整体短路，不产生任何失败副作用。UI 侧的重新认证提示走
     * [SecurityManager.getVaultAccessState] → REQUIRES_PASSWORD_REENTRY，不依赖本路径。
     *
     * 注意：本判定不要求 isVaultRuntimeUnlocked()。首次成功解密本身才会把 MDK 解包进进程
     * 缓存，若要求解锁态会造成「未解锁 → 不能解密 → 永远解锁不了」的死锁。
     */
    private fun shouldSkipDecryptAttempt(): Boolean {
        return securityManager.requiresPasswordReentryForWrapperRebuild()
    }

    private suspend fun repairLegacyDetachedKeePassEntries(entries: List<PasswordEntry>) {
        val staleIds = mutableListOf<Long>()
        entries.forEach { entry ->
            if (isLegacyDetachedKeePassEntry(entry)) {
                staleIds += entry.id
            }
        }
        if (staleIds.isEmpty()) return

        repository.updateKeePassDatabaseForPasswords(staleIds, null)
        Log.i(
            "PasswordViewModel",
            "Detached legacy KeePass-local password bindings: count=${staleIds.size}"
        )
    }

    private suspend fun repairLegacyOwnershipConflicts(entries: List<PasswordEntry>) {
        var repairedCount = 0

        entries.forEach { entry ->
            if (!entry.hasOwnershipConflict()) return@forEach
            val normalized = normalizeLegacyOwnershipConflictEntry(entry)
            if (normalized != entry) {
                repairedCount++
            }
        }

        if (repairedCount > 0) {
            Log.i(
                "PasswordViewModel",
                "Repaired legacy ownership conflicts: count=$repairedCount"
            )
        }
    }

    private suspend fun normalizeLegacyOwnershipMetadata(entry: PasswordEntry): PasswordEntry {
        val keePassNormalized = normalizeLegacyDetachedKeePassEntry(entry)
        return normalizeLegacyOwnershipConflictEntry(keePassNormalized)
    }

    private suspend fun normalizeLegacyOwnershipConflictEntry(entry: PasswordEntry): PasswordEntry {
        if (!entry.hasOwnershipConflict()) return entry

        val hasKeePassIdentity =
            !entry.keepassEntryUuid.isNullOrBlank() ||
                !entry.keepassGroupUuid.isNullOrBlank() ||
                !entry.keepassGroupPath.isNullOrBlank()

        val hasBitwardenIdentity =
            !entry.bitwardenCipherId.isNullOrBlank() ||
                !entry.bitwardenRevisionDate.isNullOrBlank() ||
                entry.bitwardenLocalModified

        val normalized = when {
            hasBitwardenIdentity && !hasKeePassIdentity -> entry.clearKeePassBindingOnly()
            hasKeePassIdentity && !hasBitwardenIdentity -> entry.clearBitwardenBindingOnly()
            !hasKeePassIdentity && !hasBitwardenIdentity &&
                entry.keepassGroupPath.isNullOrBlank() &&
                entry.bitwardenFolderId.isNullOrBlank() -> {
                entry.clearKeePassBindingOnly().clearBitwardenBindingOnly()
            }

            else -> entry
        }

        if (normalized == entry) return entry

        repository.updatePasswordEntry(normalized)
        return repository.getPasswordEntryById(entry.id) ?: normalized
    }

    private fun PasswordEntry.clearKeePassBindingOnly(): PasswordEntry {
        return copy(
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null
        )
    }

    private fun PasswordEntry.clearBitwardenBindingOnly(): PasswordEntry {
        return copy(
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false
        )
    }

    private suspend fun normalizeLegacyDetachedKeePassEntry(entry: PasswordEntry): PasswordEntry {
        if (!isLegacyDetachedKeePassEntry(entry)) return entry
        repository.updateKeePassDatabaseForPasswords(listOf(entry.id), null)
        return repository.getPasswordEntryById(entry.id) ?: entry.copy(
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null
        )
    }

    private suspend fun isLegacyDetachedKeePassEntry(entry: PasswordEntry): Boolean {
        val keepassDatabaseId = entry.keepassDatabaseId ?: return false
        if (entry.bitwardenVaultId != null || !entry.bitwardenCipherId.isNullOrBlank()) return false
        if (entry.categoryId != null) return true

        val keepassDatabaseExists = localKeePassDatabaseDao
            ?.getDatabaseById(keepassDatabaseId) != null
        if (keepassDatabaseExists) return false

        return entry.keepassEntryUuid.isNullOrBlank() && entry.keepassGroupUuid.isNullOrBlank()
    }

    private fun shouldPreserveUnreadableBitwardenPassword(
        existing: PasswordEntry?,
        incomingPassword: String
    ): Boolean {
        if (existing == null || incomingPassword.isNotEmpty()) return false
        if (existing.bitwardenVaultId == null || existing.bitwardenCipherId.isNullOrBlank()) return false
        if (existing.password.isBlank()) return false
        val secretState = inspectSecretState(existing)
        return secretState is SecretValueState.Unreadable && secretState.source is PasswordSource.Bitwarden
    }

    private fun resolvePasswordForUpdate(
        existing: PasswordEntry?,
        pendingEntry: PasswordEntry,
        incomingPassword: String
    ): String {
        return passwordProviderRegistry.resolvePasswordForStorage(
            existingEntry = existing,
            pendingEntry = pendingEntry,
            incomingPassword = incomingPassword
        )
    }

    /**
     * Historical data may contain nested encrypted payloads (ciphertext saved as plaintext, then encrypted again).
     * Try a few rounds and stop once value is stable.
     */
    private fun unwrapPasswordLayersForDisplay(value: String): String {
        var current = value
        repeat(3) { round ->
            // 第 1 轮无条件执行：历史遗留的 V1 密文没有 MDK|/V2|/C2| 前缀，不能用
            // 「长得像不像密文」判断，否则会漏解老数据。
            // 第 2 轮起先做前缀快筛：只有仍是 Bastion 密文才继续解下一层。绝大多数条目
            // 只有一层，少了这个判断每个条目都会多做 1~2 次注定原样返回的 decryptData，
            // 而每次 decryptData 都可能碰一次 Keystore。
            if (round > 0 && !securityManager.looksLikeBastionCiphertext(current)) {
                return current
            }
            val decrypted = synchronized(decryptLock) {
                securityManager.decryptData(current)
            }
            if (decrypted == current) return current
            current = decrypted
        }
        return current
    }

    private fun syncKeePassDatabase(databaseId: Long, forceRefresh: Boolean = false) {
        val requestId = SyncDiagnostics.nextTaskId("kp-password")
        viewModelScope.launch {
            SyncTaskRunner.request(
                request = SyncRequest(
                    requestId = requestId,
                    target = SyncTarget.KeePassCompatibilityIndex(
                        databaseId = databaseId,
                        itemTypes = setOf(SyncItemKind.PASSWORD, SyncItemKind.TOTP)
                    ),
                    trigger = if (forceRefresh) SyncTrigger.MANUAL else SyncTrigger.PAGE_VISIBLE,
                    createdAtMillis = System.currentTimeMillis(),
                    priority = if (forceRefresh) SyncPriority.MANUAL else SyncPriority.PAGE_VISIBLE,
                    mode = if (forceRefresh) SyncMode.FOREGROUND else SyncMode.SILENT,
                    throttleMs = if (forceRefresh) 0L else 30_000L
                )
            ) {
                syncKeePassDatabaseNow(
                    databaseId = databaseId,
                    forceRefresh = forceRefresh,
                    taskId = requestId
                )
            }
        }
    }

    private suspend fun syncKeePassDatabaseNow(
        databaseId: Long,
        forceRefresh: Boolean,
        taskId: String
    ) {
        val target = "keepass:password:$databaseId"
        val trigger = if (forceRefresh) "PASSWORD_MANUAL_REFRESH" else "PASSWORD_FILTER_ENTER"
        SyncDiagnostics.queued(taskId, target, trigger, detail = "forceRefresh=$forceRefresh")
        val bridge = keepassBridge ?: run {
            SyncDiagnostics.skipped(taskId, target, trigger, "bridge_unavailable", detail = "forceRefresh=$forceRefresh")
            return
        }
        val startedAt = SyncDiagnostics.start(taskId, target, trigger, detail = "forceRefresh=$forceRefresh")
        try {
            if (forceRefresh) {
                bridge.syncLegacyRemoteDatabase(databaseId)
                    .onFailure { error ->
                        Log.w("PasswordViewModel", "KeePass remote refresh failed before projection for databaseId=$databaseId", error)
                    }
                KeePassKdbxService.invalidateProcessCache(databaseId)
            }
            val snapshot = bridge
                .loadLegacyWorkspace(databaseId, allowedSecureItemTypes = setOf(ItemType.TOTP))
                .getOrNull()
                ?: run {
                    SyncDiagnostics.skipped(taskId, target, trigger, "workspace_unavailable", startedAt)
                    return
                }
            upsertKeePassEntries(databaseId, snapshot.passwords)
            syncKeePassTotpEntries(databaseId, snapshot.secureItems)
            SyncDiagnostics.success(
                taskId = taskId,
                target = target,
                trigger = trigger,
                startedAt = startedAt,
                detail = "passwords=${snapshot.passwords.size} secureItems=${snapshot.secureItems.size}"
            )
        } catch (error: Exception) {
            SyncDiagnostics.failed(taskId, target, trigger, startedAt, error)
            Log.w("PasswordViewModel", "KeePass sync failed for databaseId=$databaseId", error)
            throw error
        }
    }

    private suspend fun refreshAllKeePassDatabases() {
        val dao = localKeePassDatabaseDao ?: return
        dao.getAllDatabasesSync().forEach { database ->
            syncKeePassDatabase(database.id, forceRefresh = false)
        }
    }

    fun refreshKeePassFromSourceForCurrentContext() {
        val current = _categoryFilter.value
        val activeDatabaseId = when (current) {
            is CategoryFilter.KeePassDatabase -> current.databaseId
            is CategoryFilter.KeePassGroupFilter -> current.databaseId
            is CategoryFilter.KeePassDatabaseStarred -> current.databaseId
            is CategoryFilter.KeePassDatabaseUncategorized -> current.databaseId
            else -> null
        }
        val resolvedDatabaseId = activeDatabaseId ?: return
        syncKeePassDatabase(resolvedDatabaseId, forceRefresh = true)
    }

    fun syncKeePassDatabaseForVisibleVault(databaseId: Long, forceRefresh: Boolean = false) {
        KeePassKdbxService.markDatabaseActive(databaseId)
        syncKeePassDatabase(databaseId, forceRefresh = forceRefresh)
    }

    private suspend fun upsertKeePassEntries(databaseId: Long, entries: List<KeePassEntryData>) {
        val incomingEntries = entries.filter { shouldImportKeePassPasswordEntry(it) }
        val activeBefore = repository.getPasswordEntriesByKeePassDatabaseSync(databaseId).size
        val recycleIncomingCount = incomingEntries.count { it.isInRecycleBin }
        val incomingKeys = incomingEntries
            .asSequence()
            .map { buildKeePassSyncKey(it) }
            .toSet()
        Log.i(
            "PasswordViewModel",
            "KeePass password upsert begin: databaseId=$databaseId, " +
                "raw=${entries.size}, importable=${incomingEntries.size}, " +
                "incomingRecycle=$recycleIncomingCount, activeBefore=$activeBefore, " +
                "uniqueKeys=${incomingKeys.size}"
        )

        incomingEntries.forEach { item ->
            val hasStableKeePassUuid = !item.entryUuid.isNullOrBlank()
            val isRemoteConflictReplica = isRemoteConflictReplicaTitle(item.title)
            val existingByUuid = item.entryUuid
                ?.takeIf { it.isNotBlank() }
                ?.let { repository.getPasswordEntryByKeePassUuid(databaseId, it) }
            val existingById = if (isRemoteConflictReplica) {
                null
            } else {
                item.bastionLocalId?.let { repository.getPasswordEntryById(it) }
            }
            val existing = when {
                existingByUuid != null -> existingByUuid
                existingById != null && existingById.keepassDatabaseId == databaseId -> existingById
                hasStableKeePassUuid -> null
                else -> repository.getDuplicateEntryInKeePass(
                    databaseId = databaseId,
                    title = item.title,
                    username = item.username,
                    website = item.url,
                    groupPath = item.groupPath
                )
            }
            val normalizedPassword = normalizeIncomingKeePassPassword(item.password)
            val existingPlainPassword = existing?.let { decryptForDisplay(it.password) }.orEmpty()
            val encryptedPassword = if (existing != null && normalizedPassword.isBlank()) {
                if (existingPlainPassword.isNotBlank()) {
                    Log.w(
                        "PasswordViewModel",
                        "Skip KeePass blank-password overwrite for entryId=${existing.id}, title=${existing.title}"
                    )
                    existing.password
                } else {
                    securityManager.encryptData(normalizedPassword)
                }
            } else {
                securityManager.encryptData(normalizedPassword)
            }
            val importedPlainPassword = if (existing != null && encryptedPassword == existing.password) {
                existingPlainPassword
            } else {
                normalizedPassword
            }
            if (existing != null) {
                val isInRecycleBin = item.isInRecycleBin
                val updated = existing.copy(
                    title = item.title,
                    username = item.username,
                    password = encryptedPassword,
                    website = item.url,
                    notes = item.notes,
                    appPackageName = item.appPackageName,
                    appName = item.appName,
                    email = item.email,
                    phone = item.phone,
                    addressLine = item.addressLine,
                    city = item.city,
                    state = item.state,
                    zipCode = item.zipCode,
                    country = item.country,
                    creditCardNumber = item.creditCardNumber,
                    creditCardHolder = item.creditCardHolder,
                    creditCardExpiry = item.creditCardExpiry,
                    creditCardCVV = item.creditCardCVV,
                    keepassDatabaseId = databaseId,
                    keepassGroupPath = item.groupPath,
                    keepassEntryUuid = item.entryUuid,
                    keepassGroupUuid = item.groupUuid,
                    sshKeyData = item.sshKeyData,
                    loginType = item.loginType,
                    ssoProvider = item.ssoProvider,
                    ssoRefEntryId = item.ssoRefEntryId,
                    wifiMetadata = item.wifiMetadata,
                    isDeleted = isInRecycleBin,
                    deletedAt = if (isInRecycleBin) (existing.deletedAt ?: Date()) else null,
                    updatedAt = Date()
                )
                if (!existing.matchesKeePassImport(updated, importedPlainPassword)) {
                    repository.updatePasswordEntry(updated)
                }
                saveKeePassCustomFields(existing.id, item)
            } else {
                val isInRecycleBin = item.isInRecycleBin
                val newEntry = PasswordEntry(
                    title = item.title,
                    username = item.username,
                    password = encryptedPassword,
                    website = item.url,
                    notes = item.notes,
                    appPackageName = item.appPackageName,
                    appName = item.appName,
                    email = item.email,
                    phone = item.phone,
                    addressLine = item.addressLine,
                    city = item.city,
                    state = item.state,
                    zipCode = item.zipCode,
                    country = item.country,
                    creditCardNumber = item.creditCardNumber,
                    creditCardHolder = item.creditCardHolder,
                    creditCardExpiry = item.creditCardExpiry,
                    creditCardCVV = item.creditCardCVV,
                    createdAt = Date(),
                    updatedAt = Date(),
                    keepassDatabaseId = databaseId,
                    keepassGroupPath = item.groupPath,
                    keepassEntryUuid = item.entryUuid,
                    keepassGroupUuid = item.groupUuid,
                    sshKeyData = item.sshKeyData,
                    loginType = item.loginType,
                    ssoProvider = item.ssoProvider,
                    ssoRefEntryId = item.ssoRefEntryId,
                    wifiMetadata = item.wifiMetadata,
                    isDeleted = isInRecycleBin,
                    deletedAt = if (isInRecycleBin) Date() else null
                )
                val insertedId = repository.insertPasswordEntry(newEntry)
                saveKeePassCustomFields(insertedId, item)
            }
        }

        val staleCount = reconcileKeePassEntries(databaseId, incomingKeys)
        val activeAfter = repository.getPasswordEntriesByKeePassDatabaseSync(databaseId).size
        Log.i(
            "PasswordViewModel",
            "KeePass password upsert end: databaseId=$databaseId, " +
                "raw=${entries.size}, importable=${incomingEntries.size}, " +
                "incomingRecycle=$recycleIncomingCount, staleRemoved=$staleCount, " +
                "activeBefore=$activeBefore, activeAfter=$activeAfter"
        )
    }

    private fun PasswordEntry.matchesKeePassImport(
        imported: PasswordEntry,
        importedPlainPassword: String
    ): Boolean {
        return copy(password = "", updatedAt = imported.updatedAt) ==
            imported.copy(password = "") &&
            decryptForDisplay(password) == importedPlainPassword
    }

    private suspend fun saveKeePassCustomFields(entryId: Long, item: KeePassEntryData) {
        val fieldRepository = customFieldRepository ?: return
        val fields = item.customFields.map { field ->
            CustomField(
                entryId = entryId,
                title = field.title,
                value = field.value,
                isProtected = field.isProtected,
                sortOrder = field.sortOrder
            )
        }
        if (fieldRepository.getFieldsByEntryIdSync(entryId).matchesKeePassCustomFields(fields)) {
            return
        }
        fieldRepository.saveFieldsForEntries(mapOf(entryId to fields))
    }

    private fun List<CustomField>.matchesKeePassCustomFields(imported: List<CustomField>): Boolean {
        return toKeePassCustomFieldFingerprints() == imported.toKeePassCustomFieldFingerprints()
    }

    private fun List<CustomField>.toKeePassCustomFieldFingerprints(): List<KeePassCustomFieldFingerprint> {
        return mapIndexed { index, field ->
            KeePassCustomFieldFingerprint(
                title = field.title,
                value = field.value,
                isProtected = field.isProtected,
                sortOrder = index
            )
        }
    }


    private suspend fun resolveKeePassCustomFieldsForSync(
        entryId: Long,
        customFieldsOverride: List<CustomFieldDraft>?
    ): List<KeePassCustomFieldData> {
        customFieldsOverride?.let { drafts ->
            return drafts
                .filter { it.shouldPersist() }
                .mapIndexed { index, field ->
                    KeePassCustomFieldData(
                        title = field.title,
                        value = field.value,
                        isProtected = field.isProtected,
                        sortOrder = index
                    )
                }
        }

        if (entryId <= 0) return emptyList()
        val fieldRepository = customFieldRepository ?: return emptyList()
        return fieldRepository.getFieldsByEntryIdSync(entryId)
            .filter { it.title.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy<CustomField> { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, field ->
                KeePassCustomFieldData(
                    title = field.title,
                    value = field.value,
                    isProtected = field.isProtected,
                    sortOrder = index
                )
            }
    }

    private suspend fun syncKeePassTotpEntries(
        databaseId: Long,
        snapshots: List<KeePassSecureItemData>? = null
    ) {
        val secureRepo = secureItemRepository ?: return

        val resolvedSnapshots = snapshots ?: keepassBridge
            ?.readLegacySecureItems(databaseId, setOf(ItemType.TOTP))
            ?.getOrNull()
            ?: return

        val existingTotp = secureRepo.getItemsByType(ItemType.TOTP).first()
        resolvedSnapshots.forEach { snapshot ->
            val incoming = snapshot.item
            val existingByUuid = incoming.keepassEntryUuid
                ?.takeIf { it.isNotBlank() }
                ?.let { entryUuid -> secureRepo.getItemByKeePassUuid(databaseId, entryUuid) }
            val existingBySource = snapshot.sourceBastionId
                ?.takeIf { it > 0 }
                ?.let { sourceId -> secureRepo.getItemById(sourceId) }
                ?.takeIf { it.itemType == ItemType.TOTP }
            val incomingIdentityKey = parseStoredTotpData(incoming)?.let(::buildTotpCopyIdentityKey)
            val existing = KeePassTotpProjectionMatcher.findExistingProjection(
                databaseId = databaseId,
                incoming = incoming,
                existingTotp = existingTotp,
                existingByUuid = existingByUuid,
                existingBySource = existingBySource,
                incomingIdentityKey = incomingIdentityKey
            ) { candidate ->
                parseStoredTotpData(candidate)?.let(::buildTotpCopyIdentityKey)
            }

                if (existing == null) {
                    secureRepo.insertItem(incoming)
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
                        secureRepo.updateItem(updated)
                    }
                }
        }
    }

    private fun SecureItem.matchesKeePassSecureItemImport(imported: SecureItem): Boolean {
        return copy(itemData = "", updatedAt = imported.updatedAt) == imported.copy(itemData = "") &&
            decryptStoredSensitiveValue(itemData) == decryptStoredSensitiveValue(imported.itemData)
    }

    private fun normalizeIncomingKeePassPassword(raw: String): String {
        if (raw.isBlank()) return raw
        var current = raw
        repeat(3) {
            val decrypted = runCatchingObserved {
                synchronized(decryptLock) {
                    securityManager.decryptData(current)
                }
            }.getOrNull() ?: return current
            if (decrypted == current) return current
            current = decrypted
        }
        return current
    }

    private fun shouldImportKeePassPasswordEntry(item: KeePassEntryData): Boolean {
        // KeePass 纯模板条目已在解析层过滤。
        // 这里保留“只有标题”的真实条目，避免误伤用户手工维护的极简记录。
        return item.title.isNotBlank() ||
            item.username.isNotBlank() ||
            item.password.isNotBlank() ||
            item.url.isNotBlank() ||
            item.notes.isNotBlank()
    }

    private fun isRemoteConflictReplicaTitle(title: String): Boolean {
        return title.contains("[远端冲突副本]")
    }

    private fun buildKeePassSyncKey(
        title: String,
        username: String,
        website: String,
        groupPath: String?
    ): String {
        val normalizedTitle = title.trim().lowercase(Locale.ROOT)
        val normalizedUsername = username.trim().lowercase(Locale.ROOT)
        val normalizedWebsite = normalizeWebsiteForDedupe(website)
        val normalizedGroup = groupPath?.trim().orEmpty()
        return "$normalizedGroup|$normalizedTitle|$normalizedUsername|$normalizedWebsite"
    }

    private fun buildKeePassSyncKey(item: KeePassEntryData): String {
        val entryUuid = item.entryUuid?.trim().orEmpty()
        if (entryUuid.isNotEmpty()) {
            return "uuid:${entryUuid.lowercase(Locale.ROOT)}"
        }
        return buildKeePassSyncKey(item.title, item.username, item.url, item.groupPath)
    }

    private fun buildKeePassSyncKey(entry: PasswordEntry): String {
        val entryUuid = entry.keepassEntryUuid?.trim().orEmpty()
        if (entryUuid.isNotEmpty()) {
            return "uuid:${entryUuid.lowercase(Locale.ROOT)}"
        }
        return buildKeePassSyncKey(entry.title, entry.username, entry.website, entry.keepassGroupPath)
    }

    private suspend fun reconcileKeePassEntries(databaseId: Long, incomingKeys: Set<String>): Int {
        val localEntries = repository.getPasswordEntriesByKeePassDatabaseSync(databaseId)
        if (localEntries.isEmpty()) return 0

        val grouped = localEntries.groupBy { entry -> buildKeePassSyncKey(entry) }

        val keepIds = mutableSetOf<Long>()
        grouped.forEach { (key, candidates) ->
            if (key !in incomingKeys) return@forEach
            val keep = candidates.maxWithOrNull(
                compareBy<PasswordEntry> { if (decryptForDisplay(it.password).isNotBlank()) 1 else 0 }
                    .thenBy { it.updatedAt.time }
                    .thenBy { it.id }
            ) ?: candidates.first()
            keepIds += keep.id
        }

        val stale = localEntries.filter { entry ->
            val key = buildKeePassSyncKey(entry)
            key !in incomingKeys || entry.id !in keepIds
        }

        stale.forEach { repository.deletePasswordEntry(it) }
        if (stale.isNotEmpty()) {
            Log.i(
                "PasswordViewModel",
                "KeePass password reconcile removed stale active rows: databaseId=$databaseId, " +
                    "localActive=${localEntries.size}, incomingKeys=${incomingKeys.size}, stale=${stale.size}"
            )
        }
        return stale.size
    }
    
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategoryFilter(filter: CategoryFilter) {
        if (filter is CategoryFilter.Archived) {
            openArchiveView()
            return
        }
        archiveFilterController.clear()
        applyCategoryFilter(filter, persist = true)
    }

    fun openArchiveView() {
        val archiveFilter = archiveFilterController.open(_categoryFilter.value)
        applyCategoryFilter(archiveFilter, persist = false)
    }

    fun closeArchiveView() {
        applyCategoryFilter(archiveFilterController.close(), persist = true)
    }

    private fun applyCategoryFilter(filter: CategoryFilter, persist: Boolean) {
        _categoryFilter.value = filter
        if (persist) {
            persistCategoryFilter(filter)
        }
        when (filter) {
            is CategoryFilter.KeePassDatabase -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassDatabase(filter.databaseId)
            }
            is CategoryFilter.KeePassGroupFilter -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassDatabase(filter.databaseId)
            }
            is CategoryFilter.KeePassDatabaseStarred -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassDatabase(filter.databaseId)
            }
            is CategoryFilter.KeePassDatabaseUncategorized -> {
                KeePassKdbxService.markDatabaseActive(filter.databaseId)
                syncKeePassDatabase(filter.databaseId)
            }
            else -> KeePassKdbxService.trimInactiveCaches()
        }
    }

    private fun restoreLastCategoryFilter() {
        val manager = settingsManager ?: return
        viewModelScope.launch {
            runCatchingObserved { manager.settingsFlow.first() }
                .onSuccess { settings ->
                    if (_categoryFilter.value !is CategoryFilter.All) return@onSuccess
                    val restoredFilter = decodeSavedCategoryFilter(settings)
                    val sanitizedFilter = sanitizeRestoredCategoryFilter(restoredFilter)
                    if (sanitizedFilter != restoredFilter) {
                        applyCategoryFilter(CategoryFilter.All, persist = true)
                    } else {
                        applyCategoryFilter(sanitizedFilter, persist = false)
                    }
                }
                .onFailure { error ->
                    Log.w("PasswordViewModel", "Failed to restore last category filter", error)
                }
        }
    }

    private suspend fun sanitizeRestoredCategoryFilter(filter: CategoryFilter): CategoryFilter {
        if (filter is CategoryFilter.Custom) {
            return if (repository.getCategoryById(filter.categoryId) == null) {
                CategoryFilter.All
            } else {
                filter
            }
        }

        val keepassDatabaseId = when (filter) {
            is CategoryFilter.KeePassDatabase -> filter.databaseId
            is CategoryFilter.KeePassGroupFilter -> filter.databaseId
            is CategoryFilter.KeePassDatabaseStarred -> filter.databaseId
            is CategoryFilter.KeePassDatabaseUncategorized -> filter.databaseId
            else -> null
        } ?: return filter

        val dao = localKeePassDatabaseDao ?: return CategoryFilter.All
        return if (dao.getDatabaseById(keepassDatabaseId) == null) CategoryFilter.All else filter
    }

    private fun observeInvalidCustomCategoryFilter() {
        viewModelScope.launch {
            combine(_categoryFilter, categories) { filter, categoryList ->
                filter to categoryList
            }.collectLatest { (filter, categoryList) ->
                val customFilter = filter as? CategoryFilter.Custom ?: return@collectLatest
                if (categoryList.any { it.id == customFilter.categoryId }) return@collectLatest

                val existsInDb = repository.getCategoryById(customFilter.categoryId) != null
                if (!existsInDb &&
                    _categoryFilter.value is CategoryFilter.Custom &&
                    (_categoryFilter.value as CategoryFilter.Custom).categoryId == customFilter.categoryId
                ) {
                    applyCategoryFilter(CategoryFilter.All, persist = true)
                }
            }
        }
    }

    private fun decodeSavedCategoryFilter(settings: com.bastion.app.data.AppSettings): CategoryFilter {
        return CategoryFilterCodec.decode(settings)
    }

    private fun persistCategoryFilter(filter: CategoryFilter) {
        val manager = settingsManager ?: return
        viewModelScope.launch {
            runCatchingObserved {
                when (filter) {
                    is CategoryFilter.All -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_ALL
                    )
                    is CategoryFilter.Archived -> Unit
                    is CategoryFilter.Local -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_LOCAL
                    )
                    is CategoryFilter.LocalOnly -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_LOCAL_ONLY
                    )
                    is CategoryFilter.Starred -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_STARRED
                    )
                    is CategoryFilter.Uncategorized -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_UNCATEGORIZED
                    )
                    is CategoryFilter.LocalStarred -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_LOCAL_STARRED
                    )
                    is CategoryFilter.LocalUncategorized -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_LOCAL_UNCATEGORIZED
                    )
                    is CategoryFilter.Custom -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_CUSTOM,
                        primaryId = filter.categoryId
                    )
                    is CategoryFilter.KeePassDatabase -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_KEEPASS_DATABASE,
                        primaryId = filter.databaseId
                    )
                    is CategoryFilter.KeePassDatabaseStarred -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_KEEPASS_DATABASE_STARRED,
                        primaryId = filter.databaseId
                    )
                    is CategoryFilter.KeePassDatabaseUncategorized -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_KEEPASS_DATABASE_UNCATEGORIZED,
                        primaryId = filter.databaseId
                    )
                    is CategoryFilter.KeePassGroupFilter -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_KEEPASS_GROUP,
                        primaryId = filter.databaseId,
                        text = filter.groupPath
                    )
                    is CategoryFilter.BitwardenVault -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_BITWARDEN_VAULT,
                        primaryId = filter.vaultId
                    )
                    is CategoryFilter.BitwardenVaultStarred -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_BITWARDEN_VAULT_STARRED,
                        primaryId = filter.vaultId
                    )
                    is CategoryFilter.BitwardenVaultUncategorized -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_BITWARDEN_VAULT_UNCATEGORIZED,
                        primaryId = filter.vaultId
                    )
                    is CategoryFilter.BitwardenFolderFilter -> manager.updateLastPasswordCategoryFilter(
                        type = SAVED_FILTER_BITWARDEN_FOLDER,
                        secondaryId = filter.vaultId,
                        text = filter.folderId
                    )
                }
            }.onFailure { error ->
                Log.w("PasswordViewModel", "Failed to persist category filter", error)
            }
        }
    }

    fun addCategory(name: String, onResult: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.insertCategory(Category(name = name))
            onResult(id)
        }
    }

    fun updateCategory(category: Category) {
        viewModelScope.launch {
            repository.updateCategory(category)
        }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            if (_categoryFilter.value is CategoryFilter.Custom && (_categoryFilter.value as CategoryFilter.Custom).categoryId == category.id) {
                applyCategoryFilter(CategoryFilter.All, persist = true)
            }
        }
    }
    
    fun updateCategorySortOrder(categories: List<Category>) {
        viewModelScope.launch {
            categories.forEachIndexed { index, category ->
                repository.updateCategorySortOrder(category.id, index)
            }
        }
    }

    fun movePasswordsToCategory(ids: List<Long>, categoryId: Long?) {
        viewModelScope.launch {
            movePasswordsToCategoryAwait(ids, categoryId)
        }
    }

    fun movePasswordsToKeePassDatabase(ids: List<Long>, databaseId: Long?) {
        viewModelScope.launch {
            movePasswordsToKeePassDatabaseAwait(ids, databaseId)
        }
    }

    fun movePasswordsToKeePassGroup(ids: List<Long>, databaseId: Long, groupPath: String) {
        viewModelScope.launch {
            movePasswordsToKeePassGroupAwait(ids, databaseId, groupPath)
        }
    }

    fun movePasswordsToBitwardenFolder(ids: List<Long>, vaultId: Long, folderId: String) {
        viewModelScope.launch {
            movePasswordsToBitwardenFolderAwait(ids, vaultId, folderId)
        }
    }

    suspend fun movePasswordsToCategoryAwait(ids: List<Long>, categoryId: Long?) {
        passwordMoveExecutor.movePasswordsToCategoryAwait(ids, categoryId)
    }

    suspend fun moveKeePassPasswordsToBastionCategoryAwait(
        ids: List<Long>,
        categoryId: Long?
    ): Result<Int> {
        return passwordMoveExecutor.moveKeePassPasswordsToBastionCategoryAwait(ids, categoryId)
    }

    suspend fun movePasswordsToKeePassDatabaseAwait(ids: List<Long>, databaseId: Long?) {
        passwordMoveExecutor.movePasswordsToKeePassDatabaseAwait(ids, databaseId)
    }

    suspend fun movePasswordsToKeePassGroupAwait(ids: List<Long>, databaseId: Long, groupPath: String) {
        passwordMoveExecutor.movePasswordsToKeePassGroupAwait(ids, databaseId, groupPath)
    }

    suspend fun movePasswordsToBitwardenFolderAwait(ids: List<Long>, vaultId: Long, folderId: String) {
        passwordMoveExecutor.movePasswordsToBitwardenFolderAwait(ids, vaultId, folderId)
    }

    
    fun authenticate(password: String): Boolean {
        val isValid = securityManager.unlockVaultWithPassword(password)
        _isAuthenticated.value = isValid
        if (isValid) {
            securityManager.markVaultAuthenticated()
        }
        return isValid
    }

    /**
     * Restore only the UI-level authenticated flag.
     *
     * SessionManager and runtime unlock state must already be valid before this
     * is called. This method must not create or extend an unlock window.
     */
    fun restoreAuthenticatedUiState() {
        if (!_isAuthenticated.value) {
            _isAuthenticated.value = true
        }
    }

    /**
     * Backward-compatible wrapper for old call sites.
     */
    fun restoreAuthenticatedSession() {
        restoreAuthenticatedUiState()
    }

    /**
     * Developer bypass only affects UI state and must not mark the app session unlocked.
     */
    fun markAuthenticatedForBypass() {
        restoreAuthenticatedUiState()
    }
    
    fun setMasterPassword(password: String) {
        securityManager.setMasterPassword(password)
        _isAuthenticated.value = true
        securityManager.markVaultAuthenticated()
    }
    
    fun isMasterPasswordSet(): Boolean {
        return securityManager.isMasterPasswordSet()
    }
    
    fun logout() {
        _isAuthenticated.value = false
        SessionManager.markLocked()
    }
    
    fun addPasswordEntry(entry: PasswordEntry, onResult: (Long) -> Unit = {}) {
        addPasswordEntryWithResult(
            entry = entry,
            includeDetailedLog = true
        ) { id ->
            if (id != null) {
                onResult(id)
            }
        }
    }

    fun addPasswordEntryWithResult(
        entry: PasswordEntry,
        includeDetailedLog: Boolean = true,
        skipCategoryBinding: Boolean = false,
        passwordAlreadyEncrypted: Boolean = false,
        onResult: (Long?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                createPasswordEntryInternal(
                    entry = entry,
                    includeDetailedLog = includeDetailedLog,
                    skipCategoryBinding = skipCategoryBinding,
                    passwordAlreadyEncrypted = passwordAlreadyEncrypted
                )
            }
            onResult(id)
        }
    }

    private suspend fun createPasswordEntryInternal(
        entry: PasswordEntry,
        includeDetailedLog: Boolean,
        skipCategoryBinding: Boolean = false,
        passwordAlreadyEncrypted: Boolean = false,
        customFieldsOverride: List<CustomFieldDraft>? = null
    ): Long? {
        val boundEntry = (if (skipCategoryBinding) entry else applyCategoryBinding(entry)).let { candidate ->
            // 只在"纯 KeePass 新建"场景下补 entryUuid；若 candidate 同时绑定了 Bitwarden vault，
            // 说明 keepassDatabaseId 其实是 applyCategoryBinding 根据当前 UI 过滤误塞进去的，
            // 继续补 UUID 会让 entry 同时拥有 concrete KeePass + concrete Bitwarden 绑定，
            // resolveOwnership 会判为 Conflict 导致 normalizePasswordInsert 后续 block 整个创建。
            if (candidate.keepassDatabaseId != null &&
                candidate.keepassEntryUuid.isNullOrBlank() &&
                candidate.bitwardenVaultId == null
            ) {
                candidate.copy(keepassEntryUuid = UUID.randomUUID().toString())
            } else {
                candidate
            }
        }
        val normalizedBoundEntry = BitwardenMutationStateHelper.normalizePasswordInsert(boundEntry)
        if (normalizedBoundEntry.hasOwnershipConflict()) {
            Log.w(
                "PasswordViewModel",
                "Blocked password create because of ownership conflict"
            )
            return null
        }
        val encryptedEntry = normalizedBoundEntry.copy(
            // 复制已有条目（batch copy / cross-container）的 password 字段已经是 Bastion SecurityManager
            // 加密过的密文，不需要再加密一次，否则解密时会多出一层导致显示乱码或用不了。
            password = if (passwordAlreadyEncrypted) {
                normalizedBoundEntry.password
            } else {
                securityManager.encryptData(normalizedBoundEntry.password)
            },
            authenticatorKey = encodeAuthenticatorKeyForStorage(normalizedBoundEntry.authenticatorKey),
            createdAt = Date(),
            updatedAt = Date()
        )
        val id = keepassPasswordCreateExecutor.create(
            localEntry = encryptedEntry,
            syncEntry = normalizedBoundEntry,
            insertEntry = repository::insertPasswordEntry,
            rollbackEntry = repository::deletePasswordEntryById,
            resolvePassword = { it.password },
            customFields = resolveKeePassCustomFieldsForSync(
                entryId = 0,
                customFieldsOverride = customFieldsOverride
            )
        ) ?: return null
        normalizedBoundEntry.bitwardenVaultId?.let { vaultId ->
            bitwardenRepository?.requestLocalMutationSync(vaultId)
        }

        if (includeDetailedLog) {
            val createDetails = mutableListOf<com.bastion.app.utils.FieldChange>()
            if (normalizedBoundEntry.username.isNotBlank()) {
                createDetails.add(com.bastion.app.utils.FieldChange("用户名", "", normalizedBoundEntry.username))
            }
            if (normalizedBoundEntry.website.isNotBlank()) {
                createDetails.add(com.bastion.app.utils.FieldChange("网站", "", normalizedBoundEntry.website))
            }
            if (normalizedBoundEntry.password.isNotBlank()) {
                createDetails.add(com.bastion.app.utils.FieldChange("密码", "", "<redacted>"))
            }
            if (normalizedBoundEntry.notes.isNotBlank()) {
                createDetails.add(com.bastion.app.utils.FieldChange("备注", "", "<redacted>"))
            }
            com.bastion.app.utils.OperationLogger.logCreate(
                itemType = com.bastion.app.data.OperationLogItemType.PASSWORD,
                itemId = id,
                itemTitle = normalizedBoundEntry.title,
                details = createDetails
            )
        }
        return id
    }

    suspend fun copyPasswordToBastionLocal(
        entry: PasswordEntry,
        categoryId: Long?
    ): Long? {
        val newId = createPasswordEntryInternal(
            entry = buildBastionLocalCopy(entry, categoryId),
            includeDetailedLog = false,
            skipCategoryBinding = true,
            // 源条目的 password 已经是 Bastion SecurityManager 加密过的密文，直接复用
            passwordAlreadyEncrypted = true
        )
        if (newId != null) {
            copyCustomFieldsForEntryCopy(
                sourceEntryId = entry.id,
                targetEntryId = newId
            )
        }
        return newId
    }

    suspend fun moveBitwardenPasswordToBastionLocal(
        entry: PasswordEntry,
        categoryId: Long?
    ): Result<Long> {
        val newId = copyPasswordToBastionLocal(entry, categoryId)
            ?: return Result.failure(IllegalStateException("创建 Bastion 本地副本失败"))

        val vaultId = entry.bitwardenVaultId
        val cipherId = entry.bitwardenCipherId
        if (vaultId != null && !cipherId.isNullOrBlank()) {
            val queueResult = bitwardenRepository?.queueCipherDelete(
                vaultId = vaultId,
                cipherId = cipherId,
                entryId = entry.id
            ) ?: Result.failure(IllegalStateException("Bitwarden 仓库不可用"))
            if (queueResult.isFailure) {
                repository.deletePasswordEntryById(newId)
                return Result.failure(
                    queueResult.exceptionOrNull() ?: IllegalStateException("排队删除 Bitwarden 条目失败")
                )
            }
        }

        repository.deletePasswordEntry(entry)
        repository.deleteArchiveSyncMeta(entry.id)
        return Result.success(newId)
    }

    private fun buildBastionLocalCopy(
        entry: PasswordEntry,
        categoryId: Long?
    ): PasswordEntry {
        return entry.copy(
            id = 0,
            categoryId = categoryId,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = null,
            isArchived = false,
            archivedAt = null,
            isDeleted = false,
            deletedAt = null
        )
    }

    fun addSecureItem(item: SecureItem) {
        viewModelScope.launch {
            secureItemRepository?.insertItem(item)
        }
    }
    
    /**
     * 快速添加密码（从底部导航栏快速添加）
     */
    fun quickAddPassword(title: String, username: String, password: String) {
        if (title.isBlank()) return
        val entry = PasswordEntry(
            title = title,
            username = username,
            password = password,
            website = "",
            notes = "",
            isFavorite = false
        )
        addPasswordEntry(entry)
    }
    
    fun updatePasswordEntry(entry: PasswordEntry) {
        viewModelScope.launch {
            updatePasswordEntryInternal(entry)
        }
    }

    fun updateBoundNoteId(id: Long, noteId: Long?) {
        viewModelScope.launch {
            repository.getPasswordEntryById(id)?.let { entry ->
                updatePasswordEntryInternal(entry.copy(boundNoteId = noteId))
            }
        }
    }

    private suspend fun updatePasswordEntryInternal(
        entry: PasswordEntry,
        customFieldsOverride: List<CustomFieldDraft>? = null,
        skipCategoryBinding: Boolean = false
    ): Boolean {
        // 获取旧数据用于对比
        val oldEntry = repository.getPasswordEntryById(entry.id)

        // 应用分类绑定。
        // skipCategoryBinding=true 用于"条目归属已经显式确定"的路径
        // （saveGroupedPasswordsInternal 的调用方已通过 applyToPasswordEntry /
        // applyCategoryBinding 定好 vault/folder/category），避免二次绑定把用户
        // 刚选好的 Bitwarden 位置按当前 UI 过滤器改写或清空。
        val boundEntry = if (skipCategoryBinding) entry else applyCategoryBinding(entry)
        if (boundEntry.hasOwnershipConflict()) {
            Log.w(
                "PasswordViewModel",
                "Blocked password update because of ownership conflict: entryId=${boundEntry.id}"
            )
            return false
        }
        val entryToUpdate = if (boundEntry.bitwardenVaultId != null) {
            boundEntry.copy(bitwardenLocalModified = true)
        } else {
            boundEntry
        }
        
        val oldPassword = oldEntry?.let { decryptForDisplay(it.password) } ?: ""
        val resolvedPassword = resolvePasswordForUpdate(
            existing = oldEntry,
            pendingEntry = entryToUpdate,
            incomingPassword = entryToUpdate.password
        )
        val newPassword = decryptForDisplay(resolvedPassword)
        val persistedEntry = entryToUpdate.copy(
            password = resolvedPassword,
            authenticatorKey = encodeAuthenticatorKeyForStorage(entryToUpdate.authenticatorKey),
            updatedAt = Date()
        )

        val keepassSync = keepassPasswordUpdateExecutor.syncUpdatedEntry(
            existingEntry = oldEntry,
            updatedEntry = persistedEntry,
            resolvePassword = { entryToUpdate.password },
            customFields = resolveKeePassCustomFieldsForSync(
                entryId = entryToUpdate.id,
                customFieldsOverride = customFieldsOverride
            ),
            persistUpdate = { updated ->
                repository.updatePasswordEntry(updated)
            }
        )
        if (keepassSync.isFailure) {
            Log.e(
                "PasswordViewModel",
                "KeePass password update failed before local update: ${keepassSync.exceptionOrNull()?.message}"
            )
            return false
        }

        if (oldEntry != null && oldPassword.isNotBlank() && oldPassword != newPassword) {
            savePasswordHistorySnapshot(entryToUpdate.id, oldPassword)
        }

        entryToUpdate.bitwardenVaultId?.let { vaultId ->
            bitwardenRepository?.requestLocalMutationSync(vaultId)
        }
        
        // 记录更新操作
        val changes = com.bastion.app.utils.OperationLogger.compareAndGetChanges(
            old = oldEntry,
            new = entryToUpdate,
            fields = listOf(
                "标题" to { it.title },
                "用户名" to { it.username },
                "网站" to { it.website },
                "备注" to { it.notes }
            )
        )

        // 原始值只交给加密版本快照；OperationLogger 写入审计日志前仍会脱敏。
        if (oldEntry != null && oldPassword != newPassword) {
            val updatedChanges = changes.toMutableList()
            updatedChanges.add(
                com.bastion.app.utils.FieldChange(
                    fieldName = "密码",
                    oldValue = oldPassword,
                    newValue = newPassword
                )
            )
            com.bastion.app.utils.OperationLogger.logUpdate(
                itemType = com.bastion.app.data.OperationLogItemType.PASSWORD,
                itemId = entryToUpdate.id,
                itemTitle = entryToUpdate.title,
                changes = updatedChanges
            )
            return true
        }
        com.bastion.app.utils.OperationLogger.logUpdate(
            itemType = com.bastion.app.data.OperationLogItemType.PASSWORD,
            itemId = entryToUpdate.id,
            itemTitle = entryToUpdate.title,
            changes = changes
        )
        return true
    }

    private suspend fun savePasswordHistorySnapshot(entryId: Long, plainPassword: String) {
        passwordHistoryRecorder.savePasswordHistorySnapshot(entryId, plainPassword)
    }
    
    fun deletePasswordEntry(entry: PasswordEntry) {
        viewModelScope.launch {
            val trashEnabled = trashSettings?.value?.first ?: true
            val commandPolicy = passwordProviderRegistry.commandPolicy(entry)
            val keepassId = entry.keepassDatabaseId
            val bitwardenVaultId = entry.bitwardenVaultId
            val bitwardenCipherId = entry.bitwardenCipherId
            val isBitwardenCipher = bitwardenVaultId != null && commandPolicy.usesRemoteDeleteQueue
            Log.i(
                "PasswordViewModel",
                "Delete requested: id=${entry.id}, title=${entry.title}, keepassId=$keepassId, trashEnabled=$trashEnabled, bitwardenCipher=$isBitwardenCipher"
            )

            if (isBitwardenCipher) {
                handleBitwardenQueuedDelete(
                    entry = entry,
                    vaultId = bitwardenVaultId!!,
                    cipherId = bitwardenCipherId!!,
                    commandPolicy = commandPolicy
                )
                return@launch
            }
              
            if (trashEnabled) {
                moveEntryToTrash(
                    entry = entry,
                    keepassId = keepassId,
                    commandPolicy = commandPolicy
                )
            } else {
                permanentlyDeleteEntry(entry)
            }
        }
    }

    suspend fun deletePasswordEntriesBatch(
        entries: List<PasswordEntry>,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null
    ): Int {
        if (entries.isEmpty()) return 0

        val trashEnabled = trashSettings?.value?.first ?: true
        var deletedCount = 0
        var processedCount = 0
        val totalCount = entries.size
        onProgress?.invoke(processedCount, totalCount)
        val keepassTargets = mutableListOf<
            Pair<PasswordEntry, com.bastion.app.domain.provider.PasswordCommandPolicy>
        >()
        val localTargets = mutableListOf<
            Pair<PasswordEntry, com.bastion.app.domain.provider.PasswordCommandPolicy>
        >()

        entries.forEach { entry ->
            val commandPolicy = passwordProviderRegistry.commandPolicy(entry)
            val bitwardenVaultId = entry.bitwardenVaultId
            val bitwardenCipherId = entry.bitwardenCipherId
            val isBitwardenCipher = bitwardenVaultId != null && commandPolicy.usesRemoteDeleteQueue

            if (entry.keepassDatabaseId != null && !isBitwardenCipher) {
                keepassTargets += entry to commandPolicy
            } else {
                val deleted = if (isBitwardenCipher) {
                    if (!bitwardenCipherId.isNullOrBlank()) {
                        handleBitwardenQueuedDelete(
                            entry = entry,
                            vaultId = bitwardenVaultId!!,
                            cipherId = bitwardenCipherId,
                            commandPolicy = commandPolicy
                        )
                    } else {
                        false
                    }
                } else {
                    localTargets += entry to commandPolicy
                    true
                }
                if (deleted && isBitwardenCipher) {
                    deletedCount++
                    processedCount++
                    onProgress?.invoke(processedCount, totalCount)
                } else if (!isBitwardenCipher) {
                    // Local deletes are flushed below through repository batch APIs as one commit per vault.
                } else {
                    processedCount++
                    onProgress?.invoke(processedCount, totalCount)
                }
            }
        }

        if (localTargets.isNotEmpty()) {
            val appliedCount = applyLocalDeleteBatch(localTargets, trashEnabled)
            deletedCount += appliedCount
            repeat(localTargets.size) {
                processedCount++
                onProgress?.invoke(processedCount, totalCount)
            }
        }

        if (keepassTargets.isEmpty()) return deletedCount

        keepassTargets
            .groupBy { it.first.keepassDatabaseId }
            .values
            .forEach { groupedEntries ->
                groupedEntries
                    .chunked(KEEPASS_BATCH_DELETE_CHUNK_SIZE)
                    .forEach { chunk ->
                        val chunkEntries = chunk.map { it.first }
                        val remoteDeleted = keepassPasswordDeleteExecutor.deleteBatch(
                            entries = chunkEntries,
                            useRecycleBin = trashEnabled
                        )
                        if (!remoteDeleted) {
                            Log.e(
                                "PasswordViewModel",
                                "KeePass batch delete failed: trash=$trashEnabled, ids=${chunkEntries.map { it.id }}"
                            )
                            // 批量路径失败时退回逐条删除，尽可能提升成功率并输出真实进度。
                            val singleDeletedTargets = mutableListOf<
                                Pair<PasswordEntry, com.bastion.app.domain.provider.PasswordCommandPolicy>
                            >()
                            chunk.forEach { (entry, commandPolicy) ->
                                val singleDeleted = keepassPasswordDeleteExecutor.delete(
                                    entry = entry,
                                    useRecycleBin = trashEnabled
                                )
                                if (singleDeleted) {
                                    singleDeletedTargets += entry to commandPolicy
                                }
                            }
                            if (singleDeletedTargets.isNotEmpty()) {
                                deletedCount += applyLocalDeleteBatch(singleDeletedTargets, trashEnabled)
                            }
                            repeat(chunk.size) {
                                processedCount++
                                onProgress?.invoke(processedCount, totalCount)
                            }
                            return@forEach
                        }

                        deletedCount += applyLocalDeleteBatch(chunk, trashEnabled)
                        repeat(chunk.size) {
                            processedCount++
                            onProgress?.invoke(processedCount, totalCount)
                        }
                    }
            }

        return deletedCount
    }

    private suspend fun handleBitwardenQueuedDelete(
        entry: PasswordEntry,
        vaultId: Long,
        cipherId: String,
        commandPolicy: com.bastion.app.domain.provider.PasswordCommandPolicy
    ): Boolean {
        val queueResult = bitwardenRepository?.queueCipherDelete(
            vaultId = vaultId,
            cipherId = cipherId,
            entryId = entry.id
        )
        if (queueResult?.isFailure == true) {
            Log.e(
                "PasswordViewModel",
                "Queue Bitwarden delete failed: ${queueResult.exceptionOrNull()?.message}"
            )
            return false
        }
        if (!keepassPasswordDeleteExecutor.delete(entry, useRecycleBin = true)) return false

        val tombstone = passwordCommandStateFactory.createQueuedDeleteTombstone(
            entry = entry,
            now = Date(),
            commandPolicy = commandPolicy
        )
        repository.updatePasswordEntry(tombstone)
        repository.deleteArchiveSyncMeta(entry.id)
        cascadeDeleteBoundTotpItems(listOf(entry), softDelete = true)
        com.bastion.app.utils.OperationLogger.logDelete(
            itemType = com.bastion.app.data.OperationLogItemType.PASSWORD,
            itemId = entry.id,
            itemTitle = entry.title,
            detail = "移入回收站（待同步删除）"
        )
        Log.i("PasswordViewModel", "Delete queued as tombstone: id=${entry.id}")
        return true
    }

    private suspend fun applyLocalDeleteBatch(
        entries: List<Pair<PasswordEntry, com.bastion.app.domain.provider.PasswordCommandPolicy>>,
        trashEnabled: Boolean
    ): Int {
        if (entries.isEmpty()) return 0
        val originalEntries = entries.map { it.first }
        if (trashEnabled) {
            val now = Date()
            val softDeletedEntries = entries.map { (entry, commandPolicy) ->
                passwordCommandStateFactory.createSoftDeletedEntry(
                    entry = entry,
                    now = now,
                    commandPolicy = commandPolicy
                )
            }
            repository.updatePasswordEntries(softDeletedEntries)
            repository.deleteArchiveSyncMeta(originalEntries.map { it.id })
            cascadeDeleteBoundTotpItems(originalEntries, softDelete = true)
            originalEntries.forEach { entry ->
                com.bastion.app.utils.OperationLogger.logDelete(
                    itemType = com.bastion.app.data.OperationLogItemType.PASSWORD,
                    itemId = entry.id,
                    itemTitle = entry.title,
                    detail = "移入回收站"
                )
                Log.i("PasswordViewModel", "Delete moved to trash: id=${entry.id}")
            }
        } else {
            repository.deletePasswordEntries(originalEntries)
            repository.deleteArchiveSyncMeta(originalEntries.map { it.id })
            cascadeDeleteBoundTotpItems(originalEntries, softDelete = false)
            originalEntries.forEach { entry ->
                com.bastion.app.utils.OperationLogger.logDelete(
                    itemType = com.bastion.app.data.OperationLogItemType.PASSWORD,
                    itemId = entry.id,
                    itemTitle = entry.title
                )
                Log.i("PasswordViewModel", "Delete permanently removed: id=${entry.id}")
            }
        }
        return originalEntries.size
    }

    private suspend fun moveEntryToTrash(
        entry: PasswordEntry,
        keepassId: Long?,
        commandPolicy: com.bastion.app.domain.provider.PasswordCommandPolicy
    ) {
        moveEntryToTrashLocalOnly(entry, commandPolicy)

        if (keepassId != null) {
            syncKeePassTrashDelete(entry)
        }
    }

    private suspend fun moveEntryToTrashLocalOnly(
        entry: PasswordEntry,
        commandPolicy: com.bastion.app.domain.provider.PasswordCommandPolicy
    ) {
        val softDeletedEntry = passwordCommandStateFactory.createSoftDeletedEntry(
            entry = entry,
            now = Date(),
            commandPolicy = commandPolicy
        )
        repository.updatePasswordEntry(softDeletedEntry)
        repository.deleteArchiveSyncMeta(entry.id)
        cascadeDeleteBoundTotpItems(listOf(entry), softDelete = true)
        com.bastion.app.utils.OperationLogger.logDelete(
            itemType = com.bastion.app.data.OperationLogItemType.PASSWORD,
            itemId = entry.id,
            itemTitle = entry.title,
            detail = "移入回收站"
        )
        Log.i("PasswordViewModel", "Delete moved to trash: id=${entry.id}")
    }

    private fun syncKeePassTrashDelete(entry: PasswordEntry) {
        viewModelScope.launch keepassDeleteSync@{
            if (keepassPasswordDeleteExecutor.delete(entry, useRecycleBin = true)) {
                Log.i("PasswordViewModel", "KeePass trash delete synced: id=${entry.id}")
                return@keepassDeleteSync
            }

            Log.e("PasswordViewModel", "KeePass trash delete failed, reverting local trash state: id=${entry.id}")
            repository.updatePasswordEntry(
                passwordCommandStateFactory.createTrashRevertedEntry(
                    entry = entry,
                    now = Date()
                )
            )
        }
    }

    private suspend fun permanentlyDeleteEntry(entry: PasswordEntry) {
        if (!keepassPasswordDeleteExecutor.delete(entry, useRecycleBin = false)) return

        permanentlyDeleteEntryLocalOnly(entry)
    }

    private suspend fun permanentlyDeleteEntryLocalOnly(entry: PasswordEntry) {
        repository.deletePasswordEntry(entry)
        repository.deleteArchiveSyncMeta(entry.id)
        cascadeDeleteBoundTotpItems(listOf(entry), softDelete = false)
        com.bastion.app.utils.OperationLogger.logDelete(
            itemType = com.bastion.app.data.OperationLogItemType.PASSWORD,
            itemId = entry.id,
            itemTitle = entry.title
        )
        Log.i("PasswordViewModel", "Delete permanently removed: id=${entry.id}")
    }

    /**
     * 删除密码条目时级联清理其绑定型验证器在 secure_items 中的载体记录。
     *
     * 背景（真机复现 bug）：删除带验证码的密码条目后，验证器界面仍残留该验证码，
     * 且 Bitwarden 同步报冲突——因为绑定型 TOTP 在 secure_items 里有一条
     * SYNC_STATUS_REFERENCE 记录（boundPasswordId 指向密码条目），删除密码条目的
     * 各条链路此前均未清理它，成为孤儿验证码。
     *
     * 级联范围：仅处理「绑定到被删密码、且没有独立 Bitwarden cipher」的 TOTP——
     * 这类记录是密码验证码在本地 secure_items 的唯一载体，验证码数据本体随密码
     * 条目的 authenticatorKey → cipher.login.totp 一起消失。自带独立 cipher 的
     * 历史条目在服务器上是另一份独立数据，不纳入级联（与
     * TotpListContent.willClearBoundPasswordTotp / TotpViewModel.deleteTotpItem
     * 的既有语义保持一致）。
     *
     * softDelete 与密码条目的删除语义对齐：密码进回收站则验证器同步进回收站，
     * 恢复对称逻辑见 TrashViewModel.applyLocalRestore。
     */
    private suspend fun cascadeDeleteBoundTotpItems(
        entries: List<PasswordEntry>,
        softDelete: Boolean
    ) {
        val secureRepository = secureItemRepository ?: return
        if (entries.isEmpty()) return
        val deletedPasswordIds = entries.mapTo(mutableSetOf()) { it.id }
        runCatchingObserved {
            secureRepository.getItemsByType(ItemType.TOTP)
                .first()
                .filter { !it.isDeleted && it.bitwardenCipherId.isNullOrBlank() }
                .filter { item ->
                    val boundId = parseStoredTotpData(item)?.boundPasswordId
                    boundId != null && boundId in deletedPasswordIds
                }
                .forEach { item ->
                    if (softDelete) {
                        secureRepository.softDeleteItem(item)
                    } else {
                        secureRepository.deleteItem(item)
                    }
                    com.bastion.app.utils.OperationLogger.logDelete(
                        itemType = com.bastion.app.data.OperationLogItemType.TOTP,
                        itemId = item.id,
                        itemTitle = item.title,
                        detail = "随密码条目一并删除"
                    )
                    Log.i(
                        "PasswordViewModel",
                        "Cascade deleted bound totp: itemId=${item.id}, softDelete=$softDelete"
                    )
                }
        }.onFailure { e ->
            Log.e("PasswordViewModel", "Cascade delete bound totp failed", e)
        }
    }
    
    fun toggleFavorite(id: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.toggleFavorite(id, isFavorite)
        }
    }

    fun archivePassword(id: Long) {
        viewModelScope.launch {
            archivePasswordsInternal(listOf(id))
        }
    }

    fun archivePasswords(ids: List<Long>) {
        viewModelScope.launch {
            archivePasswordsInternal(ids)
        }
    }

    fun unarchivePassword(id: Long) {
        viewModelScope.launch {
            unarchivePasswordsInternal(listOf(id))
        }
    }

    fun unarchivePasswords(ids: List<Long>) {
        viewModelScope.launch {
            unarchivePasswordsAwait(ids)
        }
    }

    suspend fun unarchivePasswordsAwait(ids: List<Long>) {
        unarchivePasswordsInternal(ids)
    }

    private suspend fun archivePasswordsInternal(ids: List<Long>) {
        archiveOrchestrator.archivePasswordsInternal(ids)
    }

    private suspend fun unarchivePasswordsInternal(ids: List<Long>) {
        archiveOrchestrator.unarchivePasswordsInternal(ids)
    }

    private suspend fun moveKeePassEntryGroupPath(
        entry: PasswordEntry,
        targetGroupPath: String?
    ): Result<Unit> {
        val databaseId = entry.keepassDatabaseId
            ?: return Result.failure(IllegalStateException("No KeePass database bound"))
        val bridge = keepassBridge
            ?: return Result.failure(IllegalStateException("KeePass bridge unavailable"))

        return bridge.updateLegacyPasswordEntry(
            databaseId = databaseId,
            entry = entry.copy(keepassGroupPath = targetGroupPath),
            resolvePassword = { resolvePlainPasswordForKeePass(it.password) },
            customFields = resolveKeePassCustomFieldsForSync(
                entryId = entry.id,
                customFieldsOverride = null
            )
        )
    }

    private suspend fun ensureKeePassArchiveGroupPath(databaseId: Long?): String? {
        val resolvedDatabaseId = databaseId ?: return null
        val bridge = keepassBridge ?: return null

        val rootPath = buildKeePassPathKey(null, MONICA_KEEPASS_ARCHIVE_ROOT_GROUP_NAME)
        val archivePath = buildKeePassPathKey(rootPath, MONICA_KEEPASS_ARCHIVE_GROUP_NAME)

        var groups = bridge.listLegacyGroups(resolvedDatabaseId).getOrElse { return null }

        if (groups.none { it.path == rootPath }) {
            val rootResult = bridge.createLegacyGroup(
                databaseId = resolvedDatabaseId,
                groupName = MONICA_KEEPASS_ARCHIVE_ROOT_GROUP_NAME
            )
            if (rootResult.isFailure) return null
            groups = bridge.listLegacyGroups(resolvedDatabaseId).getOrElse { return null }
        }

        if (groups.none { it.path == archivePath }) {
            val archiveResult = bridge.createLegacyGroup(
                databaseId = resolvedDatabaseId,
                groupName = MONICA_KEEPASS_ARCHIVE_GROUP_NAME,
                parentPath = rootPath
            )
            if (archiveResult.isFailure) return null
        }

        return archivePath
    }

    private suspend fun resolveKeePassRestorePathOrRoot(
        databaseId: Long?,
        preferredPath: String?
    ): String? {
        if (databaseId == null) return preferredPath
        if (preferredPath.isNullOrBlank()) return null
        val bridge = keepassBridge ?: return null

        val groups = bridge.listLegacyGroups(databaseId).getOrNull() ?: return null
        return groups.firstOrNull { it.path == preferredPath }?.path
    }

    private fun resolvePlainPasswordForKeePass(storedPassword: String): String {
        if (storedPassword.isBlank()) return ""
        return try {
            decryptForDisplay(storedPassword)
        } catch (_: Exception) {
            storedPassword
        }
    }
    
    fun toggleGroupCover(id: Long, website: String, isGroupCover: Boolean) {
        viewModelScope.launch {
            if (isGroupCover) {
                // 设置为封面,会自动清除该分组的其他封面
                repository.setGroupCover(id, website)
            } else {
                // 取消封面
                repository.updateGroupCoverStatus(id, false)
            }
        }
    }
    
    fun updateSortOrders(items: List<Pair<Long, Int>>) {
        viewModelScope.launch {
            repository.updateSortOrders(items)
        }
    }

    /**
     * 更新绑定的验证器密钥
     */
    fun updateAuthenticatorKey(id: Long, authenticatorKey: String) {
        viewModelScope.launch {
            repository.updateAuthenticatorKey(id, encodeAuthenticatorKeyForStorage(authenticatorKey))
        }
    }

    /**
     * 更新绑定的通行密钥元数据
     */
    fun updatePasskeyBindings(id: Long, passkeyBindings: String) {
        viewModelScope.launch {
            repository.updatePasskeyBindings(id, passkeyBindings)
        }
    }
    
    suspend fun getPasswordEntryById(id: Long): PasswordEntry? {
        return getRawPasswordEntryById(id)?.let { entry ->
            entry.copy(password = inspectSecretState(entry).plainValueOrEmpty())
        }
    }

    suspend fun recoverUnreadableBitwardenEntry(entryId: Long): BitwardenRecoveryResult {
        val entry = repository.getPasswordEntryById(entryId)
            ?: return BitwardenRecoveryResult.Error("Entry not found")
        val vaultId = entry.bitwardenVaultId
            ?: return BitwardenRecoveryResult.Error("Entry is not backed by Bitwarden")
        if (entry.bitwardenCipherId.isNullOrBlank()) {
            return BitwardenRecoveryResult.Error("Entry has no Bitwarden cipher binding")
        }
        val repositoryInstance = bitwardenRepository
            ?: return BitwardenRecoveryResult.Error("Bitwarden repository unavailable")

        return when (val result = repositoryInstance.syncForUserVisibleRequest(
            vaultId = vaultId,
            requestIdPrefix = "bw-password-recover-vault"
        )) {
            is BitwardenRepository.SyncResult.Success -> BitwardenRecoveryResult.Success
            is BitwardenRepository.SyncResult.Error -> BitwardenRecoveryResult.Error(result.message)
            is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
                BitwardenRecoveryResult.EmptyVaultBlocked(result.reason)
            }
        }
    }

    fun getPasswordHistoryFlow(passwordId: Long): Flow<List<PasswordHistoryEntry>> {
        return passwordHistoryRecorder.getPasswordHistoryFlow(passwordId)
    }

    /**
     * 为附件下载提供 [com.bastion.app.attachments.facade.AttachmentFacade.BitwardenContext]。
     *
     * 返回 null 表示 vault 未解锁或会话无效。
     */
    fun getAttachmentBitwardenContext(
        vault: BitwardenVault,
        cipherId: String?
    ): com.bastion.app.attachments.facade.AttachmentFacade.BitwardenContext? {
        return bitwardenRepository?.getAttachmentBitwardenContext(vault, cipherId)
    }

    fun getBitwardenSyncRawHistoryFlow(
        vaultId: Long,
        cipherId: String
    ): Flow<List<BitwardenSyncRawHistoryItem>> {
        return passwordHistoryRecorder.getBitwardenSyncRawHistoryFlow(vaultId, cipherId)
    }

    fun deletePasswordHistoryEntry(historyId: Long) {
        viewModelScope.launch {
            repository.deletePasswordHistoryById(historyId)
        }
    }

    fun clearPasswordHistory(entryId: Long) {
        viewModelScope.launch {
            repository.clearPasswordHistory(entryId)
        }
    }

    /**
     * Get linked TOTP data for a password entry
     */
    fun getLinkedTotpFlow(passwordId: Long): Flow<TotpData?> {
        val itemFlow = secureItemRepository?.getItemsByType(ItemType.TOTP) ?: return flowOf(null)
        return combine(itemFlow, repository.getAllPasswordEntries()) { items, passwords ->
            val boundPassword = passwords.firstOrNull { it.id == passwordId }
            val candidates = items.mapNotNull { item ->
                val data = parseStoredTotpData(item)
                if (data?.boundPasswordId == passwordId) item to data else null
            }
            val preferred = candidates.firstOrNull()
            preferred?.second
        }.flowOn(Dispatchers.Default)
    }

    suspend fun copyBoundTotpsForPasswordCopies(idPairs: List<Pair<Long, Long>>): Int {
        val secureRepository = secureItemRepository ?: return 0
        if (idPairs.isEmpty()) return 0

        val sourceIds = idPairs.map { it.first }.distinct()
        val newIds = idPairs.map { it.second }.distinct()
        val sourcePasswords = repository.getPasswordsByIds(sourceIds).associateBy { it.id }
        val newPasswords = repository.getPasswordsByIds(newIds).associateBy { it.id }
        val storedTotps = secureRepository.getItemsByType(ItemType.TOTP)
            .first()
            .mapNotNull { item ->
                val data = parseStoredTotpData(item)
                    ?: return@mapNotNull null
                item to data
            }

        var copiedCount = 0
        val copiedNewPasswordIds = mutableSetOf<Long>()
        idPairs.forEach { (sourceId, newId) ->
            val sourcePassword = sourcePasswords[sourceId] ?: return@forEach
            val newPassword = newPasswords[newId] ?: return@forEach
            if (!copiedNewPasswordIds.add(newId)) {
                return@forEach
            }

            val sourceTotp = resolveBoundTotpCopySource(
                sourcePassword = sourcePassword,
                storedTotps = storedTotps
            ) ?: return@forEach

            runCatchingObserved {
                val now = Date()
                val normalizedData = TotpDataResolver.normalizeTotpData(sourceTotp.data).copy(
                    boundPasswordId = newPassword.id,
                    categoryId = null,
                    keepassDatabaseId = null
                )
                if (normalizedData.secret.isBlank()) return@runCatchingObserved

                val copiedItem = sourceTotp.item?.copy(
                    id = 0,
                    title = sourceTotp.title,
                    notes = sourceTotp.notes,
                    itemData = encodeStoredSensitiveValueForCopy(
                        sourceTotp.item.itemData,
                        Json.encodeToString(normalizedData)
                    ),
                    categoryId = null,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false,
                    syncStatus = "NONE",
                    replicaGroupId = null,
                    isDeleted = false,
                    deletedAt = null,
                    createdAt = now,
                    updatedAt = now
                ) ?: SecureItem(
                    itemType = ItemType.TOTP,
                    title = sourceTotp.title,
                    notes = sourceTotp.notes,
                    itemData = Json.encodeToString(normalizedData),
                    isFavorite = false,
                    categoryId = null,
                    createdAt = now,
                    updatedAt = now
                )

                secureRepository.insertItem(copiedItem)
                val authenticatorPayload = TotpDataResolver.toBitwardenPayload(sourceTotp.title, normalizedData)
                if (
                    authenticatorPayload.isNotBlank() &&
                    decryptStoredSensitiveValue(newPassword.authenticatorKey) != authenticatorPayload
                ) {
                    repository.updateAuthenticatorKey(newPassword.id, encodeAuthenticatorKeyForStorage(authenticatorPayload))
                }
                copiedCount += 1
            }.onFailure { error ->
                Log.w(
                    "PasswordViewModel",
                    "Failed to copy bound TOTP for password copy $sourceId -> $newId: ${error.message}"
                )
            }
        }
        return copiedCount
    }

    private data class BoundTotpCopySource(
        val item: SecureItem?,
        val data: TotpData,
        val title: String,
        val notes: String
    )

    private fun resolveBoundTotpCopySource(
        sourcePassword: PasswordEntry,
        storedTotps: List<Pair<SecureItem, TotpData>>
    ): BoundTotpCopySource? {
        val passwordTotpData = parseStoredAuthenticatorKey(sourcePassword)?.copy(
            boundPasswordId = sourcePassword.id,
            categoryId = sourcePassword.categoryId
        )
        val passwordTotpKey = passwordTotpData?.let(::buildTotpCopyIdentityKey)

        val candidates = storedTotps.filter { (_, data) -> data.boundPasswordId == sourcePassword.id }
        val preferredStored = candidates.firstOrNull { (item, data) ->
            item.bitwardenVaultId == sourcePassword.bitwardenVaultId &&
                (passwordTotpKey == null || buildTotpCopyIdentityKey(data) == passwordTotpKey)
        } ?: candidates.firstOrNull { (_, data) ->
            passwordTotpKey != null && buildTotpCopyIdentityKey(data) == passwordTotpKey
        } ?: candidates.firstOrNull()

        if (preferredStored != null) {
            val (item, data) = preferredStored
            return BoundTotpCopySource(
                item = item,
                data = data,
                title = item.title,
                notes = item.notes
            )
        }

        return passwordTotpData?.let { data ->
            BoundTotpCopySource(
                item = null,
                data = data,
                title = sourcePassword.title,
                notes = "来自密码: ${sourcePassword.title}"
            )
        }
    }

    private fun buildTotpCopyIdentityKey(data: TotpData): String {
        val normalized = TotpDataResolver.normalizeTotpData(data)
        return listOf(
            normalized.otpType.name,
            normalized.secret,
            normalized.algorithm.uppercase(Locale.ROOT),
            normalized.digits.toString(),
            normalized.period.toString(),
            normalized.counter.toString()
        ).joinToString("|")
    }
    
    /**
     * Verify master password
     */
    fun verifyMasterPassword(password: String): Boolean {
        return securityManager.verifyMasterPassword(password)
    }
    
    /**
     * Reset all application data - used for forgot password scenario
     * Supports selective clearing of different data categories
     */
    fun resetAllData(
        clearPasswords: Boolean = true,
        clearTotp: Boolean = true,
        clearDocuments: Boolean = true,
        clearBankCards: Boolean = true,
        clearGeneratorHistory: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                // Clear selected data categories
                if (clearPasswords) {
                    repository.deleteAllPasswordEntries()
                }
                
                if (secureItemRepository != null) {
                    if (clearTotp) {
                        secureItemRepository.deleteAllTotpEntries()
                    }
                    
                    if (clearDocuments) {
                        secureItemRepository.deleteAllDocuments()
                    }
                    
                    if (clearBankCards) {
                        secureItemRepository.deleteAllBankCards()
                    }
                }
                
                if (clearGeneratorHistory && passwordHistoryManager != null) {
                    passwordHistoryManager.clearHistory()
                }
                
                // Always clear security data when resetting
                securityManager.clearSecurityData()
                
                // Reset authentication state
                _isAuthenticated.value = false
            } catch (e: Exception) {
                // Handle error - log it
                Log.e("PasswordViewModel", "Error clearing data", e)
            }
        }
    }
    
    /**
     * Change master password
     * 修改主密码并重新加密所有数据
     *
     * 实现已抽取到 [MasterPasswordOps]（B.3 集群 7）。变更成功后恢复认证态。
     */
    fun changePassword(currentPassword: String, newPassword: String) {
        viewModelScope.launch {
            if (masterPasswordOps.changePassword(currentPassword, newPassword)) {
                _isAuthenticated.value = true
            }
        }
    }
    
    /**
     * Save security questions
     * 保存密保问题
     *
     * 实现已抽取到 [MasterPasswordOps]（B.3 集群 7，原 TODO 已补全落库）。
     */
    fun saveSecurityQuestions(questions: List<Pair<String, String>>) {
        viewModelScope.launch {
            masterPasswordOps.saveSecurityQuestions(questions)
        }
    }

    fun updateAppAssociationByWebsite(website: String, packageName: String, appName: String) {
        viewModelScope.launch {
            repository.updateAppAssociationByWebsite(website, packageName, appName)
        }
    }

    fun updateAppAssociationByTitle(title: String, packageName: String, appName: String) {
        viewModelScope.launch {
            repository.updateAppAssociationByTitle(title, packageName, appName)
        }
    }

    // ==========================================
    // Grouping Helpers
    // ==========================================

    private fun getPasswordInfoKey(entry: PasswordEntry): String {
        return "${entry.title}|${entry.website}|${entry.username}|${entry.notes}|${entry.appPackageName}|${entry.appName}"
    }

    private fun applyCategoryBinding(entry: PasswordEntry): PasswordEntry {
        val filterBoundEntry = when (val filter = _categoryFilter.value) {
            is CategoryFilter.KeePassDatabase -> {
                if (entry.keepassDatabaseId == null) entry.copy(keepassDatabaseId = filter.databaseId) else entry
            }
            is CategoryFilter.KeePassDatabaseStarred -> {
                if (entry.keepassDatabaseId == null) entry.copy(keepassDatabaseId = filter.databaseId) else entry
            }
            is CategoryFilter.KeePassDatabaseUncategorized -> {
                if (entry.keepassDatabaseId == null) entry.copy(keepassDatabaseId = filter.databaseId) else entry
            }
            is CategoryFilter.KeePassGroupFilter -> {
                if (entry.keepassDatabaseId == null) {
                    entry.copy(
                        keepassDatabaseId = filter.databaseId,
                        keepassGroupPath = entry.keepassGroupPath ?: filter.groupPath
                    )
                } else if (entry.keepassGroupPath.isNullOrBlank()) {
                    entry.copy(keepassGroupPath = filter.groupPath)
                } else {
                    entry
                }
            }
            else -> entry
        }

        // Password category assignment should not silently change storage
        // ownership for local Bastion items. Only entries that already belong to
        // Bitwarden may inherit/update folder linkage from a linked category.

        val categoryId = filterBoundEntry.categoryId ?: return filterBoundEntry
        val category = categories.value.find { it.id == categoryId } ?: return filterBoundEntry

        // KeePass 条目保持独立，不参与 Bitwarden 自动绑定
        if (filterBoundEntry.keepassDatabaseId != null) return filterBoundEntry

        val alreadyBitwardenOwned = filterBoundEntry.bitwardenVaultId != null ||
            !filterBoundEntry.bitwardenCipherId.isNullOrBlank()
        if (!alreadyBitwardenOwned) {
            return filterBoundEntry.copy(
                bitwardenVaultId = null,
                bitwardenFolderId = null,
                bitwardenLocalModified = false
            )
        }

        // 分类未绑定 Bitwarden：清理"待上传"绑定（已同步条目保持映射不动）。
        // 【单一归属】待上传条目（vaultId 已挂、cipherId 未生成）只清 folderId 细节，
        // 保留 vaultId 与 bitwardenLocalModified——否则用户刚选好的 Bitwarden 位置
        // 会被这里静默撤销（条目退回本地），这正是"编辑后同步状态不对"的成因之一。
        if (category.bitwardenVaultId == null || category.bitwardenFolderId == null) {
            return if (filterBoundEntry.bitwardenCipherId == null) {
                if (filterBoundEntry.bitwardenVaultId != null) {
                    filterBoundEntry.copy(bitwardenFolderId = null)
                } else {
                    filterBoundEntry.copy(
                        bitwardenVaultId = null,
                        bitwardenFolderId = null,
                        bitwardenLocalModified = false
                    )
                }
            } else {
                filterBoundEntry
            }
        }
        
        // 自动绑定到分类关联的 Bitwarden 文件夹
        return filterBoundEntry.copy(
            bitwardenVaultId = category.bitwardenVaultId,
            bitwardenFolderId = category.bitwardenFolderId,
            // 如果是已同步的条目，且文件夹改变了，标记为本地修改
            bitwardenLocalModified = if (filterBoundEntry.bitwardenCipherId != null && filterBoundEntry.bitwardenFolderId != category.bitwardenFolderId) true else filterBoundEntry.bitwardenLocalModified
        )
    }

    /**
     * Save a group of passwords.
     * Updates existing entries to preserve IDs (and TOTP links), creates new ones if needed,
     * and deletes removed ones.
     * The callback receives the ID of the first password (for TOTP binding).
     */
    fun saveGroupedPasswords(
        originalIds: List<Long>,
        commonEntry: PasswordEntry, // Contains common info and ONE password (ignored)
        passwords: List<String>,
        customFields: List<CustomFieldDraft> = emptyList(), // 自定义字段
        onComplete: (firstPasswordId: Long?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val firstPasswordId = withContext(Dispatchers.IO) {
                saveGroupedPasswordsInternal(
                    originalIds = originalIds,
                    commonEntry = commonEntry,
                    passwords = passwords,
                    customFields = customFields,
                    skipCategoryBinding = false
                )
            }
            onComplete(firstPasswordId)
        }
    }

    fun savePasswordsAcrossTargets(
        originalIds: List<Long>,
        commonEntry: PasswordEntry,
        passwords: List<String>,
        targets: List<StorageTarget>,
        customFields: List<CustomFieldDraft> = emptyList(),
        onCompleteWithIds: (firstPasswordId: Long?, savedPasswordIds: List<Long>) -> Unit = { _, _ -> },
        onComplete: (firstPasswordId: Long?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val requestedTargetKeys = targets.distinctBy(StorageTarget::stableKey)
                .map(StorageTarget::stableKey)
            val saveResult = try {
                withContext(Dispatchers.IO) {
                    val distinctTargets = targets.distinctBy(StorageTarget::stableKey)
                    if (distinctTargets.isEmpty()) {
                        Log.w("PasswordViewModel", "savePasswordsAcrossTargets blocked because target list is empty")
                        return@withContext PasswordSaveAcrossTargetsResult(null, emptyList())
                    }
                    if (!canWriteKeePassTargets(distinctTargets)) {
                        Log.w(
                            "PasswordViewModel",
                            "savePasswordsAcrossTargets blocked because a KeePass target is unavailable targets=$requestedTargetKeys"
                        )
                        return@withContext PasswordSaveAcrossTargetsResult(null, emptyList())
                    }

                val currentEntry = originalIds.firstOrNull()?.let { repository.getPasswordEntryById(it) }
                val replicaGroupId = currentEntry?.replicaGroupId
                    ?.takeIf { it.isNotBlank() }
                    ?: commonEntry.replicaGroupId?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                val selectedTargetKeys = distinctTargets
                    .map(StorageTarget::stableKey)
                    .toSet()
                val allEntries = repository.getAllPasswordEntries().first()
                val originalEntries = originalIds.mapNotNull { id ->
                    allEntries.firstOrNull { it.id == id }
                }
                val existingReplicasByKey = allEntries
                    .filter {
                        it.replicaGroupId == replicaGroupId &&
                            !it.isDeleted &&
                            !it.isArchived
                    }
                    .groupBy { it.toStorageTarget().stableKey }
                val currentEntryTarget = currentEntry?.toStorageTarget()
                val currentTarget = currentEntryTarget
                    ?.takeIf { it.stableKey in selectedTargetKeys }
                    ?: distinctTargets.first()
                val currentTargetOriginalIds = originalEntries
                    .filter {
                        it.toStorageTarget().stableKey == currentTarget.stableKey
                    }
                    .map { it.id }
                    .ifEmpty {
                        existingReplicasByKey[currentTarget.stableKey]
                            .orEmpty()
                            .sortedBy { it.id }
                            .map { it.id }
                    }
                    .ifEmpty {
                        if (currentEntryTarget?.stableKey in selectedTargetKeys && currentEntry != null) {
                            listOf(currentEntry.id)
                        } else {
                            emptyList()
                        }
                    }

                val updatedCurrentEntry = currentTarget.applyToPasswordEntry(
                    commonEntry,
                    replicaGroupId = replicaGroupId
                )
                val initialId = saveGroupedPasswordsInternal(
                    originalIds = currentTargetOriginalIds.ifEmpty { originalIds },
                    commonEntry = updatedCurrentEntry,
                    passwords = passwords,
                    customFields = customFields,
                    skipCategoryBinding = true
                )

                if (initialId == null) {
                    Log.e(
                        "PasswordViewModel",
                        "savePasswordsAcrossTargets failed current target=${currentTarget.stableKey} originalIds=$originalIds targets=$requestedTargetKeys"
                    )
                    return@withContext PasswordSaveAcrossTargetsResult(null, emptyList())
                }
                val savedTargetFirstIds = mutableListOf(initialId)

                distinctTargets
                    .filter { target ->
                        target.stableKey != currentTarget.stableKey &&
                            target.stableKey in selectedTargetKeys
                    }
                    .forEach { target ->
                        val existingTargetIds = existingReplicasByKey[target.stableKey]
                            .orEmpty()
                            .sortedBy { it.id }
                            .map { it.id }
                        val replicaEntry = target.applyToPasswordEntry(
                            commonEntry,
                            replicaGroupId = replicaGroupId
                        )
                        val createdId = saveGroupedPasswordsInternal(
                            originalIds = existingTargetIds,
                            commonEntry = replicaEntry,
                            passwords = passwords,
                            customFields = customFields,
                            skipCategoryBinding = true
                        )
                        if (createdId == null) {
                            Log.e(
                                "PasswordViewModel",
                                "savePasswordsAcrossTargets skipped failed target=${target.stableKey}"
                            )
                        } else {
                            savedTargetFirstIds += createdId
                        }
                    }

                val activeReplicas = repository.getAllPasswordEntries()
                    .first()
                    .filter {
                        it.replicaGroupId == replicaGroupId &&
                            !it.isDeleted &&
                            !it.isArchived
                    }
                // 【移动语义】未勾选位置上的旧副本 = 用户要迁出的位置。
                // 与验证器（saveTotpAcrossTargets）对齐：直接删除，而不是保留。
                // 这样「改选库」就是真正的迁移——新库写入、旧库移除，不再留副本。
                val staleReplicas = activeReplicas.filter {
                    it.toStorageTarget().stableKey !in selectedTargetKeys
                }
                if (staleReplicas.isNotEmpty()) {
                    Log.w(
                        "PasswordViewModel",
                        "Removing ${staleReplicas.size} stale password replicas after target change: ids=${staleReplicas.map { it.id }}"
                    )
                    staleReplicas.forEach { repository.deletePasswordEntry(it) }
                }

                    PasswordSaveAcrossTargetsResult(
                        firstPasswordId = initialId,
                        savedPasswordIds = savedTargetFirstIds.distinct()
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "PasswordViewModel",
                    "savePasswordsAcrossTargets crashed originalIds=$originalIds targets=$requestedTargetKeys error=${e::class.java.simpleName}: ${e.message}",
                    e
                )
                PasswordSaveAcrossTargetsResult(null, emptyList())
            }

            onComplete(saveResult.firstPasswordId)
            onCompleteWithIds(saveResult.firstPasswordId, saveResult.savedPasswordIds)
        }
    }

    private data class PasswordSaveAcrossTargetsResult(
        val firstPasswordId: Long?,
        val savedPasswordIds: List<Long>
    )

    private suspend fun canWriteKeePassTargets(targets: List<StorageTarget>): Boolean {
        val dao = localKeePassDatabaseDao ?: return true
        return targets.all { target ->
            val keepassTarget = target as? StorageTarget.KeePass ?: return@all true
            val database = dao.getDatabaseById(keepassTarget.databaseId) ?: return@all false
            database.writeOperationAvailability().canOperate
        }
    }

    private suspend fun canWriteKeePassDatabase(databaseId: Long): Boolean {
        val dao = localKeePassDatabaseDao ?: return true
        val database = dao.getDatabaseById(databaseId) ?: return false
        return database.writeOperationAvailability().canOperate
    }

    private suspend fun saveGroupedPasswordsInternal(
        originalIds: List<Long>,
        commonEntry: PasswordEntry,
        passwords: List<String>,
        customFields: List<CustomFieldDraft> = emptyList(),
        skipCategoryBinding: Boolean
    ): Long? {
        var firstId: Long? = null
        val normalizedPasswords = passwords.map { it.trim() }
        val normalizedInput = normalizedPasswords.filter { it.isNotEmpty() }
        val preservedUnreadablePasswords = if (normalizedInput.isEmpty() && originalIds.isNotEmpty()) {
            originalIds.mapNotNull { id ->
                val existing = repository.getPasswordEntryById(id) ?: return@mapNotNull null
                if (shouldPreserveUnreadableBitwardenPassword(existing, "")) "" else null
            }
        } else {
            emptyList()
        }
        val hasPreservedUnreadablePassword = preservedUnreadablePasswords.isNotEmpty()

        val effectivePasswords = when {
            normalizedInput.isNotEmpty() -> normalizedInput
            commonEntry.loginType.equals("SSO", ignoreCase = true) -> listOf("")
            hasPreservedUnreadablePassword -> preservedUnreadablePasswords
            else -> listOf("")
        }

        val boundCommonEntry = if (skipCategoryBinding) {
            commonEntry
        } else {
            applyCategoryBinding(commonEntry)
        }

        effectivePasswords.forEachIndexed { index, password ->
            if (index < originalIds.size) {
                val id = originalIds[index]
                if (index == 0) firstId = id
                val draftEntry = boundCommonEntry.copy(
                    id = id,
                    password = password
                )
                val existingEntry = repository.getPasswordEntryById(id)
                val updatedEntry = existingEntry?.copy(
                    title = draftEntry.title,
                    website = draftEntry.website,
                    username = draftEntry.username,
                    password = draftEntry.password,
                    notes = draftEntry.notes,
                    isFavorite = draftEntry.isFavorite,
                    appPackageName = draftEntry.appPackageName,
                    appName = draftEntry.appName,
                    email = draftEntry.email,
                    phone = draftEntry.phone,
                    addressLine = draftEntry.addressLine,
                    city = draftEntry.city,
                    state = draftEntry.state,
                    zipCode = draftEntry.zipCode,
                    country = draftEntry.country,
                    creditCardNumber = draftEntry.creditCardNumber,
                    creditCardHolder = draftEntry.creditCardHolder,
                    creditCardExpiry = draftEntry.creditCardExpiry,
                    creditCardCVV = draftEntry.creditCardCVV,
                    categoryId = draftEntry.categoryId,
                    boundNoteId = draftEntry.boundNoteId,
                    keepassDatabaseId = draftEntry.keepassDatabaseId,
                    keepassGroupPath = draftEntry.keepassGroupPath,
                    authenticatorKey = draftEntry.authenticatorKey,
                    passkeyBindings = draftEntry.passkeyBindings,
                    sshKeyData = draftEntry.sshKeyData,
                    loginType = draftEntry.loginType,
                    ssoProvider = draftEntry.ssoProvider,
                    ssoRefEntryId = draftEntry.ssoRefEntryId,
                    replicaGroupId = draftEntry.replicaGroupId,
                    bitwardenVaultId = draftEntry.bitwardenVaultId,
                    bitwardenFolderId = draftEntry.bitwardenFolderId,
                    customIconType = draftEntry.customIconType,
                    customIconValue = draftEntry.customIconValue,
                    customIconUpdatedAt = draftEntry.customIconUpdatedAt
                ) ?: draftEntry
                val entryCustomFields = if (index == 0) customFields else emptyList()
                val updated = updatePasswordEntryInternal(
                    entry = updatedEntry,
                    customFieldsOverride = entryCustomFields,
                    // 归属已经在本函数入口定好（applyCategoryBinding 或 applyToPasswordEntry），
                    // 这里跳过二次绑定，防止"分类未绑定 Bitwarden 文件夹"分支把
                    // 待上传条目的 vaultId 清回本地（撤销用户刚选的存储位置）。
                    skipCategoryBinding = true
                )
                if (!updated) {
                    Log.e(
                        "PasswordViewModel",
                        "saveGroupedPasswords aborted due to password update failure entryId=$id target=${draftEntry.toStorageTarget().stableKey}"
                    )
                    return null
                }
            } else {
                val newEntry = boundCommonEntry.copy(
                    id = 0,
                    password = password
                )
                val entryCustomFields = if (index == 0) customFields else emptyList()
                val newId = createPasswordEntryInternal(
                    entry = newEntry,
                    includeDetailedLog = false,
                    skipCategoryBinding = skipCategoryBinding,
                    customFieldsOverride = entryCustomFields
                )
                if (newId == null) {
                    Log.e("PasswordViewModel", "saveGroupedPasswords aborted due to KeePass write failure")
                    return firstId ?: originalIds.firstOrNull()
                }
                if (index == 0) firstId = newId
            }
        }

        if (originalIds.size > effectivePasswords.size) {
            val toDelete = originalIds.subList(effectivePasswords.size, originalIds.size)
            val entriesToDelete = toDelete.mapNotNull { id -> repository.getPasswordEntryById(id) }
            if (entriesToDelete.isNotEmpty()) {
                deletePasswordEntriesBatch(entriesToDelete)
            }
        }

        firstId?.let { entryId ->
            saveCustomFieldsForEntry(entryId, customFields)
        }

        return firstId
    }

    // =============== 自定义字段相关方法 ===============
    
    /**
     * 获取指定密码条目的自定义字段（Flow）
     */
    fun getCustomFieldsByEntryId(entryId: Long): Flow<List<CustomField>> {
        return customFieldRepository?.getFieldsByEntryId(entryId) ?: flowOf(emptyList())
    }
    
    /**
     * 获取指定密码条目的自定义字段（同步版本）
     */
    suspend fun getCustomFieldsByEntryIdSync(entryId: Long): List<CustomField> {
        return customFieldRepository?.getFieldsByEntryIdSync(entryId) ?: emptyList()
    }
    
    /**
     * 保存密码条目的自定义字段
     * 同时更新密码条目的 updatedAt 以触发同步
     */
    suspend fun saveCustomFieldsForEntry(entryId: Long, fields: List<CustomFieldDraft>) {
        customFieldRepository?.saveFieldsForEntry(entryId, fields)
        
        // 更新密码条目的 updatedAt 以确保 WebDAV 同步能检测到自定义字段的变化
        repository.updatePasswordUpdatedAt(entryId, java.util.Date())
    }

    private suspend fun copyCustomFieldsForEntryCopy(
        sourceEntryId: Long,
        targetEntryId: Long
    ) {
        if (sourceEntryId <= 0 || targetEntryId <= 0 || sourceEntryId == targetEntryId) return
        val fieldRepository = customFieldRepository ?: return
        val fields = fieldRepository.getFieldsByEntryIdSync(sourceEntryId)
            .filter { it.title.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy<CustomField> { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, field ->
                field.copy(
                    id = 0,
                    entryId = targetEntryId,
                    sortOrder = index
                )
            }
        if (fields.isNotEmpty()) {
            fieldRepository.saveFieldsForEntries(mapOf(targetEntryId to fields))
        }
    }
    
    /**
     * 批量获取多个条目的自定义字段（用于列表显示优化）
     */
    suspend fun getCustomFieldsByEntryIds(entryIds: List<Long>): Map<Long, List<CustomField>> {
        return customFieldRepository?.getFieldsByEntryIds(entryIds) ?: emptyMap()
    }

    /**
     * 为选中的密码条目应用同一个手动堆叠分组。
     * 使用内部自定义字段持久化，优先级高于自动堆叠规则。
     *
     * @return 实际写入的条目数量
     */
    suspend fun applyManualStack(entryIds: List<Long>): Int {
        return applyManualStackMode(entryIds, ManualStackMode.STACK)
    }

    /**
     * 设置选中条目的堆叠模式：
     * STACK: 写入同一手动堆叠组
     * AUTO_STACK: 清除手动堆叠/不堆叠标记，回归自动堆叠
     * NEVER_STACK: 标记为永不参与堆叠
     */
    suspend fun applyManualStackMode(entryIds: List<Long>, mode: ManualStackMode): Int {
        val validIds = entryIds.distinct().filter { it > 0L }
        if (validIds.isEmpty()) return 0

        val stackGroupId = if (mode == ManualStackMode.STACK) UUID.randomUUID().toString() else null
        val existingFieldsByEntry = getCustomFieldsByEntryIds(validIds)

        validIds.forEach { entryId ->
            val keptFields = existingFieldsByEntry[entryId]
                .orEmpty()
                .asSequence()
                .filterNot {
                    it.title == MONICA_MANUAL_STACK_GROUP_FIELD_TITLE ||
                        it.title == MONICA_NO_STACK_FIELD_TITLE
                }
                .map { field ->
                    CustomFieldDraft(
                        title = field.title,
                        value = field.value,
                        isProtected = field.isProtected
                    )
                }
                .toMutableList()

            when (mode) {
                ManualStackMode.STACK -> {
                    keptFields += CustomFieldDraft(
                        title = MONICA_MANUAL_STACK_GROUP_FIELD_TITLE,
                        value = stackGroupId.orEmpty(),
                        isProtected = false
                    )
                }
                ManualStackMode.NEVER_STACK -> {
                    keptFields += CustomFieldDraft(
                        title = MONICA_NO_STACK_FIELD_TITLE,
                        value = "1",
                        isProtected = false
                    )
                }
                ManualStackMode.AUTO_STACK -> Unit
            }

            saveCustomFieldsForEntry(entryId, keptFields)
        }

        return validIds.size
    }
    
    /**
     * 搜索包含指定关键词的条目ID（通过自定义字段搜索）
     */
    suspend fun searchEntryIdsByCustomFieldContent(query: String): List<Long> {
        return customFieldRepository?.searchEntryIdsByFieldContent(query) ?: emptyList()
    }
}
