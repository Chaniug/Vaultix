package com.bastion.app.autofill_ng

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualFillTargetPolicyTest {
    @Test
    fun targetBoundFillWaitsUntilDetectedAppIsActive() {
        assertNull(
            resolveManualFillTargetPackage(
                activePackage = "com.other.app",
                packageNameToSkip = "com.bastion.app",
                expectedTargetPackage = "com.detected.app",
            )
        )
        assertEquals(
            "com.detected.app",
            resolveManualFillTargetPackage(
                activePackage = "COM.DETECTED.APP",
                packageNameToSkip = "com.bastion.app",
                expectedTargetPackage = "com.detected.app",
            )
        )
    }

    @Test
    fun legacyManualEntryKeepsCurrentAppBehavior() {
        assertEquals(
            "com.current.app",
            resolveManualFillTargetPackage(
                activePackage = "com.current.app",
                packageNameToSkip = "com.bastion.app",
                expectedTargetPackage = null,
            )
        )
        assertNull(
            resolveManualFillTargetPackage(
                activePackage = "com.bastion.app",
                packageNameToSkip = "com.bastion.app",
                expectedTargetPackage = null,
            )
        )
    }
}
