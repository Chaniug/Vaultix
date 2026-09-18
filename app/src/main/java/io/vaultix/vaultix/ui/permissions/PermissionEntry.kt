/*
 * Vaultix — app:ui · permissions
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.permissions

import androidx.annotation.StringRes

/**
 * 一个权限项在系统里的状态（权限引导页每一行据此决定显示什么、点哪去）。
 *
 * 刻意**不区分**「从没问过」和「问过被拒」——那需要 `shouldShowRequestPermissionRationale`，
 * 而它只在 Activity 里才问得准：返回 `false` 既可能是"从没问过"，也可能是"勾了不再询问"，
 * 是个**二义值**，拿它驱动 UI 会显示出自相矛盾的一句话。本页统一按
 * [Granted] / [Denied] / [Unavailable] 三态呈现。
 */
enum class PermissionState {
    /** 已授予（或该能力不需要运行时权限且当前可用）。 */
    Granted,

    /** 未授予 / 被拒 / 设备有硬件但没录入 —— 用户需要做点什么。 */
    Denied,

    /** 本设备**根本没有**这个能力（无相机、无指纹硬件）⇒ 不该提示"去开启"。 */
    Unavailable,
}

/**
 * 权限引导页要展示的一项（**纯数据**，不含 Context / 不含 Android 调用 ⇒ 可单测）。
 *
 * @param id 稳定标识（用作列表 key 与 UI 分支判据，不参与展示）。
 * @param titleRes 行标题（权限 / 能力名）。
 * @param purposeRes 用途说明：**为什么 Vaultix 要它、以及不要它做什么**。
 *   遵循 `.ai` 文案纪律 —— 讲"后果"不讲"操作步骤"（"点这里开启"那类说明书口气是廉价感来源）。
 * @param state 当前状态。
 * @param runtimePermission 运行时权限名；`null` = 只能跳系统设置（如生物识别录入、自动填充服务）。
 */
data class PermissionEntry(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val purposeRes: Int,
    val state: PermissionState,
    val runtimePermission: String? = null,
) {
    /** 是否可以就地弹系统授权框（能弹的才给"授予"按钮，其余走系统设置）。 */
    val canRequestInApp: Boolean get() = runtimePermission != null
}

/** 权限项的稳定 id（UI 用作 key 与分支判据）。 */
object PermissionIds {
    const val CAMERA = "camera"
    const val NOTIFICATIONS = "notifications"
    const val BIOMETRICS = "biometrics"
    const val NETWORK = "network"
}
