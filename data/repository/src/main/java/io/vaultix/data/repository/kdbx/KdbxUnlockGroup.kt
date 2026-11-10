/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */

package io.vaultix.data.repository.kdbx

import io.vaultix.common.logging.VaultixLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * KDBX **解锁组**：切库不再锁，只有「锁定动作」才锁（2026-11-10 定稿）。
 *
 * ## 它取代了什么
 *
 * 同一天早些时候这里是 [KdbxGraceLocks]（切库后 60 秒锁掉其它库）。那个方案的
 * 判据是「**时间维度**」—— 60 秒内允许两份明文并存。它解决了"来回点两下就重解两次"，
 * 但没解决"在 B 里看五分钟再切回 C" —— 那仍然要重付一次 `read` + 解析。
 *
 * 用户当天追问「最有效的其实是不锁，怎么解决，有何优点」，并选择了：
 *
 * > 改成「跟随锁定」—— 切库不再锁；任一 KDBX 被锁则全部一起锁；
 * > 同时解锁上限默认 3 个，超了锁最久没用的那个。
 *
 * 判据因此从「时间」换到「**生命周期**」：明文份数仍然有上限（常数 3），
 * 但**上限不再靠计时器兑现，而是靠"你锁了它"**。没有计时器 ⇒ 没有"差 1 秒"的边缘，
 * 也没有"后台还剩多少秒"的心智负担。
 *
 * ## 为什么"不锁"的代价比直觉小（这个论证很重要，别丢）
 *
 * `KdbxTransformKeyCache` 把**派生密钥**（32 字节）常驻内存、并加密落盘，
 * 且**刻意不随 `Kdbx.lock()` 清除**（#247 的决定：清了就等于没做）。
 *
 * ⇒ `Kdbx.lock()` 现在丢掉的只是**解析后的对象图**，不是密钥。真正的秘密
 *   （派生密钥）本来就一直在。所以"多留一份对象图"的**额外**暴露面，
 *   远小于"整库明文常驻"这个说法给人的直觉。
 *
 * 反过来，`Kdbx.unlock` 的成本仍然全额存在：
 *
 * | 段 | 本地库 | OneDrive 库 |
 * |---|---|---|
 * | `read` 读整库 | 磁盘 IO | **stat 往返 + 整库下载** |
 * | `open` 的 KDF | 命中缓存 ≈ 0 | 同 |
 * | `open` 的解析 | kotpass 解密 + XML 解析 | 同 |
 *
 * ⇒ 省掉的是第一段和第三段。对网盘库，"切一次库 = 下一次文件"这件事才是用户
 *   真正感受到的慢。
 *
 * ## 上限为什么是 3
 *
 * - 1 就是今天早些时候的行为（切库即锁）；
 * - 2 覆盖不了"Bitwarden + 两个 KDBX 三头对照"；
 * - 3 ≈ 一个真实用户的库数量级，且对象图按 2000 条 / 库估约 2–4MB ⇒ 上限约 10MB 级；
 * - 再高就要开始担心低端机的堆（本项目没有 `largeHeap`）。
 *
 * ⚠️ 上限是**硬**的：`note` 会返回必须被驱逐的 id，调用方**必须**真锁掉它们。
 *    返回了却不执行 ⇒ 本类就退化成"只记账不干活"，`#60` 的份数上限名存实亡。
 *
 * ## 为什么用单调计数器而不是时间戳
 *
 * 排序只关心"谁更久没用"，不关心"多久"。用 `System.nanoTime` 会让单测必须
 * 伪造时钟（而 `KdbxUnlockGroup` 是纯逻辑、没有协程），用递增序号则一次比较就够，
 * 且**顺序完全确定** —— 同一毫秒内的两次 `note` 也不会并列。
 */
internal class KdbxUnlockGroup(
    private val maxUnlocked: Int = DEFAULT_MAX_UNLOCKED,
) {

    /** vaultId → 最近一次被使用时的序号（越大越新）。只含 KDBX 库。 */
    private val lastUsed = ConcurrentHashMap<String, Long>()

    private val clock = AtomicLong(0)

    /**
     * 记录 [vaultId] 刚刚被使用，并返回**超出上限、应当被锁掉**的库（最久未用的在前）。
     *
     * @param unlocked 当前**真实**已解锁的 KDBX 集合（`Kdbx.unlockedIds()`）。
     *   本类不持有会话真源，只用它对齐 —— 只用自己记的集合会在"库被别处锁掉"后
     *   继续把它算进名额，导致上限提前触发（表现：明明只开了 2 个，第 3 个却被挤掉）。
     */
    fun note(vaultId: String, unlocked: Collection<String>): List<String> {
        val actual = unlocked.toSet()
        // 与真源对齐：已被别处锁掉的库从顺序表里摘掉，别占名额。
        lastUsed.keys.retainAll(actual)
        // 真源里有、本类没见过的库（冷启动恢复、或本类建立之前的解锁）补记 ——
        // 漏记会让 `actual.size` 与 `lastUsed.size` 不一致，上限判据失真。
        // ⚠️ 必须先于下面那行：补记的值更小，否则刚用的这个库会被排成"最旧"。
        actual.forEach { lastUsed.getOrPut(it) { clock.incrementAndGet() } }
        // 本次使用的库：序号最大 ⇒ 一定不会被自己驱逐。
        lastUsed[vaultId] = clock.incrementAndGet()

        val excess = actual.size - maxUnlocked
        if (excess <= 0) return emptyList()
        val victims = lastUsed.entries
            .sortedBy { it.value }
            .take(excess)
            .map { it.key }
        VaultixLog.d(TAG) {
            "KDBX 解锁组超上限（${actual.size}/$maxUnlocked）⇒ 驱逐最久未用：$victims"
        }
        return victims
    }

    /** 某库已被锁定 / 移除 ⇒ 从组里摘掉（不再占名额）。 */
    fun forget(vaultId: String) {
        lastUsed.remove(vaultId)
    }

    /** 全锁 ⇒ 组清空。 */
    fun clear() {
        lastUsed.clear()
    }

    /** 当前组内的库（诊断与测试用；不含任何密钥材料，只是一串 id）。 */
    fun ids(): Set<String> = lastUsed.keys.toSet()

    companion object {
        /**
         * 同时解锁的 KDBX 上限。
         *
         * 取值理由见类 KDoc。**别为了"再快一点"调大** —— 每加 1 就多一份常驻明文对象图，
         * 而收益在第 3 个库之后基本为零（没人会同时对照四个库）。
         */
        const val DEFAULT_MAX_UNLOCKED: Int = 3

        private const val TAG = "VaultixKdbxGroup"
    }
}
