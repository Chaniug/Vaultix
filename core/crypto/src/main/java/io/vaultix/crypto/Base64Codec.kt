/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件由 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 *   bastion/Bastion/app/src/main/java/com/bastion/app/bitwarden/crypto/BitwardenCrypto.kt
 * 中的 `decodeBase64Part` / `Base64.encodeToString(..., Base64.NO_WRAP)` 搬运改造而来。
 * 核心改造：**android.util.Base64 → java.util.Base64**。
 * 原实现依赖 Android 平台类，纯 JVM 单元测试会抛 "not mocked" 运行时异常；
 * minSdk 26 已完整支持 java.util.Base64，且它与 android.util.Base64 在
 * 标准字母表 + 带 padding + 不换行（getEncoder/getDecoder）上的行为完全一致，
 * 可安全替换。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import java.util.Base64

/** 单个 EncString / GCM 信封的最大字符数（1 MiB），防止恶意输入撑爆内存。 */
internal const val MAX_CIPHER_STRING_LENGTH = 1024 * 1024

/** 单个 base64 分段的最大字符数（1 MiB）。 */
internal const val MAX_BASE64_PART_LENGTH = 1024 * 1024

/** 编码为标准 Base64（字母表含 '+' '/'，带 '=' 填充，不换行）——对齐 android.util.Base64.NO_WRAP。 */
internal fun ByteArray.encodeStandardBase64(): String = Base64.getEncoder().encodeToString(this)

/**
 * 解码 Bitwarden 风格的 base64 分段。
 *
 * 相比 Bastion 的 [decodeBase64Part] 增强：
 * - 同时接受标准字母表与 URL-safe 字母表（'-' → '+'，'_' → '/'）；
 * - 同时接受带填充与不带填充的输入（先剥离所有 '=' 再按长度补回），
 *   Bitwarden 服务端与各分支客户端实际两种都产出过；
 * - 长度上限校验，避免 `OutOfMemoryError` 变成可远程触发的 DoS。
 *
 * @param raw 原始分段文本
 * @param partName 分段的语义名（iv / data / mac），仅用于错误信息
 * @throws IllegalArgumentException 输入为空、超长或非法 base64
 */
internal fun decodeStandardBase64(raw: String, partName: String): ByteArray {
    val part = raw.trim()
    require(part.isNotEmpty()) { "Empty base64 part: $partName" }
    require(part.length <= MAX_BASE64_PART_LENGTH) {
        "Base64 part too large: $partName, len=${part.length}"
    }

    val normalized = part
        .replace('-', '+')
        .replace('_', '/')
        .replace("=", "")
    val padding = (4 - normalized.length % 4) % 4
    val padded = if (padding == 0) normalized else normalized + "=".repeat(padding)

    return try {
        Base64.getDecoder().decode(padded)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException(
            "Invalid base64 part: $partName, len=${part.length}",
            error,
        )
    }
}

/** 常量时间比较（Docs/03 第 5 节：所有密钥/MAC 比较一律走 MessageDigest.isEqual）。 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
    java.security.MessageDigest.isEqual(a, b)
