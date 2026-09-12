/*
 * Vaultix — app:ui:cardwallet
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 卡包 Tab 界面。视觉组件（品牌图标）来自 Bastion（见 [CardBrandIcon]），
 * 容器与列表按 Vaultix 条目模型重写（Bastion 的 pane 依赖其私有条目类型，不搬）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.cardwallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.common.CardBrandDetector
import io.vaultix.common.formatCardNumberGrouped
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.ItemFormDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add

/**
 * 卡包 Tab（Docs/08 S20 最小版 / main-shell-migration §6.1.3）。
 *
 * @param embedded 主界面 Tab 内嵌模式：隐藏返回键与 FAB（「+」由底部导航条统一承载）。
 * @param addRequest 「+」请求计数（非零即触发新建表单，避免重复弹窗）。
 * @param onAddConsumed 表单已弹出，通知宿主清零请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardWalletScreen(
    embedded: Boolean = false,
    addRequest: Int = 0,
    onAddConsumed: () -> Unit = {},
    viewModel: CardWalletViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var creating by remember { mutableStateOf<VaultItem?>(null) }

    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            creating = VaultItem(id = "", title = "", type = VaultItemType.Card)
            onAddConsumed()
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.nav_card_wallet)) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (!embedded) {
                FloatingActionButton(onClick = {
                    creating = VaultItem(id = "", title = "", type = VaultItemType.Card)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.nav_add))
                }
            }
        },
    ) { padding ->
        if (cards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.card_wallet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cards, key = { it.id }) { item ->
                    CardWalletRow(item = item)
                }
            }
        }
    }

    val draft = creating
    if (draft != null) {
        ItemFormDialog(
            title = stringResource(R.string.card_wallet_new),
            initial = draft,
            saving = saving,
            typeEditable = false,
            onDismiss = { creating = null },
            onSave = { item ->
                viewModel.createCard(item)
                creating = null
            },
        )
    }
}

/** 单张卡：品牌图标 + 名称 + 分组卡号（末四位外打码）。 */
@Composable
private fun CardWalletRow(item: VaultItem) {
    val card = item.card
    val brand = remember(card?.number, card?.brand) {
        CardBrandDetector.detect(card?.number.orEmpty(), card?.brand.orEmpty())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(width = 44.dp, height = 30.dp)) {
            CardBrandIcon(brand = brand, modifier = Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val number = card?.number.orEmpty()
            if (number.isNotBlank()) {
                Text(
                    text = maskCardNumber(formatCardNumberGrouped(number)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                card?.cardholderName?.takeIf { it.isNotBlank() }?.let { holder ->
                    Text(
                        text = holder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val expiry = listOf(card?.expMonth, card?.expYear)
            .map { it.orEmpty() }
            .filter { it.isNotBlank() }
        if (expiry.size == 2) {
            Text(
                text = expiry.joinToString("/"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 卡号打码：保留末 4 位，其余替换为「•」（对齐 Bastion 卡包列表观感）。
 *
 * ⚠️ 分组格式化后的分隔符保留，避免把「1234 5678」压成一串难以辨认的圆点。
 */
private fun maskCardNumber(grouped: String): String {
    if (grouped.isBlank()) return ""
    val totalDigits = grouped.count { it.isDigit() }
    var index = 0
    return grouped.map { char ->
        if (!char.isDigit()) {
            char
        } else {
            val keep = index >= totalDigits - 4
            index++
            if (keep) char else '•'
        }
    }.joinToString("")
}
