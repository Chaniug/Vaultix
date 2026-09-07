package com.bastion.app.viewmodel

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordArchiveFilterControllerTest {

    @Test
    fun restoresTheExactDatabaseFolderFilterAfterArchive() {
        val controller = PasswordArchiveFilterController()
        val databaseFolder = CategoryFilter.KeePassGroupFilter(
            databaseId = 42L,
            groupPath = "accounts/email"
        )

        assertSame(CategoryFilter.Archived, controller.open(databaseFolder))

        assertSame(databaseFolder, controller.close())
    }

    @Test
    fun repeatedArchiveOpenDoesNotReplaceTheOriginalReturnFilter() {
        val controller = PasswordArchiveFilterController()
        val database = CategoryFilter.KeePassDatabase(databaseId = 8L)

        controller.open(database)
        controller.open(CategoryFilter.Archived)

        assertSame(database, controller.close())
    }

    @Test
    fun archiveWithoutAStoredSourceFallsBackToAll() {
        val controller = PasswordArchiveFilterController()

        assertTrue(controller.close() is CategoryFilter.All)
    }

    @Test
    fun directFilterNavigationClearsTheArchivedReturnTarget() {
        val controller = PasswordArchiveFilterController()
        controller.open(CategoryFilter.BitwardenVault(vaultId = 7L))

        controller.clear()

        assertTrue(controller.close() is CategoryFilter.All)
    }
}
