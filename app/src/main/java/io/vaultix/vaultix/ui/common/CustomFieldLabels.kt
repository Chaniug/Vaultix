package io.vaultix.vaultix.ui.common

import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultLinkedId
import io.vaultix.vaultix.R

/**
 * 自定义字段相关的**标签映射**（详情页展示与编辑表单共用，避免两处各写一份）。
 *
 * 用映射表而非 `when`：Bitwarden 官方 linkedId 是 100/300/400 三段共 27 个值，
 * 写成 when 会让圈复杂度直接爆掉门禁（>14）。
 */

/** 关联字段编号 → 标准字段名资源（按 Bitwarden 官方分段编码）。 */
val LINKED_FIELD_LABELS: Map<VaultLinkedId, Int> = mapOf(
    VaultLinkedId.LoginUsername to R.string.item_field_username,
    VaultLinkedId.LoginPassword to R.string.item_field_password,
    VaultLinkedId.CardCardholderName to R.string.card_cardholder,
    VaultLinkedId.CardBrand to R.string.card_brand,
    VaultLinkedId.CardNumber to R.string.card_number,
    VaultLinkedId.CardExpMonth to R.string.card_exp_month,
    VaultLinkedId.CardExpYear to R.string.card_exp_year,
    VaultLinkedId.CardCode to R.string.card_cvv,
    VaultLinkedId.IdentityTitle to R.string.identity_title,
    VaultLinkedId.IdentityMiddleName to R.string.identity_middle_name,
    VaultLinkedId.IdentityAddress1 to R.string.identity_address1,
    VaultLinkedId.IdentityAddress2 to R.string.identity_address2,
    VaultLinkedId.IdentityAddress3 to R.string.identity_address3,
    VaultLinkedId.IdentityCity to R.string.identity_city,
    VaultLinkedId.IdentityState to R.string.identity_state,
    VaultLinkedId.IdentityPostalCode to R.string.identity_postal_code,
    VaultLinkedId.IdentityCountry to R.string.identity_country,
    VaultLinkedId.IdentityCompany to R.string.identity_company,
    VaultLinkedId.IdentityEmail to R.string.identity_email,
    VaultLinkedId.IdentityPhone to R.string.identity_phone,
    VaultLinkedId.IdentitySsn to R.string.identity_ssn,
    VaultLinkedId.IdentityUsername to R.string.identity_username,
    VaultLinkedId.IdentityPassportNumber to R.string.identity_passport,
    VaultLinkedId.IdentityLicenseNumber to R.string.identity_license,
    VaultLinkedId.IdentityFirstName to R.string.identity_first_name,
    VaultLinkedId.IdentityLastName to R.string.identity_last_name,
    VaultLinkedId.IdentityFullName to R.string.identity_full_name,
)

/** 自定义字段类型 → 类型名资源（对齐 Bitwarden 官方「添加自定义字段」的四种）。 */
val CUSTOM_FIELD_TYPE_LABELS: Map<CustomFieldType, Int> = mapOf(
    CustomFieldType.Text to R.string.custom_field_type_text,
    CustomFieldType.Hidden to R.string.custom_field_type_hidden,
    CustomFieldType.Boolean to R.string.custom_field_type_boolean,
    CustomFieldType.Linked to R.string.custom_field_type_linked,
)
