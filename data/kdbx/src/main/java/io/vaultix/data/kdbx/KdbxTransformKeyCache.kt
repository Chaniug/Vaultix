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
 * | **编辑 / 保存**（encode + 往返自检） | 2 遍（真机各 ≈30 秒） | 1 遍：encode 的派生结果入缓存，decode 命中 |
 * | 保存后（含冷启动）打开这一新版本 | 1 次 | 命中，0 次 |
 *
 * ## 为什么保存路径要单独接一根线（#248）
 *
 * [providerFor] 要求**先有完整文件字节**，可 `encode` 那一侧恰恰是「字节还没产出」：
 * 它先 `regenerateVectors` 换掉 KDF 种子，再调 `transformKey` 派生一遍，最后才写出字节。
 * 于是 encode 这一遍 KDF 既查不了缓存、结果也没人收；紧接着
 * `KdbxRoundTrip.verifyEncoded` 解码这份新字节时又派生第二遍 —— 真机一次保存就是 **60 秒**。
 *
 * ⇒ [captureProvider] 包装的仍是同一个 `BaseKdfProvider.INSTANCE`（与 decode 侧同源），
 *   只多记一份 `(compositeKey, transformedKey)`；产完字节由 [commitCaptured] 按
 *   **新字节**算出 key 写进缓存，随后的往返自检与下次打开都直接命中。
 *   ⚠️ 捕获错了不会写坏库：`verifyEncoded` 用这份缓存解不开自己刚写的字节，
 *   会当场判 `isSafeToWrite = false`。
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
 * ## 缓存 key 为什么是「KDF 参数段 + compositeKey」（2026-11-04 改）
 *
 * `transformedKey = KDF(文件头里的 KDF 参数, compositeKey)` —— **只依赖这两样，
 * 与条目内容无关**。旧实现取整份文件字节的 SHA-256，把两种「文件变了」混为一谈：
 * ① KeePassXC 改了一个条目同步下来（KDF 参数没变，本可复用）；
 * ② 保存时 `regenerateVectors` 换了 KDF 种子（必须重算）。
 * 后果是**每次跨设备同步都白跑一遍 Argon2**，而真机最慢的库要 30 秒。
 * 用户原话：「手机打开和电脑同样处于 OneDrive 的数据时候，也能够快速解锁」。
 *
 * KDBX 4 的 header 是明文 TLV（sig1 4B + sig2 4B + version 2B 之后开始），
 * id=11 就是 KdfParameters。取**整段原始 TLV 字节**（含 id / size / data）做判据：
 * 不解析 VariantDictionary 内部结构 ⇒ 不存在「逐字段读漏一个」的风险，
 * 任何一个参数字节变化都必然得到不同的 key；条目内容变化则完全不影响 key。
 * 这同时回答了旧注释的顾虑：不用碰 kotpass 的 `KdfParameters` 内部类。
 *
 * ⚠️ 提取失败（非 KDBX4 / 文件损坏 / 头部异常）一律**回退整份文件哈希**：
 *    最坏只是多跑一次 KDF，绝不会拿不确定的判据去复用密钥。
 *    [enabled] = false 仍是完整的逃生开关。
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

    /** KDBX 固定头长度：`Signature` 8B + `FormatVersion` 4B，之后是 TLV 区。 */
    private const val HEADER_TLV_START = 12

    /** KDBX 4 每个 TLV 项的头：1B id + 4B 小端长度。 */
    private const val TLV_HEAD_SIZE = 5

    private const val TLV_END_OF_HEADER = 0

    /** `KdfParameters`（VariantDictionary）在 KDBX 4 header 中的字段 id。 */
    private const val TLV_KDF_PARAMETERS = 11

    /** KDBX 文件签名 `03 D9 A2 9A 67 FB 4B B5`（存成 Int 便于逐字节无符号比对）。 */
    private val KDBX_SIGNATURE = intArrayOf(0x03, 0xD9, 0xA2, 0x9A, 0x67, 0xFB, 0x4B, 0xB5)

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

    /**
     * 给这一份文件配一个 KDF provider（每个 decode 调用点各取一个）。
     *
     * 判据只取 [kdfDigest]：KeePassXC 改条目不会改变 KDF 参数段，因此同步下来的
     * 新版本仍命中同一条缓存 —— 这正是「跨设备同步后不用重跑 Argon2」的落点。
     */
    fun providerFor(bytes: ByteArray): KdfProvider = CachingProvider(kdfDigest(bytes))

    /**
     * 给 **encode** 配的 provider：此时新文件字节还没产出，查不了缓存（而且
     * `regenerateVectors` 换了 KDF 种子，查了也必然 miss）。
     * 它只把这一遍真实派生用的凭据与结果记下来，等 [commitCaptured] 拿到字节再入缓存。
     */
    internal fun captureProvider(): CapturedKdf = CapturedKdf()

    /**
     * encode 产完字节后，把捕获的 `transformedKey` 按「新文件 KDF 参数段 + compositeKey」写缓存。
     *
     * 只认**恰好一次**派生：零次（没跑到 KDF）/ 多次（kotpass 换了内部实现）都放弃提交 ——
     * 写错一条缓存的代价是「密码明明对却说密码错」，比多跑一次 KDF 难查得多。
     */
    internal fun commitCaptured(capture: CapturedKdf, bytes: ByteArray) {
        if (!enabled || bytes.isEmpty()) return
        val (compositeKey, derivedKey) = capture.snapshot() ?: return
        val key = keyOf(kdfDigest(bytes), compositeKey)
        synchronized(entries) { entries[key] = derivedKey.copyOf() }
        // 与 computeJoined 同一口径：写盘失败只是少一次优化，不能让保存失败。
        runCatching { disk?.save(key, derivedKey) }
        VaultixLog.d(TAG) { "派生密钥写入缓存（保存路径）⇒ 往返自检与下次打开跳过 KDF" }
    }

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
     * @param contentDigest [kdfDigest] 的结果（KDF 参数段摘要；提取失败时为整份文件摘要）。
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
        // ★ 公式只此一处：[commitCaptured] 也走 [keyOf]，避免两处装配漂移。
        val key = keyOf(contentDigest, compositeKey)
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

    /**
     * encode 侧的捕获型 provider（#248）。
     *
     * ⚠️ 刻意**不做缓存查找**：encode 时新字节尚未产出，且 `regenerateVectors` 换了
     *    KDF 种子，旧条目本来就不适用（拿旧 transformedKey 去加密新文件 = 写坏库）。
     *    这里只记结果，由 [commitCaptured] 在拿到字节后按正确的 key 入缓存。
     *
     * 委托对象与 decode 的默认实现同源（`KotpassKdfBridge.defaultProvider()`），
     * ⇒ 传它进 `encode` 不改变任何加密行为，只是多了一次记账。
     */
    internal class CapturedKdf : KdfProvider {
        private val guard = Any()
        private var callCount = 0
        private var composite: ByteArray? = null
        private var derived: ByteArray? = null

        override fun transformKey(parameters: KdfParameters, compositeKey: ByteArray): ByteArray {
            val result = KotpassKdfBridge.defaultProvider().transformKey(parameters, compositeKey)
            record(compositeKey, result)
            return result
        }

        /**
         * 记一次派生。单测可**直接喂**数据 —— `KdfParameters` 是 kotpass 的抽象类，
         * 纯 JVM 测试里造不出实例，把「记账」与「真跑 KDF」拆开才能测。
         */
        internal fun record(compositeKey: ByteArray, derivedKey: ByteArray) {
            synchronized(guard) {
                callCount++
                composite = compositeKey.copyOf()
                derived = derivedKey.copyOf()
            }
        }

        /** 恰好一次派生才可信；空结果也一律不提交。 */
        internal fun snapshot(): Pair<ByteArray, ByteArray>? = synchronized(guard) {
            val c = composite
            val d = derived
            if (c == null || d == null) return@synchronized null
            // 拆成两段判据：写在一起会是 4 个条件，顶过 detekt ComplexCondition（上限 3）。
            if (callCount != 1 || d.isEmpty()) null else c.copyOf() to d.copyOf()
        }
    }

    /** 缓存 key 的**唯一**公式：判据摘要 + 凭据摘要，再整体哈希。 */
    private fun keyOf(contentDigest: ByteArray, compositeKey: ByteArray): String =
        digest(contentDigest + digest(compositeKey)).toHex()

    /**
     * 判据摘要：只取决定 `transformedKey` 的 KDF 参数段；取不到就回退整份文件。
     *
     * 回退是 **fail-closed** 的：非 KDBX4 / 头部损坏 / 截断，一律按最保守的
     * 全文件哈希处理（多跑一次 KDF），绝不拿不确定的判据去复用密钥。
     */
    private fun kdfDigest(bytes: ByteArray): ByteArray =
        digest(kdfParametersBlock(bytes) ?: bytes)

    /**
     * 从 KDBX 4 明文 header 里取 id=11 `KdfParameters` 的**整段原始 TLV 字节**
     * （含 id / 长度 / VariantDictionary 数据）。
     *
     * 布局核实自 kotpass `DatabaseHeader.readHeaderValue`（`D:/Vaultix-refs/kotpass`）：
     * `Signature` 8B + `FormatVersion` 4B（`minor:Short` + `major:Short`）⇒ TLV 从 12 起；
     * major ≥ 4 时长度字段是 4B 小端。不解析 VariantDictionary 内部结构，
     * 因此任何一个 KDF 参数字节变化都会改变判据，且不存在「逐字段读漏一个」的风险。
     */
    private fun kdfParametersBlock(bytes: ByteArray): ByteArray? {
        if (!isKdbx4(bytes)) return null
        var offset = HEADER_TLV_START
        while (offset + TLV_HEAD_SIZE <= bytes.size) {
            val id = bytes[offset].toInt() and BYTE_MASK
            val size = readUInt32Le(bytes, offset + 1) ?: return null
            val endLong = offset.toLong() + TLV_HEAD_SIZE + size
            if (endLong > bytes.size) return null
            val end = endLong.toInt()
            // EndOfHeader 之前没找到 KDF 参数 ⇒ 不是可判定的头，回退全文件哈希。
            if (id == TLV_END_OF_HEADER) return null
            if (id == TLV_KDF_PARAMETERS) return bytes.copyOfRange(offset, end)
            offset = end
        }
        return null
    }

    /** 版本字段中 major（第 2 个 Short，小端）的起始偏移：minor 在 8..9，major 在 10..11。 */
    private const val VERSION_MAJOR_OFFSET = 10

    /** 本判据只适用于 KDBX 4；其它版本一律回退全文件哈希。 */
    private const val KDBX_MAJOR_VERSION = 4

    /** uint32 由 4 字节组成，每字节 8 位。 */
    private const val UINT32_BYTE_COUNT = 4

    private const val BITS_PER_BYTE = 8

    /** 只认 KDBX 4（major=4）；签名不对或版本不是 4 都返回 false。 */
    private fun isKdbx4(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_TLV_START) return false
        for (index in KDBX_SIGNATURE.indices) {
            if ((bytes[index].toInt() and BYTE_MASK) != KDBX_SIGNATURE[index]) return false
        }
        return bytes[VERSION_MAJOR_OFFSET] == KDBX_MAJOR_VERSION.toByte() &&
            bytes[VERSION_MAJOR_OFFSET + 1] == 0.toByte()
    }

    /** 4B 小端无符号长度；用 Long 承载，避免符号扩展把大长度读成负数绕过边界检查。 */
    private fun readUInt32Le(bytes: ByteArray, at: Int): Long? {
        if (at + UINT32_BYTE_COUNT > bytes.size) return null
        val mask = BYTE_MASK.toLong()
        var value = 0L
        for (index in 0 until UINT32_BYTE_COUNT) {
            value += (bytes[at + index].toLong() and mask) shl (index * BITS_PER_BYTE)
        }
        return value
    }

    private fun digest(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and BYTE_MASK) }
}
