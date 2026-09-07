package com.bastion.app.utils

import android.content.Context
import com.bastion.app.bitwarden.export.BitwardenJsonExporter
import com.bastion.app.bitwarden.export.BitwardenPlainExport
import com.bastion.app.bitwarden.export.BwCard
import com.bastion.app.bitwarden.export.BwExportItem
import com.bastion.app.bitwarden.export.BwLogin
import com.bastion.app.bitwarden.import.BitwardenJsonImport
import com.bastion.app.data.BastionDatabaseFormat
import com.bastion.app.data.ItemType
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.SecureItem
import com.bastion.app.data.model.BankCardData
import com.bastion.app.data.model.CardWalletDataCodec
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Date

/**
 * Bastion 工具多格式存储编解码器。
 *
 * 与 kdbx 共用同一套「来源 / 工作副本 / 远端同步 / 上传 Worker」抽象，仅在
 * 「文件字节 <-> Room 条目」这一层按 [BastionDatabaseFormat] 分派：
 * - KDBX：由 KeePassKdbxService 既有 kotpass 链路处理（本类不负责）。
 * - JSON：复用 Bitwarden 兼容的明文/加密编解码（与 Bastion 的 Bitwarden 导入导出互通）。
 * - CSV：扁平明文（仅账号/密码/网址/备注/TOTP/笔记），作为常驻存储的最小子集。
 *
 * 所有「导入」都会把条目镜像进 Room 并打上 keepassDatabaseId 归属标记，
 * 因此 Vault 中按 keepassDatabaseId 过滤即可正常展示。
 */
object BastionDatabaseFormatCodec {

    private const val TAG = "BastionFormatCodec"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** 生成空数据库文件内容（新建数据库时调用）。KDBX 由 KeePass 流程处理。 */
    fun createInitialContent(
        format: BastionDatabaseFormat,
        name: String,
        password: String?,
        securityManager: SecurityManager
    ): ByteArray {
        return when (format) {
            BastionDatabaseFormat.KDBX -> throw IllegalStateException("KDBX 初始内容由 KeePass 流程生成")
            BastionDatabaseFormat.JSON -> {
                val plain = BitwardenPlainExport(folders = emptyList(), items = emptyList())
                if (password.isNullOrBlank()) {
                    json.encodeToString(plain).toByteArray(Charsets.UTF_8)
                } else {
                    val exporter = BitwardenJsonExporter(securityManager, emptyList())
                    json.encodeToString(exporter.encryptExport(plain, password)).toByteArray(Charsets.UTF_8)
                }
            }
            BastionDatabaseFormat.CSV -> {
                "# Bastion CSV 数据库\n".toByteArray(Charsets.UTF_8)
            }
        }
    }

    /** 文件扩展名 */
    fun fileExtension(format: BastionDatabaseFormat): String = when (format) {
        BastionDatabaseFormat.KDBX -> "kdbx"
        BastionDatabaseFormat.JSON -> "json"
        BastionDatabaseFormat.CSV -> "csv"
    }

