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
import io.vaultix.database.dao.VaultDao
import java.io.File
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

    /**
     * 远端 kdbx 的**本地缓存**（批次 B1，2026-09-30）。
     *
     * ⚠️ 根目录用 **`noBackupFilesDir`**：缓存不该进系统备份 —— 备份里多一份库文件
     * 既无必要，也白白扩大暴露面（虽然它只是密文，见 [KdbxFileCache] 的安全取舍）。
     */
    @Provides
    @Singleton
    fun provideKdbxFileCache(
        @ApplicationContext context: Context,
    ): KdbxFileCache = FileKdbxFileCache(
        root = File(context.applicationContext.noBackupFilesDir, "kdbx_cache"),
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
        fileCache: KdbxFileCache,
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
        fileCache = fileCache,
    )

    /**
     * 把「origin ⇒ 来源」这张表**单独暴露成一个小接口**（见 [KdbxFileSourceResolver] 的 KDoc）。
     *
     * 解锁 / 校验路径（`VaultRepositoryImpl` · `LocalUnlockEnrollment` · `PinEnrollment`）
     * 靠它把旧 `KdbxSource` 换掉，从此 `webdav:` / `onedrive:` 的库**也能被打开**
     * （2026-09-17 前它们只会被交给 `Uri.parse` 然后静默读不到，方案 §16.1）。
     *
     * ⚠️ 走 [Provider] 而不是直接注入协调器 —— 与上面编排器同款理由：
     * 协调器依赖 `KdbxSessionReplacer`，而"免密会话替换"迟早要反过来依赖仓储
     * （它得拿 app 侧的快解锁凭据才能解出主密码）。直接注入会成**构造环**，
     * `get()` 把求值推迟到调用时，环就消失了。
     */
    @Provides
    @Singleton
    fun provideKdbxFileSourceResolver(
        coordinator: Provider<KdbxCloudSyncCoordinator>,
    ): KdbxFileSourceResolver = KdbxFileSourceResolver { origin ->
        coordinator.get().fileSourceFor(origin)
    }
}

/*
 * ⚠️ 这里**曾经**有一个 `RequiresUnlockSessionReplacer`（恒定返回失败的"占位实现"），
 * 2026-10-01 已删除 —— 保留它的代价太高：
 *
 * 1. 它其实**从未被注入过**：`@Inject constructor` 提供的是 `RequiresUnlockSessionReplacer`
 *    这个具体类型，而不是 [KdbxSessionReplacer]，Hilt 只会去取 app 侧那个 `@Provides`。
 *    ⇒ 它是一个**死类**，唯一的作用是让人以为"还有个兜底实现"。
 * 2. 更糟的是它把一句错误结论写成了定论：「免密重开需要主密码，而主密码只在 app 侧」。
 *    实际上已解锁的会话里就存着那组凭据（`KdbxSession.credentials`），
 *    免密替换根本不需要把主密码取回来 —— 这个误判让拉取路径一直做不出来。
 *
 * ⇒ 现在只有**一份**实现：`Kdbx.replaceSession`（`data:kdbx`），由 app 侧
 *   `KdbxCloudSyncAppModule.provideKdbxSessionReplacer` 直接指向它。
 *   别再加第二个实现：两条替换路径迟早漂移，而漂移的代价是"一边换了会话、一边没换"。
 */
