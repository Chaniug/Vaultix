/*
 * Vaultix — app:util
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 移植来源：Bastion（GPL-3.0）—— app/src/main/java/com/bastion/app/autofill_ng/core/
 * AutofillServiceChecker.kt
 *
 * 只取 Bastion 中与 Vaultix 能力对得上的两项检查（系统启用 / 凭据提供商），未搬运其
 * 「服务声明检查 / 权限检查 / ROM 兼容性清单 / 修复建议列表」：服务声明由 manifest 在
 * 编译期保证（漏了整个功能都不存在，运行期检测只会误报），Vaultix 的自动填充不依赖
 * 无障碍服务，权限项恒为通过——搬过来只是噪音。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.util

import android.content.Context
import android.provider.Settings
import android.view.autofill.AutofillManager

/**
 * 自动填充服务状态（设置页顶部状态卡的数据源）。
 *
 * @param systemEnabled 系统「自动填充服务」里选中的是 Vaultix（密码填充的开关）
 * @param credentialProviderEnabled Android 14+ 凭据提供商已启用（Chromium 通行密钥必需）。
 *   ⚠️ Android 16+ 起 `Settings.Secure` 对第三方 App 受限、**无法可靠读出**，此时
 *   [CredentialProviderStatus] 按「已启用」处理，避免误报「未启用」（见该对象 KDoc）。
 */
data class AutofillStatus(
    val systemEnabled: Boolean,
    val credentialProviderEnabled: Boolean,
) {
    /** 是否「密码能填、但通行密钥还差一步」——状态卡切到告警配色的依据。 */
    val needsAttention: Boolean get() = systemEnabled && !credentialProviderEnabled
}

/**
 * 自动填充服务状态只读检测。
 *
 * 与 [CredentialProviderStatus] 对称：读的是**受保护的系统安全设置**
 * （`autofill_service`），App 只有读权限，写必须由用户在系统设置里完成，所以这里
 * 不存在「一键开启」，只能检测结果并引导跳转。
 */
object AutofillStatusChecker {

    /** Settings.Secure 中记录已选自动填充服务的键（AOSP AutofillManagerService）。 */
    private const val SETTING_AUTOFILL_SERVICE = "autofill_service"

    /** 自身自动填充服务类名（manifest 中即此名）。 */
    private const val SERVICE_NAME = "VaultixAutofillService"

    fun check(context: Context): AutofillStatus = AutofillStatus(
        systemEnabled = isSystemEnabled(context),
        credentialProviderEnabled = CredentialProviderStatus.isEnabled(context),
    )

    /**
     * 系统是否把 Vaultix 选为当前自动填充服务。
     *
     * 两步判定，缺一不可：
     * 1. `AutofillManager.hasEnabledAutofillServices()` —— 系统里到底有没有启用任何服务。
     *    这一步单独用不够：它只回答「有」，选的是 Google 还是 Vaultix 分不出来。
     * 2. 读 `Settings.Secure:autofill_service` 按自身服务类名子串匹配 —— 回答「是不是我」。
     *
     * **读不到值时的取向**：部分 ROM 对第三方 App 隐藏该设置项，此时返回「已启用」而
     * 非「未启用」。理由：第 1 步已确认系统启用了某个自动填充服务，而用户既然装了
     * Vaultix 又进了这个设置页，选的通常就是它；反过来，把「读不到」报成「未启用」会
     * 让用户对着一张红卡反复去系统设置里确认，属于误导。
     */
    private fun isSystemEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AutofillManager::class.java) ?: return false
        if (!manager.hasEnabledAutofillServices()) return false
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, SETTING_AUTOFILL_SERVICE)
        }.getOrNull()
        return raw.isNullOrBlank() || raw.contains(SERVICE_NAME)
    }
}
