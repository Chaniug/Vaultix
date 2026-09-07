package com.bastion.app.data.model

import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.SecureItem

sealed interface StorageTarget {
    val stableKey: String

    data class BastionLocal(val categoryId: Long?) : StorageTarget {
        override val stableKey: String = "local:${categoryId ?: "root"}"
    }

    data class KeePass(
        val databaseId: Long,
        val groupPath: String?
    ) : StorageTarget {
        override val stableKey: String = "keepass:$databaseId:${groupPath.orEmpty()}"
    }

    data class Bitwarden(
        val vaultId: Long,
        val folderId: String?
    ) : StorageTarget {
        override val stableKey: String = "bitwarden:$vaultId:${folderId.orEmpty()}"
    }

}

fun StorageTarget.storageScopeKey(): String = when (this) {
    is StorageTarget.BastionLocal -> "local"
    is StorageTarget.KeePass -> "keepass:$databaseId"
    is StorageTarget.Bitwarden -> "bitwarden:$vaultId"
}

fun StorageTarget.uncategorizedPeer(): StorageTarget = when (this) {
    is StorageTarget.BastionLocal -> StorageTarget.BastionLocal(null)
    is StorageTarget.KeePass -> StorageTarget.KeePass(databaseId, null)
    is StorageTarget.Bitwarden -> StorageTarget.Bitwarden(vaultId, null)
}

fun StorageTarget.isUncategorizedTarget(): Boolean = when (this) {
    is StorageTarget.BastionLocal -> categoryId == null
    is StorageTarget.KeePass -> groupPath.isNullOrBlank()
    is StorageTarget.Bitwarden -> folderId.isNullOrBlank()
}

/**
 * 【编辑期语义】只做去重，**不做任何兜底**。
 *
 * 空列表在此是合法状态，表示"用户当前未选择任何存储位置"，由调用方决定如何提示或拦截。
 * 用于：屏幕内的 targets 状态、选择器 onChange、移除 target 的结果。
 *
 * ⚠️ 不要在"移除 target"的链路上改用 [normalizedStorageTargets]，否则取消最后一个位置后
 * 会被立刻填回 BastionLocal，用户永远无法清空选择（这正是"仅 Bastion 本地存储点不掉"的成因）。
 */
fun List<StorageTarget>.dedupedStorageTargets(): List<StorageTarget> =
    distinctBy(StorageTarget::stableKey)

/**
 * 【新建 / 提交语义】去重 + 兜底：空列表时回落到 [defaultTarget]。
 *
 * 仅用于：新建默认值初始化、保存提交前的兼容兜底。
 * 其余 20+ 处既有调用方（Note / Document / BankCard / Wifi / SSH / BillingAddress 等）
 * 继续使用它，行为完全不变。
 */
fun List<StorageTarget>.normalizedStorageTargets(
    defaultTarget: StorageTarget = StorageTarget.BastionLocal(null)
): List<StorageTarget> = dedupedStorageTargets().ifEmpty { listOf(defaultTarget) }

fun List<StorageTarget>.withStorageTargetSelected(
    target: StorageTarget,
    defaultTarget: StorageTarget = StorageTarget.BastionLocal(null),
    /**
     * 当前选择为空时，是否先兜底 [defaultTarget] 再追加 [target]。
     *
     * true（默认）—— 既有调用方行为不变。
     * false —— 存储选择器（允许清空的页面）专用：空列表直接追加 target，
     *   否则"清空后再选 Bitwarden"会被兜底出一条 BastionLocal 隐式占位，幽灵本地副本复活。
     */
    fallbackIfEmpty: Boolean = true
): List<StorageTarget> {
    val current = if (fallbackIfEmpty) {
        normalizedStorageTargets(defaultTarget)
    } else {
        dedupedStorageTargets()
    }
    if (current.any { it.stableKey == target.stableKey }) return current
    val withoutImplicitFallback = if (target.isUncategorizedTarget()) {
        current
    } else {
        current.filterNot {
            it.storageScopeKey() == target.storageScopeKey() && it.isUncategorizedTarget()
        }
    }
    val merged = withoutImplicitFallback + target
    return if (fallbackIfEmpty) {
        merged.normalizedStorageTargets(defaultTarget)
    } else {
        merged.dedupedStorageTargets()
    }
}

