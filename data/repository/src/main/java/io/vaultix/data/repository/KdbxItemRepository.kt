/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 库的条目写回**（M2 阶段 B · 批次 W2）。
 *
 * ## 为什么单独立一个类，而不是塞进 `ItemRepositoryImpl`
 *
 * `ItemRepositoryImpl` 是 Bitwarden 的读写实现（Room 密文行 + `pending_ops` 队列），
 * 而 KDBX 的存储模型**完全不同**：条目活在 `data:kdbx` 的**内存会话**里，落盘是一个
 * kdbx 文件。两者的写路径除了方法名以外没有一处相同 ⇒ 塞在一起只会让那个类
 * 同时懂两套存储（它已经贴着 detekt 的函数数上限）。
 * 这与本项目既有先例一致（`KdbxSyncRepositoryImpl` / `AutoUnlockRepositoryImpl` 都独立成类）。
 *
 * ## 落盘规则（本地 vs 网盘，**语义不同**）
 *
 * | 来源 | 写去哪 | 为什么 |
 * |---|---|---|
 * | 本地 SAF（`content://`） | **直接写文件** | 文件就是存储本身，没有"云端"这回事 |
 * | 网盘（OneDrive / WebDAV） | **先写本地缓存** + 标记待上传 | 「本地立即生效、上传异步」—— 网盘一次写入 5–60 s，不能让用户等 |
 *
 * ⚠️ 缓存里的 `versionToken` **必须沿用旧的**：它记的是"这份本地字节基于远端的哪一版"，
 * 正是下一次条件写（`If-Match`）的基线。写成本次的新令牌会把基线挪走，
 * 于是"远端在这期间变过"这件事就再也检测不出来（静默覆盖别人的改动）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.database.dao.VaultDao
