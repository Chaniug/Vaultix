/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.KdbxCloudSyncStatus
import io.vaultix.model.VaultKind
import io.vaultix.vaultix.R
import org.junit.Test

/**
 * `vaultSyncLineResOf` —— 「那张卡片第三行写不写同步状态」的唯一判据（2026-11-10 晚）。
 *
 * ## 要钉住的真机反馈
 *
 * > 本地 kdbx 打开后，随便新建一个条目，然后点击一下同步。居然变成了已同步的本地文件，
 * > 这样合理吗……
 *
 * 「不合理」的理由不是文案不好看，而是**它说了一件不成立的事**：本地文件没有"另一端"，
 * 所以「已同步」在这个对象上**没有真值**。而两张卡片（本地 / OneDrive）外观一样的
 * 直接来源，就是第三行都写着同一句「已同步」。
 *
 * ## 判据的两层，缺一层就漏
 *
 * | 层 | 判据 | 漏了会怎样 |
 * |---|---|---|
 * | ① 有没有远端 | `kdbxSourceTargetOf(origin)` | 本地库照样写「已同步」（本轮 bug） |
 * | ② 有没有在同步 | `syncStatus` | 网盘库还没接入时也写「已同步」（凭空断言） |
 *
 * 第一层用 **origin** 而不是 `syncStatus != null`：后者是**可以写错的数据**
 * （迁移 / 旧版本 / 别的写入点都能写），而 origin 是"这个文件存在哪"的事实。
 * 这条区分正是本轮 bug 的根：一个数据列有没有值，被拿来回答了"库在哪"。
 */
class VaultSyncLineTest {

    private companion object {
        /** 真实形态：`content://<authority>/document/<percent-encoded path>`。 */
        const val SAF = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fnew.kdbx"

        /** 真实形态：`onedrive:<accountId>:<URL-encoded path>`。 */
        const val ONEDRIVE = "onedrive:acct-1:Keepass%2Fvalk.kdbx"

        /** 真实形态：`webdav:<credentialId>:<URL-encoded url>`。 */
        const val WEBDAV = "webdav:cred-1:https%3A%2F%2Fnas.local%3A5006%2Fdav%2Fx.kdbx"
    }

    // ───────────────────────── ① 本地库：任何状态都不写 ─────────────────────────

    @Test
    fun `本地 KDBX 不写同步行`() {
        // 这一条就是本轮 bug 的正解：本地文件没有远端，第三行不写。
        assertThat(vaultSyncLineResOf(VaultKind.KDBX, SAF, null)).isNull()
        assertThat(vaultSyncLineResOf(VaultKind.KDBX, SAF, KdbxCloudSyncStatus.LOCAL_ONLY)).isNull()
    }

    @Test
    fun `本地 KDBX 即使数据里写着 IN_SYNC 也不显示`() {
        // ★ 这是本轮最重要的一条：**数据写歪了，界面也不许跟着说谎**。
        //
        // 成因不是假想 —— 编排器曾把本地文件当成远端，走完三态判定后写 IN_SYNC
        // （真机：「新建一个条目 → 点同步 → 已同步」）。数据层已修（入口挡下 + 纠正），
        // 但已经写进 Room 的那一行不会自己变回去，所以展示侧必须独立成立。
        assertThat(vaultSyncLineResOf(VaultKind.KDBX, SAF, KdbxCloudSyncStatus.IN_SYNC)).isNull()
        // 其余状态同理：本地文件上它们每一个都是"关于不存在的东西"的断言。
        val allButLocalOnly = KdbxCloudSyncStatus.entries - KdbxCloudSyncStatus.LOCAL_ONLY
        allButLocalOnly.forEach { status ->
            assertThat(vaultSyncLineResOf(VaultKind.KDBX, SAF, status)).isNull()
        }
    }

    // ───────────────────────── ② 网盘库：只有真的对上才说 ─────────────────────────

    @Test
    fun `网盘 KDBX 显示同步行`() {
        assertThat(vaultSyncLineResOf(VaultKind.KDBX, ONEDRIVE, KdbxCloudSyncStatus.IN_SYNC))
            .isEqualTo(R.string.kdbx_sync_in_sync)
        assertThat(vaultSyncLineResOf(VaultKind.KDBX, WEBDAV, KdbxCloudSyncStatus.PENDING_UPLOAD))
            .isEqualTo(R.string.kdbx_sync_pending_upload)
    }

    @Test
    fun `网盘库尚未接入时也不写（没有在同步的东西）`() {
        // 来源是网盘、但库还没同步过：此刻写「已同步」是一句没有依据的断言。
        assertThat(vaultSyncLineResOf(VaultKind.KDBX, ONEDRIVE, null)).isNull()
        assertThat(vaultSyncLineResOf(VaultKind.KDBX, ONEDRIVE, KdbxCloudSyncStatus.LOCAL_ONLY))
            .isNull()
    }

    @Test
    fun `每一种需要用户处理的状态都有对应文案`() {
        // 穷尽性：新增状态忘了映射时，这里会先红，而不是界面上静默少一行。
        KdbxCloudSyncStatus.entries.forEach { status ->
            val mapped = kdbxSyncLineRes(status)
            if (status == KdbxCloudSyncStatus.LOCAL_ONLY) {
                assertThat(mapped).isNull()
            } else {
                assertThat(mapped).isNotNull()
            }
        }
    }

    // ───────────────────────── ③ Bitwarden 不串 ─────────────────────────

    @Test
    fun `Bitwarden 不走这一行`() {
        // 它有自己的 `SyncStatusLine`（本次同步进度）。两套数据串在一起，
        // 用户会看到"已同步"和"正在同步…"同时出现。
        assertThat(vaultSyncLineResOf(VaultKind.BITWARDEN, "https://vault.example.com", null))
            .isNull()
        assertThat(
            vaultSyncLineResOf(VaultKind.BITWARDEN, "https://vault.example.com", KdbxCloudSyncStatus.IN_SYNC),
        ).isNull()
    }

    // ───────────────────────── ④ 与云徽章判据同源 ─────────────────────────

    @Test
    fun `畸形网盘 origin 按本地处理（与条目云徽章同一个退化）`() {
        // `onedrive:` 缺账号/路径 ⇒ `kdbxSourceTargetOf` 退化成 LocalFile。
        // 两处判据共用同一个函数，所以这里必须与 `CloudSyncBadgeTest` 的结论一致：
        // 坏 origin 的库既不该有裸云徽章，也不该有同步行。
        listOf("onedrive:", "onedrive:acct-1:", "webdav:cred-1:").forEach { bad ->
            assertThat(vaultSyncLineResOf(VaultKind.KDBX, bad, KdbxCloudSyncStatus.IN_SYNC))
                .isNull()
        }
    }
}
