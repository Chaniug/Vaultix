package com.bastion.app.bitwarden.mapper

import com.bastion.app.logging.runCatchingObserved
import com.bastion.app.bitwarden.api.CipherLoginFido2CredentialApiData
import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.passkey.PasskeyCredentialIdCodec
import java.time.Instant

/**
 * FIDO2 credential 编解码与合并公共逻辑。
 *
 * 供以下场景复用（避免 CipherSyncProcessor 与 CipherUploadProcessor 重复实现）：
 * - 下载：把服务器 cipher 的 login.fido2Credentials 解密为本地 PasskeyEntry 数据
 * - 上传合并：绑定型 passkey 合并进密码 cipher 时，先解密 baseline → 明文合并去重 → 重加密
 * - 迁移：校验独立 passkey cipher 内容
 */
object Fido2CredentialCodec {

    /** 解密后的 FIDO2 credential（对齐原 CipherSyncProcessor.DecodedPasskeyCredential） */
    data class DecodedFido2Credential(
        val credentialId: String,
        val keyValue: String,
        val rpId: String,
        val rpName: String,
        val userHandle: String,
        val userName: String,
        val userDisplayName: String,
        val counter: Long,
        val discoverable: Boolean,
        val creationDateMillis: Long?,
        val publicKeyAlgorithm: Int
    )

    /** Bitwarden cipher string 完整格式（用于判断是否已加密） */
    private val CIPHER_STRING_PATTERN =
        Regex("^[0-9]+\\.[A-Za-z0-9+/_=-]+\\|[A-Za-z0-9+/_=-]+(?:\\|[A-Za-z0-9+/_=-]+)?$")

    // ========== 解密 ==========

    /**
     * 解密服务器 fido2Credentials 列表。
     * 任一条目若没有任何有效信号（credentialId/keyValue/rpId/userName 全空）则丢弃。
     */
    fun decodeFido2Credentials(
        credentials: List<CipherLoginFido2CredentialApiData>?,
        key: SymmetricCryptoKey
    ): List<DecodedFido2Credential> {
        if (credentials.isNullOrEmpty()) return emptyList()

        return credentials.mapNotNull { credential ->
            val credentialId = decryptOrPlain(credential.credentialId, key).orEmpty()
            val keyValue = decryptOrPlain(credential.keyValue, key).orEmpty()
            val rpId = decryptOrPlain(credential.rpId, key).orEmpty()
            val rpName = decryptOrPlain(credential.rpName, key).orEmpty()
            val userHandle = decryptOrPlain(credential.userHandle, key).orEmpty()
            val userName = decryptOrPlain(credential.userName, key).orEmpty()
            val userDisplayName = decryptOrPlain(credential.userDisplayName, key).orEmpty()
            val counter = decryptOrPlain(credential.counter, key)?.toLongOrNull() ?: 0L
            val discoverable = parseBooleanText(decryptOrPlain(credential.discoverable, key))
            val creationDate = parseCreationDateMillis(decryptOrPlain(credential.creationDate, key))
            val keyAlgorithm = decryptOrPlain(credential.keyAlgorithm, key)
            val publicKeyAlgorithm = parseAlgorithm(keyAlgorithm)

            val hasAnySignal = credentialId.isNotBlank() ||
                keyValue.isNotBlank() ||
                rpId.isNotBlank() ||
                userName.isNotBlank()
            if (!hasAnySignal) return@mapNotNull null

            DecodedFido2Credential(
                credentialId = credentialId,
                keyValue = keyValue,
                rpId = rpId,
                rpName = rpName,
                userHandle = userHandle,
                userName = userName,
                userDisplayName = userDisplayName,
                counter = counter,
                discoverable = discoverable,
                creationDateMillis = creationDate,
                publicKeyAlgorithm = publicKeyAlgorithm
            )
        }
    }

    /**
     * 解密或原样返回：
     * - 已加密 → 解密成功返回明文
     * - 形似密文但解密失败 → null（不把密文当明文返回）
     * - 明文 → 原样返回
     */
    fun decryptOrPlain(value: String?, key: SymmetricCryptoKey): String? {
        if (value.isNullOrBlank()) return null
        val decrypted = decryptString(value, key)
        if (decrypted != null) return decrypted
        if (looksLikeCipherString(value)) return null
        return value
    }

    /** 仅当形似 cipher string 时尝试解密；失败返回 null */
    fun decryptString(encrypted: String?, key: SymmetricCryptoKey): String? {
        if (encrypted.isNullOrBlank()) return null
        if (!looksLikeCipherString(encrypted)) return null
        return runCatchingObserved { BitwardenCrypto.decryptToString(encrypted, key) }.getOrNull()
    }

    /** 宽松判断：`数字.` 前缀即视为可能密文（解密判断用） */
    fun looksLikeCipherString(value: String): Boolean {
        val dotIndex = value.indexOf('.')
        if (dotIndex <= 0) return false
        return value.substring(0, dotIndex).all(Char::isDigit)
    }

