package com.bastion.app.autofill_ng

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillInlineClickRegressionGuardTest {

    @Test
    fun directInlineSuggestionsUseRealAuthenticationCallbackInsteadOfNoopIntent() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/builder/FillResponseBuilderNg.kt"
        ).readText()
        val cipherDatasetBody = source.substringAfter("private fun buildCipherDataset(")
            .substringBefore("private fun buildStrongPasswordSuggestionDataset(")

        // 原先单个 `authPendingIntent`（条件为 requiresAuthentication || hasInlinePresentation）
        // 已拆成两个独立变量：`authPendingIntent`（仅认证用，挂 Dataset authentication）与
        // `inlinePendingIntent`（仅 inline 用，喂给 InlinePresentation）。语义不变，守卫随之拆分。
        assertTrue(
            "Inline suggestions should still create the real callback PendingIntent for keyboards that launch the slice PendingIntent.",
            cipherDatasetBody.contains("val inlinePendingIntent = if (hasInlinePresentation)") &&
                cipherDatasetBody.contains("createCipherAuthPendingIntent(")
        )
        assertTrue(
            "The real callback PendingIntent should be wired to the inline presentation itself.",
            cipherDatasetBody.contains("pendingIntent = inlinePendingIntent ?: return@create null")
        )
        assertFalse(
            "Inline suggestion clicks must not be wired to a no-op PendingIntent, because some keyboards launch it instead of applying dataset values.",
            cipherDatasetBody.contains("createNoopPendingIntent")
        )
        assertTrue(
            "Inline alone must not wrap direct-fill suggestions; Dataset authentication is only for locked authenticated suggestions (or WebView forceDatasetAuth aligned with Bitwarden).",
            cipherDatasetBody.contains("val authPendingIntent = if (partition.requiresAuthentication || partition.forceDatasetAuth)") &&
                cipherDatasetBody.contains("if (authPendingIntent != null)") &&
                cipherDatasetBody.contains("datasetBuilder.setAuthentication(authPendingIntent.intentSender)")
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