import io.vaultix.common.logging.VaultixLog
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFailure
import io.vaultix.data.kdbx.KdbxWriteFailure
import io.vaultix.data.kdbx.folderIdOf
import io.vaultix.data.repository.kdbx.CachedKdbxFile
import io.vaultix.data.repository.kdbx.KdbxAutoUploader
import io.vaultix.data.repository.kdbx.KdbxCloudSyncCoordinator
import io.vaultix.data.repository.kdbx.KdbxFileCache
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.KdbxCloudSyncStatus
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** KDBX 条目的增删改（写回）。五个动作与 [io.vaultix.domain.ItemRepository] 的写方法一一对应。 */
@Singleton
class KdbxItemRepository @Inject constructor(
    private val vaultDao: VaultDao,
    /**
     * 网盘来源的判定与"待上传"标记都在它身上（它自己就实现 [io.vaultix.data.kdbx.KdbxFileSourceResolver]）。
     * 单独再注入一个 resolver 会拿到**同一个对象**，但"这个 origin 有没有云端"这条判据
     * 只有协调器那一张表知道 ⇒ 注入协调器，不注入接口。
     */
    private val cloudSync: KdbxCloudSyncCoordinator,
    private val fileCache: KdbxFileCache,
    private val kdbxSessions: KdbxSessionFlow,
    /**
     * 网盘库的「保存后自动上传」（施工单 S1 + S6）。
     *
     * ⚠️ 只 `enqueue`、**不等它**：网盘一次写入 5–60 s，在保存路径里等它
     * 会把"保存"卡成转圈（用户体感就是"点了保存没反应"）。
     * 它自己保证串行与失败留痕，见 `KdbxAutoUploader` 的类注释。
     */
    private val autoUploader: KdbxAutoUploader,
) {

    // ⚠️⚠️ 五个写动作一律 `withContext(Dispatchers.IO)`：它们内部都要经过
    //     `Kdbx.mutate`，而 `mutate` 的 KDoc 明确写着「编码代价与一次 KDF 相当，
    //     调用方应当切到 IO/Default」。ViewModel 却在 `viewModelScope`（主线程）里调用它们，
    //     结果是**每次保存都同步跑一遍 Argon2/AES-KDF** —— 表现为"保存时界面卡几百毫秒"
    //     （「打开库的流畅性」这条诉求里最容易被感知的一处）。
    //     ⇒ 调度责任收在本类的 public 层（一次做完），而不是指望每个调用点各自记得切。
    suspend fun create(vaultId: String, item: VaultItem): Result<VaultSaveOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                assertWritable(vaultId)
                requireSupportedType(item)
                val write = Kdbx.createItem(vaultId, folderId = item.folderId, item = item)
                    .getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    /**
     * 修改条目。
     *
     * ⚠️ **必须先取出改动前的那一份**（`before`）：`data:kdbx` 靠它判断"哪些字段真的变了"，
     * 从而做到"没变的不重写"（详见 `KdbxItemWriter.updateEntry` 的 KDoc）。
     * 拿不到（会话里没有这条）⇒ 报"条目不存在"，而不是拿一个空壳当 before 去覆盖。
     */
    suspend fun update(vaultId: String, item: VaultItem): Result<VaultSaveOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                assertWritable(vaultId)
                // 同 #create：类型不对就连"改动前那一份"都不必取（取它只为写回比对）。
                requireSupportedType(item)
                val before = Kdbx.contentOf(vaultId)?.items?.firstOrNull { it.id == item.id }
                    ?: error("条目不存在：${item.id}")
                val write = Kdbx.updateItem(vaultId, before = before, after = item)
                    .getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    suspend fun softDelete(vaultId: String, itemId: String): Result<VaultSaveOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                assertWritable(vaultId)
                val write = Kdbx.moveItemToRecycleBin(vaultId, itemId).getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    suspend fun restore(vaultId: String, itemId: String): Result<VaultSaveOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                assertWritable(vaultId)
                val write = Kdbx.restoreItemFromRecycleBin(vaultId, itemId)
                    .getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    suspend fun permanentDelete(vaultId: String, itemId: String): Result<VaultSaveOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                assertWritable(vaultId)
                val write = Kdbx.purgeItem(vaultId, itemId).getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    // ---------------------------------------------------------------- 文件夹（分组）

    /**
     * **新建文件夹**（= KDBX 分组）。
     *
     * ⚠️ 和五个条目写动作走**同一个** [persist]：往返自检、写回基线闸门、
     * 缓存/文件分流、`kdbxSessions.bump()` 全都在那里。分组写若自己再写一份落盘逻辑，
     * 那些保护在文件夹路径上就等于不存在（本项目已因"同一规则写两处"栽过三次）。
     */
    suspend fun createFolder(
        vaultId: String,
        parentFolderId: String?,
        name: String,
    ): Result<VaultFolder> = withContext(Dispatchers.IO) {
        runCatching {
            assertWritable(vaultId)
            val write = Kdbx.createFolder(vaultId, parentFolderId = parentFolderId, name = name)
                .getOrElse { throw it.toUserFacing() }
            // entryUuid 位放的是新组 uuid（见 KdbxItemWriter.createGroup 的 KDoc）。
            val uuid = write.entryUuid ?: error("新建分组没有返回 id，未做任何改动")
            val folder = VaultFolder(id = folderIdOf(uuid), name = name)
            persist(vaultId, write)
            folder
        }
    }

    /** **重命名文件夹**（只改组名，组内条目与子组原样保留）。 */
    suspend fun renameFolder(
        vaultId: String,
        folderId: String,
        name: String,
    ): Result<VaultSaveOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            assertWritable(vaultId)
            val write = Kdbx.renameFolder(vaultId, folderId = folderId, name = name)
                .getOrElse { throw it.toUserFacing() }
            persist(vaultId, write)
        }
    }

    /**
     * **删除文件夹** —— ⚠️ KDBX 语义是**递归删整棵子树**（子组 + 组内条目一起没）。
     *
     * 代价预览在写之前由 [Kdbx.folderDeletionCostOf] 取得（UI 的确认框用），
     * 这里不重复计算（数法只有 `KdbxItemWriter` 一处）。
     */
    suspend fun deleteFolder(vaultId: String, folderId: String): Result<VaultSaveOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                assertWritable(vaultId)
                val write = Kdbx.deleteFolder(vaultId, folderId).getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    /**
     * 写动作入口的**类型校验**（11-07 解除 A3 硬闸后只剩兜底）。
     *
     * A3 当初拦 Card / Identity / SSH，理由是 writer 会把这三类静默丢成空壳。
     * 但 11-01 起 `KdbxItemWriter.applyPayloads` 已把它们写成自定义字段载荷，
     * `KdbxItemMapper.toVaultCard/Identity/SshKey` 双向读回（卡号 / CVV 还用
     * `EntryValue.Encrypted` 保护）；`card_*` 更是 KeePassXC 的 Bitwarden 导入器
     * 用的生态标准键名 ⇒ 再拦就等于"数据层能存、仓储偏不让存"。
     *
     * ⇒ 现在只挡**领域模型里根本不存在的类型**（枚举外的非法值走不到这里，
     *   保留是为了导入路径拿到损坏数据时不会静默写进库）。
     */
    private fun requireSupportedType(item: VaultItem) {
        require(item.type in KDBX_SUPPORTED_TYPES) {
            "KDBX 密码库不支持${item.type.displayName()}条目（未做任何改动）"
        }
    }

    /**
     * 写动作**之前**的闸门（#185，2026-11-08 真机死锁修复）。
     *
     * ## 为什么必须放在 mutate 之前
     *
     * 旧闸门长在 [persist] 里，而 `Kdbx.mutateLocked` 在那之前就已经把新会话
     * `KdbxSessionStore.put` 进内存了 ⇒ 拒写时"内存已删、磁盘没动"，列表还持有删除前
     * 的旧快照，详情/回收站却读到新会话 —— 用户看到三份互相矛盾的真相。判据本身是
     * 只读的（[Kdbx.baselineStateOf]），完全可以在动手**之前**判。
     *
     * ## `!aligned` 的两种成因必须分开
     *
     * - 远端真变过（同步状态 `REMOTE_CHANGED` / `CONFLICT`）⇒ 拒写，#185 防线原样保留；
     * - 本机自己连续编辑、基线没跟着推进 ⇒ 就地重对齐后放行。
     *
     * 旧实现把两者一律当成"远端变过"：于是「保存被拦 → 同步判两边都没变 → 仍被拦」
     * 成环；同步状态被洗成绿色 `IN_SYNC` 后编排器的补对账又不跑（基线已登记），
     * 死锁只能靠锁库 / 冷启动清掉内存基线才解开 —— 真机现象完全一致。
     *
     * ⚠️ 远端是否变过最终仍由条件写（`If-Match` → 412 → `CONFLICT`）兜底；
     *    内容指纹只是"本机这份内容来自哪一版"的辅助判据，不是唯一防线。
     */
    private suspend fun assertWritable(vaultId: String) {
        val origin = vaultDao.get(vaultId)?.origin ?: return
        if (!cloudSync.hasCloudSource(origin)) return
        // 会话不在 ⇒ 把 NotUnlocked 交给 mutate 自己报，别在这里另造一份错误。
        val state = Kdbx.baselineStateOf(vaultId) ?: return
        if (!state.known || state.aligned) return
        val status = cloudSync.currentSyncStatus(vaultId)
        if (status == KdbxCloudSyncStatus.REMOTE_CHANGED ||
            status == KdbxCloudSyncStatus.CONFLICT
        ) {
            VaultixLog.w(TAG) {
                "写前闸门拒写：远端已变（状态=$status，会话=${state.sessionFingerprint.take(8)}，" +
                    "基线=${state.baselineFingerprint?.take(8)}），会话未做任何改动"
            }
            throw KdbxWriteFailure.StaleBaseline().toUserFacing()
        }
        VaultixLog.d(TAG) {
            "写前闸门：基线落后于会话（状态=$status，本机自己的编辑）⇒ 就地重对齐后放行"
        }
        Kdbx.rebaseBaselineOnSession(vaultId)
    }

    /**
     * 把**已经写好**的字节送到它该去的地方，并让界面能看见这次改动。
     *
     * @param write `mutate` 的产物。`bytes` 落盘、`baseFingerprint`/`newFingerprint` 用于
     *   判断**写回基线**该不该跟着推进（#185，见下面的说明）。
     * @return 恒为 [VaultSaveOutcome.Queued] —— 它的新语义是"**已安全落到本机**"
     *   （见该枚举的 KDoc：本地库写进文件、网盘库写进缓存待上传），
     *   两种情形对用户都是"保存好了"，区别在于云端状态由 `VaultSummary.syncStatus` 表达。
     */
    private suspend fun persist(vaultId: String, write: Kdbx.KdbxPendingWrite): VaultSaveOutcome {
        val bytes = write.bytes
        val origin = vaultDao.get(vaultId)?.origin
            ?: error("这个库里没有对应记录，无法保存：$vaultId")
        val hasCloud = cloudSync.hasCloudSource(origin)
        // ⚠️ 无论成功失败都要 bump：`mutateLocked` 已经换过会话，任何 throw 之后不 bump
        //    都会让列表停在旧快照、详情/回收站读到新会话（三份真相）。
        try {
            // ★★ 写回前的往返自检（2026-10-02 审计修复）：只解码不重编码，一次 KDF 量级。
            //    放在这里让九个写动作共用同一个咽点；失败必须抛出 —— 落盘一份坏库不可逆。
            Kdbx.verifyWrite(vaultId, bytes).getOrElse { throw it.toUserFacing() }

            // 拒写闸门已前移到 [assertWritable]（mutate 之前）。走到这里的 `!aligned`
            // 一定是"本机自己的编辑"造成的，且已在写前就地重对齐。
            if (hasCloud && write.baselineKnown && !write.baselineAligned) {
                VaultixLog.d(TAG) { "写回放行：基线落后由本机编辑造成，已在写前重对齐" }
            }

            if (hasCloud) {
                // ⚠️ 沿用旧令牌 —— 它是下一次条件写（If-Match）的基线，换成新值就再也
                //    检测不到"远端变过"。
                val previousToken = runCatching { fileCache.load(origin) }.getOrNull()?.versionToken
                // ⚠️ 状态必须在写缓存之前读：下面 markLocalEdited 会把它改成 PENDING_UPLOAD。
                val statusBeforeWrite = cloudSync.currentSyncStatus(vaultId)
                fileCache.save(
                    origin,
                    CachedKdbxFile(bytes, previousToken, pendingLocalEdits = true),
                )
                VaultixLog.d(TAG) {
                    "保存落缓存：${bytes.size}B，待上传脏标记已置，状态=$statusBeforeWrite，" +
                        "基线 known=${write.baselineKnown}/aligned=${write.baselineAligned}"
                }
                cloudSync.markLocalEdited(vaultId)
                // ★★ 只要"出发时基线对齐"就推进，不再要求改动前是 IN_SYNC。
                //    连续编辑时每一笔都基于上一笔的产物：基线不跟着走，第三笔就会被
                //    自己造成的 `!aligned` 拦住（真机：删除/移动反复失败、必须先等同步）。
                //    版本令牌仍沿用缓存里的 previousToken，条件写 412 兜底不受影响。
                if (write.baselineAligned) {
                    Kdbx.advanceBaseline(vaultId, write)
                }
                VaultixLog.d(TAG) { "已排入自动上传队列" }
                autoUploader.enqueue(vaultId)
            } else {
                // 本地文件库：文件即存储 ⇒ 直接写文件（本地 IO，毫秒级）。
                val source = cloudSync.fileSourceFor(origin)
                    ?: error("这个库还没有可用的文件来源，请重新选择该 .kdbx 文件")
                source.write(bytes)
            }
            return VaultSaveOutcome.Queued
        } finally {
            kdbxSessions.bump()
        }
    }

    /**
     * `KdbxFailure` → 给用户看的异常。
     *
     * ⚠️ 用它自己的 `message`：那些话是各层**专门写给人看的**
     * （"请先解锁该密码库" / "目标条目或分组不存在，未做任何改动"），
     * 换成通用文案会把"该做什么"这条信息抹掉。
     */
    private fun Throwable.toUserFacing(): Throwable =
        if (this is KdbxFailure) IllegalStateException(message, this) else this
}

