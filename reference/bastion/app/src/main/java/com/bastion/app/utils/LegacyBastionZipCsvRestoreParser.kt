package com.bastion.app.utils

import com.bastion.app.util.DataExportImportManager
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

internal enum class LegacyBastionSecureCsvRole {
    GENERIC_SECURE,
    NOTES_ONLY,
    TOTP_ONLY,
    CARDS_DOCS_ONLY
}

internal data class LegacyBastionCsvParseResult(
    val items: List<DataExportImportManager.ExportItem>,
    val warnings: List<String>
)

internal object LegacyBastionZipCsvRestoreParser {
    fun parseSecureItems(
        file: File,
        role: LegacyBastionSecureCsvRole
    ): LegacyBastionCsvParseResult {
        val items = mutableListOf<DataExportImportManager.ExportItem>()
        val warnings = mutableListOf<String>()

        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            var recordIndex = 0
            var firstRecord = readCsvRecord(reader) ?: return LegacyBastionCsvParseResult(emptyList(), emptyList())
            firstRecord = firstRecord.removePrefix("\uFEFF")

            var currentRecord: String? = firstRecord
            var hasConsumedHeader = false
            while (currentRecord != null) {
                recordIndex++
                val fields = parseCsvLine(currentRecord)
                if (!hasConsumedHeader && isAppExportHeader(fields)) {
                    hasConsumedHeader = true
                    currentRecord = readCsvRecord(reader)
                    continue
                }
                hasConsumedHeader = true

                if (fields.size < 9) {
                    warnings += "${file.name} 第${recordIndex}行字段不足，已跳过"
                    currentRecord = readCsvRecord(reader)
                    continue
                }

                val baseItem = DataExportImportManager.ExportItem(
                    id = fields[0].toLongOrNull() ?: 0,
                    itemType = fields[1],
                    title = fields[2],
                    itemData = fields[3],
                    notes = fields[4],
                    isFavorite = fields[5].toBoolean(),
                    imagePaths = fields[6],
                    createdAt = fields[7].toLongOrNull() ?: System.currentTimeMillis(),
                    updatedAt = fields[8].toLongOrNull() ?: System.currentTimeMillis(),
                    categoryId = fields.getOrNull(9)?.toLongOrNull(),
                    keepassDatabaseId = fields.getOrNull(10)?.toLongOrNull(),
                    keepassGroupPath = fields.getOrNull(11)?.takeIf { it.isNotBlank() },
                    bitwardenVaultId = fields.getOrNull(12)?.toLongOrNull(),
                    bitwardenFolderId = fields.getOrNull(13)?.takeIf { it.isNotBlank() }
                )

                val normalizedItem = when (role) {
                    LegacyBastionSecureCsvRole.NOTES_ONLY -> {
                        baseItem.copy(itemType = "NOTE")
                    }

                    LegacyBastionSecureCsvRole.TOTP_ONLY -> {
                        baseItem.copy(itemType = "TOTP")
                    }

                    LegacyBastionSecureCsvRole.CARDS_DOCS_ONLY -> {
                        val resolved = SecureItemRestoreTypeResolver.resolve(
                            rawType = baseItem.itemType,
                            itemData = baseItem.itemData,
                            sourceFileName = file.name
                        )
                        if (resolved == com.bastion.app.data.ItemType.BANK_CARD ||
                            resolved == com.bastion.app.data.ItemType.DOCUMENT
                        ) {
                            baseItem.copy(itemType = resolved.name)
                        } else {
                            warnings += "${file.name} 第${recordIndex}行无法识别为卡片或证件，已跳过"
                            null
                        }
                    }

                    LegacyBastionSecureCsvRole.GENERIC_SECURE -> {
                        val resolved = SecureItemRestoreTypeResolver.resolve(
                            rawType = baseItem.itemType,
                            itemData = baseItem.itemData,
                            sourceFileName = file.name
                        )
                        if (resolved != null) {
                            baseItem.copy(itemType = resolved.name)
                        } else {
                            baseItem
                        }
                    }
                }

                if (normalizedItem != null) {
                    items += normalizedItem
                }

                currentRecord = readCsvRecord(reader)
            }
        }

        return LegacyBastionCsvParseResult(items = items, warnings = warnings)
    }

    private fun isAppExportHeader(fields: List<String>): Boolean {
        if (fields.size < 4) return false
        return fields[0].equals("ID", ignoreCase = true) &&
            fields[1].equals("Type", ignoreCase = true) &&
            fields[2].equals("Title", ignoreCase = true) &&
            fields[3].equals("Data", ignoreCase = true)
    }

    private fun readCsvRecord(reader: BufferedReader): String? {
        val builder = StringBuilder()
        var line = reader.readLine() ?: return null
        builder.append(line)
        var inQuotes = hasUnclosedQuotes(builder.toString())

        while (inQuotes) {
            line = reader.readLine() ?: break
            builder.append('\n').append(line)
            inQuotes = hasUnclosedQuotes(builder.toString())
        }

        return builder.toString()
    }

    private fun hasUnclosedQuotes(text: String): Boolean {
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && inQuotes && index + 1 < text.length && text[index + 1] == '"' -> index++
                char == '"' -> inQuotes = !inQuotes
            }
            index++
        }
        return inQuotes
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }

        fields += current.toString().trim()
        return fields
    }
}
