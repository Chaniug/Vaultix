package io.vaultix.model

import kotlinx.serialization.Serializable

/**
 * 统一领域模型：VaultItem（依据 Docs/02 统一领域模型）。
 * 同时承载 Bitwarden 与 KDBX 两种库格式的抽象，字段映射由各数据源 Mapper 完成。
 *
 * ⚠️ 明文承载：仅存在于已解锁的内存中，禁止落盘、禁止进日志（Docs/09）。
 * [password] 于 2026-09-08 补入（M1 UI 新建条目需要）；补字段必须同步补 Mapper
 * （CipherMapper 与未来的 KDBX Mapper），见 MEMORY「保真度三级」约定。
 */
@Serializable
data class VaultItem(
    val id: String,
    val title: String,
    val username: String = "",
    val password: String = "",
    val notes: String = "",
    val type: VaultItemType = VaultItemType.Login,
)

enum class VaultItemType { Login, SecureNote, Card, Identity }
