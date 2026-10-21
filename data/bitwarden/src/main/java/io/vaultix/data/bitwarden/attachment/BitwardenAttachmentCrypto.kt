/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * Bitwarden 附件密文的字节布局与密钥包裹方式参考 Bastion 项目（GPL-3.0，Copyright 2025
 * JoyinJoester）的 `attachments/crypto/BitwardenAttachmentCrypto.kt`；本文件为独立实现，
 * 并额外要求「先验 MAC、再解密」（参见下方 KDoc）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.attachment

import io.vaultix.crypto.SymmetricCryptoKey
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bitwarden **附件密文**的解密器（只读方向，2026-10-21）。
 *
 * ## 密文布局（官方格式，不是 EncString 文本）
 *
 * ```
 * offset 0       16                        EOF-32        EOF
 * +--------------+-------------------------+-------------+
 * | 16B IV(明文) | AES-256-CBC 密文(PKCS7) | 32B HMAC    |
 * +--------------+-------------------------+-------------+
 * ```
 *
 * - AES key = 附件密钥的 encKey（32B），MAC key = macKey（32B）；
 * - HMAC-SHA256 的输入是 `IV ‖ 密文`（**不含** 最后那 32 字节 MAC 本身）。
 *
 * ## ★ 为什么必须「先验 MAC、再解密」—— 本实现与参考实现最大的差别
 *
 * 参考实现是**一边解密一边喂 MAC**（流式，省一次读盘），代价是：MAC 只有在写完之后
 * 才被校验，**明文已经落盘了**。对附件这种"解密后直接交给系统「另存/打开」"的场景，
 * 那意味着一个被篡改的密文可以先把**任意内容**写进用户指定的文件，校验失败时才删 ——
 * 中途崩溃或被其它应用读取的窗口里，那份未认证的内容已经出去了。
 *
 * ⇒ 本实现选择**先完整校验 HMAC，通过后才解密写出**。代价是多扫一遍字节
 * （内存里只有 16KB 缓冲，不占空间）；换到的是「写出去的一定是认证过的明文」。
 *
 * ## 为什么是流式而不是 `ByteArray`
 *
 * 附件单文件上限 500MB（移动端 100MB）。整份读进内存对密码管理器不可接受 ——
 * 那会让"打开一个带大附件的条目"直接 OOM。
 */
internal object BitwardenAttachmentCrypto {

    /** IV 长度（AES 块长）。 */
    private const val IV_SIZE = 16

    /** HMAC-SHA256 输出长度。 */
    private const val MAC_SIZE = 32

    /** 附件密钥材料长度：encKey(32) ‖ macKey(32)。 */
    private const val KEY_MATERIAL_SIZE = 64

    /** 附件密钥前半段（AES 加密密钥）长度。 */
    private const val KEY_ENC_SIZE = 32

    private const val BUFFER_SIZE = 16 * 1024

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * 从 EncString 解开附件独立密钥。
     *
     * @param wrappedKey 服务端给的 `attachments[i].key`（EncString 文本）。
     * @param unwrap 用哪把钥匙解包 —— cipher key（条目有独立 key 时）或账号 key。
     *   ⚠️ 做成函数参数而不是自己判断：**"哪把钥匙"这件事只有调用方知道**
     *   （要不要先解 `cipher.key`）。在这里再猜一遍，就是第二份判据。
     * @return 64 字节的附件密钥；长度不对时抛 [IllegalArgumentException]（说明协议不符，
     *   **不要**截断或补零 —— 那会解出垃圾还以为只是"文件坏了"）。
     */
    fun unwrapAttachmentKey(
        wrappedKey: String,
        unwrap: (String) -> ByteArray,
    ): SymmetricCryptoKey {
        val raw = unwrap(wrappedKey)
        try {
            require(raw.size == KEY_MATERIAL_SIZE) {
                "附件密钥长度应为 $KEY_MATERIAL_SIZE 字节，实际 ${raw.size}"
            }
            return SymmetricCryptoKey(
                raw.copyOfRange(0, KEY_ENC_SIZE),
                raw.copyOfRange(KEY_ENC_SIZE, KEY_MATERIAL_SIZE),
            )
        } finally {
            raw.fill(0)
        }
    }

