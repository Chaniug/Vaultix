/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * This file is part of Vaultix.
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 * Vaultix is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Vaultix.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件的 [SecureBytes] 是 Vaultix 新增类型，但设计目标源自 Bastion 项目
 * （GPL-3.0，Copyright 2025 JoyinJoester）中「密钥材料必须可清零、禁止用 String
 * 保存」的既有约定，见：
 *   bastion/Bastion/app/src/main/java/com/bastion/app/bitwarden/crypto/BitwardenCrypto.kt
 * 该约定同样记载于 Vaultix 的 Docs/03-密码学与密钥管理.md 第 4 节
 * 「内存中的密钥 → SecureBytes（ByteArray 包装，提供 zero()）；禁止用 String 保存」。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 承载密钥材料的安全内存容器。
 *
 * 设计要点：
 * 1. **私有构造 + 拷贝语义**：外部只能通过 [of] / [random] 创建，[of] 默认拷贝入参，
 *    避免调用方持有一份"以为已被托管"的引用；需要转移所有权时显式传 `wipeSource = true`。
 * 2. **可清零**：[zero] 就地填充 0；实现 [AutoCloseable] 以便配合 `use {}`。
 * 3. **防时序比较**：[equals] 走 [MessageDigest.isEqual]，与项目规范
 *    （Docs/03 第 5 节）一致。
 * 4. **不泄露内容**：[toString] 只输出长度。
 *
 * 注意：JCE 的 `SecretKeySpec` / `Cipher` 只接受 `ByteArray`，因此 [useBytes] 会暴露
 * 内部数组。**调用方不得保存或逃逸该引用**，只能在 lambda 内一次性使用。
 */
class SecureBytes private constructor(
    private val data: ByteArray,
) : AutoCloseable {

    /** 字节数。清零后仍返回原长度（不清空容器本身，只抹内容）。 */
    val size: Int
        get() = data.size

    /** 是否为零长度容器。 */
    val isEmpty: Boolean
        get() = data.isEmpty()

    /**
     * 在作用域内访问内部字节数组。
     *
     * 用于与只接受 `ByteArray` 的 JCE API 对接。lambda 返回后调用方不应再持有引用。
     */
    fun <T> useBytes(block: (ByteArray) -> T): T = block(data)

    /** 深拷贝出一份新的 [SecureBytes]。 */
    fun copyOf(): SecureBytes = SecureBytes(data.copyOf())

    /** 深拷贝为普通 [ByteArray]（所有权转移给调用方，需自行清零）。 */
    fun toByteArray(): ByteArray = data.copyOf()

    /** 就地清零。幂等。 */
    fun zero() {
        data.fill(0)
    }

    override fun close() {
        zero()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureBytes) return false
        return MessageDigest.isEqual(data, other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "SecureBytes(size=$size)"

    companion object {
        /** 构造一份拷贝；[wipeSource] 为 true 时同时清零入参数组（所有权转移）。 */
        fun of(source: ByteArray, wipeSource: Boolean = false): SecureBytes {
            val copy = source.copyOf()
            if (wipeSource) source.fill(0)
            return SecureBytes(copy)
        }

        /** 生成 [size] 字节密码学安全随机数（Docs/03 第 5 节：一律 SecureRandom）。 */
        fun random(size: Int): SecureBytes = SecureBytes(CryptoRandom.nextBytes(size))

        /** 零长度容器。 */
        fun empty(): SecureBytes = SecureBytes(ByteArray(0))

        /** 供内部实现使用：直接接管一个已经归本对象所有的数组（不再拷贝）。 */
        internal fun adopt(owned: ByteArray): SecureBytes = SecureBytes(owned)
    }
}

/**
 * 模块内共享的密码学随机源。
 *
 * 使用 `SecureRandom()` 无参构造：Android 上由 Conscrypt 提供实现，纯 JVM 上由
 * `NativePRNG` 提供，二者都不需要 Android 平台类，保证单元测可跑。
 */
internal object CryptoRandom {
    private val random: SecureRandom = SecureRandom()

    /** 返回 [size] 字节随机数据。 */
    fun nextBytes(size: Int): ByteArray {
        require(size >= 0) { "Random size must be non-negative: $size" }
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }
}
