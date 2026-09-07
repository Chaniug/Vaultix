package com.bastion.app.keepass

import java.util.Locale

enum class KeePassFieldRole {
    STANDARD,
    MONICA_PASSWORD,
    MONICA_SECURE_ITEM,
    MONICA_PASSKEY,
    KEEPASS_TOTP,
    KEEPASS_PASSKEY,
    KEEPASS_PLUGIN,
    UNKNOWN
}

object KeePassFieldRegistry {
    private val passwordEntryOverlayFields = setOf(
        "Title", "UserName", "Password", "URL", "Notes",
        "BastionLocalId",
        "BastionConflictCopy",
        "App Package Name", "App Name",
        "Email", "Phone",
        "Address", "City", "State", "Postal Code", "Country",
        "Card Number", "Card Holder", "Card Expiry", "Card CVV",
        "SSO Provider", "BastionSsoRefEntryId",
        "BastionLoginType", "SSID", "BastionWifiData",
        "BastionSshAlgorithm", "BastionSshKeySize", "BastionSshPublicKey",
        "BastionSshPrivateKey", "BastionSshFingerprint", "BastionSshComment", "BastionSshFormat"
    )

    private val secureItemOverlayFields = setOf(
        "Title", "UserName", "Password", "URL", "Notes",
        "BastionSecureItemId",
        "BastionConflictCopy",
        "BastionItemType",
        "BastionItemData",
        "BastionImagePaths",
        "BastionIsFavorite",
        "Card Number", "CardNumber", "Credit Card Number", "CreditCardNumber",
        "Card Holder", "CardHolder", "Credit Card Holder", "CreditCardHolder",
        "Card Expiry", "CardExpiry", "Expiration Date", "Expiry Date",
        "Card CVV", "CardCVV", "CVV", "CVC",
        "Expiry Month", "Expiry Year",
        "Bank Name",
        "Card Type",
        "Billing Address",
        "Brand",
        "Nickname",
        "Valid From Month",
        "Valid From Year",
        "PIN",
        "IBAN",
        "SWIFT/BIC",
        "Routing Number",
        "Account Number",
        "Branch Code",
        "Currency",
        "Customer Service Phone"
    )

    private val passkeyEntryOverlayFields = setOf(
        "Title", "UserName", "Password", "URL", "Notes",
        "BastionConflictCopy",
        "BastionkeyCredentialId",
        "BastionkeyData",
        "BastionkeyMode",
        KeePassDxPasskeyCodec.FIELD_PASSKEY,
        KeePassDxPasskeyCodec.FIELD_USERNAME,
        KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY,
        KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID,
        KeePassDxPasskeyCodec.FIELD_USER_HANDLE,
        KeePassDxPasskeyCodec.FIELD_RELYING_PARTY,
        KeePassDxPasskeyCodec.FIELD_FLAG_BE,
        KeePassDxPasskeyCodec.FIELD_FLAG_BS
    )

    private val standardFields = setOf(
        "Title", "Name",
        "UserName", "Username", "User", "Login",
        "Password", "Pass", "pass", "pwd", "PWD", "密码", "口令",
        "URL", "Url", "Website", "URI",
        "Notes", "Note", "Comment"
    )

    private val bastionPasswordFields = setOf(
        "BastionLocalId",
        "BastionConflictCopy",
        "App Package Name", "AppPackageName", "BastionAppPackageName",
        "App Name", "AppName", "BastionAppName",
        // 旧 Monica Pass 遗留 + 与小写 bastion_* 兼容键 + keepass2android 原生绑定。
        "monica_app_package", "monica_app_name", "MonicaAppPackageName", "MonicaAppName",
        "bastion_app_package", "bastion_app_name", "KP2A_APP", "KP2A_APP_NAME",
        "Email", "E-mail", "Mail",
        "Phone", "Phone Number", "Telephone",
        "Address", "Address Line",
        "City", "State", "Province", "Postal Code", "PostalCode", "Zip Code", "ZipCode", "Country",
        "Card Number", "CardNumber", "Credit Card Number", "CreditCardNumber",
        "Card Holder", "CardHolder", "Credit Card Holder", "CreditCardHolder",
        "Card Expiry", "CardExpiry", "Expiration Date", "Expiry Date",
        "Card CVV", "CardCVV", "CVV", "CVC",
        "Expiry Month", "Expiry Year",
        "SSO Provider", "SsoProvider", "BastionSsoProvider", "BastionSsoRefEntryId",
        "SSID", "BastionWifiData", "BastionLoginType",
        "BastionSshAlgorithm", "BastionSshKeySize", "BastionSshPublicKey",
        "BastionSshPrivateKey", "BastionSshFingerprint", "BastionSshComment", "BastionSshFormat"
    )

