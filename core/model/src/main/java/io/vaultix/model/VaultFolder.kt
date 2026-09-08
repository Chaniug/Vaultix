package io.vaultix.model

import kotlinx.serialization.Serializable

/**
 * 文件夹（Bitwarden folder；解密后明文）。
 *
 * [id] 为服务端 folder id；[name] 为解密后的名称（解密失败降级空串）。
 * 领域模型本身与库种类无关——Bitwarden 来自服务端 `folders` 列表，
 * 未来 KDBX 可映射为分组。
 */
@Serializable
data class VaultFolder(
    val id: String,
    val name: String = "",
)
