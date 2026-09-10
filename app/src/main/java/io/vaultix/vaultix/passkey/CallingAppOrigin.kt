/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 来源：语义对齐 Bitwarden Android 客户端
 * `data/platform/util/CallingAppInfoExtensions.kt` 与
 * `data/credentials/manager/OriginManagerImpl.kt`（GPL-3.0，Copyright Bitwarden Inc. 2024），
 * 按 Vaultix 结构重写为单一工具对象。
 *
 * 为什么需要本文件（兼容性根因备忘）：
 * 曾经把 `androidx.credentials` 锁在 1.3.0，理由是「1.6.0 把 `CallingAppInfo.origin`
 * 收紧为 internal，读它做来源校验会编译失败」。但 1.3.0 缺 1.5.0 才引入的「凭据选择
 * 二级 UI 体验」（聚焦输入框时向 Credential Manager 下发请求 + 下拉/键盘建议聚合），
 * 而这正是 Chromium（Chrome/Edge）在 Android 14+ 呈现凭据条目的机制 —— 结果是
 * 「凭据提供商已启用，但浏览器点密码框什么都不弹」。
 *
 * 升级到 1.6.0 后，官方给出的替代读法是：
 *  1. `isOriginPopulated()` 判空守卫（origin 为 null 时 `getOrigin()` 返回 null，不作校验）；
 *  2. `getOrigin(privilegedAllowlist: String)` —— **必须传入一份合法的 JSON 特权应用名单**，
 *     且调用方包名与**签名指纹**都要在名单里命中，才会把 origin 回填出来。
 *
 * 反编译 1.6.0 确认的 `getOrigin(allowList)` 完整分支：
 * ```
 * 1. isValidJSON(allowList)                     ?: throw IllegalArgumentException  // 空串/非 JSON 抛
 * 2. if (origin == null) return null            // 系统没填 origin，直接返回，不校验
 * 3. apps = PrivilegedApp.extractPrivilegedApps(JSONObject(allowList))
 * 4. if (isAppPrivileged(apps)) return origin   // 包名命中 + 签名指纹 intersect 非空
 *    else throw IllegalStateException           // 命中不了
 * ```
 * 且 `PrivilegedApp.createFromJSONObject` 对 `signatures` 用的是 `getJSONArray`（必填），
 * 空数组能解析但指纹集合为空，`intersect()` 为空 → 仍抛 `IllegalStateException`。
 *
 * 结论：**任何「写死一个万能名单」的取巧写法都不成立**，必须用调用方自己的真实签名指纹。
 * 这正是 Bitwarden `OriginManagerImpl` 要读 assets 名单（Google / 社区）+ 用户信任名单的原因：
 * 它用名单来**做签名背书**，而 Vaultix 当前并不做这件事。
 *
 * 因此本文件的策略是「**自证式读取**」：拿调用方自己的签名指纹拼一份只含它自己的合法名单，
 * 从而纯粹地**把系统已填好的 origin 读出来**用于按来源过滤候选，不做任何身份背书。
 */
package io.vaultix.vaultix.passkey

import androidx.credentials.provider.CallingAppInfo
import java.security.MessageDigest

/**
 * `CallingAppInfo` 来源读取的唯一入口。
 *
 * 三种来源形态（对齐 Bitwarden 的判定顺序）：
 *  1. **privileged app**：`isOriginPopulated()` 为真 → 走 `getOrigin(allowList)`；
 *  2. **普通 App（无资产链接）**：无 origin，仅能拿到 `packageName`；
 *  3. **浏览器（Chromium 系）**：origin 由系统填入（形如 `https://example.com`）。
 *
 * 注意 `getOrigin()` 会抛两类异常（Bitwarden 同款处理，这里一律兜底为「无 origin」）：
 *  - `IllegalArgumentException`：allowList 不是合法 JSON / 调用方不在名单内；
 *  - `IllegalStateException`：名单命中但签名校验不通过。
 */
object CallingAppOrigin {

