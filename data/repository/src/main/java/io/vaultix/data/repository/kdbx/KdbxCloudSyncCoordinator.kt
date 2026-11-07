/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 网盘同步协调器** —— [KdbxSyncOrchestrator]（纯决策）与
 * `VaultRepository`（对 UI 的门面）之间的那一层。
 *
 * ## 为什么需要这一层（而不是把逻辑塞进 `VaultRepositoryImpl`）
 *
 * 1. 🔴 `VaultRepositoryImpl` 的函数数**正好卡在 detekt `TooManyFunctions` 的 40 上限**
 *    （方案 §2 已记录；之前加 `LocalUnlockEnrollment` / `PinEnrollmentCoordinator`
 *    就是为了避这个）。再往里加 4 个方法是必爆的。
 * 2. 这一层要**构造**文件来源（SAF / WebDAV），而 `VaultRepositoryImpl` 已经够长。
 * 3. 「把远端内容替换进已解锁会话」是一次**会话级**操作，与库生命周期是两件事。
 *
 * ## 来源是怎么解析出来的
 *
 * 只认 `VaultEntity.origin` 的**前缀** —— `VaultEntity` 没有 sourceType 列
 * （方案 §2 已核实），刻意不加：加了就有两处真相（列 + 前缀），迟早不一致。
 * - `content://…` ⇒ [SafKdbxFileSource]（本地文件，也算"来源"，便于统一走一条同步链）
 * - `webdav:<credentialId>:<url>` ⇒ [WebDavKdbxFileSource]
 * - `onedrive:<accountId>:<path>` ⇒ 由 app 侧注册的工厂（见 [registerFactory]）
 *
 * ★ 本类**就是** [KdbxFileSourceResolver] 的实现 —— 解锁 / 校验路径也用它
 * （2026-09-17 从"旧 `KdbxSource`"迁过来）。那张判别表因此只有一个定义点，
 * 不会出现"同步找得到来源、解锁却找不到"这种无报错的漂移。
 *
 * ⚠️ OneDrive 的构造需要 MSAL token（在 app 层），所以这里开一个**注册口** ——
 * 而不是让 `data:repository` 依赖 app（那会形成反向依赖）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.data.kdbx.KdbxWriteFailure
import io.vaultix.database.dao.VaultDao
import io.vaultix.model.KdbxCloudSyncStatus
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * 「把远端字节替换进已解锁会话」的钩子。
 *
 * ## 为什么是钩子而不是在这里直接调 `Kdbx.unlock`
 *
 * 直接调也行，但那**要求调用方有主密码**。而"远端更新了，请重新解锁"是一条
 * 用户可见的提示；如果每次拉取都要用户重新输密码，自动同步就没意义了。
 *
 * ⇒ 会话替换由**持有解锁凭据的那一层**（app 侧的解锁流程 / `VaultRepositoryImpl`）
 * 提供：它知道该库的快解锁凭据能不能免密重开。这里只负责"该拉到哪一步"。
 */
fun interface KdbxSessionReplacer {
    /**
     * 用 [remoteBytes] 替换 [vaultId] 的会话。
     *
     * @return 成功 = 新会话已就位（此后保存会写远端版本）；失败 = 原因（可展示）。
     */
    suspend fun replace(vaultId: String, remoteBytes: ByteArray): Result<Unit>
}

/** WebDAV 凭据来源（按 `credentialId` 现取；**不缓存**，见 `WebDavKdbxFileSource` 的说明）。 */
fun interface WebDavCredentialLookup {
    suspend fun credentials(credentialId: String): WebDavCredentials?
}

/**
 * KDBX 网盘同步协调器。
 *
 * @param vaultDao 读 origin、写同步状态。
 * @param orchestrator 纯决策层（可脱离网络单测）。
 * @param safFactory `content://` URI ⇒ 来源（需要 `Context`，故从构造点传进来）。
 * @param sessionReplacer 拉取后替换已解锁会话（见 [KdbxSessionReplacer]）。
 * @param okHttp 给 WebDAV 来源用（构造点在 app 的 DI，那边已有共享的 `OkHttpClient`）。
 * @param webDavCredentials 按 credentialId 取 WebDAV 账号密码。
 * @param fileCache 远端 kdbx 的本地缓存（**批次 B1**，2026-09-30）：见 [CachedKdbxFileSource]。
 */
