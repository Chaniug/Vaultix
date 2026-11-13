/*
 * Vaultix — app:test · settings（行为 / 反证）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * #290 行为反证：把「下发到 UI 的 notice 不是 0」这条契约钉成可执行断言。
 *
 * - `safeStringRes(0)` 必须返回 null —— 撤掉守卫立刻红；
 * - 注销 / 自检成功后 `state.notice` 必须是**有效**资源 id —— 把常量改回
 *   `const val` 后，编译期内联的 0 会在这里红（前提见下面 R 值探针）；
 * - 覆盖 #290 主路径（点注销就闪退）与 #289 主路径（连上就闪退），
 *   两条路径写的是不同常量，都要验，别只修一条。
 *
 * ⚠️ 探针 `单测classpath里的R是真值...` 是下面断言的前提：AGP 必须给 unit test
 *    `compile_and_runtime_r_class_jar`（真值 0x7f…）。若给的是编译期 R（0），
 *    `const val` 与 `val` 在 JVM 里都读成 0，行为层区分不出来 —— 那时反证由
 *    `CloudAccountsResourceIdRulesTest` 的源码门禁 + 字节码 `javap` 承担。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import android.content.Context
import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.vaultix.data.repository.kdbx.WebDavKdbxFileSource
import io.vaultix.vaultix.R
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountConnector
import io.vaultix.vaultix.remote.CloudAccountInventory
import io.vaultix.vaultix.remote.CloudAccountKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudAccountsResourceIdBehaviorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `单测classpath里的R是真值而不是编译期0占位`() {
        // 与 `UnitTestEnvironmentTest` 同一类"环境守卫"：AGP 换策略时会立刻红，
        // 而不是让下面的行为断言莫名全绿/全红。
        assertThat(R.string.cloud_msg_removed_webdav).isNotEqualTo(0)
        assertThat(R.string.cloud_msg_removed_onedrive).isNotEqualTo(0)
        assertThat(R.string.cloud_msg_probe_ok).isNotEqualTo(0)
    }

    @Test
    fun `safeStringRes拒绝0与ID_NULL并保留有效id`() {
        assertThat(safeStringRes(0)).isNull()
        assertThat(safeStringRes(Resources.ID_NULL)).isNull()
        assertThat(safeStringRes(null)).isNull()
        val valid = R.string.cloud_msg_removed_webdav
        assertThat(safeStringRes(valid)).isEqualTo(valid)
    }

    @Test
    fun `注销WebDAV后notice是有效资源id而不是0`() = runTest {
        val vm = viewModel()
        vm.requestRemoval(
            CloudAccount(
                kind = CloudAccountKind.WEBDAV,
                storedId = CREDENTIAL_ID,
                label = "dav.jianguoyun.com",
                vaultIds = emptyList(),
                connected = true,
                browseRoot = SERVER_URL,
            ),
        )

        vm.confirmRemoval()
        awaitUntil { vm.state.value.notice != null }

        val notice = vm.state.value.notice
        assertThat(notice).isNotNull()
        assertThat(safeStringRes(notice)).isNotNull()
        assertThat(notice).isEqualTo(R.string.cloud_msg_removed_webdav)
    }

    @Test
    fun `注销OneDrive后notice同样是有效资源id`() = runTest {
        val vm = viewModel()
        vm.requestRemoval(
            CloudAccount(
                kind = CloudAccountKind.ONEDRIVE,
                storedId = "msal-account",
                label = "user@outlook.com",
                vaultIds = emptyList(),
                connected = true,
                browseRoot = null,
            ),
        )

        vm.confirmRemoval()
        awaitUntil { vm.state.value.notice != null }

        val notice = vm.state.value.notice
        assertThat(notice).isNotNull()
        assertThat(safeStringRes(notice)).isNotNull()
        assertThat(notice).isEqualTo(R.string.cloud_msg_removed_onedrive)
    }

    @Test
    fun `WebDAV自检成功后notice是有效id_覆盖289连上就闪退主路径`() = runTest {
        val connector = mockk<CloudAccountConnector>(relaxed = true)
        every { connector.normalizeServerUrl(any()) } returns SERVER_URL
        every { connector.saveWebDavCredential(any(), any(), any()) } returns CREDENTIAL_ID
        val source = mockk<WebDavKdbxFileSource>(relaxed = true)
        coEvery { source.testConnection() } returns Result.success(Unit)
        every { connector.webDavSource(any(), any()) } returns source
        val vm = viewModel(connector)
        vm.onServerUrlChange(SERVER_URL)
        vm.onUsernameChange("user@example.com")
        vm.onPasswordChange("app-password")

        vm.probeWebDav()
        awaitUntil { vm.state.value.probe == ProbeState.OK }

        val notice = vm.state.value.notice
        assertThat(notice).isNotNull()
        assertThat(safeStringRes(notice)).isNotNull()
        assertThat(notice).isEqualTo(R.string.cloud_msg_probe_ok)
    }

    // ---------------------------------------------------------------- 内部

    private fun viewModel(
        connector: CloudAccountConnector = mockk(relaxed = true),
    ): CloudAccountsViewModel {
        val inventory = mockk<CloudAccountInventory>(relaxed = true)
        coEvery { inventory.list() } returns emptyList()
        return CloudAccountsViewModel(inventory, connector, mockk<Context>(relaxed = true))
    }

    /** 真实线程轮询：等 `Dispatchers.IO` 上的动作收尾（同 `CloudAccountsCrashGuardTest`）。 */
    private suspend fun TestScope.awaitUntil(
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) {
                while (!condition()) delay(20)
            }
        }
    }

    private companion object {
        const val SERVER_URL = "https://dav.jianguoyun.com/dav/"
        const val CREDENTIAL_ID = "cred123"
    }
}
