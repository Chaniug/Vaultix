package com.bastion.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.screens.GeneratorScreen
import com.bastion.app.viewmodel.GeneratorType
import com.bastion.app.viewmodel.GeneratorViewModel
import com.bastion.app.viewmodel.PasswordViewModel

@Composable
internal fun GeneratorPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    generatorViewModel: GeneratorViewModel,
    passwordViewModel: PasswordViewModel,
    externalRefreshRequestKey: Int,
    onRefreshRequestConsumed: () -> Unit,
    selectedGenerator: GeneratorType,
    generatedValue: String,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {}
) {
    if (isCompactWidth) {
        GeneratorScreen(
            onNavigateBack = {},
            viewModel = generatorViewModel,
            passwordViewModel = passwordViewModel,
            externalRefreshRequestKey = externalRefreshRequestKey,
            onRefreshRequestConsumed = onRefreshRequestConsumed,
            useExternalRefreshFab = true,
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings
        )
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            ListPane(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(wideListPaneWidth)
            ) {
                GeneratorScreen(
                    onNavigateBack = {},
                    viewModel = generatorViewModel,
                    passwordViewModel = passwordViewModel,
                    externalRefreshRequestKey = externalRefreshRequestKey,
                    onRefreshRequestConsumed = onRefreshRequestConsumed,
                    useExternalRefreshFab = true,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            DetailPane(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                GeneratorDetailPane(
                    selectedGenerator = selectedGenerator,
                    generatedValue = generatedValue,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
