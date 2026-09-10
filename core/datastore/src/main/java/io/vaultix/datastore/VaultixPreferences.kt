/*
 * Vaultix — core:datastore
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 偏好默认值（public：UI 层 stateIn 初始值与偏好层默认保持单一真值源）。
 */
object VaultixPreferencesDefaults {
    /** 回收站自动清理档位（天；0 = 不自动清空；语义对齐 Bastion autoDeleteDays）。 */
    const val TRASH_AUTO_DELETE_DAYS = 30

    /** 主题模式（对齐 Bastion themeMode：system / light / dark）。 */
    const val THEME_MODE = "system"
}

/**
 * 应用设置（非敏感）。
 *
 * 敏感凭据（token、主密钥材料）一律走 [SecureCredentialStore]，不放在这里。
 */
@Singleton
class VaultixPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private companion object {
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
        val CLIPBOARD_CLEAR_MS = longPreferencesKey("clipboard_clear_ms")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        val DEFAULT_VAULT_ID = stringPreferencesKey("default_vault_id")
        val QUICK_UNLOCK_PROMPT_DISMISSED = booleanPreferencesKey("quick_unlock_prompt_dismissed")
        val TRASH_AUTO_DELETE_DAYS = intPreferencesKey("trash_auto_delete_days")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val OLED_PURE_BLACK = booleanPreferencesKey("oled_pure_black")
        val AUTOFILL_SAVE_PROMPT = booleanPreferencesKey("autofill_save_prompt")
        val AUTO_COPY_TOTP = booleanPreferencesKey("auto_copy_totp")
        val AUTOFILL_BASE_DOMAIN_MATCH = booleanPreferencesKey("autofill_base_domain_match")
        val AUTOFILL_EXACT_DOMAIN_ONLY = booleanPreferencesKey("autofill_exact_domain_only")

