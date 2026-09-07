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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
        val AUTO_LOCK_TIMEOUT_MS = longPreferencesKey("auto_lock_timeout_ms")
        val CLIPBOARD_CLEAR_MS = longPreferencesKey("clipboard_clear_ms")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        val DEFAULT_VAULT_ID = stringPreferencesKey("default_vault_id")

        const val DEFAULT_AUTO_LOCK_MS = 5 * 60 * 1000L
        const val DEFAULT_CLIPBOARD_CLEAR_MS = 30 * 1000L
    }

    private val safeData: Flow<Preferences> = dataStore.data
        .catch { error ->
            // 读取异常（首次运行或文件损坏）时退回空配置，避免整条流挂掉
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val autoLockTimeoutMs: Flow<Long> =
        safeData.map { it[AUTO_LOCK_TIMEOUT_MS] ?: DEFAULT_AUTO_LOCK_MS }

    val clipboardClearMs: Flow<Long> =
        safeData.map { it[CLIPBOARD_CLEAR_MS] ?: DEFAULT_CLIPBOARD_CLEAR_MS }

    val dynamicColor: Flow<Boolean> =
        safeData.map { it[DYNAMIC_COLOR] ?: true }

    /** 是否开启 FLAG_SECURE（防截屏 / 防最近任务缩略图）。 */
    val screenSecurity: Flow<Boolean> =
        safeData.map { it[SCREEN_SECURITY] ?: true }

    val defaultVaultId: Flow<String?> = safeData.map { it[DEFAULT_VAULT_ID] }

    suspend fun setAutoLockTimeoutMs(value: Long) {
        dataStore.edit { it[AUTO_LOCK_TIMEOUT_MS] = value }
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

    /** 同步取一次（用于启动时判断，非 UI 收集场景）。 */
    suspend fun autoLockTimeoutOnce(): Long = autoLockTimeoutMs.first()
}
