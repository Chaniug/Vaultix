/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * KDBX 会话（M2 阶段 A：只读）的**对外门面**。
 *
 * 为什么单独一层门面（而不是把 `KdbxOpener` / `KdbxSessionStore` 直接 public）：
 *  1. 内部类型里握着 kotpass 的 `KeePassDatabase`（**整库明文**）——它不该出现在
 *     任何跨模块签名里，否则一次 `println(session)` 就可能把明文写进日志；
 *  2. 上层（data:repository）只需要三件事：**能开吗 / 打开后有什么 / 锁掉**；
 *  3. 阶段 B 的写回会在门面里补 `save(...)`，上层调用点不必再改。
 *
 * 安全约定（与 Bitwarden 侧一致）：解出来的明文只活在内存，锁库即丢弃，绝不落盘。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem

/** 打开 KDBX 的失败原因（UI 据此给可执行文案）。 */
sealed interface KdbxOpenError {
    /** 文件不是 KDBX（选错文件 / 文件损坏）。 */
    data object NotKdbxFile : KdbxOpenError

    /** KDBX 版本不受支持（如 KDBX 2.x）。 */
    data class UnsupportedVersion(val version: String) : KdbxOpenError

    /** 密码 / keyfile 不正确（已尝试 [attempted] 种组合）。 */
    data class InvalidCredentials(val attempted: List<String>) : KdbxOpenError

    /** 文件读不到（URI 授权失效 / 文件被删除 / 存储不可用）。 */
    data class SourceUnavailable(val detail: String) : KdbxOpenError

    /** 其他异常（IO、损坏等），[detail] 为原始信息。 */
    data class Unknown(val detail: String) : KdbxOpenError
}

/**
 * 一次成功打开的内容快照（**不含数据库本体**）。
 *
 * 刻意只暴露映射后的领域模型 + 诊断计数：调用方拿不到 `KeePassDatabase`，
 * 也就没有把明文整库传出去的可能。
 */
data class KdbxUnlockedContent(
    val items: List<VaultItem>,
    val folders: List<VaultFolder>,
    /** 回收站里的条目数（本阶段不映射，明确告知用户而不是静默吞掉）。 */
    val recycleBinCount: Int,
    /** 成功打开所用的凭据形态（诊断用；不含任何密钥material）。 */
    val credentialLabel: String,
)

/**
 * KDBX 库源：把「一个 URI」变成「文件字节」。
 *
 * 抽象成接口的原因：`data:kdbx` 是**纯逻辑模块**（只依赖 core:*，不碰 Android 框架），
 * 因此它不知道 `ContentResolver`；由 `data:repository`（Android 侧）实现本接口后注入。
 * 附带好处：阶段 A 的全部单测都能直接喂 `ByteArray`，不需要真机存储。
 */
fun interface KdbxSource {
    /**
     * 读取该库的原始字节。
     *
     * @return 读不到返回 null（授权失效 / 文件不存在）—— 调用方据此给出
     *   「请重新选择文件」而不是「密码错误」。
     */
    fun read(sourceUri: String): ByteArray?
}

/**
 * KDBX 引擎门面（阶段 A：只读）。
 *
 * 会话按 vaultId 存在内存里；[lock] 即丢弃（明文的可达路径随之中断）。
 */
object Kdbx {
    /** 引擎标识（诊断日志用）。 */
    const val ENGINE_NAME: String = "kotpass"

    /**
     * 用主密码（可选 keyfile）打开 [sourceUri] 指向的库，并登记为 [vaultId] 的会话。
     *
     * 成功即覆盖同 id 的旧会话（换文件 / 换密码重开时不会残留旧明文）。
     */
    fun unlock(
        vaultId: String,
        sourceUri: String,
        password: String,
        keyFileUri: String?,
        source: KdbxSource,
    ): Result<KdbxUnlockedContent> {
        val bytes = source.read(sourceUri)
            ?: return Result.failure(
                KdbxFailure(KdbxOpenError.SourceUnavailable("无法读取该库文件，请重新选择")),
            )
        val keyFileBytes = keyFileUri
            ?.let { uri -> source.read(uri) }
            ?: null

        val opened = KdbxOpener.open(bytes = bytes, password = password, keyFileBytes = keyFileBytes)
        val session = opened.getOrElse { error ->
            return Result.failure(
                error as? KdbxFailure ?: KdbxFailure(KdbxOpenError.Unknown(error.message.orEmpty())),
            )
        }
        KdbxSessionStore.put(vaultId, session)
        return Result.success(
            KdbxUnlockedContent(
                items = session.content.items,
                folders = session.content.folders,
                recycleBinCount = session.content.recycleBinCount,
                credentialLabel = session.credentialLabel,
            ),
        )
    }

    /** 已登记的会话内容（未解锁 / 已锁返回 null）。 */
    fun contentOf(vaultId: String): KdbxUnlockedContent? = KdbxSessionStore.get(vaultId)?.let { session ->
        KdbxUnlockedContent(
            items = session.content.items,
            folders = session.content.folders,
            recycleBinCount = session.content.recycleBinCount,
            credentialLabel = session.credentialLabel,
        )
    }

    /** 该库是否已解锁（内存里有会话）。 */
    fun isUnlocked(vaultId: String): Boolean = KdbxSessionStore.get(vaultId) != null

    /** 全部已解锁的 KDBX 库 id（供仓储合并进「已解锁库」集合）。 */
    fun unlockedIds(): Set<String> = KdbxSessionStore.unlockedIds()

    /** 锁定单个库（丢弃明文）。幂等。 */
    fun lock(vaultId: String) = KdbxSessionStore.close(vaultId)

    /** 锁定全部（退出数据库 / 全量锁定）。幂等。 */
    fun lockAll() = KdbxSessionStore.closeAll()
}

/**
 * 携带结构化原因的异常（`Result` 只能带 Throwable，而 UI 需要区分
 * 「密码错」与「文件读不到」—— 二者的用户动作完全不同）。
 */
class KdbxFailure(val error: KdbxOpenError, cause: Throwable? = null) : Exception(
    when (error) {
        is KdbxOpenError.NotKdbxFile -> "不是 KDBX 文件"
        is KdbxOpenError.UnsupportedVersion -> "不支持的 KDBX 版本 ${error.version}"
        is KdbxOpenError.InvalidCredentials -> invalidCredentialMessage(error.attempted)
        is KdbxOpenError.SourceUnavailable -> error.detail
        is KdbxOpenError.Unknown -> error.detail
    },
    cause,
)
