package com.bastion.app.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportDocumentSpec(
    val fileName: String,
    val mimeType: String
)

fun exportDocumentSpec(
    selectedOption: ExportOption,
    currentTimeMillis: Long = System.currentTimeMillis()
): ExportDocumentSpec {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(currentTimeMillis))
    val fileName = when (selectedOption) {
        ExportOption.ZIP_BACKUP -> "bastion_backup_${timestamp}.zip"
        ExportOption.KDBX -> "bastion_${timestamp}.kdbx"
        ExportOption.BITWARDEN_JSON -> "bastion_bitwarden_${timestamp}.json"
        ExportOption.BITWARDEN_ENCRYPTED_JSON -> "bastion_bitwarden_encrypted_${timestamp}.json"
    }

    val mimeType = when {
        fileName.endsWith(".zip") -> "application/zip"
        fileName.endsWith(".kdbx") -> "application/octet-stream"
        fileName.endsWith(".json") -> "application/json"
        else -> "*/*"
    }

    return ExportDocumentSpec(fileName, mimeType)
}
