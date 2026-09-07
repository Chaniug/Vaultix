package com.bastion.app.bitwarden.service

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.bastion.app.attachments.AttachmentContainer
import com.bastion.app.bitwarden.BitwardenVaultPremiumStore
import com.bastion.app.bitwarden.api.*
import com.bastion.app.bitwarden.BitwardenVaultIdentity
import com.bastion.app.bitwarden.mapper.BitwardenSendMapper
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import com.bastion.app.bitwarden.sync.EmptyVaultProtection
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.OperationLogItemType
import com.bastion.app.data.model.LOGIN_TYPE_SSH_KEY
import com.bastion.app.data.model.SshKeyData
import com.bastion.app.data.model.SshKeyDataCodec
import com.bastion.app.data.bitwarden.*
import com.bastion.app.security.SecurityManager
import com.bastion.app.utils.PasswordWebsiteCodec
import com.bastion.app.utils.FieldChange
import com.bastion.app.utils.OperationLogger
import com.bastion.app.util.TotpDataResolver
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

/**
 * Bitwarden 同步服务
 * 
 * 负责:
 * 1. 全量同步 - 首次登录或强制刷新
 * 2. 增量同步 - 基于 revision date 的差异同步
 * 3. 冲突检测和处理
 * 4. 离线操作队列管理
 * 
 * 安全规则:
 * - 同步失败时保留本地数据，不删除
 * - 冲突时优先保留本地修改，并备份服务器版本
 * - 所有数据库操作使用事务
 */
