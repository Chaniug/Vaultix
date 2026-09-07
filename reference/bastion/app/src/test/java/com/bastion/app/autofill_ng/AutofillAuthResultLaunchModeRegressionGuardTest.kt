package com.bastion.app.autofill_ng

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillAuthResultLaunchModeRegressionGuardTest {

    /**
     * AutofillCipherCallbackActivity 通过 onNewIntent 回传解锁结果，因此 singleTop 是刻意的实例复用。
     * 守卫只确认：它确实声明了 singleTop 且确实覆写了 onNewIntent（否则复用会导致结果丢失）。
     */
    @Test
    fun callbackActivityMayReuseInstanceViaSingleTopWithOnNewIntent() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val block = activityBlock(manifest, ".autofill_ng.AutofillCipherCallbackActivity")

        assertTrue(
            "AutofillCipherCallbackActivity relays the unlock result through onNewIntent, so singleTop is intentional.",
            block.contains(Regex("android:launchMode\\s*=\\s*\"singleTop\""))
        )
        val callbackSource = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/AutofillCipherCallbackActivity.kt"
        ).readText()
        assertTrue(
            "singleTop reuse is safe only if the activity handles relaunched intents via onNewIntent.",
            callbackSource.contains(Regex("override\\s+fun\\s+onNewIntent\\(\\s*intent:\\s*Intent\\s*\\)"))
        )
    }

    /**
     * 其余返回 EXTRA_AUTHENTICATION_RESULT 的 Activity 必须每次用全新的结果记录，禁止实例复用。
     */
    @Test
    fun otherAuthResultActivitiesMustNotReuseExistingInstances() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val authResultActivities = listOf(
            ".autofill_ng.BiometricAuthActivity",
            ".autofill_ng.AutofillAuthenticationActivity",
            ".autofill_ng.AutofillUnlockActivity",
            ".autofill_ng.AutofillPickerActivity",
            ".autofill_ng.AutofillPickerActivityV2",
            ".autofill_ng.PasswordSuggestionActivity",
        )

        authResultActivities.forEach { activityName ->
            val block = activityBlock(manifest, activityName)
            assertFalse(
                "$activityName returns AutofillManager.EXTRA_AUTHENTICATION_RESULT and must use a fresh Activity result record.",
                block.contains(Regex("android:launchMode\\s*=\\s*\"singleTop\"")) ||
                    block.contains(Regex("android:launchMode\\s*=\\s*\"singleTask\""))
            )
        }
    }

    private fun activityBlock(manifest: String, activityName: String): String {
        val marker = "android:name=\"$activityName\""
        val markerIndex = manifest.indexOf(marker)
        require(markerIndex >= 0) { "Unable to find activity in manifest: $activityName" }
        val blockStart = manifest.lastIndexOf("<activity", markerIndex)
        val blockEnd = manifest.indexOf("/>", markerIndex)
        require(blockStart >= 0 && blockEnd >= 0) { "Unable to read manifest block for: $activityName" }
        return manifest.substring(blockStart, blockEnd)
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
