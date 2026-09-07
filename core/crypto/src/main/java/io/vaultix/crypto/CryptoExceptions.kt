/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 * Vaultix is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件由 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）同名类搬运改造而来：
 *   bastion/Bastion/app/src/main/java/com/bastion/app/bitwarden/crypto/BitwardenKdfMemoryException.kt
 *   bastion/Bastion/app/src/main/java/com/bastion/app/bitwarden/crypto/BitwardenArgon2MemoryGuard.kt
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

/**
 * Argon2id 请求内存超过当前 JVM 安全上限时抛出。
 *
 * 搬运自 Bastion 的 `BitwardenKdfMemoryException`：这是它在 Android 低端机上踩到
 * Argon2 触发 OOM 后的收敛产物。Vaultix 保留该异常，但消息文案改为 Vaultix 措辞，
 * 并把三个诊断字段（请求内存 / 堆上限 / 安全上限）暴露给上层，用于给用户
 * 「该设备内存不足，请降低 KDF 内存参数」的准确提示。
 *
 * @property requestedMemoryMb 服务端下发的 Argon2 memory 参数（MiB）
 * @property maxHeapMb 当前进程堆上限（MiB）
 * @property safeLimitMb 依据护栏算出的安全上限（MiB）
 */
class VaultixKdfMemoryException(
    val requestedMemoryMb: Int,
    val maxHeapMb: Long,
    val safeLimitMb: Long,
) : IllegalStateException(
    "Vaultix Argon2id KDF memory is too high for the current runtime: " +
        "requested=${requestedMemoryMb}MB, safeLimit=${safeLimitMb}MB, heap=${maxHeapMb}MB",
)

/**
 * MAC 校验失败。
 *
 * 单独建模（而非裸 `SecurityException`），便于上层区分「密码错误 / 数据被篡改」
 * 与「其他 IO 异常」。Bastion 原实现直接 `throw SecurityException("MAC verification failed")`，
 * Vaultix 保留继承关系，既有 catch 逻辑不受影响。
 */
class MacVerificationException : SecurityException(
    "EncString MAC verification failed: ciphertext was tampered with, or the key is wrong",
)

/**
 * 遇到尚未实现的 EncString 类型（如 3/5 RSA-OAEP、7 COSE）。
 *
 * Docs/03 第 2.4 节要求这些类型「只读」，M0 阶段未实现，因此以明确异常暴露，
 * 而不是静默返回空数据。
 */
class UnsupportedCipherTypeException(
    val type: Int,
) : IllegalArgumentException(
    "Unsupported EncString type: $type. Vaultix M0 only reads type 2 (AesCbc256_HmacSha256_B64)",
)

/**
 * 遇到 Docs/03 第 2.4 节明确不支持的遗留类型 0（AesCbc256_B64，无 MAC）。
 *
 * 规范原话：「遇到即报『数据由过旧版本客户端生成，请升级后重新同步』」。
 * 解密路径默认拒绝；确实需要抢救旧数据时可显式打开 [VaultixCrypto.decrypt] 的
 * `allowLegacyWithoutMac` 开关。
 */
class LegacyCipherTypeException : IllegalArgumentException(
    "Legacy EncString type 0 (AesCbc256_B64, no MAC) is not supported. " +
        "This item was produced by an outdated client; re-sync after upgrading.",
)

/**
 * Argon2 内存护栏。
 *
 * 搬运自 Bastion 的 `BitwardenArgon2MemoryGuard`（GPL-3.0，Copyright 2025 JoyinJoester）。
 * 逻辑原样保留，仅重命名并改用 [VaultixKdfMemoryException]：
 *
 * 安全上限 = min(堆上限 / 2, 堆上限 - 已用堆 - 96MB 应用余量)，下限截断到 0。
 *
 * - 「堆上限 / 2」：Argon2 需要一次性连续块，不能贴近堆上限；
 * - 「96MB 应用余量」：给 UI / 数据库 / 图片缓存留出呼吸空间，避免 KDF 期间被 LMK 杀进程。
 */
object Argon2MemoryGuard {

    private const val BYTES_PER_MB = 1024L * 1024L
    private const val MIN_APP_HEADROOM_MB = 96L
    private const val MAX_ARGON2_HEAP_FRACTION_DIVISOR = 2L

    /**
     * 校验 [memoryMb] 是否可安全运行；不可行则抛 [VaultixKdfMemoryException]。
     *
     * @param memoryMb 请求的 Argon2 内存（MiB），必须为正
     */
    fun requireCanRun(memoryMb: Int) {
        if (memoryMb <= 0) {
            throw IllegalArgumentException("Argon2 memory must be positive: $memoryMb")
        }

        val runtime = Runtime.getRuntime()
        val maxHeapBytes = runtime.maxMemory()
        val usedHeapBytes = runtime.totalMemory() - runtime.freeMemory()
        val safeLimitMb = safeLimitMb(maxHeapBytes, usedHeapBytes)

        if (memoryMb.toLong() > safeLimitMb) {
            throw VaultixKdfMemoryException(
                requestedMemoryMb = memoryMb,
                maxHeapMb = bytesToMb(maxHeapBytes),
                safeLimitMb = safeLimitMb,
            )
        }
    }

    /** 依据堆上限与已用堆计算安全内存上限（MiB）。对纯函数，便于单测。 */
    internal fun safeLimitMb(maxHeapBytes: Long, usedHeapBytes: Long): Long {
        val fractionLimitBytes = maxHeapBytes / MAX_ARGON2_HEAP_FRACTION_DIVISOR
        val availableLimitBytes = maxHeapBytes - usedHeapBytes - (MIN_APP_HEADROOM_MB * BYTES_PER_MB)
        val safeLimitBytes = minOf(fractionLimitBytes, availableLimitBytes).coerceAtLeast(0L)
        return bytesToMb(safeLimitBytes)
    }

    private fun bytesToMb(bytes: Long): Long = bytes / BYTES_PER_MB
}
