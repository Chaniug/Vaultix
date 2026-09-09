package io.vaultix.vaultix.security

/**
 * 自动锁定判定策略（纯函数，可单测）。
 *
 * 语义参考 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `SessionManager.canSkipVerification` 与 `autoLockMinutes` 档位模型，
 * 本文件为独立实现：
 * - 档位（分钟）：`0` = 切后台立即锁；`>0` = 离开超过 N 分钟锁；
 *   `<0` = 从不自动锁（只手动锁）；
 * - 设备屏幕当前锁定时一律要求重新验证（与分钟档位无关，Bastion 同款规则）；
 * - Vaultix 的对称密钥只在内存，进程重启天然锁定 → Bastion 的
 *   「-2 = 重启后锁定」档位无需建模。
 */
object AutoLockPolicy {

    /** 从不自动锁定（仅手动锁定）。 */
    const val NEVER = -1

    /** 切后台立即锁定。 */
    const val IMMEDIATE = 0

    /** 默认空闲锁定分钟数（与 Bastion 默认一致）。 */
    const val DEFAULT_MINUTES = 5

    const val MS_PER_MINUTE = 60_000L

    /** 切后台时：是否直接锁定（0 = 立即）。 */
    fun lockImmediatelyOnBackground(minutes: Int): Boolean = minutes == IMMEDIATE

    /** 是否永不自动锁定（<0；0 是「立即」，不算）。 */
    fun neverAutoLock(minutes: Int): Boolean = minutes < 0

    /**
     * 回前台时：按「切后台时刻 + 空闲档位」判断是否已超时。
     * 未解锁 / 从不 / 没离开过 → false。
     */
    fun backgroundTimeoutElapsed(
        nowMs: Long,
        backgroundedAtMs: Long?,
        minutes: Int,
    ): Boolean {
        if (minutes <= 0 || backgroundedAtMs == null) return false
        return nowMs - backgroundedAtMs >= minutes * MS_PER_MINUTE
    }

    /**
     * 设备屏幕当前仍处于锁定（keyguard）→ 必须重新验证。
     * Bastion 语义：屏幕锁定时不允许免验证访问。
     */
    fun screenLockRequiresRelock(screenLocked: Boolean): Boolean = screenLocked

    /**
     * 回前台是否应锁定。
     *
     * **「从不自动锁定」档位优先级最高**：用户显式选了「永不锁定」时，屏幕锁定与
     * 空闲超时两条规则全部跳过（只保留手动锁定与进程重启后的内存密钥清零）——
     * 否则选了「永不」却仍会因息屏 / 锁屏被锁，与档位语义自相矛盾。
     */
    fun shouldLockOnResume(minutes: Int, screenLocked: Boolean, timedOut: Boolean): Boolean {
        if (neverAutoLock(minutes)) return false
        return screenLockRequiresRelock(screenLocked) || timedOut
    }
}