        /**
         * 自动锁定档位（分钟，语义对齐 Bastion autoLockMinutes）：
         * 0 = 切后台立即锁定；>0 = 离开超过 N 分钟锁定；<0 = 从不自动锁定。
         */
        const val DEFAULT_AUTO_LOCK_MINUTES = 5
        const val DEFAULT_CLIPBOARD_CLEAR_MS = 30 * 1000L
    }

    private val safeData: Flow<Preferences> = dataStore.data
        .catch { error ->
            // 读取异常（首次运行或文件损坏）时退回空配置，避免整条流挂掉
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    /** 自动锁定档位（分钟；0=立即 / N=空闲分钟 / 负=从不），语义见 companion 注释。 */
    val autoLockMinutes: Flow<Int> =
        safeData.map { it[AUTO_LOCK_MINUTES] ?: DEFAULT_AUTO_LOCK_MINUTES }

    /** 敏感内容复制后自动清空剪贴板的延迟（0 = 不清除）。 */
    val clipboardClearMs: Flow<Long> =
        safeData.map { it[CLIPBOARD_CLEAR_MS] ?: DEFAULT_CLIPBOARD_CLEAR_MS }

    val dynamicColor: Flow<Boolean> =
        safeData.map { it[DYNAMIC_COLOR] ?: true }

    /** 是否开启 FLAG_SECURE（防截屏 / 防最近任务缩略图）。 */
    val screenSecurity: Flow<Boolean> =
        safeData.map { it[SCREEN_SECURITY] ?: true }

    /**
     * 回收站自动清理档位（天；0 = 不自动清空，语义见 [VaultixPreferencesDefaults.TRASH_AUTO_DELETE_DAYS]）。
     * 清理时机 = 进入回收站（TrashViewModel init），策略与倒计时口径见
     * [io.vaultix.common.TrashCleanupPolicy]。
     */
    val trashAutoDeleteDays: Flow<Int> =
        safeData.map { it[TRASH_AUTO_DELETE_DAYS] ?: VaultixPreferencesDefaults.TRASH_AUTO_DELETE_DAYS }

    /**
     * 主题模式（`system` / `light` / `dark`，语义对齐 Bastion themeMode）。
     * 由 MainActivity 收集驱动 [io.vaultix.vaultix.ui.theme.VaultixTheme]。
     */
    val themeMode: Flow<String> =
        safeData.map { it[THEME_MODE] ?: VaultixPreferencesDefaults.THEME_MODE }

    /** OLED 纯黑（对齐 Bastion oledPureBlackEnabled）：深色模式下 surface/background 用纯黑。 */
    val oledPureBlack: Flow<Boolean> =
        safeData.map { it[OLED_PURE_BLACK] ?: false }

    /**
     * 自动填充保存提示（对齐 Bitwarden `isAutofillSavePromptDisabled` 的反向开关）：
     * 在 App / 网页提交登录表单后询问是否保存 / 更新凭据。默认开启。
     */
    val autofillSavePrompt: Flow<Boolean> =
        safeData.map { it[AUTOFILL_SAVE_PROMPT] ?: true }

    /**
     * 自动填充后自动复制验证码（对齐 Bitwarden `isAutoCopyTotpDisabled = false`）：
     * 条目带 TOTP 而页面没有验证码框时，填充完成即把当前验证码放进剪贴板，
     * 用户直接粘贴即可完成 2FA 第二步。默认开启。
     */
    val autoCopyTotp: Flow<Boolean> =
        safeData.map { it[AUTO_COPY_TOTP] ?: true }

    /**
     * 允许「基域 / 子域名」匹配（对齐 Bastion `allowBaseDomainMatch`，Bitwarden 默认开）。
     *
     * 例：条目存的是 `example.com`，页面在 `login.example.com` 时也能命中。关掉后只有
     * 域名完全一致才填充——更严格、更省心，但跨子域登录会填不出来。
     */
    val autofillBaseDomainMatch: Flow<Boolean> =
        safeData.map { it[AUTOFILL_BASE_DOMAIN_MATCH] ?: true }

    /**
     * 仅精确域匹配（对齐 Bastion `exactDomainOnly`，Bitwarden 默认关）。
     *
     * 开启后忽略条目上配的「起始匹配 / 正则匹配」等宽松规则，只认域名完全相等。
     * 与 [autofillBaseDomainMatch] 独立生效：两者都开 = 只认精确域名。
     */
    val autofillExactDomainOnly: Flow<Boolean> =
        safeData.map { it[AUTOFILL_EXACT_DOMAIN_ONLY] ?: false }

    val defaultVaultId: Flow<String?> = safeData.map { it[DEFAULT_VAULT_ID] }

    /**
     * 本地快速解锁开关（按库）。仅为元数据：真正的包裹密钥密文在
     * [SecureCredentialStore]（key：local_unlock_key::<vaultId>）。
     */
    fun isLocalUnlockEnabled(vaultId: String): Flow<Boolean> =
        safeData.map { it[localUnlockKey(vaultId)] ?: false }

    suspend fun setLocalUnlockEnabled(vaultId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            if (enabled) {
                prefs[localUnlockKey(vaultId)] = true
            } else {
                prefs.remove(localUnlockKey(vaultId))
            }
        }
    }

    private fun localUnlockKey(vaultId: String) =
        booleanPreferencesKey("local_unlock_enabled_$vaultId")

    /** 登录后「启用快速解锁」引导横幅是否已被用户拒绝（不再打扰，设置页仍可启用）。 */
    fun isQuickUnlockPromptDismissed(): Flow<Boolean> =
        safeData.map { it[QUICK_UNLOCK_PROMPT_DISMISSED] ?: false }

    suspend fun setQuickUnlockPromptDismissed(dismissed: Boolean) {
        dataStore.edit { prefs ->
            if (dismissed) {
                prefs[QUICK_UNLOCK_PROMPT_DISMISSED] = true
            } else {
                prefs.remove(QUICK_UNLOCK_PROMPT_DISMISSED)
            }
        }
    }

    suspend fun setAutoLockMinutes(minutes: Int) {
        dataStore.edit { it[AUTO_LOCK_MINUTES] = minutes }
    }

    suspend fun setClipboardClearMs(value: Long) {
        dataStore.edit { it[CLIPBOARD_CLEAR_MS] = value }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setScreenSecurity(enabled: Boolean) {
        dataStore.edit { it[SCREEN_SECURITY] = enabled }
    }

    /** 回收站自动清理档位（天；0 = 不自动清空）。 */
    suspend fun setTrashAutoDeleteDays(days: Int) {
        dataStore.edit { it[TRASH_AUTO_DELETE_DAYS] = days }
    }

    /** 主题模式（`system` / `light` / `dark`）。 */
    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[THEME_MODE] = mode }
    }

    /** OLED 纯黑（深色模式 surface/background 纯黑）。 */
    suspend fun setOledPureBlack(enabled: Boolean) {
        dataStore.edit { it[OLED_PURE_BLACK] = enabled }
    }

    /** 自动填充保存提示开关（关闭后登录成功不再询问保存）。 */
    suspend fun setAutofillSavePrompt(enabled: Boolean) {
        dataStore.edit { it[AUTOFILL_SAVE_PROMPT] = enabled }
    }

    /** 自动填充后自动复制验证码开关。 */
    suspend fun setAutoCopyTotp(enabled: Boolean) {
        dataStore.edit { it[AUTO_COPY_TOTP] = enabled }
    }

    suspend fun setAutofillBaseDomainMatch(enabled: Boolean) {
        dataStore.edit { it[AUTOFILL_BASE_DOMAIN_MATCH] = enabled }
    }

    suspend fun setAutofillExactDomainOnly(enabled: Boolean) {
        dataStore.edit { it[AUTOFILL_EXACT_DOMAIN_ONLY] = enabled }
    }

    suspend fun setDefaultVaultId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(DEFAULT_VAULT_ID) else prefs[DEFAULT_VAULT_ID] = id
        }
    }
}
