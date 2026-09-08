package io.vaultix.model

/**
 * 关联字段编号（对齐 Bitwarden `LinkedIdType`）。
 *
 * 用于 [VaultCustomField.type] == [CustomFieldType.Linked] 的自定义字段：
 * 该字段本身不存值，而是指向条目的某个**标准字段**（如用户名、卡号），
 * 展示时显示被指字段的名称与值。
 *
 * ⚠️ **编码是分段的，不是 1/2/3 的顺序编号**（这是最容易踩的坑）：
 * - 100 段 = 登录类（Login）
 * - 300 段 = 银行卡类（Card）
 * - 400 段 = 身份类（Identity）
 *
 * 若误用顺序编号，服务端下发 `linkedId = 100`（官方「用户名」）时本端匹配不到，
 * Linked 字段就会退化成「未知关联字段」，等于丢信息。
 *
 * 溯源：GPL-3.0，编码取自 Bitwarden 官方 `LinkedIdType`
 * （bitwarden/clients · src/Core/Enums/LinkedIdType.cs，官方 Rust SDK
 * bitwarden-vault/cipher/linked_id.rs 同值）。
 */
enum class VaultLinkedId(val code: Int) {
    // ---- 登录类（100 段）----
    LoginUsername(code = 100),
    LoginPassword(code = 101),

    // ---- 银行卡类（300 段）----
    CardCardholderName(code = 300),
    CardExpMonth(code = 301),
    CardExpYear(code = 302),
    CardCode(code = 303),
    CardBrand(code = 304),
    CardNumber(code = 305),

    // ---- 身份类（400 段）----
    IdentityTitle(code = 400),
    IdentityMiddleName(code = 401),
    IdentityAddress1(code = 402),
    IdentityAddress2(code = 403),
    IdentityAddress3(code = 404),
    IdentityCity(code = 405),
    IdentityState(code = 406),
    IdentityPostalCode(code = 407),
    IdentityCountry(code = 408),
    IdentityCompany(code = 409),
    IdentityEmail(code = 410),
    IdentityPhone(code = 411),
    IdentitySsn(code = 412),
    IdentityUsername(code = 413),
    IdentityPassportNumber(code = 414),
    IdentityLicenseNumber(code = 415),
    IdentityFirstName(code = 416),
    IdentityLastName(code = 417),
    IdentityFullName(code = 418),
    ;

    companion object {
        /**
         * 服务端编号 → 枚举；未知编号（含 null）返回 null，
         * 由调用方决定降级展示（不要抛异常，个别条目损坏不应让列表加载失败）。
         */
        fun fromCode(code: Int?): VaultLinkedId? = entries.firstOrNull { it.code == code }
    }
}
