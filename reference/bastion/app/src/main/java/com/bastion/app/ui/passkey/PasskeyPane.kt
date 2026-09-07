package com.bastion.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.passkey.managementRecordIdOrNull
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.screens.PasskeyListScreen
import com.bastion.app.viewmodel.PasskeyViewModel
import com.bastion.app.viewmodel.PasswordViewModel

@Composable
internal fun PasskeyPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    passkeyViewModel: PasskeyViewModel,
    passwordViewModel: PasswordViewModel,
    onNavigateToPasswordDetail: (Long) -> Unit,
    onNavigateToPasskeyDetail: (Long) -> Unit,
    onPasskeyOpen: (PasskeyEntry) -> Unit,
    selectedPasskey: PasskeyEntry?,
    passkeyTotalCount: Int,
    passkeyBoundCount: Int,
    resolvePasswordTitle: (Long) -> String?,
    onOpenPasswordDetail: (Long) -> Unit,
    onUnbindPasskey: (PasskeyEntry) -> Unit,
    onDeletePasskey: (PasskeyEntry) -> Unit,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    onNavigateToAuthenticator: () -> Unit
) {
    if (isCompactWidth) {
        PasskeyListScreen(
            viewModel = passkeyViewModel,
            passwordViewModel = passwordViewModel,
            onNavigateToPasswordDetail = onNavigateToPasswordDetail,
            onPasskeyClick = { passkey ->
                passkey.managementRecordIdOrNull()?.let(onNavigateToPasskeyDetail)
            },
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings,
            onNavigateToAuthenticator = onNavigateToAuthenticator
        )
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            ListPane(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(wideListPaneWidth)
            ) {
                PasskeyListScreen(
                    viewModel = passkeyViewModel,
                    passwordViewModel = passwordViewModel,
                    onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                    onPasskeyClick = onPasskeyOpen,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    onNavigateToAuthenticator = onNavigateToAuthenticator
                )
            }
            DetailPane(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val passkey = selectedPasskey
                if (passkey == null) {
                    PasskeyOverviewPane(
                        totalPasskeys = passkeyTotalCount,
                        boundPasskeys = passkeyBoundCount,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val boundPasswordTitle = passkey.boundPasswordId?.let(resolvePasswordTitle)
                    PasskeyDetailPane(
                        passkey = passkey,
                        boundPasswordTitle = boundPasswordTitle,
                        onOpenBoundPassword = passkey.boundPasswordId?.let { boundId ->
                            { onOpenPasswordDetail(boundId) }
                        },
                        onUnbindPassword = if (passkey.boundPasswordId != null) {
                            { onUnbindPasskey(passkey) }
                        } else {
                            null
                        },
                        onDeletePasskey = { onDeletePasskey(passkey) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
