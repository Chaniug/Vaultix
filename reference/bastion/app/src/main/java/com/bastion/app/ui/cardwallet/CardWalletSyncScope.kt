package com.bastion.app.ui.cardwallet

import com.bastion.app.ui.components.UnifiedCategoryFilterSelection

fun UnifiedCategoryFilterSelection.isBitwardenWalletScope(): Boolean =
    bitwardenVaultIdForWalletSync() != null

fun UnifiedCategoryFilterSelection.bitwardenVaultIdForWalletSync(): Long? =
    when (this) {
        is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> vaultId
        is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> vaultId
        is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> vaultId
        is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> vaultId
        else -> null
    }
