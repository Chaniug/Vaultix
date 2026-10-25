/*
 * Vaultix — app:autofill · save
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.save

import android.content.Context
import android.content.Intent

/**
 * Service → AutofillSaveActivity 的 Intent 约定（集中于此，避免两侧 key 漂移）。
 *
 * ## ⚠️ 这里**只准出现票据**，不准出现账号密码（#187，2026-10-26）
 *
 * 曾经有 `EXTRA_USERNAME` / `EXTRA_PASSWORD` 带**明文凭据**。而 Activity 的启动意图
 * 会被系统记进 `IntentSender` / 最近任务快照，`SavedStateHandle` 还会再序列化一份到
 * 系统侧 —— 等于把用户的密码写进 /data/system（本应用的 `allowBackup=false` 与
 * `FLAG_SECURE` 都管不到系统那份）。现在改成：
 *
 *   Service ──stage()──> [PendingSaveStore]（进程内存，TTL 60s，一次性）
 *                            ▲
 *                            │ 只取走一次
 *   Service ──EXTRA_TOKEN──> Activity ──consume()──> ViewModel
 *
 * 剩下的两个 extra 是**非秘密**的来源标记（包名 / 域名），用于给条目起名和匹配已有条目。
 */
object AutofillSaveIntents {

    /**
     * 一次性票据（[PendingSaveStore.stage] 的返回值）。
     *
     * ⚠️ 别再往这里加用户名 / 密码的同级 key。敏感字段一律走 [PendingSaveStore]。
     */
    const val EXTRA_TOKEN = "vaultix.save.token"
    const val EXTRA_PACKAGE = "vaultix.save.package"
    const val EXTRA_DOMAIN = "vaultix.save.domain"

    fun create(
        context: Context,
        packageName: String?,
        webDomain: String?,
        token: String,
    ): Intent = Intent(context, AutofillSaveActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_TOKEN, token)
        .putExtra(EXTRA_PACKAGE, packageName)
        .putExtra(EXTRA_DOMAIN, webDomain)

    fun tokenOf(intent: Intent): String? = intent.getStringExtra(EXTRA_TOKEN)
    fun packageNameOf(intent: Intent): String? = intent.getStringExtra(EXTRA_PACKAGE)
    fun webDomainOf(intent: Intent): String? = intent.getStringExtra(EXTRA_DOMAIN)
}
