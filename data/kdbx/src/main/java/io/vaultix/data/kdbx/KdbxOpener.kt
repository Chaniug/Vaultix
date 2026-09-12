/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * KDBX 库的打开 / 解密（M2 阶段 A：只读）。
 *
 * 设计要点：
 * - **不落盘明文**：解出来的 `KeePassDatabase`（含明文密码）只活在内存里，锁库即丢弃
 *   （对齐 Bitwarden 侧「密钥只在内存」的既有约定）；对外只经 [Kdbx] 门面暴露映射结果；
 * - **候选凭据依次尝试**：keyfile 有多种历史形态，见 [buildCredentialCandidates]；
 * - **先判格式再解密**：不是 KDBX 文件时应直接说清楚，而不是报「密码错误」；
 * - 打开失败时返回**可区分的失败原因**（[KdbxOpenError]），UI 才能给出可执行提示。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/** 一次成功打开的会话（内存态；**绝不外泄**，见 [Kdbx] 门面的说明）。 */
internal class KdbxSession(
    val database: KeePassDatabase,
    val content: KdbxMappedContent,
    /** 成功打开所用的凭据形态（diagnostics 用；不含任何密钥 material）。 */
    val credentialLabel: String,
)

/**
 * KDBX 会话持有者（进程级单例）。
 *
 * 只按 vaultId 持有**已解锁**的会话；锁库时 [close] 丢弃（内存明文随之不可达）。
 */
internal object KdbxSessionStore {
    private val sessions = ConcurrentHashMap<String, KdbxSession>()

    fun put(vaultId: String, session: KdbxSession) {
        sessions[vaultId] = session
    }

    fun get(vaultId: String): KdbxSession? = sessions[vaultId]

    fun close(vaultId: String) {
        sessions.remove(vaultId)
    }

    fun closeAll() {
        sessions.clear()
    }

    fun unlockedIds(): Set<String> = sessions.keys.toSet()
}

/** KDBX 打开器（纯逻辑；文件字节由调用方读进来，便于单测直接喂 ByteArray）。 */
internal object KdbxOpener {

    /**
     * 用候选凭据尝试打开 [bytes]。
     *
     * @param password 主密码（空串 = 仅 keyfile）。
     * @param keyFileBytes keyfile 原始字节（null = 无）。
     */
    fun open(
        bytes: ByteArray,
        password: String,
        keyFileBytes: ByteArray?,
    ): Result<KdbxSession> {
        when (val format = inspectKdbxFormat(bytes)) {
            is KdbxFormat.NotKdbx -> return Result.failure(KdbxFailure(KdbxOpenError.NotKdbxFile))
            is KdbxFormat.UnsupportedVersion ->
                return Result.failure(KdbxFailure(KdbxOpenError.UnsupportedVersion(format.version)))

            is KdbxFormat.Supported -> Unit
        }

        val candidates = buildCredentialCandidates(password = password, keyFileBytes = keyFileBytes)
        val attempted = mutableListOf<String>()
        var lastError: Throwable? = null
        candidates.forEach { candidate ->
            attempted += candidate.label
            val decoded = runCatching {
                ByteArrayInputStream(bytes).use { input ->
                    KeePassDatabase.decode(
                        inputStream = input,
                        credentials = candidate.credentials,
                        cipherProviders = KDBX_CIPHER_PROVIDERS,
                    )
                }
            }
            decoded.getOrNull()?.let { database ->
                return Result.success(
                    KdbxSession(
                        database = database,
                        content = database.toMappedContent(),
                        credentialLabel = candidate.label,
                    ),
                )
            }
            lastError = decoded.exceptionOrNull()
        }
        // 全部候选都失败：按「凭据不对」归类（格式已在上面判过，走到这里说明文件本身可解析）。
        return Result.failure(
            KdbxFailure(KdbxOpenError.InvalidCredentials(attempted), cause = lastError),
        )
    }
}
