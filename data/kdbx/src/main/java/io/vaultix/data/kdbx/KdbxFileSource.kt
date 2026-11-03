/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 文件来源抽象** —— 让「本地 SAF / OneDrive / WebDAV」三种库源
 * 在读写两侧共用同一套接口。
 *
 * ## 为什么要另起一个接口（而不是把 `KdbxSource` 加两个方法）
 *
 * 现有 `KdbxSource` 是 `fun interface { fun read(uri): ByteArray? }` ——
 * **同步、只能读、没有版本令牌、没有错误细分**。而网盘接入有三件它答不了的事：
 *
 *  1. **写**（`write`）——「同步」只有读就只是半条腿；
 *  2. **版本令牌**（`stat().versionToken`）—— 条件写（`If-Match`）与冲突检测的地基，
 *     没有它就只能"先查再写"，而那个窗口正是**静默覆盖**的入口（TOCTOU）；
 *  3. **可区分的失败**（挂起函数 + 异常）—— 「网络超时」与「密码错」的用户动作不同。
 *
 * ⇒ 起一个**新接口**，`KdbxSource` 原样留着（旧调用点一行都不用改）。
 *   这与方案 §4.2 的"新旧并存两步走"一致：**不做一次性大改**。
 *
 * ## 为什么这个文件在纯 Kotlin 模块
 *
 * `data:kdbx` 不碰 Android（见 `Kdbx` 的文件头说明）。接口里因此**没有任何
 * `Uri` / `Context` / `ContentResolver`** —— 那些只出现在 Android 侧的实现里
 * （`SafKdbxFileSource` 在 `data:repository`，OneDrive/WebDAV 在各自 provider）。
 * 附带好处：本接口的所有实现都能在 JVM 单测里喂假数据。
 *
 * ## ★ 版本令牌（`versionToken`）到底是什么
 *
 * 它是**服务端给的、能代表"文件当前这一版"的不透明字符串**：
 *
 * | 来源 | versionToken 取什么 |
 * |---|---|
 * | OneDrive | `eTag`（兜底 `cTag`） |
 * | WebDAV | **归一化后**的 `ETag`（剥 `W/` 与引号，见下） |
 * | 本地 SAF | **内容 SHA-256**（`lastModified` 在同秒内多次修改时看不出差异，不可靠） |
 *
 * 调用约定：**上一次 `stat()`/`read()` 拿到的值原样传回 `write(expectedVersion=…)`**。
 * 实现方负责把它翻译成 `If-Match` 之类的条件头 —— 让**服务端**去判定
 * "我手上的这版还是最新的吗"，而不是让客户端猜。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

/**
 * 一个文件来源的当前状态快照。
 *
 * @property versionToken ★ 条件写用的**版本令牌**（见文件头说明）。null = 该来源
 *   不提供版本信息（此时写路径必须退化为"写前预检 + 写后读回校验"，且**不能**
 *   声称"已消除并发覆盖风险"）。
 * @property lastModified 上次修改时间的**毫秒**时间戳（仅用于展示与排序，**不参与判重**）。
 * @property sizeBytes 文件大小（字节）；null = 未知。
 * @property remoteId 服务端的稳定标识（OneDrive `itemId`）。本地来源可为 null。
 * @property displayName 展示名（文件名）。
 */
data class KdbxFileStat(
    val versionToken: String? = null,
    val lastModified: Long? = null,
    val sizeBytes: Long? = null,
    val remoteId: String? = null,
    val displayName: String? = null,
) {
    /**
     * 该来源是否具备**真正的**条件写能力。
     *
     * ⚠️ 判据是"有没有版本令牌"，不是"接口有没有 `write`"：
     * 没有令牌时 `write(expectedVersion=…)` 只能被忽略，那等于无条件覆盖。
     * UI 与同步状态机据此决定要不要提示用户"该来源的并发保障较弱"。
     */
    val supportsConditionalWrite: Boolean get() = !versionToken.isNullOrBlank()
}

