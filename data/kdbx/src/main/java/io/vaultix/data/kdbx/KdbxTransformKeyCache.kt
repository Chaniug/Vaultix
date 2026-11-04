/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * ---------------------------------------------------------------------------
 * 「派生密钥缓存」—— 让锁库之后的重新解锁**跳过 Argon2**（#246 第五批）。
 *
 * ## 为什么这是唯一能做到「秒解」的位置
 *
 * KDBX 的 Argon2 参数是**写在文件头里的**（真机 64 MiB / 46 轮），
 * 任何兼容实现打开同一个库都要付同一笔账，Vaultix 不能擅自改参数
 * （那等于悄悄降低用户数据的抗爆破强度）。能省的只有**重复的那几次**。
 *
 * kotpass 的 `KeePassDatabase.decode` 收一个 `KdfProvider` 参数，
 * 接口只有一个方法：
 *
 * ```kotlin
 * fun transformKey(parameters: KdfParameters, compositeKey: ByteArray): ByteArray
 * ```
 *
 * ⇒ 不需要 fork、不需要改文件格式、不需要碰 `Credentials`：
 * 只要在这一层把「(这一版文件, 这一组凭据) ⇒ transformedKey」记下来，
 * 第二次解码同一份字节就直接复用，**KDF 归零**，只剩 AES 解密 + XML 解析。
 *
 * ## 覆盖哪几条路径
 *
 * | 场景 | 缓存前 | 缓存后 |
 * |---|---|---|
 * | 切库 / 退后台 / 超时锁定后重新解锁 | 1 次完整 Argon2 | 命中，0 次 |
 * | 一次解锁里 `verify` + `unlock` | 2 次 | 1 次 |
 * | fanout 顺带开多个库 | 每库 1 次 | 每库首次 1 次，之后 0 次 |
 * | **同 key 并发**（autoRestore × fanout） | 两条各跑一遍 30 秒 | single-flight 合并，只跑一遍 |
 * | 进程冷启动 | 1 次 | **落盘缓存命中，0 次**（见 [DerivedKeyDiskCache]） |
 *
 * ## 为什么还需要 single-flight（用户真机日志的判据）
 *
 * ```
 * 15:38:38.578 unlock ... open=32000ms 累计命中 0 / 未命中 2
 * 15:38:41.466 unlock ... open=30381ms
 * ```
 *
 * 两条解锁几乎同时起步，都在对方把结果写进缓存之前查了一次 ⇒ 双双 miss，
 * 两条 30 秒 Argon2 抢内存带宽，谁都慢一倍。光有 LRU 挡不住这个窗口，
 * 必须让「同一个 key 正在算」这件事对后来者可见。
 *
 * ## 缓存 key 为什么是「整份文件的哈希 + compositeKey 的哈希」
 *
 * `transformedKey = KDF(文件头里的 KDF 参数, compositeKey)`。要判「能不能复用」，
 * 就得判这两样有没有变。逐字段读 `KdfParameters` 既脆（kotpass 内部类型，
 * 带 name mangling）又容易漏字段 —— 漏一个就会拿旧密钥去解新文件，
 * 表现是「密码明明对却说密码错」，比慢更难查。
 *
 * 取**整份字节的 SHA-256** 是最保守的等价判据：文件变了（同步拉到新版本、
 * 保存后 `regenerateVectors` 换了 KDF 种子）必然 miss ⇒ 老实重算；
 * 文件没变必然可以复用。30 KB 做一次 SHA-256 是微秒级，
 * 与真机 Argon2 的 **30 秒**不在一个量级。
 *
 * ⚠️ 只记**派生结果**（32 字节），不记主密码、不记 keyfile、不记明文条目。
 *    内存一份（进程级 LRU），磁盘一份（Keystore 加密，见 [DerivedKeyDiskCache]）。
 *
 * ## ★ 安全取舍（必须让用户知道，不能悄悄做）
 *
 * 缓存**不随 `Kdbx.lock()` 清除** —— 清了就等于没做，用户抱怨的正是
 * 「锁定之后再解锁很慢」。代价是：锁库之后，这 32 字节派生密钥仍能被取回，
 * 拿到它 + 库字节就能解开库。
 *
 * 落盘那一份的暴露面与 `AutoUnlockKeyStore`（「从不」档自动恢复）同级，
 * 且**不比已有的快速解锁房间信封更弱** —— 后者落盘的是主密码本身。
 * 完整对照与开关见 [installKdbxDerivedKeyDiskCache] 的文件头 KDoc。
 *
 * ⇒ [enabled] 是全局开关（置 false 连落盘一起禁）；[attachDisk] 决定落不落盘。
 *    [clear] 默认**只清内存**（超时全锁走它，不能连盘一起删，否则又变 30 秒）；
 *    `clear(includeDisk = true)` 供退出数据库 / 删除库这类「彻底忘记」的时机调用。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.KdfParameters
