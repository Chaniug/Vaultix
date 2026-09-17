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
     * 写入（覆盖）。
     *
     * @param expectedVersion ★ **上次 [stat]/[read] 拿到的 `versionToken`**。
     *   - 非空且来源支持条件写 ⇒ 服务端在 HTTP/文件系统边界强制"必须还是这一版"，
     *     不匹配就抛 [KdbxFileConflictException]（**这才是真正消除 TOCTOU 的做法**）；
     *   - `null` ⇒ 调用方明确表示"不对并发做保证"（如首次创建、或来源不提供令牌）。
     *     实现方**不要**把它当成"随便覆盖"的许可，而是退化为可用的最严保障
     *     （写前预检 + 写后读回校验），并在发现被改动时抛 [KdbxFileConflictException]。
     * @param force ★ **无条件强写**（见下）。
     * @return 写入后的新状态（含**新的** `versionToken`）。
     *
     * ## ★ `force` 与 `expectedVersion = null` 的区别（别混）
     *
     * | 参数 | 含义 |
     * |---|---|
     * | `expectedVersion = null` | "我没带基线" —— 实现方**应退化为最严保障**（写前预检 + 写后读回校验），不要放弃检查 |
     * | `force = true` | "**别检查，直接盖**" —— 只有用户在冲突对话框里明确选了「用本地覆盖远端」才允许 |
     *
     * ⚠️ 为什么值得一个独立参数：把它俩合成一个 `null` 会让"忘了传基线"
     * （一个**编码疏忽**）静默升级成"强行覆盖远端"（一次**数据丢失**）。
     * 两件事的风险差着量级，就必须是两个名字。
     *
     * ⚠️ `force = true` 时 `expectedVersion` 被忽略，实现方**不要**再发条件头
     * （发了必然失败——远端确实变了，这正是用户要覆盖的那次改动）。
     */
    suspend fun write(
        bytes: ByteArray,
        expectedVersion: String? = null,
        force: Boolean = false,
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
 */
class KdbxFileConflictException(
    val currentVersion: String?,
    message: String,
) : Exception(message)

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
 */
fun normalizeVersionToken(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed
        .removePrefix("W/")
        .removePrefix("w/")
        .trim()
        .removeSurrounding("\"")
        .trim()
        .takeIf { it.isNotEmpty() }
}
