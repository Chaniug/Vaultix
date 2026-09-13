package io.vaultix.vaultix

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

    /**
     * 「解锁完就退出、把控制权交还宿主 App」标记（由 [AutofillIntents.EXTRA_MAIN_UNLOCK_EXIT] 驱动）。
     *
     * ⚠️ 2026-09-13 修（用户报告：QQ 输入框选填充 → 指纹解锁完**人却留在 Vaultix 里**、
     * 填充再也没发生）。此前是**在 composition 里直接读 `intent`**，且效果块只以
     * `unlockedIds` 为键 —— 于是两种情况都收不了尾：
     * ① `FLAG_ACTIVITY_CLEAR_TOP` 复用已有 Activity ⇒ 走 [onNewIntent]，
     *    而 composition 读到的仍是**旧 intent**（拿不到这个 extra）；
     * ② 本次进入时库**已经是解锁态** ⇒ `unlockedIds` 没有"变化"，效果块压根不会再跑。
     * 现在把它变成一个真正的 Compose 状态，并在 [onNewIntent] 里同步刷新。
     */
    private val exitAfterUnlock = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        exitAfterUnlock.value = hasMainUnlockExit(intent)
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
                val exitAfterUnlockNow by exitAfterUnlock
                // 两个键都要进 key：标记来自**新 intent**、或"本来是锁定态刚被解开"，
                // 任一变化都必须收尾（finish 回宿主 App → 那边 onResume 完成回填）。
                LaunchedEffect(exitAfterUnlockNow, unlockedIds) {
                    if (exitAfterUnlockNow && unlockedIds.isNotEmpty()) finish()
                }
                VaultixApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 复用时（FLAG_ACTIVITY_CLEAR_TOP）必须同步刷新标记，否则这次「解锁即回填」的
        // 收尾条件永远不成立 —— 用户就会停在 Vaultix 主界面上，填充悄无声息地失败。
        exitAfterUnlock.value = hasMainUnlockExit(intent)
    }

    /** 是否由「自动填充的解锁桥」（[io.vaultix.vaultix.autofill.AutofillActivity]）拉起。 */
    private fun hasMainUnlockExit(intent: Intent?): Boolean =
        intent?.getBooleanExtra(AutofillIntents.EXTRA_MAIN_UNLOCK_EXIT, false) == true
}
