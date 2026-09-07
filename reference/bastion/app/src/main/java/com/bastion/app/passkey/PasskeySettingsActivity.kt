package com.bastion.app.passkey

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.bastion.app.ui.screens.PasskeySettingsScreen
import com.bastion.app.ui.theme.BastionTheme

/**
 * Passkey 设置入口 Activity
 * 用于系统设置中的 Credential Provider 页面跳转
 */
class PasskeySettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BastionTheme {
                PasskeySettingsScreen(
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}
