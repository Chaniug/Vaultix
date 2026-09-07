package com.bastion.app.keepass

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassFolderPathRegressionGuardTest {

    @Test
    fun passwordFallbackCreationUsesEntryGroupPath() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/utils/KeePassKdbxService.kt"
        ).readText()

        val addOrUpdateBody = source.substringAfter("suspend fun addOrUpdatePasswordEntries(")
            .substringBefore("suspend fun updatePasswordEntry(")
        val updateBody = source.substringAfter("suspend fun updatePasswordEntry(")
            .substringBefore("suspend fun addPasswordEntry(")

        assertTrue(
            "addOrUpdatePasswordEntries must add new password entries to entry.keepassGroupPath, not root.",
            addOrUpdateBody.contains(Regex("groupPath\\s*=\\s*entry\\.keepassGroupPath"))
        )
        assertTrue(
            "updatePasswordEntry fallback creation must add new password entries to entry.keepassGroupPath.",
            updateBody.contains(Regex("groupPath\\s*=\\s*entry\\.keepassGroupPath"))
        )
        assertFalse(
            "Password creation fallback must not append directly to the root group.",
            addOrUpdateBody.contains(Regex("entries\\s*\\+\\s*newEntry"))
        )
        assertFalse(
            "Password update fallback must not append directly to the root group.",
            updateBody.contains(Regex("entries\\s*\\+\\s*newEntry"))
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }

        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }
}
