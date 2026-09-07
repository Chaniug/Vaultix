package com.bastion.app.domain.provider

data class PasswordCommandPolicy(
    val archiveProviderType: String,
    val shouldMarkPendingRemoteMutation: Boolean,
    val usesRemoteDeleteQueue: Boolean
)
