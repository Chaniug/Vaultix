/*
 * Vaultix — core:datastore
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **首次启动引导**的两个偏好位（2026-10-24 新增）。
 *
 * ## 为什么单独一个类，而不是塞进 `VaultixPreferences`
 *
 * `VaultixPreferences` 当前已有 **35** 个函数，detekt `TooManyFunctions` 的单类硬上限是 40。
 * 本轮如果直接往里加（读 + 写 × 2 组 = 4 个函数）就顶到 39，**下一次任何改动都会越线**。
 * 房内纪律（该类 KDoc 与 `.ai/conventions/8.6` §141）写得很清楚：
 * **顶到上限是「该抽类了」的信号，不是「该压注释 / 加 `@Suppress`」的信号。**
 * 于是照 `VaultTimeoutPreferences` 的先例，这"一族"（引导流程）先立自己的类。
 *
 * 依赖面也更准：只有根导航与引导页关心这两件事，它们不该拿到整个偏好门面。
 *
 * ## 为什么要"看过"这个标记，而不是「判断有没有库」
 *
 * 无库时展示引导，这句话反过来说不成立：
 * - 老用户把库删光了 ⇒ 无库，但他看过引导，**不该再看一遍**；
 * - 用户看完引导后故意先不建库（想先翻翻设置）⇒ 无库，同样不该被反复弹。
 *
 * 所以判断条件是 **「无库」且「没看过」**，两者缺一不可。"看过"这件事只能靠标记，
 * 不能从库的状态里推。
 */
@Singleton
class OnboardingPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private companion object {
        /**
         * 引导是否**已经被看过**（看完最后一页，或点了跳过 —— 两者等价）。
         *
         * ⚠️ 语义是 **`true` = 看过**，默认 `false`。不要反写成 `showOnboarding`：
         * 那种写法在"将来想给老用户补一次引导"时要改默认值，而 `defaultValue` 的改动
         * 对**已经有键的用户**无效 —— 一个看名字像开关、实际改了没反应的键，是最坏的一种。
         */
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")

        /**
         * 「自动填充设置」这一步是否**已经被询问过**（引导第 2 页的那个可选动作）。
         *
         * 与 [ONBOARDING_SEEN] 分开存，理由：用户可能点「跳过」直接出去了，
         * 那么"自动填充要不要配"这件事**根本还没问过**，下次首启（他删光了库）
         * 仍应把这一页给他看；反之他进过一次系统自动填充页，就不该再被送去第二次。
         */
        val FILL_SETUP_PROMPTED = booleanPreferencesKey("onboarding_fill_setup_prompted")
    }

    private val safeData: Flow<Preferences> = dataStore.safePreferencesFlow()

    /** 是否已看过引导。默认 `false` = 全新安装。 */
    val onboardingSeen: Flow<Boolean> =
        safeData.map { it[ONBOARDING_SEEN] ?: false }

    /** 「去开启自动填充」这一步是否已被询问过。默认 `false`。 */
    val fillSetupPrompted: Flow<Boolean> =
        safeData.map { it[FILL_SETUP_PROMPTED] ?: false }

    /**
     * 标记引导已看过。
     *
     * ⚠️ 只写 `true`，**不提供"重置"**：重置入口属于隐藏的调试功能，
     * 露出来就是一个"可能让用户莫名其妙又看到一遍引导"的按钮。
     * 真要重看，清应用数据即可 —— 那本来就是"全新安装"的定义。
     */
    suspend fun markOnboardingSeen() {
        dataStore.edit { it[ONBOARDING_SEEN] = true }
    }

    /** 标记「自动填充」这一步已被询问过（无论用户是去了系统页还是选择稍后）。 */
    suspend fun markFillSetupPrompted() {
        dataStore.edit { it[FILL_SETUP_PROMPTED] = true }
    }
}