class KdbxCloudSyncCoordinator(
    private val vaultDao: VaultDao,
    private val orchestrator: KdbxSyncOrchestrator,
    private val safFactory: (String) -> KdbxFileSource?,
    private val sessionReplacer: KdbxSessionReplacer,
    private val okHttp: () -> OkHttpClient?,
    private val webDavCredentials: WebDavCredentialLookup,
    /**
     * 远端 kdbx 的本地缓存（批次 B1）。
     *
     * ⚠️ **只在 [fileSourceFor] 里给远端来源套一层** —— 别在别处再包：
     * 双重装饰会让缓存写两遍、"命中"判断套娃（外层命中内层还要再判一次）。
     */
    private val fileCache: KdbxFileCache,
    /**
     * 后台校验用的**应用级** scope（2026-10-05）。
     *
     * ⚠️ 必须是应用级而不是构造点随手 `CoroutineScope(Dispatchers.IO)`：
     *   后台校验要活过「解锁完成 → 导航 → ViewModel 销毁」这一整段，
     *   绑页面/会话的 scope 会在导航瞬间取消 ⇒ 提示永远不出现。
     */
    private val appScope: CoroutineScope,
) : KdbxFileSourceResolver {

    /**
     * app 侧注册的额外来源工厂（目前只有 OneDrive）。
     *
     * ⚠️ 单值而不是列表：**同时注册两个工厂去认同一个前缀**是配置错误，
     * 不是一个特性。用单值让这件事在**编译期**就说不出口。
     */
    @Volatile
    private var extraFactory: ((String) -> KdbxFileSource?)? = null

    /** 由 app 侧的 DI 在启动时调用一次（见 `OneDriveModule`）。 */
    fun registerFactory(factory: (String) -> KdbxFileSource?) {
        extraFactory = factory
    }

    /**
     * 解析 origin ⇒ 来源（实现 [KdbxFileSourceResolver]，因此 `override`）。
     *
     * @return null = 这个库没有可同步的网盘来源（UI 据此隐藏同步入口）。
     */
    override fun fileSourceFor(origin: String): KdbxFileSource? = when {
        // 本地 SAF：**不缓存字节** —— 它的 `versionToken` 是内容 SHA-256，
        // 存一份字节换不来任何一次 IO。但**必须复用同一个实例**（见下方 `safSources`）。
        origin.startsWith(SAF_ORIGIN_PREFIX) ->
            safSources.getOrPut(origin) { safFactory(origin) ?: NullSource }.takeUnless { it === NullSource }

        WebDavVaultOrigin.matches(origin) -> {
            val parsed = WebDavVaultOrigin.parse(origin) ?: return null
            val client = okHttp() ?: return null
            withRemoteCache(
                origin,
                WebDavKdbxFileSource(
                    client = client,
                    fileUrl = parsed.fileUrl,
                    credentialProvider = {
                        webDavCredentials.credentials(parsed.credentialId)
                            ?: throw IllegalStateException("找不到该 WebDAV 账号的凭据，请重新填写")
                    },
                ),
            )
        }

        else -> extraFactory?.invoke(origin)?.let { withRemoteCache(origin, it) }
    }

    /**
     * 这个 origin 是不是**网盘**来源（本地 SAF 不算）。
     *
     * ## 为什么需要它（2026-10-01，W2）
     *
     * 条目写回成功后要标记「待上传」，而**本地 SAF 库没有"上传"这回事** ——
     * 它的文件就是存储本身。给它标记的后果是 `VaultSummary.syncStatus`
     * 从 null 变成非 null，UI 会在一个根本没有云端的库上渲染「待上传」角标。
     *
     * ⚠️ 判据与 [fileSourceFor] **同一张表**（就是上面那个 `when`），
     * 不新加 `sourceType` 列 —— 两处真相必然漂移（这条纪律在本文件里已写过一次）。
     * 并且额外要求"**真的能解析出来源**"：畸形 origin（如 `onedrive:` 缺路径）不该被
     * 当成"有云端" —— 那会留下一个永远传不上去的「待上传」。
     *
     * ⚠️ 2026-11-02：判据本身下沉到 [KdbxFileSourceResolver.isCloudOrigin]（接口默认方法）。
     * 此前 `KdbxCreateRepositoryImpl` 与 `VaultRepositoryImpl` 各自抄了一份，
     * 而"哪条路径认为这个库没有云端"这件事**失效时不报错**。这里保留同名方法只为
     * 让类内调用点读起来顺，实现改为直接委托 —— 判据仍然只有一份。
     */
    fun hasCloudSource(origin: String): Boolean = isCloudOrigin(origin)

    /**
     * 这个 origin 是否由 [registerFactory] 注册的额外工厂接管（#233）？
     *
     * ## 为什么需要它
     *
     * [fileSourceFor] 用 `when` 依次试 SAF / WebDAV / 额外工厂，而 `registerFactory`
     * 只有**一个**槽位 —— 想验证"某条路径确实走到那个槽"，光看前缀不够：
     * `webdav:` 会先被 WebDAV 分支吃掉，用例于是安静地测到**另一个来源**上
     * （最糟的一种"绿"）。
     * ⇒ 把"这条路径归额外工厂"变成可以**问**的判据，而不是让调用方去背 `when` 的顺序。
     */
    fun isExtraFactoryOrigin(origin: String): Boolean =
        !origin.startsWith(SAF_ORIGIN_PREFIX) &&
            !WebDavVaultOrigin.matches(origin) &&
            extraFactory != null

    /**
     * **本地内容改过**（条目写回成功后由仓储调用）。
     *
     * @return 是否真的标记了（false = 本地库 / 畸形来源 ⇒ 没有"待上传"这回事）。
     */
    suspend fun markLocalEdited(vaultId: String): Boolean {
        val row = vaultDao.get(vaultId) ?: return false
        if (!hasCloudSource(row.origin)) return false
        orchestrator.markLocalEdited(vaultId)
        return true
    }

    /**
     * 该库**此刻**的持久化同步状态（无记录 / 未知值 ⇒ `null`）。
     *
     * 用途：调用方要"在改动之前"看一眼状态时不许自己 `vaultDao.get` ——
     * 那等于把"状态字符串 ↔ 枚举"的翻译再抄一份，抄漏一个名字就会静默当成
     * "不在同步中"（而这条判据正好决定**写回基线该不该推进**，见 `KdbxItemRepository.persist`）。
     */
    suspend fun currentSyncStatus(vaultId: String): KdbxCloudSyncStatus? =
        KdbxCloudSyncStatus.fromName(vaultDao.get(vaultId)?.syncStatus)

    /**
     * 每个 origin 一份**新鲜度容器**（#233）。
     *
     * ## 为什么它必须住在协调器里
     *
     * [withRemoteCache] 每次调用都新建一个 `CachedKdbxFileSource`。若新鲜度住在那个临时
     * 实例里，"上传成功 ⇒ 与远端一致"这条结论写进去就随实例消失了 ——
     * 下一次解锁拿到的仍是 `UNVERIFIED`，提示永远不消失。
     *
     * ⚠️ 用 `ConcurrentHashMap`（而不是普通 map）：协调器是**单例**，
     * `fileSourceFor` 会在解锁线程、同步协程、后台校验协程里**并发**调用。
     *
     * ⚠️ 不做淘汰：键是 origin（＝库 id），数量等于用户拥有的远端库数量，
     * 量级是个位数到几十；为它加一层 LRU 只会多一个"淘汰了还在用"的失效面。
     * （删库时容器留下一个 `UNVERIFIED` 值，无副作用。）
     */
    private val freshnessHolders = java.util.concurrent.ConcurrentHashMap<String, FreshnessHolder>()

    /**
     * 本地 SAF 来源**按 origin 复用同一个实例**（2026-11-02，#234）。
     *
     * ## 为什么非复用不可
     *
     * `SafKdbxFileSource` 的 `stat()` 现在有一条快速路径：记住上一次整份读的摘要与读区间，
     * 下次只查元数据就能证明"内容没变"。而这条路径依赖**实例内部的两份状态**：
     * 读缓存（摘要 + 区间）和库变更序号（本进程写过几次）。
     *
     * 每次 `fileSourceFor` 新建一个实例 ⇒ 序号恒为 0、缓存在构造时就没了 ⇒
     * 快速路径**永远不会命中**（不会出错，只是完全白做）。这与 #233 里
     * "`FreshnessHolder` 写在临时实例上、UI 永远 `UNVERIFIED`" 是**同一个坑**：
     * **把状态挂在短命对象上，等于没挂。**
     *
     * ⚠️ 用 `ConcurrentHashMap.getOrPut` 而不是 `computeIfAbsent`：后者会在 map 的锁里
     * 调用 `safFactory`（它会 `Uri.parse`），多库并发解锁时容易变成热点。
     * 代价是极端并发下可能造出两个实例、只留一个 —— 与"每次新建"等价，不会更糟。
     */
    private val safSources = java.util.concurrent.ConcurrentHashMap<String, KdbxFileSource>()

    /** `safFactory` 返回 null 时占位，避免每次都重新构造一遍（`computeIfAbsent` 不支持存 null）。 */
    private object NullSource : KdbxFileSource {
        override suspend fun stat(): KdbxFileStat = error("占位对象，不应被使用")
        override suspend fun read(): ByteArray = error("占位对象，不应被使用")
        override suspend fun write(
            bytes: ByteArray,
            expectedVersion: String?,
            force: Boolean,
            failIfExists: Boolean,
        ): KdbxFileWriteResult = error("占位对象，不应被使用")
        override suspend fun testConnection(): Result<Unit> = error("占位对象，不应被使用")
    }

    /**
     * 给**远端**来源套本地缓存（批次 B1，2026-09-30）。
     *
     * 只此一处套：`fileSourceFor` 是「origin ⇒ 来源」的唯一判据表，解锁 / 校验 /
     * keyfile / 同步四条路径都从这里取来源 ⇒ 挂在这里即全覆盖（`KdbxFileSourceResolver`
     * 的 KDoc：「判据只能有一份」）。
     *
     * ## ★ 为什么装饰器现在**按 origin 复用**（#246，2026-11-04）
     *
     * ⚠️ 以前是"每次现装一个"。后果不在解锁路径（那两个字段 `read()` 每次都从缓存
     * 文件重新读），而在**订阅侧**：本类每个实例各有一个 `freshness` StateFlow，
     * 而 UI 拿到的是**解析那一刻**那个实例上的流 —— 上传成功 / 后台校验把结论写进
     * **别的**实例后，UI 那个流**永远不会再发一个值**。用户看到的就是
     * 「同步成功了，但角标一直不改」。
     *
     * `FreshnessHolder`（#233）解决了"值"的共享，但这个流本身仍是每个实例一份。
     * ⇒ 复用实例是唯一不需要改 UI 侧签名（`fileSourceFor` 的返回类型是
     * `KdbxFileSource`，上面没有 `freshness`）的修法。
     *
     * ⚠️ 复用是**安全**的：装饰器自身除 `recheckStarted` 外无状态，而那个
     *   `AtomicBoolean` 的语义恰好是"同一个库同时只跑一轮后台校验" —— 复用它
     *   与该语义完全一致（以前是"每个实例各许一轮"，反而更松）。
     */
    private fun withRemoteCache(origin: String, source: KdbxFileSource): KdbxFileSource =
        remoteSources.getOrPut(origin) {
            newRemoteCache(
                origin = origin,
                source = source,
                holder = freshnessHolders.getOrPut(origin) { FreshnessHolder() },
            )
        }

    /** 远端来源实例表（见 [withRemoteCache] 的 KDoc）。键是 origin ⇒ 数量 = 远端库数量。 */
    private val remoteSources = java.util.concurrent.ConcurrentHashMap<String, KdbxFileSource>()

    /**
     * 真正构造装饰器的地方（从 [withRemoteCache] 拆出来，只为让那个方法的
     * `getOrPut` 读起来不嵌套三层）。
     *
     * ⚠️ `delegate` 仍是**每次调用都新造**的（`WebDavKdbxFileSource` / OneDrive 来源
     * 都是无状态、按参数构造的薄适配层）；被复用并持有的只有装饰器本身。
     */
    private fun newRemoteCache(
        origin: String,
        source: KdbxFileSource,
        holder: FreshnessHolder,
    ): KdbxFileSource =
        CachedKdbxFileSource(
            delegate = source,
            cache = fileCache,
            cacheKey = origin,
            // ★ 同一 origin 的所有装饰器实例共享同一个新鲜度（#233）。
            freshHolder = holder,
            scope = appScope,
            // ⚠️ `origin` 就是 vaultId：KDBX 库的 `VaultEntity.id` 与 `origin` 同值
            //   （见 `KdbxCreateRepositoryImpl` 建库时 `id = resolvedOrigin`），
            //   所以这里不需要再查一次表 —— 查不到时 `markRemoteChanged` 自己会 `return`
            //   （`vaultDao.get(vaultId) ?: return`），不存在的库不会写出坏状态。
            onRemoteChanged = { orchestrator.markRemoteChanged(origin) },
            // ★ 上传成功后把缓存里的版本令牌一起刷新（#233）。
            //   ⚠️ 少了这一句：`vaults.remoteVersionToken` 已经是新 eTag，而缓存里还是旧的
            //   ⇒ 每次解锁都判"远端已变"、白下一次整库，并挂一个**假的**「远端有更新」角标。
            onUploaded = { bytes, newVersion -> orchestrator.recordUploaded(origin, bytes, newVersion) },
        )

    /**
     * 跑一次同步。
     *
     * @param localChangedSinceLastSync 见 `KdbxSyncOrchestrator.sync`。
     */
    suspend fun sync(vaultId: String, localChangedSinceLastSync: Boolean): KdbxSyncResult {
        val row = vaultDao.get(vaultId) ?: return KdbxSyncResult.Failed("找不到该密码库")
        // ★★ 11-07：缓存里的脏标记是「本地有改动」的**第二个真源**。
        //
        // `sync` 一开头就 markStatus(SYNCING)，把 PENDING_UPLOAD 冲成 SYNCING；
        // 而 `impliesLocalChanges(SYNCING) = false`。于是并发/紧随的另一次同步
        // 现查状态时得到 false ⇒ 编排器走「两边都没变」捷径 ⇒ 状态洗成 IN_SYNC，
        // 那笔改动就再也不会被推上去（真机：手机改了、云端一个字节没变、直接显示小云）。
        //
        // ⇒ 只要缓存还带 pendingLocalEdits，无论持久化状态说什么，都按"本地改过"处理。
        //   标记在 `write()` 上传成功后才清，不会造成重复上传的空转。
        val cachedEdits = runCatching {
            fileCache.load(row.origin)?.pendingLocalEdits == true
        }.getOrDefault(false)
        return orchestrator
            .sync(vaultId, row.origin, localChangedSinceLastSync || cachedEdits)
            .toResult()
    }

    /**
     * **现查**本地有没有未上传的改动（读持久化的 `vaults.syncStatus`）。
     *
     * 判据只有一份：[KdbxCloudSyncStatus.impliesLocalChanges]。
     * `KdbxSyncRepositoryImpl.localChangedSinceLastSync` 与这里同源 ——
     * 在这个类里也留一份，是因为 [resolveUsingRemote] 需要在**下载之前**
     * 自己再判一次（见那里的说明），而它拿不到仓储层。
     */
    private suspend fun localChangedSinceLastSync(vaultId: String): Boolean =
        KdbxCloudSyncStatus.fromName(vaultDao.get(vaultId)?.syncStatus)?.impliesLocalChanges == true

    /**
     * 用户拍板「用远端覆盖本地」。
     *
     * ## 这里**必须**真的把字节拉下来替换会话
     *
     * ⚠️ 只改状态不拉字节是**最坏**的伪实现：状态显示「已用远端覆盖」，而本地
     * 会话里还是旧内容 ⇒ 用户下一次保存就把旧内容推回去，**远端的新版本被静默覆盖**。
     * 那正好违反用户的硬要求（「同步不丢」）。
     *
     * ⇒ 顺序是：拉字节 → 替换会话 → **才**记状态。任一步失败都不记"成功"。
     *
     * @param discardLocalChanges ★ 调用方是否**已经取得用户明确同意**丢弃本地改动
     *   （冲突对话框「用云端覆盖本地」= true；`pull` 的自动拉取 = false）。
     *
     *   ## 为什么需要这个开关（2026-11-03 修掉的一个死按钮）
     *
     * 下面那道"本地有未上传改动就不许拉"的闸门，本意是**防止自动拉取静默吞掉编辑**。
     * 但它是用 `localChangedSinceLastSync` 判的，而该判据把 `CONFLICT` 也算成
     * "有本地改动"（那是给编排器判"两边都改"用的）。于是出现一个自锁：
     *
     * ```
     * 状态 = CONFLICT（用户在冲突对话框上）→ 选「用云端覆盖本地」
     *   → 闸门判定"本地有改动" → 直接返回 Conflict
     *   → UI 弹"云端又有了新改动，请重新同步"（而实际上一个字节都没动）
     *   → 再点一次，还是这一句。远端那份**永远拉不下来**。
     * ```
     *
     * ⇒ 用户**明确选择丢弃**时，这道闸门不该再拦（要丢的就是它拦的东西）。
     *   默认 `false`：不表态就是不许丢。
     */
    suspend fun resolveUsingRemote(vaultId: String, discardLocalChanges: Boolean = false): KdbxSyncResult {
        val row = vaultDao.get(vaultId) ?: return KdbxSyncResult.Failed("找不到该密码库")
        val source = fileSourceFor(row.origin) ?: return KdbxSyncResult.NoCloudSource

        // ★ 先拿令牌**再**读字节（2026-10-01 调序）。
        //
        // ⚠️ 顺序不是无所谓：读到字节之后远端可能又被人改了一版，那时再 stat
        //    拿到的是**更新的**令牌，而本地会话里躺的却是**旧一版** —— 把那个更新的
        //    令牌记成基线，就等于宣称"我已经跟上这一版了"，于是那一版再也不会被拉，
        //    **静默漏掉别人的改动**。
        //    ⇒ 令牌偏**旧**是安全的：下次同步只会再报一次"远端有更新"，多拉一次而已；
        //      偏**新**是危险的。所以 stat 必须在 read **之前**。
        val remoteToken = runCatching { source.stat().versionToken }.getOrNull()

        // ★★ 下载**之前**再判一次"本地有没有新改动"（2026-10-20）。
        //
        // ## 为什么必须在 read 之前
        //
        // `readRemoteCurrent()` 是一次真实网络下载（真机 1–10 s）。调用方（`pull` / 冲突对话框
        // 「用远端覆盖」）在**开始前**判过一次"本地没改"，但那个判断在这几秒里会过期：
        // 用户完全可以在下载过程中再编辑一笔。而 `replaceSession` 是**整体替换**会话 ⇒
        // 那笔编辑被静默抹掉，且当前状态马上被记成 `IN_SYNC`（"已同步"）——
        // **没有任何地方会再提醒他**。
        //
        // 判据用 `localChangedSinceLastSync`（读持久化的 `syncStatus`，只此一份），
        // 它在 `persist` 里由 `markLocalEdited` 同步写入 ⇒ 编辑一落盘就能看见。
        //
        // ⚠️ 窗口的残留：判断与 read 之间仍有微秒级 gap，但真正长的窗口（下载）被覆盖了；
        //    要彻底消掉得把"编辑"也纳入同一把锁，而那是 UI 线程会等的锁 —— 不值。
        //    这里选择"宁可多报一次冲突，也不静默覆盖"：报冲突最坏是多点一下，
        //    覆盖的代价是**用户那一笔永远没了**。
        if (!discardLocalChanges && localChangedSinceLastSync(vaultId)) {
            // ⚠️ **不要**用 `markDeferred`：它只在状态已是 CONFLICT 时生效（它的语义是
            //    "用户点了稍后"），用在这里等于什么都不做，状态会停在 `SYNCING`。
            //    也不要把本地改动改写成 CONFLICT —— 那会伪报"两边都改了"，
            //    把用户推向「用本地覆盖远端」这个**真丢数据**的选项。
            //    ⇒ 原样保留 `PENDING_UPLOAD`（甚至什么都不写）：下一次同步自然拿到
            //      `localChangedSinceLastSync = true`，走编排器自己那条冲突判定。
            return KdbxSyncResult.Conflict(currentRemoteVersion = remoteToken)
        }

        // ★★ 必须 [KdbxFileSource.readRemoteCurrent]，**不能**用 `read()`（2026-11-03 修）。
        //
        // ## 修的是什么（真机可复现，表现是"提示已下载，条目还是旧的"）
        //
        // `read()` 的契约只是"把这个库给我"：远端来源套了缓存装饰器（解锁提速），
        // 有缓存时它**不发任何网络请求**，直接返回上一版字节。于是这里拉下来的
        // "远端"其实是缓存里的旧版 —— 会话被旧内容替换，而下面 `markResolved`
        // 记的却是**远端新令牌**。两者一配就对不上了：
        // 此后再同步恒判 `remoteChanged = false` ⇒ 远端那版**永远拉不下来**；
        // 而会话里是旧内容 ⇒ 用户改一笔就把旧内容推回去，覆盖别人的改动。
        //
        // ⇒ `readRemoteCurrent()` 保证是远端此刻的字节，并顺手把缓存也换成这一版
        //   （否则下次解锁又读回旧缓存，用户会以为"同步没生效"）。
        // ⚠️ 代价是**两次元数据往返**：这里一次（给基线），装饰器内部再一次（给缓存配对）。
        //    比"多一次整库下载"便宜得多，而且两次都偏旧 ⇒ 只会多拉一次，不会漏拉。
        val bytes = runCatching { source.readRemoteCurrent() }.getOrElse { error ->
            return KdbxSyncResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "无法读取远端文件")
        }
        sessionReplacer.replace(vaultId, bytes).exceptionOrNull()?.let { error ->
            // ⚠️ 会话没换成功 ⇒ **不能**记"已同步"。留在 CONFLICT 上，让用户还能再选一次。
            orchestrator.markDeferred(vaultId)
            return KdbxSyncResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "无法替换本地会话")
        }

        // 只有会话真的换好了才记"已同步"；`remoteToken` 为 null 时编排器保留旧基线（同样偏旧，安全）。
        // ⚠️ 写回基线（#185）由 `Kdbx.replaceSession` 一并建立 —— 会话换成了远端那一版，
        //    "本机内容来自哪一版"这个问题从这一刻起有了确定答案。
        orchestrator.markResolved(vaultId, remoteToken)
        // ★ 11-07：会话已经是远端那一版 ⇒ 缓存必须同步收敛，并**清掉脏标记**。
        //   `readRemoteCurrent()` 在缓存带 pendingLocalEdits 时故意不回写（保住本地改动），
        //   但走到这里说明用户已明确选「用远端覆盖」（或本地确实没改动），
        //   留着脏缓存会让下次解锁又读回被丢弃的本地内容。
        runCatching { fileCache.save(row.origin, CachedKdbxFile(bytes, remoteToken)) }
        return KdbxSyncResult.Downloaded
    }

    /**
     * 用户拍板「用本地覆盖远端」—— **唯一**会绕过条件写的路径。
     *
     * ⚠️ 远端这一刻的内容会被永久丢弃。UI 必须明确告知（见 `KdbxConflictDialog`）。
     *
     * ⚠️ 同时把 [expectedVersion] 显式传 `null` —— `Kdbx.saveVia` 见到 `null`
     * 会**先 stat 一次**拿当前版本，那是错的（我们要的就是"不看远端、直接盖"）。
     * 所以走 `force` 开关而不是靠 `null` 语义。
     */
    suspend fun resolveUsingLocal(vaultId: String, force: Boolean = true): KdbxSyncResult {
        val row = vaultDao.get(vaultId) ?: return KdbxSyncResult.Failed("找不到该密码库")
        val source = fileSourceFor(row.origin) ?: return KdbxSyncResult.NoCloudSource

        return Kdbx.saveVia(vaultId = vaultId, source = source, force = force).fold(
            onSuccess = { report ->
                orchestrator.markResolved(vaultId, report.writtenVersion)
                KdbxSyncResult.Uploaded(report.writtenVersion)
            },
            onFailure = { error ->
                when (error) {
                    is KdbxWriteFailure.Conflict -> KdbxSyncResult.Conflict(error.currentVersion)
                    is KdbxWriteFailure.StaleBaseline -> KdbxSyncResult.StaleBaseline
                    else -> KdbxSyncResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "覆盖远端失败")
                }
            },
        )
    }

    /** 用户拍板「稍后再决定」：只标状态，不做 IO。 */
    suspend fun defer(vaultId: String) {
        orchestrator.markDeferred(vaultId)
    }

    /**
     * 上报「上传期间本地又改了」（转发给编排器，见其 KDoc）。
     *
     * ⚠️ 方案 §9 点名的**最容易漏的竞态**：漏掉它那笔改动永远推不上去。
     */
    suspend fun notifyLocalChangedDuringUpload(vaultId: String) {
        orchestrator.notifyLocalChangedDuringUpload(vaultId)
    }

    private fun SyncOutcome.toResult(): KdbxSyncResult = when (this) {
        is SyncOutcome.AlreadyInSync -> KdbxSyncResult.InSync
        is SyncOutcome.Uploaded -> KdbxSyncResult.Uploaded(newVersionToken)
        is SyncOutcome.NeedsUserDecision -> KdbxSyncResult.Conflict(currentRemoteVersion)
        is SyncOutcome.RemoteNewerNeedsReload -> KdbxSyncResult.NeedsReload
        is SyncOutcome.Failed -> KdbxSyncResult.Failed(reason)
    }
}

