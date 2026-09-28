/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.util.SystemSettingsIntents

/**
 * 「关于」二级页（设置首页「关于 → 关于 Vaultix」进入）。
 *
 * ## 为什么单独成页
 *
 * 2026-09-28 用户要求精简设置页「关于」组，并指出「源码与反馈 / 开源许可**很多项目
 * 都是合并成一个**」。主流的开源 App 做法就是：设置首页只留一个 About 入口，
 * 把仓库 / 反馈 / 许可证 / 版本历史都收进这一页 —— 设置首页因此回到"一眼扫完"的长度。
 *
 * ## 与其他页面的关系
 *
 * - **版本与更新**不在本页：它留在设置首页的「版本」行上（点击即检查更新），
 *   因为那是**高频动作**——用户找它时不会想到要先进"关于"。
 *   但「更新日志」在本页给了一个入口（查看历史发布说明，低频、需要跳 GitHub）。
 * - 结构照抄 `PermissionsScreen` / `AutofillSettingsScreen`：
 *   `Scaffold + TopAppBar + 分组卡片`，不另起一套规格（约定 8.4）。
 *
 * ## 为什么链接都外跳浏览器
 *
 * 不在 App 内做 WebView：① 密码管理器内嵌 WebView 是攻击面（加载的是远端页面）；
 * ② GitHub 的 Issue / Release 页在浏览器里有完整功能（登录、附件、Markdown 渲染）。
 * 唯一例外是**开源许可**：它在 App 内用一个对话框就地展示 [R.string.about_license_body]
 * （见下）。
 *
 * ## 许可为什么是对话框，不是外链
 *
 * 2026-09-28 修正：设置首页那个旧的许可对话框随着本轮"合并成 About 一行"变成了
 * **不可达代码**（它的触发参数 `onShowLicense` 没人再用了）—— 等于把
 * 「GPL-3.0 + Bastion 溯源声明 + 使用风险」这段话**静默删掉了**。
 * 对一个 GPL 项目来说，溯源声明不是装饰，是许可证要求的义务；"精简"可以合并入口，
 * 但不能让义务声明消失。故把它**移进本页**：内容照旧、入口随合并走。
 *
 * 注意这里刻意**不外跳**：许可证正文是用户点开就想**读到**的东西，跳走会让
 * "读许可"变成"访问一个网站"。真要读全文，`about_license_body` 里已指明许可类型，
 * 用户可自行去仓库看 LICENSE。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repositoryUrl = stringResource(R.string.about_github_url)
    val issuesUrl = stringResource(R.string.about_issues_url)
    val releasesUrl = UPDATE_CHECKER_RELEASES_URL
    // 开源许可对话框（2026-09-28 从设置首页迁入，见上）。
    var showLicense by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_entry_title)) },
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
                .padding(bottom = Spacing.xxl),
        ) {
            SettingsGroupTitle(stringResource(R.string.about_group_links))
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.Code, contentDescription = null) },
                    title = stringResource(R.string.about_repository_title),
                    subtitle = stringResource(R.string.about_repository_desc),
                    onClick = { SystemSettingsIntents.openUrl(context, repositoryUrl) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                    title = stringResource(R.string.about_issues_title),
                    subtitle = stringResource(R.string.about_issues_desc),
                    onClick = { SystemSettingsIntents.openUrl(context, issuesUrl) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    title = stringResource(R.string.about_releases_title),
                    subtitle = stringResource(R.string.about_releases_desc),
                    onClick = { SystemSettingsIntents.openUrl(context, releasesUrl) },
                )
            }

            SettingsGroupTitle(stringResource(R.string.about_group_license))
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    title = stringResource(R.string.about_license_title),
                    subtitle = stringResource(R.string.about_license_desc),
                    // ★ 2026-09-28：由"外跳仓库"改为"就地弹许可正文"（理由见文件头）。
                    onClick = { showLicense = true },
                )
            }

            // 页脚：一句话说明这 App 是什么 + 安全承诺，收尾（对齐 PermissionsScreen 的页脚写法）。
            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.about_license)) },
            text = { Text(stringResource(R.string.about_license_body)) },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
}

/**
 * Release 列表页地址。
 *
 * ⚠️ 刻意**复用** [io.vaultix.vaultix.util.UpdateChecker.RELEASES_PAGE_URL] 的值而非 import：
 * 那个对象是 `util` 层的实现细节，UI 层引用它会让"UI → util 具体实现"多一条耦合边；
 * 而这里要的只是一个 URL 常量。两处若有一天要改，改 `UpdateChecker` 时一并搜这里即可
 * （`grep RELEASES_PAGE_URL` 有注释互相指路）。
 *
 * ⚠️ 命名必须是 `SCREAMING_SNAKE_CASE`：detekt `TopLevelPropertyNaming` 对顶层常量
 * 强制该风格（2026-09-28 首版写成 `UpdateCheckerReleasesUrl` 被门禁拦下）。
 */
private const val UPDATE_CHECKER_RELEASES_URL = "https://github.com/Chaniug/Vaultix/releases"
