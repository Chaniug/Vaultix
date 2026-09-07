package com.bastion.app.bitwarden.service

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.bastion.app.bitwarden.api.*
import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import com.bastion.app.bitwarden.mapper.*
import com.bastion.app.bitwarden.sync.SyncItemType
import com.bastion.app.data.*
import com.bastion.app.data.model.BankCardData
import com.bastion.app.data.model.CardWalletDataCodec
import com.bastion.app.data.model.CardType
import com.bastion.app.data.model.DocumentData
import com.bastion.app.data.model.DocumentType
import com.bastion.app.data.model.NoteData
import com.bastion.app.data.model.SecureCustomField
import com.bastion.app.data.model.SecureCustomFieldType
import com.bastion.app.data.model.LOGIN_TYPE_SSH_KEY
import com.bastion.app.data.model.PasskeyBinding
import com.bastion.app.data.model.PasskeyBindingCodec
import com.bastion.app.data.model.SshKeyData
import com.bastion.app.data.model.SshKeyDataCodec
import com.bastion.app.data.model.TotpData
import com.bastion.app.data.model.formatForDisplay
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.passkey.PasskeyCredentialIdCodec
import com.bastion.app.passkey.PasskeyPrivateKeyStore
import com.bastion.app.security.SecurityManager
import com.bastion.app.util.TotpDataResolver
import java.time.Instant
import java.util.Date

/**
 * 多类型 Cipher 同步处理器
 * 
 * 扩展现有的同步服务，支持所有 Cipher 类型：
 * - Type 1 (Login): PasswordEntry, TOTP, Passkey
 * - Type 2 (SecureNote): SecureItem(NOTE)
 * - Type 3 (Card): SecureItem(BANK_CARD)
 * - Type 4 (Identity): SecureItem(DOCUMENT)
 */
