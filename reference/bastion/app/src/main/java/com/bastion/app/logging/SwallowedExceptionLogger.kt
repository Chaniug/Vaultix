package com.bastion.app.logging

import android.util.Log
import com.bastion.app.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 全局默认 tag，logcat 统一可过滤。 */
private const val DEFAULT_TAG = "Bastion"

/** 限频：每个 tag 在窗口内最多打 [RATE_MAX] 条，防日志风暴。 */
private const val RATE_WINDOW_MS = 60_000L
private const val RATE_MAX = 50

/**
 * 汇聚点（sink）：所有被 [runCatchingObserved] 吞掉、需要被观测的异常都会流经此处。
 * 默认实现走 [Log.println]；未来接监控 / 上报只需改这一处（见 [setSwallowedExceptionSink]）。
 */
private var swallowedExceptionSink: (tag: String, priority: Int, throwable: Throwable?) -> Unit =
    { tag, priority, throwable ->
        Log.println(priority, tag, "Swallowed exception: " + Log.getStackTraceString(throwable))
    }

/** 每个 tag 的限频状态。 */
private val lastLogCount: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()
private val windowStart: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()

/**
 * 语义与 [kotlin.runCatching] 对齐：保留非局部返回。
 *
 * `tag` / `priority` 置前带默认值，`block` 置末以支持 trailing-lambda 调用语法
 * （`runCatchingObserved { ... }`），与原 [kotlin.runCatching] 的调用习惯一致。
 *
 * 区别：原 [kotlin.runCatching] 会静默吞掉异常，本函数会在 DEBUG 构建下把异常上报到
 * [swallowedExceptionSink]（默认写 logcat），从而让“被吞掉的异常”变得可观测。
 * release 构建下 [logSwallowed] 直接返回，零开销、绝不外泄异常栈。
 *
 * @param T 返回值类型
 * @param tag 自定义 logcat tag；为 null 时回落到 [DEFAULT_TAG]
 * @param priority logcat 优先级，默认 [Log.WARN]
 * @param block 可能抛异常的代码块（lambda，支持非局部返回）
 * @return 与 [kotlin.runCatching] 完全一致的 [kotlin.Result]
 */
inline fun <T> runCatchingObserved(
    tag: String? = null,
    priority: Int = Log.WARN,
    block: () -> T
): kotlin.Result<T> =
    kotlin.runCatching(block).onFailure { logSwallowed(tag, priority, it) }

/**
 * 外部扩展点：用自定义 sink 替换异常汇聚点（监控 / 上报 / 崩溃平台等）。
 * 默认 sink 见 [swallowedExceptionSink]。
 */
fun setSwallowedExceptionSink(sink: (tag: String, priority: Int, throwable: Throwable?) -> Unit) {
    swallowedExceptionSink = sink
}

/**
 * 记录一次被吞掉的异常。DEBUG 构建才生效。
 *
 * 协程取消（[java.util.concurrent.CancellationException] 家族，含 JobCancellationException）
 * 属正常控制流而非故障（服务断开、界面退出、作用域取消都会触发），**完全静默**：
 * 实测一次通知会话结束就连打 11 条，占日志量且掩盖真实故障。
 */
fun logSwallowed(tag: String?, priority: Int, throwable: Throwable?) {
    if (!BuildConfig.DEBUG) return                 // release 全剔除，绝不留异常栈
    if (throwable is java.util.concurrent.CancellationException) return  // 取消是控制流，非故障
    val t = tag ?: DEFAULT_TAG
    if (!shouldLog(t)) return                      // 限频
    try {
        swallowedExceptionSink(t, priority, throwable)
    } catch (_: Throwable) {
        // 日志 sink 自身绝不能抛异常拖垮调用方（如 JVM 单测里 android.util.Log 未 mock 时会抛 RuntimeException）
    }
}

/**
 * 基于滑动时间窗的每-tag 限频：窗口内超过 [RATE_MAX] 条则丢弃，直至下一个窗口。
 */
private fun shouldLog(tag: String): Boolean {
    val now = System.currentTimeMillis()
    val start = windowStart.computeIfAbsent(tag) { AtomicLong(now) }
    val startVal = start.get()
    if (now - startVal > RATE_WINDOW_MS) {
        start.set(now)
        lastLogCount.put(tag, AtomicLong(0))
        return true
    }
    val count = lastLogCount.computeIfAbsent(tag) { AtomicLong(0) }
    return count.incrementAndGet() <= RATE_MAX
}
