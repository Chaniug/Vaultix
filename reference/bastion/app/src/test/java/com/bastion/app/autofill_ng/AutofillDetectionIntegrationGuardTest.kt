package com.bastion.app.autofill_ng

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillDetectionIntegrationGuardTest {

    @Test
    fun servicePassesManualRequestThroughWeakTargetAndConfidenceGates() {
        val service = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/BastionAutofillServiceNg.kt"
        ).readText()
        val parser = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/EnhancedAutofillStructureParserV2.kt"
        ).readText()

        assertTrue(service.contains("FillRequest.FLAG_MANUAL_REQUEST"))
        assertTrue(service.contains("allowWeakTargets = isManualRequest"))
        assertTrue(service.contains("manualRequest = isManualRequest"))
        assertTrue(service.contains("if (!isManualRequest && loginTargetCount == 0"))
        assertTrue(parser.contains("if (allowWeakTargets) return@let list"))
        assertTrue(parser.contains("promotePasswordTermCandidates"))
        assertTrue(parser.contains("nodeHasPasswordTermMatch"))
        assertTrue(parser.contains("nodeHasLoginTermMatch"))
        // P1：weakLoginContext 变量与 hasLoginTypeField 已拆除（注释中的历史说明不参与判定），
        // 识别层保留规则下沉到策略对象
        assertFalse(parser.contains("val weakLoginContext"))
        assertFalse(parser.contains("hasLoginTypeField"))
        assertTrue(parser.contains("AutofillDetectionPolicy.shouldKeepLoginField("))
        assertFalse(service.contains("shouldSuppressWeakLoginSuggestion"))
        // P2：条目级 website 双向一致性校验已接入匹配结果之后（stabilize 之前）；
        // 页面域名只用 AssistStructure 权威信号 parsedWebDomain，避免无障碍回退域名误杀 native 条目
        assertTrue(service.contains("AutofillWebsiteConsistencyPolicy.isConsistent("))
        assertTrue(service.contains("pageWebDomain = parsedWebDomain"))
        assertTrue(service.contains("entryTitle = entry.title"))
        assertTrue(service.contains("matchedPasswords = consistentPasswords"))
        assertTrue(parser.contains("Weak-signal node signals (LOWEST username / UNKNOWN fallback)"))
        assertTrue(parser.contains("AutofillFieldPromotionPolicy.selectUsernameNeighborIndex"))
        assertTrue(parser.contains("autofillLabelLoginTranslations"))
        assertTrue(service.contains("AutofillRequestContextPolicy.allowPackageMatching("))
        assertTrue(service.contains("allowPackageMatch = allowPackageMatch"))
    }

    @Test
    fun genericNumbersAndHiddenFieldsUseTheSharedAdmissionPolicy() {
        val parser = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/EnhancedAutofillStructureParserV2.kt"
        ).readText()
        val numberBranch = parser
            .substringAfter("InputType.TYPE_CLASS_NUMBER ->")
            .substringBefore("return out")

        assertTrue(numberBranch.contains("genericNumberFallbackAccuracy()"))
        assertFalse(numberBranch.contains("Accuracy.MEDIUM"))
        assertTrue(parser.contains("shouldIncludeHiddenCredential("))
        assertTrue(parser.contains("matchesUsernameLabel(hint)"))
        assertTrue(parser.contains("matchesPhoneFieldName"))
        assertTrue(parser.contains("placeholder"))
        assertTrue(parser.contains("inputmode"))
    }

    @Test
    fun parserAndAuthenticationCallbackShareTheConflictPolicy() {
        val parser = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/EnhancedAutofillStructureParserV2.kt"
        ).readText()
        val callback = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/AutofillCipherCallbackActivity.kt"
        ).readText()

        assertTrue(parser.contains("AutofillFieldRolePolicy.selectWithDiagnostics("))
        assertTrue(callback.contains("val parser = EnhancedAutofillStructureParserV2()"))
        assertTrue(callback.contains("source = \"assist_structure_fresh\""))
    }

    @Test
    fun phoneLoginFieldsUseTheSavedAccountAcrossEveryPickerPath() {
        val modernPicker = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/AutofillPickerActivityV2.kt"
        ).readText()
        val authenticatedCallback = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/AutofillCipherCallbackActivity.kt"
        ).readText()
        val legacyPicker = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/AutofillPickerActivity.kt"
        ).readText()

        assertTrue(
            modernPicker.contains("FieldHint.PHONE_NUMBER.name.lowercase()") &&
                modernPicker.contains("normalizedHint.contains(\"phone\")") &&
                modernPicker.contains("normalizedHint.contains(\"tel\") -> accountValue")
        )
        assertTrue(
            authenticatedCallback.contains("FieldHint.PHONE_NUMBER.name.lowercase()") &&
                authenticatedCallback.contains("normalizedHint.contains(\"phone\")") &&
                authenticatedCallback.contains("normalizedHint.contains(\"tel\") -> accountValue")
        )
        assertTrue(
            "The legacy picker must also treat an explicit phone login field as the saved account identifier.",
            legacyPicker.contains("FieldHint.PHONE_NUMBER.name -> accountValue")
        )
        assertFalse(
            "A phone login field must never receive the password value.",
            legacyPicker.contains("FieldHint.PHONE_NUMBER.name -> decryptedPassword")
        )
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
