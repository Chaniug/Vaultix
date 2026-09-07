/*
 * Vaultix — core:datastore
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 敏感凭据存储。
 *
 * 实现选择：直接使用 **Android Keystore + AES-256-GCM**，而非
 * ndroidx.security.crypto 的 EncryptedSharedPreferences——后者（含 MasterKey）
 * 在 1.1.0 中已整体废弃。
 *
 * 安全性：密钥由 Keystore 生成且**不可导出**，加解密都在 Keystore 内完成，
 * 应用进程拿不到密钥材料；每个值的 IV 随机生成，密文自带认证标签（GCM）。
 *
 * 用于 refresh token、生物识别解封出的密钥材料等绝不能明文落盘的数据（见 Docs/09）。
 */
@Singleton
class SecureCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 惰性获取，避免构造时触发 Keystore IO。 */
    private val key: SecretKey by lazy(::getOrCreateKey)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
        }.generateKey()
    }

    fun putString(storageKey: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = buildString {
            append(Base64.encodeToString(iv, Base64.NO_WRAP))
            append(SEPARATOR)
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        prefs.edit().putString(storageKey, encoded).apply()
    }

    fun getString(storageKey: String): String? {
        val raw = prefs.getString(storageKey, null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size != 2) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(storageKey: String) {
        prefs.edit().remove(storageKey).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vaultix_credential_key"
        const val PREFS_NAME = "vaultix_secure"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val SEPARATOR = "."
    }
}
