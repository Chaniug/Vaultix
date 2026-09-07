package com.bastion.app.security

import com.bastion.app.logging.runCatchingObserved
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话管理器 - 统一管理应用解锁状态
 *
 * 职责：
 * - 维护解锁标志、时间戳、进程标识
 * - 暴露 canSkipVerification() 统一判断免验证条件
 * - 负责与 AutoLock 逻辑联动，超时自动清理会话
 *
 * 安全窗规则：
 * - 仅在解锁后 N 分钟内允许免验证
 * - 屏幕锁定时必须重新验证
 * - 进程重启后必须重新验证
 *
 * 跨进程说明（重要）：
 * 自动填充服务（BastionAutofillServiceNg）运行在独立进程。原实现将解锁状态仅保存在
 * 本对象的内存字段中，导致自动填充进程永远读不到主进程记录的“已解锁”状态，从而在
 * 每次填充时都误判为锁定、强制二次解锁（即使主进程已设置“永不过期/不锁定”）。
 * 现将解锁状态持久化到跨进程可见的 SharedPreferences，使自动填充进程与主进程共享
 * 同一份解锁会话；unlockTimestamp 基于 SystemClock.elapsedRealtime（开机时长，跨进程一致）。
 */
object SessionManager {
    
    private const val TAG = "SessionManager"
    private const val PREFS_NAME = "bastion_session_state"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_UNLOCK_TS = "unlock_timestamp"
    private const val KEY_AUTO_LOCK = "auto_lock_minutes"
    
    // 解锁状态
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()
    
    // 解锁时间戳（基于 SystemClock.elapsedRealtime，不受系统时间修改影响）
    @Volatile
    private var unlockTimestamp: Long = 0L

    // 自动锁定超时（分钟），从 SettingsManager 同步。
    // -1 = 永不过期/不锁定；-2 = 重启后锁定（运行期不空闲超时，但进程冷启动即清锁）；>0 = 对应分钟空闲超时；0 = 立即
    @Volatile
    private var autoLockMinutes: Int = 5

    // 保护解锁状态复合读写的锁（跨进程/多线程场景，避免撕裂读与 read-modify-write 竞态）
    private val sessionStateLock = Any()
    
    // 进程标识（用于检测进程重启）
    private val processId: Int = android.os.Process.myPid()
    
