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
            val remote = runCatching { delegate.stat() }.getOrNull()
            val remoteToken = remote?.versionToken
            val current = runCatching { cache.load(cacheKey) }.getOrNull()
            when {
                // 校验失败 / 来源不给令牌 ⇒ 保持现状，但要能被 UI 看到"没校验成"。
                remoteToken == null || current == null -> {
                    freshHolder.set(Freshness.UNVERIFIED)
                }
                // 远端变了 ⇒ 换新缓存，并如实标成"已过期"（UI 提示用）。
                remoteToken != current.versionToken -> {
                    runCatching { delegate.read() }
                        .onSuccess { fresh ->
                            runCatching { cache.save(cacheKey, CachedKdbxFile(fresh, remoteToken)) }
                            freshHolder.set(Freshness.STALE)
                            // ⚠️ 换掉缓存**之后**才上报：先让字节就位，
                            //   否则 UI 可能在状态已变、缓存还是旧的瞬间点同步而拉到旧内容。
                            runCatching { onRemoteChanged?.invoke() }
                            VaultixLog.d(TAG) {
                                "后台校验：远端已变（${fresh.size}B），已换新缓存；当前会话仍是旧内容"
                            }
                        }
                        .onFailure { freshHolder.set(Freshness.UNVERIFIED) }
                }
                else -> {
                    freshHolder.set(Freshness.VERIFIED)
                    VaultixLog.d(TAG) { "后台校验：缓存与远端一致" }
                }
            }
            recheckStarted.set(false)
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
