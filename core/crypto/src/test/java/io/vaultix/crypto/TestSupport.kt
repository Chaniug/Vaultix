/*
 * Vaultix — core:crypto 单元测试支撑
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 说明：本文件不是被测代码，只是测试专用工具。
 * 1) 密码学断言一律以「小写十六进制字符串」比较：ByteArray 的 Truth 断言失败信息不可读，
 *    而向量比对必须能定位到具体字节。
 * 2) assertThrows 自行实现而非依赖 Truth.assertThrows：避免绑定特定 Truth 版本 API，
 *    且失败时能同时打印期望类型与实际类型。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import java.nio.charset.StandardCharsets

/** UTF-8 编码，测试内统一收口，避免各处重复写 `toByteArray(StandardCharsets.UTF_8)`。 */
internal fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

/** 字节数组转小写十六进制（无分隔符）。 */
internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    byte.toInt().and(0xFF).toString(radix = 16).padStart(2, '0')
}

/** 十六进制字符串转字节数组；用于把参考向量写成可读常量。 */
internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have even length: ${hex.length}" }
    return ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
    }
}

/** 生成 `size` 字节全 0 的十六进制表示，用于断言「已清零」。 */
internal fun zerosHex(size: Int): String = "00".repeat(size)

/**
 * 断言 [block] 抛出 [T]，并返回该异常以便进一步断言其属性（如 [UnsupportedCipherTypeException.type]）。
 *
 * @throws AssertionError 未抛异常，或抛出的类型不是 [T]
 */
internal inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    var thrown: Throwable? = null
    try {
        block()
    } catch (error: Throwable) {
        thrown = error
    }
    if (thrown == null) {
        throw AssertionError("Expected ${T::class.java.simpleName} to be thrown, but nothing was.")
    }
    if (thrown !is T) {
        throw AssertionError(
            "Expected ${T::class.java.simpleName}, but got ${thrown::class.java.simpleName}: ${thrown.message}",
            thrown,
        )
    }
    return thrown
}
