/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository.kdbx

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.model.KdbxCloudSyncStatus
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `KdbxCloudSyncCoordinator.resolveUsingRemote` 的**下载窗口保护**（2026-10-20）。
 *
 * ## 这条钉的是一个"只差几秒"的静默丢改动
 *
 * 「用远端覆盖本地 / 拉取远端更新」的形状是：
 *
 * ```
 * 调用方判"本地没改"  →  stat  →  read(网络，真机 1–10 s)  →  replaceSession(整体替换)
 * ```
 *
 * 调用方那个判断在 `read` 之前就做完了，而 `read` 本身要好几秒 ——
 * 用户在这几秒里再编辑一笔，`replaceSession` 会把会话**整体替换**掉，
 * 那一笔就没了，而且紧接着状态被记成 `IN_SYNC`（"已同步"），**再也不会重试**。
 *
 * ⇒ 修复是在 `read` **之前**用最新的 `syncStatus` 再判一次，有本地改动就**不下载**。
 *
 * ## 为什么这条必须是"断言没发生"
 *
 * 我们要证明的是"**绝不能**在本地有改动时读远端 / 替换会话"。
 * ⇒ 断言用 `coVerify(exactly = 0) { source.read() }` 与 `replaceSession` 零调用。
 * ⚠️ 「零调用」有假绿风险（装置配错、压根没进这个分支）⇒
 *    配了反证用例 [本地没改时_确实下载并替换会话]，同一套装置下必须看到 1 次。
 */
class KdbxCloudSyncCoordinatorTest {

    @Test
    fun `下载前发现本地已改_不许读远端也不许替换会话`() = runTest {
        // 状态在"调用方判完之后"被改成 PENDING_UPLOAD —— 模拟下载窗口里的编辑。
        val status = AtomicReference(KdbxCloudSyncStatus.PENDING_UPLOAD.name)
        val source = mockk<KdbxFileSource>(relaxed = true)
        coEvery { source.stat() } returns KdbxFileStat(versionToken = "v2")
        coEvery { source.read() } returns ByteArray(0)

        val replacer = mockk<KdbxSessionReplacer>(relaxed = true)
        coEvery { replacer.replace(VAULT_ID, any()) } returns Result.success(Unit)

        val coordinator = coordinator(status, source, replacer, this@runTest)
        val result = coordinator.resolveUsingRemote(VAULT_ID)

        // ① 一个字节都不许读（读就等于承认"可以覆盖"，而覆盖是不可逆的）。
        coVerify(exactly = 0) { source.read() }
        // ② 会话绝不能被替换。
        coVerify(exactly = 0) { replacer.replace(any(), any()) }
        // ③ 必须报冲突（交用户拍板），而不是"失败"或"成功"。
        assertEquals(KdbxSyncResult.Conflict("v2"), result)
    }

    /**
     * **反证**：同一套装置下，本地没改时必须真的下载并替换会话。
     *
     * ⚠️ 没有这条，上面那两条 `exactly = 0` 可能只是因为装置压根没走到这段逻辑。
     */
    @Test
    fun `本地没改时_确实下载并替换会话`() = runTest {
        val status = AtomicReference(KdbxCloudSyncStatus.REMOTE_CHANGED.name)
        val source = mockk<KdbxFileSource>(relaxed = true)
        coEvery { source.stat() } returns KdbxFileStat(versionToken = "v2")
        coEvery { source.read() } returns ByteArray(4) { 1 }

        val replacer = mockk<KdbxSessionReplacer>(relaxed = true)
        coEvery { replacer.replace(VAULT_ID, any()) } returns Result.success(Unit)

        val coordinator = coordinator(status, source, replacer, this@runTest)
        val result = coordinator.resolveUsingRemote(VAULT_ID)

        coVerify(exactly = 1) { source.read() }
        coVerify(exactly = 1) { replacer.replace(VAULT_ID, any()) }
        assertEquals(KdbxSyncResult.Downloaded, result)
    }

    // ---------------------------------------------------------------- 装置

    private fun coordinator(
        status: AtomicReference<String?>,
        source: KdbxFileSource,
        replacer: KdbxSessionReplacer,
        scope: CoroutineScope,
    ): KdbxCloudSyncCoordinator {
        // `relaxed = true` 只是为了让 `updateSyncState` 这类**与断言无关**的副作用
        // （协调器成功下载后会 `markResolved` 记 IN_SYNC）不用逐个打桩；
        // `get` 仍然显式打桩并**按 `status` 实时求值** —— 那才是被测的判据。
        val dao = mockk<VaultDao>(relaxed = true)
        // ⚠️ 别写成裸的 `get(...)`：`coEvery { }` 块的 receiver 是 `MockKMatcherScope`，
        //    它自带的 `get` 会遮蔽 DAO 的方法（`.ai/ISSUES.md` #146）。
        coEvery { dao.get(VAULT_ID) } coAnswers {
            VaultEntity(
                id = VAULT_ID,
                kind = "KDBX",
                displayName = "库",
                origin = SAF_ORIGIN,
                createdAt = 0L,
                syncStatus = status.get(),
            )
        }
        val orchestrator = KdbxSyncOrchestrator(vaultDao = dao, fileSourceFactory = { source })
        return KdbxCloudSyncCoordinator(
            vaultDao = dao,
            orchestrator = orchestrator,
            safFactory = { source },
            sessionReplacer = replacer,
            okHttp = { null },
            webDavCredentials = mockk(relaxed = true),
            fileCache = mockk<KdbxFileCache>(relaxed = true),
            appScope = scope,
        )
    }

    private companion object {
        const val VAULT_ID = "vault-coordinator-1"
        const val SAF_ORIGIN = "content://com.android.providers.downloads.documents/document/9"
    }
}
