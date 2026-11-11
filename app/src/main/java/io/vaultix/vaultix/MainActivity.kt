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
import io.vaultix.vaultix.ui.glass.isLiquidGlassBlurSupported
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

    /**
     * 外部「用 Vaultix 打开 xxx.kdbx」带进来的 URI（2026-11-02）。
     *
     * ## 为什么是 Compose 状态而不是直接读 `intent`
     *
     * 与 [exitAfterUnlock] **完全同一个坑**：`FLAG_ACTIVITY_CLEAR_TOP` /
     * `singleTop` 复用已有 Activity 时走 [onNewIntent]，而 composition 里读
     * `intent` 拿到的是**旧对象** ⇒ 用户在文件管理器里第二次点开另一个 `.kdbx` 时，
     * App 只会若无其事地停在上一个页面（表现：点了没反应）。
     *
     * `null` = 这次不是由外部文件意图拉起的。
     */
    private val externalKdbxUri = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        exitAfterUnlock.value = hasMainUnlockExit(intent)
        externalKdbxUri.value = externalKdbxUriOf(intent)
        setContent {
            // 外观 / 安全偏好驱动主题与防截屏（设置页可实时开关）
            val themeMode by preferences.themeMode
                .collectAsStateWithLifecycle(initialValue = "system")
            // ⚠️ 2026-11-12 #285 首帧占位值改 false：必须与
            //    VaultixPreferences.dynamicColor 的 `?: false`、VaultixTheme 的
            //    `dynamicColor: Boolean = false` 三处一致，否则冷启动第一帧会先吃
            //    壁纸派生色、第二帧才回品牌浅色板（闪一下色）。理由见 VaultixBrandColor §五。
            val dynamicColor by preferences.dynamicColor
                .collectAsStateWithLifecycle(initialValue = false)
            val oledPureBlack by preferences.oledPureBlack
                .collectAsStateWithLifecycle(initialValue = false)
            // 底栏液态玻璃：与 themeMode / dynamicColor 同一取向 —— 观感偏好只在
            // 组合顶层收一次，再往下传给主壳（避免两处各读一份偏好）。
            // ⚠️ 2026-11-09 默认开：首帧占位值必须跟 DataStore 的新默认值一致，
            //    否则会「第一帧实色、第二帧玻璃」闪一下。低版本不支持真模糊 ⇒
            //    首帧仍走实色分支，与随后降级档的实色观感一致，也不会闪。
            val liquidGlassDock by preferences.liquidGlassDock
                .collectAsStateWithLifecycle(initialValue = isLiquidGlassBlurSupported())
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
                val externalUri by externalKdbxUri
                VaultixApp(
                    liquidGlassDock = liquidGlassDock,
                    externalKdbxUri = externalUri,
                    // ★ 消费即清：否则用户从添加页返回、或导航到别处之后再返回，
                    //   这条 URI 会**反复**把人拽回添加页（一个自己打不开的循环）。
                    //   清空发生在 NavHost 处理完这次导航之后（见 VaultixApp 的 LaunchedEffect）。
                    onExternalKdbxConsumed = { externalKdbxUri.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 复用时（FLAG_ACTIVITY_CLEAR_TOP）必须同步刷新标记，否则这次「解锁即回填」的
        // 收尾条件永远不成立 —— 用户就会停在 Vaultix 主界面上，填充悄无声息地失败。
        exitAfterUnlock.value = hasMainUnlockExit(intent)
        // 同上：第二次「用 Vaultix 打开」必须能被看见（见 externalKdbxUri 的 KDoc）。
        externalKdbxUri.value = externalKdbxUriOf(intent)
    }

    /**
     * 从外部 intent 里取出那个 `.kdbx` 的 URI（没有则 `null`）。
     *
     * ## 只看 `ACTION_VIEW` 的 `data`，**不看** `ACTION_SEND` 的 `EXTRA_STREAM`
     *
     * | intent | 为什么这么处理 |
     * |---|---|
     * | `ACTION_VIEW` + `data` | 文件管理器「打开方式」走的就是它 ⇒ 支持 |
     * | `ACTION_SEND` + `EXTRA_STREAM` | 分享面板的「发送到」在部分机型上**只带临时授权、不带 MIME**，
     *   且同一份文件常被当图片/文本流打开 ⇒ 支持它要额外一套校验，收益不明显 |
     *
     * ## 为什么还要判 `content:` scheme
     *
     * `file://` 从 Android 7 起就不该出现在跨应用 intent 里（`FileUriExposedException`），
     * 而 `content://` 才是 SAF / 各文件管理器给的东西。别的 scheme（`http:` 之类）
     * 更不该被当成"本地文件"去 `openInputStream` —— 那会变成一次没有任何用户可见提示的联网。
     * ⇒ 只接受 `content:`，其余一律当"没有外部文件"（App 正常打开库列表）。
     */
    private fun externalKdbxUriOf(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme?.lowercase() != "content") return null
        return uri.toString().takeIf { it.isNotBlank() }
    }

    /** 是否由「自动填充的解锁桥」（[io.vaultix.vaultix.autofill.AutofillActivity]）拉起。 */
    private fun hasMainUnlockExit(intent: Intent?): Boolean =
        intent?.getBooleanExtra(AutofillIntents.EXTRA_MAIN_UNLOCK_EXIT, false) == true
}
