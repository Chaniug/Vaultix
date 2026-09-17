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
import java.io.File

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

    /**
     * 库未解锁 —— 写回的**前提**不成立。
     *
     * 单独成一个原因而不是复用 [Unknown]：它的用户动作很明确
     * （「先解锁这个库」）而 [Unknown] 只能是「未知错误，重试看看」。
     */
    data object NotUnlocked : KdbxOpenError
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
     *
     * @param keyFileBytes keyfile **内容**（优先于 [keyFileUri]）。快速解锁场景下
     *   keyfile 是从包裹物里解出来的字节，**没有 URI 可读**；若为了走 URI 参数而把
     *   它落成临时文件，等于把明文写盘 —— 与「明文绝不落盘」的约定直接冲突。
     *   因此这里直接支持字节输入：**有字节就用字节，没有才去读 URI**。
     */
    fun unlock(
        vaultId: String,
        sourceUri: String,
        password: String,
        keyFileUri: String?,
        source: KdbxSource,
        keyFileBytes: ByteArray? = null,
    ): Result<KdbxUnlockedContent> {
        val bytes = source.read(sourceUri)
            ?: return Result.failure(
                KdbxFailure(KdbxOpenError.SourceUnavailable("无法读取该库文件，请重新选择")),
            )
        // 字节优先：包裹物解出的 keyfile 没有可读的 URI。
        val resolvedKeyFileBytes = keyFileBytes
            ?: keyFileUri?.let { uri -> source.read(uri) }
            ?: null

        val opened = KdbxOpener.open(bytes = bytes, password = password, keyFileBytes = resolvedKeyFileBytes)
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

    /**
     * **只校验凭据、不开库**：这组主密码 / keyfile 能不能打开 [sourceUri]？
     *
     * ## 为什么需要一个"只验不开"的入口（2026-09-16）
     *
     * 「一个 PIN 打开多个库」要求在设置 PIN 时就为 KDBX 组好信封，而信封里躺的是
     * 「主密码 + keyfile」。**必须先确认这组凭据真的能开库，再包进去** ——
     * 否则会得到「设置成功、但躺的是错密码」，用户要到下次解锁才发现
     * 「PIN 明明对了却打不开库」，那时已经分不清是 PIN 错还是密码错。
     *
     * 但校验又**不能**真的去开库：设置 PIN 时用户只是在配解锁方式，
     * 并未要求「把这个库也打开并读进内存」——真开了会：
     * 1. 把明文内容拉进内存（用户没要求，且页面根本没准备展示它）；
     * 2. 覆盖该库可能已存在的会话（用户可能正开着，却在为**另一个**库设 PIN）；
     * 3. 在「多库配齐」里对每个 KDBX 库各开一次，成本与副作用都白付。
     *
     * ⇒ 本方法只做「读文件 → 尝试凭据」两步，**不碰 [KdbxSessionStore]**。
     *   副作用仅限读完即弃的字节，与调用方在读文件这件事上完全一致。
     *
     * ⚠️ **不要把它实现成 `unlock()` 包一层 try**：那正是上面第 2 条要避免的
     * 「副作用泄漏到校验路径」。校验必须是**无副作用**的。
     *
     * @return 凭据对不对。文件读不到 / 不是 KDBX 文件等也一律算"不通过"——
     *   调用方关心的是「能不能用这组凭据开库」，具体原因不影响它要不要包裹。
     */
    fun verify(
        sourceUri: String,
        password: String,
        keyFileUri: String?,
        source: KdbxSource,
    ): Boolean {
        val bytes = source.read(sourceUri) ?: return false
        val keyFileBytes = keyFileUri?.takeIf { it.isNotBlank() }?.let { uri -> source.read(uri) }
        return KdbxOpener.open(
            bytes = bytes,
            password = password,
            keyFileBytes = keyFileBytes,
        ).isSuccess
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

    /**
     * 把 [vaultId] 的会话**编码并安全落盘**到 [target]（阶段 B：写回）。
     *
     * ## 调用前提
     *
     * - 该库必须**已解锁**（[isUnlocked]）—— 没有会话就没有可写的内容，
     *   更没有打开它时用的凭据（写回必须用同一组，见 [KdbxSession.credentials]）。
     * - [target] 所属目录必须可写；实现方负责把网盘/SAF 的差异挡在外面。
     *
     * ## 这个入口做了三件"不做就会出事"的事
     *
     * 1. **往返自检**（[KdbxRoundTrip]）—— 编码后立刻解码回来逐字段比对，
     *    不一致就**不落盘**。编码器对未知 XML 标签是静默丢弃的，
     *    没有这一步的话，"写坏"要到用户下次打开才发现（甚至发现不了）。
     * 2. **原子替换**（[KdbxAtomicWriter]）—— 临时文件 → fsync → rename。
     *    半写状态对 KDBX 而言就是**整库损坏**。
     * 3. **`.kdbx.bak` 备份** —— 防"写完了但内容是坏的"（自检挡不住的编码 bug）。
     *
     * ## 为什么在门面而不是调用方
     *
     * 这三件事是**写回的正确性下限**，不是可选优化。放在门面里意味着
     * 「凡是走 `Kdbx.save` 的路径都安全」，调用方不可能忘掉其中任何一步。
     *
     * @return 成功时给出落盘信息（含备份路径与保真度提示）。
     */
    fun save(vaultId: String, target: File): Result<KdbxSaveReport> {
        val session = KdbxSessionStore.get(vaultId)
            ?: return Result.failure(KdbxFailure(KdbxOpenError.NotUnlocked))

        val checked = KdbxRoundTrip.verify(
            database = session.database,
            credentials = session.credentials,
        )
        if (!checked.isSafeToWrite) {
            return Result.failure(KdbxWriteFailure.RoundTripFailed(checked))
        }

        val backup = runCatching { KdbxAtomicWriter.write(target, checked.bytes) }
            .getOrElse { error ->
                // 落盘失败时**务必清掉临时文件** —— 它里面是完整的密钥库。
                runCatching { KdbxAtomicWriter.discardTemp(target) }
                return Result.failure(
                    KdbxWriteFailure.IoError(error.message.orEmpty(), error),
                )
            }

        return Result.success(
            KdbxSaveReport(
                targetPath = target.path,
                backupPath = backup?.path,
                bytesWritten = checked.bytes.size,
                entryCount = checked.entryCountAfter,
                notes = KdbxFidelity.inspect(session.database) + checked.fidelityLosses,
            ),
        )
    }

    /** 锁定单个库（丢弃明文）。幂等。 */
    fun lock(vaultId: String) = KdbxSessionStore.close(vaultId)

    /** 锁定全部（退出数据库 / 全量锁定）。幂等。 */
    fun lockAll() = KdbxSessionStore.closeAll()

    /**
     * 把 [vaultId] 的会话编码并**经 [source] 写入**（阶段 B × 网盘）。
     *
     * 与本地 [save] 的关系：**编码与自检完全共用**（同一套 [KdbxRoundTrip] 逻辑，
     * 不存在"网盘走的另一条编码路径"——那样的分叉迟早漂移）。区别只在
     * **字节的最终去处**：本地是原子替换文件，这里是交给一个 [KdbxFileSource]。
     *
     * ## ★ 冲突判定（这是网盘与本地最关键的不同）
     *
     * 写之前先 [KdbxFileSource.stat]，把**拿到的那一刻的版本**作为条件写的
     * `expectedVersion` 传给 [KdbxFileSource.write]。含义：
     * - 服务端说"还是这版" ⇒ 写成功；
     * - 服务端说"已经变了" ⇒ 抛 [KdbxFileConflictException] ⇒ 这里包成
     *   [KdbxWriteFailure.Conflict] 返回，**远端一个字节都没被覆盖**。
     *
     * ⚠️ 为什么 stat 与 write 之间仍有窗口、却不担心：**判定权在服务端**。
     * 我们传的是"我以为的最新版"，服务端拿它原子地比对；窗口里发生的改动
     * 会让请求收到 412 而不是覆盖 —— 这正是方案 §6.2 坑 1 的要点
     * （TOCTOU 只能靠服务端条件写消除，客户端"先查再写"消除不了）。
     *
     * @param source 该库的文件来源（本地 SAF / OneDrive / WebDAV）。
     * @param expectedVersion **调用方手上那一版**的令牌（通常是最后一次同步时记下的）。
     *   传 null 表示"不做并发保证" —— 但**只要来源支持条件写，本方法仍会自己
     *   stat 一次并采用拿到的版本**，不会轻易退化成无条件覆盖。
     * @param force ★ **无条件强写**（忽略 [expectedVersion]，不问远端现状）。
     *
     *   ## 这个开关是干什么的、以及为什么必须显式
     *
     *   只有一种合法用途：**用户在冲突对话框里明确选了「用本地覆盖远端」**。
     *   那一刻用户已经看到"远端有别人的改动、选了会丢"的警告并确认了 ——
     *   此时再去做条件检查反而**永远失败**（远端确实变了，条件必然不满足），
     *   于是用户点了确认却什么也没发生，只能反复点。
     *
     *   ⚠️ 所以它与 `expectedVersion = null` 是**两件不同的事**：
     *   `null` 只表示"我没带基线"，本方法仍会 stat 一次并采用拿到的版本
     *   （即仍是乐观并发）；[force] 才是真正的"不看、直接盖"。
     *   把这两件事混成一个 null 会让"忘了传基线"悄悄变成"强行覆盖远端"。
     */
    suspend fun saveVia(
        vaultId: String,
        source: KdbxFileSource,
        expectedVersion: String? = null,
        force: Boolean = false,
    ): Result<KdbxSaveReport> {
        val session = KdbxSessionStore.get(vaultId)
            ?: return Result.failure(KdbxFailure(KdbxOpenError.NotUnlocked))

        val checked = KdbxRoundTrip.verify(
            database = session.database,
            credentials = session.credentials,
        )
        if (!checked.isSafeToWrite) {
            return Result.failure(KdbxWriteFailure.RoundTripFailed(checked))
        }

        // 拿"我以为的最新版"：调用方给了就用它，没给则现取一次。
        //
        // ⚠️ 顺序很重要：**先 stat 再 write**，且 stat 之后绝不重新读内容
        // （重读就变成"读-改-写"三步，窗口反而更大）。这里 stat 只为拿令牌。
        //
        // ⚠️ force = true 时**跳过 stat** —— 用户已经确认要覆盖，再 stat 一次
        //    只会把"远端变成什么样"读进来当条件，必然不满足（就是这个条件挡住的）。
        val currentVersion = when {
            force -> null
            expectedVersion != null -> expectedVersion
            else -> runCatching { source.stat() }.getOrNull()?.versionToken
        }

        val written = runCatching {
            source.write(bytes = checked.bytes, expectedVersion = currentVersion, force = force)
        }.getOrElse { error ->
            // ★ 冲突要单独归类：它不是"网络不好，重试就行"，而是**必须用户拍板**的状态。
            //   混在 IoError 里的话 UI 只会说"保存失败请重试"，而重试永远失败。
            if (error is KdbxFileConflictException) {
                return Result.failure(KdbxWriteFailure.Conflict(error.currentVersion, error))
            }
            return Result.failure(KdbxWriteFailure.IoError(error.message.orEmpty(), error))
        }

        return Result.success(
            KdbxSaveReport(
                targetPath = source.javaClass.simpleName,
                backupPath = null,
                bytesWritten = checked.bytes.size,
                entryCount = checked.entryCountAfter,
                notes = KdbxFidelity.inspect(session.database) + checked.fidelityLosses,
                // 记住写入后服务端给的新版本 —— 下一次条件写要用它，否则会自己跟自己冲突。
                writtenVersion = written.versionToken,
            ),
        )
    }
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
        is KdbxOpenError.NotUnlocked -> "请先解锁该密码库"
    },
    cause,
)

