/*
 * Vaultix — app:autofill · engine
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * `RequestCodes` 的行为锁定（2026-10-26，`.ai/ISSUES.md` #187）。
 *
 * ## 背景一句话
 *
 * 这一条修的是「点第 2 行却填进第 1 条的密码」—— 起因是 `requestCode` 用了
 * `id.hashCode()`，而 PendingIntent 在 `FLAG_UPDATE_CURRENT` 下会把「同 requestCode +
 * filterEquals」的 pending **复用并改写 extras**。撞号了就串。
 *
 * ## 这里要钉的两个不变量
 *
 * 1. **不重号**：大批量发号里不能出现重复（这是唯一真正的要求）；
 * 2. **让开手写区间**：起点必须是 8000，把 1..7999 留给自动填充 / 凭据提供商里
 *    那些**手写**的 requestCode（`REQUEST_UNLOCK` / `REQUEST_UNLOCK_CP` …）。
 *    手写号与自增号相交同样会串，而"相交"不会有人主动去查，只能靠测试钉住。
 */
package io.vaultix.vaultix.autofill.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCodesTest {

    @Test
    fun `大批量发号不重复`() {
        val issued = HashSet<Int>()
        repeat(BATCH) { issued.add(RequestCodes.next()) }

        assertEquals("$BATCH 次发号里出现了重复 —— 撞号会让两个 PendingIntent 互相改写", BATCH, issued.size)
    }

    @Test
    fun `起点让开手写区间`() {
        // 手写常量都在 1..7999（1001 / 1002 / 2101 …），自增号必须从 8000 起。
        val first = RequestCodes.next()

        assertTrue("自增号的首号是 $first，必须 ≥ $HAND_WRITTEN_CEILING", first >= HAND_WRITTEN_CEILING)
        assertTrue("自增号不能是负数或 0", first > 0)
    }

    private companion object {
        /** 发号批量：远大于任何真实 FillResponse 的 dataset 数（10），足够暴露重号。 */
        const val BATCH = 20_000

        /** 手写 requestCode 的**上限**（含），即自增号的下限：`VaultixAutofillService` 1001/1002、CP 2101… */
        const val HAND_WRITTEN_CEILING = 8000
    }
}
