/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.model.FolderRequest
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.FolderEntity
import io.vaultix.domain.FolderNamePolicy
import io.vaultix.domain.FolderRepository
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultKind
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * 文件夹（Bitwarden folder / KDBX 分组）的读写实现。
 *
 * ## 读路径
 *
 * 与 [ItemRepositoryImpl.observeItems] 同一链路形态：Room 密文快照 ×
 * 解锁状态 → 会话密钥解密。解密在注入的 crypto 调度器上执行；
 * 个别损坏的名称跳过（列表可浏览优先），未解锁返回空列表。
 *
 * ## 2026-10-13 起 KDBX 库也走这里（A2）
 *
 * 此前本类**只绑 Room** ⇒ KDBX 库的分组整体不可见：表单的文件夹下拉框恒为空、
 * 「按文件夹分组」视图里所有条目都落"无文件夹"、JSON 导出丢分组、
 * 导入时全部落根组。而 `KdbxMappedContent.folders` 其实早就映射好了，
 * 只差一个消费方 —— 于是补上按库类型的分流（读侧判据与
 * [ItemRepositoryImpl] 逐字同构：每次订阅重新解析种类，不引入缓存真相源）。
 *
 * ## 2026-10-27：写路径（#199）
 *
 * Bitwarden 侧**直连服务端**（理由见 [FolderRepository] 的 KDoc：入队会被
 * `pruneRemovedRows` 当成"服务端已删"而清掉）；KDBX 侧转交 [KdbxItemRepository]，
 * 与条目写共用同一个落盘咽点（往返自检 / 写回基线 / `bump`）。
 *
 * ### 🔴 顺序：**先远端、后本地**
 *
 * 远端成功才写本地。反过来（本地先行）在远端失败时会留下"本地有、远端没有"的幽灵行 ——
 * 下一次同步它会被 prune 掉，用户看到自己刚建的文件夹**凭空消失**。
 * 而"远端成功、本地没写"最坏也只是重启后短暂看不到，下次同步就补回来了。
 * ⇒ 两条失败路径的代价不对称，顺序不能反。
 */
