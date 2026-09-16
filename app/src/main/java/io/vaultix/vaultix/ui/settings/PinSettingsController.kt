/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.domain.PinEnrollOutcome
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「应用内 PIN」的设置交互控制器（2026-09-16 从 [SettingsViewModel] 抽出）。
 *
 * ## 为什么单独一个类
 *
 * 直接原因是 detekt `TooManyFunctions`：[SettingsViewModel] 加完「一个 PIN 打开多个库」
 * 后到了 43 个函数（上限 40）。但更实际的理由是**内聚**：PIN 设置是一个
 * 自成一体的多步流程（勾选目标 → 输 PIN → KDBX 输主密码 → 结果汇报），
 * 与"设置页各种偏好开关"不是一回事。抽出来之后两边都更好读。
 *
 * ⚠️ **下一个再往里加解锁手段时，同样要提取，而不是继续堆回 `SettingsViewModel`。**
 * （同 `PinUnlockStore` / `PinEnrollmentCoordinator` 抽出去时留的告诫。）
 *
 * ## 状态模型
 *
 * 单一 [pinDialog] 状态流驱动对话框，UI 按它选帧渲染（见 `PinDialogHost`）。
 * 步骤之间的字段传递靠状态本身携带，**不靠外部缓存** —— 状态迁移丢信息是这类
 * 流程最常见的 bug（例如把"用户勾选了哪些库"留在 Entering 里，一旦进入
 * 下一步就再也取不回来，只能退化成"全部库"，那正是要避免的越权）。
 */
