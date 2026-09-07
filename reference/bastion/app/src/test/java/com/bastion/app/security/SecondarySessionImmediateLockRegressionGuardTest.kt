package com.bastion.app.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondarySessionImmediateLockRegressionGuardTest {

    @Test
    fun immediateAutoLockKeepsShortSecondaryWindow() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/security/SecondarySessionManager.kt"
        ).readText()

        assertTrue(
            "IME/Autofill secondary sessions need a short grace window when main app auto-lock is immediate.",
            source.contains("IMMEDIATE_LOCK_SECONDARY_GRACE_MS")
        )
        assertTrue(
            "Immediate auto-lock must not expire a secondary session in the same millisecond it is granted.",
            source.contains("autoLockMinutes <= 0 -> elapsedMillis >= IMMEDIATE_LOCK_SECONDARY_GRACE_MS")
        )
    }

    @Test
    fun mainSessionExpiryDoesNotClearSecondarySessionDuringSkipCheck() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/security/SessionManager.kt"
        ).readText()

        assertTrue(
            "Session expiry checks for the main app must not clear isolated IME/Autofill secondary sessions.",
            source.contains("markLocked(clearSecondarySession = false)")
        )
    }

    @Test
    fun secondaryVaultAccessChecksSecondarySessionBeforeSharedSession() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/security/SecurityManager.kt"
        ).readText()
        val accessBody = source.substringAfter("fun canAccessVaultNow(")
            .substringBefore("fun canAccessVaultMaterialNow()")

        assertTrue(
            "Secondary entry points must check the isolated secondary session before evaluating the main app session.",
            accessBody.contains("val secondarySessionActive = hasActiveSecondarySession")
        )
        assertTrue(
            "When the secondary session is active, avoid triggering main-session expiry side effects.",
            accessBody.contains("if (secondarySessionActive)")
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
