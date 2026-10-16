/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * SAF（Storage Access Framework）文件读写小工具。
 *
 * ## 为什么抽到这里
 *
 * 「导入 / 导出」页（ImportExportScreen）与「格式转换」页（ConversionScreen）都要经
 * SAF 读用户文件、写产物文件。两页各写一份 `contentResolver.openInputStream`
 * 的后果：某天给读路径加「超 100MB 拒绝」的防护，只改一页 = 另一页静默缺失。
 *
 * 刻意**不做** Compose 组件（这三个函数与 UI 框架无关，纯 Android API 包装），
 * 所以放 `ui/common` 而不是 settings 包的组件文件。
 *
 * 失败一律**不抛**：SAF 的 URI 随时可能被撤销（用户清了权限 / 文件被移走），
 * 抛异常会让调用方在协程里炸栈；返回 null / false 让调用方给用户一句话就行。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * SAF URI 的显示名（选不到时返回 null，调用方退回 `uri.lastPathSegment`）。
 *
 * 走 [OpenableColumns.DISPLAY_NAME] 而不是末段：下载目录里的 URI 末段常是
 * 一串文档 id（`msf:1234`），显示给用户毫无意义。
 */
fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0 || !cursor.moveToFirst()) return@use null
        cursor.getString(index)
    }
}.getOrNull()

/** 读整个文件为字节（失败返回 null）。 */
fun readAllBytes(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}.getOrNull()

/**
 * 把字节写入目标 URI（覆盖写；失败返回 false）。
 *
 * ⚠️ `openOutputStream` 返回 null 时不抛业务异常 —— 上面 KDoc 说的「URI 失效」
 * 典型形态就是它（文件被删 / 权限撤销），统一走 false 分支。
 */
fun writeBytes(context: Context, uri: Uri, bytes: ByteArray): Boolean = runCatching {
    val out = context.contentResolver.openOutputStream(uri) ?: return false
    out.use { it.write(bytes) }
    true
}.getOrDefault(false)
