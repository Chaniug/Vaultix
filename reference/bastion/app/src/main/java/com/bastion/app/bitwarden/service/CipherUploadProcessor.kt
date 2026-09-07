package com.bastion.app.bitwarden.service

import com.bastion.app.logging.runCatchingObserved
import android.util.Base64
import android.util.Log
import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.bastion.app.bitwarden.api.*
import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import com.bastion.app.bitwarden.mapper.*
import com.bastion.app.bitwarden.sync.SyncItemType
import com.bastion.app.data.*
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.data.model.BankCardData
import com.bastion.app.data.model.CardWalletDataCodec
import com.bastion.app.data.model.DocumentData
import com.bastion.app.data.model.DocumentType
import com.bastion.app.data.model.NoteData
import com.bastion.app.data.model.OtpType
import com.bastion.app.data.model.SecureCustomField
import com.bastion.app.data.model.SecureCustomFieldType
import com.bastion.app.data.model.TotpData
import com.bastion.app.data.model.formatForDisplay
import com.bastion.app.data.OperationLogItemType
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.passkey.PasskeyCredentialIdCodec
import com.bastion.app.passkey.PasskeyPrivateKeyStore
import com.bastion.app.security.SecurityManager
import com.bastion.app.utils.FieldChange
import com.bastion.app.utils.OperationLogger
import com.bastion.app.util.TotpDataResolver
import java.util.Date
import java.security.KeyStore
import java.security.MessageDigest

/**
 * 多类型 Cipher 上传处理器
 * 
 * 负责将本地创建的各类条目上传到 Bitwarden 服务器
 * 支持所有类型：Password, TOTP, Card, Note, Document, Passkey
 */
