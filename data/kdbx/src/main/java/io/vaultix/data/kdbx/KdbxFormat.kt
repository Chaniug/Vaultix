/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * KDBX 文件头识别。
 *
 * 为什么要先识别再解密：`.kdbx` 被喂错文件（选了 .kdbx1/.key/.txt/加密盘文件）时，
 * kotpass 抛的是底层格式/解密异常，用户看到的是「密码错误」——**误导性极强**。
 * 这里先按官方文件头判版本，给出「这不是 KDBX 文件 / 这是 KDBX 3.1」这类确定结论，
 * 解密失败时才能理直气壮地说「密码或 keyfile 不对」。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

/** KDBX 文件头识别结果。 */
internal sealed interface KdbxFormat {
    /** 可读的 KDBX（[version] 形如 4.1 / 3.1）。 */
    data class Supported(val version: String, val major: Int) : KdbxFormat

    /** KDBX 家族但版本不受支持（如 KDBX 2.x 的 0x00020000）。 */
    data class UnsupportedVersion(val version: String) : KdbxFormat

    /** 根本不是 KDBX 文件。 */
    data object NotKdbx : KdbxFormat
}

/** 文件头前 8 字节：sig1 = 0x9AA2D903、sig2 = 0xB54BFB67（小端）。 */
private val KDBX_SIGNATURE = byteArrayOf(
    0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
    0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte(),
)

internal fun inspectKdbxFormat(bytes: ByteArray): KdbxFormat {
    if (bytes.size < HEADER_MIN_BYTES) return KdbxFormat.NotKdbx
    if (!bytes.copyOfRange(0, KDBX_SIGNATURE.size).contentEquals(KDBX_SIGNATURE)) return KdbxFormat.NotKdbx
    // 版本 4 字节小端：低 16 位 = minor，高 16 位 = major。
    val minor = (bytes[9].toInt() and 0xFF shl 8) or (bytes[8].toInt() and 0xFF)
    val major = (bytes[11].toInt() and 0xFF shl 8) or (bytes[10].toInt() and 0xFF)
    val version = "$major.$minor"
    return if (major >= SUPPORTED_MAJOR) {
        KdbxFormat.Supported(version, major)
    } else {
        KdbxFormat.UnsupportedVersion(version)
    }
}

/** 文件头最小可判定长度（8 字节签名 + 4 字节版本）。 */
private const val HEADER_MIN_BYTES = 12

/** 支持的最低主版本：KDBX 3.1 起（3.0 及更早的字段模型差异过大，明确不支持）。 */
private const val SUPPORTED_MAJOR = 3
