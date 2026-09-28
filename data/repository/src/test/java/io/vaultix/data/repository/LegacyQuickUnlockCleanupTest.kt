/*
 * Vaultix — data:repository (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.datastore.VaultixPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧「每库信封」残留的**检测与清理**门禁（房子化批次 2）。
 *
 * ## 钉死的是什么
 *
 * 老用户升级后设备上有两类垃圾：旧信封（[SecureCredentialStore]，躺的是**库凭据密文**）
 * 与旧按库元数据键（[VaultixPreferences]）。它们不会再被任何代码读到，但**躺在那里**
 * 就是一份不该存在的凭据副本 —— 清不干净等于把攻击面留在原地。
 *
 * ⇒ 本测试钉死两件事：
 * 1. **认得出**：三个信封前缀 + 两个偏好键前缀，任一处有就算有残留
 *    （漏一个前缀 ⇒ 那类垃圾永远留着，且没人会发现）；
 * 2. **删得掉且不多删**：只删旧前缀下的键，新体系自己的键（`house_*`、
 *    `quick_unlock_scope`）一个都不许碰。
 *
 * ⚠️ 清理**时机**不在这里测（那由 `QuickUnlockController` 决定："新体系真站起来才清"）。
 * 这里只保证"让它清的时候，它清得干净且不误伤"。
 */
class LegacyQuickUnlockCleanupTest {

    private val credentials = mockk<SecureCredentialStore>(relaxed = true)
    private val preferences = mockk<VaultixPreferences>()

    private fun cleanup() = LegacyQuickUnlockCleanup(
        secureStore = credentials,
        preferences = preferences,
    )

    /** 让 [SecureCredentialStore.keysWithPrefix] 只对旧前缀返回键。 */
    private fun withEnvelopes(prefixToKeys: Map<String, List<String>> = emptyMap()) {
        every { credentials.keysWithPrefix(any()) } answers { call ->
            prefixToKeys[call.invocation.args.first() as String].orEmpty()
        }
    }

    private suspend fun withPreferenceKeys(keys: Set<String>) {
        coEvery { preferences.legacyQuickUnlockKeys() } returns keys
        coEvery { preferences.removeLegacyQuickUnlockKeys(any()) } returns keys.size
    }

    // ---- 检测 ----

    @Test
    fun `什么都没有时报告无残留`() = runTest {
        withEnvelopes()
        withPreferenceKeys(emptySet())

        assertFalse(cleanup().hasLegacyRemains())
    }

    @Test
    fun `有旧指纹信封时报告有残留`() = runTest {
        withEnvelopes(mapOf("local_unlock_key::" to listOf("local_unlock_key::v1")))
        withPreferenceKeys(emptySet())

        assertTrue(cleanup().hasLegacyRemains())
    }

    @Test
    fun `只有旧 PIN 计数残留也算有残留`() = runTest {
        // 计数不是凭据，但同样属于旧体系的垃圾，一并认出来。
        withEnvelopes(mapOf("local_pin_attempts::" to listOf("local_pin_attempts::v1")))
        withPreferenceKeys(emptySet())

        assertTrue(cleanup().hasLegacyRemains())
    }

    @Test
    fun `只有旧偏好键也算有残留`() = runTest {
        withEnvelopes()
        withPreferenceKeys(setOf("local_unlock_enabled_v1", "pin_unlock_enabled_v1"))

        assertTrue(cleanup().hasLegacyRemains())
    }

    @Test
    fun `新体系的键不会被误认成残留`() = runTest {
        // house_* 是新房子化的真身，认错就会把正在用的信封删掉 —— 灾难级误伤。
        withEnvelopes(mapOf("local_unlock_key::" to emptyList()))
        withPreferenceKeys(emptySet())

        assertFalse(cleanup().hasLegacyRemains())
    }

    // ---- 清理 ----

    @Test
    fun `清理会删掉全部三类旧信封`() = runTest {
        withEnvelopes(
            mapOf(
                "local_unlock_key::" to listOf("local_unlock_key::v1", "local_unlock_key::v2"),
                "local_pin_key::" to listOf("local_pin_key::v1"),
                "local_pin_attempts::" to listOf("local_pin_attempts::v1"),
            ),
        )
        withPreferenceKeys(setOf("local_unlock_enabled_v1"))

        val report = cleanup().clearLegacyRemains()

        assertEquals(4, report.envelopesRemoved)
        assertEquals(1, report.preferenceKeysRemoved)
        verify(exactly = 1) { credentials.remove("local_unlock_key::v1") }
        verify(exactly = 1) { credentials.remove("local_unlock_key::v2") }
        verify(exactly = 1) { credentials.remove("local_pin_key::v1") }
        verify(exactly = 1) { credentials.remove("local_pin_attempts::v1") }
        coVerify(exactly = 1) { preferences.removeLegacyQuickUnlockKeys(setOf("local_unlock_enabled_v1")) }
    }

    @Test
    fun `只删旧前缀下的键，不碰新体系的键`() = runTest {
        withEnvelopes()
        withPreferenceKeys(emptySet())

        cleanup().clearLegacyRemains()

        // 没有残留 ⇒ 一次 remove 都不该发生（"删了 0 个"和"删了新信封"必须能区分）。
        verify(exactly = 0) { credentials.remove(any()) }
        coVerify(exactly = 0) { preferences.removeLegacyQuickUnlockKeys(any()) }
    }

    @Test
    fun `清理之后残留真的消失了`() = runTest {
        // 用一个会**真的变空**的假存储：只断言"删了几次"会漏掉"其实没删掉"的情况
        // （报告说清了 4 个、存储里却还有 4 个 —— 那正是谎报状态）。
        val stored = mutableSetOf("local_unlock_key::v1", "local_pin_key::v1")
        every { credentials.keysWithPrefix(any()) } answers { call ->
            val prefix = call.invocation.args.first() as String
            stored.filter { it.startsWith(prefix) }
        }
        every { credentials.remove(any()) } answers { call ->
            stored.remove(call.invocation.args.first() as String)
            Unit
        }
        withPreferenceKeys(emptySet())

        val subject = cleanup()
        assertTrue(subject.hasLegacyRemains())

        val report = subject.clearLegacyRemains()

        assertEquals(2, report.envelopesRemoved)
        assertFalse(subject.hasLegacyRemains())
    }
}
