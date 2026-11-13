/*
 * Vaultix — data:repository（单测 / 源码文本门禁）
 * Copyright (C) 2026 Vaultix contributors
 *
 * ---------------------------------------------------------------------------
 * #288（2026-11-13）：把「编译过、detekt 查不出、真机才看得出来」的三处契约钉住：
 *
 * 1. 两个 WebDAV **生产构造点**都必须过 `webDavProductionClient()` ——
 *    漏一个就变成「配置页能连上、真正读库时被 Bearer 顶掉」的分裂现象；
 * 2. 自检必须 `PROPFIND Depth: 0` 优先，HEAD 只能作为 405/501 的降级；
 * 3. 403 的文案必须**否定**「账号或密码不正确」并给出可依次排除的原因。
 *
 * 规则一律精确点名，不写粗放的「不许出现某个词」（见 part23 踩坑 6）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebDavConnectionRulesTest {

    private val root: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(relative: String): String = codeOnly(File(root, relative).readText())

    private fun webDavSource(): String = source(
        "data/repository/src/main/java/io/vaultix/data/repository/kdbx/WebDavKdbxFileSource.kt",
    )

    private fun connector(): String = source(
        "app/src/main/java/io/vaultix/vaultix/remote/CloudAccountConnector.kt",
    )

    private fun coordinator(): String = source(
        "data/repository/src/main/java/io/vaultix/data/repository/kdbx/KdbxCloudSyncCoordinator.kt",
    )

    private fun accountsScreen(): String = source(
        "app/src/main/java/io/vaultix/vaultix/ui/settings/CloudAccountsScreen.kt",
    )

    @Test
    fun `两个生产构造点都必须派生WebDAV专用client`() {
        assertTrue(
            "设置页「测试连接」的构造点不能用全局 OkHttpClient（会被 Bearer 覆盖 Basic）",
            connector().contains("client = webDavProductionClient(okHttp)"),
        )
        assertTrue(
            "同步 / 解锁链路的构造点同样必须隔离，否则两处现象会分裂",
            coordinator().contains("client = webDavProductionClient(client)"),
        )
        assertTrue(
            "派生函数必须存在",
            webDavSource().contains("fun webDavProductionClient(shared: OkHttpClient)"),
        )
    }

    @Test
    fun `专用client必须清空拦截器与Authenticator`() {
        val body = functionBody(webDavSource(), "webDavProductionClient")
        assertTrue("必须清 application 拦截器（BitwardenAuthInterceptor 在这里）", body.contains("interceptors().clear()"))
        assertTrue("必须清 network 拦截器", body.contains("networkInterceptors().clear()"))
        assertTrue(
            "newBuilder() 会复制 BitwardenAuthenticator，必须显式覆盖成 no-op",
            body.contains("authenticator(Authenticator { _, _ -> null })"),
        )
        assertTrue("必须用 newBuilder() 共享连接池，不许另起一套", body.contains("shared.newBuilder()"))
    }

    @Test
    fun `自检必须PROPFIND优先且HEAD只作降级`() {
        val src = webDavSource()
        val body = functionBody(src, "testConnection")
        assertTrue("自检主探必须是 PROPFIND", body.contains("\"PROPFIND\""))
        assertTrue("必须带 Depth: 0（只探目录自身，不拉整棵子树）", body.contains("\"Depth\", \"0\""))
        assertTrue("405 / 501 才允许降级", body.contains("HTTP_METHOD_NOT_ALLOWED") && body.contains("HTTP_NOT_IMPLEMENTED"))
        assertTrue("降级必须复用同一套判据", body.contains("probeViaHead()"))
        assertFalse(
            "testConnection 里不许再直接发 HEAD（HEAD 只应出现在 probeViaHead）",
            body.contains(".head().build()"),
        )
    }

    @Test
    fun `403文案必须否定密码错并给出可操作原因`() {
        val src = webDavSource()
        // ⚠️ forbiddenMessage 是纯字符串拼接的表达式体（无花括号），
        //    functionBody() 会一路找到下一个函数的 `{` ⇒ 用 declarationText 截到下一个声明。
        val body = declarationText(src, "forbiddenMessage")
        assertFalse(
            "403 认证已通过，再说「账号或密码不正确」会让用户无限重输（#288 真机现场）",
            body.contains("账号或密码不正确"),
        )
        assertTrue("必须明确否定密码错", body.contains("不是密码错"))
        listOf("二次验证", "应用密码", "频率", "目录").forEach { reason ->
            assertTrue("403 文案缺可操作原因「$reason」", body.contains(reason))
        }
        assertTrue(
            "分流必须覆盖 errorMessage（stat/read/listChildren/强写都走它）",
            src.contains("if (code == HTTP_FORBIDDEN) return forbiddenMessage()"),
        )
    }

    @Test
    fun `表单必须在填密码前提示应用密码与二次验证`() {
        val screen = accountsScreen()
        assertTrue(
            "界面必须引用应用密码提示串，否则用户只能靠失败文案补救（晚一轮）",
            screen.contains("R.string.cloud_webdav_password_hint"),
        )
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        assertTrue("提示串必须在 strings.xml 里真实定义", strings.contains("name=\"cloud_webdav_password_hint\""))
        assertTrue("提示必须点名应用密码", strings.contains("应用密码"))
        assertTrue("提示必须点名微信二次验证", strings.contains("微信二次验证"))
    }

    /** 括号配平取「签名 + 函数体」；从**签名起点**截，兼容表达式体（见 #287 踩坑）。 */
    private fun functionBody(src: String, name: String): String {
        val signature = Regex("""fun\s+(?:<[^>]+>\s+)?${Regex.escape(name)}\s*\(""").find(src)
        assertTrue("找不到函数 $name", signature != null)
        val start = signature!!.range.first
        val open = src.indexOf('{', start)
        assertTrue("$name 没有函数体", open >= 0)
        var depth = 0
        for (index in open until src.length) {
            when (src[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, index + 1)
                }
            }
        }
        return ""
    }

    /**
     * 从签名起点截到**下一个声明**，用于无花括号的表达式体（`= "…" + "…"`）。
     * ⚠️ `codeOnly()` 已把 KDoc 整段删掉，所以只能按代码声明边界找，不能找注释起始标记。
     * ⚠️ 别在 KDoc 里写出块注释起始符：Kotlin 块注释**可嵌套**，会把后面的代码吞进注释。
     */
    private fun declarationText(src: String, name: String): String {
        val signature = Regex("""fun\s+(?:<[^>]+>\s+)?${Regex.escape(name)}\s*\(""").find(src)
        assertTrue("找不到函数 $name", signature != null)
        val start = signature!!.range.first
        val tail = src.substring(start)
        val boundaries = listOf(
            "\n    private fun ",
            "\n    override fun ",
            "\n    fun ",
            "\n    private companion",
        )
        val end = boundaries.mapNotNull { tail.indexOf(it, startIndex = 1).takeIf { i -> i > 0 } }
            .minOrNull() ?: tail.length
        return tail.substring(0, end)
    }

    /** 去掉 KDoc / 行注释，避免注释里的示例代码把判据带偏。 */
    private fun codeOnly(text: String): String {
        var inBlock = false
        return text.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            when {
                inBlock -> {
                    if (trimmed.contains("*/")) inBlock = false
                    null
                }
                trimmed.startsWith("/*") -> {
                    inBlock = !trimmed.contains("*/")
                    null
                }
                trimmed.startsWith("*") || trimmed.startsWith("//") -> null
                else -> line
            }
        }.joinToString("\n")
    }
}
