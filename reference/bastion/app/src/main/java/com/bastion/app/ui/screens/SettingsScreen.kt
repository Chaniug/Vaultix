@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui.screens

import com.bastion.app.logging.runCatchingObserved
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.graphics.Bitmap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.bastion.app.BuildConfig
import com.bastion.app.R
import com.bastion.app.data.AppSettings
import com.bastion.app.data.Language
import com.bastion.app.data.ItemType
import com.bastion.app.ui.components.TrashSettingsSheet
import com.bastion.app.data.ThemeMode
import com.bastion.app.ui.components.M3IdentityVerifyDialog
import com.bastion.app.ui.components.MarkdownPreviewText
import com.bastion.app.ui.components.UnifiedMoveAction
import com.bastion.app.ui.password.PasswordBatchDeleteGlobalProgressState
import com.bastion.app.ui.password.PasswordBatchDeleteProgressTracker
import com.bastion.app.ui.password.PasswordBatchTransferGlobalProgressState
import com.bastion.app.ui.password.PasswordBatchTransferProgressTracker
import com.bastion.app.utils.BiometricAuthHelper
import com.bastion.app.utils.UpdateChannel
import com.bastion.app.utils.UpdateCheckResult
import com.bastion.app.utils.UpdateChecker
import com.bastion.app.utils.UpdateDownloadProgress
import com.bastion.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import com.bastion.app.data.SecureItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.bastion.app.ui.components.OutlinedTextField
import java.io.File
import java.util.Locale

/**
 * 「清空所有数据」的多通道密码验证：
 * 1) 优先验证本地主密码（SecurityManager）；
 * 2) 若本地密码不匹配但已连接 Bitwarden，则用 Bitwarden 主密码验证（走 KDF 派生对比）。
 * 这样在纯 Bitwarden 模式下，用户用 Bitwarden 主密码也能通过清空校验。
 */
private suspend fun verifyClearDataPassword(context: android.content.Context, password: String): Boolean {
    val sm = com.bastion.app.security.SecurityManager.instance(context)
    if (sm.verifyMasterPassword(password)) return true
    return try {
        val bw = com.bastion.app.bitwarden.repository.BitwardenRepository.getInstance(context)
        bw.getAllVaults().any { vault ->
            bw.unlock(vault.id, password) is com.bastion.app.bitwarden.repository.BitwardenRepository.UnlockResult.Success
        }
    } catch (e: Exception) {
        false
    }
}

