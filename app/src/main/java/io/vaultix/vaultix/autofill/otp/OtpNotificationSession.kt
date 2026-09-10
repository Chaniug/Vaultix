/*
 * Vaultix — app:autofill · otp
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「倒计时显示值 = min(验证码剩余有效秒, 通知剩余展示秒)」的时间口径与
 * 纯状态机/Android 生命周期分离的设计，参考 Bastion 项目（GPL-3.0，
 * Copyright 2025 JoyinJoester）的 autofill_ng/service/AutofillOtpNotificationSession.kt；
 * 本文件按 Vaultix 的 TotpConfig / TotpGenerator 结构独立编写，同样以 GPL-3.0 发布。
 * 差异：Vaultix 支持五种 OTP 类型，**HOTP 为计数器驱动、无时间步长**，故不做周期倒计时
 * （否则会每秒递减一个永远不改变的码的有效期，属误导）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.autofill.otp

import io.vaultix.common.OtpType
import io.vaultix.common.TotpConfig
import io.vaultix.common.TotpGenerator

/**
 * 验证码通知的纯时间计算（不碰 Android API，便于 JVM 单测）。
 *
 * 服务层只负责生命周期与通知发布；这里负责「第 N 秒该显示什么码、还剩几秒」，
 * 避免缓存状态导致倒计时与实际码值漂移。
 */
internal class OtpNotificationSession(
    private val config: TotpConfig,
    startedElapsedMs: Long,
    durationSeconds: Int,
) {

    private val periodSeconds = config.period.coerceAtLeast(1)

    /** 通知自动收起时刻（elapsedRealtime 基准，不受系统改时间影响）。 */
    private val deadlineElapsedMs: Long =
        startedElapsedMs + durationSeconds.coerceAtLeast(1) * 1000L

    /**
     * @param nowElapsedMs [android.os.SystemClock.elapsedRealtime]
     * @param nowWallSeconds 墙钟秒（验证码按墙钟时间步长变化）
     */
    fun snapshot(nowElapsedMs: Long, nowWallSeconds: Long): Snapshot {
        val dismissRemaining = ((deadlineElapsedMs - nowElapsedMs) / 1000L).toInt().coerceAtLeast(0)
        val code = runCatching { TotpGenerator.generate(config, nowWallSeconds) }.getOrDefault("")
        // HOTP 无时间步长：倒计时只反映通知本身的剩余展示时间。
        val otpRemaining = if (config.type == OtpType.HOTP) {
            dismissRemaining
        } else {
            runCatching { TotpGenerator.remainingSeconds(periodSeconds, nowWallSeconds) }
                .getOrDefault(periodSeconds)
        }
        return Snapshot(
            code = code,
            remainingSeconds = minOf(otpRemaining, dismissRemaining),
            expired = dismissRemaining <= 0,
        )
    }

    data class Snapshot(
        val code: String,
        val remainingSeconds: Int,
        val expired: Boolean,
    )
}
