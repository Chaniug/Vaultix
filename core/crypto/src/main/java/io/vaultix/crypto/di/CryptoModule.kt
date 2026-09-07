/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.crypto.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/**
 * core:crypto 的 Hilt 装配。
 *
 * [io.vaultix.crypto.VaultixCrypto] 自身带 `@Inject constructor` + `@Singleton`，
 * Dagger 会直接构造它，因此这里**不再**为它写 `@Provides` —— 同一组件内
 * `@Inject` 构造与模块 `@Provides` 同时存在会触发 `Dagger/DuplicateBindings`。
 *
 * 本模块只提供它依赖的 [CryptoDispatcher]。
 */
@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    /** KDF 并发上限：同时跑多个 Argon2id（64MiB × N）很容易触发低内存设备 OOM。 */
    private const val MAX_CONCURRENT_KDF = 2

    /**
     * 密码学运算专用调度器。
     *
     * 用 `Dispatchers.Default.limitedParallelism(2)` 而非裸 `Dispatchers.Default`：
     * `Default` 的并行度等于 CPU 核心数（常见 8），8 个并发 Argon2id × 64MiB
     * 足以在低端机上直接 OOM。限制并发后，多余的 KDF 请求排队而非同时分配内存。
     *
     * 注意：`limitedParallelism` 只限制本 dispatcher 能占用的线程数，
     * 不会新建线程池，因此没有额外开销。
     */
    @Provides
    @Singleton
    @CryptoDispatcher
    fun provideCryptoDispatcher(): CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(MAX_CONCURRENT_KDF)
}