fun List<StorageTarget>.withoutStorageTarget(
    target: StorageTarget,
    defaultTarget: StorageTarget = StorageTarget.BastionLocal(null),
    /**
     * 移除某 scope 内最后一个 target 时，是否补一个该 scope 的「未分类」target。
     *
     * true（默认）—— Note / Document / BankCard / Wifi / SSH / BillingAddress 等既有
     *   调用方保持原行为：不会因为移除而丢掉这条数据的归属。
     * false —— 密码页 / 验证器页 / 卡包页：允许彻底移除，不做任何兜底补全，
     *   这样用户才能表达"我不要本地副本"（跨库迁移语义）。
     */
    allowScopeFallback: Boolean = true
): List<StorageTarget> {
    val current = normalizedStorageTargets(defaultTarget)
    val remaining = current.filterNot { it.stableKey == target.stableKey }
    if (remaining.size == current.size) return current
    if (!allowScopeFallback) return remaining.dedupedStorageTargets()
    val hasSameScopeTarget = remaining.any { it.storageScopeKey() == target.storageScopeKey() }
    val fallback = target.uncategorizedPeer()
    val next = when {
        hasSameScopeTarget -> remaining
        fallback.stableKey != target.stableKey -> remaining + fallback
        else -> remaining
    }
    return next.normalizedStorageTargets(defaultTarget)
}

fun PasswordEntry.toStorageTarget(): StorageTarget = when {
    bitwardenVaultId != null -> StorageTarget.Bitwarden(
        vaultId = bitwardenVaultId,
        folderId = bitwardenFolderId
    )
    keepassDatabaseId != null -> StorageTarget.KeePass(
        databaseId = keepassDatabaseId,
        groupPath = keepassGroupPath
    )
    else -> StorageTarget.BastionLocal(categoryId = categoryId)
}

fun SecureItem.toStorageTarget(): StorageTarget = when {
    bitwardenVaultId != null -> StorageTarget.Bitwarden(
        vaultId = bitwardenVaultId,
        folderId = bitwardenFolderId
    )
    keepassDatabaseId != null -> StorageTarget.KeePass(
        databaseId = keepassDatabaseId,
        groupPath = keepassGroupPath
    )
    else -> StorageTarget.BastionLocal(categoryId = categoryId)
}

fun StorageTarget.applyToPasswordEntry(
    entry: PasswordEntry,
    replicaGroupId: String? = entry.replicaGroupId
): PasswordEntry {
    return when (this) {
        is StorageTarget.BastionLocal -> entry.copy(
            categoryId = categoryId,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = replicaGroupId
        )
        is StorageTarget.KeePass -> entry.copy(
            categoryId = null,
            keepassDatabaseId = databaseId,
            keepassGroupPath = groupPath,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = replicaGroupId
        )
        is StorageTarget.Bitwarden -> entry.copy(
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = vaultId,
            bitwardenCipherId = null,
            bitwardenFolderId = folderId,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            replicaGroupId = replicaGroupId
        )
    }
}

fun StorageTarget.applyToSecureItem(
    item: SecureItem,
    replicaGroupId: String? = item.replicaGroupId
): SecureItem {
    return when (this) {
        is StorageTarget.BastionLocal -> item.copy(
            categoryId = categoryId,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            syncStatus = "NONE",
            replicaGroupId = replicaGroupId
        )
        is StorageTarget.KeePass -> item.copy(
            categoryId = null,
            keepassDatabaseId = databaseId,
            keepassGroupPath = groupPath,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            syncStatus = "NONE",
            replicaGroupId = replicaGroupId
        )
        is StorageTarget.Bitwarden -> item.copy(
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = vaultId,
            bitwardenCipherId = null,
            bitwardenFolderId = folderId,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            syncStatus = "PENDING",
            replicaGroupId = replicaGroupId
        )
    }
}