/** 一次写入之后服务端回给我们的**新**状态。 */
data class KdbxFileWriteResult(
    /** 写入后该文件的版本令牌（下次条件写要用这个，**不是**写之前那个）。 */
    val versionToken: String? = null,
    val lastModified: Long? = null,
    val sizeBytes: Long? = null,
    val remoteId: String? = null,
)

/** 目录里的一个条目（供"选库"列表用）。 */
data class KdbxFileEntry(
    val name: String,
    val id: String,
    val isDirectory: Boolean,
    val versionToken: String? = null,
    val lastModified: Long? = null,
    val sizeBytes: Long? = null,
) {
    /** 是否是 KeePass 数据库（大小写不敏感 —— 各网盘两种写法都常见）。 */
    val isKdbx: Boolean get() = !isDirectory && name.endsWith(".kdbx", ignoreCase = true)
}

/**
 * KDBX 库的文件来源。
 *
 * ⚠️ 全部是 `suspend`：三个实现里有两个要走网络，**同步签名会把网络等待压在调用线程上**。
 *
 * ⚠️ 实现方**不允许**在失败时返回"看起来正常的空值"（如空 `ByteArray`）——
 * 那会让上层把"读不到"当成"库是空的"，进而**写回一个空库覆盖用户数据**。
 * 失败一律抛异常（或由调用方包在 `runCatching` 里）。
 */
interface KdbxFileSource {

    /**
     * 取当前状态（不下载内容）。
     *
     * 用途：① 判断文件在不在；② 拿 `versionToken` 供条件写；③ 列表展示大小/时间。
     */
    suspend fun stat(): KdbxFileStat

    /** 读全部字节。失败抛异常（**不要**返回空数组）。 */
    suspend fun read(): ByteArray

    /**
     * 读**远端此刻**的那一份字节（可能是一次真实下载）。
     *
     * ## 为什么 [read] 不够（这是已经发生过的真实缺陷，2026-11-03）
     *
     * [read] 的契约是"把这个库给我"，**没有**说"必须来自远端"。生产环境里所有
     * 远端来源都套了一层缓存装饰器（`CachedKdbxFileSource`，为解锁提速，实测
     * 3.58 s 里 3.4 s 原本花在下载上），它的 `read()` 在**有缓存**时直接返回缓存
     * 字节、一个网络请求都不发；离线时也回退缓存。
     *
     * ⇒ 拿 `read()` 当"远端当前字节"的两个调用点都拿到了**旧字节**：
     *
     * 1. `KdbxSyncOrchestrator.adoptRemoteIfSameContent`（内容对账）：
     *    用旧字节跟旧会话比 ⇒ 必然"相同" ⇒ 判「已同步」并把基线推进到**新**令牌
     *    ⇒ 远端的新内容**再也不会被拉下来**；更糟的是下一次本地一改，还会用
     *    "旧内容 + 新改动"去覆盖远端（基线对得上，条件写会通过）。
     * 2. `KdbxCloudSyncCoordinator.resolveUsingRemote`（点「同步」/「用远端覆盖」）：
     *    拉下来的"远端"其实是缓存里的旧版 ⇒ 会话被旧内容替换、基线却记成新令牌
     *    ⇒ 界面提示"已下载"而条目还是旧的，且此后**永远**判不出远端有更新。
     *
     * ## 什么时候用哪个
     *
     * | 调用方的意图 | 用哪个 |
     * |---|---|
     * | "把这个库打开给我 / 内容读出来就行" | [read]（缓存命中正是想要的） |
     * | "远端**现在**是什么？"（对账 / 拉取 / 冲突取字节） | ★ 本方法 |
     *
     * ⚠️ 默认实现退化为 [read]：对**不缓存**的来源（本地 SAF）两者本就同义
     *   （它的 `read()` 每次都真读文件）。带缓存的装饰器**必须覆写** ——
     *   覆写是契约的一部分，不是优化。
     * ⚠️ 代价是**整份下载**（真机 1–10 s，取决于库大小与网络），别在热路径上随手调。
     */
    suspend fun readRemoteCurrent(): ByteArray = read()

