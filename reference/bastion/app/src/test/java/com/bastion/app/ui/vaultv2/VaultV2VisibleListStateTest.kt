package com.bastion.app.ui.vaultv2

import org.junit.Assert.assertEquals
import org.junit.Test
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.ui.components.UnifiedCategoryFilterSelection

class VaultV2VisibleListStateTest {

    @Test
    fun `visible list filters content and builds stable section indexes`() {
        val alpha = item("password:1", VaultV2ItemType.PASSWORD, "Alpha", "alpha user")
        val betaNote = item("note:2", VaultV2ItemType.NOTE, "Beta", "beta note")
        val numeric = item("password:3", VaultV2ItemType.PASSWORD, "123", "numeric")

        val result = buildVaultV2VisibleListState(
            allItems = listOf(alpha, betaNote, numeric),
            config = config(displayedTypes = setOf(PasswordPageContentType.PASSWORD)),
        )

        assertEquals(listOf(alpha, numeric), result.filteredItems)
        assertEquals(listOf("A", "#"), result.sectionedItems.map { it.first })
        assertEquals(listOf(0, 1), result.sectionLayouts.map { it.itemStartIndex })
        assertEquals(listOf(1, 3), result.sectionLayouts.map { it.firstItemLazyIndex })
    }

    @Test
    fun `visible list search keeps matching items only`() {
        val alpha = item("password:1", VaultV2ItemType.PASSWORD, "Alpha", "alpha user")
        val beta = item("password:2", VaultV2ItemType.PASSWORD, "Beta", "beta account")

        val result = buildVaultV2VisibleListState(
            allItems = listOf(alpha, beta),
            config = config(
                displayedTypes = setOf(PasswordPageContentType.PASSWORD),
                query = "USER",
            ),
        )

        assertEquals(listOf(alpha), result.filteredItems)
        assertEquals(listOf("A"), result.sectionedItems.map { it.first })
    }

    private fun config(
        displayedTypes: Set<PasswordPageContentType>,
        query: String = "",
    ) = VaultV2VisibleListConfig(
        storageSelection = UnifiedCategoryFilterSelection.All,
        displayedContentTypes = displayedTypes,
        configuredQuickFilterItems = PasswordListQuickFilterItem.DEFAULT_ORDER,
        quickFilterFavorite = false,
        quickFilter2fa = false,
        quickFilterNotes = false,
        quickFilterPasskey = false,
        quickFilterBoundNote = false,
        quickFilterAttachments = false,
        activeAttachmentParentIds = emptySet(),
        quickFilterUncategorized = false,
        quickFilterLocalOnly = false,
        quickFilterManualStackOnly = false,
        quickFilterNeverStack = false,
        quickFilterUnstacked = false,
        manualStackGroupByEntryId = emptyMap(),
        noStackEntryIds = emptySet(),
        normalizedQuery = query,
        isArchiveView = false,
    )

    private fun item(
        key: String,
        type: VaultV2ItemType,
        title: String,
        searchText: String,
    ) = VaultV2Item(
        key = key,
        type = type,
        title = title,
        subtitle = "-",
        isFavorite = false,
        sortKey = title,
        searchableValues = listOf(searchText),
    )
}
