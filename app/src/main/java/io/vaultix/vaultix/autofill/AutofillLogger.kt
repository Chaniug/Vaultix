/*
 * Vaultix — app:autofill
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill

import android.util.Log
import io.vaultix.vaultix.BuildConfig

/**
 * 自动填充诊断日志（**仅 debug 构建**，release 为空实现）。
 *
 * 存在的理由：浏览器填充「静默失效」（Edge 看不到条目、点了没反应）只能靠现场数据定位，
 * 而项目此前零日志，排障只能靠猜。
 *
 * ⚠️ **硬性红线（Docs/09）**：**只允许记录非敏感元数据**——包名、域名、字段语义与数量、
 * 匹配条数。**严禁**记录条目标题 / 账号 / 密码 / TOTP / URI 完整值。调用点必须遵守。
 */
internal object AutofillLogger {

    private const val TAG = "VaultixAutofill"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    /**
     * 指定 tag 的 debug 日志。
     *
     * 供 Credential Provider 等其它子系统复用同一策略（仅 debug 输出 + 仅非敏感元数据），
     * 避免每个子系统各写一份 BuildConfig.DEBUG 门禁。
     */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }
}