    /**
     * 写入（覆盖或创建）。
     *
     * @param expectedVersion ★ **上次 [stat]/[read] 拿到的 `versionToken`**。
     *   - 非空且来源支持条件写 ⇒ 服务端在 HTTP/文件系统边界强制"必须还是这一版"，
     *     不匹配就抛 [KdbxFileConflictException]（**这才是真正消除 TOCTOU 的做法**）；
     *   - `null` ⇒ 调用方明确表示"我没有基线"。实现方**不要**把它当成"随便覆盖"的许可，
     *     而是退化为可用的最严保障（写前预检 + **写后读回校验**），
     *     并在发现被改动时抛 [KdbxFileConflictException]。
     * @param force ★ **无条件强写**（见下）。
     * @param failIfExists ★ **只创建**：目标已存在则失败，绝不覆盖。
     *   见下方四象限表 —— 它与 `expectedVersion = null` **不是一回事**。
     * @return 写入后的新状态（含**新的** `versionToken`）。
     *
     * ## ★ 四个参数的语义象限（别混）
     *
     * | 组合 | 含义 | 谁会用 |
     * |---|---|---|
     * | `failIfExists = true` | "**这是新文件**，已存在就报错" | 新建库（`Kdbx.createVault`） |
     * | `expectedVersion != null` | "必须还是这一版" | 正常保存（条件写） |
     * | `force = true` | "**别检查，直接盖**" | 用户在冲突对话框里明确选了「用本地覆盖远端」 |
     * | 全默认（`null` + false + false） | "我没带基线"，实现方退化为最严保障 | 本地 SAF 主写路径 |
     *
     * ⚠️ **为什么 `failIfExists` 必须是一个独立的参数，而不是靠 `expectedVersion = null` 推断**
     *
     * 这正是 2026-10-17 修掉的那个缺陷的成因（ISSUES #172）：
     * "忘了传基线"是一个**编码疏忽**，"这是新文件、不许覆盖"是一个**明确意图**。
     * 把两者压进同一个 `null`，疏忽就会静默降级成"无条件覆盖"——OneDrive 的简单上传
     * 在 `expectedVersion = null` 时**一个条件头都不带**，于是同名库被静默盖掉。
     * 与 `force` 单独存在的理由完全同构：风险差着量级的两件事，就必须是两个名字。
     *
     * ⚠️ `force = true` 时其余两个参数被忽略，实现方**不要**再发条件头
     * （发了必然失败——远端确实变了，这正是用户要覆盖的那次改动）。
     */
    suspend fun write(
        bytes: ByteArray,
        expectedVersion: String? = null,
        force: Boolean = false,
        failIfExists: Boolean = false,
    ): KdbxFileWriteResult

    /** 列当前目录的子项（供选库）。来源不支持时返回空列表。 */
    suspend fun listChildren(): List<KdbxFileEntry> = emptyList()

    /**
     * 连通性自检（配置保存前调一次，避免"保存了但根本连不上"）。
     *
     * @return 失败的 `Throwable` 应带**可展示**的 message（"账号或密码不对"而不是
     *   "HTTP 401"）—— 它会被直接拼进 UI 提示。
     */
    suspend fun testConnection(): Result<Unit>
}

