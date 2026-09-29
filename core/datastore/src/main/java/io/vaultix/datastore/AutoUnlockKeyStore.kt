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
 * 「vault timeout = Never 时把解锁密钥用一把**不要求用户认证**的 Keystore 密钥
 * 加密落盘、进程重启后自动恢复会话、主动锁库时删除」的模型逐句对齐 Bitwarden
 * Android 官方客户端的 `AuthDiskSource.storeUserAutoUnlockKey`（存进
 * keystoreEncryptedPreferences，即 androidx security-crypto 的 Keystore 主密钥加密
 * 偏好，GPL-3.0，Copyright Bitwarden Inc.）；本文件为独立实现，不含其代码。
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
 * 「从不锁定」档位的自动恢复门禁（对齐 Bitwarden `userAutoUnlockKey` 的载体）。
 *
 * 与 [LocalUnlockKeyStore]（指纹门锁 KEK）的关键差别：本密钥
 * **不要求用户认证**（`setUserAuthenticationRequired(false)`）—— 这正是
 * 「从不」的语义本身：进程死亡后无需任何交互即可恢复会话。它包裹的也只有
 * 一样东西：房子钥匙（见 `data/repository/HouseKeyStore`，键 `house_lock_auto`）。
 *
 * ## 安全边界（定稿 2026-09-29 修订，硬约束 #1 的边界拓展）
 *
 * 旧约束「房钥匙绝不落盘」修订为「房钥匙**绝不以明文落盘**」：
 * - 指纹门锁信封：auth-per-use（每次用都要指纹）；
 * - PIN 门锁信封：Argon2id；
 * - **本信封：Keystore 硬件密钥（AES-256-GCM，密钥在 TEE/StrongBound，
 *   不可导出）**——拿到密文也解不开，密文只在用户显式选择「从不」档时存在。
 *
 * 实体安全边界 = 手机锁屏 + app 沙箱 + 硬件密钥。与 Bitwarden 的
 * keystoreEncryptedPreferences 同一暴露面。用户主动锁库（或改档离开 Never）时
 * 信封即删 —— 那一刻的「锁定」是真锁定。
 *
 * ⚠️ `setInvalidatedByBiometricEnrollment(false)`：生物增删不该废掉它 ——
 * 它与生物识别无关（废了会让「从不」档在用户加了个指纹后静默失效，
 * 表现是「明明设了从不，又要重新解锁」，且没有任何提示）。
 */
@Singleton
class AutoUnlockKeyStore @Inject constructor() {

    /**
     * 用自动恢复密钥包裹明文（可直接调用，无认证要求）。
     *
     * @return `iv.b64|cipher.b64` payload；null = Keystore 不可用（瞬时异常），
     *   调用方应如实跳过本次写入，下次状态变化再试。
     */
    fun encrypt(plain: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        buildString {
            append(Base64.encodeToString(iv, Base64.NO_WRAP))
            append(SEPARATOR)
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }.getOrNull()

    /**
     * 解开自动恢复信封。
     *
     * @return 明文；null = 信封损坏 / 密钥不可用（密钥不存在或永久失效时
     *   该信封**永久不可解**，调用方应删除它，避免每次启动白跑一次注定失败的解密）。
     */
    fun decrypt(payload: String): ByteArray? = runCatching {
        val parts = payload.split(SEPARATOR)
        check(parts.size == 2) { "invalid auto-unlock payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = loadKey() ?: return null
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }.doFinal(ciphertext)
    }.getOrNull()

    /** 删除 Keystore 密钥（信封随之永久不可解；调用方应连带删信封）。幂等。 */
    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(KEY_ALIAS)
        }
    }

    /**
     * 加密前取用或新建密钥。
     *
     * ⚠️ 与 [LocalUnlockKeyStore] 相反，**这里允许新建**：本密钥不包裹任何
     * 既有数据（信封还没写），新建不会让任何旧密文变得不可解 —— 「旧信封 +
     * 新密钥」的组合会在 [decrypt] 那里拿到 AEAD 校验失败，由调用方删信封自愈。
     */
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
            // 「从不」的语义 = 无交互恢复 ⇒ 不绑认证（这正是与指纹门锁的差异）。
            .setUserAuthenticationRequired(false)
            // 生物增删与本锁无关（见类 KDoc）。
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** 只读加载既有密钥（不存在返回 null）。 */
    private fun loadKey(): SecretKey? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            .getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vaultix_auto_unlock_kek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val SEPARATOR = "."
    }
}
