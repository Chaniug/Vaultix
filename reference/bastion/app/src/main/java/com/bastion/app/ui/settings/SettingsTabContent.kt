package com.bastion.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bastion.app.R
import com.bastion.app.data.AppSettings
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.InspectorRow
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.screens.SettingsScreen
import com.bastion.app.viewmodel.SettingsViewModel

@Composable
internal fun SettingsTabContent(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    viewModel: SettingsViewModel,
    onResetPassword: () -> Unit,
    onSecurityQuestions: () -> Unit,
    onNavigateToMasterPasswordLocking: () -> Unit,
    onNavigateToSyncBackup: () -> Unit,
    onNavigateToAutofill: () -> Unit,
    onNavigateToPasskeySettings: () -> Unit,
    onSecurityAnalysis: () -> Unit,
    onNavigateToDeveloperSettings: () -> Unit,
    onNavigateToPermissionManagement: () -> Unit,
    onNavigateToExtensions: () -> Unit,
    onNavigateToPageCustomization: () -> Unit,
    onNavigateToThemeAndColorScheme: () -> Unit = {},
    onClearAllData: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current
    var selectedSection by remember { mutableStateOf<String?>(null) }
    val settings by viewModel.settings.collectAsState()

    val settingsListContent: @Composable () -> Unit = {
        SettingsScreen(
            viewModel = viewModel,
            onNavigateBack = {},
            onResetPassword = onResetPassword,
            onSecurityQuestions = onSecurityQuestions,
            onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
            onNavigateToSyncBackup = onNavigateToSyncBackup,
            onNavigateToAutofill = onNavigateToAutofill,
            onNavigateToPasskeySettings = onNavigateToPasskeySettings,
            onSecurityAnalysis = onSecurityAnalysis,
            onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
            onNavigateToPermissionManagement = onNavigateToPermissionManagement,
            onNavigateToExtensions = onNavigateToExtensions,
            onNavigateToPageCustomization = onNavigateToPageCustomization,
            onNavigateToThemeAndColorScheme = onNavigateToThemeAndColorScheme,
            onClearAllData = onClearAllData,
            showTopBar = false,
            onSectionSelected = if (isCompactWidth) null else { section -> selectedSection = section }
        )
    }

    if (isCompactWidth) {
        Box(modifier = Modifier.fillMaxSize()) {
            settingsListContent()
        }
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            ListPane(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(wideListPaneWidth)
            ) {
                settingsListContent()
            }
            DetailPane(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (selectedSection != null) {
                    SectionDetailCard(
                        sectionTitle = selectedSection!!,
                        settings = settings,
                        context = context
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.settings_detail_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionDetailCard(
    sectionTitle: String,
    settings: AppSettings,
    context: android.content.Context
) {
    val detailItems = buildSectionDetailItems(sectionTitle, settings, context)
    if (detailItems.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = sectionTitle,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))
        detailItems.forEach { item ->
            InspectorRow(
                label = item.label,
                value = item.value
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private data class DetailItem(val label: String, val value: String)

private fun buildSectionDetailItems(
    sectionTitle: String,
    settings: AppSettings,
    context: android.content.Context
): List<DetailItem> {
    return when (sectionTitle) {
        context.getString(R.string.security) -> buildSecurityDetails(settings, context)
        context.getString(R.string.data_management) -> buildDataManagementDetails(settings, context)
        context.getString(R.string.theme) -> buildAppearanceDetails(settings, context)
        context.getString(R.string.about) -> buildAboutDetails(settings, context)
        else -> emptyList()
    }
}

private fun buildSecurityDetails(settings: AppSettings, context: android.content.Context): List<DetailItem> {
    val items = mutableListOf<DetailItem>()
    items.add(DetailItem(
        context.getString(R.string.master_password_and_locking),
        context.getString(R.string.master_password_and_locking_description)
    ))
    items.add(DetailItem(
        context.getString(R.string.screenshot_protection),
        if (settings.screenshotProtectionEnabled) "开启" else "关闭"
    ))
    items.add(DetailItem(
        context.getString(R.string.permission_management_title),
        context.getString(R.string.permission_management_subtitle)
    ))
    return items
}

private fun buildDataManagementDetails(settings: AppSettings, context: android.content.Context): List<DetailItem> {
    val items = mutableListOf<DetailItem>()
    items.add(DetailItem(
        context.getString(R.string.sync_backup_title),
        context.getString(R.string.sync_backup_description)
    ))
    items.add(DetailItem(
        context.getString(R.string.autofill),
        context.getString(R.string.autofill_subtitle)
    ))
    items.add(DetailItem(
        context.getString(R.string.clear_all_data),
        context.getString(R.string.clear_all_data_subtitle)
    ))
    return items
}

private fun buildAppearanceDetails(settings: AppSettings, context: android.content.Context): List<DetailItem> {
    val items = mutableListOf<DetailItem>()
    val themeName = when (settings.themeMode) {
        com.bastion.app.data.ThemeMode.SYSTEM -> context.getString(R.string.theme_system)
        com.bastion.app.data.ThemeMode.LIGHT -> context.getString(R.string.theme_light)
        com.bastion.app.data.ThemeMode.DARK -> context.getString(R.string.theme_dark)
    }
    items.add(DetailItem(context.getString(R.string.theme), themeName))
    items.add(DetailItem(
        context.getString(R.string.color_scheme),
        settings.colorScheme.name
    ))
    items.add(DetailItem(
        context.getString(R.string.bottom_nav_settings),
        context.getString(R.string.bottom_nav_settings_entry_subtitle)
    ))
    return items
}

private fun buildAboutDetails(settings: AppSettings, context: android.content.Context): List<DetailItem> {
    val items = mutableListOf<DetailItem>()
    items.add(DetailItem(
        context.getString(R.string.version),
        com.bastion.app.BuildConfig.VERSION_NAME.ifBlank { com.bastion.app.BuildConfig.FULL_VERSION_NAME }
    ))
    return items
}
