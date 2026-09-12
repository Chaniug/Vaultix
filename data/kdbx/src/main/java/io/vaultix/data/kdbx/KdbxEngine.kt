/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

/**
 * KDBX（KeePass 2.x）本地库引擎的入口标记。
 *
 * 本模块承担 M2 里程碑：把 `.kdbx` 文件读成 Vaultix 的领域模型（[io.vaultix.model.VaultItem]），
 * 让「Bitwarden 云端 + KDBX 本地」两种库在 UI / 自动填充侧完全同构。
 *
 * ## 引擎选型（2026-09-12 定案）
 * 使用 **kotpass**（`app.keemobile:kotpass`，**MIT** 许可证，Copyright (c) 2021 Denis T.）：
 * - 支持 KDBX 4.1（含 Argon2d/id、AES-KDF、AES-256-CBC、ChaCha20、HMAC-SHA256 分块认证、GZip）；
 * - 纯 Kotlin/JVM 库，唯一传递依赖 okio（项目已有）；
 * - 上游自带往返测试与测试向量，我们只需做「映射层 + 集成层」。
 *
 * ⚠️ **刻意不搬 Keyguard 的 KDBX 实现**：Keyguard 本体是
 * `All Rights Reserved`（README：「available for personal use only」），且它的 KDBX 能力
 * 本来就来自 kotpass —— 那份 vendored 副本已被改成依赖 Keyguard 自己的
 * `util:foundation` / `util:crypto`（见其 `PlatformCrypto.kt`），搬过来反而要先拆补丁。
 * 直接依赖上游 MIT 版本，许可证链条最干净、也最省事。
 */
internal object KdbxEngine {
    /** 引擎标识（诊断日志用）。 */
    const val NAME: String = "kotpass"
}
