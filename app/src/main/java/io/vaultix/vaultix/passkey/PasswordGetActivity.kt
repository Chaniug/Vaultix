/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 密码「填充」宿主：由 Credential Provider 的 GET(password) 候选 PendingIntent 拉起。
 *
 * 与 PasskeyGetActivity 不同：库已解锁时才会出现此候选（锁定走「解锁 Vaultix」入口），
 * 因此无需再做设备验证签名，仅需一次明文确认即可把解密后的 (用户名, 密码) 经
 * `PendingIntentHandler.setGetCredentialResponse` 回灌给 Credential Manager（由系统直接填入
 * 登录字段，等价于 Bitwarden 在 Android 14+ 上的密码填充行为）。
 *
 * 安全约定（同 PasskeyGetActivity）：按 (vaultId, itemId) 从已解锁内存仓储取明文，绝不走 Intent。
 */
package io.vaultix.vaultix.passkey

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.VaultixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@AndroidEntryPoint
class PasswordGetActivity : FragmentActivity() {

    @Inject
    lateinit var itemRepository: ItemRepository

    /** 只用于读**锁态快照**（与 CP 列候选同一真源，含 KDBX 会话）；不持有任何密钥。 */
    @Inject
    lateinit var vaultRepository: VaultRepository

    private lateinit var vaultId: String
    private lateinit var itemId: String
    private var item: VaultItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注：旧版在这里 CredentialFlowGuard.markFlowStarted() 豁免自动锁定；
        // 已改为由 CredentialProviderActivity 以 createdForAutofill = true 通知
        // VaultLockManager（结构性豁免，对齐 Bitwarden）。

        vaultId = intent.getStringExtra(PasskeyProviderIntents.EXTRA_VAULT_ID).orEmpty()
        itemId = intent.getStringExtra(PasskeyProviderIntents.EXTRA_ITEM_ID).orEmpty()

        if (listOf(vaultId, itemId).any { it.isBlank() }) {
            fail(GetCredentialUnknownException("Missing password parameters"))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val it = runCatching { itemRepository.observeItem(vaultId, itemId).first() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (it == null || it.type != VaultItemType.Login) {
                    fail(GetCredentialUnknownException("Login item not found"))
                    return@withContext
                }
                // ⚠️ 「用户名与密码都为空」**不等于**「库锁定」。条目本来就允许空密码
                // （只有用户名、或密码由别处生成）。旧写法把两者划等号 ⇒ 一条合法条目
                // 被报成 "Vault locked"，用户完全无从理解（且不知道要去解锁什么）。
                // 真正的锁态**问仓储**（含 KDBX 会话），与 CP 列候选保持同一判据。
                val locked = runCatching { !vaultRepository.isVaultUnlocked(vaultId) }.getOrDefault(false)
                if (locked) {
                    fail(GetCredentialUnknownException("Vault locked"))
                    return@withContext
                }
                item = it
                showConfirm()
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun showConfirm() {
        setContent {
            VaultixTheme {
                PasswordFillSheet(
                    title = item?.title.orEmpty(),
                    username = item?.username.orEmpty(),
                    onConfirm = { returnPassword() },
                    onCancel = { cancel() },
                )
            }
        }
    }

    private fun returnPassword() {
        val it = item ?: run {
            fail(GetCredentialUnknownException("Password not available"))
            return
        }
        runCatching {
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialResponse(
                resultIntent,
                GetCredentialResponse(PasswordCredential(it.username, it.password)),
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }.onFailure { fail(GetCredentialUnknownException(it.message)) }
    }

    private fun fail(e: GetCredentialException) {
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(resultIntent, e)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun cancel() {
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(
            resultIntent,
            GetCredentialCancellationException("Cancelled"),
        )
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}

@Composable
private fun PasswordFillSheet(
    title: String,
    username: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.password_fill_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.password_fill_message, title.ifBlank { username }),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (username.isNotBlank()) {
                    Text(text = username, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.password_fill_confirm))
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
