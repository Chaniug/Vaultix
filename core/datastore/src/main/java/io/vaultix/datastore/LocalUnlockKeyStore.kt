/*
 * Vaultix — core:datastore
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「登录成功后把账号对称密钥用 Keystore 用户认证密钥包裹落盘、锁定时只清内存、
 * 生物识别/设备凭据重新解封」的模型与 Bitwarden 官方客户端及 Bastion
 * （GPL-3.0，Copyright 2025 JoyinJoester）的 SecurityManager 思路一致；
 * 本文件为独立实现，不含其代码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KEK 的健康状态（三态）。
 *
 * 为什么需要它：`containsAlias` **不能**当健康检查 —— AOSP 的 `AndroidKeyStoreSpi`
 * 在密钥被永久失效时（用户新增/删除指纹、清除锁屏凭据）会让 `getKeyMetadata()` **静默
 * 返回 null**（只打一条 warning），于是 `containsAlias` 变 false，应用看到的就是
 * 「别名不存在」；只有 `getKey()` 才会把 KEY_PERMANENTLY_INVALIDATED 暴露成
 * `UnrecoverableKeyException`。两者混为一谈时，「开关还开着但钥匙已废」会被当成
 * 「从未启用」——用户体感就是**覆盖安装/重启后指纹解锁被莫名清除且再也开不回来**。
 */
enum class LocalUnlockKekStatus {
    /** 存在且可加载（能否真正解密仍取决于本次生物认证）。 */
    LOADABLE,

    /** 别名不存在（从未启用过，或已被删除）。 */
    MISSING,

    /** 存在但被平台永久失效（凭据变更）→ 只能删除重建，无法解密旧 payload。 */
    INVALIDATED,
}

/**
 * 本地快速解锁的 Keystore 门禁（KEK）。
 *
 * 模型：Android Keystore 里生成一把 **用户认证保护** 的 AES-256-GCM 密钥：
 * - 登录/主密码解锁成功后，用该 KEK 把账号对称密钥（64B）加密落盘
 *   （payload 由调用方存 [SecureCredentialStore]）；
 * - 锁库只清内存；再次解锁时先 `initDecrypt` 把 Cipher 交给
 *   BiometricPrompt（生物识别或设备 PIN，API 30+ 可两者，低版本生物识别），
 *   认证通过后 `decryptPayload` 取回密钥——主密码与 2FA 都无需再走网络；
 * - 指纹/人脸变更（enrollment 增删）会 invalidate KEK（默认行为），
 *   被包裹数据随之不可解 → 需要重新主密码登录一次。
 *
 * ⚠️ Cipher 一旦 init 必须立刻交给本次 BiometricPrompt；跨认证复用不安全。
 * ⚠️ **解密路径绝不新建 KEK**（见 [newDecryptCipher]）：静默重建会把「钥匙丢了」
 * 掩盖成一次认证成功 + 解密失败，进而触发上层把用户注册真删掉。
 */
@Singleton
class LocalUnlockKeyStore @Inject constructor() {

    /**
     * KEK 三态探测（**只读，不产生任何副作用**）。
     *
     * 判定方式刻意用 `getKey` 而不是 `containsAlias`：见 [LocalUnlockKekStatus]。
     */
    val kekStatus: LocalUnlockKekStatus
        get() = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(KEY_ALIAS, null) != null
        }.fold(
            onSuccess = { exists -> if (exists) LocalUnlockKekStatus.LOADABLE else LocalUnlockKekStatus.MISSING },
            // 任何异常（UnrecoverableKeyException / ProviderException…）都归为「已失效」：
            // 它比对上层更安全的语义是「这把钥匙不能用了，需要重建」，而不是「从未启用」。
            onFailure = { LocalUnlockKekStatus.INVALIDATED },
        )

    /** 是否已有一把**可用**的 KEK（设备支持判定：无锁屏/无生物识别时创建会失败）。 */
    val keyAvailable: Boolean
        get() = kekStatus == LocalUnlockKekStatus.LOADABLE

    /**
     * 初始化「包装」Cipher（启用快速解锁时用）：随机 IV，需用户认证后 doFinal。
     *
     * ⚠️ 这是**唯一**允许新建 KEK 的入口：旧 KEK 若已失效会先被删除再重建，
     * 保证用户点「启用」总能成功（否则会卡在「点了没反应」的死局里无法自愈）。
     * 返回 null 表示设备不支持（无锁屏凭据 / 无强生物识别）。
     */
    fun newEncryptCipher(): Cipher? = runCatching {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, obtainOrCreateKey())
        }
    }.getOrNull()

    /**
     * 初始化「解封」Cipher（解锁时用）：解密必须先指定 IV，而 IV 存于 payload，
     * 故由 payload 驱动 init；随后把 cipher 交给 BiometricPrompt，认证通过后
     * 调 [unwrap] 完成解密。
     *
     * ⚠️ **KEK 缺失或已失效时直接返回 null，绝不新建**：旧实现在这里会顺手生成一把
     * 新 KEK，于是 ①`Cipher.init` 在 per-use 认证下不报错 → ②BiometricPrompt 认证
     * **成功** → ③`unwrap` 抛 `AEADBadTagException` → ④上层判定「不可恢复」并**删除
     * 用户的快速解锁注册**。钥匙丢了不该顺手把锁砸了：返回 null 让 UI 如实提示
     * 「本地解锁不可用，请用主密码登录」，用户可重新启用自愈。
     */
    fun newDecryptCipher(payload: String): Cipher? {
        val key = loadKey() ?: return null
        return runCatching {
            val parts = payload.split(SEPARATOR)
            check(parts.size == 2) { "invalid wrapped key payload" }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
        }.getOrNull()
    }

    /** 认证通过后完成包装：明文 full key → "iv.b64|cipher.b64" payload。 */
    fun wrap(cipher: Cipher, fullKey: ByteArray): String {
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(fullKey)
        return buildString {
            append(Base64.encodeToString(iv, Base64.NO_WRAP))
            append(SEPARATOR)
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    /** 认证通过后完成解封：payload → 明文 full key。 */
    fun unwrap(cipher: Cipher, payload: String): ByteArray {
        val parts = payload.split(SEPARATOR)
        check(parts.size == 2) { "invalid wrapped key payload" }
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        return cipher.doFinal(ciphertext)
    }

    /** 关闭快速解锁：删除 KEK（被包裹数据随之永久不可解）。幂等。 */
    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(KEY_ALIAS)
        }
    }

    // API 26-29 无 setUserAuthenticationParameters；setUserAuthenticationValidityDurationSeconds
    // 已废弃但该分支是低版本唯一写法（deprecation 告警属预期）
    @Suppress("DEPRECATION")
    private fun obtainOrCreateKey(): SecretKey {
        // 已失效的旧 KEK 先删：部分 OEM KeyMint 在别名已被永久失效时直接
        // generateKey 覆盖会失败，先删再建才能保证「用户点启用一定成功」。
        loadKey()?.let { return it }
        deleteKey()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    // API 30+：生物识别强认证或设备凭据（PIN/图案/密码）都可解锁
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(
                        0, // 0 = 每次使用都需认证（不做超时免认证）
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    // API 26-29：仅强生物识别（设备凭据无法绑定 Keystore key）
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            // 默认 invalidatedByBiometricEnrollment=true：新增/删除指纹使人脸失效
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** 只读加载既有 KEK（**缺失或失效时返回 null，绝不新建**）。 */
    private fun loadKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vaultix_local_unlock_kek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val SEPARATOR = "."
    }
}
