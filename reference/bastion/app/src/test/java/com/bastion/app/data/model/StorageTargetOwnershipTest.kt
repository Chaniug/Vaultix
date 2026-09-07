package com.bastion.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.data.PasswordEntry

class StorageTargetOwnershipTest {

    @Test
    fun applyingBastionLocalTargetClearsExternalOwnership() {
        val source = keepassPasswordEntry()

        val local = StorageTarget.BastionLocal(categoryId = 42L)
            .applyToPasswordEntry(source, replicaGroupId = null)

        assertEquals(42L, local.categoryId)
        assertNull(local.keepassDatabaseId)
        assertNull(local.keepassGroupPath)
        assertNull(local.bitwardenVaultId)
        assertNull(local.replicaGroupId)
        assertTrue(local.isLocalOnlyEntry())
    }

    @Test
    fun applyingExternalTargetsClearOtherExternalOwnership() {
        val source = keepassPasswordEntry()

        val bitwarden = StorageTarget.Bitwarden(vaultId = 8L, folderId = "folder")
            .applyToPasswordEntry(source, replicaGroupId = "group")

        assertNull(bitwarden.keepassDatabaseId)
        assertNull(bitwarden.keepassGroupPath)
        assertEquals(8L, bitwarden.bitwardenVaultId)
        assertEquals("group", bitwarden.replicaGroupId)
    }

    private fun keepassPasswordEntry(): PasswordEntry {
        return PasswordEntry(
            id = 1L,
            title = "Example",
            website = "example.com",
            username = "alice",
            password = "secret",
            keepassDatabaseId = 7L,
            keepassGroupPath = "Root",
            replicaGroupId = "keepass:source"
        )
    }
}
