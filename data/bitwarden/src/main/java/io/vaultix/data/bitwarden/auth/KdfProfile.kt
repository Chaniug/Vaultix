/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * KDF 参数语义（类型 / 迭代 / 内存 / 并行度）以 Bitwarden 官方客户端实际行为为准；
 * 参考 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的实战经验，本文件为独立编写。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.auth

import io.vaultix.data.bitwarden.api.PreLoginResponse

/**
 * 账号的 KDF 参数快照（`/accounts/prelogin` 的产物），登录成功时落盘。
 *
 * **为什么必须持久化**：主密码解锁走的是**本地路径**（见
 * [BitwardenAuthRepository.unlockWithMasterKey]）—— 用主密码派生 MasterKey，再解开本地
 * 已持久化的账号对称密钥。若每次解锁都要先联网取 KDF 参数，用户断网时就**连输对主密码
 * 也开不了库**，这与 Bitwarden 官方客户端的行为不符（官方把 KDF 参数随账号状态一起保存）。
 */
internal data class KdfProfile(
    val type: Int,
    val iterations: Int,
    /** Argon2id 内存（MB）；PBKDF2 无此字段 ⇒ null。 */
    val memoryMb: Int?,
    /** Argon2id 并行度；PBKDF2 无此字段 ⇒ null。 */
    val parallelism: Int?,
) {
    /** 打包成单行文本；[ABSENT] 表示服务端未返回该字段（PBKDF2 下必然如此）。 */
    fun serialize(): String =
        listOf(type, iterations, memoryMb ?: ABSENT, parallelism ?: ABSENT).joinToString(SEPARATOR)

    internal companion object {
        /** 服务端未返回该字段时的占位值（`-1`，不可能与真实参数混淆）。 */
        private const val ABSENT = -1
        private const val FIELD_COUNT = 4
        private const val SEPARATOR = "|"

        /** 反序列化；格式不符返回 null（**不抛异常**：落盘数据被破坏时应当退回联网取参数）。 */
        fun parse(raw: String): KdfProfile? {
            val parts = raw.split(SEPARATOR)
            if (parts.size != FIELD_COUNT) return null
            val type = parts[0].toIntOrNull() ?: return null
            val iterations = parts[1].toIntOrNull() ?: return null
            val memoryMb = parts[2].toIntOrNull()?.takeIf { it > 0 }
            val parallelism = parts[3].toIntOrNull()?.takeIf { it > 0 }
            return KdfProfile(type, iterations, memoryMb, parallelism)
        }
    }
}

/** prelogin 响应 → 参数快照（兼容官方 PascalCase 与 Vaultwarden camelCase 双形态）。 */
internal fun PreLoginResponse.toKdfProfile(): KdfProfile = KdfProfile(
    type = resolvedKdf(),
    iterations = resolvedIterations(),
    memoryMb = resolvedMemoryMb(),
    parallelism = resolvedParallelism(),
)
