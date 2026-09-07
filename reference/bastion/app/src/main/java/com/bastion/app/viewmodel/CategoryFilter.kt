package com.bastion.app.viewmodel

sealed class CategoryFilter {
    object All : CategoryFilter()
    object Archived : CategoryFilter()
    object Local : CategoryFilter() // Pure local view (Bastion)
    object LocalOnly : CategoryFilter() // Local entries that have no matching item in Bitwarden
    object Starred : CategoryFilter()
    object Uncategorized : CategoryFilter()
    object LocalStarred : CategoryFilter()
    object LocalUncategorized : CategoryFilter()
    data class Custom(val categoryId: Long) : CategoryFilter()
    data class KeePassDatabase(val databaseId: Long) : CategoryFilter()
    data class KeePassGroupFilter(val databaseId: Long, val groupPath: String) : CategoryFilter()
    data class KeePassDatabaseStarred(val databaseId: Long) : CategoryFilter()
    data class KeePassDatabaseUncategorized(val databaseId: Long) : CategoryFilter()
    data class BitwardenVault(val vaultId: Long) : CategoryFilter()
    data class BitwardenFolderFilter(val folderId: String, val vaultId: Long) : CategoryFilter()
    data class BitwardenVaultStarred(val vaultId: Long) : CategoryFilter()
    data class BitwardenVaultUncategorized(val vaultId: Long) : CategoryFilter()
}

/**
 * 归档筛选状态控制器（B.3 集群 1 迁移至此；集群 8 提升为 public 以便作为
 * `PasswordViewModel` 构造参数注入——无状态、无敏感逻辑，公开无风险）。
 */
class PasswordArchiveFilterController {
    private var returnFilter: CategoryFilter? = null

    fun open(currentFilter: CategoryFilter): CategoryFilter {
        if (currentFilter !is CategoryFilter.Archived) {
            returnFilter = currentFilter
        }
        return CategoryFilter.Archived
    }

    fun close(): CategoryFilter {
        val target = returnFilter ?: CategoryFilter.All
        returnFilter = null
        return target
    }

    fun clear() {
        returnFilter = null
    }
}
