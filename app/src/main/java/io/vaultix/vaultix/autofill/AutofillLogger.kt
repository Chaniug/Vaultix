/*
 * Vaultix — app:autofill
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill

import io.vaultix.common.logging.VaultixLog

/**
 * 自动填充诊断日志（**仅 debug 构建**输出，release 完全静默）。
 *
 * 存在的理由：浏览器填充「静默失效」（Edge 看不到条目、点了没反应）只能靠现场数据定位，
 * 而项目此前零日志，排障只能靠猜。
 *
 * ⚠️ **硬性红线（Docs/09）**：**只允许记录非敏感元数据**——包名、域名、字段语义与数量、
 * 匹配条数。**严禁**记录条目标题 / 账号 / 密码 / TOTP / URI 完整值。调用点必须遵守。
 *
 * ## 为什么走 [VaultixLog] 而不是自己判 `BuildConfig.DEBUG`
 *
 * 2026-11-14（#291）之前这里是 `if (BuildConfig.DEBUG) Log.d(...)`。它**不泄露**（release
 * 下不输出），但让工程存在第二个日志开关：装配套在 `VaultixApplication`、限频与异常兜底
 * 在 `VaultixLog`，这里绕过两者。收敛后：
 * - 全仓生产代码只剩 `VaultixApplication.kt` 门面 sink 一处直接用 `android.util.Log`
 *   （`check_production_safety.py` 据此只留单行白名单，门禁不会被例外稀释成摆设）；
 * - 同样享受每 tag 50 条 / 60s 限频与「sink 抛异常不拖垮调用方」；
 * - 63 个调用点一行未改 —— 签名仍是 `(message: String)` / `(tag, message)`。
 *
 * ⚠️ 调用点已经把 `message` 拼成 String 了，故这里拿不到「关闭时零拼接」的收益；
 * 这是保持 63 处不动的刻意取舍，不是遗漏。新增日志请直接用 `VaultixLog.d { }`。
 */
internal object AutofillLogger {

    private const val TAG = "VaultixAutofill"

    fun d(message: String) {
        VaultixLog.d(TAG) { message }
    }

    /**
     * 指定 tag 的 debug 日志。
     *
     * 供 Credential Provider 等其它子系统复用同一策略（仅 debug 输出 + 仅非敏感元数据），
     * 避免每个子系统各写一份 BuildConfig.DEBUG 门禁。
     */
    fun d(tag: String, message: String) {
        VaultixLog.d(tag) { message }
    }
}
