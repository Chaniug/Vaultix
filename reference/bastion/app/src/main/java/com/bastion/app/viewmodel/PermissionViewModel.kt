package com.bastion.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.bastion.app.data.model.PermissionCategory
import com.bastion.app.data.model.PermissionInfo
import com.bastion.app.data.model.PermissionStats
import com.bastion.app.repository.PermissionRepository

/**
 * 权限管理ViewModel
 * Permission management ViewModel
 */
class PermissionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PermissionRepository(application)

    private val _permissionsByCategory = MutableStateFlow<Map<PermissionCategory, List<PermissionInfo>>>(emptyMap())
    val permissionsByCategory: StateFlow<Map<PermissionCategory, List<PermissionInfo>>> =
        _permissionsByCategory.asStateFlow()

    private val _permissionStats = MutableStateFlow<PermissionStats?>(null)
    val permissionStats: StateFlow<PermissionStats?> = _permissionStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadPermissions()
    }

    /**
     * 加载权限信息
     * Load permission information
     */
    fun loadPermissions() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _permissionsByCategory.value = repository.getPermissionsByCategory()
                _permissionStats.value = repository.getPermissionStats()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 刷新权限状态
     * Refresh permission status
     */
    fun refreshPermissions() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 强制刷新，不使用缓存
                _permissionsByCategory.value = repository.apply {
                    getAllPermissions(forceRefresh = true)
                }.getPermissionsByCategory()
                _permissionStats.value = repository.getPermissionStats()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