/**
 * 远端已被别的客户端改动 —— **本次写入被服务端拒绝，用户数据一个字都没被覆盖**。
 *
 * ## 为什么值得一个专门的异常类型
 *
 * 它是**唯一一种"失败了好事"**的结果：说明条件写起作用了。
 * 上层（同步状态机）见到它要做的不是"重试"，而是**转 `CONFLICT` 状态并让用户决定**
 * （方案 §§8 方案 B）。混进普通 `IOException` 里的话，UI 只会说"保存失败，请重试"，
 * 而**重试会一直失败**，用户完全不知道该怎么办。
 *
 * @param currentVersion 远端**现在**的版本令牌（null = 该来源拿不到）。
 *   留给冲突处理用：用户选"用远端覆盖本地"时，这一版就是他要的。
 * @param cause 原始异常（如 OkHttp 侧的 412 异常）。
 *
 *   ⚠️ 有 `cause` 这条不是可有可无：本异常的作用是**把 HTTP 层面的失败翻译成一句
 *   人话**给上层用。若翻译时把原异常丢掉，就再也查不出"到底是哪个请求、
 *   服务端原话是什么"—— 而那正是排查同步问题的唯一线索。
 */
class KdbxFileConflictException(
    val currentVersion: String?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * **本机内容已不是远端那一版** —— 写出之前就被自己拦下了，网络与文件一个字都没动。
 *
 * ## 与 [KdbxFileConflictException] 的区别（两者都是"拒写"，但成因与结局不同）
 *
 * | | 谁发现的 | 成因 | 用户动作 |
 * |---|---|---|---|
 * | [KdbxFileConflictException] | 服务端（412 / If-Match 失配） | 远端**在我们发出请求之前**变了 | 拉下来，或明确选择覆盖 |
 * | 本异常 | **我们自己**（内容指纹） | 本机内容从一开始就不是远端那一版（远端变过而我们没拉） | 同上 —— 但**不必先白跑一次网络写入** |
 *
 * 为什么值得单独一个类型：它不是"失败"，是**最后一道防线的主动拦截**。
 * 若混进 `IoError`，UI 只会说"保存失败，请重试"，而重试永远失败；
 * 若混进 `Conflict` 也不行 —— 冲突对话框的依据是"远端**现在**是哪一版"，
 * 而这里我们**没有去问远端**（问了就等于承认可以继续写）。
 *
 * ⚠️ 它同时是 #185 那条"令牌对得上 ≠ 内容来源对得上"的**可观测证据**：
 * 出现本异常，说明确实有一次 REMOTE_CHANGED 被漏掉了。
 */
class KdbxStaleBaselineException(
    val vaultId: String,
) : Exception("本机内容不是云端那一版（云端有更新还没拉下来），为避免覆盖云端改动，已停止保存")

/**
 * 版本令牌归一化 —— **WebDAV 的必踩坑，但放在通用位置让所有来源共用**。
 *
 * ## 为什么必须归一化
 *
 * RFC 7232 的 ETag 有两种形态，而服务端的支持参差不齐：
 * - **强校验**：`"abc123"`（带引号，无前缀）
 * - **弱校验**：`W/"abc123"`
 *
 * 实测坑（方案 §6.2 坑 3）：**直接把原始 ETag 拿去当 `If-Match` 的值，
 * 部分服务器会以 412 拒绝**（它认的是不带引号、不带 `W/` 的那个形态）；
 * 反过来做字符串比对时，`W/"x"` 与 `"x"` 明明指同一版却比不相等，
 * 会让程序**误报冲突**（用户看到一个根本不存在的冲突，然后开始怀疑数据）。
 *
 * ⇒ 统一剥掉 `W/` 前缀与包裹的双引号，只留核心值与来源比较。
 *   ⚠️ 归一化后**弱校验的语义确实变弱了**（原本允许内容等价即通过），
 *   但 WebDAV 场景下我们只拿它做"和服务端说同一句话"，这个取舍是稳的。
 *
 * ## ★ `ctag:` / `etag:` 来源前缀（#244）
 *
 * 前缀必须在**剥引号之前**摘掉，否则 `ctag:"abc"` 会被当成一个整体，
 * `removeSurrounding("\"")` 从两端都匹配不上，剥出一半引号（`ctag:"abc`）——
 * 比不剥更糟：它既不等于旧值也不等于新值，**每次同步都判"远端变了"**。
 */
fun normalizeVersionToken(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed
        .removePrefix("W/")
        .removePrefix("w/")
        .trim()
        // ⚠️ 顺序不能与下面两行的剥引号调换 —— 见上面 KDoc 的 `ctag:"abc"` 例子。
        .removePrefix(TAG_PREFIX_CTAG)
        .removePrefix(TAG_PREFIX_ETAG)
        .trim()
        .removeSurrounding("\"")
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * 「这两个令牌是不是**同一版**」—— 归一化 + 跨格式兼容的**唯一**判据。
 *
 * ## 为什么不直接用 `normalizeVersionToken(a) != normalizeVersionToken(b)`
 *
 * 令牌的**来源字段**在 2026-11-04（#244）改过：OneDrive 从"裸 eTag" 换成
 * "`ctag:` 前缀 + cTag"（理由见 `OneDriveGraphClient.versionTokenOf`）。
 * 直接字符串比较会让**每一个已存在的库**在升级后第一次同步就判成"远端变了"
 * —— 用户看到的就是"我什么都没改，它说云端有更新/冲突"。
 *
 * ## 兼容规则（只做**减法**，绝不做"看起来差不多就算一样"的猜测）
 *
 * 1. 两边归一化后完全相同 ⇒ 同一版（常规路径）。
 * 2. 只有一边带 `ctag:` / `etag:` 前缀 ⇒ 去掉前缀再比。
 *    这是**格式迁移**，不是"内容等价"：裸值时代存的就是 cTag（`eTag ?: cTag`
 *    在 OneDrive 上传响应里 eTag 总是有的，所以实际写进去的是 eTag —— 见下）。
 *    ⇒ 只有 `ctag:`/`etag:` **同类前缀**才允许去掉后比较，且仅在旧值无前缀时生效。
 * 3. 两边前缀**不同**（`ctag:` vs `etag:`）⇒ **不同版**。它们是两个不同字段，
 *    把它们当同一版等于凭空宣布"内容没变"，那正是要避免的假阴性。
 *
 * ⚠️ 规则 2 的代价：迁移后的**第一次**比较，旧库的裸 eTag 与新 cTag 在内容没变时
 * 仍可能不相等（eTag ≠ cTag）。那一次会走到 `KdbxSyncOrchestrator` 的
 * **内容对账**兜底（字节相同 ⇒ 不算冲突，并顺手把基线换成新格式），
 * 于是只多下载一次、不会多弹一次冲突。
 */
fun isSameVersionToken(a: String?, b: String?): Boolean {
    val leftTag = tagPrefixOf(a)
    val rightTag = tagPrefixOf(b)
    val left = normalizeVersionToken(a)
    val right = normalizeVersionToken(b)
    // 两边都为空 = 都"没有版本"，视为同一状态（首次同步前的典型形态）。
    if (left == null || right == null) return left == null && right == null
    // 前缀不同（两个都带前缀但字段不同）⇒ 保守判为不同版。
    if (leftTag != null && rightTag != null && leftTag != rightTag) return false
    return left == right
}

/**
 * 令牌的**来源字段前缀**（`ctag:` / `etag:`），没有则 null。
 *
 * ⚠️ 必须读**原始值**：`normalizeVersionToken` 会把前缀剥掉（那是为了与服务端
 * 说同一句话），所以归一化之后就看不出"这是哪个字段"了 —— 而"cTag 与 eTag
 * 能不能互相顶替"恰好取决于这一点。
 */
fun tagPrefixOf(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    return when {
        trimmed.startsWith(TAG_PREFIX_CTAG) -> TAG_PREFIX_CTAG
        trimmed.startsWith(TAG_PREFIX_ETAG) -> TAG_PREFIX_ETAG
        else -> null
    }
}

/** OneDrive 的**内容**版本（cTag）令牌前缀；写在通用位置供来源与比较共用。 */
const val TAG_PREFIX_CTAG: String = "ctag:"

/** OneDrive 的**整个 item**（元数据 + 内容）版本（eTag）令牌前缀 —— 仅作兜底。 */
const val TAG_PREFIX_ETAG: String = "etag:"