class CipherSyncProcessor(
    private val context: Context
) {
    companion object {
        private const val TAG = "CipherSyncProcessor"
        const val SKIP_REMOTE_UNCHANGED = "Remote unchanged"
        private val LEGACY_MONICA_FIELD_NAMES = setOf(
            "bastionlocalid",
            "bastionsecureitemid",
            "bastionitemtype",
            "bastionitemdata",
            "bastionimagepaths",
            "bastionisfavorite"
        )
        private val LEGACY_CARD_FIELD_NAMES = setOf(
            "bastion_bank_name",
            "bastion_card_type",
            "bastion_billing_address",
            "bastion_nickname",
            "bastion_valid_from_month",
            "bastion_valid_from_year",
            "bastion_pin",
            "bastion_iban",
            "bastion_swift_bic",
            "bastion_routing_number",
            "bastion_account_number",
            "bastion_branch_code",
            "bastion_currency",
            "bastion_customer_service_phone"
        )
        private val READABLE_CARD_FIELD_NAMES = setOf(
            "bank name",
            "card type",
            "billing address",
            "nickname",
            "valid from month",
            "valid from year",
            "pin",
            "iban",
            "swift/bic",
            "routing number",
            "account number",
            "branch code",
            "currency",
            "customer service phone"
        )
    }

    private data class ParsedLoginUris(
        val website: String = "",
        val appPackageName: String = ""
    )

    private fun normalizeWebsite(rawWebsite: String): String {
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

    private fun hasSameRemoteRevision(localRevision: String?, remoteRevision: String?): Boolean {
        return !localRevision.isNullOrBlank() && localRevision == remoteRevision
    }

    private fun shouldSkipPasswordRemoteSync(
        existing: PasswordEntry,
        cipher: CipherApiResponse,
        serverDeletedAt: Date?,
        serverArchivedAt: Date?,
        allowSshKeySkip: Boolean = false,
        sshNativeMigrationDue: Boolean = false
    ): Boolean {
        if (!hasSameRemoteRevision(existing.bitwardenRevisionDate, cipher.revisionDate)) return false
        if (existing.bitwardenLocalModified) return false
        if (existing.isDeleted || serverDeletedAt != null) {
            // 删除态幂等：本地已是软删除、删除时间与 revision 都和服务器一致时，直接按
            // 未变更跳过。否则服务端回收站里的 cipher 每轮同步都会被重写一遍并恒定计入
            // 一条「变更」（真机实证：每轮 overallSyncResult ciphersUpdated=1 的真凶）。
            // 仅当删除态真实变化（新删除 / 服务器侧恢复 / 删除时间变动）时才继续走更新分支。
            if (existing.isDeleted &&
                existing.deletedAt == serverDeletedAt &&
                hasSameRemoteRevision(existing.bitwardenRevisionDate, cipher.revisionDate)
            ) {
                return true
            }
            return false
        }
        if (existing.bitwardenFolderId != cipher.folderId) return false
        if (existing.isArchived != (serverArchivedAt != null)) return false
        if (!allowSshKeySkip &&
            existing.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true) &&
            existing.sshKeyData.isNotBlank()
        ) {
            // SSH 降级存储（Type 1 + 自定义字段）条目：仅当服务端自定义字段全空
            // （SSH 数据可能被官方端弄丢）时才需要重处理以触发重新上传
            // （见 syncPasswordCipher 的 serverLostSshData 判定，其前提同样是 customFields 为空）。
            // 字段还在就按未变更跳过，否则每轮同步都会把该条目重写并计成一条「变更」，
            // 表现为通知栏永远显示「已同步 1 条变更」。fields 是否为空无需解密即可判断。
            if (cipher.fields.isNullOrEmpty()) return false
            // 服务端支持原生 Type 5 且该条目仍是降级形态：重处理一次，
            // 由 syncPasswordCipher 打脏标记推动「降级 → 原生 Type 5」迁移重传。
            // 迁移上传若被服务端拒绝会自动进入冷却期，此处随之停摆，不会循环。
            if (sshNativeMigrationDue) return false
        }
        return true
    }

    private fun shouldSkipSecureItemRemoteSync(
        existing: SecureItem,
        cipher: CipherApiResponse,
        serverDeletedAt: Date?
    ): Boolean {
        if (!hasSameRemoteRevision(existing.bitwardenRevisionDate, cipher.revisionDate)) return false
        if (existing.bitwardenLocalModified == true) return false
        if (existing.isDeleted || serverDeletedAt != null) {
            // 与 password 路径同款删除态幂等：回收站里的 secure item 稳定后不再重复计变更。
            if (existing.isDeleted &&
                existing.deletedAt == serverDeletedAt &&
                hasSameRemoteRevision(existing.bitwardenRevisionDate, cipher.revisionDate)
            ) {
                return true
            }
            return false
        }
        if (existing.bitwardenFolderId != cipher.folderId) return false
        if (!existing.syncStatus.equals("SYNCED", ignoreCase = true)) return false
        return true
    }

    private suspend fun resolveLocalDirtySecureItem(
        existing: SecureItem,
        vault: BitwardenVault,
        cipher: CipherApiResponse
    ): CipherSyncResult? {
        if (existing.bitwardenLocalModified != true) return null
        if (existing.bitwardenVaultId != vault.id || existing.bitwardenFolderId != cipher.folderId) {
            secureItemDao.update(
                existing.copy(
                    bitwardenVaultId = vault.id,
                    bitwardenFolderId = cipher.folderId,
                    updatedAt = Date()
                )
            )
        }
        if (existing.bitwardenRevisionDate != cipher.revisionDate) {
            return CipherSyncResult.Conflict
        }
        return CipherSyncResult.Skipped("Local changes pending upload")
    }
    
    private val database = PasswordDatabase.getDatabase(context)
    private val passwordEntryDao = database.passwordEntryDao()
    private val secureItemDao = database.secureItemDao()
    private val passkeyDao = database.passkeyDao()
    private val pendingOpDao = database.bitwardenPendingOperationDao()
    private val customFieldDao = database.customFieldDao()
    private val securityManager = SecurityManager(context)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * 处理从服务器同步的 Cipher
     * 自动识别类型并路由到对应的处理器
     */
    suspend fun syncCipherFromServer(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): CipherSyncResult {
        return BitwardenCipherKeyResolver.withCipherKey(cipher, symmetricKey, TAG) { effectiveKey ->
            val serverDeletedAt = parseBitwardenDeletedAt(cipher.deletedDate)
            val serverArchivedAt = parseBitwardenArchivedAt(cipher.archivedDate)

            // 调试：记录 cipher 类型路由信息
            if (cipher.type == 5 || cipher.sshKey != null) {
                android.util.Log.i(TAG, "CIPHER_ROUTE id=${cipher.id} type=${cipher.type} hasSshKey=${cipher.sshKey != null} fieldsCount=${cipher.fields?.size ?: 0}")
            }

            val syncResult = when (cipher.type) {
                1 -> syncLoginCipher(vault, cipher, effectiveKey, serverDeletedAt, serverArchivedAt)
                2 -> syncSecureNoteCipher(vault, cipher, effectiveKey, serverDeletedAt)
                3 -> syncCardCipher(vault, cipher, effectiveKey, serverDeletedAt)
                4 -> syncIdentityCipher(vault, cipher, effectiveKey, serverDeletedAt)
                5 -> syncSshKeyCipher(vault, cipher, effectiveKey, serverDeletedAt, serverArchivedAt)
                else -> CipherSyncResult.Skipped("Unknown cipher type: ${cipher.type}")
            }
            syncResult
        }
    }
    
    /**
     * 判断是否为 Bastion 自己为「独立验证器」创建的承载 cipher。
     *
     * 判据比 [TotpMapper.isStandaloneTotpCipher] 更严格：额外要求 login.uris 中带
     * otpauth:// 前缀——该 URI 由 TotpMapper.toCreateRequest 写入，用于同步时回解 issuer，
     * 是 Bastion 承载 cipher 的独有特征。这样用户手动创建的「只填了 TOTP、没填密码」的
     * 密码条目不会被误判，仍按密码条目同步，行为保持不变。
     */
    private fun isBastionStandaloneTotpContainer(
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        if (!TotpMapper.isStandaloneTotpCipher(cipher)) return false
        return cipher.login?.uris.orEmpty().any { uriData ->
            val rawUri = uriData.uri?.trim().orEmpty()
            if (rawUri.isBlank()) return@any false
            // URI 在服务端是加密存储的（与 syncPasswordCipher 里的 parseLoginUris 同理），
            // 必须先解密再比对。直接拿密文比 "otpauth://" 前缀永远不可能匹配，
            // 承载验证器的 cipher 会被误判成普通密码条目，本地凭空多出一条重复条目
            // （用户反馈的「同步后密码列表多一条纯验证码条目」即由此产生）。
            // 非加密串（历史/手改数据）时回退原文比对，行为保持不变。
            val resolved = decryptString(rawUri, symmetricKey) ?: rawUri
            resolved.trim().startsWith("otpauth://", ignoreCase = true)
        }
    }

    /**
     * 同步 Login 类型 Cipher (Type 1)
     * 可能是: Password, TOTP, 或 Passkey
     */
    private suspend fun syncLoginCipher(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey,
        serverDeletedAt: Date?,
        serverArchivedAt: Date?
    ): CipherSyncResult {
        // Bastion 为「独立验证器」创建的承载 cipher（type=1、无密码、只有 totp，
        // login.uris 写成 otpauth://totp/{issuer}）在语义上并不是密码条目。
        // 这类 cipher 必须只走 TotpMapper：若再走 syncPasswordCipher，同一个 cipher 会被
        // 额外解析成一条密码条目，导致本地凭空多出一条重复条目——正是用户反馈的
        // 「同步后密码界面多一条、验证器界面却因去重规则不显示」的根因。
        if (isBastionStandaloneTotpContainer(cipher, symmetricKey)) {
            // 诊断日志：承载验证器的 cipher 只应同步为验证器，不能再生成一条密码条目。
            android.util.Log.d(
                TAG,
                "CIPHER_ROUTE_TOTP_CONTAINER id=${cipher.id} name=${cipher.name} " +
                    "uris=${cipher.login?.uris?.map { it.uri }} -> 只同步为验证器"
            )
            return syncTotpCipher(vault, cipher, symmetricKey, serverDeletedAt)
        }
        android.util.Log.d(TAG, "CIPHER_ROUTE_PASSWORD id=${cipher.id} name=${cipher.name}")

        val passwordResult = syncPasswordCipher(vault, cipher, symmetricKey, serverDeletedAt, serverArchivedAt)
        val passwordRemoteUnchanged =
            passwordResult is CipherSyncResult.Skipped && passwordResult.reason == SKIP_REMOTE_UNCHANGED
        val supplementalResult = when {
            PasskeyMapper.isPasskeyCipher(cipher) -> {
                val passkeyResult = syncPasskeyCipher(vault, cipher, symmetricKey, passwordRemoteUnchanged)
                // 重建密码条目的 passkey_bindings 列：绑定关系以本地 passkeys 表为准
                // （boundPasswordId 已回填），不再依赖服务器 bastion_passkey_bindings 自定义字段。
                rebuildPasswordPasskeyBindings(vault, cipher)
                passkeyResult
            }
            TotpMapper.isStandaloneTotpCipher(cipher) -> {
                syncTotpCipher(vault, cipher, symmetricKey, serverDeletedAt)
            }
            else -> {
                null
            }
        }

        return when (passwordResult) {
            is CipherSyncResult.Added,
            is CipherSyncResult.Updated,
            is CipherSyncResult.Conflict -> passwordResult
            is CipherSyncResult.Skipped -> supplementalResult ?: passwordResult
            is CipherSyncResult.Error -> supplementalResult ?: passwordResult
        }
    }
    
    /**
     * 同步密码条目
     */
    private suspend fun syncPasswordCipher(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey,
        serverDeletedAt: Date?,
        serverArchivedAt: Date?
    ): CipherSyncResult {
        val login = cipher.login ?: return CipherSyncResult.Skipped("No login data")
        val isServerDeleted = serverDeletedAt != null

        // 先按 cipherId 收敛历史重复副本，避免“同一条目异常膨胀”。
        val existing = resolveCanonicalPasswordEntry(vault.id, cipher.id)
        val hasPendingDelete = pendingOpDao.hasActiveDeleteByCipher(vault.id, cipher.id)
        if (existing == null && hasPendingDelete && !isServerDeleted) {
            return CipherSyncResult.Skipped("Pending local delete")
        }
        if (existing != null && hasPendingDelete && !isServerDeleted) {
            return CipherSyncResult.Skipped("Local delete wins")
        }
        if (existing != null && shouldSkipPasswordRemoteSync(
                existing = existing,
                cipher = cipher,
                serverDeletedAt = serverDeletedAt,
                serverArchivedAt = serverArchivedAt,
                sshNativeMigrationDue = existing.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true) &&
                    existing.sshKeyData.isNotBlank() &&
                    SshNativeUploadPolicy.isNativeUploadAllowed(context, vault.id)
            )
        ) {
            return CipherSyncResult.Skipped(SKIP_REMOTE_UNCHANGED)
        }
        
        // 解密字段
        val name = decryptString(cipher.name, symmetricKey) ?: "Untitled"
        val username = decryptString(login.username, symmetricKey) ?: ""
        val decryptedPassword = decryptString(login.password, symmetricKey)
        // login.password 有值但无法解密时，不能回写为空，否则会制造“幽灵空密码”副本。
        if (!login.password.isNullOrBlank() && decryptedPassword == null) {
            android.util.Log.w(TAG, "Skip cipher ${cipher.id}: password decrypt failed")
            return CipherSyncResult.Skipped("Password decrypt failed")
        }
        val password = decryptedPassword ?: ""
        val notes = decryptString(cipher.notes, symmetricKey) ?: ""
        val totp = decryptString(login.totp, symmetricKey) ?: ""
        val parsedUris = parseLoginUris(login.uris, symmetricKey)
        val customFields = decryptCustomFieldMap(cipher.fields, symmetricKey)
        // 调试：对有自定义字段的 cipher 记录 ID
        if (customFields.isNotEmpty()) {
            android.util.Log.i(TAG, "syncPasswordCipher has ${customFields.size} custom fields")
        }
        // 提取用户自由自定义字段（排除 bastion 保留名），写入 custom_fields 表，
        // 修复 Bitwarden 登录型条目自定义字段下载后不可见的问题。
        val userCustomFields = buildLocalCustomFields(cipher.fields, symmetricKey)
        val remoteAppPackage = customFields["bastion_app_package"]
            ?: customFields["appPackageName"]
            ?: customFields["monica_app_package"]
            ?: parsedUris.appPackageName
        val remoteAppName = customFields["bastion_app_name"]
            ?: customFields["appName"]
            ?: customFields["monica_app_name"]
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
        // 调试：记录 SSH 识别结果
        if (customFields.isNotEmpty()) {
            android.util.Log.i(TAG, "syncPasswordCipher cipherId=${cipher.id} loginType=$remoteLoginType sshDataBlank=${remoteSshKeyData.isBlank()}")
        }
        val encryptedPassword = encryptBitwardenPasswordForOfflineDisplay(password, cipher.id)
        val storedTotp = encodeBitwardenTotpForLocalStorage(totp)
        
        if (existing == null) {
            // 创建新条目（不吞并本地同名条目，保持数据源独立）
            val newEntry = PasswordEntry(
                title = name,
                website = parsedUris.website,
                username = username,
                password = encryptedPassword,
                notes = notes,
                authenticatorKey = storedTotp,
                appPackageName = remoteAppPackage,
                appName = remoteAppName,
                email = remoteEmail,
                phone = remotePhone,
                addressLine = remoteAddress,
                city = remoteCity,
                state = remoteState,
                zipCode = remoteZip,
                country = remoteCountry,
                passkeyBindings = remotePasskeyBindings,
                sshKeyData = remoteSshKeyData,
                loginType = remoteLoginType,
                wifiMetadata = remoteWifiMetadata,
                ssoProvider = remoteSsoProvider,
                isFavorite = cipher.favorite == true,
                createdAt = Date(),
                updatedAt = Date(),
                bitwardenVaultId = vault.id,
                bitwardenCipherId = cipher.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenCipherType = 1,
                bitwardenLocalModified = false,
                isDeleted = isServerDeleted,
                deletedAt = serverDeletedAt,
                isArchived = serverArchivedAt != null,
                archivedAt = serverArchivedAt
            )
            passwordEntryDao.insert(newEntry).also { newId ->
                if (userCustomFields.isNotEmpty()) {
                    customFieldDao.replaceFieldsForEntry(newId, userCustomFields)
                }
            }
            return CipherSyncResult.Added
        } else {
            // BUG-5 修复：本地存在未上传改动、且服务器软删除（非本地删除待决）时，视为并发冲突，
            // 保留本地编辑并交给冲突流程处理，避免服务器删除静默丢弃本地未上传数据。
            // 但本地删除墓碑（isDeleted=true）不算"未上传改动"：本地删除经 pending DELETE op
            // 上传成功后服务器才软删（deletedDate 置位、revision 变新），此时 op 已 COMPLETED、
            // 墓碑的 bitwardenLocalModified 尚未清除——双方对删除已达成一致，若不排除会在这里
            // 误报冲突（实证：每删一条密码，下轮同步就报一条冲突）。真冲突仍要求本地条目
            // 未被删除（!isDeleted），即"本地编辑未上传 + 服务器被删"才成立。
            if (!existing.isDeleted && existing.bitwardenLocalModified && isServerDeleted && !hasPendingDelete) {
                return CipherSyncResult.Conflict
            }
            if (isServerDeleted) {
                passwordEntryDao.update(
                    existing.copy(
                        title = name,
                        website = parsedUris.website.ifBlank { existing.website },
                        username = username,
                        password = encryptedPassword,
                        notes = notes,
                        authenticatorKey = storedTotp,
                        appPackageName = remoteAppPackage.ifBlank { existing.appPackageName },
                        appName = remoteAppName.ifBlank { existing.appName },
                        email = remoteEmail.ifBlank { existing.email },
                        phone = remotePhone.ifBlank { existing.phone },
                        addressLine = remoteAddress.ifBlank { existing.addressLine },
                        city = remoteCity.ifBlank { existing.city },
                        state = remoteState.ifBlank { existing.state },
                        zipCode = remoteZip.ifBlank { existing.zipCode },
                        country = remoteCountry.ifBlank { existing.country },
                        passkeyBindings = remotePasskeyBindings.ifBlank { existing.passkeyBindings },
                        sshKeyData = remoteSshKeyData.ifBlank { existing.sshKeyData },
                        loginType = if (remoteSshKeyData.isNotBlank()) LOGIN_TYPE_SSH_KEY else existing.loginType,
                        wifiMetadata = remoteWifiMetadata.ifBlank { existing.wifiMetadata },
                        ssoProvider = remoteSsoProvider.ifBlank { existing.ssoProvider },
                        isFavorite = cipher.favorite == true,
                        isDeleted = true,
                        deletedAt = serverDeletedAt,
                        isArchived = false,
                        archivedAt = null,
                        updatedAt = Date(),
                        bitwardenVaultId = vault.id,
                        bitwardenFolderId = cipher.folderId,
                        bitwardenRevisionDate = cipher.revisionDate,
                        bitwardenLocalModified = false
                    )
                )
                return CipherSyncResult.Updated
            }
            // 更新现有条目
            if (existing.bitwardenLocalModified) {
                if (existing.bitwardenVaultId != vault.id || existing.bitwardenFolderId != cipher.folderId) {
                    passwordEntryDao.update(
                        existing.copy(
                            bitwardenVaultId = vault.id,
                            bitwardenFolderId = cipher.folderId,
                            updatedAt = Date()
                        )
                    )
                }
                if (existing.bitwardenRevisionDate != cipher.revisionDate) {
                    return CipherSyncResult.Conflict
                }
                return CipherSyncResult.Skipped("Local changes pending upload")
            }
            
            // 如果本地是 SSH 密钥但服务端返回空数据（Type 5 被降级），
            // 标记为本地已修改，触发重新上传（上传侧现在优先原生 Type 5，被拒才回落降级格式）。
            val localIsSshKey = existing.loginType.equals(LOGIN_TYPE_SSH_KEY, ignoreCase = true) &&
                existing.sshKeyData.isNotBlank()
            val serverLostSshData = localIsSshKey && remoteSshKeyData.isBlank() &&
                customFields.isEmpty()
            // 降级 → 原生迁移：服务端支持原生 Type 5 且该 cipher 仍是「Type 1 + 自定义字段」
            //（远端 SSH 数据来自字段解密）时，打脏标记推动下轮 push 以原生 Type 5 重传，
            // 与 Bitwarden 官方条目结构统一。迁移上传被拒会自动进入冷却期，此处随之停摆，不循环。
            val migrateSshToNative = localIsSshKey && remoteSshKeyData.isNotBlank() &&
                SshNativeUploadPolicy.isNativeUploadAllowed(context, vault.id)
            
            val updated = existing.copy(
                title = name,
                website = parsedUris.website.ifBlank { existing.website },
                username = username,
                password = encryptedPassword,
                notes = notes,
                authenticatorKey = storedTotp,
                appPackageName = remoteAppPackage.ifBlank { existing.appPackageName },
                appName = remoteAppName.ifBlank { existing.appName },
                email = remoteEmail.ifBlank { existing.email },
                phone = remotePhone.ifBlank { existing.phone },
                addressLine = remoteAddress.ifBlank { existing.addressLine },
                city = remoteCity.ifBlank { existing.city },
                state = remoteState.ifBlank { existing.state },
                zipCode = remoteZip.ifBlank { existing.zipCode },
                country = remoteCountry.ifBlank { existing.country },
                passkeyBindings = remotePasskeyBindings.ifBlank { existing.passkeyBindings },
                sshKeyData = remoteSshKeyData.ifBlank { existing.sshKeyData },
                loginType = if (remoteSshKeyData.isNotBlank()) LOGIN_TYPE_SSH_KEY else existing.loginType,
                wifiMetadata = remoteWifiMetadata.ifBlank { existing.wifiMetadata },
                ssoProvider = remoteSsoProvider.ifBlank { existing.ssoProvider },
                isFavorite = cipher.favorite == true,
                isDeleted = false,
                deletedAt = null,
                isArchived = serverArchivedAt != null,
                archivedAt = serverArchivedAt,
                updatedAt = Date(),
                bitwardenVaultId = vault.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                // 如果服务端丢失了 SSH 数据，标记为本地修改以触发重新上传；
                // 降级形态条目在服务端支持原生 Type 5 时同样打脏标记以触发迁移重传。
                bitwardenLocalModified = serverLostSshData || migrateSshToNative
            )
            passwordEntryDao.update(updated)
            // 远端有自定义字段时，以远端为准整体替换本地 custom_fields（本地未上传改动已被上方
            // bitwardenLocalModified 分支拦截，不会误覆盖）；远端无自定义字段则保留本地。
            if (userCustomFields.isNotEmpty()) {
                customFieldDao.replaceFieldsForEntry(existing.id, userCustomFields)
            }
            if (serverLostSshData) {
                android.util.Log.i(TAG, "SSH key ${cipher.id} lost on server, marking for re-upload as Type 1 + fields")
            }
            return CipherSyncResult.Updated
        }
    }

    private suspend fun resolveCanonicalPasswordEntry(vaultId: Long, cipherId: String): PasswordEntry? {
        val allEntries = passwordEntryDao.getAllByBitwardenCipherIdInVault(vaultId, cipherId)
        if (allEntries.isEmpty()) return null

        val canonical = allEntries.maxWithOrNull(
            compareBy<PasswordEntry> { if (it.isDeleted) 2 else if (it.bitwardenLocalModified) 1 else 0 }
                .thenBy { if (hasLikelyNonBlankPassword(it)) 1 else 0 }
                .thenBy { it.updatedAt.time }
                .thenBy { it.id }
        ) ?: allEntries.first()

        if (allEntries.size > 1) {
            val duplicates = allEntries.filter { it.id != canonical.id }
            duplicates.forEach { passwordEntryDao.delete(it) }
            android.util.Log.w(
                TAG,
                "Removed ${duplicates.size} duplicate password rows for cipherId=$cipherId"
            )
        }

        return canonical
    }

    private fun hasLikelyNonBlankPassword(entry: PasswordEntry): Boolean {
        if (entry.password.isBlank()) return false

        val decrypted = runCatchingObserved { securityManager.decryptData(entry.password) }.getOrNull()
        return when {
            decrypted == null -> true
            decrypted.isBlank() -> false
            else -> true
        }
    }

    private fun buildSshKeyDataFromCustomFields(fields: Map<String, String>): String {
        if (fields.isNotEmpty()) {
            android.util.Log.i(TAG, "SSH custom fields available: count=${fields.size}")
        }

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

        if (fields.isNotEmpty()) {
            android.util.Log.i(
                TAG,
                "SSH fields resolved: hasPublic=${resolvedPublicKey.isNotBlank()}, " +
                    "hasPrivate=${resolvedPrivateKey.isNotBlank()}, hasFingerprint=${resolvedFingerprint.isNotBlank()}"
            )
        }

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

    /** SSH 公钥特征：以 ssh-rsa、ssh-ed25519、ecdsa-sha2- 等开头 */
    private fun looksLikeSshPublicKey(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("ssh-rsa ") ||
            trimmed.startsWith("ssh-ed25519 ") ||
            trimmed.startsWith("ecdsa-sha2-") ||
            trimmed.startsWith("ssh-dss ")
    }

    /** SSH 私钥特征：PEM 格式 BEGIN 头 */
    private fun looksLikeSshPrivateKey(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("-----BEGIN") &&
            (trimmed.contains("PRIVATE KEY") || trimmed.contains("OPENSSH"))
    }

    /** SSH 指纹特征：SHA256: 前缀或 MD5 hex 格式 */
    private fun looksLikeSshFingerprint(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("SHA256:") ||
            trimmed.startsWith("MD5:") ||
            // 纯 base64 指纹（无前缀），长度在合理范围内
            (trimmed.length in 40..50 && trimmed.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
    }

    /** 在所有字段值中查找符合特征的值（跳过已被其他字段使用的值） */
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

    private suspend fun syncSshKeyCipher(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey,
        serverDeletedAt: Date?,
        serverArchivedAt: Date?
    ): CipherSyncResult {
        val sshKey = cipher.sshKey ?: return CipherSyncResult.Skipped("No SSH key data")
        val isServerDeleted = serverDeletedAt != null
        val existing = resolveCanonicalPasswordEntry(vault.id, cipher.id)
        val hasPendingDelete = pendingOpDao.hasActiveDeleteByCipher(vault.id, cipher.id)

        if (existing == null && hasPendingDelete && !isServerDeleted) {
            return CipherSyncResult.Skipped("Pending local delete")
        }
        if (existing != null && hasPendingDelete && !isServerDeleted) {
            return CipherSyncResult.Skipped("Local delete wins")
        }
        if (existing != null && shouldSkipPasswordRemoteSync(
                existing = existing,
                cipher = cipher,
                serverDeletedAt = serverDeletedAt,
                serverArchivedAt = serverArchivedAt,
                allowSshKeySkip = true
            )
        ) {
            return CipherSyncResult.Skipped(SKIP_REMOTE_UNCHANGED)
        }

        val name = decryptString(cipher.name, symmetricKey) ?: "SSH Key"
        val notes = decryptString(cipher.notes, symmetricKey) ?: ""
        val privateKey = decryptString(sshKey.privateKey, symmetricKey).orEmpty()
        val publicKey = decryptString(sshKey.publicKey, symmetricKey).orEmpty()
        val fingerprint = decryptString(sshKey.keyFingerprint, symmetricKey).orEmpty()
        val sshKeyData = SshKeyDataCodec.encode(
            SshKeyData(
                algorithm = inferSshAlgorithm(publicKey),
                publicKeyOpenSsh = publicKey,
                privateKeyOpenSsh = privateKey,
                fingerprintSha256 = fingerprint,
                format = SshKeyData.FORMAT_OPENSSH
            )
        )
        if (sshKeyData.isBlank()) {
            return CipherSyncResult.Skipped("Empty SSH key data")
        }

        if (existing == null) {
            passwordEntryDao.insert(
                PasswordEntry(
                    title = name,
                    website = "",
                    username = "",
                    password = "",
                    notes = notes,
                    sshKeyData = sshKeyData,
                    loginType = LOGIN_TYPE_SSH_KEY,
                    isFavorite = cipher.favorite == true,
                    createdAt = Date(),
                    updatedAt = Date(),
                    bitwardenVaultId = vault.id,
                    bitwardenCipherId = cipher.id,
                    bitwardenFolderId = cipher.folderId,
                    bitwardenRevisionDate = cipher.revisionDate,
                    bitwardenCipherType = 5,
                    bitwardenLocalModified = false,
                    isDeleted = isServerDeleted,
                    deletedAt = serverDeletedAt,
                    isArchived = serverArchivedAt != null,
                    archivedAt = serverArchivedAt
                )
            )
            return CipherSyncResult.Added
        }

        if (existing.bitwardenLocalModified) {
            if (existing.bitwardenRevisionDate != cipher.revisionDate) {
                return CipherSyncResult.Conflict
            }
            return CipherSyncResult.Skipped("Local changes pending upload")
        }

        passwordEntryDao.update(
            existing.copy(
                title = name,
                website = "",
                username = "",
                password = "",
                notes = notes,
                sshKeyData = sshKeyData,
                loginType = LOGIN_TYPE_SSH_KEY,
                isFavorite = cipher.favorite == true,
                isDeleted = isServerDeleted,
                deletedAt = serverDeletedAt,
                isArchived = serverArchivedAt != null,
                archivedAt = serverArchivedAt,
                updatedAt = Date(),
                bitwardenVaultId = vault.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenCipherType = 5,
                bitwardenLocalModified = false
            )
        )
        return CipherSyncResult.Updated
    }

    private fun inferSshAlgorithm(publicKey: String): String {
        return when {
            publicKey.startsWith("ssh-rsa") -> SshKeyData.ALGORITHM_RSA
            publicKey.startsWith("ssh-ed25519") -> SshKeyData.ALGORITHM_ED25519
            else -> ""
        }
    }

    private fun encodeBitwardenTotpForLocalStorage(totpPayload: String): String {
        if (totpPayload.isBlank()) return ""
        return securityManager.encryptDataLegacyCompat(totpPayload)
    }

    private fun encodeSecureItemDataForLocalStorage(itemData: String): String {
        if (itemData.isBlank()) return ""
        return securityManager.encryptDataLegacyCompat(itemData)
    }

    /**
     * Bitwarden 条目在离线浏览时只能依赖本地缓存。
     * 若当前 MDK 状态不稳定，优先降级到可立即读回的兼容密文，避免详情页显示空密码。
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
    
    /**
     * 同步独立 TOTP
     */
    private suspend fun syncTotpCipher(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey,
        serverDeletedAt: Date?
    ): CipherSyncResult {
        val login = cipher.login ?: return CipherSyncResult.Skipped("No login data")
        val isServerDeleted = serverDeletedAt != null
        val existing = secureItemDao.getByBitwardenCipherIdInVault(vault.id, cipher.id)

        if (existing != null && !isServerDeleted) {
            resolveLocalDirtySecureItem(existing, vault, cipher)?.let { return it }
            if (shouldSkipSecureItemRemoteSync(existing, cipher, serverDeletedAt)) {
                return CipherSyncResult.Skipped(SKIP_REMOTE_UNCHANGED)
            }
        }
        
        // 解密 TOTP 密钥
        val decryptedTotp = decryptString(login.totp, symmetricKey) ?: ""
        if (decryptedTotp.isBlank()) {
            return CipherSyncResult.Skipped("No TOTP secret")
        }
        
        val name = decryptString(cipher.name, symmetricKey) ?: "Authenticator"
        val notes = decryptString(cipher.notes, symmetricKey) ?: ""
        val account = decryptString(login.username, symmetricKey) ?: ""

        val resolvedTotpData = TotpDataResolver.fromAuthenticatorKey(
            rawKey = decryptedTotp,
            fallbackIssuer = name,
            fallbackAccountName = account
        ) ?: return CipherSyncResult.Skipped("Invalid TOTP payload")
        
        // 构建 TOTP 数据
        // 从已有条目解析图标字段，同步时保留用户手动设置的图标
        val existingIconFields = existing?.itemData?.let {
            TotpDataResolver.parseStoredItemData(
                itemData = it,
                decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
            )
        }
        val totpData = resolvedTotpData.copy(
            issuer = resolvedTotpData.issuer.ifBlank { name },
            accountName = resolvedTotpData.accountName.ifBlank { account },
            customIconType = existingIconFields?.customIconType ?: "NONE",
            customIconValue = existingIconFields?.customIconValue,
            customIconUpdatedAt = existingIconFields?.customIconUpdatedAt ?: 0L
        )
        val itemData = securityManager.encryptDataLegacyCompat(json.encodeToString(totpData))
        
        if (existing == null) {
            val newItem = SecureItem(
                itemType = ItemType.TOTP,
                title = name,
                notes = notes,
                isFavorite = cipher.favorite == true,
                createdAt = Date(),
                updatedAt = Date(),
                itemData = itemData,
                bitwardenVaultId = vault.id,
                bitwardenCipherId = cipher.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                syncStatus = "SYNCED",
                isDeleted = isServerDeleted,
                deletedAt = serverDeletedAt
            )
            secureItemDao.insert(newItem)
            return CipherSyncResult.Added
        } else {
            if (isServerDeleted) {
                secureItemDao.update(
                    existing.copy(
                        title = name,
                        notes = notes,
                        itemData = itemData,
                        isFavorite = cipher.favorite == true,
                        isDeleted = true,
                        deletedAt = serverDeletedAt,
                        updatedAt = Date(),
                        bitwardenVaultId = vault.id,
                        bitwardenFolderId = cipher.folderId,
                        bitwardenRevisionDate = cipher.revisionDate,
                        bitwardenLocalModified = false,
                        syncStatus = "SYNCED"
                    )
                )
                return CipherSyncResult.Updated
            }
            val updated = existing.copy(
                title = name,
                notes = notes,
                itemData = itemData,
                isFavorite = cipher.favorite == true,
                isDeleted = false,
                deletedAt = null,
                updatedAt = Date(),
                bitwardenVaultId = vault.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = false,
                syncStatus = "SYNCED"
            )
            secureItemDao.update(updated)
            return CipherSyncResult.Updated
        }
    }
    
    /**
     * 同步 SecureNote (Type 2) -> SecureItem(NOTE)
     */
    private suspend fun syncSecureNoteCipher(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey,
        serverDeletedAt: Date?
    ): CipherSyncResult {
        val isServerDeleted = serverDeletedAt != null
        val hasPendingDelete = pendingOpDao.hasActiveDeleteByCipher(vault.id, cipher.id)
        if (hasPendingDelete && !isServerDeleted) {
            return CipherSyncResult.Skipped("Pending local delete")
        }
        val existing = secureItemDao.getByBitwardenCipherIdInVault(vault.id, cipher.id)
        if (existing != null && !isServerDeleted) {
            resolveLocalDirtySecureItem(existing, vault, cipher)?.let { return it }
            if (shouldSkipSecureItemRemoteSync(existing, cipher, serverDeletedAt)) {
                return CipherSyncResult.Skipped(SKIP_REMOTE_UNCHANGED)
            }
        }

        val name = decryptString(cipher.name, symmetricKey) ?: "Note"
        val notes = decryptString(cipher.notes, symmetricKey) ?: ""
        
        // 构建笔记数据
        val noteData = NoteData(content = notes)
        val itemData = json.encodeToString(noteData)
        
        if (existing == null) {
            val newItem = SecureItem(
                itemType = ItemType.NOTE,
                title = name,
                notes = notes,
                isFavorite = cipher.favorite == true,
                createdAt = Date(),
                updatedAt = Date(),
                itemData = itemData,
                bitwardenVaultId = vault.id,
                bitwardenCipherId = cipher.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                syncStatus = "SYNCED",
                isDeleted = isServerDeleted,
                deletedAt = serverDeletedAt
            )
            secureItemDao.insert(newItem)
            return CipherSyncResult.Added
        } else {
            if (isServerDeleted) {
                secureItemDao.update(
                    existing.copy(
                        title = name,
                        notes = notes,
                        itemData = itemData,
                        isFavorite = cipher.favorite == true,
                        isDeleted = true,
                        deletedAt = serverDeletedAt,
                        updatedAt = Date(),
                        bitwardenVaultId = vault.id,
                        bitwardenFolderId = cipher.folderId,
                        bitwardenRevisionDate = cipher.revisionDate,
                        bitwardenLocalModified = false,
                        syncStatus = "SYNCED"
                    )
                )
                return CipherSyncResult.Updated
            }
            val updated = existing.copy(
                title = name,
                notes = notes,
                itemData = itemData,
                isFavorite = cipher.favorite == true,
                isDeleted = false,
                deletedAt = null,
                updatedAt = Date(),
                bitwardenVaultId = vault.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = false,
                syncStatus = "SYNCED"
            )
            secureItemDao.update(updated)
            return CipherSyncResult.Updated
        }
    }
    
    /**
     * 同步 Card (Type 3) -> SecureItem(BANK_CARD)
     */
    private suspend fun syncCardCipher(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey,
        serverDeletedAt: Date?
    ): CipherSyncResult {
        val card = cipher.card ?: return CipherSyncResult.Skipped("No card data")
        val isServerDeleted = serverDeletedAt != null
        
        val name = decryptString(cipher.name, symmetricKey) ?: "Card"
        val notes = decryptString(cipher.notes, symmetricKey) ?: ""
        
        // 解密卡片字段
        val cardNumber = decryptString(card.number, symmetricKey) ?: ""
        val cardHolder = decryptString(card.cardholderName, symmetricKey) ?: ""
        val expMonth = decryptString(card.expMonth, symmetricKey) ?: ""
        val expYear = decryptString(card.expYear, symmetricKey) ?: ""
        val cvv = decryptString(card.code, symmetricKey) ?: ""
        val brand = decryptString(card.brand, symmetricKey) ?: ""
        val decryptedFields = decryptCustomFields(cipher.fields, symmetricKey)
        val fieldMap = decryptedFields.associate { it.name to it.value }
        val hasLegacyCardFields = !isServerDeleted &&
            decryptedFields.any { isLegacyCardCleanupFieldName(it.name) }
        
        val existing = secureItemDao.getByBitwardenCipherIdInVault(vault.id, cipher.id)
        if (existing != null && !isServerDeleted) {
            resolveLocalDirtySecureItem(existing, vault, cipher)?.let { return it }
            if (!hasLegacyCardFields && shouldSkipSecureItemRemoteSync(existing, cipher, serverDeletedAt)) {
                return CipherSyncResult.Skipped(SKIP_REMOTE_UNCHANGED)
            }
        }
        val existingCardData = existing?.let {
            CardWalletDataCodec.parseBankCardData(it.itemData) { encrypted ->
                securityManager.decryptData(encrypted)
            }
        }
        val remoteBillingAddress = fieldMap.valueByNames("Billing Address", "bastion_billing_address")
        
        val cardData = BankCardData(
            cardNumber = cardNumber,
            cardholderName = cardHolder,
            expiryMonth = expMonth,
            expiryYear = expYear,
            cvv = cvv,
            bankName = fieldMap.valueByNames("Bank Name", "bastion_bank_name"),
            cardType = parseCardType(fieldMap.valueByNames("Card Type", "bastion_card_type")),
            billingAddress = resolveSyncedCardBillingAddress(existingCardData, remoteBillingAddress),
            brand = brand,
            nickname = fieldMap.valueByNames("Nickname", "bastion_nickname"),
            validFromMonth = fieldMap.valueByNames("Valid From Month", "bastion_valid_from_month"),
            validFromYear = fieldMap.valueByNames("Valid From Year", "bastion_valid_from_year"),
            pin = fieldMap.valueByNames("PIN", "bastion_pin"),
            iban = fieldMap.valueByNames("IBAN", "bastion_iban"),
            swiftBic = fieldMap.valueByNames("SWIFT/BIC", "bastion_swift_bic"),
            routingNumber = fieldMap.valueByNames("Routing Number", "bastion_routing_number"),
            accountNumber = fieldMap.valueByNames("Account Number", "bastion_account_number"),
            branchCode = fieldMap.valueByNames("Branch Code", "bastion_branch_code"),
            currency = fieldMap.valueByNames("Currency", "bastion_currency"),
            customerServicePhone = fieldMap.valueByNames("Customer Service Phone", "bastion_customer_service_phone"),
            customFields = decryptedFields.toCardCustomFields()
        )
        val itemData = encodeSecureItemDataForLocalStorage(
            CardWalletDataCodec.encodeBankCardData(cardData)
        )
        
        if (existing == null) {
            val newItem = SecureItem(
                itemType = ItemType.BANK_CARD,
                title = name,
                notes = notes,
                isFavorite = cipher.favorite == true,
                createdAt = Date(),
                updatedAt = Date(),
                itemData = itemData,
                bitwardenVaultId = vault.id,
                bitwardenCipherId = cipher.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = hasLegacyCardFields,
                syncStatus = if (hasLegacyCardFields) "PENDING" else "SYNCED",
                isDeleted = isServerDeleted,
                deletedAt = serverDeletedAt
            )
            secureItemDao.insert(newItem)
            return CipherSyncResult.Added
        } else {
            if (isServerDeleted) {
                secureItemDao.update(
                    existing.copy(
                        title = name,
                        notes = notes,
                        itemData = itemData,
                        isFavorite = cipher.favorite == true,
                        isDeleted = true,
                        deletedAt = serverDeletedAt,
                        updatedAt = Date(),
                        bitwardenVaultId = vault.id,
                        bitwardenFolderId = cipher.folderId,
                        bitwardenRevisionDate = cipher.revisionDate,
                        bitwardenLocalModified = false,
                        syncStatus = "SYNCED"
                    )
                )
                return CipherSyncResult.Updated
            }
            val updated = existing.copy(
                title = name,
                notes = notes,
                itemData = itemData,
                isFavorite = cipher.favorite == true,
                isDeleted = false,
                deletedAt = null,
                updatedAt = Date(),
                bitwardenVaultId = vault.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = hasLegacyCardFields,
                syncStatus = if (hasLegacyCardFields) "PENDING" else "SYNCED"
            )
            secureItemDao.update(updated)
            return CipherSyncResult.Updated
        }
    }
    
    /**
     * 同步 Identity (Type 4) -> SecureItem(DOCUMENT)
     */
    private suspend fun syncIdentityCipher(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey,
        serverDeletedAt: Date?
    ): CipherSyncResult {
        val identity = cipher.identity ?: return CipherSyncResult.Skipped("No identity data")
        val isServerDeleted = serverDeletedAt != null
        
        val name = decryptString(cipher.name, symmetricKey) ?: "Identity"
        val notes = decryptString(cipher.notes, symmetricKey) ?: ""
        val decryptedFields = decryptCustomFields(cipher.fields, symmetricKey)
        val customFieldMap = decryptedFields.associate { it.name to it.value }
        val fieldDocumentType = parseDocumentType(customFieldMap["bastion_document_type"])
        val resolvedDocumentType = fieldDocumentType ?: guessDocumentType(identity)
        
        // 解密身份字段
        val firstName = decryptString(identity.firstName, symmetricKey) ?: ""
        val middleName = decryptString(identity.middleName, symmetricKey) ?: ""
        val lastName = decryptString(identity.lastName, symmetricKey) ?: ""
        val fullName = listOf(firstName, middleName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val passportNumber = decryptString(identity.passportNumber, symmetricKey).orEmpty()
        val licenseNumber = decryptString(identity.licenseNumber, symmetricKey).orEmpty()
        val ssn = decryptString(identity.ssn, symmetricKey).orEmpty()
        val idNumber = when (resolvedDocumentType) {
            DocumentType.PASSPORT -> passportNumber.ifBlank { ssn.ifBlank { licenseNumber } }
            DocumentType.DRIVER_LICENSE -> licenseNumber.ifBlank { ssn.ifBlank { passportNumber } }
            DocumentType.ID_CARD, DocumentType.SOCIAL_SECURITY, DocumentType.OTHER -> {
                ssn.ifBlank { licenseNumber.ifBlank { passportNumber } }
            }
        }
        
        val existing = secureItemDao.getByBitwardenCipherIdInVault(vault.id, cipher.id)
        if (existing != null && !isServerDeleted) {
            resolveLocalDirtySecureItem(existing, vault, cipher)?.let { return it }
            if (shouldSkipSecureItemRemoteSync(existing, cipher, serverDeletedAt)) {
                return CipherSyncResult.Skipped(SKIP_REMOTE_UNCHANGED)
            }
        }
        
        val docData = DocumentData(
            documentType = resolvedDocumentType,
            documentNumber = idNumber,
            fullName = fullName,
            issuedDate = customFieldMap["bastion_issue_date"].orEmpty(),
            expiryDate = customFieldMap["bastion_expiry_date"].orEmpty(),
            issuedBy = customFieldMap["bastion_issued_by"].orEmpty(),
            nationality = customFieldMap["bastion_nationality"].orEmpty(),
            additionalInfo = customFieldMap["bastion_additional_info"].orEmpty(),
            title = decryptString(identity.title, symmetricKey) ?: "",
            firstName = firstName,
            middleName = middleName,
            lastName = lastName,
            address1 = decryptString(identity.address1, symmetricKey) ?: "",
            address2 = decryptString(identity.address2, symmetricKey) ?: "",
            address3 = decryptString(identity.address3, symmetricKey) ?: "",
            city = decryptString(identity.city, symmetricKey) ?: "",
            stateProvince = decryptString(identity.state, symmetricKey) ?: "",
            postalCode = decryptString(identity.postalCode, symmetricKey) ?: "",
            country = decryptString(identity.country, symmetricKey) ?: "",
            company = decryptString(identity.company, symmetricKey) ?: "",
            email = decryptString(identity.email, symmetricKey) ?: "",
            phone = decryptString(identity.phone, symmetricKey) ?: "",
            ssn = ssn,
            username = decryptString(identity.username, symmetricKey) ?: "",
            passportNumber = passportNumber,
            licenseNumber = licenseNumber,
            customFields = decryptedFields.toDocumentCustomFields()
        )
        val itemData = encodeSecureItemDataForLocalStorage(
            CardWalletDataCodec.encodeDocumentData(docData)
        )
        
        if (existing == null) {
            val newItem = SecureItem(
                itemType = ItemType.DOCUMENT,
                title = name,
                notes = notes,
                isFavorite = cipher.favorite == true,
                createdAt = Date(),
                updatedAt = Date(),
                itemData = itemData,
                bitwardenVaultId = vault.id,
                bitwardenCipherId = cipher.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                syncStatus = "SYNCED",
                isDeleted = isServerDeleted,
                deletedAt = serverDeletedAt
            )
            secureItemDao.insert(newItem)
            return CipherSyncResult.Added
        } else {
            if (isServerDeleted) {
                secureItemDao.update(
                    existing.copy(
                        title = name,
                        notes = notes,
                        itemData = itemData,
                        isFavorite = cipher.favorite == true,
                        isDeleted = true,
                        deletedAt = serverDeletedAt,
                        updatedAt = Date(),
                        bitwardenVaultId = vault.id,
                        bitwardenFolderId = cipher.folderId,
                        bitwardenRevisionDate = cipher.revisionDate,
                        bitwardenLocalModified = false,
                        syncStatus = "SYNCED"
                    )
                )
                return CipherSyncResult.Updated
            }
            val updated = existing.copy(
                title = name,
                notes = notes,
                itemData = itemData,
                isFavorite = cipher.favorite == true,
                isDeleted = false,
                deletedAt = null,
                updatedAt = Date(),
                bitwardenVaultId = vault.id,
                bitwardenFolderId = cipher.folderId,
                bitwardenRevisionDate = cipher.revisionDate,
                bitwardenLocalModified = false,
                syncStatus = "SYNCED"
            )
            secureItemDao.update(updated)
            return CipherSyncResult.Updated
        }
    }
    
    /**
     * 同步 Passkey 元数据
     */
    private suspend fun syncPasskeyCipher(
        vault: BitwardenVault,
        cipher: CipherApiResponse,
        symmetricKey: SymmetricCryptoKey,
        remoteUnchanged: Boolean
    ): CipherSyncResult {
        val hasPendingDelete = pendingOpDao.hasActiveDeleteByCipher(vault.id, cipher.id)
        if (hasPendingDelete) {
            return CipherSyncResult.Skipped("Pending local delete")
        }
        if (remoteUnchanged) {
            val existingPasskeys = passkeyDao.getAllByBitwardenCipherIdInVault(vault.id, cipher.id)
            if (existingPasskeys.isNotEmpty() && existingPasskeys.all { it.syncStatus == "SYNCED" }) {
                return CipherSyncResult.Skipped(SKIP_REMOTE_UNCHANGED)
            }
        }

        val login = cipher.login
        val name = decryptString(cipher.name, symmetricKey) ?: "Passkey"
        val notes = extractPasskeyUserNotes(decryptString(cipher.notes, symmetricKey))
        val fallbackUserName = decryptString(login?.username, symmetricKey) ?: ""

        // 回填本地绑定关系：密码 cipher 带 fido2Credentials 时，passkey 关联到本地密码条目
        // （syncLoginCipher 先 syncPasswordCipher 再 syncPasskeyCipher，密码条目此时已存在）
        val boundPasswordEntry = passwordEntryDao.getByBitwardenCipherIdInVault(vault.id, cipher.id)
        val boundPasswordId = boundPasswordEntry?.id

        val fallbackRpId = login?.uris
            ?.asSequence()
            ?.mapNotNull { uri ->
                val uriStr = decryptString(uri.uri, symmetricKey) ?: return@mapNotNull null
                extractRpIdFromUri(uriStr).takeIf { it.isNotBlank() }
            }
            ?.firstOrNull()
            .orEmpty()

        val decodedCredentials = decodeFido2Credentials(login?.fido2Credentials, symmetricKey)

        if (decodedCredentials.isEmpty()) {
            // 兼容历史 Bastion marker-only passkey：至少落一个引用记录
            val referenceId = buildReferenceCredentialId(cipher.id, 0)
            val existing = passkeyDao.getByBitwardenCipherIdInVault(vault.id, cipher.id)
                ?: passkeyDao.getByBitwardenCipherCredentialIdInVault(vault.id, cipher.id, referenceId)

            val rpId = fallbackRpId
            val rpName = name.removeSuffix(" [Passkey]").ifBlank { rpId }
            val userName = fallbackUserName
            val now = System.currentTimeMillis()

            if (existing == null) {
                passkeyDao.insert(
                    PasskeyEntry(
                        credentialId = referenceId,
                        rpId = rpId,
                        rpName = rpName,
                        userId = "",
                        userName = userName,
                        userDisplayName = userName,
                        publicKeyAlgorithm = PasskeyEntry.ALGORITHM_ES256,
                        publicKey = "",
                        privateKeyAlias = "",
                        createdAt = now,
                        lastUsedAt = now,
                        useCount = 0,
                        iconUrl = null,
                        isDiscoverable = true,
                        isUserVerificationRequired = true,
                        transports = PasskeyEntry.TRANSPORT_INTERNAL,
                        aaguid = "",
                        signCount = 0,
                        isBackedUp = false,
                        notes = notes,
                        boundPasswordId = boundPasswordId,
                        bitwardenVaultId = vault.id,
                        bitwardenCipherId = cipher.id,
                        syncStatus = "REFERENCE",
                        passkeyMode = PasskeyEntry.MODE_BW_COMPAT
                    )
                )
                android.util.Log.i(TAG, "Created reference-only passkey for cipher ${cipher.id}")
                return CipherSyncResult.Added
            }

            passkeyDao.update(
                existing.copy(
                    rpId = rpId.ifBlank { existing.rpId },
                    rpName = rpName.ifBlank { existing.rpName },
                    userName = userName.ifBlank { existing.userName },
                    userDisplayName = userName.ifBlank { existing.userDisplayName },
                    notes = notes.ifBlank { existing.notes },
                    bitwardenVaultId = vault.id,
                    bitwardenCipherId = cipher.id,
                    syncStatus = "REFERENCE",
                    passkeyMode = PasskeyEntry.MODE_BW_COMPAT
                )
            )
            return CipherSyncResult.Updated
        }

        var added = 0
        var updated = 0
        val keepCredentialIds = mutableSetOf<String>()
        val now = System.currentTimeMillis()

        decodedCredentials.forEachIndexed { index, decoded ->
            val resolvedCredentialId = normalizeCredentialId(decoded.credentialId)
                ?: buildReferenceCredentialId(cipher.id, index)
            keepCredentialIds += resolvedCredentialId

            val rpId = decoded.rpId.ifBlank { fallbackRpId }
            val rpName = decoded.rpName
                .ifBlank { name.removeSuffix(" [Passkey]") }
                .ifBlank { rpId }
            val userName = decoded.userName.ifBlank { fallbackUserName }
            val userDisplayName = decoded.userDisplayName.ifBlank { userName }
            val storedPrivateKey = PasskeyPrivateKeyStore.protectForStorage(
                context = context,
                credentialId = resolvedCredentialId,
                rpId = rpId,
                userId = decoded.userHandle,
                keyMaterial = decoded.keyValue
            )

            val existing = passkeyDao.getByBitwardenCipherCredentialIdInVault(
                vaultId = vault.id,
                cipherId = cipher.id,
                credentialId = resolvedCredentialId
            )
            if (existing == null) {
                val syncStatus = if (decoded.keyValue.isBlank()) "REFERENCE" else "SYNCED"
                passkeyDao.insert(
                    PasskeyEntry(
                        credentialId = resolvedCredentialId,
                        rpId = rpId,
                        rpName = rpName,
                        userId = decoded.userHandle,
                        userName = userName,
                        userDisplayName = userDisplayName,
                        publicKeyAlgorithm = decoded.publicKeyAlgorithm,
                        publicKey = "",
                        privateKeyAlias = storedPrivateKey,
                        createdAt = decoded.creationDateMillis ?: now,
                        lastUsedAt = now,
                        useCount = 0,
                        iconUrl = null,
                        isDiscoverable = decoded.discoverable,
                        isUserVerificationRequired = true,
                        transports = PasskeyEntry.TRANSPORT_INTERNAL,
                        aaguid = "",
                        signCount = decoded.counter,
                        isBackedUp = false,
                        notes = notes,
                        boundPasswordId = boundPasswordId,
                        bitwardenVaultId = vault.id,
                        bitwardenCipherId = cipher.id,
                        syncStatus = syncStatus,
                        passkeyMode = PasskeyEntry.MODE_BW_COMPAT
                    )
                )
                added++
            } else {
                val mergedPrivateKey = storedPrivateKey.ifBlank { existing.privateKeyAlias }
                val syncStatus = if (mergedPrivateKey.isBlank()) "REFERENCE" else "SYNCED"

                passkeyDao.update(
                    existing.copy(
                        rpId = rpId.ifBlank { existing.rpId },
                        rpName = rpName.ifBlank { existing.rpName },
                        userId = decoded.userHandle.ifBlank { existing.userId },
                        userName = userName.ifBlank { existing.userName },
                        userDisplayName = userDisplayName.ifBlank { existing.userDisplayName },
                        publicKeyAlgorithm = decoded.publicKeyAlgorithm,
                        privateKeyAlias = mergedPrivateKey,
                        isDiscoverable = decoded.discoverable,
                        signCount = maxOf(existing.signCount, decoded.counter),
                        notes = notes.ifBlank { existing.notes },
                        boundPasswordId = boundPasswordId ?: existing.boundPasswordId,
                        bitwardenVaultId = vault.id,
                        bitwardenCipherId = cipher.id,
                        syncStatus = syncStatus,
                        passkeyMode = PasskeyEntry.MODE_BW_COMPAT
                    )
                )
                updated++
            }
        }

        val staleEntries = passkeyDao.getAllByBitwardenCipherIdInVault(vault.id, cipher.id)
            .filterNot { keepCredentialIds.contains(it.credentialId) }
        staleEntries.forEach { passkeyDao.delete(it) }

        return when {
            added > 0 -> CipherSyncResult.Added
            updated > 0 || staleEntries.isNotEmpty() -> CipherSyncResult.Updated
            else -> CipherSyncResult.Skipped("No passkey changes")
        }
    }
    
    // ========== 辅助方法 ==========

    private typealias DecodedPasskeyCredential = Fido2CredentialCodec.DecodedFido2Credential

    /**
     * 重建密码条目的 passkey_bindings 列（以本地 passkeys 表的 boundPasswordId 关联为准）。
     *
     * passkey 与密码的绑定关系已由官方 login.fido2Credentials 承载（syncPasskeyCipher 已回填
     * boundPasswordId），本地 passkey_bindings 列不再依赖服务器 bastion_passkey_bindings 自定义字段，
     * 改为从本地 passkeys 表反查生成，保证 UI 展示/安全分析/备份恢复的绑定信息一致。
     */
    private suspend fun rebuildPasswordPasskeyBindings(
        vault: BitwardenVault,
        cipher: CipherApiResponse
    ) {
        val passwordEntry = resolveCanonicalPasswordEntry(vault.id, cipher.id) ?: return
        val boundPasskeys = passkeyDao.getByBoundPasswordIds(listOf(passwordEntry.id))
        val bindings = boundPasskeys.map { passkey ->
            PasskeyBinding(
                credentialId = passkey.credentialId,
                rpId = passkey.rpId,
                rpName = passkey.rpName,
                userName = passkey.userName,
                userDisplayName = passkey.userDisplayName
            )
        }
        val encoded = PasskeyBindingCodec.encodeList(bindings)
        if (encoded != passwordEntry.passkeyBindings) {
            passwordEntryDao.updatePasskeyBindings(passwordEntry.id, encoded)
        }
    }

    private fun decodeFido2Credentials(
        credentials: List<CipherLoginFido2CredentialApiData>?,
        key: SymmetricCryptoKey
    ): List<DecodedPasskeyCredential> {
        return Fido2CredentialCodec.decodeFido2Credentials(credentials, key)
    }

    private fun decryptOrPlain(value: String?, key: SymmetricCryptoKey): String? {
        if (value.isNullOrBlank()) return null
        val decrypted = decryptString(value, key)
        if (decrypted != null) return decrypted
        if (looksLikeCipherString(value)) return null
        return value
    }

    private fun looksLikeCipherString(value: String): Boolean {
        val dotIndex = value.indexOf('.')
        if (dotIndex <= 0) return false
        return value.substring(0, dotIndex).all(Char::isDigit)
    }

    private fun normalizeCredentialId(credentialId: String): String? {
        if (credentialId.isBlank()) return null
        return PasskeyCredentialIdCodec.normalize(credentialId)
    }

    private fun extractPasskeyUserNotes(notes: String?): String {
        if (notes.isNullOrBlank()) return ""
        return notes.substringBefore("---").trim()
    }

    private fun parseBooleanText(value: String?): Boolean {
        return when (value?.trim()?.lowercase()) {
            "false", "0", "no" -> false
            else -> true
        }
    }

    private fun parseRevisionMillis(revisionDate: String?): Long? {
        if (revisionDate.isNullOrBlank()) return null
        return runCatchingObserved { Instant.parse(revisionDate).toEpochMilli() }.getOrNull()
    }

    private fun parseCreationDateMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatchingObserved { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun parseAlgorithm(value: String?): Int {
        val parsed = value?.trim()?.toIntOrNull()
        if (parsed != null) return parsed
        return when (value?.trim()?.lowercase()) {
            "es256", "ecdsa" -> PasskeyEntry.ALGORITHM_ES256
            "rs256", "rsa" -> PasskeyEntry.ALGORITHM_RS256
            "ps256" -> PasskeyEntry.ALGORITHM_PS256
            "eddsa", "ed25519" -> PasskeyEntry.ALGORITHM_EDDSA
            else -> PasskeyEntry.ALGORITHM_ES256
        }
    }

    private fun buildReferenceCredentialId(cipherId: String, index: Int): String {
        return "bw_ref_${cipherId}_$index"
    }
    
    private fun decryptString(encrypted: String?, key: SymmetricCryptoKey): String? {
        if (encrypted.isNullOrBlank()) return null
        if (!looksLikeCipherString(encrypted)) return null
        return try {
            BitwardenCrypto.decryptToString(encrypted, key)
        } catch (e: Throwable) {
            android.util.Log.w(
                TAG,
                "decryptString failed: len=${encrypted.length}, typeHint=${extractCipherTypeHint(encrypted)}, error=${e.javaClass.simpleName}"
            )
            null
        }
    }

    private fun extractCipherTypeHint(cipherString: String): String {
        val dotIndex = cipherString.indexOf('.')
        if (dotIndex <= 0) return "none"
        return cipherString.substring(0, dotIndex).take(8)
    }
    
    private fun extractRpIdFromUri(uri: String): String {
        return try {
            if (uri.startsWith("https://")) {
                java.net.URI(uri).host ?: ""
            } else {
                uri
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun guessDocumentType(identity: CipherIdentityApiData): DocumentType {
        return when {
            !identity.passportNumber.isNullOrBlank() -> DocumentType.PASSPORT
            !identity.licenseNumber.isNullOrBlank() -> DocumentType.DRIVER_LICENSE
            !identity.ssn.isNullOrBlank() -> DocumentType.ID_CARD
            else -> DocumentType.OTHER
        }
    }

    private fun parseCardType(raw: String?): CardType {
        return when (raw?.trim()?.uppercase()) {
            "DEBIT" -> CardType.DEBIT
            "PREPAID" -> CardType.PREPAID
            else -> CardType.CREDIT
        }
    }

    private fun Map<String, String>.valueByNames(vararg names: String): String {
        names.forEach { name ->
            get(name)?.let { return it }
        }
        val normalized = entries.associate { normalizeFieldName(it.key) to it.value }
        names.forEach { name ->
            normalized[normalizeFieldName(name)]?.let { return it }
        }
        return ""
    }

    private fun resolveSyncedCardBillingAddress(
        existingCardData: BankCardData?,
        remoteBillingAddress: String
    ): String {
        if (remoteBillingAddress.isBlank()) return ""
        val existingRaw = existingCardData?.billingAddress.orEmpty()
        if (existingRaw.isBlank()) return remoteBillingAddress
        val existingDisplay = formatBankCardBillingAddressForExternal(existingRaw)
        return if (existingDisplay.isNotBlank() && existingDisplay == remoteBillingAddress) {
            existingRaw
        } else {
            remoteBillingAddress
        }
    }

    private fun formatBankCardBillingAddressForExternal(raw: String): String {
        return CardWalletDataCodec.parseBillingAddress(raw)
            .formatForDisplay()
            .ifBlank { raw }
    }

    private fun isInternalOrStructuredCardFieldName(name: String): Boolean {
        val normalized = normalizeFieldName(name)
        return normalized in LEGACY_MONICA_FIELD_NAMES ||
            normalized in LEGACY_CARD_FIELD_NAMES ||
            normalized in READABLE_CARD_FIELD_NAMES
    }

    private fun isLegacyCardCleanupFieldName(name: String): Boolean {
        val normalized = normalizeFieldName(name)
        return normalized in LEGACY_MONICA_FIELD_NAMES ||
            normalized in LEGACY_CARD_FIELD_NAMES
    }

    private fun normalizeFieldName(name: String): String {
        return name.trim().lowercase()
    }

    private fun decryptCustomFields(
        fields: List<CipherFieldApiData>?,
        key: SymmetricCryptoKey
    ): List<DecryptedCustomField> {
        if (fields.isNullOrEmpty()) return emptyList()

        return fields.mapNotNull { field ->
            val name = decryptOrPlain(field.name, key).orEmpty().trim()
            if (name.isBlank()) return@mapNotNull null
            val value = decryptOrPlain(field.value, key).orEmpty()
            DecryptedCustomField(
                name = name,
                value = value,
                type = field.type
            )
        }
    }

    private fun decryptCustomFieldMap(
        fields: List<CipherFieldApiData>?,
        key: SymmetricCryptoKey
    ): Map<String, String> {
        return decryptCustomFields(fields, key).associate { it.name to it.value }
    }

    /**
     * Bitwarden 登录型 cipher 中，以下字段名会被 bastion 映射到 [PasswordEntry] 的
     * 专用列（appPackageName/email/phone/地址/ssh 等），不属于「用户自由自定义字段」。
     * 这些字段不应再写入 custom_fields 表，否则会与专用列产生重复。
     * 注意：所有 bastion 内部字段统一以 `bastion_` 前缀命名，故前缀命中即视为保留。
     */
    private val LOGIN_RESERVED_CUSTOM_FIELD_NAMES = setOf(
        "apppackagename", "appname", "email", "phone",
        "addressline", "address", "city", "state", "zipcode", "country"
    )

    private fun isLoginReservedCustomFieldName(name: String): Boolean {
        val n = name.trim()
        if (n.startsWith("bastion_", ignoreCase = true)) return true
        return n.lowercase() in LOGIN_RESERVED_CUSTOM_FIELD_NAMES
    }

    /**
     * 从解密后的 Bitwarden 字段中，提取「用户自由自定义字段」（排除 bastion 保留名），
     * 转换为本地 [CustomField] 列表，供写入 custom_fields 表。
     * 修复与 Monica e348623f3f 同类的「Bitwarden 自定义字段下载后不可见」问题。
     */
    private fun buildLocalCustomFields(fields: List<CipherFieldApiData>?, key: SymmetricCryptoKey): List<CustomField> {
        return decryptCustomFields(fields, key)
            .filter { !isLoginReservedCustomFieldName(it.name) }
            .mapIndexed { index, f ->
                CustomField(
                    id = 0,
                    entryId = 0,
                    title = f.name,
                    value = f.value,
                    isProtected = f.type == 1, // Bitwarden 字段类型: 0=Text, 1=Hidden, 2=Boolean
                    sortOrder = index
                )
            }
    }

    private fun parseLoginUris(
        uris: List<CipherUriApiData>?,
        key: SymmetricCryptoKey
    ): ParsedLoginUris {
        if (uris.isNullOrEmpty()) return ParsedLoginUris()

        val websites = linkedSetOf<String>()
        var appPackageName = ""
        uris.forEach { uriData ->
            val uri = decryptString(uriData.uri, key) ?: return@forEach
            when {
                uri.startsWith("androidapp://", ignoreCase = true) -> {
                    if (appPackageName.isBlank()) {
                        appPackageName = uri.removePrefix("androidapp://")
                    }
                }
                else -> websites += normalizeWebsite(uri)
            }
        }
        return ParsedLoginUris(
            website = websites.joinToString(", "),
            appPackageName = appPackageName
        )
    }

    private fun parseDocumentType(raw: String?): DocumentType? {
        val normalized = raw?.trim()?.uppercase() ?: return null
        return when (normalized) {
            "PASSPORT" -> DocumentType.PASSPORT
            "DRIVER_LICENSE", "DRIVERLICENCE", "DRIVING_LICENSE", "DRIVING_LICENCE" -> DocumentType.DRIVER_LICENSE
            "SOCIAL_SECURITY", "SSN" -> DocumentType.SOCIAL_SECURITY
            "ID_CARD", "IDENTITY_CARD", "IDENTITY" -> DocumentType.ID_CARD
            "OTHER" -> DocumentType.OTHER
            else -> null
        }
    }

    private fun parseBitwardenDeletedAt(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return runCatchingObserved {
            Date.from(Instant.parse(raw))
        }.getOrNull() ?: Date()
    }

    private fun parseBitwardenArchivedAt(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return runCatchingObserved {
            Date.from(Instant.parse(raw))
        }.getOrNull()
    }

    private data class DecryptedCustomField(
        val name: String,
        val value: String,
        val type: Int
    )

    private fun List<DecryptedCustomField>.toCardCustomFields(): List<SecureCustomField> {
        val reserved = LEGACY_MONICA_FIELD_NAMES + LEGACY_CARD_FIELD_NAMES + READABLE_CARD_FIELD_NAMES
        return filterNot { normalizeFieldName(it.name) in reserved }
            .map { it.toSecureCustomField() }
    }

    private fun List<DecryptedCustomField>.toDocumentCustomFields(): List<SecureCustomField> {
        val reserved = setOf(
            "bastion_document_type",
            "bastion_issue_date",
            "bastion_expiry_date",
            "bastion_issued_by",
            "bastion_nationality",
            "bastion_additional_info"
        )
        return filterNot { it.name in reserved }
            .map { it.toSecureCustomField() }
    }

    private fun DecryptedCustomField.toSecureCustomField(): SecureCustomField {
        return SecureCustomField(
            label = name,
            value = value,
        type = when (type) {
            1 -> SecureCustomFieldType.HIDDEN
            2 -> SecureCustomFieldType.BOOLEAN
            3 -> SecureCustomFieldType.LINKED
            else -> SecureCustomFieldType.TEXT
        }
        )
    }
}

// CipherSyncResult 定义在 BitwardenSyncService.kt 中
