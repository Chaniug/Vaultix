package com.bastion.app.security

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory unlock window for secondary secure entry points such as Autofill and IME.
 *
 * This state is intentionally isolated from the main app session so that secondary
 * verification can unlock the current secure request without implicitly unlocking
 * the main application.
 */
object SecondarySessionManager {

    private const val TAG = "SecondarySessionManager"
    private const val IMMEDIATE_LOCK_SECONDARY_GRACE_MS = 60_000L

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    @Volatile
    private var unlockTimestamp: Long = 0L
    @Volatile
    private var autoLockMinutes: Int = 5

    // 保护解锁状态复合读写的锁（跨进程/多线程场景，避免撕裂读与 read-modify-write 竞态）
    private val sessionStateLock = Any()

    fun markUnlocked() {
        _isUnlocked.value = true
        unlockTimestamp = SystemClock.elapsedRealtime()
        android.util.Log.d(TAG, "Secondary session unlocked at $unlockTimestamp")
    }

    fun markLocked(clearRuntimeUnlockCache: Boolean = true) {
        _isUnlocked.value = false
        unlockTimestamp = 0L
        if (clearRuntimeUnlockCache && !SessionManager.isUnlocked.value) {
            SecurityManager.clearRuntimeUnlockCache()
        }
        android.util.Log.d(TAG, "Secondary session locked")
    }

    fun updateAutoLockTimeout(minutes: Int) {
        autoLockMinutes = minutes
        android.util.Log.d(TAG, "Secondary auto-lock timeout updated to $minutes minutes")
    }

    fun canSkipVerification(context: Context): Boolean {
        if (!_isUnlocked.value) {
            android.util.Log.d(TAG, "canSkipVerification: false (not unlocked)")
            return false
        }

        val elapsedMillis = synchronized(sessionStateLock) {
            SystemClock.elapsedRealtime() - unlockTimestamp
        }
        if (isExpired(elapsedMillis)) {
            android.util.Log.d(
                TAG,
                "canSkipVerification: false (session expired, elapsedMs=$elapsedMillis, autoLockMinutes=$autoLockMinutes)"
            )
            markLocked()
            return false
        }

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            android.util.Log.d(TAG, "canSkipVerification: false (device locked)")
            return false
        }

        android.util.Log.d(TAG, "canSkipVerification: true (unlocked, within timeout, screen unlocked)")
        return true
    }

    private fun isExpired(elapsedMillis: Long): Boolean {
        return when {
            autoLockMinutes == -1 -> false
            autoLockMinutes == -2 -> false  // 重启后锁定：运行期内不空闲超时（同 -1）
            autoLockMinutes <= 0 -> elapsedMillis >= IMMEDIATE_LOCK_SECONDARY_GRACE_MS
            else -> elapsedMillis >= autoLockMinutes * 60_000L
        }
    }
}
