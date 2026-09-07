package com.bastion.app.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归守卫：DataStore 的两条硬性约束。
 *
 * 1) 同一进程内，同一个 DataStore 文件只能有一个实例。曾因 AutofillPreferences
 *    重复声明 preferencesDataStore("settings") 导致进入「自动填充设置」直接崩溃。
 *
 * 2) DataStore 不支持多进程。自动填充配置会被独立进程 :accessibility 读取，
 *    因此必须留在独立的 "autofill_settings" 存储中，不得并入主进程高频写入的
 *    "settings"，否则 :accessibility 会永久缓存到过期快照，
 *    导致「主动填充通知」静默失效。
 */
class SettingsDataStoreSingleInstanceRegressionGuardTest {

    @Test
    fun settingsDataStoreIsDeclaredExactlyOnceInMainSources() {
        val declarations = mainSourceFiles()
            .filter { storeDeclaration("settings").containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()

        assertEquals(
            "\"settings\" DataStore 只允许在 AppDataStore.kt 中声明一次，实际声明于：$declarations",
            listOf("AppDataStore.kt"),
            declarations
        )
    }

    @Test
    fun everyDataStoreFileNameIsDeclaredOnlyOnce() {
        val occurrences = mutableMapOf<String, MutableList<String>>()
        mainSourceFiles().forEach { file ->
            ANY_STORE_DECLARATION.findAll(file.readText()).forEach { match ->
                occurrences.getOrPut(match.groupValues[1]) { mutableListOf() }.add(file.name)
            }
        }
        val duplicated = occurrences.filterValues { it.size > 1 }

        assertEquals(
            "每个 DataStore 文件名只能声明一次，重复的有：$duplicated",
            emptyMap<String, List<String>>(),
            duplicated
        )
    }

    @Test
    fun autofillPreferencesStaysOnItsOwnStoreForCrossProcessSafety() {
        val autofillPreferences = projectFile(
            "app/src/main/java/com/bastion/app/autofill_ng/AutofillPreferences.kt"
        ).readText()

        assertTrue(
            "自动填充配置必须使用独立的 autofill_settings 存储",
            storeDeclaration("autofill_settings").containsMatchIn(autofillPreferences)
        )
        assertTrue(
            "AutofillPreferences 不得复用主进程的 settings 委托（:accessibility 跨进程读会拿到过期快照）",
            !autofillPreferences.contains("import com.bastion.app.utils.dataStore")
        )
    }

    @Test
    fun accessibilityServiceRunsInItsOwnProcess() {
        // 该断言是上面跨进程约束成立的前提；若将来无障碍服务改回主进程，
        // 这条会失败，提示重新评估存储是否可以合并。
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "BastionAccessibilityService 预期运行在 :accessibility 独立进程",
            manifest.contains("android:process=\":accessibility\"")
        )
    }

    private fun mainSourceFiles(): List<File> =
        projectFile("app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!
        }
        return File(directory, relativePath)
    }

    private companion object {
        fun storeDeclaration(name: String) =
            Regex("""by\s+preferencesDataStore\(\s*(?:name\s*=\s*)?"$name"\s*\)""")

        val ANY_STORE_DECLARATION =
            Regex("""by\s+preferencesDataStore\(\s*(?:name\s*=\s*)?"([^"]+)"\s*\)""")
    }
}
