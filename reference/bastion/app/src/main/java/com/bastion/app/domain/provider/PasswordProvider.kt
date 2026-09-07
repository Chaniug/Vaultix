package com.bastion.app.domain.provider

import com.bastion.app.data.PasswordEntry
import com.bastion.app.ui.model.SecretValueState

interface PasswordProvider {
    fun supports(entry: PasswordEntry): Boolean
    fun sourceOf(entry: PasswordEntry): PasswordSource
    fun inspectSecret(entry: PasswordEntry): SecretValueState
    fun commandPolicy(entry: PasswordEntry): PasswordCommandPolicy
    fun resolvePasswordForStorage(
        existingEntry: PasswordEntry?,
        pendingEntry: PasswordEntry,
        incomingPassword: String
    ): String
}
