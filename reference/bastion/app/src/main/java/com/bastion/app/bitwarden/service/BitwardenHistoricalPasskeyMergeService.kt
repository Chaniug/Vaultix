package com.bastion.app.bitwarden.service

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bastion.app.bitwarden.api.BitwardenApiManager
import com.bastion.app.bitwarden.api.BitwardenVaultApi
import com.bastion.app.bitwarden.api.CipherUpdateRequest
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import com.bastion.app.bitwarden.mapper.Fido2CredentialCodec
import com.bastion.app.bitwarden.mapper.PasskeyMapper
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.bitwarden.BitwardenVault

/**
 * 历史独立 [Passkey] cipher 迁移服务。
 *
 * 历史版本把「绑定到密码条目」的 passkey 上传成了独立 login cipher
 * （name 形如 "xxx [Passkey]"、login.fido2Credentials 单元素）。
 * 本服务在每次同步时自动扫描这类 passkey，将其合并进对应密码 cipher 的
 * login.fido2Credentials（复用 CipherUploadProcessor.mergePasskeyIntoPasswordCipher），
 * 然后软删独立 cipher 并清理下载侧遗留的空密码条目。
 *
 * 幂等：已迁移的 passkey（bitwardenCipherId == 密码 cipherId）不在扫描结果内。
 * 失败重试：单条失败标记 syncStatus = FAILED（保留独立 cipherId），下轮同步自动重试。
 *
 * 模式参考：BitwardenHistoricalTotpRepairService（遍历本地 → GET 对比 → 更新 → 交统一 sync）。
 */
