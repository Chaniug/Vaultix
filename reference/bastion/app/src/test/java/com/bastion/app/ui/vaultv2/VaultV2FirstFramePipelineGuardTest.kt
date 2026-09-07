package com.bastion.app.ui.vaultv2

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultV2FirstFramePipelineGuardTest {

    @Test
    fun `first cards require only one asynchronous list model stage`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val asyncListStages = Regex(
            "val\\s+\\w+\\s*=\\s*rememberVaultV2AsyncComputedValue\\("
        ).findAll(pane).count()

        assertEquals(1, asyncListStages)
        assertTrue(pane.contains("buildVaultV2VisibleListState("))
        assertFalse(pane.contains("val visibleListStateAsync ="))
    }

    @Test
    fun `independent secondary item types are parsed concurrently`() {
        val pane = source("ui/vaultv2/VaultV2Pane.kt")
        val computedBlock = pane
            .substringAfter("val computedListStateAsync =")
            .substringBefore("val computedListState =")

        assertTrue(computedBlock.contains("val secondaryLists = coroutineScope"))
        assertTrue(computedBlock.indexOf("val visiblePasswordIds =") < computedBlock.indexOf("coroutineScope"))
        assertTrue(Regex("async\\(Dispatchers\\.Default\\)").findAll(computedBlock).count() >= 5)
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(
            directory,
            "app/src/main/java/com/bastion/app/$relativePath"
        ).readText()
    }
}
