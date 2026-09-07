package com.bastion.app.security.lock

import android.content.Context
import com.bastion.app.security.SecurityManager

/**
 * Single decision point for Bastion main-process startup authentication.
 *
 * Allowed callers:
 * - MainActivity cold-start bootstrap
 * - MainActivity foreground restoration
 */
object MainAppLockPolicy {

    fun resolveAccessState(
        securityManager: SecurityManager,
        context: Context,
        autoLockMinutes: Int,
        devBypassAppLock: Boolean = false
    ): MainAppAccessState {
        val firstTime = !securityManager.isMasterPasswordSet()
        if (firstTime) {
            return MainAppAccessState(
                isFirstTime = true,
                bypassEnabled = false,
                canRestoreSession = false,
                reason = "first_time_setup_required"
            )
        }

        // 开发者调试开关：跳过主应用锁直接进入，省去每次调试都要过一遍主密码/指纹。
        // 仅在主密码已设置时生效——首次设置流程本身就要引导建密码，绕过没有意义。
        if (devBypassAppLock) {
            return MainAppAccessState(
                isFirstTime = false,
                bypassEnabled = true,
                canRestoreSession = true,
                reason = "dev_bypass_app_lock"
            )
        }

        val canRestoreSession = securityManager.canRestoreMainAppSession(context, autoLockMinutes)
        return MainAppAccessState(
            isFirstTime = false,
            bypassEnabled = false,
            canRestoreSession = canRestoreSession,
            reason = if (canRestoreSession) "restorable_session" else "authentication_required"
        )
    }
}
