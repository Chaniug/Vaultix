/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package io.vaultix.data.repository.kdbx

import io.vaultix.common.logging.VaultixLog
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.data.kdbx.isSameVersionToken
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 给**远端** [KdbxFileSource] 加一层本地缓存（多库锁模型定稿 **批次 B1**，2026-09-30）。
 *
 * ## 为什么（实测账）
 *
 * KDBX 解锁 3.58 s 里 ≈**3.4 s 是「读文件」**（`Kdbx.unlock` 先 `source.read()`
 * 再 KDF+解析；库在 OneDrive、设备无本地副本 ⇒ 每次解锁重下整份）。本层把这件事
 * 压成**一次元数据往返**（`stat()`，不下载内容）。
 *
 * ## 新鲜度判据：`versionToken`
 *
 * | 局面 | 行为 |
 * |---|---|
 * | 有缓存 且 `stat().versionToken` == 缓存令牌 | **命中，不下载** |
 * | 令牌变了 / 没有缓存 / 来源不给令牌（`null`） | 老实 `delegate.read()` 并更新缓存 |
 * | `stat()` 失败（离线 / 账号失效 / 服务端抽风） | **有缓存就回退缓存**（顺带获得离线解锁）；没有则**如实抛** |
 *
 * ⚠️ **`null` 令牌必须走「下载」这一支**：令牌是唯一能判定"缓存还是不是最新"的依据，
 * 拿不到它却声称命中，就会把一个**可能过期的库**静默交给用户（进而覆盖远端）。
 * 宁可慢一次，不可说谎一次 —— 与本项目「空有三态」是同一条纪律。
 *
 * ⚠️ **回退缓存只影响"能不能解锁"，不影响"能不能安全写回"**：推送侧仍走条件写
 * （`expectedVersion`），远端真的变了会抛 `KdbxFileConflictException` 让用户决定。
 *
 * ⚠️ **只应该包远端来源**（`content://` 本地的令牌是内容 SHA-256，算它就要读整份文件，
 * 缓存反而多做一次读）—— 挂载点见 `KdbxCloudSyncCoordinator.fileSourceFor`。
 */
