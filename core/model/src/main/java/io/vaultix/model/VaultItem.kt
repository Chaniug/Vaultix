package io.vaultix.model

import kotlinx.serialization.Serializable

/**
 * 统一领域模型：VaultItem（依据 Docs/02 统一领域模型）。
 * 同时承载 Bitwarden 与 KDBX 两种库格式的抽象，字段映射由各数据源 Mapper 完成。
 */
@Serializable
data class VaultItem(
    val id: String,
    val title: String,
    val username: String = "",
    val notes: String = "",
    val type: VaultItemType = VaultItemType.Login,
)

enum class VaultItemType { Login, SecureNote, Card, Identity }