/**
 * KDBX 库**支持**的条目类型（11-07：五类全开）。
 *
 * 与 `KdbxItemMapper.toVaultItem` 的产出集、`ui/common/ItemTypePolicy.kt` 的
 * 表单可选集三处同源。用 `entries.toSet()` 而不是手写名单：新增类型时不会
 * 出现"mapper 能读、仓储拒收"的漂移（A3 时代正是手写名单漏了三类）。
 */
internal val KDBX_SUPPORTED_TYPES: Set<VaultItemType> = VaultItemType.entries.toSet()

/**
 * 类型的中文名（只给 [requireSupportedType] 的拒绝文案用）。
 *
 * ⚠️ data 层够不到 `R.string`，且这里的文案**不面向最终用户**——
 * 它沿 `Result.failure` 一路上浮，最终由 UI 层决定怎么呈现
 * （与 [KdbxFailure] 的 message 同款处理，见 [toUserFacing] 的 KDoc）。
 */
private fun VaultItemType.displayName(): String = when (this) {
    VaultItemType.Login -> "登录"
    VaultItemType.SecureNote -> "安全笔记"
    VaultItemType.Card -> "银行卡"
    VaultItemType.Identity -> "身份信息"
    VaultItemType.SshKey -> "SSH 密钥"
}

/** 写回链路日志（11-07）：只记字节数与阶段名，绝不记条目内容。 */
private const val TAG = "VaultixKdbxWrite"