    // 跨进程共享：自动填充服务运行在独立进程，需读取主进程写入的解锁状态
    private var appContext: Context? = null
    private val prefs: SharedPreferences?
        get() = appContext?.applicationContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)
    
    /**
     * 注入应用上下文，用于跨进程持久化解锁状态。
     * 在 [BastionApplication.onCreate] 中调用一次即可（各进程独立 Application 实例，
     * 但应用内部存储在本 UID 下跨进程共享）。
     */
    fun attachAppContext(context: Context) {
        appContext = context.applicationContext
    }
    
    private fun persistAll() {
        prefs?.edit()?.apply {
            putBoolean(KEY_UNLOCKED, _isUnlocked.value)
            putLong(KEY_UNLOCK_TS, unlockTimestamp)
            putInt(KEY_AUTO_LOCK, autoLockMinutes)
        }?.apply()
    }
    
    /**
     * 标记应用已解锁
     */
    fun markUnlocked() {
        _isUnlocked.value = true
        unlockTimestamp = SystemClock.elapsedRealtime()
        persistAll()
        android.util.Log.d(TAG, "Session unlocked at $unlockTimestamp, PID=$processId")
    }
    
    /**
     * 标记应用已锁定
     */
    fun markLocked(clearSecondarySession: Boolean = true) {
        _isUnlocked.value = false
        unlockTimestamp = 0L
        persistAll()
        SecurityManager.clearRuntimeUnlockCache()
        if (clearSecondarySession) {
            SecondarySessionManager.markLocked(clearRuntimeUnlockCache = false)
        }
        android.util.Log.d(TAG, "Session locked, PID=$processId")
    }
    
    /**
     * 更新自动锁定超时配置。
     * 注意：仅持久化 autoLockMinutes，不触碰 unlocked/timestamp，避免覆盖其他进程写入的解锁状态。
     */
    fun updateAutoLockTimeout(minutes: Int) {
        autoLockMinutes = minutes
        prefs?.edit()?.putInt(KEY_AUTO_LOCK, minutes)?.apply()
        android.util.Log.d(TAG, "Auto-lock timeout updated to $minutes minutes")
    }

    /**
     * 「重启后锁定」(-2) 的冷启动清锁。
     *
     * 语义：应用（主进程）被系统回收 / 用户手动杀死 / 设备重启后再次冷启动时必须重新验证，
     * 但在同一运行会话内不触发空闲超时（表现同 -1「从不」）。
     *
     * 实现：仅读取持久化的自动锁定模式；若其等于 -2，则清空已持久化的解锁会话。
     * 该调用必须在【主进程】的 Application.onCreate 中执行，并通过 [isMainProcess] 守卫，
     * 避免自动填充 / 无障碍等独立进程在自身启动时误清主进程会话（否则会破坏同源会话共享）。
     */
    fun enforceLockOnRestartIfNeeded(context: Context) {
        if (!isMainProcess(context)) return
        val mode = prefs?.getInt(KEY_AUTO_LOCK, -99) ?: -99
        if (mode == -2) {
            android.util.Log.d(TAG, "enforceLockOnRestartIfNeeded: mode=-2 -> 清除持久化会话（重启即锁定）")
            markLocked(clearSecondarySession = false)
        }
    }

    /**
     * 判断当前是否为主进程（包名进程）。自动填充 / 无障碍服务运行在 :autofill / :accessibility
     * 等独立进程，其 Application 也会 onCreate，但「重启后锁定」的清锁只应在主进程发生。
     */
    private fun isMainProcess(context: Context): Boolean {
        val pkg = context.packageName
        val procName = resolveCurrentProcessName()
        // 仅当能可靠确认是「主进程（包名进程）」时才执行清锁；
        // 子进程（:autofill / :accessibility）必须排除，否则其共享的持久化会话会被误清，
        // 进而破坏主进程的同源会话共享。无法判明时保守返回 false，不清锁。
        return procName == pkg
    }

    /**
     * 是否「确定」运行在非主进程（如 :accessibility 独立进程）。
     *
     * 用于 [com.bastion.app.BastionApplication.onCreate] 中守卫「仅主进程执行」的重度初始化：
     * 只有能可靠确认进程名以 "包名:" 开头时才返回 true；若进程名无法判明（null）或就是主进程，
     * 一律返回 false —— 这样即使进程名探测失败，也仍会执行完整初始化，
     * 绝不会误跳过主进程必备逻辑。语义与 [isMainProcess] 相反，但同样保守安全。
     */
    fun isNonMainProcess(context: Context): Boolean {
        val pkg = context.packageName
        val procName = resolveCurrentProcessName() ?: return false
        return procName != pkg && procName.startsWith("$pkg:")
    }

    /**
     * 解析当前进程名，优先用 [android.app.ActivityThread.currentProcessName]，
     * 个别 ROM 上该反射路径可能失效，回退到读取 /proc/self/cmdline（内容即进程名）。
     * 两者都失败时返回 null，交由调用方保守处理。
     */
    @SuppressLint("PrivateApi")
    private fun resolveCurrentProcessName(): String? {
        runCatchingObserved {
            val clazz = Class.forName("android.app.ActivityThread")
            val name = clazz.getMethod("currentProcessName").invoke(null) as? String
            if (!name.isNullOrEmpty()) return name
        }

        // 兜底：/proc/self/cmdline 以 \u0000 分隔，第一段即进程名（主进程=包名，子进程=包名:xxx）
        runCatchingObserved {
            val bytes = java.io.File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOf(0)
            val slice = if (end >= 0) bytes.copyOfRange(0, end) else bytes
            val text = slice.toString(Charsets.UTF_8).trim()
            if (text.isNotEmpty()) return text
        }

        return null
    }
    
    /**
     * 检查是否可以跳过验证
     *
     * 安全窗规则：
     * 1. 必须已解锁
     * 2. 未超过自动锁定时间
     * 3. 屏幕未锁定
     *
     * 跨进程：自动填充进程在此读取主进程持久化的解锁状态，使“已解锁/永不过期”在
     * 自动填充场景真正生效，避免每次填充强制二次解锁。
     *
     * @param context 上下文，用于检查屏幕锁定状态并访问跨进程 SharedPreferences
     * @return true 如果可以跳过验证
     */
    fun canSkipVerification(context: Context): Boolean {
        appContext = context.applicationContext
        // 跨进程同步：以持久化的解锁状态为准（自动填充服务独立进程）
        // 用锁保护对 _isUnlocked / unlockTimestamp / autoLockMinutes 的复合读写，避免跨进程/多线程撕裂读
        prefs?.let { p ->
            synchronized(sessionStateLock) {
                val persistedUnlocked = p.getBoolean(KEY_UNLOCKED, false)
                val persistedTs = p.getLong(KEY_UNLOCK_TS, 0L)
                val persistedAutoLock = p.getInt(KEY_AUTO_LOCK, autoLockMinutes)
                _isUnlocked.value = persistedUnlocked
                if (persistedUnlocked && persistedTs != 0L) {
                    unlockTimestamp = persistedTs
                } else if (!persistedUnlocked) {
                    unlockTimestamp = 0L
                }
                autoLockMinutes = persistedAutoLock
            }
        }
        
        // 检查是否已解锁
        if (!_isUnlocked.value) {
            android.util.Log.d(TAG, "canSkipVerification: false (not unlocked)")
            return false
        }
        
        // 检查是否超时（仅 >0 的空闲超时模式会锁；-1 从不 / -2 重启后锁定 均不在此处锁）
        val elapsedMinutes = (SystemClock.elapsedRealtime() - unlockTimestamp) / 60000
        if (autoLockMinutes > 0 && elapsedMinutes >= autoLockMinutes) {
            android.util.Log.d(TAG, "canSkipVerification: false (session expired, elapsed=$elapsedMinutes min)")
            markLocked(clearSecondarySession = false)
            return false
        }
        
        // 检查屏幕是否锁定（仅返回 false，不主动清除会话，避免切后台时误锁）
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            android.util.Log.d(TAG, "canSkipVerification: false (device locked)")
            return false
        }
        
        android.util.Log.d(TAG, "canSkipVerification: true (unlocked, within timeout, screen unlocked)")
        return true
    }
    
    /**
     * 刷新会话时间戳（用户活动时调用）
     */
    fun refreshSession() {
        if (_isUnlocked.value) {
            unlockTimestamp = SystemClock.elapsedRealtime()
            persistAll()
            android.util.Log.d(TAG, "Session refreshed at $unlockTimestamp")
        }
    }
    
    /**
     * 检查会话是否过期（不自动锁定，仅检查）
     */
    fun isSessionExpired(): Boolean {
        if (!_isUnlocked.value) return true
        val elapsedMinutes = (SystemClock.elapsedRealtime() - unlockTimestamp) / 60000
        return autoLockMinutes > 0 && elapsedMinutes >= autoLockMinutes
    }
    
    /**
     * 获取剩余有效时间（分钟）
     */
    fun getRemainingMinutes(): Int {
        if (!_isUnlocked.value) return 0
        if (autoLockMinutes <= 0) return -1  // -1 从不 / -2 重启后锁定：均无空闲倒计时
        val elapsedMinutes = (SystemClock.elapsedRealtime() - unlockTimestamp) / 60000
        return maxOf(0, autoLockMinutes - elapsedMinutes.toInt())
    }
}
