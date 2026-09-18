/*
 * Vaultix — app:ui · permissions (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.permissions

import android.Manifest
import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限状态映射的单测。
 *
 * ## 为什么这批用例值得写
 *
 * [PermissionStatusChecker] 是纯 JVM 可测的 —— 它的三个依赖都是函数式接口
 * （`(String) -> Boolean` / `() -> Boolean` / [BiometricCapability]），
 * 所以**不需要模拟器**就能把三态映射全部跑一遍。这正是当初把它从 Composable 里
 * 拆出来的理由（见该类 KDoc）。
 *
 * ## 这些用例锁住的是**用户看到的那句话**，不是实现
 *
 * 三态各自会渲染成完全不同的东西（`Granted` = 「已允许」陈述 / `Denied` = 「授予·去设置」
 * 按钮 / `Unavailable` = 「设备不支持」且**无按钮**）。映射错了不会崩，
 * 只会让用户对着一个点不动的按钮发呆，或者被要一个他根本给不了的权限 ——
 * **静默的错误**，只能靠用例钉死。
 */
class PermissionStatusCheckerTest {

    /** 造一个检查器。默认：有相机、权限全拒、生物识别可用。 */
    private fun checker(
        granted: Set<String> = emptySet(),
        hasCamera: Boolean = true,
        biometricCode: Int = BiometricManager.BIOMETRIC_SUCCESS,
    ) = PermissionStatusChecker(
        permissionGranted = { it in granted },
        hasCamera = { hasCamera },
        biometric = { biometricCode },
    )

    private fun List<PermissionEntry>.byId(id: String): PermissionEntry =
        first { it.id == id }

    // ---------------------------------------------------------------- 结构

    @Test
    fun `条目顺序即页面展示顺序`() {
        val ids = checker().entries().map { it.id }
        // 顺序是产品决定（相机最常用 → 通知 → 指纹 → 网络是陈述性收尾），
        // 不是数据结构副产品：有人改动列表顺序时应当被这条拦下。
        assertEquals(
            listOf(
                PermissionIds.CAMERA,
                PermissionIds.NOTIFICATIONS,
                PermissionIds.BIOMETRICS,
                PermissionIds.NETWORK,
            ),
            ids,
        )
    }

    @Test
    fun `只列出真正用得到的四项`() {
        // NFC / USE_FINGERPRINT / DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION 都在合并清单里，
        // 但它们不是 Vaultix 自己要的（详见 PermissionStatusChecker KDoc）。
        // 这条用例防的是「照着合并清单一把梭」的回归。
        assertEquals(4, checker().entries().size)
    }

    // ---------------------------------------------------------------- 相机

    @Test
    fun `相机被拒时是可申请的未授予`() {
        val camera = checker(granted = emptySet()).entries().byId(PermissionIds.CAMERA)
        assertEquals(PermissionState.Denied, camera.state)
        // runtimePermission 非空 ⇒ 界面会给「授予」按钮（能就地弹系统框），
        // 而不是把人赶去系统设置。
        assertEquals(Manifest.permission.CAMERA, camera.runtimePermission)
        assertTrue(camera.canRequestInApp)
    }

    @Test
    fun `相机已授予`() {
        val camera = checker(granted = setOf(Manifest.permission.CAMERA))
            .entries().byId(PermissionIds.CAMERA)
        assertEquals(PermissionState.Granted, camera.state)
    }

    @Test
    fun `没有摄像头硬件时优先显示设备不支持`() {
        // ⚠️ 关键：即便权限**已经授予**，没有硬件也该显示 Unavailable ——
        // 否则界面会告诉用户"相机已允许"，而扫码功能其实永远用不了。
        val camera = checker(
            granted = setOf(Manifest.permission.CAMERA),
            hasCamera = false,
        ).entries().byId(PermissionIds.CAMERA)
        assertEquals(PermissionState.Unavailable, camera.state)
    }

    // ---------------------------------------------------------------- 通知

