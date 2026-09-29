/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * [VaultLockManager] 的主实现。
 *
 * 逐句对齐 Bitwarden Android 官方客户端 `data/vault/manager/VaultLockManagerImpl.kt`
 * （GPL-3.0，Copyright Bitwarden Inc.）中的超时部分：
 *   - `observeAppForegroundChanges()`  → [onAppBackgrounded] / [onAppForegrounded]
 *   - `handleOnBackground()`           → 触发超时检查
 *   - `handleOnForeground()`           → **仅取消定时器 job**
 *   - `checkForVaultTimeout()`         → 按档位与「原因」分流
 *   - `handleTimeoutActionWithDelay()` → 启动 `delay()` job
 *   - `handleTimeoutAction()`          → 执行锁定
 *   - `CheckTimeoutReason`             → 三类原因（见文件末尾）
 *
 * ⚠️ 与 Bitwarden 的已知差异（刻意的）：
 *  1. Bitwarden 用 SDK 的 `WrappedAccountCryptographicState` 解锁；Vaultix 走自己的
 *     `BitwardenAuthRepository`（`VaultRepositoryImpl.doUnlock`），故 [unlockVault]
 *     委托给 [VaultRepository] 而不是 SDK；
 *  2. Bitwarden 有 `LOGOUT` 超时动作；Vaultix 只实现 `LOCK`；
 *  3. Bitwarden 的定时器在 `unconfinedScope` 上跑（它需要即时性）；Vaultix 用
 *     注入的 `@ApplicationScope` 语义的独立 scope，避免依赖 `Dispatchers.Unconfined`。
 */

package io.vaultix.vaultix.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行中的超时定时器（对齐 Bitwarden `TimeoutJobData`）。
 *
 * @property startTimeMs 定时器启动时刻。⚠️ 取自 `SystemClock.elapsedRealtime()`，
 *   **不是** `System.currentTimeMillis()` —— 见 [VaultLockManagerImpl.onScreenOn] 的
 *   「为什么必须用单调时钟」。
 * @property durationMs 本次定时器要求的**总时长**（不是剩余）。
 *   亮屏补偿要用它减去已经走过的时长，重算剩余。见 [VaultLockManagerImpl.onScreenOn]。
 */
private data class TimeoutJobData(
    val job: Job,
    val startTimeMs: Long,
    val durationMs: Long,
)

