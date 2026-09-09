/*
 * Vaultix — app:autofill · shortcut
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 思路参考 Bastion `SmartCopyNotificationHelper`（GPL-3.0，Copyright 2025 JoyinJoester）：
 * 先复制密码 → 通知栏留一个「点此复制用户名」的接力入口。区别是 Vaultix 去掉了
 * 无障碍依赖，改由用户从快捷磁贴 / 通知主动触发。
 */
package io.vaultix.vaultix.autofill.shortcut

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.vaultix.R
import io.vaultix.vaultix.util.VaultixClipboard
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能复制接力：复制密码后留一条通知，点一下再复制用户名。
 *
 * 存在的意义：**不依赖输入法内联建议、也不依赖无障碍**的兜底填充路径——
 * 国产输入法基本不实现 Android 11+ 的 IME inline suggestions，原生 autofill
 * 在部分国产 ROM / 浏览器里也会被吞掉，此时「复制 + 粘贴」是唯一可靠的填充方式。
 */
@Singleton
class SmartCopyNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clipboard: VaultixClipboard,
    private val prefs: VaultixPreferences,
) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * 复制密码 → 发接力通知（有用户名才发，否则一条通知没意义）。
     * 剪贴板自动清除延迟取自用户偏好（`clipboardClearMs`，0 = 不清除）。
     */
    suspend fun copyPasswordThenOfferUsername(
        title: String,
        username: String,
        password: String,
        totpCode: String? = null,
    ) {
        clipboard.copy(text = password, label = CLIP_LABEL, autoClearMs = prefs.clipboardClearMs.first())
        if (!canNotify()) return
        // 用户名和验证码都没有时，一条没有动作的通知没有意义
        if (username.isBlank() && totpCode.isNullOrBlank()) return
        manager.notify(NOTIFICATION_ID, buildNotification(title, username, totpCode))
    }

    /** 通知点击：复制用户名并收起通知。 */
    suspend fun copyUsernameAndDismiss(username: String) {
        clipboard.copy(text = username, label = CLIP_LABEL, autoClearMs = prefs.clipboardClearMs.first())
        manager.cancel(NOTIFICATION_ID)
    }

    /** 通知点击：复制验证码并收起通知（2FA 第二步常只需要验证码）。 */
    suspend fun copyTotpAndDismiss(code: String) {
        clipboard.copy(text = code, label = CLIP_LABEL, autoClearMs = prefs.clipboardClearMs.first())
        manager.cancel(NOTIFICATION_ID)
    }

    /** Android 13+ 需运行时授权；系统级通知开关关闭时同样不发（发了也看不见）。 */
    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun buildNotification(title: String, username: String, totpCode: String?): Notification {
        ensureChannel()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(context.getString(R.string.manual_fill_notif_title, title))
            .setContentText(context.getString(R.string.manual_fill_notif_text))
            .setTimeoutAfter(TIMEOUT_MS)
            .setAutoCancel(true)
            // 锁屏上不泄露条目名
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)

        val actions = mutableListOf<NotificationCompat.Action>()
        if (username.isNotBlank()) {
            val intent = Intent(context, SmartCopyReceiver::class.java)
                .putExtra(EXTRA_USERNAME, username)
                .putExtra(EXTRA_TITLE, title)
            val pending = PendingIntent.getBroadcast(context, REQUEST_COPY_USERNAME, intent, flags)
            builder.setContentIntent(pending)
            actions += NotificationCompat.Action(
                R.drawable.ic_stat_lock,
                context.getString(R.string.manual_fill_copy_username),
                pending,
            )
        }
        if (!totpCode.isNullOrBlank()) {
            val intent = Intent(context, SmartCopyReceiver::class.java)
                .putExtra(EXTRA_TOTP, totpCode)
                .putExtra(EXTRA_TITLE, title)
            val pending = PendingIntent.getBroadcast(context, REQUEST_COPY_TOTP, intent, flags)
            if (actions.isEmpty()) builder.setContentIntent(pending)
            actions += NotificationCompat.Action(
                R.drawable.ic_stat_lock,
                context.getString(R.string.manual_fill_copy_totp),
                pending,
            )
        }
        actions.forEach { builder.addAction(it) }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.tile_manual_fill),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.manual_fill_notif_text) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** 通知 id（Bastion 同值为 9528，保持可比）。 */
        const val NOTIFICATION_ID = 9528

        const val CHANNEL_ID = "manual_fill"
        const val EXTRA_USERNAME = "vaultix.manual_fill.username"
        const val EXTRA_TOTP = "vaultix.manual_fill.totp"
        const val EXTRA_TITLE = "vaultix.manual_fill.title"

        /** 剪贴板条目标签（清除校验按 label + text 匹配）。 */
        const val CLIP_LABEL = "Vaultix"

        /** 接力通知自动消失时间。 */
        private const val TIMEOUT_MS = 60_000L

        private const val REQUEST_COPY_USERNAME = 2001
        private const val REQUEST_COPY_TOTP = 2002
    }
}
