package com.bastion.app.passkey

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.data.PasskeyEntry

class PasskeyRemarkAndNavigationGuardTest {

    @Test
    fun remarkWinsWithoutChangingOriginalPasskeyIdentity() {
        val passkey = testPasskey(notes = "工作 GitHub")

        assertEquals("工作 GitHub", passkey.displayTitle())
        assertEquals("TakagiKuyomi", passkey.userDisplayName)
        assertEquals("takagi", passkey.userName)
    }

    @Test
    fun blankRemarkFallsBackToOriginalDisplayName() {
        assertEquals("TakagiKuyomi", testPasskey(notes = "   ").displayTitle())
    }

    @Test
    fun compactPasskeyCardNavigatesInsteadOfExpanding() {
        val listSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/PasskeyListScreen.kt"
        ).readText()
        val clickBody = listSource.substringAfter("onClick = {\n                            if (selectionMode)")
            .substringBefore("onLongClick =")
        val paneSource = projectFile(
            "app/src/main/java/com/bastion/app/ui/passkey/PasskeyPane.kt"
        ).readText()

        assertFalse(clickBody.contains("expanded = !expanded"))
        assertTrue(clickBody.contains("onClick()"))
        assertTrue(paneSource.contains("passkey.managementRecordIdOrNull()?.let(onNavigateToPasskeyDetail)"))
    }

    @Test
    fun credentialSelectorUsesRemarkFirstTitle() {
        val provider = projectFile(
            "app/src/main/java/com/bastion/app/passkey/BastionCredentialProviderService.kt"
        ).readText()
        val authActivity = projectFile(
            "app/src/main/java/com/bastion/app/passkey/PasskeyAuthActivity.kt"
        ).readText()

        assertTrue(provider.contains("passkey.displayTitle()"))
        assertTrue(authActivity.contains("title = passkey.displayTitle()"))
    }

    @Test
    fun detailPrioritizesCompactAccountHeroAndMovesBindingDown() {
        val detail = projectFile(
            "app/src/main/java/com/bastion/app/ui/passkey/PasskeyDetailPanes.kt"
        ).readText()
        val paneBody = detail.substringAfter("internal fun PasskeyDetailPane(")
            .substringBefore("private fun PasskeyHeroCard(")
        val heroBody = detail.substringAfter("private fun PasskeyHeroCard(")
            .substringBefore("private fun PasskeyBindingCard(")

        assertTrue(heroBody.contains("rememberAutoMatchedSimpleIcon("))
        assertTrue(heroBody.contains("rememberFavicon("))
        assertTrue(heroBody.contains("Row(\n                modifier = Modifier.fillMaxWidth()"))
        assertFalse(paneBody.contains("title = stringResource(R.string.passkey_detail_account)"))
        assertTrue(
            paneBody.indexOf("R.string.passkey_detail_activity") <
                paneBody.indexOf("PasskeyBindingCard(")
        )
    }

    @Test
    fun authenticatorAndPasskeyShareOneDockDestinationWithBidirectionalControls() {
        val mainScreen = projectFile(
            "app/src/main/java/com/bastion/app/ui/SimpleMainScreen.kt"
        ).readText()
        val fab = projectFile(
            "app/src/main/java/com/bastion/app/ui/MainScreenFab.kt"
        ).readText()
        val passkeyList = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/PasskeyListScreen.kt"
        ).readText()
        // 底栏排序设置已从 SettingsScreen 拆出到独立的 BottomNavSettingsScreen，
        // PASSKEY 的过滤逻辑随之迁移，守卫的读取目标同步跟进。
        val bottomNavSettings = projectFile(
            "app/src/main/java/com/bastion/app/ui/screens/BottomNavSettingsScreen.kt"
        ).readText()

        assertTrue(mainScreen.contains("if (tab == BottomNavContentTab.PASSKEY) BottomNavContentTab.AUTHENTICATOR else tab"))
        assertTrue(mainScreen.contains("val selectedDockTab = if (currentTab == BottomNavItem.Passkey)"))
        assertTrue(mainScreen.contains("BackHandler(enabled = currentTab == BottomNavItem.Passkey)"))
        assertTrue(mainScreen.contains("onNavigateToPasskey = {\n            selectedTabKey = BottomNavItem.Passkey.key"))
        assertTrue(fab.contains("val shouldShowPasskeyFab ="))
        assertTrue(fab.contains("onClick = onNavigateToPasskey"))
        assertTrue(passkeyList.contains("onNavigateToAuthenticator: (() -> Unit)? = null"))
        assertTrue(passkeyList.contains("imageVector = Icons.Default.Security"))
        assertTrue(bottomNavSettings.contains("filterNot { it == BottomNavContentTab.PASSKEY }"))
    }

    @Test
    fun authenticatorPasskeySwitchUsesBastionDirectionalNavigationMotion() {
        val transition = projectFile(
            "app/src/main/java/com/bastion/app/ui/AuthenticatorPasskeyAnimatedContent.kt"
        ).readText()

        assertTrue(transition.contains("slideInFromRight() togetherWith parallaxExitToLeft()"))
        assertTrue(transition.contains("parallaxEnterFromLeft() togetherWith slideOutToRight()"))
        assertTrue(transition.contains("SizeTransform(clip = false)"))
    }

    private fun testPasskey(notes: String) = PasskeyEntry(
        credentialId = "credential",
        rpId = "github.com",
        rpName = "GitHub",
        userId = "user-id",
        userName = "takagi",
        userDisplayName = "TakagiKuyomi",
        publicKey = "public-key",
        privateKeyAlias = "private-key",
        notes = notes,
    )

    private fun projectFile(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
