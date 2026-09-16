/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.common.logging

/** 日志级别。刻意不复用 `android.util.Log` 的常量 —— 本模块是**纯 Kotlin**（见 [VaultixLog]）。 */
enum class LogLevel { DEBUG, WARN, ERROR }

/**
 * Vaultix 的诊断日志门面（2026-09-16 新增，参考 Bastion 的 `SwallowedExceptionLogger`）。
 *
 * ## 为什么需要它
 *
 * 在此之前，`data:bitwarden` 与 `data:repository` **完全没有日志设施**
 * （`.ai/issues/07-数据与同步.md` #92 明确记着「连 `android.util.Log` 都没引」）。
 * 后果是同步/认证类故障**只能复现、不能观察**：
 * - 「登录已失效」的三根因（issue #8）全靠真机抓包才定位；
 * - 4xx 弃单导致条目消失（#92）**连一条痕迹都没有**。
 *
 * ## 为什么放在 core:common 且**不依赖 Android**
 *
 * `core:common` 是纯 Kotlin 模块（只依赖 coroutines / datetime），供所有层依赖
 * （`data:bitwarden` 就依赖它）。若在这里引 `android.util.Log`：
 * ① 会把纯逻辑模块拖成 Android 模块；② 单测里 `Log` 未 mock 会直接抛异常。
 *
 * ⇒ 本门面**只定义一个 sink 接口**，真正写日志的实现由 Android 侧注入
 *   （见 [install]）。这同时让"日志内容"变得**可断言** —— 单测注入一个收集器即可。
 *
 * ## ★★ 铁律：绝不记录敏感数据
 *
 * 这是密码管理器，日志一旦泄露比不记更糟。**禁止**写入：
 * - 主密码、PIN、任何用户输入的密码
 * - 对称密钥、Keystore 材料、`enc‖mac`、信封密文
 * - access / refresh token、`Authorization` 头
 * - 解密后的任何条目字段（用户名、密码、备注、TOTP secret）
 *
 * **允许**的记录：状态迁移、结果分类、耗时、库类型、条目/文件夹**数量**、
 * 失败**原因类别**、host（建议经 [redact] 脱敏）。
 *
 * ⚠️ 判断标准：**「这条日志出现在别人手机的错误报告里，我能接受吗？」**
 *
 * ## 性能与安全的两道闸
 *
 * 1. **默认关闭**：[enabled] 初值 false，且消息参数是 `() -> String`
 *    ⇒ 关闭时**连字符串拼接都不会发生**（零开销），release 构建不开启即完全静默。
 * 2. **限频**：每个 tag 每 [RATE_WINDOW_MS] 最多 [RATE_MAX] 条，
 *    防止循环里的日志把 logcat 刷爆、掩盖真正的故障（Bastion 的取舍，实测有效）。
 */
object VaultixLog {

    /** 每 tag 的限频窗口（毫秒）。 */
    private const val RATE_WINDOW_MS = 60_000L

    /** 每 tag 在窗口内最多打印条数。 */
    private const val RATE_MAX = 50

    /**
     * 日志总开关。**默认 false**（零开销）。
     *
     * Android 侧应在 `Application.onCreate` 里按 `BuildConfig.DEBUG` 打开 —— 见 [install]。
     */
    @Volatile
    var enabled: Boolean = false

    /** 实际写入日志的位置。默认**丢弃**（未装配时什么都不做）。 */
    @Volatile
    private var sink: (tag: String, level: LogLevel, message: String, throwable: Throwable?) -> Unit =
        { _, _, _, _ -> }

