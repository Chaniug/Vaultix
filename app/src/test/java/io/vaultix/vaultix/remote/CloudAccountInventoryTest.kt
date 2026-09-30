package io.vaultix.vaultix.remote

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.remote.onedrive.OneDriveAccountSession
import io.vaultix.vaultix.remote.onedrive.OneDriveAuthManager
import io.vaultix.vaultix.remote.onedrive.OneDriveVaultOrigin
import io.vaultix.vaultix.remote.webdav.WebDavCredentialStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「网盘账号」清单的 **OneDrive 兜底起点**（2026-09-30 用户报的 bug）。
 *
 * ## 这个 bug 长什么样
 *
 * 「添加密码库 → 从网盘添加」选一个**刚登录、还没有任何库指向它**的 OneDrive 账号时，
 * 页面直接报「这个账号还没有可用的目录，请先在设置里重新配置」—— 而用户刚刚登录成功，
 * 最自然的下一步恰恰是"看看网盘上有什么"。
 *
 * ## 两个叠加的成因（缺一个都不会出现这个症状）
 *
 * 1. `CloudAccountInventory.list()` 只给 WebDAV 账号配了"兜底起点"
 *    （`normalizeServerUrl(configured.serverUrl)`），**OneDrive 侧没有** ⇒
 *    `browseRoot = null`；
 * 2. `AddCloudVaultViewModel.pickAccount()` 的判据写成 `isNullOrBlank()` ——
 *    而 OneDrive 的 `browseRoot` 是**相对路径**，**空串就是根目录**
 *    （契约：`OneDriveGraphClient.listChildren(path = null/空)` = 根）。
 *
 * ⇒ 本文件钉住第 1 条（第 2 条在同一个 ViewModel 的 KDoc 里说明）。
 * 断言刻意用**空串**而不是"非 null"：空串正是"根目录"这个语义值，
 * 换成别的占位（如 `"/"`、`null → 猜一个`）都会在下游被解读成别的目录。
 */
class CloudAccountInventoryTest {

    private val vaults = mockk<VaultRepository>()
    private val webDavCredentials = mockk<WebDavCredentialStore>(relaxed = true)
    private val oneDriveAuth = mockk<OneDriveAuthManager>(relaxed = true)
    private val connector = mockk<CloudAccountConnector>(relaxed = true)

    private val accountId = "00000000-0000-0000-c8b8-c85b19b15ac7"

    private fun inventory() = CloudAccountInventory(
        vaultRepository = vaults,
        webDavCredentials = webDavCredentials,
        oneDriveAuth = oneDriveAuth,
        connector = connector,
    )

    private fun session() = OneDriveAccountSession(
        accountId = accountId,
        username = "valkjin@example.com",
        displayName = "Valkjin",
    )

    private fun vault(id: String, path: String) = VaultSummary(
        id = id,
        kind = VaultKind.KDBX,
        name = path.substringAfterLast('/'),
        origin = OneDriveVaultOrigin.build(accountId, path),
    )

    @Test
    fun `刚登录、还没有库指向它时 browseRoot 是空串（空串 = 根目录）`() = runTest {
        every { vaults.observeVaults() } returns flowOf(emptyList())
        coEvery { oneDriveAuth.listCachedSessions() } returns listOf(session())

        val account = inventory().list().single()

        assertEquals(CloudAccountKind.ONEDRIVE, account.kind)
        // ★ 这一条就是 bug 本身：修复前这里是 null ⇒ 添加库页报「没有可用目录」。
        assertEquals("", account.browseRoot)
    }

    @Test
    fun `已有库指向它时以库所在目录为起点，兜底空串不覆盖更具体的位置`() = runTest {
        every { vaults.observeVaults() } returns
            flowOf(listOf(vault("v1", "Keepass/valkjin.kdbx")))
        coEvery { oneDriveAuth.listCachedSessions() } returns listOf(session())

        val account = inventory().list().single()

        // putIfAbsent：来自**库**的起点优先（用户当初填的可能是子目录）。
        assertEquals("Keepass", account.browseRoot)
        assertEquals(listOf("v1"), account.vaultIds)
    }

    @Test
    fun `库直接放在根目录时起点仍是空串（根目录是合法起点，不是缺失）`() = runTest {
        every { vaults.observeVaults() } returns flowOf(listOf(vault("v1", "valkjin.kdbx")))
        coEvery { oneDriveAuth.listCachedSessions() } returns listOf(session())

        val account = inventory().list().single()

        assertEquals("", account.browseRoot)
    }
}