internal class CachedKdbxFileSource(
    private val delegate: KdbxFileSource,
    private val cache: KdbxFileCache,
    private val cacheKey: String,
    /**
     * 后台校验用的 scope —— **必须是应用级**（不是 ViewModel / 页面作用域）。
     *
     * ⚠️ 为什么：`read()` 返回后 `unlock` 往往紧接着导航，页面作用域此时已销毁。
     * 用短生命周期 scope 会让校验在导航瞬间被取消 ⇒ 永远停在"未校验"，
     *   提示永远不出现，功能等于没做。
     */
    private val scope: CoroutineScope,
    /**
     * 后台校验发现「远端已变」时的回调（2026-10-05，折中的第二半）。
     *
     * ⚠️ **可空**：不是所有挂载点都知道 vaultId（例如按 origin 解析来源的那些路径）。
     *   为 null 时后台校验**只换缓存、不上报状态**——那样用户拿不到角标，
     *   等于退化成"裸奔"，所以调用方**应当**尽量传进来（见 `withRemoteCache`）。
     */
    private val onRemoteChanged: (suspend () -> Unit)? = null,
    /**
     * **上传成功**之后的回调（#233）：把"这份字节对应哪一版"这件事一起告诉上层。
     *
     * @param bytes 刚写上去的字节。
     * @param newVersion 服务端给的新版本令牌。
     *
     * ⚠️ 与 [onRemoteChanged] 是**两个方向**，不能合并：
     * 前者是"远端被别人改了"（要提醒用户），这里是"我把远端改了"（要收敛基线）。
     * 合并的话，一次成功上传会被报成"远端有更新"，用户看到一个假角标。
     */
    private val onUploaded: (suspend (ByteArray, String?) -> Unit)? = null,
    /**
     * **跨实例共享**的新鲜度容器（#233 修完 #232 之后补的，2026-11-02）。
     *
     * ## 为什么新鲜度不能住在实例里
     *
     * `KdbxCloudSyncCoordinator.fileSourceFor` **每次调用都新建**一个本类实例
     * （它的 KDoc 早就写明"本地 SAF 不缓存"，远端则每次现装一个装饰器）。
     * 而上传成功后的收敛是由**另一个**实例做的（编排器自己按 origin 又解析了一次）。
     * 于是"刚上传成功 ⇒ 缓存与远端一致"这个结论被写进了那个临时实例的
     * `_freshness`，随它一起被回收 —— 用户下一次解锁看到的仍是 `UNVERIFIED`，
     * 那条"内容可能不是最新的"提示因此永远不消失。
     *
     * ⚠️ 这不是可选的优雅改动：新鲜度是**关于这个 cacheKey 的事实**，
     * 不是"关于某个装饰器对象的状态"。同一把 key 的所有实例必须看到同一个值。
     *
     * @param freshHolder 由持有者（协调器）按 cacheKey 提供；默认自建一个（供直接构造用）。
     */
    private val freshHolder: FreshnessHolder = FreshnessHolder(),
) : KdbxFileSource {

    // ⚠️ 声明必须**在 read() 之前**：Kotlin 按声明顺序初始化，
    //    放在后面会让 read() 里引用到一个尚未初始化的 `_freshness`
    //    （编译报 "must be initialized" 与 "'val' cannot be reassigned"）。

    /**
     * 新鲜度状态（供 UI 提示「内容可能不是最新的」）。
     *
     * ⚠️ 这是**只读上报**，不是「同步状态」：它回答的是"你看到的字节是不是最新的"，
     * 与 `VaultSyncStatus` / `KdbxSyncStatus` 回答的"上次同步成没成"是两件事，
     * 不该混进那个模型（混了就会出现"没同步过却显示待上传"这类误报）。
     */
    enum class Freshness { VERIFIED, STALE, UNVERIFIED }

    /** 当前新鲜度（初始 [Freshness.UNVERIFIED] = 还没校验过）。跨实例共享，见 [freshHolder]。 */
    val freshness: StateFlow<Freshness> = freshHolder.state

    /** 防止同一轮里并发触发多次后台校验（`read()` 可能在解锁流程里被调多次）。 */
    private val recheckStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * ★★ **必须原样转发给 [delegate]**（2026-11-10 深夜修复）。
     *
     * ## 漏掉这一行的后果（真机：OneDrive 库「不能同步」+ 绿灯消失）
     *
     * `KdbxFileSource.isRemote` 是接口的**默认属性**，值写死 `false`（保守：
     * 新来源忘了覆写时宁可被当成"本地"）。而本类**没有 `by delegate`**，
     * 只逐个实现了方法 —— 于是**每一个套了缓存的来源都自称本地**。
     *
     * 而 `fileSourceFor` 对**所有**远端来源都套这一层（OneDrive / WebDAV 都走
     * `withRemoteCache`）⇒ 同步入口那道「有没有另一端」的闸门
     * （`KdbxSyncOrchestrator.localOnlyGate`）把它们**全部判成本地文件**：
     *
     * | 用户看到 | 真因 |
     * |---|---|
     * | 点同步 →「这是本地文件，没有需要同步的云端副本」 | 闸门走了 `LOCAL` 分支 |
     * | ⋮ 菜单里没有绿灯、卡片没有「已同步」那行 | 同一分支顺手把状态写成 `LOCAL_ONLY`，而 `LOCAL_ONLY` 在 UI 侧是"不显示" |
     * | 之后一直不亮 | 状态已是 `LOCAL_ONLY`，没有编辑动作就不会再被改写 |
     *
     * ⚠️ 这条**不能写死 `true`**：本类也可能包住本地来源（虽然当前唯一挂载点只包远端），
     *    写死会让"缓存包了本地文件"这种组合说谎。**转发**才是"语义随被装饰者走"。
     * ⚠️ 也别改成 `delegate.isRemote && true` 之类 —— 那是同一个错误换了个写法。
     */
    override val isRemote: Boolean get() = delegate.isRemote

    /** 元数据请求照旧直连（它本来就不下载内容）；也保证 `write` 前拿到的令牌是真的。 */
    override suspend fun stat(): KdbxFileStat = delegate.stat()

    // 来源可能是网络 / HTTP / IO / 解析，异常族无法穷举；这里要的是"**任何**读不到"
    // 都退化为读缓存，而不是分类处理 ⇒ 与 `Kdbx.unlock` 同款压制（那里也这么写）。
    @Suppress("TooGenericExceptionCaught")
    override suspend fun read(): ByteArray {
        val cached = runCatching { cache.load(cacheKey) }.getOrNull()

        // ★★ 2026-10-05：**有缓存就先用缓存解密，`stat()` 丢到后台**（用户拍板的「折中」）。
        //
        // ## 为什么改：真机实测证明 `stat()` 才是瓶颈，不是下载
        //
        // 第三次抓包（`VaultixKdbxCache` + `VaultixKdbx` 两个 TAG 一起过滤）实测：
        // ```
        // 00:47:35.008 缓存判定：cached=true(30701B,token=#32d46eea) 远端token=#32d46eea ⇒ 命中，不下载
        // 00:47:35.342 unlock 耗时分解：read=2837ms + open=334ms
        // ```
        // ⇒ **缓存一直在命中、B1 功能完全正常**；而「判定那行」到「unlock 完成」只隔 0.1–0.3s，
        //   `read` 却报 2837ms ⇒ **95% 的时间花在判定之前的 `stat()` 网络往返上**。
        //   OneDrive 的 Graph 元数据往返（`/me/drive/…`）和下载 30KB 差不多慢。
        //
        // ## 之前为什么不是这样
        //
        // 2026-09-30 的 B1 刻意做成「先 stat 保新鲜，再决定要不要下载」，是为了绝不把
        // **可能过期的库**交给用户（`CachedKdbxFileSource` 原 KDoc 写的是
        // 「拿不到令牌却声称命中，就会把一个可能过期的库静默交给用户」）。
        // 那个取舍在「stat 很快」的前提下是对的；实测证明 OneDrive 上 stat 要 1–2.8s，
        // 于是取舍**反转**了：为了 2 秒而阻塞整个解锁，代价远大于它防的风险。
        //
        // ## 现在怎么保证不丢数据（这是「折中」而非「裸奔」）
        //
        // ① **写入侧仍有条件写**：`write(bytes, expectedVersion)` 照旧带 eTag，
        //    远端真的变了会抛 `KdbxFileConflictException`（见 `write` 与
        //    `KdbxCloudSyncCoordinator` 的冲突对话框）⇒ **绝不会静默覆盖远端**。
        // ② **后台校验发现令牌变了** ⇒ 立刻重下并替换缓存，同时置 [freshness]，
        //    让 UI 能标出「内容可能不是最新的」（用户拍板的第二半）。
        // ③ **没有缓存时**（首次解锁 / 已被清理）仍走「先 stat 再下」，此时没有可牺牲的东西。
        //
        // ⚠️ **这是有意的取舍，不是 bug**：解锁瞬间可能展示旧内容。
        //   用户已知并接受（2026-10-05 拍板），且①保证了它不会被静默写回。
        if (cached != null) {
            markStalePending()
            return cached.bytes
        }

        // ⚠️ 没有缓存 ⇒ 没有可回退的东西，失败就**如实抛**（否则只能给用户一个空库）。
        //   顺带保住「取消不是读不到」这条分界（同 `Kdbx.unlock` 的取向）：不包 runCatching。
        val remote = delegate.stat()

        val token = remote.versionToken
        // ⚠️ 这里**必然下载**（走到这一行说明 `cached == null`），令牌只用于**写缓存**。
        //   判据本身没有消失：后台 `markStalePending` 每次都会重新 stat 一次并比对令牌，
        //   远端变了就把缓存换新并置 `Freshness.STALE`（UI 据此提示）。
        VaultixLog.d(TAG) {
            "无缓存（首次解锁 / 已被清理）⇒ 下载；写缓存用 token=${token.tag()}"
        }
        val bytes = delegate.read()
        // 缓存写失败不能影响解锁本身（缓存是**优化**，不是正确性前提）。
        // ⚠️ 但**失败必须留痕**：静默吞掉会让"缓存永远不生效"这类问题查不出来。
        runCatching { cache.save(cacheKey, CachedKdbxFile(bytes, token)) }
            .onSuccess {
                VaultixLog.d(TAG) {
                    "已写缓存（${bytes.size}B, token=${token.tag()}）"
                }
            }
            .onFailure { error ->
                VaultixLog.w(TAG) { "写缓存失败（不影响本次解锁）：${error.message}" }
            }
        return bytes
    }

    /**
     * ★ 读**远端此刻**的字节：绕过缓存，并把这一版登记为新的缓存基线（2026-11-03）。
     *
     * ## 为什么必须覆写（父接口有默认实现）
     *
     * 默认实现是 `read()` —— 而本类的 `read()` **有缓存就直接 return 缓存字节**。
     * 于是"要远端现在是什么"的调用方拿到的是旧内容，具体后果见
     * [KdbxFileSource.readRemoteCurrent] 的 KDoc（对账误判"已同步"、拉取拉到旧版）。
     *
     * ## 三件事的顺序都是有理由的
     *
     * 1. **先 `stat()` 后 `read()`**：读到字节之后远端可能又被人改了一版，那时再 stat
     *    拿到的是**更新的**令牌，而字节是旧的 —— 把新令牌配旧字节写进缓存，就等于宣称
     *    "这份旧的已经是那一版"，那一版从此**再也不会被拉**。
     *    反过来令牌偏旧是安全的：下次同步只会再报一次"远端有更新"，多拉一次而已。
     * 2. **读失败如实抛**：这里没有"回退缓存"这条退路 —— 调用方问的就是远端。
     *    给旧字节等于把它们重新带回上面那个缺陷。
     * 3. **回写缓存**：不回写的话，拉取成功后会话是新的、缓存还是旧的 ⇒
     *    **下一次解锁又是旧内容**（用户会以为"同步没生效"，然后再改一笔把旧内容推回去）。
     *
     * ⚠️ 故意**不碰** [freshness]：它回答的是"用户此刻看到的内容是否最新"，
     *   而本方法只管缓存；会话有没有换成这一版由调用方负责（拉取路径会
     *   `replaceSession` 并 `bump`）。在这里置 `VERIFIED` 会在"对账读了远端、
     *   但会话仍是旧的"那一瞬说谎。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun readRemoteCurrent(): ByteArray {
        // stat 只为给缓存配对令牌；拿不到就以 null 落缓存（下次后台校验会重下一次，安全）。
        // ⚠️ 取消必须**原样抛**：它表示"这次不要了"，不是"读不到"（同 `Kdbx.unlock` 的取向）。
        val token = try {
            delegate.stat().versionToken
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // 拿不到令牌不影响读字节；但**必须留痕** —— 静默吞掉会让"缓存令牌老是 null、
            // 每次解锁都白下一次"这类问题再也查不出来（只记原因，不记内容）。
            VaultixLog.d(TAG) { "远端令牌取不到（缓存记 null，下次校验会重下）：${error.message}" }
            null
        }

        // ★ 短路：后台校验（[markStalePending]）发现远端变了时，已经把那一版字节
        //   连同令牌写进缓存。只要本次 `stat()` 的令牌与缓存令牌一致，就**证明**
        //   缓存即远端此刻的字节，不必再整份下载。
        //   真机 2026-11-04 18:08：同一份 35014B 被下了 3 次（后台校验 / 点同步 / 解码），
        //   其中至少两次是纯代码造成的重复，与网络波动无关。
        //
        // ⚠️ 判据必须是「令牌一致」而不是「有缓存」：缓存是旧版时老老实实下载，
        //    否则会重演 #172「提示已下载 / 已同步，条目还是旧的」——那次正是
        //    `read()` 无条件拿缓存冒充远端，把基线推进到新令牌却换了旧会话。
        //    令牌取不到（null）同样不许短路，fail-closed。
        val cached = runCatching { cache.load(cacheKey) }.getOrNull()
        val cachedBytes = cached?.bytes?.takeIf { it.isNotEmpty() }
        // 拆成两个两条件判断：合起来 4 个条件会顶破 detekt ComplexCondition（上限 3）。
        val tokenMatches = token != null && isSameVersionToken(token, cached?.versionToken)
        // ★★ 11-07 真机事故：令牌一致**不能**证明缓存就是远端那一版。
        //    `persist` 写缓存时沿用旧令牌，于是「远端没变 + 本地刚改过」也会 tokenMatches。
        //    此时若短路返回缓存，内容对账拿到的其实是本地改动字节 ⇒ 对账恒等 ⇒
        //    真冲突被判成「已同步」，改动永远推不上去。
        val hasLocalEdits = cached?.pendingLocalEdits == true
        if (cachedBytes != null && tokenMatches && !hasLocalEdits) {
            VaultixLog.d(TAG) {
                "缓存已是远端当前版本（${cachedBytes.size}B）⇒ 跳过整份下载"
            }
            return cachedBytes
        }

        val bytes = delegate.read()
        // ★★ #286（2026-11-12）：[hasLocalEdits] 是**下载之前**读的快照，而
        //   `delegate.read()` 真机要 1–10 s。下载期间用户完全可以编辑一笔，
        //   `persist` 会写一份带 pendingLocalEdits 的脏缓存 —— 那个快照已经过期。
        //   照旧回写就会用远端**旧字节**盖掉刚落盘的本地改动，且协调器那道
        //   「下载之后」的闸门管不到装饰器内部的这次写（会话保住了，缓存没了）。
        // ⇒ 回写前**现查一次**脏标记。⚠️ 读缓存失败也不写（fail-closed）：
        //   「没有缓存」（首次下载，允许写）与「读失败」（不许写）必须分开，
        //   所以不能用 `getOrNull()` 一个值把两者混掉。
        val loadNow = runCatching { cache.load(cacheKey) }
        val dirtyNow = loadNow.getOrNull()?.pendingLocalEdits == true
        val loadFailed = loadNow.isFailure
        // ⚠️ 本地有未上传改动时**绝不回写缓存**：那会用远端旧字节盖掉用户刚编辑的内容。
        //    缓存保持 dirty，直到自动上传成功由 `write()` 写回干净版本。
        if (!hasLocalEdits && !dirtyNow && !loadFailed) {
            runCatching { cache.save(cacheKey, CachedKdbxFile(bytes, token)) }
                .onSuccess {
                    VaultixLog.d(TAG) {
                        "已用远端当前字节换缓存（${bytes.size}B, token=${token.tag()}）"
                    }
                }
                .onFailure { error ->
                    VaultixLog.w(TAG) { "换缓存失败（不影响本次读到的字节）：${error.message}" }
                }
        } else {
            VaultixLog.d(TAG) { "缓存含本地未上传改动或读取失败 ⇒ 真读远端，且不覆盖缓存" }
        }
        return bytes
    }

    /**
     * 后台校验：拿缓存解密之后**异步**做一次 `stat()`，令牌变了就换新缓存。
     *
     * 为什么**只换缓存、不动已解密的内容**：此刻用户正在看这个库，
     * 悄悄换掉底层字节而 UI 不动，只会造成「列表与详情对不上」这种更糟的问题。
     * ⇒ 把「内容已过期」这件事**如实上报**（[freshness]），由 UI 决定提示与重载。
     *
     * 失败一律吞掉：这条路径是**优化**，失败等价于「没校验」，不是错误。
     */
    private fun markStalePending() {
        if (!recheckStarted.compareAndSet(false, true)) return
        scope.launch {
            // ★ `finally` 复位而不是在末尾平铺一句（2026-11-04）。
            //
            // 这个 `AtomicBoolean` 是**一次性闸门**：置 true 后若没被复位，后台校验
            // 从此再也不跑（`compareAndSet` 永远失败）。而 2026-11-04 起本类实例
            // 按 origin **复用**（见 `KdbxCloudSyncCoordinator.withRemoteCache`）——
            // 以前"每次新建实例"会顺带把闸门重置，这个缺陷是隐形的；复用之后
            // 一次粘住就是**永久粘住**，"内容可能不是最新"的判定从此彻底停摆。
            //
            // 会让它粘住的路径不止"抛异常"：协程被取消（作用域销毁）同样跳过后面的语句，
            // 而取消是**正常事件**，不是错误。⇒ 只有 `finally` 能覆盖全部出口。
            try {
                val remote = runCatching { delegate.stat() }.getOrNull()
                val remoteToken = remote?.versionToken
                val current = runCatching { cache.load(cacheKey) }.getOrNull()
                when {
                    // 校验失败 / 来源不给令牌 ⇒ 保持现状，但要能被 UI 看到"没校验成"。
                    remoteToken == null || current == null -> {
                        freshHolder.set(Freshness.UNVERIFIED)
                    }
                    // 远端变了 ⇒ 换新缓存，并如实标成"已过期"（UI 提示用）。
                    //
                    // ⚠️ 必须走 `isSameVersionToken` 而不是 `!=` 裸比较（2026-11-04 修）。
                    //   令牌有**三种写法**：`ctag:…` / `etag:…` / 无前缀的裸值（迁移前的老缓存），
                    //   且 OneDrive 的返回值自带引号。裸比较只要有一处写法差一个字，
                    //   就会判成"远端变了" ⇒ **每次解锁都白下一次整库**、
                    //   还会把一个**根本不存在的**「云端有更新」角标挂上去。
                    //   `isSameVersionToken` 就是 #244 为这件事写的（剥前缀 + 剥引号 + 前缀冲突保守判异）。
                    !isSameVersionToken(remoteToken, current.versionToken) -> {
                        runCatching { delegate.read() }
                            .onSuccess { fresh ->
                                // ⚠️ 本地有未上传改动时不许换缓存（会静默抹掉用户编辑）；
                                //    只如实上报"远端变了"，由同步流程去报冲突。
                                if (!current.pendingLocalEdits) {
                                    runCatching { cache.save(cacheKey, CachedKdbxFile(fresh, remoteToken)) }
                                }
                                freshHolder.set(Freshness.STALE)
                                // ⚠️ 换掉缓存**之后**才上报：先让字节就位，
                                //   否则 UI 可能在状态已变、缓存还是旧的瞬间点同步而拉到旧内容。
                                runCatching { onRemoteChanged?.invoke() }
                                VaultixLog.d(TAG) {
                                    if (current.pendingLocalEdits) {
                                        "后台校验：远端已变（${fresh.size}B），缓存含本地改动 ⇒ 保留缓存并上报"
                                    } else {
                                        "后台校验：远端已变（${fresh.size}B），已换新缓存；当前会话仍是旧内容"
                                    }
                                }
                            }
                            .onFailure { freshHolder.set(Freshness.UNVERIFIED) }
                    }
                    else -> {
                        freshHolder.set(Freshness.VERIFIED)
                        VaultixLog.d(TAG) { "后台校验：缓存与远端一致" }
                    }
                }
            } finally {
                recheckStarted.set(false)
            }
        }
    }

    /**
     * 令牌的诊断标记：**长度 + 短哈希**（够判定"两次是不是同一个"，又不落原文）。
     * `null` 单独标记 —— 它代表"来源不给令牌"，是三类未命中原因里最需要区分的一类。
     */
    private fun String?.tag(): String = when {
        this == null -> "null"
        isEmpty() -> "空串"
        else -> "#${"%08x".format(hashCode())}(len=$length)"
    }

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
        failIfExists: Boolean,
    ): KdbxFileWriteResult {
        // 冲突时 `delegate.write` 抛 `KdbxFileConflictException` ⇒ 根本走不到下面，
        // 缓存不会被污染成"远端其实是旧的"。
        //
        // ⚠️ `failIfExists` **必须原样透传**：本类只是一个缓存装饰器，
        //    在这里把它吞掉就等于把"只创建"的保险丝剪断，
        //    而被装饰的远端来源正是最需要那根保险丝的地方（ISSUES #172）。
        val result = delegate.write(bytes, expectedVersion, force, failIfExists)
        runCatching { cache.save(cacheKey, CachedKdbxFile(bytes, result.versionToken)) }
        // ★ 通知上层：`vaults.remoteVersionToken` 与缓存里的令牌在这一刻**必须**收敛（#233）。
        //   ⚠️ 失败不影响本次写入的结论（already 落盘了），但必须留痕。
        onUploaded?.let { callback ->
            runCatching { callback(bytes, result.versionToken) }
                .onFailure { error ->
                    VaultixLog.w(TAG) { "上传成功后的收敛回调失败（不影响本次上传）：${error.message}" }
                }
        }
        return result
    }

    /**
     * **上传成功之后**，把缓存里的版本令牌与新鲜度一起刷新（#233）。
     *
     * ## 为什么必须有这一步（一条已经发生过的"假角标"链）
     *
     * 条目保存走的是 `KdbxItemRepository.persist`：它把新字节写进缓存时**刻意沿用旧令牌**
     * （那是下一次条件写的基线），然后标 `PENDING_UPLOAD`、自动上传器立刻跑一轮。
     * 那一轮真的推上去了，`KdbxSyncOrchestrator` 也把 `vaults.remoteVersionToken`
     * 更新成了服务端给的新 eTag —— 但**缓存里那个令牌没动**。
     *
     * 于是下一次解锁：`read()` 有缓存就直接用，而后台校验拿"远端新 eTag"去比
     * "缓存里那个旧令牌" ⇒ **永远不等** ⇒ 重下一次内容完全相同的整库、
     * 把 `freshness` 置成 `STALE`、调 `onRemoteChanged` ⇒ 状态变成 `REMOTE_CHANGED`。
     *
     * 用户什么都没改，界面却一直挂着「远端有更新」（那是**要人处理**的角标），
     * 而且每次解锁都白下一次。根因是**同一件事存了两份、只更新一份**。
     *
     * ## 为什么可以直接置 [Freshness.VERIFIED]（这不是猜）
     *
     * 刚刚写上去的就是 [bytes]，服务端回的新令牌就是它对应的版本 ⇒ 缓存现在装的
     * **正是**远端那一版。"缓存与远端一致"在这一刻是**已知事实**，不是推断。
     * 再去 `stat()` 一次只会多一个网络往返，还会把"刚上传成功"降级成"待校验"。
     *
     * ## ⚠️ 为什么是 `launch` 而不是 `suspend`
     *
     * 调用方是 [KdbxSyncOrchestrator] —— 纯决策层，它的 `sync` 保持同步签名
     * （无 suspend）是那条路径可单测的前提。一次本地缓存写不该把它拖成挂起函数。
     * 代价：刷新与"记状态"之间有极小窗口，此时若正好解锁可能仍读到旧缓存 ——
     * 后台校验会**如实**判成 `STALE` 并重下一次，属于"多跑一次"而不是"报错状态"。
     *
     * ## ⚠️ 缓存写失败不影响任何结论
     *
     * 缓存是**优化**，不是正确性前提；但失败必须留日志 ——
     * 静默吞掉会让"缓存永远不更新"这类问题再也查不出来。
     *
     * @param newVersionTag 上传成功后服务端给的**新** `versionToken`。
     */
    fun recordUploaded(bytes: ByteArray, newVersionTag: String?) {
        scope.launch {
            runCatching { cache.save(cacheKey, CachedKdbxFile(bytes, newVersionTag)) }
                .onFailure { error ->
                    VaultixLog.w(TAG) { "上传成功后刷新缓存失败（不影响本次上传）：${error.message}" }
                }
            // ⚠️ 无论缓存写没写成：这份字节就是远端那一版，新鲜度是**事实**。
            freshHolder.set(Freshness.VERIFIED)
            VaultixLog.d(TAG) {
                "上传成功，缓存令牌已刷新为 ${newVersionTag.tag()}"
            }
        }
    }

    override suspend fun listChildren(): List<KdbxFileEntry> = delegate.listChildren()

    override suspend fun testConnection(): Result<Unit> = delegate.testConnection()

    private companion object {
        const val TAG = "VaultixKdbxCache"
    }
}

/**
 * 按 `cacheKey` 共享的新鲜度容器（#233）。
 *
 * 为什么需要它：见 [CachedKdbxFileSource] 的 `freshHolder` 参数 ——
 * 装饰器是**每次解析都新建**的，而新鲜度是关于"这份缓存"的事实。
 * 放在实例里 ⇒ 收敛写进去的值随实例一起消失，UI 永远看到 `UNVERIFIED`。
 *
 * ⚠️ 它是**可变的单格状态**，只应在装饰器持有者（协调器）里作为 map 的值存在；
 * 别把它当全局单例 —— 不同 cacheKey 的库必须互不影响。
 */
internal class FreshnessHolder {
    private val _state = MutableStateFlow(CachedKdbxFileSource.Freshness.UNVERIFIED)

    val state: StateFlow<CachedKdbxFileSource.Freshness> = _state.asStateFlow()

    fun set(value: CachedKdbxFileSource.Freshness) {
        _state.value = value
    }
}
