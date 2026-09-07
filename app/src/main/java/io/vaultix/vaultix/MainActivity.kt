package io.vaultix.vaultix

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.vaultix.ui.VaultixApp
import io.vaultix.vaultix.ui.theme.ScreenSecurityEffect
import io.vaultix.vaultix.ui.theme.VaultixTheme
import javax.inject.Inject

/**
 * FragmentActivity：androidx.biometric 的 BiometricPrompt 需要 FragmentActivity 宿主
 * （本地快速解锁的认证对话框），Compose 内容不受影响。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var preferences: VaultixPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 外观 / 安全偏好驱动主题与防截屏（设置页可实时开关）
            val dynamicColor by preferences.dynamicColor
                .collectAsStateWithLifecycle(initialValue = true)
            val screenSecure by preferences.screenSecurity
                .collectAsStateWithLifecycle(initialValue = true)

            VaultixTheme(dynamicColor = dynamicColor) {
                ScreenSecurityEffect(enabled = screenSecure)
                VaultixApp()
            }
        }
    }
}
