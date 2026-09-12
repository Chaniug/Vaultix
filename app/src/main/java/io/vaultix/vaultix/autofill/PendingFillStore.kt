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
import io.vaultix.vaultix.autofill.model.ParsedStructure
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** 一次「待解锁后回填」的暂存：解析结果 + 入暂存时刻。 */
data class PendingFill(
    val parsed: ParsedStructure,
    val stagedAtElapsedMs: Long,
)

/**
 * 「解锁即回填」的**内存暂存**（`.ai/ISSUES.md` #60 第 4 步）。
 *
 * ## 为什么必须暂存
 * 系统只在库**锁定时**给我们一次 `onFillRequest`：那一次我们只能回一条
 * 「整表认证」入口（`FillResponse` 的 authentication），因为候选要解密才拿得到。
 * 用户解锁之后，浏览器**不会**自动重发请求 —— 不暂存这次请求的目标字段，
 * 用户就只能回到浏览器再点一次（用户原话：「填充时解锁完还要再验证一次，逻辑太稀烂」）。
 *
 * ## 存什么 / 不存什么
 * - 存：**解析结果**（含各字段的 `AutofillId`）与时间戳；
 * - **不存**任何明文口令 —— 暂存期间该字段还不存在，条目是在解锁之后现读现映射的。
 *   这既是安全取舍，也让「解锁后拿到的是最新条目」自然成立。
 *
 * ## 生命周期
 * - [TTL_MS] 是硬上限（对齐「不早不晚」的取舍）：网址导航走 / 会话过期后
 *   `AutofillId` 会失效，回填必然失败并可能抛 `SecurityException`；
 * - 单条暂存（新请求覆盖旧的）：同一时刻浏览器只有一个填充会话；
 * - [clear] 在回灌后立刻调用 —— 同一个 `AutofillId` 集合只回灌一次，
 *   否则用户取消解锁后再进来会被上一次的陈旧字段顶掉。
 *
 * 用 `SystemClock.elapsedRealtime` 而不是 `currentTimeMillis`：TTL 是**时长**语义，
 * 不能被用户改系统时间影响（`.ai/ISSUES.md` #36 的溢出教训同源：时间基准选错很难查）。
 */
@Singleton
class PendingFillStore @Inject constructor() {

    private val slot = AtomicReference<PendingFill?>(null)

    /** 暂存一次填充请求（覆盖上一次）。 */
    fun stage(parsed: ParsedStructure) {
        slot.set(PendingFill(parsed = parsed, stagedAtElapsedMs = SystemClock.elapsedRealtime()))
    }

    /** 取出未过期的暂存；已过期则顺手清掉并返回 null。 */
    fun takeValid(): PendingFill? {
        val pending = slot.get() ?: return null
        val age = SystemClock.elapsedRealtime() - pending.stagedAtElapsedMs
        if (age > TTL_MS) {
            slot.compareAndSet(pending, null)
            return null
        }
        return pending
    }

    /** 当前是否有未过期的暂存（UI 据此决定「解锁后自动填充」还是普通解锁）。 */
    fun hasPending(): Boolean = takeValid() != null

    /** 回灌完成后清空（幂等）。 */
    fun clear() {
        slot.set(null)
    }

    private companion object {
        /**
         * 暂存有效期。取 2 分钟的依据：① 用户从「点 Vaultix 行」到「解锁完成」的正常耗时
         * 在 30 秒内（指纹更快）；② 浏览器 Autofill 会话与 `AutofillId` 的有效期由系统
         * 会话决定，越长越可能撞上失效；③ 太短则用户在解锁页被打断（接电话）后回填就没了 ——
         * 那时宁可不填，也不能填到错的地方。
         */
        const val TTL_MS = 120_000L
    }
}
