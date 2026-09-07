package io.vaultix.vaultix.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.vaultix.security.AutoLockController
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/**
 * 导航壳 ViewModel：把 [AutoLockController.lockEvents]（锁定代次）桥接成可收集状态，
 * 供 VaultixApp 在自动锁定时强制回到库列表根路由。
 */
@HiltViewModel
class VaultShellViewModel @Inject constructor(
    autoLockController: AutoLockController,
) : ViewModel() {

    val lockEpoch: StateFlow<Int> = autoLockController.lockEvents
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0,
        )
}
