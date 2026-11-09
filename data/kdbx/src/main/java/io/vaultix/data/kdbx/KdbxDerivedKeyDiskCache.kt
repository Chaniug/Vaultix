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
 * 「派生密钥落盘缓存」—— 让**进程冷启动**也能跳过 Argon2（#247 第六批）。
 *
 * ## 为什么必须落盘（用户 2026-11-04 真机日志给出的判据）
 *
 * ```
 * 15:38:38.578 unlock 耗时分解：read=2ms（33926B）+ open(KDF+解析)=32000ms
 * 15:38:41.466 unlock 耗时分解：read=1ms（33926B）+ open(KDF+解析)=30381ms
 *              本次 KDF 缓存命中 0 次（累计命中 0 / 未命中 2）
 * ```
 *
 * 真机这份库的 Argon2 单次约 **30 秒**（不是文档里假设的几百毫秒）。
 * #246 第五批做的内存缓存只活在一个进程里，而用户抱怨的场景恰恰是
 * 「退出 App → 再次打开 → 切到 KDBX → 指纹进去很慢」—— **冷启动必然 miss**。
 * 内存缓存答不了这道题，只有把派生结果安全落盘才行。
 *
 * ## 落的是什么、不落什么
 *
 * 只落 `transformedKey`（32 字节，**不是主密码、不是 keyfile、不是明文条目**），
 * 且用 Android Keystore 里一把**不可导出**的 AES-256-GCM 密钥包裹后才写盘。
 * 索引仍是 `SHA256(判据摘要 + compositeKey 哈希)`，判据由 `KdbxTransformKeyCache.kdfDigest`
 * 统一给出（2026-11-04 起取 **KDBX4 header 的 KdfParameters TLV 段**，取不到才回退整份文件）：
 * - **条目内容变了**（同步拉到新版本）⇒ 判据不变 ⇒ **命中，冷启动也秒开**；
 * - KDF 参数变了（改 KDF 设置 / 保存时换种子）⇒ key 变 ⇒ 老实重算；
 * - 密码或 keyfile 变了 ⇒ compositeKey 变 ⇒ 老实重算；
 * - 密文坏了 / Keystore 密钥丢了 ⇒ 解不开 ⇒ 老实重算并覆盖。
 *
 * ⇒ **永远没有「拿旧密钥解新文件」的窗口**，最坏情况只是多等一次 KDF。
 *
 * ## ★ 安全取舍（必须让用户知道，不能悄悄做）
 *
 * | 项目 | 已有模型 | 本缓存 |
 * |---|---|---|
 * | 快速解锁房间信封 | 主密码经指纹 KEK 包裹后落盘 | — |
 * | 「从不」档自动恢复 | 房钥匙经无认证 Keystore 密钥包裹后落盘 | — |
 * | 本缓存 | — | 32 字节派生结果经无认证 Keystore 密钥包裹后落盘 |
 *
 * 拿到密文 + 能调用本 app 的 Keystore 别名就能解开这个库，暴露面与
 * `AutoUnlockKeyStore`（Never 档）同级，且**不比已有的房间信封更弱** ——
 * 后者落盘的可是主密码本身。安全边界仍是：手机锁屏 + app 沙箱 + TEE。
 *
 * ⚠️ 用户主动锁库 / 超时全锁**不删**本缓存：删了就等于没做，用户抱怨的正是
 *    「锁定之后再解锁很慢」。真正「彻底忘记」的时机只有
 *    `Kdbx.forgetDerivedKeys()`（退出数据库 / 删除库 / 改主密码）。
 *
 * ⇒ 提供 [installKdbxDerivedKeyDiskCache] 的 `enabled` 开关：置 false 即完全不落盘，
 *    退回 #246 第五批的纯内存语义。
 *
 * ## ⚠️ CodeQL #15 / #16 已评估并 dismiss（2026-11-10）
 *
 * 规则 `java/android/insecure-local-key-gen`（"Insecurely generated keys for local
 * authentication"）在 [AndroidDerivedKeyDiskCache.obtainOrCreateKey] 的
 * `setUserAuthenticationRequired(false)` 上报了两条。评估结论：**规则的模式匹配成立，
 * 但它假设的威胁场景不适用** —— 规则标题的前提是「这把密钥用于本地认证」，而本缓存
 * 的密钥只包裹 KDF 派生结果、不参与任何认证流程。
 *
 * GitHub 告警上挂的 dismiss 理由是：
 * 「设计取舍非缺陷：密钥包裹 KDBX 的 32B transformedKey，不含主密码/明文，不用于
 *   本地认证。落盘因真机 Argon2 单次约 30s，内存缓存盖不住冷启动。补偿：TEE 不可
 *   导出密钥 + 沙箱 + 锁屏；与 AutoUnlockKeyStore（落主密码）同级，enabled=false 可关闭。」
 *
 * ⇒ **若未来把这把密钥改作任何认证用途，必须回来重新评估这两条** ——
 *    那时「不用于本地认证」这个前提就不成立了。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.vaultix.common.logging.VaultixLog
import java.io.File
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

/** 与 `Kdbx` / `KdbxTransformKeyCache` 同 tag，真机 `adb logcat -s VaultixKdbx` 一次看全。 */
private const val TAG = "VaultixKdbx"

/**
 * 落盘缓存的可插拔接口。
 *
 * 为什么要做成接口而不是在 [KdbxTransformKeyCache] 里直接摸 Keystore：
 * 那个对象是 `data:kdbx` 的纯逻辑单例，**纯 JVM 单测**会直接调它；
 * 一旦在 `getOrCompute` 里硬编码 `android.util.Base64` / `KeyStore`，
 * 单测就会撞上 "Method not mocked"。接口默认 `null` ⇒ 单测走纯内存路径，
 * 真机由 [installKdbxDerivedKeyDiskCache] 在进程启动时装上 Android 实现。
 */
