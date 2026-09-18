/*
 * Vaultix — app:ui · permissions
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import io.vaultix.vaultix.R

/**
 * 「设备有没有生物识别能力」的探测口径（抽成接口 ⇒ 单测可注入假实现）。
 */
fun interface BiometricCapability {
    /** 返回 [BiometricManager.canAuthenticate] 的结果码。 */
    fun canAuthenticate(): Int
}

/**
 * 权限引导页的状态检查。
 *
 * ## 为什么单独成类（而不是写在 Composable 里）
 *
 * 一是**可测**：把 `Context` 换成两个极小的函数式依赖（[permissionGranted] /
 * [hasCamera] / [biometric]），纯 JVM 单测就能把三态映射全跑一遍，不用起模拟器；
 * 二是**可复用**：将来扫码页 / 快速填充页要判状态，不该各写一份
 * `checkSelfPermission`（8.4 纪律：同一功能三种写法 = 高危信号）。
 *
 * ## ⚠️ 只列「真正用得到」的权限（2026-09-18 定案）
 *
 * 合并后的清单里还有 `NFC`、`USE_FINGERPRINT`、`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
 * ——**它们都不是 Vaultix 自己要的**：
 * - `NFC`：MSAL（OneDrive 登录）链路把 **YubiKit** 拉进来时合并的**传递依赖**。
 *   Vaultix 当前没有任何 YubiKey 能力在读它；
 * - `USE_FINGERPRINT`：`USE_BIOMETRIC` 的**旧版别名**，同一件事的两种写法；
 * - `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`：`androidx.core` 的**签名级内部权限**，
 *   用户既看不见也管不着。
 *
 * 把它们列进权限页 = 让用户去管三个**自己无法操作、也不影响 App** 的条目，
 * 只会稀释真正要管的那几项。
 *
 * @param permissionGranted 某运行时权限是否已授予（生产实现 = `ContextCompat.checkSelfPermission`）。
 * @param hasCamera 设备是否有摄像头（无则相机项显示为"设备不支持"）。
 * @param biometric 生物识别能力探测。
 */
class PermissionStatusChecker(
    private val permissionGranted: (String) -> Boolean,
    private val hasCamera: () -> Boolean,
    private val biometric: BiometricCapability,
) {

    /** 真实设备实现（页面用这个）。 */
    constructor(context: Context) : this(
        permissionGranted = { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        },
        hasCamera = { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
        biometric = { canAuthenticate(context) },
    )

    /** 采集当前所有权限项（**顺序即页面展示顺序**：相机 / 通知 / 指纹 / 网络）。 */
    fun entries(): List<PermissionEntry> = listOf(
        camera(),
        notifications(),
        biometrics(),
        network(),
    )

    private fun camera(): PermissionEntry = PermissionEntry(
        id = PermissionIds.CAMERA,
        titleRes = R.string.permission_camera_title,
        purposeRes = R.string.permission_camera_purpose,
        state = when {
            !hasCamera() -> PermissionState.Unavailable
            permissionGranted(Manifest.permission.CAMERA) -> PermissionState.Granted
            else -> PermissionState.Denied
        },
        runtimePermission = Manifest.permission.CAMERA,
    )

    private fun notifications(): PermissionEntry = PermissionEntry(
        id = PermissionIds.NOTIFICATIONS,
        titleRes = R.string.permission_notifications_title,
        purposeRes = R.string.permission_notifications_purpose,
        // Android 13 以下没有这个运行时权限（系统默认允许）⇒ 不该显示成"未授予"，
        // 那会让低版本用户对着一个点不动的"去开启"发呆。
        state = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> PermissionState.Granted
            permissionGranted(Manifest.permission.POST_NOTIFICATIONS) -> PermissionState.Granted
            else -> PermissionState.Denied
        },
        runtimePermission = Manifest.permission.POST_NOTIFICATIONS
            .takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
    )

    /**
     * 生物识别。
     *
     * - `BIOMETRIC_SUCCESS` → 已授予（能弹框认证）；
     * - `ERROR_NONE_ENROLLED` → 未授予：**有硬件但没录指纹** ⇒ 该跳系统录入页；
     * - 其余（无硬件 / 硬件不可用 / 不支持）→ 设备不具备 ⇒ 如实显示，**不提供跳转**
     *   （8.4：不可用行仍要完整可读，但别给一个点了也没用的按钮）。
     *
     * ⚠️ 这条**没有运行时权限可申请**：`USE_BIOMETRIC` 是普通权限（安装即授予），
     * 用户真正要做的是"去系统里录一个指纹"。故 [PermissionEntry.runtimePermission] 为 null。
     */
    private fun biometrics(): PermissionEntry = PermissionEntry(
        id = PermissionIds.BIOMETRICS,
        titleRes = R.string.permission_biometrics_title,
        purposeRes = R.string.permission_biometrics_purpose,
        state = when (biometric.canAuthenticate()) {
            BiometricManager.BIOMETRIC_SUCCESS -> PermissionState.Granted
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> PermissionState.Denied
            else -> PermissionState.Unavailable
        },
    )

    /**
     * 网络访问。
     *
     * ⚠️ 恒为已授予：`INTERNET` 是普通权限，安装即授予、用户无法单独关掉（要断网只能走
     * 系统网络开关）。列出来是为了**回答"这 App 要联网吗、联去干什么"** ——
     * 用户对密码管理器是否联网是有疑问的，**沉默才是问题**。
     * 所以它在页面上是**陈述**，不是待办（不给按钮）。
     */
    private fun network(): PermissionEntry = PermissionEntry(
        id = PermissionIds.NETWORK,
        titleRes = R.string.permission_network_title,
        purposeRes = R.string.permission_network_purpose,
        state = PermissionState.Granted,
    )
}

/**
 * 生物识别认证器的**探测口径**。
 *
 * ⚠️ **必须与 [io.vaultix.vaultix.ui.common.deviceCanAuthenticate] 逐字一致** ——
 * 权限页说"可用"、点快速解锁却说"不支持"是最典型的**两套口径**事故
 * （8.4 纪律：同一件事在项目里出现两种写法 = 高危信号，先统一再修）。
 * 上游口径是：API 30+ 取 `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`（允许设备 PIN 兜底），
 * API 26–29 走无参重载（只有强生物识别）。这里照抄，包括那个版本分支。
 */
private fun canAuthenticate(context: Context): Int {
    val manager = BiometricManager.from(context)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    } else {
        // API 26-29：有参重载尚不存在，用无参（即强生物识别）。
        @Suppress("DEPRECATION")
        manager.canAuthenticate()
    }
}
