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
 * [VaultTimeoutPreferences]（core:datastore，叶子模块，不依赖任何项目模块）直接读写，
 * 同时又要被 app 层的 `VaultLockManager` 消费。放在 core:datastore 是唯一不引入
 * 反向依赖的位置；它是纯 Kotlin 数据模型，不含 Android 依赖。
 */

package io.vaultix.datastore

/**
 * 自动锁定档位。
 *
 * ## 作用域：**全局默认 + 每库可覆盖**（2026-09-30 傍晚定稿，推翻 D3 的"每库一份"）
 *
 * - **全局默认** = `vault_timeout` 键（`VaultTimeoutPreferences.globalVaultTimeout()`），
 *   入口 = 设置首页「自动锁定」行；
 * - **每库覆盖** = `vault_timeout::<vaultId>` 键（`vaultTimeout(vaultId)` / `setVaultTimeout`），
 *   入口 = 库管理页该库的 ⋮（第一项「跟随全局设置」= 删除覆盖）；
 * - 回退链：覆盖 → 全局键 → 更旧的 `auto_lock_minutes`（按其旧语义转换）→ [DEFAULT]。
 *
 * 理由与逐一取证见 `.ai/decisions/多库锁模型-定稿.md` §9（关键结论：主流密码管理器
 * "一个设置全局生效"的前提是**一次只有一个解锁对象**，而 Vaultix 是异构双后端同时解锁）。
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

    /**
     * 系统锁屏时锁定（**设备真正上锁**才锁）。
     *
     * ## 来自上游的新档位（2026-10-30 补）
     *
     * Bitwarden 在 **2025.11.0** 新增了 `On system lock` / `On app restart` / `Never`
     * 三个档位（先只在 Android 客户端生效，2025.12.1 起全客户端）。官方描述：
     *
     * > On system lock: When the device is locked or the screensaver activates.
     *
     * 本类此前只补了后两个 —— 因为 `.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`
     * 里「❌ 仍不做：锁屏即锁档位」的依据是"对齐 Bitwarden，上游没有这一档"。
     * **该依据已被上游自己推翻**（见该定稿 2026-10-30 的勘误段）。
     * 用户原话也是两句、对应两档：「app 清掉后台或者**锁屏**后，指纹解锁是正确的方案」。
     *
     * ## 语义边界（**只锁这一件事**，别扩大）
     *
     * - ✅ 设备进入锁屏（`KeyguardManager.isDeviceLocked == true`）→ 立即锁；
     * - ❌ 切后台**不锁**（这是本档位与 [Immediately] 的全部区别，也是它的已知弱点：
     *   手机解锁着被别人拿到，切回 App 仍是开着的 —— 那正是用户选择更严档位的场景）；
     * - ❌ 进程创建**不锁**（进程都没了，库本来就是锁的，再"锁"一次只是噪音）；
     * - ❌ 熄屏但设备没锁（未设锁屏密码）→ **不锁**，因为系统层面根本没有"锁"发生。
     *
     * ⚠️ **无锁屏密码的设备上本档位永远不会触发**。这一点必须在 UI 上说出来
     * （见 `auto_lock_on_system_lock_note`），否则就会造出一个"选了但永远没效果"的
     * 档位 —— 与 #93「谎报状态的开关」是同一族问题。
     */
    data object OnSystemLock : VaultTimeout() {
        override val type: Type get() = Type.ON_SYSTEM_LOCK

        /** `-3`（见 [STORAGE_ON_SYSTEM_LOCK] 的取值说明）。 */
        override val vaultTimeoutInMinutes: Int get() = STORAGE_ON_SYSTEM_LOCK
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
        ON_SYSTEM_LOCK,
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
            OnSystemLock -> STORAGE_ON_SYSTEM_LOCK
            else -> value.vaultTimeoutInMinutes ?: STORAGE_NEVER
        }

        /**
         * 已知预设档位的「分钟数 → 档位」查找表。
         *
         * 用各 data object 自身的 [vaultTimeoutInMinutes] 作键，避免在 [fromStorageValue]
         * 里重复写死魔法数字（detekt [MagicNumber] 门禁会拦截）；新增预设档位时只要把它加进
         * 这个列表就会被自动识别，无需再改 [fromStorageValue] 的分支。
         *
         * ⚠️ **必须是 `by lazy`，不能改回急切 `val`**（2026-09-29 修，issues **#129**）。
         *
         * 急切实现在 `VaultTimeout.<clinit>` **执行期间**就会调用
         * `timeout.vaultTimeoutInMinutes`；而该 getter 会引用嵌套的 [Type] 枚举
         * ⇒ 构成**环形静态初始化**（`VaultTimeout` ⇄ `Type` ⇄ 各 data object，
         * 谁的类加载先触发、走到哪一步，决定成败）。
         *
         * 实测后果：`VaultLockManagerImplNeverTest` / `AutoRestoreTriggerTest`
         * **确定性**抛 `ExceptionInInitializerError: NullPointerException`，
         * 其后所有引用退化为
         * `NoClassDefFoundError: Could not initialize class VaultTimeout`
         * —— 而报错信息完全指向错误方向（看着像 datastore 模块坏了）。
         *
         * 改 `by lazy` 后推迟到首次 [fromStorageValue] 调用才构建，此时所有类
         * 均已初始化完毕。语义不变（首次访问构建一次 + 缓存），默认线程安全。
         */
        private val KNOWN_BY_MINUTES: Map<Int, VaultTimeout> by lazy {
            listOf(
                Immediately, OneMinute, FiveMinutes, FifteenMinutes,
                ThirtyMinutes, OneHour, FourHours,
            ).mapNotNull { timeout -> timeout.vaultTimeoutInMinutes?.let { it to timeout } }.toMap()
        }

        /**
         * 反序列化：存储整数 → 档位。
         *
         * 已知分钟数会还原成对应的 data object（而不是 `Custom`），这样 UI 的单选状态、
         * `==` 比较与来回映射都是稳定双射（避免 `Custom(5) != FiveMinutes` 这类"看起来一样
         * 但不相等"的坑）。
         *
         * 负数档位的判定顺序**有意写死在前面**（[STORAGE_NEVER] → [STORAGE_ON_APP_RESTART]
         * → [STORAGE_ON_SYSTEM_LOCK]），没有塞进 [KNOWN_BY_MINUTES]：那张表按
         * `vaultTimeoutInMinutes` 建构，把负数也放进去依赖"不会有人返回 null 当键"，
         * 而 `Never.vaultTimeoutInMinutes` 恰好是 null —— 一旦有人改了那个返回值，
         * 负数会被静默吞掉。显式分支不会。
         */
        fun fromStorageValue(value: Int): VaultTimeout {
            if (value == STORAGE_NEVER) return Never
            if (value == STORAGE_ON_APP_RESTART) return OnAppRestart
            if (value == STORAGE_ON_SYSTEM_LOCK) return OnSystemLock
            KNOWN_BY_MINUTES[value]?.let { return it }
            return if (value > 0) Custom(value) else DEFAULT
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

        /**
         * [OnSystemLock] 的存储值。
         *
         * ⚠️ **`-3` 是本项目的取值，不是 Bitwarden 的原值**。上游用
         * `vaultTimeoutInMinutes = -1` 表示 `OnAppRestart`、另有一个枚举区分
         * `OnSystemLock`；而本项目的 `-1`/`-2` 已被 [OnAppRestart] / [Never] 占住
         * （`-2` 是因为**旧数据里 `-1` 曾是「从不」**，见 [fromLegacyMinutes]），
         * 所以只能取 `-3`。语义与上游一致，数值刻意不同 —— **别照抄上游的 -2**，
         * 那会读成 `Never`。
         *
         * ⚠️ 负数档位有个共同陷阱：`vaultTimeoutInMinutes` 被 `* 60_000` 之前若只做
         * `coerceAtLeast(0)`，`-3` 会变成 **0 分钟 = 立即锁**。所以
         * `VaultLockManagerImpl.checkForVaultTimeoutInternal` 必须给本档位**显式分支**，
         * 不能让它落进 `else`（那里是按分钟数起定时器的）。
         */
        const val STORAGE_ON_SYSTEM_LOCK: Int = -3
    }
}
