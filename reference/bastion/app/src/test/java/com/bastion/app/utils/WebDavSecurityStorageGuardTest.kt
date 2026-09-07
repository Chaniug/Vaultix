package com.bastion.app.utils

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSecurityStorageGuardTest {

    @Test
    fun webDavHelper_doesNotPersistSensitiveConfigInPlainSharedPreferences() {
        val source = projectFile("app/src/main/java/com/bastion/app/utils/WebDavHelper.kt")

        // 正则仅容忍空白排版；语义锚点(putProtectedString / SECURE_KEY_*)保持原样
        assertFalse(source.contains(Regex("putString\\s*\\(\\s*KEY_PASSWORD,\\s*password\\s*\\)")))
        assertFalse(source.contains(Regex("putString\\s*\\(\\s*KEY_ENCRYPTION_PASSWORD,\\s*encryptionPassword\\s*\\)")))
        assertTrue(source.contains(Regex("securityManager\\.putProtectedString\\s*\\(\\s*SECURE_KEY_PASSWORD")))
        assertTrue(source.contains("securityManager.putProtectedString("))
        assertTrue(source.contains("SECURE_KEY_ENCRYPTION_PASSWORD"))
        assertTrue(source.contains("migrateLegacyConfigIfNeeded"))
    }

    @Test
    fun sensitiveLogs_doNotPrintUsernameOrUserEmail() {
        val webDavHelper = projectFile("app/src/main/java/com/bastion/app/utils/WebDavHelper.kt")
        val autofillAuth = projectFile("app/src/main/java/com/bastion/app/autofill_ng/AutofillAuthenticationActivity.kt")
        val securityManager = projectFile("app/src/main/java/com/bastion/app/security/SecurityManager.kt")

        // 日志不打印明文：敏感变量锚点 ${...} 保留，容忍冒号/等号前后空白与全半角冒号
        assertFalse(webDavHelper.contains(Regex("Username:\\s*\\$\\{username\\}")))
        assertFalse(webDavHelper.contains(Regex("user\\s*=\\s*\\$\\{username\\}")))
        assertFalse(autofillAuth.contains(Regex("Username:\\s*\\$\\{usernameValue\\}")))
        assertFalse(securityManager.contains(Regex("Bitwarden\\s+credential\\s+saved\\s+for\\s+user[:：]?")))
    }

    @Test
    fun backupCreation_doesNotUseHardcodedFallbackKeyForNewSensitiveExports() {
        val source = projectFile("app/src/main/java/com/bastion/app/utils/WebDavHelper.kt")

        assertTrue(source.contains(Regex("val\\s+backupEncryptPassword\\s*=\\s*currentBackupEncryptionPassword\\s*\\(\\s*\\)")))
        assertTrue(source.contains("未启用备份加密，已跳过 WebDAV 连接凭证和 Bitwarden Vault 密钥材料"))
        assertFalse(source.contains(Regex("val\\s+backupEncryptPassword\\s*=\\s*if\\s*\\(\\s*enableEncryption\\s*&&\\s*encryptionPassword\\.isNotEmpty\\s*\\(\\s*\\)\\s*\\)")))
    }

    @Test
    fun restoreCompatibility_keepsLegacyFallbackOnlyInRestoreHelpers() {
        val source = projectFile("app/src/main/java/com/bastion/app/utils/WebDavHelper.kt")

        assertTrue(source.contains("private const val LEGACY_WEBDAV_BACKUP_FALLBACK_KEY = \"Bastion_WebDAV_Config_Key\""))
        assertTrue(source.contains(Regex("private\\s+fun\\s+decryptBackupValueWithLegacyFallback")))
        assertTrue(source.contains(Regex("if\\s*\\(\\s*decryptPassword\\.isNullOrBlank\\s*\\(\\s*\\)\\s*\\)")))
    }

    @Test
    fun webDavHelper_defaultsMissingSchemeToHttps() {
        val source = projectFile("app/src/main/java/com/bastion/app/utils/WebDavHelper.kt")

        assertTrue(source.contains("\"https://${'$'}trimmed\""))
        assertFalse(source.contains("\"http://${'$'}trimmed\""))
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
