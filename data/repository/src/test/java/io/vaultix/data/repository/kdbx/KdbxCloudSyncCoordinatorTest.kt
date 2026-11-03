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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        coEvery { source.readRemoteCurrent() } returns ByteArray(0)

        val replacer = mockk<KdbxSessionReplacer>(relaxed = true)
        coEvery { replacer.replace(VAULT_ID, any()) } returns Result.success(Unit)

        val coordinator = coordinator(status, source, replacer, this@runTest)
        val result = coordinator.resolveUsingRemote(VAULT_ID)

        // ① 一个字节都不许读（读就等于承认"可以覆盖"，而覆盖是不可逆的）。
        coVerify(exactly = 0) { source.read() }
        coVerify(exactly = 0) { source.readRemoteCurrent() }
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
        val remote = ByteArray(4) { 1 }
        coEvery { source.readRemoteCurrent() } returns remote

        val replacer = mockk<KdbxSessionReplacer>(relaxed = true)
        coEvery { replacer.replace(VAULT_ID, any()) } returns Result.success(Unit)

        val coordinator = coordinator(status, source, replacer, this@runTest)
        val result = coordinator.resolveUsingRemote(VAULT_ID)

        // ★ 2026-11-03：必须走"远端当前字节"这条通道。用 `read()` 的话，生产里的
        //   缓存装饰器会直接返回旧缓存 ⇒ 会话被旧内容替换、基线却记成新令牌
        //   ⇒ 提示「已下载」而条目没变，且此后永远判不出远端有更新。
        coVerify(exactly = 1) { source.readRemoteCurrent() }
        coVerify(exactly = 0) { source.read() }
        coVerify(exactly = 1) { replacer.replace(VAULT_ID, any()) }
        assertEquals(KdbxSyncResult.Downloaded, result)
    }

    /**
     * ★ 冲突对话框「用云端覆盖本地」必须**真的拉下来**（2026-11-03 修的死按钮）。
     *
     * ## 修的是什么
     *
     * 协调器内部有一道"本地有未上传改动就不许拉"的闸门（防自动拉取静默吞编辑），
     * 而它的判据 `impliesLocalChanges` 把 `CONFLICT` 也算成"有本地改动"
     * （那是给编排器判"两边都改"用的）。于是冲突对话框上点「用云端覆盖本地」时：
     *
     * ```
     * 状态 = CONFLICT → 闸门判定"本地有改动" → 直接返回 Conflict
     *   → UI 弹「云端又有了新改动，请重新同步」，而实际上一个字节都没动
     * ```
     *
     * ⇒ 除了这里，**没有任何一条用例**会以 `CONFLICT` 为初始状态走进这个入口，
     *   所以那个按钮坏了很久都没被测试发现。
     */
    @Test
    fun `用户选云端覆盖本地_状态是冲突也必须真的下载`() = runTest {
        val status = AtomicReference(KdbxCloudSyncStatus.CONFLICT.name)
        val source = mockk<KdbxFileSource>(relaxed = true)
        coEvery { source.stat() } returns KdbxFileStat(versionToken = "v2")
        val remote = ByteArray(3) { 7 }
        coEvery { source.readRemoteCurrent() } returns remote

        val replacer = mockk<KdbxSessionReplacer>(relaxed = true)
        coEvery { replacer.replace(VAULT_ID, any()) } returns Result.success(Unit)

        val coordinator = coordinator(status, source, replacer, this@runTest)
        val result = coordinator.resolveUsingRemote(VAULT_ID, discardLocalChanges = true)

        coVerify(exactly = 1) { source.readRemoteCurrent() }
        coVerify(exactly = 1) { replacer.replace(VAULT_ID, any()) }
        assertEquals(KdbxSyncResult.Downloaded, result)
    }

    /**
     * ★ **上传成功必须把收敛回调接上**（#233）。
     *
     * ## 为什么这条必须存在，以及它第一版为什么是假绿
     *
     * #233 的修复横跨三处：缓存装饰器的 `recordUploaded`、编排器的 `recordUploaded`、
     * 以及**协调器里那一行接线**。前两处都绿、这一行漏掉时整条链是断的 ——
     * 而它的表现不是报错，是"一切正常，只是每次解锁白下一次、角标一直亮着"。
     *
     * ⚠️ 第一版用例走了一次真实的 `write` 然后断言"缓存令牌变成了新值" ——
     * **假绿**：`CachedKdbxFileSource.write` **自己**就会用写入结果刷新缓存
     * （那是 #172 时代就有的行为），所以有没有 `onUploaded` 这条断言都成立。
     * 变异验证当场抓到了它（删掉接线，用例仍然绿）。
     *
     * ⇒ 现在断言的是**只有接线才能办到的那件事**：上传成功之后，新鲜度被直接置成
     * `VERIFIED`。`write` 路径不碰新鲜度（它初始是 `UNVERIFIED`），
     * 所以这一条只可能由 `recordUploaded` 做出来。
     */
    @Test
    fun `上传成功后_协调器必须把收敛回调接上`() = runTest {
        val dao = mockk<VaultDao>(relaxed = true)
        coEvery { dao.get(VAULT_ID) } returns VaultEntity(
            id = VAULT_ID,
            kind = "KDBX",
            displayName = "库",
            origin = SAF_ORIGIN,
            createdAt = 0L,
        )
        val cache = RecordingCache()
        // ⚠️ 编排器的工厂必须**与协调器同源**（生产里 `KdbxCloudSyncModule` 就是这么接的：
        //    `fileSourceFactory = { origin -> coordinator.get().fileSourceFor(origin) }`）。
        //    第一版这里写的是 `{ null }` ⇒ 收敛路径在编排器里就安静地 return 了，
        //    用例因此红在"新鲜度没变"，看起来像接线断了，实际是装置没搭成生产的形状。
        lateinit var coordinatorRef: KdbxCloudSyncCoordinator
        val orchestrator = KdbxSyncOrchestrator(
            vaultDao = dao,
            fileSourceFactory = { origin -> coordinatorRef.fileSourceFor(origin) },
        )
        val coordinator = KdbxCloudSyncCoordinator(
            vaultDao = dao,
            orchestrator = orchestrator,
            safFactory = { null },
            sessionReplacer = mockk(relaxed = true),
            okHttp = { null },
            webDavCredentials = mockk(relaxed = true),
            fileCache = cache,
            appScope = this,
        )

        val remote = mockk<KdbxFileSource>(relaxed = true)
        coEvery { remote.stat() } returns KdbxFileStat(versionToken = OLD_TOKEN)
        coEvery { remote.write(any(), any(), any(), any()) } returns
            io.vaultix.data.kdbx.KdbxFileWriteResult(versionToken = NEW_TOKEN)
        coordinatorRef = coordinator
        coordinator.registerFactory { origin -> remote.takeIf { origin == EXTRA_ORIGIN } }
        // ⚠️ 别猜前缀：WebDAV 的 `webdav:` 会先被 WebDAV 分支吃掉 ⇒ 用例会安静地测到
        //    另一个来源上。用协调器自己的判据问一次。
        assertTrue(
            "装置错了：$EXTRA_ORIGIN 没走到额外工厂，这条用例测的就不是它",
            coordinator.isExtraFactoryOrigin(EXTRA_ORIGIN),
        )

        val source = requireNotNull(coordinator.fileSourceFor(EXTRA_ORIGIN)) as CachedKdbxFileSource
        assertEquals(
            "前置条件：写之前一定是未校验（否则下面那条断言说明不了任何事）",
            CachedKdbxFileSource.Freshness.UNVERIFIED,
            source.freshness.value,
        )

        source.write(ByteArray(8) { it.toByte() }, expectedVersion = OLD_TOKEN)
        runCurrent()

        assertEquals(
            "上传成功 ⇒ 缓存内容就是远端那一版，新鲜度必须直接是 VERIFIED；" +
                "停在 UNVERIFIED 说明协调器根本没把收敛回调接进来",
            CachedKdbxFileSource.Freshness.VERIFIED,
            source.freshness.value,
        )
        assertEquals(NEW_TOKEN, cache.load(EXTRA_ORIGIN)?.versionToken)
        assertEquals(NEW_TOKEN, cache.load(EXTRA_ORIGIN)?.versionToken)
    }

    /**
     * ★ 远端来源**按 origin 复用实例**（#246）。
     *
     * ## 这条钉的是一类"值共享了、流却没共享"的缺陷
     *
     * 装饰器每次新建时，`freshness` 是**每个实例各一个** StateFlow。
     * UI 订阅的是"解析那一刻"那个实例上的流 —— 之后上传成功 / 后台校验把结论
     * 写进别的实例，UI 那个流**再也不会发值**。用户看到的就是
     * 「同步成功了，角标一直不变」。
     *
     * ⚠️ #233 的 `FreshnessHolder` 只解决了"值"（容器跨实例共享），
     * 容器**本身**仍然是每个实例 `state = asStateFlow()` 各自包一层 ——
     * 所以同一个 `FreshnessHolder.value` 变了，只有**那个**实例的流会发。
     * 实测确认：`MutableStateFlow.asStateFlow()` 每次返回新的包装对象，两者不共享订阅。
     *
     * ⇒ 判据只能是"同一个 origin 拿到的是不是同一个实例"。
     */
    @Test
    fun `同一个远端库两次解析来源必须拿到同一个实例`() = runTest {
        val dao = mockk<VaultDao>(relaxed = true)
        coEvery { dao.get(any()) } returns VaultEntity(
            id = VAULT_ID,
            kind = "KDBX",
            displayName = "库",
            origin = EXTRA_ORIGIN,
            createdAt = 0L,
        )
        val coordinator = KdbxCloudSyncCoordinator(
            vaultDao = dao,
            orchestrator = KdbxSyncOrchestrator(vaultDao = dao, fileSourceFactory = { null }),
            safFactory = { null },
            sessionReplacer = mockk(relaxed = true),
            okHttp = { null },
            webDavCredentials = mockk(relaxed = true),
            fileCache = RecordingCache(),
            appScope = this,
        )
        coordinator.registerFactory { origin ->
            mockk<KdbxFileSource>(relaxed = true).takeIf { origin == EXTRA_ORIGIN }
        }

        val first = coordinator.fileSourceFor(EXTRA_ORIGIN)
        val second = coordinator.fileSourceFor(EXTRA_ORIGIN)

        assertTrue("装置错了：没解析出远端来源，这条用例什么都没测", first != null)
        assertTrue(
            "同一个 origin 必须复用同一个装饰器实例 —— 否则 UI 订到的那条 freshness 流" +
                "在别处更新后永远不再发值（角标不刷新）",
            first === second,
        )
        // ⚠️ 不同 origin 绝不能串：这是"按 key 复用"而不是"全局单例"。
        val other = coordinator.fileSourceFor("onedrive:acc-2:Vaultix%2Fother.kdbx")
        assertTrue("不同 origin 不得共享同一个装饰器", other !== first || other == null)
    }

    /** 只记录最后一次 `save` 的缓存替身（断言"收敛是否真的落到缓存上"）。 */
    private class RecordingCache : KdbxFileCache {
        private val map = mutableMapOf<String, CachedKdbxFile>()

        override suspend fun load(key: String): CachedKdbxFile? = map[key]

        override suspend fun save(key: String, file: CachedKdbxFile) {
            map[key] = file
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }

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
        /** 一个**不会**被 SAF / WebDAV 分支吃掉的 origin：模拟 OneDrive 这类额外工厂。 */
        const val EXTRA_ORIGIN = "onedrive:acc-1:Vaultix%2Fvault.kdbx"
        const val OLD_TOKEN = "etag-old"
        const val NEW_TOKEN = "etag-new"
    }
}
