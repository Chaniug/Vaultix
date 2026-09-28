/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.repository.kdbx.KdbxFileSourceResolver
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.database.dao.VaultDao
import io.vaultix.domain.LocalUnlockEnrollOutcome
import io.vaultix.domain.LocalUnlockPrepareOutcome
import io.vaultix.domain.LocalUnlockPreparedEnrollment
import io.vaultix.model.VaultKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 快速解锁「生效范围」（房间信封）的**备料与落盘**。
 *
 * 房子化（2026-09-28 定稿）后的职责边界：
 *
 * | 事 | 谁 |
 * |---|---|
 * | 房钥匙 / 门锁信封 / 房间信封的钥匙学 | [HouseKeyStore] |
 * | 「这个库往房间信封里包什么明文」+ 逐库校验 | 本类（[prepareForVaults]） |
 * | 逐库软封装落盘 + 范围镜像同步 | 本类（[sealRoomsForVaults]） |
 * | 向导编排（问密码、开门锁的顺序） | app 层控制器 |
 *
 * ## 与旧模型（每库信封）的关键差异
 *
 * - **勾库不再碰指纹**：房间信封是纯软件封装（[HouseKeyStore.sealRoom]），
 *   没有任何系统认证参与 ——「备料 → 弹指纹 → 认证后连包」的三段式整体消失，
 *   旧 [commitForVaults] 一把 cipher 连包 N 库的路径（H1 病灶）结构性不存在；
 * - 备料产物 [Prepared.plaintext] **恒非空**：旧模型里 Bitwarden 的密钥留到
 *   「认证后」取，是因为认证窗口里用户可能锁库；新模型没有认证窗口，
 *   取与封同一个时刻完成，锁库边角由 [sealOne] 如实报 `Failed`。
 *
 * ## 仍然保留的两条老纪律
 *
 * - **先校验后包裹**：KDBX 先 [Kdbx.verify] 真验一次才组装明文 —— `sealRoom`
 *   只负责封字节、不管字节对不对，先包后校会得到「启用成功、但躺的是错密码」；
 * - **明文用完即擦**：[Prepared] 实现 `AutoCloseable`，[sealRoomsForVaults]
 *   无论成败逐个 `close()`（且 [HouseKeyStore.sealRoom] 自带 `finally` 清零，
 *   明文副本不落任何第二处）。
 *
 * ## 为什么不能复用单库路径（历史教训，路径已于房子化删除）
 *
 * 旧单库路径的暂存位是**一个不按库分的 `ByteArray?`**：一次勾选多个 KDBX 库时
 * 后一个库的明文会覆盖前一个，前者的信封里躺的是别人的密码。该路径
 * （`prepareKdbxEnroll` / `commitKdbxEnroll` / `discardKdbxEnroll`）连同暂存槽
 * 于 2026-09-28 房子化时整体删除 —— app 层本就零调用。
 */
