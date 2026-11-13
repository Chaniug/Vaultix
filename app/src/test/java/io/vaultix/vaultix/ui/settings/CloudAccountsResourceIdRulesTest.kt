/*
 * Vaultix — app:test · settings（源码文本门禁）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * #290：真机两次闪退（#289「连上就退」、#290「点注销就退」）同源 ——
 * `const val SETTINGS_CLOUD_* = R.string.*` 被 Kotlin 编译期内联成 **0**：
 * AGP 编译期 R 类里资源 id 是 `0x0` 占位，`const val` 要编译期常量就把 0
 * 写死；`notice = 0` → Compose **重组期** `stringResource(0)` 抛
 * `Resources$NotFoundException` 杀进程。`launchGuarded` 只兜协程异常，兜不住重组。
 *
 * 这类问题编译过、detekt 过、JVM 单测也过，只有真机日志或字节码看得见，
 * 所以把「形状」用源码文本钉死：
 *   ① 全 main 源码禁止 `const val ... = R.*`（资源 id 只能运行时读）；
 *   ② 这 11 条必须是普通 `val` 且都来自 `R.string.*`；
 *   ③ 两处变量式解析点必须过 `safeStringRes`，守卫认 `Resources.ID_NULL`；
 *   ④ 无效 id 必须被消费掉，且要有可见降级。
 *
 * 反证：把任一 `val` 改回 `const val`，或撤掉 `safeStringRes`，本文件立刻红。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAccountsResourceIdRulesTest {

    private val root: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(relative: String): String = File(root, relative).readText()

    /** 注释里正解释被禁的写法，计数前必须剥掉 KDoc / 行注释。 */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
            }
            .joinToString("\n")

    /** 只扫各模块 `src/main` 下的 Kotlin；跳过 build / .gradle / .git，避免遍历生成物。 */
    private fun mainKotlinSources(): Sequence<File> =
        root.walkTopDown()
            .onEnter { dir ->
                dir == root || (dir.name != "build" && dir.name != ".gradle" && dir.name != ".git")
            }
            .filter { file ->
                file.isFile && file.extension == "kt" &&
                    "/src/main/" in file.invariantSeparatorsPath
            }

    @Test
    fun `全main源码禁止把资源id写进const_val`() {
        // \s* 允许跨行（常量名与等号之后换行再写 R 的 string 字段，同样算）。
        val banned = Regex("""const\s+val\s+\w+\s*=\s*R\.""")
        val offenders = mainKotlinSources().mapNotNull { file ->
            val hits = banned.findAll(codeOnly(file.readText()))
                .map { it.value.replace(Regex("""\s+"""), " ").trim() }
                .toList()
            if (hits.isEmpty()) null else "${file.invariantSeparatorsPath}: ${hits.joinToString()}"
        }.toList()
        assertTrue(
            "资源 id 在编译期 R 类里是 0 占位，`const val` 会把 0 内联死，" +
                "导致重组期 stringResource(0) 杀进程。违规：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `11条提示资源id必须是运行时val且都来自R_string`() {
        val code = codeOnly(source(ACCOUNTS_VM))
        val names = Regex("""\bval\s+(SETTINGS_CLOUD_\w+)\s*=""")
            .findAll(code).map { it.groupValues[1] }.toList()
        assertEquals("资源 id 常量必须仍是集中定义的 11 条", 11, names.size)
        assertFalse(
            "只要有一条回到 `const val`，真机就重新拿到 0（#290）",
            Regex("""const\s+val\s+SETTINGS_CLOUD_""").containsMatchIn(code),
        )
        assertEquals(
            "每条都必须来自 R.string（不是字面量、不是别的 R 字段）",
            11,
            Regex("""val\s+SETTINGS_CLOUD_\w+\s*=\s*R\.string\.""").findAll(code).count(),
        )
        assertTrue(
            "TAG 是普通字符串常量，保持 const val 无害",
            code.contains("const val TAG = \"CloudAccounts\""),
        )
    }

    @Test
    fun `两处变量式stringResource必须先过safeStringRes`() {
        val screen = codeOnly(source(ACCOUNTS_SCREEN))
        assertEquals(
            "safeStringRes 必须定义在 Screen，且 NoticeSnackbar / CloudError.Res 两处都用它",
            3,
            Regex("""safeStringRes\(""").findAll(screen).count(),
        )
        assertTrue(
            "守卫必须认 Resources.ID_NULL（=0），这是 #290 崩溃值",
            screen.contains("id?.takeIf { it != Resources.ID_NULL }"),
        )
        assertFalse(
            "notice 不得直接交给 stringResource —— 重组期会崩且协程兜不住",
            Regex("""stringResource\(noticeId\)""").containsMatchIn(screen),
        )
        assertFalse(
            "CloudError.Res 的 id 不得直接交给 stringResource",
            Regex("""stringResource\(error\.id\)""").containsMatchIn(screen),
        )
        assertTrue(
            "无效 id 必须消费掉，否则永远挂在 UiState 上",
            screen.contains("noticeId != null -> onConsumed()"),
        )
        assertTrue(
            "无效 id 要有可见降级，不能静默吞掉",
            screen.contains("R.string.error_unknown_no_detail"),
        )
    }

    @Test
    fun `被下发成notice的文案必须仍在strings_xml里`() {
        val strings = source(STRINGS_XML)
        listOf(
            "cloud_msg_fields_missing",
            "cloud_msg_invalid_server",
            "cloud_msg_credentials_in_url",
            "cloud_msg_probe_ok",
            "cloud_msg_probe_failed",
            "cloud_msg_onedrive_signed_in",
            "cloud_msg_onedrive_failed",
            "cloud_msg_onedrive_temp_unavailable",
            "cloud_msg_activity_missing",
            "cloud_msg_removed_webdav",
            "cloud_msg_removed_onedrive",
        ).forEach { name ->
            assertTrue(
                "$name 被 ViewModel 当资源 id 下发，strings.xml 里必须存在",
                strings.contains("<string name=\"$name\">"),
            )
        }
    }

    private companion object {
        const val ACCOUNTS_VM =
            "app/src/main/java/io/vaultix/vaultix/ui/settings/CloudAccountsViewModel.kt"
        const val ACCOUNTS_SCREEN =
            "app/src/main/java/io/vaultix/vaultix/ui/settings/CloudAccountsScreen.kt"
        const val STRINGS_XML = "app/src/main/res/values/strings.xml"
    }
}
