package io.vaultix.vaultix.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
 * 自动锁定（Docs/10 §4「AppLifecycleObserver + InactivityTimer」的最小实现）。
 *
 * 计时语义参考 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）会话管理的实战
 * 经验：用 [SystemClock.elapsedRealtime]（不受系统时间修改影响）计时、切后台起算、
 * 回前台校验；本文件为独立实现。
 *
 * - 任一库解锁后，切后台记录时间戳；
 * - 回到前台时若离开时长 ≥ [VaultixPreferences.autoLockTimeoutMs] → lockAll，
 *   并自增 [lockEvents] 代次，供 UI 强制回到库列表根路由；
 * - timeout ≤ 0 / 进程被杀 / 手动锁定不在本类处理（进程死亡密钥天然消失）。
 *
 * 注册：VaultixApplication.onCreate 里
 * `ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockController)`。
 */
@Singleton
class AutoLockController @Inject constructor(
    private val vaultRepository: VaultRepository,
    prefs: VaultixPreferences,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lockEvents = MutableStateFlow(0)
    /** 锁定代次：UI 收集到新值即强制回到库列表根（防状态穿透）。 */
    val lockEvents: StateFlow<Int> = _lockEvents.asStateFlow()

    @Volatile
    private var backgroundedAtMs: Long? = null

    @Volatile
    private var anyUnlocked = false

    @Volatile
    private var timeoutMs: Long = DEFAULT_AUTO_LOCK_MS

    init {
        scope.launch { prefs.autoLockTimeoutMs.collect { timeoutMs = it } }
        scope.launch {
            vaultRepository.observeUnlockedVaultIds().collect { ids ->
                anyUnlocked = ids.isNotEmpty()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (anyUnlocked) {
            backgroundedAtMs = SystemClock.elapsedRealtime()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val stoppedAt = backgroundedAtMs ?: return
        backgroundedAtMs = null
        val elapsed = SystemClock.elapsedRealtime() - stoppedAt
        if (anyUnlocked && timeoutMs > 0 && elapsed >= timeoutMs) {
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

    private companion object {
        /** 与 VaultixPreferences.DEFAULT_AUTO_LOCK_MS 对齐（偏好流首值到达前的兜底）。 */
        const val DEFAULT_AUTO_LOCK_MS = 5 * 60 * 1000L
    }
}
