package io.vaultix.vaultix.ui.vaultlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 库列表（S3 最小版）：Room vaults + 会话锁定状态合并成 [VaultSummary] 流。
 */
@HiltViewModel
class VaultListViewModel @Inject constructor(
    vaultRepository: VaultRepository,
) : ViewModel() {

    /** 库列表；WhileSubscribed(5s)：切后台停止收集后保留最近值。 */
    val vaults: StateFlow<List<VaultSummary>> = vaultRepository.observeVaults()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
