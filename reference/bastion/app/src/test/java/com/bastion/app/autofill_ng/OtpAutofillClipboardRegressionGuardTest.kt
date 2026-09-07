package com.bastion.app.autofill_ng

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固化“自动填充后 OTP 进入输入法剪贴板”的行为（修复 Edge/WebView 上 OTP 不进剪贴板）。
 *
 * 两层根因：
 *  1) OTP 自动复制此前为 opt-in 且默认关闭；
 *  2) 无障碍兜底的“临时剪贴板还原”会在填充完成数秒后把已复制的 OTP 抹掉（竞态）。
 * 本测试确保：(a) OTP 自动复制默认开启；(b) 无障碍填充收尾显式把 OTP 留在剪贴板、取消还原、
 * 且不打 IS_SENSITIVE 标记（否则输入法剪贴板不收录）。
 */
class OtpAutofillClipboardRegressionGuardTest {

    @Test
    fun otpAutoCopyIsOnByDefaultAndSurvivesAccessibilityFill() {
        val preferencesSource = projectFile("app/src/main/java/com/bastion/app/autofill_ng/AutofillPreferences.kt").readText()
        val serviceSource = projectFile("app/src/main/java/com/bastion/app/service/BastionAccessibilityService.kt").readText()

        // 1) OTP 自动复制默认开启（对齐 Bitwarden；修复 Edge/WebView OTP 不进输入法剪贴板）。
        assertTrue(preferencesSource.contains(Regex("preferences\\[KEY_AUTO_COPY_OTP\\]\\s*\\?\\:\\s*true")))

        // 2) 无障碍填充收尾显式把 OTP 留在剪贴板，取消挂起的还原。
        assertTrue(serviceSource.contains("private fun leaveOtpInClipboard("))
        assertTrue(serviceSource.contains(Regex("if\\s*\\(otp\\.isNotBlank\\(\\)\\)\\s*\\{\\s*leaveOtpInClipboard\\(otp\\)")))

        // leaveOtpInClipboard 方法体：必须取消还原、清会话、且不得打 IS_SENSITIVE。
        val leaveBody = serviceSource.substringAfter("private fun leaveOtpInClipboard(").substringBefore("private fun resetTemporaryClipboardSessionLocked(")
        assertTrue(leaveBody.contains("clipboardHandler::removeCallbacks"))
        assertTrue(leaveBody.contains("resetTemporaryClipboardSessionLocked()"))
        assertFalse(leaveBody.contains("IS_SENSITIVE"))
    }

    private fun projectFile(relativePath: String): File {
        val file = projectPath(relativePath)
        if (file.isFile) return file
        error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }

    private fun projectPath(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }
        return candidates.firstOrNull { it.exists() }
            ?: error("Unable to find project path: $relativePath from ${System.getProperty("user.dir")}")
    }
}
