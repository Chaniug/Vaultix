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
 */
@Singleton
class LocalUnlockKeyStore @Inject constructor() {

    /** 是否已生成 KEK（设备支持判定：无锁屏/无生物识别时创建会失败）。 */
    val keyAvailable: Boolean
        get() = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.containsAlias(KEY_ALIAS)
        }.getOrDefault(false)

    /**
     * 初始化「包装」Cipher（启用快速解锁时用）：随机 IV，需用户认证后 doFinal。
     * 返回 null 表示 KEK 不存在或设备不支持（上层回退主密码登录）。
     */
    fun newEncryptCipher(): Cipher? = runCatching {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, obtainKey())
        }
    }.getOrNull()

    /**
     * 初始化「解封」Cipher（解锁时用）：解密必须先指定 IV，而 IV 存于 payload，
     * 故由 payload 驱动 init；随后把 cipher 交给 BiometricPrompt，认证通过后
     * 调 [unwrap] 完成解密。返回 null 表示 KEK 不存在/设备不支持。
     */
    fun newDecryptCipher(payload: String): Cipher? = runCatching {
        val parts = payload.split(SEPARATOR)
        check(parts.size == 2) { "invalid wrapped key payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, iv))
        }
    }.getOrNull()

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
    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vaultix_local_unlock_kek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val SEPARATOR = "."
    }
}
