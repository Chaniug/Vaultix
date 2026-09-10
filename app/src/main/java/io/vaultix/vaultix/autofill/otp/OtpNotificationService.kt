/*
 * Vaultix — app:autofill · otp
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 设计参考 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * autofill_ng/service/AutofillOtpNotificationService.kt：自动填充后在通知栏
 * **实时**展示两步验证码（每秒重算 + 倒计时 + 点按复制当前最新码 + 到期自动收起 +
 * 息屏暂停刷新省电）。本文件按 Vaultix 的偏好层（VaultixPreferences）与
 * 安全剪贴板（VaultixClipboard）独立重写，同样以 GPL-3.0 发布。
 * ---------------------------------------------------------------------------
 *
 * 存在的理由：验证码「一律自动复制到剪贴板」在第一步登录时纯属多此一举（用户还没走到
 * 2FA 那一步，剪贴板已被占用/被清空）。改为通知承载后，验证码始终可点、可看、不抢剪贴板，
 * 两个开关（本服务 / autoCopyTotp）相互独立，用户自选。
 */
package io.vaultix.vaultix.autofill.otp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.common.OtpUriParser
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.vaultix.R
import io.vaultix.vaultix.util.VaultixClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 填充后验证码「通知栏实时展示」前台服务。
 *
 * - 启动即进入前台（`specialUse`），每秒重算验证码并刷新通知
 * - 通知标题带剩余秒数（取「验证码有效期」与「通知展示期」的较小值）
 * - 动作按钮复制的是**当前最新**的码，而非通知首次发出时那一份
 * - 展示时长到期自动收起通知并停止服务；息屏期间暂停刷新
 */
@AndroidEntryPoint
class OtpNotificationService : Service() {

    @Inject
    lateinit var clipboard: VaultixClipboard

    @Inject
    lateinit var prefs: VaultixPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private var session: OtpNotificationSession? = null
    private var label: String = ""
    private var isForeground = false

    /** 当前应复制的验证码（每次 tick 刷新）。 */
    @Volatile
    private var latestCode: String = ""

    /** 息屏暂停刷新：验证码对用户不可见时不做每秒通知推送。 */
    @Volatile
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_COPY -> handleCopy()
            ACTION_DISMISS -> stopCompletely()
            else -> if (session == null) stopCompletely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        tickJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ----- 指令处理 -----

    private fun handleStart(intent: Intent) {
        val otpUri = intent.getStringExtra(EXTRA_OTP_URI).orEmpty()
        label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val duration = intent.getIntExtra(EXTRA_DURATION_SECONDS, DEFAULT_DURATION_SECONDS)
            .coerceAtLeast(1)

        val config = runCatching { OtpUriParser.parse(otpUri) }.getOrNull()
        if (config == null) {
            // 密钥不可解析（非 otpauth URI / 字段损坏）：静默收起，不打断填充主流程。
            stopCompletely()
            return
        }

        val current = OtpNotificationSession(config, SystemClock.elapsedRealtime(), duration)
        session = current

        val initial = current.snapshot(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            nowWallSeconds = nowWallSeconds(),
        )
        latestCode = initial.code
        publish(buildNotification(initial.code, initial.remainingSeconds), initial = true)

        tickJob?.cancel()
        tickJob = scope.launch {
            // 首帧已发，跳过第一个 1s 等待避免双发
            delay(TICK_MS)
            while (isActive) {
                val active = session ?: return@launch
                val snapshot = active.snapshot(
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    nowWallSeconds = nowWallSeconds(),
                )
                if (snapshot.expired) break
                latestCode = snapshot.code
                if (screenOn) {
                    publish(buildNotification(snapshot.code, snapshot.remainingSeconds), initial = false)
                }
                delay(TICK_MS)
            }
            stopCompletely()
        }
    }