import io.vaultix.common.logging.VaultixLog
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

internal object KdbxTransformKeyCache {

    private const val TAG = "VaultixKdbx"

    /** 最多留几条。每条约 32 字节，留 16 条也不到 1 KB；纯粹防异常场景无限增长。 */
    private const val MAX_ENTRIES = 16

    /** `Byte.toInt()` 会符号扩展，转十六进制前必须先掩掉高位的 1。 */
    private const val BYTE_MASK = 0xff

    /**
     * 全局开关（安全取舍的出口，见文件头 KDoc）。
     *
     * ⚠️ `@Volatile`：解锁跑在 IO 线程，设置可能从主线程改。
     */
    @Volatile
    var enabled: Boolean = true

    /** 命中 / 未命中计数：真机日志与单测都靠它证明「确实跳过了 KDF」。 */
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)

    fun hits(): Long = hits.get()
    fun misses(): Long = misses.get()

    /**
     * LRU：`accessOrder = true`，`removeEldestEntry` 淘汰最久没用的那条。
     * 所有访问都在 `synchronized(map)` 里做 —— `LinkedHashMap` 不是线程安全的，
     * 而 fanout 会并发开多个库。
     */
    private val entries = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
                size > MAX_ENTRIES
        },
    )

    /**
     * 落盘缓存（进程启动时由 [installKdbxDerivedKeyDiskCache] 安装；纯 JVM 单测为 null）。
     *
     * ⚠️ `@Volatile`：安装发生在 `Application.onCreate`（主线程），读取在 IO 线程。
     */
    @Volatile
    private var disk: DerivedKeyDiskCache? = null

    /**
     * 「这个 key 正在被谁算」—— single-flight 的占位表。
     *
     * 值是一个**尚未完成**的 Future：抢到 `putIfAbsent` 的线程负责跑 KDF，
     * 后来者阻塞在 `get()` 上复用同一份结果。算完（成功或异常）立即 `remove`，
     * 之后的调用走正常的内存 / 落盘命中路径。
     */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()

    /** 给**这一份文件字节**配一个 KDF provider（每个 decode 调用点各取一个）。 */
    fun providerFor(bytes: ByteArray): KdfProvider = CachingProvider(digest(bytes))

    /** 安装 / 卸载落盘缓存（app 启动时调用一次；传 null 即退回纯内存语义）。 */
    internal fun attachDisk(cache: DerivedKeyDiskCache?) {
        disk = cache
    }

    /**
     * 清空内存 LRU 与并发占位。
     *
     * ⚠️ 默认**不动落盘缓存**：`Kdbx.lockAll()`（超时全锁 / 用户全锁）走这里，
     *    连盘一起删的话，用户下一次解锁又要等 30 秒 Argon2 —— 正是要修的毛病。
     *    `includeDisk = true` 只由 `Kdbx.forgetDerivedKeys()`（退出数据库 / 删库）使用。
     */
    fun clear(includeDisk: Boolean = false) {
        synchronized(entries) { entries.clear() }
        inFlight.clear()
        if (includeDisk) disk?.clear()
    }

    /**
     * 缓存的核心判据，抽出来是为了**脱离 `KdfParameters` 也能单测**
     * （那个类型是 kotpass 的抽象类，测试里造不出实例）。
     *
     * 查找顺序刻意固定为「**内存 → 落盘 → 并发占位 → 真跑 KDF**」：
     * 先查最便宜的，最后才让一条线程去付那 30 秒。
     *
     * @param contentDigest 整份文件字节的 SHA-256。
     * @param compositeKey kotpass 算出的 composite key（**不是**主密码明文）。
     * @param compute 真正跑 KDF 的动作；只在未命中时调用。
     */
    internal fun getOrCompute(
        contentDigest: ByteArray,
        compositeKey: ByteArray,
        compute: () -> ByteArray,
    ): ByteArray {
        if (!enabled) {
            misses.incrementAndGet()
            return compute()
        }
        // 两段哈希拼起来再哈希：任何一段变了都会得到完全不同的 key。
        val key = digest(contentDigest + digest(compositeKey)).toHex()
        memoryOf(key)?.let { cached ->
            hits.incrementAndGet()
            VaultixLog.d(TAG) { "派生密钥内存缓存命中 ⇒ 本次解码跳过 KDF" }
            return cached
        }
        disk?.load(key)?.takeIf { it.isNotEmpty() }?.let { fromDisk ->
            hits.incrementAndGet()
            synchronized(entries) { entries[key] = fromDisk.copyOf() }
            VaultixLog.d(TAG) { "派生密钥落盘缓存命中 ⇒ 冷启动跳过 KDF" }
            return fromDisk
        }
        // 空结果 / 解不开的密文一律当作「没有」：宁可再付一次 KDF，
        // 也绝不把一段可疑字节当成 transformedKey 交给 kotpass。
        return computeJoined(key, compute)
    }

    private fun memoryOf(key: String): ByteArray? =
        synchronized(entries) { entries[key]?.copyOf() }

    /**
     * single-flight：同 key 只有一条线程真去跑 KDF，其余等它。
     *
     * ⚠️ 不用 `ConcurrentHashMap.computeIfAbsent` 直接在 mapping 里算：
     *    那会**持着 bin 锁跑 30 秒**，不同 key 撞到同一 bin 时会被无辜拖住，
     *    resize 期间还有死锁风险。改成「抢占位 → 抢到的人算 → 其余 get()」，
     *    占位本身是 O(1) 且立刻返回。
     *
     * ⚠️ `transformKey` 是 kotpass 的**同步**接口，这里不能 `await`；
     *    阻塞调用线程是唯一选择（它本来也要等这 30 秒，且跑在 IO 调度器上）。
     */
    @Suppress("TooGenericExceptionCaught") // 连 Error 也要 completeExceptionally，否则等待者永远挂住
    private fun computeJoined(key: String, compute: () -> ByteArray): ByteArray {
        val mine = CompletableFuture<ByteArray>()
        val owner = inFlight.putIfAbsent(key, mine)
        if (owner != null) {
            val joined = awaitOwner(owner)
            hits.incrementAndGet()
            VaultixLog.d(TAG) { "派生密钥并发合并 ⇒ 复用同一次 KDF" }
            return joined.copyOf()
        }
        val computed = try {
            compute()
        } catch (error: Throwable) {
            mine.completeExceptionally(error)
            inFlight.remove(key, mine)
            throw error
        }
        synchronized(entries) { entries[key] = computed.copyOf() }
        // 写盘失败绝不能把「解锁成功」翻成「解锁失败」：缓存只是优化，不是前置条件。
        // 实现内部已吞异常，这里再兜一层，防将来换实现时漏掉。
        runCatching { disk?.save(key, computed) }
        misses.incrementAndGet()
        VaultixLog.d(TAG) { "派生密钥缓存未命中 ⇒ 执行 KDF（缓存 ${entries.size}/$MAX_ENTRIES 条）" }
        mine.complete(computed.copyOf())
        inFlight.remove(key, mine)
        return computed
    }

    /**
     * 等待另一位线程算完；把 `Future` 的检查异常还原成**原始**异常。
     *
     * 单独成函数是为了不把 `computeJoined` 顶过 detekt `ThrowsCount`（上限 2）：
     * 这里两个 throw、那边一个，各自都在限内；更重要的是「等待者怎么失败」
     * 本来就是与「负责者怎么算」无关的一件事。
     */
    private fun awaitOwner(owner: CompletableFuture<ByteArray>): ByteArray = try {
        owner.get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    }

    private class CachingProvider(private val contentDigest: ByteArray) : KdfProvider {
        override fun transformKey(parameters: KdfParameters, compositeKey: ByteArray): ByteArray =
            getOrCompute(contentDigest, compositeKey) {
                // 经 Java 桥取 kotpass 的默认实现（它是 internal object，
                // Kotlin 源码直接引用会 INVISIBLE_REFERENCE，见桥接类 KDoc）。
                KotpassKdfBridge.defaultProvider().transformKey(parameters, compositeKey)
            }
    }

    private fun digest(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and BYTE_MASK) }
}