    /**
     * 解密 [cipherStream] 的全部内容并写入 [sink]。
     *
     * ## 两个"不许"
     *
     * 1. **不许在校验失败时留下任何字节**：调用方拿到的异常必须意味着 `sink`
     *    一个字都没写。所以本函数**先**把整条流读一遍算 MAC，再从头解密 ——
     *    这也意味着 [cipherStream] 必须可重复读（`ByteArrayInputStream` / 可 rewind 的
     *    文件流）。网上下载的字节本来就该先落临时文件，本就不该直接流式喂进来。
     * 2. **不许把 MAC 尾缀当密文解**：密文区间是 `[16, size-32)`。
     *
     * @return 写出的明文字节数。
     * @throws AttachmentAuthException MAC 不匹配（密文被篡改 / 密钥不对）。
     * @throws AttachmentFormatException 长度不足（连 IV + MAC 都塞不下）。
     */
    fun decrypt(encrypted: ByteArray, key: SymmetricCryptoKey, sink: OutputStream): Long {
        if (encrypted.size < IV_SIZE + MAC_SIZE) {
            throw AttachmentFormatException(
                "附件密文过短（${encrypted.size} 字节），无法包含 IV 与校验码",
            )
        }
        val ciphertextEnd = encrypted.size - MAC_SIZE
        val iv = encrypted.copyOfRange(0, IV_SIZE)
        val ciphertextLen = ciphertextEnd - IV_SIZE

        // ① 先验 MAC：MAC 覆盖 IV ‖ 密文（不含尾缀）。
        val actualMac = key.macKey.useBytes { macKeyBytes ->
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(macKeyBytes, HMAC_ALGORITHM))
            mac.update(encrypted, 0, ciphertextEnd)
            mac.doFinal()
        }
        val expectedMac = encrypted.copyOfRange(ciphertextEnd, encrypted.size)
        val macOk = java.security.MessageDigest.isEqual(actualMac, expectedMac)
        actualMac.fill(0)
        if (!macOk) throw AttachmentAuthException()

        // ② 验过了才解；PKCS7 去填充由 JCE 负责。
        val cipher = Cipher.getInstance(TRANSFORMATION)
        key.encKey.useBytes { encKeyBytes ->
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKeyBytes, "AES"), IvParameterSpec(iv))
        }
        var written = 0L
        var offset = IV_SIZE
        val chunk = ByteArray(BUFFER_SIZE)
        while (offset < ciphertextEnd) {
            val len = minOf(BUFFER_SIZE, ciphertextEnd - offset)
            val produced = cipher.update(encrypted, offset, len, chunk, 0)
            if (produced > 0) {
                sink.write(chunk, 0, produced)
                written += produced
            }
            offset += len
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) {
            sink.write(tail)
            written += tail.size
            tail.fill(0)
        }
        chunk.fill(0)
        iv.fill(0)
        return written
    }
}

/**
 * 附件密文**认证失败**：MAC 不匹配。
 *
 * ## 为什么值得单独一个类型
 *
 * 它与"网络断了"的用户动作完全不同：
 * - 网络问题 ⇒ 重试有意义；
 * - MAC 不对 ⇒ **重试永远不会成功**，说明文件被篡改或密钥对不上。
 *   混进 IO 异常里，UI 会一直提示"下载失败，请重试"。
 *
 * ⚠️ 消息里**不含**任何密钥材料片段（见 `VaultixLog` 铁律）。
 */
class AttachmentAuthException : Exception("附件校验失败：内容可能被篡改或密钥不匹配")

/** 附件密文**格式不对**（长度不足、结构不符）。 */
class AttachmentFormatException(message: String) : Exception(message)
