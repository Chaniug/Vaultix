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
 *
 * **刻意不持久化「总开关」**：开关的 ON/OFF 由「范围 + 每库信封」推导。
 * 若另存一个开关字段，就会出现"开关说开着、信封却是空的"这种双源漂移 ——
 * 那正是 issue #93「谎报状态的开关」的成因，别再造一个。
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

        /** 输 PIN（仅 PIN 流程，在问主密码**之前**）。 */
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
         */
        data class Report(
            val succeeded: List<String>,
            val skipped: List<String>,
            val failed: List<FailureItem>,
        ) : Dialog
    }

    /** 结果页里单个库的失败项。 */
    data class FailureItem(val vaultName: String, val reason: String)

    /** 供 Snackbar 的一次性提示。 */
    sealed interface Notice {
        /** 某个库被移出生效范围（连带删掉了它的信封，用户应当知道）。 */
        data class ScopeRemoved(val vaultName: String) : Notice
    }

    /** 界面需要的全部状态。 */
    data class UiState(
        val rows: List<VaultUi> = emptyList(),
        val biometric: CapabilityState = CapabilityState.Off,
        val pin: CapabilityState = CapabilityState.Off,
    )

    private val _dialog = MutableStateFlow<Dialog>(Dialog.Idle)
    val dialog: StateFlow<Dialog> = _dialog.asStateFlow()

    private val _notices = MutableStateFlow<Notice?>(null)
    val notices: StateFlow<Notice?> = _notices.asStateFlow()

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
     * 把一个库加入 / 移出生效范围。
     *
     * ⚠️ 移出范围 = 该库不再用快速解锁 ⇒ 同时删掉它的**两个**信封。
     * 那是用户主动做的删除，故不弹二次确认；但发一条 [Notice] 让 UI 用 Snackbar 告知
     * —— 重配需要再输 KDBX 主密码，用户应当知道发生了什么。
     *
     * ⚠️ 加入范围**只改范围、不当场登记**：用户可能想先把要覆盖的库勾齐，再一次性配置。
     * 此时若开关原为 `On`，状态会自动变成 `Partial`（新勾的库还没信封）——
     * 那正是诚实的表达，也正好提示用户"还差几个"。
     */
    fun toggleScope(vaultId: String) {
        scope.launch {
            val current = preferences.quickUnlockScope().first()
            if (vaultId in current) {
                val name = vaultNameOf(vaultId)
                vaultRepository.disableLocalUnlock(vaultId)
                vaultRepository.disablePin(vaultId)
                preferences.setQuickUnlockScope(current - vaultId)
                _notices.value = Notice.ScopeRemoved(name)
            } else {
                preferences.setQuickUnlockScope(current + vaultId)
            }
        }
    }

    /**
     * 点「指纹」开关。
     *
     * - 当前 `On` ⇒ 视为**关闭**：删掉范围内所有库的指纹信封；
     * - `Off` / `Partial` ⇒ 视为**打开或继续**：为范围内尚未建信封的库走登记流程。
     *
     * ⚠️ `Partial` 必须走"继续"而不是"关闭"：那正是补完剩下几个库的入口。
     */
    fun toggleBiometric() {
        scope.launch {
            when (state.value.biometric) {
                CapabilityState.On -> disableAll(UnlockMethod.BIOMETRIC)
                else -> beginEnroll(UnlockMethod.BIOMETRIC)
            }
        }
    }

    /** 点「应用内 PIN」开关。语义同 [toggleBiometric]。 */
    fun togglePin() {
        scope.launch {
            when (state.value.pin) {
                CapabilityState.On -> disableAll(UnlockMethod.PIN)
                else -> beginEnroll(UnlockMethod.PIN)
            }
        }
    }

    /**
     * 从**库列表页的引导横幅**一键启用某个库的快速解锁（指纹）。
     *
     * 语义 = 「把这个库纳入范围，然后立刻为它配指纹」。
     *
     * ⚠️ 必须**等 state 反映新范围之后**再开始登记：`setQuickUnlockScope` 是异步落盘，
     * 紧接着读 `state` 拿到的还是**旧范围** ⇒ 目标集合里没有这个库，
     * 登记会「什么都没做」（用户看到横幅点了没反应）。
     */
    fun enableForVault(vaultId: String) {
        scope.launch {
            val current = preferences.quickUnlockScope().first()
            if (vaultId !in current) {
                preferences.setQuickUnlockScope(current + vaultId)
            }
            val snapshot = state
                .filter { ui -> ui.rows.any { it.vaultId == vaultId && it.inScope } }
                .first()
            session = Session(
                method = UnlockMethod.BIOMETRIC,
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
            _dialog.value = buildBiometricReport(committed, sessionNow)
            clearSession()
        }
    }

    /**
     * 认证失败 / 被用户取消：**擦掉备料明文**并结束本次流程。
     *
     * 不做"保留备料下次再试"的优化：cipher 已作废（一次认证一把），留着也没有 cipher
     * 可用；而它里面躺着 KDBX 主密码，多留一刻都是风险。
     */
    fun onAuthenticationFailed() {
        _pendingCipher.value = null
        clearPrepared()
        clearSession()
        _dialog.value = Dialog.Idle
    }

    /** 用户取消对话框：清掉一切在途的敏感物。 */
    fun dismiss() {
        _pendingCipher.value = null
        clearPrepared()
        clearSession()
        _dialog.value = Dialog.Idle
    }

    /** Snackbar 提示已消费。 */
    fun noticeShown() {
        _notices.value = null
    }

    // ===== 内部：登记流程 =====

    /**
     * 开始为某个能力登记。
     *
     * 目标 = 范围内**尚未建信封**的库。全都建好了就什么都不做（此时开关已是 `On`）。
     * PIN 流程要先收 PIN；指纹流程直接进"问主密码"阶段。
     */
    private suspend fun beginEnroll(method: UnlockMethod) {
        val snapshot = state.value
        val targets = snapshot.rows
            .filter { it.inScope }
            .filter { if (method == UnlockMethod.BIOMETRIC) !it.biometricReady else !it.pinReady }
            .map { it.vaultId }
        if (targets.isEmpty()) return
        session = Session(method = method, targets = targets, rows = snapshot.rows)
        if (method == UnlockMethod.PIN) {
            _dialog.value = Dialog.PinEntry()
        } else {
            advanceToPasswordOrExecute()
        }
    }

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

    /** 备料/参数就绪后真正执行：指纹要弹认证，PIN 当场落盘。 */
    private fun execute() {
        val current = session ?: return
        scope.launch {
            if (current.method == UnlockMethod.BIOMETRIC) {
                executeBiometric(current)
            } else {
                executePin(current)
            }
        }
    }

    /**
     * 指纹路径：**认证前**备料（校验凭据 + 组装明文），再取 cipher 交给 UI 弹**一次**认证。
     *
     * 备料失败的库在这里就被挑出来 —— 让用户按了指纹再告诉他"密码错"是本末倒置，
     * 而且他会以为是指纹出了问题。若全部备料都失败，直接出报告、**不弹指纹**
     * （没有东西要落盘，弹了纯属打扰）。
     */
    private suspend fun executeBiometric(current: Session) {
        val outcomes = withContext(Dispatchers.IO) {
            enrollment.prepareForVaults(current.targets) { id -> current.passwordFor(id) }
        }
        current.prepareOutcomes = outcomes
        prepared = outcomes.values
            .filterIsInstance<LocalUnlockPrepareOutcome.Ready>()
            .map { it.prepared }
        if (prepared.isEmpty()) {
            _dialog.value = buildBiometricReport(committed = emptyMap(), session = current)
            clearSession()
            return
        }
        // ⚠️ cipher 在**备料通过之后**才创建：反过来会在全都失败时也造一把用不上的 cipher。
        val cipher = withContext(Dispatchers.IO) { vaultRepository.prepareLocalEnroll() }
        if (cipher == null) {
            // 设备无可用认证方式 ⇒ 备料失去意义，立刻擦掉（含 KDBX 主密码明文）。
            clearPrepared()
            clearSession()
            _dialog.value = Dialog.Report(
                succeeded = emptyList(),
                skipped = emptyList(),
                failed = listOf(FailureItem("—", "本设备未设置锁屏或生物识别，无法启用")),
            )
            return
        }
        _dialog.value = Dialog.Authenticating
        _pendingCipher.value = cipher
    }

    /** PIN 路径：不碰系统认证，当场落盘。 */
    private suspend fun executePin(current: Session) {
        val results = withContext(Dispatchers.IO) {
            vaultRepository.enrollPinForVaults(current.targets, current.pin) { id ->
                current.passwordFor(id)
            }
        }
        // ⚠️ 先取 names 再清 session —— 反过来的话报告里的库名会全变成 id。
        _dialog.value = buildPinReport(results, current.names)
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

    private fun buildBiometricReport(
        committed: Map<String, LocalUnlockEnrollOutcome>,
        session: Session?,
    ): Dialog.Report {
        val names = session?.names ?: emptyMap()
        val succeeded = committed
            .filterValues { it is LocalUnlockEnrollOutcome.Enrolled }
            .keys.map { names[it] ?: it }
        val failed = buildList {
            // 备料阶段就挂掉的（这些库连指纹都没等到）。
            session?.prepareOutcomes?.forEach { (id, outcome) ->
                prepareFailureReason(outcome)?.let { add(FailureItem(names[id] ?: id, it)) }
            }
            // 落盘阶段挂掉的。
            committed.forEach { (id, outcome) ->
                commitFailureReason(outcome)?.let { add(FailureItem(names[id] ?: id, it)) }
            }
        }
        val skipped = session?.skipped?.map { names[it] ?: it }.orEmpty()
        return Dialog.Report(succeeded = succeeded, skipped = skipped, failed = failed)
    }

    private fun buildPinReport(
        results: Map<String, PinEnrollOutcome>,
        names: Map<String, String>,
    ): Dialog.Report {
        val succeeded = results.filterValues { it is PinEnrollOutcome.Enrolled }
            .keys.map { names[it] ?: it }
        val skipped = results.filterValues { it is PinEnrollOutcome.Skipped }
            .keys.map { names[it] ?: it }
        val failed = results
            .filterValues { it !is PinEnrollOutcome.Enrolled && it !is PinEnrollOutcome.Skipped }
            .map { (id, outcome) -> FailureItem(names[id] ?: id, pinFailureReason(outcome)) }
        return Dialog.Report(succeeded = succeeded, skipped = skipped, failed = failed)
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

    /** PIN 登记阶段单库失败的原因（`Enrolled` / `Skipped` 之外才有值）。 */
    private fun pinFailureReason(outcome: PinEnrollOutcome): String = when (outcome) {
        is PinEnrollOutcome.PinTooShort -> "PIN 需要 ${outcome.minimum} 位数字"
        PinEnrollOutcome.InvalidCredentials -> "主密码不正确"
        PinEnrollOutcome.SessionUnavailable -> "该库未解锁，请先用主密码打开它"
        is PinEnrollOutcome.Failed -> outcome.detail
        PinEnrollOutcome.Enrolled, PinEnrollOutcome.Skipped -> ""
    }

    // ===== 内部：状态组装 =====

    private fun composeState(): StateFlow<UiState> =
        vaultRepository.observeVaults()
            .flatMapLatest { vaults ->
                combine(preferences.quickUnlockScope(), flagsOf(vaults)) { scopeIds, flags ->
                    assemble(vaults, flags, scopeIds)
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
     */
    private fun assemble(
        vaults: List<VaultSummary>,
        flags: List<EnvelopeFlags>,
        scopeIds: Set<String>,
    ): UiState {
        val rows = vaults.mapIndexed { index, vault ->
            val flag = flags.getOrElse(index) { EnvelopeFlags(biometric = false, pin = false) }
            VaultUi(
                vaultId = vault.id,
                name = vault.name,
                kind = vault.kind,
                inScope = vault.id in scopeIds,
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

    private suspend fun vaultNameOf(vaultId: String): String =
        runCatching {
            vaultRepository.observeVaults().first().firstOrNull { it.id == vaultId }?.name
        }.getOrNull() ?: vaultId

    private fun String.onlyDigits(): String = filter(Char::isDigit).take(PIN_MIN_LENGTH)

    /** 一个库里两个信封的存在性。 */
    private data class EnvelopeFlags(val biometric: Boolean, val pin: Boolean)

    /**
     * 一次登记会话。
     *
     * 把"目标是谁、已经问到哪、哪些被跳过"收在一处，避免散成一堆局部变量
     * （散开之后最容易出的错是**状态迁移丢信息** —— 例如忘了把"跳过集合"带过去，
     * 于是结果页把跳过谎报成成功）。
     */
    private inner class Session(
        val method: UnlockMethod,
        val targets: List<String>,
        rows: List<VaultUi>,
    ) {
        /** 已收集的主密码（vaultId -> password）。 */
        val passwords = mutableMapOf<String, String>()

        /** 用户跳过的库。 */
        val skipped = mutableSetOf<String>()

        /** PIN 流程收到的 PIN（指纹流程恒为空串）。 */
        var pin: String = ""

        /** 备料阶段的逐库结论（仅指纹流程有值，供结果页与落盘结论合并展示）。 */
        var prepareOutcomes: Map<String, LocalUnlockPrepareOutcome> = emptyMap()

        /** 供结果页展示的 id -> 库名。 */
        val names: Map<String, String> = rows.associate { it.vaultId to it.name }

        /** 目标里需要问主密码的库（KDBX）。 */
        val kdbxTargets: List<String> = targets.filter { id ->
            rows.firstOrNull { it.vaultId == id }?.needsMasterPassword == true
        }

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