@Singleton
class LocalUnlockEnrollment @Inject constructor(
    /**
     * 内存里的对称密钥（Bitwarden 侧房间信封里包的就是它派生的 64B full key）。
     *
     * ⚠️ 只依赖会话管理器，**不依赖 [VaultRepositoryImpl]** —— 反过来会让两者成环。
     */
    private val sessions: VaultSessionManager,
    private val houseKeyStore: HouseKeyStore,
    private val vaultDao: VaultDao,
    private val preferences: VaultixPreferences,
    /**
     * 「origin ⇒ 文件来源」的解析。
     *
     * ⚠️ 来源解析必须走与读写两侧**同一张**判别表（[KdbxFileSourceResolver] 的
     * KDoc：那张表只能有一份）—— 曾各自维护一份导致网盘库在这里校验永远失败，
     * 用户看到的却是"主密码不正确"。
     */
    private val kdbxFileSources: KdbxFileSourceResolver,
) {

    /**
     * 一个库「要包进房间信封」的备料。
     *
     * 实现 [LocalUnlockPreparedEnrollment]（域层接口）而不是自带一套公开属性：
     * 控制器只依赖 `domain` 的类型，不会反向依赖 `data.repository`。
     *
     * [close] = 明文用完即擦：KDBX 这份里躺着主密码，没理由让它活到 GC。
     * 调用方必须保证 `close()` 一定被调到（[sealRoomsForVaults] 内部已保证；
     * 直接持有备料的调用方用 `use { }` 或 finally）。
     */
    class Prepared internal constructor(
        override val vaultId: String,
        override val displayName: String,
        /** 待封装的明文（恒非空，见类 KDoc）。 */
        internal val plaintext: ByteArray,
    ) : LocalUnlockPreparedEnrollment {
        override fun close() {
            plaintext.fill(0)
        }
    }

    /**
     * 备料：逐库校验凭据 + 组装待封装明文（**不碰任何门锁 / 指纹**）。
     *
     * 逐个库独立判定（`associate`），**一个库失败不影响其它库** —— 这是刻意保留的
     * 真实状态：用户可能勾了 3 个库、其中 1 个密码不对，另外 2 个没有理由不生效。
     *
     * KDBX 校验走 [Kdbx.verify]（**只验不开库**、不碰 `KdbxSessionStore`）。
     *
     * @param passwordOf 为每个 KDBX 库**单独**取一次它的主密码。
     *   返回 null / 空白 ⇒ 视为**用户跳过该库**（[LocalUnlockPrepareOutcome.Skipped]）。
     *   Bitwarden 库**不会**调用它（房间信封里包的是会话密钥，不需要密码）。
     */
    suspend fun prepareForVaults(
        vaultIds: List<String>,
        passwordOf: suspend (vaultId: String) -> String?,
    ): Map<String, LocalUnlockPrepareOutcome> = withContext(Dispatchers.IO) {
        vaultIds.associateWith { vaultId -> prepareOne(vaultId, passwordOf) }
    }

    /** 单个库的备料。 */
    private suspend fun prepareOne(
        vaultId: String,
        passwordOf: suspend (vaultId: String) -> String?,
    ): LocalUnlockPrepareOutcome {
        val row = vaultDao.get(vaultId)
            ?: return LocalUnlockPrepareOutcome.Failed("本地不存在该库")
        return when (VaultKind.fromName(row.kind)) {
            VaultKind.BITWARDEN -> {
                // 会话不在 ⇒ 没有密钥可包，如实报（不拿空信封糊过去）。
                val key = sessions.keyOf(vaultId)
                    ?: return LocalUnlockPrepareOutcome.Failed("该库未解锁，请先用主密码打开它")
                LocalUnlockPrepareOutcome.Ready(
                    Prepared(vaultId, row.displayName, buildFullKey(key)),
                )
            }
            VaultKind.KDBX -> {
                val masterPassword = passwordOf(vaultId)
                if (masterPassword.isNullOrBlank()) {
                    // 用户跳过（没输密码）⇒ 如实报，不当成功（假状态）。
                    LocalUnlockPrepareOutcome.Skipped
                } else {
                    prepareKdbx(row.id, row.displayName, row.origin, masterPassword)
                }
            }
            null -> LocalUnlockPrepareOutcome.Failed("无法识别该库类型")
        }
    }

    /**
     * KDBX 的备料：先校验，过了才把「主密码 + keyfile」组装好。
     */
    private suspend fun prepareKdbx(
        vaultId: String,
        displayName: String,
        originUri: String,
        masterPassword: String,
    ): LocalUnlockPrepareOutcome {
        val source = kdbxFileSources.fileSourceFor(originUri)
            ?: return LocalUnlockPrepareOutcome.SourceUnavailable("这个库还没有可用的文件来源")
        val keyFileUri = keyFileUriOf(vaultId)
        // keyfile 与库文件走同一套解析；读不到会是 null，届时 `verify` 自然验不过
        // （不是降级成"仅主密码"，而是**如实**：少一个 keyfile 字节就是开不了）。
        val keyFileBytes = kdbxFileSources.readBytes(keyFileUri)
        // ★ 先校验、后组装。`sealRoom` 只负责封字节、不管字节对不对。
        val verified = Kdbx.verify(
            source = source,
            password = masterPassword,
            keyFileBytes = keyFileBytes,
        )
        if (!verified) {
            // 校验不过有两种可能：密码错，或文件读不到。分辨它们对用户很重要 ——
            // 前者该重输，后者该重新选文件（读不到时 `KdbxFileSource.read` 会抛）。
            val readable = runCatching { source.read() }.isSuccess
            return if (readable) {
                LocalUnlockPrepareOutcome.InvalidCredentials
            } else {
                LocalUnlockPrepareOutcome.SourceUnavailable("读不到该库文件，请重新选择")
            }
        }
        // ⚠️ keyfile 读不出来时**拒绝**而不是降级成"仅主密码"：keyfile 不是可选装饰，
        //    少它一个字节就是开不了。静默降级会让信封里躺一组永远解不开的凭据。
        return LocalUnlockPrepareOutcome.Ready(
            Prepared(
                vaultId = vaultId,
                displayName = displayName,
                plaintext = KdbxUnlockPayload.encode(masterPassword, keyFileBytes),
            ),
        )
    }

    /**
     * 落盘：把备好的明文逐库软封装成房间信封（纯软件，**不碰指纹、不收 cipher**）。
     *
     * ## 前置：房钥匙必须在内存（至少一把门锁已开）
     *
     * 定稿 §5 顺序约束 —— 房间信封只在至少一把门锁已存在时创建，否则房钥匙从未
     * 被包裹过，进程一死房间信封即成孤儿。不满足时**全部库**如实报失败
     * （不是静默跳过，那正是「假成功」）。
     *
     * ## 逐库独立成败
     *
     * 某个库封装失败（信封损坏等）只回退**该库**，绝不牵连其它库。
     *
     * ## 范围镜像同步
     *
     * 成功的库同步进 `QUICK_UNLOCK_SCOPE`（**真源仍是房间信封本身**，偏好键只是
     * UI 响应式镜像 —— 见 `VaultixPreferences.QUICK_UNLOCK_SCOPE` 的 KDoc）。
     *
     * ⚠️ 无论成败，每个 [Prepared] 的明文都会被擦掉（`close()`）。
     */
    suspend fun sealRoomsForVaults(
        prepared: List<LocalUnlockPreparedEnrollment>,
    ): Map<String, LocalUnlockEnrollOutcome> = withContext(Dispatchers.IO) {
        if (!houseKeyStore.isUnlocked) {
            // 全部如实失败 + 备料明文统一擦除（KDBX 那些含主密码字节）。
            prepared.forEach { it.close() }
            return@withContext prepared.associate {
                it.vaultId to LocalUnlockEnrollOutcome.Failed("请先开启并解锁一把门锁（指纹或 PIN）")
            }
        }
        val outcomes = prepared.associate { unit ->
            val outcome = try {
                // 🔴 强转而非 `when`：接口非 sealed（跨模块无法构成 sealed 层级），
                //    非单分支 when 不穷尽 ⇒ CI 报错。唯一构造入口是 [prepareForVaults]，
                //    真出现第二个实现会立刻 ClassCastException（响亮失败），
                //    好过静默走 else（旧 commitForVaults 的同款取向）。
                val target = unit as Prepared
                sealOneSafely(target)
            } finally {
                unit.close()
            }
            unit.vaultId to outcome
        }
        syncScopeMirror(outcomes)
        outcomes
    }

    /** 单个库的软封装落盘。 */
    private suspend fun sealOne(unit: Prepared): LocalUnlockEnrollOutcome {
        // plaintext 所有权转移给 sealRoom（内部 finally 清零）。
        houseKeyStore.sealRoom(unit.vaultId, unit.plaintext)
        return LocalUnlockEnrollOutcome.Enrolled
    }

    /**
     * [sealOne] 的失败归类：软封装的异常（GCM/存储层）吞成逐库 `Failed`。
     *
     * 用 `runCatching` 形态与项目既有取向一致（detekt `TooGenericExceptionCaught`
     * 只看 `catch` 子句；这里确实需要接住任意异常再如实归类）。
     */
    private suspend fun sealOneSafely(unit: Prepared): LocalUnlockEnrollOutcome =
        runCatching { sealOne(unit) }.getOrElse { error ->
            LocalUnlockEnrollOutcome.Failed(error.message ?: "房间信封封装失败")
        }

    /** 把成功的库并入范围镜像（真源是房间信封；空集语义见 `quickUnlockScope`）。 */
    private suspend fun syncScopeMirror(outcomes: Map<String, LocalUnlockEnrollOutcome>) {
        val succeeded = outcomes.filterValues { it == LocalUnlockEnrollOutcome.Enrolled }.keys
        if (succeeded.isEmpty()) return
        val current = preferences.quickUnlockScope().first()
        preferences.setQuickUnlockScope(current + succeeded)
    }

    /** 该库的 keyfile URI（按库独立存于偏好；读失败当作"没配 keyfile"）。 */
    private suspend fun keyFileUriOf(vaultId: String): String? =
        runCatching { preferences.kdbxKeyFileUri(vaultId).first() }.getOrNull()
}
