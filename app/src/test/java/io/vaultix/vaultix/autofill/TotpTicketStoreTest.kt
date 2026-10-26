/*
 * Vaultix — app
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Field

/**
 * [TotpTicketStore] 的行为锁定（#195）。
 *
 * ⚠️ `SystemClock.elapsedRealtime()` 在纯 JVM 单测里返回 0 且**不会前进**，
 * 所以过期用例不能"真等"，而是用反射把那一条的 `stagedAtElapsedMs` 往前推 61 秒
 * （与 `PendingSaveStoreTest` 同一手法）。
 */
class TotpTicketStoreTest {

    private val store = TotpTicketStore()

    /** 把某张票据的暂存时间往前推 [ms] 毫秒（模拟"已经过了这么久"）。 */
    private fun ageTicket(ticket: String, ms: Long) {
        val slotsField: Field = TotpTicketStore::class.java.getDeclaredField("slots")
        slotsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val slots = slotsField.get(store) as LinkedHashMap<String, Any>
        val slot = slots[ticket] ?: error("ticket not found: $ticket")
        val tsField = slot.javaClass.getDeclaredField("stagedAtElapsedMs")
        tsField.isAccessible = true
        tsField.setLong(slot, tsField.getLong(slot) - ms)
    }

    @Test
    fun `stage_返回可用的票据`() {
        val ticket = store.stage("JBSWY3DPEHPK3PXP")
        assertEquals("JBSWY3DPEHPK3PXP", store.consume(ticket))
    }

    @Test
    fun `同一票据可重复读取_因为Activity可能被重投递`() {
        // ★ 这是与 `PendingSaveStore`（一次性）**有意不同**的地方：
        //   `AutofillActivity` 在 MODE_COPY_TOTP 下可能因 launchMode / 系统重投递
        //   被投递两次（onNewIntent）。若做成一次性，第二次会拿到 null，
        //   在界面上等同于"点了没反应" —— 用户看不到任何提示，只会觉得功能坏了。
        val ticket = store.stage("SECRET")
        assertEquals("SECRET", store.consume(ticket))
        assertEquals("SECRET", store.consume(ticket))
        assertEquals("SECRET", store.consume(ticket))
    }

    @Test
    fun `票据不匹配返回null且不影响原票据`() {
        val ticket = store.stage("SECRET")
        assertNull(store.consume("not-a-ticket"))
        assertNull(store.consume(null))
        assertNull(store.consume(""))
        // 关键：错误票据不能把正确的那条吃掉。
        assertEquals("SECRET", store.consume(ticket))
    }

    @Test
    fun `过期后返回null`() {
        val ticket = store.stage("SECRET")
        ageTicket(ticket, 61_000L)
        assertNull(store.consume(ticket))
    }

    @Test
    fun `过期前一刻仍可读`() {
        val ticket = store.stage("SECRET")
        ageTicket(ticket, 59_000L)
        assertEquals("SECRET", store.consume(ticket))
    }

    @Test
    fun `每次stage得到不同票据`() {
        val a = store.stage("A")
        val b = store.stage("B")
        assertNotEquals(a, b)
        assertEquals("A", store.consume(a))
        assertEquals("B", store.consume(b))
    }

    @Test
    fun `clear后全部失效`() {
        val ticket = store.stage("SECRET")
        store.clear()
        assertNull(store.consume(ticket))
    }

    @Test
    fun `stage数量上限_不会无界增长且保留最新`() {
        // 防异常高频 stage（stage 在 onFillRequest 里被调用）把内存撑大。
        repeat(100) { store.stage("S$it") }
        val latest = store.stage("NEWEST")
        assertEquals("NEWEST", store.consume(latest))

        val slotsField = TotpTicketStore::class.java.getDeclaredField("slots")
        slotsField.isAccessible = true
        val slots = slotsField.get(store) as Map<*, *>
        // 上限 32（见 TotpTicketStore.MAX_SLOTS）
        assertEquals(32, slots.size)
    }

    @Test
    fun `stage时顺带清理过期票据`() {
        val old = store.stage("OLD")
        ageTicket(old, 61_000L)
        store.stage("NEW")

        val slotsField = TotpTicketStore::class.java.getDeclaredField("slots")
        slotsField.isAccessible = true
        val slots = slotsField.get(store) as Map<*, *>
        // 过期的那条在下次 stage 时被清掉 ⇒ 只剩新的那条。
        assertEquals(1, slots.size)
        assertNull(store.consume(old))
    }
}
