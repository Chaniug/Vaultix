/*
 * Vaultix — core:datastore
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 库超时（自动锁定）档位模型。
 *
 * 逐句对齐 Bitwarden Android 官方客户端
 * `data/platform/repository/model/VaultTimeout.kt`（GPL-3.0，Copyright Bitwarden Inc.），
 * 按 Vaultix 的持久化方式重写 `toStorageValue` / `fromStorageValue` 两个映射函数。
 *
 * 为什么不再用裸 `Int`（2026-09-11 重写）：
 *  旧实现 `autoLockMinutes: Flow<Int>` 用「负数=从不 / 0=立即 / N=分钟」这套**口头约定**，
 *  既没法表达 Bitwarden 的「重启时锁定（OnAppRestart）」档位，也没法承载
 *  「本次前台切换是否由 autofill 造成」这类需要结构化表达的信息。档位一旦变成 sealed
 *  class，`when` 分支就是穷尽的，编译器会挡住漏改（旧写法漏一个分支只会在运行时静默走 else）。
 *
 * ⚠️ **为什么放在 core:datastore 而不是 app:security**：本模型需要被
 * [VaultixPreferences]（core:datastore，叶子模块，不依赖任何项目模块）直接读写，
 * 同时又要被 app 层的 `VaultLockManager` 消费。放在 core:datastore 是唯一不引入
 * 反向依赖的位置；它是纯 Kotlin 数据模型，不含 Android 依赖。
 */

package io.vaultix.datastore

/**
 * 单个库的自动锁定档位。
 *
 * [vaultTimeoutInMinutes] 语义（与 Bitwarden 一致）：
 * - `null`：永不自动锁定（[Never]）；
 * - `0`：立即（[Immediately]）；
 * - `> 0`：空闲多少分钟后锁定；
 * - `-1`：重启时锁定（[OnAppRestart]）——**注意这是 Bitwarden 的取值约定**，
 *   Vaultix 沿用它以保证与上游语义一一对应（见 [toStorageValue] 的注释）。
 */
sealed class VaultTimeout {

    /** 档位类型（供 UI 与日志做可读判定，避免 `when` 里比较对象）。 */
    abstract val type: Type

    /** 空闲分钟数；`null` 表示永不锁定。 */
    abstract val vaultTimeoutInMinutes: Int?

    /** 立即超时（切后台即锁）。 */
    data object Immediately : VaultTimeout() {
        override val type: Type get() = Type.IMMEDIATELY
        override val vaultTimeoutInMinutes: Int get() = 0
    }

    /** 1 分钟。 */
    data object OneMinute : VaultTimeout() {
        override val type: Type get() = Type.ONE_MINUTE
        override val vaultTimeoutInMinutes: Int get() = 1
    }

    /** 5 分钟（默认档位）。 */
    data object FiveMinutes : VaultTimeout() {
        override val type: Type get() = Type.FIVE_MINUTES
        override val vaultTimeoutInMinutes: Int get() = 5
    }

    /** 15 分钟。 */
    data object FifteenMinutes : VaultTimeout() {
        override val type: Type get() = Type.FIFTEEN_MINUTES
        override val vaultTimeoutInMinutes: Int get() = 15
    }

    /** 30 分钟。 */
    data object ThirtyMinutes : VaultTimeout() {
        override val type: Type get() = Type.THIRTY_MINUTES
        override val vaultTimeoutInMinutes: Int get() = 30
    }

    /** 1 小时。 */
    data object OneHour : VaultTimeout() {
        override val type: Type get() = Type.ONE_HOUR
        override val vaultTimeoutInMinutes: Int get() = 60
    }

    /** 4 小时。 */
    data object FourHours : VaultTimeout() {
        override val type: Type get() = Type.FOUR_HOURS
        override val vaultTimeoutInMinutes: Int get() = 240
    }

    /**
     * 重启 App 时锁定（切后台不锁、进程重建才锁）。
     *
     * ⚠️ 语义细节（对齐 Bitwarden `VaultLockManagerImpl.checkForVaultTimeout`）：
     * 本档位**只在** `CheckTimeoutReason.AppCreated` 时触发，且当
     * `createdForAutofill == true` 时（**非首次**创建进程）会被**豁免**——
     * 这正是「autofill / 凭据提供商拉起我方进程不该把库锁掉」的**结构性**保障，
     * 取代了旧版 `CredentialFlowGuard` 那种靠时间戳窗口的近似判断。
     */
    data object OnAppRestart : VaultTimeout() {
        override val type: Type get() = Type.ON_APP_RESTART
        override val vaultTimeoutInMinutes: Int get() = -1
    }

