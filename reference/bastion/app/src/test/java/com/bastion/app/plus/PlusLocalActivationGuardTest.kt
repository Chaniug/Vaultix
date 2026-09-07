package com.bastion.app.plus

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class PlusLocalActivationGuardTest {
    @Test
    fun retiredRemoteLicenseStackIsAbsent() {
        val retiredFiles = listOf(
            "app/src/main/java/com/bastion/app/plus/PlusDeviceFingerprint.kt",
            "app/src/main/java/com/bastion/app/plus/PlusLicenseApiService.kt",
            "app/src/main/java/com/bastion/app/plus/PlusLicenseManager.kt",
            "app/src/main/java/com/bastion/app/plus/PlusLicenseModels.kt"
        )
        retiredFiles.forEach { path ->
            assertFalse("Retired Plus license file still exists: $path", projectFile(path).exists())
        }

        val settingsManagerSource = projectFile(
            "app/src/main/java/com/bastion/app/utils/SettingsManager.kt"
        ).readText()
        val settingsViewModelSource = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/SettingsViewModel.kt"
        ).readText()
        val buildSource = projectFile("app/build.gradle").readText()

        listOf(
            "PLUS_LICENSE_CDK_KEY",
            "PLUS_LICENSE_DEVICE_FINGERPRINT_KEY",
            "PLUS_LICENSE_LAST_VERIFIED_AT_KEY",
            "updatePlusLicenseCdk",
            "updatePlusLicenseDeviceFingerprint",
            "updatePlusLicenseLastVerifiedAt",
            "getPlusLicenseCdk",
            "getPlusLicenseDeviceFingerprint",
            "getPlusLicenseLastVerifiedAt",
            "clearPlusLicenseData"
        ).forEach { token ->
            assertFalse("SettingsManager still contains $token", settingsManagerSource.contains(token))
        }
        assertFalse(settingsViewModelSource.contains("clearPlusLicenseData"))
        assertFalse(buildSource.contains("CF_LICENSE_"))
        assertFalse(buildSource.contains("cfLicense"))

        val resourceSource = projectFile("app/src/main/res")
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .joinToString(separator = "\n") { it.readText() }
        listOf(
            "plus_activation_cdk_",
            "plus_activation_empty_cdk",
            "plus_activation_verify_button",
            "payment_activated_message"
        ).forEach { token ->
            assertFalse("Resources still contain $token", resourceSource.contains(token))
        }
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
