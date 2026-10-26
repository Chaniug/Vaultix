/*
 * Vaultix — app:autofill
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill

import android.os.SystemClock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动填充「复制验证码」用的 **TOTP 密钥一次性暂存**（`.ai/ISSUES.md` #195，2026-10-26）。
 *
 * ## 它替换掉了什么
 *
 * 原实现把条目的 **TOTP 密钥原文**（`otpauth://…?secret=…` 或裸 base32）放进
 * `PendingIntent` 的 extras，由 `AutofillActivity` 读出来现算验证码。问题在于：
 *
 * 1. `PendingIntent` 会被系统保存（`ActivityManager` 的 pending intent 记录里带着
 *    被包装的 `Intent`），并且**这类对象可能被系统以 `dumpsys` 等方式导出**；
 * 2. TOTP 密钥是**长期共享秘密**（不像验证码 30 秒就换），泄露它等于永久拿到二次验证能力；
 * 3. 它躺在 extras 里，恰恰是本项目 #187 已经判定"绝不能放明文"的那类容器。
 *
 * 所以改成：`AutofillDatasetFactory` 生成 Dataset 时**先 stage 一份**，Intent 里只带
 * 一张一次性票据；`AutofillActivity` 现算码之前 `consume` 它。
 *
 * ## 为什么与 `PendingSaveStore` 分开
 *
 * 两者的 TTL、触发点、**消费语义**都不同：
 *
 * | | `PendingSaveStore`（#187） | 这里（#195） |
 * |---|---|---|
 * | 装的东西 | 明文用户名/密码 | TOTP 密钥（长期共享秘密） |
 * | 谁消费 | `AutofillSaveViewModel`（一个普通 Activity） | `AutofillActivity`（可能被系统直接拉起） |
 * | 消费语义 | **一次性**（取走即清） | **可重读**（见下） |
 *
 * ⚠️ **这里不能做成"一次性"**：`AutofillActivity` 在 `MODE_COPY_TOTP` 下可能因为
 * `launchMode` / 系统重投递而被**投递两次**（`onNewIntent`），
 * 而"第二次拿到 null"在界面上等同于"点了没反应"—— 用户看不到任何提示，
 * 只会觉得"这个功能坏了"。所以这里的语义是**短期可重读**：60 秒窗口内同一张票据可以
 * 取多次，过期即清。安全性由 TTL + 随机票据保证，不需要靠"只能取一次"。
 */
@Singleton
class TotpTicketStore @Inject constructor() {

    private class Slot(val ticket: String, val secret: String, val stagedAtElapsedMs: Long)

    /** 票据 → 密钥。用 `Map` 是因为可能有几条建议同时挂着（用户在面板里来回看）。 */
    private val slots = LinkedHashMap<String, Slot>()

    /**
     * 暂存一个 TOTP 密钥，返回**一次性票据**（放进 Intent 的只有它）。
     *
     * 同一密钥重复 stage 会生成新票据（旧的留着直到过期，无害）。
     */
    fun stage(secret: String): String {
        val ticket = UUID.randomUUID().toString()
        pruneExpired()
        slots[ticket] = Slot(ticket, secret, SystemClock.elapsedRealtime())
        // 上限保护：一次填充请求最多几条建议，理论上不会堆；
        // 但 `stage` 在 `onFillRequest` 里被调用，恶意/异常情况下可能高频触发。
        while (slots.size > MAX_SLOTS) {
            val oldest = slots.keys.firstOrNull() ?: break
            slots.remove(oldest)
        }
        return ticket
    }

    /** 按票据取密钥（60 秒窗口内可重读；过期 / 不匹配返回 null）。 */
    fun consume(ticket: String?): String? {
        if (ticket == null) return null
        val slot = slots[ticket] ?: return null
        if (SystemClock.elapsedRealtime() - slot.stagedAtElapsedMs > TTL_MS) {
            slots.remove(ticket)
            return null
        }
        return slot.secret
    }

    /** 清空全部（锁库时调用）。 */
    fun clear() {
        slots.clear()
    }

    private fun pruneExpired() {
        val now = SystemClock.elapsedRealtime()
        slots.entries.removeAll { now - it.value.stagedAtElapsedMs > TTL_MS }
    }

    private companion object {
        /**
         * 有效期 60 秒。
         *
         * 依据同 `PendingSaveStore`：从「系统拉起填充面板」到「用户点复制验证码」
         * 通常是秒级。60 秒覆盖"用户切出去看了一眼网址再回来"，
         * 又不至于让一个长期共享秘密在内存里过夜。
         */
        const val TTL_MS = 60_000L

        /** 最多同时挂几张票据（防异常高频 stage 把内存撑爆）。 */
        const val MAX_SLOTS = 32
    }
}