    /** 限频状态：tag -> (窗口起点, 窗口内已打印数)。 */
    private val rateStart = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val rateCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 一次性装配。Android 侧调用，例如：
     *
     * ```kotlin
     * VaultixLog.install(enabled = BuildConfig.DEBUG) { tag, level, message, throwable ->
     *     when (level) {
     *         LogLevel.DEBUG -> Log.d(tag, message, throwable)
     *         LogLevel.WARN  -> Log.w(tag, message, throwable)
     *         LogLevel.ERROR -> Log.e(tag, message, throwable)
     *     }
     * }
     * ```
     *
     * ⚠️ sink **自身绝不允许抛异常**（门面里已兜底 catch）—— 否则会把
     * 「记日志」变成「制造崩溃」。
     */
    fun install(
        enabled: Boolean,
        sink: (tag: String, level: LogLevel, message: String, throwable: Throwable?) -> Unit,
    ) {
        this.sink = sink
        this.enabled = enabled
    }

    /** 关闭并把 sink 复位为丢弃（测试收尾用）。 */
    fun reset() {
        enabled = false
        sink = { _, _, _, _ -> }
        rateStart.clear()
        rateCount.clear()
    }

    fun d(tag: String, message: () -> String) = emit(tag, LogLevel.DEBUG, null, message)

    /**
     * ⚠️ **`message` 必须是最后一个参数**。
     *
     * Kotlin 的 trailing lambda **只绑定到最后一个参数**。若写成
     * `w(tag, message, throwable = null)`，那么 `VaultixLog.w(TAG) { "…" }`
     * 会把 lambda 当成 `throwable` 传进去 —— 编译期报
     * `No value passed for parameter 'message'`，而错误信息完全指不到真因。
     *
     * Bastion 的 `runCatchingObserved` 特意把 block 置末，就是同一条理由。
     */
    fun w(tag: String, throwable: Throwable? = null, message: () -> String) =
        emit(tag, LogLevel.WARN, throwable, message)

    /** 见 [w] 的签名说明：`message` 必须在最后。 */
    fun e(tag: String, throwable: Throwable? = null, message: () -> String) =
        emit(tag, LogLevel.ERROR, throwable, message)

    private inline fun emit(
        tag: String,
        level: LogLevel,
        throwable: Throwable?,
        message: () -> String,
    ) {
        if (!enabled) return
        // ⚠️ 协程取消是**正常控制流**（作用域退出 / 服务断开），不是故障。
        //    记它只会稀释日志、掩盖真问题（Bastion 实测：一次会话结束连打 11 条）。
        if (throwable is java.util.concurrent.CancellationException) return
        if (!shouldLog(tag)) return
        try {
            sink(tag, level, message(), throwable)
        } catch (_: Throwable) {
            // 日志设施绝不能反过来把调用方拖垮。
        }
    }

    /** 每 tag 的滑动窗口限频。非线程严格但**刻意如此**：日志不值得加锁，偶尔多打几条无妨。 */
    private fun shouldLog(tag: String): Boolean {
        val now = System.currentTimeMillis()
        val start = rateStart[tag]
        if (start == null || now - start > RATE_WINDOW_MS) {
            rateStart[tag] = now
            rateCount[tag] = 1
            return true
        }
        val count = (rateCount[tag] ?: 0) + 1
        rateCount[tag] = count
        return count <= RATE_MAX
    }
}

/**
 * 脱敏：保留首尾少量字符，中间打码。用于**可以记录但需弱化**的标识
 * （账号邮箱、服务器 host）。
 *
 * 规则（刻意保守）：
 * - 长度 ≤ [keep] * 2 + 2 时**整体打码**（太短，露出首尾等于泄露）；
 * - 否则保留前 [keep] 与后 [keep] 个字符，中间固定 3 个 `*`（**不透露原长**）。
 *
 * ⚠️ 它**不是**"可以随便记敏感数据"的许可证 —— 密码/密钥/token **一律不许进日志**，
 *    脱敏只用于"记了有助于定位、不记又区分不了环境"的标识。
 */
fun redact(value: String, keep: Int = 2): String {
    if (value.isEmpty()) return ""
    if (value.length <= keep * 2 + 2) return "***"
    return value.take(keep) + "***" + value.takeLast(keep)
}
