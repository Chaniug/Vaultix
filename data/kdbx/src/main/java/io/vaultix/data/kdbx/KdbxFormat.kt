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
 * 这里先按官方文件头判版本，给出「这不是 KDBX 文件 / 这是 KDBX 3.1（需先用
 * KeePassXC 另存为 4.1）」这类确定结论，解密失败时才能理直气壮地说「密码或 keyfile 不对」。
 *
 * ⚠️ 2026-09-30 起**只支持主版本 4**（用户拍板；理由见下方 SUPPORTED_MAJOR 的长注释）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

/** KDBX 文件头识别结果。 */
internal sealed interface KdbxFormat {
    /** 可读的 KDBX（[version] 形如 4.1；本应用只支持主版本 4）。 */
    data class Supported(val version: String, val major: Int) : KdbxFormat

    /** KDBX 家族但版本不受支持（如 3.1 的 0x00030001、2.x 的 0x00020000）。 */
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

/**
 * 支持的最低主版本 = **4**。
 *
 * ## ★ 2026-09-30 用户拍板：只认 4.x（原为 3）
 *
 * 用户原话：「库的标准要匹配最新的 kdbx 格式，**只做最新兼容，4.1 以上，不向下兼容 3.0 等**」。
 *
 * 取舍说明（为什么敢直接拒读，而不是"能读就读"）：
 * - **写**侧本来就只能出 4.1（kotpass 的 `Ver4x.create` + `MaxSupportedVersion = 4`）；
 * - 若**读**侧仍接受 3.1，就会存在"读得进来、写不回同一个版本"的**半支持**状态 ——
 *   而 KDBX 3.x 与 4.x 在 kotpass 里是**两个不同的类**（`Ver3x` / `Ver4x`），
 *   字段模型有实质差异（`binaries` / `customData` / 内层头）。半支持意味着要写一条
 *   `Ver3x → Ver4x` 的字段迁移路径，而那正是**最容易把用户数据写坏**的一类代码。
 *   ⇒ 与其做一个半可信的迁移，不如**明确拒绝 + 给出下一步**（见 [KdbxOpenError.UnsupportedVersion]）。
 * - 拒绝是**可逆**的：用户用 KeePassXC 另存为 4.1 即可，且这一步由**专业工具**完成，
 *   比我们自己迁移字段更可靠。
 *
 * ⚠️ 这条与历史行为**相反**（旧代码接受 3.1+），是本轮主动收窄。
 * ⚠️ 拒绝文案必须给出"下一步"，不能只说"不支持" —— 见 `KdbxOpenError.UnsupportedVersion.guidance`。
 */
private const val SUPPORTED_MAJOR = 4
