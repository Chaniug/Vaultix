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

    suspend fun setDefaultVaultId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(DEFAULT_VAULT_ID) else prefs[DEFAULT_VAULT_ID] = id
        }
    }
}
