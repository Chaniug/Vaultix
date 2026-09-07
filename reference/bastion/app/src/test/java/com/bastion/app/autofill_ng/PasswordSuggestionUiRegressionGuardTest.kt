package com.bastion.app.autofill_ng

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordSuggestionUiRegressionGuardTest {

    @Test
    fun passwordSuggestionUsesUnifiedAutofillCard() {
        val builder = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/builder/AutofillDatasetBuilder.kt"
        ).readText()
        val factoryBody = builder.substringAfter("fun createPasswordSuggestion(context: Context)")
            .substringBefore("\n        }")

        assertTrue(factoryBody.contains("R.layout.autofill_dataset_card"))
        assertTrue(factoryBody.contains("R.id.text_title"))
        assertTrue(factoryBody.contains("R.id.text_username"))
        assertFalse(factoryBody.contains("R.layout.autofill_suggestion_item"))
    }

    @Test
    fun passwordSuggestionDialogRemainsAdaptive() {
        val activity = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/PasswordSuggestionActivity.kt"
        ).readText()

        assertTrue(activity.contains("DialogProperties(usePlatformDefaultWidth = false)"))
        assertTrue(activity.contains(".widthIn(max = 420.dp)"))
        assertTrue(activity.contains(".heightIn(max = 640.dp)"))
        assertTrue(activity.contains(".verticalScroll(rememberScrollState())"))
    }

    @Test
    fun primaryActionUsesShortAcceptLabel() {
        // 应用默认资源(values/strings.xml)即中文，不存在独立英文资源文件(values-en)，
        // 历史上冗余的 values-zh 目录已合并回 values/ 并删除。守卫只需校验默认资源定义了
        // password_suggestion_accept 且保持为简短的接受文案。
        val defaultStrings = projectFile("app/src/main/res/values/strings.xml").readText()

        val defaultMatch = Regex("""<string name="password_suggestion_accept">([^<]+)</string>""").find(defaultStrings)
        requireNotNull(defaultMatch) { "Default strings.xml is missing password_suggestion_accept" }
        assertTrue(
            "password_suggestion_accept must stay a short accept label.",
            defaultMatch.groupValues[1].length <= 4
        )
    }

    private fun projectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
