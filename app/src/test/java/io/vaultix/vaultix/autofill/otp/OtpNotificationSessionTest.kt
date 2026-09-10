/*
 * Vaultix — app:autofill · otp
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.otp

import com.google.common.truth.Truth.assertThat
import io.vaultix.common.OtpType
import io.vaultix.common.TotpConfig
import org.junit.Test

/**
 * 通知栏验证码倒计时口径（对齐 Bastion AutofillOtpNotificationSession）：
 * 显示值 = min(验证码剩余有效秒, 通知剩余展示秒)；HOTP 无时间步长，不参与周期倒计时。
 */
class OtpNotificationSessionTest {

    private fun totpConfig(period: Int = THIRTY_SECONDS): TotpConfig =
        TotpConfig(secret = BASE32_SECRET, period = period)

    private fun hotpConfig(): TotpConfig =
        TotpConfig(secret = BASE32_SECRET, type = OtpType.HOTP)

    @Test
    fun `倒计时取验证码剩余与通知剩余的较小值`() {
        // startedElapsed=0，展示 30s；墙钟 20s → 周期 30s 的码还剩 10s
        val session = OtpNotificationSession(
            config = totpConfig(),
            startedElapsedMs = 0L,
            durationSeconds = THIRTY_SECONDS,
        )

        val snapshot = session.snapshot(nowElapsedMs = 0L, nowWallSeconds = WALL_TWENTY)

        assertThat(snapshot.remainingSeconds).isEqualTo(TEN_SECONDS)
        assertThat(snapshot.expired).isFalse()
        assertThat(snapshot.code).hasLength(SIX_DIGITS)
    }

    @Test
    fun `通知剩余小于验证码剩余时以通知为准`() {
        // 展示期只剩 5s，而验证码还剩 10s → 取 5s
        val session = OtpNotificationSession(
            config = totpConfig(),
            startedElapsedMs = 0L,
            durationSeconds = THIRTY_SECONDS,
        )

        val snapshot = session.snapshot(
            nowElapsedMs = TWENTY_FIVE_SECONDS_MS,
            nowWallSeconds = WALL_TWENTY,
        )

        assertThat(snapshot.remainingSeconds).isEqualTo(FIVE_SECONDS)
        assertThat(snapshot.expired).isFalse()
    }

    @Test
    fun `到达展示时长即过期且剩余归零`() {
        val session = OtpNotificationSession(
            config = totpConfig(),
            startedElapsedMs = 0L,
            durationSeconds = THIRTY_SECONDS,
        )

        val snapshot = session.snapshot(
            nowElapsedMs = THIRTY_SECONDS_MS,
            nowWallSeconds = WALL_TWENTY,
        )

        assertThat(snapshot.expired).isTrue()
        assertThat(snapshot.remainingSeconds).isEqualTo(0)
    }

    @Test
    fun `HOTP 不做周期倒计时只看通知剩余`() {
        val session = OtpNotificationSession(
            config = hotpConfig(),
            startedElapsedMs = 0L,
            durationSeconds = SIXTY_SECONDS,
        )

        // 墙钟落在周期边界上（周期倒计时只有 1s），HOTP 不应因此变成 1
        val snapshot = session.snapshot(
            nowElapsedMs = 0L,
            nowWallSeconds = WALL_TWENTY_NINE,
        )

        assertThat(snapshot.remainingSeconds).isEqualTo(SIXTY_SECONDS)
        assertThat(snapshot.expired).isFalse()
        assertThat(snapshot.code).hasLength(SIX_DIGITS)
    }

    private companion object {
        /** RFC 4648 测试向量用的合法 base32 密钥。 */
        const val BASE32_SECRET = "JBSWY3DPEHPK3PXP"

        const val THIRTY_SECONDS = 30
        const val SIXTY_SECONDS = 60
        const val TEN_SECONDS = 10
        const val FIVE_SECONDS = 5
        const val SIX_DIGITS = 6

        /** 墙钟秒（t % 30 = 20 → 周期剩余 10s）。 */
        const val WALL_TWENTY = 20L
        const val WALL_TWENTY_NINE = 29L

        const val TWENTY_FIVE_SECONDS_MS = 25_000L
        const val THIRTY_SECONDS_MS = 30_000L
    }
}