@Singleton
class VaultLockManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
    private val preferences: VaultixPreferences,
    // ⚠️ 2026-09-29 二次定稿：`AutoUnlockRepository` 注入已移除 ——
    //   `Never` 档对齐 Bitwarden 后不再有「离场软锁」这个动作，
    //   本类也不再需要软锁的实现（保留会触发 detekt `UnusedPrivateMember`）。
) : VaultLockManager {

    // 进程级短任务 scope（启动/取消定时器）。项目当前唯一的调度器限定符是
    // @CryptoDispatcher（语义 = KDF/加解密 CPU 密集），用在这里会混淆语义。
    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前活动库 id（Vaultix 一服务器一账号，取首个已注册的库）。 */
    @Volatile
    private var activeVaultId: String? = null

    /** 每个库的运行中定时器（对齐 Bitwarden `userIdTimerJobMap`）。 */
    private val timerJobMap = mutableMapOf<String, TimeoutJobData>()

    private val _vaultUnlockDataStateFlow = MutableStateFlow<List<VaultUnlockData>>(emptyList())
    override val vaultUnlockDataStateFlow: StateFlow<List<VaultUnlockData>> =
        _vaultUnlockDataStateFlow.asStateFlow()

    private val _vaultStateEventFlow = MutableSharedFlow<VaultStateEvent>(extraBufferCapacity = 16)
    override val vaultStateEventFlow: Flow<VaultStateEvent> = _vaultStateEventFlow.asSharedFlow()

    private val _isActiveUserUnlockingFlow = MutableStateFlow(false)
    override val isActiveUserUnlockingFlow: StateFlow<Boolean> = _isActiveUserUnlockingFlow.asStateFlow()

    // ⚠️ `isFromLockFlow` 已于 2026-09-29 删除（死代码 + 赋值写反 + 已被 `viewLocked` 取代）。
    //   详见接口 `VaultLockManager` 的 KDoc。

    init {
        scope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                activeVaultId = vaults.firstOrNull()?.id
                // 锁态快照：把仓储的 unlocked 布尔映射为本管理器的三态模型。
                // 注意 UNLOCKING 由 _isActiveUserUnlockingFlow 单独表达，这里只发已知的
                // UNLOCKED 库；未解锁的库**不出现在列表中**（对齐 Bitwarden
                // `vaultUnlockDataStateFlow` 只含已解锁/解锁中的用户）。
                _vaultUnlockDataStateFlow.value = vaults
                    .filter { it.unlocked }
                    .map { VaultUnlockData(it.id, VaultUnlockData.Status.UNLOCKED) }
            }
        }
        registerScreenOnReceiver()
    }

    /**
     * 注册亮屏广播接收器（对齐 Bitwarden `init { context.registerReceiver(...) }`）。
     *
     * ## ⚠️ 为什么**不**反注册、也不怕泄漏
     *
     * 本类是 `@Singleton` 且随进程存活；`context` 是 `@ApplicationContext`
     * （生命周期 = 进程）。所以：
     * - 进程活着 → 接收器就该活着（定时器补偿随时可能被需要）；
     * - 进程死亡 → 接收器与 context 一起被系统回收，无外部引用可泄漏。
     *
     * 也就是说**没有**「Activity 泄漏」那种需要 `onDestroy` 反注册的场景。
     * 反过来，若在某个短生命周期处反注册，就会出现「用户锁屏放了一会，
     * 接收器已被摘掉 ⇒ 亮屏不补偿 ⇒ 该锁的库还开着」的静默 bug。
     *
     * ## 为什么用 `ACTION_SCREEN_ON` 而不是 `ACTION_USER_PRESENT`
     *
     * Bitwarden 用的就是 `SCREEN_ON`（:`158`）。二者的差别在于：`USER_PRESENT` 要等
     * 用户**解锁**，而 `SCREEN_ON` 在**屏幕刚亮**就触发 —— 更早、更稳，且不依赖
     * 用户是否设了锁屏密码。补偿越早做，用户看到的锁态就越接近真实剩余时间。
     */
    private fun registerScreenOnReceiver() {
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    onScreenOn()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_ON),
        )
    }

    override fun isVaultUnlocked(vaultId: String): Boolean =
        _vaultUnlockDataStateFlow.value.any {
            it.vaultId == vaultId && it.status == VaultUnlockData.Status.UNLOCKED
        }

    override fun isVaultUnlocking(vaultId: String): Boolean = _isActiveUserUnlockingFlow.value

    override fun lockVault(vaultId: String, isUserInitiated: Boolean) {
        // 用户主动锁定：立即取消任何在跑的定时器（对齐 Bitwarden `lockVault` 先清 job）。
        cancelTimer(vaultId)
        setVaultToLocked(vaultId)
    }

    override fun lockVaultForCurrentUser(isUserInitiated: Boolean) {
        val vaultId = activeVaultId ?: return
        lockVault(vaultId, isUserInitiated)
    }

    override suspend fun unlockVault(vaultId: String, masterPassword: String): UnlockResult {
        _isActiveUserUnlockingFlow.value = true
        return try {
            val result = vaultRepository.unlockVault(vaultId, masterPassword)
            if (result is UnlockResult.Success) {
                _vaultStateEventFlow.tryEmit(VaultStateEvent.Unlocked(vaultId))
            }
            result
        } finally {
            _isActiveUserUnlockingFlow.value = false
        }
    }

    override suspend fun waitUntilUnlocked(vaultId: String) {
        if (isVaultUnlocked(vaultId)) return
        vaultUnlockDataStateFlow.first { list ->
            list.any { it.vaultId == vaultId && it.status == VaultUnlockData.Status.UNLOCKED }
        }
    }

    // ===================== 生命周期驱动（本文件的核心） =====================

    override fun onAppBackgrounded() {
        // ★★ 多库锁模型定稿 **D2**（2026-09-29）：**每个已解锁库各起一份定时器**，
        //   各按自己的档位到点（档位每库一份见 D3，施工中）。
        //
        //   旧实现只取 `activeVaultId` ⇒ **非活动库永远不会被自动锁定**
        //   （它在 App 离开后没有任何人在计时）—— 那正是用户报的
        //   「开 A 再开 B，只有 B 会被锁、A 一直开着」。见 `.ai/issues/04-锁与解锁.md` #131。
        //
        //   定时器的时长仍是「离开 App 的时长」这一个语义（对齐 Bitwarden：
        //   handleOnBackground 起、handleOnForeground 撤），所以回前台是**全撤**
        //   （见 [onAppForegrounded]），不是只撤当前这个。
        val unlocked = _vaultUnlockDataStateFlow.value.map { it.vaultId }
        if (unlocked.isEmpty()) return
        unlocked.forEach { checkForVaultTimeoutInternal(it, CheckTimeoutReason.AppBackgrounded) }
    }

    override fun onAppForegrounded() {
        // ★★ D2 的另一半：回前台 = **撤销全部**定时器。
        //   旧实现只撤 `activeVaultId`，别的库的 job 会继续跑到点 ——
        //   于是「用户明明在 App 里看着，另一个库却被锁了」。
        //   直接按 map 清，不用再查快照：快照可能不含已锁库的残留 job。
        timerJobMap.keys.toList().forEach { cancelTimer(it) }
    }

    /**
     * 屏幕点亮（对齐 Bitwarden `ScreenStateBroadcastReceiver`）。
     *
     * ## ★ 为什么需要它（核心原理，改动前必读）
     *
     * 协程的 `delay(n)` 之所以「到点执行」，最终依赖线程的定时等待，而线程在
     * **设备深度睡眠时被完全挂起**。后果是：**熄屏期间时间在走，但定时器不走**。
     *
     * 举个具体的例子：档位 = 后台 5 分钟锁，用户锁屏后把手机放兜里 30 分钟。
     * 如果只有 `delay()`，熄屏期间 CPU 挂起，30 分钟里 `delay` 可能只推进了 2 分钟
     * ⇒ 用户掏出手机解锁的瞬间，**本应早已锁定的库还是开着的**，直到那"剩下的 3 分钟"
     * 走完才锁 —— 这正是用户抱怨过的那类「锁得不及时」。
     *
     * 补偿办法：亮屏时不让原 job 继续跑，而是**按单调时钟重算剩余时长**，重启一个新 job。
     * 走过了多少 = `elapsedRealtime() - startTimeMs`（单调时钟在熄屏期间**照常累加**，
     * 这正是它区别于 `currentTimeMillis` 的关键）；剩余 = `durationMs - 走过`。
     *
     * ## 与 Bitwarden 的逐句对照
     *
     * ```kotlin
     * // Bitwarden :737-749
     * val durationSoFarMs = (realtimeManager.elapsedRealtimeMs - data.startTimeMs).coerceAtLeast(0L)
     * handleTimeoutActionWithDelay(userId, data.vaultTimeoutAction, delayMs = data.durationMs - durationSoFarMs)
     * ```
     *
     * ## 两处对 Bitwarden 的**有意收口**（不是抄错）
     *
     * 1. **剩余钳到 ≥0**：Bitwarden 只对 `durationSoFarMs` 保了下界，`delayMs`
     *    理论上可为负（时钟被回拨等）。本项目统一 `coerceIn(0L, durationMs)`，
     *    让「已超时」稳定退化为「立即锁」，而不是把负值丢给 `delay()`。
     * 2. **补跑时不再依赖 `timerJobMap` 迭代期间的可变性**：Bitwarden 对 map 做 `map{}`
     *    并在迭代中调 `handleTimeoutActionWithDelay`（内部又 `remove`/`put` 同一 map），
     *    靠 Kotlin 的 `map{}` 先快照规避。这里显式取 `toList()` 快照，意图更直白。
     */
    override fun onScreenOn() {
        // 先快照：下面的 handleTimeoutActionWithDelay 会改写 timerJobMap，
        // 不能边遍历边改。
        val snapshot = timerJobMap.toList()
        if (snapshot.isEmpty()) return

        snapshot.forEach { (vaultId, data) ->
            val durationSoFarMs =
                (SystemClock.elapsedRealtime() - data.startTimeMs).coerceAtLeast(0L)
            val remainingMs = (data.durationMs - durationSoFarMs).coerceIn(0L, data.durationMs)
            // 剩余 0 → 立即锁；否则按剩余重启定时器。
            // 注意 handleTimeoutActionWithDelay 内部会先 cancelTimer(vaultId)，
            // 所以这里的旧 job 会被正确取消，不会出现"新旧 job 同时到点锁两次"。
            handleTimeoutActionWithDelay(vaultId = vaultId, delayMs = remainingMs)
        }
    }

    override fun onAppCreated(isFirstCreation: Boolean, createdForAutofill: Boolean) {
        val vaultId = activeVaultId ?: return
        checkForVaultTimeoutInternal(
            vaultId,
            CheckTimeoutReason.AppCreated(
                firstTimeCreation = isFirstCreation,
                createdForAutofill = createdForAutofill,
            ),
        )
    }

    override fun checkForVaultTimeout(vaultId: String) {
        checkForVaultTimeoutInternal(vaultId, CheckTimeoutReason.UserChanged)
    }

    /**
     * 超时检查（对齐 Bitwarden `checkForVaultTimeout`）。
     *
     * 分流规则**逐条照抄**：
     * - `Never` → **直接返回，任何原因都不锁**（见下），`AppCreated` / `AppBackgrounded` 同；
     * - `OnAppRestart` → **只在** `AppCreated` 时触发；且 `createdForAutofill == true`
     *   且**非**首次创建时**豁免**（为 autofill/凭据流程拉起进程不该锁库）；
     * - 其它档位 → `AppCreated(firstTimeCreation = true)` 立即执行；
     *   `AppBackgrounded` / `UserChanged` 走「延迟 N 分钟后执行」。
     *
     * ## ★★ `Never` 档为什么不锁（2026-09-29 二次定稿，用户三次拍板「对齐 Bitwarden」）
     *
     * ⚠️ **本节推翻同日早些时候的结论**，先读「为什么推翻」再改代码。
     *
     * ### 推翻的理由
     *
     * 早先版本把 `Never -> return@launch` 判定为「安全漏洞」，并补了离场软锁
     * （清密钥 + 留信封）+ `AutoRestoreTrigger` 前台门禁。**该判断被推翻**，
     * 因为它误判了 Bitwarden 的安全模型：
     *
     * > Bitwarden 敢在 `Never` 档 `return`（进程创建/切后台都不锁），**不是**
     * > 因为它有恢复信封兜底，而是因为**它信任 Android 的进程内存**：密钥只活在
     * > 进程地址空间里，**进程一死就没了** —— 内存转储威胁的前提是攻击者能拿到
     * > 特权，而那种前提下 Vaultix 的信封同样可被解开（信封用的是**免认证**
     * > `AutoUnlockKeyStore` 密钥）。**信封不构成额外的安全层**，
     * > 它只把「进程死亡」这一个场景从「要指纹」变成「不要指纹」。
     *
     * 换言之，软锁换来的「后台期间密钥不在内存」是**有代价的伪安全**：
     * 真正的安全边界是「进程是否活着」，而非「是否在前台」。
     *
     * ### 代价与补偿（务必知悉）
     *
     * - **代价**：`Never` 档下，进程存活期间房钥匙常驻内存；`adb`/root 环境下
     *   可 dump 进程内存拿到密钥。这**与 Bitwarden 完全一致**（用户明确接受）。
     * - **补偿**：真正的防线是**进程死亡后用恢复信封仍需指纹**吗？—— 不是。
     *   信封是免认证的，所以**进程死亡也不需要指纹**（这正是 Never 档的语义：
     *   「从不要求重新验证」）。用户选择的锁屏防线 = **系统锁屏**（Vaultix 不额外设闸）。
     * - ⇒ 于是 `AutoRestoreTrigger` 的**前台门禁失去意义**（没有软锁要保护），
     *   一并去掉；恢复降级为「只服务进程死亡后的冷启动」（该场景仍然必要，
     *   否则 autofill 拉起的新进程打不开库）。
     *
     * @see AutoUnlockRepositoryImpl.softLock 保留接口但不再被离场路径调用
     */
    private fun checkForVaultTimeoutInternal(vaultId: String, reason: CheckTimeoutReason) {
        scope.launch {
            // ★ **D3**（2026-09-29）：档位**每库一份** —— 读该库自己的档位，
            //   而不是全局单值（全局单值是「两个库不同档位却互相干扰」的来源）。
            val timeout = preferences.vaultTimeout(vaultId).first()

            when (timeout) {
                VaultTimeout.Never -> {
                    // ★★ 对齐 Bitwarden：`VaultTimeout.Never -> return`（不锁）。
                    //   进程创建、切后台、用户切换 —— 一律不触发任何锁定动作。
                    //   密钥常驻内存，直到进程死亡；进程死亡后由恢复信封免交互开回。
                    //   ⚠️ 别在这里加软锁：见上方 KDoc「为什么推翻」。
                    return@launch
                }

                VaultTimeout.OnAppRestart -> {
                    // 仅「进程创建」这一原因会触发；切后台不锁。
                    if (reason is CheckTimeoutReason.AppCreated) {
                        // 首次创建必查；非首次创建时，若本次是为 autofill 拉起 → 跳过。
                        if (reason.firstTimeCreation || !reason.createdForAutofill) {
                            handleTimeoutAction(vaultId)
                        }
                    }
                }

                else -> when (reason) {
                    is CheckTimeoutReason.AppCreated -> {
                        // 冷启动（首次创建）一律按档位执行，保证用户看到的是正确状态；
                        // 非首次创建（如 autofill 拉起）不在此路径处理，避免误锁。
                        if (reason.firstTimeCreation) {
                            handleTimeoutAction(vaultId)
                        }
                    }

                    CheckTimeoutReason.AppBackgrounded,
                    CheckTimeoutReason.UserChanged,
                    -> {
                        val minutes = timeout.vaultTimeoutInMinutes ?: 0
                        handleTimeoutActionWithDelay(
                            vaultId = vaultId,
                            delayMs = minutes.coerceAtLeast(0).toLong() * MS_PER_MINUTE,
                        )
                    }
                }
            }
        }
    }

    /**
     * 延迟执行超时动作（对齐 Bitwarden `handleTimeoutActionWithDelay`）。
     *
     * 先取消同库旧 job（避免同时刻存在多个定时器），再启动新的。
     */
    private fun handleTimeoutActionWithDelay(vaultId: String, delayMs: Long) {
        cancelTimer(vaultId)
        val job = scope.launch {
            delay(timeMillis = delayMs)
            timerJobMap.remove(vaultId)
            handleTimeoutAction(vaultId)
        }
        timerJobMap[vaultId] = TimeoutJobData(
            job = job,
            // ★ 单调时钟（对齐 Bitwarden `realtimeManager.elapsedRealtimeMs`）。
            //   必须是 elapsedRealtime 而非 currentTimeMillis：后者会被用户改系统时间、
            //   被 NTP 校时、被时区切换推动 —— 一旦被往前拨，`durationMs - durationSoFarMs`
            //   就会算错，导致定时器提前或延后触发。亮屏补偿的等差全靠这个基准。
            startTimeMs = SystemClock.elapsedRealtime(),
            durationMs = delayMs,
        )
    }

    /** 执行锁定（对齐 Bitwarden `handleTimeoutAction` 的 `LOCK` 分支）。 */
    private fun handleTimeoutAction(vaultId: String) {
        setVaultToLocked(vaultId)
    }

    /**
     * ~~「从不」档离场软锁~~ —— **已废弃（2026-09-29 二次定稿）**。
     *
     * `Never` 档现在对齐 Bitwarden 直接 `return@launch`，不再有任何离场锁定，
     * 所以本方法**没有任何调用点**。保留为注释而非代码，是为了让接力的下一个
     * 人不至于（像上一轮那样）重新发明这套「软锁 + 前台门禁」的机制：
     * **它不是被遗漏，是被明确否决的**。
     *
     * 之所以删掉：detekt 对未使用的 private 函数报 `UnusedPrivateMember`，
     * 留着会红门禁。
     *
     * 若将来要恢复「离开 App 就清密钥」的档位（用户提过想要「锁屏即锁」），
     * 正确做法是**新增一个独立档位**，而不是把它塞回 `Never` ——
     * 见 `.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md` § 二次定稿。
     */

    /** 取消并移除指定库的定时器。 */
    private fun cancelTimer(vaultId: String) {
        timerJobMap.remove(vaultId)?.job?.cancel()
    }

    /**
     * 立即锁定（对齐 Bitwarden `setVaultToLocked`）。
     *
     * 会话密钥清零由仓储层执行；本管理器只负责发事件。
     */
    private fun setVaultToLocked(vaultId: String) {
        scope.launch {
            vaultRepository.lockVault(vaultId)
            _vaultStateEventFlow.tryEmit(VaultStateEvent.Locked(vaultId))
        }
    }

    /**
     * 超时触发原因（对齐 Bitwarden `CheckTimeoutReason`）。
     *
     * 三个原因对应三类不同的处置：
     * - [AppBackgrounded]：用户离开 App → 启动定时器；
     * - [AppCreated]：进程创建 → 冷启动立即执行；为 autofill 创建时可豁免；
     * - [UserChanged]：账号切换 → 按离开处理（启动定时器）。
     */
    private sealed class CheckTimeoutReason {
        /** 应用进入后台但仍存活。 */
        data object AppBackgrounded : CheckTimeoutReason()

        /**
         * 应用进程创建完成。
         *
         * @param firstTimeCreation 是否为进程的**首次**创建（冷启动）；
         * @param createdForAutofill 本次创建是否由 autofill / 凭据提供商拉起。
         */
        data class AppCreated(
            val firstTimeCreation: Boolean,
            val createdForAutofill: Boolean,
        ) : CheckTimeoutReason()

        /** 活动账号发生变更。 */
        data object UserChanged : CheckTimeoutReason()
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val TAG = "VaultixLockManager"
    }
}
