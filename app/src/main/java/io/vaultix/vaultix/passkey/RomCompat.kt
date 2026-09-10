/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 来源：语义对齐 Bitwarden Android 客户端
 *  - `core/util/AndroidPropUtils.kt`（`isHyperOS`，GPL-3.0，Copyright Bitwarden Inc.）
 *  - `data/credentials/util/CredentialEntryBuilderExtensions.kt`
 *    （`setBiometricPromptDataIfSupported`）
 *
 * 作用：为 Credential Provider 的凭据条目（PasswordCredentialEntry /
 * PublicKeyCredentialEntry）按需附加 `BiometricPromptData`。
 *
 * 为什么必须做设备判定（否则会「越修越坏」）：
 *  - `setBiometricPromptData` 自 androidx.credentials 1.5.0 引入，要求 Android 15
 *    （API 35 / VANILLA_ICE_CREAM）及以上。
 *  - **小米 HyperOS 已知不兼容**（Bitwarden 原文注明）。荣耀 MagicOS 与 HyperOS 同源
 *    的「魔改厂商 ROM」属性，风险同族 —— 挂上后可能反而让系统在渲染条目阶段丢弃
 *    整个 entry，表现仍是「浏览器里什么都不弹」。故对同一族 ROM 采取保守策略：
 *    不挂 BiometricPromptData，改由条目点击后的 Activity 内做设备验证（Vaultix 原本的做法）。
 */
package io.vaultix.vaultix.passkey

import android.os.Build
import javax.crypto.Cipher

/**
 * 厂商 ROM 判定与 BiometricPromptData 支持判定。
 *
 * 判定依据取自系统属性（对齐 Bitwarden `AndroidPropUtils`）：
 *  - `ro.mi.os.version.name` 非空 → HyperOS。
 *  - MagicOS（荣耀）无稳定公开属性，故用 `ro.build.version.magic` / `ro.build.magic`
 *    前缀探测；探测不到时按「不确定 = 保守不挂」处理。
 */
object RomCompat {

    /** 是否小米 HyperOS（Bitwarden 明示与 BiometricPromptData 不兼容）。 */
    val isHyperOs: Boolean by lazy { !systemProperty(KEY_HYPER_OS).isNullOrEmpty() }

    /** 是否荣耀 MagicOS（与 HyperOS 同族魔改，保守同等待遇）。 */
    val isMagicOs: Boolean by lazy {
        !systemProperty(KEY_MAGIC_OS).isNullOrEmpty() ||
            !systemProperty(KEY_MAGIC_OS_LEGACY).isNullOrEmpty()
    }

    /**
     * 本机是否可安全使用 `CredentialEntry.setBiometricPromptData`。
     *
     * 条件：API ≥ 35 **且** 非 HyperOS / MagicOS 族 ROM。
     */
    val biometricPromptDataSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            !isHyperOs &&
            !isMagicOs
    }

    /**
     * 读取系统属性（`android.os.SystemProperties`，隐藏 API，反射调用）。
     * 任何失败（字段名变更 / 非 AOSP ROM 限制反射）都返回 null —— 判定退化为
     * 「不确定 = 保守不挂 BiometricPromptData」，不会崩。
     */
    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    }.getOrNull()

    private const val KEY_HYPER_OS = "ro.mi.os.version.name"
    private const val KEY_MAGIC_OS = "ro.build.version.magic"
    private const val KEY_MAGIC_OS_LEGACY = "ro.build.magic"
}

/**
 * 构造用于 `BiometricPromptData` 的 CryptoObject 时所需的 cipher。
 *
 * ⚠️ Vaultix 的库解密密钥只在内存（`VaultSessionManager`），**不存在**可用于
 * CryptoObject 的 Keystore 密钥绑定到某个库条目 —— 故这里返回 null，
 * 由条目点击后的 Activity（PasskeyGetActivity / PasswordGetActivity）承担设备验证。
 * 该函数保留为显式扩展点：日后若为凭据条目引入 Keystore 包裹的「快速验证」密钥，
 * 在此返回已 init 的 cipher 即可让系统在选择列表内直接完成生物识别。
 */
fun credentialEntryCipher(): Cipher? = null