    /**
     * ⚠️ 这两条用例跑的是**低版本分支**，这不是巧合。
     *
     * [PermissionStatusChecker] 用 `Build.VERSION.SDK_INT` 比较
     * `Build.VERSION_CODES.TIRAMISU`（33）来决定通知这项怎么写。纯粹 JVM 单测里
     * `Build.VERSION.SDK_INT` 读到的是桩值 `0`（`unitTests.isReturnDefaultValues = true`）
     * ⇒ 恒定走 `< TIRAMISU` 分支。**能测到的只有这一支**，
     * Android 13+ 的那一支得靠真机验收覆盖（`.ai/SESSION-2026-09-18.md` 的 R1–R3）。
     * 与其假装测了、不如把"哪一支被覆盖了"写清楚。
     */
    @Test
    fun `Android 13 以下通知视为已允许且不给运行时权限`() {
        val entry = checker(granted = emptySet()).entries().byId(PermissionIds.NOTIFICATIONS)
        // 低版本系统没有 POST_NOTIFICATIONS 这个运行时权限（默认允许）⇒ 不该显示成
        // "未授予"，否则用户会对着一个**点不动的「去开启」**发呆。
        assertEquals(PermissionState.Granted, entry.state)
        assertNull(entry.runtimePermission)
        assertFalse(entry.canRequestInApp)
    }

    // ---------------------------------------------------------------- 生物识别

    @Test
    fun `生物识别可用`() {
        val entry = checker(biometricCode = BiometricManager.BIOMETRIC_SUCCESS)
            .entries().byId(PermissionIds.BIOMETRICS)
        assertEquals(PermissionState.Granted, entry.state)
    }

    @Test
    fun `有硬件但没录入指纹时算未授予且没有运行时权限`() {
        val entry = checker(biometricCode = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
            .entries().byId(PermissionIds.BIOMETRICS)
        assertEquals(PermissionState.Denied, entry.state)
        // ⚠️ USE_BIOMETRIC 是普通权限，安装即授予 —— 用户能做的只有"去系统里录一个"。
        // 若这里给出 runtimePermission，界面会弹一个**永远弹不出结果的授权框**。
        assertNull(entry.runtimePermission)
        assertFalse(entry.canRequestInApp)
    }

    @Test
    fun `没有生物识别硬件时算设备不支持`() {
        val entry = checker(biometricCode = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
            .entries().byId(PermissionIds.BIOMETRICS)
        assertEquals(PermissionState.Unavailable, entry.state)
        assertNull(entry.runtimePermission)
    }

    @Test
    fun `硬件暂不可用也算设备不支持`() {
        val entry = checker(biometricCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
            .entries().byId(PermissionIds.BIOMETRICS)
        assertEquals(PermissionState.Unavailable, entry.state)
    }

    // ---------------------------------------------------------------- 网络

    @Test
    fun `网络恒为已授予且不给运行时权限`() {
        // 用户无法单独关掉 INTERNET（要断网只能走系统网络开关）。
        // 列出来是为了回答"这 App 联网吗、联去干什么" —— 是**陈述**，不是待办。
        // 一旦这里变成 Denied，界面就会挂一个点不动的按钮。
        val entry = checker(granted = emptySet()).entries().byId(PermissionIds.NETWORK)
        assertEquals(PermissionState.Granted, entry.state)
        assertNull(entry.runtimePermission)
    }

    // ---------------------------------------------------------------- 汇总口径

    @Test
    fun `待办计数只数 Denied 不数 Unavailable`() {
        // ⚠️ 这条是状态卡"有 N 项待允许"的口径（PermissionsScreen 里
        // `entries.count { it.state == PermissionState.Denied }`）。
        // 把 Unavailable 也算进去 ⇒ 没有指纹的设备会永远显示"有 1 项待允许"，
        // 而那一项**用户无论怎么做都消不掉** —— 页面从此永远无法到达"一切就绪"。
        //
        // 本用例在纯 JVM 下跑（`SDK_INT = 0`）⇒ 通知项走低版本分支恒为 Granted，
        // 于是「未授予」只剩相机一项，「设备不支持」是相机（无硬件）+ 指纹（无硬件）。
        val entries = checker(
            granted = emptySet(),
            hasCamera = false,
            biometricCode = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        ).entries()

        assertEquals(0, entries.count { it.state == PermissionState.Denied })
        assertEquals(2, entries.count { it.state == PermissionState.Unavailable })
    }

    @Test
    fun `有相机且权限被拒时计一项待办`() {
        // 上一条的对照：补上"确实会数出 Denied"的正例，
        // 否则 `count == 0` 有可能是"计数逻辑根本没生效"造成的假绿。
        val entries = checker(granted = emptySet(), hasCamera = true).entries()
        assertEquals(1, entries.count { it.state == PermissionState.Denied })
    }
}
