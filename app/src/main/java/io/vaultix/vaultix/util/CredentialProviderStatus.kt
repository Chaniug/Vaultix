/*
 * Vaultix — app:settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.util

import android.content.Context
import android.provider.Settings

/**
 * Credential Provider（Android 14+ 凭据管理器数据源）启用状态检测。
 *
 * Chromium 系浏览器（Chrome / Edge）在 Android 14+ 只向「系统已启用」的 Credential
 * Provider 要密码与通行密钥；App 无法自行写入该开关（`credential_service` 是受保护
 * 的安全设置，必须用户在系统设置里显式勾选）。这里只做**只读检测**，供设置页展示
 * 状态并引导用户去系统设置开启。
 *
 * 存储格式随系统版本可能是 XML / 组件列表，故按自身 Provider 的类名子串匹配
 * （类名足够唯一，避免解析格式耦合）。
 */
object CredentialProviderStatus {

    /** Settings.Secure 中记录已启用 Provider 的键（AOSP CredentialManagerService）。 */
    private const val SETTING_CREDENTIAL_SERVICE = "credential_service"

    /** 自身 Provider 服务类名（manifest 中即此名）。 */
    private const val PROVIDER_SERVICE_NAME = "VaultixCredentialProviderService"

    /** 是否已被系统启用为 Credential Provider。 */
    fun isEnabled(context: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, SETTING_CREDENTIAL_SERVICE)
        }.getOrNull()
        return !raw.isNullOrBlank() && raw.contains(PROVIDER_SERVICE_NAME)
    }
}
