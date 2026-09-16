package io.vaultix.vaultix.ui.unlock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.KdbxUnlockOutcome
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.domain.PinUnlockOutcome
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.ui.common.TwoFactorProvider
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.toUnlockUiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 解锁页（Docs/08 S6）：主密码 →（若 2FA）验证码步骤 → 解锁。
 *
 * 2FA 期间主密码保留在内存（完成/放弃即清）；库信息从
 * [VaultRepository.observeVaults] 按 vaultId 取。
 *
 * 两条**语义完全不同**的路径（`.ai/ISSUES.md` #60）：
 * | 来源 | 状态 | 页面内容 | 恢复成本 |
 * |---|---|---|---|
 * | 真锁（超时 / 冷启动 / 退出数据库）| [UiState.viewLocked] = false | 主密码（+2FA）+ 生物识别 | 联网重登 |
 * | 查看层锁（主页锁按钮）| [UiState.viewLocked] = true | **只有生物识别** | 一次认证 |
 *
 * ⚠️ 查看层锁分支**绝不能**渲染主密码 / 2FA 区块：那会让用户以为密钥被清了，
 * 而实际上密钥就在内存里 —— 用户原话「填充时解锁完还要再验证一次，逻辑太稀烂」。
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository,
    private val sessionRepository: VaultSessionRepository,
) : ViewModel() {

    data class TwoFactorUi(
        val providers: List<Int>,
        val provider: Int,
    )

    data class UiState(
        val vault: VaultSummary? = null,
        /**
         * 库列表**已经到达**但解析不出可解锁的库（一个库都没有 / 该库已被移除）。
         *
         * 与 `vault == null` 的区别：`vault == null` 也可能是"首帧还没到"（此时要继续
         * loading）；本值为 true 表示"确认没有可解锁的库"，UI 必须立刻离开解锁页，
         * 否则就是永久转圈（2026-09-12 修复的启动死锁）。
         */
        val noVaultToUnlock: Boolean = false,
        val password: String = "",
        val passwordVisible: Boolean = false,
        val submitting: Boolean = false,
        val error: UnlockUiError? = null,
        val twoFactor: TwoFactorUi? = null,
        val localUnlockAvailable: Boolean = false,
        /**
         * **自动弹出被系统侧终止**的累计次数。
         *
         * 为什么值得进 state：`AutoPromptQuickUnlock` 用一次性守卫 `prompted` 保证「自动弹一次」，
         * 但那个守卫是在**弹窗真正出现之前**置位的。系统侧终止（冷启动首帧窗口还没可见 /
         * 生物硬件尚未就绪）会把这一次机会白白吃掉 ⇒ 用户什么都没做却再也不弹
         * （2026-09-13 用户报告「生物验证不是 100% 能在覆盖安装后弹出」）。
         * 这个计数就是让 UI 把机会还回来的信号。
         * ⚠️ **用户主动放弃不计入** —— 那种情况必须继续保持「不再打扰」。
         */
        val autoPromptAborts: Int = 0,
        /**
         * 目标库处于**查看层锁**（密钥仍在内存）。
         *
         * true 时页面走「仅认证」分支：只弹生物识别，不渲染主密码 / 2FA，
         * 认证通过即 `clearViewLock` 回主界面。
         */
        val viewLocked: Boolean = false,
        /** 本次生物识别是为查看层锁发起的（成功分支据此只清标记、不重建会话）。 */
        val viewUnlockStarted: Boolean = false,
        /** 应用内 PIN 入口是否可见（按库）。取向同 [localUnlockAvailable]：只看持久化开关。 */
        val pinUnlockAvailable: Boolean = false,
        /** 是否已切到 PIN 输入模式。 */
        val pinMode: Boolean = false,
        /**
         * 已输入的 PIN **位数**（界面只画圆点）。
         *
         * ⚠️ 刻意**不把 PIN 本身放进 state**：state 会被反复读取/比较（重组、日志、
         * 状态转储），让一个明文口令在里面流动没有意义 —— 它只在下一次提交时需要，
         * 存在 [pinBuffer] 里、提交后立即抹掉就够了。
         */
        val pinLength: Int = 0,
        val pinError: String? = null,
        val pinSubmitting: Boolean = false,
    )

    sealed interface Event {
        data object Unlocked : Event

        /**
         * UI 收到后立即弹 BiometricPrompt（cipher 已 init，等待用户认证）。
         *
         * @param rest 除目标库之外、本次认证成功后可**顺带解封**的库（只含已启用快速
         *   解锁的锁定库）。空 = 只开目标库（查看锁场景恒为空）。
         *   ⚠️ 必须随事件携带而不是让 `completeLocalUnlock` 自己去查：认证期间
         *   库列表可能变化，届时再查会解封用户**发起认证时并不存在**的库。
         */
        data class PromptForUnlock(
            val cipher: javax.crypto.Cipher,
            val rest: List<String> = emptyList(),
        ) : Event
    }

    /**
     * 要解锁的库 id。
     *
     * 两种来源（2026-09-11 起）：
     *  1. [UnlockRoute] 带参数进入（从库列表点某个锁定的库）→ 直接用该 id；
     *  2. [UnlockEntryRoute] 无参数进入（**根导航在锁定态直达**，对齐 Bitwarden
     *     `VaultUnlockRoute.Standard`）→ 自动选中第一个已锁定的库。
     *
     * 用 `var` 是因为第 2 种情况下库列表是异步到达的；在解析出之前保持空串，
     * 各入口方法会先判空。
     */
    var vaultId: String = savedStateHandle.get<String>(ARG_VAULT_ID).orEmpty()
        private set

    /**
     * 构造期就定下查看锁：路由带 vaultId 时（查看层锁场景）必须**同步**读标记。
     *
     * 为什么不能只靠下面的流订阅：自动弹认证的判定（[UnlockScreen]）在首帧就生效，
     * 若 `viewLocked` 要等一帧才到，首帧会按「真锁」分支去弹本地快速解锁 ——
     * 没启用快速解锁的用户在那一步什么都点不出来。
     */
    private val _state = MutableStateFlow(
        UiState(
            viewLocked = vaultId.isNotBlank() && sessionRepository.isViewLocked(vaultId),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * **提前备好的** BiometricPrompt cipher（见 [prewarmCipher]）。
     *
     * 只在主线程读写，故无需额外同步。
     */
    private var preparedCipher: javax.crypto.Cipher? = null

    /**
     * PIN 输入的**明文缓冲**。刻意不放进 [UiState]（见 `pinLength` 的说明）；
     * 每次提交后立刻抹掉，不让它比这次提交活得更久。
     */
    private var pinBuffer: String = ""

    init {
        viewModelScope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                if (vaultId.isBlank()) vaultId = resolveTargetVaultId(vaults)
                val target = vaults.firstOrNull { v -> v.id == vaultId }
                _state.update {
                    it.copy(
                        vault = target,
                        viewLocked = target != null && sessionRepository.isViewLocked(target.id),
                        // 首帧未到（vaults 尚未发射）时不会走到这里，故此处可判定为"确认无库"
                        noVaultToUnlock = target == null,
                    )
                }
            }
        }
        // 查看层锁标记变化：**只更新、不新建** viewLocked（用户认证成功清掉标记时这里会归位）。
        viewModelScope.launch {
            sessionRepository.observeViewLockedVaultIds().collect { locked ->
                _state.update { it.copy(viewLocked = vaultId.isNotBlank() && vaultId in locked) }
            }
        }
        viewModelScope.launch {
            // ⚠️ 这里**绝不能**读 `var vaultId`：负责补它的那条流与本条**并发**收集同一个
            // `observeVaults()`，谁先处理同一次发射是不确定的。若本条先到，读到的还是空串
            // ⇒ 提前跳过；而 `localUnlockAvailable` 的**唯一写入点**正在下面
            // ⇒ 状态永久停在初值 `false` ⇒ **指纹入口不渲染、生物识别也不自动弹**。
            // 用户实测症状（2026-09-13）：「必须清掉后台重开才看得到指纹解锁」——
            // 因为重建 ViewModel 才会有一次重掷的机会（#88）。
            // 现改为**从本次发射自己解析**目标库（[resolveTargetVaultId]），
            // 不再依赖任何跨协程写入 ⇒ 竞态从根上消失。
            vaultRepository.observeVaults()
                .map { vaults -> resolveTargetVaultId(vaults) }
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                .collectLatest { id ->
                    // 目标库确定后再校正一次查看锁（标记流可能先于选库到达）。
                    _state.update { it.copy(viewLocked = sessionRepository.isViewLocked(id)) }
                    vaultRepository.localUnlockAvailable(id).collect { available ->
                        _state.update { it.copy(localUnlockAvailable = available) }
                        if (available) prewarmCipher(id)
                    }
                }
        }
        // PIN 可用性：**单独起一条协程**而不接在上面那条里 ——
        // 上面那个 `localUnlockAvailable(id).collect {}` 永不结束，
        // 在它后面顺序再写一个 `collect` 的话**永远不会被执行**（这是极易踩的坑）。
        // 同样遵守 #88 的纪律：从**一次发射**自己解析目标库，不读跨协程写入的 `var vaultId`。
        viewModelScope.launch {
            vaultRepository.observeVaults()
                .map { vaults -> resolveTargetVaultId(vaults) }
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                .collectLatest { id ->
                    vaultRepository.pinUnlockAvailable(id).collect { available ->
                        _state.update { it.copy(pinUnlockAvailable = available) }
                    }
                }
        }
    }

    // ---- 应用内 PIN 解锁（解锁便利，非找回手段）----

    /** 切到 PIN 输入模式。仅在 [UiState.pinUnlockAvailable] 为真时有意义。 */
    fun enterPinMode() {
        if (!_state.value.pinUnlockAvailable) return
        pinBuffer = ""
        _state.update {
            it.copy(pinMode = true, pinLength = 0, pinError = null, error = null)
        }
    }

    fun exitPinMode() {
        pinBuffer = ""
        _state.update { it.copy(pinMode = false, pinLength = 0, pinError = null) }
    }

    /**
     * 输入一位数字。
     *
     * ★ **满 [PIN_MIN_LENGTH] 位即自动提交**：6 位数字之后再要按一次「确认」是多余动作
     * （系统锁屏同款）。因此设置页建 PIN 时也要求**恰好** 6 位 —— 否则自动提交会
     * 把长于 6 位的 PIN 提前截断提交，用户永远解不开。
     */
    fun onPinDigit(digit: Char) {
        val current = _state.value
        if (!current.pinMode || current.pinSubmitting) return
        if (digit !in '0'..'9') return
        if (pinBuffer.length >= PIN_MIN_LENGTH) return
        pinBuffer += digit
        _state.update { it.copy(pinLength = pinBuffer.length, pinError = null) }
        if (pinBuffer.length == PIN_MIN_LENGTH) {
            viewModelScope.launch { submitPin() }
        }
    }

    fun onPinBackspace() {
        val current = _state.value
        if (!current.pinMode || current.pinSubmitting) return
        if (pinBuffer.isEmpty()) return
        pinBuffer = pinBuffer.dropLast(1)
        _state.update { it.copy(pinLength = pinBuffer.length, pinError = null) }
    }

    /**
     * 提交 PIN。
     *
     * 按**库类型分流**（与 #93 的解锁路径分流同一条纪律）：Bitwarden 侧解出的是
     * 会话密钥、KDBX 侧解出的是凭据，两者后续动作不同 —— 混走是错语义。
     */
    private suspend fun submitPin() {
        val id = vaultId
        val vault = _state.value.vault
        if (id.isBlank() || vault == null) {
            // 库都定位不到，PIN 无从谈起 ⇒ 退回主密码界面，别把用户卡在一个空面板上
            pinBuffer = ""
            _state.update { it.copy(pinMode = false, pinLength = 0, pinSubmitting = false) }
            return
        }
        val pin = pinBuffer
        _state.update { it.copy(pinSubmitting = true, pinError = null) }
        val outcome = withContext(Dispatchers.IO) {
            if (vault.kind == VaultKind.KDBX) {
                vaultRepository.completePinUnlockKdbx(id, pin)
            } else {
                vaultRepository.completePinUnlock(id, pin)
            }
        }
        // 无论成败都把 PIN 从内存抹掉：它不该活过这次提交。
        pinBuffer = ""
        if (outcome == PinUnlockOutcome.Opened) {
            _state.update { it.copy(pinSubmitting = false, pinMode = false, pinLength = 0) }
            _events.send(Event.Unlocked)
            return
        }
        _state.update {
            it.copy(
                pinSubmitting = false,
                // 清空圆点：用户下一次输入从头开始（保留已输入的位会让人以为多输了）
                pinLength = 0,
                pinError = pinFailureText(outcome),
            )
        }
    }

    /**
     * PIN 失败的文案。
     *
     * ⚠️ **四态必须给出四句不同的话**：它们的用户动作互不相同 —— 重试 / 等主密码 /
     * 重设 / 报告数据损坏。合成一句「解锁失败」等于把用户丢在原地，这也是项目
     * 反复强调的「假状态」在文案层的表现。
     */
    private fun pinFailureText(outcome: PinUnlockOutcome): String = when (outcome) {
        is PinUnlockOutcome.WrongPin ->
            "PIN 不正确，还可尝试 ${outcome.remainingAttempts} 次"
        PinUnlockOutcome.LockedOut -> "PIN 尝试次数过多，请改用主密码解锁后在设置里重设"
        PinUnlockOutcome.StaleCredentials ->
            "PIN 正确，但该库的主密码已在别处变更，请用主密码解锁后重设"
        is PinUnlockOutcome.Unavailable -> outcome.detail
        PinUnlockOutcome.Opened -> ""
    }

    /**
     * 从**一次**库列表发射里解析出「本次要解锁哪个库」。
     *
     * 规则（顺序有意义）：**先看查看层锁** —— 查看锁的库在会话层面是「已解锁」
     * （密钥仍在内存），`!it.unlocked` 在它身上为 false；若先按「第一个未解锁」选，
     * 用户按了主页锁按钮却会被要求解锁**另一个**库（ISSUES #60 第 1c 步）。
     *
     * ⚠️ 本函数只把 [vaultId] 当**「路由带参」**用（来自 `savedStateHandle`，构造期即定，
     * 且必须仍在该次发射的列表里）。**绝不依赖它被异步补写后的值** ——
     * 那正是 #88 竞态的根源：两个协程并发收集同一条流，读 `var` 的时机不确定。
     */
    private fun resolveTargetVaultId(vaults: List<VaultSummary>): String {
        val routed = vaultId
        if (routed.isNotBlank()) {
            // 路由带参进入（从库列表点某个锁定的库 / 查看层锁）：**只认这个库**。
            // 它已不在列表里 ⇒ 视为「没有可解锁的目标」（保持既有语义：不另选一个库顶上）。
            return if (vaults.any { it.id == routed }) routed else ""
        }
        // 无参数进入（根导航直达）：自动选中目标库 —— 先查看层锁，再第一个未锁定的库。
        val viewLocked = vaults.firstOrNull { sessionRepository.isViewLocked(it.id) }
        val firstLocked = vaults.firstOrNull { !it.unlocked }
        return (viewLocked ?: firstLocked)?.id.orEmpty()
    }

    /** 用户点了「生物识别 / 设备 PIN 解锁」：准备解密 Cipher 并交给 UI 弹认证。 */
    fun startLocalUnlock() = startBiometricUnlock(viewLock = false)

    /**
     * 查看层锁的认证入口：**同一套生物识别，语义只是"证明是本人"**。
     *
     * 与 [startLocalUnlock] 的唯一区别是成功分支不同（见 [completeLocalUnlock]）：
     * 这里不清会话、不重建密钥，只把查看锁标记抹掉。
     */
    fun startViewUnlock() = startBiometricUnlock(viewLock = true)

    /**
     * 后台把 BiometricPrompt 要用的 cipher 先备好。
     *
     * 为什么值得多此一举：自动弹认证（见 `UnlockScreen.AutoPromptQuickUnlock`）要等
     * `localUnlockAvailable` 首帧 → 再 `prepareLocalUnlock` → 再等 Activity RESUMED，
     * 三次握手串起来就是用户感知的「指纹不能第一时间弹出来」。可用状态一到位就
     * 提前把最后一步做掉，等真正要弹时只剩「把 cipher 交给系统」这一件事。
     */
    private fun prewarmCipher(id: String) {
        if (preparedCipher != null) return
        viewModelScope.launch {
            preparedCipher = withContext(Dispatchers.IO) {
                runCatching { vaultRepository.prepareLocalUnlock(id) }.getOrNull()
            }
        }
    }

    private fun startBiometricUnlock(viewLock: Boolean) {
        val current = _state.value
        if (current.submitting) return
        if (viewLock && !current.viewLocked) return
        if (!viewLock && current.localUnlockAvailable.not()) return
        _state.update { it.copy(submitting = true, viewUnlockStarted = viewLock, error = null) }
        viewModelScope.launch {
            // ⚠️ Keystore / 解密都在**后台**做：`viewModelScope` 默认跑在主线程，
            // 而 `prepareLocalUnlock` 要初始化一个 AES Cipher（首次还会触发 keystore
            // 解密），冷启动或覆盖安装后首次进入时足以让首帧渲染卡住 —— 表现就是
            // 用户看到的「指纹弹窗不能第一时间出来」。
            val cipher = preparedCipher ?: withContext(Dispatchers.IO) {
                vaultRepository.prepareLocalUnlock(vaultId)
            }
            if (cipher == null) {
                // 密钥包不可用（KEK 被指纹变更失效 / 从未启用）。
                // ⚠️ 查看锁分支要**摘掉查看锁**再落回主密码表单：否则页面会停在
                // 「只有生物识别按钮、但按钮必然失败」的死角（用户点不出任何出路）。
                _state.update {
                    it.copy(
                        submitting = false,
                        viewUnlockStarted = false,
                        viewLocked = if (viewLock) false else it.viewLocked,
                        error = UnlockUiError.Unknown("本地解锁不可用，请用主密码登录"),
                    )
                }
            } else {
                // cipher 一次性：交出去就作废缓存，下次重新准备。
                preparedCipher = null
                // ★ 一并带上"其余待解锁的库"：用户诉求是**一次指纹开所有库**，
                //   而不是只开被点的那一个（2026-09-16）。
                // ⚠️ 查看锁场景没有"其余库"可言 —— 它的语义只是"证明是本人"，
                //   不涉及任何库的解封，多带列表只会白跑一轮。
                val rest = if (viewLock) emptyList() else candidateVaultIds(vaultId)
                _events.send(Event.PromptForUnlock(cipher, rest))
            }
        }
    }

    /**
     * 除 [target] 之外，本次还应当顺带解封的库（**只挑已启用快速解锁的锁定库**）。
     *
     * ## 为什么必须过滤成"已启用"的
     *
     * 库列表里通常既有启用了指纹的库，也有只走主密码的库。对后者调
     * `prepareLocalUnlock` 必然返回 null（没有信封），白跑一趟还多算一次"失败"，
     * 结果页会报一堆莫名其妙的"未打开" —— 而用户根本没打算开它们。
     *
     * ## 为什么不包含已解锁的库
     *
     * 已经解锁的库密钥就在内存里，再解封一次等于把同一把密钥写第二遍，白做一轮
     * KDF 派生（同 [completeLocalUnlock] 对查看锁分支的告诫）。
     */
    private suspend fun candidateVaultIds(target: String): List<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                vaultRepository.observeVaults().first()
                    .filter { it.id != target && !it.unlocked }
                    .map { it.id }
                    .filter { id ->
                        runCatching { vaultRepository.localUnlockAvailable(id).first() }
                            .getOrDefault(false)
                    }
            }.getOrDefault(emptyList())
        }

    /**
     * BiometricPrompt 认证成功（携带本次 cipher）：解封本地密钥建立会话。
     *
     * @param forViewLock 本次认证是为查看层锁发起的 → 只清标记，**不重新解封密钥**
     *   （密钥本来就在会话里；再解封一次等于把同一把密钥写第二遍，白做一轮 KDF 派生）。
     * @param rest 除目标库之外可顺带解封的库（见 [Event.PromptForUnlock.rest]）；
     *   查看锁场景传空。
     */
    fun completeLocalUnlock(
        cipher: javax.crypto.Cipher,
        forViewLock: Boolean,
        rest: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            if (forViewLock) {
                sessionRepository.clearViewLock(vaultId)
                _state.update {
                    it.copy(submitting = false, viewUnlockStarted = false, error = null)
                }
                _events.send(Event.Unlocked)
                return@launch
            }
            // 同上：unwrap（Keystore 解密 + 密钥重建）不占主线程。
            // ⚠️ 按库类型分流（定稿 §4）：Bitwarden 的包裹物是「对称密钥」，
            // KDBX 的是「主密码 + keyfile」—— 后者还要真的开一次库，
            // 因此**不能**共用同一条路径（混用会解出完全错误的语义）。
            // ★ 2026-09-16：分流逻辑与"顺带解封其余库"一并抽到 LocalUnlockFanout，
            //   与 AutofillActivity 共用同一份实现（此前两处各写一遍，只可能修好一处）。
            val fanout = withContext(Dispatchers.IO) {
                LocalUnlockFanout.unlockAll(
                    repository = vaultRepository,
                    first = vaultId,
                    rest = rest,
                    cipher = cipher,
                )
            }
            val result = fanout.first
            val isKdbx = _state.value.vault?.kind == VaultKind.KDBX
            if (result == UnlockResult.Success) {
                _state.update {
                    it.copy(
                        submitting = false,
                        viewUnlockStarted = false,
                        password = "",
                        twoFactor = null,
                        error = null,
                    )
                }
                _events.send(Event.Unlocked)
            } else {
                // 仓储给的**具体原因**优先（例如「文件读不到了，请重新选择」），
                // 没有具体原因时才用按库类型区分的兜底文案。
                val detail = (result as? UnlockResult.Unknown)?.detail
                _state.update {
                    it.copy(
                        submitting = false,
                        viewUnlockStarted = false,
                        error = UnlockUiError.Unknown(detail ?: localUnlockFailureText(isKdbx)),
                    )
                }
            }
        }
    }

    /**
     * 本地解锁失败时的兜底文案。
     *
     * ⚠️ KDBX 必须**单独一句**：它的包裹物是主密码，「指纹过了但打不开」
     * 的唯一合理解释是主密码被改过（定稿 §4.4 D3）。若沿用 Bitwarden 那句
     * 「请用主密码登录」，用户会以为指纹坏了 —— 而他真正需要做的是**输新主密码**。
     *
     * 说明：ViewModel 层没有 `stringResource`，故此处返回字面量；
     * `detail` 非空时（仓储给了具体原因）优先用 detail。
     */
    private fun localUnlockFailureText(isKdbx: Boolean): String = if (isKdbx) {
        "主密码可能已在别处变更，请输入当前主密码"
    } else {
        "本地解锁失败，请用主密码登录"
    }

    /** 认证对话框被系统错误终止（非用户取消）时收起 busy 态。 */
    fun onBiometricPromptDismissed() {
        _state.update { it.copy(submitting = false, viewUnlockStarted = false) }
    }

    /**
     * BiometricPrompt 报错（用户取消 / 超时 / 硬件不可用 / 被系统撤销…）。
     *
     * **无论哪种错误都必须把 submitting 复位**——它是「认证中」的唯一标志，
     * 与指纹按钮、主密码按钮的 enabled 状态、转圈指示器都直接挂钩；
     * 漏复位就会把整页锁死（用户体感：指纹解锁按钮点了没反应）。
     *
     * 用户/系统取消保持安静（那是「改用主密码」的正常路径），其余错误给出原因，
     * 否则用户只会看到按钮毫无反应。
     *
     * ⚠️ `systemAbort` 与 `cancelled` **必须分开**：前者表示「这次压根没弹成」，
     * 要把自动弹出的机会还回来（[UiState.autoPromptAborts]）；后者只表示「安静收起」。
     * 将两者混为一谈，就会得到「覆盖安装后有时候不弹、且再也不弹」。
     */
    fun onBiometricPromptError(message: String, cancelled: Boolean, systemAbort: Boolean) {
        _state.update {
            it.copy(
                submitting = false,
                viewUnlockStarted = false,
                error = if (cancelled) it.error else UnlockUiError.Unknown(message),
                autoPromptAborts = if (systemAbort) it.autoPromptAborts + 1 else it.autoPromptAborts,
            )
        }
    }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(passwordVisible = visible) }

    fun selectTwoFactorProvider(provider: Int) = _state.update { state ->
        val tf = state.twoFactor ?: return@update state
        state.copy(twoFactor = tf.copy(provider = provider), error = null)
    }

    /** 放弃 2FA 回到密码步骤（密码保留）。 */
    fun backToPassword() = _state.update { it.copy(twoFactor = null, error = null) }

    fun submit() {
        val current = _state.value
        if (current.password.isBlank()) {
            _state.update { it.copy(error = UnlockUiError.FieldsMissing) }
            return
        }
        if (current.submitting) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // ★ KDBX 与 Bitwarden 的解锁是**两条完全不同的路**（M2 阶段 A）：
            // KDBX 认的是文件（离线、无账号、无 2FA），Bitwarden 认的是账号（联网 + 可能 2FA）。
            // 走错一条的后果不是「报错」而是「报错信息完全对不上」（如 KDBX 库被拿去联网 prelogin）。
            val target = _state.value.vault
            // PBKDF2 / Argon2 派生是秒级 CPU 活（Bitwarden 默认 600k 次迭代），
            // 必须离开主线程，否则「登录」按钮按下到转圈之间会整页卡住。
            val result = withContext(Dispatchers.IO) {
                if (target?.kind == VaultKind.KDBX) {
                    vaultRepository.unlockKdbxVault(vaultId, current.password)
                } else {
                    vaultRepository.unlockVault(vaultId, current.password)
                }
            }
            handleSubmitResult(result, submitTwoFactor = false)
        }
    }

    /** 2FA 步骤：提交验证码完成解锁。 */
    fun submitCode(code: String) {
        val current = _state.value
        val tf = current.twoFactor ?: return
        if (current.submitting || code.isBlank()) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                vaultRepository.unlockVaultWithTwoFactor(
                    vaultId = vaultId,
                    masterPassword = current.password,
                    provider = tf.provider,
                    code = code.trim(),
                )
            }
            handleSubmitResult(result, submitTwoFactor = true)
        }
    }

    private suspend fun handleSubmitResult(result: UnlockResult, submitTwoFactor: Boolean) {
        when (result) {
            UnlockResult.Success -> {
                // 成功解锁 = 密钥已在内存 → 查看锁标记失去意义（真锁 / 退出数据库后
                // 残留的标记若不清，根导航会立刻把用户弹回解锁页）。
                sessionRepository.clearViewLock(vaultId)
                _state.update {
                    it.copy(
                        submitting = false,
                        viewLocked = false,
                        viewUnlockStarted = false,
                        password = "",
                        twoFactor = null,
                        error = null,
                    )
                }
                _events.send(Event.Unlocked)
            }
            is UnlockResult.TwoFactorRequired -> {
                // 切到验证码步骤；主密码保留在内存直到完成/放弃
                _state.update {
                    it.copy(
                        submitting = false,
                        error = null,
                        twoFactor = TwoFactorUi(
                            providers = result.providers,
                            provider = TwoFactorProvider.defaultOf(result.providers),
                        ),
                    )
                }
            }
            UnlockResult.TwoFactorInvalid -> {
                _state.update { it.copy(submitting = false, error = UnlockUiError.TwoFactorInvalid) }
            }
            else -> {
                if (submitTwoFactor &&
                    (result == UnlockResult.Network || result is UnlockResult.Unknown)
                ) {
                    // 网络等瞬时错误：留在 2FA 步骤可重试
                    _state.update { it.copy(submitting = false, error = result.toUnlockUiError()) }
                } else {
                    _state.update {
                        it.copy(
                            submitting = false,
                            password = "",
                            twoFactor = null,
                            error = result.toUnlockUiError(),
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"
    }
}
