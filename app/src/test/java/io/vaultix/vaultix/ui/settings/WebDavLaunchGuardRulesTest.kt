/*
 * Vaultix — app:ui:settings（源码文本门禁）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * #289：编译过、detekt 过、真机才看得见的那类契约 —— 「网盘链路的每个
 * `viewModelScope.launch` 都必须有异常边界」。
 *
 * 工程里没有全局 `CoroutineExceptionHandler`，漏一处就是一个**闪退**。
 * 逐个 ViewModel 打补丁必然漂移（设置页与添加页是同一条流程的两段），
 * 所以用源码文本把「形状」钉死：裸 launch 只允许出现在 `launchGuarded` 定义里。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavLaunchGuardRulesTest {

    private val root: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(relative: String): String = File(root, relative).readText()

    /** 去掉 KDoc / 行注释 —— 注释里大量出现 `viewModelScope.launch` 的字样，会带偏计数。 */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
            }
            .joinToString("\n")

    @Test
    fun `两个ViewModel都只剩launchGuarded定义里的一处裸launch`() {
        listOf(ACCOUNTS_VM, ADD_VM).forEach { path ->
            val code = codeOnly(source(path))
            val count = Regex("""viewModelScope\.launch""").findAll(code).count()
            assertEquals(
                "$path 只允许 launchGuarded 定义里的一处裸 launch，实际 $count 处",
                1,
                count,
            )
        }
    }

    @Test
    fun `所有动作都走launchGuarded且没有残留的return@launch`() {
        val accounts = codeOnly(source(ACCOUNTS_VM))
        val add = codeOnly(source(ADD_VM))
        assertTrue(
            "设置页 5 个动作（refresh / probe / signIn / health / removal）必须全部收口",
            Regex("""launchGuarded\(""").findAll(accounts).count() >= 5,
        )
        assertTrue(
            "添加页 5 个动作（refresh / pick / open / goUp / submit）必须全部收口",
            Regex("""launchGuarded\(""").findAll(add).count() >= 5,
        )
        // ⚠️ \b 保证 return@launchGuarded 不被误判（h→G 都是单词字符，不构成边界）。
        assertFalse(
            "不得残留 return@launch —— 换了宿主后标签必须一起改成 return@launchGuarded",
            Regex("""return@launch\b""").containsMatchIn(add),
        )
    }

    @Test
    fun `launchGuarded必须重抛取消写日志并把UI拉回终态`() {
        listOf(ACCOUNTS_VM, ADD_VM).forEach { path ->
            val code = codeOnly(source(path))
            val start = code.indexOf("private fun launchGuarded")
            assertTrue("$path 必须有 launchGuarded 定义", start >= 0)
            val body = code.substring(start, (start + GUARD_BODY_CHARS).coerceAtMost(code.length))
            assertTrue(
                "$path 必须用 runCatching 建立异常边界（detekt 不容宽泛 catch）",
                body.contains("runCatching { block() }"),
            )
            assertTrue(
                "$path 必须原样重抛取消与 Error（页面返回不是失败）",
                body.contains("error is CancellationException || error is Error"),
            )
            assertTrue(
                "$path 必须写 ERROR 日志（真机 logcat 才能取证）",
                body.contains("VaultixLog.e(TAG, error)"),
            )
            assertTrue(
                "$path 必须把 busy 拉回 false，否则界面永远转圈",
                body.contains("busy = false"),
            )
        }
    }

    @Test
    fun `重IO必须显式下IO且回滚异常不得盖掉原始原因`() {
        val accounts = codeOnly(source(ACCOUNTS_VM))
        val add = codeOnly(source(ADD_VM))
        assertEquals(
            "设置页凭据写入/自检 + 清单读取两处 IO 都要离开主线程",
            2,
            Regex("""withContext\(Dispatchers\.IO\)""").findAll(accounts).count(),
        )
        assertTrue("添加页清单读取必须下 IO", add.contains("withContext(Dispatchers.IO)"))
        assertTrue(
            "自检失败回滚凭据也必须兜住（Keystore 故障会二次抛）",
            accounts.contains("runCatching { connector.removeWebDavCredential(credentialId) }"),
        )
        assertTrue(
            "自检的 runCatching 不得再吞掉取消（否则误删刚写好的凭据）",
            accounts.contains("if (error is CancellationException || error is Error) throw error"),
        )
    }

    private companion object {
        const val ACCOUNTS_VM =
            "app/src/main/java/io/vaultix/vaultix/ui/settings/CloudAccountsViewModel.kt"
        const val ADD_VM =
            "app/src/main/java/io/vaultix/vaultix/ui/addvault/AddCloudVaultViewModel.kt"
        const val GUARD_BODY_CHARS = 1400
    }
}
