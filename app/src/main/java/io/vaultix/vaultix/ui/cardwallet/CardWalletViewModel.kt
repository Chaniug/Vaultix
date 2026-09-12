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
 * 卡包 Tab 的**容器与列表**（Docs/progress/main-shell-migration.md §6.1.3）。
 * 参考 Bastion `ui/cardwallet/CardWalletContent.kt`（容器结构，53 行）后**按 Vaultix
 * 条目模型重写**：Bastion 的 pane 以「私有条目类型（BankCard/Document/BillingAddress）」
 * 为参数，Vaultix 无这些类型，故不搬 pane，只搬纯视觉组件（[CardBrandIcon]）。
 * 内容源 = 当前活跃库内 `Cipher type=3`（[io.vaultix.model.VaultItemType.Card]）条目。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.cardwallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.ItemRepository
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 卡包 Tab。
 *
 * 库 id 取自 [ActiveVaultStore]（**不是**路由参数）——主界面是 Tab 容器，
 * 不携带 vaultId（Docs/progress/main-shell-migration.md A4）。
 */
@HiltViewModel
class CardWalletViewModel @Inject constructor(
    private val activeVaultStore: ActiveVaultStore,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    /** 卡包内容 = 活跃库内的全部 `type=Card` 条目。 */
    val cards: StateFlow<List<VaultItem>> = activeVaultStore.activeVaultId
        .flatMapLatest { vaultId ->
            if (vaultId.isNullOrBlank()) emptyFlow() else itemRepository.observeItems(vaultId)
        }
        .map { items -> items.filter { it.type == VaultItemType.Card } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** 新建卡片（「+」按钮触发的表单回传）。id 由 data 层分配。 */
    fun createCard(item: VaultItem) {
        val vaultId = activeVaultStore.current() ?: return
        if (item.title.isBlank()) return
        viewModelScope.launch {
            _saving.value = true
            itemRepository.createItem(vaultId = vaultId, item = item.copy(id = ""))
            _saving.value = false
        }
    }

    /**
     * 「按住后滑动删除」（与密码列表同一手势语义）：走**软删除**进回收站，可恢复。
     * 卡包 Tab 因此不必切到详情页也能删卡，同时保留误删的安全网。
     */
    fun deleteCard(item: VaultItem) {
        val vaultId = activeVaultStore.current() ?: return
        viewModelScope.launch { itemRepository.softDeleteItem(vaultId = vaultId, itemId = item.id) }
    }
}
