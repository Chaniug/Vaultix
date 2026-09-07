package com.bastion.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.data.dedup.DedupMergeExecutionProgress
import com.bastion.app.data.dedup.DedupMergePlan
import com.bastion.app.data.dedup.DedupMergeTarget
import com.bastion.app.data.dedup.DedupMergeTargetOption

class DedupEngineViewModelTest {
    private val localTarget = DedupMergeTargetOption(
        target = DedupMergeTarget.BastionLocal,
        sourceKey = "bastion",
        label = "Bastion local",
        passwordCount = 0
    )

    @Test
    fun uiStateAllowsExecutionOnlyForCompleteSelectionAndWritablePlan() {
        val ready = DedupEngineUiState(
            isLoading = false,
            selectedMergeSourceKeys = setOf("keepass:1", "bitwarden:1"),
            targetOptions = listOf(localTarget),
            selectedMergeTarget = DedupMergeTarget.BastionLocal,
            mergePlan = DedupMergePlan(
                selectedSources = emptyList(),
                target = DedupMergeTarget.BastionLocal,
                uniquePasswords = 2
            )
        )

        assertTrue(ready.validation.canExecute)
        assertEquals(localTarget, ready.selectedTargetOption)

        val incomplete = ready.copy(selectedMergeSourceKeys = setOf("keepass:1"))
        assertFalse(incomplete.validation.canExecute)
    }

    @Test
    fun progressFractionIsStableForEmptyAndCompletedWork() {
        assertEquals(0f, DedupMergeExecutionProgress(0, 0, "").fraction)
        assertEquals(1f, DedupMergeExecutionProgress(4, 4, "done").fraction)
    }
}
