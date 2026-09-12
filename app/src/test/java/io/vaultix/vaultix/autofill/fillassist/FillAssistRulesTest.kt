/*
 * Vaultix — app:autofill · fillassist 单测
 * Copyright (C) 2026 Vaultix contributors
 *
 * 覆盖两块纯逻辑：CSS 选择器子集解析（对齐 Bitwarden `FillAssistManagerImpl`）
 * 与规则表 JSON 解析（manifest / forms）。
 */
package io.vaultix.vaultix.autofill.fillassist

import com.google.common.truth.Truth.assertThat
import io.vaultix.vaultix.autofill.model.FieldHint
import org.junit.Test

class FillAssistRulesTest {

    // ---- 选择器解析 ----

    @Test
    fun `selector parser keeps supported constraints`() {
        val shorthand = FillAssistSelectorParser.parse("input#oid")!!
        assertThat(shorthand.tag).isEqualTo("input")
        assertThat(shorthand.id).isEqualTo("oid")

        val attrs = FillAssistSelectorParser.parse("input[type='password'][name=\"pwd\"]")!!
        assertThat(attrs.tag).isEqualTo("input")
        assertThat(attrs.type).isEqualTo("password")
        assertThat(attrs.name).isEqualTo("pwd")
        assertThat(attrs.id).isNull()
    }

    @Test
    fun `selector parser drops selectors it cannot express safely`() {
        // 纯 class 选择器：没有任何可表达约束
        assertThat(FillAssistSelectorParser.parse(".foo")).isNull()
        // class 限定符 + 无属性约束 → 作废（否则退化成「按 tag 匹配一切」）
        assertThat(FillAssistSelectorParser.parse("input.hidden")).isNull()
        // 只有不支持的属性、没有可用约束 → 作废
        assertThat(FillAssistSelectorParser.parse("input[placeholder='Email']")).isNull()
    }

    @Test
    fun `selector parser keeps attribute constraint even with unsupported ones`() {
        // autocomplete / title 不可表达，但 type 可表达 ⇒ 保留该条（只按 type 约束）
        val clause = FillAssistSelectorParser.parse(
            "input[type='email'][autocomplete='username'][title='Enter your email.']",
        )!!
        assertThat(clause.tag).isEqualTo("input")
        assertThat(clause.type).isEqualTo("email")
    }

    @Test
    fun `selector parser takes last descendant segment and strips shadow boundary`() {
        val descendant = FillAssistSelectorParser.parse("div#container input#field")!!
        assertThat(descendant.tag).isEqualTo("input")
        assertThat(descendant.id).isEqualTo("field")

        val shadow = FillAssistSelectorParser.parse("my-host >>> form input#x")!!
        assertThat(shadow.tag).isEqualTo("input")
        assertThat(shadow.id).isEqualTo("x")
    }

    // ---- 规则表 JSON ----

    @Test
    fun `manifest parses filename and cid`() {
        val entry = FillAssistJsonParser.parseManifest(MANIFEST_JSON)!!
        assertThat(entry.filename).isEqualTo("forms.v1.json")
        assertThat(entry.cid).isEqualTo("sha256:abc")
    }

    @Test
    fun `forms parse into host rules and strip www prefix`() {
        val rules = FillAssistJsonParser.parseForms(FORMS_JSON)!!
        val hostRules = rules.forHost("www.example.com")
        assertThat(hostRules).hasSize(1)
        assertThat(hostRules[0].category).isEqualTo("account-login")
        // email 的那条选择器带不可表达的 autocomplete，但 type 可表达 ⇒ 保留
        assertThat(hostRules[0].fields["email"]).hasSize(1)
        // password 的嵌套数组形态（["a","b"]）同样要展开
        assertThat(hostRules[0].fields["password"]).hasSize(2)
        // 未知主机 → 空表（调用方退回启发式）
        assertThat(rules.forHost("not-covered.example")).isEmpty()
    }

    @Test
    fun `forms with unsupported schema major are rejected wholesale`() {
        assertThat(FillAssistJsonParser.parseForms(FORMS_JSON.replace("1.0.0", "2.0.0"))).isNull()
        assertThat(FillAssistJsonParser.parseForms("{ not json")).isNull()
    }

    @Test
    fun `field key mapping follows upstream`() {
        assertThat(fieldKeyToHint("username")).isEqualTo(FieldHint.USERNAME)
        // 上游把 phone 也归 Login.Username（Username 不限值形态）
        assertThat(fieldKeyToHint("phone")).isEqualTo(FieldHint.USERNAME)
        assertThat(fieldKeyToHint("email")).isEqualTo(FieldHint.EMAIL_ADDRESS)
        assertThat(fieldKeyToHint("password")).isEqualTo(FieldHint.PASSWORD)
        assertThat(fieldKeyToHint("cardNumber")).isEqualTo(FieldHint.CARD_NUMBER)
        assertThat(fieldKeyToHint("whatever")).isNull()
    }

    private companion object {
        const val MANIFEST_JSON = """
        {"buildId":"v1","maps":{"forms":{"v1":{"filename":"forms.v1.json",
        "cid":"sha256:abc","schema":"forms.v1.schema.json"}}}}
        """

        const val FORMS_JSON = """
        {"schemaVersion":"1.0.0","hosts":{"example.com":{"forms":[{
        "category":"account-login",
        "fields":{
          "email":["input[type='email'][autocomplete='username']"],
          "password":["input[type='password'][name='pwd']","form#login input#pass"]
        }}]}}}
        """
    }
}
