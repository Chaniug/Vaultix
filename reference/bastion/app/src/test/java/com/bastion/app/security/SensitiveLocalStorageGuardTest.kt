package com.bastion.app.security

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveLocalStorageGuardTest {

    @Test
    fun noteDrafts_useProtectedStorageWithLegacyMigration() {
        val source = projectFile("app/src/main/java/com/bastion/app/notes/domain/NoteDraftStore.kt")

        assertTrue(source.contains("SecurityManager(context.applicationContext)"))
        assertTrue(source.contains("securityManager.putProtectedString(key(noteId, \"content\"), content)"))
        assertTrue(source.contains("securityManager.getProtectedString(key(noteId, \"content\"))"))
        assertTrue(source.contains("securityManager.removeProtectedString(key(noteId, \"content\"))"))
        assertFalse(source.contains(".putString(key(noteId, \"content\"), content)"))
    }

    @Test
    fun passwordGenerationHistory_isStoredAsEncryptedPayload() {
        val source = projectFile("app/src/main/java/com/bastion/app/data/PasswordHistoryManager.kt")

        assertTrue(source.contains("migrateLegacyHistoryIfNeeded"))
        assertTrue(source.contains("securityManager.encryptDataLegacyCompat(json.encodeToString(history))"))
        assertTrue(source.contains("securityManager.decryptData(raw)"))
        assertFalse(source.contains("preferences[HISTORY_KEY] = json.encodeToString"))
    }

    @Test
    fun commonAccountSensitiveFields_areStoredAsEncryptedPayloads() {
        val source = projectFile("app/src/main/java/com/bastion/app/data/CommonAccountPreferences.kt")

        assertTrue(source.contains("migrateSensitivePreferencesIfNeeded"))
        assertTrue(source.contains("protectedPreferenceValue(CardWalletDataCodec.encodeBillingAddress(address))"))
        assertTrue(source.contains("protectedPreferenceValue(encodeTemplates"))
        assertTrue(source.contains("securityManager.encryptDataLegacyCompat(it)"))
        assertFalse(source.contains("preferences[KEY_TEMPLATES_JSON] = encodeTemplates"))
        assertFalse(source.contains("preferences[KEY_BILLING_ADDRESS_JSON] = CardWalletDataCodec.encodeBillingAddress(address)"))
    }

    @Test
    fun oneDriveBackupConfig_usesProtectedStorageWithLegacyMigration() {
        val source = projectFile("app/src/main/java/com/bastion/app/utils/OneDriveBackupHelper.kt")

        assertTrue(source.contains("SecurityManager(appContext)"))
        assertTrue(source.contains("migrateLegacyConfigIfNeeded"))
        assertTrue(source.contains("securityManager.putProtectedString(SECURE_KEY_USERNAME"))
        assertTrue(source.contains("securityManager.getProtectedString(SECURE_KEY_USERNAME)"))
        assertTrue(source.contains("securityManager.removeProtectedString(SECURE_KEY_USERNAME)"))
        assertFalse(source.contains(".putString(KEY_USERNAME, session.username)"))
        assertFalse(source.contains(".putString(KEY_FOLDER_PATH, normalizedFolderPath)"))
    }

    @Test
    fun webDavBackoffPersistence_hashesHostKeys() {
        val source = projectFile("app/src/main/java/com/bastion/app/webdav/WebDavBackoffState.kt")

        assertTrue(source.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(source.contains("private fun storageKey(host: String)"))
        assertTrue(source.contains("readState(p, storageKey(host))"))
        assertTrue(source.contains("private fun legacyStorageKey(host: String)"))
        assertFalse(source.contains(".putString(KEY_PREFIX + host + KEY_RL_WINDOW"))
        assertFalse(source.contains(".putLong(KEY_PREFIX + host + KEY_BLOCK_UNTIL"))
    }

    @Test
    fun importAndBitwardenSyncLogs_doNotExposeSensitiveRawValues() {
        val importManager = projectFile("app/src/main/java/com/bastion/app/util/DataExportImportManager.kt")
        val cipherSync = projectFile("app/src/main/java/com/bastion/app/bitwarden/service/CipherSyncProcessor.kt")

        // 只容忍冒号/等号前后的空白与全半角冒号，敏感变量锚点 ${...} 保持原样，确保"不打印明文"语义不丢
        assertFalse(importManager.contains(Regex("第一行\\s*[:：]\\s*\\$\\{firstLine\\}")))
        assertFalse(importManager.contains(Regex("内容\\s*[:：]\\s*\\$\\{fields\\}")))
        assertFalse(importManager.contains(Regex("读取第\\$\\{lineCount\\}行\\s*[:：]\\s*\\$\\{currentLine\\}")))
        assertFalse(importManager.contains(Regex("解析CSV行失败\\s*[:：]\\s*\\$\\{line\\}")))
        assertFalse(cipherSync.contains("SSH_FIELD_DUMP"))
        assertFalse(cipherSync.contains("SSH_RESOLVE"))
        assertFalse(cipherSync.contains("resolvedPrivateKey.take"))
    }

    @Test
    fun importAndMediaLogs_doNotExposeTitlesUrisOrLocalPaths() {
        val importViewModel = projectFile("app/src/main/java/com/bastion/app/viewmodel/DataExportImportViewModel.kt")
        val imageManager = projectFile("app/src/main/java/com/bastion/app/util/ImageManager.kt")
        val noteScreen = projectFile("app/src/main/java/com/bastion/app/ui/screens/AddEditNoteScreen.kt")
        val dualPhotoPicker = projectFile("app/src/main/java/com/bastion/app/ui/components/DualPhotoPicker.kt")
        val keepassViewModel = projectFile("app/src/main/java/com/bastion/app/ui/screens/KeePassKdbxViewModel.kt")
        val accessibilityService = projectFile("app/src/main/java/com/bastion/app/service/BastionAccessibilityService.kt")
        val autofillPreferences = projectFile("app/src/main/java/com/bastion/app/autofill_ng/AutofillPreferences.kt")
        val autofillPicker = projectFile("app/src/main/java/com/bastion/app/autofill_ng/AutofillPickerActivityV2.kt")
        val oneDriveBackup = projectFile("app/src/main/java/com/bastion/app/utils/OneDriveBackupHelper.kt")
        val autoBackupWorker = projectFile("app/src/main/java/com/bastion/app/workers/AutoBackupWorker.kt")
        val webDavBackupScreen = projectFile("app/src/main/java/com/bastion/app/ui/screens/WebDavBackupScreen.kt")
        val passkeyRepository = projectFile("app/src/main/java/com/bastion/app/repository/PasskeyRepository.kt")
        val passkeyCreate = projectFile("app/src/main/java/com/bastion/app/passkey/PasskeyCreateActivity.kt")
        val passkeyAuth = projectFile("app/src/main/java/com/bastion/app/passkey/PasskeyAuthActivity.kt")
        val cipherSync = projectFile("app/src/main/java/com/bastion/app/bitwarden/service/CipherSyncProcessor.kt")
        val operationLogger = projectFile("app/src/main/java/com/bastion/app/utils/OperationLogger.kt")

        // 只容忍分隔符(冒号全/半角、等号前后空白)与空白，敏感变量锚点 ${...} 保持原样，
        // 确保"日志不打印明文"语义不丢（正则是对原精确串的超集，不削弱现有信号）
        assertFalse(importViewModel.contains(Regex("成功插入到PasswordEntry表\\s*[:：]\\s*\\$\\{exportItem\\.title\\}")))
        assertFalse(importViewModel.contains(Regex("跳过重复条目\\s*[:：]\\s*\\$\\{aegisEntry\\.name\\}")))
        assertFalse(imageManager.contains(Regex("uri\\s*=\\s*\\$\\{uri\\}")))
        assertFalse(imageManager.contains(Regex("path\\s*=\\s*\\$\\{file\\.absolutePath\\}")))
        assertFalse(noteScreen.contains(Regex("tempPath\\s*=\\s*\\$\\{tempPath\\}")))
        assertFalse(noteScreen.contains(Regex("uri\\s*=\\s*\\$\\{tempUri\\}")))
        assertFalse(dualPhotoPicker.contains(Regex("tempPath\\s*=\\s*\\$\\{tempPath\\}")))
        assertFalse(dualPhotoPicker.contains(Regex("uri\\s*=\\s*\\$\\{tempUri\\}")))
        assertFalse(keepassViewModel.contains(Regex("Starting local KDBX import from uri\\s*=\\s*\\$\\{sourceUri\\}")))
        assertFalse(keepassViewModel.contains(Regex("Failed to parse otpauth URI\\s*[:：]\\s*\\$\\{uri\\}")))
        assertFalse(accessibilityService.contains(Regex("url\\s*=\\s*\\$\\{url\\}")))
        assertFalse(autofillPreferences.contains(Regex("id\\s*=\\s*\\$\\{normalized\\},\\s*passwordId\\s*=\\s*\\$\\{passwordId\\}")))
        assertFalse(autofillPicker.contains(Regex("app\\s*=\\s*\\$\\{applicationId\\},\\s*web\\s*=\\s*\\$\\{webDomain\\}")))
        assertFalse(oneDriveBackup.contains(Regex("folder\\s*=\\s*\\$\\{config\\.folderPath\\}")))
        assertFalse(oneDriveBackup.contains(Regex("target\\s*=\\s*\\$\\{targetName\\}")))
        assertFalse(autoBackupWorker.contains(Regex("无法解密密码\\s*\\$\\{entry\\.title\\}")))
        assertFalse(webDavBackupScreen.contains(Regex("无法解密密码\\s*\\$\\{entry\\.title\\}")))
        assertFalse(webDavBackupScreen.contains("entry.website.ifBlank { entry.username }"))
        assertFalse(passkeyRepository.contains(Regex("\\[\\$\\{action\\}\\]\\s*\\$\\{details\\}")))
        assertFalse(passkeyRepository.contains(Regex("Keystore\\s*[:：]\\s*\\$\\{keyAlias\\}")))
        assertFalse(passkeyCreate.contains(Regex("Passkey created successfully\\s*[:：]\\s*\\$\\{credentialIdB64\\}")))
        assertFalse(passkeyAuth.contains(Regex("Passkey not found\\s*[:：]\\s*\\$\\{credentialId\\}")))
        assertFalse(passkeyAuth.contains(Regex("Authentication successful for\\s*[:：]\\s*\\$\\{passkey\\.credentialId\\}")))
        assertFalse(cipherSync.contains(Regex("title\\s*=\\s*\\$\\{name\\}")))
        assertFalse(operationLogger.contains(Regex("for\\s*\\$\\{itemType\\}\\s*[:：]\\s*\\$\\{itemTitle\\}")))
    }

    @Test
    fun passkeyPrivateKeys_areProtectedOutsideRoom() {
        val passkeyCreate = projectFile("app/src/main/java/com/bastion/app/passkey/PasskeyCreateActivity.kt")
        val passkeyAuth = projectFile("app/src/main/java/com/bastion/app/passkey/PasskeyAuthActivity.kt")
        val privateKeyStore = projectFile("app/src/main/java/com/bastion/app/passkey/PasskeyPrivateKeyStore.kt")
        val repository = projectFile("app/src/main/java/com/bastion/app/repository/PasskeyRepository.kt")

        assertTrue(privateKeyStore.contains("putProtectedString(storageKey, pkcs8Base64)"))
        assertTrue(privateKeyStore.contains("REF_PREFIX + storageKey"))
        assertTrue(passkeyCreate.contains("PasskeyPrivateKeyStore.protectForStorage"))
        assertFalse(passkeyCreate.contains("privateKeyAlias = privateKeyB64"))
        assertTrue(passkeyAuth.contains("PasskeyPrivateKeyStore.resolve(applicationContext, privateKeyData)"))
        assertTrue(repository.contains("protectPlaintextPrivateKeys"))
        assertTrue(repository.contains("protectPrivateKeyForRoom"))
    }

    @Test
    fun operationLogs_redactSensitivePayloadsBeforePersistence() {
        val operationLogger = projectFile("app/src/main/java/com/bastion/app/utils/OperationLogger.kt")
        val database = projectFile("app/src/main/java/com/bastion/app/data/PasswordDatabase.kt")

        assertTrue(operationLogger.contains("sanitizeChanges(itemType, changes)"))
        assertTrue(operationLogger.contains("sanitizeItemTitle(itemType, itemTitle, itemId)"))
        assertTrue(operationLogger.contains("requiresSensitiveLogRedaction"))
        assertTrue(operationLogger.contains("\"<redacted>\""))
        assertTrue(database.contains("MIGRATION_68_69"))
        assertTrue(database.contains("UPDATE operation_logs"))
        assertTrue(database.contains("changesJson = ''"))
    }

    private fun projectFile(relativePath: String): String {
        val start = Paths.get("").toAbsolutePath()
        var cursor = start
        while (cursor.parent != null) {
            val candidate = cursor.resolve(relativePath).toFile()
            if (candidate.exists()) {
                return candidate.readText()
            }
            cursor = cursor.parent
        }
        error("Project file not found from $start: $relativePath")
    }
}