    private fun handleCopy() {
        val code = latestCode
        if (code.isBlank()) return
        scope.launch {
            val autoClearMs = runCatching { prefs.clipboardClearMs.first() }.getOrDefault(0L)
            clipboard.copy(text = code, autoClearMs = autoClearMs)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 系统自带剪贴板浮层，不再重复提示
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OtpNotificationService,
                        getString(R.string.otp_notification_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // ----- 通知 -----

    private fun buildNotification(code: String, remainingSeconds: Int): Notification {
        val displayCode = code.ifBlank { getString(R.string.otp_notification_placeholder) }
        val spannable = SpannableString(formatCodeForDisplay(displayCode)).apply {
            setSpan(RelativeSizeSpan(CODE_SCALE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val title = if (label.isBlank()) {
            getString(R.string.otp_notification_channel)
        } else {
            getString(R.string.otp_notification_title_format, label, remainingSeconds)
        }
        val copyAction = getString(R.string.otp_notification_copy_action, displayCode)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(title)
            .setContentText(spannable)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spannable))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            // 锁屏不泄露条目名与验证码
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setDeleteIntent(servicePendingIntent(ACTION_DISMISS, REQUEST_DISMISS))
            .addAction(
                R.drawable.ic_stat_lock,
                copyAction,
                servicePendingIntent(ACTION_COPY, REQUEST_COPY),
            )
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, OtpNotificationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun publish(notification: Notification, initial: Boolean) {
        if (initial || !isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            return
        }
        // notify 走主线程（与 SmartCopyNotifier 同一取舍）
        scope.launch {
            withContext(Dispatchers.Main) {
                if (isForeground) {
                    NotificationManagerCompat.from(this@OtpNotificationService)
                        .notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.otp_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.otp_notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopCompletely() {
        session = null
        latestCode = ""
        tickJob?.cancel()
        tickJob = null
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    /** 数字每 3 位分组，便于照读（对齐 Bastion formatCodeForDisplay）。 */
    private fun formatCodeForDisplay(code: String): String = when {
        code.length >= SIX_DIGITS -> code.chunked(THREE_CHUNK).joinToString(" ")
        code.length >= FOUR_DIGITS -> code.chunked(TWO_CHUNK).joinToString(" ")
        else -> code
    }

    private fun nowWallSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

    companion object {
        private const val CHANNEL_ID = "otp_notification"
        private const val NOTIFICATION_ID = 9529
        private const val MILLIS_PER_SECOND = 1000L
        private const val REQUEST_COPY = 3001
        private const val REQUEST_DISMISS = 3002
        private const val TICK_MS = 1000L
        private const val CODE_SCALE = 1.4f
        private const val DEFAULT_DURATION_SECONDS = 30

        /** 验证码分组展示的位宽阈值（6 位按 3+3、4 位按 2+2）。 */
        private const val SIX_DIGITS = 6
        private const val THREE_CHUNK = 3
        private const val FOUR_DIGITS = 4
        private const val TWO_CHUNK = 2

        const val ACTION_START = "io.vaultix.vaultix.otp.ACTION_START"
        const val ACTION_COPY = "io.vaultix.vaultix.otp.ACTION_COPY"
        const val ACTION_DISMISS = "io.vaultix.vaultix.otp.ACTION_DISMISS"

        const val EXTRA_OTP_URI = "vaultix.otp.uri"
        const val EXTRA_LABEL = "vaultix.otp.label"
        const val EXTRA_DURATION_SECONDS = "vaultix.otp.duration"

        /**
         * 启动 / 重启验证码通知。
         *
         * 通知权限被拒时直接不启动：前台服务仍会跑，但通知不可见 → 用户什么也看不到，
         * 起一个不可见的常驻服务没有意义（开关打开时设置页会引导去授权）。
         */
        fun start(context: Context, otpUri: String, label: String, durationSeconds: Int) {
            if (otpUri.isBlank()) return
            if (!canNotify(context)) return
            val intent = Intent(context, OtpNotificationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_OTP_URI, otpUri)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_DURATION_SECONDS, durationSeconds)
            }
            runCatching { context.startForegroundService(intent) }
        }

        private fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
