package io.vaultix.vaultix

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import io.vaultix.vaultix.security.AutoLockController
import javax.inject.Inject

@HiltAndroidApp
class VaultixApplication : Application() {

    @Inject
    lateinit var autoLockController: AutoLockController

    override fun onCreate() {
        super.onCreate()
        // 自动锁定：进程前台/后台事件（Docs/10 §4）
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockController)
    }
}
