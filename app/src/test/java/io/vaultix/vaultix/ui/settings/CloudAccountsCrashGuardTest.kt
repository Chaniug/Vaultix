/*
 * Vaultix — app:ui:settings（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * #289 真机：「连上 WebDAV 后闪退，重进账号已添加」。
 *
 * 凭据已落盘 ⇒ 崩点在成功之后的状态更新 / 清单刷新 / 重组。工程里没有全局
 * `CoroutineExceptionHandler`（`.ai/issues/05-KDBX本地库-part2.md` 的 #98），
 * 裸 `viewModelScope.launch` 里任何未捕获异常都会冒泡成**进程退出**。
 *
 * ⇒ 这组用例不猜具体异常，而是钉死**异常边界本身**：来源层抛什么，
 *   都不得冒出 ViewModel，且必须把 UI 拉回可操作终态。
 *
 * ## 为什么用 awaitUntil 真实轮询
 *
 * `launchGuarded` 里的重 IO 走 `withContext(Dispatchers.IO)`（真实线程），
 * 虚拟时间等不到它 —— 与 `ConversionViewModelTest` 同一套手法：
 * 切到 `Dispatchers.Default` 用真实 timeout / delay 轮询。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.vaultix.data.repository.kdbx.WebDavKdbxFileSource
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountConnector
import io.vaultix.vaultix.remote.CloudAccountInventory
import io.vaultix.vaultix.remote.CloudAccountKind
import io.vaultix.vaultix.ui.addvault.AddCloudVaultViewModel
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
class CloudAccountsCrashGuardTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- 清单

    @Test
    fun `清单读取抛异常_不冒到进程且页面回到可操作终态`() = runTest {
        val inventory = mockk<CloudAccountInventory>(relaxed = true)
        coEvery { inventory.list() } throws IllegalStateException("Room 迁移中")
        val vm = viewModel(inventory)

        // init { refresh() } 立刻就会撞上这个异常。
        awaitUntil { !vm.state.value.loading }

        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.error).isInstanceOf(CloudError.Detail::class.java)
        assertThat((vm.state.value.error as CloudError.Detail).message).contains("Room 迁移中")
    }

    @Test
    fun `添加页清单抛异常_不闪退且loadingAccounts归零`() = runTest {
        val inventory = mockk<CloudAccountInventory>(relaxed = true)
        coEvery { inventory.list() } throws IllegalStateException("MSAL 未就绪")
        val vm = AddCloudVaultViewModel(
            mockk<VaultRepository>(relaxed = true),
            mockk<CloudAccountConnector>(relaxed = true),
            inventory,
        )

        vm.refreshConfiguredAccounts()
        awaitUntil { !vm.state.value.loadingAccounts }

        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.error).isNotNull()
    }

    // ---------------------------------------------------------------- 配置

    @Test
    fun `凭据写入抛异常_不闪退且PROBING回到FAILED`() = runTest {
        val inventory = mockk<CloudAccountInventory>(relaxed = true)
        coEvery { inventory.list() } returns emptyList()
        val connector = mockk<CloudAccountConnector>(relaxed = true)
        every { connector.normalizeServerUrl(any()) } returns SERVER_URL
        every { connector.saveWebDavCredential(any(), any(), any()) } throws
            IllegalStateException("Keystore 密钥失效")
        val vm = viewModel(inventory, connector)
        fillForm(vm)

        vm.probeWebDav()
        awaitUntil { vm.state.value.probe == ProbeState.FAILED }

        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.probeMessage).contains("Keystore 密钥失效")
    }

    @Test
    fun `自检成功_显示OK刷新出账号且密码即刻清空`() = runTest {
        val account = CloudAccount(
            kind = CloudAccountKind.WEBDAV,
            storedId = CREDENTIAL_ID,
            label = "dav.jianguoyun.com",
            vaultIds = emptyList(),
            connected = true,
            browseRoot = SERVER_URL,
        )
        val inventory = mockk<CloudAccountInventory>(relaxed = true)
        coEvery { inventory.list() } returns listOf(account)
        val connector = mockk<CloudAccountConnector>(relaxed = true)
        every { connector.normalizeServerUrl(any()) } returns SERVER_URL
        every { connector.saveWebDavCredential(any(), any(), any()) } returns CREDENTIAL_ID
        val source = mockk<WebDavKdbxFileSource>(relaxed = true)
        coEvery { source.testConnection() } returns Result.success(Unit)
        every { connector.webDavSource(any(), any()) } returns source
        val vm = viewModel(inventory, connector)
        fillForm(vm)

        vm.probeWebDav()
        awaitUntil { vm.state.value.probe == ProbeState.OK && vm.state.value.accounts.isNotEmpty() }

        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.accounts.single().storedId).isEqualTo(CREDENTIAL_ID)
        assertThat(vm.state.value.password).isEmpty()
    }

    // ---------------------------------------------------------------- 内部

    private fun viewModel(
        inventory: CloudAccountInventory,
        connector: CloudAccountConnector = mockk(relaxed = true),
    ): CloudAccountsViewModel =
        CloudAccountsViewModel(inventory, connector, mockk<Context>(relaxed = true))

    private fun fillForm(vm: CloudAccountsViewModel) {
        vm.onServerUrlChange(SERVER_URL)
        vm.onUsernameChange("user@example.com")
        vm.onPasswordChange("app-password")
    }

    /** 真实线程轮询：等 `Dispatchers.IO` 上的动作收尾（见文件头）。 */
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
