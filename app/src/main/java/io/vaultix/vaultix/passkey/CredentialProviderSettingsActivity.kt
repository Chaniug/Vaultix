/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Credential Provider 的「设置」入口宿主（provider.xml `android:settingsActivity`）。
 *
 * 来源/对齐：Bitwarden Android 客户端把凭据提供商的设置入口指到其专属的
 * 「Settings → Autofill / Passkeys」页面；Keyguard / Bastion 同样提供独立的
 * 通行密钥管理入口。系统凭据管理器（设置 → 密码和账号 → Vaultix → 齿轮）会拉起
 * 这里指向的 Activity。
 *
 * ⚠️ 此前 `credential_provider.xml` 把 settingsActivity 指向 `MainActivity`
 * （带底部导航的 app 主壳）。语义不符：系统期望的是「能管理本 Provider 凭据」的界面，
 * 指到主壳会让用户从系统设置点进来后落在库列表页、看不到任何凭据管理入口。
 * 本 Activity 直接承载 [AutofillSettingsScreen]（含自动填充开关 + 凭据提供商状态 +
 * 快速填充磁贴），是当前最贴近「凭据管理」的页面。
 */
package io.vaultix.vaultix.passkey

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.vaultix.ui.settings.AutofillSettingsScreen
import io.vaultix.vaultix.ui.theme.VaultixTheme

@AndroidEntryPoint
class CredentialProviderSettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VaultixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CredentialProviderSettingsHost(onBack = { finish() })
                }
            }
        }
    }
}

/**
 * 承载自动填充/凭据提供商设置页。
 *
 * 抽成独立 composable：既避免 Activity 内直接引 hiltViewModel 造成的可测性下降，
 * 也让 detekt 的 LongMethod 门禁不受影响。
 */
@Composable
private fun CredentialProviderSettingsHost(onBack: () -> Unit) {
    AutofillSettingsScreen(
        onBack = onBack,
        viewModel = hiltViewModel(),
    )
}
