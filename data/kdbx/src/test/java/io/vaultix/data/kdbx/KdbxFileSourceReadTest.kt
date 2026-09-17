/*
 * Vaultix — data:kdbx（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * KDBX **门面的来源无关性**（方案 §16.2 R1 的验收项）。
 *
 * ## 这条测试要证明什么
 *
 * 迁移（2026-09-17）之前，`Kdbx.unlock` 收的是一个 `KdbxSource`（`(uri) -> ByteArray?`），
 * 而它在生产里的**唯一**实现是
 * `ContentResolver.openInputStream(Uri.parse(uri))` —— 只认 SAF `content://`。
 * 于是 `"webdav:…"` / `"onedrive:…"` 交给它必然读不到：网盘库**加不进来、也解锁不了**
 * （方案 §16.1）。迁移后参数是一个 [KdbxFileSource] **对象**。
 *
 * ⇒ 本测试用**纯内存**来源把库打开：没有 `Uri`、没有 `ContentResolver`、
 *   没有 Android 运行时。只要它过，"读路径不再依赖 SAF" 就是**被证明**的，
 *   而不是被声称的。
 *
 * ## ⚠️ 为什么它必须是一条独立测试（而不是并进 `KdbxReadPathTest`）
 *
 * `KdbxReadPathTest` 的用例全部**绕过门面**、直接喂 `ByteArray` 给 `KdbxOpener`。
 * 于是"门面收不收得下非 SAF 来源"这件事**此前没有任何测试覆盖** ——
 * 那正是这次线上问题的盲区：测试全绿，功能却完全不可用。
 * 教训：**别只测被调用的那一层，要测被"接线"的那一层。**
 *
 * ## 为什么用 `runBlocking` 而不是 `runTest`
 *
 * 本模块没有 `kotlinx-coroutines-test` 依赖，而这三个用例要验的是"能不能读"，
 * 不需要虚拟时间。为一个不需要调度器的测试引入整套测试调度器，
 * 只会让这个模块多一个可能漂移的依赖。
 */
class KdbxFileSourceReadTest {

    @After
    fun tearDown() {
        // `Kdbx` 是全局单例（会话按 vaultId 存在内存里）⇒ 用例之间必须互不残留。
        Kdbx.lockAll()
    }

    @Test
    fun `opens a kdbx through an in-memory source with no content resolver`() = runBlocking {
        val source = InMemoryKdbxSource(buildDatabase())

        val opened = Kdbx.unlock(
            vaultId = MEMORY_VAULT_ID,
            source = source,
            password = PASSWORD,
        ).getOrThrow()

        assertThat(opened.items.map { it.title }).contains("GitHub")
        assertThat(opened.items.single { it.title == "GitHub" }.password).isEqualTo("s3cret-pw")
        // 会话真的登记上了（不只是返回了内容）—— UI 的"已解锁"据此而来。
        assertThat(Kdbx.isUnlocked(MEMORY_VAULT_ID)).isTrue()
        assertThat(Kdbx.contentOf(MEMORY_VAULT_ID)?.items?.size).isEqualTo(opened.items.size)
    }

    @Test
    fun `verify checks credentials without registering a session`() = runBlocking {
        val source = InMemoryKdbxSource(buildDatabase())

        assertThat(Kdbx.verify(source, PASSWORD)).isTrue()
        assertThat(Kdbx.verify(source, "wrong-password")).isFalse()
        // ★ 校验必须**无副作用**：它是"配 PIN / 配快速解锁"前的预检，
        //   真开库会把明文拉进内存并覆盖该库可能已有的会话。
        assertThat(Kdbx.isUnlocked(MEMORY_VAULT_ID)).isFalse()
    }

    @Test
    fun `a source that cannot read fails as SourceUnavailable and keeps the original cause`() = runBlocking {
        val source = FailingKdbxSource(SOURCE_ERROR_MESSAGE)

        val result = Kdbx.unlock(
            vaultId = MEMORY_VAULT_ID,
            source = source,
            password = PASSWORD,
        )

        assertThat(result.isFailure).isTrue()
        val failure = result.exceptionOrNull()
        assertThat(failure).isInstanceOf(KdbxFailure::class.java)
        val reason = (failure as KdbxFailure).error
        assertThat(reason).isInstanceOf(KdbxOpenError.SourceUnavailable::class.java)
        // ★ 来源自己那句人话必须**原样**带上来（"账号或密码不对"而不是"读取失败"）——
        //   用户的动作由这句话决定。
        assertThat((reason as KdbxOpenError.SourceUnavailable).detail).isEqualTo(SOURCE_ERROR_MESSAGE)
        // ★ 原始异常也必须留着：它是排查时唯一能看出"到底哪个请求、服务端原话"的线索。
        assertThat(failure.cause).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `verify returns false instead of throwing when the source cannot read`() = runBlocking {
        // 校验路径的调用方只问"能不能"，读不到一律算"不能"；
        // 抛出会把「文件读不到」伪装成一次崩溃。
        assertThat(Kdbx.verify(FailingKdbxSource(SOURCE_ERROR_MESSAGE), PASSWORD)).isFalse()
    }

    private companion object {
        const val MEMORY_VAULT_ID = "memory://fixture/test.kdbx"
        const val SOURCE_ERROR_MESSAGE = "远端账号或密码不对"
    }
}

/**
 * 纯内存来源 —— 证明"来源可以是任何东西"，而不是只有 `ContentResolver`。
 *
 * ⚠️ 刻意让它**不是** SAF 实现：这正是本文件要验的那条边界。
 */
private class InMemoryKdbxSource(private val bytes: ByteArray) : KdbxFileSource {

    override suspend fun stat(): KdbxFileStat = KdbxFileStat(
        // 稳定的令牌（内容长度即可）—— 内容不会变，故无需哈希。
        versionToken = "memory-${bytes.size}",
        sizeBytes = bytes.size.toLong(),
        displayName = "test.kdbx",
    )

    override suspend fun read(): ByteArray = bytes

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
    ): KdbxFileWriteResult = throw UnsupportedOperationException("本测试只覆盖读路径")

    override suspend fun testConnection(): Result<Unit> = Result.success(Unit)
}

/** 读不到的来源（模拟 WebDAV 凭据缺失 / 网络失败 / SAF 授权失效）。 */
private class FailingKdbxSource(private val message: String) : KdbxFileSource {

    override suspend fun stat(): KdbxFileStat = throw IllegalStateException(message)

    override suspend fun read(): ByteArray = throw IllegalStateException(message)

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
    ): KdbxFileWriteResult = throw IllegalStateException(message)

    override suspend fun testConnection(): Result<Unit> = Result.failure(IllegalStateException(message))
}
