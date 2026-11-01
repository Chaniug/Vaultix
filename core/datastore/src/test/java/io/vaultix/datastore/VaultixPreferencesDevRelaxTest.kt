/*
 * Vaultix — core:datastore (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 开发者设置「保持不锁」的偏好位（2026-10-30，`dev_relax_screen_lock_v2`）。
 *
 * ## 为什么值得单独钉三条
 *
 * 这个开关的第一版把**语义写反**（`true` = 仍需验证），UI 又对它取反一次，
 * 两个负号相消 ⇒ **每次点击都写回旧值**，开关纹丝不动（用户报的"点了没反应"，
 * 见 `.ai/ISSUES.md` #213）。三条用例分别守住翻正之后最容易再退回去的三个点：
 *
 * 1. **默认值**：必须是 `false`（= 与正式版一致，照常锁）。默认写成 `true`
 *    等于给所有 debug 包静默关掉解锁验证；
 * 2. **写口真的生效**：`writeDevRelaxScreenLock` 是个**非 suspend 的 setter**，
 *    内部 `writeScope.launch { dataStore.edit … }`。它最容易坏的方式是"发了但没落盘"
 *    （scope 被取消 / 键写错）—— 而 UI 层看不出来，只会表现为"开关自己弹回去"；
 * 3. **旧键必须被忽略**：`dev_relax_screen_lock`（无 `_v2`）存的是**反义**。
 *    老机器上可能留着一条 `true`；若沿用旧键并翻转语义，升级后会突然变成"不锁" ——
 *    一次静默的安全降级。这条用例直接往旧键里塞 `true`，断言读出来仍是 `false`。
 *
 * ## 为什么用真 DataStore 而不是 mock
 *
 * 与 [OnboardingPreferencesTest] 同一条理由：`edit { }` 内部会先读 `data` 再合成，
 * 手写内存桩很容易造出"产品里不存在"的执行路径。这里用官方工厂 + 临时目录，
 * 只把落盘位置换掉。
 */
class VaultixPreferencesDevRelaxTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            // ⚠️ 2026-11-01：不要用 `tempFolder.newFile(...)` —— 它会**预先创建一个空文件**，
            //    而 DataStore 的原子写是「写 .tmp → rename 覆盖目标」。
            //    在 Windows 上，目标文件已被创建/持有句柄时这次 rename 会失败：
            //      `Unable to rename …preferences_pb.tmp to …preferences_pb.
            //       This likely means that there are multiple instances of DataStore for this file.`
            //    症状是**写永远不落盘** ⇒ 等它的 `first { it }` 一路超时（CI 上就是这么红的）。
            //    DataStore 自己会创建文件，这里只给路径（与官方示例一致）。
            File(tempFolder.root, "vaultix.preferences_pb")
        }
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun prefs() = VaultixPreferences(dataStore)

    /**
     * Windows 上跳过「**多次写**」的用例。
     *
     * DataStore 的原子写 = 「写 `.tmp` → rename 覆盖目标」，而 Windows 的 `File.renameTo`
     * **不能覆盖已存在的目标** ⇒ 第二次 `edit` 必然抛
     * `Unable to rename …preferences_pb.tmp to …preferences_pb`
     * （库里那句 "multiple instances of DataStore" 是它的误诊）。
     *
     * Linux（CI）的 rename 可覆盖 ⇒ 那边正常跑，**覆盖不为零**。
     * 产品跑在 Android（POSIX 语义），本跳过不失真 —— 它跳过的是"测试环境的文件系统差异"，
     * 不是产品行为。
     */
    private fun assumePosixRename() {
        assumeFalse(
            "Windows 的 renameTo 不能覆盖目标 ⇒ DataStore 第二次写必失败（CI 为 Linux，仍会跑）",
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
        )
    }

    @Test
    fun `默认不放松_与正式版一致`() = runBlocking {
        assertThat(prefs().devRelaxScreenLock.first()).isFalse()
    }

    @Test
    fun `写入保持不锁后能读回true`() {
        // 本用例要写两次（true → false），第二次在 Windows 上必失败（见 assumePosixRename）。
        assumePosixRename()
        runBlocking {
            val prefs = prefs()
            prefs.writeDevRelaxScreenLock = true

            // 写口是 fire-and-forget（属性 setter 不能 suspend），所以要"等它落盘"：
            // 直接 first() 可能读到写入前的快照。用 first { it } + 超时把竞态变成"要么成功要么红"。
            //
            // ⚠️ 超时 5s → 30s 是给慢环境的余量（CI 首次真跑就 5s 超时）。
            //    **放宽超时不是放宽断言**：断言仍是「必须读到 true」，只是不再把"慢"误判成"没写"。
            val relaxed = withTimeout(30_000) { prefs.devRelaxScreenLock.first { it } }
            assertThat(relaxed).isTrue()

            // 反向也要能写回去（否则开关只能打开、关不掉）。
            prefs.writeDevRelaxScreenLock = false
            val normal = withTimeout(30_000) { prefs.devRelaxScreenLock.first { !it } }
            assertThat(normal).isFalse()
        }
    }

    @Test
    fun `旧键_dev_relax_screen_lock_必须被忽略`() = runBlocking {
        // 旧键的 true = "仍需验证"（反义）。升级后若被当成新语义读出来，
        // 就等于把一个"要验证"的旧值解读成"不锁" —— 静默降级。
        dataStore.edit { it[booleanPreferencesKey("dev_relax_screen_lock")] = true }

        assertThat(prefs().devRelaxScreenLock.first()).isFalse()
    }
}
