package io.vaultix.vaultix.ui.unlock

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.domain.PinUnlockOutcome
import io.vaultix.domain.RoomResealOutcome
import io.vaultix.domain.RoomResealRepository
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.UnlockRecoveryRepository
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.TwoFactorProvider
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.toUnlockUiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
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
    /**
     * 指纹门锁失效后的善后（批次 4，定稿 §6）。
     *
     * 解锁页是**用户主动解锁**的入口 —— 只有这里有 UI 能如实呈现
     * 「指纹锁需要重新启用 / 已关闭」并给出下一步，故失效矩阵接在这个调用点。
     * `AutofillActivity` 的自动填充路径不接（没有可呈现的界面，降级留给这里）。
     */
    private val unlockRecovery: UnlockRecoveryRepository,
    /**
     * 房间信封重包（批次 4，定稿 §6 目标 3）：某库主密码变更后，把它的信封
     * 用刚输的密码重包一遍，让快速解锁自此自愈（否则每周都要重输主密码）。
     */
    private val roomReseal: RoomResealRepository,
    /**
     * 生效范围（`house_room::` 的镜像）。
     *
     * ⚠️ 为**解锁提速**而注入（2026-09-29）：候选库筛选需要「哪些库在快速解锁
     * 范围内」这一个事实，而它就在偏好里（DataStore，**零 Keystore**）。
     * 旧实现绕道 `VaultRepository.fingerprintQuickUnlockAvailable(id)` ——
     * 那条路每个库都要过一次 Keystore（见 [candidateVaultIds] 的 KDoc）。
     */
    private val preferences: VaultixPreferences,
    /**
     * 只用来把**我们自己写的**文案（[LocalUnlockMessage] 里的资源 id）解析成一句话。
     *
     * ⚠️ 为什么不把文案从 ViewModel 挪到 Composable：解锁失败的原因要经
     * `UnlockUiError.Unknown(message)` 往上抛，而 `Unknown` 携带的是 `String?`
     * （它有前缀「出错了：」的语义，见 `ErrorText.kt`）。改成携带资源 id 会牵动
     * 整个 `UnlockUiError` 家族的契约 —— 那是另一件事，不该顺手做。
     * ⇒ 本轮只把「文案存在哪里」修对（`strings.xml`），解析点仍在 ViewModel。
     */
    @ApplicationContext private val context: Context,

    /**
     * ★ 测试接缝（生产由 Hilt 提供一个**全空**实例，行为与从前一字不差）。
     *
     * ## 为什么必须是构造参数（#174 / #176 / #220 三次返工的共同根因）
     *
     * `init` 在**构造期间**执行。此前接缝是三个「构造后赋值」的 `var`，于是
     * `init` 里那句「接缝为 null 才自启」**恒为真** ——
     * 观察协程永远拿 `viewModelScope`（= `Dispatchers.Main`）当作用域，
     * 测试注入的 `TestScope` 一次都没生效；而 `observersStarted` 的 CAS 守卫
     * 又让测试工厂随后那次 `startObservers()` **静默返回**，连日志都没有。
     *
     * 症状就是 #174 修了三轮仍在 CI 偶发：`prewarmCipher` 里
     * `withContext(Dispatchers.IO)` 真的跳了线程池，回到 `Dispatchers.Main` 时
     * `@After resetMain()` 已把它卸下 ⇒ `TestMainDispatcher.isDispatchNeeded` 抛
     * `Dispatchers.Main was accessed…`。异常归到**下一条**正在跑的用例头上，
     * 所以每次都换受害者、看起来像"随机"。
     *
     * ⇒ 接缝的赋值时机必须早于「用到它的第一个协程」，构造参数是唯一能保证
     *   这一点的位置。（默认值不行、Hilt 注入 `CoroutineScope` 也不行，
     *   四种失败尝试见 [UnlockViewModelSeams] 的 KDoc。）
     */
    private val seams: UnlockViewModelSeams = UnlockViewModelSeams(),
) : ViewModel() {

    /**
     * 观察协程是否已启动（[startObservers] 的幂等守卫）。
     *
     * 生产在 `init` 里置 true；单测由测试工厂在"设完接缝之后"调 [startObservers] 置 true。
     */
    private val observersStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 本页所有"发起即返回"的协程都挂在这里。生产 = `viewModelScope`，测试 = [seams].scope。 */
    private val scope: CoroutineScope get() = seams.scope ?: viewModelScope

    /** 本页所有"后台活"用的调度器。生产 = `Dispatchers.IO`，测试 = [UnlockViewModelSeams.io]。 */
    private val io: CoroutineDispatcher get() = seams.io ?: Dispatchers.IO

    /**
     * 真正执行"后台活"的调度器。生产 = [io]；测试 = [UnlockViewModelSeams.ioContextOverride]。
     *
     * ## #176：为什么在 [io] 之外还要一个"拦截"口
     *
     * 把 [io] 换成 `StandardTestDispatcher(testScheduler)` 只让虚拟时钟推得动，
     * **不等于"续体不跳线程"**：`StandardTestDispatcher` 跑任务时**不恢复**原协程的
     * `ContinuationInterceptor` ⇒ 跨 `withContext(io)` 的续体恢复时，上下文里既没有
     * 拦截器也没有 `Dispatchers.Main`，于是
     * `DispatchedContinuation.resumeCancellableWith` → `TestMainDispatcher.isDispatchNeeded` → 炸。
     *
     * 测试里把它设成 `runTest` 自己的 `StandardTestDispatcher(testScheduler)`：
     * 它**不是** `TestDispatcher` 子类，不会被 `runTest` 收编进虚拟时钟，
     * 只是把任务**内联执行**且**不改变上下文** ⇒ 续体恢复时拦截器还在 ⇒ 永不碰 Main。
     *
     * ⚠️ 只设 [UnlockViewModelSeams.io] 而忘了设本字段 ⇒ 仍然会偶发踩 `Dispatchers.Main`。
     */
    private val ioContext: CoroutineContext
        get() = (seams.ioContextOverride ?: io) as CoroutineContext

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
        /**
         * 快速解锁入口是否可见（指纹图标 / 自动弹认证 / PIN 按钮）。
         *
         * ★ #243 起判据 = **装过任意一把门锁**（`quickUnlockEntryAvailable`），
         * **不再**要求"该库已在快速解锁范围里"。原因：房间信封能在认证那一刻补建，
         * 而拿"在不在范围里"当入口判据，会让还没封房间的库永远没有入口 ⇒
         * 永远补不上（真机 2026-11-04 报障：「该库未纳入快速解锁」）。
         */
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
        /**
         * ★ 这次查看层锁是**系统自动给的**（熄屏 / 冷启动恢复，`AutoViewGate` 打的标记）。
         *
         * 为什么必须与手动查看锁分开（#245，2026-11-04 真机）：
         * 手动查看锁是用户的一次**显式指令**，被系统取消后不该自己消失；
         * 自动门禁则是"离开设备后回来确认一次身份"——用户主动取消（去拿眼镜 / 手湿）
         * 完全合理，而且用户的真实诉求是**自己按指纹的那一刻**才认证，而不是一进页面
         * 就被系统弹窗拦一道。两者用同一个 `viewLocked` 渲染，但退出策略不同。
         */
        val viewLockAuto: Boolean = false,
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
            // ⚠️ **构造期恒 false**：这里不知道"是系统自动给的标记还是用户手动锁的"，
            //   猜错两个方向都坏事（猜自动 ⇒ 用户的手动锁被一次取消抹掉；
            //   猜手动 ⇒ 自动门禁又变成"一进来就弹窗"）。由 [observersStarted] 之后
            //   的第一帧按 `isAutoViewLocked` 如实补上（见 `refreshViewLockOf`）。
            viewLockAuto = false,
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

    /**
     * ★ 「本次会话里用户为**哪个库**输过主密码」+ 那个密码（#243）。
     *
     * ## 为什么需要留一个副本（而不是继续读 [UiState.password]）
     *
     * 两条路径的**时序**决定读 state 靠不住：
     *
     * - `submit()` 成功后会立刻 `password = ""`（正确：不该让它多活一秒）；
     * - 而补建房间信封发生在**指纹/房间解锁失败之后** —— 那时 state 里早就是空串了。
     *
     * ⇒ 成功那一刻**抢在清空之前**记下来，只保留到下一次认证；[vaultId] 相同才认，
     * 绝不拿"某个库的密码"去试另一个库。
     *
     * ⚠️ 内存里多留一份明文是该有的代价吗？—— 是，且是**有界**的：只留最近一个库、
     * 下一次提交/认证即覆盖，进程死亡随内存消失。KDBX 的房间信封本来就必须
     * 存这份主密码（加密后落盘），否则快速解锁对 KDBX 无从成立。
     */
    private var lastPasswordVaultId: String? = null
    private var lastPassword: String = ""

    /** 记下「刚刚为 [id] 输对的那份密码」，并清掉上一次（只留最近一个库）。 */
    private fun rememberPassword(id: String, password: String) {
        lastPasswordVaultId = id
        lastPassword = password
    }

    /**
     * 忘掉暂存的主密码（信封建好 / 换库 / 解锁失败之后都要调）。
     *
     * ⚠️ `String` 无法原地擦除（不可变），这里只能**断开引用**让 GC 回收 ——
     *   所以判据是"能不留就不留"，而不是"擦得干净"。真正的保护是**不缓存**：
     *   它只活到「为用户刚输的那一次认证补建房间信封」为止。
     */
    private fun forgetPassword() {
        lastPasswordVaultId = null
        lastPassword = ""
    }

    /**
     * 启动各条观察协程。
     *
     * ## ★ 为什么拆成显式方法（#174 的第一层修法，保留）
     *
     * 生产由 `init` 调；测试工厂在**设完接缝之后**自己调。这样"何时启动"是可控的，
     * 测试才有机会在协程跑起来之前把 [seams] 布置好。
     *
     * ⚠️ **但光有这一层不够** —— 真正让它生效的是 [seams] 改为**构造参数**：
     * 之前三个接缝是构造后才赋值的 `var`，`init` 那一刻恒为 null，守卫条件恒真，
     * 于是 `init` 总是抢先启动（`observersStarted` 的 CAS 又让测试那次静默返回）。
     * 详见 [seams] 的 KDoc。判据始终是同一句：
     * **接缝的生效范围必须覆盖"用到它的第一个协程"**。
     */
    init {
        // 生产：seams.scope 恒 null ⇒ 构造完立刻开始观察（行为与从前一致）。
        // 单测：seams 是**构造参数**，测试工厂传进来的实例已带 scope ⇒ 这里不启动，
        // 由测试在设好全部接缝后自己调 [startObservers]（否则 init 会抢先一步用
        // viewModelScope 起协程，注入的 scope 就永远轮不上 —— 见 [seams] 的 KDoc）。
        if (seams.scope == null) startObservers()
    }

    /**
     * 启动观察协程（幂等：重复调用不会起第二份）。
     *
     * 生产走 `init`；测试必须先构造好带 [UnlockViewModelSeams.scope] 的实例再调
     * —— 详见 `init` 上的说明。
     */
    internal fun startObservers() {
        // 幂等守卫：测试工厂可能先构造、设完接缝再调一次，而 init 已经起过一份。
        // 用 CAS 而不是布尔判断，避免"检查与设置"之间被并发插入。
        if (!observersStarted.compareAndSet(false, true)) return
        scope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                if (vaultId.isBlank()) vaultId = resolveTargetVaultId(vaults)
                val target = vaults.firstOrNull { v -> v.id == vaultId }
                _state.update {
                    it.copy(
                        vault = target,
                        viewLocked = target != null && sessionRepository.isViewLocked(target.id),
                        // ★ 必须与 viewLocked **同一次**更新：两者分开写会出现一帧
                        //   "锁着但不知是自动还是手动"的中间态，而自动弹认证正好读它（#245）。
                        viewLockAuto = target != null && sessionRepository.isAutoViewLocked(target.id),
                        // 首帧未到（vaults 尚未发射）时不会走到这里，故此处可判定为"确认无库"
                        noVaultToUnlock = target == null,
                    )
                }
            }
        }
        // 查看层锁标记变化：**只更新、不新建** viewLocked（用户认证成功清掉标记时这里会归位）。
        scope.launch {
            sessionRepository.observeViewLockedVaultIds().collect { locked ->
                _state.update { it.copy(viewLocked = vaultId.isNotBlank() && vaultId in locked) }
            }
        }
        scope.launch {
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
                    _state.update {
                        it.copy(
                            viewLocked = sessionRepository.isViewLocked(id),
                            viewLockAuto = sessionRepository.isAutoViewLocked(id),
                        )
                    }
                    // 房子化（2026-09-28）：入口可见性曾 = 全局指纹门锁 && 该库房间信封在。
                    //
                    // ★ #243（2026-11-04）：后半句去掉了 —— 入口只由**门锁**决定。
                    //   理由见 `VaultRepository.quickUnlockEntryAvailable` 的 KDoc：
                    //   房间信封可以在按指纹那一刻补建（`LocalUnlockFanout.RoomEnroller`），
                    //   而用它当入口判据
                    //   会让「还没封房间」的库**永远**没有入口 ⇒ 永远补不上 ⇒ 用户只能
                    //   一直输主密码（真机报障原文：「该库未纳入快速解锁」，而他连按指纹
                    //   的地方都看不到）。
                    //   ⚠️ 流与具体库**无关**了，但仍留在 `collectLatest` 里：
                    //   目标库一变就重订一次拿当前值，少一次状态滞后的可能。
                    vaultRepository.quickUnlockEntryAvailable().collect { available ->
                        _state.update { it.copy(localUnlockAvailable = available) }
                        if (available) prewarmCipher()
                    }
                }
        }
        // PIN 门锁可用性：门锁是**全局**的（房子化后不再按库），不需要解析目标库 ——
        // 直接订阅即可。上面那条 `collectLatest` 永不结束的告诫依旧成立，故仍单独起协程。
        scope.launch {
            vaultRepository.pinLockAvailable().collect { available ->
                _state.update { it.copy(pinUnlockAvailable = available) }
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
            scope.launch { submitPin() }
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
     * 房子化（2026-09-28）后是**两段**：先 `openHouseWithPin` 开全局门锁
     * （房钥匙进内存），再 `unlockVaultFromRoom` 开目标库。库类型分流已下沉到
     * repository 一侧，这里不再感知 —— 旧「Bitwarden / KDBX 各调一个方法」
     * 的分叉随每库信封一起消失。
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
        val outcome = withContext(ioContext) {
            when (val house = vaultRepository.openHouseWithPin(pin)) {
                // ★ #243：门锁（PIN）已开 ⇒ 若这个库还没有房间信封，同一套「补建 + 重试」
                //   在这里也必须成立 —— 否则「只用 PIN、不用指纹」的用户走房间信封的路
                //   一样会卡在「该库未纳入快速解锁」，而他的入口（PIN 圆点）明明就在眼前。
                PinUnlockOutcome.Opened -> openRoomWithEnrollFallback(id).toPinOutcome()
                // WrongPin / LockedOut / Unavailable 原样透传（文案四态各不相同）。
                else -> house
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

    /** 门锁已开、开房间失败的映射（两套 sealed 的分支一一对应）。 */
    private fun RoomUnlockOutcome.toPinOutcome(): PinUnlockOutcome = when (this) {
        RoomUnlockOutcome.Opened -> PinUnlockOutcome.Opened
        RoomUnlockOutcome.StaleCredentials -> PinUnlockOutcome.StaleCredentials
        is RoomUnlockOutcome.Unavailable -> PinUnlockOutcome.Unavailable(detail)
    }

    /**
     * ★ **门锁已开**之后开目标库，信封缺失时补建并重试一次（#243）。
     *
     * 与 `LocalUnlockFanout` 里那段同源（那里服务于「一次认证开多库」的扇出，
     * 这里服务于「单库 + PIN」），三步完全一致：
     *
     * 1. `unlockVaultFromRoom(id)`；
     * 2. 若结论是 `Unavailable`（`NotEnrolled` / `Damaged` 都归这里）⇒ 补建房间信封
     *    ——房子化后这一步**纯软件**，房钥匙就在内存里，不需要第二次认证；
     * 3. 补建成功就**再开一次**（凭据就是刚验过的那一份，不会再失败）。
     *
     * ⚠️ 不抽到 repository 里当"自动补建"：那是**策略**（什么时候值得补、用谁的主密码），
     * 而 repository 只该提供能力（`enrollVaultAfterFingerprintUnlock`）。
     * 把它藏进 `unlockVaultFromRoom` 会让"开库"这个方法意外产生副作用（写盘），
     * 也让 `StaleCredentials` 那条自愈路径与它纠缠不清。
     */
    private suspend fun openRoomWithEnrollFallback(id: String): RoomUnlockOutcome {
        val first = runCatching { vaultRepository.unlockVaultFromRoom(id) }
            .getOrElse { RoomUnlockOutcome.Unavailable(openFailedText(it)) }
        if (first !is RoomUnlockOutcome.Unavailable) return first
        val enrolled = runCatching {
            vaultRepository.enrollVaultAfterFingerprintUnlock(id) { target ->
                if (target == lastPasswordVaultId) lastPassword else null
            }
        }.getOrDefault(false)
        if (!enrolled) return first
        return runCatching { vaultRepository.unlockVaultFromRoom(id) }
            .getOrElse { RoomUnlockOutcome.Unavailable(openFailedText(it)) }
    }

    /**
     * 「开库抛异常」时该给用户看的那一句。
     *
     * ⚠️ 兜底文案**不进 Kotlin**（审计 §3.4：我们自己写的字必须能翻、能被孤儿串检查看见）。
     * 拿到异常消息时仍原样用它 —— 那是能定位问题的信息，套一句通用话反而把它盖住。
     * 这个 `detail` 最终会经 `lockMessageOf(...)` 认一次标识、认不出就当成话来展示，
     * 所以这里返回人话是对的（返回资源名会变成一句英文标识上屏）。
     */
    private fun openFailedText(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.local_unlock_room_open_failed)

    /**
     * PIN 失败的文案。
     *
     * ⚠️ **四态必须给出四句不同的话**：它们的用户动作互不相同 —— 重试 / 等主密码 /
     * 重设 / 报告数据损坏。合成一句「解锁失败」等于把用户丢在原地，这也是项目
     * 反复强调的「假状态」在文案层的表现。
     */
    private fun pinFailureText(outcome: PinUnlockOutcome): String = when (outcome) {
        is PinUnlockOutcome.WrongPin ->
            context.getString(R.string.pin_error_wrong, outcome.remainingAttempts)
        PinUnlockOutcome.LockedOut -> context.getString(R.string.pin_error_locked_out)
        PinUnlockOutcome.StaleCredentials -> context.getString(R.string.pin_error_stale)
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
     * `localUnlockAvailable` 首帧 → 再 `prepareFingerprintUnlock` → 再等 Activity RESUMED，
     * 三次握手串起来就是用户感知的「指纹不能第一时间弹出来」。可用状态一到位就
     * 提前把最后一步做掉，等真正要弹时只剩「把 cipher 交给系统」这一件事。
     *
     * 房子化后门锁是**全局**的（不再按库取 cipher），参数只留作日志锚点也失去意义，
     * 故无参。
     */
    private fun prewarmCipher() {
        if (preparedCipher != null) return
        scope.launch {
            preparedCipher = withContext(ioContext) {
                runCatching { vaultRepository.prepareFingerprintUnlock() }.getOrNull()
            }
        }
    }

    private fun startBiometricUnlock(viewLock: Boolean) {
        val current = _state.value
        if (current.submitting) return
        if (viewLock && !current.viewLocked) return
        if (!viewLock && current.localUnlockAvailable.not()) return
        _state.update { it.copy(submitting = true, viewUnlockStarted = viewLock, error = null) }
        scope.launch {
            // ⚠️ Keystore / 解密都在**后台**做：`viewModelScope` 默认跑在主线程，
            // 而 `prepareFingerprintUnlock` 要初始化一个 AES Cipher（首次还会触发
            // keystore 解密），冷启动或覆盖安装后首次进入时足以让首帧渲染卡住 ——
            // 表现就是用户看到的「指纹弹窗不能第一时间出来」。
            val cipher = preparedCipher ?: withContext(ioContext) {
                vaultRepository.prepareFingerprintUnlock()
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
                        error = UnlockUiError.Unknown(
                            context.getString(R.string.quick_unlock_local_failed),
                        ),
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
     * 除 [target] 之外，本次还应当顺带开房间的库（**只挑已纳入范围的锁定库**）。
     *
     * ## 为什么必须过滤成"已纳入范围"的
     *
     * 库列表里通常既有纳入了快速解锁的库，也有只走主密码的库。对后者调
     * `unlockVaultFromRoom` 必然 NotEnrolled，白跑一趟还多算一次"失败"，
     * 结果页会报一堆莫名其妙的"未打开" —— 而用户根本没打算开它们。
     *
     * ## 为什么不包含已解锁的库
     *
     * 已经解锁的库密钥就在内存里，再开一次房间是纯浪费（同
     * [completeLocalUnlock] 对查看锁分支的告诫）。
     *
     * ## ★ 为什么改成"一次快照"而不是逐库问（2026-09-29 提速修复）
     *
     * 旧实现是 `.filter { fingerprintQuickUnlockAvailable(it).first() }` ——
     * **每个库各订阅一次 Flow**。而那条 Flow 内部含
     * `localUnlockKeyStore.keyAvailable`，即**一次 Keystore 往返**
     * （源码注释自述「冷启动可达数百毫秒」）。N 个库 ⇒ **N 次串行 Keystore**，
     * 且全部发生在「指纹认证成功 → 结果返回」之间 ⇒ 用户感知「指纹过了却要等好几秒」。
     *
     * 正确的判据是**两个全局事实**相乘，与"具体哪个库"无关：
     * 1. 指纹门锁是否可用 —— **全局一份**（房子化后门锁只有一把）；
     * 2. 该库是否在生效范围内 —— 一次读偏好（`house_room::` 的镜像）。
     *
     * ⇒ 问**一次**就够，然后在内存里做集合过滤。Keystore 往返从 N 次降到 1 次
     * （且那次 `fingerprintLockAvailable()` 在解锁页首帧已经预取过）。
     *
     * ⚠️ 这里刻意**不**再调 `fingerprintQuickUnlockAvailable(id)`：它逐库求值
     * 的样子看着"更精确"，实际上是把两个全局量重复计算了 N 遍。
     */
    private suspend fun candidateVaultIds(target: String): List<String> =
        withContext(ioContext) {
            runCatching {
                val vaults = vaultRepository.observeVaults().first()
                // 一次读范围（DataStore，零 Keystore），与库列表在内存里求交。
                val scope = preferences.quickUnlockScope().first()
                vaults
                    .filter { it.id != target && !it.unlocked && it.id in scope }
                    .map { it.id }
            }.getOrDefault(emptyList())
        }

    /**
     * BiometricPrompt 认证成功（携带本次 cipher）：解门锁 → 开目标库（+顺带库）。
     *
     * ## ★ 为什么「先开核心库」再异步补开其余库（2026-09-29 提速修复）
     *
     * 旧实现一次性把「核心库 + 其余库」交给 `LocalUnlockFanout.unlockAll`，
     * **等全部开完**才发 [Event.Unlocked]。而「其余库」是**附加收益**，
     * 却挡在了用户的核心诉求（"我要进我点的那一个库"）前面 ——
     * 库多时（每个库要解信封 + 开库，KDBX 还要解析整个文件）就是好几秒。
     *
     * 现在拆成两段：
     * 1. **核心库同步开**（用户等着的那一个）→ 立刻发 [Event.Unlocked] 放行；
     * 2. **其余库异步补开**（`viewModelScope` 里，发完事件之后）——
     *    它们本来就只是"顺便也让别的库能用"，慢一点无所谓，
     *    且其中任何失败都不该影响"我已经进去了"这个事实。
     *
     * ⚠️ 门锁解不开时的 rearm / 降级善后**仍属核心段**（在 fanout 里）——
     * 那是用户必须知道的结论，不能推到后台异步里。
     *
     * @param forViewLock 本次认证是为查看层锁发起的 → 只清标记，**不重新解封密钥**
     *   （密钥本来就在会话里；再解封一次等于把同一把密钥写第二遍，白做一轮 KDF 派生）。
     *   ⚠️ 清的是**所有库的自动门禁**，不是只清 [vaultId] —— 见方法体内注释与
     *   `VaultSessionRepository.clearAutoViewLocks` 的 KDoc（一次指纹够全部库）。
     * @param rest 除目标库之外可顺带开房间的库（见 [Event.PromptForUnlock.rest]）；
     *   查看锁场景传空。
     */
    fun completeLocalUnlock(
        cipher: javax.crypto.Cipher,
        forViewLock: Boolean,
        rest: List<String> = emptyList(),
    ) {
        scope.launch {
            if (forViewLock) {
                // ★ 2026-10-31：清**所有库**的自动门禁，不只当前库 —— 见
                //   [VaultSessionRepository.clearAutoViewLocks] 的 KDoc。
                //
                // 用户报告的「进来要验两次指纹」就是这里：熄屏时 AutoViewGate 给
                // **每个**已解锁的「从不」库各打了一个标记，而这里原来只清当前库。
                // 根导航看到别的库还带着标记 ⇒ 又判成 VaultLocked ⇒ 再验一次。
                //
                // ⚠️ 一次认证之所以够：指纹是**全局**的（房子化后只有一把门锁），
                //   "验过是本人"与"哪个库"无关。分区标记只是 UI 的实现细节，
                //   不该把它升格成"每个库各验一次"。
                //
                // ⚠️ 手动查看锁**不动**：那是用户对单个库的显式指令，
                //   不能被一次身份确认顺手撤销（此方法内部只清自动子集）。
                sessionRepository.clearAutoViewLocks()
                _state.update {
                    it.copy(submitting = false, viewUnlockStarted = false, error = null)
                }
                _events.send(Event.Unlocked)
                return@launch
            }
            // 核心段：一次门锁 + 只开用户点的那个库（其余库延后）。
            // 库类型分流已随房子化下沉到 repository（unlockVaultFromRoom 内部按 kind 分流）。
            val fanout = withContext(ioContext) {
                LocalUnlockFanout.unlockAll(
                    repository = vaultRepository,
                    first = vaultId,
                    // ★ 其余库不在这一段里 —— 见本方法 KDoc「先开核心库」。
                    rest = emptyList(),
                    cipher = cipher,
                    // 门锁解不开时的分叉（rearm / 降级）在 fanout 里，
                    // 判据 = 房钥匙在不在内存（定稿 §6，批次 4）。
                    recovery = unlockRecovery,
                    // ★ #243：门锁刚开（房钥匙在内存）⇒ 顺手给「信封不在 / 坏了」的
                    //   目标库补建房间信封。不补的话解锁页永远不渲染指纹入口，
                    //   每次都回主密码（用户 2026-11-04 报障）。
                    //   ⚠️ 「只在信封缺失时才调」由 `LocalUnlockFanout` 判断
                    //   （它拿着开库结论），这里不再重复一遍，避免两处判据漂移。
                    roomEnroller = { target, _ ->
                        // 只认「用户刚刚在本次会话里为这个库输过的那一个」
                        // （[lastPasswordVaultId] 判库 id，不是拿别人的密码去试）。
                        // Bitwarden 库凭据是会话密钥，[masterPasswordOf] 给 null 即可。
                        val enrolled = vaultRepository.enrollVaultAfterFingerprintUnlock(target) { id ->
                            if (id == lastPasswordVaultId) lastPassword else null
                        }
                        // ★ 封好就把明文忘掉：这份密码的唯一用途就是建这个信封，
                        //   信封建好后它再没有任何读者 —— 留在 ViewModel 字段里
                        //   只是给内存转储多一个窗口（项目里"明文不过夜"的同一条纪律）。
                        if (enrolled) forgetPassword()
                        enrolled
                    },
                )
            }
            when (val result = fanout.first) {
                RoomUnlockOutcome.Opened -> {
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
                    // ★ 放行之后**才**去补开其余库（异步，失败无人问津）。
                    openRemainingInBackground(fanout.lockOpened, rest)
                }
                // 主密码在别处改过（只可能来自 KDBX）：给「输新密码」的指引，
                // 别让用户误以为指纹坏了（定稿 §4.4 D3）。
                //
                // ★ 2026-09-29（批次 4，定稿 §6 目标 3）：与旧行为的关键差别 ——
                //   用户**之前**在本次会话里为这个库输过的新主密码，会被顺手用来
                //   重包它的房间信封。旧行为是「当次能开、但信封没换」，于是下次
                //   快速解锁又 Stale，用户每周都要重输一遍（真实体感：指纹解锁废了）。
                RoomUnlockOutcome.StaleCredentials -> {
                    resealStaleRoomIfPossible()
                    _state.update {
                        it.copy(
                            submitting = false,
                            viewUnlockStarted = false,
                            error = UnlockUiError.Unknown(localUnlockFailureText(isKdbx = true)),
                        )
                    }
                }
                is RoomUnlockOutcome.Unavailable -> {
                    // 仓储给的**具体原因**优先（例如「文件读不到了，请重新选择」），
                    // 没有具体原因时才用按库类型区分的兜底文案。
                    //
                    // ⚠️ 2026-10-21 §3.4：`detail` 有两种来源 —— 门锁失败时
                    //   fanout 填的是 [LocalUnlockMessage] 的 `name`（**内部标识**，
                    //   绝不能上屏），其余情况是仓储翻译好的一句话。先按标识认一次，
                    //   认不出才当成人话用。判据与 `QuickUnlockController` 的
                    //   `SEAL_NO_HOUSE_KEY` 完全一致。
                    val isKdbx = _state.value.vault?.kind == VaultKind.KDBX
                    val message = lockMessageOf(result.detail)?.resolve(context)
                        ?: result.detail.ifBlank { localUnlockFailureText(isKdbx) }
                    _state.update {
                        it.copy(
                            submitting = false,
                            viewUnlockStarted = false,
                            error = UnlockUiError.Unknown(message),
                        )
                    }
                }
            }
        }
    }

    /**
     * 补开「其余库」用的**进程级** scope（多库锁模型定稿 **D5**，2026-09-29）。
     *
     * ## 为什么不能用 `viewModelScope`
     *
     * 用户点开的核心库一旦打开就**立刻放行导航**，解锁页随即销毁 ⇒ `viewModelScope`
     * 被取消 ⇒ 补开 job 一起被取消。真机实测的后果（`.ai/issues/04-锁与解锁.md` #133）：
     *
     * ```
     * fanout rest=onedrive:… → Unavailable(Job was cancelled)
     * VaultixUnlockVm: 其余库补开失败（不影响已进入的库）：Job was cancelled
     * ```
     *
     * ⇒ 用户被迫为「一次认证开多库」**再按一次指纹**。本 scope 的存在就是为了让
     * 这段工作活过导航。
     *
     * 形态与 `AutofillActivity.restOpenScope` 一致（同类问题、同一处置）：自建
     * `SupervisorJob` 的短任务 scope —— 工作只有「解几个房间信封」，秒级完成；
     * 失败也不影响任何用户可见结论（旧 KDoc 的「无人问津」取向不变）。
     */
    @Suppress("InjectDispatcher")
    private val restOpenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 放行之后异步补开「其余库」（2026-09-29 提速修复的第二段）。
     *
     * ## 为什么是"发完事件之后"而不是"和核心库一起"
     *
     * 见 [completeLocalUnlock] 的 KDoc：其余库是附加收益，用户等的是他点的那一个。
     *
     * ## 为什么可以"无人问津"
     *
     * 这些库的成败**不改变任何用户可见结论** —— 用户已经进库了。
     * 它们唯一的用途是"下次跨库搜索/填充时这些库也是可用的"，失败时
     * 用户下次解锁会自然重试。把结果塞进 UI 反而是噪音
     * （旧实现在结果页报"有 N 个库未打开"，而用户根本没打算开它们）。
     *
     * ⚠️ 跑在 [restOpenScope]（**进程级**）而不是 `viewModelScope` —— 后者会在
     * 放行导航时被取消，那正是 D5 要修的病（见该 scope 的 KDoc）。
     *
     * @param lockOpened 门锁是否已开（房钥匙在不在内存）。false 时补开必全败，直接跳过。
     */
    private fun openRemainingInBackground(lockOpened: Boolean, rest: List<String>) {
        if (rest.isEmpty()) return
        restOpenScope.launch {
            runCatching {
                withContext(ioContext) {
                    LocalUnlockFanout.openRest(
                        repository = vaultRepository,
                        rest = rest,
                        houseKeyInMemory = lockOpened,
                    )
                }
            }.onFailure { Log.d(TAG, "其余库补开失败（不影响已进入的库）：${it.message}") }
        }
    }

    /**
     * 快速解锁走到 `StaleCredentials` 时，若用户**刚输过一次密码**，顺手把它
     * 用来重包该库的房间信封（定稿 §6 目标 3）。
     *
     * ## 为什么需要「刚输过一次」
     *
     * `StaleCredentials` 的意思是「门锁开了、房钥匙拿到了，但信封里那份主密码
     * 已经过时」。重包要一份**当前有效**的主密码 —— 唯一可能的来源就是用户
     * 上一次输进 [UiState.password] 的那个。这个值在提交成功后会清空，
     * 但 `StaleCredentials` 恰恰**不是**成功路径，故此刻它还在。
     *
     * ## 失败一律静默（有意）
     *
     * 重包是**次要的自我修复**：它的成败不该改变用户看到的主提示（「请输入当前主密码」）。
     * 更重要的是失败**不能**变成另一个错误 —— 那会把一句清楚的指引升级成
     * 两条互相矛盾的报错。用户下一次走正常解锁时若仍 Stale，会再到这里重试。
     *
     * ## 只在 KDBX 上有意义
     *
     * Bitwarden 库的房间信封包的是会话 full key（派生自当前会话），
     * 与用户输入的密码无关 ⇒ 它压根不会 Stale。
     */
    private suspend fun resealStaleRoomIfPossible() {
        val candidate = _state.value.password
        if (candidate.isBlank()) return
        runCatching {
            when (val outcome = roomReseal.resealRoom(vaultId, candidate)) {
                RoomResealOutcome.Resealed ->
                    Log.d(TAG, "reseal $vaultId → 房间信封已用刚输的密码重包")
                else ->
                    Log.d(TAG, "reseal $vaultId → 未重包（$outcome），下次解锁再试")
            }
        }.onFailure { Log.d(TAG, "reseal $vaultId → 异常：${it.message}") }
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
    private fun localUnlockFailureText(isKdbx: Boolean): String =
        (if (isKdbx) {
            LocalUnlockMessage.VaultOpenFailedKdbx
        } else {
            LocalUnlockMessage.VaultOpenFailed
        }).resolve(context)

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
        // ★ #245：**系统自动**打的查看锁，用户主动取消 ⇒ 标记就此消失，回到库列表。
        //
        //   为什么：自动门禁的语义是「离开设备后回来确认一次身份」，而用户取消
        //   （去拿眼镜 / 手湿 / 只想先看看）本身就是一个合法选择 —— 把他钉在
        //   「只有生物识别按钮」的这一页上，等于逼他为一次"不想验证"付一次验证的代价。
        //   真锁（密钥已清）不会因此失守：这个标记只管**界面**，密钥从没被它保护过。
        //
        //   ⚠️ 手动查看锁（用户自己在主页按的锁）**不动** —— 那是一次显式指令。
        //   ⚠️ 系统侧终止（`systemAbort`，压根没弹成）**不动** —— 那不算用户的选择。
        if (cancelled && !systemAbort) clearAutoViewLockIfAny()
    }

    /**
     * 若当前是**自动**查看锁，清掉它并回到主界面（#245）。
     *
     * 复用 [completeLocalUnlock] 那条放行路径里"清所有库的自动门禁"的同一判据
     * （一次身份确认是全局的），避免只清当前库、别的库还带着标记 ⇒ 根导航又判一次锁。
     */
    private fun clearAutoViewLockIfAny() {
        if (!_state.value.viewLockAuto) return
        scope.launch {
            sessionRepository.clearAutoViewLocks()
            // ⚠️ 根导航此刻已不在"锁着"的状态 ⇒ 交回主界面。走同一个 [Event.Unlocked]：
            //   解锁页对它的处理只有 `onUnlocked()`，与正常认证成功完全一致。
            _events.send(Event.Unlocked)
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
        scope.launch {
            // ★ KDBX 与 Bitwarden 的解锁是**两条完全不同的路**（M2 阶段 A）：
            // KDBX 认的是文件（离线、无账号、无 2FA），Bitwarden 认的是账号（联网 + 可能 2FA）。
            // 走错一条的后果不是「报错」而是「报错信息完全对不上」（如 KDBX 库被拿去联网 prelogin）。
            val target = _state.value.vault
            // PBKDF2 / Argon2 派生是秒级 CPU 活（Bitwarden 默认 600k 次迭代），
            // 必须离开主线程，否则「登录」按钮按下到转圈之间会整页卡住。
            val result = withContext(ioContext) {
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
        scope.launch {
            val result = withContext(ioContext) {
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
                // ★ #243：**必须**在下面那次 `password = ""` 之前记 —— 之后就读不到了。
                //   详见 [rememberPassword] 的 KDoc。空串不记：那说明本次没输密码
                //   （KDBX 走的是房间/其它路径），不能伪装成"验过"。
                _state.value.password.takeIf { it.isNotBlank() && vaultId.isNotBlank() }?.let {
                    rememberPassword(vaultId, it)
                }
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

    // ---- ★ #174：给单测的两个把手（不含任何业务逻辑，见各自 KDoc）----

    /**
     * 把 [candidateVaultIds] 的筛选结果原样交出去。
     *
     * 为什么值得开这个口子：筛选语义（"不是目标 + 还锁着 + 在范围内"）是这一页最
     * 容易改错、也最需要钉死的三条判据，而它们原本只能"真的走到弹认证、再从事件里
     * 捞回来"才测得到 —— 那条路末尾有一次真实的线程跳转，在 CI 上会踩被卸下的
     * `Dispatchers.Main`（#174 的事故现场），也让每条用例都多花一次真实往返。
     *
     * ⚠️ 这里**不做任何新逻辑**，只是转发；"筛出来的结果确实随事件发出去"另由
     * `UnlockViewModelTest.promptCarriesTheFilteredCandidateVaults` 端到端钉死。
     */
    internal suspend fun candidateVaultIdsForTest(target: String): List<String> = candidateVaultIds(target)

    /** 把本 VM 实际使用的 [scope] 交出去（调用方用 Job 同一性判定）。 */
    internal fun probeOwnScope(): CoroutineScope = scope

    /**
     * 观察协程是否已经启动 —— **构造刚完成、测试尚未调用 [startObservers] 时必须为 false**。
     *
     * 这是 #220 的正面判据：此前 `init` 无条件抢先启动，注入的 scope 从未生效，
     * 而所有用例仍然"看起来绿"（因为它们用的是 `Dispatchers.setMain` 那个 unconfined 替身，
     * 同步跑完就结束）。只有把「init 有没有抢跑」变成一条可断言的探针，
     * 才能保证 `UnlockViewModelTest.overrideScopeIsActuallyUsedByTheViewModel` 不是空断言。
     */
    internal fun probeObserversStarted(): Boolean = observersStarted.get()

    companion object {
        const val ARG_VAULT_ID = "vaultId"

        /**
         * 日志 tag（用 `android.util.Log` 而非 `VaultixLog`：本类单测跑纯 JVM，
         * 后者默认实现会碰 native 层。`testOptions.unitTests.isReturnDefaultValues`
         * 已置 true，故 `Log.d` 在测试里是安全的 no-op）。
         */
        private const val TAG = "VaultixUnlockVm"
    }
}

/**
 * 把 `RoomUnlockOutcome.Unavailable.detail` 里的**内部标识**认回枚举（认不出返回 null）。
 *
 * ⚠️ `detail` 这个字段承载了两种东西：仓储翻译好的**人话**，以及
 * `LocalUnlockFanout` 在门锁失败时填的**枚举名**（`name`）。后者绝对不能直接上屏，
 * 否则用户会看到 `DegradedRoomsRemoved` 这种字符串。
 * 用 `entries.firstOrNull { it.name == detail }` 而不是 `valueOf`：`valueOf` 会抛
 * `IllegalArgumentException`，而这里恰恰是**失败路径**，不能因为一条文案再崩一次。
 */
private fun lockMessageOf(detail: String): LocalUnlockMessage? =
    LocalUnlockMessage.entries.firstOrNull { it.name == detail }

/**
 * [UnlockViewModel] 的**测试接缝容器**：生产恒为空（Hilt 直接 new 一个全 null 的实例）。
 *
 * ## 为什么不能像从前那样用「构造后赋值的 var」
 *
 * `init` 在构造**期间**执行，构造后赋值永远赶不上。于是
 * `if (scope == null) startObservers()` 在测试里恒为真 ⇒ 观察协程永远挂在
 * `viewModelScope`（`Dispatchers.Main`）上，测试注入的 `TestScope` **从未生效**。
 *
 * 而 `observersStarted` 的 CAS 守卫又让测试工厂随后那次 `startObservers()` 直接返回，
 * 连一行日志都不留 —— 这就是 #174 / #176 修了三次、CI 仍然偶发
 * `Dispatchers.Main was accessed…` 的原因（异常还总是归到**下一条**用例头上）。
 *
 * ## 为什么不能用别的位置
 *
 * | 位置 | 结果 |
 * |---|---|
 * | 构造参数默认值里读 `viewModelScope` / `this` | `Cannot access '<this>' before …` |
 * | Hilt `@Provides` 注入 `CoroutineScope` / `dagger.Lazy` | `Injection of an @HiltViewModel class is prohibited` |
 * | `scopeOverride: CoroutineScope? = null` 当构造参数 | `CoroutineScope cannot be provided`（可空 + 默认值照解析不误） |
 * | **本类（`@Inject constructor()` 空参，字段可写）** | ✅ Dagger 能直接绑定，且**构造参数在 `init` 之前就绪** |
 *
 * ⚠️ 三个字段必须由调用方**在构造 VM 之前**写好；构造之后再改对 `init` 无效。
 */
class UnlockViewModelSeams @Inject constructor() {
    /** 生产 null ⇒ `viewModelScope`；测试传 `TestScope`（`runTest` 的 `this`）。 */
    internal var scope: CoroutineScope? = null

    /** 生产 null ⇒ `Dispatchers.IO`；测试传可推进虚拟时间的调度器。 */
    internal var io: CoroutineDispatcher? = null

    /**
     * 生产 null ⇒ 直接用 [io]；测试传一个**可内联执行**的 `StandardTestDispatcher`。
     *
     * 详见 `UnlockViewModel.ioContext` 的 KDoc（#176：为什么只有 [io] 还不够）。
     */
    internal var ioContextOverride: CoroutineDispatcher? = null
}
