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
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.vaultix.session.ActiveVaultStore
import javax.inject.Inject

@HiltViewModel
class MainShellViewModel @Inject constructor(
    val activeVaultStore: ActiveVaultStore,
) : ViewModel()
