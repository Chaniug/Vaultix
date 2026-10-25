/*
 * Vaultix — app
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 设计（含「触发即忘」的延迟清空任务、清空前校验剪贴板未被用户改写、
 * API 33+ IS_SENSITIVE 标记）参考 Bastion 项目（GPL-3.0，Copyright 2025
 * JoyinJoester）的 utils/ClipboardUtils.kt；本文件按 Vaultix 的
 * clipboardClearMs 偏好与注入风格独立编写，同样以 GPL-3.0 发布。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 安全剪贴板（敏感字段复制专用）。
 *
 * 语义（与 Bastion 一致）：
 * - 复制即安排延迟清空任务（「触发即忘」，不绑定页面生命周期）；
 * - 清空前**校验剪贴板内容仍是本次复制的内容**（或无法读取），
 *   避免清掉用户之后手动复制的新内容；
 * - API 33+ 给剪贴条目打 `IS_SENSITIVE` 标记，系统预览不泄露；
 * - `autoClearMs <= 0` 表示不自动清除。
 *
 * 纯 JVM 单测友好：调度器 lazy 初始化，避免类加载触碰主线程（Bastion CI 教训）。
 */
@Singleton
class VaultixClipboard @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val appContext = context.applicationContext

    /**
     * 延迟清除任务的调度器。
     *
     * ⚠️⚠️ **必须是 `Dispatchers.Default`，不能是 `Dispatchers.Main.immediate`**
     * （#188，2026-10-26）。
     *
     * `Dispatchers.Main` 上的 `delay()` 走的是 **Handler 消息 + SystemClock 对齐的
     * Choreographer/消息队列**，它会因为以下任何一种情况被推迟甚至永不执行：
     * - 设备进入 Doze / App Standby，进程被冻结（`delay` 的唤醒消息不会准时投递）；
     * - App 已经退到后台且没有前台服务，主线程消息队列被系统限流；
     * - 用户锁屏后进程被 cache 回收（此时 `clearJob` 连同整个进程一起消失）。
     * 于是"30 秒后清除剪贴板"在最需要它的时候（用户复制完就把手机塞回兜里）**最不可靠**。
     *
     * `Dispatchers.Default` 的 `delay` 走 `DefaultExecutor`，同样受进程冻结影响，
     * 但它不受**主线程消息队列**的排期与限流影响 —— 少一层可被推迟的中间环节。
     * 真正把"进程都死了也要清"补上的是 `MainActivity` / 锁库时的
     * [clearNow]（见 `VaultLockManagerImpl` 的锁定钩子）：锁定是"用户已经离开"的
     * 最可靠信号，在那时立刻清一次，比指望定时器更实在。
     *
     * `ClipboardManager` 本身是系统服务代理，在任何线程调用都安全。
     */
    private val clipboardScope by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Volatile
    private var clearJob: Job? = null

    /** 复制文本；sensitive 内容按 [autoClearMs] 延迟清空（0 = 不自动清除）。 */
    fun copy(
        text: String,
        label: String = "Vaultix",
        sensitive: Boolean = true,
        autoClearMs: Long,
    ) {
        if (text.isBlank()) return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text).apply {
            if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)

        clearJob?.cancel()
        clearJob = clipboardScope.launch {
            if (autoClearMs <= 0) return@launch
            delay(autoClearMs)
            clearIfExpected(clipboard, label, text)
        }
    }

    fun cancelPendingClear() {
        clearJob?.cancel()
    }

    /**
     * **立刻**清空剪贴板（若内容仍是本应用复制的那一份）。
     *
     * 与定时清除的区别：不看 [autoClearMs]，也不重新安排任务。调用点是
     * "用户已经离开"的强信号 —— 锁库 / 锁屏 / 退出应用。这类信号比定时器可靠，
     * 因为它一定会在进程还活着的时候被调用一次。
     *
     * ⚠️ 与 [clearIfExpected] 同款取舍：**只有确认内容是本应用放进去的才清**。
     * Android 10+ 后台读剪贴板会拿到 null（`primaryClip` 返回空），
     * 此时说明"读不到"而不是"是我放的"—— 读不到就什么都不做，绝不能盲清，
     * 否则会误删用户自己复制的内容。
     */
    fun clearNow() {
        clearJob?.cancel()
        clearJob = null
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        val current = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        // 只清本应用标记过 IS_SENSITIVE 的条目（见 [copy]）——那是"我们放的"的可靠印记。
        val sensitive = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
            } else {
                current.description?.label?.toString() == LABEL
            }
        }.getOrDefault(false)
        if (!sensitive) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    private fun clearIfExpected(clipboard: ClipboardManager, label: String, text: String) {
        val current = clipboard.primaryClip
        if (current == null) return // 已无可清内容
        val currentText = runCatching {
            current.getItemAt(0)?.coerceToText(appContext)?.toString()
        }.getOrNull()
        val currentLabel = current.description?.label?.toString()
        if (currentText == text && currentLabel == label) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    private companion object {
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** 默认裁剪标签（`copy` 的 `label` 默认值），供 API 33 以下的 [clearNow] 识别。 */
        const val LABEL = "Vaultix"
    }
}
