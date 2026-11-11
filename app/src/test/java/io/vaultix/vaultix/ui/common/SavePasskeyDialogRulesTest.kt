/*
 * Vaultix — app:test · common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通行密钥「写入侧必须产出完整凭证」的**三条源码门禁**（2026-11-12 #284）。
 *
 * 起因：用户问「KDBX 库能不能通过 Vaultix 存入通行密钥」。排查结论是**能**
 * （`KdbxPasskeyCodec` + `KdbxItemWriter.applyPasskeys` + `ItemRepositoryImpl` 分流），
 * 但发现一处静默丢数据：管理页 `SavePasskeyDialog` 手工录入构造的
 * `VaultFido2Credential` 缺 `userHandle`（模型默认 `null`）且允许 `userName` 留空，
 * 而 `KdbxPasskeyCodec.toCredential` 把这两项列入 5 个必填字段、缺一即返回 `null`
 * ⇒ 写进 KDBX 后读不回来（内存会话里当下还看得到，重开库就消失）。
 *
 * ⚠️ 为什么用源码扫描：`VaultFido2Credential` 是纯 data class，漏字段**编译通过、
 *    detekt 查不到、JVM 单测也测不到 UI 构造路径**；而 KDBX 读侧的判据在另一个模块。
 *    配套 `KdbxPasskeyWritebackTest` 的两条用例把「读侧为什么要这些字段」钉死，
 *    本文件负责钉住「写入侧确实给了」—— 两头合起来才是完整闭环。
 */
class SavePasskeyDialogRulesTest {

    private val repoRoot: File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private fun read(relative: String): String =
        File(repoRoot, "java/io/vaultix/vaultix/$relative").readText()

    // ------------------------------------------------------------------ 规则 1

    /**
     * **手工录入构造的凭证必须带齐 KDBX 读侧必填字段。**
     *
     * `KdbxPasskeyCodec.toCredential` 的必填五项：`rpId` / `credentialId` /
     * `privateKey(keyValue)` / `userName` / `userHandle`。前三项本就校验过，
     * 后两项是 #284 补的；`userHandle` 还必须带 `WebAuthn.randomUserHandle()` 兜底
     * （表单选填，不留空生成就会重演「存了读不回来」）。
     */
    @Test
    fun `手工录入凭证必须带齐KDBX读侧必填字段`() {
        val source = stripComments(read("ui/common/SavePasskeyDialog.kt"))
        val block = credentialBlock(source)
        val missing = buildList {
            listOf(
                "credentialId =",
                "rpId =",
                "userName =",
                "userHandle =",
                "keyValue =",
            ).filterNot { block.contains(it) }.forEach { add("构造块缺字段 $it") }
            if (!block.contains("WebAuthn.randomUserHandle()")) {
                add("userHandle 没有留空兜底（会写进 KDBX 后读不回来）")
            }
            if (!block.contains("keyAlgorithm = PasskeyKeyAlgorithm.DEFAULT")) {
                add("缺 keyAlgorithm：手工录入应与系统注册流程同形")
            }
            if (!block.contains("creationDate = Instant.now().toString()")) {
                add("缺 creationDate：详情页会显示空创建时间")
            }
        }
        assertTrue(
            "SavePasskeyDialog 构造的凭证不完整：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 规则 2

    /**
     * **`userName` 必须有空白校验。**
     *
     * 光在构造里写 `userName = draft.userName.trim()` 不够：空串照样写进 KDBX，
     * `toCredential` 一样返回 `null`。必须在保存前拦下，别让用户看到
     * 「保存成功」却什么都没留下。
     */
    @Test
    fun `用户名必须有空白校验`() {
        val source = stripComments(read("ui/common/SavePasskeyDialog.kt"))
        assertTrue(
            "手工录入必须先校验 userName 非空（KDBX 读侧必填）：" +
                "缺 draft.userName.isBlank() 或 passkey_error_user_name_required",
            source.contains("draft.userName.isBlank()") &&
                source.contains("R.string.passkey_error_user_name_required"),
        )
    }

    // ------------------------------------------------------------------ 规则 3

    /**
     * **系统注册流程的 userHandle 必须有兜底。**
     *
     * `PasskeyCreateActivity` 从 RP 的 `requestJson.user.id` 取 userHandle，
     * WebAuthn 规范里它必填，但取不到时不能写 `null` 进 KDBX —— 与规则 1
     * 同一条读侧契约。两条「创建凭证」路径必须共用同一个兜底函数。
     */
    @Test
    fun `系统注册流程的userHandle必须有兜底`() {
        val source = stripComments(read("passkey/PasskeyCreateActivity.kt"))
        assertTrue(
            "PasskeyCreateActivity 的 userHandle 缺 WebAuthn.randomUserHandle() 兜底，" +
                "RP 未给 user.id 时凭证会在 KDBX 里静默消失",
            source.contains("userId ?: WebAuthn.randomUserHandle()"),
        )
    }

    // ----------------------------------------------------------------- helpers

    /** 取 `VaultFido2Credential(...)` 构造块（括号配平），不含参数类型声明。 */
    private fun credentialBlock(text: String): String {
        val start = text.indexOf("VaultFido2Credential(")
        if (start < 0) return ""
        var depth = 0
        var i = start + "VaultFido2Credential".length
        while (i < text.length) {
            if (text[i] == '(') depth++
            if (text[i] == ')') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
            i++
        }
        return text.substring(start)
    }

    /** 去掉行注释与块注释、保留换行（与 `DialogStyleRulesTest` 同一套写法）。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            i = when {
                text[i] == '"' -> copyStringLiteral(text, i, out)
                text.startsWith("//", i) -> skipLineComment(text, i, out)
                text.startsWith("/*", i) -> skipBlockComment(text, i, out)
                else -> {
                    out.append(text[i])
                    i + 1
                }
            }
        }
        return out.toString()
    }

    private fun copyStringLiteral(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(text[i])
        i++
        while (i < text.length) {
            val c = text[i]
            out.append(c)
            i++
            if (c == '\\' && i < text.length) {
                out.append(text[i])
                i++
            } else if (c == '"') {
                return i
            }
        }
        return i
    }

    private fun skipLineComment(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        while (i < text.length && text[i] != '\n') i++
        out.append('\n')
        return i
    }

    private fun skipBlockComment(text: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) {
            if (text[i] == '\n') out.append('\n')
            i++
        }
        return i + 2
    }
}
