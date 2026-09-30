package io.vaultix.data.repository

import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.common.TrashCleanupPolicy
import io.vaultix.data.bitwarden.mapper.CipherMapper
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.database.dao.AtomicWriteDao
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.PendingOpEntity
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.ReadOnlyVaultException
import io.vaultix.domain.TrashEntry
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    /**
     * 「本地行 + 入队」的**原子写**（2026-09-16 新增）。
     *
     * ⚠️ 凡是要同时改这两处的写路径，**必须**走它 —— 分开写一旦第二次失败，
     * 会留下「行在、队列不在」的中间态：该行不在 `pendingIds` 里，
     * 随后会被 `pruneRemovedRows` 当成"服务端已删"删掉（或按旧版覆盖）。
     */
    private val atomicWriteDao: AtomicWriteDao,
    private val sessions: VaultSessionManager,
    private val mapper: CipherMapper,
    private val json: Json,
    private val syncService: BitwardenSyncService,
    /** KDBX 会话变化的可观察桥（读路径分流后靠它重新取内容；见 [KdbxSessionFlow]）。 */
    private val kdbxSessions: KdbxSessionFlow,
    /**
     * KDBX 的条目写回（2026-10-01，批次 W2）。
     *
     * ⚠️ 五个写方法**照例先分流**：读路径早就按 `kind` 分流了，而写路径当年没分流 ——
     * 那就是 `.ai/ISSUES.md` **#106**（在 KDBX 库新建条目写出一条 Room 孤儿行，
     * 用户看到"保存成功、条目却没出现"）。**判据：读分流了，写就必须同时分流。**
     */
    private val kdbxItemWrites: KdbxItemRepository,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : ItemRepository {

    // ---- 解密结果缓存（2026-09-29，对齐 Bitwarden decryptCipherListResultStateFlow）----

    /**
     * 每 vault 一份的共享解密流。**解锁期间常驻、锁库即清空**（见 [observedItemsFlow]）。
     */
    private val sharedItemFlows = ConcurrentHashMap<String, SharedFlow<List<VaultItem>>>()

    /** 缓存流的驱动 scope（进程级；收集的执行线程由各段的 flowOn 决定）。 */
    private val shareScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 解密流缓存键存在性无需清理：map 的键是 vaultId，随库删除后留一个空流
     * （上游 `cipherDao.observeByVault` 对已删 vault 发空列表），无泄漏面。
     */
    override fun observeItems(vaultId: String): Flow<List<VaultItem>> =
        sharedItemFlows.getOrPut(vaultId) { observedItemsFlow(vaultId) }

    /**
     * [observeItems] 的原流（按 vaultId 缓存共享，2026-09-29）。
     *
     * ## 为什么要共享（对齐 Bitwarden `decryptCipherListResultStateFlow`）
     *
     * 原实现是冷流：**每个新订阅者**都从头跑一遍「Room 密文行 → 全量解密」。
     * 自动填充的取数是 `observeItems(vaultId).first()` —— 每次聚焦输入框都是
     * 一个全新订阅 ⇒ 几百条目的 JSON 解析 + AES-GCM 解密每次重跑一遍
     * （真机 219 条实测可观），这正是「填充框弹条目不够及时」的组成部分。
     * Bitwarden 的 autofill（`AutofillCipherProviderImpl`）取的是 repository 的
     * 解密 StateFlow 现成值，纯内存过滤。
     *
     * ## 失效语义（不需要手动失效）
     *
     * - 密文行变化 ⇒ Room invalidation 重发 ⇒ 重新解密；
     * - 解锁状态变化 ⇒ `sessions.unlockedIds` 重发 ⇒ 锁库瞬间列表归
     *   `emptyList()`（内存里的明文随之失引用，可被 GC —— 不存在「锁了还
     *   持有明文」的窗口）；
     * - [decodeAll] 的「全失败 ⇒ 作废会话」副作用保持原位。
     *
     * ## 为什么 Eagerly 而不是 WhileSubscribed
     *
     * autofill 场景恰恰发生在**没有任何 UI 订阅**的时候（主界面在后台 /
     * 进程只被 autofill 拉起）—— WhileSubscribed 断流后超时即丢缓存，
     * 填充每次都撞冷启动解密。Eagerly 的代价（解锁期间明文列表常驻内存）
     * 与 Bitwarden 解锁后全量持有 CipherView 同一取舍，且锁库即清（见上）。
     */
    private fun observedItemsFlow(vaultId: String): SharedFlow<List<VaultItem>> =
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
            .shareIn(shareScope, started = SharingStarted.Eagerly, replay = 1)

    /**
     * KDBX 库的回收站流（施工单 S5）。
     *
     * 与 [kdbxItems] 同一套触发机制（会话 bump ⇒ 重读），因为 KDBX 的会话是纯内存结构。
     *
     * ⚠️ [TrashEntry.deletedDate] 取的是**最后修改时间**：KDBX 没有独立的删除时间
     * （详见 `KdbxTrashItem` 的 KDoc）。这里如实转换，不补默认值 ——
     * 条目没有 `<Times>` 时就是 `null`，UI 不显示倒计时而不是显示一个编出来的日期。
     */
    private fun kdbxTrash(vaultId: String): Flow<List<TrashEntry>> =
        kdbxSessions.asSignal()
            .map {
                Kdbx.contentOf(vaultId)?.trashItems.orEmpty().map { trash ->
                    TrashEntry(
                        item = trash.item,
                        deletedDate = trash.lastModifiedAtMillis?.let { Instant.ofEpochMilli(it).toString() },
                    )
                }
            }
            .flowOn(cryptoDispatcher)

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
                // KDBX：回收站 = `meta.recycleBinUuid` 那棵子树（施工单 S5）。
                // 阶段 A 这里恒定返回空列表（占位），**删掉的条目在回收站页看不见**；
                // 现在映射真数据 —— 读方向的识别早在 `toMappedContent` 里就有了。
                if (kind == VaultKind.KDBX) {
                    kdbxTrash(vaultId)
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

    override fun observeSyncStates(vaultId: String): Flow<Map<String, Boolean>> =
        vaultDao.observeAll()
            .map { vaults -> VaultKind.fromName(vaults.firstOrNull { it.id == vaultId }?.kind) }
            .distinctUntilChanged()
            .flatMapLatest { kind ->
                if (kind == VaultKind.KDBX) {
                    // KDBX 没有「云端」这一层（文件即存储）⇒ 恒空 map，UI 因而不画云图标。
                    flowOf(emptyMap())
                } else {
                    pendingOpDao.observeItemLevelIds(vaultId).map { pending ->
                        // 键存在 = 已同步；待推送的 id **不入 map**，读侧 default 到 true，
                        // 于是这里只需列出「未同步」的那批。
                        pending.associateWith { false }
                    }
                }
            }

    override suspend fun createItem(vaultId: String, item: VaultItem): Result<VaultSaveOutcome> =
        runCatching {
            // KDBX 走**完全另一套存储**（内存会话 + kdbx 文件），见 [KdbxItemRepository]。
            if (isKdbx(vaultId)) return@runCatching kdbxItemWrites.create(vaultId, item).getOrThrow()
            val key = sessions.keyOf(vaultId) ?: error("库未解锁，无法保存：$vaultId")

            val localId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val request = mapper.toRequest(item.copy(id = localId), key)
            val dto = request.toStoredCipherDto(id = localId, revisionDate = "")

            // ★ 落库 + 入队必须**原子**（见 AtomicWriteDao 的 KDoc）：
            //   分开写时若第二次失败，会留下「行在、队列不在」的中间态，
            //   该行随后会被 pruneRemovedRows 当成"服务端已删"而删掉。
            //   行落库让列表立即可见（离线也安全）；队列是推送凭据。
            atomicWriteDao.upsertCipherAndEnqueue(
                row = CipherEntity(
                    id = localId,
                    vaultId = vaultId,
                    type = request.type,
                    encryptedPayload = json.encodeToString(dto),
                    revisionDate = "",
                    deletedDate = null,
                    folderId = null,
                    favorite = request.favorite,
                ),
                op = PendingOpEntity(
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
            // KDBX 走**完全另一套存储**（内存会话 + kdbx 文件），见 [KdbxItemRepository]。
            if (isKdbx(vaultId)) return@runCatching kdbxItemWrites.update(vaultId, item).getOrThrow()
            val key = sessions.keyOf(vaultId) ?: error("库未解锁，无法保存：$vaultId")
            val existing = cipherDao.get(item.id) ?: error("条目不存在：${item.id}")
            require(existing.vaultId == vaultId) { "条目不属于该库：${item.id}" }
            require(existing.deletedDate == null) { "条目已在回收站，无法编辑：${item.id}" }
            // 类型守恒：领域类型必须与服务端类型一致（未知类型不映射到 Login，
            // 宁可不编辑也不发生 type 漂移 / 载荷重写）
            require(existing.type == mapper.serverTypeOf(item.type)) {
                "该类型条目的编辑暂不支持（服务端 type=${existing.type}），已停止保存以防数据丢失"
            }

            // 合并更新：只覆盖可编辑明文段，uri/totp/card/identity 等未编辑段沿用原密文。
            // ⚠️ 这里**不要**用 `existing.folderId/favorite` 覆盖 —— mapper.toUpdateRequest
            // 已按「表单意图」写入这两个字段（2026-09-08 专门修过），在调用方再 copy 一次
            // 等于把该修复抵消掉：用户在编辑页改了文件夹/收藏，保存后被静默回退（表现为
            // 「勾了收藏、选了文件夹，退出再进又变回去了」）。
            val stored = runCatching { json.decodeFromString<CipherDto>(existing.encryptedPayload) }
                .getOrElse { error("本地密文损坏，无法编辑：${item.id}") }
            val request = mapper.toUpdateRequest(item, stored, key)
            val dto = request.toStoredCipherDto(id = existing.id, revisionDate = existing.revisionDate)

            // ★ 原子写（同 CREATE）。UPDATE 尤其要紧：若只写了行却漏了队列，
            //   该行不在 `pendingIds` 里，下次同步会拿服务端旧版本**覆盖**这次编辑
            //   —— 用户会看到"改完又变回去了"。
            //   本地行同样按表单意图落库（此前 favorite 写的是 existing.favorite，同样是回退）。
            atomicWriteDao.upsertCipherAndEnqueue(
                row = existing.copy(
                    type = request.type,
                    encryptedPayload = json.encodeToString(dto),
                    favorite = item.favorite,
                    folderId = item.folderId,
                ),
                op = PendingOpEntity(
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
            // KDBX 走**完全另一套存储**（内存会话 + kdbx 文件），见 [KdbxItemRepository]。
            if (isKdbx(vaultId)) return@runCatching kdbxItemWrites.softDelete(vaultId, itemId).getOrThrow()
            val existing = cipherDao.get(itemId) ?: error("条目不存在：$itemId")
            require(existing.vaultId == vaultId) { "条目不属于该库：$itemId" }

            // ★ 原子写（同 CREATE/UPDATE）。软删除若只标了本地行却没入队，
            //   该行会被服务端版本**复活**（删除静默失效）。
            //   本地立即标记删除：列表查询（deletedDate IS NULL）随之隐藏。
            atomicWriteDao.upsertCipherAndEnqueue(
                row = existing.copy(deletedDate = Instant.now().toString()),
                op = PendingOpEntity(
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
            // KDBX 走**完全另一套存储**（内存会话 + kdbx 文件），见 [KdbxItemRepository]。
            if (isKdbx(vaultId)) return@runCatching kdbxItemWrites.restore(vaultId, itemId).getOrThrow()
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
            // KDBX 走**完全另一套存储**（内存会话 + kdbx 文件），见 [KdbxItemRepository]。
            if (isKdbx(vaultId)) return@runCatching kdbxItemWrites.permanentDelete(vaultId, itemId).getOrThrow()
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
            // ⚠️ KDBX 库必须在这里就拦住，不能"反正也查不到东西"就算了：
            //   若历史上已经留下过孤儿行（#106 修之前产生的），本方法会**查到它们**
            //   并给一个 KDBX 库入队 DELETE ⇒ 队列里躺下永不推送的毒丸
            //   （`flushAfterLocalWrite` 对非 Bitwarden 库恒返回 Queued，没人会消费它）。
            //   拒绝后由 `getOrDefault(0)` 兜成 0 —— 与「清理失败静默」的既有约定一致。
            requireNotKdbx(vaultId, "KDBX 库没有回收站清理")
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
        requireNotKdbx(vaultId, PASSKEY_READ_ONLY_REASON)
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
        // ⚠️ 必须在自己这一层就拦：否则会先走到 `loadItem` —— 它查的是 Room，
        //   KDBX 条目不在 Room ⇒ 返回 null ⇒ 报出**「条目不存在」这个与真实原因无关**的错误。
        requireNotKdbx(vaultId, PASSKEY_READ_ONLY_REASON)
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

    /**
     * 解密当前快照。
     *
     * ## ⚠️ ★ 「跳过个别坏行」与「整把密钥不对」必须分开（2026-09-17 真机实证）
     *
     * 原实现把两种失败**合并**成同一个结果（空列表）：
     * ```
     * rows.mapNotNull { runCatching { 解密 }.getOrNull() }   // 失败即跳过
     * ```
     * 密钥不对时**每一行都被跳过** ⇒ 返回空列表 ⇒ 用户看到「还没有保存的密码」，
     * 与"这个库真的是空的"**在界面上无法区分** —— 于是用户以为**数据丢了**。
     * （实测：库里有 218 条密文、归属与活跃库逐字一致；用指纹快速解锁进去是空的，
     *  改用主密码走联网登录后 218 条全部出现 ⇒ 差别只在**密钥对不对**。）
     *
     * ⇒ 判据：**有行、却一条都解不出来** = 密钥不可用，不是库空。
     *   处理：留一条日志，并**作废该库会话**（`lock`）—— 于是 `isUnlocked` 变 false，
     *   UI 落到解锁页，用户能用**主密码**重开（那才是能解出正确密钥的那条路）。
     *
     * ⚠️ 真·空库必须**提前返回**：不能因为"0 行"就判成密钥错，
     *    否则一个正常空库会变成"反复要求解锁"。
     * ⚠️ 只有**全部**失败才作废：个别行损坏（编码不兼容等）不该把整个会话打掉。
     */
    private suspend fun decodeAll(vaultId: String, rows: List<CipherEntity>): List<VaultItem> {
        val key = sessions.keyOf(vaultId) ?: return emptyList()
        // 真·空库：直接返回（见上，不能落到下面的"全部失败"判据里）。
        if (rows.isEmpty()) return emptyList()
        val decoded = rows.mapNotNull { row ->
            runCatching { json.decodeFromString<CipherDto>(row.encryptedPayload) }
                .getOrNull()
                ?.let { dto -> mapper.toDomain(dto, key) }
        }
        if (decoded.isEmpty()) {
            io.vaultix.common.logging.VaultixLog.d("VaultixItem") {
                "decodeAll: rows=${rows.size} 全部解密失败 ⇒ 会话密钥不可用，作废会话要求重新解锁"
            }
            sessions.lock(vaultId)
        }
        return decoded
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

    /** 这个库是不是 KDBX（读/写两侧分流的**唯一**判据，别在别处再抄一遍）。 */
    private suspend fun isKdbx(vaultId: String): Boolean =
        VaultKind.fromName(vaultDao.get(vaultId)?.kind) == VaultKind.KDBX

    /**
     * 「这个操作在 KDBX 库上**不支持**」的守卫。
     *
     * ## ⚠️ 2026-10-01 起它只管两件事（不是"整个库只读"了）
     *
     * KDBX 的**条目**已经可以增删改（走 [KdbxItemRepository]，那五个写方法各自先分流）。
     * 剩下仍然拒绝的只有：
     * 1. **通行密钥的写** —— `KPEX_*` 由浏览器 / 服务端创建，客户端只读；
     * 2. **旧孤儿行的回收站清理** —— #106 修之前留下的脏数据，不许再碰
     *    （`cleanupExpiredTrash` 会**查到**它们并给一个 KDBX 库入队 DELETE
     *    ⇒ 队列里躺下永不推送的毒丸：`flushAfterLocalWrite` 对非 Bitwarden 库恒 Queued、没人消费）。
     *
     * ⇒ **再往里加"因为 KDBX 所以拒绝"之前，先确认那件事真的不该支持。**
     *   这条守卫的历史名字是 requireWritable —— 那时的确是整个库都不可写（#106）。
     *
     * ⚠️ 每次**现查** `vaultDao.get` 而不是缓存 kind：与读路径「每次订阅重新解析种类」
     * 同一取向 —— 不引入第二份真相源（缓存一旦过期，闸就会漏）。
     */
    private suspend fun requireNotKdbx(vaultId: String, reason: String) {
        val kind = VaultKind.fromName(vaultDao.get(vaultId)?.kind)
        if (kind == VaultKind.KDBX) throw ReadOnlyVaultException(vaultId, reason)
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

/**
 * 通行密钥在 KDBX 上不可写的原因。
 *
 * ⚠️ **会直接显示给用户**（`ReadOnlyVaultException.message` 走 `onFailure { error.message }`
 * 链路进 UI）⇒ 写成一句人话，而不是"不支持的操作"这种说了等于没说的台词。
 */
private const val PASSKEY_READ_ONLY_REASON =
    "KDBX 库的通行密钥由浏览器 / 服务端管理，本应用不能修改"

