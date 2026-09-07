package io.vaultix.vaultix.security

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动锁定（Docs/10 §4「AppLifecycleObserver + InactivityTimer」）。
 *
 * 判定语义对齐 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `SessionManager.canSkipVerification` 与 `autoLockMinutes` 档位模型，
 * 决策逻辑抽在 [AutoLockPolicy]（纯函数、可单测），本类只做事件接线：
 * - 档位 0（立即）：切后台即 lockAll；>0：切后台记 [SystemClock.elapsedRealtime]，
 *   回前台超时即 lockAll；<0（从不）：只手动锁；
 * - 回前台时若设备屏幕仍处于 keyguard 锁定 → 立即 lockAll（Bastion：
 *   「屏幕锁定时必须重新验证」，本类对 UI 场景落成锁定而非免验证判定）；
 * - 锁定后自增 [lockEvents] 代次，供 UI 强制回到库列表根路由；
 * - 进程死亡密钥天然清零（Bastion 的「重启后锁定」无需建模）。
 *
 * 注册：VaultixApplication.onCreate 里
 * `ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockController)`。
 */
@Singleton
class AutoLockController @Inject constructor(
    @ApplicationContext context: Context,
    private val vaultRepository: VaultRepository,
    prefs: VaultixPreferences,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keyguardManager =
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    private val _lockEvents = MutableStateFlow(0)
    /** 锁定代次：UI 收集到新值即强制回到库列表根（防状态穿透）。 */
    val lockEvents: StateFlow<Int> = _lockEvents.asStateFlow()

    @Volatile
    private var autoLockMinutes: Int = AutoLockPolicy.DEFAULT_MINUTES

    @Volatile
    private var backgroundedAtMs: Long? = null

    @Volatile
    private var anyUnlocked = false

    init {
        scope.launch { prefs.autoLockMinutes.collect { autoLockMinutes = it } }
        scope.launch {
            vaultRepository.observeUnlockedVaultIds().collect { ids ->
                anyUnlocked = ids.isNotEmpty()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!anyUnlocked) return
        if (AutoLockPolicy.lockImmediatelyOnBackground(autoLockMinutes)) {
            // 档位 0：切后台立即锁（不等回前台再判断）
            lockAllNow()
        } else {
            backgroundedAtMs = SystemClock.elapsedRealtime()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!anyUnlocked) return
        val stoppedAt = backgroundedAtMs
        backgroundedAtMs = null

        val screenLocked = keyguardManager?.isKeyguardLocked == true
        val timedOut = AutoLockPolicy.backgroundTimeoutElapsed(
            nowMs = SystemClock.elapsedRealtime(),
            backgroundedAtMs = stoppedAt,
            minutes = autoLockMinutes,
        )
        if (AutoLockPolicy.screenLockRequiresRelock(screenLocked) || timedOut) {
            lockAllNow()
        }
    }

    /** 供「立即锁定」等入口直接调用（幂等）。 */
    fun lockAllNow() {
        scope.launch {
            vaultRepository.lockAll()
            _lockEvents.update { it + 1 }
        }
    }
}
