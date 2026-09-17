/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 网盘同步的 Hilt 装配**。
 *
 * ## 为什么这一个模块里塞了三重间接（工厂 + 注册口 + 单例）
 *
 * 同步链上有一个**形状上的**循环依赖，不打破就编译不过（Hilt 会直接拒绝）：
 *
 * ```
 *   KdbxCloudSyncCoordinator  ──需要──▶  KdbxSyncOrchestrator   （跑一次同步）
 *   KdbxSyncOrchestrator      ──需要──▶  文件来源工厂            （stat / write）
 *   文件来源工厂              ──需要──▶  KdbxCloudSyncCoordinator（解析 origin）
 * ```
 *
 * ⚠️ 注意最后一跳**不是笔误**：来源工厂要知道"这个 origin 该用哪个实现"，
 * 而那张判据表（`content://` / `webdav:` / `onedrive:`）就在协调器里（单一真值源）。
 * 于是协调器既是"装配者"又是"被装配者"。
 *
 * 打破方式：编排器拿到的是 `(origin) -> KdbxFileSource?` 这个**函数**，函数体里
 * 才去问协调器 ⇒ 依赖被推迟到**运行时**，构造期不再有环。
 * 这里必须用 [javax.inject.Provider] 而不是提前把 lambda 捕获好 —— 捕获要在
 * `@Provides` 函数体内做，那时候协调器还没构造出来。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.database.dao.VaultDao
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KdbxCloudSyncModule {

    /**
     * 同步编排器。
     *
     * 来源工厂是 `{ origin -> coordinator.get().fileSourceFor(origin) }` ——
     * `get()` 在**每次同步时**才求值，环因此被推迟到运行时（见文件头）。
     */
    @Provides
    @Singleton
    fun provideKdbxSyncOrchestrator(
        vaultDao: VaultDao,
        coordinator: Provider<KdbxCloudSyncCoordinator>,
    ): KdbxSyncOrchestrator = KdbxSyncOrchestrator(
        vaultDao = vaultDao,
        fileSourceFactory = { origin -> coordinator.get().fileSourceFor(origin) },
    )

    @Provides
    @Singleton
    fun provideKdbxCloudSyncCoordinator(
        @ApplicationContext context: Context,
        vaultDao: VaultDao,
        orchestrator: KdbxSyncOrchestrator,
        sessionReplacer: KdbxSessionReplacer,
        okHttp: Provider<OkHttpClient>,
        webDavCredentials: WebDavCredentialLookup,
    ): KdbxCloudSyncCoordinator = KdbxCloudSyncCoordinator(
        vaultDao = vaultDao,
        orchestrator = orchestrator,
        // ⚠️ `applicationContext` 而不是裸 `context`：SAF 来源会被长命对象持有，
        //    持有 Activity 的 Context 就是一次泄漏。
        safFactory = { origin -> safKdbxFileSource(context.applicationContext, origin) },
        sessionReplacer = sessionReplacer,
        // ⚠️ 同样是 Provider：OkHttpClient 的构造（拦截器、连接池）不该在
        //    "只是问一下有没有来源"时被触发。
        okHttp = { runCatching { okHttp.get() }.getOrNull() },
        webDavCredentials = webDavCredentials,
    )
}

/**
 * **默认**的会话替换实现：诚实退化 —— 要求用户重新解锁。
 *
 * ## 为什么先放这一版
 *
 * 「用远端覆盖本地」之后的正确状态是"会话里已经是远端那份字节"。要做到**免密**，
 * 需要 [Kdbx.unlock] 拿到该库的主密码 —— 而那把密码只存在于 app 侧的快速解锁
 * 凭据（`LocalUnlockEnrollment` / `PinUnlockStore` 的信封）里，**不在 `data:repository`**。
 *
 * ⇒ 两害相权：
 * - **现在**：替换失败 ⇒ 返回一句人话，状态留在 `CONFLICT`（用户还能再选一次）。
 *   数据是安全的 —— 什么都没写，远端毫发无损。
 * - **硬凑一个"假成功"**：状态记成"已用远端覆盖"，而会话里还是旧内容 ⇒
 *   用户下一次保存就把旧内容推回去，**静默覆盖远端的新版本**。
 *   那正好违反"同步不丢"，比"多要求一次解锁"恶劣得多。
 *
 * ⇒ 所以这里**故意**不装作能做。真正免密的实现在 app 侧绑定（见 `KdbxCloudSyncModule`
 * 的说明与 `OneDriveModule` 附近的 app 装配），归下一批。
 */
@Singleton
class RequiresUnlockSessionReplacer @javax.inject.Inject constructor() : KdbxSessionReplacer {

    override suspend fun replace(vaultId: String, remoteBytes: ByteArray): Result<Unit> =
        Result.failure(
            IllegalStateException("云端已更新。请先锁定并重新解锁该密码库，再执行同步。"),
        )
}
