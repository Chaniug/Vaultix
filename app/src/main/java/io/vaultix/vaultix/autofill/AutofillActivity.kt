/*
 * Vaultix — app:autofill
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 自动填充的「认证回灌」宿主：库锁定时引导解锁、无匹配时引导搜索、
 * 主密码二次验证（cipher.reprompt）时验证后回灌 Dataset。
 */
package io.vaultix.vaultix.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.provider.PendingIntentHandler
import io.vaultix.vaultix.passkey.CredentialProviderEntryBuilder
import io.vaultix.vaultix.passkey.CredentialProviderRequestManager
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.common.OtpUriParser
import io.vaultix.common.TotpGenerator
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.AutoUnlockRepository
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.vaultix.MainActivity
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.engine.AutofillCandidateSource
import io.vaultix.vaultix.autofill.engine.AutofillDatasetFactory
import io.vaultix.vaultix.autofill.engine.AutofillDatasets
import io.vaultix.vaultix.autofill.engine.FillPlanner
import io.vaultix.vaultix.autofill.engine.AutofillCredentialMapper
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.unlock.LocalUnlockFanout
import io.vaultix.vaultix.ui.theme.VaultixTheme
import io.vaultix.vaultix.util.VaultixClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 透明宿 Activity：由系统经 PendingIntent 拉起（见 [AutofillIntents]）。
 *
 * 四条路径：
 * - [AutofillIntents.MODE_UNLOCK]：库锁定时**优先原地生物识别解锁**（已启用本地快速解锁时），
 *   认证通过即把本次请求的候选回灌并 finish 返回原 App（★ 解锁即回填，见下）；
 *   未启用 / KEK 失效时回退到引导卡片 → 打开 Vaultix 用主密码解锁，
 *   **用户解锁完回来时本 Activity 仍在栈上**，由 [onResume] 完成回灌；
 * - [AutofillIntents.MODE_SEARCH]：跳转到主界面解锁 / 搜索；
 * - [AutofillIntents.MODE_REPROMPT]：设备认证（生物识别 / 设备凭据）通过后回灌 Dataset；
 * - [AutofillIntents.MODE_COPY_TOTP]：回灌 Dataset 后把验证码复制到剪贴板（全程无界面）。
 *
 * ## ★ 解锁即回填（`.ai/ISSUES.md` #60 第 4 步）
 * 系统只在库锁定时给我们**一次** `onFillRequest`，解锁后**不会**重发。
 * 因此填充服务在锁定时把该次请求的解析结果暂存进 [PendingFillStore]，
 * 解锁完成后由本 Activity 用同一批 `AutofillId` 构造 Dataset，经
 * `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回灌
 * （对齐 Bitwarden `AutofillIntentUtils:109-119` 的 `createAutofillSelectionResultIntent`）。
 *
 * 关键设计：走「打开 Vaultix 解锁」那条路时本 Activity **不 finish** ——
 * 它必须留在栈上，否则解锁完成后没有任何人能投递认证结果
 * （`.ai/ISSUES.md` #25：认证结果只在 Activity 走完生命周期后返回才有效）。
 */
@AndroidEntryPoint
class AutofillActivity : FragmentActivity() {

    @Inject
    lateinit var clipboard: VaultixClipboard

    @Inject
    lateinit var prefs: VaultixPreferences

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var sessionRepository: VaultSessionRepository

    /** 「解锁即回填」的暂存（候选要解锁后才读得到，字段 id 只有请求那一刻拿得到）。 */
    @Inject
    lateinit var pendingFillStore: PendingFillStore

    /** 候选来源：与填充服务共用同一实现，保证两条路径结论一致。 */
    @Inject
    lateinit var candidates: AutofillCandidateSource

    /**
     * 「从不」档的**免交互恢复**（2026-09-29）。
     *
     * 见 [prepareBiometricUnlock] 顶部的前置分支：Never 档 + 信封在 ⇒ 这里恢复，
     * 用户不必按指纹。
     *
     * ⚠️ **与 [io.vaultix.vaultix.security.AutoRestoreTrigger] 的第三分支是
     * 同一语义的两个触发点**（2026-09-29 二次定稿后语义已澄清）：
     * - `AutoRestoreTrigger` 走 **combine 被动响应**（进程启动 / 档位变化时求值），
     *   有调度延迟；
     * - 本分支走 **fillRequest 主动按需**（填充请求到来那一刻同步恢复）。
     *
     * ⇒ **两条都要留**：autofill 的 fillRequest 可能早于 combine 首次求值到达
     * （实测差 32ms），只靠被动那条会出现「信封就在、却仍走指纹 fallback」。
     * ⚠️ 别因为「AutoRestoreTrigger 已经会恢复了」而删掉本分支 —— 上游 Bitwarden
     * 也是「`handleUserAutoUnlockChanges` 自动解锁」+「`isVaultLocked` 等 500ms」
     * 双管齐下，不是单靠一个观察者。
     */
    @Inject
    lateinit var autoUnlock: AutoUnlockRepository

    /**
     * CP 解锁动作的**收尾**要用它重建候选列表。
     *
     * ⚠️ 依赖方向是从 autofill 指向 passkey（`CredentialProviderEntryBuilder`）——
     * 这是有意的：那套构建逻辑原本私有在 CP 服务里，而**收尾必须由解锁 Activity 做**
     * （系统不会回来重查，见 `finishCredentialFlowUnlocked` 的 KDoc）。
     * 与其复制一份，不如让两边共用同一个构建器。
     */
    @Inject
    lateinit var credentialEntryBuilder: CredentialProviderEntryBuilder

    private var biometricPrompt: BiometricPrompt? = null

    /**
     * 「交付后补开其余库」用的**进程级短任务 scope**（2026-09-29）。
     *
     * 为什么不能用 `lifecycleScope`：本 Activity 交付完填充响应就 `finish()`，
     * `lifecycleScope` 随之取消 ⇒ 补开会在半路被掐断，表现为「其余库永远开不满」。
     *
     * 与 `VaultLockManagerImpl` 的既有惯例一致（项目当前唯一的调度器限定符是
     * 自建的 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`，不额外引入 DI 限定符）。
     * `SupervisorJob` 保证一个库补开失败不会连坐取消其余库的补开。
     */
    private val restOpenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** MODE_UNLOCK 下先隐藏卡片、等本地解锁判定；无可判定回退时再亮卡片。 */
    private val showPrompt = mutableStateOf(true)

    /**
     * 已把用户送去主界面解锁，正等他回来。
     *
     * 只有它为 true 时 [onResume] 才会尝试回灌 —— 否则本 Activity 首次启动时的
     * `onCreate → onResume` 就会误判（那一刻库当然还锁着）。
     */
    private var awaitingExternalUnlock = false

    /** 回灌一次性 guard（[onResume] 可能被对话框等打断重入）。 */
    private var delivered = false

    /**
     * 本次启动属于**凭据提供商（CP）**流程（标记见 [AutofillIntents.EXTRA_CREDENTIAL_FLOW]）。
     *
     * CP 的认证动作是**两段式**：用户完成认证后，系统会**重新调用**
     * `onBeginGetCredentialRequest`。因此这条流程里**没有、也不可能有**
     * [PendingFillStore] 暂存 —— 暂存只由 autofill 的 `onFillRequest` 写入。
     *
     * ## ⚠️ 2026-09-17 更正：这里**不能**只 `finish()`
     *
     * 原文写的是"解锁完只需 `finish()`，让系统重新取一次候选" —— **那是错的**，
     * 而且是一个真机可见的死循环的根因：系统是否重列候选，取决于这次
     * `AuthenticationAction` 的 PendingIntent **回没回 `RESULT_OK`**；
     * 只 `finish()` 等于回 `RESULT_CANCELED`，面板会判成"动作没完成"并**重发**。
     * ⇒ 收尾一律走 [finishCredentialFlowUnlocked]（实证与完整时间线见它的 KDoc）。
     *
     * 原文还正确记录过一次同族问题（"[解锁环]：`prepareBiometricUnlock()` 返回 `Ready` 时
     * 会去 `deliverPendingFill()`，而 `takeValid()` 恒为 null ⇒ finish 不带任何结果 ⇒
     * 系统重列候选时若判据未变 ⇒ 再次弹解锁"）—— 当年的修法是"CP 就跳过 `deliverPendingFill`"，
     * 但**漏掉了"跳过"之后仍然要给一个正向结果**。这次补上。
     */
    private val credentialFlow: Boolean by lazy { AutofillIntents.isCredentialFlow(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = AutofillIntents.modeOf(intent)
        if (mode == AutofillIntents.MODE_COPY_TOTP) {
            // ⚠️ 无感中转：**绝不渲染任何界面**。回灌从 onCreate 推迟到 onResume
            // （见 onResume）：认证 Activity 须完整启动后再 setResult + finish，
            // onCreate 同步投递在部分系统版本上认证结果会丢失——此前表现即
            // 「验证码复制成功（Activity 副作用照跑）但密码没填进（dataset 被丢弃）」。
            return
        }
        val title = AutofillIntents.titleOf(intent).ifBlank { getString(R.string.autofill_unlock_title) }
        val subtitle = AutofillIntents.subtitleOf(intent)
        if (mode == AutofillIntents.MODE_UNLOCK) {
            showPrompt.value = false
        }
        setContent {
            VaultixTheme {
                if (showPrompt.value) {
                    AutofillPromptScreen(
                        title = title,
                        subtitle = subtitle,
                        onOpenVault = { openVaultAndFinish() },
                        onDismiss = { finish() },
                    )
                }
            }
        }
        when (mode) {
            AutofillIntents.MODE_UNLOCK -> maybeBiometricUnlock(title, subtitle)
            AutofillIntents.MODE_REPROMPT -> startReprompt(title, subtitle)
            else -> Unit
        }
    }

    private var copyTotpDelivered = false

    override fun onResume() {
        super.onResume()
        // MODE_COPY_TOTP：Activity 已完整启动（onResume），此时回灌认证结果最稳。
        // 一次性 guard：onResume 可能被对话框等打断重入，不得重复投递。
        if (!copyTotpDelivered && AutofillIntents.modeOf(intent) == AutofillIntents.MODE_COPY_TOTP) {
            copyTotpDelivered = true
            deliverDatasetAndDeliverTotp(
                AutofillIntents.titleOf(intent),
                AutofillIntents.subtitleOf(intent),
            )
            return
        }
        // ★ 解锁即回填：用户从 Vaultix 主界面解锁完回到本页 → 立刻把候选回灌。
        if (!delivered && awaitingExternalUnlock) {
            lifecycleScope.launch(Dispatchers.Default) { deliverPendingFill() }
            return
        }
        // 用户在主界面点的是「主页锁按钮」（查看层锁）：密钥仍在内存，回来时库里
        // **仍是已解锁**，我们要做的是把界面门禁的认证走一遍 —— 亮卡片让他「打开 Vaultix」
        // 认证一次，回来时上面那条分支会完成回灌。不这么做的话本页会停在透明状态，
        // 用户看到的就是「点了填充什么都没发生」。
        if (!delivered && !showPrompt.value) {
            lifecycleScope.launch(Dispatchers.Default) {
                if (hasViewLockedVault()) withContext(Dispatchers.Main) { showPrompt.value = true }
            }
        }
    }

    /** 是否有库正处「查看层锁」（界面被挡但密钥仍在 → 填充本可读，只差一次认证）。 */
    private suspend fun hasViewLockedVault(): Boolean {
        if (!sessionRepository.anyViewLocked()) return false
        val unlocked = runCatching { vaultRepository.observeUnlockedVaultIds().first() }
            .getOrDefault(emptySet())
        return unlocked.any { sessionRepository.isViewLocked(it) }
    }

    /** 现在是否至少有一个库是解锁的（CP 收尾判断"这次解锁到底成没成"用）。 */
    private suspend fun isAnyVaultUnlocked(): Boolean =
        runCatching { vaultRepository.observeUnlockedVaultIds().first().isNotEmpty() }
            .getOrDefault(false)

    /**
     * CP（凭据提供商）流程的**成功**收尾：先回 `RESULT_OK` 再 finish。
     *
     * ## 为什么必须回 OK，不能只 `finish()`（2026-09-17 真机实证）
     *
     * 库锁定时的解锁入口是一条 `AuthenticationAction`，它的 `PendingIntent` 指向本 Activity。
     * **Credential Manager 用这次 PendingIntent 的 `resultCode` 判断"用户完成认证动作了吗"**：
     *
     * | 收尾方式 | 系统的判断 | 后果 |
     * |---|---|---|
     * | `RESULT_OK` | 动作完成 | **重新调用** `onBeginGetCredentialRequest`，那时库已解锁、正常列出通行密钥 |
     * | 其他（默认 `RESULT_CANCELED`） | 动作**没完成** | **重发同一个 PendingIntent** |
     *
     * ⚠️ 本 Activity 的解锁路径全是"秒创建秒 finish"（[BiometricUnlockOutcome.Ready] 分支尤其快），
     * 配上 CANCELED 就成了一个 **~2 次/秒的重发循环** —— 用户看到的正是
     * 「明明解锁过了，还是不停让我解锁」。
     *
     * 实测日志（荣耀 BKQ-AN00 · Edge · 2026-09-17，`adb logcat` tag=`VaultixAutofill`）：
     * ```
     * 17:48:42.027  CP GET locked → authenticationActions     （面板弹出「解锁 Vaultix」）
     * 17:48:45.375  unlockAllAndFinish: 认证成功 first=… rest=1（★ 库真的解锁了）
     * 17:48:46.7 ~ 17:49:00.9  maybeBiometricUnlock: outcome=Ready  × 22
     * 同期 ActivityTaskManager：23 次
     *   START … cmp=…/.autofill.AutofillActivity with LAUNCH_MULTIPLE
     *   (realCallingUid=10165 = com.google.android.gms:identitycredentials)
     * ```
     * `realCallingUid` 就是那个凭据面板进程 —— **重发者是面板本身**，不是我们的代码在循环。
     *
     * ## 也解释了用户说的"刷新页面就好了"
     *
     * 刷新会发起**全新的** `getCredential`，重新走一次 `onBeginGetCredentialRequest`，
     * 绕开了这个卡死的认证动作 ⇒ 立刻正常。
     *
     * ## 上游对照
     *
     * Bastion 的解锁 `PendingIntent` 落在 `CredentialProviderActivity`（trampoline），
     * 而那个 trampoline 会 `setResult(result.resultCode, result.data)` **原样透传**结果 ——
     * 契约完全相同，只是载体不同。Vaultix 的载体就是本 Activity，所以结果得由**这里**给出。
     */
    private suspend fun finishCredentialFlowUnlocked() {
        // minSdk 26：CP 只可能在 34+ 发生；这里显式判一次 API 既为 lint `NewApi`，
        // 也把"低版本走不到这条路"写在代码里而不是靠注释约定。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            finishWithRefreshedEntries()
        } else {
            finish()
        }
    }

    /**
     * CP 解锁动作的收尾：**把刷新后的候选列表塞进结果 Intent**，再以 `RESULT_OK` 收工。
     *
     * ## 为什么结果里必须带候选（2026-09-17 真机实证，这是本条 bug 的第二层）
     *
     * 加了 `RESULT_OK` 之后循环停了，但用户看到的是「**Vaultix 没有任何登录信息**，
     * 得手动把面板关掉再来一次才有通行密钥」。原因：
     *
     * > **系统在认证动作完成后不会重新调用 `onBeginGetCredentialRequest`。**
     *
     * 日志实证（荣耀 BKQ-AN00 / Edge）：
     * ```
     * CP GET locked → authenticationActions      ← 面板弹出「解锁 Vaultix」
     * fanout first=… → Success                   ← 指纹过了，库真的解锁了
     * （之后再没有任何 CP GET 这一行）
     * ```
     * ⇒ 面板手里还是那份「只有解锁动作、没有候选」的旧响应。**刷新后的列表只能由我们
     *   放进结果 Intent**：`PendingIntentHandler.setBeginGetCredentialResponse(...)`。
     *
     * ## 上游 Bitwarden 正是这么做的
     *
     * 它的解锁 `PendingIntent` 落在 `CredentialProviderActivity`（trampoline），
     * 由 `CredentialProviderCompletionManagerImpl.completeProviderGetCredentialsRequest`
     * 调 `PendingIntentHandler.setBeginGetCredentialResponse(...)` + `setResult(RESULT_OK)`；
     * 那个方法由**解锁页 / 条目列表页**在解锁成功后调用 —— 所以它"一次指纹进去"。
     * 它同时还 `setAuthenticationActions(emptyList())` 清掉解锁动作，
     * 我们的构建器在非锁定分支已经这么做了（见 `CredentialProviderEntryBuilder`）。
     *
     * ## 拿不到原始请求时怎么办
     *
     * 回一个**空的 `RESULT_OK`**（不塞 payload）—— 那是诚实的降级：面板会收起，
     * 用户重触发一次就能拿到候选。⚠️ **不要**回 `RESULT_CANCELED`：
     * 那会让面板判成"认证动作没完成"并**不停重发**（2026-09-17 实测 23 次）。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun finishWithRefreshedEntries() {
        val request = credentialEntryBuilder.pendingUnlockRequest
        val response = request?.let { pending ->
            runCatching { credentialEntryBuilder.buildGetResponse(pending) }.getOrNull()
        }
        // 请求对象用完即清（它只服务这一轮解锁动作）。
        credentialEntryBuilder.clearPendingUnlockRequest()

        val intent = Intent()
        if (response != null) {
            PendingIntentHandler.setBeginGetCredentialResponse(intent, response)
            AutofillLogger.d(
                "CP unlock → 回灌候选 entries=${response.credentialEntries.size} " +
                    "actions=${response.authenticationActions.size}",
            )
        } else {
            AutofillLogger.d("CP unlock → 无暂存请求，回空结果（用户需再触发一次）")
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun finish() {
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        super.finish()
        // 认证界面是瞬时中转，退出时不做转场动画（避免遮挡被填充的 App）
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * 打开 Vaultix 主界面解锁。
     *
     * ⚠️ **不 finish**：本 Activity 是系统认定的「认证 Activity」，解锁结果只能由它回灌
     * （见类 KDoc）。它留在栈上，用户在 Vaultix 里解锁完按返回 / 解锁页自动返回时，
     * [onResume] 会完成回灌 —— 用户体感就是「解锁一次，密码已经填好了」。
     *
     * 若本次没有可回灌的暂存（例如系统没给 AssistStructure），则保持旧行为直接 finish，
     * 不把用户无意义地扣在一个空白 Activity 上。
     *
     * ⚠️ **唯独 CP 流程例外**：它同样没有暂存，但**仍要留在栈上**等用户从主界面回来。
     * 否则本 Activity 立刻 finish，而系统在「库仍锁定」时会立即重新弹解锁动作 ——
     * 用户体感是「被卡在解锁页反复弹」。留在栈上等返回，回来时 [onResume] 会收尾。
     */
    private fun openVaultAndFinish() {
        val hasPending = pendingFillStore.hasPending()
        // ⚠️ 诊断埋点（常驻）：这是「打开 Vaultix 解锁」**卡片**路径 ——
        // 与生物弹窗路径并列的另一条路，收尾方式完全不同（靠 onResume 回灌）。
        AutofillLogger.d(
            "openVaultAndFinish: hasPending=$hasPending credentialFlow=$credentialFlow " +
                "→ awaitingExternalUnlock=${hasPending || credentialFlow}",
        )
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AutofillIntents.EXTRA_MAIN_UNLOCK_EXIT, true),
        )
        if (hasPending || credentialFlow) {
            awaitingExternalUnlock = true
        } else {
            finish()
        }
    }

    /**
     * MODE_UNLOCK 原地生物解锁：库锁定且任一生效库启用本地快速解锁 → 直接弹
     * BiometricPrompt（KEK 共享，一次认证解封所有已启用库的本地密钥），认证通过即
     * **回灌本次填充**并 finish；无本地快速解锁 / KEK 失效 → 亮卡片走「打开 Vaultix」主密码。
     */
    private fun maybeBiometricUnlock(title: String, subtitle: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = prepareBiometricUnlock()
            // ⚠️ 诊断埋点（常驻）：这三条分支决定用户看到的是「指纹弹窗」还是
            // 「打开 Vaultix 解锁」卡片 —— 两者是完全不同的收尾路径，
            // 出了问题必须能从日志一眼分辨（tag=VaultixAutofill）。
            AutofillLogger.d(
                "maybeBiometricUnlock: outcome=${outcome::class.simpleName} " +
                    "credentialFlow=$credentialFlow hasPending=${pendingFillStore.hasPending()}",
            )
            when (outcome) {
                is BiometricUnlockOutcome.Fallback ->
                    withContext(Dispatchers.Main) { showPrompt.value = true }

                // 竞态：别的入口已解锁（含「查看层锁」——密钥仍可读，填充本不需要再认证）
                // → 直接回灌，不必让用户再验证一次。
                // ⚠️ CP 流程**没有暂存可回灌**（暂存只由 onFillRequest 写入）：直接收工，
                // 系统会重新取一次候选；这里若去 deliverPendingFill() 拿到 null 后 finish，
                // 行为上与直接 finish 等价，但会把「无暂存」伪装成「回灌失败」，日志难读。
                BiometricUnlockOutcome.Ready ->
                    withContext(Dispatchers.Main) {
                        // ⚠️ CP 流程**必须回 `RESULT_OK`，不能只 finish** ——
                        //    否则面板把这次解锁动作当成"没完成"并**不停重发**
                        //    PendingIntent。实证见 [finishCredentialFlowUnlocked]。
                        if (credentialFlow) finishCredentialFlowUnlocked() else deliverPendingFill()
                    }

                is BiometricUnlockOutcome.Prompt -> withContext(Dispatchers.Main) {
                    BiometricPrompter(this@AutofillActivity).authenticate(
                        cipher = outcome.pending.cipher,
                        title = title,
                        subtitle = subtitle.ifBlank { null },
                        cancelText = getString(R.string.action_cancel),
                        onSuccess = { cipher -> unlockAllAndFinish(outcome.pending, cipher) },
                        onError = { _, _, _ ->
                            // 用户取消认证 → 亮卡片给「打开 Vaultix 解锁」这条主密码退路
                            // （而不是直接消失，让用户以为填充坏掉了）。
                            showPrompt.value = true
                        },
                    )
                }
            }
        }
    }

    /**
     * 找第一个「已锁定且纳入快速解锁范围」的库，并备好全局指纹门锁的解密 Cipher。
     *
     * ## 判定顺序（三条，自上而下短路）
     *
     * 0. **「从不」档 + 信封在 ⇒ 免交互恢复**（2026-09-29，方案 A，见方法内联 KDoc）。
     *    这一条排在最前：Never 档压根不该走到指纹这条路。
     * 1. 已有解锁的库 ⇒ `Ready`（竞态：别的入口刚解锁）。
     * 2. 否则备好 cipher ⇒ `Prompt`（弹指纹）/ `Fallback`（亮卡片走主密码）。
     *
     * ## ★ 为什么改成"一次快照"而不是逐库问（2026-09-29 提速修复）
     *
     * 旧实现是 `lockedIds.filter { fingerprintQuickUnlockAvailable(it).first() }` ——
     * **每个库各订阅一次 Flow**，而那条 Flow 内部含 `localUnlockKeyStore.keyAvailable`，
     * 即**一次 Keystore 往返**。N 个库 ⇒ **N 次串行 Keystore**。
     *
     * 真机日志实证：从「决定解锁」到「弹出指纹框」实测 **2.29 秒**
     * （`locked: no unlocked vault → unlock fallback` → `maybeBiometricUnlock: outcome=Prompt`），
     * 用户感知为「提醒我解锁的阶段很慢」。
     *
     * ⚠️ 这正是 `UnlockViewModel.candidateVaultIds` 在批次 5.0 已修过的同一个坑 ——
     * 但 autofill 这条路径**漏改了**（`LocalUnlockFanout.openRest` 也只接到了解锁页）。
     * 两条路径的判据其实是**两个全局事实**相乘，与"具体哪个库"无关：
     * 1. 指纹门锁是否可用 —— **全局一份**（房子化后门锁只有一把）；
     * 2. 该库是否在生效范围内 —— 一次读偏好（`house_key_scope`，DataStore，**零 Keystore**）。
     *
     * ⇒ 问**一次**就够，然后在内存里做集合过滤。与解锁页保持**同一实现口径**，
     * 避免两处再次漂移（本类与该 ViewModel 的漂移已两次造成问题）。
     */
    private suspend fun prepareBiometricUnlock(): BiometricUnlockOutcome {
        if (vaultRepository.observeUnlockedVaultIds().first().isNotEmpty()) {
            return BiometricUnlockOutcome.Ready
        }
        // ★ 「从不」档：信封在就免交互恢复（2026-09-29）。
        //
        // ## 为什么还需要这条分支（真机实证 18:00:10.902 ~ 18:00:11.946）
        //
        // ⚠️ 2026-09-29 **二次定稿后理由已更新**（旧版本的「前台门禁开洞」说已作废：
        //    `AutoRestoreTrigger` 的前台门禁已随「Never 取消软锁」一并删除）。
        //
        // 现在的理由是**时序**，而不是门禁：
        //
        // ```
        // t=10.902  autofill 拉起进程 → AutoRestoreTrigger.combine 首次求值（异步，未完成）
        // t=10.934  fillRequest 到达（比上面只晚 32ms）← 此刻信封还没解
        // t=11.946  locked: no unlocked vault → unlock fallback   ← 明明信封就在，却要指纹
        // ```
        //
        // ⇒ `AutoRestoreTrigger` 的恢复是 **combine 被动响应**，有调度延迟；
        //   本分支是 **fillRequest 主动按需**，同步解信封。**两条互补，缺一不可** ——
        //   只留被动那条，用户每次填充的第一下仍会被索要指纹，与「从不」（= 无交互）
        //   语义相悖。用户实测原话：「提醒我解锁的阶段很慢」。
        //
        // ## 安全性（已与用户确认取舍）
        //
        // `AutoUnlockRepository.restore()` → `HouseKeyStore.openAutoEnvelope()` 用的是
        // `AutoUnlockKeyStore`（`setUserAuthenticationRequired(false)`，**免认证**密钥），
        // 不弹指纹、不阻塞。这与「用户自己打开 App 时信封自动恢复」是**同一个信任级别**
        // —— 本分支不新建信任模型，只是把已有语义延伸到 autofill。
        // 代价：手机解锁状态下，任何 App 的输入框都能免验证自动填充；
        // 已同步补进设置页「从不」档的风险提示文案。
        //
        // ⚠️ 只对 Never 档生效：其它档位「回来要验证」本来就是设计意图，
        //    这里若放宽会把定时锁的安全保证整个抹掉。
        if (prefs.vaultTimeout.first() == VaultTimeout.Never && autoUnlock.hasEnvelope()) {
            val report = autoUnlock.restore()
            if (report.opened > 0) {
                AutofillLogger.d(
                    "prepareBiometricUnlock: Never 档信封恢复 envelope=${report.envelopeOpened} " +
                        "rooms=${report.roomCount} opened=${report.opened} → Ready（免交互）",
                )
                return BiometricUnlockOutcome.Ready
            }
            // 信封在但一个库都没开成（凭据过期 / 文件被移走）⇒ 落到指纹那条路，
            // 不谎报 Ready（Ready 会让下游直接交付 null，日志读起来像「填充坏了」）。
            AutofillLogger.d(
                "prepareBiometricUnlock: Never 档信封恢复 0 库成功 → 回落指纹 " +
                    "failed=${report.failedVaultIds.size}",
            )
        }
        val vaults = runCatching { vaultRepository.observeVaults().first() }.getOrDefault(emptyList())
        // 一次读范围（DataStore，零 Keystore），与库列表在内存里求交。
        // ⚠️ 只挑**已纳入范围**的锁定库，与解锁页的 `candidateVaultIds` 同一口径：
        //    对只走主密码的库调 `unlockVaultFromRoom` 必然 NotEnrolled，
        //    白跑一趟还多算一次失败，日志里会冒出一堆莫名其妙的"未打开"。
        val scope = runCatching { prefs.quickUnlockScope().first() }.getOrDefault(emptySet())
        val unlockable = vaults
            .filter { !it.unlocked && it.id in scope }
            .map { it.id }
        val first = unlockable.firstOrNull() ?: return BiometricUnlockOutcome.Fallback
        // 门锁是全局的（房子化）：cipher 不再按库取，一次认证解门锁、逐库开房间。
        val cipher = runCatching { vaultRepository.prepareFingerprintUnlock() }.getOrNull()
            ?: return BiometricUnlockOutcome.Fallback
        return BiometricUnlockOutcome.Prompt(
            PendingBiometricUnlock(
                first = first,
                rest = unlockable.filter { it != first },
                cipher = cipher,
            ),
        )
    }

    /**
     * 认证通过：先解封**首个库**（用户点指纹要开的那个）→ 立即交付填充响应 →
     * 其余已启用库改由 [openRemainingInBackground] 异步补开。
     *
     * ## 为什么不能等全部库都开完才交付（2026-09-29，真机实证）
     *
     * 旧实现对 `pending.rest` 一并串行解锁（`LocalUnlockFanout.unlockAll` 的 `for` 循环），
     * 全部开完才 `buildPendingResponse` / `deliverPendingFill`。真机日志实证：
     *
     * ```
     * fanout first=https://pwd.vv1234.cn → Opened        30ms    ← 核心库极快
     * fanout rest=onedrive:…valkjin.kdbx → Opened      3315ms    ← 网络下载 + KDF
     * buildPendingResponse: unlocked=2                          ← 两库全开才构造响应
     * deliverPendingFill: response=true
     * ```
     *
     * KDBX 库的「开房间」并非纯软件解密 —— `unlockVaultFromRoom` 对 KDBX 会经
     * `Kdbx.unlock(source)` **真的打开文件**（OneDrive 源 = 网络拉取 + KDF 派生），
     * 实测稳定 3.3 秒（三次样本 3.32 / 3.65 / 3.32 秒）。
     *
     * ⚠️ **代价不只是慢**：系统 autofill 框架在等我们交 `FillResponse`，这段等待会
     * 反馈到宿主输入交互上 —— 用户实测「条目弹出卡顿，连 QQ 都卡」。
     * 而其余库对**本次填充**毫无贡献（用户要的是他点的那个库的条目）。
     *
     * ⇒ 与解锁页 `UnlockViewModel.completeLocalUnlock` 完全对齐：`rest = emptyList()`
     * 只开核心库、立即放行，其余库「发射后不管」。
     */
    private fun unlockAllAndFinish(pending: PendingBiometricUnlock, cipher: Cipher) {
        // ⚠️ 诊断埋点（常驻）：看到这行 = 生物认证**已成功**，接下来就是解封 + 回灌。
        AutofillLogger.d(
            "unlockAllAndFinish: 认证成功 first=${pending.first} rest=${pending.rest.size} " +
                "credentialFlow=$credentialFlow",
        )
        lifecycleScope.launch(Dispatchers.IO) {
            // ★ 先开核心库就放行（2026-09-29，对齐 UnlockViewModel.completeLocalUnlock）。
            //
            // 见 `unlockAllAndFinish` 的「为什么不能等全部库」KDoc：其余库（尤其是
            // OneDrive 上的 KDBX —— 网络下载 + KDF 派生，实测 3.3 秒）是**附加收益**，
            // 绝不该挡住用户点的那个库。旧实现把 rest 也塞进这一轮串行解锁，
            // 于是「指纹过了却要干等三秒」——而且系统 autofill 框架在等我们交响应，
            // 连宿主 QQ 的输入交互都被拖住（用户实测反馈）。
            val result = unlockAll(pending.copy(rest = emptyList()), cipher)
            withContext(Dispatchers.Main) {
                when {
                    // CP 流程无暂存可回灌：解锁**成功**就回 RESULT_OK 收工
                    // （见 finishCredentialFlowUnlocked 的 KDoc —— 少了它面板会死循环）。
                    credentialFlow && result.first is RoomUnlockOutcome.Opened -> {
                        // ★ 标记「本流程内刚完成过设备验证」。
                        //
                        // 为什么在这里、只在这里：用户刚为**完成 CP 认证动作**做过一次
                        // 生物识别（`maybeBiometricUnlock` 的 Prompt 分支），而且**解封成功**
                        // ——这是"用户在场且被验证"的唯一可信来源。
                        //
                        // 为什么必须 `credentialFlow`：非 CP 流程（普通 autofill）没有后续的
                        // 通行密钥断言，置位只会让标记在白等中过期（且违反"流程内"语义）。
                        //
                        // 为什么必须 `result.first is Opened`：认证过了但开房失败时，
                        // 库仍是锁定态 —— 此时置位会让候选列表把"已验证"传下去，
                        // 而实际上库打不开（`PasskeyGetActivity` 的库态校验会拦住，
                        // 但**不该**依赖下游兜底，这里就不该置）。
                        //
                        // ⚠️ 绝不由「库已解锁」这类**状态**反推（决策文档 §3 I2）：
                        // 解锁可能来自主密码、可能发生在很久以前。
                        CredentialProviderRequestManager.markUserPreVerified()
                        AutofillLogger.d("CP unlock → 标记 UV 已完成（供候选断言复用）")
                        // ★ 其余库同样改后台补开（2026-09-29）：CP 流程的收尾要重建候选，
                        //   其余库未开会让候选缺项 —— 但不能为它挡住 RESULT_OK 的回执
                        //   （面板在等，见 finishCredentialFlowUnlocked 的 KDoc）。
                        openRemainingInBackground(pending, result.lockOpened)
                        finishCredentialFlowUnlocked()
                    }

                    // 认证过了但解封失败（KEK 失效 / 主密码被改过）：**不能谎报成功**，
                    // 保持默认的 CANCELED 让用户重试或改走主密码。
                    credentialFlow -> {
                        AutofillLogger.d(
                            "CP 解封未成功（first=${result.first::class.simpleName}）→ 不回 OK，也不标记 UV",
                        )
                        finish()
                    }

                    else -> {
                        AutofillLogger.d(
                            "unlockAllAndFinish: 解封结果 first=${result.first::class.simpleName} " +
                                "opened=${result.restOpened} failed=${result.restFailed}",
                        )
                        // ★ 交付**先于**补开其余库（2026-09-29）：`deliverPendingFill()` 是
                        //   用户等的那一步（条目出现在输入框下方），必须第一优先；其余库
                        //   在交付完成后再异步补开（`openRemainingInBackground`）。
                        deliverPendingFill()
                        openRemainingInBackground(pending, result.lockOpened)
                    }
                }
            }
        }
    }

    /**
     * 交付完成后，在后台补开其余已启用库（对齐 `UnlockViewModel.openRemainingInBackground`）。
     *
     * 为什么独立成一个「发射后不管」的协程：其余库是**附加收益** —— 用户点指纹的目的是
     * 开他自己那个库，其余库开不开都不该影响他已拿到的填充结果。失败不打搅用户
     * （只在日志留痕），与解锁页的处置保持一致。
     *
     * @param houseKeyInMemory 房钥匙是否已在内存（`LocalUnlockFanout.Result.lockOpened`）。
     *   false 时直接跳过：没有钥匙，`unlockVaultFromRoom` 必然全败，白跑一轮还刷一堆日志。
     *
     * ⚠️ **不需要 cipher**：门锁已在 `unlockAll` 里用本次认证的 cipher 解开了，
     *   此后各库是纯 `unlockVaultFromRoom`（房间信封，不碰 Keystore）。
     *   若这里再收一个 cipher 会暗示「还要一次 Keystore 操作」，误导后来者。
     */
    private fun openRemainingInBackground(
        pending: PendingBiometricUnlock,
        houseKeyInMemory: Boolean,
    ) {
        if (pending.rest.isEmpty() || !houseKeyInMemory) return
        // ⚠️ 用 restOpenScope（进程级）而非 lifecycleScope：本 Activity 交付完就会 finish，
        //   lifecycleScope 随之取消 ⇒ 补开会在半路被掐断（现象：其余库永远开不满）。
        restOpenScope.launch(Dispatchers.IO) {
            val opened = LocalUnlockFanout.openRest(
                repository = vaultRepository,
                rest = pending.rest,
                houseKeyInMemory = true,
            )
            AutofillLogger.d("unlockAllAndFinish: 后台补开其余库 opened=$opened/${pending.rest.size}")
        }
    }

    /**
     * 解封首个库，随后趁 KEK 授权窗口解封其余已启用库（两处解锁路径共用）。
     *
     * ★ 2026-09-17：**返回**解封结论（此前把 `LocalUnlockFanout.Result` 直接丢掉）。
     * 丢掉它意味着"指纹过了但某个库根本没打开"这件事**没有任何出口** ——
     * 调用方既不能如实告知用户，也没法在 CP 流程里决定该不该回 `RESULT_OK`
     * （谎报成功会让面板重列候选、却仍是锁定态，用户更困惑）。
     */
    private suspend fun unlockAll(
        pending: PendingBiometricUnlock,
        cipher: Cipher,
    ): LocalUnlockFanout.Result {
        // ★ 2026-09-16：与解锁页共用同一份实现（`LocalUnlockFanout`）。
        //   此前这里与 `UnlockViewModel` 各写一份"按库类型分流"，而那条分流一旦
        //   写错只会**静默失效**（KDBX 走 Bitwarden 那条路会解出错误语义）——
        //   两份实现意味着同一个坑埋两次，且很可能只修好一处。
        return LocalUnlockFanout.unlockAll(
            repository = vaultRepository,
            first = pending.first,
            rest = pending.rest,
            cipher = cipher,
        )
    }

    /**
     * ★ 解锁即回填：用暂存的解析结果重建 Dataset 并回灌给系统。
     *
     * 收尾（都必须 finish，否则用户被扣在一个透明 Activity 上）。
     * ⚠️ 2026-09-28 修订（B′）：**判据从"有没有回灌内容"改成"库到底解锁了没"** ——
     * 只有「**确实仍未解锁**」才回 `RESULT_CANCELED`；其余一律回 `RESULT_OK`。
     * 理由见下方两处 ★ 注释：非 OK 会被系统判成「认证动作没完成」并**重发解锁**，
     * 那正是用户报的「反复要求解锁」。
     * - 无有效暂存 + 库**已解锁** → 空 `RESULT_OK`（降级：用户再触发一次即得候选）；
     * - 无有效暂存 + 库**仍未解锁** → `CANCELED`（用户可能只是按返回键退回来）；
     * - 重建出候选 → `RESULT_OK` + `FillResponse`（列候选让用户挑）；
     * - 已解锁但**重建不出候选**（`AutofillId` 失效）→ 空 `RESULT_OK`（不重发）；
     * - 仍未解锁 → `CANCELED`。
     */
    private suspend fun deliverPendingFill() {
        if (delivered) return
        delivered = true
        val pending = pendingFillStore.takeValid()
        if (pending == null) {
            // ⚠️ 诊断埋点（2026-09-13）：用户报「指纹解锁完还要再解锁、且看不到条目」，
            // 而这条路径此前**完全没有日志** ⇒ 无法判断卡在哪一步。这些 d() 是常驻的，
            // 以后同类问题可直接靠 logcat（tag=VaultixAutofill）定位。
            //
            // ★ 2026-09-28 修（B′ 第一处）：**去掉 `else false`**。
            //
            // 原状（病灶）：
            //     val unlockedNow = if (credentialFlow) isAnyVaultUnlocked() else false
            // 非 CP 路（= 普通自动填充，浏览器/输入法点填充框）恒为 false ⇒ 走下面的
            // `finish()`（不带 resultCode = `RESULT_CANCELED`）⇒ 系统判成「认证动作没完成」
            // **不停重发**。这与 CP 路 2026-09-17 修掉的是**同一个坑**
            // （见 [finishCredentialFlowUnlocked] 的 KDoc：实测重发 23 次）。
            //
            // 逻辑上这也是矛盾的：走到「无有效暂存」这一步，**恰恰说明用户刚完成了外部解锁**
            // —— 普通路的入口是 onResume 的 `awaitingExternalUnlock` 分支（[openVaultAndFinish]
            // 置位），用户不打开 Vaultix 解锁是不会回来的。把它当成「没解锁」等于：
            // 用户刚解锁完，我们回一个"没解锁"，系统再弹一次解锁。
            //
            // ⚠️ 但**不能无条件报 OK**：用户可能只是按返回键退回来（没真解锁），
            //   那时报 OK 就是"假成功"（面板重列候选 ⇒ 还是解锁入口 ⇒ 更困惑）。
            //   ⇒ 两条路统一做**真实检查**（CP 路原本就有，普通路补上）。
            val unlockedNow = isAnyVaultUnlocked()
            AutofillLogger.d(
                "deliverPendingFill: 无有效暂存 → 收工（credentialFlow=$credentialFlow " +
                    "unlockedNow=$unlockedNow）",
            )
            if (unlockedNow) {
                // ⚠️ 两条路的「正向收尾」**载体不同**，不能用同一个方法：
                //  - CP 路：走 Credential Manager 的 `PendingIntentHandler.setBeginGetCredentialResponse`
                //    （`finishCredentialFlowUnlocked`，@RequiresApi(34)）——**Autofill 框架用不了**。
                //  - 普通路：走 Autofill 框架的 `RESULT_OK` + `EXTRA_AUTHENTICATION_RESULT`
                //    （实证见 VaultixAutofillService 的 buildResponse，
                //    以及 reference/bastion/.../AutofillAuthenticationActivity.kt:249-258）。
                // ★ 2026-09-28：这里原本两条路都调 `finishCredentialFlowUnlocked()`，
                //   对普通路是**错的载体**（低版本还会直接 finish()=CANCELED，等于没修）。
                if (credentialFlow) {
                    finishCredentialFlowUnlocked()
                } else {
                    // 普通 autofill：没有暂存 ⇒ 重建不出 FillResponse（模板 id 已随暂存丢失）。
                    // 回一个**空的 `RESULT_OK`** —— 与 CP 路 L348-350 的降级同款：
                    // 面板收起、判成"动作已完成"，**不再重发**；用户重新点一次填充即拿到候选。
                    // ⚠️ 关键就是**不能回 CANCELED**：那会让系统判成"没完成"并重发（本次修的病灶）。
                    AutofillLogger.d("autofill unlock → 无暂存可回灌，回空 RESULT_OK（不重发）")
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            } else {
                finish()
            }
            return
        }
        val parsed = pending.parsed
        val response = runCatching { buildPendingResponse(parsed) }.getOrNull()
        // 一次性语义：无论成功与否都清掉（同一批 AutofillId 不得二次回灌）。
        pendingFillStore.clear()
        AutofillLogger.d("deliverPendingFill: response=${response != null}")
        // ★ 2026-09-28（B′ 第二处）：`response == null` 的两种成因，收尾必须分开。
        //
        // 原状是**一律回 CANCELED**，于是系统判成「认证动作没完成」⇒ **重发解锁**。
        // 但两种成因里只有一种是"真的没成"：
        //  ① `unlocked.isEmpty()`（库**仍是锁定**）→ 回 CANCELED 合理（解锁确实没发生）；
        //  ② `added == 0`（库**已解锁**，只是这批字段重建不出候选）→ 回 CANCELED **是错的**：
        //     用户明明解锁成功了，却因"没有候选"被判成"没解锁" ⇒ 再弹一次 ⇒
        //     **这正是用户报的「匹配不到条目 + 反复解锁」的成因**（见 buildPendingResponse
        //     L650 的既有记载：「观感就是『一直让我继续解锁』」）。
        // ⇒ ② 要回 `RESULT_OK`：动作完成（面板收起、不重发）；用户重新触发一次即可拿到候选。
        val unlockedNowForRebuild = isAnyVaultUnlocked()
        withContext(Dispatchers.Main) {
            when {
                response != null -> setResult(
                    Activity.RESULT_OK,
                    // ⚠️ 必须回 FillResponse（列候选），不是 Dataset（直接填）—— 见函数 KDoc。
                    Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
                )

                // ② 已解锁、但无候选：动作**完成**了，诚实地回 OK（空 payload）不重发。
                unlockedNowForRebuild -> {
                    AutofillLogger.d(
                        "deliverPendingFill: 已解锁但无候选（AutofillId 可能已失效）→ 回空 RESULT_OK",
                    )
                    setResult(Activity.RESULT_OK)
                }

                // ① 仍未解锁：确实没成，保持 CANCELED（让用户重试或改走主密码）。
                else -> setResult(Activity.RESULT_CANCELED)
            }
            finish()
        }
    }

    /**
     * 暂存的解析结果 → **FillResponse（含全部匹配条目）**。
     *
     * ⚠️ 2026-09-13 定案（用户报「指纹解锁后看不到密码条目、只反复让我解锁」，logcat 实证）：
     * `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 的语义**按类型分岔** ——
     *  - 回 **`Dataset`**：系统把**这一个**直接填进去，用户**看不到任何候选列表**；
     *  - 回 **`FillResponse`**：系统把里面的 datasets **列成候选让用户挑**。
     * 用户要的"解锁后出现密码条目"= 后者（也是 Bitwarden 的行为）。
     * 旧的实现回的是单个 Dataset ⇒ 填是填了，但下拉里仍然是那条旧的「解锁 Vaultix」，
     * 用户以为没生效、再点一次 ⇒ 此时库已解锁 ⇒ `Ready` 分支 ⇒ 无暂存 ⇒ 什么都不做
     * ⇒ 观感就是「一直让我继续解锁」。
     *
     * ⚠️ 对照组：[deliverDataset] 那条路径回的是 `Dataset`，那是**用户已经选定了某个条目**
     * （二次验证 / 主密码复核）之后的回灌 —— 那里"直接填"才是对的，别一起改。
     */
    private suspend fun buildPendingResponse(
        parsed: io.vaultix.vaultix.autofill.model.ParsedStructure,
    ): android.service.autofill.FillResponse? {
        val unlocked = vaultRepository.observeUnlockedVaultIds().first()
        AutofillLogger.d("buildPendingResponse: unlocked=${unlocked.size}")
        if (unlocked.isEmpty()) return null
        val sources = candidates.singleActiveVault(unlocked)
        val vault = candidates.collectCandidates(sources)
        val webDomain = candidates.webDomainOf(parsed)
        val matched = candidates.matchLogins(vault.credentials, parsed, webDomain)
        val plan = FillPlanner.plan(
            context = AutofillCredentialMapper.toFillContext(parsed, webDomain),
            matchedLogins = matched,
            cards = vault.cards,
            identities = vault.identities,
            totpProvider = AutofillDatasetFactory::totpCode,
            serverOrigin = vault.serverOrigin,
        )
        val copyTotp = runCatching { prefs.autoCopyTotp.first() }.getOrDefault(true)
        val builder = android.service.autofill.FillResponse.Builder()
        var added = 0
        // 上限保护同服务端：FillResponse 经 Binder 传输有大小限制。
        for (suggestion in plan.suggestions.take(maxUnlockDatasets)) {
            val dataset = AutofillDatasetFactory
                .datasetFor(this, parsed, suggestion, copyTotp) ?: continue
            builder.addDataset(dataset)
            added++
        }
        AutofillLogger.d("buildPendingResponse: datasets=$added")
        return if (added > 0) builder.build() else null
    }

    /** 「解锁即回填」一次最多列出的条目数（Binder 传输上限保护，同服务端）。 */
    private val maxUnlockDatasets = 10

    /** 二次验证：设备认证通过后把 Dataset 回灌给系统。 */
    private fun startReprompt(title: String, subtitle: String) {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                deliverDataset(title, subtitle)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // 用户取消 / 设备无凭据：直接收起，不回灌任何数据
                finish()
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        biometricPrompt = prompt
        prompt.authenticate(promptInfo(title, subtitle))
    }

    private fun promptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
        if (subtitle.isNotBlank()) builder.setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 含 DEVICE_CREDENTIAL 时由系统凭据面板提供返回，禁止再设负按钮
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(R.string.action_cancel))
        }
        return builder.build()
    }

    /**
     * 回填 Dataset 后把验证码复制到剪贴板（页面没有验证码框时的 2FA 第二步）。
     *
     * 保持**最简链路**：识别到条目 → 填密码 → 验证码进剪贴板，不做额外的通知/常驻服务。
     * 复制动作仍受 [VaultixPreferences.autoCopyTotp] 门控（此前该开关只影响是否挂认证意图、
     * 没门控复制本身，是个既有 bug）。
     *
     * 复制走 [VaultixClipboard]（安全剪贴板：IS_SENSITIVE + 按偏好自动清除），
     * 并用 **ProcessLifecycleOwner** 作用域——本 Activity 会立刻 finish()，
     * 用 lifecycleScope 会来不及跑完（Bastion 踩过的坑）。
     */
    private fun deliverDatasetAndDeliverTotp(title: String, subtitle: String) {
        deliverDataset(title, subtitle)
        val secret = AutofillIntents.totpSecretOf(intent) ?: return
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            if (!runCatching { prefs.autoCopyTotp.first() }.getOrDefault(false)) return@launch
            val code = runCatching {
                OtpUriParser.parse(secret)?.let { TotpGenerator.generate(it) }
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                clipboard.copy(
                    text = code,
                    autoClearMs = prefs.clipboardClearMs.first(),
                )
                Toast.makeText(
                    this@AutofillActivity,
                    getString(R.string.copy_totp),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun deliverDataset(title: String, subtitle: String) {
        val dataset = AutofillDatasets.build(
            context = this,
            entries = AutofillIntents.entriesOf(intent),
            title = title,
            subtitle = subtitle,
            datasetId = AutofillIntents.datasetIdOf(intent),
            // 回灌的 Dataset 是同一个条目，图标必须与原条目一致（否则二次验证后
            // 换了个图标，看起来像换了条目）。图标按「标题 → 字母头像」确定性生成，
            // 同一标题必然得到同一个颜色与字母；类别由 Intent 带入，缺失则回退登录。
            category = AutofillIntents.categoryOf(intent) ?: io.vaultix.vaultix.autofill.model.FillCategory.LOGIN,
        )
        if (dataset == null) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset),
            )
        }
        finish()
    }
}