    /**
     * 解密为明文 ApiData 列表（供上传合并/删除用）。
     * 与 [decodeFido2Credentials] 不同：保留原始字段形态，便于与本地明文 credential 合并。
     * 任一条目若没有任何有效信号（credentialId/keyValue/rpId/userName 全空）则丢弃。
     */
    fun decryptCredentialsToPlainApiData(
        credentials: List<CipherLoginFido2CredentialApiData>?,
        key: SymmetricCryptoKey
    ): List<CipherLoginFido2CredentialApiData> {
        if (credentials.isNullOrEmpty()) return emptyList()

        return credentials.mapNotNull { credential ->
            val decryptedId = decryptOrPlain(credential.credentialId, key)
            val hasAnySignal = !decryptedId.isNullOrBlank() ||
                !decryptOrPlain(credential.keyValue, key).isNullOrBlank() ||
                !decryptOrPlain(credential.rpId, key).isNullOrBlank() ||
                !decryptOrPlain(credential.userName, key).isNullOrBlank()
            if (!hasAnySignal) return@mapNotNull null

            credential.copy(
                credentialId = decryptedId,
                keyType = decryptOrPlain(credential.keyType, key),
                keyAlgorithm = decryptOrPlain(credential.keyAlgorithm, key),
                keyCurve = decryptOrPlain(credential.keyCurve, key),
                keyValue = decryptOrPlain(credential.keyValue, key),
                rpId = decryptOrPlain(credential.rpId, key),
                rpName = decryptOrPlain(credential.rpName, key),
                counter = decryptOrPlain(credential.counter, key),
                userHandle = decryptOrPlain(credential.userHandle, key),
                userName = decryptOrPlain(credential.userName, key),
                userDisplayName = decryptOrPlain(credential.userDisplayName, key),
                discoverable = decryptOrPlain(credential.discoverable, key),
                creationDate = decryptOrPlain(credential.creationDate, key)
            )
        }
    }

    /**
     * 规范化 credentialId（比较用）。公开给删除匹配等场景使用。
     */
    fun normalizeCredentialId(credentialId: String?): String? =
        PasskeyCredentialIdCodec.normalize(credentialId)

    // ========== 加密 ==========

    /**
     * 加密单个明文 credential。
     * 注意：creationDate 不加密（Bitwarden 期望可解析的 DateTime，而非 cipher string）。
     */
    fun encryptCredential(
        plain: CipherLoginFido2CredentialApiData,
        key: SymmetricCryptoKey
    ): CipherLoginFido2CredentialApiData {
        return plain.copy(
            credentialId = encryptIfNeeded(plain.credentialId, key),
            keyType = encryptIfNeeded(plain.keyType, key),
            keyAlgorithm = encryptIfNeeded(plain.keyAlgorithm, key),
            keyCurve = encryptIfNeeded(plain.keyCurve, key),
            keyValue = encryptIfNeeded(plain.keyValue, key),
            rpId = encryptIfNeeded(plain.rpId, key),
            rpName = encryptIfNeeded(plain.rpName, key),
            counter = encryptIfNeeded(plain.counter, key),
            userHandle = encryptIfNeeded(plain.userHandle, key),
            userName = encryptIfNeeded(plain.userName, key),
            userDisplayName = encryptIfNeeded(plain.userDisplayName, key),
            discoverable = encryptIfNeeded(plain.discoverable, key),
            creationDate = plain.creationDate
        )
    }

    /** 加密 credential 列表 */
    fun encryptCredentials(
        plainList: List<CipherLoginFido2CredentialApiData>,
        key: SymmetricCryptoKey
    ): List<CipherLoginFido2CredentialApiData> = plainList.map { encryptCredential(it, key) }

    // ========== 合并 ==========

    /**
     * 按 credentialId（规范化后）合并本地 credential 到已有列表：
     * - 与已有条目 credentialId 相同 → 本地覆盖（本地私钥优先）
     * - 不同 → 追加
     * - 其余既有条目全部保留（保护其他设备/客户端添加的 passkey，避免覆盖丢失）
     *
     * 返回明文列表，调用方自行决定是否重加密。
     */
    fun mergeByCredentialId(
        localPlain: CipherLoginFido2CredentialApiData,
        existingPlain: List<CipherLoginFido2CredentialApiData>
    ): List<CipherLoginFido2CredentialApiData> {
        val localKey = normalizeCredentialId(localPlain.credentialId)
        val result = existingPlain.toMutableList()
        val replacedIndex = result.indexOfFirst { credential ->
            localKey != null && normalizeCredentialId(credential.credentialId) == localKey
        }
        if (replacedIndex >= 0) {
            result[replacedIndex] = localPlain
        } else {
            result.add(localPlain)
        }
        return result
    }

    // ========== 内部工具 ==========

    private fun encryptIfNeeded(value: String?, key: SymmetricCryptoKey): String? {
        if (value.isNullOrBlank()) return value
        return if (isEncrypted(value, key)) value else BitwardenCrypto.encryptString(value, key)
    }

    private fun isEncrypted(value: String?, key: SymmetricCryptoKey): Boolean {
        if (value.isNullOrBlank()) return false
        if (!CIPHER_STRING_PATTERN.matches(value)) return false
        return runCatchingObserved { BitwardenCrypto.parseCipherString(value) }.isSuccess
    }

    private fun parseBooleanText(value: String?): Boolean {
        return when (value?.trim()?.lowercase()) {
            "false", "0", "no" -> false
            else -> true
        }
    }

    private fun parseCreationDateMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatchingObserved { Instant.parse(value).toEpochMilli() }.getOrNull()
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
}
