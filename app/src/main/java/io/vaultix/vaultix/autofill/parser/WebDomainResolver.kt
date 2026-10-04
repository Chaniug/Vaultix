/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 页面级域名的「收集 + 裁决」，移植自 Bastion autofill_ng 的域名兜底优先级
 * （GPL-3.0，Copyright 2025 JoyinJoester），该优先级本身对齐 Bitwarden。
 */
package io.vaultix.vaultix.autofill.parser

/**
 * 页面级域名裁决：**纯函数**，`ViewNode` 依赖全在 [AssistStructureParser] 那一侧。
 *
 * ## 为什么要独立成类
 *
 * 域名是 Autofill 里最容易出静默错误的一环：拿不到域名**不会报错**，只会让所有候选
 * 静默变成 0 分（用户体感是「有提示、但一条都匹配不出来」）。而 `ViewNode` 是
 * `@SystemApi` 抽象类、构造不出实例，逻辑留在 [AssistStructureParser] 里就没法做
 * JVM 单测 —— 于是只能靠真机复现，而真机复现一次的成本极高（见 [FillTargetResolver]
 * 的同类说明：把逻辑投影成可构造的最小抽象后再测）。
 *
 * ## 🔴 判读这条链时最容易踩的坑：空白串 ≠ null
 *
 * `webDomains` 收的是**整棵树所有节点**的 `ViewNode.webDomain`。GeckoView（Firefox）
 * 在登录页上会把它设成**空字符串**而不是 null（2026-10-04 用户真机日志：登录页那几次
 * `fillRequest` 的 `webDomain=` 后面什么都没有，而纯 `null` 只出现在非登录页）。
 * 而 Kotlin 的 `?:` **只对 null 短路**，空串会原样穿透 —— 于是
 * `webDomain ?: fallbackWebDomain` 在空串时返回 `""`，把兜底结果白白丢掉。
 * [FillTargetResolver.inheritWebDomain] 早就用 `takeIf { it.isNotBlank() }` 防了这一手，
 * 唯独页面级的收集处漏了，两处判据不一致。
 *
 * ⇒ **本类的 [collect] 是收集期的空白防御**（对齐 `inheritWebDomain`），
 * [resolve] 里对兜底候选也再做一次 —— 任何一条入口塞进来的空白串都不会污染结果。
 *
 * ⚠️ 但**别把修好这个空白防御当成"Firefox 修好了"**：兜底（地址栏 / 结构文本）在
 * GeckoView 上实测同样取不到（`fallback=null`），Bitwarden Android 在 Firefox Android
 * 上也一样做不了域名匹配（其 issue #5720 / 社区 #19444）。空白防御是**必要的正确性
 * 修复**，不是 Firefox 的**充分**解法 —— 充分解法见
 * [io.vaultix.vaultix.autofill.engine.AutofillCandidateSource.matchLogins] 的「无域名降级」。
 */
internal object WebDomainResolver {

    /**
     * 收集期：把一个节点的 `webDomain` 收进列表，**空白串视同「没上报」**。
     *
     * 与 [FillTargetResolver.inheritWebDomain] 的判据保持一致（`isNotBlank`）。
     * 不在这里 trim 归一化大小写 —— 大小写归一由 [io.vaultix.vaultix.autofill.match.UriMatcher]
     * 统一做，这里保持原样，避免与既有匹配行为产生意外差异。
     */
    fun collect(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

    /**
     * 裁决最终参与匹配的域名（兜底优先级，对齐 Bitwarden / Bastion）：
     *
     * 1. 结构里**第一个非空白**的 `webDomain`（权威，跨域 iframe 的根节点上才有）；
     * 2. 地址栏文本（浏览器包名 + 资源 id **双重**匹配，比启发式权威）；
     * 3. 结构文本里形似 URL 的串（last-resort，深度受限）。
     *
     * ⚠️ 判据用 `isNullOrBlank()` 而非 `== null`：即便上游漏了防御、即便将来新增了
     * 收集入口，空白串也不会让它赢掉真正的兜底（这就是本类存在的意义）。
     */
    fun resolve(collected: List<String>, urlBarHosts: List<String>, structureTextHost: String?): Resolution {
        val webDomain = collected.firstOrNull { it.isNotBlank() }
        val fallback = if (webDomain.isNullOrBlank()) {
            urlBarHosts.firstOrNull { it.isNotBlank() } ?: structureTextHost?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        return Resolution(
            webDomain = webDomain,
            fallbackWebDomain = fallback,
            effective = webDomain ?: fallback,
        )
    }

    /**
     * @param webDomain 权威域名（结构里第一个非空白值；没有则 null）。
     * @param fallbackWebDomain 兜底域名（地址栏 / 结构文本；**非权威**，只用于匹配）。
     * @param effective 实际参与匹配的域名 —— 就是 [io.vaultix.vaultix.autofill.engine.AutofillCandidateSource]
     *   拿到的那个值。它为 null 意味着「本次请求拿不到任何域名」，此时走无域名降级。
     */
    data class Resolution(
        val webDomain: String?,
        val fallbackWebDomain: String?,
        val effective: String?,
    )
}