    /**
     * 解析数据库文件字节并镜像进 Room（带 keepassDatabaseId 归属），
     * 返回导入条目数。
     */
    suspend fun importContent(
        format: BastionDatabaseFormat,
        bytes: ByteArray,
        password: String?,
        keepassDatabaseId: Long,
        context: Context
    ): Int = withContext(Dispatchers.IO) {
        val db = PasswordDatabase.getDatabase(context)
        val passwordDao = db.passwordEntryDao()
        val secureItemDao = db.secureItemDao()

        when (format) {
            BastionDatabaseFormat.KDBX -> {
                throw IllegalStateException("KDBX 由 KeePass 流程导入")
            }
            BastionDatabaseFormat.JSON -> {
                val content = bytes.toString(Charsets.UTF_8)
                val plain = if (password.isNullOrBlank()) {
                    if (BitwardenJsonImport.isEncryptedExport(content)) {
                        throw IllegalStateException("该 JSON 数据库已加密，请提供密码")
                    }
                    BitwardenJsonImport.parsePlain(content)
                } else {
                    BitwardenJsonImport.decryptAndParse(content, password)
                }
                var count = 0
                for (item in plain.items) {
                    val title = item.name.ifBlank { "(无标题)" }
                    when (item.type) {
                        1 -> { // login
                            val login = item.login ?: BwLogin()
                            val website = login.uris?.firstOrNull()?.uri ?: ""
                            val username = login.username ?: ""
                            val pw = login.password ?: ""
                            val itemData = buildString {
                                append("website:").append(website).append(";")
                                append("username:").append(username).append(";")
                                append("password:").append(pw).append(";")
                                append("email:").append("").append(";")
                                append("phone:").append("").append(";")
                                append("notes:").append(item.notes ?: "")
                            }
                            passwordDao.insertPasswordEntry(
                                PasswordEntry(
                                    id = 0,
                                    title = title,
                                    website = website,
                                    username = username,
                                    password = pw,
                                    notes = item.notes ?: "",
                                    email = "",
                                    phone = "",
                                    categoryId = null,
                                    isFavorite = item.favorite,
                                    createdAt = Date(),
                                    updatedAt = Date(),
                                    keepassDatabaseId = keepassDatabaseId,
                                    keepassGroupPath = null,
                                    authenticatorKey = login.totp ?: "",
                                    bitwardenVaultId = null,
                                    bitwardenFolderId = item.folderId
                                )
                            )
                            count++
                        }
                        else -> { // note / card / identity 等统一以 SecureItem 保存
                            val itemData = when (item.type) {
                                3 -> { // card
                                    val card = item.card ?: BwCard()
                                    val bankCard = BankCardData(
                                        cardNumber = card.number ?: "",
                                        cardholderName = card.cardholderName ?: "",
                                        expiryMonth = card.expMonth ?: "",
                                        expiryYear = card.expYear ?: "",
                                        cvv = card.code ?: "",
                                        brand = card.brand ?: "",
                                        customFields = emptyList()
                                    )
                                    CardWalletDataCodec.encodeBankCardData(bankCard)
                                }
                                2 -> { // secure note
                                    val (encoded, _) = NoteContentCodec.encode(content = item.notes ?: item.name)
                                    encoded
                                }
                                else -> { // identity / 未知 -> 笔记
                                    val (encoded, _) = NoteContentCodec.encode(content = item.notes ?: item.name)
                                    encoded
                                }
                            }
                            secureItemDao.insertItem(
                                SecureItem(
                                    id = 0,
                                    itemType = if (item.type == 3) ItemType.BANK_CARD else ItemType.NOTE,
                                    title = title,
                                    itemData = itemData,
                                    notes = item.notes ?: "",
                                    isFavorite = item.favorite,
                                    imagePaths = "",
                                    createdAt = Date(),
                                    updatedAt = Date(),
                                    categoryId = null,
                                    keepassDatabaseId = keepassDatabaseId,
                                    keepassGroupPath = null,
                                    bitwardenVaultId = null,
                                    bitwardenFolderId = item.folderId
                                )
                            )
                            count++
                        }
                    }
                }
                count
            }
            BastionDatabaseFormat.CSV -> {
                val content = bytes.toString(Charsets.UTF_8)
                parseCsvContent(content, keepassDatabaseId, passwordDao, secureItemDao)
            }
        }
    }

    /** 把 Room 中该 keepassDatabaseId 下的条目序列化回数据库文件字节。 */
    suspend fun exportContent(
        format: BastionDatabaseFormat,
        keepassDatabaseId: Long,
        password: String?,
        context: Context,
        securityManager: SecurityManager
    ): ByteArray = withContext(Dispatchers.IO) {
        val db = PasswordDatabase.getDatabase(context)
        val passwordDao = db.passwordEntryDao()
        val secureItemDao = db.secureItemDao()
        val categoryDao = db.categoryDao()

        when (format) {
            BastionDatabaseFormat.KDBX -> throw IllegalStateException("KDBX 由 KeePass 流程导出")
            BastionDatabaseFormat.JSON -> {
                val entries = passwordDao.getPasswordEntriesByKeePassDatabaseSync(keepassDatabaseId)
                val items = secureItemDao.getAllItems().first().filter { it.keepassDatabaseId == keepassDatabaseId }
                val categories = categoryDao.getAllCategories().first()
                val plain = BitwardenJsonExporter(securityManager, categories)
                    .buildPlainExport(entries, items)
                if (password.isNullOrBlank()) {
                    json.encodeToString(plain).toByteArray(Charsets.UTF_8)
                } else {
                    val exporter = BitwardenJsonExporter(securityManager, categories)
                    json.encodeToString(exporter.encryptExport(plain, password)).toByteArray(Charsets.UTF_8)
                }
            }
            BastionDatabaseFormat.CSV -> {
                val entries = passwordDao.getPasswordEntriesByKeePassDatabaseSync(keepassDatabaseId)
                val sb = StringBuilder()
                sb.append("type,title,website,username,password,notes,totp\n")
                for (e in entries) {
                    sb.append("password,")
                    sb.append(csvField(e.title)).append(",")
                    sb.append(csvField(e.website)).append(",")
                    sb.append(csvField(e.username)).append(",")
                    sb.append(csvField(e.password)).append(",")
                    sb.append(csvField(e.notes)).append(",")
                    sb.append(csvField(e.authenticatorKey)).append("\n")
                }
                sb.toString().toByteArray(Charsets.UTF_8)
            }
        }
    }