@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: FolderDao,
    private val vaultDao: VaultDao,
    private val sessions: VaultSessionManager,
    private val kdbxSessions: KdbxSessionFlow,
    private val kdbxItemWrites: KdbxItemRepository,
    private val apiFactory: BitwardenApiFactory,
    private val crypto: VaultixCrypto,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : FolderRepository {

    // ------------------------------------------------------------------ 读

    override fun observeFolders(vaultId: String): Flow<List<VaultFolder>> =
        vaultDao.observeAll()
            .map { vaults -> VaultKind.fromName(vaults.firstOrNull { it.id == vaultId }?.kind) }
            .distinctUntilChanged()
            .flatMapLatest { kind ->
                if (kind == VaultKind.KDBX) {
                    kdbxFolders(vaultId)
                } else {
                    combine(folderDao.observeByVault(vaultId), sessions.unlockedIds) { rows, unlocked ->
                        rows to (vaultId in unlocked)
                    }
                        .map { (rows, isUnlocked) ->
                            if (!isUnlocked) emptyList() else decodeAll(vaultId, rows)
                        }
                        .flowOn(cryptoDispatcher)
                }
            }

    /**
     * KDBX 库的文件夹流（A2 · 2026-10-13）。
     *
     * 会话是纯内存结构（没有可订阅的 Flow）⇒ 同 [ItemRepositoryImpl.kdbxItems]
     * 用 [KdbxSessionFlow] 当触发器：解锁 / 锁定 / 移除库都会 bump 一次，
     * 届时重读会话即可。未解锁时 `contentOf` 返回 null ⇒ 空列表
     * （与 Bitwarden 侧"未解锁返回空列表"同口径，不泄露"这个库有几个组"）。
     */
    private fun kdbxFolders(vaultId: String): Flow<List<VaultFolder>> =
        kdbxSessions.asSignal()
            .map { Kdbx.contentOf(vaultId)?.folders.orEmpty() }

    /** 解密当前快照；名称解密失败或为空白的行跳过。 */
    private suspend fun decodeAll(vaultId: String, rows: List<FolderEntity>): List<VaultFolder> {
        val key = sessions.keyOf(vaultId) ?: return emptyList()
        return rows.mapNotNull { row ->
            runCatching { crypto.decryptToString(row.encryptedName, key) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { VaultFolder(id = row.id, name = it) }
        }
    }

    // ------------------------------------------------------------------ 写

    override suspend fun createFolder(vaultId: String, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val folderName = checkedName(name)
                when (val target = resolve(vaultId)) {
                    Target.Kdbx -> kdbx(kdbxItemWrites.createFolder(vaultId, null, folderName))
                    is Target.Remote -> createRemote(target, vaultId, folderName)
                }
            }
        }

    override suspend fun renameFolder(
        vaultId: String,
        folderId: String,
        name: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val folderName = checkedName(name)
            when (val target = resolve(vaultId)) {
                Target.Kdbx -> kdbx(kdbxItemWrites.renameFolder(vaultId, folderId, folderName))
                is Target.Remote -> renameRemote(target, vaultId, folderId, folderName)
            }
        }
    }

    override suspend fun deleteFolder(vaultId: String, folderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (val target = resolve(vaultId)) {
                    Target.Kdbx -> kdbx(kdbxItemWrites.deleteFolder(vaultId, folderId))
                    is Target.Remote -> deleteRemote(target, vaultId, folderId)
                }
            }
        }

    // ---------------------------------------------------------------- Bitwarden

    /**
     * 新建：**远端先成功**，再用服务端返回的 id 落本地行。
     *
     * ⚠️ 本地 id 一律用 `dto.id`：sync 的 `persistFolders` 按 id 做 `upsertAll`，
     * 本地若自己造一个 id，同步后会多出一行重复的文件夹（同一名字出现两次）。
     */
    private suspend fun createRemote(target: Target.Remote, vaultId: String, name: String) {
        val key = requireKey(vaultId)
        val dto = target.api.createFolder(FolderRequest(crypto.encryptString(name, key)))
        require(dto.id.isNotBlank()) { "服务器没有返回文件夹 id，请稍后重试" }
        folderDao.upsertOne(
            FolderEntity(
                id = dto.id,
                vaultId = vaultId,
                encryptedName = dto.name.takeIf { it.isNotBlank() }
                    ?: crypto.encryptString(name, key),
                revisionDate = dto.revisionDate,
            ),
        )
    }

    /**
     * 改名：远端成功后覆盖本地密文行。
     *
     * ⚠️ 每次都**重新加密**，不沿用本地旧密文 —— 与 `CipherMapper.toUpdateRequest`
     * 同口径（Bitwarden 的 EncString 每次加密结果都不同，复用旧值在某些服务端实现上
     * 会被判成"没变"而跳过更新）。
     */
    private suspend fun renameRemote(
        target: Target.Remote,
        vaultId: String,
        folderId: String,
        name: String,
    ) {
        val key = requireKey(vaultId)
        val encrypted = crypto.encryptString(name, key)
        val dto = target.api.updateFolder(folderId, FolderRequest(encrypted))
        val existing = folderDao.get(folderId)
        folderDao.upsertOne(
            FolderEntity(
                id = folderId,
                vaultId = vaultId,
                encryptedName = encrypted,
                // 服务端没回 revisionDate 时沿用旧值，不要写成空串（会让"内容有没有变"失真）。
                revisionDate = dto.revisionDate.ifBlank { existing?.revisionDate.orEmpty() },
            ),
        )
    }

    /**
     * 删除：远端成功后删本地行。
     *
     * ⚠️ 服务端返回 404 当作**成功**：那说明远端已经没有它了（可能别的设备删过），
     * 此刻本地继续留着才是真错误 —— 下一次同步会把这一行 prune 掉，
     * 而现在直接删掉，不留中间态。
     */
    private suspend fun deleteRemote(target: Target.Remote, vaultId: String, folderId: String) {
        requireKey(vaultId)
        try {
            target.api.deleteFolder(folderId)
        } catch (e: HttpException) {
            if (e.code() != HTTP_NOT_FOUND) throw e
        }
        folderDao.deleteById(folderId)
    }

    // ---------------------------------------------------------------- 公共

    /** 写操作的目标：解析一次库类型，后面都用它。 */
    private sealed interface Target {
        /** 远端 + 本地 Room 双写。 */
        data class Remote(val api: BitwardenVaultApi) : Target

        /** 转交 KDBX 写路径（它与条目写共用同一个落盘咽点）。 */
        data object Kdbx : Target
    }

    /**
     * 解析目标库。
     *
     * ⚠️ `VaultEntity.origin` 对 Bitwarden 库就是**服务器地址**（与
     * `ItemRepositoryImpl.flushAfterLocalWrite` 同源），所以 API 直接用它构造，
     * 调用方不需要另找地方取 server。
     */
    private suspend fun resolve(vaultId: String): Target {
        val row = vaultDao.get(vaultId) ?: error("找不到这个库：$vaultId")
        // ⚠️ `fromName` 返回**可空**（未知种类 ⇒ null）。未知一律按"不支持文件夹管理"拒绝，
        //    绝不能默默当成 Bitwarden —— 那会把一个来历不明的库行发去某个服务器。
        return when (VaultKind.fromName(row.kind)) {
            VaultKind.KDBX -> Target.Kdbx
            VaultKind.BITWARDEN -> Target.Remote(api = apiFactory.vault(row.origin))
            null -> error("这个库的类型无法识别，不能管理文件夹")
        }
    }

    private suspend fun requireKey(vaultId: String): SymmetricCryptoKey =
        sessions.keyOf(vaultId) ?: error("密码库未解锁，请先解锁再管理文件夹")

    private fun checkedName(raw: String): String =
        when (val verdict = FolderNamePolicy.check(raw)) {
            is FolderNamePolicy.Verdict.Ok -> verdict.name
            is FolderNamePolicy.Verdict.Rejected -> error(verdict.reason)
        }

    /**
     * KDBX 结果的统一翻译：失败把异常抛出去（由外层 `runCatching` 收集）；
     * 成功什么都不做 —— 仓储内部已经落盘并 `bump` 过。
     */
    private fun kdbx(result: Result<*>): Unit {
        result.getOrElse { throw it }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}

/**
 * 把文件夹写操作的失败翻成**给用户看的一句话**。
 *
 * ⚠️ 只写一处：本项目已经因为"同一判断在两处各写一遍、只改了一处"栽过三次
 * （#120 / #193 / #195）。文件夹的失败翻译必须也只有这一个入口。
 *
 * ⚠️ 不要拼服务端 body（可能含邮箱 / 域名），状态码足够用户判断"是我这边的问题还是要找管理员"。
 */
internal fun folderFailureMessage(error: Throwable): String = when (error) {
    is HttpException -> when (error.code()) {
        HTTP_BAD_REQUEST_UNAUTHORIZED, HTTP_FORBIDDEN -> "没有权限修改文件夹，请重新登录后再试"
        HTTP_NOT_FOUND_CODE -> "这个文件夹在服务器上已经不存在了"
        HTTP_CONFLICT -> "文件夹已被其他设备修改，请同步后重试"
        else -> "文件夹操作失败（HTTP ${error.code()}），请稍后重试"
    }
    is IOException -> "无法连接服务器，请检查网络后重试"
    else -> error.message ?: "文件夹操作失败"
}

private const val HTTP_BAD_REQUEST_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND_CODE = 404
private const val HTTP_CONFLICT = 409
