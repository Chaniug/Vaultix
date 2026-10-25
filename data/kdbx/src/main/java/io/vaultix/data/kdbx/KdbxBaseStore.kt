/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **写回基线**：本机当前内容「对应远端哪一份字节」—— 用**内容指纹**表达，不靠版本令牌。
 *
 * ## 它补的是哪道盲区（`.ai/ISSUES.md` #185）
 *
 * 同步的乐观并发有两层：
 * 1. **版本令牌**（ETag）—— 服务端判「我以为的那版还是最新的吗」。它只保证
 *    "**浏览器那层**没人抢先"，**不保证"我手里这份内容确实来自那一版"**；
 * 2. **内容指纹**（本文件）—— 我们自己判「会话里的内容，是不是远端那一版解出来的」。
 *
 * 只有第 1 层时会出这种事（旧文档写"当前休眠"，实际主写路径早已打通）：
 *
 * ```
 * 远端 v1→v2（另一台设备改了密码）
 * → 同步报 REMOTE_CHANGED（基线令牌仍是 v1 ⇒ 这个判断是对的）
 * → 用户在旧会话上编辑并保存
 * → 主写路径 saveVia(expectedVersion = 缓存里的 v1)
 * → 服务端说"还是 v1" ⇒ 条件写**通过** ⇒ 远端 v2 被基于 v1 的内容静默覆盖
 * ```
 *
 * 关键点：**保存路径读的是缓存里的旧令牌**，它天生不满足"内容与令牌配套"这条前提。
 * 令牌对得上 ≠ 内容来源对得上 —— 这就是第 2 层存在的理由。
 *
 * ## 语义（三条，改任何一条都要重新论证）
 *
 * | 基线状态 | 含义 | 写回时 |
 * |---|---|---|
 * | `null`（没记录） | 不知道 | **放行**（不凭空造冲突；见下"为什么没记录时放行"） |
 * | 与当前会话内容一致 | 内容就来自远端那一版 | 放行 |
 * | 与当前会话内容不一致 | 本机内容**不是**远端那一版（远端变过而我们没拉 / 改过没推） | **拒写**，交用户拍板 |
 *
 * ## 为什么没记录时放行
 *
 * "没记录"出现在解锁（进程重启 / 冷启动）之后：那时我们确实不知道基线，
 * 而**令牌那层仍然在起作用**（远端变了 ⇒ `If-Match` 失配 ⇒ 冲突）。
 * 在这种情形下硬拦会把正常保存也拦掉，用户看到的是"保存不了了"而没有出路。
 * ⇒ 宁可让令牌那一层兜着，也不要在信息不足时制造假冲突。
 *
 * ## 内存态，与锁同生命周期
 *
 * 基线的输入是**密文**（会话里那份字节），本身不敏感；但配上"哪个库"仍然暴露
 * 使用痕迹 ⇒ 只放内存，锁库即丢（与 [KdbxSessionStore] 同款纪律）。
 * 丢了不需要补偿：下次同步的「对账」会把它重新建立起来（见
 * `KdbxSyncOrchestrator.sync`），而丢的那一刻最新内容本来就在远端那一版。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import java.util.concurrent.ConcurrentHashMap

/**
 * 每库的「写回基线」内容指纹（进程级、仅内存）。
 *
 * 指纹算法在 [KdbxEncoder.contentFingerprint] —— **不是**对编码后的字节做哈希，
 * 理由见那里（KDBX 每次编码都换 IV / 随机流，字节天生不稳定）。
 *
 * 线程模型：`ConcurrentHashMap` —— 写入发生在各库自己的写锁或 IO 线程上，
 * 不同的库之间互不相干；同一库的读-比对-写都发生在 [Kdbx] 的写锁临界区内。
 */
internal object KdbxBaseStore {
    private val fingerprints = ConcurrentHashMap<String, String>()

    /** 登记基线（[content] = 远端那一版解出来的内容，见 `KdbxEncoder.contentFingerprint`）。 */
    fun record(vaultId: String, content: KdbxMappedContent) {
        fingerprints[vaultId] = KdbxEncoder.contentFingerprint(content)
    }

    /** 直接登记指纹（上层已经算好时用，避免重复遍历内容）。 */
    fun recordFingerprint(vaultId: String, fingerprint: String) {
        fingerprints[vaultId] = fingerprint
    }

    /** 没有基线（解锁后尚未对账 / 已锁库）。 */
    fun clear(vaultId: String) {
        fingerprints.remove(vaultId)
    }

    /** 当前基线指纹；`null` = 不知道（见文件头"为什么没记录时放行"）。 */
    fun current(vaultId: String): String? = fingerprints[vaultId]

    /** 全部清空（全量锁定）。 */
    fun clearAll() {
        fingerprints.clear()
    }
}