    /**
     * 单条特权应用名单模板。
     *
     * 结构对齐 `androidx.credentials.provider.utils.PrivilegedApp` 的解析约定：
     * `{"apps":[{"type":"android","info":{"package_name":"...","signatures":[{"cert_fingerprint_sha256":"..."}]}}]}`。
     * 注意 `signatures` 里必须是**对象数组**（`getJSONObject(i)` 后取 `cert_fingerprint_sha256`），
     * 不是裸字符串数组 —— 这是最容易写错、且错了只能拿到 `IllegalStateException` 的地方。
     */
    private const val ALLOW_LIST_TEMPLATE =
        """{"apps":[{"type":"android","info":{"package_name":"%s",""" +
            """"signatures":[{"cert_fingerprint_sha256":"%s"}]}}]}"""

    /** 用调用方真实包名 + 指纹拼出「只含它自己」的合法名单。 */
    private fun allowListFor(packageName: String, fingerprint: String): String =
        ALLOW_LIST_TEMPLATE.format(packageName, fingerprint)

    /**
     * 调用方声明的 origin（如 `https://example.com`）；不可得时返回 null。
     *
     * 读取步骤：
     *
     * 1. **origin 未填充** → 直接 null（系统没给来源，`getOrigin()` 也只会返回 null）。
     * 2. **算调用方签名指纹**：多签名者 / 无 APK 签名时返回 null —— 此时无法自证，
     *    降级为 null，上游退回按 `packageName` 做应用匹配（宁可多列候选，不可漏列）。
     * 3. **拼自证名单并读取**：失败（异常）一律吞掉当 null，绝不让凭据流程崩掉。
     *
     * 返回 null 不表示「来源非法」，只表示「本次拿不到网页来源」。
     */
    fun originOrNull(callingAppInfo: CallingAppInfo?): String? {
        val info = callingAppInfo ?: return null
        val populated = runCatching { info.isOriginPopulated() }.getOrDefault(false)
        if (!populated) return null
        val fingerprint = signingFingerprintOrNull(info) ?: return null
        return readOrigin(info, allowListFor(info.packageName, fingerprint))
    }

    /**
     * 调用方**当前生效**的签名证书 SHA-256 指纹（大写十六进制，冒号分隔）。
     *
     * 对齐 Bitwarden `CallingAppInfo.getSignatureFingerprintAsHexString()`：多签名者无法
     * 唯一确定，返回 null；否则取 `apkContentsSigners` 首项做 SHA-256。
     */
    private fun signingFingerprintOrNull(info: CallingAppInfo): String? = runCatching {
        val signingInfo = info.signingInfo
        if (signingInfo.hasMultipleSigners()) return@runCatching null
        val signers = signingInfo.apkContentsSigners
        if (signers.isEmpty()) return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256").digest(signers.first().toByteArray())
        digest.joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    /**
     * 用**指定特权名单**读取 origin（签名不通过或不在名单内时返回 null）。
     *
     * 供将来接入「用户信任的应用名单」时使用，语义与 Bitwarden
     * `validatePrivilegedApp(relyingPartyId, allowList, isVerifiedSource)` 的读取部分一致。
     * 当前 Vaultix 尚无该名单，保留 API 以免后续重复改造。
     */
    fun trustedOriginOrNull(callingAppInfo: CallingAppInfo?, allowList: String): String? {
        val info = callingAppInfo ?: return null
        if (allowList.isBlank()) return null
        val populated = runCatching { info.isOriginPopulated() }.getOrDefault(false)
        if (!populated) return null
        return readOrigin(info, allowList)
    }

    /** 读取并规整 origin：空串视为无来源，避免下游把 `""` 当成有效域名。 */
    private fun readOrigin(info: CallingAppInfo, allowList: String): String? =
        runCatching { info.getOrigin(allowList) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** 调用方包名（浏览器即为 `com.microsoft.emmx` / `com.android.chrome` 等）。 */
    fun packageNameOrNull(callingAppInfo: CallingAppInfo?): String? =
        callingAppInfo?.packageName?.takeIf { it.isNotBlank() }
}