class BitwardenSyncService(
    private val context: Context,
    private val apiManager: BitwardenApiManager = BitwardenApiManager()
) {
    
    companion object {
        private const val TAG = "BitwardenSyncService"
        private val CIPHER_STRING_PATTERN =
            Regex("^[0-9]+\\.[A-Za-z0-9+/_=-]+\\|[A-Za-z0-9+/_=-]+(?:\\|[A-Za-z0-9+/_=-]+)?$")
    }
    
    private val database = PasswordDatabase.getDatabase(context)
    private val vaultDao = database.bitwardenVaultDao()
    private val folderDao = database.bitwardenFolderDao()
    private val sendDao = database.bitwardenSendDao()
    private val conflictDao = database.bitwardenConflictBackupDao()
    private val pendingOpDao = database.bitwardenPendingOperationDao()
    private val passwordEntryDao = database.passwordEntryDao()
    private val secureItemDao = database.secureItemDao()
    private val passkeyDao = database.passkeyDao()
    private val customFieldDao = database.customFieldDao()
    private val securityManager = SecurityManager(context)
    
    // 多类型 Cipher 同步处理器
    private val cipherSyncProcessor = CipherSyncProcessor(context)
    private val cipherUploadProcessor = CipherUploadProcessor(context, apiManager)

    // 附件元数据对齐器（只对齐元数据 + 清理本地孤儿缓存，不下载字节）
    private val attachmentReconciler = AttachmentContainer.bitwardenReconciler(context)
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        runCatchingObserved { OperationLogger.init(context.applicationContext) }
        runCatchingObserved { BitwardenDiagLogger.initialize(context.applicationContext) }
    }

    private data class ParsedLoginUris(
        val website: String = "",
        val appPackageName: String = ""
    )

    private fun normalizeWebsiteKey(rawWebsite: String): String {
        return PasswordWebsiteCodec.normalizeForKey(normalizeBitwardenWebsite(rawWebsite))
    }
    
    /**
     * 执行同步（全量或增量）
     * 
     * @param vault Vault 配置
     * @param accessToken 访问令牌
     * @param symmetricKey 对称加密密钥
     * @param sinceRevisionDate 非空 = 增量同步，只拉该时间点之后的变更；空 = 全量
     */
    suspend fun fullSync(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey,
        sinceRevisionDate: String? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        val isIncremental = sinceRevisionDate != null
        android.util.Log.i(TAG, "Starting ${if (isIncremental) "incremental" else "full"} sync for vault ${vault.id}")

        try {
                val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.sync(
                authorization = "Bearer $accessToken",
                sinceRevisionDate = sinceRevisionDate
            )
            
            if (!response.isSuccessful) {
                val errorPayload = runCatchingObserved { response.errorBody()?.string() }.getOrNull()
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "sync_full",
                    method = "GET",
                    endpoint = "/sync?excludeDomains=true",
                    requestBody = null,
                    responseCode = response.code(),
                    responseBody = errorPayload,
                    success = false,
                    error = "sync failed: ${response.code()} ${response.message()}"
                )
                return@withContext SyncResult.Error(
                    "Sync failed: ${response.code()} ${response.message()}"
                )
            }
            
            val syncResponse = response.body()
            if (syncResponse == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "sync_full",
                    method = "GET",
                    endpoint = "/sync?excludeDomains=true",
                    requestBody = null,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "empty sync response"
                )
                return@withContext SyncResult.Error("Empty sync response")
            }

            // P0 诊断：探查服务端是否真的支持增量同步。Vaultwarden 会忽略 sinceRevisionDate，
            // 每次仍返回整个保险库（ciphers 数≈全量）。真机日志据此确认根因，判断是否需回退全量。
            val contentLength = response.headers()["Content-Length"]
            android.util.Log.i(
                TAG,
                "sync probe: isIncremental=$isIncremental ciphers=${syncResponse.ciphers.size} " +
                    "revisionDate=${syncResponse.revisionDate} contentLength=$contentLength"
            )

            // ===== 空 Vault 保护检查（仅全量同步执行：增量响应里 serverCipherCount 只是变更数，会误判）=====
            if (!isIncremental) {
                val serverCipherCount = syncResponse.ciphers.size
                val localCipherCount = passwordEntryDao.getBitwardenEntriesCount(vault.id)
                val isFirstSync = vault.lastSyncAt == null

                val protectionResult = EmptyVaultProtection.checkSyncAllowed(
                    vaultId = vault.id,
                    localCipherCount = localCipherCount,
                    serverCipherCount = serverCipherCount,
                    isFirstSync = isFirstSync
                )

                when (protectionResult) {
                    is EmptyVaultProtection.CheckResult.Blocked -> {
                        android.util.Log.w(TAG, "⚠️ 空 Vault 保护触发: ${protectionResult.reason}")
                        // 发送警告事件
                        EmptyVaultProtection.emitEmptyVaultDetected(
                            vaultId = vault.id,
                            localCount = protectionResult.localCount,
                            serverCount = protectionResult.serverCount
                        )
                        return@withContext SyncResult.EmptyVaultBlocked(
                            localCount = protectionResult.localCount,
                            serverCount = protectionResult.serverCount,
                            reason = protectionResult.reason
                        )
                    }
                    is EmptyVaultProtection.CheckResult.FirstSyncAllowed -> {
                        android.util.Log.i(TAG, "首次同步，允许空 Vault")
                    }
                    is EmptyVaultProtection.CheckResult.Allowed -> {
                        android.util.Log.d(TAG, "同步检查通过")
                    }
                }

                // 额外检查：是否有显著数据丢失风险
                if (EmptyVaultProtection.checkSignificantDataLoss(localCipherCount, serverCipherCount)) {
                    android.util.Log.w(TAG, "⚠️ 检测到潜在数据丢失: 本地 $localCipherCount 条 → 服务器 $serverCipherCount 条")
                    // 这里不阻止同步，但记录警告
                }
            }
            // ===== 空 Vault 保护检查结束 =====
            
            // 处理同步数据
            val result = processSyncResponse(vault, syncResponse, symmetricKey, isIncremental)
            
            // 更新 Vault 同步状态
            val now = System.currentTimeMillis()
            val profileIdentity = BitwardenVaultIdentity.createProfileIdentity(
                serverUrl = vault.serverUrl,
                profile = syncResponse.profile,
                fallbackEmail = vault.email
            )
            vaultDao.updateIdentityAndSyncStatus(
                vaultId = vault.id,
                email = profileIdentity.email,
                canonicalEmail = profileIdentity.canonicalEmail,
                userId = profileIdentity.userId,
                accountKey = profileIdentity.accountKey,
                displayName = profileIdentity.displayName,
                lastSyncAt = now,
                // 增量游标优先用 sync 响应顶层 RevisionDate（若服务端不返回则回退 securityStamp）
                revisionDate = syncResponse.revisionDate ?: syncResponse.profile.securityStamp
            )
            // 记录 profile.premium 供附件 UI 判断（批量移动弹窗 / 上传按钮启用态）
            BitwardenVaultPremiumStore.setPremium(
                context = context,
                vaultId = vault.id,
                premium = syncResponse.profile.hasPremium
            )
            
            android.util.Log.i(TAG, "Full sync completed: $result")
            result
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Full sync failed", e)
            SyncResult.Error("Sync failed: ${e.message}")
        }
    }
    
    /**
     * 处理同步响应数据
     */
    private suspend fun processSyncResponse(
        vault: BitwardenVault,
        response: SyncResponse,
        symmetricKey: SymmetricCryptoKey,
        isIncremental: Boolean = false
    ): SyncResult {
        // Send 防回收的双重保护基线：本次 sync 启动时刻。
        // 任何 created_at >= 这个值的 send，都会被 deleteNotInProtectingDirty 视作"本次 sync 之后才出现"。
        val syncStartedAtMs = System.currentTimeMillis()
        var foldersAdded = 0
        var ciphersAdded = 0
        var ciphersUpdated = 0
        var conflictsDetected = 0
        var skippedDueToLocalDirty = 0
        var sendsSynced = 0
        val activeServerCipherIds = response.ciphers
            .asSequence()
            .filter { it.deletedDate == null }
            .map { it.id }
            .toList()

        // 整段 DB 写库包进单事务：千条 cipher 不会变成千次 fsync，
        // 自建服务器/手机闪存下大 vault 同步显著加速
        val successResult: SyncResult = try {
            database.withTransaction {
                // 1. 同步文件夹
                response.folders.forEach { folderApi ->
                    try {
                        val existingFolder = folderDao.getFolderByBitwardenId(folderApi.id)
                        if (existingFolder != null &&
                            existingFolder.revisionDate == folderApi.revisionDate &&
                            existingFolder.encryptedName == folderApi.name
                        ) {
                            return@forEach
                        }

                        val decryptedName = decryptFolderName(folderApi.name, symmetricKey)

                        if (existingFolder == null) {
                            // 新文件夹
                            folderDao.upsert(
                                BitwardenFolder(
                                    vaultId = vault.id,
                                    bitwardenFolderId = folderApi.id,
                                    name = decryptedName,
                                    encryptedName = folderApi.name,
                                    revisionDate = folderApi.revisionDate
                                )
                            )
                            foldersAdded++
                        } else {
                            // 更新文件夹
                            folderDao.update(
                                existingFolder.copy(
                                    name = decryptedName,
                                    encryptedName = folderApi.name,
                                    revisionDate = folderApi.revisionDate,
                                    lastSyncedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to sync folder ${folderApi.id}: ${e.message}")
                    }
                }

                // 2. 同步 Ciphers (使用新的多类型处理器)
                response.ciphers.forEach { cipherApi ->
                    try {
                        // 使用 CipherSyncProcessor 处理所有类型
                        val result = cipherSyncProcessor.syncCipherFromServer(
                            vault = vault,
                            cipher = cipherApi,
                            symmetricKey = symmetricKey
                        )
                        when (result) {
                            is CipherSyncResult.Added -> ciphersAdded++
                            is CipherSyncResult.Updated -> ciphersUpdated++
                            is CipherSyncResult.Conflict -> {
                                conflictsDetected++
                                // BUG-4 修复：让真正的并发冲突落盘备份，避免服务器侧编辑在下次整体上传时被静默覆盖丢失。
                                runCatchingObserved {
                                    val localEntry = passwordEntryDao.getByBitwardenCipherIdInVault(
                                        vaultId = vault.id,
                                        cipherId = cipherApi.id
                                    )
                                    if (localEntry != null) {
                                        createConflictBackup(
                                            vault = vault,
                                            entry = localEntry,
                                            serverCipher = cipherApi,
                                            conflictType = BitwardenConflictBackup.TYPE_CONCURRENT_EDIT
                                        )
                                    }
                                }
                            }
                            is CipherSyncResult.Skipped -> {
                                if (result.reason == "Local changes pending upload") {
                                    skippedDueToLocalDirty++
                                }
                            }
                            is CipherSyncResult.Error -> {
                                android.util.Log.w(TAG, "Cipher sync error: ${result.message}")
                            }
                        }
                        // 附件元数据对齐：仅对已有本地 PasswordEntry 的 cipher 执行
                        val remoteUnchanged =
                            result is CipherSyncResult.Skipped &&
                                result.reason == CipherSyncProcessor.SKIP_REMOTE_UNCHANGED
                        val shouldReconcileAttachments =
                            cipherApi.attachments != null &&
                                (!remoteUnchanged || cipherApi.attachments.isNotEmpty())
                        if (shouldReconcileAttachments) {
                            runCatchingObserved {
                                val localEntry = passwordEntryDao.getByBitwardenCipherIdInVault(
                                    vaultId = vault.id,
                                    cipherId = cipherApi.id
                                )
                                if (localEntry != null) {
                                    attachmentReconciler.reconcile(
                                        passwordId = localEntry.id,
                                        remoteAttachments = cipherApi.attachments
                                    )
                                }
                            }.onFailure { err ->
                                android.util.Log.w(TAG, "Attachment reconcile failed for ${cipherApi.id}: ${err.message}")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to sync cipher ${cipherApi.id}: ${e.message}")
                    }
                }

                // 2.1 清理服务器已不存在的本地 Cipher（delete-wins）+ 3. 文件夹清理
                // 仅全量同步执行：增量响应只含变更条目，不能按 not-in 清理，
                // 否则会把本次未返回的本地条目全部误删；增量删除由 syncCipherFromServer
                // 对 deletedDate 非空条目的软删逻辑处理。
                if (!isIncremental) {
                    if (activeServerCipherIds.isEmpty()) {
                        // 三者都必须保留本地未上传（cipher_id 为空）的记录：
                        // 服务器返回空列表≠本地新数据可丢弃，防止异常空响应清空用户数据。
                        passwordEntryDao.deleteAllSyncedBitwardenEntries(vault.id)
                        secureItemDao.deleteAllSyncedBitwardenEntries(vault.id)
                        passkeyDao.deleteAllSyncedBitwardenPasskeys(vault.id)
                    } else {
                        passwordEntryDao.deleteBitwardenEntriesNotIn(vault.id, activeServerCipherIds)
                        secureItemDao.deleteBitwardenEntriesNotIn(vault.id, activeServerCipherIds)
                        passkeyDao.deleteBitwardenEntriesNotIn(vault.id, activeServerCipherIds)
                    }

                    // 3. 清理已删除的文件夹 (服务器上不存在的)
                    val serverFolderIds = response.folders.map { it.id }
                    folderDao.deleteNotIn(vault.id, serverFolderIds)
                }

                // 4. 同步 Sends
                response.sends?.forEach { sendApi ->
                    try {
                        val synced = syncSend(vault, sendApi, symmetricKey)
                        if (synced) {
                            sendsSynced++
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to sync send ${sendApi.id}: ${e.message}")
                    }
                }
                response.sends?.let { sends ->
                    if (isIncremental) {
                        // 增量模式：只按 deletedDate 删除，不做 not-in 清理（响应只有变更）
                        sends.filter { it.deletedDate != null }.forEach { deletedSend ->
                            runCatchingObserved {
                                sendDao.deleteBySendId(vault.id, deletedSend.id)
                            }.onFailure { err ->
                                android.util.Log.w(TAG, "Incremental send delete failed for ${deletedSend.id}: ${err.message}")
                            }
                        }
                    } else {
                        val serverSendIds = sends.map { it.id }
                        // 双重保护：保留 is_dirty=1 行（写后读不一致）+ 本次 sync 之后落地的行（兜底）
                        if (serverSendIds.isEmpty()) {
                            sendDao.deleteByVaultProtectingDirty(vault.id, syncStartedAtMs)
                        } else {
                            sendDao.deleteNotInProtectingDirty(vault.id, serverSendIds, syncStartedAtMs)
                        }
                    }
                }

                if (sendsSynced > 0) {
                    android.util.Log.i(TAG, "Sends synced: $sendsSynced")
                }

                SyncResult.Success(
                    foldersAdded = foldersAdded,
                    ciphersAdded = ciphersAdded,
                    ciphersUpdated = ciphersUpdated,
                    conflictsDetected = conflictsDetected,
                    skippedDueToLocalDirty = skippedDueToLocalDirty
                )
            }
        } catch (e: Exception) {
            throw e
        }
        return successResult
    }

    private suspend fun syncSend(
        vault: BitwardenVault,
        sendApi: SendApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val mapped = BitwardenSendMapper.mapApiToEntity(
            vaultId = vault.id,
            serverUrl = vault.serverUrl,
            api = sendApi,
            vaultKey = symmetricKey
        ) ?: return false

        val existing = sendDao.getBySendId(vault.id, mapped.bitwardenSendId)
        val now = System.currentTimeMillis()
        val entity = if (existing == null) {
            mapped.copy(
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                // 服务器返回了这条 Send，说明已对账完成
                isDirty = false
            )
        } else {
            mapped.copy(
                id = existing.id,
                createdAt = existing.createdAt,
                updatedAt = now,
                lastSyncedAt = now,
                // 一旦服务器确认这条 send，本地 dirty 标记失效
                isDirty = false
            )
        }
        sendDao.upsert(entity)
        return true
    }
    
    /**
     * 同步单个 Cipher
     */
    private suspend fun syncCipher(
        vault: BitwardenVault,
        cipherApi: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): CipherSyncResult {
        // 只处理 Login 类型的 Cipher (类型 1)
        if (cipherApi.type != 1) {
            return CipherSyncResult.Skipped("Only login ciphers are supported")
        }
        
        // 跳过已删除的 Cipher
        if (cipherApi.deletedDate != null) {
            return CipherSyncResult.Skipped("Cipher is deleted")
        }
        
        // 查找本地是否存在此 Cipher
        val existingEntry = passwordEntryDao.getByBitwardenCipherIdInVault(vault.id, cipherApi.id)

        if (existingEntry != null && existingEntry.isDeleted) {
            // 本地已墓碑化（待删除队列执行中/待重试）：本地删除意图优先。
            // 若此处继续走下行合并，墓碑的 bitwardenLocalModified=true 会与服务器
            // revisionDate 比对产生伪 CONCURRENT_EDIT 冲突，或把墓碑"复活"更新。
            // 等待 OP_DELETE 重试成功后，该 cipher 将从下行列表消失（deletedDate 过滤）。
            return CipherSyncResult.Skipped("Local entry is tombstoned (pending delete)")
        }

        if (existingEntry == null) {
            // 新建条目
            val newEntry = cipherToPasswordEntry(vault, cipherApi, symmetricKey)
            if (newEntry != null) {
                passwordEntryDao.insert(newEntry)
                return CipherSyncResult.Added
            } else {
                return CipherSyncResult.Error("Failed to convert cipher")
            }
        } else {
            // 检查是否有本地修改
            if (existingEntry.bitwardenLocalModified) {
                // 检测冲突
                if (existingEntry.bitwardenRevisionDate != cipherApi.revisionDate) {
                    // 版本冲突 - 创建备份
                    createConflictBackup(
                        vault = vault,
                        entry = existingEntry,
                        serverCipher = cipherApi,
                        conflictType = BitwardenConflictBackup.TYPE_CONCURRENT_EDIT
                    )
                    return CipherSyncResult.Conflict
                }
            }
            
            // 更新条目
            val updatedEntry = updatePasswordEntryFromCipher(
                existingEntry, vault.id, cipherApi, symmetricKey
            )
            if (updatedEntry != null) {
                passwordEntryDao.update(updatedEntry)
                return CipherSyncResult.Updated
            } else {
                return CipherSyncResult.Error("Failed to update entry")
            }
        }
    }
    
    /**
     * 将 Cipher 转换为 PasswordEntry
     */
    private fun cipherToPasswordEntry(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): PasswordEntry? {
        try {
            val login = cipher.login ?: return null
            
            // 解密字段
            val name = decryptString(cipher.name, symmetricKey) ?: "Untitled"
            val username = decryptString(login.username, symmetricKey) ?: ""
            val decryptedPassword = decryptString(login.password, symmetricKey)
            if (!login.password.isNullOrBlank() && decryptedPassword == null) {
                android.util.Log.w(TAG, "Skip cipher ${cipher.id}: password decrypt failed")
                return null
            }
            val password = decryptedPassword ?: ""
            val notes = decryptString(cipher.notes, symmetricKey) ?: ""
            val totp = decryptString(login.totp, symmetricKey) ?: ""
            val parsedUris = parseLoginUris(login.uris, symmetricKey)
            val customFields = parsePasswordCustomFieldMap(cipher.fields, symmetricKey)
            val encryptedPassword = encryptBitwardenPasswordForOfflineDisplay(password, cipher.id)
            val sshKeyData = buildSshKeyDataFromCustomFields(customFields)
            // T2: WIFI / SSO 扩展元数据还原
            val remoteWifiMetadata = customFields["bastion_wifi_data"].orEmpty()
            val remoteSsoProvider = customFields["bastion_sso_provider"].orEmpty()
            val remoteLoginType = when {
                sshKeyData.isNotBlank() -> LOGIN_TYPE_SSH_KEY
                customFields["bastion_login_type"].equals("WIFI", ignoreCase = true) -> "WIFI"
                customFields["bastion_login_type"].equals("SSO", ignoreCase = true) -> "SSO"
                else -> "PASSWORD"
            }

            return PasswordEntry(
                title = name,
                website = parsedUris.website,
                username = username,
                password = encryptedPassword,
                notes = notes,
                authenticatorKey = encodeBitwardenTotpForLocalStorage(totp),
                appPackageName = customFields["bastion_app_package"]
                    ?: customFields["appPackageName"]
                    ?: parsedUris.appPackageName,
                appName = customFields["bastion_app_name"]
                    ?: customFields["appName"]
                    ?: "",
                email = customFields["bastion_email"]
                    ?: customFields["email"]
                    ?: "",
                phone = customFields["bastion_phone"]
                    ?: customFields["phone"]
                    ?: "",
                addressLine = customFields["bastion_address_line"]
                    ?: customFields["addressLine"]
                    ?: customFields["address"]
                    ?: "",
                city = customFields["bastion_city"] ?: customFields["city"] ?: "",
                state = customFields["bastion_state"] ?: customFields["state"] ?: "",
                zipCode = customFields["bastion_zip_code"]
                    ?: customFields["zipCode"]
                    ?: "",
                country = customFields["bastion_country"] ?: customFields["country"] ?: "",
                passkeyBindings = customFields["bastion_passkey_bindings"].orEmpty(),
                sshKeyData = sshKeyData,
                loginType = remoteLoginType,
                wifiMetadata = remoteWifiMetadata,
                ssoProvider = remoteSsoProvider,
                isFavorite = cipher.favorite,
                createdAt = Date(),
                updatedAt = Date(),
                // Bitwarden 关联字段
                bitwardenVaultId = vault.id,
                bitwardenCipherId = cipher.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenCipherType = cipher.type,
                bitwardenLocalModified = false
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to convert cipher: ${e.message}")
            return null
        }
    }
    
    /**
     * 从 Cipher 更新 PasswordEntry
     */
    private fun updatePasswordEntryFromCipher(
        entry: PasswordEntry,
        vaultId: Long,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): PasswordEntry? {
        try {
            val login = cipher.login ?: return null
            
            val name = decryptString(cipher.name, symmetricKey) ?: entry.title
            val username = decryptString(login.username, symmetricKey) ?: entry.username
            val decryptedPassword = decryptString(login.password, symmetricKey)
            val encryptedPassword = decryptedPassword?.let {
                encryptBitwardenPasswordForOfflineDisplay(it, cipher.id)
            } ?: entry.password
            val notes = decryptString(cipher.notes, symmetricKey) ?: entry.notes
            val remoteTotp = decryptString(login.totp, symmetricKey)
            val storedTotp = remoteTotp?.let(::encodeBitwardenTotpForLocalStorage) ?: entry.authenticatorKey
            val parsedUris = parseLoginUris(login.uris, symmetricKey)
            val customFields = parsePasswordCustomFieldMap(cipher.fields, symmetricKey)
            val remoteAppPackage = customFields["bastion_app_package"]
                ?: customFields["appPackageName"]
                ?: parsedUris.appPackageName
            val remoteAppName = customFields["bastion_app_name"]
                ?: customFields["appName"]
                ?: ""
            val remoteEmail = customFields["bastion_email"]
                ?: customFields["email"]
                ?: ""
            val remotePhone = customFields["bastion_phone"]
                ?: customFields["phone"]
                ?: ""
            val remoteAddress = customFields["bastion_address_line"]
                ?: customFields["addressLine"]
                ?: customFields["address"]
                ?: ""
            val remoteCity = customFields["bastion_city"] ?: customFields["city"] ?: ""
            val remoteState = customFields["bastion_state"] ?: customFields["state"] ?: ""
            val remoteZip = customFields["bastion_zip_code"]
                ?: customFields["zipCode"]
                ?: ""
            val remoteCountry = customFields["bastion_country"] ?: customFields["country"] ?: ""
            val remotePasskeyBindings = customFields["bastion_passkey_bindings"].orEmpty()
            val remoteSshKeyData = buildSshKeyDataFromCustomFields(customFields)
            // T2: WIFI / SSO 扩展元数据还原
            val remoteWifiMetadata = customFields["bastion_wifi_data"].orEmpty()
            val remoteSsoProvider = customFields["bastion_sso_provider"].orEmpty()
            val remoteLoginType = when {
                remoteSshKeyData.isNotBlank() -> LOGIN_TYPE_SSH_KEY
                customFields["bastion_login_type"].equals("WIFI", ignoreCase = true) -> "WIFI"
                customFields["bastion_login_type"].equals("SSO", ignoreCase = true) -> "SSO"
                else -> "PASSWORD"
            }

            return entry.copy(
                title = name,
                website = parsedUris.website.ifBlank { entry.website },
                username = username,
                password = encryptedPassword,
                notes = notes,
                authenticatorKey = storedTotp,
                appPackageName = remoteAppPackage.ifBlank { entry.appPackageName },
                appName = remoteAppName.ifBlank { entry.appName },
                email = remoteEmail.ifBlank { entry.email },
                phone = remotePhone.ifBlank { entry.phone },
                addressLine = remoteAddress.ifBlank { entry.addressLine },
                city = remoteCity.ifBlank { entry.city },
                state = remoteState.ifBlank { entry.state },
                zipCode = remoteZip.ifBlank { entry.zipCode },
                country = remoteCountry.ifBlank { entry.country },
                passkeyBindings = remotePasskeyBindings.ifBlank { entry.passkeyBindings },
                sshKeyData = remoteSshKeyData.ifBlank { entry.sshKeyData },
                loginType = remoteLoginType,
                wifiMetadata = remoteWifiMetadata.ifBlank { entry.wifiMetadata },
                ssoProvider = remoteSsoProvider.ifBlank { entry.ssoProvider },
                isFavorite = cipher.favorite,
                updatedAt = Date(),
                bitwardenVaultId = vaultId,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = false
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to update entry from cipher: ${e.message}")
            return null
        }
    }
    
    /**
     * 创建冲突备份
     */
    private suspend fun createConflictBackup(
        vault: BitwardenVault,
        entry: PasswordEntry,
        serverCipher: CipherApiResponse,
        conflictType: String
    ) {
        try {
            val localJson = json.encodeToString(
                mapOf(
                    "id" to entry.id.toString(),
                    "title" to entry.title,
                    "website" to entry.website,
                    "username" to entry.username,
                    "notes" to entry.notes
                    // 不包含密码等敏感数据的明文
                )
            )
            
            val serverJson = json.encodeToString(
                mapOf(
                    "id" to serverCipher.id,
                    "revisionDate" to serverCipher.revisionDate
                )
            )
            
            conflictDao.insert(
                BitwardenConflictBackup(
                    vaultId = vault.id,
                    entryId = entry.id,
                    bitwardenCipherId = serverCipher.id,
                    conflictType = conflictType,
                    localDataJson = localJson,
                    serverDataJson = serverJson,
                    localRevisionDate = entry.bitwardenRevisionDate,
                    serverRevisionDate = serverCipher.revisionDate,
                    entryTitle = entry.title,
                    description = "本地和服务器同时修改了此条目"
                )
            )
            
            android.util.Log.w(TAG, "Created conflict backup for entry ${entry.id}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create conflict backup: ${e.message}")
        }
    }
    
    /**
     * 解密文件夹名称
     */
    private fun decryptFolderName(encrypted: String?, key: SymmetricCryptoKey): String {
        if (encrypted.isNullOrBlank()) return "Unnamed Folder"
        return try {
            com.bastion.app.bitwarden.crypto.BitwardenCrypto.decryptToString(encrypted, key)
        } catch (e: Exception) {
            "Unnamed Folder"
        }
    }
    
    /**
     * 解密字符串
     */
    private fun decryptString(encrypted: String?, key: SymmetricCryptoKey): String? {
        if (encrypted.isNullOrBlank()) return null
        return try {
            com.bastion.app.bitwarden.crypto.BitwardenCrypto.decryptToString(encrypted, key)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 处理待处理的操作队列
     */
    suspend fun processPendingOperations(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): Int = withContext(Dispatchers.IO) {
        val pendingOps = pendingOpDao.getRunnableOperationsByVault(vault.id)
        var processed = 0
        if (pendingOps.isNotEmpty()) {
            android.util.Log.i(
                TAG,
                "Processing ${pendingOps.size} pending operations for vault ${vault.id}"
            )
        }
        
        for (op in pendingOps) {
            val opStart = System.currentTimeMillis()
            try {
                if (op.operationType == BitwardenPendingOperation.OP_DELETE) {
                    android.util.Log.i(
                        TAG,
                        "Process delete op: vault=${vault.id}, itemType=${op.itemType}, entryId=${op.entryId}, cipherId=${op.bitwardenCipherId}"
                    )
                }
                val success = when (op.operationType) {
                    BitwardenPendingOperation.OP_CREATE -> processCreateOperation(vault, op, accessToken, symmetricKey)
                    BitwardenPendingOperation.OP_UPDATE -> processUpdateOperation(vault, op, accessToken, symmetricKey)
                    BitwardenPendingOperation.OP_DELETE -> processDeleteOperation(vault, op, accessToken)
                    BitwardenPendingOperation.OP_RESTORE -> processRestoreOperation(vault, op, accessToken)
                    else -> false
                }
                BitwardenDiagLogger.append(
                    "BitwardenSyncService.processPendingOperations: opId=${op.id}, type=${op.operationType}, " +
                        "cipherId=${op.bitwardenCipherId}, took ${System.currentTimeMillis() - opStart}ms, " +
                        "success=$success, vaultId=${vault.id}"
                )

                if (success) {
                    pendingOpDao.markCompleted(op.id)
                    processed++
                    if (op.operationType == BitwardenPendingOperation.OP_DELETE) {
                        android.util.Log.i(TAG, "Delete op completed: opId=${op.id}, cipherId=${op.bitwardenCipherId}")
                    }
                } else {
                    pendingOpDao.updateStatus(op.id, BitwardenPendingOperation.STATUS_FAILED)
                    if (op.operationType == BitwardenPendingOperation.OP_DELETE) {
                        android.util.Log.w(TAG, "Delete op failed: opId=${op.id}, cipherId=${op.bitwardenCipherId}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to process operation ${op.id}: ${e.message}")
                BitwardenDiagLogger.append(
                    "BitwardenSyncService.processPendingOperations EXCEPTION: opId=${op.id}, type=${op.operationType}, " +
                        "took ${System.currentTimeMillis() - opStart}ms, msg=${e.message}, vaultId=${vault.id}"
                )
                pendingOpDao.updateStatus(
                    id = op.id,
                    status = BitwardenPendingOperation.STATUS_FAILED,
                    lastError = e.message
                )
            }
        }
        
        processed
    }
    
    private suspend fun processCreateOperation(
        vault: BitwardenVault,
        op: BitwardenPendingOperation,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val entryId = op.entryId ?: return false
        val entry = passwordEntryDao.getPasswordEntryById(entryId) ?: return false
        return uploadLocalEntry(vault, entry, accessToken, symmetricKey).success
    }
    
    private suspend fun processUpdateOperation(
        vault: BitwardenVault,
        op: BitwardenPendingOperation,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val entryId = op.entryId ?: return false
        val entry = passwordEntryDao.getPasswordEntryById(entryId) ?: return false
        val cipherId = entry.bitwardenCipherId ?: return false
        return updateRemoteCipher(vault, entry, cipherId, accessToken, symmetricKey).success
    }
    
    private suspend fun processDeleteOperation(
        vault: BitwardenVault,
        op: BitwardenPendingOperation,
        accessToken: String
    ): Boolean {
        val cipherId = op.bitwardenCipherId ?: return false
        return deleteRemoteCipher(vault, cipherId, accessToken)
    }

    private suspend fun processRestoreOperation(
        vault: BitwardenVault,
        op: BitwardenPendingOperation,
        accessToken: String
    ): Boolean {
        val cipherId = op.bitwardenCipherId ?: return false
        return restoreRemoteCipher(vault, cipherId, accessToken)
    }
    
    // ========== 上传本地条目到 Bitwarden ==========
    
    /**
     * 上传本地创建的条目到 Bitwarden 服务器
     * 用于处理在 Bastion 本地创建但标记为 Bitwarden 存储的条目
     */
    suspend fun uploadLocalEntries(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadResult = withContext(Dispatchers.IO) {
        android.util.Log.i(TAG, "Checking for local entries to upload for vault ${vault.id}")

        val repairedCount = passwordEntryDao.markHistoricalPendingBitwardenUploads(vault.id)
        if (repairedCount > 0) {
            val line = "$TAG repairHistoricalPendingUploads: vaultId=${vault.id}, repaired=$repairedCount"
            android.util.Log.i(TAG, line)
            BitwardenDiagLogger.append(line)
        }
        
        var entriesToUpload = passwordEntryDao.getLocalEntriesPendingUpload(vault.id)
        if (entriesToUpload.isNotEmpty()) {
            val reconcileStart = System.currentTimeMillis()
            // 优化：上传前先打轻量 GET /accounts/revision-date，与本地 vault.revisionDate 一致说明远端
            // 没有其他设备的新变更，本地 pending 条目不可能已在远端存在 → 跳过全量对账，
            // 省掉一次全量 GET /sync（Vaultwarden 忽略 sinceRevisionDate，每次全量返回）。
            var needReconcile = true
            try {
                val revResp = apiManager.getVaultApi(vault).getRevisionDate("Bearer $accessToken")
                if (revResp.isSuccessful) {
                    val serverRev = revResp.body()?.string()?.trim('"', ' ', '\n', '\r')
                    if (!serverRev.isNullOrBlank() && serverRev == vault.revisionDate) {
                        needReconcile = false
                        BitwardenDiagLogger.append(
                            "BitwardenSyncService.uploadLocalEntries: skip reconcile (serverRev==localRev), " +
                                "vaultId=${vault.id}, serverRev=$serverRev"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "revision-date precheck failed, fallback to reconcile: ${e.message}")
            }
            if (needReconcile) {
                val reconciled = reconcilePendingPasswordUploadsFromRemote(
                    vault = vault,
                    accessToken = accessToken,
                    symmetricKey = symmetricKey,
                    pendingEntries = entriesToUpload
                )
                BitwardenDiagLogger.append(
                    "BitwardenSyncService.uploadLocalEntries: reconcile took ${System.currentTimeMillis() - reconcileStart}ms, " +
                        "reconciled=$reconciled, vaultId=${vault.id}"
                )
                if (reconciled > 0) {
                    val line = "$TAG reconcilePendingPasswordUploadsFromRemote: vaultId=${vault.id}, reconciled=$reconciled"
                    android.util.Log.i(TAG, line)
                    BitwardenDiagLogger.append(line)
                    entriesToUpload = passwordEntryDao.getLocalEntriesPendingUpload(vault.id)
                }
            } else {
                BitwardenDiagLogger.append(
                    "BitwardenSyncService.uploadLocalEntries: reconcile skipped, precheck took " +
                        "${System.currentTimeMillis() - reconcileStart}ms, vaultId=${vault.id}"
                )
            }
        }
        
        var uploaded = 0
        var failed = 0
        var authRequiredFailures = 0

        if (entriesToUpload.isNotEmpty()) {
            android.util.Log.i(TAG, "Found ${entriesToUpload.size} password entries to upload")
        }

        for (entry in entriesToUpload) {
            val entryStart = System.currentTimeMillis()
            try {
                val result = uploadLocalEntry(vault, entry, accessToken, symmetricKey)
                BitwardenDiagLogger.append(
                    "BitwardenSyncService.uploadLocalEntry: entryId=${entry.id}, " +
                        "took ${System.currentTimeMillis() - entryStart}ms, success=${result.success}, " +
                        "authRequired=${result.authRequired}, vaultId=${vault.id}"
                )
                if (result.success) {
                    uploaded++
                } else {
                    failed++
                    if (result.authRequired) {
                        authRequiredFailures++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to upload entry ${entry.id}: ${e.message}")
                BitwardenDiagLogger.append(
                    "BitwardenSyncService.uploadLocalEntry EXCEPTION: entryId=${entry.id}, " +
                        "took ${System.currentTimeMillis() - entryStart}ms, msg=${e.message}, vaultId=${vault.id}"
                )
                failed++
                if (isMdkUnavailableException(e)) {
                    authRequiredFailures++
                }
            }
        }
        
        // 同步上传 SecureItems
        val secureStart = System.currentTimeMillis()
        val secureResult = cipherUploadProcessor.uploadPendingSecureItems(vault, accessToken, symmetricKey)
        BitwardenDiagLogger.append(
            "BitwardenSyncService.uploadLocalEntries: uploadPendingSecureItems took " +
                "${System.currentTimeMillis() - secureStart}ms, uploaded=${secureResult.uploaded}, " +
                "failed=${secureResult.failed}, vaultId=${vault.id}"
        )
        uploaded += secureResult.uploaded
        failed += secureResult.failed

        // 同步上传 Passkeys（仅新增）
        val passkeyStart = System.currentTimeMillis()
        val passkeyResult = cipherUploadProcessor.uploadPendingPasskeys(vault, accessToken, symmetricKey)
        BitwardenDiagLogger.append(
            "BitwardenSyncService.uploadLocalEntries: uploadPendingPasskeys took " +
                "${System.currentTimeMillis() - passkeyStart}ms, uploaded=${passkeyResult.uploaded}, " +
                "failed=${passkeyResult.failed}, vaultId=${vault.id}"
        )
        uploaded += passkeyResult.uploaded
        failed += passkeyResult.failed

        android.util.Log.i(
            TAG,
            "Upload complete: $uploaded uploaded, $failed failed (password + secure items + passkeys)"
        )
        UploadResult.Success(
            uploaded = uploaded,
            failed = failed,
            authRequiredFailures = authRequiredFailures
        )
    }

    /**
     * 上传本地已修改的 Bitwarden 条目（已有 cipherId）
     * 用于处理在 Bastion 中编辑过的 Bitwarden 密码条目
     */
    suspend fun uploadModifiedEntries(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadResult = withContext(Dispatchers.IO) {
        android.util.Log.i(TAG, "Checking for modified entries to upload for vault ${vault.id}")

        val modifiedEntries = passwordEntryDao
            .getEntriesWithPendingBitwardenSync(vault.id)
            .filter { !it.bitwardenCipherId.isNullOrBlank() }

        var uploaded = 0
        var failed = 0
        var authRequiredFailures = 0

        if (modifiedEntries.isNotEmpty()) {
            android.util.Log.i(TAG, "Found ${modifiedEntries.size} modified password entries to upload")
        }

        for (entry in modifiedEntries) {
            try {
                val cipherId = entry.bitwardenCipherId
                if (cipherId.isNullOrBlank()) {
                    failed++
                    continue
                }
                val result = updateRemoteCipher(vault, entry, cipherId, accessToken, symmetricKey)
                if (result.success) {
                    uploaded++
                } else {
                    failed++
                    if (result.authRequired) {
                        authRequiredFailures++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to upload modified entry ${entry.id}: ${e.message}")
                failed++
                if (isMdkUnavailableException(e)) {
                    authRequiredFailures++
                }
            }
        }

        // 同步上传已修改的 SecureItems
        val secureResult = cipherUploadProcessor.uploadModifiedSecureItems(vault, accessToken, symmetricKey)
        uploaded += secureResult.uploaded
        failed += secureResult.failed

        // 同步更新已存在的 Passkeys（修复历史 counter / userHandle 兼容）
        val passkeyResult = cipherUploadProcessor.uploadModifiedPasskeys(vault, accessToken, symmetricKey)
        uploaded += passkeyResult.uploaded
        failed += passkeyResult.failed

        android.util.Log.i(TAG, "Modified upload complete: $uploaded uploaded, $failed failed")
        UploadResult.Success(
            uploaded = uploaded,
            failed = failed,
            authRequiredFailures = authRequiredFailures
        )
    }
    
    /**
     * 上传单个本地条目到 Bitwarden
     */
    private suspend fun uploadLocalEntry(
        vault: BitwardenVault,
        entry: PasswordEntry,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadAttemptResult {
        val latestEntry = passwordEntryDao.getPasswordEntryById(entry.id) ?: entry
        if (!latestEntry.bitwardenCipherId.isNullOrBlank()) {
            android.util.Log.i(
                TAG,
                "Skip uploadLocalEntry for ${entry.id}: entry already bound to cipher ${latestEntry.bitwardenCipherId}"
            )
            return UploadAttemptResult(success = true)
        }

        // 构建加密的 Cipher 请求（SSH 条目优先原生 Type 5，失败自动回落降级格式）
        var createRequest = passwordEntryToCipherRequest(latestEntry, symmetricKey, vault.id)
        var requestPayload = runCatchingObserved { json.encodeToString(createRequest) }.getOrNull()

        try {
            val vaultApi = apiManager.getVaultApi(vault)
            val postStart = System.currentTimeMillis()
            var response = vaultApi.createCipher(
                authorization = "Bearer $accessToken",
                cipher = createRequest
            )
            // SSH 原生 Type 5 被服务端校验拒绝（400/422）→ 记入冷却期并回落 Type 1 + 自定义字段重试一次
            if (createRequest.type == 5 && !response.isSuccessful &&
                (response.code() == 400 || response.code() == 422)
            ) {
                SshNativeUploadPolicy.markNativeUploadFailed(context, vault.id)
                BitwardenDiagLogger.append(
                    "SSH native type5 create rejected (code=${response.code()}), fallback to type1+fields, vaultId=${vault.id}"
                )
                createRequest = passwordEntryToCipherRequest(latestEntry, symmetricKey, vault.id)
                requestPayload = runCatchingObserved { json.encodeToString(createRequest) }.getOrNull()
                response = vaultApi.createCipher(
                    authorization = "Bearer $accessToken",
                    cipher = createRequest
                )
            }
            if (response.isSuccessful && createRequest.type == 5) {
                // 仅当最终成功的请求确实是 Type 5 才清零失败记录，
                // 回落成功的场景保持冷却期，避免每轮同步反复被拒。
                SshNativeUploadPolicy.markNativeUploadSucceeded(context, vault.id)
            }
            val postMs = System.currentTimeMillis() - postStart
            BitwardenDiagLogger.append(
                "BitwardenSyncService.uploadLocalEntry POST: entryId=${entry.id}, took ${postMs}ms, " +
                    "code=${response.code()}, vaultId=${vault.id}"
            )

            if (!response.isSuccessful) {
                val errorPayload = runCatchingObserved { response.errorBody()?.string() }.getOrNull()
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_password_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = errorPayload,
                    success = false,
                    error = "create cipher failed: ${response.code()} ${response.message()}"
                )
                appendPasswordUploadFailureLog(
                    vaultId = vault.id,
                    operation = "create",
                    entry = latestEntry,
                    cipherId = null,
                    responseCode = response.code(),
                    errorPayload = errorPayload,
                    throwable = null
                )
                android.util.Log.e(TAG, "Create cipher failed: ${response.code()} ${response.message()}")
                return UploadAttemptResult(success = false)
            }

            val createdCipher = response.body()
            if (createdCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_password_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "create cipher returned empty body"
                )
                appendPasswordUploadFailureLog(
                    vaultId = vault.id,
                    operation = "create",
                    entry = latestEntry,
                    cipherId = null,
                    responseCode = response.code(),
                    errorPayload = "empty-body",
                    throwable = null
                )
                return UploadAttemptResult(success = false)
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_password_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatchingObserved { json.encodeToString(createdCipher) }.getOrNull(),
                success = true
            )

            // 更新本地条目，添加服务器返回的 cipherId 和 revisionDate
            val updatedEntry = latestEntry.copy(
                bitwardenCipherId = createdCipher.id,
                bitwardenRevisionDate = createdCipher.revisionDate,
                bitwardenLocalModified = false,
                updatedAt = Date()
            )
            passwordEntryDao.update(updatedEntry)

            android.util.Log.d(TAG, "Successfully uploaded entry ${entry.id} as cipher ${createdCipher.id}")
            return UploadAttemptResult(success = true)
        } catch (e: Exception) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_password_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = requestPayload,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            appendPasswordUploadFailureLog(
                vaultId = vault.id,
                operation = "create",
                entry = latestEntry,
                cipherId = null,
                responseCode = null,
                errorPayload = null,
                throwable = e
            )
            android.util.Log.e(TAG, "Upload entry failed: ${e.message}", e)
            return UploadAttemptResult(
                success = false,
                authRequired = isMdkUnavailableException(e)
            )
        }
    }
    
    /**
     * 更新远程 Cipher
     */
    private suspend fun updateRemoteCipher(
        vault: BitwardenVault,
        entry: PasswordEntry,
        cipherId: String,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadAttemptResult {
        val vaultApi = apiManager.getVaultApi(vault)
        var baselineCipher: CipherApiResponse? = null
        val mergedFields = runCatchingObserved {
            val remote = vaultApi.getCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )
            if (remote.isSuccessful) {
                baselineCipher = remote.body()
                mergeCipherFieldsPreservingUnknown(
                    localFields = buildEncryptedPasswordCustomFields(entry, symmetricKey),
                    remoteFields = baselineCipher?.fields,
                    symmetricKey = symmetricKey
                )
            } else {
                null
            }
        }.getOrNull()

        var updateRequest = passwordEntryToCipherUpdateRequest(
            entry = entry,
            symmetricKey = symmetricKey,
            vaultId = vault.id,
            mergedFields = mergedFields,
            baselineCipher = baselineCipher
        )
        var requestPayload = runCatchingObserved { json.encodeToString(updateRequest) }.getOrNull()

        try {
            var response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId,
                cipher = updateRequest
            )
            // SSH 原生 Type 5 被服务端校验拒绝（400/422）→ 记入冷却期并回落 Type 1 + 自定义字段重试一次
            if (updateRequest.type == 5 && !response.isSuccessful &&
                (response.code() == 400 || response.code() == 422)
            ) {
                SshNativeUploadPolicy.markNativeUploadFailed(context, vault.id)
                BitwardenDiagLogger.append(
                    "SSH native type5 update rejected (code=${response.code()}), fallback to type1+fields, vaultId=${vault.id}"
                )
                updateRequest = passwordEntryToCipherUpdateRequest(
                    entry = entry,
                    symmetricKey = symmetricKey,
                    vaultId = vault.id,
                    mergedFields = mergedFields,
                    baselineCipher = baselineCipher
                )
                requestPayload = runCatchingObserved { json.encodeToString(updateRequest) }.getOrNull()
                response = vaultApi.updateCipher(
                    authorization = "Bearer $accessToken",
                    cipherId = cipherId,
                    cipher = updateRequest
                )
            }
            if (response.isSuccessful && updateRequest.type == 5) {
                // 仅当最终成功的请求确实是 Type 5 才清零失败记录，
                // 回落成功的场景保持冷却期，避免每轮同步反复被拒。
                SshNativeUploadPolicy.markNativeUploadSucceeded(context, vault.id)
            }

            if (!response.isSuccessful) {
                val errorPayload = runCatchingObserved { response.errorBody()?.string() }.getOrNull()
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_password_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = errorPayload,
                    success = false,
                    error = "update cipher failed: ${response.code()}"
                )
                appendPasswordUploadFailureLog(
                    vaultId = vault.id,
                    operation = "update",
                    entry = entry,
                    cipherId = cipherId,
                    responseCode = response.code(),
                    errorPayload = errorPayload,
                    throwable = null
                )
                android.util.Log.e(TAG, "Update cipher failed: ${response.code()}")
                return UploadAttemptResult(success = false)
            }

            val updatedCipher = response.body()
            if (updatedCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_password_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "update cipher returned empty body"
                )
                appendPasswordUploadFailureLog(
                    vaultId = vault.id,
                    operation = "update",
                    entry = entry,
                    cipherId = cipherId,
                    responseCode = response.code(),
                    errorPayload = "empty-body",
                    throwable = null
                )
                return UploadAttemptResult(success = false)
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_password_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatchingObserved { json.encodeToString(updatedCipher) }.getOrNull(),
                success = true
            )

            // BUG-1 修复：上传回写前重新读取最新行，避免用“网络往返前的快照”整行覆盖，
            // 从而静默丢弃往返期间发生的并发本地编辑。若期间确有新的本地编辑（updatedAt 变化），
            // 保留 dirty 标记，下一轮同步会重新上传新内容，绝不丢数据。
            val latestEntry = passwordEntryDao.getPasswordEntryById(entry.id) ?: entry
            val concurrentLocalEdit = latestEntry.updatedAt.time != entry.updatedAt.time
            val updatedEntry = latestEntry.copy(
                bitwardenRevisionDate = updatedCipher.revisionDate,
                bitwardenLocalModified = concurrentLocalEdit,
                updatedAt = if (concurrentLocalEdit) latestEntry.updatedAt else Date()
            )
            passwordEntryDao.update(updatedEntry)

            logBitwardenPasswordEditHistory(
                vaultId = vault.id,
                cipherId = cipherId,
                title = entry.title,
                baselineCipher = baselineCipher,
                updateRequest = updateRequest,
                symmetricKey = symmetricKey
            )

            return UploadAttemptResult(success = true)
        } catch (e: Exception) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_password_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = requestPayload,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            appendPasswordUploadFailureLog(
                vaultId = vault.id,
                operation = "update",
                entry = entry,
                cipherId = cipherId,
                responseCode = null,
                errorPayload = null,
                throwable = e
            )
            android.util.Log.e(TAG, "Update remote cipher failed: ${e.message}", e)
            return UploadAttemptResult(
                success = false,
                authRequired = isMdkUnavailableException(e)
            )
        }
    }

    private data class UploadAttemptResult(
        val success: Boolean,
        val authRequired: Boolean = false
    )

    private fun isMdkUnavailableException(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            val message = current.message?.lowercase().orEmpty()
            if (message.contains("mdk not available")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun captureRawExchange(
        vaultId: Long,
        operation: String,
        method: String,
        endpoint: String,
        requestBody: String?,
        responseCode: Int?,
        responseBody: String?,
        success: Boolean,
        error: String? = null
    ) {
        // 取证采集已移除（BitwardenSyncForensicsLogger 已删除）；保留方法签名以避免改动多处调用点。
    }

    private fun logBitwardenPasswordEditHistory(
        vaultId: Long,
        cipherId: String,
        title: String,
        baselineCipher: CipherApiResponse?,
        updateRequest: CipherUpdateRequest,
        symmetricKey: SymmetricCryptoKey
    ) {
        val changes = buildPasswordEditHistoryChanges(
            baselineCipher = baselineCipher,
            updateRequest = updateRequest,
            symmetricKey = symmetricKey
        )
        if (changes.isEmpty()) return

        OperationLogger.logUpdate(
            itemType = OperationLogItemType.BITWARDEN_SYNC,
            itemId = buildBitwardenItemId(vaultId, cipherId),
            itemTitle = title.ifBlank { "Bitwarden Password Entry" },
            changes = changes
        )
    }

    private fun buildPasswordEditHistoryChanges(
        baselineCipher: CipherApiResponse?,
        updateRequest: CipherUpdateRequest,
        symmetricKey: SymmetricCryptoKey
    ): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()

        appendIfChanged(
            changes = changes,
            fieldName = "title",
            oldValue = decryptOrPlain(baselineCipher?.name, symmetricKey),
            newValue = decryptOrPlain(updateRequest.name, symmetricKey),
            sensitive = false
        )
        appendIfChanged(
            changes = changes,
            fieldName = "username",
            oldValue = decryptOrPlain(baselineCipher?.login?.username, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.username, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "password",
            oldValue = decryptOrPlain(baselineCipher?.login?.password, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.password, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "totp",
            oldValue = decryptOrPlain(baselineCipher?.login?.totp, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.totp, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "notes",
            oldValue = decryptOrPlain(baselineCipher?.notes, symmetricKey),
            newValue = decryptOrPlain(updateRequest.notes, symmetricKey),
            sensitive = true
        )

        val oldUriCount = baselineCipher?.login?.uris?.size ?: 0
        val newUriCount = updateRequest.login?.uris?.size ?: 0
        if (oldUriCount != newUriCount) {
            changes += FieldChange("login_uri_count", oldUriCount.toString(), newUriCount.toString())
        }

        val oldFavorite = baselineCipher?.favorite ?: false
        val newFavorite = updateRequest.favorite ?: false
        if (oldFavorite != newFavorite) {
            changes += FieldChange("favorite", oldFavorite.toString(), newFavorite.toString())
        }

        val oldArchived = baselineCipher?.archivedDate.orEmpty().ifBlank { "active" }
        val newArchived = updateRequest.archivedDate.orEmpty().ifBlank { "active" }
        if (oldArchived != newArchived) {
            changes += FieldChange("archived", oldArchived, newArchived)
        }

        val oldFieldSummary = summarizeFieldCollection(baselineCipher?.fields, symmetricKey)
        val newFieldSummary = summarizeFieldCollection(updateRequest.fields, symmetricKey)
        if (oldFieldSummary != newFieldSummary) {
            changes += FieldChange("custom_fields", oldFieldSummary, newFieldSummary)
        }

        return changes
    }

    private fun summarizeFieldCollection(
        fields: List<CipherFieldApiData>?,
        symmetricKey: SymmetricCryptoKey
    ): String {
        if (fields.isNullOrEmpty()) return "count=0"

        val nameSamples = fields.mapNotNull { field ->
            decryptOrPlain(field.name, symmetricKey)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.replace("\n", " ")
                ?.replace("\r", "")
                ?.take(24)
        }.distinct().sorted().take(4)

        val signatures = fields.map { field ->
            val plainName = decryptOrPlain(field.name, symmetricKey).orEmpty().trim()
            val plainValue = decryptOrPlain(field.value, symmetricKey)
            val summarizedValue = summarizeHistoryValue(plainValue, sensitive = true)
            "${field.type}|${field.linkedId}|$plainName|$summarizedValue"
        }.sorted()

        val sampleText = nameSamples.joinToString("|").ifBlank { "-" }
        return "count=${fields.size},names=$sampleText,sha=${shortSha(signatures.joinToString("||"))}"
    }

    private fun appendIfChanged(
        changes: MutableList<FieldChange>,
        fieldName: String,
        oldValue: String?,
        newValue: String?,
        sensitive: Boolean
    ) {
        val oldNormalized = oldValue.orEmpty()
        val newNormalized = newValue.orEmpty()
        if (oldNormalized == newNormalized) return

        changes += FieldChange(
            fieldName = fieldName,
            oldValue = summarizeHistoryValue(oldNormalized, sensitive),
            newValue = summarizeHistoryValue(newNormalized, sensitive)
        )
    }

    private fun summarizeHistoryValue(value: String?, sensitive: Boolean): String {
        val normalized = value.orEmpty()
        if (normalized.isBlank()) return "(empty)"

        if (sensitive) {
            return summarizeSensitiveHistoryValue(normalized)
        }

        val sanitized = normalized
            .replace("\n", "\\n")
            .replace("\r", "")
        if (sanitized.length <= 180) {
            return sanitized
        }
        val head = sanitized.take(120)
        val tail = sanitized.takeLast(40)
        val omitted = (sanitized.length - 160).coerceAtLeast(0)
        return "$head...(omitted=$omitted)...$tail"
    }

    private fun summarizeSensitiveHistoryValue(normalized: String): String {
        val lower = normalized.count { it.isLowerCase() }
        val upper = normalized.count { it.isUpperCase() }
        val digits = normalized.count { it.isDigit() }
        val spaces = normalized.count { it.isWhitespace() }
        val symbols = (normalized.length - lower - upper - digits - spaces).coerceAtLeast(0)
        val lines = normalized.count { it == '\n' } + 1
        val format = detectSensitiveFormat(normalized)
        val pattern = buildClassPattern(normalized)

        return "len=${normalized.length},sha=${shortSha(normalized)},fmt=$format,lines=$lines,mix=u$upper/l$lower/d$digits/s$symbols,pat=$pattern"
    }

    private fun detectSensitiveFormat(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "empty"
        return when {
            trimmed.startsWith("otpauth://", ignoreCase = true) -> "otpauth"
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> "url"
            trimmed.startsWith("{") && trimmed.endsWith("}") -> "json_like"
            trimmed.contains('@') && !trimmed.contains(' ') -> "email_like"
            trimmed.all { it.isDigit() } -> "digits"
            trimmed.contains("\n") -> "multiline"
            else -> "text"
        }
    }

    private fun buildClassPattern(value: String, maxLen: Int = 24): String {
        val pattern = value.take(maxLen).map { ch ->
            when {
                ch.isUpperCase() -> 'A'
                ch.isLowerCase() -> 'a'
                ch.isDigit() -> '0'
                ch.isWhitespace() -> '_'
                else -> '#'
            }
        }.joinToString("")
        return if (value.length > maxLen) "$pattern..." else pattern
    }

    private fun shortSha(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun buildBitwardenItemId(vaultId: Long, cipherId: String): Long {
        return "${vaultId}:$cipherId".hashCode().toLong() and 0x7FFFFFFFL
    }

    /**
     * 删除远程 Cipher
     */
    private suspend fun deleteRemoteCipher(
        vault: BitwardenVault,
        cipherId: String,
        accessToken: String
    ): Boolean {
        try {
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.deleteCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )
             
            return response.isSuccessful || response.code() == 404
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Delete remote cipher failed: ${e.message}", e)
            return false
        }
    }

    private suspend fun restoreRemoteCipher(
        vault: BitwardenVault,
        cipherId: String,
        accessToken: String
    ): Boolean {
        try {
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.restoreCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )

            return response.isSuccessful || response.code() == 400 || response.code() == 404
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Restore remote cipher failed: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 将 PasswordEntry 转换为加密的 CipherCreateRequest
     *
     * SSH 密钥优先使用原生 Type 5（sshKey 字段），与 Bitwarden 官方条目结构完全同构，
     * 官方客户端会显示为真正的「SSH 密钥」条目。历史上曾因 Vaultwarden 等兼容服务端
     * 不支持 Type 5（/sync 时降级并丢弃 sshKey 数据）而一律降级为 Type 1 + 自定义字段；
     * 如今主流自建服务端已原生支持 Type 5，故改为：默认原生上传，被服务端 4xx 拒绝时
     * 由 [SshNativeUploadPolicy] 记入冷却期并自动回落降级格式（见 uploadLocalEntry）。
     * 下载侧两种形态都识别（原生 Type 5 + 降级 Type 1 字段），双向无缝。
     */
    private suspend fun passwordEntryToCipherRequest(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey,
        vaultId: Long
    ): CipherCreateRequest {
        val crypto = com.bastion.app.bitwarden.crypto.BitwardenCrypto
        if (entry.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true)) {
            val encryptedName = crypto.encryptString(entry.title, symmetricKey)
            val encryptedNotes = entry.notes.takeIf { it.isNotBlank() }?.let {
                crypto.encryptString(it, symmetricKey)
            }
            val ssh = SshKeyDataCodec.decode(entry.sshKeyData)
            if (ssh != null && SshNativeUploadPolicy.isNativeUploadAllowed(context, vaultId)) {
                // 原生 Type 5：与 Bitwarden 官方同构
                return CipherCreateRequest(
                    type = 5,
                    folderId = entry.bitwardenFolderId,
                    name = encryptedName,
                    notes = encryptedNotes,
                    sshKey = CipherSshKeyApiData(
                        privateKey = crypto.encryptString(ssh.privateKeyOpenSsh, symmetricKey),
                        publicKey = crypto.encryptString(ssh.publicKeyOpenSsh, symmetricKey),
                        keyFingerprint = crypto.encryptString(ssh.fingerprintSha256, symmetricKey)
                    ),
                    favorite = entry.isFavorite,
                    archivedDate = toBitwardenArchivedDate(entry)
                )
            }
            // 降级：Type 1 + 自定义字段（服务端不支持 Type 5 或处于探测冷却期）
            val sshFields = buildEncryptedSshKeyCustomFields(entry, symmetricKey)
            return CipherCreateRequest(
                type = 1,
                folderId = entry.bitwardenFolderId,
                name = encryptedName,
                notes = encryptedNotes,
                login = CipherLoginApiData(
                    uris = emptyList(),
                    fido2Credentials = emptyList()
                ),
                fields = sshFields,
                favorite = entry.isFavorite,
                archivedDate = toBitwardenArchivedDate(entry)
            )
        }

        val plainPassword = resolvePlainPasswordForBitwardenUpload(entry.password, entry.id)
        
        // 加密各个字段
        val encryptedName = crypto.encryptString(entry.title, symmetricKey)
        val encryptedNotes = if (entry.notes.isNotBlank()) {
            crypto.encryptString(entry.notes, symmetricKey)
        } else null
        
        // 构建 Login 数据
        val loginData = CipherLoginApiData(
            username = if (entry.username.isNotBlank()) {
                crypto.encryptString(entry.username, symmetricKey)
            } else null,
            password = if (plainPassword.isNotBlank()) {
                crypto.encryptString(plainPassword, symmetricKey)
            } else null,
            passwordRevisionDate = if (plainPassword.isNotBlank()) {
                java.time.Instant.now().toString()
            } else null,
            totp = if (entry.authenticatorKey.isNotBlank()) {
                crypto.encryptString(resolveBitwardenTotpPayload(entry), symmetricKey)
            } else null,
            uris = buildEncryptedLoginUris(entry, symmetricKey) ?: emptyList(),
            fido2Credentials = emptyList()
        )
        
        return CipherCreateRequest(
            type = 1, // Login type
            folderId = entry.bitwardenFolderId,
            name = encryptedName,
            notes = encryptedNotes,
            login = loginData,
            fields = buildEncryptedPasswordCustomFields(entry, symmetricKey),
            favorite = entry.isFavorite,
            archivedDate = toBitwardenArchivedDate(entry)
        )
    }
    
    /**
     * 将 PasswordEntry 转换为加密的 CipherUpdateRequest
     */
    private suspend fun passwordEntryToCipherUpdateRequest(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey,
        vaultId: Long,
        mergedFields: List<CipherFieldApiData>? = null,
        baselineCipher: CipherApiResponse? = null
    ): CipherUpdateRequest {
        val crypto = com.bastion.app.bitwarden.crypto.BitwardenCrypto
        if (entry.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true)) {
            val encryptedName = crypto.encryptString(entry.title, symmetricKey)
            val encryptedNotes = entry.notes.takeIf { it.isNotBlank() }?.let {
                crypto.encryptString(it, symmetricKey)
            }
            val ssh = SshKeyDataCodec.decode(entry.sshKeyData)
            if (ssh != null && SshNativeUploadPolicy.isNativeUploadAllowed(context, vaultId)) {
                // 原生 Type 5：与 Bitwarden 官方同构
                return CipherUpdateRequest(
                    type = 5,
                    folderId = entry.bitwardenFolderId,
                    name = encryptedName,
                    notes = encryptedNotes,
                    sshKey = CipherSshKeyApiData(
                        privateKey = crypto.encryptString(ssh.privateKeyOpenSsh, symmetricKey),
                        publicKey = crypto.encryptString(ssh.publicKeyOpenSsh, symmetricKey),
                        keyFingerprint = crypto.encryptString(ssh.fingerprintSha256, symmetricKey)
                    ),
                    favorite = entry.isFavorite,
                    archivedDate = toBitwardenArchivedDate(entry)
                )
            }
            if (ssh == null && baselineCipher?.sshKey != null) {
                // 本地 SSH 数据无法解析但服务端已是原生 Type 5：原样回传，
                // 避免整体 PUT 把服务端 sshKey 清空。
                return CipherUpdateRequest(
                    type = 5,
                    folderId = entry.bitwardenFolderId,
                    name = encryptedName,
                    notes = encryptedNotes,
                    sshKey = baselineCipher.sshKey,
                    favorite = entry.isFavorite,
                    archivedDate = toBitwardenArchivedDate(entry)
                )
            }
            // 降级：Type 1 + 自定义字段（服务端不支持 Type 5 或处于探测冷却期）
            val sshFields = buildEncryptedSshKeyCustomFields(entry, symmetricKey)
            return CipherUpdateRequest(
                type = 1,
                folderId = entry.bitwardenFolderId,
                name = encryptedName,
                notes = encryptedNotes,
                // BUG-3 修复：SSH 更新时保留服务器侧已存在的 login（含 fido2/uris 等），
                // 避免整体 PUT 把服务器侧字段清空。
                login = baselineCipher?.login ?: CipherLoginApiData(
                    uris = emptyList(),
                    fido2Credentials = emptyList()
                ),
                fields = sshFields,
                favorite = entry.isFavorite,
                archivedDate = toBitwardenArchivedDate(entry)
            )
        }

        val plainPassword = resolvePlainPasswordForBitwardenUpload(entry.password, entry.id)
        
        val encryptedName = crypto.encryptString(entry.title, symmetricKey)
        val encryptedNotes = if (entry.notes.isNotBlank()) {
            crypto.encryptString(entry.notes, symmetricKey)
        } else null
        
        val loginData = CipherLoginApiData(
            username = if (entry.username.isNotBlank()) {
                crypto.encryptString(entry.username, symmetricKey)
            } else null,
            password = if (plainPassword.isNotBlank()) {
                crypto.encryptString(plainPassword, symmetricKey)
            } else null,
            passwordRevisionDate = if (plainPassword.isNotBlank()) {
                java.time.Instant.now().toString()
            } else null,
            totp = if (entry.authenticatorKey.isNotBlank()) {
                crypto.encryptString(resolveBitwardenTotpPayload(entry), symmetricKey)
            } else null,
            uris = buildEncryptedLoginUris(entry, symmetricKey) ?: emptyList(),
            // BUG-2 修复：整体 PUT 会替换整个 login，硬编码空列表会清空服务器侧已存在的 Passkey。
            // 改用 baselineCipher 中已有的 fido2Credentials 原样回传，保护服务器 Passkey 不被抹除。
            fido2Credentials = baselineCipher?.login?.fido2Credentials ?: emptyList()
        )

        return CipherUpdateRequest(
            type = 1,
            folderId = entry.bitwardenFolderId,
            name = encryptedName,
            notes = encryptedNotes,
            login = loginData,
            fields = mergedFields ?: buildEncryptedPasswordCustomFields(entry, symmetricKey),
            favorite = entry.isFavorite,
            archivedDate = toBitwardenArchivedDate(entry)
        )
    }

    private fun resolveBitwardenTotpPayload(entry: PasswordEntry): String {
        val plainAuthenticatorKey = resolvePlainStoredSensitiveValueForBitwardenUpload(
            storedValue = entry.authenticatorKey,
            entryId = entry.id,
            fieldName = "authenticatorKey"
        )
        return TotpDataResolver.fromAuthenticatorKey(
            rawKey = plainAuthenticatorKey,
            fallbackIssuer = entry.website,
            fallbackAccountName = entry.username
        )?.let { resolved ->
            TotpDataResolver.toBitwardenPayload(entry.title, resolved)
        } ?: plainAuthenticatorKey
    }

    private fun toBitwardenArchivedDate(entry: PasswordEntry): String? {
        if (!entry.isArchived) return null
        val archiveDate = entry.archivedAt ?: entry.updatedAt
        return runCatchingObserved {
            Instant.ofEpochMilli(archiveDate.time).toString()
        }.getOrNull()
    }

    private fun parseRevisionMillis(revisionDate: String?): Long? {
        if (revisionDate.isNullOrBlank()) return null
        return runCatchingObserved { Instant.parse(revisionDate).toEpochMilli() }.getOrNull()
    }

    private fun mergeCipherFieldsPreservingUnknown(
        localFields: List<CipherFieldApiData>?,
        remoteFields: List<CipherFieldApiData>?,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherFieldApiData>? {
        if (localFields.isNullOrEmpty()) return remoteFields
        if (remoteFields.isNullOrEmpty()) return localFields

        val localFieldNames = localFields.mapNotNull { field ->
            decryptOrPlain(field.name, symmetricKey)?.trim()?.takeIf { it.isNotEmpty() }
        }.toSet()

        val preservedRemote = remoteFields.filter { remote ->
            val remoteName = decryptOrPlain(remote.name, symmetricKey)?.trim()
            if (remoteName.isNullOrEmpty()) {
                true
            } else {
                remoteName !in localFieldNames
            }
        }

        return (preservedRemote + localFields).ifEmpty { null }
    }

    private fun buildEncryptedLoginUris(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherUriApiData>? {
        val crypto = com.bastion.app.bitwarden.crypto.BitwardenCrypto
        val uris = mutableListOf<CipherUriApiData>()
        PasswordWebsiteCodec.parse(entry.website)
            .filter { it.isNotBlank() }
            .map(::normalizeBitwardenWebsite)
            .distinct()
            .forEach { normalizedWebsite ->
                uris.add(
                    CipherUriApiData(
                        uri = crypto.encryptString(normalizedWebsite, symmetricKey),
                        match = null
                    )
                )
            }
        if (entry.appPackageName.isNotBlank()) {
            val pkg = entry.appPackageName.removePrefix("androidapp://")
            uris.add(
                CipherUriApiData(
                    uri = crypto.encryptString("androidapp://$pkg", symmetricKey),
                    match = null
                )
            )
        }
        return uris.ifEmpty { null }
    }

    private fun normalizeBitwardenWebsite(rawWebsite: String): String {
        val trimmed = rawWebsite.trim()
        if (trimmed.isBlank()) return trimmed
        return if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("androidapp://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private suspend fun reconcilePendingPasswordUploadsFromRemote(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey,
        pendingEntries: List<PasswordEntry>
    ): Int {
        if (pendingEntries.isEmpty()) return 0

        return runCatchingObserved {
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.sync(
                authorization = "Bearer $accessToken",
                excludeDomains = true
            )
            if (!response.isSuccessful) {
                android.util.Log.w(
                    TAG,
                    "Failed to reconcile pending uploads before create: sync code=${response.code()}"
                )
                return@runCatchingObserved 0
            }

            val syncResponse = response.body() ?: return@runCatchingObserved 0
            val remoteLoginCiphers = syncResponse.ciphers
                .filter { it.type == 1 && it.deletedDate == null && !it.id.isBlank() }

            var reconciled = 0
            for (entry in pendingEntries) {
                val current = passwordEntryDao.getPasswordEntryById(entry.id) ?: continue
                if (!current.bitwardenCipherId.isNullOrBlank()) continue

                val matches = remoteLoginCiphers.filter { cipher ->
                    pendingEntryMatchesRemoteCipher(current, cipher, symmetricKey)
                }
                if (matches.size != 1) continue

                val matched = matches.first()
                val rebound = current.copy(
                    bitwardenCipherId = matched.id,
                    bitwardenFolderId = matched.folderId,
                    bitwardenRevisionDate = matched.revisionDate,
                    bitwardenCipherType = matched.type,
                    bitwardenLocalModified = false,
                    updatedAt = Date()
                )
                passwordEntryDao.update(rebound)
                reconciled++
            }

            reconciled
        }.getOrElse { error ->
            android.util.Log.w(
                TAG,
                "Pending upload reconciliation skipped: ${error.message}",
                error
            )
            0
        }
    }

    private fun pendingEntryMatchesRemoteCipher(
        entry: PasswordEntry,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val login = cipher.login ?: return false
        val remoteTitle = decryptString(cipher.name, symmetricKey) ?: return false
        val remoteUsername = decryptString(login.username, symmetricKey).orEmpty()
        val remotePassword = decryptString(login.password, symmetricKey).orEmpty()
        val remoteNotes = decryptString(cipher.notes, symmetricKey).orEmpty()
        val remoteTotp = decryptString(login.totp, symmetricKey).orEmpty()
        val remoteUris = parseLoginUris(login.uris, symmetricKey)
        val remoteAppPackage = remoteUris.appPackageName.trim()
        val localWebsites = PasswordWebsiteCodec.parse(entry.website)
            .filter { it.isNotBlank() }
            .map(::normalizeWebsiteKey)
            .distinct()
        val remoteWebsites = PasswordWebsiteCodec.parse(remoteUris.website)
            .filter { it.isNotBlank() }
            .map(::normalizeWebsiteKey)
            .distinct()
        val localPassword = resolvePlainPasswordForBitwardenUpload(entry.password, entry.id)
        val localAuthenticatorKey = resolvePlainStoredSensitiveValueForBitwardenUpload(
            storedValue = entry.authenticatorKey,
            entryId = entry.id,
            fieldName = "authenticatorKey"
        )

        return entry.title == remoteTitle &&
            entry.username == remoteUsername &&
            localPassword == remotePassword &&
            entry.notes == remoteNotes &&
            localAuthenticatorKey == remoteTotp &&
            entry.isFavorite == cipher.favorite &&
            entry.bitwardenFolderId == cipher.folderId &&
            entry.appPackageName.trim() == remoteAppPackage &&
            localWebsites == remoteWebsites
    }

    private fun appendPasswordUploadFailureLog(
        vaultId: Long,
        operation: String,
        entry: PasswordEntry,
        cipherId: String?,
        responseCode: Int?,
        errorPayload: String?,
        throwable: Throwable?
    ) {
        val summary = buildString {
            append(TAG)
            append(" passwordUploadFailure")
            append(": vaultId=")
            append(vaultId)
            append(", op=")
            append(operation)
            append(", entryId=")
            append(entry.id)
            append(", cipherId=")
            append(cipherId ?: "pending")
            append(", hasFolder=")
            append(!entry.bitwardenFolderId.isNullOrBlank())
            append(", website=")
            append(
                PasswordWebsiteCodec.parse(entry.website)
                    .filter { it.isNotBlank() }
                    .map(::normalizeBitwardenWebsite)
                    .joinToString(", ")
                    .take(120)
            )
            responseCode?.let {
                append(", code=")
                append(it)
            }
            errorPayload?.takeIf { it.isNotBlank() }?.let {
                append(", errorPayload=")
                append(it.take(240))
            }
            throwable?.let {
                append(", exception=")
                append(it.javaClass.simpleName)
                append(":")
                append(it.message?.take(180) ?: "unknown")
            }
        }
        BitwardenDiagLogger.append(summary)
    }

    /**
     * 登录型字段名中，bastion 会映射到专用列（appPackageName/email/ssh 等）的部分。
     * 这些字段由上方 addField 显式写入，custom_fields 表中的同名用户字段不应重复上报，
     * 否则会在 Bitwarden 服务端产生重复字段。
     */
    private val RESERVED_BITWARDEN_FIELD_NAMES = setOf(
        "apppackagename", "appname", "email", "phone",
        "addressline", "address", "city", "state", "zipcode", "country"
    )

    private fun isReservedBitwardenFieldName(name: String): Boolean {
        val n = name.trim()
        if (n.startsWith("bastion_", ignoreCase = true)) return true
        return n.lowercase() in RESERVED_BITWARDEN_FIELD_NAMES
    }

    private suspend fun buildEncryptedPasswordCustomFields(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherFieldApiData>? {
        val crypto = com.bastion.app.bitwarden.crypto.BitwardenCrypto
        val fields = mutableListOf<Pair<String, String>>()

        fun addField(name: String, value: String) {
            if (value.isNotBlank()) fields.add(name to value)
        }

        addField("bastion_app_package", entry.appPackageName)
        addField("appPackageName", entry.appPackageName)
        addField("bastion_app_name", entry.appName)
        addField("appName", entry.appName)
        addField("bastion_email", entry.email)
        addField("email", entry.email)
        addField("bastion_phone", entry.phone)
        addField("phone", entry.phone)
        addField("bastion_address_line", entry.addressLine)
        addField("bastion_city", entry.city)
        addField("bastion_state", entry.state)
        addField("bastion_zip_code", entry.zipCode)
        addField("bastion_country", entry.country)
        // passkey 绑定关系已由官方 login.fido2Credentials 承载（见 PasskeyMapper / Fido2CredentialCodec），
        // 不再写入 bastion_passkey_bindings 私有自定义字段（避免服务器端冗余脏数据）。
        // 下载侧改为从本地 passkeys 表重建 passkey_bindings 列。
        // T2: WIFI / SSO 扩展元数据走隐藏字段，消除 REST 同步静默丢数据
        if (entry.loginType.equals("WIFI", ignoreCase = true)) {
            addField("bastion_login_type", "WIFI")
            addField("bastion_wifi_data", entry.wifiMetadata)
        } else if (entry.loginType.equals("SSO", ignoreCase = true)) {
            addField("bastion_login_type", "SSO")
            addField("bastion_sso_provider", entry.ssoProvider)
        }
        SshKeyDataCodec.decode(entry.sshKeyData)?.let { ssh ->
            addField("bastion_ssh_algorithm", ssh.algorithm)
            addField("bastion_ssh_key_size", ssh.keySize.takeIf { it > 0 }?.toString().orEmpty())
            addField("bastion_ssh_public_key", ssh.publicKeyOpenSsh)
            addField("bastion_ssh_private_key", ssh.privateKeyOpenSsh)
            addField("bastion_ssh_fingerprint", ssh.fingerprintSha256)
            addField("bastion_ssh_comment", ssh.comment)
            addField("bastion_ssh_format", ssh.format)
        }

        val legacyAddress = listOf(entry.addressLine, entry.city, entry.state, entry.zipCode, entry.country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        addField("address", legacyAddress)

        // 读取本地 custom_fields 表（用户自由自定义字段 + 本地新增字段），随条目回传 Bitwarden，
        // 与下载侧修复配套，确保「下载 → 上传」往返不丢字段。
        val localCustomFields = runCatchingObserved {
            customFieldDao.getFieldsByEntryIdSync(entry.id)
        }.getOrElse { emptyList() }
        val customCipherFields = localCustomFields
            .filter { it.title.isNotBlank() && !isReservedBitwardenFieldName(it.title) }
            .map { cf ->
                CipherFieldApiData(
                    name = crypto.encryptString(cf.title, symmetricKey),
                    value = crypto.encryptString(cf.value, symmetricKey),
                    type = if (cf.isProtected) 1 else 0
                )
            }

        if (fields.isEmpty() && customCipherFields.isEmpty()) return null

        return fields.map { (name, value) ->
            CipherFieldApiData(
                name = crypto.encryptString(name, symmetricKey),
                value = crypto.encryptString(value, symmetricKey),
                type = 0
            )
        } + customCipherFields
    }

    /**
     * 为 SSH 密钥条目构建加密的自定义字段列表。
     * 使用 bastion_ssh_* 前缀字段名，同步回来时 buildSshKeyDataFromCustomFields 可识别。
     */
    private fun buildEncryptedSshKeyCustomFields(
        entry: PasswordEntry,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherFieldApiData> {
        val crypto = com.bastion.app.bitwarden.crypto.BitwardenCrypto
        val ssh = SshKeyDataCodec.decode(entry.sshKeyData) ?: return emptyList()
        val fields = mutableListOf<Pair<String, String>>()

        fun addField(name: String, value: String) {
            if (value.isNotBlank()) fields.add(name to value)
        }

        addField("bastion_ssh_algorithm", ssh.algorithm)
        addField("bastion_ssh_key_size", ssh.keySize.takeIf { it > 0 }?.toString().orEmpty())
        addField("bastion_ssh_public_key", ssh.publicKeyOpenSsh)
        addField("bastion_ssh_private_key", ssh.privateKeyOpenSsh)
        addField("bastion_ssh_fingerprint", ssh.fingerprintSha256)
        addField("bastion_ssh_comment", ssh.comment)
        addField("bastion_ssh_format", ssh.format)
        // 添加 bastion_login_type 标记，方便同步时快速识别
        addField("bastion_login_type", LOGIN_TYPE_SSH_KEY)

        return fields.map { (name, value) ->
            CipherFieldApiData(
                name = crypto.encryptString(name, symmetricKey),
                value = crypto.encryptString(value, symmetricKey),
                type = if (name == "bastion_ssh_private_key") 1 else 0 // Hidden type for private key
            )
        }
    }

    private fun parseLoginUris(
        uris: List<CipherUriApiData>?,
        symmetricKey: SymmetricCryptoKey
    ): ParsedLoginUris {
        if (uris.isNullOrEmpty()) return ParsedLoginUris()

        val websites = linkedSetOf<String>()
        var appPackageName = ""
        uris.forEach { uriData ->
            val uri = decryptString(uriData.uri, symmetricKey) ?: return@forEach
            when {
                uri.startsWith("androidapp://", ignoreCase = true) -> {
                    if (appPackageName.isBlank()) {
                        appPackageName = uri.removePrefix("androidapp://")
                    }
                }
                else -> websites += normalizeBitwardenWebsite(uri)
            }
        }
        return ParsedLoginUris(
            website = PasswordWebsiteCodec.encode(websites.toList()),
            appPackageName = appPackageName
        )
    }

    private fun parsePasswordCustomFieldMap(
        fields: List<CipherFieldApiData>?,
        symmetricKey: SymmetricCryptoKey
    ): Map<String, String> {
        if (fields.isNullOrEmpty()) return emptyMap()
        return buildMap {
            fields.forEach { field ->
                val name = decryptOrPlain(field.name, symmetricKey).orEmpty().trim()
                if (name.isBlank()) return@forEach
                val value = decryptOrPlain(field.value, symmetricKey).orEmpty()
                put(name, value)
            }
        }
    }

    private fun decryptOrPlain(value: String?, key: SymmetricCryptoKey): String? {
        if (value.isNullOrBlank()) return null
        val decrypted = decryptString(value, key)
        if (decrypted != null) return decrypted
        if (looksLikeCipherString(value)) return null
        return value
    }

    private fun looksLikeCipherString(value: String): Boolean {
        return CIPHER_STRING_PATTERN.matches(value)
    }

    private fun buildSshKeyDataFromCustomFields(fields: Map<String, String>): String {
        // 1. 先按字段名匹配（Bastion 自己上传的格式）
        val publicKey = fields.findSshField(
            "bastion_ssh_public_key",
            "publicKey",
            "public_key",
            "public key",
            "public-key",
            "ssh public key",
            "ssh_public_key"
        )
        val privateKey = fields.findSshField(
            "bastion_ssh_private_key",
            "privateKey",
            "private_key",
            "private key",
            "private-key",
            "ssh private key",
            "ssh_private_key"
        )
        val fingerprint = fields.findSshField(
            "bastion_ssh_fingerprint",
            "keyFingerprint",
            "key_fingerprint",
            "key fingerprint",
            "key-fingerprint",
            "fingerprint",
            "ssh fingerprint",
            "ssh_fingerprint"
        )

        // 2. 如果按名称没找到，尝试按值的内容特征识别（兼容 Bitwarden 等第三方客户端）
        val resolvedPublicKey = publicKey.ifBlank { fields.findValueByContent(::looksLikeSshPublicKey) }
        val resolvedPrivateKey = privateKey.ifBlank { fields.findValueByContent(::looksLikeSshPrivateKey) }
        val resolvedFingerprint = fingerprint.ifBlank { fields.findValueByContent(::looksLikeSshFingerprint) }

        val algorithm = fields.findSshField("bastion_ssh_algorithm", "algorithm", "key type", "key-type", "keyType")
            .ifBlank { inferSshAlgorithm(resolvedPublicKey) }

        return SshKeyDataCodec.encode(
            SshKeyData(
                algorithm = algorithm,
                keySize = fields["bastion_ssh_key_size"]?.toIntOrNull() ?: 0,
                publicKeyOpenSsh = resolvedPublicKey,
                privateKeyOpenSsh = resolvedPrivateKey,
                fingerprintSha256 = resolvedFingerprint,
                comment = fields["bastion_ssh_comment"].orEmpty(),
                format = fields["bastion_ssh_format"].orEmpty().ifBlank { SshKeyData.FORMAT_OPENSSH }
            )
        )
    }

    private fun looksLikeSshPublicKey(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("ssh-rsa ") ||
            trimmed.startsWith("ssh-ed25519 ") ||
            trimmed.startsWith("ecdsa-sha2-") ||
            trimmed.startsWith("ssh-dss ")
    }

    private fun looksLikeSshPrivateKey(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("-----BEGIN") &&
            (trimmed.contains("PRIVATE KEY") || trimmed.contains("OPENSSH"))
    }

    private fun looksLikeSshFingerprint(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("SHA256:") ||
            trimmed.startsWith("MD5:") ||
            (trimmed.length in 40..50 && trimmed.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
    }

    private fun Map<String, String>.findValueByContent(predicate: (String) -> Boolean): String {
        return values.firstOrNull { it.isNotBlank() && predicate(it) }.orEmpty()
    }

    private fun Map<String, String>.findSshField(vararg names: String): String {
        names.firstNotNullOfOrNull { this[it]?.takeIf(String::isNotBlank) }?.let { return it }
        val normalizedNames = names.map { normalizeSshFieldName(it) }.toSet()
        return entries.firstOrNull { (name, value) ->
            value.isNotBlank() && normalizeSshFieldName(name) in normalizedNames
        }?.value.orEmpty()
    }

    private fun normalizeSshFieldName(name: String): String {
        return name.filter { it.isLetterOrDigit() }.lowercase()
    }

    private fun inferSshAlgorithm(publicKey: String): String {
        return when {
            publicKey.startsWith("ssh-rsa") -> SshKeyData.ALGORITHM_RSA
            publicKey.startsWith("ssh-ed25519") -> SshKeyData.ALGORITHM_ED25519
            else -> ""
        }
    }

    /**
     * Resolve local stored password to plain text before uploading to Bitwarden.
     *
     * Local DB stores encrypted payloads. If we upload that payload directly,
     * it will be encrypted again by Bitwarden and eventually show as "garbled" text.
     */
    private fun resolvePlainPasswordForBitwardenUpload(storedPassword: String, entryId: Long): String {
        if (storedPassword.isBlank()) return ""

        var candidate = storedPassword
        repeat(3) {
            val decrypted = try {
                securityManager.decryptData(candidate)
            } catch (e: Exception) {
                // For prefixed payloads, decrypt failure means auth/key state is invalid.
                // Failing closed avoids uploading ciphertext as if it were plaintext.
                if (
                    candidate.startsWith("MDK|") ||
                    candidate.startsWith("V2|") ||
                    candidate.startsWith("C2|")
                ) {
                    throw IllegalStateException(
                        "Cannot decrypt local password for Bitwarden upload, entryId=$entryId",
                        e
                    )
                }
                return candidate
            }

            if (decrypted == candidate) {
                return candidate
            }
            candidate = decrypted
        }
        return candidate
    }

    private fun resolvePlainStoredSensitiveValueForBitwardenUpload(
        storedValue: String,
        entryId: Long,
        fieldName: String
    ): String {
        if (storedValue.isBlank()) return ""
        if (!securityManager.looksLikeBastionCiphertext(storedValue)) return storedValue

        return runCatchingObserved { securityManager.decryptData(storedValue) }.getOrElse { error ->
            throw IllegalStateException(
                "Cannot decrypt local $fieldName for Bitwarden upload, entryId=$entryId",
                error
            )
        }
    }

    private fun encodeBitwardenTotpForLocalStorage(totpPayload: String): String {
        if (totpPayload.isBlank()) return ""
        return securityManager.encryptDataLegacyCompat(totpPayload)
    }

    /**
     * Bitwarden 密码需要支持“服务器暂时不可用时的本地查看”。
     * 若主路径加密后的密文当前无法立即读回，则降级为兼容密文，避免详情页出现空密码。
     */
    private fun encryptBitwardenPasswordForOfflineDisplay(
        plainPassword: String,
        cipherId: String
    ): String {
        val primaryEncrypted = securityManager.encryptData(plainPassword)
        val primaryReadable = runCatchingObserved { securityManager.decryptData(primaryEncrypted) }
            .getOrNull()
            ?.let { it == plainPassword }
            ?: false
        if (primaryReadable) {
            return primaryEncrypted
        }

        android.util.Log.w(
            TAG,
            "Bitwarden password payload is not immediately readable; fallback to legacy V1, cipherId=$cipherId"
        )

        val legacyEncrypted = securityManager.encryptDataLegacyCompat(plainPassword)
        val legacyReadable = runCatchingObserved { securityManager.decryptData(legacyEncrypted) }
            .getOrNull()
            ?.let { it == plainPassword }
            ?: false

        return if (legacyReadable) {
            legacyEncrypted
        } else {
            android.util.Log.w(
                TAG,
                "Legacy fallback is still unreadable; keep primary encrypted payload, cipherId=$cipherId"
            )
            primaryEncrypted
        }
    }
}

// ========== 同步结果 ==========

sealed class SyncResult {
    data class Success(
        val foldersAdded: Int,
        val ciphersAdded: Int,
        val ciphersUpdated: Int,
        val conflictsDetected: Int,
        val skippedDueToLocalDirty: Int
    ) : SyncResult()
    
    data class Error(val message: String) : SyncResult()
    
    /**
     * 空 Vault 保护阻止了同步
     */
    data class EmptyVaultBlocked(
        val localCount: Int,
        val serverCount: Int,
        val reason: String
    ) : SyncResult()
}

sealed class CipherSyncResult {
    object Added : CipherSyncResult()
    object Updated : CipherSyncResult()
    object Conflict : CipherSyncResult()
    data class Skipped(val reason: String) : CipherSyncResult()
    data class Error(val message: String) : CipherSyncResult()
}

sealed class UploadResult {
    data class Success(
        val uploaded: Int,
        val failed: Int,
        val authRequiredFailures: Int = 0
    ) : UploadResult()
    data class Error(val message: String) : UploadResult()
}
