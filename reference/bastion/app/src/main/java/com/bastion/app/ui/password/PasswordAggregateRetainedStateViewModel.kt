package com.bastion.app.ui.password

import androidx.lifecycle.ViewModel
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.viewmodel.CategoryFilter

internal data class PasswordAggregateSnapshotKey(
    val displayedContentTypes: Set<PasswordPageContentType>,
    val searchQuery: String,
    val categoryFilter: CategoryFilter,
)

internal data class PasswordAggregateSnapshotSeed(
    val items: List<PasswordAggregateListItemUi>,
    val hasSnapshot: Boolean,
)

internal class PasswordAggregateRetainedState {
    private var snapshotKey: PasswordAggregateSnapshotKey? = null
    private var snapshotItems: List<PasswordAggregateListItemUi> = emptyList()
    private var generation: Long = 0L

    fun currentGeneration(): Long = generation

    fun seed(key: PasswordAggregateSnapshotKey): PasswordAggregateSnapshotSeed {
        val matches = snapshotKey == key
        return PasswordAggregateSnapshotSeed(
            items = if (matches) snapshotItems else emptyList(),
            hasSnapshot = matches,
        )
    }

    fun updateIfCurrent(
        expectedGeneration: Long,
        key: PasswordAggregateSnapshotKey,
        items: List<PasswordAggregateListItemUi>,
    ): Boolean {
        if (generation != expectedGeneration) return false
        snapshotKey = key
        snapshotItems = items
        return true
    }

    fun clear() {
        generation += 1L
        snapshotKey = null
        snapshotItems = emptyList()
    }
}

internal class PasswordAggregateRetainedStateViewModel : ViewModel() {
    val retainedState = PasswordAggregateRetainedState()

    override fun onCleared() {
        retainedState.clear()
    }
}
