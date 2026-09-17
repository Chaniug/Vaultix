/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import io.vaultix.data.repository.LocalUnlockEnrollment
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.LocalUnlockEnrollOutcome
import io.vaultix.domain.LocalUnlockPrepareOutcome
import io.vaultix.domain.LocalUnlockPreparedEnrollment
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.domain.PinEnrollOutcome
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「快速解锁」的**能力级**设置交互（2026-09-16 重构）。
 *
 * ## 为什么重写（这次重构的正当性）
 *
 * 旧实现把快速解锁做成了**每库独占的三选一**（指纹 / PIN / 主密码）。那是基于
 * 「每个库单独选一种方式」的假设 —— 与用户原本的意图相反。用户原话：
 *
 * > 「两种并列可选，且这两个设置应该覆盖多个库，多个库都能用这一套逻辑解锁。」
 *
 * 而更早的定稿（`库选择与快速解锁-逻辑定稿.md` §4.7）其实早就写过：
 * > 「增加一个快速解锁的**生效范围**，可以选取哪些库生效。」
 *
 * ⇒ 正确的心智模型是：**快速解锁是一个能力，作用于一批库**，
 *   而不是一道"每库各选一种"的判断题。本类即该模型。
 *
 * ## ★ 真源：范围持久化，开关**推导**（本类最重要的一条）
 *
 * | 状态 | 存在哪 |
 * |---|---|
 * | 每库「指纹信封已建」 | `preferences.isLocalUnlockEnabled(vaultId)` |
 * | 每库「PIN 信封已建」 | `preferences.isPinUnlockEnabled(vaultId)` |
 * | **生效范围**（哪些库纳入） | `preferences.quickUnlockScope()` |
 * | **范围是否已被用户确认** | `preferences.isQuickUnlockScopeConfirmed()` |
 *
 * **刻意不持久化「总开关」**：开关的 ON/OFF 由「范围 + 每库信封」推导。
 * 若另存一个开关字段，就会出现"开关说开着、信封却是空的"这种双源漂移 ——
 * 那正是 issue #93「谎报状态的开关」的成因，别再造一个。
 *
 * ### 为什么「范围」要配一个独立的"已确认"标记（2026-09-17 新增）
 *
 * 需求是「**默认全勾**」——用户 99% 想要"所有库都能快速解锁"，逐个勾选是纯负担。
 * 但"默认全勾"**不能**用范围为空来表达：空集在这里已经有确定含义「一个库都不要」。
 * 一个值背两种含义，后果是**用户主动全部取消勾选之后，界面反过来告诉他"全都勾上了"**。
 * 这正是「空有三态」那条纪律要防的塌缩（同族：读路径解密失败被渲染成空列表）。
 *
 * ⇒ 拆成两个事实：
 * - `confirmed = false`（从未配置）⇒ **所有库都在范围内**，向导默认全勾；
 * - `confirmed = true` ⇒ 范围就是存储值，空集如实表示"一个都不要"。
 *
 * 这个拆法还顺带修掉一个新库的坑：新加的库在"未确认"阶段天然就在范围内，
 * 不必等用户回来手动勾一次。
 *
 * 推导规则见 [derive]：`On` / `Partial(n)` / `Off` 三态。
 * ⚠️ `Partial` 与 `Off` 必须分开：用户主动关闭后应看到 `Off`，
 * 而不是被"有 N 个库未完成"误导成"我是不是没配完"。
 *
 * ## 两个能力并列，互不干扰
 *
 * 指纹与 PIN 是**两种并列手段**，可同时启用、各有各的信封。**不做任何互斥** ——
 * 旧实现那套「选指纹就关 PIN」正是本次要纠正的错误（它还会造成"用户中途取消后
 * 指纹没了、PIN 也没设成"的静默数据丢失）。
 *
 * ⚠️「互不干扰」说的是**开关与信封**，不包括配置流程 —— 两者走**同一个向导**（见下）。
 *
 * ## ★ 一次流程配完两种方式（2026-09-17；用户："逻辑很麻烦、操作很复杂"）
 *
 * 旧交互 =「逐库一行 + 两步对话框 + 每种方式各跑一遍」。其中重复的 N 次**不是平白多出来的**，
 * 来源是可查的：**KDBX 的主密码两种方式都要用** ——
 *
 * ```
 * enrollment.prepareForVaults(targets) { id -> passwordFor(id) }             // 指纹要
 * vaultRepository.enrollPinForVaults(targets, pin) { id -> passwordFor(id) } // PIN 也要
 * ```
 *
 * ⇒ 想同时开指纹与 PIN，**每个 KDBX 库的主密码要输两遍**。所以把两者合进**一次**流程
 * （主密码只收一次、两种方式共用）才是真正砍掉那个 N 的做法；
 * 只把"选库"那一步缩短，N 一点都不会少。
 *
 * 于是 [Dialog.Configure] 成为**唯一**的配置入口：一次问清「对哪些库 + 用哪些方式」，
 * 随后是 `主密码 →（PIN 当场落盘）→（指纹备料 + 一次认证）→ 结果`。
 *
 * ## 逐库问主密码（用户 2026-09-16 拍板）
 *
 * KDBX 的信封里必须躺「主密码 + keyfile」，而 KDBX 会话里**没有**主密码
 * （`KdbxSession` 只持整库明文，`Kdbx.unlock()` 用完即弃）。多个 KDBX 库的主密码
 * **可以各不相同** ⇒ 只能**逐个库**去问。每个框都有「跳过」出口：
 * 用户可能确实不知道某个库的密码，不该被一个库卡死整批登记。
 *
 * ## 顺序是硬约束（沿用旧实现，别绕回去）
 *
 * 快解的保护器 KEK 是 **auth-per-use**：只有被 `BiometricPrompt` 授权过的那**一个**
 * `Cipher` 实例才能 `doFinal`。所以必须：
 * **备料全部在认证之前做完 → 弹一次指纹 → 用同一个 cipher 连续 wrap 完**。
 * 绝不能"每库各准备一个 cipher"或"先 wrap 再弹指纹" —— 那正是 2026-09-14 闪退的根因
 * （`UserNotAuthenticatedException`，且当时无人捕获 ⇒ 进程退出）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickUnlockController(
    private val vaultRepository: VaultRepository,
    /**
     * 多库备料器。
     *
     * ⚠️ 直接注入具体实现而非走 [VaultRepository] 接口：多库备料入口
     * （`prepareForVaults`）故意**不在**接口上，否则 `VaultRepositoryImpl` 会突破
     * detekt `TooManyFunctions` 的 40 上限（它本就顶格）。
     */
    private val enrollment: LocalUnlockEnrollment,
    /**
     * 生效范围的存在处。
     *
     * ⚠️ 范围读写走偏好、**不走仓储**：`VaultRepositoryImpl` 函数数已顶格 40，
     * 再加一个方法就违规；而且"范围"本身是用户偏好，不是"库"的领域概念。
     */
    private val preferences: VaultixPreferences,
    private val scope: CoroutineScope,
) {

    /** 两种并列的解锁手段。 */
    enum class UnlockMethod { BIOMETRIC, PIN }

    /**
     * 一个「能力」的当前状态（**推导**得出，不是独立存储的开关）。
     *
     * ⚠️ 见类 KDoc：`Partial` 与 `Off` 的区分是关键 —— 前者要说"还差几个"，
     * 后者就是"没启用"。混成一体会让用户以为自己的操作没生效。
     */
    sealed interface CapabilityState {
        /** 范围内一个都没建（含范围为空 ⇒ 从未启用）。 */
        data object Off : CapabilityState

        /** 范围内已有建好的，但还差 [pending] 个（登记失败 / 新勾进来的库）。 */
        data class Partial(val pending: Int) : CapabilityState

        /** 范围内全部建好。 */
        data object On : CapabilityState
    }

    /** 范围列表里的一行（一个库）。 */
    data class VaultUi(
        val vaultId: String,
        val name: String,
        val kind: VaultKind,
        /** 是否在生效范围内（用户勾选）。 */
        val inScope: Boolean,
        /** 指纹信封是否已建。 */
        val biometricReady: Boolean,
        /** PIN 信封是否已建。 */
        val pinReady: Boolean,
    ) {
        /** 登记时是否需要问这个库的主密码（只有 KDBX 需要）。 */
        val needsMasterPassword: Boolean get() = kind == VaultKind.KDBX
    }

    /** 对话框/流程的当前步骤。 */
    sealed interface Dialog {
        data object Idle : Dialog

        /**
         * ★ **配置向导的第一步（也是唯一入口）**：一次问清「对哪些库 + 用哪些方式」。
         *
         * 取代了旧的两个入口（对话框里逐库勾选范围 + 两个开关各自触发一遍流程）。
         * 设计要点：
         * - **默认全勾**（首次配置时）—— 见类 KDoc 的"已确认"标记；
         * - 方式可多选 —— 主密码只收一次、两种方式共用（类 KDoc 里那条 N 的来源）；
         * - 取消勾选某个库**不是必须动作**，只是可选项。
         */
        data class Configure(
            val rows: List<ConfigureRow>,
            val methodBiometric: Boolean,
            val methodPin: Boolean,
            val error: String? = null,
        ) : Dialog

        /** 输 PIN（仅当选了 PIN 方式；在问主密码**之前**）。 */
        data class PinEntry(
            val pin: String = "",
            val confirm: String = "",
            val error: String? = null,
        ) : Dialog

        /**
         * 逐个库问主密码。
         *
         * @param vaultName 正在问的这个库（用户必须知道是**哪个**库的密码）
         * @param remaining 后面还有几个要问（0 = 这是最后一个）
         */
        data class KdbxPassword(
            val vaultName: String,
            val remaining: Int,
            val password: String = "",
            val error: String? = null,
        ) : Dialog

        /** 指纹认证中（备料已完成，等用户按指纹）。 */
        data object Authenticating : Dialog

        /**
         * 结果。
         *
         * 三段分开报而不是合成"成功/失败"：**跳过**是用户的选择、**失败**是出了问题，
         * 两者后续动作完全不同（前者不用管，后者要重试）。
         *
         * @param scopeOnly true = 本次**只改了范围、没有库需要登记**。单列出来是为了不把
         *   "无事可做"演成"配置成功"（三个空列表的结果页会被读成后者，属"假成功"）。
         */
        data class Report(
            val succeeded: List<String>,
            val skipped: List<String>,
            val failed: List<FailureItem>,
            val scopeOnly: Boolean = false,
        ) : Dialog
    }

    /** 结果页里单个库的失败项。 */
    data class FailureItem(val vaultName: String, val reason: String)

    /**
     * 配置向导里的一行（一个库）。
     *
     * ⚠️ 与 [VaultUi] 分开而不是复用：两者的 `inScope` 语义不同 —— [VaultUi.inScope] 是
     * **已落盘**的范围，这里是**向导里尚未提交**的勾选。混用一个类型，就会出现
     * "用户还没确认，界面已经把范围当成已生效"的谎报（#93 同族）。
     */
    data class ConfigureRow(
        val vaultId: String,
        val name: String,
        /** 是否勾选（首次配置时默认全勾）。 */
        val checked: Boolean,
        val biometricReady: Boolean,
        val pinReady: Boolean,
    )

    /** 界面需要的全部状态。 */
    data class UiState(
        val rows: List<VaultUi> = emptyList(),
        val biometric: CapabilityState = CapabilityState.Off,
        val pin: CapabilityState = CapabilityState.Off,
    )

    private val _dialog = MutableStateFlow<Dialog>(Dialog.Idle)
    val dialog: StateFlow<Dialog> = _dialog.asStateFlow()

    /**
     * 交给 UI 弹指纹的 cipher（仅 [Dialog.Authenticating] 期间有值）。
     *
     * ⚠️ 用 `StateFlow` 而不是 `Channel`：语义是"现在有一把待认证的 cipher"，
     * 而它**只对一次认证有效**。用 Channel 时若 UI 因配置变更重建、订阅晚了一帧，
     * 事件会永久滞留（或反过来被消费到一把已作废的 cipher）。
     * StateFlow 让"有没有待认证"成为可查询的状态；UI 拿到后回调 [onPromptHandled]。
     */
    private val _pendingCipher = MutableStateFlow<Cipher?>(null)
    val pendingCipher: StateFlow<Cipher?> = _pendingCipher.asStateFlow()

    /** 本次登记会话（进行中才有值）。 */
    private var session: Session? = null

    /**
     * 本次指纹认证要落盘的备料。
     *
     * ⚠️ **刻意不进 UI state**：里面有 KDBX 的**主密码明文**，进 state 意味着它会被
     * 反复比较、可能在日志/转储里出现。生命周期：写入 [executeBiometric]；
     * 消费 [onAuthenticated]（成败都清）；丢弃 [dismiss] / [onAuthenticationFailed]。
     */
    private var prepared: List<LocalUnlockPreparedEnrollment> = emptyList()

    val state: StateFlow<UiState> = composeState()

    // ===== 范围与开关 =====

    /**
     * 点「管理解锁方式」：打开向导，两种方式**都不预选**（由用户勾）。
     *
     * 与两个开关的分工：开关 = 「这个方式，对范围内的库，开关一下」的粗动作；
     * 本入口 = 精细控制（挑库、挑方式），也就是旧实现那三种入口合并后的**唯一**入口。
     */
    fun manageUnlock() {
        scope.launch { showConfigure(preferred = null) }
    }

    /** 向导里勾选 / 取消勾选一个库。 */
    fun toggleConfigureVault(vaultId: String) {
        val current = _dialog.value as? Dialog.Configure ?: return
        _dialog.value = current.copy(
            rows = current.rows.map {
                if (it.vaultId == vaultId) it.copy(checked = !it.checked) else it
            },
            error = null,
        )
    }

    /**
     * 向导里勾选 / 取消勾选一种方式。
     *
     * ⚠️ 两种方式之间**没有任何联动**：勾指纹不会顺手勾上 PIN，反之亦然
     * （旧实现「选指纹就关 PIN」那次错误的反面，别又做成一端）。
     */
    fun toggleConfigureMethod(method: UnlockMethod) {
        val current = _dialog.value as? Dialog.Configure ?: return
        _dialog.value = when (method) {
            UnlockMethod.BIOMETRIC -> current.copy(methodBiometric = !current.methodBiometric, error = null)
            UnlockMethod.PIN -> current.copy(methodPin = !current.methodPin, error = null)
        }
    }

    /**
     * 向导的「开始配置」：落范围 → 收主密码 →（PIN 当场落盘）→（指纹备料 + 一次认证）→ 结果。
     *
     * ⚠️ **取消勾选 = 移出生效范围 = 删掉它的两个信封**（沿用旧 `toggleScope` 的语义）。
     * 用户重配时要再输一次那个库的主密码 —— 所以这句后果**写在向导里**（动手之前），
     * 而不是事后弹提示：提示追不回已经删掉的东西（旧实现正是发了一条**无人消费**的提示，
     * 见类 KDoc 的"沉默的分支"一处）。
     */
    fun confirmConfigure() {
        val current = _dialog.value as? Dialog.Configure ?: return
        val checked = current.rows.filter { it.checked }.map { it.vaultId }
        val methods = buildSet {
            if (current.methodBiometric) add(UnlockMethod.BIOMETRIC)
            if (current.methodPin) add(UnlockMethod.PIN)
        }
        if (methods.isEmpty()) {
            _dialog.value = current.copy(error = "请至少选择一种解锁方式")
            return
        }
        if (checked.isEmpty()) {
            _dialog.value = current.copy(error = "请至少选择一个密码库")
            return
        }
        scope.launch {
            val snapshot = state.value
            // 从范围内移出的库：连带删掉它的两个信封 —— 否则会出现"界面说没启用、
            // 实际仍能用指纹打开"的双源不一致（谎报状态那一类）。
            snapshot.rows
                .filter { it.inScope && it.vaultId !in checked }
                .forEach { row ->
                    vaultRepository.disableLocalUnlock(row.vaultId)
                    vaultRepository.disablePin(row.vaultId)
                }
            preferences.confirmQuickUnlockScope(checked.toSet())

            val newSession = Session(
                methods = methods,
                targets = checked,
                rows = snapshot.rows.map { it.copy(inScope = it.vaultId in checked) },
            )
            session = newSession
            _dialog.value = Dialog.Idle

            if (newSession.pendingTargets().isEmpty()) {
                // 勾选的库都已经配好了 ⇒ 这次只落了范围，**如实说明**而不是演出一个
                // "配置成功"的空结果页（那正是"假成功"）。
                _dialog.value = Dialog.Report(
                    succeeded = emptyList(),
                    skipped = emptyList(),
                    failed = emptyList(),
                    scopeOnly = true,
                )
                clearSession()
                return@launch
            }
            // ⚠️ 只在**PIN 那边真有活**时才问 PIN：若勾选的库里 PIN 信封都已存在
            //    （例如只有指纹那边还有活干），弹一个输了也不生效的 PIN 输入框，
            //    正是"逻辑很麻烦"要消灭的那种多余步骤 —— 用户会以为 PIN 出了问题。
            if (UnlockMethod.PIN in methods && newSession.targetsFor(UnlockMethod.PIN).isNotEmpty()) {
                _dialog.value = Dialog.PinEntry()
            } else {
                advanceToPasswordOrExecute()
            }
        }
    }

    /**
     * 打开配置向导。
     *
     * @param preferred 预选的方式（从某个开关进来时）；`null` = 「管理解锁方式」按钮。
     *
     * ⚠️ 「从未确认过 ⇒ 默认全勾」是本轮**最大的省事点**（用户 99% 想要"所有库都能快速解锁"）。
     * 判据是 `isQuickUnlockScopeConfirmed()` 而**不是**范围是否为空 —— 见类 KDoc：
     * 空集已经表示"一个都不要"，不能借它表示"还没配过"。
     */
    private suspend fun showConfigure(preferred: UnlockMethod?) {
        val ui = state.value
        val confirmed = preferences.isQuickUnlockScopeConfirmed().first()
        val scopeIds = preferences.quickUnlockScope().first()
        val checked = if (confirmed) scopeIds else ui.rows.map { it.vaultId }.toSet()
        val biometricOn = ui.biometric is CapabilityState.On
        val pinOn = ui.pin is CapabilityState.On
        val bothOn = biometricOn && pinOn
        // 「管理解锁方式」预选"还没配好的方式"；两种都已启用时都预选 ——
        // 此时本来就没有要干的活，确认后会如实说"只更新了范围"，不会卡在一个空选择上。
        _dialog.value = Dialog.Configure(
            rows = ui.rows.map {
                ConfigureRow(
                    vaultId = it.vaultId,
                    name = it.name,
                    checked = it.vaultId in checked,
                    biometricReady = it.biometricReady,
                    pinReady = it.pinReady,
                )
            },
            methodBiometric = when (preferred) {
                UnlockMethod.BIOMETRIC -> true
                UnlockMethod.PIN -> false
                null -> bothOn || !biometricOn
            },
            methodPin = when (preferred) {
                UnlockMethod.PIN -> true
                UnlockMethod.BIOMETRIC -> false
                null -> bothOn || !pinOn
            },
        )
    }

    /**
     * 点「指纹」开关。
     *
     * - 当前 `On` ⇒ 视为**关闭**：删掉范围内所有库的指纹信封（**不动 PIN 的信封**）；
     * - `Off` / `Partial` ⇒ **打开配置向导并预选指纹**（默认全勾）。
     *
     * ⚠️ `Partial` 必须走"继续"而不是"关闭"：那正是补完剩下几个库的入口。
     * 进了向导之后，"只补没配的那几个"是自动的（`Session.targetsFor` 会跳过已有信封的库），
     * 用户不必自己判断哪些还没配。
     */
    fun toggleBiometric() {
        scope.launch {
            if (state.value.biometric is CapabilityState.On) {
                disableAll(UnlockMethod.BIOMETRIC)
            } else {
                showConfigure(UnlockMethod.BIOMETRIC)
            }
        }
    }

    /**
     * 点「应用内 PIN」开关。语义同 [toggleBiometric]。
     *
     * ⚠️ 与指纹**完全独立**：关掉这一个**不会**顺手关掉另一个（两者各有各的信封）。
     * 这是验收清单里明确列出的一条 —— 旧实现那种"选一个就关另一个"是反例。
     */
    fun togglePin() {
        scope.launch {
            if (state.value.pin is CapabilityState.On) {
                disableAll(UnlockMethod.PIN)
            } else {
                showConfigure(UnlockMethod.PIN)
            }
        }
    }

    /**
     * 从**库列表页的引导横幅**一键启用某个库的快速解锁（指纹）。
     *
     * 语义 = 「把这个库纳入范围，然后立刻为它配指纹」。
     *
     * ⚠️ 范围**尚未被确认过**时不需要写范围：那时"所有库都在范围内"（见类 KDoc 的
     * "已确认"标记），写一个只含本库的范围反而会把"默认全勾"缩成"只有这一个"。
     *
     * ⚠️ 已确认过时要**等 state 反映新范围之后**再登记：`setQuickUnlockScope` 是异步落盘，
     * 紧接着读 `state` 还是旧范围。本方法的目标是显式指定的 [vaultId]，所以等待的判据用
     * "rows 里出现了这个库"（而不是"它在范围内"）—— 后者在未确认时会一直为真，
     * 反而可能在 rows 尚为空时就推进流程。
     */
    fun enableForVault(vaultId: String) {
        scope.launch {
            if (preferences.isQuickUnlockScopeConfirmed().first()) {
                val current = preferences.quickUnlockScope().first()
                if (vaultId !in current) {
                    preferences.setQuickUnlockScope(current + vaultId)
                }
            }
            val snapshot = state
                .filter { ui -> ui.rows.any { it.vaultId == vaultId } }
                .first()
            session = Session(
                methods = setOf(UnlockMethod.BIOMETRIC),
                targets = listOf(vaultId),
                rows = snapshot.rows,
            )
            advanceToPasswordOrExecute()
        }
    }

    // ===== 流程输入 =====

    fun onPinChange(value: String) {
        val current = _dialog.value as? Dialog.PinEntry ?: return
        _dialog.value = current.copy(pin = value.onlyDigits(), error = null)
    }

    fun onPinConfirmChange(value: String) {
        val current = _dialog.value as? Dialog.PinEntry ?: return
        _dialog.value = current.copy(confirm = value.onlyDigits(), error = null)
    }

    fun onPasswordChange(value: String) {
        val current = _dialog.value as? Dialog.KdbxPassword ?: return
        _dialog.value = current.copy(password = value, error = null)
    }

    /**
     * 提交 PIN（判位数 + 两次一致）。
     *
     * 规则放控制器而不是只在 UI：UI 只负责把 `error` 画出来，判定只有一处，
     * 避免两个入口各写一遍阈值。
     */
    fun submitPin() {
        val current = _dialog.value as? Dialog.PinEntry ?: return
        if (current.pin.length != PIN_MIN_LENGTH) {
            _dialog.value = current.copy(error = "PIN 需要 $PIN_MIN_LENGTH 位数字")
            return
        }
        if (current.pin != current.confirm) {
            _dialog.value = current.copy(error = "两次输入不一致，请重新输入")
            return
        }
        session?.pin = current.pin
        advanceToPasswordOrExecute()
    }

    /** 提交当前库的主密码，进入下一个库。 */
    fun submitPassword() {
        val current = _dialog.value as? Dialog.KdbxPassword ?: return
        if (current.password.isBlank()) {
            _dialog.value = current.copy(error = "请输入主密码，或选择跳过")
            return
        }
        val sessionNow = session ?: return
        val target = sessionNow.currentTarget() ?: return
        sessionNow.passwords[target] = current.password
        advanceToPasswordOrExecute()
    }

    /**
     * 跳过当前库的主密码。
     *
     * ⚠️ 「跳过」与「输错」必须分开：跳过是**用户的选择**，不该报错、也不计入失败；
     * 它只让这个库这次不配成，用户之后可以再点开关继续（那时会重新问一次）。
     */
    fun skipCurrentVault() {
        val sessionNow = session ?: return
        val target = sessionNow.currentTarget() ?: return
        sessionNow.skipped.add(target)
        advanceToPasswordOrExecute()
    }

    /** UI 已把 cipher 交给 `BiometricPrompt` ⇒ 清掉，避免重组时重复弹。 */
    fun onPromptHandled() {
        _pendingCipher.value = null
    }

    /**
     * 指纹认证成功：用本次 cipher **连续** wrap 完所有备料。
     *
     * ⚠️ 必须连续循环、共用一个 cipher（auth-per-use，见类 KDoc）。
     * 逐库独立成败：某个库 wrap 失败只回退该库，绝不牵连其它 ——
     * 那会静默丢掉用户已经配好的部分。
     *
     * ⚠️ 结果页要**带上 PIN 段的结论**（它在此之前就已落盘）—— 见 [buildReport]。
     */
    fun onAuthenticated(cipher: Cipher) {
        _pendingCipher.value = null
        val units = prepared
        prepared = emptyList()
        val sessionNow = session
        scope.launch {
            val committed = withContext(Dispatchers.IO) {
                vaultRepository.commitLocalUnlockEnrollForVaults(units, cipher)
            }
            _dialog.value = buildReport(sessionNow, committed)
            clearSession()
        }
    }

    /**
     * 认证失败 / 被用户取消：**擦掉备料明文**并结束本次指纹流程。
     *
     * 不做"保留备料下次再试"的优化：cipher 已作废（一次认证一把），留着也没有 cipher
     * 可用；而它里面躺着 KDBX 主密码，多留一刻都是风险。
     *
     * ⚠️ 但**PIN 段可能已经落盘了**（PIN 先执行）。那时若直接回设置界面，用户会以为
     * "什么都没配成"，实际下次已经能用 PIN 打开 —— 与"看起来没配好、实际能打开"同族。
     * ⇒ 只要 PIN 段有结论，就照样出结果页，如实说明"指纹已取消、PIN 已启用"。
     */
    fun onAuthenticationFailed() {
        _pendingCipher.value = null
        clearPrepared()
        val sessionNow = session
        val pinTouched = !sessionNow?.pinOutcomes.isNullOrEmpty()
        clearSession()
        _dialog.value = if (sessionNow == null || !pinTouched) {
            Dialog.Idle
        } else {
            buildReport(
                sessionNow,
                committed = emptyMap(),
                extraFailures = listOf(FailureItem("—", "已取消指纹验证，指纹未启用")),
            )
        }
    }

    /** 用户取消对话框：清掉一切在途的敏感物。 */
    fun dismiss() {
        _pendingCipher.value = null
        clearPrepared()
        clearSession()
        _dialog.value = Dialog.Idle
    }

    // ===== 内部：登记流程 =====

    /**
     * 推进流程：还有库要问主密码就问下一个，否则执行。
     *
     * ⚠️ 只对**目标里的 KDBX 库**问密码 —— Bitwarden 包的是会话里的密钥，不需要密码，
     * 问它只会给用户一个"为什么又要输密码"的困惑。
     */
    private fun advanceToPasswordOrExecute() {
        val current = session ?: return
        val pending = current.kdbxTargets.filter { it !in current.passwords && it !in current.skipped }
        val next = pending.firstOrNull()
        if (next == null) {
            execute()
            return
        }
        _dialog.value = Dialog.KdbxPassword(
            vaultName = current.nameOf(next),
            remaining = pending.size - 1,
        )
    }

    /** 主密码收齐后真正执行。 */
    private fun execute() {
        val current = session ?: return
        scope.launch { executeSession(current) }
    }

    /**
     * 执行本次会话：**先 PIN，后指纹**。
     *
     * ⚠️ 顺序不可颠倒：PIN 是"当场落盘"（不碰系统认证），指纹要弹**一次**认证。若先弹认证、
     * 再回头收 PIN，用户会在以为已经完事之后又被要求输一次 PIN（此时界面已回到结果页）。
     *
     * ⚠️ 一段失败**不清掉另一段**：PIN 段失败不影响已备好的指纹料，反之亦然 ——
     * 那会静默丢掉用户已经配好的部分。
     */
    private suspend fun executeSession(current: Session) {
        if (UnlockMethod.PIN in current.methods) {
            val pinTargets = current.targetsFor(UnlockMethod.PIN)
            current.pinOutcomes = if (pinTargets.isEmpty()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    vaultRepository.enrollPinForVaults(pinTargets, current.pin) { id ->
                        current.passwordFor(id)
                    }
                }
            }
        }
        if (UnlockMethod.BIOMETRIC !in current.methods) {
            finish(current, committed = emptyMap())
            return
        }
        executeBiometric(current)
    }

    /**
     * 指纹路径：**认证前**备料（校验凭据 + 组装明文），再取 cipher 交给 UI 弹**一次**认证。
     *
     * 备料失败的库在这里就被挑出来 —— 让用户按了指纹再告诉他"密码错"是本末倒置，
     * 而且他会以为是指纹出了问题。若全部备料都失败，直接出报告、**不弹指纹**
     * （没有东西要落盘，弹了纯属打扰）。
     */
    private suspend fun executeBiometric(current: Session) {
        val bioTargets = current.targetsFor(UnlockMethod.BIOMETRIC)
        val outcomes = withContext(Dispatchers.IO) {
            enrollment.prepareForVaults(bioTargets) { id -> current.passwordFor(id) }
        }
        current.prepareOutcomes = outcomes
        prepared = outcomes.values
            .filterIsInstance<LocalUnlockPrepareOutcome.Ready>()
            .map { it.prepared }
        if (prepared.isEmpty()) {
            finish(current, committed = emptyMap())
            return
        }
        // ⚠️ cipher 在**备料通过之后**才创建：反过来会在全都失败时也造一把用不上的 cipher。
        val cipher = withContext(Dispatchers.IO) { vaultRepository.prepareLocalEnroll() }
        if (cipher == null) {
            // 设备无可用认证方式 ⇒ 备料失去意义，立刻擦掉（含 KDBX 主密码明文）。
            clearPrepared()
            // ⚠️ 走 finish 而不是自己拼一个只含失败的空报告：**PIN 段可能已经落盘了**，
            //    自己拼会把那部分抹掉 ⇒ 结果页变成"假失败"（用户以为没配上，其实能用了）。
            finish(
                current,
                committed = emptyMap(),
                extraFailures = listOf(
                    FailureItem("—", "本设备未设置锁屏或生物识别，无法启用"),
                ),
            )
            return
        }
        _dialog.value = Dialog.Authenticating
        _pendingCipher.value = cipher
    }

    /** 收尾：出结果页、清会话。 */
    private fun finish(
        session: Session,
        committed: Map<String, LocalUnlockEnrollOutcome>,
        extraFailures: List<FailureItem> = emptyList(),
    ) {
        _dialog.value = buildReport(session, committed, extraFailures)
        clearSession()
    }

    // ===== 内部：关闭 =====

    /** 关闭某个能力：删掉范围内所有库的该信封。范围本身保留（另一个能力可能还在用）。 */
    private suspend fun disableAll(method: UnlockMethod) {
        val ids = state.value.rows.filter { it.inScope }.map { it.vaultId }
        withContext(Dispatchers.IO) {
            ids.forEach { id ->
                if (method == UnlockMethod.BIOMETRIC) {
                    vaultRepository.disableLocalUnlock(id)
                } else {
                    vaultRepository.disablePin(id)
                }
            }
        }
    }

    // ===== 内部：报告 =====

    /**
     * 把一次会话的**全部**结论合并成结果页（PIN 段 + 指纹段）。
     *
     * ⚠️ 必须**合并**而不是各出各的报告：两种方式在同一次流程里配置，用户看到的是**一件事**的
     * 结果。旧实现每种方式各跑一遍、各出一个报告，正是"逻辑很麻烦"的一部分。
     *
     * ⚠️ 三段（成功 / 跳过 / 失败）各自拆成了独立函数，不是为了好看：合并逻辑一旦挤在一处，
     * 圈复杂度会直接顶到 detekt 上限（**实测** 15 > 14，门禁红过一次）。
     *
     * @param session 允许为 null（认证成功、但会话已被丢弃时）：此时库名退化成 id，
     *   但结论**照样展示** —— 不能因为拿不到名字就把结果吞掉（那就是沉默的分支）。
     */
    private fun buildReport(
        session: Session?,
        committed: Map<String, LocalUnlockEnrollOutcome>,
        extraFailures: List<FailureItem> = emptyList(),
    ): Dialog.Report {
        val names = session?.names.orEmpty()
        return Dialog.Report(
            succeeded = succeededNames(session, committed, names),
            skipped = skippedNames(session, names),
            // ⚠️ extraFailures 放**前面**：它多为"设备不支持"这类前置原因，
            //    排在逐库失败之前读起来才是因果顺序。
            failed = extraFailures + collectFailures(session, committed, names),
        )
    }

    /** 结果页「已启用」：PIN 段 + 指纹段，去重（同一个库可能两种方式都成功）。 */
    private fun succeededNames(
        session: Session?,
        committed: Map<String, LocalUnlockEnrollOutcome>,
        names: Map<String, String>,
    ): List<String> {
        val pin = matchingNames(session?.pinOutcomes.orEmpty(), names) {
            it is PinEnrollOutcome.Enrolled
        }
        val biometric = matchingNames(committed, names) {
            it is LocalUnlockEnrollOutcome.Enrolled
        }
        return (pin + biometric).distinct()
    }

    /** 结果页「已跳过」：用户主动跳过的 + PIN 段判定跳过的。 */
    private fun skippedNames(session: Session?, names: Map<String, String>): List<String> {
        val userSkipped = session?.skipped.orEmpty().map { names[it] ?: it }
        val pin = matchingNames(session?.pinOutcomes.orEmpty(), names) {
            it is PinEnrollOutcome.Skipped
        }
        return (userSkipped + pin).distinct()
    }

    /**
     * 结果页「未成功」：指纹备料 / 指纹落盘 / PIN 三段各自的失败项。
     *
     * ⚠️ 三段都要收集：漏掉任何一段都会让"失败"变成**静默**（用户以为只是没配完，
     * 实际是出了错），而这两者的后续动作完全不同（一个不用管，一个要重试）。
     */
    private fun collectFailures(
        session: Session?,
        committed: Map<String, LocalUnlockEnrollOutcome>,
        names: Map<String, String>,
    ): List<FailureItem> {
        val failures = mutableListOf<FailureItem>()
        // 指纹**备料**阶段就挂掉的（这些库连指纹都没等到）。
        addFailures(session?.prepareOutcomes.orEmpty(), names, ::prepareFailureReason, failures)
        // 指纹**落盘**阶段挂掉的。
        addFailures(committed, names, ::commitFailureReason, failures)
        // PIN 阶段挂掉的。
        addFailures(session?.pinOutcomes.orEmpty(), names, ::pinFailureReason, failures)
        return failures
    }

    /** 一组逐库结论里满足 [predicate] 的库名（保序，取不到名字时退回 id）。 */
    private fun <T> matchingNames(
        outcomes: Map<String, T>,
        names: Map<String, String>,
        predicate: (T) -> Boolean,
    ): List<String> = outcomes.filterValues(predicate).keys.map { names[it] ?: it }

    /** 把一组逐库结论里"有失败原因"的那些收成失败项（原因返回 null 即不计）。 */
    private fun <T> addFailures(
        outcomes: Map<String, T>,
        names: Map<String, String>,
        reason: (T) -> String?,
        into: MutableList<FailureItem>,
    ) {
        outcomes.forEach { (id, outcome) ->
            reason(outcome)?.let { into += FailureItem(names[id] ?: id, it) }
        }
    }

    /**
     * 备料阶段单库失败的原因（`Ready` / `Skipped` 返回 null：都不进失败列表）。
     *
     * ⚠️ 「跳过」**不算失败**：那是用户的选择，列进失败会让他以为自己操作错了。
     * 它会单独出现在 `Report.skipped` 里。
     *
     * ⚠️ 与解锁页的文案**不是一回事**，别合并：这里描述**登记**阶段，
     * 解锁页描述**开信封**阶段（指纹对了但信封打不开）。
     */
    private fun prepareFailureReason(outcome: LocalUnlockPrepareOutcome): String? =
        when (outcome) {
            is LocalUnlockPrepareOutcome.Ready -> null
            LocalUnlockPrepareOutcome.Skipped -> null
            LocalUnlockPrepareOutcome.InvalidCredentials -> "主密码不正确"
            is LocalUnlockPrepareOutcome.SourceUnavailable -> outcome.detail
            is LocalUnlockPrepareOutcome.Failed -> outcome.detail
        }

    /** 落盘阶段单库失败的原因（`Enrolled` 返回 null）。 */
    private fun commitFailureReason(outcome: LocalUnlockEnrollOutcome): String? =
        when (outcome) {
            LocalUnlockEnrollOutcome.Enrolled -> null
            is LocalUnlockEnrollOutcome.Failed -> outcome.detail
        }

    /**
     * PIN 登记阶段单库失败的原因（`Enrolled` / `Skipped` 返回 null：都不进失败列表）。
     *
     * ⚠️ 返回可空而不是空串：空串会**混进失败列表并渲染成"库名 · "**（一个没有原因的失败），
     * 而「跳过」根本不等于失败 —— 那是用户的选择，列进失败会让他以为自己操作错了。
     */
    private fun pinFailureReason(outcome: PinEnrollOutcome): String? = when (outcome) {
        is PinEnrollOutcome.PinTooShort -> "PIN 需要 ${outcome.minimum} 位数字"
        PinEnrollOutcome.InvalidCredentials -> "主密码不正确"
        PinEnrollOutcome.SessionUnavailable -> "该库未解锁，请先用主密码打开它"
        is PinEnrollOutcome.Failed -> outcome.detail
        PinEnrollOutcome.Enrolled, PinEnrollOutcome.Skipped -> null
    }

    // ===== 内部：状态组装 =====

    private fun composeState(): StateFlow<UiState> =
        vaultRepository.observeVaults()
            .flatMapLatest { vaults ->
                combine(
                    preferences.quickUnlockScope(),
                    preferences.isQuickUnlockScopeConfirmed(),
                    flagsOf(vaults),
                ) { scopeIds, confirmed, flags ->
                    assemble(vaults, flags, scopeIds, confirmed)
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState(),
            )

    /** 逐库两个信封的存在性（顺序与 [vaults] 一一对应）。 */
    private fun flagsOf(vaults: List<VaultSummary>): Flow<List<EnvelopeFlags>> {
        val flows = vaults.map { vault ->
            combine(
                vaultRepository.localUnlockAvailable(vault.id),
                vaultRepository.pinUnlockAvailable(vault.id),
            ) { biometric, pin -> EnvelopeFlags(biometric, pin) }
        }
        // ⚠️ 空列表必须短路成 flowOf：`combine(emptyList())` 永不发射，
        //    会让整条 state 流卡在初值 ⇒ 用户看到"列表永远空着"。
        return if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
    }

    /**
     * 组装 UI 状态。
     *
     * ⚠️ 库类型（[VaultUi.kind]）**直接取自本次发射的 [vaults]**，不走任何旁路缓存 ——
     * 缓存会因为初始化时机不对而退化成"所有库都被当成 Bitwarden"，
     * 表现为 KDBX 库登记时**不问主密码**，于是静默地建不出信封。
     *
     * ⚠️ [confirmed] = false（用户从未确认过范围）时，**所有库都算在范围内** —— 这是
     * "默认全勾"的落点。别改成"范围为空就算全部库"：空集是指"一个都不要"，
     * 借它表示"还没配过"会让用户主动全不勾之后被显示成全勾（「空有三态」那条纪律）。
     */
    private fun assemble(
        vaults: List<VaultSummary>,
        flags: List<EnvelopeFlags>,
        scopeIds: Set<String>,
        confirmed: Boolean,
    ): UiState {
        val rows = vaults.mapIndexed { index, vault ->
            val flag = flags.getOrElse(index) { EnvelopeFlags(biometric = false, pin = false) }
            VaultUi(
                vaultId = vault.id,
                name = vault.name,
                kind = vault.kind,
                inScope = !confirmed || vault.id in scopeIds,
                biometricReady = flag.biometric,
                pinReady = flag.pin,
            )
        }
        return UiState(
            rows = rows,
            biometric = deriveCapabilityState(rows) { it.biometricReady },
            pin = deriveCapabilityState(rows) { it.pinReady },
        )
    }

    private fun clearPrepared() {
        prepared.forEach { it.close() }
        prepared = emptyList()
    }

    private fun clearSession() {
        session = null
    }

    private fun String.onlyDigits(): String = filter(Char::isDigit).take(PIN_MIN_LENGTH)

    /** 一个库里两个信封的存在性。 */
    private data class EnvelopeFlags(val biometric: Boolean, val pin: Boolean)

    /**
     * 一次登记会话。
     *
     * 把"用哪些方式、对谁、已经问到哪、哪些被跳过"收在一处，避免散成一堆局部变量
     * （散开之后最容易出的错是**状态迁移丢信息** —— 例如忘了把"跳过集合"带过去，
     * 于是结果页把跳过谎报成成功）。
     *
     * ⚠️ [methods] 是**集合**而不是单值：两种方式在同一次流程里配完（类 KDoc 那条
     * "主密码两种方式都要用"），单值会让主密码又变成收两遍。
     */
    private inner class Session(
        val methods: Set<UnlockMethod>,
        val targets: List<String>,
        rows: List<VaultUi>,
    ) {
        /** 已收集的主密码（vaultId -> password）。 */
        val passwords = mutableMapOf<String, String>()

        /** 用户跳过的库。 */
        val skipped = mutableSetOf<String>()

        /** PIN 流程收到的 PIN（未选 PIN 时恒为空串）。 */
        var pin: String = ""

        /** 指纹**备料**阶段的逐库结论（仅选了指纹时有值）。 */
        var prepareOutcomes: Map<String, LocalUnlockPrepareOutcome> = emptyMap()

        /** PIN 阶段的逐库结论（仅选了 PIN 时有值）；与指纹段在 [buildReport] 里合并。 */
        var pinOutcomes: Map<String, PinEnrollOutcome> = emptyMap()

        /** 供结果页展示的 id -> 库名。 */
        val names: Map<String, String> = rows.associate { it.vaultId to it.name }

        /** 需要主密码的库（KDBX 才有这一项）。 */
        private val masterPasswordIds: Set<String> =
            rows.filter { it.needsMasterPassword }.map { it.vaultId }.toSet()

        private val envelopeReady: Map<String, Pair<Boolean, Boolean>> =
            rows.associate { it.vaultId to (it.biometricReady to it.pinReady) }

        /**
         * 某个方式下**真正要登记**的库（已有信封的跳过）。
         *
         * ⚠️ 这一层过滤是"默认全勾"能落地的关键：用户不必自己判断哪些库还没配 ——
         * 勾了全体也没关系，已经配好的库既不会被重问主密码，也不会被重写信封。
         */
        fun targetsFor(method: UnlockMethod): List<String> = targets.filter { id ->
            val (biometricReady, pinReady) = envelopeReady[id] ?: (false to false)
            when (method) {
                UnlockMethod.BIOMETRIC -> !biometricReady
                UnlockMethod.PIN -> !pinReady
            }
        }

        /** 本次要处理的全部库（各已选方式的并集）；为空 = 只有范围要落盘。 */
        fun pendingTargets(): List<String> = methods.flatMap { targetsFor(it) }.distinct()

        /** 目标里需要问主密码的库（KDBX）。 */
        val kdbxTargets: List<String> = pendingTargets().filter { it in masterPasswordIds }

        /** 下一个待问主密码的库（null = 都问过了）。 */
        fun currentTarget(): String? =
            kdbxTargets.firstOrNull { it !in passwords && it !in skipped }

        fun nameOf(vaultId: String): String = names[vaultId] ?: vaultId

        /** 逐库取密码（跳过 / 未问到的返回 null ⇒ 上层如实报 `Skipped`）。 */
        fun passwordFor(vaultId: String): String? = passwords[vaultId]
    }

    private companion object {
        /**
         * `stateIn` 的订阅超时（与库列表 / 设置页同款：切后台 5 秒后才停收集）。
         *
         * ⚠️ 必须是命名常量：detekt 的 `MagicNumber` 只盯**函数体内**的字面量，
         * 写在属性初始化处不报、写在函数里就报 —— 这正是它先红一次的原因。
         */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * 由「范围内每库的信封状态」推导一个能力的状态（`On` / `Partial(n)` / `Off`）。
 *
 * ⚠️ **三个分支的顺序即语义**：
 * - 范围为空 ⇒ `Off`；
 * - 范围内全建好 ⇒ `On`；
 * - **一个都没建 ⇒ `Off`（不是 `Partial`）**；
 * - 中间才是 `Partial`。
 *
 * 把"一个都没建"也报成 `Partial` 是很容易犯的错：用户主动关闭某能力之后，
 * 界面会告诉他"有 N 个库未完成"，让他以为自己的操作没生效 ——
 * 那正是旧实现里「谎报状态」的同一种病（issue #93）。
 *
 * 提到**顶层**是为了可单测：它是纯函数（只依赖入参），
 * 若留在类里，测它就得把 Repository / Preferences 一起 mock。
 */
internal fun deriveCapabilityState(
    rows: List<QuickUnlockController.VaultUi>,
    ready: (QuickUnlockController.VaultUi) -> Boolean,
): QuickUnlockController.CapabilityState {
    val inScope = rows.filter { it.inScope }
    if (inScope.isEmpty()) return QuickUnlockController.CapabilityState.Off
    val readyCount = inScope.count(ready)
    return when {
        readyCount == inScope.size -> QuickUnlockController.CapabilityState.On
        readyCount == 0 -> QuickUnlockController.CapabilityState.Off
        else -> QuickUnlockController.CapabilityState.Partial(pending = inScope.size - readyCount)
    }
}
