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
 * | 进程冷启动 | 1 次 | 仍是 1 次（内存缓存随进程消失，省不掉） |
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
 * 与 Argon2 的几百毫秒不在一个量级。
 *
 * ⚠️ 只记**派生结果**（32 字节），不记主密码、不记 keyfile、不记明文条目，
 *    且**只活在内存、绝不落盘**。进程一死全部消失。
 *
 * ## ★ 安全取舍（必须让用户知道，不能悄悄做）
 *
 * 缓存**不随 `Kdbx.lock()` 清除** —— 清了就等于没做，用户抱怨的正是
 * 「锁定之后再解锁很慢」。代价是：锁库之后，这 32 字节派生密钥仍留在进程内存里，
 * 拿到它 + 库字节就能解开库。
 *
 * 这与本项目已有的取舍同量级：`Never` 档下房钥匙本来就常驻内存，
 * 且 `AutoViewGate` 的 KDoc 写明用户已接受「密钥只在内存、系统锁屏是防线」。
 * 差别只是把「锁库 = 密钥彻底不可达」放宽成「锁库 = 清掉整库明文，
 * 但保留一条 32 字节的派生密钥」。
 *
 * ⇒ 提供 [enabled] 全局开关：要恢复严格语义，置 false 即可（一行代码，
 *    后续接设置项也只是把它绑到一个偏好上）。[clear] 供登出 / 删除库 /
 *    主密码变更这类「应当彻底忘记」的时机调用。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.KdfParameters
import io.vaultix.common.logging.VaultixLog
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
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

    /** 给**这一份文件字节**配一个 KDF provider（每个 decode 调用点各取一个）。 */
    fun providerFor(bytes: ByteArray): KdfProvider = CachingProvider(digest(bytes))

    /** 彻底忘记（登出 / 删除库 / 改主密码）。 */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /**
     * 缓存的核心判据，抽出来是为了**脱离 `KdfParameters` 也能单测**
     * （那个类型是 kotpass 的抽象类，测试里造不出实例）。
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
        val cached = synchronized(entries) { entries[key]?.copyOf() }
        if (cached != null) {
            hits.incrementAndGet()
            VaultixLog.d(TAG) { "派生密钥缓存命中 ⇒ 本次解码跳过 KDF" }
            return cached
        }
        val computed = compute()
        synchronized(entries) { entries[key] = computed.copyOf() }
        misses.incrementAndGet()
        VaultixLog.d(TAG) { "派生密钥缓存未命中 ⇒ 执行 KDF（缓存 ${entries.size}/$MAX_ENTRIES 条）" }
        return computed
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
