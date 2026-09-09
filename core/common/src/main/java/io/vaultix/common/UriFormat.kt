/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 网址/包名归一化逻辑移植自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * autofill_ng/BitwardenLikeAutofillMatcherNg.kt（normalizePackageName /
 * extractAndroidAppPackage / normalizeHost），仅取「识别」部分用于条目 URI
 * 展示与启动，不涉及自动填充匹配（M3 再独立实现）；数值与 Bastion 一致。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import java.net.URI
import java.util.Locale

/**
 * 条目 URI 的兼容格式识别（Bitwarden login.uris[i].uri）。
 *
 * Bitwarden 的 URI 不止 http(s) 网址，还可能是：
 * - `androidapp://com.example.app`：绑定到某个 Android 应用（自动填充/启动用）；
 * - `iosapp://...`：iOS 绑定（当前仅识别，不启动）；
 * - 裸域名 / 带路径的网址。
 *
 * 本对象把任意 uri 归一为 [UriKind]，供详情页展示「网址 / 应用」友好标签与
 * 对应的打开动作。纯 Kotlin（无 Android 依赖），便于单元复用。
 */
object UriFormat {

    /** Android 应用绑定前缀（Bitwarden 官方形态，服务端与其它客户端均识别）。 */
    const val ANDROID_APP_SCHEME = "androidapp://"

    /** 包名 → 条目 URI（`androidapp://<package>`）：条目关联手机 App 的唯一写法。 */
    fun androidAppUri(packageName: String): String = ANDROID_APP_SCHEME + packageName.trim()

    /** 把 uri 分类为网站 / Android 应用 / 其他。 */
    fun classify(uri: String): UriKind {
        val normalized = uri.trim()
        if (normalized.isEmpty()) return UriKind.Other(raw = normalized)

        val lower = normalized.lowercase(Locale.ROOT)
        if (lower.startsWith(ANDROID_APP_SCHEME) || lower.startsWith("android-app://")) {
            val pkg = normalized.substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
                .takeIf { it.isNotBlank() }
            return if (pkg != null) {
                UriKind.AndroidApp(raw = normalized, packageName = pkg)
            } else {
                UriKind.Other(raw = normalized)
            }
        }

        if (lower.startsWith("iosapp://")) {
            return UriKind.Other(raw = normalized)
        }

        val host = runCatching {
            val withScheme = if (lower.contains("://")) normalized else "https://$normalized"
            URI(withScheme).host
        }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        return if (host != null) {
            UriKind.Website(raw = normalized, host = host)
        } else {
            UriKind.Other(raw = normalized)
        }
    }
}

/** 归一化后的 URI 种类。 */
sealed interface UriKind {
    /** 原始字符串（上传/复制用）。 */
    val raw: String

    /** 网站（http/https/裸域名）。 */
    data class Website(override val raw: String, val host: String) : UriKind

    /** Android 应用绑定（androidapp://<package>）。 */
    data class AndroidApp(override val raw: String, val packageName: String) : UriKind

    /** 无法归类的格式（直接原样展示）。 */
    data class Other(override val raw: String) : UriKind
}