    /** 永不自动锁定（只手动锁定 / 进程死亡导致的内存清零）。 */
    data object Never : VaultTimeout() {
        override val type: Type get() = Type.NEVER
        override val vaultTimeoutInMinutes: Int? get() = null
    }

    /** 自定义分钟数（必须为正）。 */
    data class Custom(
        override val vaultTimeoutInMinutes: Int,
    ) : VaultTimeout() {
        override val type: Type get() = Type.CUSTOM

        init {
            require(vaultTimeoutInMinutes > 0) {
                "Custom 档位必须为正分钟数，实际：$vaultTimeoutInMinutes"
            }
        }
    }

    /** 档位类型枚举（与 Bitwarden `VaultTimeout.Type` 一一对应）。 */
    enum class Type {
        IMMEDIATELY,
        ONE_MINUTE,
        FIVE_MINUTES,
        FIFTEEN_MINUTES,
        THIRTY_MINUTES,
        ONE_HOUR,
        FOUR_HOURS,
        ON_APP_RESTART,
        NEVER,
        CUSTOM,
    }

    companion object {

        /** 默认档位（旧实现 `DEFAULT_AUTO_LOCK_MINUTES = 5` 的等价物）。 */
        val DEFAULT: VaultTimeout = FiveMinutes

        /**
         * 持久化编码：写入 DataStore 的整数。
         *
         * 取值约定（**刻意与 Bitwarden 的 `vaultTimeoutInMinutes` 保持同构**，
         * 但把 `Never` 与 `OnAppRestart` 分开，因为旧数据里 `-1` 曾被用来表示 `Never`）：
         * - `Never`        → `-2`
         * - `OnAppRestart` → `-1`（Bitwarden 原值）
         * - `Immediately`  → `0`
         * - 其余正数        → 分钟数
         */
        fun toStorageValue(value: VaultTimeout): Int = when (value) {
            Never -> STORAGE_NEVER
            OnAppRestart -> STORAGE_ON_APP_RESTART
            else -> value.vaultTimeoutInMinutes ?: STORAGE_NEVER
        }

        /**
         * 反序列化：存储整数 → 档位。
         *
         * 已知分钟数会还原成对应的 data object（而不是 `Custom`），这样 UI 的单选状态、
         * `==` 比较与来回映射都是稳定双射（避免 `Custom(5) != FiveMinutes` 这类"看起来一样
         * 但不相等"的坑）。
         */
        fun fromStorageValue(value: Int): VaultTimeout = when (value) {
            STORAGE_NEVER -> Never
            STORAGE_ON_APP_RESTART -> OnAppRestart
            0 -> Immediately
            1 -> OneMinute
            5 -> FiveMinutes
            15 -> FifteenMinutes
            30 -> ThirtyMinutes
            60 -> OneHour
            240 -> FourHours
            else -> if (value > 0) Custom(value) else DEFAULT
        }

        /**
         * 旧版档位（裸 `Int` 分钟，`-1`=从不）→ 新模型的一次性迁移映射。
         *
         * ⚠️ **这是本次改动最危险的回归点**：旧语义下 `-1` 是「从不锁定」，
         * 而新模型里 `-1`（[OnAppRestart]）是「重启时锁定」——**语义正好相反**。
         * 若不迁移，用户明确选择的「永不锁定」会被静默改成「重启即锁」。
         *
         * 映射表：
         * - 旧 `< 0`（旧『从不』）→ [Never]
         * - 旧 `0`（旧『立即』）   → [Immediately]
         * - 旧 `> 0`               → 同值档位（已知值还原为 data object，未知值为 [Custom]）
         */
        fun fromLegacyMinutes(minutes: Int): VaultTimeout = when {
            minutes < 0 -> Never
            else -> fromStorageValue(minutes)
        }

        /** `Never` 的存储值。 */
        const val STORAGE_NEVER: Int = -2

        /** `OnAppRestart` 的存储值（与 Bitwarden 一致）。 */
        const val STORAGE_ON_APP_RESTART: Int = -1
    }
}
