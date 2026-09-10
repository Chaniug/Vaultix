package io.vaultix.vaultix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.datastore.VaultixPreferencesDefaults
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.security.AutoLockController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * 设置页（最小版）。偏好值全部来自 [VaultixPreferences]（DataStore，非敏感），
 * 保存即实时生效（主题 / 防截屏由 MainActivity 收集，锁定由 AutoLockController
 * 收集，剪贴板清除由详情页收集）。
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel @Inject constructor(
    private val preferences: VaultixPreferences,
    private val vaultRepository: VaultRepository,
    private val autoLockController: AutoLockController,
) : ViewModel() {
    data class UiState(
        val autoLockMinutes: Int = 5,
        val clipboardClearMs: Long = 30_000L,
        val dynamicColor: Boolean = true,
        val screenSecurity: Boolean = true,
    )

    /** 本地快速解锁管理列表（每库：是否已启用）。 */
    data class QuickUnlockVaultUi(
        val vaultId: String,
        val name: String,
        val enabled: Boolean,
    )

    val state: StateFlow<UiState> = combine(
        preferences.autoLockMinutes,
        preferences.clipboardClearMs,
        preferences.dynamicColor,
        preferences.screenSecurity,
    ) { minutes, clearMs, dynamic, secure ->
        UiState(
            autoLockMinutes = minutes,
            clipboardClearMs = clearMs,
            dynamicColor = dynamic,
            screenSecurity = secure,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState(),
    )

    val quickUnlockVaults: StateFlow<List<QuickUnlockVaultUi>> =
        vaultRepository.observeVaults()
            .flatMapLatest { vaults ->
                combine(
                    vaults.map { vault ->
                        vaultRepository.localUnlockAvailable(vault.id).map { enabled ->
                            QuickUnlockVaultUi(
                                vaultId = vault.id,
                                name = vault.name,
                                enabled = enabled,
                            )
                        }
                    },
                ) { items -> items.toList() }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /** 主题模式原始值（system / light / dark；UI 侧用 ThemeMode.from 解析显示与回传）。 */
    val themeMode: StateFlow<String> = preferences.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VaultixPreferencesDefaults.THEME_MODE,
        )

    /** OLED 纯黑（深色模式 surface/background 纯黑）。 */
    val oledPureBlack: StateFlow<Boolean> = preferences.oledPureBlack
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** 回收站自动清理档位（天；0 = 不自动清空），与回收站页顶栏入口共用同一偏好。 */
    val trashAutoDeleteDays: StateFlow<Int> = preferences.trashAutoDeleteDays
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VaultixPreferencesDefaults.TRASH_AUTO_DELETE_DAYS,
        )

    /** 自动填充保存提示（登录成功后询问保存 / 更新凭据，默认开）。 */
    val autofillSavePrompt: StateFlow<Boolean> = preferences.autofillSavePrompt
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setAutofillSavePrompt(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutofillSavePrompt(enabled) }
    }

    /** 自动填充后自动复制验证码（条目带 TOTP 而页面没有验证码框时）。 */
    val autoCopyTotp: StateFlow<Boolean> = preferences.autoCopyTotp
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setAutoCopyTotp(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoCopyTotp(enabled) }
    }

    fun setAutoLockMinutes(minutes: Int) {
        viewModelScope.launch { preferences.setAutoLockMinutes(minutes) }
    }

    fun setClipboardClearMs(ms: Long) {
        viewModelScope.launch { preferences.setClipboardClearMs(ms) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferences.setDynamicColor(enabled) }
    }

    fun setScreenSecurity(enabled: Boolean) {
        viewModelScope.launch { preferences.setScreenSecurity(enabled) }
    }

    /** 主题模式（system / light / dark），MainActivity 收集后实时切换。 */
    fun setThemeMode(mode: String) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    /** OLED 纯黑开关。 */
    fun setOledPureBlack(enabled: Boolean) {
        viewModelScope.launch { preferences.setOledPureBlack(enabled) }
    }

    /** 回收站自动清理档位（天；0 = 不自动清空）。 */
    fun setTrashAutoDeleteDays(days: Int) {
        viewModelScope.launch { preferences.setTrashAutoDeleteDays(days) }
    }

    /** 关闭某库的本地快速解锁（删除包裹密钥与开关）。 */
    fun disableQuickUnlock(vaultId: String) {
        viewModelScope.launch { vaultRepository.disableLocalUnlock(vaultId) }
    }

    /**
     * 设置页**启用**某库的快速解锁（消除入口死角）。
     *
     * 背景：此前启用入口**只有**库列表页横幅（`QuickUnlockBanner`），而横幅点
     * 「以后再说」会置位 `isQuickUnlockPromptDismissed` → 横幅永不再现，
     * 用户就此**彻底失去启用路径**（设置页对话框只能关不能开）。
     * 此处补上对称入口，复用与横幅完全相同的 enroll 流程。
     *
     * 设备无可用认证方式时静默返回（不弹无意义的认证框）；
     * 否则准备 ENCRYPT cipher 并通知 UI 弹 BiometricPrompt。
     */
    fun startQuickUnlockEnroll(vaultId: String) {
        viewModelScope.launch {
            val cipher = vaultRepository.prepareLocalEnroll() ?: return@launch
            _events.send(Event.PromptForEnroll(vaultId, cipher))
        }
    }

    /** BiometricPrompt 认证通过：用本次 cipher 包裹当前会话密钥并落盘。 */
    fun enrollWithCipher(vaultId: String, cipher: Cipher) {
        viewModelScope.launch { vaultRepository.enrollLocalUnlock(vaultId, cipher) }
    }

    sealed interface Event {
        /** UI 收到后弹 BiometricPrompt（cipher 已 init，等待用户认证）。 */
        data class PromptForEnroll(val vaultId: String, val cipher: Cipher) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** 立即锁定全部库：AutoLockController 会自增锁定代次，导航壳自动回库列表。 */
    fun lockAllNow() = autoLockController.lockAllNow()

    companion object {
        /** 剪贴板清除候选（ms）：0 = 关闭。默认 30s 与详情页一致。 */
        val CLIPBOARD_PRESETS_MS: List<Long> = listOf(0L, 10_000L, 30_000L, 60_000L, 300_000L)
    }
}
