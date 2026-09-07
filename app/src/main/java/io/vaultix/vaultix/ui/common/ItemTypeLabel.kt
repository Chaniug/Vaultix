package io.vaultix.vaultix.ui.common

import androidx.annotation.StringRes
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R

/**
 * 条目类型徽标文案（列表行/详情页通用）。
 *
 * M1 范围：只做**类型标识**与编辑门禁提示；card/identity/sshKey 的专属字段
 * 展示与编辑推迟（Docs/progress/audit/bitwarden-alignment.md M2-1）。
 */
@StringRes
fun itemTypeLabelRes(type: VaultItemType): Int = when (type) {
    VaultItemType.Login -> R.string.item_type_login
    VaultItemType.SecureNote -> R.string.item_type_secure_note
    VaultItemType.Card -> R.string.item_type_card
    VaultItemType.Identity -> R.string.item_type_identity
    VaultItemType.SshKey -> R.string.item_type_ssh_key
}
