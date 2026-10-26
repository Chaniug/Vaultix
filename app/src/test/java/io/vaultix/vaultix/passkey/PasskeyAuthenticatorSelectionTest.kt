/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.passkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PasskeyAuthenticatorSelection] 的回归锁（`.ai/ISSUES.md` #197）。
 *
 * 重点锁住两条**反直觉**的取舍（理由都写在实现里）：
 *  - `preferred` 记 **true**（本实现确实可发现，属满足偏好）；
 *  - **缺省** 记 **true**（有意偏离 WebAuthn L2 的 `discouraged` 默认值，与 Bitwarden 一致）。
 *
 * ⚠️ 另一个容易踩的坑：`org.json` 的 `optString` 对**缺失键**返回字面量 `"null"`，
 * 于是"未写 residentKey"会被误读成"写了 null"。下面有专门用例守这条。
 */
class PasskeyAuthenticatorSelectionTest {

    /** 拼一份创建请求 JSON；[selection] 为 null 时不带 `authenticatorSelection` 块。 */
    /**
     * 拼一份创建请求 JSON；[selection] 为 null 时不带 `authenticatorSelection` 块。
     *
     * ⚠️ 用**具体 JSON 文本**而不是 `JSONObject.put(...)` 构造：后者的写入路径与
     * 文本解析路径在本项目的单测环境里行为不一致（`has()` 判 false），
     * 造出的夹具会"看起来对、读出来空"，把被测逻辑冤枉成 bug —— 这个坑真踩过一次。
     */
    private fun json(selection: String?): String =
        if (selection == null) {
            BASE_JSON
        } else {
            BASE_JSON.dropLast(1) + ",\"authenticatorSelection\":" + selection + "}"
        }

    @Test
    fun residentKey_required_记可发现() {
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json(sel("required"))))
        // 大小写不敏感（部分服务端序列化不规范）。
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json(sel("REQUIRED"))))
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json(sel("Required"))))
    }

    @Test
    fun residentKey_preferred_也记可发现() {
        // `preferred` = "能可发现最好"，而本实现确实可发现 ⇒ 满足偏好。
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json(sel("preferred"))))
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json(sel("Preferred"))))
    }

    @Test
    fun residentKey_discouraged_是唯一记不可发现的取值() {
        assertFalse(PasskeyAuthenticatorSelection.isDiscoverable(json(sel("discouraged"))))
        assertFalse(PasskeyAuthenticatorSelection.isDiscoverable(json(sel("Discouraged"))))
        // 带空白的取值也要认。
        assertFalse(PasskeyAuthenticatorSelection.isDiscoverable(json(sel(" discouraged "))))
    }

    @Test
    fun residentKey_为空串等价于未写() {
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json(sel(""))))
    }

    @Test
    fun residentKey_显式为null等价于未写() {
        // org.json 的 optString 对显式 null 也返回 "null" ⇒ 必须落到"未写"分支。
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json("""{"residentKey":null}""")))
    }

    @Test
    fun 只给旧字段requireResidentKey时按它判断() {
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json("""{"requireResidentKey":true}""")))
        assertFalse(PasskeyAuthenticatorSelection.isDiscoverable(json("""{"requireResidentKey":false}""")))
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json("""{"requireResidentKey":null}""")))
    }

    @Test
    fun residentKey优先于旧字段requireResidentKey() {
        // 旧字段说 true，新字段说 discouraged —— 新字段是规范推荐写法，优先。
        assertFalse(
            PasskeyAuthenticatorSelection.isDiscoverable(
                json("""{"residentKey":"discouraged","requireResidentKey":true}"""),
            ),
        )
        assertTrue(
            PasskeyAuthenticatorSelection.isDiscoverable(
                json("""{"residentKey":"required","requireResidentKey":false}"""),
            ),
        )
    }

    @Test
    fun 整个authenticatorSelection缺省时记可发现() {
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json(null)))
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(json("{}")))
        // 只带其它字段（UV / attachment）时不受影响。
        assertTrue(
            PasskeyAuthenticatorSelection.isDiscoverable(
                json("""{"userVerification":"required","authenticatorAttachment":"platform"}"""),
            ),
        )
    }

    @Test
    fun 畸形JSON回落true而不是崩() {
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable(""))
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable("not json"))
        assertTrue(PasskeyAuthenticatorSelection.isDiscoverable("""{"authenticatorSelection":"oops"}"""))
    }

    private companion object {
        /** 最小可解析的创建请求骨架。 */
        const val BASE_JSON = """{"rpId":"example.com","challenge":"AAAA"}"""

        fun sel(residentKey: String): String = """{"residentKey":"$residentKey"}"""
    }
}
