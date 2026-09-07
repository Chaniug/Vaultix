package com.bastion.app.autofill_ng.model

import com.bastion.app.data.PasswordEntry

/**
 * Bitwarden-compatible cipher model (login only in Bastion v1).
 */
sealed class AutofillCipher {
    abstract val cipherId: String?
    abstract val name: String
    abstract val subtitle: String

    data class Login(
        override val cipherId: String?,
        override val name: String,
        override val subtitle: String,
        val username: String,
        val password: String,
        val website: String,
        val appPackageName: String?,
    ) : AutofillCipher()
}

fun PasswordEntry.toAutofillCipherLogin(
    fallbackWebsite: String,
    usernameValue: String = username,
    passwordValue: String = password,
): AutofillCipher.Login {
    val websiteValue = website.takeIf { it.isNotBlank() } ?: fallbackWebsite
    val titleValue = title
        .takeIf { it.isNotBlank() }
        ?: usernameValue.takeIf { it.isNotBlank() }
        ?: websiteValue.takeIf { it.isNotBlank() }
        ?: "Credential"
    val subtitleValue = usernameValue
        .takeIf { it.isNotBlank() }
        ?: websiteValue.takeIf { it.isNotBlank() }
        ?: titleValue
    return AutofillCipher.Login(
        cipherId = id.toString(),
        name = titleValue,
        subtitle = subtitleValue,
        username = usernameValue,
        password = passwordValue,
        website = websiteValue,
        appPackageName = appPackageName.takeIf { it.isNotBlank() }
    )
}
