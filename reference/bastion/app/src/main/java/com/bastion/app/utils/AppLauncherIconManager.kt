package com.bastion.app.utils

import com.bastion.app.logging.runCatchingObserved
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bastion.app.MainActivity
import com.bastion.app.R
import com.bastion.app.data.AppLauncherIcon
import com.bastion.app.data.AppLauncherLabel

object AppLauncherIconManager {
    private const val COMPAT_MODERN_ALIAS = "com.bastion.app.ModernLauncherAlias"
    private const val COMPAT_CLASSIC_ALIAS = "com.bastion.app.LockLauncherAlias"
    private const val HOME_MODERN_ALIAS = "com.bastion.app.ModernHomeLauncherAlias"
    private const val HOME_CLASSIC_ALIAS = "com.bastion.app.ClassicHomeLauncherAlias"
    private const val VISIBLE_MODERN_PASS_ALIAS = "com.bastion.app.ModernVisibleLauncherAlias"
    private const val VISIBLE_CLASSIC_PASS_ALIAS = "com.bastion.app.ClassicVisibleLauncherAlias"
    private const val VISIBLE_MODERN_MONICA_ALIAS = "com.bastion.app.ModernVisibleLauncherAliasBastion"
    private const val VISIBLE_CLASSIC_MONICA_ALIAS = "com.bastion.app.ClassicVisibleLauncherAliasBastion"

    fun apply(context: Context, icon: AppLauncherIcon, label: AppLauncherLabel) {
        repairCompatibilityLaunchTargets(context)
        applyVisibleLauncherSelection(context, label)
    }

    fun repairLegacyDisabledComponents(context: Context) {
        repairCompatibilityLaunchTargets(context)
    }

    fun repairLaunchEntryPointsAfterUpgrade(
        context: Context,
        icon: AppLauncherIcon,
        label: AppLauncherLabel
    ) {
        repairCompatibilityLaunchTargets(context)
        applyVisibleLauncherSelection(context, label)
    }

    fun getCurrentSelection(context: Context): AppLauncherIcon {
        return AppLauncherIcon.MODERN
    }

    fun resolveBrandingIconRes(context: Context): Int {
        // 与桌面启动器图标保持一致：使用当前启用的 launcher mipmap。
        // 此前返回 R.drawable.bastion_launcher（drawable-nodpi/bastion_launcher.png），
        // 该 png 是未随品牌更新换掉的旧图标，导致通知/生物识别等位置仍显示旧图标。
        return when (getCurrentSelection(context)) {
            AppLauncherIcon.MODERN -> R.mipmap.ic_launcher_modern
        }
    }

    /**
     * 通知小图标专用资源。
     *
     * Android 状态栏/通知的小图标只取 alpha 通道做单色化：alpha 覆盖到的区域被整体涂成
     * 主题色，RGB 一律忽略。自适应图标（`@mipmap/ic_launcher_modern`）合成后至少覆盖背景
     * 层的整个正方形，旧版背景层是纯透明（alpha 全 0）时小图标恰好只剩爪印轮廓；
     * 「琥珀守护」方案的背景层是**不透明**的深色玻璃底，若继续用它当 smallIcon，
     * 小图标会被单色化成一块实心方块。
     *
     * 因此通知小图标改用 monochrome 层——它本就是「纯白剪影 + alpha」，
     * 正是 smallIcon 需要的形态。
     */
    fun resolveNotificationSmallIconRes(): Int = R.drawable.ic_launcher_monochrome

    fun applyBiometricPromptBranding(context: Context, promptInfoBuilder: Any) {
        val builderClass = promptInfoBuilder.javaClass
        val iconRes = resolveBrandingIconRes(context)

        runCatchingObserved {
            builderClass.methods.firstOrNull { method ->
                method.name == "setLogoRes" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.invoke(promptInfoBuilder, iconRes)
        }

        runCatchingObserved {
            builderClass.methods.firstOrNull { method ->
                method.name == "setLogoDescription" &&
                    method.parameterTypes.size == 1 &&
                    CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
            }?.invoke(promptInfoBuilder, context.getString(R.string.app_name))
        }
    }

    private fun repairCompatibilityLaunchTargets(context: Context) {
        val packageManager = context.packageManager
        val components = listOf(
            ComponentName(context, MainActivity::class.java),
            ComponentName(context, COMPAT_MODERN_ALIAS),
            ComponentName(context, COMPAT_CLASSIC_ALIAS),
            ComponentName(context, HOME_MODERN_ALIAS),
            ComponentName(context, HOME_CLASSIC_ALIAS)
        )

        components.forEach { component ->
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun applyVisibleLauncherSelection(
        context: Context,
        label: AppLauncherLabel
    ) {
        val packageManager = context.packageManager
        val states = mapOf(
            ComponentName(context, VISIBLE_MODERN_PASS_ALIAS) to componentStateFor(
                label == AppLauncherLabel.MONICA_PASS
            ),
            ComponentName(context, VISIBLE_CLASSIC_PASS_ALIAS) to
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            ComponentName(context, VISIBLE_MODERN_MONICA_ALIAS) to componentStateFor(
                label == AppLauncherLabel.MONICA
            ),
            ComponentName(context, VISIBLE_CLASSIC_MONICA_ALIAS) to
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                states.map { (component, state) ->
                    PackageManager.ComponentEnabledSetting(
                        component,
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
            )
            return
        }

        states.forEach { (component, state) ->
            packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun componentStateFor(shouldEnable: Boolean): Int {
        return if (shouldEnable) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    }
}