internal interface DerivedKeyDiskCache {
    /** 取回派生结果；null = 没有 / 损坏 / Keystore 暂不可用（一律退回重算 KDF）。 */
    fun load(key: String): ByteArray?

    /** 记住派生结果；**任何失败都不得影响本次解锁**（调用方不检查返回值）。 */
    fun save(key: String, derivedKey: ByteArray)

    /** 彻底忘记（退出数据库 / 删除库）。幂等。 */
    fun clear()
}

/**
 * 在进程启动早期安装落盘缓存。
 *
 * @param cacheDir app 私有目录下的子目录（由 `Application.filesDir` 派生）。
 * @param enabled false = 完全不落盘，退回「只活内存」的旧语义。
 *
 * ⚠️ 由 `VaultixApplication.onCreate()` 调用一次；重复调用只是换一个实现，无副作用。
 */
fun installKdbxDerivedKeyDiskCache(cacheDir: File, enabled: Boolean = true) {
    KdbxTransformKeyCache.attachDisk(
        if (enabled) AndroidDerivedKeyDiskCache(cacheDir) else null,
    )
    VaultixLog.d(TAG) { "派生密钥落盘缓存已安装：enabled=$enabled" }
}

/**
 * [DerivedKeyDiskCache] 的 Android 实现：Keystore 包裹 + 每 key 一个小文件。
 *
 * ## 为什么不用 SharedPreferences / DataStore
 *
 * 这两个都是「一整份结构化状态」，而这里要的是**按内容哈希寻址的独立条目**：
 * 一个库每保存一次就换一份字节 ⇒ 新条目。放进一个 KV 容器会越攒越大，
 * 且没法按 LRU 淘汰单个条目。小文件 + 目录级淘汰反而最直白。
 *
 * ## 写入不是原子的，但这是安全的
 *
 * 半写 / 断电留下的坏文件在 [load] 里会被 AEAD 标签校验挡掉 ⇒ 返回 null ⇒
 * 重算一次 KDF（正确性无损）。为「少等 30 秒」去引入临时文件 + fsync + rename
 * 反而会把这条路径复杂化，不值得。
 */
internal class AndroidDerivedKeyDiskCache(private val rootDir: File) : DerivedKeyDiskCache {

    /** 同一 key 的读 / 写 / 清空串行化（fanout 会并发开多个库）。 */
    private val lock = ReentrantLock()

    override fun load(key: String): ByteArray? = lock.withLock {
        val payload = runCatching { fileOf(key).takeIf { it.exists() }?.readText() }.getOrNull()
        if (payload == null) null else decrypt(payload)
    }

    override fun save(key: String, derivedKey: ByteArray) {
        lock.withLock {
            val payload = encrypt(derivedKey) ?: return@withLock
            runCatching {
                rootDir.mkdirs()
                fileOf(key).writeText(payload)
                trim()
            }.onFailure { error ->
                // 只记类型，不记任何密钥 / 路径细节（VaultixLog 铁律）。
                VaultixLog.d(TAG) { "派生密钥落盘写入失败：${error.javaClass.simpleName}" }
            }
        }
    }

    override fun clear() {
        lock.withLock {
            runCatching { rootDir.listFiles()?.forEach { file -> file.delete() } }
        }
    }

    /**
     * 条目数量上限。每次保存都会让文件字节变化 ⇒ 天然会攒旧版本，
     * 不淘汰的话用一年就是几百个小文件（每个几十字节，但目录遍历会变慢）。
     * 淘汰**只影响性能**：被删掉的条目下次老老实实重算一次 KDF。
     */
    private fun trim() {
        val files = rootDir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    /** key 恒为 64 字符小写 hex；显式校验防止将来误传带路径分隔符的值。 */
    private fun fileOf(key: String): File {
        require(key.length == KEY_LENGTH && key.all { it in '0'..'9' || it in 'a'..'f' }) {
            "invalid derived-key cache key"
        }
        return File(rootDir, key)
    }

    private fun encrypt(plain: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainOrCreateKey())
        buildString {
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append(SEPARATOR)
            append(Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP))
        }
    }.getOrNull()

    private fun decrypt(payload: String): ByteArray? = runCatching {
        val parts = payload.split(SEPARATOR)
        check(parts.size == PAYLOAD_PARTS) { "invalid derived-key payload" }
        // ⚠️ 只用既有密钥：解密路径绝不新建 KEK（新建会让旧密文永久不可解，
        //    却假装自己「可用」）。密钥丢了就返回 null，由上层重算并覆盖写入。
        val key = loadKey() ?: error("keystore key unavailable")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }.doFinal(ciphertext)
    }.getOrNull()

    private fun obtainOrCreateKey(): SecretKey {
        loadKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // 无认证要求：KDF 是在 kotpass 的同步 decode 里跑的，
            // 那一层不可能弹 BiometricPrompt。安全性由 TEE + 沙箱 + 锁屏承担。
            .setUserAuthenticationRequired(false)
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun loadKey(): SecretKey? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            .getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        /** 与 `AutoUnlockKeyStore` / `LocalUnlockKeyStore` 分开：删它们的密钥不该连累本缓存。 */
        const val KEY_ALIAS = "vaultix_kdf_derived_kek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val SEPARATOR = "."
        const val PAYLOAD_PARTS = 2
        const val MAX_FILES = 48
        const val KEY_LENGTH = 64
    }
}
