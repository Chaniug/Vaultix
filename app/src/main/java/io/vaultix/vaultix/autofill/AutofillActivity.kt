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
import io.vaultix.vaultix.MainActivity
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.engine.AutofillDatasets
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
 * 三条路径：
 * - [AutofillIntents.MODE_UNLOCK]：库锁定时**优先原地生物识别解锁**（已启用本地快速解锁时），
 *   认证通过即 finish 返回原 App——用户回浏览器再点一次即秒填；未启用 / KEK 失效时
 *   回退到引导卡片 → 打开 Vaultix 用主密码解锁；
 * - [AutofillIntents.MODE_SEARCH]：跳转到主界面解锁 / 搜索；
 * - [AutofillIntents.MODE_REPROMPT]：设备认证（生物识别 / 设备凭据）通过后回灌 Dataset。
 */
@AndroidEntryPoint
class AutofillActivity : FragmentActivity() {

    @Inject
    lateinit var clipboard: VaultixClipboard

    @Inject
    lateinit var prefs: VaultixPreferences

    @Inject
    lateinit var vaultRepository: VaultRepository

    private var biometricPrompt: BiometricPrompt? = null

    /** MODE_UNLOCK 下先隐藏卡片、等本地解锁判定；无可判定回退时再亮卡片。 */
    private val showPrompt = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = AutofillIntents.modeOf(intent)
        if (mode == AutofillIntents.MODE_COPY_TOTP) {
            // ⚠️ 无感中转：**绝不渲染任何界面**。此前先 setContent 再回填，卡片会闪一下，
            // 用户正好点中「打开 Vaultix 解锁」→ 表现为「点填充却跳到库页面、密码没填进去」。
            deliverDatasetAndCopyTotp(
                AutofillIntents.titleOf(intent),
                AutofillIntents.subtitleOf(intent),
            )
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

    override fun finish() {
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        super.finish()
        // 认证界面是瞬时中转，退出时不做转场动画（避免遮挡被填充的 App）
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun openVaultAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AutofillIntents.EXTRA_MAIN_UNLOCK_EXIT, true),
        )
        finish()
    }

    /**
     * MODE_UNLOCK 原地生物解锁：库锁定且任一生效库启用本地快速解锁 → 直接弹
     * BiometricPrompt（KEK 共享，一次认证解封所有已启用库的本地密钥），认证通过即
     * finish 返回原 App；无本地快速解锁 / KEK 失效 → 亮卡片走「打开 Vaultix」主密码。
     */
    private fun maybeBiometricUnlock(title: String, subtitle: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val pending = prepareBiometricUnlock()
            if (pending == null) {
                withContext(Dispatchers.Main) { showPrompt.value = true }
                return@launch
            }
            withContext(Dispatchers.Main) {
                BiometricPrompter(this@AutofillActivity).authenticate(
                    cipher = pending.cipher,
                    title = title,
                    subtitle = subtitle.ifBlank { null },
                    cancelText = getString(R.string.action_cancel),
                    onSuccess = { cipher -> unlockAllAndFinish(pending, cipher) },
                    onError = { _, _ -> finish() },
                )
            }
        }
    }

    /** 找第一个「已锁定且启用本地快速解锁」的库并准备解密 Cipher；无可解锁 → null。 */
    private suspend fun prepareBiometricUnlock(): PendingBiometricUnlock? {
        if (vaultRepository.observeUnlockedVaultIds().first().isNotEmpty()) {
            // 竞态：别的入口已解锁 → 直接收工，用户回浏览器重点即可
            withContext(Dispatchers.Main) { finish() }
            return null
        }
        val vaults = runCatching { vaultRepository.observeVaults().first() }.getOrDefault(emptyList())
        val lockedIds = vaults.filterNot { it.unlocked }.map { it.id }
        val first = lockedIds.firstOrNull { id ->
            runCatching { vaultRepository.localUnlockAvailable(id).first() }.getOrDefault(false)
        } ?: return null
        val cipher = runCatching { vaultRepository.prepareLocalUnlock(first) }.getOrNull() ?: return null
        return PendingBiometricUnlock(first = first, rest = lockedIds.filter { it != first }, cipher = cipher)
    }

    /** 认证通过：解封首个库，随后趁 KEK 授权窗口解封其余已启用库，然后 finish。 */
    private fun unlockAllAndFinish(pending: PendingBiometricUnlock, cipher: Cipher) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { vaultRepository.completeLocalUnlock(pending.first, cipher) }
            for (id in pending.rest) {
                val c = runCatching { vaultRepository.prepareLocalUnlock(id) }.getOrNull() ?: continue
                runCatching { vaultRepository.completeLocalUnlock(id, c) }
            }
            withContext(Dispatchers.Main) { finish() }
        }
    }

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
     * 回填 Dataset 后自动复制验证码（页面没有验证码框时的 2FA 第二步）。
     *
     * 复制走 [VaultixClipboard]（安全剪贴板：IS_SENSITIVE + 按偏好自动清除），
     * 并用 **ProcessLifecycleOwner** 作用域——本 Activity 会立刻 finish()，
     * 用 lifecycleScope 会来不及跑完（Bastion 踩过的坑）。
     */
    private fun deliverDatasetAndCopyTotp(title: String, subtitle: String) {
        deliverDataset(title, subtitle)
        val secret = AutofillIntents.totpSecretOf(intent) ?: return
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
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
