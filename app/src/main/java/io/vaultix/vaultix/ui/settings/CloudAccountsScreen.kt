/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「网盘账号」二级页** —— 把 OneDrive / WebDAV 从「添加密码库」里独立出来的落点
 * （`.ai/decisions/设置页信息架构-定稿.md` §11.7 第 1 步）。
 *
 * ## 为什么必须独立成页（这是病因，不是排版偏好）
 *
 * 用户实测：「**返回到添加密码库界面，登录状态就没了**」—— 而且「根本不用划掉后台」。
 *
 * | | 生命周期 |
 * |---|---|
 * | OneDrive 登录态 / WebDAV 凭据 | **长**（跨进程跨页面，活在 MSAL 缓存 / `SecureCredentialStore`） |
 * | 「添加密码库」那一页 | **短** —— 导航路由，**一返回 ViewModel 即销毁** |
 *
 * ⇒ 把长寿命的状态存在短寿命页面里，**必然"返回就没了"**。
 * 本页就是给它一个**与状态寿命匹配的宿主**：设置里的持久页面。
 *
 * ## 数据从哪来
 *
 * [CloudAccountInventory] —— **从库的 origin 反推**（不另存一份账号清单）。
 * 理由见该类的文件头：一个账号的"现实意义"就是它被哪些库在用。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.vaultix.R
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountInventory
import io.vaultix.vaultix.remote.CloudAccountKind
import io.vaultix.vaultix.ui.theme.Spacing
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountsScreen(
    onBack: () -> Unit,
    viewModel: CloudAccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.cloud_accounts_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Spacer(Modifier.height(Spacing.lg))

            // ⚠️ 「空」有两态必须分开（本项目的老坑）：还在读 vs 真的一个都没有。
            //    塌进一个 isEmpty() 就是**假空态** —— 用户会以为"我配的账号丢了"。
            if (state.accounts.isEmpty()) {
                Text(
                    text = stringResource(
                        if (state.loading) {
                            R.string.cloud_accounts_loading
                        } else {
                            R.string.cloud_accounts_empty
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.accounts.forEach { account ->
                    CloudAccountCard(account)
                    Spacer(Modifier.height(Spacing.md))
                }
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/** 一个账号一张卡：名字 + 类型 + **被几个库在用** + 连接状态。 */
@Composable
private fun CloudAccountCard(account: CloudAccount) {
    SettingsGroupCard {
        SettingsRow(
            icon = {
                Icon(
                    imageVector = if (account.kind == CloudAccountKind.ONEDRIVE) {
                        Icons.Filled.Cloud
                    } else {
                        Icons.Filled.Storage
                    },
                    contentDescription = null,
                )
            },
            title = account.label,
            subtitle = stringResource(account.kind.subtitleRes()) + " · " +
                stringResource(R.string.cloud_accounts_used_by, account.vaultCount),
        )
    }
    // 连接状态**单独一行**：它**会变**（token 过期 / 凭据被清 / 库被移除），
    // 混进副标题会让人以为它是账号的固有属性。
    Text(
        text = stringResource(
            if (account.connected) {
                R.string.cloud_accounts_connected
            } else {
                R.string.cloud_accounts_disconnected
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (account.connected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.padding(start = Spacing.xl, top = Spacing.xs, bottom = Spacing.sm),
    )
}

private fun CloudAccountKind.subtitleRes(): Int = when (this) {
    CloudAccountKind.ONEDRIVE -> R.string.cloud_accounts_kind_onedrive
    CloudAccountKind.WEBDAV -> R.string.cloud_accounts_kind_webdav
}
