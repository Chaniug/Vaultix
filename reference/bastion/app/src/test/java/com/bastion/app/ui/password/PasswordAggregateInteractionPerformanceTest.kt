package com.bastion.app.ui.password

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.viewmodel.CategoryFilter

class PasswordAggregateInteractionPerformanceTest {

    @Test
    fun `content type selection filters prebuilt items without copying the unfiltered list`() {
        val note = item("note:1", PasswordPageContentType.NOTE)
        val passkey = item("passkey:2", PasswordPageContentType.PASSKEY)
        val items = listOf(note, passkey)

        assertSame(items, filterPasswordAggregateItemsByContentTypes(items, emptySet()))
        assertEquals(
            listOf(note),
            filterPasswordAggregateItemsByContentTypes(
                items = items,
                selectedTypes = setOf(PasswordPageContentType.NOTE),
            )
        )
    }

    @Test
    fun `retained aggregate snapshot survives detail composition and clears on lock`() {
        val state = PasswordAggregateRetainedState()
        val items = listOf(item("note:1", PasswordPageContentType.NOTE))
        val key = PasswordAggregateSnapshotKey(
            displayedContentTypes = setOf(PasswordPageContentType.NOTE),
            searchQuery = "",
            categoryFilter = CategoryFilter.All,
        )

        assertFalse(state.seed(key).hasSnapshot)
        val generation = state.currentGeneration()
        assertTrue(state.updateIfCurrent(generation, key, items))

        val retained = state.seed(key)
        assertTrue(retained.hasSnapshot)
        assertSame(items, retained.items)
        assertFalse(
            state.seed(key.copy(searchQuery = "different query")).hasSnapshot
        )

        state.clear()
        assertFalse(state.updateIfCurrent(generation, key, items))
        assertFalse(state.seed(key).hasSnapshot)
        assertEquals(emptyList<PasswordAggregateListItemUi>(), state.seed(key).items)
    }

    @Test
    fun `aggregate type changes stay outside the background full build inputs`() {
        val source = source("ui/password/PasswordListContentSupport.kt")
        val fullBuild = source
            .substringAfter("val aggregateAllVisibleItemsAsync = rememberPasswordAggregateAsyncItems(")
            .substringBefore("val aggregateVisibleItems = remember(")

        assertTrue(fullBuild.contains("withContext(Dispatchers.Default)"))
        assertTrue(fullBuild.contains("buildPasswordAggregateItems("))
        assertFalse(fullBuild.contains("aggregateContentTypeFilterTypes"))
        assertTrue(source.contains("var value by remember(stateKey)"))
    }

    @Test
    fun `main back stack view model owns snapshot and password lock clears it`() {
        val source = source("ui/password/PasswordListContent.kt")
        val support = source("ui/password/PasswordListContentSupport.kt")

        assertTrue(
            source.contains(
                "PasswordAggregateRetainedStateViewModel = viewModel()"
            )
        )
        assertTrue(
            source.contains(
                "aggregateRetainedStateViewModel.retainedState.clear()"
            )
        )
        assertTrue(
            source.contains(
                "retainedState = aggregateRetainedStateViewModel.retainedState"
            )
        )
        assertTrue(support.contains("retainedState.updateIfCurrent("))
    }

    @Test
    fun `aggregate source state flows provide their retained value on return`() {
        val support = source("ui/password/PasswordListContentSupport.kt")
        val billing = source("viewmodel/BillingAddressViewModel.kt")

        assertTrue(support.contains("allCards?.collectAsState()"))
        assertTrue(support.contains("allDocuments?.collectAsState()"))
        assertTrue(support.contains("allBillingAddresses?.collectAsState()"))
        assertTrue(support.contains("allNotes?.collectAsState()"))
        assertTrue(
            billing.contains("val allBillingAddresses: StateFlow<List<SecureItem>>")
        )
    }

    private fun item(
        key: String,
        type: PasswordPageContentType,
    ): PasswordAggregateListItemUi = PasswordAggregateListItemUi(
        key = key,
        entry = PasswordEntry(
            id = key.hashCode().toLong(),
            title = key,
            website = "",
            username = "",
            password = "",
        ),
        type = type,
        badgeText = type.name,
        badgeColor = Color.Blue,
        sortTime = 1L,
    )

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