/** 原地生物解锁的准备工作（首个要解锁的库 + 其余待解锁库 + 已认证 Cipher）。 */
private data class PendingBiometricUnlock(
    val first: String,
    val rest: List<String>,
    val cipher: Cipher,
)

/**
 * [AutofillActivity.prepareBiometricUnlock] 的三种结论。
 *
 * 为什么不用可空返回值：`null` 同时要表达「库已解锁（直接回灌）」与
 * 「没有可用的本地解锁（亮卡片）」两件**相反**的事 —— 前者应当立刻把密码填进去，
 * 后者要把用户送去主界面。合并成一个值必然有一边行为错（`.ai/ISSUES.md`
 * 里「锁态与不存在混为一谈」的同类教训）。
 */
private sealed interface BiometricUnlockOutcome {
    /** 可原地认证：弹 BiometricPrompt。 */
    data class Prompt(val pending: PendingBiometricUnlock) : BiometricUnlockOutcome

    /** 库已解锁（或密钥仍在查看锁下可用）→ 直接回灌，无需认证。 */
    data object Ready : BiometricUnlockOutcome

    /** 无处可认证（未启用快速解锁 / KEK 不可用）→ 亮卡片走主密码。 */
    data object Fallback : BiometricUnlockOutcome
}

/** 认证 / 引导卡片（透明遮罩 + 居中卡片，点遮罩即收起）。 */
@Composable
private fun AutofillPromptScreen(
    title: String,
    subtitle: String,
    onOpenVault: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl)
                .clickable(onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onOpenVault, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.autofill_unlock_action))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
