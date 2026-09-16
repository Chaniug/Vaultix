/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import io.vaultix.data.repository.LocalUnlockEnrollment
import io.vaultix.domain.LocalUnlockEnrollOutcome
import io.vaultix.domain.LocalUnlockPrepareOutcome
import io.vaultix.domain.LocalUnlockPreparedEnrollment
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「生物识别 / 设备 PIN 快速解锁」的设置交互控制器（2026-09-16 新增）。
 *
 * ## 为什么单独一个类
 *
 * 用户原话：「**默认一个生物验证的指纹，管理解锁所有的库也可以吗**」。
 * 此前设置页启用快速解锁只能**一次一个库**（`SettingsViewModel.startQuickUnlockEnroll`），
 * 库多了就要点 N 遍指纹、走 N 遍流程。本类让「一次勾选 → 一次指纹」覆盖全部选中库。
 *
 * 抽成独立类而非塞回 [SettingsViewModel]，理由与 [PinSettingsController] 完全一致：
 * ① 那个 ViewModel 函数数本就贴着 detekt `TooManyFunctions` 的 40 上限；
 * ② 内聚 —— 这是一条自成一体的多步流程（勾选 → 输共用主密码 → 静默校验 → 一次认证 → 逐库落盘）。
 *
 * ⚠️ **下一个再往里加解锁手段时，同样要提取，而不是继续堆回 `SettingsViewModel`。**
 *
 * ## ★ 顺序是硬约束：先静默验完全部 → 弹**一次**指纹 → 连续逐库 wrap
 *
 * 快解的保护器 KEK 是 **auth-per-use**（`setUserAuthenticationParameters(0, …)`），
 * 一个 `Cipher` 实例**只对一次认证有效**。所以：
 *
 * | 顺序 | 结果 |
 * |---|---|
 * | ❌ 每库各准备一个 cipher、逐个弹指纹 | 第 2 个库的 cipher 未被授权 ⇒ `UserNotAuthenticatedException`（2026-09-14 闪退同源） |
 * | ❌ 先 wrap 再弹指纹 | 同上，`doFinal` 抛异常 |
 * | ✅ 认证前只校验备料、认证后用**同一个** cipher 连续 wrap 完 | 正确，且用户只被问一次 |
 *
 * 这也决定了「KDBX 主密码」必须在弹指纹**之前**收：包裹物要提前组装好。
 *
 * ## 与 PIN 侧的分工
 *
 * | | PIN（[PinSettingsController]） | 指纹（本类） |
 * |---|---|---|
 * | 保护器 | `PinKeyWrapper` + 硬件密钥 | Keystore KEK，**需系统认证** |
 * | 提示次数 | 每库 6 位 PIN，可共用同一值 | 一次 BiometricPrompt 覆盖全部 |
 * | 落盘时机 | 当场（校验后立即 wrap） | **认证后**（必须先备料 → 认证 → 再落盘） |
 *
 * ⚠️ **两套流程不能合并**：PIN 没有"认证窗口"这个概念，硬凑成一个流程会让 PIN 白等一次指纹。
 */
