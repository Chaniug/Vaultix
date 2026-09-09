package io.vaultix.vaultix

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.autofill.AutofillIntents
import io.vaultix.vaultix.ui.VaultixApp
import io.vaultix.vaultix.ui.theme.ScreenSecurityEffect
import io.vaultix.vaultix.ui.theme.ThemeMode
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

    @Inject
    lateinit var vaultRepository: VaultRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 外观 / 安全偏好驱动主题与防截屏（设置页可实时开关）
            val themeMode by preferences.themeMode
                .collectAsStateWithLifecycle(initialValue = "system")
            val dynamicColor by preferences.dynamicColor
                .collectAsStateWithLifecycle(initialValue = true)
            val oledPureBlack by preferences.oledPureBlack
                .collectAsStateWithLifecycle(initialValue = false)
            val screenSecure by preferences.screenSecurity
                .collectAsStateWithLifecycle(initialValue = true)

            VaultixTheme(
                themeMode = ThemeMode.from(themeMode),
                dynamicColor = dynamicColor,
                oledPureBlack = oledPureBlack,
            ) {
                ScreenSecurityEffect(enabled = screenSecure)
                // AutofillActivity（MODE_UNLOCK 解锁桥）拉起本页时：解锁完成即 finish 返回原 App，
                // 避免把用户晾在 Vaultix 主界面。普通启动 / 磁贴 / 保存流程不带该 extra，不受影响。
                val unlockedIds by vaultRepository.observeUnlockedVaultIds()
                    .collectAsStateWithLifecycle(initialValue = emptySet())
                LaunchedEffect(unlockedIds) {
                    if (intent.getBooleanExtra(AutofillIntents.EXTRA_MAIN_UNLOCK_EXIT, false) &&
                        unlockedIds.isNotEmpty()
                    ) {
                        finish()
                    }
                }
                VaultixApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
