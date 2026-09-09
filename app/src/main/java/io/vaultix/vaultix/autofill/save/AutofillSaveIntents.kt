/*
 * Vaultix — app:autofill · save
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.save

import android.content.Context
import android.content.Intent

/** Service → AutofillSaveActivity 的 Intent 约定（集中于此，避免两侧 key 漂移）。 */
object AutofillSaveIntents {

    const val EXTRA_USERNAME = "vaultix.save.username"
    const val EXTRA_PASSWORD = "vaultix.save.password"
    const val EXTRA_PACKAGE = "vaultix.save.package"
    const val EXTRA_DOMAIN = "vaultix.save.domain"

    fun create(
        context: Context,
        packageName: String?,
        webDomain: String?,
        username: String,
        password: String,
    ): Intent = Intent(context, AutofillSaveActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_USERNAME, username)
        .putExtra(EXTRA_PASSWORD, password)
        .putExtra(EXTRA_PACKAGE, packageName)
        .putExtra(EXTRA_DOMAIN, webDomain)

    fun usernameOf(intent: Intent): String = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
    fun passwordOf(intent: Intent): String = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
    fun packageNameOf(intent: Intent): String? = intent.getStringExtra(EXTRA_PACKAGE)
    fun webDomainOf(intent: Intent): String? = intent.getStringExtra(EXTRA_DOMAIN)
}
