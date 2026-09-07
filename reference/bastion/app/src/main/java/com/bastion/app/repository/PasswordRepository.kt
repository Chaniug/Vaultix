package com.bastion.app.repository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.bastion.app.data.Category
import com.bastion.app.data.CategoryDao
import com.bastion.app.data.PasskeyDao
import com.bastion.app.data.PasswordArchiveSyncMeta
import com.bastion.app.data.PasswordArchiveSyncMetaDao
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.PasswordEntryDao
import com.bastion.app.data.PasswordEntryListItem
import com.bastion.app.data.PasswordHistoryDao
import com.bastion.app.data.PasswordHistoryEntry
import com.bastion.app.data.SecureItemDao
import com.bastion.app.bitwarden.BitwardenMutationStateHelper
import com.bastion.app.data.bitwarden.BitwardenFolder
import com.bastion.app.data.bitwarden.BitwardenFolderDao
import com.bastion.app.data.bitwarden.BitwardenSyncRawEntryRecord
import com.bastion.app.data.bitwarden.BitwardenSyncRawEntryRecordDao
import java.util.Date
import java.util.Locale
/**
 * Repository for password entries
 */
class PasswordRepository(
    private val passwordEntryDao: PasswordEntryDao,
    private val categoryDao: CategoryDao? = null,
    private val bitwardenFolderDao: BitwardenFolderDao? = null,
    private val secureItemDao: SecureItemDao? = null,
    private val passkeyDao: PasskeyDao? = null,
    private val passwordArchiveSyncMetaDao: PasswordArchiveSyncMetaDao? = null,
    private val passwordHistoryDao: PasswordHistoryDao? = null,
    private val bitwardenSyncRawEntryRecordDao: BitwardenSyncRawEntryRecordDao? = null
) {
    fun getAllPasswordEntries(): Flow<List<PasswordEntry>> {
        // Keep semantic behavior the same but avoid the legacy generated callable index path.
        return passwordEntryDao.getActiveEntries()
    }
    suspend fun getAllLocalPasswordEntries(): List<PasswordEntry> {
        return passwordEntryDao.getAllLocalEntries()
    }
    // =============== C.3.3 选项 A：列表专用投影查询 ===============
    // 仅查询列表/卡片所需轻量列（见 PasswordEntryListItem），避免加载 sshKeyData / wifiMetadata /
    // passkeyBindings / 支付卡 / Phase7 PII 等大字段。映射回 PasswordEntry 后对外仍是熟悉的
    // PasswordEntry 类型，列表与卡片 UI 无需改动。注意：这些方法仅用于列表展示路径；导出、自动填充
    // 填充、主密码重加密、去重合并等需要完整实体的路径仍使用上方 SELECT * 方法。
    fun getAllPasswordEntriesListItem(): Flow<List<PasswordEntry>> =
        passwordEntryDao.getAllPasswordEntriesListItem().mapListToEntry()
    fun getPasswordEntriesByCategoryListItem(categoryId: Long): Flow<List<PasswordEntry>> =
        passwordEntryDao.getPasswordEntriesByCategoryListItem(categoryId).mapListToEntry()
    fun getUncategorizedPasswordEntriesListItem(): Flow<List<PasswordEntry>> =
        passwordEntryDao.getUncategorizedPasswordEntriesListItem().mapListToEntry()
    fun getPasswordEntriesByKeePassDatabaseListItem(databaseId: Long): Flow<List<PasswordEntry>> =
        passwordEntryDao.getPasswordEntriesByKeePassDatabaseListItem(databaseId).mapListToEntry()
    fun getPasswordEntriesByKeePassGroupListItem(databaseId: Long, groupPath: String): Flow<List<PasswordEntry>> =
        passwordEntryDao.getPasswordEntriesByKeePassGroupListItem(databaseId, groupPath).mapListToEntry()
    fun getFavoritePasswordEntriesListItem(): Flow<List<PasswordEntry>> =
        passwordEntryDao.getFavoritePasswordEntriesListItem().mapListToEntry()
    fun searchPasswordEntriesListItem(query: String): Flow<List<PasswordEntry>> =
        passwordEntryDao.searchPasswordEntriesListItem(query).mapListToEntry()
    fun getArchivedEntriesListItem(): Flow<List<PasswordEntry>> =
        passwordEntryDao.getArchivedEntriesListItem().mapListToEntry()
    fun getPasswordEntriesByBitwardenVaultListItem(vaultId: Long): Flow<List<PasswordEntry>> =
        passwordEntryDao.getPasswordEntriesByBitwardenVaultListItem(vaultId).mapListToEntry()
    fun getPasswordEntriesByBitwardenFolderListItem(vaultId: Long, folderId: String): Flow<List<PasswordEntry>> =
        passwordEntryDao.getPasswordEntriesByBitwardenFolderListItem(vaultId, folderId).mapListToEntry()
    private fun Flow<List<PasswordEntryListItem>>.mapListToEntry(): Flow<List<PasswordEntry>> =
        map { list -> list.map { it.toPasswordEntry() } }
    fun getBitwardenFoldersByVaultId(vaultId: Long): Flow<List<BitwardenFolder>> {
        return bitwardenFolderDao?.getFoldersByVaultFlow(vaultId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    fun getArchivedEntries(): Flow<List<PasswordEntry>> {
        return passwordEntryDao.getArchivedEntries()
    }
    // Category operations
    fun getAllCategories(): Flow<List<Category>> {
        return categoryDao?.getAllCategories() ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    suspend fun insertCategory(category: Category): Long {
        return categoryDao?.insert(category) ?: -1
    }
    suspend fun updateCategory(category: Category) {
        categoryDao?.update(category)
    }
    suspend fun deleteCategory(category: Category) {
        // Clear category references across all local data types first.
        passwordEntryDao.removeCategoryFromPasswords(category.id)
        secureItemDao?.removeCategoryFromItems(category.id)
        passkeyDao?.removeCategoryFromPasskeys(category.id)
        categoryDao?.delete(category)
    }
    suspend fun updateCategorySortOrder(id: Long, sortOrder: Int) {
        categoryDao?.updateSortOrder(id, sortOrder)
    }
    suspend fun getCategoryById(id: Long): Category? {
        return categoryDao?.getCategoryById(id)
    }
    suspend fun updateCategoryForPasswords(ids: List<Long>, categoryId: Long?) {
        passwordEntryDao.updateCategoryForPasswords(ids, categoryId)
    }
    suspend fun updateBoundNoteId(id: Long, noteId: Long?) {
        passwordEntryDao.updateBoundNoteId(id, noteId)
    }
    suspend fun clearBoundNoteReferences(noteId: Long) {
        passwordEntryDao.clearBoundNoteReferences(noteId)
    }
    suspend fun bindPasswordsToBitwardenFolder(ids: List<Long>, vaultId: Long, folderId: String) {
        passwordEntryDao.bindPasswordsToBitwardenFolder(ids, vaultId, folderId)
    }
    suspend fun clearPendingBitwardenBinding(ids: List<Long>) {
        passwordEntryDao.clearPendingBitwardenBinding(ids)
    }
    suspend fun bindCategoryToBitwarden(categoryId: Long, vaultId: Long, folderId: String) {
        passwordEntryDao.bindCategoryToBitwarden(categoryId, vaultId, folderId)
    }
    suspend fun updateKeePassDatabaseForPasswords(ids: List<Long>, databaseId: Long?) {
        passwordEntryDao.updateKeePassDatabaseForPasswords(ids, databaseId)
    }
    suspend fun updateKeePassGroupForPasswords(ids: List<Long>, databaseId: Long, groupPath: String) {
        passwordEntryDao.updateKeePassGroupForPasswords(ids, databaseId, groupPath)
    }
    suspend fun clearBitwardenBindingForPasswords(ids: List<Long>) {
        passwordEntryDao.clearBitwardenBindingForPasswords(ids)
    }
    fun searchPasswordEntries(query: String): Flow<List<PasswordEntry>> {
        return passwordEntryDao.searchPasswordEntries(query)
    }
    suspend fun getPasswordEntryById(id: Long): PasswordEntry? {
        return passwordEntryDao.getPasswordEntryById(id)
    }
    suspend fun getPasswordEntryByKeePassUuid(databaseId: Long, entryUuid: String): PasswordEntry? {
        return passwordEntryDao.findByKeePassEntryUuid(databaseId, entryUuid)
    }
    suspend fun getPasswordsByIds(ids: List<Long>): List<PasswordEntry> {
        return passwordEntryDao.getPasswordsByIds(ids)
    }
    suspend fun getActivePasswordsByIds(ids: List<Long>): List<PasswordEntry> {
        return passwordEntryDao.getActivePasswordsByIds(ids)
    }
    suspend fun insertPasswordEntry(entry: PasswordEntry): Long {
        val normalizedEntry = BitwardenMutationStateHelper.normalizePasswordInsert(entry)
        return passwordEntryDao.insertPasswordEntry(normalizedEntry)
    }
    suspend fun insertPasswordEntries(entries: List<PasswordEntry>): List<Long> {
        if (entries.isEmpty()) return emptyList()
        val normalizedEntries = entries.map(BitwardenMutationStateHelper::normalizePasswordInsert)
        return passwordEntryDao.insertPasswordEntries(normalizedEntries)
    }
    suspend fun updatePasswordEntry(entry: PasswordEntry) {
        val existingEntry = if (entry.id != 0L) passwordEntryDao.getPasswordEntryById(entry.id) else null
        val normalizedEntry = BitwardenMutationStateHelper.normalizePasswordUpdate(existingEntry, entry)
        passwordEntryDao.updatePasswordEntry(normalizedEntry)
    }
    suspend fun updatePasswordEntries(entries: List<PasswordEntry>) {
        if (entries.isEmpty()) return
        val existingEntriesById = passwordEntryDao
            .getPasswordsByIds(entries.map { it.id })
            .associateBy { it.id }
        val normalizedEntries = entries.map { entry ->
            val existingEntry = existingEntriesById[entry.id]
            BitwardenMutationStateHelper.normalizePasswordUpdate(existingEntry, entry)
        }
        passwordEntryDao.updatePasswordEntries(normalizedEntries)
    }
    suspend fun updatePasswordUpdatedAt(id: Long, updatedAt: java.util.Date) {
        passwordEntryDao.updateUpdatedAt(id, updatedAt)
    }
    suspend fun deletePasswordEntry(entry: PasswordEntry) {
        passwordEntryDao.deletePasswordEntry(entry)
    }
    suspend fun deletePasswordEntries(entries: List<PasswordEntry>) {
        if (entries.isEmpty()) return
        passwordEntryDao.deletePasswordEntries(entries)
    }
    suspend fun deletePasswordEntryById(id: Long) {
        passwordEntryDao.deletePasswordEntryById(id)
    }
    suspend fun archivePasswordById(id: Long) {
        passwordEntryDao.archiveById(id)
    }
    suspend fun archivePasswordsByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        passwordEntryDao.archiveByIds(ids)
    }
    suspend fun unarchivePasswordById(id: Long) {
        passwordEntryDao.unarchiveById(id)
    }
    suspend fun unarchivePasswordsByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        passwordEntryDao.unarchiveByIds(ids)
    }
    suspend fun getArchiveSyncMeta(entryId: Long): PasswordArchiveSyncMeta? {
        return passwordArchiveSyncMetaDao?.getByEntryId(entryId)
    }
    suspend fun upsertArchiveSyncMeta(meta: PasswordArchiveSyncMeta) {
        passwordArchiveSyncMetaDao?.upsert(meta)
    }
    suspend fun deleteArchiveSyncMeta(entryId: Long) {
        passwordArchiveSyncMetaDao?.deleteByEntryId(entryId)
    }
    suspend fun deleteArchiveSyncMeta(entryIds: List<Long>) {
        if (entryIds.isEmpty()) return
        passwordArchiveSyncMetaDao?.deleteByEntryIds(entryIds)
    }
    fun getPasswordHistoryByEntryId(entryId: Long): Flow<List<PasswordHistoryEntry>> {
        return passwordHistoryDao?.getHistoryByEntryId(entryId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    fun getBitwardenSyncRawRecords(
        vaultId: Long,
        cipherId: String
    ): Flow<List<BitwardenSyncRawEntryRecord>> {
        if (cipherId.isBlank()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return bitwardenSyncRawEntryRecordDao?.getByCipherFlow(vaultId, cipherId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    suspend fun getPasswordHistoryByEntryIdSync(entryId: Long): List<PasswordHistoryEntry> {
        return passwordHistoryDao?.getHistoryByEntryIdSync(entryId) ?: emptyList()
    }
    suspend fun insertPasswordHistory(entry: PasswordHistoryEntry): Long {
        return passwordHistoryDao?.insert(entry) ?: -1L
    }
    suspend fun updatePasswordHistoryPassword(historyId: Long, password: String) {
        passwordHistoryDao?.updatePasswordById(historyId, password)
    }
    suspend fun trimPasswordHistory(entryId: Long, limit: Int) {
        passwordHistoryDao?.trimToLimit(entryId, limit)
    }
    suspend fun deletePasswordHistoryById(id: Long) {
        passwordHistoryDao?.deleteById(id)
    }
    suspend fun clearPasswordHistory(entryId: Long) {
        passwordHistoryDao?.deleteByEntryId(entryId)
    }
    suspend fun toggleFavorite(id: Long, isFavorite: Boolean) {
        val existingEntry = passwordEntryDao.getPasswordEntryById(id) ?: return
        updatePasswordEntry(
            existingEntry.copy(
                isFavorite = isFavorite,
                updatedAt = Date()
            )
        )
    }
    suspend fun updateGroupCoverStatus(id: Long, isGroupCover: Boolean) {
        passwordEntryDao.updateGroupCoverStatus(id, isGroupCover)
    }
    suspend fun setGroupCover(id: Long, website: String) {
        passwordEntryDao.setGroupCover(id, website)
    }
    suspend fun updateSortOrders(items: List<Pair<Long, Int>>) {
        passwordEntryDao.updateSortOrders(items)
    }
    suspend fun getPasswordEntriesCount(): Int {
        return passwordEntryDao.getPasswordEntriesCount()
    }
    /**
     * 获取本地密码条目数量（排除 KeePass 和 Bitwarden 的数据）
     */
    suspend fun getLocalEntriesCount(): Int {
        return passwordEntryDao.getLocalEntriesCount()
    }
    /**
     * 获取本地已删除密码条目数量（排除 KeePass 和 Bitwarden 的数据）
     */
    suspend fun getLocalDeletedEntriesCount(): Int {
        return passwordEntryDao.getLocalDeletedEntriesCount()
    }
    suspend fun deleteAllPasswordEntries() {
        passwordEntryDao.deleteAllPasswordEntries()
    }
    /**
     * 检查是否存在重复的密码条目
     */
    suspend fun isDuplicateEntry(title: String, username: String, website: String): Boolean {
        return passwordEntryDao.findDuplicateEntry(title, username, website) != null
    }
    /**
     * 获取重复的密码条目
     */
    suspend fun getDuplicateEntry(title: String, username: String, website: String): PasswordEntry? {
        return passwordEntryDao.findDuplicateEntry(title, username, website)
    }
    /**
     * 仅在 Bastion 本地库范围内查重（排除 KeePass / Bitwarden）
     */
    suspend fun getLocalDuplicateEntry(title: String, username: String, website: String): PasswordEntry? {
        return passwordEntryDao.findLocalDuplicateByKey(
            title.lowercase(Locale.ROOT),
            username.lowercase(Locale.ROOT),
            website.lowercase(Locale.ROOT)
        )
    }
    suspend fun getDuplicateCandidates(title: String, username: String, website: String): List<PasswordEntry> {
        return passwordEntryDao.findActiveDuplicateCandidatesByKey(
            title.lowercase(Locale.ROOT),
            username.lowercase(Locale.ROOT),
            website.lowercase(Locale.ROOT)
        )
    }
    suspend fun getLocalDuplicateCandidates(title: String, username: String, website: String): List<PasswordEntry> {
        return passwordEntryDao.findLocalDuplicateCandidatesByKey(
            title.lowercase(Locale.ROOT),
            username.lowercase(Locale.ROOT),
            website.lowercase(Locale.ROOT)
        )
    }
    suspend fun getDuplicateEntryInKeePass(
        databaseId: Long,
        title: String,
        username: String,
        website: String,
        groupPath: String?
    ): PasswordEntry? {
        return passwordEntryDao.findDuplicateEntryInKeePass(databaseId, title, username, website, groupPath)
    }
    suspend fun getPasswordEntriesByKeePassDatabaseSync(databaseId: Long): List<PasswordEntry> {
        return passwordEntryDao.getPasswordEntriesByKeePassDatabaseSync(databaseId)
    }
    /**
     * 按包名和用户名查询密码(自动填充保存功能)
     */
    suspend fun findByPackageAndUsername(packageName: String, username: String): PasswordEntry? {
        return passwordEntryDao.findByPackageAndUsername(packageName, username)
    }
    /**
     * 按网站和用户名查询密码(自动填充保存功能)
     */
    suspend fun findByDomainAndUsername(domain: String, username: String): PasswordEntry? {
        return passwordEntryDao.findByDomainAndUsername(domain, username)
    }
    /**
     * 按包名查询所有密码
     */
    suspend fun findByPackageName(packageName: String): List<PasswordEntry> {
        return passwordEntryDao.findByPackageName(packageName)
    }
    /**
     * 按网站域名查询所有密码
     */
    suspend fun findByDomain(domain: String): List<PasswordEntry> {
        return passwordEntryDao.findByDomain(domain)
    }
    /**
     * 检查是否存在完全相同的密码
     */
    suspend fun findExactMatch(packageName: String, username: String, encryptedPassword: String): PasswordEntry? {
        return passwordEntryDao.findExactMatch(packageName, username, encryptedPassword)
    }
    suspend fun updateAppAssociationByWebsite(website: String, packageName: String, appName: String) {
        passwordEntryDao.updateAppAssociationByWebsite(website, packageName, appName)
    }
    suspend fun updateAppAssociationByTitle(title: String, packageName: String, appName: String) {
        passwordEntryDao.updateAppAssociationByTitle(title, packageName, appName)
    }
    /**
     * 更新绑定的验证器密钥
     */
    suspend fun updateAuthenticatorKey(id: Long, authenticatorKey: String) {
        val existing = passwordEntryDao.getPasswordEntryById(id) ?: return
        updatePasswordEntry(
            existing.copy(
                authenticatorKey = authenticatorKey,
                updatedAt = Date()
            )
        )
    }
    /**
     * 更新绑定的通行密钥元数据
     */
    suspend fun updatePasskeyBindings(id: Long, passkeyBindings: String) {
        val existing = passwordEntryDao.getPasswordEntryById(id) ?: return
        updatePasswordEntry(
            existing.copy(
                passkeyBindings = passkeyBindings,
                updatedAt = Date()
            )
        )
    }
}
/**
 * C.3.3 选项 A：将列表投影 [PasswordEntryListItem] 映射回完整 [PasswordEntry]。
 *
 * 列表路径只读取投影字段；未投影的大字段（sshKeyData / wifiMetadata / passkeyBindings /
 * 支付卡 / Phase7 PII / ssoRefEntryId / replicaGroupId / boundNoteId 等）以实体默认值填充——
 * 列表展示、卡片、内存过滤、密码解密指示均不依赖这些字段，因此行为保持一致。
 * （keepassEntryUuid / bitwardenCipherId 已纳入投影，以保证去重键与「仅本地」判定结果不变。）
 * 需要完整实体的路径（导出、自动填充填充、主密码重加密、去重合并）请勿使用此方法，
 * 应走 Repository 的 SELECT * 方法重新拉取。
 */
private fun PasswordEntryListItem.toPasswordEntry(): PasswordEntry = PasswordEntry(
    id = id,
    title = title,
    website = website,
    username = username,
    password = password,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isFavorite = isFavorite,
    sortOrder = sortOrder,
    isGroupCover = isGroupCover,
    appPackageName = appPackageName,
    appName = appName,
    categoryId = categoryId,
    keepassDatabaseId = keepassDatabaseId,
    keepassGroupPath = keepassGroupPath,
    authenticatorKey = authenticatorKey,
    loginType = loginType,
    ssoProvider = ssoProvider,
    customIconType = customIconType,
    customIconValue = customIconValue,
    isDeleted = isDeleted,
    isArchived = isArchived,
    bitwardenVaultId = bitwardenVaultId,
    bitwardenLocalModified = bitwardenLocalModified,
    bitwardenFolderId = bitwardenFolderId,
    keepassEntryUuid = keepassEntryUuid,
    bitwardenCipherId = bitwardenCipherId
)
