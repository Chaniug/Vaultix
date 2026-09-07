package com.bastion.app.ui

import androidx.compose.runtime.saveable.Saver
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.ui.screens.HistoryTab
import com.bastion.app.ui.password.sanitizeSelectedPasswordPageTypes
import com.bastion.app.viewmodel.CategoryFilter

internal enum class PasswordHistoryPageMode(val tab: HistoryTab?) {
    NONE(null),
    TIMELINE(HistoryTab.TIMELINE),
    TRASH(HistoryTab.TRASH)
}

internal val PasswordHistoryPageMode.isVisible: Boolean
    get() = this != PasswordHistoryPageMode.NONE

internal val passwordPageContentTypeSetSaver = Saver<Set<PasswordPageContentType>, ArrayList<String>>(
    save = { selectedTypes ->
        ArrayList(selectedTypes.map(PasswordPageContentType::name))
    },
    restore = { savedNames ->
        savedNames
            .mapNotNull { name -> PasswordPageContentType.entries.find { it.name == name } }
            .toSet()
    }
)

internal fun togglePasswordPageContentType(
    currentTypes: Set<PasswordPageContentType>,
    toggledType: PasswordPageContentType,
    visibleTypes: List<PasswordPageContentType>
): Set<PasswordPageContentType> {
    val nextTypes = if (toggledType in currentTypes && currentTypes.size == 1) {
        emptySet()
    } else {
        setOf(toggledType)
    }
    return sanitizeSelectedPasswordPageTypes(
        visibleTypes = visibleTypes,
        selectedTypes = nextTypes
    )
}

internal data class NewItemStorageDefaults(
    val categoryId: Long? = null,
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null
)

internal fun NewItemStorageDefaults.hasAnyValue(): Boolean {
    return categoryId != null ||
        keepassDatabaseId != null ||
        !keepassGroupPath.isNullOrBlank() ||
        bitwardenVaultId != null ||
        !bitwardenFolderId.isNullOrBlank()
}

internal fun defaultsFromTotpFilter(filter: com.bastion.app.viewmodel.TotpCategoryFilter): NewItemStorageDefaults {
    return when (filter) {
        is com.bastion.app.viewmodel.TotpCategoryFilter.Custom -> {
            NewItemStorageDefaults(categoryId = filter.categoryId)
        }
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabase -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> {
            NewItemStorageDefaults(
                keepassDatabaseId = filter.databaseId,
                keepassGroupPath = filter.groupPath
            )
        }
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVault -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> {
            NewItemStorageDefaults(
                bitwardenVaultId = filter.vaultId,
                bitwardenFolderId = filter.folderId
            )
        }
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        else -> NewItemStorageDefaults()
    }
}

internal fun defaultsFromPasswordFilter(filter: CategoryFilter): NewItemStorageDefaults {
    return when (filter) {
        is CategoryFilter.Custom -> {
            NewItemStorageDefaults(categoryId = filter.categoryId)
        }
        is CategoryFilter.KeePassDatabase -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is CategoryFilter.KeePassGroupFilter -> {
            NewItemStorageDefaults(
                keepassDatabaseId = filter.databaseId,
                keepassGroupPath = filter.groupPath
            )
        }
        is CategoryFilter.KeePassDatabaseStarred -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is CategoryFilter.KeePassDatabaseUncategorized -> {
            NewItemStorageDefaults(keepassDatabaseId = filter.databaseId)
        }
        is CategoryFilter.BitwardenVault -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        is CategoryFilter.BitwardenFolderFilter -> {
            NewItemStorageDefaults(
                bitwardenVaultId = filter.vaultId,
                bitwardenFolderId = filter.folderId
            )
        }
        is CategoryFilter.BitwardenVaultStarred -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        is CategoryFilter.BitwardenVaultUncategorized -> {
            NewItemStorageDefaults(bitwardenVaultId = filter.vaultId)
        }
        else -> NewItemStorageDefaults()
    }
}
