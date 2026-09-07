package com.bastion.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bastion.app.ui.components.PasswordVerificationContent
import com.bastion.app.viewmodel.PasswordViewModel
import com.bastion.app.viewmodel.SettingsViewModel

@Composable
fun LoginScreen(
    viewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel? = null,
    onFirstFrameRendered: (() -> Unit)? = null,
    onForgotPassword: (() -> Unit)? = null
) {
    val context = LocalContext.current
    
    // 首次启动判定：结果在进程生命周期内不变，remember 避免每次重组都触发
    // isMasterPasswordSet()（EncryptedSharedPreferences 读盘+解密，主线程 IO）。
    val isFirstTime = remember(viewModel) { !viewModel.isMasterPasswordSet() }
    
    // 获取设置
    val settings = settingsViewModel?.settings?.collectAsState()?.value
    val biometricEnabled = settings?.biometricEnabled ?: false
    val autoLockMinutes = settings?.autoLockMinutes ?: 5

    LaunchedEffect(Unit) {
        withFrameNanos { }
        onFirstFrameRendered?.invoke()
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        PasswordVerificationContent(
            modifier = Modifier.fillMaxSize(),
            isFirstTime = isFirstTime,
            biometricEnabled = biometricEnabled,
            autoLockMinutes = autoLockMinutes,
            onVerifyPassword = { password -> 
                viewModel.authenticate(password)
            },
            onSetPassword = { password ->
                viewModel.setMasterPassword(password)
            },
            onSuccess = {
                viewModel.restoreAuthenticatedUiState()
            },
            onForgotPassword = onForgotPassword
        )
    }
}