private const val UPDATE_APK_DIR = "update_apk"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onResetPassword: () -> Unit,
    onSecurityQuestions: () -> Unit,
    onNavigateToMasterPasswordLocking: () -> Unit,
    onNavigateToSyncBackup: () -> Unit = {},
    onNavigateToAutofill: () -> Unit = {},
    onNavigateToPasskeySettings: () -> Unit = {},
    onNavigateToThemeAndColorScheme: () -> Unit = {},
    onSecurityAnalysis: () -> Unit = {},
    onNavigateToDeveloperSettings: () -> Unit = {},
    onNavigateToPermissionManagement: () -> Unit = {},
    onNavigateToExtensions: () -> Unit = {},
    onNavigateToPageCustomization: () -> Unit = {},
    onClearAllData: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _, _, _ -> },
    showTopBar: Boolean = true,  // 添加参数控制是否显示顶栏
    onSectionSelected: ((String) -> Unit)? = null  // 宽屏模式下 section 被选中时回调
) {
    val context = LocalContext.current
    val openExternalLink: (String) -> Unit = { url ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.cannot_open_browser),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    // 直接使用 LocalContext.current as? ComponentActivity 获取 Activity
    val activity = context as? FragmentActivity
    
    val settings by viewModel.settings.collectAsState()
    val batchDeleteProgress by PasswordBatchDeleteProgressTracker.progress.collectAsState()
    val batchTransferProgress by PasswordBatchTransferProgressTracker.progress.collectAsState()
    val totpItems by viewModel.totpItems.collectAsState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var showClearDataDialog by remember { mutableStateOf(false) }
    var clearDataPasswordInput by remember { mutableStateOf("") }

    var showUpdateCheckDialog by remember { mutableStateOf(false) }
    var updateChannel by remember { mutableStateOf(UpdateChannel.STABLE) }
    // 下载方式：false=应用内直接下载安装；true=用浏览器打开 Release 页面
    var downloadViaBrowser by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableStateOf<UpdateDownloadProgress?>(null) }
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var updateCheckError by remember { mutableStateOf<String?>(null) }
    var lastUpdateCheckAt by remember { mutableStateOf<Long?>(null) }
    var showDeveloperVerifyDialog by remember { mutableStateOf(false) }
    var previewFeaturesExpanded by remember { mutableStateOf(false) }
    var developerPasswordInput by remember { mutableStateOf("") }
    var developerPasswordError by remember { mutableStateOf(false) }
    var showWeakBiometricWarning by remember { mutableStateOf(false) }
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    var advancedSectionExpanded by rememberSaveable { mutableStateOf(false) }

    // 预览渠道用构建时间戳（CI 的 versionCode 即构建时刻的 Unix 秒）对比是否更新
    val currentVersionCode = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
    }

    val startUpdateCheck: () -> Unit = {
        if (!isCheckingUpdate) {
            isCheckingUpdate = true
            updateCheckResult = null
            updateCheckError = null
            coroutineScope.launch {
                val currentVersion = BuildConfig.VERSION_NAME.ifBlank { BuildConfig.FULL_VERSION_NAME }
                UpdateChecker.checkForUpdate(currentVersion, currentVersionCode, updateChannel)
                    .onSuccess { result ->
                        updateCheckResult = result
                        showUpdateCheckDialog = true
                    }
                    .onFailure { error ->
                        updateCheckError = error.localizedMessage
                            ?: context.getString(R.string.update_check_failed_unknown)
                        showUpdateCheckDialog = true
                    }
                lastUpdateCheckAt = System.currentTimeMillis()
                isCheckingUpdate = false
            }
        }
    }

    val startUpdateDownload: (UpdateCheckResult) -> Unit = download@ { result ->
        val downloadUrl = result.apkDownloadUrl
        if (downloadUrl.isNullOrBlank()) {
            openExternalLink(result.releaseUrl)
            return@download
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                context,
                context.getString(R.string.update_install_permission_required),
                Toast.LENGTH_LONG
            ).show()
            runCatchingObserved {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
            return@download
        }

        if (!isDownloadingUpdate) {
            isDownloadingUpdate = true
            updateDownloadProgress = null
            coroutineScope.launch {
                val apkName = result.apkAssetName ?: "Bastion-${result.latestVersion}.apk"
                val outputDir = File(context.cacheDir, UPDATE_APK_DIR)
                // 候选下载源：原始 GitHub 在前，国内镜像在后。downloadApk 会按顺序尝试，
                // 某个源失败自动切下一个，用户始终只点一个下载按钮。
                val candidateUrls = buildList {
                    result.apkDownloadUrl?.let { add(it) }
                    addAll(result.mirrorDownloadUrls)
                }.filter { it.isNotBlank() }
                UpdateChecker.downloadApk(candidateUrls, outputDir, apkName) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        updateDownloadProgress = progress
                    }
                }
                    .onSuccess { apkFile ->
                        UpdateChecker.validateDownloadedApk(context, apkFile)
                            .onSuccess {
                                val apkUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    apkFile
                                )
                                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatchingObserved {
                                    context.startActivity(installIntent)
                                    showUpdateCheckDialog = false
                                }.onFailure {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.update_install_open_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            .onFailure { error ->
                                apkFile.delete()
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.update_download_failed,
                                        error.localizedMessage
                                            ?: context.getString(R.string.update_check_failed_unknown)
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.update_download_failed,
                                error.localizedMessage
                                    ?: context.getString(R.string.update_check_failed_unknown)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                updateDownloadProgress = null
                isDownloadingUpdate = false
            }
        }
    }
    
    // 生物识别帮助类
    val biometricHelper = remember(context) { BiometricAuthHelper(context) }
    val isBiometricAvailable = remember(biometricHelper) { 
        biometricHelper.isBiometricAvailable()
    }
    
    // 使用本地状态跟踪生物识别开关,避免验证失败时状态不一致
    var biometricSwitchState by remember(settings.biometricEnabled) { 
        mutableStateOf(settings.biometricEnabled) 
    }


    // 准备共享元素 Modifier
    val sharedTransitionScope = com.bastion.app.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.bastion.app.ui.LocalAnimatedVisibilityScope.current
    
    val startBiometricEnable = {
        if (activity != null) {
            biometricHelper.authenticate(
                activity = activity,
                title = context.getString(R.string.biometric_login_title),
                subtitle = context.getString(R.string.biometric_subtitle),
                description = context.getString(R.string.biometric_login_description),
                negativeButtonText = context.getString(R.string.cancel),
                onSuccess = {
                    biometricSwitchState = true
                    viewModel.updateBiometricEnabled(true)
                    Toast.makeText(
                        context,
                        context.getString(R.string.biometric_unlock_enabled),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onError = { errorCode, errorMsg ->
                    biometricSwitchState = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.biometric_auth_error, errorMsg),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onCancel = {
                    biometricSwitchState = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.cancel),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        } else {
            biometricSwitchState = false
            Toast.makeText(
                context,
                context.getString(R.string.biometric_cannot_enable),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val securityTitle = stringResource(R.string.security)
    val masterPasswordLockingTitle = stringResource(R.string.master_password_and_locking)
    val masterPasswordLockingDescription = stringResource(R.string.master_password_and_locking_description)
    val dataManagementTitle = stringResource(R.string.data_management)
    val appearanceTitle = stringResource(R.string.theme)
    val aboutTitle = stringResource(R.string.about)
    val developerTitle = stringResource(R.string.developer_settings)

    val biometricSubtitle = if (isBiometricAvailable) {
        if (biometricSwitchState) stringResource(R.string.biometric_unlock_enabled)
        else stringResource(R.string.biometric_unlock_disabled)
    } else {
        biometricHelper.getBiometricStatusMessage()
    }
    val screenshotProtectionSubtitle = if (settings.screenshotProtectionEnabled) {
        stringResource(R.string.screenshot_protection_enabled)
    } else {
        stringResource(R.string.screenshot_protection_disabled)
    }
    val autoLockSubtitle = getAutoLockDisplayName(settings.autoLockMinutes, context)
    val trashSubtitle = if (settings.trashEnabled) {
        if (settings.trashAutoDeleteDays > 0) {
            stringResource(R.string.trash_status_enabled_auto_clear, settings.trashAutoDeleteDays)
        } else {
            stringResource(R.string.trash_status_enabled_no_auto_clear)
        }
    } else {
        stringResource(R.string.trash_status_disabled_permanent_delete)
    }
    fun searchTexts(vararg resIds: Int): Array<String> = resIds.map(context::getString).toTypedArray()

    val themeAndColorSchemeSubSettingsSearchTexts = buildList {
        add(context.getString(R.string.theme_and_color_scheme))
        ThemeMode.values().forEach { theme ->
            add(getThemeDisplayName(theme, context))
        }
        add(context.getString(R.string.oled_pure_black))
        add(context.getString(R.string.oled_pure_black_description))
        add(context.getString(R.string.oled_pure_black_dark_mode_hint))
        add(context.getString(R.string.color_scheme_description))
        com.bastion.app.data.ColorScheme.values().forEach { scheme ->
            add(getColorSchemeDisplayName(scheme, context))
        }
    }.toTypedArray()

    val syncBackupSubSettingsSearchTexts = searchTexts(
        R.string.sync_backup_common_sync,
        R.string.webdav_backup,
        R.string.webdav_backup_description,
        R.string.sync_backup_bitwarden_sync_title,
        R.string.sync_backup_bitwarden_sync_desc,
        R.string.sync_backup_database_tools,
        R.string.dedup_engine_title,
        R.string.dedup_engine_entry_desc,
        R.string.sync_backup_keepass_tools,
        R.string.local_keepass_database,
        R.string.local_keepass_database_description,
        R.string.sync_backup_import_export_low_freq,
        R.string.export_data,
        R.string.export_data_description,
        R.string.import_data,
        R.string.import_data_description
    )

    val autofillSubSettingsSearchTexts = searchTexts(
        R.string.autofill_v2_title,
        R.string.autofill_system_settings_title,
        R.string.autofill_fill_behavior_title,
        R.string.autofill_v2_set_system_service,
        R.string.autofill_v2_set_system_service_desc,
        R.string.autofill_system_passkey_settings,
        R.string.autofill_system_passkey_settings_desc,
        R.string.autofill_v2_enable_service,
        R.string.autofill_v2_enable_service_desc,
        R.string.autofill_v2_default_scope_title,
        R.string.autofill_v2_default_scope_desc,
        R.string.autofill_v2_default_keepass_title,
        R.string.autofill_v2_default_keepass_desc,
        R.string.autofill_v2_default_bitwarden_title,
        R.string.autofill_v2_default_bitwarden_desc,
        R.string.autofill_v2_strict_match,
        R.string.autofill_v2_strict_match_desc,
        R.string.autofill_v2_subdomain_match,
        R.string.autofill_v2_subdomain_match_desc,
        R.string.autofill_domain_strategy_title,
        R.string.autofill_v2_respect_off,
        R.string.autofill_v2_respect_off_desc,
        R.string.autofill_save_enable,
        R.string.autofill_save_enable_desc,
        R.string.autofill_save_update_duplicate,
        R.string.autofill_save_update_duplicate_desc,
        R.string.autofill_save_show_notification,
        R.string.autofill_save_show_notification_desc,
        R.string.autofill_save_smart_title,
        R.string.autofill_save_smart_title_desc,
        R.string.autofill_save_app_info,
        R.string.autofill_save_app_info_desc,
        R.string.autofill_save_website_info,
        R.string.autofill_save_website_info_desc,
        R.string.autofill_otp_settings_title,
        R.string.autofill_show_otp_notification,
        R.string.autofill_show_otp_notification_desc,
        R.string.autofill_otp_notification_duration,
        R.string.autofill_otp_notification_duration_desc,
        R.string.autofill_auto_copy_otp,
        R.string.autofill_auto_copy_otp_desc,
        R.string.autofill_save_blocked_targets_title,
        R.string.autofill_save_blocked_targets_manage,
        R.string.autofill_blacklist_title,
        R.string.autofill_blacklist_manage,
        R.string.autofill_blocked_fields_title,
        R.string.autofill_blocked_fields_manage
    )

    val extensionsSubSettingsSearchTexts = searchTexts(
        R.string.display_options_menu_title,
        R.string.password_card_display_mode_title,
        R.string.display_mode_all,
        R.string.display_mode_title_username,
        R.string.display_mode_title_only,
        R.string.smart_deduplication,
        R.string.smart_deduplication_desc,
        R.string.validator_vibration,
        R.string.validator_vibration_description,
        R.string.copy_next_code_when_expiring,
        R.string.copy_next_code_when_expiring_description,
        R.string.notification_validator_title,
        R.string.select_validator_to_display,
        R.string.no_validators_available
    )

    val developerSubSettingsSearchTexts = searchTexts(
        R.string.developer_log_debugging,
        R.string.developer_view_logs,
        R.string.developer_view_logs_desc,
        R.string.developer_clear_log_buffer,
        R.string.developer_clear_log_buffer_desc,
        R.string.developer_share_logs,
        R.string.developer_share_logs_desc,
        R.string.developer_functions,
        R.string.developer_system_logs,
        R.string.developer_filter_all,
        R.string.developer_filter_errors,
        R.string.developer_filter_warnings
    )

    val pageCustomizationSubSettingsSearchTexts = searchTexts(
        R.string.password_list_customization_title,
        R.string.password_list_customization_subtitle,
        R.string.password_card_adjust_title,
        R.string.password_card_adjust_subtitle,
        R.string.authenticator_card_adjust_title,
        R.string.authenticator_card_adjust_subtitle,
        R.string.password_field_customization_title,
        R.string.extensions_password_field_customization_desc,
        R.string.icon_settings_title,
        R.string.icon_settings_subtitle,
        R.string.add_button_customization_title,
        R.string.add_button_customization_desc,
        R.string.add_button_mode_title,
        R.string.add_button_mode_subtitle,
        R.string.add_button_actions_title,
        R.string.add_button_actions_desc,
        R.string.password_page_aggregate_switch_title,
        R.string.password_page_aggregate_switch_desc,
        R.string.password_list_quick_folder_path_banner_switch_title,
        R.string.password_list_quick_folder_path_banner_switch_desc,
        R.string.password_list_system_back_to_parent_folder_switch_title,
        R.string.password_list_system_back_to_parent_folder_switch_desc,
        R.string.password_card_show_authenticator_title,
        R.string.password_card_show_authenticator_desc,
        R.string.password_card_hide_other_content_when_authenticator_title,
        R.string.password_card_hide_other_content_when_authenticator_desc,
        R.string.stack_mode_menu_title,
        R.string.group_mode_menu_title,
        R.string.website_stack_match_mode_title,
        R.string.website_stack_match_mode_desc,
        R.string.authenticator_card_display_content_title,
        R.string.authenticator_card_display_field_desc,
        R.string.unified_progress_bar_title,
        R.string.unified_progress_bar_description,
        R.string.validator_progress_bar_style,
        R.string.smooth_progress_bar_title,
        R.string.smooth_progress_bar_description,
        R.string.icon_settings_page_switches_title,
        R.string.icon_settings_page_switches_desc,
        R.string.icon_settings_app_icon_title,
        R.string.icon_settings_unmatched_strategy_title,
        R.string.icon_settings_source_title,
        R.string.icon_settings_source_desc,
        R.string.icon_settings_priority_title,
        R.string.icon_settings_priority_desc
    )

    fun matchesSettingsItem(
        sectionTitle: String,
        title: String,
        subtitle: String? = null,
        vararg extraSearchTexts: String?
    ): Boolean = matchesSettingsSearch(
        settingsSearchQuery,
        sectionTitle,
        title,
        subtitle,
        *extraSearchTexts
    )

    val showSecurityAnalysisCard = matchesSettingsSearch(
        settingsSearchQuery,
        securityTitle,
        stringResource(R.string.security_analysis),
        stringResource(R.string.security_analysis_description)
    )

    val showMasterPasswordLockingItem = matchesSettingsItem(
        securityTitle,
        masterPasswordLockingTitle,
        masterPasswordLockingDescription,
        stringResource(R.string.biometric_unlock),
        biometricSubtitle,
        stringResource(R.string.auto_lock),
        autoLockSubtitle,
        stringResource(R.string.security_questions),
        stringResource(R.string.security_questions_description),
        stringResource(R.string.reset_master_password),
        stringResource(R.string.reset_password_description)
    )
    val showScreenshotProtectionItem = matchesSettingsItem(
        securityTitle,
        stringResource(R.string.screenshot_protection),
        screenshotProtectionSubtitle
    )
    val showPermissionManagementItem = matchesSettingsItem(
        securityTitle,
        stringResource(R.string.permission_management_title),
        stringResource(R.string.permission_management_subtitle)
    )
    val showSecuritySection = listOf(
        showMasterPasswordLockingItem,
        showScreenshotProtectionItem,
        showPermissionManagementItem
    ).any { it }

    val showSyncBackupItem = matchesSettingsItem(
        dataManagementTitle,
        stringResource(R.string.sync_backup_title),
        stringResource(R.string.sync_backup_description),
        *syncBackupSubSettingsSearchTexts
    )
    val showAutofillItem = matchesSettingsItem(
        dataManagementTitle,
        stringResource(R.string.autofill),
        stringResource(R.string.autofill_subtitle),
        *autofillSubSettingsSearchTexts
    )
    val showTrashItem = matchesSettingsItem(
        dataManagementTitle,
        stringResource(R.string.trash_bin),
        trashSubtitle
    )
    val showClearDataItem = matchesSettingsItem(
        dataManagementTitle,
        stringResource(R.string.clear_all_data),
        stringResource(R.string.clear_all_data_subtitle)
    )
    val showDataManagementSection = listOf(
        showSyncBackupItem,
        showAutofillItem,
        showTrashItem,
        showClearDataItem
    ).any { it }

    val showThemeAndColorSchemeItem = matchesSettingsItem(
        appearanceTitle,
        stringResource(R.string.theme_and_color_scheme),
        stringResource(R.string.color_scheme_description),
        *themeAndColorSchemeSubSettingsSearchTexts
    )
    val showExtensionsItem = matchesSettingsItem(
        appearanceTitle,
        stringResource(R.string.extensions_title),
        stringResource(R.string.extensions_description),
        *extensionsSubSettingsSearchTexts
    )
    val showPageCustomizationItem = matchesSettingsItem(
        appearanceTitle,
        stringResource(R.string.page_adjust_custom_title),
        stringResource(R.string.page_adjust_custom_subtitle),
        *pageCustomizationSubSettingsSearchTexts
    )
    val showAppearanceSection = listOf(
        showThemeAndColorSchemeItem,
        showExtensionsItem,
        showPageCustomizationItem
    ).any { it }

    val currentVersionText = "V${BuildConfig.VERSION_NAME}"
    val showVersionItem = matchesSettingsItem(
        aboutTitle,
        stringResource(R.string.version),
        currentVersionText
    )
    val showUpdateCheckItem = matchesSettingsItem(
        aboutTitle,
        stringResource(R.string.update_check_title),
        stringResource(R.string.update_check_subtitle),
        stringResource(R.string.update_check_latest_release)
    )
    val showPreviewFeaturesItem = matchesSettingsItem(
        developerTitle,
        stringResource(R.string.preview_features_title),
        stringResource(R.string.preview_features_description)
    )
    val showDeveloperSettingsItem = matchesSettingsItem(
        developerTitle,
        stringResource(R.string.developer_settings),
        stringResource(R.string.developer_settings_subtitle),
        *developerSubSettingsSearchTexts
    )
    val hasVisibleResults = listOf(
        showSecurityAnalysisCard,
        showSecuritySection,
        showDataManagementSection,
        showAppearanceSection,
        showVersionItem,
        showUpdateCheckItem,
        showPreviewFeaturesItem,
        showDeveloperSettingsItem
    ).any { it }
    
    Scaffold(
        topBar = if (showTopBar) {
            {
                // 使用自定义顶部栏以减小高度
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars) // 仅适配状态栏
                            .height(56.dp) // 标准高度
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                        
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        
                        // 安全分析图标
                        IconButton(onClick = onSecurityAnalysis) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = stringResource(R.string.security_analysis),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        } else {
            {}
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // Top padding spacer for edge-to-edge scrolling
            Spacer(modifier = Modifier.height(paddingValues.calculateTopPadding()))

            SettingsSearchField(
                query = settingsSearchQuery,
                onQueryChange = { settingsSearchQuery = it }
            )

            batchDeleteProgress?.let { progress ->
                PasswordBatchDeleteProgressCard(progress = progress)
            }

            batchTransferProgress?.let { progress ->
                PasswordBatchTransferProgressCard(progress = progress)
            }

            // Bastion Plus card is moved to Extensions page after activation.
            if (showSecurityAnalysisCard) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable { onSecurityAnalysis() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.security_analysis),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.security_analysis_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            if (showSecuritySection) {
                SettingsSection(title = securityTitle,
                    onClick = onSectionSelected?.let { cb -> { cb(securityTitle) } }) {
                    if (showMasterPasswordLockingItem) {
                        SettingsItem(
                            icon = Icons.Default.Lock,
                            title = masterPasswordLockingTitle,
                            subtitle = masterPasswordLockingDescription,
                            onClick = onNavigateToMasterPasswordLocking,
                        )
                    }

                    // 防截屏保护已移入「主密码与锁定」页作为开关（与锁定类安全项归组）

                    if (showPermissionManagementItem) {
                        SettingsItem(
                            icon = Icons.Default.AdminPanelSettings,
                            title = stringResource(R.string.permission_management_title),
                            subtitle = stringResource(R.string.permission_management_subtitle),
                            onClick = onNavigateToPermissionManagement,
                        )
                    }
                }
            }
            
            if (showDataManagementSection) {
                SettingsSection(title = dataManagementTitle,
                    onClick = onSectionSelected?.let { cb -> { cb(dataManagementTitle) } }) {
                    if (showSyncBackupItem) {
                        SettingsItem(
                            icon = Icons.Default.Sync,
                            title = stringResource(R.string.sync_backup_title),
                            subtitle = stringResource(R.string.sync_backup_description),
                            onClick = onNavigateToSyncBackup,
                        )
                    }

                    if (showAutofillItem) {
                        SettingsItem(
                            icon = Icons.Default.VpnKey,
                            title = stringResource(R.string.autofill),
                            subtitle = stringResource(R.string.autofill_subtitle),
                            onClick = onNavigateToAutofill,
                        )
                    }

                    if (showTrashItem) {
                        SettingsItemWithTrashConfig(
                            trashEnabled = settings.trashEnabled,
                            trashAutoDeleteDays = settings.trashAutoDeleteDays,
                            onTrashEnabledChange = { enabled ->
                                viewModel.updateTrashEnabled(enabled)
                            },
                            onAutoDeleteDaysChange = { days ->
                                viewModel.updateTrashAutoDeleteDays(days)
                            }
                        )
                    }

                    if (showClearDataItem) {
                        SettingsItem(
                            icon = Icons.Default.DeleteForever,
                            title = stringResource(R.string.clear_all_data),
                            subtitle = stringResource(R.string.clear_all_data_subtitle),
                            onClick = { showClearDataDialog = true },
                            iconTint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            if (showAppearanceSection) {
                SettingsSection(title = appearanceTitle,
                    onClick = onSectionSelected?.let { cb -> { cb(appearanceTitle) } }) {
                    if (showThemeAndColorSchemeItem) {
                        SettingsItem(
                            icon = Icons.Default.Palette,
                            title = stringResource(R.string.theme_and_color_scheme),
                            subtitle = stringResource(R.string.color_scheme_description),
                            onClick = onNavigateToThemeAndColorScheme,
                        )
                    }

                    if (showExtensionsItem) {
                        SettingsItem(
                            icon = Icons.Default.Extension,
                            title = stringResource(R.string.extensions_title),
                            subtitle = stringResource(R.string.extensions_description),
                            onClick = onNavigateToExtensions,
                        )
                    }

                    if (showPageCustomizationItem) {
                        SettingsItem(
                            icon = Icons.Default.Tune,
                            title = stringResource(R.string.interface_layout),
                            subtitle = stringResource(R.string.page_adjust_custom_subtitle),
                            onClick = onNavigateToPageCustomization
                        )
                    }

                }
            }

            if (showVersionItem || showUpdateCheckItem) {
                SettingsSection(title = aboutTitle,
                    onClick = onSectionSelected?.let { cb -> { cb(aboutTitle) } }) {
                    // 版本号 + 检查更新合并为一个入口：点开对话框可看版本信息、选渠道检查并下载更新
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.version_and_update_title),
                        subtitle = currentVersionText,
                        onClick = { showUpdateCheckDialog = true },
                        showSubtitle = true // 当前版本号是状态信息，保留展示
                    )
                }
            }
            
            // 高级设置：默认收起，搜索时自动展开，避免干扰普通用户
            val advancedVisible = showPreviewFeaturesItem || showDeveloperSettingsItem
            val advancedShouldExpand = advancedSectionExpanded || settingsSearchQuery.isNotBlank()
            if (advancedVisible) {
                val (headerIcon, headerTitle, headerSubtitle) = Triple(
                    Icons.Default.Build,
                    stringResource(R.string.advanced_settings_title),
                    stringResource(R.string.advanced_settings_subtitle)
                )
                // 折叠态：复用统一 SettingsItem 组件，与上方功能项保持一致的卡片风格、对齐与字号
                SettingsItem(
                    icon = headerIcon,
                    title = headerTitle,
                    subtitle = headerSubtitle,
                    onClick = { advancedSectionExpanded = !advancedSectionExpanded },
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailingContent = {
                        Icon(
                            imageVector = if (advancedShouldExpand) {
                                Icons.Default.ExpandLess
                            } else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                if (advancedShouldExpand) {
                    if (showPreviewFeaturesItem) {
                        SettingsItem(
                            icon = Icons.Default.Science,
                            title = stringResource(R.string.preview_features_title),
                            subtitle = stringResource(R.string.preview_features_description),
                            onClick = { previewFeaturesExpanded = true }
                        )
                    }

                    if (showDeveloperSettingsItem) {
                        SettingsItem(
                            icon = Icons.Default.Code,
                            title = stringResource(R.string.developer_settings),
                            subtitle = stringResource(R.string.developer_settings_subtitle),
                            onClick = {
                                val hasActivity = activity != null
                                val biometricEnabled = settings.biometricEnabled
                                val biometricAvailableNow = hasActivity && biometricEnabled && biometricHelper.isBiometricAvailable()

                                developerPasswordInput = ""
                                developerPasswordError = false
                                showDeveloperVerifyDialog = false

                                when {
                                    !hasActivity -> {
                                        android.util.Log.w(
                                            "SettingsScreen",
                                            "Cannot start biometric auth: FragmentActivity context missing"
                                        )
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.use_master_password),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        showDeveloperVerifyDialog = true
                                    }
                                    biometricAvailableNow -> {
                                        biometricHelper.authenticate(
                                            activity = activity!!,
                                            title = context.getString(R.string.biometric_login_title),
                                            subtitle = context.getString(R.string.biometric_login_subtitle),
                                            description = context.getString(R.string.biometric_login_description),
                                            negativeButtonText = context.getString(R.string.use_master_password),
                                            onSuccess = {
                                                showDeveloperVerifyDialog = false
                                                developerPasswordInput = ""
                                                developerPasswordError = false
                                                onNavigateToDeveloperSettings()
                                            },
                                            onError = { errorCode, errorMessage ->
                                                android.util.Log.w(
                                                    "SettingsScreen",
                                                    "Developer biometric error: code=$errorCode, message=$errorMessage"
                                                )
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.biometric_auth_error, errorMessage),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                showDeveloperVerifyDialog = true
                                            },
                                            onCancel = {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.use_master_password),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                showDeveloperVerifyDialog = true
                                            }
                                        )
                                    }
                                    else -> {
                                        if (biometricEnabled) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.biometric_not_available),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        showDeveloperVerifyDialog = true
                                    }
                                }
                            }
                        )
                    }
                }
            }

            if (!hasVisibleResults) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.no_results),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_search_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Bottom padding spacer for edge-to-edge scrolling
            // 外层主界面已让列表延伸到悬浮胶囊底下，这里补足胶囊高度，避免最后几张设置卡被盖住
            Spacer(
                modifier = Modifier.height(
                    paddingValues.calculateBottomPadding() + 96.dp
                )
            )
        }
    }
    
    // Developer Settings Verification Dialog
    if (showDeveloperVerifyDialog) {
        val canUseBiometricInDialog = activity != null &&
            settings.biometricEnabled &&
            biometricHelper.isBiometricAvailable()
        val retryBiometricAction = if (canUseBiometricInDialog) {
            {
                biometricHelper.authenticate(
                    activity = activity!!,
                    title = context.getString(R.string.biometric_login_title),
                    subtitle = context.getString(R.string.biometric_login_subtitle),
                    description = context.getString(R.string.biometric_login_description),
                    negativeButtonText = context.getString(R.string.use_master_password),
                    onSuccess = {
                        showDeveloperVerifyDialog = false
                        developerPasswordInput = ""
                        developerPasswordError = false
                        onNavigateToDeveloperSettings()
                    },
                    onError = { _, errorMessage ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.biometric_auth_error, errorMessage),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onCancel = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.use_master_password),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        } else {
            null
        }
        M3IdentityVerifyDialog(
            title = stringResource(R.string.verify_identity),
            message = stringResource(R.string.enter_master_password_confirm),
            passwordValue = developerPasswordInput,
            onPasswordChange = {
                developerPasswordInput = it
                developerPasswordError = false
            },
            onDismiss = {
                showDeveloperVerifyDialog = false
                developerPasswordInput = ""
                developerPasswordError = false
            },
            onConfirm = {
                coroutineScope.launch {
                    val securityManager = com.bastion.app.security.SecurityManager(context)
                    if (securityManager.verifyMasterPassword(developerPasswordInput)) {
                        showDeveloperVerifyDialog = false
                        developerPasswordInput = ""
                        developerPasswordError = false
                        onNavigateToDeveloperSettings()
                    } else {
                        developerPasswordError = true
                    }
                }
            },
            confirmText = stringResource(R.string.confirm),
            destructiveConfirm = false,
            icon = Icons.Default.Code,
            isPasswordError = developerPasswordError,
            passwordErrorText = stringResource(R.string.current_password_incorrect),
            onBiometricClick = retryBiometricAction,
            biometricHintText = if (retryBiometricAction == null) {
                stringResource(R.string.biometric_not_available)
            } else {
                null
            }
        )
    }

    if (showWeakBiometricWarning) {
        AlertDialog(
            onDismissRequest = { showWeakBiometricWarning = false },
            icon = {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.biometric_weak_warning_title)) },
            text = { Text(stringResource(R.string.biometric_weak_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWeakBiometricWarning = false
                        startBiometricEnable()
                    }
                ) {
                    Text(stringResource(R.string.biometric_weak_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWeakBiometricWarning = false }
                ) {
                    Text(stringResource(R.string.biometric_weak_warning_cancel))
                }
            }
        )
    }

    if (showUpdateCheckDialog) {
        val result = updateCheckResult
        val updateDialogScrollState = rememberScrollState()
        val updateDialogContentMaxHeight = with(LocalDensity.current) {
            (LocalWindowInfo.current.containerSize.height.toDp() * 0.52f).coerceIn(180.dp, 520.dp)
        }
        AlertDialog(
            onDismissRequest = { showUpdateCheckDialog = false },
            icon = {
                Icon(
                    imageVector = if (result?.isUpdateAvailable == true) {
                        Icons.Default.Update
                    } else {
                        Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    when {
                        updateCheckError != null -> stringResource(R.string.update_check_failed_title)
                        result?.isUpdateAvailable == true -> stringResource(R.string.update_check_update_available_title)
                        result != null -> stringResource(R.string.update_check_no_update_title)
                        else -> stringResource(R.string.version_and_update_title)
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = updateDialogContentMaxHeight)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(updateDialogScrollState)
                    ) {
                        // === 当前版本卡片 ===
                        val fullVersion = BuildConfig.FULL_VERSION_NAME.ifBlank { BuildConfig.VERSION_NAME }
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = fullVersion,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    androidx.compose.material3.Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = if (updateChannel == UpdateChannel.STABLE) {
                                                stringResource(R.string.update_channel_stable)
                                            } else {
                                                stringResource(R.string.update_channel_preview)
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.update_version_code_fmt, currentVersionCode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // === 更新渠道选择 ===
                        Text(
                            text = stringResource(R.string.update_channel_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.FilterChip(
                                selected = updateChannel == UpdateChannel.STABLE,
                                onClick = {
                                    updateChannel = UpdateChannel.STABLE
                                    updateCheckResult = null
                                    updateCheckError = null
                                },
                                label = { Text(stringResource(R.string.update_channel_stable)) }
                            )
                            androidx.compose.material3.FilterChip(
                                selected = updateChannel == UpdateChannel.PREVIEW,
                                onClick = {
                                    updateChannel = UpdateChannel.PREVIEW
                                    updateCheckResult = null
                                    updateCheckError = null
                                },
                                label = { Text(stringResource(R.string.update_channel_preview)) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        // === 检查更新按钮 ===
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.FilledTonalButton(
                                onClick = startUpdateCheck,
                                enabled = !isCheckingUpdate
                            ) {
                                Text(stringResource(R.string.update_check_title))
                            }
                            lastUpdateCheckAt?.let { checkedAt ->
                                Spacer(modifier = Modifier.width(10.dp))
                                val checkedTimeText = remember(checkedAt) {
                                    java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                                        .format(java.time.Instant.ofEpochMilli(checkedAt)
                                            .atZone(java.time.ZoneId.systemDefault()))
                                }
                                Text(
                                    text = stringResource(R.string.update_last_checked, checkedTimeText),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isCheckingUpdate) {
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (updateCheckResult != null || updateCheckError != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            androidx.compose.material3.HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        when {
                            updateCheckError != null -> {
                                Text(
                                    text = stringResource(
                                        R.string.update_check_failed_message,
                                        updateCheckError.orEmpty()
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            result != null -> {
                                if (result.isUpdateAvailable) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = stringResource(R.string.update_new_version_badge),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = result.latestVersion,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.update_check_no_update_title),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                }
                                result.publishedAtEpochSeconds?.let { publishedAt ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.update_published_at,
                                            UpdateChecker.formatPublishedDate(publishedAt)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (result.isUpdateAvailable) {
                                    result.apkSizeBytes?.let { apkSize ->
                                        Text(
                                            text = stringResource(
                                                R.string.update_apk_size,
                                                UpdateChecker.formatApkSize(apkSize)
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.update_check_latest_version,
                                            result.latestVersion
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                result.releaseName?.let { releaseName ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = releaseName,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                val notes = result.releaseNotes?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.update_whats_new)
                                Spacer(modifier = Modifier.height(12.dp))
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Text(
                                            text = stringResource(R.string.update_content_label),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        MarkdownPreviewText(
                                            markdown = notes,
                                            imageBitmaps = emptyMap(),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }

                        // === 下载方式选择（仅在有新版本时）===
                        if (result?.isUpdateAvailable == true) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.update_download_method_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DownloadMethodChip(
                                    selected = !downloadViaBrowser,
                                    icon = Icons.Default.Download,
                                    label = stringResource(R.string.update_download_method_direct),
                                    onClick = { downloadViaBrowser = false }
                                )
                                DownloadMethodChip(
                                    selected = downloadViaBrowser,
                                    icon = Icons.Default.OpenInNew,
                                    label = stringResource(R.string.update_download_method_browser),
                                    onClick = { downloadViaBrowser = true }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.update_mirror_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // === 相关链接 ===
                        Spacer(modifier = Modifier.height(16.dp))
                        androidx.compose.material3.HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.update_links_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val uriHandler = LocalUriHandler.current
                        UpdateLinkRow(
                            icon = Icons.Default.Code,
                            label = stringResource(R.string.update_project_url_label),
                            value = "Chaniug/bastion",
                            onClick = { uriHandler.openUri(com.bastion.app.utils.UpdateChecker.PROJECT_URL) }
                        )
                        UpdateLinkRow(
                            icon = Icons.Default.OpenInNew,
                            label = stringResource(R.string.update_releases_url_label),
                            value = "Releases",
                            onClick = { uriHandler.openUri(com.bastion.app.utils.UpdateChecker.RELEASES_PAGE_URL) }
                        )
                    }

                    if (result != null && isDownloadingUpdate) {
                        UpdateDownloadProgressSection(progress = updateDownloadProgress)
                    }
                }
            },
            confirmButton = {
                if (result?.isUpdateAvailable == true) {
                    val uriHandlerConfirm = LocalUriHandler.current
                    val openInBrowser = downloadViaBrowser || result.apkDownloadUrl.isNullOrBlank()
                    androidx.compose.material3.FilledTonalButton(
                        enabled = !isDownloadingUpdate,
                        onClick = {
                            if (openInBrowser) {
                                uriHandlerConfirm.openUri(
                                    result.releaseUrl.ifBlank {
                                        com.bastion.app.utils.UpdateChecker.RELEASES_PAGE_URL
                                    }
                                )
                            } else {
                                startUpdateDownload(result)
                            }
                        }
                    ) {
                        Text(
                            if (isDownloadingUpdate) {
                                stringResource(R.string.update_download_downloading)
                            } else if (openInBrowser) {
                                stringResource(R.string.update_check_open_release)
                            } else {
                                stringResource(R.string.update_download_and_install)
                            }
                        )
                    }
                } else {
                    TextButton(onClick = { showUpdateCheckDialog = false }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = {
                if (result?.isUpdateAvailable == true) {
                    TextButton(onClick = { showUpdateCheckDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        )
    }
    
    // 预览功能对话框
    if (previewFeaturesExpanded) {
        val previewDialogContentMaxHeight = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height.toDp() * 0.58f
        }
        AlertDialog(
            onDismissRequest = { previewFeaturesExpanded = false },
            icon = {
                Icon(
                    Icons.Default.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(stringResource(R.string.preview_features_title))
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = previewDialogContentMaxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.preview_features_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // === 实验功能分组 ===
                    Text(
                        text = stringResource(R.string.experimental_features_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // 导航栏版本切换 - Removed

                    // Bitwarden 底部状态栏开关（实验）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.updateBitwardenBottomStatusBarEnabled(
                                    !settings.bitwardenBottomStatusBarEnabled
                                )
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.bitwarden_bottom_status_bar_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.bitwarden_bottom_status_bar_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.bitwardenBottomStatusBarEnabled,
                            onCheckedChange = { viewModel.updateBitwardenBottomStatusBarEnabled(it) }
                        )
                    }

                    // KeePass DX 类引擎（实验）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.updateKeepassDxLikeMutationEnabled(
                                    !settings.keepassDxLikeMutationEnabled
                                )
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.keepass_dx_like_mutation_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.keepass_dx_like_mutation_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.keepassDxLikeMutationEnabled,
                            onCheckedChange = { viewModel.updateKeepassDxLikeMutationEnabled(it) }
                        )
                    }

                }
            },
            confirmButton = {
                TextButton(onClick = { previewFeaturesExpanded = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
    
    // Clear All Data Confirmation Bottom Sheet
    if (showClearDataDialog) {
        var clearPasswords by remember { mutableStateOf(true) }
        var clearTotp by remember { mutableStateOf(true) }
        var clearNotes by remember { mutableStateOf(true) }
        var clearDocuments by remember { mutableStateOf(true) }
        var clearBankCards by remember { mutableStateOf(true) }
        var clearGeneratorHistory by remember { mutableStateOf(true) }
        
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun dismissClearDataSheet(afterDismiss: (() -> Unit)? = null) {
            coroutineScope.launch {
                if (sheetState.isVisible) {
                    sheetState.hide()
                }
                showClearDataDialog = false
                clearDataPasswordInput = ""
                afterDismiss?.invoke()
            }
        }
        
        ModalBottomSheet(
            onDismissRequest = { dismissClearDataSheet() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Title
                Text(
                    text = stringResource(R.string.clear_all_data),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Warning Text
                Text(
                    text = stringResource(R.string.clear_all_data_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Options Group
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.select_data_types_to_clear),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        // Passwords
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { clearPasswords = !clearPasswords }
                        ) {
                            Checkbox(checked = clearPasswords, onCheckedChange = { clearPasswords = it })
                            Text(
                                text = stringResource(R.string.data_type_passwords),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        
                        // TOTP
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { clearTotp = !clearTotp }
                        ) {
                            Checkbox(checked = clearTotp, onCheckedChange = { clearTotp = it })
                            Text(
                                text = stringResource(R.string.data_type_totp),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        
                        // Notes (NEW)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { clearNotes = !clearNotes }
                        ) {
                            Checkbox(checked = clearNotes, onCheckedChange = { clearNotes = it })
                            Text(
                                text = stringResource(R.string.data_type_notes),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        
                        // Documents
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { clearDocuments = !clearDocuments }
                        ) {
                            Checkbox(checked = clearDocuments, onCheckedChange = { clearDocuments = it })
                            Text(
                                text = stringResource(R.string.data_type_documents),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        
                        // Bank Cards
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { clearBankCards = !clearBankCards }
                        ) {
                            Checkbox(checked = clearBankCards, onCheckedChange = { clearBankCards = it })
                            Text(
                                text = stringResource(R.string.data_type_bank_cards),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        
                        // History
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { clearGeneratorHistory = !clearGeneratorHistory }
                        ) {
                            Checkbox(checked = clearGeneratorHistory, onCheckedChange = { clearGeneratorHistory = it })
                            Text(
                                text = stringResource(R.string.data_type_generator_history),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Password Validation
                OutlinedTextField(
                    value = clearDataPasswordInput,
                    onValueChange = { clearDataPasswordInput = it },
                    label = { Text(context.getString(R.string.enter_master_password_to_confirm)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel
                    OutlinedButton(
                        onClick = { dismissClearDataSheet() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = CircleShape
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    
                    // Confirm Delete
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                if (verifyClearDataPassword(context, clearDataPasswordInput)) {
                                    dismissClearDataSheet {
                                        onClearAllData(
                                            clearPasswords,
                                            clearTotp,
                                            clearNotes,
                                            clearDocuments,
                                            clearBankCards,
                                            clearGeneratorHistory
                                        )
                                        Toast.makeText(context, context.getString(R.string.clearing_data), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.password_incorrect), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = clearDataPasswordInput.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.confirm))
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}


