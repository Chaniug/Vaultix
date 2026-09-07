package com.bastion.app.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashThemeResourceTest {
    @Test
    fun systemSplashFallbackUsesBastionM3LightAndDarkColors() {
        val lightColors = projectFile("app/src/main/res/values/colors.xml").readText()
        val darkColors = projectFile("app/src/main/res/values-night/colors.xml").readText()
        val lightApi31 = projectFile("app/src/main/res/values-v31/colors.xml").readText()
        val darkApi31 = projectFile("app/src/main/res/values-night-v31/colors.xml").readText()
        val lightTheme = projectFile("app/src/main/res/values/themes.xml").readText()
        val darkTheme = projectFile("app/src/main/res/values-night/themes.xml").readText()

        assertTrue(lightColors.contains("<color name=\"bastion_startup_background\">#F7F2FA</color>"))
        assertTrue(darkColors.contains("<color name=\"bastion_startup_background\">#1C1B1F</color>"))
        assertTrue(!lightApi31.contains("system_neutral"))
        assertTrue(!darkApi31.contains("system_neutral"))
        assertTrue(lightApi31.contains("android:windowSplashScreenBackground"))
        assertTrue(darkApi31.contains("android:windowSplashScreenBackground"))
        // 这些 item 可能带 tools:ignore="NewApi" 等附加属性（windowLightNavigationBar
        // 需 API 27），所以用容忍额外属性的正则，而不是全等文本匹配。
        assertTrue(hasThemeFlag(lightTheme, "windowLightStatusBar", expected = true))
        assertTrue(hasThemeFlag(lightTheme, "windowLightNavigationBar", expected = true))
        assertTrue(hasThemeFlag(darkTheme, "windowLightStatusBar", expected = false))
        assertTrue(hasThemeFlag(darkTheme, "windowLightNavigationBar", expected = false))
    }

    @Test
    fun startupUsesOnlyTheAndroidSystemSplashLayer() {
        val mainActivity = projectFile(
            "app/src/main/java/com/bastion/app/MainActivity.kt"
        ).readText()
        val baseActivity = projectFile(
            "app/src/main/java/com/bastion/app/ui/base/BaseBastionActivity.kt"
        ).readText()

        assertTrue(!mainActivity.contains("BastionStartupSplash"))
        assertTrue(!mainActivity.contains("doOnPreDraw"))
        assertTrue(!mainActivity.contains("private fun initializeMainContent()"))
        assertTrue(!baseActivity.contains("cachedSettings = startupSettings"))
        assertTrue(mainActivity.split("installSplashScreen()").size - 1 == 1)
        assertTrue(mainActivity.contains("setContent {"))
        // 调用点已格式化为多行具名参数，用空白不敏感的正则代替原来的紧凑文本断言。
        assertTrue(Regex("""BastionApp\(\s*repository\s*=""").containsMatchIn(mainActivity))
    }

    /**
     * 匹配形如 `<item name="android:windowLightStatusBar">true</item>` 的主题开关，
     * 允许 item 上带任意附加属性（例如 `tools:ignore="NewApi"`）。
     */
    private fun hasThemeFlag(themeXml: String, attribute: String, expected: Boolean): Boolean {
        val pattern = Regex("""<item\s+name="android:$attribute"[^>]*>\s*$expected\s*</item>""")
        return pattern.containsMatchIn(themeXml)
    }

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