    // ===================== CSV 解析 =====================

    private suspend fun parseCsvContent(
        content: String,
        keepassDatabaseId: Long,
        passwordDao: com.bastion.app.data.PasswordEntryDao,
        secureItemDao: com.bastion.app.data.SecureItemDao
    ): Int {
        val lines = content.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return 0
        val header = parseCsvLine(lines.first())
        val typeIdx = header.indexOfFirst { it.equals("type", ignoreCase = true) }
        val titleIdx = header.indexOfFirst { it.equals("title", ignoreCase = true) }
        val websiteIdx = header.indexOfFirst { it.equals("website", ignoreCase = true) }
        val userIdx = header.indexOfFirst { it.equals("username", ignoreCase = true) }
        val pwIdx = header.indexOfFirst { it.equals("password", ignoreCase = true) }
        val notesIdx = header.indexOfFirst { it.equals("notes", ignoreCase = true) }
        val totpIdx = header.indexOfFirst { it.equals("totp", ignoreCase = true) }
        var count = 0
        for (i in 1 until lines.size) {
            val cols = parseCsvLine(lines[i])
            val type = cols.getOrNull(typeIdx).orEmpty().lowercase()
            val title = cols.getOrNull(titleIdx).orEmpty().ifBlank { "(无标题)" }
            val website = cols.getOrNull(websiteIdx).orEmpty()
            val username = cols.getOrNull(userIdx).orEmpty()
            val pw = cols.getOrNull(pwIdx).orEmpty()
            val notes = cols.getOrNull(notesIdx).orEmpty()
            val totp = cols.getOrNull(totpIdx).orEmpty()
            if (type == "note") {
                val (encoded, _) = NoteContentCodec.encode(content = notes.ifBlank { title })
                secureItemDao.insertItem(
                    SecureItem(
                        id = 0,
                        itemType = ItemType.NOTE,
                        title = title,
                        itemData = encoded,
                        notes = notes,
                        isFavorite = false,
                        imagePaths = "",
                        createdAt = Date(),
                        updatedAt = Date(),
                        categoryId = null,
                        keepassDatabaseId = keepassDatabaseId,
                        keepassGroupPath = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null
                    )
                )
            } else {
                val itemData = buildString {
                    append("website:").append(website).append(";")
                    append("username:").append(username).append(";")
                    append("password:").append(pw).append(";")
                    append("email:").append("").append(";")
                    append("phone:").append("").append(";")
                    append("notes:").append(notes)
                }
                passwordDao.insertPasswordEntry(
                    PasswordEntry(
                        id = 0,
                        title = title,
                        website = website,
                        username = username,
                        password = pw,
                        notes = notes,
                        email = "",
                        phone = "",
                        categoryId = null,
                        isFavorite = false,
                        createdAt = Date(),
                        updatedAt = Date(),
                        keepassDatabaseId = keepassDatabaseId,
                        keepassGroupPath = null,
                        authenticatorKey = totp,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null
                    )
                )
            }
            count++
        }
        return count
    }

    private fun csvField(value: String): String {
        val v = value.replace("\"", "\"\"")
        return if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"$v\"" else v
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(current.toString().trim()); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString().trim())
        return fields
    }
}
