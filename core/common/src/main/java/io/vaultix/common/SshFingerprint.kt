/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 本文件为 Vaultix 新增（Bastion 无对应实现）：OpenSSH 公钥指纹计算。
 * 用途：SSH 密钥条目的表单在用户填好公钥后自动推导指纹，免去手抄
 * （指纹本就由公钥唯一确定，让人手抄反而是出错来源）。
 */
package io.vaultix.common

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * OpenSSH 公钥指纹（`SHA256:<base64>`），算法与 `ssh-keygen -lf` 逐位一致。
 *
 * 计算步骤：
 * 1. 取公钥行第二个字段（base64 的密钥 blob）解码为二进制；
 * 2. 对该 **blob 整体**求 SHA-256；
 * 3. base64 编码摘要、去掉尾部 `=` 填充，再加前缀 `SHA256:`。
 *
 * ⚠️ 第 2 步覆盖的是整个 blob（含算法名与密钥材料），**不是**只对密钥材料求哈希；
 * 正因如此第 1 步必须先解码 base64，不能直接对文本做哈希 —— 二者结果完全不同。
 *
 * 支持形态：`<算法> <base64> [注释]` 单行格式，即 `~/.ssh/id_*.pub` 的内容。
 * 不支持 RFC4716（`---- BEGIN SSH2 PUBLIC KEY ----`）、PuTTY `.ppk`，也不支持
 * `authorized_keys` 里带 `command=` / `from=` 选项前缀的行 —— 接受范围与
 * `ssh-keygen -lf` 对齐。
 *
 * 已用 `ssh-keygen -lf` 的权威输出做对照（ed25519 / rsa / ecdsa 三种各一组，
 * 期望值取自 ssh-keygen 而非本实现自算，见对应单测）。
 */
object SshFingerprint {

    /** 摘要标识前缀；OpenSSH 的「现代」指纹格式只有这一种。 */
    private const val SHA256_PREFIX = "SHA256:"

    /** base64 摘要尾部需要去掉的填充字符（OpenSSH 不补 `=`）。 */
    private const val BASE64_PADDING = '='

    /** OpenSSH blob 内的长度字段占 4 字节（大端无符号）。 */
    private const val LENGTH_FIELD_BYTES = 4

    /** 单行公钥至少两段：算法名 + base64 blob。 */
    private const val MIN_TOKENS = 2

    /** 算法名在行内的下标。 */
    private const val ALGORITHM_TOKEN = 0

    /** base64 blob 在行内的下标。 */
    private const val BLOB_TOKEN = 1

    private const val DIGEST_ALGORITHM = "SHA-256"

    private val WHITESPACE = Regex("\\s+")

    /**
     * 计算公钥指纹；输入不是合法 OpenSSH 公钥时返回 `null`。
     *
     * 返回 `null` 而**不抛异常**：表单是「边输边算」的，用户输入过程中必然出现
     * 大量中间态（只敲了半行、或刚粘贴未完成），抛异常会逼出无意义的 try/catch，
     * 也会让「暂时算不出来」与「真的填错了」失去区分度。
     */
    fun of(publicKey: String): String? {
        val blob = decodeBlob(publicKey) ?: return null
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(blob)
        val encoded = Base64.getEncoder().encodeToString(digest).trimEnd(BASE64_PADDING)
        return SHA256_PREFIX + encoded
    }

    /**
     * 解析 `<算法> <base64> [注释]`，并校验 blob 与文本**自洽**。
     *
     * 校验「blob 首字段 == 行首算法名」是关键一环：这是 OpenSSH blob 自带的
     * 结构约束，用它能把「随便一段 base64」挡在门外（例如用户误把私钥内容
     * 或别的 base64 贴进公钥框），而不是拿它硬算出一个看似合理的假指纹。
     */
    private fun decodeBlob(publicKey: String): ByteArray? {
        val tokens = publicKey.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size < MIN_TOKENS) return null
        val algorithm = tokens[ALGORITHM_TOKEN]
        val blob = runCatching { Base64.getDecoder().decode(tokens[BLOB_TOKEN]) }.getOrNull()
            ?: return null
        return blob.takeIf { it.startsWithAlgorithm(algorithm) }
    }

    /** blob 开头是「长度 + 算法名」（OpenSSH string），据以核对与行首算法名是否一致。 */
    private fun ByteArray.startsWithAlgorithm(algorithm: String): Boolean {
        if (size < LENGTH_FIELD_BYTES) return false
        val declared = ByteBuffer.wrap(this).int
        if (declared < 0 || size < LENGTH_FIELD_BYTES + declared) return false
        val inner = String(this, LENGTH_FIELD_BYTES, declared, Charsets.UTF_8)
        return inner == algorithm
    }
}
