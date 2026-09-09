/*
 * Vaultix — app:autofill · save
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 保存宿主：系统框架在用户提交登录表单后经 `onSaveRequest` 回调 → 本界面确认 → 落库。
 * 与 [io.vaultix.vaultix.autofill.AutofillActivity] 同样走「透明遮罩 + 居中卡片」，
 * 保持自动填充相关界面的观感一致。
 */
package io.vaultix.vaultix.autofill.save

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.vaultix.MainActivity
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.VaultixTheme

/** 保存确认界面（新建 / 更新二合一）。 */
@AndroidEntryPoint
class AutofillSaveActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VaultixTheme {
                SaveRoute(
                    onOpenVault = ::openVault,
                    onDismiss = ::finish,
                )
            }
        }
    }

    private fun openVault() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}

@Composable
private fun SaveRoute(onOpenVault: () -> Unit, onDismiss: () -> Unit) {
    val vm: AutofillSaveViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    SaveScreen(
        state = state,
        onTitleChange = vm::onTitleChange,
        onSave = vm::saveNew,
        onUpdate = vm::updateExisting,
        onOpenVault = onOpenVault,
        onDismiss = onDismiss,
    )
    SaveEffects(state = state, onDismiss = onDismiss)
}

@Composable
private fun SaveEffects(state: AutofillSaveViewModel.UiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val message = stringResource(
        if (state.existing != null) R.string.autofill_save_updated else R.string.autofill_save_done,
    )
    LaunchedEffect(state.done) {
        if (!state.done) return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onDismiss()
    }
}

@Composable
private fun SaveScreen(
    state: AutofillSaveViewModel.UiState,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onUpdate: () -> Unit,
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
                .clickable(onClick = { }),
        ) {
            when {
                state.loading || state.saving -> SavingBody()
                state.locked -> LockedBody(onOpenVault = onOpenVault, onDismiss = onDismiss)
                else -> SaveBody(
                    state = state,
                    onTitleChange = onTitleChange,
                    onSave = onSave,
                    onUpdate = onUpdate,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun SavingBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LockedBody(onOpenVault: () -> Unit, onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text(
            text = stringResource(R.string.autofill_save_locked),
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = onOpenVault, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(text = stringResource(R.string.autofill_unlock_action))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun SaveBody(
    state: AutofillSaveViewModel.UiState,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var revealPassword by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (state.existing != null) R.string.autofill_save_title_update else R.string.autofill_save_title,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        state.uri?.let { uri ->
            Text(
                text = stringResource(R.string.autofill_save_target, uri),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.vaultName.isNotBlank()) {
            Text(
                text = stringResource(R.string.autofill_save_vault, state.vaultName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.item_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.username,
            onValueChange = { },
            readOnly = true,
            label = { Text(stringResource(R.string.item_field_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = { },
            readOnly = true,
            label = { Text(stringResource(R.string.item_field_password)) },
            singleLine = true,
            visualTransformation = if (revealPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { revealPassword = !revealPassword }) {
                    Icon(
                        imageVector = if (revealPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.action_cancel))
            }
            val action = if (state.existing != null) onUpdate else onSave
            Button(onClick = action, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.action_save))
            }
        }
    }
}
