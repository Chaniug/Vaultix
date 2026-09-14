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
import androidx.compose.ui.unit.dp
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
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultKind
import io.vaultix.vaultix.MainActivity
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.engine.AutofillCandidateSource
import io.vaultix.vaultix.autofill.engine.AutofillDatasetFactory
import io.vaultix.vaultix.autofill.engine.AutofillDatasets
import io.vaultix.vaultix.autofill.engine.FillPlanner
import io.vaultix.vaultix.autofill.engine.AutofillCredentialMapper
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.theme.VaultixTheme
import io.vaultix.vaultix.util.VaultixClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

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

    private var biometricPrompt: BiometricPrompt? = null

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
     * 解锁完只需 `finish()`，让系统重新取一次候选。
     *
     * ⚠️ 不区分这条流程会形成**无限解锁环**：`prepareBiometricUnlock()` 返回 `Ready` 时
     * 会去 `deliverPendingFill()`，而 `takeValid()` 恒为 null ⇒ finish 不带任何结果 ⇒
     * 系统重列候选时若判据未变 ⇒ 再次弹解锁。真机表现即用户说的「**反复让人解锁**」。
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
                        if (credentialFlow) finish() else deliverPendingFill()
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

    /** 找第一个「已锁定且启用本地快速解锁」的库并准备解密 Cipher。 */
    private suspend fun prepareBiometricUnlock(): BiometricUnlockOutcome {
        if (vaultRepository.observeUnlockedVaultIds().first().isNotEmpty()) {
            return BiometricUnlockOutcome.Ready
        }
        val vaults = runCatching { vaultRepository.observeVaults().first() }.getOrDefault(emptyList())
        val lockedIds = vaults.filterNot { it.unlocked }.map { it.id }
        val first = lockedIds.firstOrNull { id ->
            runCatching { vaultRepository.localUnlockAvailable(id).first() }.getOrDefault(false)
        } ?: return BiometricUnlockOutcome.Fallback
        val cipher = runCatching { vaultRepository.prepareLocalUnlock(first) }.getOrNull()
            ?: return BiometricUnlockOutcome.Fallback
        return BiometricUnlockOutcome.Prompt(
            PendingBiometricUnlock(
                first = first,
                rest = lockedIds.filter { it != first },
                cipher = cipher,
            ),
        )
    }

    /** 认证通过：解封首个库，随后趁 KEK 授权窗口解封其余已启用库，然后回灌并 finish。 */
    private fun unlockAllAndFinish(pending: PendingBiometricUnlock, cipher: Cipher) {
        // ⚠️ 诊断埋点（常驻）：看到这行 = 生物认证**已成功**，接下来就是解封 + 回灌。
        AutofillLogger.d(
            "unlockAllAndFinish: 认证成功 first=${pending.first} rest=${pending.rest.size} " +
                "credentialFlow=$credentialFlow",
        )
        lifecycleScope.launch(Dispatchers.IO) {
            unlockAll(pending, cipher)
            withContext(Dispatchers.Main) {
                // CP 流程无暂存可回灌：解锁即收工，让系统重新取候选。
                if (credentialFlow) finish() else deliverPendingFill()
            }
        }
    }

    /** 解封首个库，随后趁 KEK 授权窗口解封其余已启用库（两处解锁路径共用）。 */
    private suspend fun unlockAll(pending: PendingBiometricUnlock, cipher: Cipher) {
        completeLocalUnlockByKind(pending.first, cipher)
        for (id in pending.rest) {
            val c = runCatching { vaultRepository.prepareLocalUnlock(id) }.getOrNull() ?: continue
            completeLocalUnlockByKind(id, c)
        }
    }

    /**
     * 按库类型走正确的本地解锁路径。
     *
     * ⚠️ 不能一律调 `completeLocalUnlock`（定稿 §4）：那条路把包裹物当作
     * **Bitwarden 对称密钥**（`enc ‖ mac`）解析；KDBX 的包裹物是
     * 「主密码 + keyfile」，走过去会解出错误语义 —— `SymmetricCryptoKey.fromFullKey`
     * 拿一段带魔数的字节当密钥，轻则解锁失败，重则把会话建立成一把错密钥。
     * ⇒ 必须先查 kind 再分流。
     */
    private suspend fun completeLocalUnlockByKind(vaultId: String, cipher: Cipher) {
        val kind = runCatching {
            vaultRepository.observeVaults().first()
                .firstOrNull { it.id == vaultId }
                ?.kind
        }.getOrNull()
        if (kind == VaultKind.KDBX) {
            runCatching { vaultRepository.completeLocalUnlockKdbx(vaultId, cipher) }
        } else {
            runCatching { vaultRepository.completeLocalUnlock(vaultId, cipher) }
        }
    }

    /**
     * ★ 解锁即回填：用暂存的解析结果重建 Dataset 并回灌给系统。
     *
     * 三种收尾（都必须 finish，否则用户被扣在一个透明 Activity 上）：
     * - 暂存已过期 / 读不到条目 → 不回灌（`RESULT_CANCELED`），退回浏览器；
     * - 匹配到登录条目 → 回灌**第一条**候选（用户点的是「Vaultix」那一行，
     *   不是某条具体条目，所以取匹配度最高的那条最贴近意图）；
     * - 未匹配 → 回灌第一条候选；一条候选都没有 → 不回灌。
     */
    private suspend fun deliverPendingFill() {
        if (delivered) return
        delivered = true
        val pending = pendingFillStore.takeValid()
        if (pending == null) {
            // ⚠️ 诊断埋点（2026-09-13）：用户报「指纹解锁完还要再解锁、且看不到条目」，
            // 而这条路径此前**完全没有日志** ⇒ 无法判断卡在哪一步。这些 d() 是常驻的，
            // 以后同类问题可直接靠 logcat（tag=VaultixAutofill）定位。
            AutofillLogger.d("deliverPendingFill: 无有效暂存 → 不回灌直接收工")
            finish()
            return
        }
        val parsed = pending.parsed
        val response = runCatching { buildPendingResponse(parsed) }.getOrNull()
        // 一次性语义：无论成功与否都清掉（同一批 AutofillId 不得二次回灌）。
        pendingFillStore.clear()
        AutofillLogger.d("deliverPendingFill: response=${response != null}")
        withContext(Dispatchers.Main) {
            if (response == null) {
                setResult(Activity.RESULT_CANCELED)
            } else {
                setResult(
                    Activity.RESULT_OK,
                    // ⚠️ 必须回 FillResponse（列候选），不是 Dataset（直接填）—— 见函数 KDoc。
                    Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
                )
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
                .padding(horizontal = 24.dp)
                .clickable(onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