    private val bastionSecureItemFields = setOf(
        "BastionSecureItemId",
        "BastionConflictCopy",
        "BastionItemType",
        "BastionItemData",
        "BastionImagePaths",
        "BastionIsFavorite",
        "Bank Name",
        "Card Type",
        "Billing Address",
        "Brand",
        "Nickname",
        "Valid From Month",
        "Valid From Year",
        "PIN",
        "IBAN",
        "SWIFT/BIC",
        "Routing Number",
        "Account Number",
        "Branch Code",
        "Currency",
        "Customer Service Phone"
    )

    private val bastionPasskeyFields = setOf(
        "BastionkeyCredentialId",
        "BastionkeyData",
        "BastionkeyMode",
        "BastionConflictCopy"
    )

    private val keepPassTotpFields = setOf(
        "otp",
        "TOTP Seed",
        "TOTPSeed",
        "TOTP Settings",
        "TOTPSettings",
        "TOTP Period",
        "TOTP Digits",
        "TOTP Algorithm",
        "OTP Type",
        "TOTP Type",
        "HOTP Counter"
    )

    private val keepPassPasskeyFields = setOf(
        KeePassDxPasskeyCodec.FIELD_PASSKEY,
        KeePassDxPasskeyCodec.FIELD_USERNAME,
        KeePassDxPasskeyCodec.FIELD_PRIVATE_KEY,
        KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID,
        KeePassDxPasskeyCodec.FIELD_USER_HANDLE,
        KeePassDxPasskeyCodec.FIELD_RELYING_PARTY,
        KeePassDxPasskeyCodec.FIELD_FLAG_BE,
        KeePassDxPasskeyCodec.FIELD_FLAG_BS
    )

    private val standardFieldKeys = normalizedSet(standardFields)
    private val bastionPasswordFieldKeys = normalizedSet(bastionPasswordFields)
    private val bastionSecureItemFieldKeys = normalizedSet(bastionSecureItemFields)
    private val bastionPasskeyFieldKeys = normalizedSet(bastionPasskeyFields)
    private val keepPassTotpFieldKeys = normalizedSet(keepPassTotpFields)
    private val keepPassPasskeyFieldKeys = normalizedSet(keepPassPasskeyFields)
    private val passwordEntryOverlayFieldKeys = normalizedSet(passwordEntryOverlayFields)
    private val secureItemOverlayFieldKeys = normalizedSet(secureItemOverlayFields)
    private val passkeyEntryOverlayFieldKeys = normalizedSet(passkeyEntryOverlayFields)

    fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)

    fun roleOf(name: String): KeePassFieldRole {
        val key = normalize(name)
        if (key.isBlank()) return KeePassFieldRole.UNKNOWN
        return when {
            key.startsWith("_etm_") -> KeePassFieldRole.KEEPASS_PLUGIN
            key in standardFieldKeys -> KeePassFieldRole.STANDARD
            key in bastionPasswordFieldKeys -> KeePassFieldRole.MONICA_PASSWORD
            key in bastionSecureItemFieldKeys -> KeePassFieldRole.MONICA_SECURE_ITEM
            key in bastionPasskeyFieldKeys -> KeePassFieldRole.MONICA_PASSKEY
            key in keepPassTotpFieldKeys -> KeePassFieldRole.KEEPASS_TOTP
            key in keepPassPasskeyFieldKeys -> KeePassFieldRole.KEEPASS_PASSKEY
            else -> KeePassFieldRole.UNKNOWN
        }
    }

    fun isBastionOwned(name: String): Boolean {
        return when (roleOf(name)) {
            KeePassFieldRole.MONICA_PASSWORD,
            KeePassFieldRole.MONICA_SECURE_ITEM,
            KeePassFieldRole.MONICA_PASSKEY -> true
            else -> false
        }
    }

    fun isPreservedByDefault(name: String): Boolean {
        return !isBastionOwned(name)
    }

    fun isReservedPasswordProjectionField(name: String): Boolean {
        return roleOf(name) != KeePassFieldRole.UNKNOWN
    }

    fun isPasswordEntryOverlayField(name: String): Boolean {
        val key = normalize(name)
        return key.isNotBlank() && key in passwordEntryOverlayFieldKeys
    }

    fun isSecureItemOverlayField(name: String): Boolean {
        val key = normalize(name)
        return key.isNotBlank() && key in secureItemOverlayFieldKeys
    }

    fun isPasskeyEntryOverlayField(name: String): Boolean {
        val key = normalize(name)
        return key.isNotBlank() && key in passkeyEntryOverlayFieldKeys
    }

    fun isKeePassTotpField(name: String): Boolean {
        return roleOf(name) == KeePassFieldRole.KEEPASS_TOTP
    }

    fun isPasswordSecretFallbackCandidateField(name: String): Boolean {
        return roleOf(name) == KeePassFieldRole.UNKNOWN
    }

    private fun normalizedSet(values: Set<String>): Set<String> {
        return values.mapTo(mutableSetOf(), ::normalize)
    }
}
