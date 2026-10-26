/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.passkey

import io.vaultix.vaultix.autofill.match.PublicSuffixList

/**
 * 通行密钥注册/断言时的 **RP 归属校验**（`.ai/ISSUES.md` #196，2026-10-26）。
 *
 * ## 为什么需要它
 *
 * WebAuthn 里 `rpId` 是**作用域**：一把通行密钥只能用于它绑定的那个 rpId。
 * 创建流程原先**完全没有校验**请求里的 `origin` 与 `rpId` 是否从属 ——
 * 也就是说，一个恶意 App（或任何能构造 `CreatePublicKeyCredentialRequest` 的调用方）
 * 可以让本应用给 `rpId = bank.example` 生成一把密钥，并把 `origin` 写成任意值。
 * 更糟的是创建侧的兜底写着 `?: "https://$rpId"` —— **凭空构造**一个 origin。
 *
 * ## 规则（与 RP 侧保持一致即可）
 *
 * `origin` 的主机必须**等于** `rpId`，或者 `rpId` 是它的**可注册后缀**
 * （`login.example.com` 属于 rpId `example.com`，但 `evil-example.com` 不属于）。
 *
 * ⚠️ **不能用 `UriMatcher.sameSite`**：它的语义是"同一站点"（可注册域相等），
 * 于是 `a.example.com` 与 `b.example.com` 会互相通过 —— 而在 WebAuthn 里
 * `rpId = a.example.com` 的密钥 **不能**用于 `b.example.com`。
 * "同一站点"是浏览器 Cookie 口径，"rpId 从属"才是 WebAuthn 口径，两者不能混用。
 *
 * ⚠️ 同一登记后缀下的子域归属只能由 RP 自己决定（能不能用 `example.com` 作 rpId，
 * 取决于 RP 有没有在它的 `/.well-known/webauthn` 里声明）。这里做的是**语法层**校验：
 * 挡住"完全不相干"的情况，不做策略层背书 —— 后者只能靠 RP 的 `origin` 校验兜底。
 */
internal object PasskeyOriginPolicy {

    /** RP 归属校验的结论。 */
    sealed interface Verdict {
        /** 通过。 */
        data object Ok : Verdict

        /**
         * 不通过。[reason] 只用于日志（**不要**拼进用户可见文案：
         * 它包含域名，会出现在截图与日志里）。
         */
        data class Rejected(val reason: String) : Verdict
    }

    /**
     * 校验 [origin]（形如 `https://example.com` 或 `https://example.com:8443`）
     * 是否可以声明 [rpId]。
     *
     * 空值一律**拒绝**：调用方必须在调这里之前把 origin 推导出来。
     * "不确定就放行"在作用域校验上是不成立的 —— 放行等于没有校验。
     */
    fun check(origin: String?, rpId: String?): Verdict {
        val host = hostOfOrigin(origin)
            ?: return Verdict.Rejected("origin 无法解析出主机（origin=${origin.orEmpty()}）")
        val rp = normalizeRpId(rpId.orEmpty())
        if (rp.isEmpty()) return Verdict.Rejected("rpId 为空")
        if (host == rp) return Verdict.Ok
        // rpId 必须是 host 的可注册后缀：`login.example.com` 以 `.example.com` 结尾。
        // ⚠️ 必须带点号比较，否则 `evilexample.com`.endsWith("example.com") 会误判通过。
        if (!host.endsWith(".$rp")) {
            return Verdict.Rejected("origin 主机 $host 不属于 rpId=$rp")
        }
        // 第二层：rpId 不能宽于可注册域。
        // 例：host = `a.b.co.uk`、rpId = `co.uk`（公共后缀本身）——"以 .co.uk 结尾"成立，
        // 但 `co.uk` 不属于任何单方，任何站点都能自称 `co.uk`。
        // 判据：rp 必须是 baseDomain(host) 本身，或它的子域（`login.example.com` 对
        // base = `example.com` 成立，因为 rp 以 `.example.com` 结尾或等于它）。
        val base = PublicSuffixList.baseDomain(host)
        val rpWithinBase = rp == base || rp.endsWith(".$base")
        if (!rpWithinBase) {
            return Verdict.Rejected("rpId=$rp 宽于 origin 的可注册域 $base")
        }
        return Verdict.Ok
    }

    /** 从 origin 串取主机（小写、去端口；无 scheme 时按 https 补）。 */
    fun hostOfOrigin(origin: String?): String? {
        val raw = origin?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        return runCatching { java.net.URI(withScheme).host?.lowercase()?.ifEmpty { null } }
            .getOrNull()
    }
}
