package com.bastion.app.service

import android.content.Context
import java.io.File

/**
 * 跨进程「无障碍注入命令」存储：autofill 进程(:autofill) 写入，accessibility 进程(:accessibility) 消费。
 *
 * 用途：Android Autofill Framework 的认证 dataset 回填在部分 WebView（如 Via + PayPal）上不可靠——
 * dataset 已正确构建并返回，但密码/用户名框收不到值。此时改走无障碍直接注入兜底：
 * 回调把凭据写入本存储并广播唤醒无障碍服务，无障碍服务对当前浏览器窗口的密码/用户名节点直接注入。
 * 这绕开框架的 autofillId 匹配，直接作用于活控件，行为对齐 Bitwarden 的 WebView 兜底。
 *
 * 跨进程机制复用 BrowserAutofillContextStore 的文件存储模式（同 UID 下 filesDir 共享，
 * 临时文件 + 原子 rename 避免半写）。命令为一次性：被消费后由消费端 clear()。
 */
object AccessibilityFillCommandStore {
    const val ACTION_FILL_COMMAND = "com.bastion.app.action.ACCESSIBILITY_FILL_COMMAND"

    private const val FILE_NAME = "bastion_a11y_fill_cmd_v1"
    private const val FILE_NAME_TMP = "bastion_a11y_fill_cmd_v1.tmp"
    private const val MAX_AGE_MS = 10_000L

    data class Command(
        val packageName: String,
        val username: String,
        val password: String,
        val preferPasswordField: Boolean,
        val otp: String,
        val createdAt: Long,
    )

    @Volatile
    private var appContext: Context? = null

    /** 由 autofill / accessibility 两进程的服务在连接时调用，提供应用上下文以便读写 filesDir 文件。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun write(command: Command) {
        val ctx = appContext ?: return
        val dir = ctx.filesDir
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, FILE_NAME_TMP)
        runCatching {
            tmp.writeText(
                buildString {
                    appendLine(command.packageName)
                    appendLine(command.username)
                    appendLine(command.password)
                    appendLine(command.preferPasswordField.toString())
                    appendLine(command.otp)
                    appendLine(command.createdAt.toString())
                }
            )
            if (!tmp.renameTo(target)) {
                // rename 失败时直接覆盖写（极端情况下可能读到半写内容，但消费端会做格式/超龄校验）。
                target.writeText(tmp.readText())
            }
            runCatching { tmp.delete() }
        }
    }

    /** 读取但不删除（供重试循环使用）。超龄或格式错误返回 null。 */
    @Synchronized
    fun peek(): Command? {
        val ctx = appContext ?: return null
        val file = File(ctx.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            val lines = file.readLines()
            if (lines.size < 6) return@runCatching null
            val pkg = lines[0].trim()
            val username = lines[1]
            val password = lines[2]
            val prefer = lines[3].trim().toBooleanStrictOrNull() ?: false
            val otp = lines[4]
            val ts = lines[5].trim().toLongOrNull() ?: return@runCatching null
            if (System.currentTimeMillis() - ts > MAX_AGE_MS) return@runCatching null
            Command(pkg, username, password, prefer, otp, ts)
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        val ctx = appContext ?: return
        runCatching { File(ctx.filesDir, FILE_NAME).delete() }
    }
}