class BitwardenHistoricalPasskeyMergeService(
    private val context: Context,
    private val apiManager: BitwardenApiManager = BitwardenApiManager(),
    private val uploadProcessor: CipherUploadProcessor = CipherUploadProcessor(context)
) {
    companion object {
        private const val TAG = "BwHistoricalPasskeyMerge"
        private const val PREFS_NAME = "bw_historical_merge_meta"
    }

    private val database = PasswordDatabase.getDatabase(context)
    private val passkeyDao = database.passkeyDao()
    private val passwordEntryDao = database.passwordEntryDao()

    /**
     * 扫描并迁移绑定型 passkey 的历史独立 cipher。
     * 在同步流程的「处理本地删除」之后、「上传本地创建」之前调用。
     */
    suspend fun mergeHistoricalStandalonePasskeys(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): BitwardenHistoricalPasskeyMergeResult = withContext(Dispatchers.IO) {
        val vaultApi = apiManager.getVaultApi(vault)

        // 0) 先清理历史冗余自定义字段 bastion_passkey_bindings（passkey 绑定已由官方 fido2Credentials 承载）
        val legacyFieldsCleaned = cleanupLegacyPasskeyBindingsField(
            vault = vault,
            vaultApi = vaultApi,
            accessToken = accessToken,
            symmetricKey = symmetricKey
        )

        var mergedPasskeys = 0
        var deletedStandaloneCiphers = 0
        var cleanedUpEmptyPasswordEntries = 0
        var failedPasskeys = 0
        var skippedPasskeys = 0

        val candidates = passkeyDao.getBoundPasskeysOnStandaloneCipher(vault.id)
        if (candidates.isEmpty()) {
            return@withContext BitwardenHistoricalPasskeyMergeResult(
                mergedPasskeys = 0,
                deletedStandaloneCiphers = 0,
                cleanedUpEmptyPasswordEntries = 0,
                failedPasskeys = 0,
                skippedPasskeys = 0
            )
        }

        val passwordMap = passwordEntryDao.getByBitwardenVaultId(vault.id)
            .associateBy { it.id }

        candidates.forEach { passkey ->
            try {
                val passwordEntry = passkey.boundPasswordId?.let { passwordMap[it] }
                if (passwordEntry == null || passwordEntry.bitwardenCipherId.isNullOrBlank()) {
                    // 密码条目缺失/未同步：无法迁移，跳过（不删不合并）
                    skippedPasskeys++
                    return@forEach
                }

                val standaloneCipherId = passkey.bitwardenCipherId
                if (standaloneCipherId.isNullOrBlank() || standaloneCipherId == passwordEntry.bitwardenCipherId) {
                    skippedPasskeys++
                    return@forEach
                }

                // 1) 校验独立 cipher 确实是 passkey cipher 且含本地 credentialId（防误删普通 cipher）
                val standaloneCipher = fetchStandaloneCipher(vaultApi, accessToken, standaloneCipherId)
                if (standaloneCipher == null) {
                    Log.w(TAG, "Skip merge for passkey ${passkey.id}: standalone cipher $standaloneCipherId not found")
                    skippedPasskeys++
                    return@forEach
                }
                if (!PasskeyMapper.isPasskeyCipher(standaloneCipher)) {
                    Log.w(TAG, "Skip merge for passkey ${passkey.id}: cipher $standaloneCipherId is not a passkey cipher")
                    skippedPasskeys++
                    return@forEach
                }
                if (!standaloneCipherContainsCredential(
                        standaloneCipher = standaloneCipher,
                        passkey = passkey,
                        symmetricKey = symmetricKey
                    )
                ) {
                    Log.w(TAG, "Skip merge for passkey ${passkey.id}: credential not found on standalone cipher $standaloneCipherId")
                    skippedPasskeys++
                    return@forEach
                }

                // 2) 合并进密码 cipher（PUT 更新密码 cipher；成功后本地 bitwardenCipherId 指向密码 cipher）
                val mergeResult = uploadProcessor.mergePasskeyIntoPasswordCipher(
                    vault = vault,
                    passwordEntry = passwordEntry,
                    passkey = passkey,
                    accessToken = accessToken,
                    symmetricKey = symmetricKey
                )
                if (mergeResult !is UploadItemResult.Success) {
                    val mergeError = (mergeResult as? UploadItemResult.Error)?.message ?: "unknown"
                    Log.w(TAG, "Merge failed for passkey ${passkey.id}: $mergeError")
                    failedPasskeys++
                    return@forEach
                }

                // 3) 软删独立 cipher（404 视为已删除/成功）
                val deleteResult = runCatchingObserved {
                    vaultApi.deleteCipher(
                        authorization = "Bearer $accessToken",
                        cipherId = standaloneCipherId
                    )
                }.getOrNull()
                if (deleteResult == null || !deleteResult.isSuccessful && deleteResult.code() != 404) {
                    Log.w(
                        TAG,
                        "Soft-delete standalone cipher $standaloneCipherId returned " +
                            "${deleteResult?.code() ?: "exception"}; will retry next sync"
                    )
                    // 合并已成功但删除失败：保留独立 cipher 状态，下轮重试删除
                    passkeyDao.markFailedByRecordId(passkey.id)
                    failedPasskeys++
                    return@forEach
                }
                deletedStandaloneCiphers++

                // 4) 清理下载侧为独立 passkey cipher 生成的遗留空密码条目
                val shadowEntry = passwordEntryDao.getByBitwardenCipherIdInVault(vault.id, standaloneCipherId)
                if (shadowEntry != null && isShadowPasswordEntry(shadowEntry)) {
                    passwordEntryDao.deletePasswordEntry(shadowEntry)
                    cleanedUpEmptyPasswordEntries++
                }

                mergedPasskeys++
            } catch (e: Exception) {
                Log.w(TAG, "Merge passkey ${passkey.id} failed with exception: ${e.message}", e)
                runCatchingObserved { passkeyDao.markFailedByRecordId(passkey.id) }
                failedPasskeys++
            }
        }

        if (legacyFieldsCleaned > 0) {
            Log.i(TAG, "Cleaned $legacyFieldsCleaned legacy bastion_passkey_bindings fields")
        }

        BitwardenHistoricalPasskeyMergeResult(
            mergedPasskeys = mergedPasskeys,
            deletedStandaloneCiphers = deletedStandaloneCiphers,
            cleanedUpEmptyPasswordEntries = cleanedUpEmptyPasswordEntries,
            failedPasskeys = failedPasskeys,
            skippedPasskeys = skippedPasskeys
        )
    }

    /**
     * 清理历史冗余自定义字段 bastion_passkey_bindings（passkey 绑定已由官方 login.fido2Credentials 承载）。
     *
     * 扫描本 vault 所有已同步的密码 cipher，若 fields 中含 bastion_passkey_bindings 则剔除后 PUT。
     * - 幂等：剔除后服务器不再含该字段，下轮扫描自动跳过
     * - 安全：其余字段（含 login/fido2Credentials/其他自定义字段）基于 GET baseline 原样回传，不动内容
     * - 失败：单条 try/catch，失败仅记日志，下轮同步自动重试
     */
    private suspend fun cleanupLegacyPasskeyBindingsField(
        vault: BitwardenVault,
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey
    ): Int {
        // 完成标记（vault 级）：首次跑完后置位，后续同步直接跳过。
        // 该函数会对 vault 内全部密码条目逐个发 GET /ciphers/{id} 检查 legacy 字段
        //（216+ 次网络请求），连接池死连接/网络慢时会把同步挂死（表现为"清缓存后才能同步成功"）。
        // legacy 字段只存在于旧版本数据，新代码不再写入，一次性清理即可，无需每次同步全量重扫。
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val markerKey = "legacy_bindings_cleanup_done_${vault.id}"
        if (prefs.getBoolean(markerKey, false)) {
            Log.i(TAG, "Skip legacy bindings cleanup (marker set): vaultId=${vault.id}")
            return 0
        }

        val candidates = passwordEntryDao.getByBitwardenVaultId(vault.id)
            .filter { !it.isDeleted && !it.bitwardenCipherId.isNullOrBlank() }
        if (candidates.isEmpty()) return 0

        var cleaned = 0
        candidates.forEach { entry ->
            try {
                val cipherId = entry.bitwardenCipherId ?: return@forEach
                val cipher = fetchStandaloneCipher(vaultApi, accessToken, cipherId) ?: return@forEach
                val fields = cipher.fields ?: return@forEach

                val hasLegacyField = fields.any { field ->
                    isLegacyPasskeyBindingsField(field.name, symmetricKey)
                }
                if (!hasLegacyField) return@forEach

                val cleanedFields = fields.filterNot { field ->
                    isLegacyPasskeyBindingsField(field.name, symmetricKey)
                }
                val updateRequest = CipherUpdateRequest(
                    type = cipher.type,
                    folderId = cipher.folderId,
                    name = cipher.name ?: return@forEach,
                    notes = cipher.notes,
                    login = cipher.login,
                    card = cipher.card,
                    identity = cipher.identity,
                    secureNote = cipher.secureNote,
                    sshKey = cipher.sshKey,
                    fields = cleanedFields.ifEmpty { null },
                    favorite = cipher.favorite,
                    reprompt = cipher.reprompt,
                    archivedDate = cipher.archivedDate
                )

                val response = runCatchingObserved {
                    vaultApi.updateCipher(
                        authorization = "Bearer $accessToken",
                        cipherId = cipherId,
                        cipher = updateRequest
                    )
                }.getOrNull()
                if (response != null && response.isSuccessful) {
                    cleaned++
                    Log.i(TAG, "Cleaned bastion_passkey_bindings from cipher $cipherId")
                } else {
                    Log.w(TAG, "Clean legacy field failed for cipher $cipherId: ${response?.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Clean legacy field exception for entry ${entry.id}: ${e.message}")
            }
        }
        // 跑完即置位（无论清理了多少；新数据不再带 legacy 字段，一次性清理即可）
        prefs.edit().putBoolean(markerKey, true).apply()
        return cleaned
    }

    private fun isLegacyPasskeyBindingsField(
        encryptedName: String?,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val name = Fido2CredentialCodec.decryptOrPlain(encryptedName, symmetricKey)
            ?.trim()
            .orEmpty()
        return name.equals("bastion_passkey_bindings", ignoreCase = true)
    }

    private suspend fun fetchStandaloneCipher(
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        cipherId: String
    ): com.bastion.app.bitwarden.api.CipherApiResponse? {
        val response = runCatchingObserved {
            vaultApi.getCipher(
                authorization = "Bearer $accessToken",
                cipherId = cipherId
            )
        }.getOrElse { error ->
            Log.w(TAG, "Failed to fetch cipher $cipherId for passkey merge: ${error.message}")
            return null
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "Skipping passkey merge for cipher $cipherId: getCipher ${response.code()}")
            return null
        }
        return response.body()
    }

    /**
     * 校验独立 cipher 的 fido2Credentials 中含本地 passkey 的 credentialId（规范化比较）。
     */
    private fun standaloneCipherContainsCredential(
        standaloneCipher: com.bastion.app.bitwarden.api.CipherApiResponse,
        passkey: PasskeyEntry,
        symmetricKey: SymmetricCryptoKey
    ): Boolean {
        val decoded = Fido2CredentialCodec.decodeFido2Credentials(
            standaloneCipher.login?.fido2Credentials,
            symmetricKey
        )
        if (decoded.isEmpty()) return false
        val localKey = Fido2CredentialCodec.normalizeCredentialId(passkey.credentialId)
            ?: return false
        return decoded.any { credential ->
            Fido2CredentialCodec.normalizeCredentialId(credential.credentialId) == localKey
        }
    }

    /**
     * 判断是否为独立 passkey cipher 下载侧产生的「影子密码条目」：
     * 无实际凭据内容（password/username/notes/totp 均空），仅由同步自动创建。
     */
    private fun isShadowPasswordEntry(entry: com.bastion.app.data.PasswordEntry): Boolean {
        return entry.username.isBlank() &&
            entry.password.isBlank() &&
            entry.notes.isBlank() &&
            entry.authenticatorKey.isBlank()
    }
}

/**
 * 迁移结果统计
 */
data class BitwardenHistoricalPasskeyMergeResult(
    val mergedPasskeys: Int,
    val deletedStandaloneCiphers: Int,
    val cleanedUpEmptyPasswordEntries: Int,
    val failedPasskeys: Int,
    val skippedPasskeys: Int
)
