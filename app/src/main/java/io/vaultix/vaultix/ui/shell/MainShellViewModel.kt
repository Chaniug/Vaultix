/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 主界面壳层 ViewModel：**只做依赖桥接**——把进程级单例 [ActiveVaultStore]
 * 暴露给 Compose 层（Compose 无法直接注入非 ViewModel 的 Hilt 绑定），
 * 业务状态一律由各 Tab 自己的 ViewModel 持有。
 */
package io.vaultix.vaultix.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainShellViewModel @Inject constructor(
    val activeVaultStore: ActiveVaultStore,
    vaultRepository: VaultRepository,
) : ViewModel() {

    /**
     * 库总数 —— 决定「切换密码库」入口是否出现（>1 才显示）。
     *
     * issue #96 的教训：入口挂在不可达路由后面等于没做；反过来，给单库用户
     * 显示一个「切换密码库」也是噪音。数量是这里唯一需要的信号，不暴露库内容。
     */
    val vaultCount: StateFlow<Int> = vaultRepository.observeVaults()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )
}
