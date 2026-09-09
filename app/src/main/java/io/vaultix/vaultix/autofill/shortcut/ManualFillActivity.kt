/*
 * Vaultix — app:autofill · shortcut
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 「快速填充」界面：由 Quick Settings 磁贴拉起，选条目 → 复制密码（通知栏可接力复制
 * 用户名）→ 自动关闭回到刚才的 App 粘贴。**不依赖输入法内联建议，也不依赖无障碍
 * 服务**，是国产 ROM / 国产输入法环境下最可靠的填充路径。
 */
package io.vaultix.vaultix.autofill.shortcut

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.vaultix.MainActivity
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.VaultixTheme

/** 快速填充：搜索已解锁的登录条目并复制（磁贴 / 手动入口共用）。 */
@AndroidEntryPoint
class ManualFillActivity : FragmentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            VaultixTheme {
                ManualFillRoute(onClose = { finish() })
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openVault() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    @Composable
    private fun ManualFillRoute(onClose: () -> Unit) {
        val vm: ManualFillViewModel = viewModel()
        val rows by vm.rows.collectAsStateWithLifecycle()
        val locked by vm.locked.collectAsStateWithLifecycle()
        var query by rememberSaveable { mutableStateOf("") }
        ManualFillScreen(
            locked = locked,
            rows = rows,
            query = query,
            onQueryChange = {
                query = it
                vm.onQueryChange(it)
            },
            onPick = { row ->
                vm.onPick(row)
                onClose()
            },
            onOpenVault = ::openVault,
            onClose = onClose,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualFillScreen(
    locked: Boolean,
    rows: List<ManualFillViewModel.Row>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (ManualFillViewModel.Row) -> Unit,
    onOpenVault: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manual_fill_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (locked) {
                LockedHint(onOpenVault = onOpenVault)
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.manual_fill_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (rows.isEmpty()) {
                EmptyHint()
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(rows, key = { "${it.vaultId}:${it.itemId}" }) { row ->
                        CredentialRow(row = row, onPick = onPick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialRow(row: ManualFillViewModel.Row, onPick: (ManualFillViewModel.Row) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(row) }
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.username.isNotBlank()) {
            Text(
                text = row.username,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LockedHint(onOpenVault: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.manual_fill_locked),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onOpenVault, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.manual_fill_open_vault))
        }
    }
}

@Composable
private fun EmptyHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.manual_fill_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
