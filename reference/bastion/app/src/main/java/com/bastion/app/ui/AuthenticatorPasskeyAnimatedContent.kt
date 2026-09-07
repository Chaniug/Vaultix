package com.bastion.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bastion.app.ui.main.navigation.BottomNavItem
import com.bastion.app.ui.navigation.parallaxEnterFromLeft
import com.bastion.app.ui.navigation.parallaxExitToLeft
import com.bastion.app.ui.navigation.slideInFromRight
import com.bastion.app.ui.navigation.slideOutToRight
import com.bastion.app.ui.navigation.tabSwitchEnter
import com.bastion.app.ui.navigation.tabSwitchExit

@Composable
internal fun AuthenticatorPasskeyAnimatedContent(
    currentTab: BottomNavItem,
    modifier: Modifier = Modifier,
    content: @Composable (BottomNavItem) -> Unit
) {
    AnimatedContent(
        targetState = currentTab,
        modifier = modifier,
        transitionSpec = {
            val transform = when {
                initialState == BottomNavItem.Authenticator && targetState == BottomNavItem.Passkey ->
                    slideInFromRight() togetherWith parallaxExitToLeft()

                initialState == BottomNavItem.Passkey && targetState == BottomNavItem.Authenticator ->
                    parallaxEnterFromLeft() togetherWith slideOutToRight()

                // 其余 tab 切换：此前为 EnterTransition.None（硬切），
                // 现改用显式页面过渡，避免 Standard motion scheme 下显得僵硬。
                else -> tabSwitchEnter() togetherWith tabSwitchExit()
            }
            transform.using(SizeTransform(clip = false))
        },
        contentKey = BottomNavItem::key,
        label = "authenticator_passkey_switch",
        content = { targetTab -> content(targetTab) }
    )
}
