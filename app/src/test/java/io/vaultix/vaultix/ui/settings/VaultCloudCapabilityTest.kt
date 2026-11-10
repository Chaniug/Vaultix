/*
 * Vaultix — app:test · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「放到网盘」入口的可见性（2026-11-10）。
 *
 * 这一条判据决定 ⋮ 菜单里**出不出**那一项。判错的两种后果都不小：
 * - 对网盘库/ Bitwarden 也出 ⇒ 用户点了发现"我已经在网盘上了"，是废话；
 * - 对本地库不出 ⇒ 这轮要解决的问题原样还在（用户找不到上云的路）。
 *
 * ⚠️ 这里刻意钉住"**先看 kind**"：Bitwarden 的 origin 是 `https://…`，若只按
 *    `kdbxSourceTargetOf(origin) == LocalFile` 判，它会被误判成"本地文件"。
 */
class VaultCloudCapabilityTest {

    @Test
    fun `本地 SAF 库可以放到网盘`() {
        assertTrue(
            vault(
                kind = VaultKind.KDBX,
                origin = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fx.kdbx",
            ).canMoveToCloud(),
        )
    }

    @Test
    fun `OneDrive 库不出这一项`() {
        // `onedrive:<accountId>:<url-encoded path>` —— 见 OneDriveVaultOrigin.build。
        assertFalse(vault(VaultKind.KDBX, "onedrive:acct-1:%2Fvault.kdbx").canMoveToCloud())
    }

    @Test
    fun `WebDAV 库不出这一项`() {
        assertFalse(
            vault(
                kind = VaultKind.KDBX,
                origin = "webdav:cred-1:https%3A%2F%2Fnas.local%2Fdav%2Fx.kdbx",
            ).canMoveToCloud(),
        )
    }

    @Test
    fun `Bitwarden 库即使 origin 是网址也不出这一项`() {
        // 🔴 这是"先判 kind"的那条：只看 origin 会落到 LocalFile。
        assertFalse(vault(VaultKind.BITWARDEN, "https://vault.bitwarden.com").canMoveToCloud())
    }

    @Test
    fun `畸形 origin 的 KDBX 当作本地库处理`() {
        // 解析不出网盘来源 ⇒ 事实上只能从本机读 ⇒ 提供"放到网盘"是安全的
        // （最坏情况是用户点进说明页发现步骤对不上，不会损坏任何数据）。
        assertTrue(vault(VaultKind.KDBX, origin = "").canMoveToCloud())
    }

    private fun vault(kind: VaultKind, origin: String): VaultSummary = VaultSummary(
        id = "vault-test",
        kind = kind,
        name = "测试库",
        origin = origin,
    )
}