/**
 * 写回的结果报告。
 *
 * 刻意**不返回字节**（字节已经在落盘过程中用掉了）：调用方需要的是
 * 「写成功了吗 / 写去哪了 / 有没有需要告诉用户的保真度提示」。
 */
data class KdbxSaveReport(
    val targetPath: String,
    /** 备份路径；null = 本次没有产生备份（原文件不存在，或备份失败，或来源是网盘）。 */
    val backupPath: String?,
    val bytesWritten: Int,
    val entryCount: Int,
    /** 保真度提示（`KPEX_*` 存在、往返丢失等）。**空不代表绝对无损，只代表没察觉到**。 */
    val notes: List<KdbxFidelityNote>,
    /**
     * ★ 写入后服务端的**新**版本令牌（仅 [Kdbx.saveVia] 会填）。
     *
     * 为什么要带出来：下一次条件写必须用**这个**，而不是写之前那个。
     * 用旧的去写会被服务端判成冲突 —— 也就是**自己跟自己冲突**，
     * 用户会看到一个莫名其妙的"远端已变化"。本地 [Kdbx.save] 恒为 null。
     */
    val writtenVersion: String? = null,
)

/**
 * 写回失败。
 *
 * 用**密封类**而不是一个字符串 message：三种失败的**用户动作完全不同** ——
 * 未解锁要去解锁、往返失败要报告 bug、IO 失败可以重试。
 * 混成一句话的话，UI 只能给出"保存失败"这种没有信息量的提示。
 */
