/*
 * Vaultix — app:autofill · engine
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Dataset 认证意图的 **requestCode 分配器**（`.ai/ISSUES.md` #187，2026-10-26）。
 *
 * ## 修的是什么
 *
 * 原写法是 `requestCode = suggestion.id.hashCode()`。而待认证的 PendingIntent 是这样创建的：
 *
 * ```
 * PendingIntent.getActivity(ctx, requestCode, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
 * ```
 *
 * `FLAG_UPDATE_CURRENT` 的语义是：**requestCode 与 Intent「相同」就复用并改写 extras**。
 * 这里的 Intent 是显式 Intent（同一个组件）且 action 固定，`filterEquals` 只比较
 * action / data / type / component / categories —— 也就是说**只要 requestCode 撞了，
 * 系统就认为是同一个 PendingIntent**。
 *
 * `String.hashCode()` 的碰撞在 32 位空间里按生日界算，几百个条目就有可观概率；
 * 而一个库动辄几百条，下拉面板一次给 10 条 —— 撞上时的后果不是"报错"，而是
 * **后一条的 pending 覆盖前一条的 extras**，用户点第二行，填进去的是第一个条目的密码。
 * 这类错误极难复现、极难归因，但后果是"把 A 站密码填进 B 站"。
 *
 * ## 怎么修
 *
 * requestCode 只要求「同一次进程内不重复」。用单调自增即可精确保证这一点，
 * 不需要任何哈希。范围取 `1 .. 0x00FFFFFF`（约 1677 万），进位回绕：
 * 回绕一次要经过 1677 万次分配，实际进程生命周期内不可能发生；
 * 即便发生，也只影响"那一瞬间同时存活的两个 pending"。
 *
 * ## 为什么不直接把 datasetId 塞进 Intent 就完事
 *
 * 已经塞了（`EXTRA_DATASET_ID`），真正的修复就是**别让两个不同的 pending 撞号**。
 * 现在 pending 上挂的 extras 与各自 dataset 一一对应，不会再互相改写。
 */
internal object RequestCodes {

    /** 上限（不含）。取 2^24 的余量：PendingIntent 的 requestCode 是 Int，不必用负数。 */
    private const val LIMIT = 0x00FF_FFFF

    /**
     * 起始号。
     *
     * ⚠️ 起点定在 8000 而不是 1，是为了**让开固定的手写 requestCode 区间**：
     * `CredentialProviderEntryBuilder` 的 `REQUEST_UNLOCK_CP = 2101` 等、以及 autofill 侧
     * `VaultixAutofillService` 的 `REQUEST_UNLOCK/SEARCH = 1001/1002` 都在 1..7999 之内。
     * 手写常量与自增号一旦相交，两个 pending 会互相改写 extras（正是本条要修的病），
     * 而"相交"这件事没人会主动去查 —— 所以用区间把可能性直接掐掉。
     * 新增手写 requestCode 请留在 7999 以内。
     */
    private const val FIRST = 8000

    private val next = AtomicInteger(FIRST)

    /** 取下一个可用 requestCode（8000 起，到 LIMIT 回绕）。 */
    fun next(): Int {
        while (true) {
            val current = next.get()
            val candidate = if (current >= LIMIT) FIRST else current
            if (next.compareAndSet(current, candidate + 1)) return candidate
        }
    }
}
