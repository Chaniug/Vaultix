package com.bastion.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import com.bastion.app.data.AppSettings
import com.bastion.app.data.AddButtonBehaviorMode
import com.bastion.app.data.AddButtonMenuAction
import com.bastion.app.data.AppLauncherIcon
import com.bastion.app.data.AppLauncherLabel
import com.bastion.app.data.BottomNavContentTab
import com.bastion.app.data.CategorySelectionUiMode
import com.bastion.app.data.ColorScheme
import com.bastion.app.data.Language
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.PasswordSwipeSelectionMode
import com.bastion.app.data.PresetCustomField
import com.bastion.app.data.ThemeMode
import com.bastion.app.data.SecureItem
import com.bastion.app.data.ItemType
import com.bastion.app.repository.SecureItemRepository
import com.bastion.app.utils.SavedCategoryFilterState
import com.bastion.app.utils.SettingsManager

/**
 * ViewModel for Settings screen
 */
class SettingsViewModel(
    private val settingsManager: SettingsManager,
    private val secureItemRepository: SecureItemRepository? = null
) : ViewModel() {
    /**
     * 统一的事务包装：在 viewModelScope 中把变更提交到 SettingsManager。
     * 所有 update/add/delete 等委托方法复用它，消除重复的 launch 样板。
     *
     * 注意：不能标记为 inline —— suspend 类型的 lambda 参数无法在非 suspend 的
     * inline 函数中内联；返回 Unit 而非 Job，保持这些委托方法原有的公开签名。
     */
    private fun commitUpdate(block: suspend SettingsManager.() -> Unit) {
        viewModelScope.launch { settingsManager.block() }
    }

    
    val settings: StateFlow<AppSettings> = settingsManager.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )
    
    // 预设自定义字段列表
    val presetCustomFields: StateFlow<List<PresetCustomField>> = settingsManager.presetCustomFieldsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 获取所有TOTP验证器
    val totpItems: StateFlow<List<SecureItem>> = secureItemRepository?.getItemsByType(ItemType.TOTP)
        ?.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        ) ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    
    fun updateThemeMode(themeMode: ThemeMode) = commitUpdate { updateThemeMode(themeMode) }

    fun updateOledPureBlackEnabled(enabled: Boolean) = commitUpdate { updateOledPureBlackEnabled(enabled) }

    fun updateColorScheme(colorScheme: ColorScheme) = commitUpdate { updateColorScheme(colorScheme) }
    
    fun updateLanguage(language: Language) = commitUpdate { updateLanguage(language) }
    
    fun updateBiometricEnabled(enabled: Boolean) = commitUpdate { updateBiometricEnabled(enabled) }

    fun updateQuickSetupCompleted(completed: Boolean) = commitUpdate { updateQuickSetupCompleted(completed) }
    
    fun updateAutoLockMinutes(minutes: Int) = commitUpdate { updateAutoLockMinutes(minutes) }
    
    fun updateScreenshotProtectionEnabled(enabled: Boolean) = commitUpdate { updateScreenshotProtectionEnabled(enabled) }

    fun updateClipboardAutoClearSeconds(seconds: Int) = commitUpdate { updateClipboardAutoClearSeconds(seconds) }

    fun updateBottomNavVisibility(tab: BottomNavContentTab, visible: Boolean) = commitUpdate { updateBottomNavVisibility(tab, visible) }

    fun updateBottomNavOrder(order: List<BottomNavContentTab>) = commitUpdate { updateBottomNavOrder(order) }

    fun updateCustomColors(
        primary: Long,
        secondary: Long,
        tertiary: Long,
        neutral: Long = primary,
        neutralVariant: Long = secondary
    ) = commitUpdate { updateCustomColors(primary, secondary, tertiary, neutral, neutralVariant) }

    fun updateStackCardMode(mode: String) = commitUpdate { updateStackCardMode(mode) }

    fun updatePasswordGroupMode(mode: String) = commitUpdate { updatePasswordGroupMode(mode) }

    fun updatePasswordWebsiteStackMatchMode(mode: String) = commitUpdate { updatePasswordWebsiteStackMatchMode(mode) }

    fun updatePasswordSwipeSelectionMode(mode: PasswordSwipeSelectionMode) = commitUpdate { updatePasswordSwipeSelectionMode(mode) }

    fun updateValidatorProgressBarStyle(style: com.bastion.app.data.ProgressBarStyle) = commitUpdate { updateValidatorProgressBarStyle(style) }

    fun updateValidatorUnifiedProgressBar(mode: com.bastion.app.data.UnifiedProgressBarMode) = commitUpdate { updateValidatorUnifiedProgressBar(mode) }

    fun updateValidatorSmoothProgress(enabled: Boolean) = commitUpdate { updateValidatorSmoothProgress(enabled) }

    fun updateValidatorVibrationEnabled(enabled: Boolean) = commitUpdate { updateValidatorVibrationEnabled(enabled) }

    
    fun updateSecurityAnalysisAutoEnabled(enabled: Boolean) = commitUpdate { updateSecurityAnalysisAutoEnabled(enabled) }

    fun updatePasswordDetailSecurityAnalysisEnabled(enabled: Boolean) = commitUpdate { updatePasswordDetailSecurityAnalysisEnabled(enabled) }

    fun updateBitwardenBottomStatusBarEnabled(enabled: Boolean) = commitUpdate { updateBitwardenBottomStatusBarEnabled(enabled) }

    fun updateCopyNextCodeWhenExpiring(enabled: Boolean) = commitUpdate { updateCopyNextCodeWhenExpiring(enabled) }

    fun updateNotificationValidatorEnabled(enabled: Boolean) = commitUpdate { updateNotificationValidatorEnabled(enabled) }

    fun updateNotificationValidatorId(id: Long) = commitUpdate { updateNotificationValidatorId(id) }

    fun updateNotificationValidatorAutoMatch(enabled: Boolean) = commitUpdate { updateNotificationValidatorAutoMatch(enabled) }

    
    fun updateUseDraggableBottomNav(enabled: Boolean) = commitUpdate { updateUseDraggableBottomNav(enabled) }

    fun updateAutoHideBottomNavWhenSingleTab(enabled: Boolean) = commitUpdate { updateAutoHideBottomNavWhenSingleTab(enabled) }
    
    // 回收站设置
    fun updateTrashEnabled(enabled: Boolean) = commitUpdate { updateTrashEnabled(enabled) }

    fun updateDevBypassAppLock(enabled: Boolean) = commitUpdate { updateDevBypassAppLock(enabled) }
    
    fun updateTrashAutoDeleteDays(days: Int) = commitUpdate { updateTrashAutoDeleteDays(days) }

    fun updateIconCardsEnabled(enabled: Boolean) = commitUpdate { updateIconCardsEnabled(enabled) }

    fun updateThirdPartyFaviconEnabled(enabled: Boolean) {
        commitUpdate { updateThirdPartyFaviconEnabled(enabled) }
        com.bastion.app.autofill_ng.ui.FaviconCache.useThirdPartyFavicons = enabled
    }

    fun updateAppLauncherIcon(icon: AppLauncherIcon) = commitUpdate { updateAppLauncherIcon(icon) }

    fun updateAppLauncherLabel(label: AppLauncherLabel) = commitUpdate { updateAppLauncherLabel(label) }

    fun updatePasswordPageIconEnabled(enabled: Boolean) = commitUpdate { updatePasswordPageIconEnabled(enabled) }

    fun updateAuthenticatorPageIconEnabled(enabled: Boolean) = commitUpdate { updateAuthenticatorPageIconEnabled(enabled) }

    fun updatePasskeyPageIconEnabled(enabled: Boolean) = commitUpdate { updatePasskeyPageIconEnabled(enabled) }

    fun updateUnmatchedIconHandlingStrategy(strategy: com.bastion.app.data.UnmatchedIconHandlingStrategy) = commitUpdate { updateUnmatchedIconHandlingStrategy(strategy) }

    fun updatePasswordCardDisplayMode(mode: com.bastion.app.data.PasswordCardDisplayMode) = commitUpdate { updatePasswordCardDisplayMode(mode) }

    fun updatePasswordCardDisplayFields(fields: List<com.bastion.app.data.PasswordCardDisplayField>) = commitUpdate { updatePasswordCardDisplayFields(fields) }

    fun updatePasswordCardShowAuthenticator(show: Boolean) = commitUpdate { updatePasswordCardShowAuthenticator(show) }

    fun updatePasswordCardHideOtherContentWhenAuthenticator(enabled: Boolean) = commitUpdate { updatePasswordCardHideOtherContentWhenAuthenticator(enabled) }

    fun updateAuthenticatorCardDisplayFields(fields: List<com.bastion.app.data.AuthenticatorCardDisplayField>) = commitUpdate { updateAuthenticatorCardDisplayFields(fields) }

    fun updateAuthenticatorCardHideCodeByDefault(enabled: Boolean) = commitUpdate { updateAuthenticatorCardHideCodeByDefault(enabled) }

    fun updatePasswordListQuickFilterItems(items: List<com.bastion.app.data.PasswordListQuickFilterItem>) = commitUpdate { updatePasswordListQuickFilterItems(items) }

    fun updatePasswordListCategoryQuickFiltersEnabled(enabled: Boolean) = commitUpdate { updatePasswordListCategoryQuickFiltersEnabled(enabled) }

    fun updatePasswordListQuickFoldersEnabled(enabled: Boolean) = commitUpdate { updatePasswordListQuickFoldersEnabled(enabled) }

    fun updatePasswordListQuickFolderStyle(style: com.bastion.app.data.PasswordListQuickFolderStyle) = commitUpdate { updatePasswordListQuickFolderStyle(style) }

    fun updatePasswordListQuickFolderPathBannerEnabled(enabled: Boolean) = commitUpdate { updatePasswordListQuickFolderPathBannerEnabled(enabled) }

    fun updatePasswordListSystemBackToParentFolderEnabled(enabled: Boolean) = commitUpdate { updatePasswordListSystemBackToParentFolderEnabled(enabled) }

    fun updateAddButtonBehaviorMode(mode: AddButtonBehaviorMode) = commitUpdate { updateAddButtonBehaviorMode(mode) }

    fun updateAddButtonMenuOrder(order: List<AddButtonMenuAction>) = commitUpdate { updateAddButtonMenuOrder(order) }

    fun updateAddButtonMenuEnabledActions(actions: List<AddButtonMenuAction>) = commitUpdate { updateAddButtonMenuEnabledActions(actions) }

    fun updatePasswordPageAggregateEnabled(enabled: Boolean) = commitUpdate { updatePasswordPageAggregateEnabled(enabled) }

    fun updatePasswordPageVisibleContentTypes(types: List<PasswordPageContentType>) = commitUpdate { updatePasswordPageVisibleContentTypes(types) }

    fun updateCategorySelectionUiMode(mode: CategorySelectionUiMode) = commitUpdate { updateCategorySelectionUiMode(mode) }

    fun updatePasswordListQuickAccessEnabled(enabled: Boolean) = commitUpdate { updatePasswordListQuickAccessEnabled(enabled) }

    fun updatePasswordListTopModulesOrder(order: List<com.bastion.app.data.PasswordListTopModule>) = commitUpdate { updatePasswordListTopModulesOrder(order) }

    fun updateNoteGridLayout(isGrid: Boolean) = commitUpdate { updateNoteGridLayout(isGrid) }

    fun updatePasswordFieldVisibility(field: String, visible: Boolean) = commitUpdate { updatePasswordFieldVisibility(field, visible) }
    
    // ==================== 预设自定义字段管理 ====================
    
    fun addPresetCustomField(field: PresetCustomField) = commitUpdate { addPresetCustomField(field) }
    
    fun updatePresetCustomField(field: PresetCustomField) = commitUpdate { updatePresetCustomField(field) }
    
    fun deletePresetCustomField(fieldId: String) = commitUpdate { deletePresetCustomField(fieldId) }
    
    fun reorderPresetCustomFields(fieldIds: List<String>) = commitUpdate { reorderPresetCustomFields(fieldIds) }
    
    fun clearAllPresetCustomFields() = commitUpdate { clearAllPresetCustomFields() }
    
    fun updateSmartDeduplicationEnabled(enabled: Boolean) = commitUpdate { updateSmartDeduplicationEnabled(enabled) }

    fun updateSeparateUsernameAccountEnabled(enabled: Boolean) = commitUpdate { updateSeparateUsernameAccountEnabled(enabled) }

    fun updateKeepassDxLikeMutationEnabled(enabled: Boolean) = commitUpdate { updateKeepassDxLikeMutationEnabled(enabled) }

    fun categoryFilterStateFlow(scope: String): Flow<SavedCategoryFilterState> {
        return settingsManager.categoryFilterStateFlow(scope)
    }

    fun updateCategoryFilterState(scope: String, state: SavedCategoryFilterState) = commitUpdate { updateCategoryFilterState(scope, state) }

}
