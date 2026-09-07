package io.vaultix.model

/**
 * 密码库类型（对应 VaultEntity.kind 的枚举名）。
 *
 * BITWARDEN = 云端同步库；KDBX = 本地文件库（M2）。
 */
enum class VaultKind {
    BITWARDEN,
    KDBX,
    ;

    companion object {
        /** 从 Room `vaults.kind` 字符串解析（BITWARDEN / KDBX）；未知值返回 null 由上层处理。 */
        fun fromName(name: String?): VaultKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * UI 面向的库摘要（由 VaultRepository 聚合 Room 行 + 会话状态产生）。
 *
 * @param account 账号标签（Bitwarden = 邮箱；KDBX 为 null），如 S3 规格的
 *                「Bitwarden · alice@mail.com」。
 * @param unlocked 当前是否已解锁（内存会话存在且密钥未清零）。
 */
data class VaultSummary(
    val id: String,
    val kind: VaultKind,
    val name: String,
    val account: String? = null,
    val origin: String,
    val unlocked: Boolean = false,
)