sealed class KdbxWriteFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 库没解锁（没有会话可写）。 */
    class NotUnlocked : KdbxWriteFailure("请先解锁该密码库再保存")

    /**
     * 往返自检不通过 —— **这是 bug，不是用户的错**。
     *
     * 携带 [result] 以便上报（哪几个条目、哪个字段丢了是定位的关键信息）。
     */
    class RoundTripFailed(val result: KdbxRoundTripResult) : KdbxWriteFailure(
        "保存前的自检未通过，为保护数据已取消写入（${result.mismatches.joinToString("；")}）",
    )

    /** 落盘失败（磁盘满 / 权限 / 目标被占用 / 网络断）。 */
    class IoError(detail: String, cause: Throwable? = null) : KdbxWriteFailure(
        detail.ifBlank { "写入文件失败" },
        cause,
    )

    /**
     * ★ **远端已被改动**，本次写入被服务端条件写拒绝 —— 用户数据一个字都没被覆盖。
     *
     * 这是**唯一一种"失败了好事"**的结果：说明条件写生效了。
     * 上层见到它要做的不是重试（**重试会一直失败**），而是转 `CONFLICT` 状态
     * 让用户拍板（用远端覆盖本地 / 用本地覆盖远端 / 两边都留）。
     *
     * @param currentVersion 远端**现在**的版本令牌（null = 来源拿不到）。
     *   用户选"用远端覆盖本地"时，要拉的就是这一版。
     */
    class Conflict(val currentVersion: String?, cause: Throwable? = null) : KdbxWriteFailure(
        "远端已被其他设备修改，为保护数据已取消写入。请选择：用远端覆盖本地 / 用本地覆盖远端 / 保留两份",
        cause,
    )
}
