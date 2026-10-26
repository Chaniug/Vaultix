/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.passkey

import org.json.JSONObject

/**
 * 读 `PublicKeyCredentialCreationOptions.authenticatorSelection`（`.ai/ISSUES.md` #197，2026-10-26）。
 *
 * ## 为什么值得读
 *
 * 这个块里只有一项对**存储**有可观察影响：`residentKey` / `requireResidentKey`
 * 决定这把凭证是不是 **可发现凭证（resident key / discoverable credential）** ——
 * 也就是"能不能不先输用户名、直接用 `allowCredentials` 为空的条件发现流程登录"。
 *
 * 之前的实现把 `discoverable` 硬编码成 `true`。后果不是崩溃，而是**语义造假**：
 * 库里所有通行密钥都自称 resident，于是：
 *  - 界面上的"可发现凭证"筛选与统计全错；
 *  - 将来做"仅展示可发现凭证"的条件发现流程时，会把只注册给某站点、
 *    声明了 `residentKey: "discouraged"` 的凭证也列出来；
 *  - 与 Bitwarden 服务端对账时字段不一致（那边写的是 RP 实际要的值）。
 *
 * ## 规范口径（WebAuthn L2 §5.4.4 / §5.4.6）
 *
 * | 请求里写的 | 语义 |
 * |---|---|
 * | `residentKey = "required"` | 必须可发现 |
 * | `residentKey = "preferred"` | 希望可发现；authenticator 自选 |
 * | `residentKey = "discouraged"` | 不要求，优先不可发现 |
 * | 未写 `residentKey` 但 `requireResidentKey = true` | 旧字段，等价 `"required"` |
 * | 两者都缺 | 默认 `"discouraged"`（L2 已把默认值从 true 改成 discouraged） |
 *
 * ## Vaultix 的选择：只有 `discouraged` 记 false
 *
 * 本应用**始终**把私钥写进库、**始终**能按 credentialId 直接取到（服务端/本地都能枚举），
 * 所以从"技术上能不能被发现"看，两者都成立。真正该记的是 **RP 的意图**：
 *  - `required` / `preferred` / 缺省 → 记 `true`；
 *  - `discouraged`（RP 明确说了"别当可发现凭证"）→ 记 `false`，尊重它。
 *
 * 为什么 `preferred` 记 true：它意味着"能可发现最好"，而本实现确实能，属于满足偏好。
 * 为什么缺省记 true 而不是按 L2 默认的 discouraged：缺省时 RP 没表态，
 * 而 Vaultix 的物理形态（密钥在库里、用户点一条就填）本来就是可发现的；
 * 记 false 反而会让条件发现流程在常见站点上失效。**这是一处有意偏离，
 * 与 Bitwarden Android 一致**（它同样不因缺省而记 false）。
 */
internal object PasskeyAuthenticatorSelection {

    /**
     * 从创建请求 JSON 推断这把凭证是否应记为可发现凭证。
     *
     * **任何解析异常都回落 `true`**：这是本对象的既有默认，且解析失败不该阻断注册 ——
     * 记错一个布尔值远好过让用户注册不了通行密钥。反过来（失败记 false）会让
     * 一批本该能用的凭证在条件发现流程里消失，更难排查。
     */
    fun isDiscoverable(requestJson: String): Boolean {
        val request = runCatching { JSONObject(requestJson) }.getOrNull() ?: return true
        // `optJSONObject` 在"块不在"或"块是 null"时都返回 null ⇒ 直接走缺省 true。
        // ⚠️ 取到之后**必须在子对象上读字段**，不要再重解析一遍 `toString()`：
        // 早期实现担心 Android 与 `org.json:json` 行为差异，绕了一层"序列化再解析"，
        // 结果把 `JSONObject.NULL`（显式 null）也变成了合法字符串 "null"，
        // 反而多出一条 case。两端行为在这几个读取方法上是一致的。
        val selection = request.optJSONObject(KEY_SELECTION) ?: return true

        // `residentKey` 优先；规范允许服务端只发旧的 `requireResidentKey`。
        // ⚠️ `optString` 对**缺失键**与**显式 null** 都返回字面量 `"null"`
        // （org.json 的实现细节），所以必须用 `isNull` 判断，不能只判空串。
        val residentKey = if (selection.isNull("residentKey")) {
            ""
        } else {
            selection.optString("residentKey").trim().lowercase()
        }
        if (residentKey.isNotEmpty()) {
            return residentKey != RESIDENT_KEY_DISCOURAGED
        }
        // 缺省 ⇒ true（有意偏离 L2 的 discouraged 默认，理由见类 KDoc）。
        // ⚠️ 显式 `null` 也走这里：`optBoolean` 对 `JSONObject.NULL` 返回 false，
        // 但"null"的语义是**没表态**，不是"明确说不要" —— 若直接返回 false，
        // 会把缺省口径从 true 悄悄改成 false。用 `isNull` 先分流。
        if (selection.isNull("requireResidentKey")) return true
        return !selection.has("requireResidentKey") || selection.optBoolean("requireResidentKey")
    }

    /** `residentKey: "discouraged"` —— 唯一会记 `false` 的取值。 */
    private const val RESIDENT_KEY_DISCOURAGED = "discouraged"

    /** 创建请求里承载 `authenticatorSelection` 的键名。 */
    private const val KEY_SELECTION = "authenticatorSelection"
}
