/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 来源：语义对齐 Bitwarden Android 客户端
 * `data/platform/util/CallingAppInfoExtensions.kt`（GPL-3.0，Copyright Bitwarden Inc. 2024），
 * 按 Vaultix 结构重写为单一工具对象。
 *
 * 为什么需要本文件（兼容性根因备忘）：
 * 曾经把 `androidx.credentials` 锁在 1.3.0，理由是「1.6.0 把 `CallingAppInfo.origin`
 * 收紧为 internal，读它做来源校验会编译失败」。但 1.3.0 缺 1.5.0 才引入的「凭据选择
 * 二级 UI 体验」（聚焦输入框时向 Credential Manager 下发请求 + 下拉/键盘建议聚合），
 * 而这正是 Chromium（Chrome/Edge）在 Android 14+ 呈现凭据条目的机制 —— 结果是
 * 「凭据提供商已启用，但浏览器点密码框什么都不弹」。
 *
 * 升级到 1.6.0 后，官方给出的替代读法是实例方法 `isOriginPopulated()` + `getOrigin()`；
 * 本对象把它们包成不会抛异常的安全读法，供两个 Activity 与 Service 共用。
 */
package io.vaultix.vaultix.passkey

import androidx.credentials.provider.CallingAppInfo

/**
 * `CallingAppInfo` 来源读取的唯一入口。
 *
 * 三种来源形态（对齐 Bitwarden 的判定顺序）：
 *  1. **privileged app**：`isOriginPopulated()` 为真 → 直接 `getOrigin()`；
 *  2. **普通 App（无资产链接）**：无 origin，仅能拿到 `packageName`；
 *  3. **浏览器（Chromium 系）**：origin 由系统填入（形如 `https://example.com`）。
 *
 * 注意 [isOriginPopulated] / [getOrigin] 在部分实现上会对格式非法的 allowList 抛
 * `IllegalArgumentException`、对缺签名抛 `IllegalStateException`（Bitwarden 同款处理），
 * 故一律 runCatching 兜底为「无 origin」而非让整个流程崩掉。
 */
object CallingAppOrigin {

    /**
     * 调用方声明的 origin（如 `https://example.com`）；不可得时返回 null。
     *
     * 返回 null 表示「调用方没有可用的网页来源」——通行密钥断言此时需回退到
     * 请求 JSON 里的 `origin` 或 rpId（见 PasskeyGetActivity）。
     */
    fun originOrNull(callingAppInfo: CallingAppInfo?): String? {
        val info = callingAppInfo ?: return null
        val populated = runCatching { info.isOriginPopulated() }.getOrDefault(false)
        if (!populated) return null
        return runCatching { info.getOrigin() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 调用方包名（浏览器即为 `com.microsoft.emmx` / `com.android.chrome` 等）。 */
    fun packageNameOrNull(callingAppInfo: CallingAppInfo?): String? =
        callingAppInfo?.packageName?.takeIf { it.isNotBlank() }
}
