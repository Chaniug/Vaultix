package com.bastion.app.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫：主密码列表流**不得**逐条解密。
 *
 * 背景：列表流曾对每行调用 `inspectSecretState()` 解密，但解密结果在列表渲染路径上
 * **零消费**——卡片展示字段（`resolvePasswordCardDisplayLines`）不含 password，
 * 点击进详情也只用 `entry.id`（明文当场丢弃，详情页自己按 id 查库解密）。
 * 也就是说那是纯浪费：每条都是「1 次解密 + 1 次加密 + 1 次 `apply()` 写盘」，
 * 100~500 条即「解锁后 3~5 秒才看到密码库」。
 *
 * 对齐官方 Bitwarden / Keyguard：列表只吃密文，解密只发生在用户点开某一条、
 * 或按需管道里，绝不为了「列表上有没有密码」做全量解密。
 *
 * 这里锁的是**架构约束**而非实现细节，避免后续改动把全库解密悄悄加回来。
 */
class PasswordListDecryptionGuardTest {

    @Test
    fun mainListStreamMustNotDecryptEveryRow() {
        val source = passwordViewModelSource()
        val sourceBody = source
            .substringAfter("private val passwordEntriesSource")
            .substringBefore("val passwordEntriesReady")

        assertFalse(
            "主列表流禁止逐条解密：解密结果在列表渲染路径上零消费，" +
                "全库解密正是「解锁后 3~5 秒」的根因。",
            sourceBody.contains("inspectSecretState")
        )
    }

    @Test
    fun allPasswordsStreamMustNotDecryptEveryRow() {
        val source = passwordViewModelSource()
        val sourceBody = source
            .substringAfter("private val allPasswordsSource")
            .substringBefore("val allPasswordsReady")

        assertFalse(
            "全量列表流禁止逐条解密：Room DAO 生成的 Flow 自带 IO 调度，无需额外 map。",
            sourceBody.contains("inspectSecretState")
        )
    }

    @Test
    fun ghostFilterMustJudgePasswordPresenceWithoutDecrypting() {
        val source = passwordViewModelSource()
        val ghostBody = source
            .substringAfter("private fun filterGhostEntriesForDisplay(")
            .substringBefore("private fun dedupeExactEntries(")

        assertTrue(
            "幽灵条目过滤必须走 hasReadablePassword()，" +
                "以保留「MDK 包装丢失时判定为无可读密码」的原有语义。",
            ghostBody.contains("hasReadablePassword")
        )
        assertFalse(
            "幽灵条目过滤不得直接对密文判空：密文非空 ≠ 有可读密码。",
            ghostBody.contains("entry.password.isNotBlank()") ||
                ghostBody.contains("entry.password.isBlank()")
        )
    }

    private fun passwordViewModelSource(): String = projectFile(
        "app/src/main/java/com/bastion/app/viewmodel/PasswordViewModel.kt"
    ).readText()

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