class CipherUploadProcessor(
    private val context: Context,
    private val apiManager: BitwardenApiManager = BitwardenApiManager()
) {
    companion object {
        private const val TAG = "CipherUploadProcessor"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private val CIPHER_STRING_PATTERN =
            Regex("^[0-9]+\\.[A-Za-z0-9+/_=-]+\\|[A-Za-z0-9+/_=-]+(?:\\|[A-Za-z0-9+/_=-]+)?$")
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
    
    private val database = PasswordDatabase.getDatabase(context)
    private val passwordEntryDao = database.passwordEntryDao()
    private val secureItemDao = database.secureItemDao()
    private val passkeyDao = database.passkeyDao()
    private val securityManager = SecurityManager(context.applicationContext)
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    /** 上传被跳过时返回的占位 cipherId：绑定型 TOTP 无需自立 cipher。 */
    private val passwordBoundTotpSkipped = "password-bound-totp-skipped"

    init {
        runCatchingObserved { OperationLogger.init(context.applicationContext) }
    }
    
    /**
     * 上传单个 SecureItem 到 Bitwarden
     */
    suspend fun uploadSecureItem(
        vault: BitwardenVault,
        item: SecureItem,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            // 【单一归属】绑定型 TOTP 不参与 Bitwarden 同步：它的验证码已随所属密码条目的
            // authenticatorKey → cipher.login.totp 上传。此处若再传一次，会生成一个独立的
            // otpauth:// cipher，该 cipher 同步回来又被解析成一条多余的密码条目（本地"副本"）。
            if (isPasswordBoundTotp(item)) {
                Log.w(
                    "CipherUploadProcessor",
                    "skip upload: password-bound TOTP is carried by its password entry id=${item.id}"
                )
                return UploadItemResult.Success(passwordBoundTotpSkipped)
            }
            val request = when (item.itemType) {
                ItemType.TOTP -> createTotpCipherRequest(item, symmetricKey)
                ItemType.BANK_CARD -> createCardCipherRequest(item, symmetricKey)
                ItemType.NOTE -> createSecureNoteCipherRequest(item, symmetricKey)
                ItemType.DOCUMENT -> createIdentityCipherRequest(item, symmetricKey)
                else -> return UploadItemResult.Error("Unsupported item type: ${item.itemType}")
            }
            val requestPayload = runCatchingObserved { json.encodeToString(request) }.getOrNull()
            
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.createCipher(
                authorization = "Bearer $accessToken",
                cipher = request
            )
            
            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_secure_item_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatchingObserved { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "create cipher failed: ${response.code()}"
                )
                return UploadItemResult.Error("Create cipher failed: ${response.code()}")
            }
            
            val createdCipher = response.body()
            if (createdCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_secure_item_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "create cipher returned empty body"
                )
                return UploadItemResult.Error("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatchingObserved { json.encodeToString(createdCipher) }.getOrNull(),
                success = true
            )
            
            // 更新本地条目
            val updatedItem = item.copy(
                bitwardenCipherId = createdCipher.id,
                bitwardenRevisionDate = createdCipher.revisionDate,
                bitwardenLocalModified = false,
                syncStatus = "SYNCED",
                updatedAt = Date()
            )
            secureItemDao.update(updatedItem)
            
            android.util.Log.d(TAG, "Uploaded SecureItem ${item.id} as cipher ${createdCipher.id}")
            UploadItemResult.Success(createdCipher.id)
        } catch (e: Exception) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Upload SecureItem failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 更新单个 SecureItem 到 Bitwarden
     */
    suspend fun updateSecureItem(
        vault: BitwardenVault,
        item: SecureItem,
        cipherId: String,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            // 【单一归属】同 uploadSecureItem：绑定型 TOTP 的验证码随密码条目同步，不自立 cipher。
            if (isPasswordBoundTotp(item)) {
                Log.w(
                    "CipherUploadProcessor",
                    "skip update: password-bound TOTP is carried by its password entry id=${item.id}"
                )
                return UploadItemResult.Success(passwordBoundTotpSkipped)
            }
            val request = when (item.itemType) {
                ItemType.TOTP -> createTotpCipherRequest(item, symmetricKey)
                ItemType.BANK_CARD -> createCardCipherRequest(item, symmetricKey)
                ItemType.NOTE -> createSecureNoteCipherRequest(item, symmetricKey)
                ItemType.DOCUMENT -> createIdentityCipherRequest(item, symmetricKey)
                else -> return UploadItemResult.Error("Unsupported item type: ${item.itemType}")
            }

            val vaultApi = apiManager.getVaultApi(vault)
            val baselineCipher = fetchCipherForFieldMerge(vaultApi, accessToken, cipherId)
            val mergedRequest = mergeRequestWithCipherBaseline(request, baselineCipher, symmetricKey)
            val updateRequest = mergedRequest.toUpdateRequest()
            val requestPayload = runCatchingObserved { json.encodeToString(updateRequest) }.getOrNull()
            val response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId,
                cipher = updateRequest
            )

            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_secure_item_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatchingObserved { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "update cipher failed: ${response.code()}"
                )
                return UploadItemResult.Error("Update cipher failed: ${response.code()}")
            }

            val updatedCipher = response.body()
            if (updatedCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_secure_item_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "update cipher returned empty body"
                )
                return UploadItemResult.Error("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatchingObserved { json.encodeToString(updatedCipher) }.getOrNull(),
                success = true
            )

            val updatedItem = item.copy(
                bitwardenRevisionDate = updatedCipher.revisionDate,
                bitwardenLocalModified = false,
                syncStatus = "SYNCED",
                updatedAt = Date()
            )
            secureItemDao.update(updatedItem)

            logBitwardenSecureItemEditHistory(
                vaultId = vault.id,
                item = item,
                cipherId = cipherId,
                baselineCipher = baselineCipher,
                updateRequest = updateRequest,
                symmetricKey = symmetricKey
            )
            UploadItemResult.Success(updatedCipher.id)
        } catch (e: IllegalArgumentException) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "invalid payload"
            )
            android.util.Log.w(
                TAG,
                "Skip SecureItem update to avoid payload pollution: ${e.message}"
            )
            UploadItemResult.Error(e.message ?: "Invalid secure item payload")
        } catch (e: Exception) {
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_secure_item_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Update SecureItem failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 上传 Passkey 元数据到 Bitwarden
     */
    suspend fun uploadPasskey(
        vault: BitwardenVault,
        passkey: PasskeyEntry,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            suspend fun fail(message: String): UploadItemResult {
                passkeyDao.markFailedByRecordId(passkey.id)
                return UploadItemResult.Error(message)
            }

            if (!canSyncPasskeyToBitwarden(passkey)) {
                return fail("Legacy passkey cannot be synced to Bitwarden")
            }

            val normalizedPasskey = normalizePasskeyForUpload(passkey)
            val mapper = PasskeyMapper()
            val request = mapper.toCreateRequest(normalizedPasskey, normalizedPasskey.bitwardenFolderId)
            if (request.login?.fido2Credentials.isNullOrEmpty()) {
                return fail(
                    "Passkey key material is missing or invalid; cannot sync as FIDO2 credential"
                )
            }
            
            // 加密请求
            val encryptedRequest = encryptCipherRequest(request, symmetricKey)
            val requestPayload = runCatchingObserved { json.encodeToString(encryptedRequest) }.getOrNull()
            
            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.createCipher(
                authorization = "Bearer $accessToken",
                cipher = encryptedRequest
            )
            
            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_passkey_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatchingObserved { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "create cipher failed: ${response.code()}"
                )
                return fail("Create cipher failed: ${response.code()}")
            }
            
            val createdCipher = response.body()
            if (createdCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_passkey_create",
                    method = "POST",
                    endpoint = "/ciphers",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "create cipher returned empty body"
                )
                return fail("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_passkey_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatchingObserved { json.encodeToString(createdCipher) }.getOrNull(),
                success = true
            )
            if (createdCipher.login?.fido2Credentials.isNullOrEmpty()) {
                return fail("Server created cipher without FIDO2 credential")
            }
            
            // 更新本地 Passkey
            passkeyDao.markSyncedByRecordId(passkey.id, createdCipher.id)
            
            android.util.Log.d(TAG, "Uploaded Passkey as cipher")
            UploadItemResult.Success(createdCipher.id)
        } catch (e: Exception) {
            runCatchingObserved { passkeyDao.markFailedByRecordId(passkey.id) }
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_passkey_create",
                method = "POST",
                endpoint = "/ciphers",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Upload Passkey failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 更新已存在的 Passkey Cipher（用于修复历史兼容字段）
     */
    suspend fun updatePasskey(
        vault: BitwardenVault,
        passkey: PasskeyEntry,
        cipherId: String,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            suspend fun fail(message: String): UploadItemResult {
                passkeyDao.markFailedByRecordId(passkey.id)
                return UploadItemResult.Error(message)
            }

            if (!canSyncPasskeyToBitwarden(passkey)) {
                return fail("Legacy passkey cannot be synced to Bitwarden")
            }

            val normalizedPasskey = normalizePasskeyForUpload(passkey)
            val mapper = PasskeyMapper()
            val createRequest = mapper.toCreateRequest(normalizedPasskey, normalizedPasskey.bitwardenFolderId)
            if (createRequest.login?.fido2Credentials.isNullOrEmpty()) {
                return fail(
                    "Passkey key material is missing or invalid; cannot sync as FIDO2 credential"
                )
            }

            val encryptedCreate = encryptCipherRequest(createRequest, symmetricKey)
            val updateRequest = encryptedCreate.toUpdateRequest()
            val requestPayload = runCatchingObserved { json.encodeToString(updateRequest) }.getOrNull()

            val vaultApi = apiManager.getVaultApi(vault)
            val response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId,
                cipher = updateRequest
            )

            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_passkey_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatchingObserved { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "update cipher failed: ${response.code()}"
                )
                return fail("Update cipher failed: ${response.code()}")
            }

            val updatedCipher = response.body()
            if (updatedCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "upload_passkey_update",
                    method = "PUT",
                    endpoint = "/ciphers/$cipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "update cipher returned empty body"
                )
                return fail("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_passkey_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatchingObserved { json.encodeToString(updatedCipher) }.getOrNull(),
                success = true
            )
            if (updatedCipher.login?.fido2Credentials.isNullOrEmpty()) {
                return fail("Server updated cipher without FIDO2 credential")
            }

            passkeyDao.markSyncedByRecordId(passkey.id, updatedCipher.id)
            UploadItemResult.Success(updatedCipher.id)
        } catch (e: Exception) {
            runCatchingObserved { passkeyDao.markFailedByRecordId(passkey.id) }
            captureRawExchange(
                vaultId = vault.id,
                operation = "upload_passkey_update",
                method = "PUT",
                endpoint = "/ciphers/$cipherId",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Update Passkey failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 把绑定型 passkey 合并进密码 cipher 的 login.fido2Credentials（PUT 更新密码 cipher）。
     *
     * 合并安全策略（避免覆盖服务器上其他 passkey / 其他字段）：
     * 1. 先 GET 密码 cipher 最新数据（baseline），拿不到则返回 Error 可重试，绝不允许盲 PUT
     * 2. 解密 baseline 已有 fido2Credentials（明文）
     * 3. 按 credentialId（规范化后）去重合并：同名本地覆盖、不同名追加、其余保留
     * 4. 重加密后仅替换 login.fido2Credentials，其余字段（name/notes/username/password/uris/fields 等）基于 baseline 原样回传
     * 5. 成功后本地 PasskeyEntry.bitwardenCipherId 指向密码 cipher
     *
     * 若密码条目尚未同步（无 cipherId），退化为独立 passkey cipher 上传（uploadPasskey）。
     */
    internal suspend fun mergePasskeyIntoPasswordCipher(
        vault: BitwardenVault,
        passwordEntry: PasswordEntry,
        passkey: PasskeyEntry,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            suspend fun fail(message: String): UploadItemResult {
                passkeyDao.markFailedByRecordId(passkey.id)
                return UploadItemResult.Error(message)
            }

            if (!canSyncPasskeyToBitwarden(passkey)) {
                return fail("Legacy passkey cannot be synced to Bitwarden")
            }
            val passwordCipherId = passwordEntry.bitwardenCipherId
            if (passwordCipherId.isNullOrBlank()) {
                // 密码尚未同步到 Bitwarden：退化为独立 passkey cipher 上传，不阻塞
                return uploadPasskey(vault, passkey, accessToken, symmetricKey)
            }

            val normalizedPasskey = normalizePasskeyForUpload(passkey)
            val mapper = PasskeyMapper()
            val createRequest = mapper.toCreateRequest(normalizedPasskey, normalizedPasskey.bitwardenFolderId)
            val localPlainFido2 = createRequest.login?.fido2Credentials.orEmpty()
            if (localPlainFido2.isEmpty()) {
                return fail("Passkey key material is missing or invalid; cannot sync as FIDO2 credential")
            }

            val vaultApi = apiManager.getVaultApi(vault)

            // 1) GET baseline：必须有，否则不盲 PUT（防止清空服务器已有 passkey）
            val baseline = fetchCipherForFieldMerge(vaultApi, accessToken, passwordCipherId)
                ?: return fail("Failed to fetch password cipher baseline for passkey merge")

            // 2) 解密服务器已有 fido2 credentials（明文）
            val existingPlain = Fido2CredentialCodec.decryptCredentialsToPlainApiData(
                baseline.login?.fido2Credentials,
                symmetricKey
            )

            // 3) 明文合并去重（本地覆盖同名、保留其余）
            val mergedPlain = Fido2CredentialCodec.mergeByCredentialId(
                localPlain = localPlainFido2.first(),
                existingPlain = existingPlain
            )

            // 4) 重加密，仅替换 fido2Credentials；其余字段基于 baseline 原样回传
            val mergedEncrypted = Fido2CredentialCodec.encryptCredentials(mergedPlain, symmetricKey)
            val login = (baseline.login ?: CipherLoginApiData()).copy(
                fido2Credentials = mergedEncrypted
            )
            val updateRequest = CipherUpdateRequest(
                type = baseline.type,
                folderId = baseline.folderId ?: passwordEntry.bitwardenFolderId,
                name = baseline.name ?: BitwardenCrypto.encryptString(passwordEntry.title, symmetricKey),
                notes = baseline.notes,
                login = login,
                card = baseline.card,
                identity = baseline.identity,
                secureNote = baseline.secureNote,
                sshKey = baseline.sshKey,
                fields = baseline.fields,
                favorite = baseline.favorite,
                reprompt = baseline.reprompt,
                archivedDate = baseline.archivedDate
            )
            val requestPayload = runCatchingObserved { json.encodeToString(updateRequest) }.getOrNull()

            val response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = passwordCipherId,
                cipher = updateRequest
            )

            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "merge_passkey_into_password_cipher",
                    method = "PUT",
                    endpoint = "/ciphers/$passwordCipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatchingObserved { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "update password cipher failed: ${response.code()}"
                )
                return fail("Update password cipher failed: ${response.code()}")
            }

            val updatedCipher = response.body()
            if (updatedCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "merge_passkey_into_password_cipher",
                    method = "PUT",
                    endpoint = "/ciphers/$passwordCipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "update password cipher returned empty body"
                )
                return fail("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "merge_passkey_into_password_cipher",
                method = "PUT",
                endpoint = "/ciphers/$passwordCipherId",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatchingObserved { json.encodeToString(updatedCipher) }.getOrNull(),
                success = true
            )
            if (updatedCipher.login?.fido2Credentials.isNullOrEmpty()) {
                return fail("Server updated password cipher without FIDO2 credential")
            }

            // 5) 本地 Passkey 指向密码 cipher
            passkeyDao.markSyncedByRecordId(passkey.id, passwordCipherId)

            android.util.Log.d(TAG, "Merged passkey ${passkey.id} into password cipher $passwordCipherId")
            UploadItemResult.Success(passwordCipherId)
        } catch (e: Exception) {
            runCatchingObserved { passkeyDao.markFailedByRecordId(passkey.id) }
            captureRawExchange(
                vaultId = vault.id,
                operation = "merge_passkey_into_password_cipher",
                method = "PUT",
                endpoint = "/ciphers/${passwordEntry.bitwardenCipherId.orEmpty()}",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Merge Passkey into password cipher failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 从密码 cipher 的 login.fido2Credentials 移除指定 passkey 的 credential 后 PUT（删除语义）。
     *
     * 同样基于 GET baseline 合并，避免覆盖其他 passkey；成功后删除本地 passkey 记录。
     * 若密码条目无服务器 cipher，直接删除本地记录（无远端可清理）。
     */
    internal suspend fun removeFido2CredentialFromPasswordCipher(
        vault: BitwardenVault,
        passwordEntry: PasswordEntry,
        passkey: PasskeyEntry,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): UploadItemResult {
        return try {
            suspend fun fail(message: String): UploadItemResult {
                passkeyDao.markFailedByRecordId(passkey.id)
                return UploadItemResult.Error(message)
            }

            val passwordCipherId = passwordEntry.bitwardenCipherId
            if (passwordCipherId.isNullOrBlank()) {
                // 密码从未同步：无远端可清理，直接删除本地记录
                passkeyDao.delete(passkey)
                return UploadItemResult.Success("local-only delete")
            }

            val vaultApi = apiManager.getVaultApi(vault)
            val baseline = fetchCipherForFieldMerge(vaultApi, accessToken, passwordCipherId)
                ?: return fail("Failed to fetch password cipher baseline for passkey removal")

            val targetKey = Fido2CredentialCodec.normalizeCredentialId(passkey.credentialId)
            val existingPlain = Fido2CredentialCodec.decryptCredentialsToPlainApiData(
                baseline.login?.fido2Credentials,
                symmetricKey
            )
            val remainingPlain = existingPlain.filterNot { credential ->
                targetKey != null &&
                    Fido2CredentialCodec.normalizeCredentialId(credential.credentialId) == targetKey
            }

            val remainingEncrypted = Fido2CredentialCodec.encryptCredentials(remainingPlain, symmetricKey)
            val login = (baseline.login ?: CipherLoginApiData()).copy(
                fido2Credentials = remainingEncrypted
            )
            val updateRequest = CipherUpdateRequest(
                type = baseline.type,
                folderId = baseline.folderId ?: passwordEntry.bitwardenFolderId,
                name = baseline.name ?: BitwardenCrypto.encryptString(passwordEntry.title, symmetricKey),
                notes = baseline.notes,
                login = login,
                card = baseline.card,
                identity = baseline.identity,
                secureNote = baseline.secureNote,
                sshKey = baseline.sshKey,
                fields = baseline.fields,
                favorite = baseline.favorite,
                reprompt = baseline.reprompt,
                archivedDate = baseline.archivedDate
            )
            val requestPayload = runCatchingObserved { json.encodeToString(updateRequest) }.getOrNull()

            val response = vaultApi.updateCipher(
                authorization = "Bearer $accessToken",
                cipherId = passwordCipherId,
                cipher = updateRequest
            )

            if (!response.isSuccessful) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "remove_passkey_from_password_cipher",
                    method = "PUT",
                    endpoint = "/ciphers/$passwordCipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = runCatchingObserved { response.errorBody()?.string() }.getOrNull(),
                    success = false,
                    error = "update password cipher failed: ${response.code()}"
                )
                return fail("Update password cipher failed: ${response.code()}")
            }

            val updatedCipher = response.body()
            if (updatedCipher == null) {
                captureRawExchange(
                    vaultId = vault.id,
                    operation = "remove_passkey_from_password_cipher",
                    method = "PUT",
                    endpoint = "/ciphers/$passwordCipherId",
                    requestBody = requestPayload,
                    responseCode = response.code(),
                    responseBody = null,
                    success = false,
                    error = "update password cipher returned empty body"
                )
                return fail("Empty response")
            }

            captureRawExchange(
                vaultId = vault.id,
                operation = "remove_passkey_from_password_cipher",
                method = "PUT",
                endpoint = "/ciphers/$passwordCipherId",
                requestBody = requestPayload,
                responseCode = response.code(),
                responseBody = runCatchingObserved { json.encodeToString(updatedCipher) }.getOrNull(),
                success = true
            )

            // 成功后删除本地 passkey 记录
            passkeyDao.delete(passkey)
            android.util.Log.d(TAG, "Removed passkey ${passkey.id} from password cipher $passwordCipherId")
            UploadItemResult.Success(passwordCipherId)
        } catch (e: Exception) {
            runCatchingObserved { passkeyDao.markFailedByRecordId(passkey.id) }
            captureRawExchange(
                vaultId = vault.id,
                operation = "remove_passkey_from_password_cipher",
                method = "PUT",
                endpoint = "/ciphers/${passwordEntry.bitwardenCipherId.orEmpty()}",
                requestBody = null,
                responseCode = null,
                responseBody = null,
                success = false,
                error = e.message ?: "unknown"
            )
            android.util.Log.e(TAG, "Remove passkey from password cipher failed: ${e.message}", e)
            UploadItemResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun normalizePasskeyForUpload(passkey: PasskeyEntry): PasskeyEntry {
        val normalizedKey = PasskeyPrivateKeyStore.normalizeForBitwardenUpload(
            context = context,
            keyReferenceOrMaterial = passkey.privateKeyAlias
        )
            ?: return passkey
        return if (normalizedKey == passkey.privateKeyAlias) {
            passkey
        } else {
            passkey.copy(privateKeyAlias = normalizedKey)
        }
    }
    
    /**
     * 批量上传待同步的 SecureItems
     */
    suspend fun uploadPendingSecureItems(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BatchUploadResult {
        val pending = secureItemDao.getLocalEntriesPendingUpload(vault.id)
        
        if (pending.isEmpty()) {
            return BatchUploadResult(uploaded = 0, failed = 0, total = 0)
        }
        
        var uploaded = 0
        var failed = 0
        
        for (item in pending) {
            // 【单一归属】防御性跳过 REFERENCE 引用型条目（绑定型 TOTP / Passkey）：
            // DAO 的待上传查询已排除它们，此处再拦一层，避免历史遗留的 REFERENCE 条目
            // 被误传成独立 cipher；同时不让"跳过"计入 uploaded/failed 统计。
            if (item.syncStatus == SYNC_STATUS_REFERENCE) {
                Log.i("CipherUploadProcessor", "skip upload(reference): id=${item.id} type=${item.itemType}")
                continue
            }
            val result = uploadSecureItem(vault, item, accessToken, symmetricKey)
            when (result) {
                is UploadItemResult.Success -> uploaded++
                is UploadItemResult.Error -> failed++
            }
        }

        return BatchUploadResult(uploaded = uploaded, failed = failed, total = pending.size)
    }

    /**
     * 批量上传已修改的 SecureItems（已有 cipherId）
     */
    suspend fun uploadModifiedSecureItems(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BatchUploadResult {
        val modifiedItems = secureItemDao.getLocalModifiedEntries(vault.id)
            .filter { !it.bitwardenCipherId.isNullOrBlank() }

        if (modifiedItems.isEmpty()) {
            return BatchUploadResult(uploaded = 0, failed = 0, total = 0)
        }

        var uploaded = 0
        var failed = 0

        for (item in modifiedItems) {
            val cipherId = item.bitwardenCipherId
            if (cipherId.isNullOrBlank()) {
                failed++
                continue
            }
            val result = updateSecureItem(vault, item, cipherId, accessToken, symmetricKey)
            when (result) {
                is UploadItemResult.Success -> uploaded++
                is UploadItemResult.Error -> failed++
            }
        }

        return BatchUploadResult(uploaded = uploaded, failed = failed, total = modifiedItems.size)
    }
    
    /**
     * 批量上传待同步的 Passkeys
     *
     * 分流策略：
     * - 绑定型 passkey 且密码条目已有服务器 cipherId → 合并进密码 cipher 的 login.fido2Credentials（PUT 更新密码 cipher）
     * - 否则（未绑定 / 密码尚未上传 Bitwarden）→ 维持独立 login cipher 创建（与 Bitwarden 官方"新建条目"一致）
     */
    suspend fun uploadPendingPasskeys(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BatchUploadResult {
        val pending = passkeyDao.getLocalEntriesPendingUpload(vault.id)
            .filter(::canSyncPasskeyToBitwarden)
            .toMutableList()
        val vaultPasswordIds = passwordEntryDao.getEntriesByVaultId(vault.id).map { it.id }
        if (vaultPasswordIds.isNotEmpty()) {
            val boundCandidates = passkeyDao.getByBoundPasswordIds(vaultPasswordIds)
                .filter { passkey ->
                    canSyncPasskeyToBitwarden(passkey) &&
                    passkey.syncStatus != "REFERENCE" &&
                        passkey.bitwardenCipherId.isNullOrBlank() &&
                        passkey.bitwardenVaultId != vault.id
                }

            boundCandidates.forEach { candidate ->
                val reassigned = candidate.copy(
                    bitwardenVaultId = vault.id,
                    syncStatus = "PENDING"
                )
                passkeyDao.update(reassigned)
                pending.add(reassigned)
            }
        }

        val uniquePending = pending.distinctBy { passkey ->
            passkey.id.takeIf { it > 0L }?.let { recordId ->
                "record:$recordId"
            } ?: listOf(
                passkey.credentialId,
                passkey.rpId,
                passkey.userName,
                passkey.userDisplayName,
                passkey.boundPasswordId?.toString().orEmpty(),
                passkey.privateKeyAlias,
                passkey.bitwardenCipherId.orEmpty()
            ).joinToString("|")
        }
        if (uniquePending.isEmpty()) {
            return BatchUploadResult(uploaded = 0, failed = 0, total = 0)
        }

        // boundPasswordId -> PasswordEntry（用于绑定型 passkey 的合并分流）
        val passwordMap = passwordEntryDao.getEntriesByVaultId(vault.id)
            .associateBy { it.id }

        var uploaded = 0
        var failed = 0

        for (passkey in uniquePending) {
            val boundPassword = passkey.boundPasswordId?.let { passwordMap[it] }
            val result = if (boundPassword != null && !boundPassword.bitwardenCipherId.isNullOrBlank()) {
                mergePasskeyIntoPasswordCipher(
                    vault = vault,
                    passwordEntry = boundPassword,
                    passkey = passkey,
                    accessToken = accessToken,
                    symmetricKey = symmetricKey
                )
            } else {
                uploadPasskey(vault, passkey, accessToken, symmetricKey)
            }
            when (result) {
                is UploadItemResult.Success -> uploaded++
                is UploadItemResult.Error -> failed++
            }
        }

        return BatchUploadResult(uploaded = uploaded, failed = failed, total = uniquePending.size)
    }

    /**
     * 批量更新已同步的 Passkeys（修复 counter / userHandle 等字段）
     *
     * 分支策略：
     * - DELETE_PENDING（绑定型 passkey 删除）→ 从密码 cipher 的 fido2Credentials 移除该 credential 后 PUT，成功后删本地
     * - 绑定型（bitwardenCipherId == 密码 cipherId）→ 合并刷新（PUT 密码 cipher）
     * - 其余 → 独立 cipher PUT（现状）
     */
    suspend fun uploadModifiedPasskeys(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BatchUploadResult {
        val candidates = passkeyDao.getByBitwardenVaultId(vault.id)
            .filter { passkey ->
                if (!canSyncPasskeyToBitwarden(passkey) || passkey.syncStatus == "REFERENCE") return@filter false
                if (passkey.syncStatus == PasskeyEntry.SYNC_STATUS_DELETE_PENDING) {
                    // 删除不需要私钥材料
                    !passkey.bitwardenCipherId.isNullOrBlank()
                } else {
                    (passkey.syncStatus == "PENDING" || passkey.syncStatus == "FAILED") &&
                        !passkey.bitwardenCipherId.isNullOrBlank() &&
                        passkey.privateKeyAlias.isNotBlank()
                }
            }

        if (candidates.isEmpty()) {
            return BatchUploadResult(uploaded = 0, failed = 0, total = 0)
        }

        val passwordMap = passwordEntryDao.getEntriesByVaultId(vault.id)
            .associateBy { it.id }

        var uploaded = 0
        var failed = 0

        for (passkey in candidates) {
            val cipherId = passkey.bitwardenCipherId
            if (cipherId.isNullOrBlank()) {
                failed++
                continue
            }
            val boundPassword = passkey.boundPasswordId?.let { passwordMap[it] }
            val result = when {
                passkey.syncStatus == PasskeyEntry.SYNC_STATUS_DELETE_PENDING -> {
                    if (boundPassword != null && !boundPassword.bitwardenCipherId.isNullOrBlank()) {
                        removeFido2CredentialFromPasswordCipher(
                            vault = vault,
                            passwordEntry = boundPassword,
                            passkey = passkey,
                            accessToken = accessToken,
                            symmetricKey = symmetricKey
                        )
                    } else {
                        // 密码条目已删除/从未同步：无远端可清理，直接删除本地记录
                        passkeyDao.delete(passkey)
                        UploadItemResult.Success("local-only delete")
                    }
                }
                boundPassword != null && !boundPassword.bitwardenCipherId.isNullOrBlank() -> {
                    // 绑定型：合并刷新进密码 cipher（更新 counter/userHandle 等）
                    mergePasskeyIntoPasswordCipher(
                        vault = vault,
                        passwordEntry = boundPassword,
                        passkey = passkey,
                        accessToken = accessToken,
                        symmetricKey = symmetricKey
                    )
                }
                else -> updatePasskey(vault, passkey, cipherId, accessToken, symmetricKey)
            }
            when (result) {
                is UploadItemResult.Success -> uploaded++
                is UploadItemResult.Error -> failed++
            }
        }

        return BatchUploadResult(uploaded = uploaded, failed = failed, total = candidates.size)
    }

    private fun canSyncPasskeyToBitwarden(passkey: PasskeyEntry): Boolean {
        return passkey.passkeyMode == PasskeyEntry.MODE_BW_COMPAT
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

    private fun logBitwardenSecureItemEditHistory(
        vaultId: Long,
        item: SecureItem,
        cipherId: String,
        baselineCipher: CipherApiResponse?,
        updateRequest: CipherUpdateRequest,
        symmetricKey: SymmetricCryptoKey
    ) {
        val changes = buildSecureItemEditHistoryChanges(
            baselineCipher = baselineCipher,
            updateRequest = updateRequest,
            symmetricKey = symmetricKey
        )
        if (changes.isEmpty()) return

        OperationLogger.logUpdate(
            itemType = OperationLogItemType.BITWARDEN_SYNC,
            itemId = buildBitwardenItemId(vaultId, cipherId),
            itemTitle = item.title.ifBlank { "Bitwarden Secure Item" },
            changes = changes
        )
    }

    private fun buildSecureItemEditHistoryChanges(
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
            fieldName = "notes",
            oldValue = decryptOrPlain(baselineCipher?.notes, symmetricKey),
            newValue = decryptOrPlain(updateRequest.notes, symmetricKey),
            sensitive = true
        )

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

        appendIfChanged(
            changes = changes,
            fieldName = "username",
            oldValue = decryptOrPlain(baselineCipher?.login?.username, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.username, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "totp",
            oldValue = decryptOrPlain(baselineCipher?.login?.totp, symmetricKey),
            newValue = decryptOrPlain(updateRequest.login?.totp, symmetricKey),
            sensitive = true
        )

        val oldUriCount = baselineCipher?.login?.uris?.size ?: 0
        val newUriCount = updateRequest.login?.uris?.size ?: 0
        if (oldUriCount != newUriCount) {
            changes += FieldChange("login_uri_count", oldUriCount.toString(), newUriCount.toString())
        }

        appendIfChanged(
            changes = changes,
            fieldName = "cardholder",
            oldValue = decryptOrPlain(baselineCipher?.card?.cardholderName, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.cardholderName, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "card_number",
            oldValue = decryptOrPlain(baselineCipher?.card?.number, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.number, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "card_exp_month",
            oldValue = decryptOrPlain(baselineCipher?.card?.expMonth, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.expMonth, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "card_exp_year",
            oldValue = decryptOrPlain(baselineCipher?.card?.expYear, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.expYear, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "card_cvv",
            oldValue = decryptOrPlain(baselineCipher?.card?.code, symmetricKey),
            newValue = decryptOrPlain(updateRequest.card?.code, symmetricKey),
            sensitive = true
        )

        appendIfChanged(
            changes = changes,
            fieldName = "identity_first_name",
            oldValue = decryptOrPlain(baselineCipher?.identity?.firstName, symmetricKey),
            newValue = decryptOrPlain(updateRequest.identity?.firstName, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "identity_last_name",
            oldValue = decryptOrPlain(baselineCipher?.identity?.lastName, symmetricKey),
            newValue = decryptOrPlain(updateRequest.identity?.lastName, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "identity_email",
            oldValue = decryptOrPlain(baselineCipher?.identity?.email, symmetricKey),
            newValue = decryptOrPlain(updateRequest.identity?.email, symmetricKey),
            sensitive = true
        )
        appendIfChanged(
            changes = changes,
            fieldName = "identity_phone",
            oldValue = decryptOrPlain(baselineCipher?.identity?.phone, symmetricKey),
            newValue = decryptOrPlain(updateRequest.identity?.phone, symmetricKey),
            sensitive = true
        )

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
    
    // ========== 创建各类型 Cipher 请求 ==========
    
    private fun createTotpCipherRequest(
        item: SecureItem,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val totpData = parseTotpData(item)
        val normalizedTotp = TotpDataResolver.normalizeTotpData(
            TotpData(
                secret = totpData.secret,
                issuer = totpData.issuer,
                accountName = totpData.account,
                algorithm = totpData.algorithm,
                digits = totpData.digits,
                period = totpData.period,
                otpType = OtpType.TOTP
            )
        )
        val totpPayload = TotpDataResolver.toBitwardenPayload(item.title, normalizedTotp)
        
        val crypto = BitwardenCrypto
        
        return CipherCreateRequest(
            type = 1,  // Login with TOTP
            name = crypto.encryptString(item.title, symmetricKey),
            notes = item.notes.takeIf { it.isNotBlank() }?.let { 
                crypto.encryptString(it, symmetricKey) 
            },
            folderId = item.bitwardenFolderId,
            favorite = item.isFavorite,
            login = CipherLoginApiData(
                username = normalizedTotp.accountName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                totp = crypto.encryptString(totpPayload, symmetricKey),
                uris = normalizedTotp.issuer.takeIf { it.isNotBlank() }?.let {
                    listOf(CipherUriApiData(uri = crypto.encryptString("otpauth://totp/$it", symmetricKey)))
                }
            )
        )
    }

    private fun createCardCipherRequest(
        item: SecureItem,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val cardData = parseBankCardData(item)
        
        val crypto = BitwardenCrypto
        
        return CipherCreateRequest(
            type = 3,  // Card
            name = crypto.encryptString(item.title, symmetricKey),
            notes = item.notes.takeIf { it.isNotBlank() }?.let { 
                crypto.encryptString(it, symmetricKey) 
            },
            folderId = item.bitwardenFolderId,
            favorite = item.isFavorite,
            card = CipherCardApiData(
                cardholderName = cardData.cardholderName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                number = cardData.cardNumber.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                expMonth = cardData.expiryMonth.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                expYear = cardData.expiryYear.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                code = cardData.cvv.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                brand = cardData.brand.ifBlank { cardData.bankName }.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                }
            ),
            fields = buildEncryptedCardFields(cardData, symmetricKey)
        )
    }
    
    private fun createSecureNoteCipherRequest(
        item: SecureItem,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val noteData = parseNoteData(item)
        val externalContent = NoteContentCodec.toExternalReadableContent(noteData.content)
        
        val crypto = BitwardenCrypto
        
        return CipherCreateRequest(
            type = 2,  // SecureNote
            name = crypto.encryptString(item.title, symmetricKey),
            notes = crypto.encryptString(externalContent, symmetricKey),
            folderId = item.bitwardenFolderId,
            favorite = item.isFavorite,
            secureNote = CipherSecureNoteApiData(type = 0)
        )
    }
    
    private fun createIdentityCipherRequest(
        item: SecureItem,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val docData = parseDocumentData(item)
        
        val crypto = BitwardenCrypto
        val identityNumberForLicense = docData.licenseNumber.ifBlank {
            docData.documentNumber.takeIf {
                it.isNotBlank() && docData.documentType == DocumentType.DRIVER_LICENSE
            }.orEmpty()
        }
        val identityNumberForPassport = docData.passportNumber.ifBlank {
            docData.documentNumber.takeIf {
                it.isNotBlank() && docData.documentType == DocumentType.PASSPORT
            }.orEmpty()
        }
        val identityNumberForSsn = docData.ssn.ifBlank {
            docData.documentNumber.takeIf {
                it.isNotBlank() && (
                    docData.documentType == DocumentType.ID_CARD ||
                        docData.documentType == DocumentType.SOCIAL_SECURITY ||
                        docData.documentType == DocumentType.OTHER
                    )
            }.orEmpty()
        }
        
        return CipherCreateRequest(
            type = 4,  // Identity
            name = crypto.encryptString(item.title, symmetricKey),
            notes = item.notes.takeIf { it.isNotBlank() }?.let { 
                crypto.encryptString(it, symmetricKey) 
            },
            folderId = item.bitwardenFolderId,
            favorite = item.isFavorite,
            identity = CipherIdentityApiData(
                title = docData.title.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                firstName = docData.firstName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                middleName = docData.middleName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                lastName = docData.lastName.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                address1 = docData.address1.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                address2 = docData.address2.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                address3 = docData.address3.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                city = docData.city.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                state = docData.stateProvince.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                postalCode = docData.postalCode.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                country = docData.country.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                company = docData.company.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                email = docData.email.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                phone = docData.phone.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                ssn = identityNumberForSsn.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                username = docData.username.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                passportNumber = identityNumberForPassport.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                },
                licenseNumber = identityNumberForLicense.takeIf { it.isNotBlank() }?.let {
                    crypto.encryptString(it, symmetricKey)
                }
            ),
            fields = buildEncryptedDocumentFields(docData, symmetricKey)
        )
    }

    /**
     * 绑定型 TOTP：依附于某条密码条目的验证码（itemData.boundPasswordId != null）。
     *
     * 这类验证码**没有自己的 Bitwarden cipher**——它随所属密码条目的 authenticatorKey
     * 同步到 cipher.login.totp。若在此单独上传，会生成一个 otpauth:// 的独立 cipher，
     * 该 cipher 同步回来又被解析成一条多余的密码条目（即用户看到的"本地副本"）。
     * 详见 BastionDocs/architecture-bitwarden-alignment.md
     */
    private fun isPasswordBoundTotp(item: SecureItem): Boolean {
        if (item.itemType != ItemType.TOTP) return false
        val data = runCatchingObserved {
            TotpDataResolver.parseStoredItemData(
                itemData = item.itemData,
                fallbackIssuer = item.title,
                decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
            )
        }.getOrNull() ?: return false
        return data.boundPasswordId != null
    }

    private fun parseTotpData(item: SecureItem): TotpItemData {
        return try {
            val appData = TotpDataResolver.parseStoredItemData(
                itemData = item.itemData,
                fallbackIssuer = item.title,
                decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
            ) ?: throw IllegalArgumentException("Unable to parse Bastion TOTP payload")
            TotpItemData(
                secret = appData.secret,
                issuer = appData.issuer,
                account = appData.accountName,
                algorithm = appData.algorithm,
                digits = appData.digits,
                period = appData.period
            )
        } catch (_: Exception) {
            try {
                json.decodeFromString(TotpItemData.serializer(), item.itemData)
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "Unsupported TOTP payload for SecureItem#${item.id}; update skipped to prevent data loss"
                )
            }
        }
    }

    private fun parseBankCardData(item: SecureItem): BankCardData {
        return CardWalletDataCodec.parseBankCardData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
        )
            ?: throw IllegalArgumentException(
                "Unsupported BANK_CARD payload for SecureItem#${item.id}; update skipped to prevent data loss"
            )
    }

    private fun parseNoteData(item: SecureItem): NoteItemData {
        return try {
            val appData = json.decodeFromString<NoteData>(item.itemData)
            NoteItemData(
                content = appData.content,
                isMarkdown = appData.isMarkdown,
                tags = appData.tags
            )
        } catch (_: Exception) {
            try {
                json.decodeFromString(NoteItemData.serializer(), item.itemData)
            } catch (_: Exception) {
                if (item.itemData.isBlank() && item.notes.isNotBlank()) {
                    NoteItemData(content = item.notes)
                } else {
                    throw IllegalArgumentException(
                        "Unsupported NOTE payload for SecureItem#${item.id}; update skipped to prevent data loss"
                    )
                }
            }
        }
    }

    private fun parseDocumentData(item: SecureItem): DocumentData {
        return CardWalletDataCodec.parseDocumentData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
        )
            ?: throw IllegalArgumentException(
                "Unsupported DOCUMENT payload for SecureItem#${item.id}; update skipped to prevent data loss"
            )
    }

    private suspend fun fetchCipherForFieldMerge(
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        cipherId: String
    ): CipherApiResponse? {
        return try {
            val response = vaultApi.getCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )
            if (response.isSuccessful) {
                response.body()
            } else {
                android.util.Log.w(
                    TAG,
                    "Fetch cipher baseline failed for merge, cipherId=$cipherId, code=${response.code()}"
                )
                null
            }
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "Fetch cipher baseline exception for merge, cipherId=$cipherId, error=${e.message}"
            )
            null
        }
    }

    private fun mergeRequestWithCipherBaseline(
        request: CipherCreateRequest,
        baselineCipher: CipherApiResponse?,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        if (baselineCipher == null) return request

        val mergedFields = mergeCipherFields(
            localFields = request.fields,
            serverFields = baselineCipher.fields,
            symmetricKey = symmetricKey,
            removeCardStructuredFields = request.type == 3
        )

        return request.copy(fields = mergedFields)
    }

    private fun mergeCipherFields(
        localFields: List<CipherFieldApiData>?,
        serverFields: List<CipherFieldApiData>?,
        symmetricKey: SymmetricCryptoKey,
        removeCardStructuredFields: Boolean
    ): List<CipherFieldApiData>? {
        if (serverFields.isNullOrEmpty()) return localFields

        val merged = localFields.orEmpty().toMutableList()
        val fieldKeys = localFields.orEmpty()
            .map { buildFieldMergeKey(it, symmetricKey) }
            .toMutableSet()

        serverFields
            .filterNot { serverField ->
                removeCardStructuredFields && isInternalOrStructuredCardFieldName(
                    decryptOrPlain(serverField.name, symmetricKey).orEmpty()
                )
            }
            .forEach { serverField ->
            val serverKey = buildFieldMergeKey(serverField, symmetricKey)
            if (fieldKeys.add(serverKey)) {
                merged += serverField
            }
        }

        return merged.ifEmpty { null }
    }

    private fun buildFieldMergeKey(
        field: CipherFieldApiData,
        symmetricKey: SymmetricCryptoKey
    ): String {
        val plainName = decryptOrPlain(field.name, symmetricKey)
            ?.trim()
            .orEmpty()
        return if (plainName.isBlank()) {
            "opaque|${field.type}|${field.linkedId}|${field.name.orEmpty()}|${field.value.orEmpty()}"
        } else {
            "named|${field.type}|${field.linkedId}|$plainName"
        }
    }

    private fun isInternalOrStructuredCardFieldName(name: String): Boolean {
        val normalized = normalizeFieldName(name)
        return normalized in LEGACY_MONICA_FIELD_NAMES ||
            normalized in LEGACY_CARD_FIELD_NAMES ||
            normalized in READABLE_CARD_FIELD_NAMES
    }

    private fun normalizeFieldName(name: String): String {
        return name.trim().lowercase()
    }

    private fun decryptOrPlain(value: String?, symmetricKey: SymmetricCryptoKey): String? {
        if (value.isNullOrBlank()) return value
        if (!CIPHER_STRING_PATTERN.matches(value)) return value
        return runCatchingObserved {
            BitwardenCrypto.decryptToString(value, symmetricKey)
        }.getOrNull()
    }

    private fun buildEncryptedDocumentFields(
        docData: DocumentData,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherFieldApiData>? {
        val reserved = buildList {
            add("bastion_document_type" to docData.documentType.name)
            add("bastion_issue_date" to docData.issuedDate)
            add("bastion_expiry_date" to docData.expiryDate)
            add("bastion_issued_by" to docData.issuedBy)
            add("bastion_nationality" to docData.nationality)
            add("bastion_additional_info" to docData.additionalInfo)
        }
        return buildEncryptedFields(
            symmetricKey = symmetricKey,
            reservedFields = reserved,
            customFields = docData.customFields
        )
    }

    private fun buildEncryptedCardFields(
        cardData: BankCardData,
        symmetricKey: SymmetricCryptoKey
    ): List<CipherFieldApiData>? {
        val billingAddressDisplay = CardWalletDataCodec.parseBillingAddress(cardData.billingAddress)
            .formatForDisplay()
            .ifBlank { cardData.billingAddress }
        val reserved = buildList {
            add("Bank Name" to cardData.bankName)
            add("Card Type" to cardData.cardType.name)
            add("Billing Address" to billingAddressDisplay)
            add("Nickname" to cardData.nickname)
            add("Valid From Month" to cardData.validFromMonth)
            add("Valid From Year" to cardData.validFromYear)
            add("PIN" to cardData.pin)
            add("IBAN" to cardData.iban)
            add("SWIFT/BIC" to cardData.swiftBic)
            add("Routing Number" to cardData.routingNumber)
            add("Account Number" to cardData.accountNumber)
            add("Branch Code" to cardData.branchCode)
            add("Currency" to cardData.currency)
            add("Customer Service Phone" to cardData.customerServicePhone)
        }
        return buildEncryptedFields(
            symmetricKey = symmetricKey,
            reservedFields = reserved,
            customFields = cardData.customFields,
            excludedCustomFieldNames = LEGACY_MONICA_FIELD_NAMES + LEGACY_CARD_FIELD_NAMES + READABLE_CARD_FIELD_NAMES
        )
    }

    private fun buildEncryptedFields(
        symmetricKey: SymmetricCryptoKey,
        reservedFields: List<Pair<String, String>>,
        customFields: List<SecureCustomField>,
        excludedCustomFieldNames: Set<String> = emptySet()
    ): List<CipherFieldApiData>? {
        val crypto = BitwardenCrypto
        val result = mutableListOf<CipherFieldApiData>()

        reservedFields
            .filter { (_, value) -> value.isNotBlank() }
            .forEach { (name, value) ->
                result += CipherFieldApiData(
                    name = crypto.encryptString(name, symmetricKey),
                    value = crypto.encryptString(value, symmetricKey),
                    type = 0
                )
            }

        customFields
            .filter { it.isValid() }
            .filterNot { normalizeFieldName(it.label) in excludedCustomFieldNames }
            .forEach { field ->
                result += CipherFieldApiData(
                    name = crypto.encryptString(field.label, symmetricKey),
                    value = crypto.encryptString(field.value, symmetricKey),
                    type = when (field.type) {
                        SecureCustomFieldType.TEXT -> 0
                        SecureCustomFieldType.HIDDEN -> 1
                        SecureCustomFieldType.BOOLEAN -> 2
                        SecureCustomFieldType.LINKED -> 3
                    }
                )
            }

        return result.ifEmpty { null }
    }

    private fun CipherCreateRequest.toUpdateRequest(): CipherUpdateRequest {
        return CipherUpdateRequest(
            type = type,
            folderId = folderId,
            name = name,
            notes = notes,
            login = login,
            card = card,
            identity = identity,
            secureNote = secureNote,
            sshKey = sshKey,
            fields = fields,
            favorite = favorite,
            reprompt = reprompt,
            archivedDate = archivedDate
        )
    }
    
    /**
     * 加密 CipherCreateRequest
     */
    private fun encryptCipherRequest(
        request: CipherCreateRequest,
        symmetricKey: SymmetricCryptoKey
    ): CipherCreateRequest {
        val crypto = BitwardenCrypto
        
        fun isEncrypted(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            if (!CIPHER_STRING_PATTERN.matches(value)) return false
            return runCatchingObserved { crypto.parseCipherString(value) }.isSuccess
        }

        fun encryptIfNeeded(value: String?): String? {
            if (value.isNullOrBlank()) return value
            return if (isEncrypted(value)) value else crypto.encryptString(value, symmetricKey)
        }
        
        return request.copy(
            name = encryptIfNeeded(request.name) ?: request.name,
            notes = encryptIfNeeded(request.notes),
            login = request.login?.let { login ->
                login.copy(
                    username = encryptIfNeeded(login.username),
                    password = encryptIfNeeded(login.password),
                    totp = encryptIfNeeded(login.totp),
                    uris = login.uris?.map { uri ->
                        uri.copy(
                            uri = encryptIfNeeded(uri.uri)
                        )
                    },
                    fido2Credentials = login.fido2Credentials?.map { fido ->
                        fido.copy(
                            credentialId = encryptIfNeeded(fido.credentialId),
                            keyType = encryptIfNeeded(fido.keyType),
                            keyAlgorithm = encryptIfNeeded(fido.keyAlgorithm),
                            keyCurve = encryptIfNeeded(fido.keyCurve),
                            keyValue = encryptIfNeeded(fido.keyValue),
                            rpId = encryptIfNeeded(fido.rpId),
                            rpName = encryptIfNeeded(fido.rpName),
                            counter = encryptIfNeeded(fido.counter),
                            userHandle = encryptIfNeeded(fido.userHandle),
                            userName = encryptIfNeeded(fido.userName),
                            userDisplayName = encryptIfNeeded(fido.userDisplayName),
                            discoverable = encryptIfNeeded(fido.discoverable),
                            // Bitwarden expects a parseable DateTime here, not a cipher string.
                            creationDate = fido.creationDate
                        )
                    },
                )
            },
            sshKey = request.sshKey?.let { sshKey ->
                sshKey.copy(
                    privateKey = encryptIfNeeded(sshKey.privateKey),
                    publicKey = encryptIfNeeded(sshKey.publicKey),
                    keyFingerprint = encryptIfNeeded(sshKey.keyFingerprint)
                )
            }
        )
    }
}

/**
 * 单项上传结果
 */
sealed class UploadItemResult {
    data class Success(val cipherId: String) : UploadItemResult()
    data class Error(val message: String) : UploadItemResult()
}

/**
 * 批量上传结果
 */
data class BatchUploadResult(
    val uploaded: Int,
    val failed: Int,
    val total: Int
) {
    val success: Boolean get() = failed == 0
}
