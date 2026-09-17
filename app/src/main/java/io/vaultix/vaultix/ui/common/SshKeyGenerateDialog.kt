/*
 * Vaultix — app
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * SSH 条目「生成密钥对」的 UI（施工单 §3，定稿 §11.12）。
 *
 * ## ★ 为什么"备份提示"被做成一道必须点掉的闸门
 *
 * **私钥不可再生**：它不是一个"改了还能改回来"的字段，丢了就是丢了
 * （与"passkey 私钥是唯一不可再生的东西"同一类）。定稿 §11.12 把这条列为硬要求，
 * 所以本文件把提示放在**结果页**里，并把确认按钮写成「我已备份，填入条目」——
 * 用户必须**主动表态**才能把私钥写进条目，而不是"生成完静默填进去、然后忘了备份"。
 *
 * 生成后**立刻算出指纹并显示**（[SshFingerprint.of]），用户能当场与
 * `ssh-keygen -lf` 的输出核对 —— 这是"钥匙真的能用"唯一当场可见的证据。
 *
 * ## 为什么生成要离开主线程
 * Ed25519 是毫秒级，但 **RSA-3072 的密钥生成是百毫秒级**（慢设备上更久）。
 * 直接在 Compose 主线程跑会明显掉帧，所以走 `Dispatchers.Default`。
 *
 * 纯算法在 `core:common` 的 [SshKeyGenerator]（有单测）；本文件只负责交互。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.vaultix.common.SshFingerprint
import io.vaultix.common.SshKeyGenerator
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 生成 SSH 密钥对（三步：选算法 → 生成中 → 结果 + 备份闸门）。
 *
 * @param onGenerated 用户在结果页**明确表态**后才回调；回调即写入表单。
 */
@Composable
internal fun SshKeyGenerateDialog(
    onGenerated: (SshKeyGenerator.GeneratedKeyPair) -> Unit,
    onDismiss: () -> Unit,
) {
    var algorithm by remember { mutableStateOf(SshKeyGenerator.Algorithm.ED25519) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SshKeyGenerator.GeneratedKeyPair?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val done = result

    AlertDialog(
        // ⚠️ 生成中**不允许**外部点空白关掉：协程还挂着，关掉会让结果无处安放
        // （私钥就那么丢了，且不可再生）。
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.ssh_generate_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                when {
                    working -> GeneratingPane()
                    done != null -> ResultPane(pair = done)
                    else -> ChoosePane(
                        algorithm = algorithm,
                        onAlgorithmChange = { algorithm = it },
                        failure = failure,
                    )
                }
            }
        },
        confirmButton = {
            when {
                working -> TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.ssh_generate_action))
                }
                done != null -> TextButton(onClick = { onGenerated(done) }) {
                    Text(stringResource(R.string.ssh_generate_ack))
                }
                else -> TextButton(onClick = {
                    failure = null
                    working = true
                    scope.launch {
                        // 见文件头注释：RSA-3072 不能在主线程生成。
                        val outcome = runCatching {
                            withContext(Dispatchers.Default) { SshKeyGenerator.generate(algorithm) }
                        }
                        working = false
                        outcome
                            .onSuccess { result = it }
                            // ★ 失败必须说出来（§0-10）：吞掉会让用户反复点一个永远不成功的按钮
                            .onFailure { failure = it.message ?: it.javaClass.simpleName }
                    }
                }) {
                    Text(stringResource(R.string.ssh_generate_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 第一步：选算法（Ed25519 默认）。 */
@Composable
private fun ChoosePane(
    algorithm: SshKeyGenerator.Algorithm,
    onAlgorithmChange: (SshKeyGenerator.Algorithm) -> Unit,
    failure: String?,
) {
    AlgorithmChoice(
        selected = algorithm,
        onSelect = onAlgorithmChange,
        algorithm = SshKeyGenerator.Algorithm.ED25519,
        label = stringResource(R.string.ssh_generate_ed25519),
        hint = stringResource(R.string.ssh_generate_ed25519_hint),
    )
    AlgorithmChoice(
        selected = algorithm,
        onSelect = onAlgorithmChange,
        algorithm = SshKeyGenerator.Algorithm.RSA_3072,
        label = stringResource(R.string.ssh_generate_rsa),
        hint = stringResource(R.string.ssh_generate_rsa_hint),
    )
    if (failure != null) {
        Text(
            text = stringResource(R.string.ssh_generate_failed, failure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 一枚算法筹码 + 它下面的一句说明（"为什么选它"必须当场可见，否则等于让人盲选）。 */
@Composable
private fun AlgorithmChoice(
    selected: SshKeyGenerator.Algorithm,
    onSelect: (SshKeyGenerator.Algorithm) -> Unit,
    algorithm: SshKeyGenerator.Algorithm,
    label: String,
    hint: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = selected == algorithm,
            onClick = { onSelect(algorithm) },
            label = { Text(label) },
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 第二步：进行中。RSA-3072 会在这里停留一小会儿。 */
@Composable
private fun GeneratingPane() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(text = stringResource(R.string.ssh_generating), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 第三步：结果 —— **指纹 + 备份闸门**。
 *
 * 指纹用 [SshFingerprint.of] 现算（`SHA256:<base64>`，与 `ssh-keygen -lf` 逐位一致），
 * 用户可当场核对；警示文案用 error 色，配一枚警告图标，避免被当成普通说明滑过去。
 */
@Composable
private fun ResultPane(pair: SshKeyGenerator.GeneratedKeyPair) {
    val fingerprint = remember(pair) { SshFingerprint.of(pair.publicKey).orEmpty() }
    Text(text = stringResource(R.string.ssh_generate_done), style = MaterialTheme.typography.titleSmall)
    Text(
        text = stringResource(R.string.ssh_key_format_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = fingerprint,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(Spacing.lg),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.ssh_generate_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 覆盖确认：条目里已经有密钥时先问一句。
 *
 * 单独一步而不是"直接覆盖"——私钥**不可再生**，覆盖旧值等于销毁它，
 * 而用户可能是想"再加一对"、或误点了生成。
 */
@Composable
internal fun SshOverwriteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_overwrite_title)) },
        text = { Text(stringResource(R.string.ssh_overwrite_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ssh_overwrite_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 表单里的**留存提示**：生成完、对话框关掉之后，这一行仍在，直到用户点掉它
 * （或保存条目）。
 *
 * 为什么关掉对话框后还要留：真正需要"立刻备份"的时刻是**用户离开编辑页之后**，
 * 那时对话框早没了 —— 留一行把这件事挂住。
 */
@Composable
internal fun SshBackupNotice(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(Spacing.lg),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.ssh_backup_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

/** 「生成密钥对」入口按钮（供编辑表单用；图标沿用密码生成器的骰子，语义一致）。 */
@Composable
internal fun SshGenerateButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(stringResource(R.string.ssh_generate))
    }
}
