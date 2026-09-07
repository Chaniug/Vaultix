package com.bastion.app.bitwarden.import

import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.export.BitwardenEncryptedExport
import com.bastion.app.bitwarden.export.BitwardenPlainExport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bitwarden 兼容 JSON 导入解析与解密。
 *
 * 与 [com.bastion.app.bitwarden.export.BitwardenJsonExporter] 完全对称：
 *  - 明文导入：{ encrypted:false, folders:[...], items:[...] }
 *  - 加密导入（密码保护）：{ encrypted:true, passwordProtected:true, salt, kdfType,
 *      kdfIterations, encKeyValidation_DO_NOT_EDIT, data, ... }
 *
 * 解密流程与 bw2keepass 的 encrypt_bitwarden_export 对称：
 *  PBKDF2-SHA256(password, utf8(salt), kdfIterations) -> masterKey
 *  HKDF-Expand(masterKey, "enc"/"mac") -> encKey/macKey
 *  data = AES-256-CBC + HMAC-SHA256(整个明文 JSON)
 */
object BitwardenJsonImport {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 仅解析信封头，判断是否密码保护加密导出 */
    fun isEncryptedExport(content: String): Boolean {
        return runCatching {
            val header = json.decodeFromString<BitwardenEnvelopeHeader>(content)
            header.encrypted && header.passwordProtected
        }.getOrDefault(false)
    }

    /** 解析明文导出文件内容 */
    fun parsePlain(content: String): BitwardenPlainExport {
        return json.decodeFromString(content)
    }

    /**
     * 解密并解析密码保护导出文件。
     *
     * 密码校验完全依赖 HMAC 校验：解密 [BitwardenEncryptedExport.encKeyValidation_DO_NOT_EDIT]
     * 时若 MAC 校验失败，即说明密码不正确。
     *
     * 注意：该字段的明文内容在不同导出方下并不一致——
     *   - Bitwarden 官方导出：加密的是导出时生成的随机 GUID；
     *   - Bastion 自导出：加密的是字面量 "Bitwarden"。
     * 因此**不能**按字面量比较，必须以「解密（MAC 校验）成功」作为密码正确的依据，
     * 否则一切 Bitwarden 官方加密导出都会被判为「密码不正确」。
     *
     * @throws SecurityException 密码错误（或导出文件损坏）导致校验串 MAC 校验失败时
     */
    fun decryptAndParse(content: String, password: String): BitwardenPlainExport {
        val envelope = json.decodeFromString<BitwardenEncryptedExport>(content)

        // salt 以 base64 字符串形式存储，PBKDF2 使用其 UTF-8 字节，与 bw2keepass salt_mode='utf8' 一致
        val masterKey = BitwardenCrypto.deriveMasterKeyPbkdf2(
            password = password,
            salt = envelope.salt,
            iterations = envelope.kdfIterations
        )
        val key = BitwardenCrypto.stretchMasterKey(masterKey)

        // 密码校验：仅当 encKeyValidation_DO_NOT_EDIT 能被成功解密（HMAC 校验通过）时，
        // 才说明密码正确。该字段明文可能是随机 GUID（Bitwarden 官方导出）或 "Bitwarden"
        // （Bastion 自导出），因此不做字面量比较，而是以解密成功与否作为依据。
        val passwordValid = runCatching {
            // decryptToString 在 MAC 校验失败时会抛出 SecurityException，
            // 此时 runCatching 返回失败，passwordValid 为 false -> 判定密码错误。
            BitwardenCrypto.decryptToString(envelope.encKeyValidation_DO_NOT_EDIT, key)
            true
        }.getOrDefault(false)

        if (!passwordValid) {
            // 清理内存中的密钥材料
            masterKey.fill(0)
            key.encKey.fill(0)
            key.macKey.fill(0)
            throw SecurityException("密码不正确，无法解密 Bitwarden 导出文件")
        }

        val plainJson = BitwardenCrypto.decryptToString(envelope.data, key)

        // 清理内存中的密钥材料
        masterKey.fill(0)
        key.encKey.fill(0)
        key.macKey.fill(0)

        return json.decodeFromString(plainJson)
    }

    @Serializable
    private data class BitwardenEnvelopeHeader(
        val encrypted: Boolean = false,
        val passwordProtected: Boolean = false
    )
}
