package io.vaultix.data.repository

import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.data.bitwarden.mapper.CipherMapper
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.PendingOpEntity
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 条目读写实现。
 *
 * 读取链路：Room 密文快照（CipherDto JSON，EncString 字段）→ 会话密钥 → 明文
 * [VaultItem]。解密在注入的 crypto 调度器上执行（Docs/10：加解密切 Default 类，
 * 统一走 core:crypto 的 @CryptoDispatcher，避免硬编码），只在解锁会话内进行；
 * 损坏条目跳过，不拖垮整个列表。
 *
 * 写入链路：
 * - 新建：本地 uuid + 密文行（立即可见）→ pending_ops 入队 → 轻量推送；
 *   服务端分配新 id 时由 BitwardenSyncService.flushPending 重映射本地行；
 * - 更新：沿用原 id 覆盖密文行 → UPDATE 入队 → 轻量推送（PUT /ciphers/{id}）；
 * - 软删除：本地行标记 deletedDate（列表立即隐藏）→ SOFT_DELETE 入队 → 轻量推送。
 */
@Singleton
class ItemRepositoryImpl @Inject constructor(
    private val vaultDao: VaultDao,
    private val cipherDao: CipherDao,
    private val pendingOpDao: PendingOpDao,
    private val sessions: VaultSessionManager,
    private val mapper: CipherMapper,
    private val json: Json,
    private val syncService: BitwardenSyncService,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : ItemRepository {

    override fun observeItems(vaultId: String): Flow<List<VaultItem>> =
        observeState(vaultId, cipherDao.observeByVault(vaultId))

    override fun observeItem(vaultId: String, itemId: String): Flow<VaultItem?> {
        val single = cipherDao.observe(itemId).map { row ->
            if (row == null) emptyList() else listOf(row)
        }
        return observeState(vaultId, single).map { list -> list.firstOrNull() }
    }

    override suspend fun createItem(vaultId: String, item: VaultItem): Result<VaultSaveOutcome> =
        runCatching {
            val key = sessions.keyOf(vaultId) ?: error("库未解锁，无法保存：$vaultId")

            val localId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val request = mapper.toRequest(item.copy(id = localId), key)
            val dto = request.toStoredCipherDto(id = localId, revisionDate = "")

            // 1) 密文行落库：列表立即可见（离线也安全）
            cipherDao.upsertAll(
                listOf(
                    CipherEntity(
                        id = localId,
                        vaultId = vaultId,
                        type = request.type,
                        encryptedPayload = json.encodeToString(dto),
                        revisionDate = "",
                        deletedDate = null,
                        folderId = null,
                        favorite = request.favorite,
                    ),
                ),
            )
            // 2) 入 dirty 队列：sync / flush 时推送
            pendingOpDao.enqueue(
                PendingOpEntity(
                    vaultId = vaultId,
                    cipherId = localId,
                    op = OP_CREATE,
                    payload = json.encodeToString(request),
                    createdAt = now,
                ),
            )

            flushAfterLocalWrite(vaultId)
        }

    override suspend fun updateItem(vaultId: String, item: VaultItem): Result<VaultSaveOutcome> =
        runCatching {
            val key = sessions.keyOf(vaultId) ?: error("库未解锁，无法保存：$vaultId")
            val existing = cipherDao.get(item.id) ?: error("条目不存在：${item.id}")
            require(existing.vaultId == vaultId) { "条目不属于该库：${item.id}" }
            require(existing.deletedDate == null) { "条目已在回收站，无法编辑：${item.id}" }
            // 类型守恒：领域类型必须与服务端类型一致（未知类型不映射到 Login，
            // 宁可不编辑也不发生 type 漂移 / 载荷重写）
            require(existing.type == mapper.serverTypeOf(item.type)) {
                "该类型条目的编辑暂不支持（服务端 type=${existing.type}），已停止保存以防数据丢失"
            }

            // 合并更新：只覆盖可编辑明文段，uri/totp/card/identity 等未编辑段沿用原密文
            val stored = runCatching { json.decodeFromString<CipherDto>(existing.encryptedPayload) }
                .getOrElse { error("本地密文损坏，无法编辑：${item.id}") }
            val request = mapper.toUpdateRequest(item, stored, key)
                .copy(folderId = existing.folderId, favorite = existing.favorite)
            val dto = request.toStoredCipherDto(id = existing.id, revisionDate = existing.revisionDate)

            cipherDao.upsertAll(
                listOf(
                    existing.copy(
                        type = request.type,
                        encryptedPayload = json.encodeToString(dto),
                        favorite = existing.favorite,
                    ),
                ),
            )
            pendingOpDao.enqueue(
                PendingOpEntity(
                    vaultId = vaultId,
                    cipherId = existing.id,
                    op = OP_UPDATE,
                    payload = json.encodeToString(request),
                    createdAt = System.currentTimeMillis(),
                ),
            )

            flushAfterLocalWrite(vaultId)
        }

    override suspend fun softDeleteItem(vaultId: String, itemId: String): Result<VaultSaveOutcome> =
        runCatching {
            val existing = cipherDao.get(itemId) ?: error("条目不存在：$itemId")
            require(existing.vaultId == vaultId) { "条目不属于该库：$itemId" }

            // 本地立即标记删除：列表查询（deletedDate IS NULL）随之隐藏
            cipherDao.upsertAll(
                listOf(
                    existing.copy(
                        deletedDate = Instant.now().toString(),
                    ),
                ),
            )
            pendingOpDao.enqueue(
                PendingOpEntity(
                    vaultId = vaultId,
                    cipherId = itemId,
                    op = OP_SOFT_DELETE,
                    payload = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )

            flushAfterLocalWrite(vaultId)
        }

    // ---- 内部 ----

    /** Room 密文行流 + 解锁状态 → 已解密明文列表流（在注入的 crypto 调度器上解码）。 */
    private fun observeState(vaultId: String, source: Flow<List<CipherEntity>>): Flow<List<VaultItem>> =
        combine(source, sessions.unlockedIds) { rows, unlocked ->
            rows to (vaultId in unlocked)
        }
            .map { (rows, isUnlocked) ->
                if (!isUnlocked) emptyList() else decodeAll(vaultId, rows)
            }
            .flowOn(cryptoDispatcher)

    /** 解密当前快照；已删除 / 解析或解密失败的条目跳过（列表可浏览优先）。 */
    private suspend fun decodeAll(vaultId: String, rows: List<CipherEntity>): List<VaultItem> {
        val key = sessions.keyOf(vaultId) ?: return emptyList()
        return rows.mapNotNull { row ->
            if (row.deletedDate != null) return@mapNotNull null
            runCatching { json.decodeFromString<CipherDto>(row.encryptedPayload) }
                .getOrNull()
                ?.let { dto -> mapper.toDomain(dto, key) }
        }
    }

    /** 写库完成后的轻量推送；非 Bitwarden 库（未来 KDBX）不入队推送逻辑。 */
    private suspend fun flushAfterLocalWrite(vaultId: String): VaultSaveOutcome {
        val row = vaultDao.get(vaultId)
        val server = row?.origin?.takeIf { VaultKind.fromName(row.kind) == VaultKind.BITWARDEN }
            ?: return VaultSaveOutcome.Queued
        val flush = syncService.flushPending(vaultId, server)
        return if (flush.isSuccess) VaultSaveOutcome.Synced else VaultSaveOutcome.Queued
    }

    private companion object {
        const val OP_CREATE = "CREATE"
        const val OP_UPDATE = "UPDATE"
        const val OP_SOFT_DELETE = "SOFT_DELETE"
    }
}
