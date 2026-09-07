package com.bastion.app.bitwarden.export

import android.util.Base64
import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.data.Category
import com.bastion.app.data.ItemType
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.SecureItem
import com.bastion.app.data.model.BankCardData
import com.bastion.app.data.model.CardWalletDataCodec
import com.bastion.app.data.model.DocumentData
import com.bastion.app.data.model.DocumentType
import com.bastion.app.data.model.NoteData
import com.bastion.app.data.model.SecureCustomFieldType
import com.bastion.app.data.model.SshKeyDataCodec
import com.bastion.app.data.model.TotpData
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.security.SecurityManager
import com.bastion.app.util.TotpDataResolver
import com.bastion.app.utils.PasswordWebsiteCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Bitwarden 兼容的 JSON 导出模型与构建器。
 *
 * 导出的 JSON 结构与 bw2keepass（以及 Bitwarden 官方导入/导出）完全对齐：
 *  - 明文导出：{ encrypted:false, folders:[...], items:[...] }
 *  - 加密导出（密码保护）：{ encrypted:true, passwordProtected:true, salt, kdfType,
 *      kdfIterations, encKeyValidation_DO_NOT_EDIT, data, ... }
 *
 * 字段全部使用 camelCase 键名，与 bw2keepass 的 Python 解析器（parser.py）读取的
 * 键名一致；不使用 Bastion 内部 Cipher 模型的 PascalCase 序列化名。
 */

// ===================== 明文导出项模型（camelCase） =====================

@Serializable
data class BwUri(
    val uri: String? = null,
    val match: Int? = null
)

@Serializable
data class BwLogin(
    val username: String? = null,
    val password: String? = null,
    val totp: String? = null,
    val uris: List<BwUri>? = null
)

@Serializable
data class BwCard(
    val cardholderName: String? = null,
    val brand: String? = null,
    val number: String? = null,
    val expMonth: String? = null,
    val expYear: String? = null,
    val code: String? = null
)

@Serializable
data class BwIdentity(
    val title: String? = null,
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val address1: String? = null,
    val address2: String? = null,
    val address3: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val company: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val ssn: String? = null,
    val username: String? = null,
    val passportNumber: String? = null,
    val licenseNumber: String? = null
)

@Serializable
data class BwSecureNote(
    val type: Int = 0
)

@Serializable
data class BwField(
    val name: String? = null,
    val value: String? = null,
    val type: Int = 0 // 0=text, 1=hidden, 2=boolean, 3=linked
)

@Serializable
data class BwExportItem(
    val id: String,
    val type: Int,
    val name: String,
    val notes: String? = null,
    val favorite: Boolean = false,
    val folderId: String? = null,
    val login: BwLogin? = null,
    val card: BwCard? = null,
    val identity: BwIdentity? = null,
    val secureNote: BwSecureNote? = null,
    val fields: List<BwField>? = null,
    val creationDate: String? = null,
    val revisionDate: String? = null
)

@Serializable
data class BwFolder(
    val id: String,
    val name: String
)

@Serializable
data class BitwardenPlainExport(
    val encrypted: Boolean = false,
    val folders: List<BwFolder> = emptyList(),
    val items: List<BwExportItem> = emptyList()
)

// ===================== 加密（密码保护）导出信封 =====================

@Serializable
data class BitwardenEncryptedExport(
    val encrypted: Boolean = true,
    val passwordProtected: Boolean = true,
    val salt: String,
    val kdfType: Int = 0,
    val kdfIterations: Int = 600000,
    val kdfMemory: Int? = null,
    val kdfParallelism: Int? = null,
    val encKeyValidation_DO_NOT_EDIT: String,
    val data: String
)

// ===================== 构建器 =====================

/**
 * 将 Bastion 本地数据（PasswordEntry + SecureItem）转换为 Bitwarden 兼容的明文导出结构，
 * 并支持进一步使用用户导出密码加密为密码保护信封。
 *
 * 加密流程与 bw2keepass 的 encrypt_bitwarden_export 对称：
 *  PBKDF2-SHA256(password, utf8(salt), 600000) -> masterKey
 *  HKDF-Expand(masterKey, "enc"/"mac") -> encKey/macKey
 *  data = AES-256-CBC + HMAC-SHA256(整个明文 JSON)
 */
