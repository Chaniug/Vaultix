package com.bastion.app.ui.base

import android.content.Context
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.bastion.app.data.AppSettings
import com.bastion.app.data.Language
import com.bastion.app.security.SessionManager
import com.bastion.app.utils.LocaleHelper
import com.bastion.app.utils.ScreenshotProtectionUtil
import com.bastion.app.utils.SettingsManager

/**
 * Bastion 应用的统一基类 Activity
 * 
 * 功能：
 * - attachBaseContext：统一处理语言上下文（LocaleHelper），带超时保护
 * - Theme：统一监听 SettingsManager 并应用主题（深色模式、动态取色）
 * - ScreenshotProtection：统一处理防截屏逻辑
 * - SessionManager：统一管理会话状态和自动锁定
 * 
 * 继承此基类的 Activity：
 * - MainActivity
 * - AutofillPickerActivityV2
 */
abstract class BaseBastionActivity : FragmentActivity() {
    
    protected lateinit var settingsManager: SettingsManager
    
    // 缓存的设置，供子类使用
    protected var cachedSettings: AppSettings? = null
    
    override fun attachBaseContext(newBase: Context?) {
        if (newBase != null) {
            // 阶段二·#8：用同步读取（带进程内缓存）替换主线程 runBlocking + 200ms 超时，
            // 消除启动期主线程卡顿；异常时回退中文以保持原行为。
            val language = try {
                SettingsManager(newBase).getLanguageSync()
            } catch (e: Exception) {
                Language.CHINESE
            }
            super.attachBaseContext(LocaleHelper.setLocale(newBase, language))
        } else {
            super.attachBaseContext(newBase)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        disableSystemAutofillForBastionUi()
        
        settingsManager = SettingsManager(applicationContext)
        
        // 监听设置变化，更新截图保护和自动锁定配置
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsManager.settingsFlow.collect { settings ->
                    cachedSettings = settings
                    
                    // 更新截图保护
                    applyScreenshotProtection(settings.screenshotProtectionEnabled)
                    
                    // 更新 SessionManager 的自动锁定超时
                    SessionManager.updateAutoLockTimeout(settings.autoLockMinutes)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()

        // Keep Bastion's own UI out of the platform Autofill pipeline so the app
        // never suggests or saves credentials for its own internal forms.
        disableSystemAutofillForBastionUi()

        // Sync latest timeout before expiration check to avoid using stale defaults.
        // Only update if settings have been loaded; do not overwrite with fallback default.
        cachedSettings?.let { SessionManager.updateAutoLockTimeout(it.autoLockMinutes) }

        if (!shouldEnforceSharedSessionLock()) {
            return
        }
        
        // 检查会话是否过期
        if (SessionManager.isSessionExpired()) {
            SessionManager.markLocked()
            onSessionExpired()
        } else {
            // 刷新会话时间戳
            SessionManager.refreshSession()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // 用户交互时刷新会话时间戳，确保“非空闲”不会被错误锁定
        SessionManager.refreshSession()
    }
    
    /**
     * 应用截图保护设置
     */
    protected fun applyScreenshotProtection(enabled: Boolean) {
        if (enabled) {
            ScreenshotProtectionUtil.enableScreenshotProtection(this)
        } else {
            ScreenshotProtectionUtil.disableScreenshotProtection(this)
        }
    }
    
    /**
     * 会话过期回调，子类可覆写以处理锁定逻辑
     */
    protected open fun onSessionExpired() {
        // 默认空实现，子类可覆写
        android.util.Log.d("BaseBastionActivity", "Session expired")
    }

    /**
     * 是否沿用主应用共享会话门控。
     * 某些独立鉴权页面只借用验证界面，不应把共享会话作为前置条件。
     */
    protected open fun shouldEnforceSharedSessionLock(): Boolean {
        return true
    }
    
    /**
     * 标记验证成功，更新会话状态
     */
    protected fun markAuthenticationSuccess() {
        SessionManager.markUnlocked()
    }

    private fun disableSystemAutofillForBastionUi() {
        window?.decorView?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        findViewById<View?>(android.R.id.content)?.importantForAutofill =
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
}
