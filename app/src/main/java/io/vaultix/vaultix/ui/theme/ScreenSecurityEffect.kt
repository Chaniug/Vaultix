package io.vaultix.vaultix.ui.theme

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * 防截屏（FLAG_SECURE）：开关来自设置页「安全 → 防截屏」
 * （VaultixPreferences.screenSecurity，默认开）。
 *
 * 思路参考 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * ScreenshotProtectionUtil/ScreenshotProtection；本文件为独立实现。
 */
@Composable
fun ScreenSecurityEffect(enabled: Boolean) {
    val activity = LocalContext.current as? Activity

    DisposableEffect(enabled) {
        if (activity == null) return@DisposableEffect onDispose {}
        if (enabled) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            // 组件销毁时恢复（Activity 重建等场景不留残留状态）
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