class BitwardenJsonExporter(
    private val securityManager: SecurityManager,
    private val categories: List<Category>
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    private fun safeDecrypt(value: String): String {
        if (value.isBlank()) return value
        return if (securityManager.looksLikeBastionCiphertext(value)) {
            runCatching { securityManager.decryptData(value) }.getOrDefault(value)
        } else {
            value
        }
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun isoDate(date: Date?): String? = date?.let { isoFormat.format(it) }

    private fun folderIdFor(categoryId: Long?): String? {
        if (categoryId == null) return null
        val cat = categories.firstOrNull { it.id == categoryId } ?: return null
        return cat.bitwardenFolderId ?: "folder-${cat.id}"
    }

    private fun ensureFolder(categoryId: Long?, folders: MutableList<BwFolder>): String? {
        if (categoryId == null) return null
        val cat = categories.firstOrNull { it.id == categoryId } ?: return null
        val fid = cat.bitwardenFolderId ?: "folder-${cat.id}"
        if (folders.none { it.id == fid }) {
            folders += BwFolder(id = fid, name = cat.name)
        }
        return fid
    }

    fun buildPlainExport(
        entries: List<PasswordEntry>,
        items: List<SecureItem>
    ): BitwardenPlainExport {
        val folders = mutableListOf<BwFolder>()
        val exportItems = mutableListOf<BwExportItem>()

        for (entry in entries) {
            if (entry.isDeleted) continue
            val folderId = ensureFolder(entry.categoryId, folders)
            exportItems += mapPasswordEntry(entry, folderId)
        }
        for (item in items) {
            if (item.isDeleted) continue
            val folderId = ensureFolder(item.categoryId, folders)
            mapSecureItem(item, folderId, folders, exportItems)
        }
        return BitwardenPlainExport(folders = folders, items = exportItems)
    }

    fun encryptExport(plain: BitwardenPlainExport, exportPassword: String): BitwardenEncryptedExport {
        val plainJson = json.encodeToString(plain)

        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltField = Base64.encodeToString(saltBytes, Base64.NO_WRAP)

        // PBKDF2 使用 salt 字符串的 UTF-8 字节，与 bw2keepass salt_mode='utf8' 一致
        val masterKey = BitwardenCrypto.deriveMasterKeyPbkdf2(
            password = exportPassword,
            salt = saltField,
            iterations = 600000
        )
        val stretched = BitwardenCrypto.stretchMasterKey(masterKey)

        val dataCipher = BitwardenCrypto.encryptString(plainJson, stretched)
        val validationCipher = BitwardenCrypto.encryptString("Bitwarden", stretched)

        // 清理内存中的密钥材料
        masterKey.fill(0)
        stretched.encKey.fill(0)
        stretched.macKey.fill(0)

        return BitwardenEncryptedExport(
            salt = saltField,
            kdfType = 0,
            kdfIterations = 600000,
            kdfMemory = null,
            kdfParallelism = null,
            encKeyValidation_DO_NOT_EDIT = validationCipher,
            data = dataCipher
        )
    }

    // ===================== 映射：PasswordEntry -> Login =====================

    private fun mapPasswordEntry(entry: PasswordEntry, folderId: String?): BwExportItem {
        val username = entry.username.takeIf { it.isNotBlank() }?.let { safeDecrypt(it) }
        val password = entry.password.takeIf { it.isNotBlank() }?.let { safeDecrypt(it) }
        val notes = entry.notes.takeIf { it.isNotBlank() }?.let { safeDecrypt(it) }
        val totp = entry.authenticatorKey.takeIf { it.isNotBlank() }?.let { safeDecrypt(it) }
        // T1: 多网址对称拆分——与读取侧 parseLoginUris / 上传侧 buildEncryptedLoginUris 一致，
        // 把 entry.website（逗号/中文逗号分隔）拆成多个独立 BwUri，避免导出畸形单 URI。
        val websiteUris = entry.website.takeIf { it.isNotBlank() }
            ?.let { PasswordWebsiteCodec.parse(it) }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.map { BwUri(uri = it) }
            ?: emptyList()
        // T6: 应用绑定对称——把 appPackageName 以 androidapp://<pkg> 形式并入 login.uris，
        // 与 CipherSyncProcessor 导入解析 (androidapp://) 对齐，修复“导出到 Bitwarden 再导回丢失应用绑定”的往返 bug。
        val uris = buildList {
            addAll(websiteUris)
            entry.appPackageName.takeIf { it.isNotBlank() }?.let { pkg ->
                add(BwUri(uri = "androidapp://$pkg"))
            }
        }.takeIf { it.isNotEmpty() }
        val fields = buildPasswordCustomFields(entry)

        return BwExportItem(
            id = entry.bitwardenCipherId ?: uuid(),
            type = 1,
            name = entry.title.ifBlank { "(无标题)" },
            notes = notes,
            favorite = entry.isFavorite,
            folderId = folderId,
            login = BwLogin(
                username = username,
                password = password,
                totp = totp,
                uris = uris
            ),
            fields = fields.takeIf { it.isNotEmpty() },
            creationDate = isoDate(entry.createdAt),
            revisionDate = isoDate(entry.updatedAt)
        )
    }

    private fun buildPasswordCustomFields(entry: PasswordEntry): List<BwField> {
        val fields = mutableListOf<BwField>()
        fun add(name: String, value: String, type: Int = 0) {
            val decrypted = value.takeIf { it.isNotBlank() }?.let { safeDecrypt(it) }
            if (!decrypted.isNullOrBlank()) {
                fields += BwField(name = name, value = decrypted, type = type)
            }
        }
        add("cardholderName", entry.creditCardHolder)
        add("number", entry.creditCardNumber, 1)
        add("expiry", entry.creditCardExpiry)
        add("code", entry.creditCardCVV, 1)
        add("email", entry.email)
        add("phone", entry.phone)
        add("addressLine", entry.addressLine)
        add("city", entry.city)
        add("state", entry.state)
        add("zipCode", entry.zipCode)
        add("country", entry.country)
        // T5: 绑定与扩展元数据走隐藏字段，消除 JSON 导出静默丢数据；
        // 与 REST 上传侧 buildEncryptedPasswordCustomFields 同构，导入时由 CipherSyncProcessor 还原。
        add("bastion_app_package", entry.appPackageName)
        add("bastion_app_name", entry.appName)
        add("bastion_login_type", entry.loginType, 1)
        if (entry.loginType.equals("WIFI", ignoreCase = true)) {
            add("bastion_wifi_data", entry.wifiMetadata, 1)
        }
        if (entry.loginType.equals("SSO", ignoreCase = true)) {
            add("bastion_sso_provider", entry.ssoProvider, 1)
        }
        // passkey 绑定关系由官方 fido2Credentials 承载，导出不再写入 bastion_passkey_bindings；
        // 导入仍兼容读取旧格式（见 CipherSyncProcessor）。
        SshKeyDataCodec.decode(entry.sshKeyData)?.let { ssh ->
            add("bastion_ssh_algorithm", ssh.algorithm, 1)
            add("bastion_ssh_public_key", ssh.publicKeyOpenSsh, 1)
            add("bastion_ssh_private_key", ssh.privateKeyOpenSsh, 1)
            add("bastion_ssh_fingerprint", ssh.fingerprintSha256, 1)
            add("bastion_ssh_comment", ssh.comment, 1)
            add("bastion_ssh_format", ssh.format, 1)
        }
        return fields
    }

    // ===================== 映射：SecureItem -> 各类型 =====================

    private fun mapSecureItem(
        item: SecureItem,
        folderId: String?,
        folders: MutableList<BwFolder>,
        out: MutableList<BwExportItem>
    ) {
        val id = item.bitwardenCipherId ?: uuid()
        val name = item.title.ifBlank { "(无标题)" }
        val notes = item.notes.takeIf { it.isNotBlank() }?.let { safeDecrypt(it) }

        when (item.itemType) {
            ItemType.TOTP -> {
                val login = buildTotpLogin(item)
                out += BwExportItem(
                    id = id, type = 1, name = name, notes = notes,
                    favorite = item.isFavorite, folderId = folderId,
                    login = login,
                    creationDate = isoDate(item.createdAt),
                    revisionDate = isoDate(item.updatedAt)
                )
            }
            ItemType.BANK_CARD -> {
                val (card, fields) = buildCard(item)
                out += BwExportItem(
                    id = id, type = 3, name = name, notes = notes,
                    favorite = item.isFavorite, folderId = folderId,
                    card = card, fields = fields.takeIf { it.isNotEmpty() },
                    creationDate = isoDate(item.createdAt),
                    revisionDate = isoDate(item.updatedAt)
                )
            }
            ItemType.NOTE -> {
                val content = buildNoteContent(item) ?: notes
                out += BwExportItem(
                    id = id, type = 2, name = name, notes = content,
                    favorite = item.isFavorite, folderId = folderId,
                    secureNote = BwSecureNote(type = 0),
                    creationDate = isoDate(item.createdAt),
                    revisionDate = isoDate(item.updatedAt)
                )
            }
            ItemType.DOCUMENT -> {
                val (identity, fields) = buildIdentity(item)
                out += BwExportItem(
                    id = id, type = 4, name = name, notes = notes,
                    favorite = item.isFavorite, folderId = folderId,
                    identity = identity, fields = fields.takeIf { it.isNotEmpty() },
                    creationDate = isoDate(item.createdAt),
                    revisionDate = isoDate(item.updatedAt)
                )
            }
            else -> {
                // 未知/暂不支持类型：作为安全笔记保留，原始 itemData 以隐藏字段存储，避免数据丢失
                val fields = listOf(
                    BwField(name = "bastion_item_type", value = item.itemType.name, type = 0),
                    BwField(name = "bastion_item_data", value = safeDecrypt(item.itemData), type = 1)
                )
                out += BwExportItem(
                    id = id, type = 2, name = name, notes = notes,
                    favorite = item.isFavorite, folderId = folderId,
                    secureNote = BwSecureNote(type = 0),
                    fields = fields,
                    creationDate = isoDate(item.createdAt),
                    revisionDate = isoDate(item.updatedAt)
                )
            }
        }
    }

    private fun buildTotpLogin(item: SecureItem): BwLogin {
        val totpData: TotpData? = TotpDataResolver.parseStoredItemData(
            itemData = item.itemData,
            fallbackIssuer = item.title,
            decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
        )
        val payload = totpData?.let { TotpDataResolver.toBitwardenPayload(item.title, it) }
        val accountName = totpData?.accountName?.takeIf { it.isNotBlank() }
        val uris = totpData?.issuer?.takeIf { it.isNotBlank() }
            ?.let { listOf(BwUri(uri = "otpauth://totp/$it")) }
        return BwLogin(username = accountName, totp = payload, uris = uris)
    }

    private fun buildCard(item: SecureItem): Pair<BwCard, List<BwField>> {
        val cardData = CardWalletDataCodec.parseBankCardData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
        )
        val brandValue = cardData?.brand?.takeIf { it.isNotBlank() }
            ?: cardData?.bankName?.takeIf { it.isNotBlank() }
        val card = BwCard(
            cardholderName = cardData?.cardholderName?.takeIf { it.isNotBlank() },
            brand = brandValue,
            number = cardData?.cardNumber?.takeIf { it.isNotBlank() },
            expMonth = cardData?.expiryMonth?.takeIf { it.isNotBlank() },
            expYear = cardData?.expiryYear?.takeIf { it.isNotBlank() },
            code = cardData?.cvv?.takeIf { it.isNotBlank() }
        )
        val fields = mutableListOf<BwField>()
        if (cardData != null) {
            fun add(name: String, value: String, type: Int = 0) {
                if (value.isNotBlank()) fields += BwField(name = name, value = value, type = type)
            }
            add("Bank Name", cardData.bankName)
            add("Card Type", cardData.cardType.name)
            add("Nickname", cardData.nickname)
            add("Valid From Month", cardData.validFromMonth)
            add("Valid From Year", cardData.validFromYear)
            add("PIN", cardData.pin, 1)
            add("IBAN", cardData.iban, 1)
            add("SWIFT/BIC", cardData.swiftBic)
            add("Routing Number", cardData.routingNumber)
            add("Account Number", cardData.accountNumber, 1)
            add("Branch Code", cardData.branchCode)
            add("Currency", cardData.currency)
            add("Customer Service Phone", cardData.customerServicePhone)
            cardData.customFields.filter { it.isValid() }.forEach { f ->
                fields += BwField(
                    name = f.label,
                    value = f.value,
                    type = when (f.type) {
                        SecureCustomFieldType.HIDDEN -> 1
                        SecureCustomFieldType.BOOLEAN -> 2
                        SecureCustomFieldType.LINKED -> 3
                        else -> 0
                    }
                )
            }
        }
        return card to fields
    }

    private fun buildNoteContent(item: SecureItem): String? {
        val decoded = runCatching {
            json.decodeFromString<NoteData>(safeDecrypt(item.itemData))
        }.getOrNull() ?: runCatching {
            json.decodeFromString<NoteData>(item.itemData)
        }.getOrNull()
        val content = decoded?.content?.takeIf { it.isNotBlank() } ?: return null
        return NoteContentCodec.toExternalReadableContent(content)
    }

    private fun buildIdentity(item: SecureItem): Pair<BwIdentity, List<BwField>> {
        val docData = CardWalletDataCodec.parseDocumentData(
            raw = item.itemData,
            decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
        ) ?: DocumentData(documentType = DocumentType.OTHER, documentNumber = "", fullName = "")

        val identityNumberForLicense = docData.licenseNumber.ifBlank {
            if (docData.documentType == DocumentType.DRIVER_LICENSE) docData.documentNumber else ""
        }
        val identityNumberForPassport = docData.passportNumber.ifBlank {
            if (docData.documentType == DocumentType.PASSPORT) docData.documentNumber else ""
        }
        val identityNumberForSsn = docData.ssn.ifBlank {
            if (docData.documentType in setOf(
                    DocumentType.ID_CARD,
                    DocumentType.SOCIAL_SECURITY,
                    DocumentType.OTHER
                )
            ) {
                docData.documentNumber
            } else {
                ""
            }
        }

        val identity = BwIdentity(
            title = docData.title.takeIf { it.isNotBlank() },
            firstName = docData.firstName.takeIf { it.isNotBlank() },
            middleName = docData.middleName.takeIf { it.isNotBlank() },
            lastName = docData.lastName.takeIf { it.isNotBlank() },
            address1 = docData.address1.takeIf { it.isNotBlank() },
            address2 = docData.address2.takeIf { it.isNotBlank() },
            address3 = docData.address3.takeIf { it.isNotBlank() },
            city = docData.city.takeIf { it.isNotBlank() },
            state = docData.stateProvince.takeIf { it.isNotBlank() },
            postalCode = docData.postalCode.takeIf { it.isNotBlank() },
            country = docData.country.takeIf { it.isNotBlank() },
            company = docData.company.takeIf { it.isNotBlank() },
            email = docData.email.takeIf { it.isNotBlank() },
            phone = docData.phone.takeIf { it.isNotBlank() },
            ssn = identityNumberForSsn.takeIf { it.isNotBlank() },
            username = docData.username.takeIf { it.isNotBlank() },
            passportNumber = identityNumberForPassport.takeIf { it.isNotBlank() },
            licenseNumber = identityNumberForLicense.takeIf { it.isNotBlank() }
        )

        val fields = mutableListOf<BwField>()
        fun add(name: String, value: String) {
            if (value.isNotBlank()) fields += BwField(name = name, value = value)
        }
        add("bastion_document_type", docData.documentType.name)
        add("bastion_issue_date", docData.issuedDate)
        add("bastion_expiry_date", docData.expiryDate)
        add("bastion_issued_by", docData.issuedBy)
        add("bastion_nationality", docData.nationality)
        add("bastion_additional_info", docData.additionalInfo)
        docData.customFields.filter { it.isValid() }.forEach { f ->
            fields += BwField(
                name = f.label,
                value = f.value,
                type = if (f.isProtected()) 1 else 0
            )
        }

        return identity to fields
    }
}
