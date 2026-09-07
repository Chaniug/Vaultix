package com.bastion.app.autofill_ng

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillPasswordSuggestionSettingRegressionGuardTest {

    @Test
    fun settingsScreenControlsExistingPasswordSuggestionPreference() {
        val settings = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/AutofillSettingsV2Screen.kt"
        ).readText()

        assertTrue(
            settings.contains(
                "preferences.isPasswordSuggestionEnabled.collectAsState(initial = true)"
            )
        )
        assertTrue(settings.contains("checked = passwordSuggestionEnabled"))
        assertTrue(settings.contains("preferences.setPasswordSuggestionEnabled(enabled)"))
    }

    @Test
    fun serviceAndResponseBuilderHonorDisabledPreference() {
        val service = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/BastionAutofillServiceNg.kt"
        ).readText()
        val builder = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/builder/FillResponseBuilderNg.kt"
        ).readText()

        // Phase C onFillRequest 全量缓存化（方案B延伸）：配置读从 .first() 切到 AutofillConfigCache，
        // 守卫断言同步更新为缓存读取方式。
        assertTrue(
            service.contains(
                "passwordSuggestionEnabled = AutofillConfigCache.isPasswordSuggestionEnabled"
            )
        )
        assertTrue(builder.contains("if (passwordSuggestionEnabled)"))
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }
}
