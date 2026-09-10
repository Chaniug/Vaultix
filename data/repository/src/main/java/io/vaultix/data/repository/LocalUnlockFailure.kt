/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「快速解锁包装失败必须区分『可重试』与『已不可恢复』，且不可恢复时必须清理状态」
 * 的不变量来自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * BiometricUnlockRegressionGuardTest / SecurityManager（invalidateCachedSecureKey、
 * persistKeystoreWrappedMdk 对 KeyPermanentlyInvalidatedException 与
 * UnrecoverableKeyException 的处理）；本文件为独立实现，不含其代码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

/**
 * 判定快速解锁包装是否已**不可恢复**。
 *
 * ## 为什么必须遍历异常链
 *
 * Android Keystore 抛出的失效异常常被包装后再抛出——`Cipher.init()` 内部会把它
 * 裹进 `ProviderException`，`KeyStore.getEntry()` 可能裹进
 * `UnrecoverableKeyException`。若只比对**顶层**类型，这些情形会漏判并落到兜底分支。
 *
 * ## 漏判的后果（静默死循环）
 *
 * 漏判 → 只吞异常而**不清状态** → 开关仍为 `enabled = true`、payload 仍在 →
 * `localUnlockAvailable` 仍返回 true → 设置页显示「已启用」，但用户每次点指纹
 * 都失败，**且永远无法自愈**（只能手动关闭再重新启用）。
 * Bastion 用 1923 行实现 + 专门的回归测试防的正是这一类故障。
 *
 * ## 被判定为不可恢复的异常
 *
 * - [android.security.keystore.KeyPermanentlyInvalidatedException]
 *   —— 用户新增/删除指纹后 KEK 被永久失效（`setInvalidatedByBiometricEnrollment(true)` 的默认后果）
 * - [java.security.UnrecoverableKeyException]
 *   —— 密钥不可恢复（陈旧句柄 / Keystore 被重置 / 设备锁屏被清除）
 * - [android.security.keystore.UserNotAuthenticatedException]
 *   —— 认证会话失效（超时或认证被撤销）
 * - [javax.crypto.AEADBadTagException]
 *   —— 密文校验失败（payload 与当前 KEK 不匹配，通常意味着 KEK 已被替换）
 *
 * @return true 表示该失败**不会因重试而好转**，调用方应清理快速解锁状态并回退主密码。
 */
internal fun Throwable.isLocalUnlockUnrecoverable(): Boolean {
    var cursor: Throwable? = this
    var depth = 0
    while (cursor != null && depth < MAX_CAUSE_DEPTH) {
        when (cursor) {
            is android.security.keystore.KeyPermanentlyInvalidatedException,
            is java.security.UnrecoverableKeyException,
            is android.security.keystore.UserNotAuthenticatedException,
            is javax.crypto.AEADBadTagException,
            -> return true
        }
        cursor = cursor.cause
        depth++
    }
    return false
}

/**
 * 异常链遍历深度上限。
 *
 * 防御性上限：异常链理论上不应成环（`Throwable.cause` 由运行时保证 `cause !== this`），
 * 但不对第三方实现的 `initCause` 行为做无限递归的假设。
 */
private const val MAX_CAUSE_DEPTH = 8
