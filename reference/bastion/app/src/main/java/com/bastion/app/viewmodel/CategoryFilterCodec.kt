package com.bastion.app.viewmodel

import com.bastion.app.data.AppSettings
import java.util.Locale

/**
 * Phase B.3 集群 4：类别过滤器的持久化编解码。
 *
 * 把 [PasswordViewModel] 中「已保存的过滤器类型字符串 ↔ [CategoryFilter]」
 * 的映射关系收敛到一处，使 ViewModel 只保留状态流转逻辑。
 *
 * 本对象是**纯函数**实现：无状态、无副作用、不触碰 DataStore。
 * [decode] 与搬迁前的 `decodeSavedCategoryFilter` 逐分支等价；
 * [encode] 描述某个过滤器应写入的持久化三元组，`null` 表示
 * **不应持久化**（当前仅 [CategoryFilter.Archived]，因为归档视图是
 * 临时态，持久化会导致下次冷启动误停在归档页）。
 *
 * 注意：写入动作仍留在 ViewModel 的 `persistCategoryFilter` 中，
 * 以保留 `PasswordArchiveReturnFilterGuardTest` 对该函数体
 * 「Archived 分支不得调用 updateLastPasswordCategoryFilter」的守卫锚点。
 */
internal object CategoryFilterCodec {

    const val SAVED_FILTER_ALL = "all"
    const val SAVED_FILTER_ARCHIVED = "archived"
    const val SAVED_FILTER_LOCAL = "local"
    const val SAVED_FILTER_LOCAL_ONLY = "local_only"
    const val SAVED_FILTER_STARRED = "starred"
    const val SAVED_FILTER_UNCATEGORIZED = "uncategorized"
    const val SAVED_FILTER_LOCAL_STARRED = "local_starred"
    const val SAVED_FILTER_LOCAL_UNCATEGORIZED = "local_uncategorized"
    const val SAVED_FILTER_CUSTOM = "custom"
    const val SAVED_FILTER_KEEPASS_DATABASE = "keepass_database"
    const val SAVED_FILTER_KEEPASS_GROUP = "keepass_group"
    const val SAVED_FILTER_KEEPASS_DATABASE_STARRED = "keepass_database_starred"
    const val SAVED_FILTER_KEEPASS_DATABASE_UNCATEGORIZED = "keepass_database_uncategorized"
    const val SAVED_FILTER_BITWARDEN_VAULT = "bitwarden_vault"
    const val SAVED_FILTER_BITWARDEN_FOLDER = "bitwarden_folder"
    const val SAVED_FILTER_BITWARDEN_VAULT_STARRED = "bitwarden_vault_starred"
    const val SAVED_FILTER_BITWARDEN_VAULT_UNCATEGORIZED = "bitwarden_vault_uncategorized"

    /**
     * 已保存设置 → 过滤器。任何缺失/非法字段一律安全回退到
     * [CategoryFilter.All]，与搬迁前行为完全一致。
     */
    fun decode(settings: AppSettings): CategoryFilter {
        val type = settings.lastPasswordCategoryFilterType.lowercase(Locale.ROOT)
        return when (type) {
            SAVED_FILTER_ALL -> CategoryFilter.All
            SAVED_FILTER_ARCHIVED -> CategoryFilter.Archived
            SAVED_FILTER_LOCAL -> CategoryFilter.Local
            SAVED_FILTER_LOCAL_ONLY -> CategoryFilter.LocalOnly
            SAVED_FILTER_STARRED -> CategoryFilter.Starred
            SAVED_FILTER_UNCATEGORIZED -> CategoryFilter.Uncategorized
            SAVED_FILTER_LOCAL_STARRED -> CategoryFilter.LocalStarred
            SAVED_FILTER_LOCAL_UNCATEGORIZED -> CategoryFilter.LocalUncategorized
            SAVED_FILTER_CUSTOM -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.Custom(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_KEEPASS_DATABASE -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.KeePassDatabase(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_KEEPASS_DATABASE_STARRED -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.KeePassDatabaseStarred(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_KEEPASS_DATABASE_UNCATEGORIZED -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.KeePassDatabaseUncategorized(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_KEEPASS_GROUP -> {
                val databaseId = settings.lastPasswordCategoryFilterPrimaryId
                val groupPath = settings.lastPasswordCategoryFilterText
                if (databaseId != null && !groupPath.isNullOrBlank()) {
                    CategoryFilter.KeePassGroupFilter(databaseId, groupPath)
                } else {
                    CategoryFilter.All
                }
            }
            SAVED_FILTER_BITWARDEN_VAULT -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.BitwardenVault(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_BITWARDEN_VAULT_STARRED -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.BitwardenVaultStarred(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_BITWARDEN_VAULT_UNCATEGORIZED -> settings.lastPasswordCategoryFilterPrimaryId
                ?.let { CategoryFilter.BitwardenVaultUncategorized(it) }
                ?: CategoryFilter.All
            SAVED_FILTER_BITWARDEN_FOLDER -> {
                val vaultId = settings.lastPasswordCategoryFilterSecondaryId
                    ?: settings.lastPasswordCategoryFilterPrimaryId
                val folderId = settings.lastPasswordCategoryFilterText
                if (vaultId != null && !folderId.isNullOrBlank()) {
                    CategoryFilter.BitwardenFolderFilter(folderId, vaultId)
                } else {
                    CategoryFilter.All
                }
            }
            else -> CategoryFilter.All
        }
    }

    /**
     * 待持久化的三元组载荷。字段语义与
     * `SettingsManager.updateLastPasswordCategoryFilter` 参数一一对应。
     */
    data class PersistPayload(
        val type: String,
        val primaryId: Long? = null,
        val secondaryId: Long? = null,
        val text: String? = null
    )
}
