package com.bastion.app.perf

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bastion.app.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 主线程卡顿监控（看门狗）。
 *
 * 关键约束（Android 17 功耗治理）：
 * - 仅在 **DEBUG** 构建中启用常驻监控；release 包默认不启动，
 *   以消除每秒一次的主线程心跳 + 线程池检查带来的待机功耗。
 * - 无论 debug/release，App 进入后台(onPause)即暂停监控，回到前台(onResume)恢复，
 *   避免后台无谓唤醒。
 * - `executor` 使用懒加载，release 包因 `BuildConfig.DEBUG == false` 直接 return，
 *   不会创建守护线程池，运行期零额外开销。
 */
object MainThreadStallMonitor {
    private const val TAG = "BastionPerfWatchdog"
    private const val CHECK_INTERVAL_MS = 1_000L
    private const val STALL_THRESHOLD_MS = 2_500L
    private const val RELOG_INTERVAL_MS = 10_000L

    private val started = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainThread = Looper.getMainLooper().thread
    /**
     * 心跳 Runnable。生命周期由 [pause] / [resume] 的 removeCallbacks / post 控制，
     * 自身不再因 paused 而 return——否则一旦 return 就不再续期，心跳链永久断裂，
     * 而 [resume] 只改标志位不会重新投递，看门狗随后会把"没有心跳"误报成
     * "主线程卡死"（blockedForMs 持续累加，每 10 秒刷一条假告警）。
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            lastHeartbeatAt.set(SystemClock.uptimeMillis())
            mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }
    private val lastHeartbeatAt = AtomicLong(SystemClock.uptimeMillis())
    private val lastWarningAt = AtomicLong(0L)
    private val executor by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "bastion-main-watchdog").apply { isDaemon = true }
        }
    }

    fun start(application: Application) {
        // 常驻卡顿监控仅在 DEBUG 构建启用；release 包默认关闭以节省待机功耗。
        if (!BuildConfig.DEBUG) return
        if (!started.compareAndSet(false, true)) {
            // 已初始化，确保前台时处于恢复状态
            resume()
            return
        }

        mainHandler.post(heartbeat)

        executor.scheduleWithFixedDelay(
            ::checkMainThread,
            CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = resume()
            override fun onPause(owner: LifecycleOwner) = pause()
        })
    }

    /** 暂停监控（App 退到后台时调用），停止心跳与卡顿检查。 */
    fun pause() {
        if (!started.get()) return
        paused.set(true)
        // 退后台直接摘掉心跳回调，不再周期性唤醒主线程。
        mainHandler.removeCallbacks(heartbeat)
    }

    /** 恢复监控（App 回到前台时调用），重置心跳基准避免误报。 */
    fun resume() {
        if (!started.get()) return
        paused.set(false)
        lastHeartbeatAt.set(SystemClock.uptimeMillis())
        // 重新投递心跳（pause 时已被移除），否则心跳永久停摆。
        mainHandler.post(heartbeat)
    }

    private fun checkMainThread() {
        if (paused.get()) return
        val now = SystemClock.uptimeMillis()
        val blockedForMs = now - lastHeartbeatAt.get()
        if (blockedForMs < STALL_THRESHOLD_MS) return

        val previousWarningAt = lastWarningAt.get()
        if (now - previousWarningAt < RELOG_INTERVAL_MS) return
        if (!lastWarningAt.compareAndSet(previousWarningAt, now)) return

        val stack = mainThread.stackTrace
        // 栈顶停在 MessageQueue.nativePollOnce —— 主线程正空闲等待下一条消息，
        // 并非被耗时任务卡住。息屏、退后台或系统进入低功耗时心跳投递会被整体延后
        // （系统 BlockMonitor 也会打出 delayed 数万毫秒的记录），此时若照常判定
        // 就会刷出"主线程卡死"的假告警。空闲态直接重置基准，不产生告警。
        if (stack.firstOrNull()?.methodName == "nativePollOnce") {
            lastHeartbeatAt.set(now)
            return
        }

        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMb = runtime.maxMemory() / (1024L * 1024L)
        val mainTop = stack
            .take(8)
            .joinToString(separator = " <- ") { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            }
        Log.w(
            TAG,
            "main_thread_stall blockedForMs=$blockedForMs heapUsedMb=$usedMb heapMaxMb=$maxMb top=$mainTop"
        )
    }
}
