/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 * ---------------------------------------------------------------------------
 */

package io.vaultix.data.repository

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * KDBX 快速解锁的**包裹物明文**编解码（`.ai/ISSUES.md` #93 / 定稿 §4.3）。
 *
 * ### 为什么需要单独一个编解码器
 *
 * Bitwarden 侧包的 `enc ‖ mac` 是**定长 64 字节**，`unwrap` 后直接交给
 * `SymmetricCryptoKey.fromFullKey` 即可，没有任何解析歧义。
 *
 * KDBX 侧要包的是**两个变长项**（主密码字符串 + 可选 keyfile 字节）。
 * 若裸拼，就会出现歧义：例如密码 `"ab"` + keyfile 首字节恰为 `'c'`，
 * 与密码 `"abc"` + 无 keyfile 得到**同一串字节** ⇒ 解出来是谁全靠猜，
 * 而且这种 bug 只在特定密码上偶发，极难定位。
 *
 * ⇒ 一律**长度前缀**（`DataOutputStream.writeInt` + `write(...)`），
 * 并把「有没有 keyfile」编码进格式而不是靠剩余长度推断。
 *
 * ### 线格式
 *
 * ```
 * [int  magic  ]  固定 0x4B445850（"KDXP"），版本演进时用于识别旧格式
 * [int  pwLen  ]  主密码 UTF-8 字节数
 * [byte ...    ]  主密码 UTF-8 字节
 * [int  kfLen  ]  keyfile 字节数；-1 = 无 keyfile
 * [byte ...    ]  keyfile 字节（kfLen >= 0 时）
 * ```
 *
 * ⚠️ 本类只负责「字节 ↔ 结构」，**不负责加密**（包裹/解包由
 * `LocalUnlockKeyStore.wrap/unwrap` 完成），也不负责校验凭据是否正确
 * （那必须真的解一次库，见 [VaultRepositoryImpl.prepareKdbxEnroll]）。
 */
internal object KdbxUnlockPayload {

    /** 魔数 `KDXP`：保护未来格式演进（读到别的值即视为不认识）。 */
    private const val MAGIC = 0x4B445850

    /** 无 keyfile 的哨兵值（用 -1 而非 0：空 keyfile 与「没有」语义不同）。 */
    private const val NO_KEYFILE = -1

    /** 编解码结果：成功给凭据，失败给可诊断的原因（不抛异常给调用方猜）。 */
    sealed interface DecodeResult {
        data class Ok(val masterPassword: String, val keyFileBytes: ByteArray?) : DecodeResult
        data class Malformed(val detail: String) : DecodeResult
    }

    /** 组装包裹物明文。 */
    fun encode(masterPassword: String, keyFileBytes: ByteArray?): ByteArray {
        val passwordBytes = masterPassword.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(passwordBytes.size)
            stream.write(passwordBytes)
            if (keyFileBytes == null) {
                stream.writeInt(NO_KEYFILE)
            } else {
                stream.writeInt(keyFileBytes.size)
                stream.write(keyFileBytes)
            }
        }
        return out.toByteArray()
    }

    /**
     * 解出包裹物明文。
     *
     * ⚠️ 任何越界 / 长度不符都返回 [DecodeResult.Malformed] 而不是抛异常或
     * 返回半截数据 —— 这里的数据来自 Keystore 解封，理论上应当完好，
     * 但「宁可明确报错，不可静默返回错密码」（静默的后果是用户看到
     * 「主密码不正确」而不知道自己刚刚输对了）。
     */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < HEADER_MIN_BYTES) {
            return DecodeResult.Malformed("包裹物过短（${bytes.size} 字节）")
        }
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.int != MAGIC) {
            return DecodeResult.Malformed("包裹物格式标识不匹配")
        }

        val passwordResult = readChunk(buffer, allowAbsent = false)
        val passwordBytes = when (passwordResult) {
            is ChunkResult.Ok -> passwordResult.bytes
            is ChunkResult.Malformed -> return DecodeResult.Malformed("主密码段：${passwordResult.detail}")
            ChunkResult.Absent -> return DecodeResult.Malformed("主密码段缺失")
        }

        val keyFileBytes = when (val keyFile = readChunk(buffer, allowAbsent = true)) {
            is ChunkResult.Ok -> keyFile.bytes
            is ChunkResult.Malformed -> return DecodeResult.Malformed("keyfile 段：${keyFile.detail}")
            ChunkResult.Absent -> null
        }

        return DecodeResult.Ok(
            masterPassword = String(passwordBytes, StandardCharsets.UTF_8),
            keyFileBytes = keyFileBytes,
        )
    }

    /** 读取一个「长度前缀 + 内容」段。 */
    private fun readChunk(buffer: ByteBuffer, allowAbsent: Boolean): ChunkResult {
        if (buffer.remaining() < Int.SIZE_BYTES) {
            return ChunkResult.Malformed("长度字段缺失")
        }
        val length = buffer.int
        if (allowAbsent && length == NO_KEYFILE) return ChunkResult.Absent
        if (length < 0) return ChunkResult.Malformed("长度非法（$length）")
        if (buffer.remaining() < length) {
            return ChunkResult.Malformed("声明 $length 字节，实际只剩 ${buffer.remaining()} 字节")
        }
        val chunk = ByteArray(length)
        buffer.get(chunk)
        return ChunkResult.Ok(chunk)
    }

    private sealed interface ChunkResult {
        data class Ok(val bytes: ByteArray) : ChunkResult
        data class Malformed(val detail: String) : ChunkResult
        data object Absent : ChunkResult
    }

    /** 最小合法长度：魔数 + 空密码长度 + 空 keyfile 哨兵。 */
    private const val HEADER_MIN_BYTES = Int.SIZE_BYTES * 3
}