/**
 * 本地 SAF 来源的 origin 前缀。
 *
 * ⚠️ 抽成常量是为了让「本地 vs 网盘」这条判据**只有一处**
 * （[fileSourceFor] 的表 + [hasCloudSource] 都用它）。
 */
private const val SAF_ORIGIN_PREFIX = "content://"

/** 协调器层面的结果（形状与领域层 `KdbxSyncReport` 一致，少一次映射）。 */
sealed interface KdbxSyncResult {
    data object InSync : KdbxSyncResult
    data class Uploaded(val newVersionToken: String?) : KdbxSyncResult
    data object Downloaded : KdbxSyncResult
    data class Conflict(val currentRemoteVersion: String?) : KdbxSyncResult

    /**
     * ★ **本机内容不是远端那一版**，写回被基线拦下（#185）。
     *
     * 与 [Conflict] 并列但**不能合并**：冲突带得出"远端现在是哪一版"（用户点了
     * 「用远端覆盖本地」就有东西可拉），而这里**压根没问过远端** —— 必须让
     * 调用方先跑一次同步把远端拉下来/对账，再谈怎么合。
     * 合并成一个 `Conflict(null)` 会让"用远端覆盖本地"变成一个拉不到东西的死按钮。
     */
    data object StaleBaseline : KdbxSyncResult

    data object NeedsReload : KdbxSyncResult
    data object NoCloudSource : KdbxSyncResult
    data class Failed(val reason: String) : KdbxSyncResult
}