class BiometricEnrollController(
    private val vaultRepository: VaultRepository,
    private val scope: CoroutineScope,
    /**
     * 直接持有备料器，而不经 [VaultRepository] 转一层。
     *
     * 原因见 `VaultRepository.commitLocalUnlockEnrollForVaults` 的 KDoc：接口只暴露
     * 「认证后落盘」这一个方法，是为了让 `VaultRepositoryImpl` 的函数数守住 40 上限。
     * 认证前的「勾了哪些库 / 主密码对不对 / 要包什么明文」都在这里做。
     */
    private val enrollment: LocalUnlockEnrollment,
) {

    sealed interface EnrollState {
        data object Idle : EnrollState

        /**
         * 第一步：**勾选要覆盖哪些库**。
         *
         * ## 为什么必须让用户勾，而不是默默遍历全表
         *
         * 「一个指纹开所有库」是用户要的效果，但**库表里有几个库 ≠ 用户想设几个**：
         * 有些库用户可能根本不想启用（只读的共享库、别人的库）。
         * 若程序直接遍历全表，就是**静默改掉用户没同意改的配置** —— 而快速解锁
         * 直接改变解锁入口，是用户可感知的安全设置。
         *
         * ⇒ 默认**全选**（多数人的诉求就是"一次全开"，别让多数人多点一次），
         *   但**选择权在用户手上**且可取消。
         *
         * @param candidates 全部可选的库（含 KDBX）。空 = 还没取到。
         * @param selected 被勾选的库 id（默认 = [candidates] 全部）。
         */
        data class Picking(
            val candidates: List<Candidate> = emptyList(),
            val selected: Set<String> = emptySet(),
            val error: String? = null,
        ) : EnrollState {
            /** 勾选里是否含 KDBX 库 ⇒ 决定要不要走"收一次主密码"那一步。 */
            val hasKdbx: Boolean
                get() = candidates.any { it.id in selected && it.kind == VaultKind.KDBX }
        }

        /**
         * 第二步（仅当勾选含 KDBX）：**收一次共用主密码**。
         *
         * KDBX 会话里没有主密码（`KdbxSession` 只有整库明文，`Kdbx.unlock()` 用完即弃），
         * 而指纹信封只能包「主密码 + keyfile」这组**能重新开库的凭据** ⇒ 只能向用户要。
         *
         * ⚠️ **[targets] 必须原样带过来**：本状态已经离开 [Picking]，若不带上勾选结果，
         * 配齐时就不知道该给哪些库设 —— 只能退化成"全部库"，而那正是要避免的静默越权。
         * **状态迁移不能丢信息。**
         *
         * ⚠️ 这里是**一个**密码框而不是每库一个：勾了多个 KDBX 库时逐个问会很烦
         * （这正是「一个 PIN 打开多个库」当初的痛点）。校验时逐库独立判定 ——
         * 同一个密码对 A 库对、对 B 库错，会如实逐库报错。
         */
        data class AskingKdbxPassword(
            val targets: List<String> = emptyList(),
            val password: String = "",
            val error: String? = null,
        ) : EnrollState

        /**
         * 认证中：备料已就绪、`BiometricPrompt` 正等用户按指纹。
         *
         * 为什么不留在 [Picking]：这期间**不允许**用户改勾选 —— 备料列表已按当时的
         * 选择定型，改了也影响不到这次认证，会让用户以为改动生效了（假状态）。
         * 置为 busy 才是诚实的表达。
         */
        data object Authenticating : EnrollState

        /**
         * 第三步：**配齐结果**。
         *
         * 为什么不直接静默成功：配齐是**逐库**进行的，部分成功是真实状态
         * （某个 KDBX 库主密码打错，别的库却是好的）。直接关掉对话框会让用户
         * 以为"全都配好了"，而实际有库没配上 —— 那正是本项目反复强调的**假状态**。
         * 如实列出每个库的成败，用户才知道下一步该做什么。
         */
        data class Report(
            val succeeded: List<String>,
            val failed: List<FailureItem>,
        ) : EnrollState
    }

    /**
     * 勾选列表里的一项。
     *
     * `enabled` 用于标出"这个库已经能用指纹打开了" —— 用户可能只是想给**新库**补一个，
     * 看到既有状态才好判断要不要动它（同 [PinSettingsController.PinCandidate] 的取向）。
     */
    data class Candidate(
        val id: String,
        val name: String,
        val kind: VaultKind,
        val enabled: Boolean,
    )

    /** 结果页里单个库的失败项（库名 + 原因）。 */
    data class FailureItem(val vaultName: String, val reason: String)

    private val _state = MutableStateFlow<EnrollState>(EnrollState.Idle)
    val state: StateFlow<EnrollState> = _state.asStateFlow()

    /**
     * 交给 UI 弹认证的 cipher（仅 [EnrollState.Authenticating] 期间有值）。
     *
     * ⚠️ 用 `StateFlow` 而不是 `Channel`：语义是「现在有一把待认证的 cipher」，
     * 而它**只对一次认证有效**。用 Channel 时若 UI 因配置变更重建、订阅晚了一帧，
     * 事件会永远滞留（或反过来被重复消费到一把已作废的 cipher）。
     * StateFlow 让「有没有待认证」是可查询的状态；UI 拿到后回调 [onPromptHandled] 清掉。
     */
    private val _pendingCipher = MutableStateFlow<Cipher?>(null)
    val pendingCipher: StateFlow<Cipher?> = _pendingCipher.asStateFlow()

    /**
     * 本次认证要落盘的备料列表。
     *
     * ⚠️ **刻意不存在 UI state 里**，理由有二：
     * ① [LocalUnlockPreparedEnrollment] 里躺着 KDBX 的**主密码明文**，放进 state
     *    意味着它会被反复比较、可能在日志/转储里出现；
     * ② 它在认证期间不能变（见 [EnrollState.Authenticating] 的 KDoc）。
     *
     * 生命周期：写入 [prepareAndAuthenticate]；消费 [onAuthenticated]（成败都清）；
     * 丢弃 [dismiss] / [onAuthenticationFailed]（必须 `close()` 擦掉明文）。
     */
    private var prepared: List<LocalUnlockPreparedEnrollment> = emptyList()

    /**
     * 备料阶段的逐库结论，等认证回来与落盘结论**合并**后一起展示。
     *
     * 为什么不能只留 [prepared]：备料失败的库不在 [prepared] 里，若不复用这份结论，
     * 结果页就**完全看不见**它们 —— 用户会以为"全都好了"。这就是把结论缓存下来的原因。
     */
    private var prepareOutcomes: Map<String, LocalUnlockPrepareOutcome> = emptyMap()

    /**
     * 打开设置流程。
     *
     * @param prefilled 只勾这几个库（从库管理页对单个库点"启用"时传入）；
     *   空 = 由候选列表按"默认全选"填。
     */
    fun open(prefilled: Set<String> = emptySet()) {
        clearPrepared()
        _state.value = EnrollState.Picking(selected = prefilled)
        loadCandidatesAsync(prefilled)
    }

    fun dismiss() {
        // ⚠️ 用户取消时**必须**擦掉备料明文（KDBX 那份含主密码）：不擦就会在
        //    单例里留到下一次登记或进程结束，与项目「明文用完即擦」的取向冲突。
        clearPrepared()
        _pendingCipher.value = null
        _state.value = EnrollState.Idle
    }

    /** 勾选 / 取消勾选某个库。 */
    fun toggleTarget(vaultId: String) {
        updatePicking { current ->
            val next = if (vaultId in current.selected) {
                current.selected - vaultId
            } else {
                current.selected + vaultId
            }
            current.copy(selected = next, error = null)
        }
    }

    fun onKdbxPasswordChange(value: String) {
        val current = _state.value as? EnrollState.AskingKdbxPassword ?: return
        _state.value = current.copy(password = value, error = null)
    }

    /**
     * 第一步提交：校验勾选 + KDBX 分支朝向。
     *
     * 规则放控制器而不是只在 UI：UI 只负责把 `error` 画出来，判定只有一处。
     */
    fun confirm() {
        val current = _state.value as? EnrollState.Picking ?: return
        if (current.selected.isEmpty()) {
            _state.value = current.copy(error = "请至少选择一个库")
            return
        }
        if (current.hasKdbx) {
            // 勾选里有 KDBX ⇒ 必须收一次主密码（指纹信封只能包这组凭据）。
            // ⚠️ 把勾选结果带过去：本状态一离开 Picking，勾选就没有别的来源了。
            _state.value = EnrollState.AskingKdbxPassword(targets = current.selected.toList())
        } else {
            prepareAndAuthenticate(current.selected.toList(), masterPassword = "")
        }
    }

    /**
     * 第二步提交（仅 KDBX 分支）：静默校验全部选中库 → 一次认证。
     *
     * ⚠️ 目标列表从**本状态自己**取，不从别处缓存读 —— 单一来源才不会漂移。
     */
    fun confirmKdbxPassword() {
        val current = _state.value as? EnrollState.AskingKdbxPassword ?: return
        if (current.password.isBlank()) {
            _state.value = current.copy(error = "请输入主密码")
            return
        }
        prepareAndAuthenticate(current.targets, masterPassword = current.password)
    }

    /** UI 已把 cipher 交给 `BiometricPrompt` ⇒ 清掉，避免重组时重复弹。 */
    fun onPromptHandled() {
        _pendingCipher.value = null
    }

    /**
     * **认证前**：逐库静默校验 + 备料，然后取 cipher 交给 UI 弹**一次**认证。
     *
     * ## 为什么认证前就把失败挑出来
     *
     * 备料阶段失败（KDBX 主密码错 / 文件读不到 / 库没解锁）是**用户输入问题**，
     * 让用户按了指纹再告诉他"密码错"是本末倒置 —— 指纹白按了，而且他会以为
     * 是指纹出了问题。故先静默验，**只有备料成功的库才值得弹指纹**。
     *
     * ## 全部备料都失败时
     *
     * 直接进 [EnrollState.Report]，**不弹指纹**（没有东西要落盘，弹了纯属打扰）。
     */
    private fun prepareAndAuthenticate(targets: List<String>, masterPassword: String) {
        _state.value = EnrollState.Authenticating
        scope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                enrollment.prepareForVaults(targets, masterPassword)
            }
            // 备料成功的那些才交给认证；失败的先攒着，等认证回来并进 Report
            // （⚠️ 必须在此**就记下整份结论**：备料失败的库不在 `prepared` 里，
            //   若不复用这份 map，结果页会完全看不见它们 —— 用户以为"全都好了"）。
            prepareOutcomes = outcomes
            prepared = outcomes.values
                .filterIsInstance<LocalUnlockPrepareOutcome.Ready>()
                .map { it.prepared }
            if (prepared.isEmpty()) {
                // 没有任何东西要落盘 ⇒ 不弹指纹，直接出结果。
                _state.value = buildReport(committed = emptyMap())
                return@launch
            }
            // ⚠️ cipher 在**校验通过之后**才创建（反过来会在全都失败时也造一把用不上的 cipher）。
            val cipher = withContext(Dispatchers.IO) { vaultRepository.prepareLocalEnroll() }
            if (cipher == null) {
                // 设备无可用认证方式 ⇒ 备料失去意义，立刻擦掉（含 KDBX 主密码明文）。
                clearPrepared()
                _state.value = EnrollState.Report(
                    succeeded = emptyList(),
                    failed = listOf(FailureItem("—", "本设备未设置锁屏或生物识别，无法启用")),
                )
                return@launch
            }
            _pendingCipher.value = cipher
        }
    }

    /**
     * `BiometricPrompt` **认证成功**：用本次 cipher **连续** wrap 完所有备料。
     *
     * ⚠️ 必须连续循环、共用一个 cipher（理由见类 KDoc）。逐库独立成败：
     * 某个库 wrap 失败（KEK 因指纹变更永久失效）只回退该库，绝不牵连其它。
     */
    fun onAuthenticated(cipher: Cipher) {
        _pendingCipher.value = null
        val units = prepared
        prepared = emptyList()
        scope.launch {
            val committed = withContext(Dispatchers.IO) {
                vaultRepository.commitLocalUnlockEnrollForVaults(units, cipher)
            }
            _state.value = buildReport(committed)
        }
    }

    /**
     * 认证失败 / 被用户取消：**擦掉备料明文**并放弃本次流程。
     *
     * 不做"保留备料下次再试"的优化：cipher 已经作废（auth-per-use，一次认证一把），
     * 留着备料也没有 cipher 能用；而它里面躺着 KDBX 主密码，多留一刻都是风险。
     */
    fun onAuthenticationFailed() {
        _pendingCipher.value = null
        clearPrepared()
        _state.value = EnrollState.Idle
    }

    /** 关掉某库的快速解锁（结果页 / 列表上的辅助动作）。 */
    fun disable(vaultId: String) {
        scope.launch { vaultRepository.disableLocalUnlock(vaultId) }
    }

    private fun clearPrepared() {
        prepared.forEach { it.close() }
        prepared = emptyList()
        prepareOutcomes = emptyMap()
    }

    /**
     * 异步取候选库并填进对话框。
     *
     * ⚠️ 取列表要读库表 + 逐库查开关状态，是 IO ⇒ 必然有一帧"列表还没到"。
     * 若这一帧用户已经动手勾选，无条件 `copy(candidates=...)` 会**覆盖**他的操作
     * —— 表现是"我点的那个勾又跳回来了"。故只在**用户还没动过**时才套用预选/全选。
     * （"状态迁移不能丢信息"的同一条纪律，这里丢的是用户输入。）
     */
    private fun loadCandidatesAsync(prefilled: Set<String>) {
        scope.launch {
            val candidates = withContext(Dispatchers.IO) { loadCandidates() }
            val current = _state.value as? EnrollState.Picking ?: return@launch
            val next = when {
                // 用户已经动手 ⇒ 只补列表，保留他的选择。
                current.selected.isNotEmpty() -> current.selected
                // 从库管理页对单个库点"启用"：只勾那几个（用户意图明确）。
                prefilled.isNotEmpty() -> prefilled
                // 从设置页进来：默认全选（多数人诉求是"一次全开"），但可取消。
                else -> candidates.map { it.id }.toSet()
            }
            _state.value = current.copy(candidates = candidates, selected = next)
        }
    }

    /** 读候选库（含每库当前的快速解锁开关，供列表标注"已启用"）。 */
    private suspend fun loadCandidates(): List<Candidate> {
        val vaults = runCatching { vaultRepository.observeVaults().first() }.getOrDefault(emptyList())
        return vaults.map { vault ->
            Candidate(
                id = vault.id,
                name = vault.name,
                kind = vault.kind,
                enabled = runCatching {
                    vaultRepository.localUnlockAvailable(vault.id).first()
                }.getOrDefault(false),
            )
        }
    }

    /**
     * 把**备料阶段**与**落盘阶段**两段结论合并成一份结果页。
     *
     * 两段都要报：备料失败的库根本没参与落盘，若只看 [committed] 会**完全看不见**它们
     * —— 用户会以为"全都好了"。这就是 [prepareOutcomes] 要一路留到这时才清的原因。
     *
     * ⚠️ 本函数**不改状态**（只读 [prepareOutcomes]），改状态由调用方做 ——
     * 之前一版在函数里顺手赋值，导致"备料成功后弹指纹"这条路径上
     * `prepareOutcomes` 根本没被写进去（它只在全失败分支被调用），
     * 结果认证回来时报的结果页丢了所有备料失败项。**别把副作用藏在取值函数里。**
     */
    private suspend fun buildReport(
        committed: Map<String, LocalUnlockEnrollOutcome>,
    ): EnrollState.Report {
        // 库名用于结果展示（只有 id 用户看不懂是哪个库）。
        val names = runCatching {
            withContext(Dispatchers.IO) { vaultRepository.observeVaults().first() }
                .associate { it.id to it.name }
        }.getOrDefault(emptyMap())

        val succeeded = committed
            .filterValues { it is LocalUnlockEnrollOutcome.Enrolled }
            .keys.map { names[it] ?: it }
        val failed = buildList {
            // 备料阶段就挂掉的：逐个如实报（这些库连指纹都没等到）。
            prepareOutcomes.forEach { (id, outcome) ->
                prepareFailureReason(outcome)?.let { add(FailureItem(names[id] ?: id, it)) }
            }
            // 落盘阶段挂掉的。
            committed.forEach { (id, outcome) ->
                commitFailureReason(outcome)?.let { add(FailureItem(names[id] ?: id, it)) }
            }
        }
        // 报告已生成 ⇒ 备料结论的使命结束（不是"清场"，是逻辑上的消费完）。
        prepareOutcomes = emptyMap()
        return EnrollState.Report(succeeded = succeeded, failed = failed)
    }

    /**
     * 备料阶段单库失败的原因文案（`Ready` 返回 null：成功项不进失败列表）。
     *
     * `InvalidCredentials` 特意说得具体（"主密码不正确"），因为这是用户当场能改的
     * —— 写成泛泛的"启用失败"他就不知道要改什么。
     */
    private fun prepareFailureReason(outcome: LocalUnlockPrepareOutcome): String? =
        when (outcome) {
            is LocalUnlockPrepareOutcome.Ready -> null
            LocalUnlockPrepareOutcome.InvalidCredentials -> "主密码不正确"
            is LocalUnlockPrepareOutcome.SourceUnavailable -> outcome.detail
            is LocalUnlockPrepareOutcome.Failed -> outcome.detail
        }

    /**
     * 落盘阶段单库失败的原因文案（`Enrolled` 返回 null）。
     *
     * ⚠️ 与解锁页的 `localUnlockFailureText` **不是一回事**，别合并：这里描述的是
     * **登记**阶段（能不能把信封写下去），解锁页描述的是**开信封**阶段
     * （指纹对了但信封打不开）。合并会让"主密码可能已变更"这种解锁期的话
     * 出现在登记结果里，词不达意。
     */
    private fun commitFailureReason(outcome: LocalUnlockEnrollOutcome): String? =
        when (outcome) {
            LocalUnlockEnrollOutcome.Enrolled -> null
            is LocalUnlockEnrollOutcome.Failed -> outcome.detail
        }

    private fun updatePicking(transform: (EnrollState.Picking) -> EnrollState.Picking) {
        val current = _state.value as? EnrollState.Picking ?: return
        _state.value = transform(current)
    }
}
