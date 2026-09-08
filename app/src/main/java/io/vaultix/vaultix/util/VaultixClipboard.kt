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
    private val clipboardScope by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    }
}
