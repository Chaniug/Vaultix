/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountInventory
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「网盘账号」页的状态。
 *
 * ⚠️ 刻意只做**读**（列出账号）；「注销 / 换号」随后补 —— 那类动作必须能先算出
 * **影响面**（会牵连哪些库），而那正是 `CloudAccount.vaultIds` 的用途。
 */
@HiltViewModel
class CloudAccountsViewModel @Inject constructor(
    private val inventory: CloudAccountInventory,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val accounts: List<CloudAccount> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * 重新读一遍账号清单。
     *
     * ⚠️ 每次进页面都重读（而不是只读一次就缓存）：账号的**连接状态会变**
     * （token 过期、凭据被清、库被移除），缓存下来的"已连接"会变成一句假话。
     */
    fun refresh() {
        viewModelScope.launch {
            val accounts = inventory.list()
            _state.update { it.copy(loading = false, accounts = accounts) }
        }
    }
}

