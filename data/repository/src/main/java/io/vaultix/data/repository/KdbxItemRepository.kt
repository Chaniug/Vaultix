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
                val write = Kdbx.moveItemToRecycleBin(vaultId, itemId).getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    suspend fun restore(vaultId: String, itemId: String): Result<VaultSaveOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val write = Kdbx.restoreItemFromRecycleBin(vaultId, itemId)
                    .getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    suspend fun permanentDelete(vaultId: String, itemId: String): Result<VaultSaveOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
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
                val write = Kdbx.deleteFolder(vaultId, folderId).getOrElse { throw it.toUserFacing() }
                persist(vaultId, write)
            }
        }

    /**
     * A3：**类型硬闸** —— KDBX 库只收 [VaultItemType.Login] / [VaultItemType.SecureNote]。
     *
     * ## 为什么表单裁过了，这里还要再拦一道
     *
     * 表单的裁剪（`ItemTypePolicy`）只覆盖「用户手点」这一条路。而
     * `VaultExportRepositoryImpl` 的**导入**同样汇入 [create]：一份 Bitwarden
     * 加密 JSON 里若有银行卡/身份/SSH 条目，导入目标是 KDBX 库时就会绕开表单。
     * 而 KDBX 的 writer 对这三类**没有对应字段区，也不报错**（静默丢弃）——
     * 结果是导入报告"成功"，库里多出一批"类型是卡、字段全空"的残骸。
     *
     * ⇒ 在写动作的入口拦：类型不对就整体拒绝，会话与文件都不碰。
     *
     * ## 判据与 UI 同源
     *
     * 支持集 = `KdbxItemMapper.toVaultItem` 的**产出集**（读得出来才配存进去），
     * 也正是 `ui/common/ItemTypePolicy.kt` 给表单看的那个集合。两处若漂移，
     * 会出现"表单让建、仓储拒收"（用户白填一场）—— 由各自的单测钉住。
     */
    private fun requireSupportedType(item: VaultItem) {
        require(item.type in KDBX_SUPPORTED_TYPES) {
            "KDBX 密码库只支持登录与安全笔记条目，不能保存${item.type.displayName()}（未做任何改动）"
        }
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

        // ★★ 写回前的往返自检（2026-10-02 审计修复）
        //
        //   此前这里**完全没做**自检 —— 字节出了 `Kdbx.mutate` 就直接奔向文件/缓存。
        //   而 `Kdbx.save(File)`（唯一自带自检的入口）在生产代码里零调用点，
        //   于是「kotpass 对未知 XML 标签静默丢弃」这类编码损坏一路无人拦截：
        //   本地库要等下次打开才发现，网盘库更糟 —— 坏字节会被上传上去，
        //   把别的设备上的好副本一起覆盖。
        //
        //   ⚠️ 检查开销：只解码不重编码（见 `KdbxRoundTrip.verifyEncoded` 的说明），
        //      一次 KDF 的量级。放在这里而不是 `#create/#update/...` 各自写一遍，
        //      是为了让"五个写动作"共用同一个咽点 —— 散着写迟早漏一个。
        //
        //   ⚠️ 失败必须**抛出**而不是记日志继续：落盘一份坏库的代价远高于
        //      "这次保存失败，用户的改动没进去"。后者用户会重试，前者不可逆。
        Kdbx.verifyWrite(vaultId, bytes).getOrElse { throw it.toUserFacing() }

        // ★★ 写回基线闸门（#185）：这是**唯一**会"带着旧令牌写"的路径 ——
        //    编辑走的 `saveVia(expectedVersion = 缓存里的旧令牌)` 天生不满足
        //    "内容与令牌配套"：远端变过而没拉时服务端会放行 ⇒ 静默覆盖别人的改动。
        //
        //    ⚠️ 只在**有云端来源**时判（本地 SAF 库没有"远端那一版"这个概念，
        //       基线永远不会登记，判它等于把所有本地保存全线拦掉 —— 实测就是这个后果）。
        //    ⚠️ 传 `null` 给 `saveVia` 的调用方（同步编排器）是另一回事：它推的是
        //        "会话里的全部改动"，本来就该让条件写去判，见那里的说明。
        if (cloudSync.hasCloudSource(origin) && !write.baselineAligned) {
            throw KdbxWriteFailure.StaleBaseline().toUserFacing()
        }

        if (cloudSync.hasCloudSource(origin)) {
            // 网盘：先落本地缓存（立即生效）。
            // ⚠️ 沿用旧令牌 —— 它是下一次条件写的基线，换成新值就再也检测不到"远端变过"。
            val previousToken = runCatching { fileCache.load(origin) }.getOrNull()?.versionToken
            // ⚠️ 状态必须在写缓存**之前**读：下面 `markLocalEdited` 会把它改成 PENDING_UPLOAD。
            val statusBeforeWrite = cloudSync.currentSyncStatus(vaultId)
            fileCache.save(origin, CachedKdbxFile(bytes, previousToken))
            // 再标记待上传。**这一步不能省**：同步的"本地改过没有"是从持久化的
            // `vaults.syncStatus` 读出来的，不标记的话下一次同步会判"两边都没变"，
            // 那笔改动永远推不上去（见 KdbxSyncOrchestrator.markLocalEdited 的 KDoc）。
            cloudSync.markLocalEdited(vaultId)
            // ★★ 写回基线（#185）：**只在"出发时内容 == 远端那一版"且"出发时是同步干净的"时推进**。
            //
            //   同步未完成时（PENDING_UPLOAD / REMOTE_CHANGED / CONFLICT）基线**必须留在原处**，
            //   它正是"本机内容不是远端那一版"这件事的唯一载体 —— 一旦推进，
            //   `Kdbx.saveVia` 那道最后防线就再也拦不住"基于旧内容的编辑覆盖远端新改动"。
            //
            //   ⚠️ 两个条件缺一不可：只看内容一致会漏掉"上传成功但幂等没推上去"的混合态
            //      （内容已对齐远端，可那笔改动还没落地）；只看状态干净则会放过
            //      "基线根本没记录"的情形（那种情形由对账补，不该在这里瞎猜）。
            if (write.baselineAligned && statusBeforeWrite == KdbxCloudSyncStatus.IN_SYNC) {
                // 基线推进由引擎门面做（它握着指纹，上层不碰）：见 `Kdbx.advanceBaseline`。
                Kdbx.advanceBaseline(vaultId, write)
            }
            // 标记之后**立即**排一次上传：不然"改完必须手动点同步"，
            // 而用户以为保存即同步（施工单 S1 的第一条用户反馈）。
            // ⚠️ 顺序不能反：先落 PENDING_UPLOAD，上传器才会认为"有活要干"。
            autoUploader.enqueue(vaultId)
        } else {
            // 本地文件库：文件即存储 ⇒ 直接写文件（本地 IO，毫秒级）。
            val source = cloudSync.fileSourceFor(origin)
                ?: error("这个库还没有可用的文件来源，请重新选择该 .kdbx 文件")
            source.write(bytes)
        }

        // ⚠️ 会话内容变了 ⇒ 必须 bump：条目列表/详情观察的是 `KdbxSessionFlow` 的信号
        //    （`ItemRepositoryImpl.kdbxItems`），不 bump 界面就不会刷新。
        kdbxSessions.bump()
        return VaultSaveOutcome.Queued
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
 * KDBX 库**支持**的条目类型（A3 硬闸的判据）。
 *
 * 与 `KdbxItemMapper.toVaultItem` 的产出集、`ui/common/ItemTypePolicy.kt` 的
 * 表单裁剪集三处同源 —— 各自有单测钉住，改一处必须同步另两处。
 */
internal val KDBX_SUPPORTED_TYPES = setOf(VaultItemType.Login, VaultItemType.SecureNote)

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
