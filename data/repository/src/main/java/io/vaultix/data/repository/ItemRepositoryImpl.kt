package io.vaultix.data.repository

import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.common.TrashCleanupPolicy
import io.vaultix.data.bitwarden.mapper.CipherMapper
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.PendingOpEntity
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.TrashEntry
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** KDBX 会话变化的可观察桥（读路径分流后靠它重新取内容；见 [KdbxSessionFlow]）。 */
    private val kdbxSessions: KdbxSessionFlow,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : ItemRepository {

    override fun observeItems(vaultId: String): Flow<List<VaultItem>> =
        // ★ KDBX 读路径分流（M2 阶段 A）：KDBX 的条目**不在 Room 里**，而在 data:kdbx 的
        // 内存会话里（明文整库，锁库即丢）。两条读路径在此分流，**UI / 自动填充侧零改动**
        // —— 它们只认 `ItemRepository.observeItems`。
        //
        // ⚠️ 库种类是**挂起**查询（`vaultDao.get`），不能在方法体里直接判：那样
        // `observeItems` 就不再是纯函数（每次订阅都要先挂起一次）。因此把它放进流里
        // `flatMapLatest`，每次订阅时解析一次种类。
        vaultDao.observeAll()
            .map { vaults -> VaultKind.fromName(vaults.firstOrNull { it.id == vaultId }?.kind) }
            .distinctUntilChanged()
            .flatMapLatest { kind ->
                if (kind == VaultKind.KDBX) {
                    kdbxItems(vaultId)
                } else {
                    observeState(vaultId, cipherDao.observeByVault(vaultId))
                }
            }

    /**
     * KDBX 库的条目流。
     *
     * KDBX 会话是纯内存结构（没有可订阅的 Flow），所以用 [KdbxSessionFlow] 当触发器：
     * 解锁 / 锁定 / 移除库都会 bump 一次，届时重读会话即可。代价是**库内容在两次触发
     * 之间不变**（KDBX 阶段 A 是只读的，本来就不会变）。
     */
    private fun kdbxItems(vaultId: String): Flow<List<VaultItem>> =
        kdbxSessions.asSignal()
            .map { Kdbx.contentOf(vaultId)?.items.orEmpty() }
            .flowOn(cryptoDispatcher)

    override fun observeTrash(vaultId: String): Flow<List<TrashEntry>> =
        vaultDao.observeAll()
            .map { vaults -> VaultKind.fromName(vaults.firstOrNull { it.id == vaultId }?.kind) }
            .distinctUntilChanged()
            .flatMapLatest { kind ->
                // KDBX 阶段 A 不映射回收站（条目数由解锁内容里的 recycleBinCount 告知 UI），
                // 因此回收站页对 KDBX 库恒为空 —— 明确返回空，而不是让 Room 查询碰巧返回别的。
                if (kind == VaultKind.KDBX) {
                    flowOf(emptyList())
                } else {
                    combine(
                        cipherDao.observeTrashByVault(vaultId),
                        sessions.unlockedIds,
                    ) { rows, unlocked -> rows to (vaultId in unlocked) }
                        .map { (rows, isUnlocked) ->
                            if (!isUnlocked) emptyList() else decodeTrashRows(vaultId, rows)
                        }
                        .flowOn(cryptoDispatcher)
                }
            }

    override fun observeItem(vaultId: String, itemId: String): Flow<VaultItem?> =
        vaultDao.observeAll()
            .map { vaults -> VaultKind.fromName(vaults.firstOrNull { it.id == vaultId }?.kind) }
            .distinctUntilChanged()
            .flatMapLatest { kind ->
                if (kind == VaultKind.KDBX) {
                    return@flatMapLatest kdbxItems(vaultId).map { items ->
                        items.firstOrNull { it.id == itemId }
                    }
                }
                val single = cipherDao.observe(itemId).map { row ->
                    // 已删除（含回收站状态）在详情语义里视同不存在 → 返回 null 触发「条目不存在」
                    if (row == null || row.deletedDate != null) emptyList() else listOf(row)
                }
                observeState(vaultId, single).map { list -> list.firstOrNull() }
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

    override suspend fun restoreItem(vaultId: String, itemId: String): Result<VaultSaveOutcome> =
        runCatching {
            val existing = cipherDao.get(itemId) ?: error("条目不存在：$itemId")
            require(existing.vaultId == vaultId) { "条目不属于该库：$itemId" }
            requireNotNull(existing.deletedDate) { "条目不在回收站中：$itemId" }

            // 本地立即清除删除标记（主列表恢复显示）；离线时队列联网补推
            cipherDao.upsertAll(listOf(existing.copy(deletedDate = null)))
            pendingOpDao.enqueue(
                PendingOpEntity(
                    vaultId = vaultId,
                    cipherId = itemId,
                    op = OP_RESTORE,
                    payload = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )

            flushAfterLocalWrite(vaultId)
        }

    override suspend fun permanentDeleteItem(vaultId: String, itemId: String): Result<VaultSaveOutcome> =
        runCatching {
            val existing = cipherDao.get(itemId) ?: error("条目不存在：$itemId")
            require(existing.vaultId == vaultId) { "条目不属于该库：$itemId" }
            requireNotNull(existing.deletedDate) { "条目不在回收站中，无法永久删除：$itemId" }

            // 本地立即移除（回收站视图随之消失）；DELETE 入队，离线时联网补推；
            // 服务端已不存在（404）时由 flush 弃单（防毒丸）
            pendingOpDao.enqueue(
                PendingOpEntity(
                    vaultId = vaultId,
                    cipherId = itemId,
                    op = OP_DELETE,
                    payload = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            cipherDao.deleteByIds(listOf(itemId))

            flushAfterLocalWrite(vaultId)
        }

    override suspend fun cleanupExpiredTrash(vaultId: String, autoDeleteDays: Int): Int {
        if (!TrashCleanupPolicy.shouldAutoCleanup(autoDeleteDays)) return 0
        return runCatching {
            val now = System.currentTimeMillis()
            val expired = cipherDao.getTrashByVault(vaultId)
                .filter { row ->
                    val deleted = row.deletedDate
                    deleted != null && TrashCleanupPolicy.isExpired(deleted, now, autoDeleteDays)
                }
            if (expired.isEmpty()) return@runCatching 0

            // 与 permanentDeleteItem 同口径：DELETE 入队（离线联网补推）→ 删本地行
            val createdAt = System.currentTimeMillis()
            expired.forEach { row ->
                pendingOpDao.enqueue(
                    PendingOpEntity(
                        vaultId = vaultId,
                        cipherId = row.id,
                        op = OP_DELETE,
                        payload = null,
                        createdAt = createdAt,
                    ),
                )
            }
            cipherDao.deleteByIds(expired.map { it.id })

            flushAfterLocalWrite(vaultId)
            expired.size
        }.getOrDefault(0)
    }

    override suspend fun updateFido2Credentials(
        vaultId: String,
        itemId: String,
        credentials: List<VaultFido2Credential>,
    ): Result<VaultSaveOutcome> = runCatching {
        // 「库已解锁」前置校验：未解锁直接失败，避免走到 updateItem 才报错
        requireNotNull(sessions.keyOf(vaultId)) { "库未解锁，无法保存：$vaultId" }
        val existing = cipherDao.get(itemId) ?: error("条目不存在：$itemId")
        require(existing.vaultId == vaultId) { "条目不属于该库：$itemId" }
        require(existing.deletedDate == null) { "条目已在回收站，无法编辑：$itemId" }
        require(existing.type == mapper.serverTypeOf(VaultItemType.Login)) {
            "通行密钥只能绑定到登录（密码）条目，无法保存到类型 ${existing.type} 的条目"
        }
        val item = loadItem(vaultId, itemId) ?: error("条目解析失败：$itemId")
        // 整体替换该登录条目的通行密钥集合。写回时 CipherMapper 按 credentialId
        // **保留未改动条目的服务端原密文**（绝不重加密），只有新增条目才加密 ——
        // 否则会把服务端已存的私钥材料（keyValue）覆写成 null（P0，不可逆）。
        updateItem(vaultId, item.copy(fido2Credentials = credentials)).getOrThrow()
    }

    override suspend fun removeFido2Credential(
        vaultId: String,
        itemId: String,
        credentialId: String,
    ): Result<VaultSaveOutcome> = runCatching {
        val item = loadItem(vaultId, itemId) ?: error("条目不存在：$itemId")
        val remaining = item.fido2Credentials.filter { it.credentialId != credentialId }
        updateFido2Credentials(vaultId, itemId, remaining).getOrThrow()
    }

    /** 解码单条密文行为明文领域模型（删除态/未解锁返回 null）。 */
    private suspend fun loadItem(vaultId: String, itemId: String): VaultItem? {
        val row = cipherDao.get(itemId) ?: return null
        if (row.deletedDate != null) return null
        val key = sessions.keyOf(vaultId) ?: return null
        return runCatching { json.decodeFromString<CipherDto>(row.encryptedPayload) }
            .getOrNull()
            ?.let { mapper.toDomain(it, key) }
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

    /** 解密当前快照；解析或解密失败的条目跳过（列表可浏览优先；删除过滤由查询保证）。 */
    private suspend fun decodeAll(vaultId: String, rows: List<CipherEntity>): List<VaultItem> {
        val key = sessions.keyOf(vaultId) ?: return emptyList()
        return rows.mapNotNull { row ->
            runCatching { json.decodeFromString<CipherDto>(row.encryptedPayload) }
                .getOrNull()
                ?.let { dto -> mapper.toDomain(dto, key) }
        }
    }

    /**
     * 解密回收站行（与 [decodeAll] 同口径），但保留行级 [CipherEntity.deletedDate]
     * 元数据（本地软删除只改列不重写密文，DTO 内的 deletedDate 不可靠）。
     */
    private suspend fun decodeTrashRows(vaultId: String, rows: List<CipherEntity>): List<TrashEntry> {
        val key = sessions.keyOf(vaultId) ?: return emptyList()
        return rows.mapNotNull { row ->
            val deleted = row.deletedDate ?: return@mapNotNull null
            runCatching { json.decodeFromString<CipherDto>(row.encryptedPayload) }
                .getOrNull()
                ?.let { dto -> mapper.toDomain(dto, key) }
                ?.let { item -> TrashEntry(item = item, deletedDate = deleted) }
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
        const val OP_RESTORE = "RESTORE"
        const val OP_DELETE = "DELETE"
    }
}
