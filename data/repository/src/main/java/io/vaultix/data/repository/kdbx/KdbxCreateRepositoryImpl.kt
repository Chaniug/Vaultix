/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * `KdbxCreateRepository` 的实现 —— **新建空白 KDBX 库**（M2 阶段 B · 批次 W0）。
 *
 * 它做的三件事，顺序不能换：
 *
 * ```
 *   ① 解析来源（targetUri → KdbxFileSource）      失败 ⇒ 还没碰过任何文件
 *   ② Kdbx.createVault（造库 → 落盘 → 登记会话）   失败 ⇒ 可能留下半截文件（见下）
 *   ③ 登记 vaults 行（origin 回写 + 默认库兜底）
 * ```
 *
 * ## ★ 为什么第 ③ 步必须在 `createVault` **之后**，而不是之前
 *
 * 反过来的话，`createVault` 失败（keyfile 读不到 / 目标不可写 / KDF 参数被拒）就会留下
 * **一行指向不存在库的记录** —— 用户看到库列表里多了一项，点进去永远报错，
 * 而列表里**没有"删掉它"的入口**（那是另一个功能）。先建文件、后登记，
 * 最坏情况只是"文件建好了但没登记"（用户可以在文件管理器里看到并自行删除）。
 *
 * ⚠️ 但**不能因此把 ② 说成"原子"**：`Kdbx.createVault` 内部是「先编码（纯内存，
 * 失败时没有文件）→ 再 write」。真正的写入失败（磁盘满 / 授权被撤）确实可能留下半截
 * 文件。这一点由 `SafKdbxFileSource.write` 的写后读回校验来**发现**（它会抛），
 * 而不是假装不存在 —— 见那个类的 KDoc 的说明。
 *
 * ## ★ origin 回写（第 ③ 步里最容易被忽略的一格）
 *
 * `targetUri` 是 SAF `CreateDocument` 刚给我们的 URI，而**部分 provider**
 * （Downloads 之类）返回的是**临时** URI、最终路径另有一个。若把 `targetUri` 直接
 * 当成库 id 记下来，就会出现「库在列表里，却怎么点都打不开」——
 * 而且只在**那些 provider 上**复现（在 AOSP Files 上一切正常），排查起来最贵。
 *
 * ⇒ 一律取 `Kdbx` 打开/写入后**实际用的那个** `source` 的 id 作为 origin。
 *   本文件的做法是**先用来源对象自身**（`stat().remoteId`）校对一次；
 *   拿不到 stat 时退回 `targetUri`（此时诚实地说"我们只知道这个"，
 *   而不是编一个看起来更对的）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.repository.KdbxSessionFlow
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.domain.KdbxCreateRepository
import io.vaultix.domain.NewKdbxVaultOutcome
import io.vaultix.domain.UnlockResult
import io.vaultix.model.VaultKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 新建 KDBX 库的落库实现。
 *
 * @param vaultDao 登记 `vaults` 行（与 [io.vaultix.data.repository.VaultRepositoryImpl.addKdbxVault]
 *   **同一张表、同一套字段** —— 两条路必须落成同样的行，否则会出现
 *   「列表里有但打不开」或「打得开但列表里没有」）。
 * @param fileSourceResolver 「origin ⇒ 文件来源」的唯一判据表。
 *   ⚠️ 与解锁路径共用同一个接口，**不另写一份**解析（见该接口的 KDoc：
 *   2026-09-17 就是因为读、写走了两套来源体系，导致网盘库加不进来也解锁不了）。
 * @param preferences keyfile 记录 + 「首个库设为默认库」。
 * @param kdbxSessions 会话建立后自增代次，让库列表立刻看到新库
 *   （`Kdbx.createVault` 已把会话登记好了，但不 bump 的话界面不会重读）。
 */
@Singleton
class KdbxCreateRepositoryImpl @Inject constructor(
    private val vaultDao: VaultDao,
    private val fileSourceResolver: KdbxFileSourceResolver,
    private val preferences: VaultixPreferences,
    private val kdbxSessions: KdbxSessionFlow,
) : KdbxCreateRepository {

    override suspend fun createVault(
        targetUri: String,
        displayName: String,
        masterPassword: String,
        keyFileBytes: ByteArray?,
    ): NewKdbxVaultOutcome {
        if (targetUri.isBlank()) {
            return NewKdbxVaultOutcome.Failed(UnlockResult.Unknown("未选择保存位置"))
        }

        // ① 解析来源。拿不到 ⇒ 还没碰过任何文件，直接失败是安全的。
        //    ⚠️ 文案与 `VaultRepositoryImpl.unlockKdbxInternal` 的同类失败**对齐**
        //    （"这个库还没有可用的文件来源…"）—— 同一个成因在两处说两样话，
        //    用户会以为是两个不同的问题。
        val source = fileSourceResolver.fileSourceFor(targetUri)
            ?: return NewKdbxVaultOutcome.Failed(
                UnlockResult.Unknown("这个保存位置还没有可用的文件来源，请重新选择"),
            )

        // ★ 先问一次"这个文件实际是谁" —— 见文件头关于 origin 回写的说明。
        //   ⚠️ 这里**允许失败**（有些 provider 刚创建的文件 stat 不到）：
        //   失败只是让我们退回 targetUri，不影响建库本身。
        val resolvedOrigin = runCatching { source.stat().remoteId }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: targetUri

        // ② 造库 + 落盘 + 登记会话（凭据一致性的唯一责任方，见 data:kdbx 的 KDoc）。
        val created = Kdbx.createVault(
            vaultId = resolvedOrigin,
            source = source,
            name = displayName,
            password = masterPassword,
            keyFileBytes = keyFileBytes,
        )
        created.fold(
            onSuccess = { /* 内容无关紧要：库刚建好是空的 */ },
            onFailure = { error ->
                // ⚠️ 失败原因**原样**交给上层（`classifyKdbxError` 那套分类只有一份），
                //    不在这里二次翻译 —— 二次翻译必然与解锁路径的文案漂移。
                return NewKdbxVaultOutcome.Failed(
                    UnlockResult.Unknown(error.message.orEmpty()),
                )
            },
        )

        // ③ 登记 vaults 行。
        val now = System.currentTimeMillis()
        val existing = vaultDao.get(resolvedOrigin)
        vaultDao.upsert(
            VaultEntity(
                id = resolvedOrigin,
                kind = VaultKind.KDBX.name,
                displayName = displayName.ifBlank { DEFAULT_DISPLAY_NAME_KDBX },
                origin = resolvedOrigin,
                account = null,
                revisionDate = existing?.revisionDate,
                createdAt = existing?.createdAt ?: now,
            ),
        )

        // 与 addKdbxVault 同一套收尾（两条路落成同样的行，见类的 KDoc）：
        // 「首个库设为默认」只在**真的新增**时做，重复创建同一路径不该有副作用。
        if (existing == null) {
            preferences.trySetDefaultVaultIfAbsent(resolvedOrigin)
        }
        // 会话已登记（createVault 内），这里只负责让界面重读。
        kdbxSessions.bump()

        return NewKdbxVaultOutcome.Created(vaultId = resolvedOrigin, origin = resolvedOrigin)
    }

    private companion object {
        /** 与 `VaultRepositoryImpl.DEFAULT_DISPLAY_NAME_KDBX` 同值（两处各一份是刻意的：
         *  那个是 private 常量，为共享它把常量提到公开位置反而会给"又一个共享常量集"开门）。 */
        const val DEFAULT_DISPLAY_NAME_KDBX = "KeePass 数据库"
    }
}
