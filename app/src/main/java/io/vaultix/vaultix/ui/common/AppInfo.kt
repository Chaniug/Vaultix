/*
 * Vaultix — app:ui · common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 应用枚举思路参考 Bastion `AppSelector.loadInstalledApps`（GPL-3.0，Copyright 2025
 * JoyinJoester）：用 LAUNCHER intent 枚举，规避 Android 11+ 包可见性限制，不申请
 * QUERY_ALL_PACKAGES（应用商店审核风险）。
 */
package io.vaultix.vaultix.ui.common

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 可选应用（包名 + 显示名）。 */
data class AppInfo(val packageName: String, val appName: String)

/** 枚举上限（去重后），防止极端设备上万条结果拖垮 UI。 */
private const val MAX_APPS = 1000

/**
 * 枚举已安装的可启动应用（不含本应用自身）。
 *
 * 用 `ACTION_MAIN + CATEGORY_LAUNCHER` 而非 `getInstalledPackages`：后者在 Android 11+
 * 受包可见性限制（需 QUERY_ALL_PACKAGES 权限，有应用商店审核风险），且会混入大量
 * 无图标的系统组件。
 */
suspend fun loadInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val self = context.packageName
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull()
        ?: return@withContext emptyList()
    val seen = mutableSetOf<String>()
    resolved
        .map { it.activityInfo }
        .filter { it.packageName != self && seen.add(it.packageName) }
        .map { info ->
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
            AppInfo(info.packageName, label?.takeIf { it.isNotBlank() } ?: info.packageName)
        }
        .sortedBy { it.appName.lowercase(Locale.ROOT) }
        .take(MAX_APPS)
}
