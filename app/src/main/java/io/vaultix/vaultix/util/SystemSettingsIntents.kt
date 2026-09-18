/*
 * Vaultix — app:util
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 「跳到系统页面」的集中出口。
 *
 * ## 为什么要收拢到一个文件
 *
 * 在此之前这些跳转**散落在各页面里当 private 函数**（`SettingsScreen.openAppPermissionSettings`、
 * `AutofillSettingsScreen.openSystemAutofillSettings` / `openCredentialProviderSettings`），
 * 谁也复用不了谁。权限引导页要跳的正是这几个页面 —— 与其在第三个文件里抄第三份
 * `Intent(Settings.ACTION_...)`，不如把**动作**收在这里、让页面只关心"点哪个"。
 *
 * ## 两条纪律
 *
 * 1. **一律 `runCatching`**：这些 `startActivity` 的目标页由各家 ROM 决定 ——
 *    部分定制系统（尤其国内 ROM）会**裁掉**某个 Settings action，抛
 *    `ActivityNotFoundException`。跳不过去最多是"点了没反应"，**绝不能把 App 带崩**。
 *    （对齐 Bitwarden：它同样对所有 Settings 跳转做了兜底。）
 * 2. **不返回成功与否**：调用方拿不到结果，也不该拿 —— 用户从系统页返回时，
 *    界面靠 `ON_RESUME` 重新检测状态（见 `AutofillSettingsScreen` 的刷新范式）。
 */
object SystemSettingsIntents {

    /**
     * 本应用的**应用信息页**（权限总入口）。
     *
     * 为什么不是 `ACTION_APPLICATION_DETAILS_SETTINGS` 之外的专用权限页：Android 没有
     * 稳定的「只打开本应用权限列表」公开 action（各版本/ROM 表现不一），而应用信息页
     * 里一定有「权限」一项，是**跨版本最稳**的落点（`SettingsScreen` 原先也是这么做的）。
     */
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 本应用的**通知设置页**。
     *
     * 需要它是因为「通知」有**两层开关**，而运行时权限只管得着其中一层：
     * - `POST_NOTIFICATIONS` 运行时权限（Android 13+，我们能弹框要）；
     * - **系统级的通知总开关**（各版本都有，用户在系统里关掉了 —— 此时权限是
     *   `GRANTED`，但通知依然发不出来，见 [io.vaultix.vaultix.autofill.shortcut.SmartCopyNotifier]）。
     *
     * 所以权限页里"通知已授予"**不等于**"通知能收到"。真要看/改总开关，只能来这里。
     * Android 8.0+ 走 `ACTION_APP_NOTIFICATION_SETTINGS`（能带上具体应用）；
     * 更低版本该 action 不存在，退回应用信息页。
     */
    fun openAppNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            openAppDetails(context)
            return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    /** 本应用的**通知设置页**里那个通知**渠道**（快速填充接力通知）。暂未使用，留作后续深链。 */
    fun openAppNotificationChannelSettings(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            openAppDetails(context)
            return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppNotificationSettings(context) }
    }

    /**
     * **系统的生物识别录入页**（`ACTION_BIOMETRIC_ENROLL`）。
     *
     * 只在「设备有指纹硬件、但一个都没录入」时才该跳这里 —— 这种情况权限页**无法**用
     * `BiometricPrompt` 代替（没录指纹时弹框会直接失败），只能把用户送去系统里录一个。
     * 同样有 ROM 裁掉这个 action 的可能，故 `runCatching` 兜底到应用信息页。
     */
    fun openBiometricEnroll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30 之前没有这个 action，退回安全设置总页。
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    /**
     * 系统**自动填充服务**设置页（选哪个 App 当填充服务）。
     *
     * ⚠️ 这里的两个 action 都是**裸字符串**，不能换成 `Settings.ACTION_*` 常量：
     * `ACTION_REQUEST_SET_AUTOFILL_SERVICE`（API 26）与 `ACTION_AUTOFILL_SERVICE_SETTINGS`
     * （API 28）**都不是公开 API**，`Settings` 类里根本没有这两个字段，写了会编译不过。
     * 现有 `AutofillSettingsScreen.openSystemAutofillSettings` 用的正是这串裸字符串，
     * 此处保持一致（同一份行为，不该出现两种写法）。
     *
     * 首选 `REQUEST_SET_AUTOFILL_SERVICE`：它带上 `package:` 直接落在「把本应用设为
     * 填充服务」的确认页；部分 ROM 没有这个 action，退回服务选择列表。
     */
    fun openAutofillSettings(context: Context) {
        val direct = Intent("android.settings.REQUEST_SET_AUTOFILL_SERVICE").apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching { context.startActivity(direct) }.isSuccess
        if (opened) return
        runCatching {
            context.startActivity(
                Intent("android.settings.AUTOFILL_SERVICE_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    /** 用外部浏览器 / 应用打开一个 URL（源码页、发布页等）。 */
    fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
