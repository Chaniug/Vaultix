package io.vaultix.data.repository

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 条目读写实现。
 *
 * 读取链路：Room 密文快照（CipherDto JSON，EncString 字段）→ 会话密钥 → 明文
 * [VaultItem]。解密在 [Dispatchers.Default]（Docs/10：加解密切 Default），
 * 只在解锁会话内进行；损坏条目跳过，不拖垮整个列表。
 *
 * 写入链路（新建）：本地 uuid + 密文行（立即可见）→ pending_ops 入队 →
 * 轻量推送（`POST /ciphers`，不等待整库下载）。服务端分配新 id 时由
 * BitwardenSyncService.flushPending 做本地行重映射。
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
) : ItemRepository {

    override fun observeItems(vaultId: String): Flow<List<VaultItem>> =
        combine(cipherDao.observeByVault(vaultId), sessions.unlockedIds) { rows, unlocked ->
            rows to (vaultId in unlocked)
        }
            .map { (rows, isUnlocked) ->
                if (!isUnlocked) emptyList() else decodeAll(vaultId, rows)
            }
            .flowOn(Dispatchers.Default)

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

            // 3) 轻量推送（不等整库下载）；失败则留在队列等下次同步
            val row = vaultDao.get(vaultId)
            val server = row?.origin?.takeIf { VaultKind.fromName(row.kind) == VaultKind.BITWARDEN }
            if (server == null) {
                VaultSaveOutcome.Queued
            } else {
                val flush = syncService.flushPending(vaultId, server)
                if (flush.isSuccess) VaultSaveOutcome.Synced else VaultSaveOutcome.Queued
            }
        }

    /** 解密某库当前快照；个别条目解析/解密失败时跳过（列表可浏览优先）。 */
    private suspend fun decodeAll(vaultId: String, rows: List<CipherEntity>): List<VaultItem> {
        val key = sessions.keyOf(vaultId) ?: return emptyList()
        return rows.mapNotNull { row ->
            runCatching { json.decodeFromString<CipherDto>(row.encryptedPayload) }
                .getOrNull()
                ?.let { dto -> mapper.toDomain(dto, key) }
        }
    }

    private companion object {
        const val OP_CREATE = "CREATE"
    }
}
