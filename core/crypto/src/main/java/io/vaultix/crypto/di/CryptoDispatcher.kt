/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.crypto.di

import javax.inject.Qualifier

/**
 * 标记 core:crypto 内部使用的 CPU 密集调度器。
 *
 * KDF（PBKDF2 600k / Argon2id 64MiB）是几百毫秒到数秒的纯 CPU 负载，
 * 由 Hilt 注入而非在 [io.vaultix.crypto.VaultixCrypto] 内硬编码 `Dispatchers.Default`：
 * 既便于测试替换为 `StandardTestDispatcher`，也让上层能按需收窄并行度。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CryptoDispatcher
