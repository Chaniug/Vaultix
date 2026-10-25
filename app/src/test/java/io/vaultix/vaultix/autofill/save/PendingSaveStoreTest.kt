/*
 * Vaultix — app:autofill · save
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * `PendingSaveStore` 的行为锁定（2026-10-26，`.ai/ISSUES.md` #187）。
 *
 * ## 为什么这个类必须有测试
 *
 * 它守的是一件**看不见的事**：明文密码不再随 Intent 走进系统（Intent extras 与
 * `SavedStateHandle` 都会被系统截图/快照链路落盘）。而它的三个性质都是
 * "写错了不会崩、也不会报错，只会静默地把密码多留一份或者留不下来"：
 *
 * 1. **一次性** —— 取走即清空。若写成"取走但不清"，同一份明文会被界面反复读到，
 *    生命周期也就随之变长；
 * 2. **票据必须匹配** —— 不匹配就当没有。若写成"不怎么校验"，旧票据可能拿到新一次
 *    保存的密码（把 A 站密码存进 B 站）；
 * 3. **过期即作废** —— TTL 让明文不会无限驻留。
 *
 * ## 为什么"过期"这条不好测
 *
 * TTL 用的是 `SystemClock.elapsedRealtime()`，单测里没有虚拟时钟（`SystemClock` 的
 * 单测返回值由 `unitTests.isReturnDefaultValues = true` 给成 0，不会前进）。
 * 所以这里用**反射改写暂存里的时间戳**来模拟"已经过去 60 秒"，
 * 而不是去等 —— 等真实时间是 flaky 的来源，本文件不引入。
 */
package io.vaultix.vaultix.autofill.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSaveStoreTest {

    private fun store() = PendingSaveStore()

    @Test
    fun `暂存后按票据取到同一份凭据`() {
        val store = store()
        val token = store.stage(
            username = "alice@example.com",
            password = "s3cret",
            packageName = "com.example.app",
            webDomain = "example.com",
        )

        val taken = store.consume(token)

        assertNotNull("同一次暂存必须能取到", taken)
        assertEquals("alice@example.com", taken!!.username)
        assertEquals("s3cret", taken.password)
        assertEquals("com.example.app", taken.packageName)
        assertEquals("example.com", taken.webDomain)
    }

    @Test
    fun `票据只能取走一次`() {
        val store = store()
        val token = store.stage("u", "p", null, null)

        assertNotNull(store.consume(token))
        assertNull("第二次取必须拿不到 —— 明文已被清空", store.consume(token))
    }

    @Test
    fun `票据不匹配时拿不到任何东西`() {
        val store = store()
        store.stage("u", "p", null, null)

        assertNull(store.consume("not-the-token"))
        assertNull(store.consume(null))
    }

    @Test
    fun `票据不匹配不会消耗掉暂存`() {
        val store = store()
        val token = store.stage("u", "p", null, null)

        // 拿错票据只是取不到，不该把这份暂存也一起弄没（否则"重复打开界面"会误清）。
        assertNull(store.consume("wrong"))
        assertNotNull(store.consume(token))
    }

    @Test
    fun `新的保存请求覆盖旧的`() {
        val store = store()
        val first = store.stage("a", "1", null, null)
        val second = store.stage("b", "2", null, null)

        assertNull("旧票据必须失效", store.consume(first))
        assertEquals("b", store.consume(second)?.username)
    }

    @Test
    fun `清空之后任何票据都无效`() {
        val store = store()
        val token = store.stage("u", "p", null, null)

        store.clear()

        assertNull(store.consume(token))
    }

    @Test
    fun `超过 TTL 的暂存视为失效`() {
        val store = store()
        val token = store.stage("u", "p", null, null)
        ageOut(store)

        assertNull("过期后必须取不到 —— 不能重放一份旧密码", store.consume(token))
    }

    /** 把当前暂存的入队时刻往前推 61 秒（TTL 是 60 秒）。 */
    private fun ageOut(store: PendingSaveStore) {
        val slotField = PendingSaveStore::class.java.getDeclaredField("slot").apply {
            isAccessible = true
        }
        val slot = slotField.get(store) ?: error("暂存应为非空")
        val saveField = slot.javaClass.getDeclaredField("save").apply { isAccessible = true }
        val save = saveField.get(slot) as PendingSave
        saveField.set(
            slot,
            save.copy(stagedAtElapsedMs = save.stagedAtElapsedMs - OVER_TTL_MS),
        )
    }

    private companion object {
        /** 比 TTL（60 秒）多出的量，保证判据落在"已过期"分支。 */
        const val OVER_TTL_MS = 61_000L
    }
}
