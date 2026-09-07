package com.bastion.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.data.ItemType
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.SecureItem
import com.bastion.app.data.isLocalOnlyItem

class BackupContentPolicyTest {
    @Test
    fun allOfflineScopeIncludesExternalPasswordsAndSanitizesThemAsLocalCopies() {
        val entries = listOf(
            password("local"),
            password("bitwarden", bitwardenVaultId = 1L, bitwardenCipherId = "cipher"),
            password("keepass", keepassDatabaseId = 3L, keepassEntryUuid = "entry")
        )

        val sanitized = entries
            .filter { BackupContentPolicy.shouldIncludePassword(it, BackupContentScope.ALL_OFFLINE) }
            .map(BackupContentPolicy::sanitizePasswordForBastionBackup)

        assertEquals(3, sanitized.size)
        sanitized.forEach { entry ->
            assertTrue(entry.isLocalOnlyEntry())
            assertNull(entry.keepassDatabaseId)
            assertNull(entry.bitwardenVaultId)
        }
    }

    @Test
    fun localOnlyScopeKeepsLegacyDetachedKeePassRepairBehavior() {
        val local = password("local")
        val detachedKeePass = password("detached", keepassDatabaseId = 3L, categoryId = 7L)
        val activeKeePass = password("keepass", keepassDatabaseId = 3L, keepassEntryUuid = "entry")
        val bitwarden = password("bitwarden", bitwardenVaultId = 1L, bitwardenCipherId = "cipher")

        val included = listOf(local, detachedKeePass, activeKeePass, bitwarden)
            .filter { BackupContentPolicy.shouldIncludePassword(it, BackupContentScope.MONICA_LOCAL_ONLY) }

        assertEquals(listOf("local", "detached"), included.map { it.title })
    }

    @Test
    fun allOfflineScopeIncludesExternalSecureItemsAndSanitizesThemAsLocalCopies() {
        val items = listOf(
            secureItem("local"),
            secureItem("bitwarden", bitwardenVaultId = 1L, bitwardenCipherId = "cipher"),
            secureItem("keepass", keepassDatabaseId = 3L, keepassEntryUuid = "entry")
        )

        val sanitized = items
            .filter { BackupContentPolicy.shouldIncludeSecureItem(it, BackupContentScope.ALL_OFFLINE) }
            .map(BackupContentPolicy::sanitizeSecureItemForBastionBackup)

        assertEquals(3, sanitized.size)
        sanitized.forEach { item ->
            assertTrue(item.isLocalOnlyItem())
            assertNull(item.keepassDatabaseId)
            assertNull(item.bitwardenVaultId)
            assertEquals("NONE", item.syncStatus)
        }
    }

    @Test
    fun localOnlyScopeExcludesExternalPasskeys() {
        val passkeys = listOf(
            passkey("local"),
            passkey("keepass", keepassDatabaseId = 1L),
            passkey("bitwarden", bitwardenVaultId = 3L, bitwardenCipherId = "cipher")
        )

        val included = passkeys
            .filter { BackupContentPolicy.shouldIncludePasskey(it, BackupContentScope.MONICA_LOCAL_ONLY) }

        assertEquals(listOf("local"), included.map { it.rpName })
    }

    private fun password(
        title: String,
        keepassDatabaseId: Long? = null,
        keepassEntryUuid: String? = null,
        bitwardenVaultId: Long? = null,
        bitwardenCipherId: String? = null,
        categoryId: Long? = null
    ): PasswordEntry {
        return PasswordEntry(
            title = title,
            website = "example.com",
            username = "user",
            password = "secret",
            keepassDatabaseId = keepassDatabaseId,
            keepassEntryUuid = keepassEntryUuid,
            bitwardenVaultId = bitwardenVaultId,
            bitwardenCipherId = bitwardenCipherId,
            categoryId = categoryId
        )
    }

    private fun secureItem(
        title: String,
        keepassDatabaseId: Long? = null,
        keepassEntryUuid: String? = null,
        bitwardenVaultId: Long? = null,
        bitwardenCipherId: String? = null
    ): SecureItem {
        return SecureItem(
            itemType = ItemType.TOTP,
            title = title,
            itemData = "{}",
            keepassDatabaseId = keepassDatabaseId,
            keepassEntryUuid = keepassEntryUuid,
            bitwardenVaultId = bitwardenVaultId,
            bitwardenCipherId = bitwardenCipherId,
            syncStatus = "SYNCED"
        )
    }

    private fun passkey(
        rpName: String,
        keepassDatabaseId: Long? = null,
        bitwardenVaultId: Long? = null,
        bitwardenCipherId: String? = null
    ): PasskeyEntry {
        return PasskeyEntry(
            credentialId = "$rpName-credential",
            rpId = "$rpName.example.com",
            rpName = rpName,
            userId = "$rpName-user-id",
            userName = "user@$rpName.example.com",
            userDisplayName = "User",
            publicKey = "public-key",
            privateKeyAlias = "private-key",
            keepassDatabaseId = keepassDatabaseId,
            bitwardenVaultId = bitwardenVaultId,
            bitwardenCipherId = bitwardenCipherId,
            syncStatus = if (bitwardenCipherId.isNullOrBlank()) "NONE" else "SYNCED"
        )
    }
}
