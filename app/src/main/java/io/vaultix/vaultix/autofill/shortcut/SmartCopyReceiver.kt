/*
 * Vaultix — app:autofill · shortcut
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.vaultix.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「点此复制用户名」通知的点击接收器（接力复制第二步）。
 *
 * `goAsync()` 保证广播回调返回后进程不会被立刻回收，复制（含剪贴板清除计时）能跑完。
 */
@AndroidEntryPoint
class SmartCopyReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notifier: SmartCopyNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val username = intent.getStringExtra(SmartCopyNotifier.EXTRA_USERNAME)
        val totp = intent.getStringExtra(SmartCopyNotifier.EXTRA_TOTP)
        if (username.isNullOrBlank() && totp.isNullOrBlank()) return
        val title = intent.getStringExtra(SmartCopyNotifier.EXTRA_TITLE).orEmpty()
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                if (!totp.isNullOrBlank()) {
                    notifier.copyTotpAndDismiss(totp)
                    toast(context, context.getString(R.string.copy_totp))
                } else if (!username.isNullOrBlank()) {
                    notifier.copyUsernameAndDismiss(username)
                    toast(context, context.getString(R.string.manual_fill_copied_username, title))
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