class PinSettingsController(
    private val vaultRepository: VaultRepository,
    private val scope: CoroutineScope,
    /** 配齐成功后要"改用 PIN ⇒ 关掉该库指纹"时调用（见 `dismissBiometricAfterPinEnroll`）。 */
    private val onDisableQuickUnlock: (vaultId: String) -> Unit,
) {

    sealed interface PinDialogState {
        data object Idle : PinDialogState

        /**
         * 第一步：输入 PIN 两次 + **勾选要覆盖哪些库**。
         *
         * ## 为什么勾选是必需的，而不是默认全选就完事
         *
         * 「一个 PIN 打开多个库」是用户要的效果，但**库表里有几个库 ≠ 用户想设几个**：
         * 有些库用户可能根本不想启用 PIN（例如只读的共享库、别人的库）。
         * 若程序直接遍历全表设置，就是**静默改掉用户没同意改的配置** ——
         * 而 PIN 覆盖会影响解锁入口，是用户可感知的安全设置。
         *
         * ⇒ 默认**全选**（多数人的诉求就是"一个 PIN 全开"，别让多数人多点一次），
         *   但**选择权在用户手上**且可取消。
         *
         * @param candidates 全部可选的库（含 KDBX）。空表示还没取到。
         * @param selected 被勾选的库 id（默认为 [candidates] 全部）。
         */
        data class Entering(
            val vaultId: String,
            val vaultName: String,
            val kind: VaultKind,
            val candidates: List<PinCandidate> = emptyList(),
            val selected: Set<String> = emptySet(),
            val pin: String = "",
            val confirm: String = "",
            val error: String? = null,
        ) : PinDialogState {
            /** 勾选里是否含 KDBX 库 ⇒ 决定要不要走"再输一次主密码"那一步。 */
            val hasKdbx: Boolean
                get() = candidates.any { it.id in selected && it.kind == VaultKind.KDBX }
        }

        /**
         * 第二步（仅 KDBX）：再输一次主密码 —— KDBX 会话里没有它，无法包裹。
         *
         * ⚠️ **[targets] 必须原样带过来**：本状态已经离开了 [Entering]，
         * 若不带上勾选结果，配齐时就不知道该给哪些库设 PIN —— 只能退化成"全部库"，
         * 而那正是要避免的静默越权。**状态迁移不能丢信息。**
         */
        data class AskingKdbxPassword(
            val vaultId: String,
            val vaultName: String,
            val targets: List<String> = emptyList(),
            val password: String = "",
            val error: String? = null,
        ) : PinDialogState

        /**
         * 第三步：**配齐结果**。
         *
         * 为什么要有这一步而不是直接静默成功：配齐是**逐库**进行的，
         * 部分成功是真实状态（某个 KDBX 库的主密码打错，Bitwarden 侧却是好的）。
         * 直接关掉对话框会让用户以为"全都配好了"，而实际有库没配上 ——
         * 那正是本项目反复强调的**假状态**。如实列出每个库的成败，用户才知道
         * 下一步该做什么（补哪个库的密码）。
         */
        data class EnrollReport(
            val succeeded: List<String>,
            val failed: List<PinEnrollFailure>,
        ) : PinDialogState
    }

    /**
     * PIN 勾选列表里的一项（库名 + 类型 + 是否已启用）。
     *
     * `pinEnabled` 用于在列表上标出"这个库已经有 PIN 了" ——
     * 用户可能只是想给**新库**补一个 PIN，看到既有状态才好判断要不要动它。
     */
    data class PinCandidate(
        val id: String,
        val name: String,
        val kind: VaultKind,
        val pinEnabled: Boolean,
    )

    /** 配齐流程里单个库的失败项（库名 + 原因，供结果页逐条显示）。 */
    data class PinEnrollFailure(val vaultName: String, val reason: String)

    private val _pinDialog = MutableStateFlow<PinDialogState>(PinDialogState.Idle)
    val pinDialog: StateFlow<PinDialogState> = _pinDialog.asStateFlow()

    /**
     * 第一步收下的 PIN，等 KDBX 那步输完主密码再一起提交。
     *
     * 刻意**不放进 UI state**（同解锁页的取向）：对话框 state 会被反复比较与展示，
     * 明文 PIN 在里面流动没有意义。⚠️ **失败时不抹** —— 抹了用户重试就得从第一步重来。
     */
    private var pendingPin: String = ""

    /**
     * 「设置 PIN 成功后，是否顺带关闭该库的指纹解锁」。
     *
     * ## 为什么需要这个标记（2026-09-16）
     *
     * 解锁方式收敛为三选一（指纹 / PIN / 每次输主密码）后，用户在**有指纹**的库上
     * 点「应用内 PIN」，语义是"改用 PIN" ⇒ 指纹应当关掉。
     *
     * ⚠️ **但不能在点击时就关**：PIN 对话框是独立流程，用户完全可能中途取消。
     * 若先关了指纹再设 PIN，取消后就变成"指纹没了、PIN 也没设成"，用户
     * **白白丢了一种已配好的解锁方式** —— 这是静默的数据丢失，比 UI 难看严重得多。
     * 故改为**登记意图、等登记真正成功后再执行**。
     *
     * ⚠️ **「修改 PIN」路径不能受影响**：那条路径下用户可能指纹与 PIN 同时开着，
     * 顺手关掉指纹就是破坏用户配置。故只在 [openSwitchingFromBiometric] 入口置位，
     * 其余入口（[open]）不置位。
     */
    private var dismissBiometricAfterEnroll: String? = null

    fun open(vault: SettingsViewModel.QuickUnlockVaultUi) {
        pendingPin = ""
        // 「修改 PIN」等常规入口：不动指纹（见 dismissBiometricAfterEnroll 的告诫）。
        dismissBiometricAfterEnroll = null
        _pinDialog.value = PinDialogState.Entering(
            vaultId = vault.vaultId,
            vaultName = vault.name,
            kind = vault.kind,
        )
        loadCandidatesAsync()
    }

    /**
     * 异步取候选库并填进对话框。
     *
     * ## 为什么用 [enrichEntering] 而不是直接 copy
     *
     * 取列表要读库表 + 逐库查 PIN 状态，是 IO ⇒ 必然有一帧"列表还没到"。
     * 若这一帧用户已经动手（勾选 / 输入），无条件 `copy(candidates=..., selected=...)`
     * 会把用户刚才的操作**覆盖掉** —— 表现是"我点的那个勾又跳回来了"。
     * 故只在**用户还没动过**时才默认全选；动过就只补列表、保留他的选择。
     * （"状态迁移不能丢信息"的同一条纪律，只是这里丢的是用户输入。）
     */
    private fun loadCandidatesAsync() {
        scope.launch {
            val candidates = withContext(Dispatchers.IO) { loadCandidates() }
            enrichEntering { current ->
                val untouched = current.selected.isEmpty() && current.pin.isEmpty()
                current.copy(
                    candidates = candidates,
                    // 默认**全选**：用户点「设置 PIN」的意图通常是"一个 PIN 全开"，
                    // 让多数人少点一次。但列表可见、可取消（见 Entering 的 KDoc）。
                    selected = if (untouched) candidates.map { it.id }.toSet() else current.selected,
                )
            }
        }
    }

    /**
     * 从「指纹解锁」切换到「应用内 PIN」时打开设置流程。
     *
     * 与 [open] 的唯一差别：**设置成功后**会关闭该库的指纹解锁，
     * 完成"改用 PIN"的语义。关的时机见 [dismissBiometricAfterEnroll] 的告诫。
     */
    fun openSwitchingFromBiometric(vault: SettingsViewModel.QuickUnlockVaultUi) {
        open(vault)
        dismissBiometricAfterEnroll = vault.vaultId
    }

    fun dismiss() {
        pendingPin = ""
        // 用户取消 ⇒ 放弃"改用 PIN"的意图，**不能**留下标记（否则下次成功时会误关指纹）。
        dismissBiometricAfterEnroll = null
        _pinDialog.value = PinDialogState.Idle
    }

    /** 勾选 / 取消勾选某个库（「一个 PIN 打开多个库」的目标选择）。 */
    fun toggleTarget(vaultId: String) {
        updateEntering { current ->
            val next = if (vaultId in current.selected) {
                current.selected - vaultId
            } else {
                current.selected + vaultId
            }
            current.copy(selected = next, error = null)
        }
    }

    fun onPinChange(value: String) {
        updateEntering { it.copy(pin = value.onlyDigits(), error = null) }
    }

    fun onConfirmChange(value: String) {
        updateEntering { it.copy(confirm = value.onlyDigits(), error = null) }
    }

    fun onKdbxPasswordChange(value: String) {
        val current = _pinDialog.value as? PinDialogState.AskingKdbxPassword ?: return
        _pinDialog.value = current.copy(password = value, error = null)
    }

    /**
     * 第一步提交：校验目标选择 + PIN 本身（位数 + 两次一致）。
     *
     * 规则放在控制器而不是只在 UI：UI 只负责把 `error` 画出来，
     * 判定只有一处，避免两个入口各写一遍阈值。
     */
    fun confirmEntry() {
        val current = _pinDialog.value as? PinDialogState.Entering ?: return
        if (current.selected.isEmpty()) {
            _pinDialog.value = current.copy(error = "请至少选择一个库")
            return
        }
        if (current.pin.length != PIN_MIN_LENGTH) {
            _pinDialog.value = current.copy(error = "PIN 需要 $PIN_MIN_LENGTH 位数字")
            return
        }
        if (current.pin != current.confirm) {
            _pinDialog.value = current.copy(error = "两次输入不一致，请重新输入")
            return
        }
        pendingPin = current.pin
        if (current.hasKdbx) {
            // 勾选里有 KDBX 库 ⇒ 必须再要一次主密码（KDBX 会话里没有它，与 §4.5 同源）。
            // ⚠️ **把勾选结果带过去**：本状态一离开 Entering，勾选就没有别的来源了。
            _pinDialog.value = PinDialogState.AskingKdbxPassword(
                vaultId = current.vaultId,
                vaultName = current.vaultName,
                targets = current.selected.toList(),
            )
        } else {
            enroll(current.selected.toList(), current.pin, masterPassword = "")
        }
    }

    fun confirmKdbxPassword() {
        val current = _pinDialog.value as? PinDialogState.AskingKdbxPassword ?: return
        if (current.password.isBlank()) {
            _pinDialog.value = current.copy(error = "请输入主密码")
            return
        }
        // ⚠️ 目标列表从**本状态自己**取，不从别处缓存读 —— 单一来源才不会漂移。
        enroll(current.targets, pendingPin, masterPassword = current.password)
    }

    fun disableVaultPin(vaultId: String) {
        scope.launch { vaultRepository.disablePin(vaultId) }
    }

    /** 读候选库（含每库当前的 PIN 开关状态，供列表标注"已有 PIN"）。 */
    private suspend fun loadCandidates(): List<PinCandidate> {
        val vaults = runCatching { vaultRepository.observeVaults().first() }.getOrDefault(emptyList())
        val ids = withContext(Dispatchers.IO) { vaultRepository.pinCandidateVaultIds() }
        return vaults.filter { it.id in ids }.map { v ->
            PinCandidate(
                id = v.id,
                name = v.name,
                kind = v.kind,
                pinEnabled = runCatching {
                    vaultRepository.pinUnlockAvailable(v.id).first()
                }.getOrDefault(false),
            )
        }
    }

    private fun String.onlyDigits(): String = filter(Char::isDigit).take(PIN_MIN_LENGTH)

    private fun updateEntering(transform: (PinDialogState.Entering) -> PinDialogState.Entering) {
        val current = _pinDialog.value as? PinDialogState.Entering ?: return
        _pinDialog.value = transform(current)
    }

    /**
     * 同 [updateEntering]，但**只认当前仍是 [PinDialogState.Entering] 的情况**，
     * 用于异步回填（见 [loadCandidatesAsync] 的告诫：不能覆盖用户已做的操作）。
     *
     * ⚠️ 与 [updateEntering] 的差别只是语义命名 —— 分开是为了让调用点自证意图：
     * 看到 `enrich` 就知道这是"补信息"，不是"设状态"。
     */
    private fun enrichEntering(transform: (PinDialogState.Entering) -> PinDialogState.Entering) =
        updateEntering(transform)

    /**
     * 把当前的 PIN 配到**用户勾选的库**上（「一个 PIN 打开多个库」的落地点）。
     *
     * 与旧实现的差别：旧实现只处理**被点的那一个库**，用户要开两个库就得设两次。
     * 现在一次配齐，之后解锁页输入同一个 PIN 即可各开各的（每个库仍有自己的信封）。
     *
     * ⚠️ **只处理 [targets]**，不自己遍历全表（见 domain 里 `enrollPinForVaults` 的 KDoc）：
     * 静默给用户没选的库设 PIN 属于越权改配置。
     *
     * ⚠️ **逐库独立成败**，不整体回滚：一个 KDBX 库的主密码打错，
     * 不该把已成功的 Bitwarden 登记一起作废。
     */
    private fun enroll(targets: List<String>, pin: String, masterPassword: String) {
        scope.launch {
            if (pin.isEmpty() || targets.isEmpty()) {
                _pinDialog.value = PinDialogState.Idle
                return@launch
            }
            val results = withContext(Dispatchers.IO) {
                vaultRepository.enrollPinForVaults(targets, pin, masterPassword)
            }
            pendingPin = ""
            // 库名用于结果页展示（只有 id 用户看不懂是哪个库）。
            val names = runCatching {
                withContext(Dispatchers.IO) { vaultRepository.observeVaults().first() }
                    .associate { it.id to it.name }
            }.getOrDefault(emptyMap())

            val succeeded = results.filterValues { it is PinEnrollOutcome.Enrolled }
                .keys.map { names[it] ?: it }
            val failed = results.filterValues { it !is PinEnrollOutcome.Enrolled }
                .map { (id, outcome) ->
                    PinEnrollFailure(
                        vaultName = names[id] ?: id,
                        reason = failureReason(outcome),
                    )
                }
            _pinDialog.value = PinDialogState.EnrollReport(succeeded = succeeded, failed = failed)

            // 配齐成功后，才执行"改用 PIN ⇒ 关掉指纹"（见 dismissBiometricAfterEnroll
            // 的告诫：不能在点击时就关）。只关发起这次设置的那个库 —— 用户点的是它。
            val switchFrom = dismissBiometricAfterEnroll
            dismissBiometricAfterEnroll = null
            if (switchFrom != null && succeeded.isNotEmpty()) onDisableQuickUnlock(switchFrom)
        }
    }

    /**
     * 配齐结果里单库失败的原因文案。
     *
     * ⚠️ 与解锁页的 `pinFailureText` **不是一回事**，别合并：这里描述的是
     * **登记**阶段的失败（输错主密码 / 库没解锁 / PIN 位数），
     * 而解锁页描述的是**开信封**阶段的失败（PIN 不对 / 被锁 / 凭据过期）。
     * 合并会让"PIN 不对"这种解锁期的话出现在登记结果里，词不达意。
     */
    private fun failureReason(outcome: PinEnrollOutcome): String = when (outcome) {
        is PinEnrollOutcome.PinTooShort -> "PIN 需要 ${outcome.minimum} 位数字"
        PinEnrollOutcome.InvalidCredentials -> "主密码不正确"
        PinEnrollOutcome.SessionUnavailable -> "该库未解锁，请先用主密码打开它"
        PinEnrollOutcome.Enrolled -> ""
        is PinEnrollOutcome.Failed -> outcome.detail
    }
}
