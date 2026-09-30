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

import app.keemobile.kotpass.database.Credentials
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
    /**
     * ★ 打开这个库所用的凭据 —— **写回时必须用同一组**。
     *
     * 为什么必须留着：KDBX 编码要先推导内容密钥，而推导依赖凭据。
     * 拿一組错凭据去 encode，得到的要么是抛错、要么更糟 ——
     * **一个用错密钥"加密"出来的文件，用户下次用原密码打不开**。
     *
     * ⚠️ 生命周期与 [database] 完全一致：锁库即随会话一起丢弃，不额外落盘、不进日志。
     */
    val credentials: Credentials,
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
        formatFailure(bytes)?.let { return Result.failure(it) }

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
                        credentials = candidate.credentials,
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

    /**
     * ★ **用已解锁会话现有的那组凭据重新打开 [bytes]**（2026-10-01，拉取路径收口）。
     *
     * ## 为什么必须有这个入口
     *
     * 「远端更新了，拉下来替换会话」要求**免密**重开 —— 那一刻用户并没有重新输密码，
     * 而会话里正握着打开这个库的那组 [Credentials]（见 [KdbxSession.credentials]）。
     * 直接用它解码即可 ⇒ **既不需要** app 侧的快速解锁信封，**也不需要**把主密码
     * 以明文形式交回这一层（此前正是"以为必须拿回主密码"才让替换一直做不出来）。
     *
     * ## ⚠️ 复用 [Credentials] 是安全的（不是巧合）
     *
     * [Credentials] 里的 `EncryptedValue` 只在**构造时**用随机 salt 做一次 XOR 混淆，
     * 取值时按同一 salt 还原 ⇒ 同一个对象可以反复用于 decode / encode。
     * 这一点早就被生产路径验证过：`KdbxRoundTrip.verify` 每次保存都用**同一组**
     * 会话凭据重新编码整库（见 `Kdbx.saveVia`）。
     *
     * ## 失败的语义
     *
     * 解码失败几乎只意味着一件事：**远端这个库换了主密码或 keyfile**。
     * 那与"用户输错了密码"是两回事 —— 归到 [KdbxOpenError.RemoteCredentialsMismatch]
     * 而不是 [KdbxOpenError.InvalidCredentials]，否则用户会看到"密码不正确"，
     * 然后去怀疑一个其实没输错的密码。
     *
     * ⚠️ 失败时**调用方的会话必须原封不动**：远端那份解不开，本地这份仍是用户
     *   此刻唯一可信的内容，不能拿解不开的字节把它顶掉。
     */
    fun reopen(
        bytes: ByteArray,
        credentials: Credentials,
        label: String,
    ): Result<KdbxSession> {
        formatFailure(bytes)?.let { return Result.failure(it) }

        return runCatching {
            ByteArrayInputStream(bytes).use { input ->
                KeePassDatabase.decode(
                    inputStream = input,
                    credentials = credentials,
                    cipherProviders = KDBX_CIPHER_PROVIDERS,
                )
            }
        }.fold(
            onSuccess = { database ->
                Result.success(
                    KdbxSession(
                        database = database,
                        content = database.toMappedContent(),
                        credentialLabel = label,
                        credentials = credentials,
                    ),
                )
            },
            onFailure = { error ->
                Result.failure(
                    KdbxFailure(KdbxOpenError.RemoteCredentialsMismatch, cause = error),
                )
            },
        )
    }

    /**
     * 先判格式再谈解密（[open] 与 [reopen] 共用的第一道闸）。
     *
     * ⚠️ 抽出来的理由与 `Kdbx.readFailure` 一样：**两个入口的格式判据必须同一份**。
     * 各写一遍的话，将来改格式策略（比如再收窄一次版本）一定会漏掉一个 ——
     * 而漏掉的那个会表现为"不是 KDBX 文件却报密码错误"，方向完全指错。
     */
    private fun formatFailure(bytes: ByteArray): KdbxFailure? = when (val format = inspectKdbxFormat(bytes)) {
        is KdbxFormat.NotKdbx -> KdbxFailure(KdbxOpenError.NotKdbxFile)
        is KdbxFormat.UnsupportedVersion ->
            KdbxFailure(KdbxOpenError.UnsupportedVersion(format.version))

        is KdbxFormat.Supported -> null
    }
}
