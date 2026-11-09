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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    /**
     * 会话集合的**代次计数**（只带一个整数，绝不携带明文）。
     *
     * ★ 2026-11-08 真机「点删除要等 1~2 秒 / 回收站要等 2~5 秒」：
     *   UI 的读流原先只认 `KdbxSessionFlow.bump()`，而它挂在 `persist` 末尾
     *   —— 要等整库编码、往返自检、落缓存全部跑完。会话其实在 `mutateLocked`
     *   里就已经换好了。⇒ 会话一换就自增本计数，订阅方立即重读，
     *   不必等网络、也不必等编码（见 [Kdbx] 门面的 `sessionRevision`）。
     */
    private val revision = MutableStateFlow(0)
    val revisions: Flow<Int> = revision.asStateFlow()

    private fun notifyChanged() {
        revision.update { it + 1 }
    }

    /**
     * ★ 一次 mutate 提交新会话**之前**的旧会话（2026-11-08 真机「三份真相」修复）。
     *
     * `mutateLocked` 编码完就把新会话放进 [sessions]，但落盘在更外层的
     * `KdbxItemRepository.persist`：后者任一步抛异常，都会留下
     * 「内存里删了、磁盘/云端没动」的中间态 —— 列表停在旧快照、详情说不存在、
     * 回收站看得到、电脑上 KeePassXC 里条目还在。
     *
     * ⇒ 令牌随 `KdbxPendingWrite` 上浮：只有持有本次令牌的 `persist` 能回滚，
     *   不会把会话误退到更早的一版；每个库只留最新一个未消费快照，
     *   落盘成功即丢弃，明文不额外常驻。
     */
    private val rollbackSnapshots = ConcurrentHashMap<Long, RollbackSnapshot>()
    private val snapshotIds = AtomicLong(0)

    private class RollbackSnapshot(
        val vaultId: String,
        val session: KdbxSession,
    )

    fun put(vaultId: String, session: KdbxSession) {
        sessions[vaultId] = session
        notifyChanged()
    }

    fun get(vaultId: String): KdbxSession? = sessions[vaultId]

    /** 记下 mutate 之前的会话，返回可交给上层的回滚令牌。 */
    fun rememberForRollback(vaultId: String, session: KdbxSession): Long {
        // ⚠️ 每个库只保留**最新一个**未消费快照：正常链路是 mutate → persist 紧邻，
        //    persist 成功/失败都会消费掉它。若有路径绕过 persist 直接 mutate（导入、
        //    单测夹具），不清旧快照就会让整库明文一直挂在内存里。
        rollbackSnapshots.entries
            .filter { it.value.vaultId == vaultId }
            .map { it.key }
            .forEach { token -> rollbackSnapshots.remove(token) }
        val token = snapshotIds.incrementAndGet()
        rollbackSnapshots[token] = RollbackSnapshot(vaultId, session)
        return token
    }

    /** 落盘失败 ⇒ 恢复旧会话；令牌用过即失效，绝不重复回滚。 */
    fun rollback(token: Long, vaultId: String): Boolean {
        val snapshot = rollbackSnapshots.remove(token) ?: return false
        if (snapshot.vaultId != vaultId) return false
        sessions[vaultId] = snapshot.session
        notifyChanged()
        return true
    }

    /** 落盘成功 ⇒ 丢弃旧会话引用。 */
    fun discardRollback(token: Long) {
        rollbackSnapshots.remove(token)
    }

    fun close(vaultId: String) {
        sessions.remove(vaultId)
        rollbackSnapshots.entries
            .filter { it.value.vaultId == vaultId }
            .map { it.key }
            .forEach { token -> rollbackSnapshots.remove(token) }
        notifyChanged()
    }

    fun closeAll() {
        sessions.clear()
        rollbackSnapshots.clear()
        notifyChanged()
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
        // ★ #246 第五批：同一份文件字节 + 同一组凭据的 KDF 结果可以复用。
        //   必须在 forEach **外面**建一次：每个候选凭据都试同一个 provider，
        //   它们的 compositeKey 不同 ⇒ 各自独立命中/未命中（见缓存类 KDoc）。
        val kdfProvider = KdbxTransformKeyCache.providerFor(bytes)
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
                        kdfProvider = kdfProvider,
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
                    kdfProvider = KdbxTransformKeyCache.providerFor(bytes),
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
        is KdbxFormat.Empty -> KdbxFailure(KdbxOpenError.EmptyFile)
        is KdbxFormat.UnsupportedVersion ->
            KdbxFailure(KdbxOpenError.UnsupportedVersion(format.version))

        is KdbxFormat.Supported -> null
    }
}
