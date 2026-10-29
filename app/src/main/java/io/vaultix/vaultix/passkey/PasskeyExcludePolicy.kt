/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.passkey

import io.vaultix.model.VaultFido2Credential
import org.json.JSONObject

/**
 * 注册请求里的 `excludeCredentials`（`.ai/ISSUES.md` #208，2026-10-29）。
 *
 * ## 规范口径（WebAuthn L2 §5.4.4 / §6.3）
 *
 * RP 在**注册**时可以用 `excludeCredentials` 列出「这个账号已经有过哪些凭证」。
 * 认证器若发现自己**已经持有其中任何一个**，就**不得**再创建一把新的 ——
 * 应当中止并返回 `InvalidStateError`。
 *
 * 目的不是省事，是**防止同一账号在同一台设备上被注册出一把以上的密钥**：
 * 每多一把，用户下次登录就多一个分不出来的候选，RP 侧也会多一份需要撤销的材料。
 *
 * ## 之前的行为：完全不读
 *
 * 全仓 `grep excludeCredentials` 零命中（2026-10-26 审计时已记下）。后果是：
 * - 用户在同一个站点点两次「创建通行密钥」⇒ 库里多出两把、RP 侧两把，
 *   登录时列出两个一模一样的候选，**用户分不出该选哪个**；
 * - 「换手机 / 重装后重新注册」这种常见路径也不会得到 RP 期望的"已存在"提示。
 *
 * 它不像 Origin 校验那样是安全缺口（不读不会让密钥跑到别的站点），
 * 但它是**规范里写死的语义**，且不读的后果直接落在用户体感上。
 *
 * ## 保守边界
 *
 * - **解析不出来 = 没有排除项**（不阻断注册）：`excludeCredentials` 是**增益**语义，
 *   一条解析失败就让整个注册失败，代价与收益不成比例；
 * - **只在同一 rpId 下比**：`excludeCredentials` 的作用域就是本次请求的 rpId，
 *   别处的同名凭证与本次注册无关；
 * - **只按 credentialId 比**：规范里 `id` 就是凭证的唯一标识，不比对 transports /
 *   type（那些是"筛选提示"，不是身份）。
 */
internal object PasskeyExcludePolicy {

    /**
     * 解析出请求里声明的「已存在凭证 id」集合（base64url 文本，与库里存的同形）。
     *
     * @return 解析失败 / 字段缺失 / 全为空 ⇒ 空集合（**不阻断注册**，理由见类 KDoc）。
     */
    fun excludedIds(requestJson: String?): Set<String> {
        if (requestJson.isNullOrBlank()) return emptySet()
        val array = runCatching {
            JSONObject(requestJson).optJSONArray(KEY_EXCLUDE)
        }.getOrNull() ?: return emptySet()
        val ids = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val id = array.optJSONObject(index)?.optString("id")?.trim().orEmpty()
            if (id.isNotEmpty()) ids += id
        }
        return ids
    }

    /**
     * 在库里已有的凭证中找出**第一个**被排除项命中的。
     *
     * @param excluded 请求声明的已存在凭证 id（见 [excludedIds]）。
     * @param existing 候选凭证（**调用方负责限定在本次写入的库**）。
     * @param rpId 本次请求的 rpId；只有同 rpId 的凭证参与比对。
     * @return 命中则返回该凭证（供日志 / 文案判断是否已存在），未命中返回 `null`。
     */
    fun firstConflict(
        excluded: Set<String>,
        existing: List<VaultFido2Credential>,
        rpId: String,
    ): VaultFido2Credential? {
        if (excluded.isEmpty() || rpId.isBlank()) return null
        return existing.firstOrNull { cred ->
            cred.rpId == rpId && cred.credentialId.trim() in excluded
        }
    }

    /** 创建请求里承载排除列表的键名。 */
    private const val KEY_EXCLUDE = "excludeCredentials"
}
