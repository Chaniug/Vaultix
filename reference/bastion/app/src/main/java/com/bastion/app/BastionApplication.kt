package com.bastion.app

import com.bastion.app.logging.runCatchingObserved
import android.app.Application
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.bastion.app.attachments.AttachmentContainer
import com.bastion.app.data.AppLauncherIcon
import com.bastion.app.data.AppLauncherLabel
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.perf.MainThreadStallMonitor
import com.bastion.app.security.AppUpdateSecurityGuard
import com.bastion.app.sync.AndroidSyncNetworkGate
import com.bastion.app.sync.SyncTaskRunner
import com.bastion.app.utils.AppLauncherIconManager
import com.bastion.app.security.SessionManager
import com.bastion.app.utils.SettingsManager
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bastion.app.security.SecurityManager
import com.bastion.app.webdav.WebDavBackoffState
import com.bastion.app.workers.KeePassRemoteUploadWorker

/**
 * Bastion 应用程序入口
 *
 * 负责全局初始化：应用更新安全守卫、SecurityManager 单例预热、
 * 同步网络门、主线程卡顿监控、诊断日志、附件清理、启动器入口同步等
 * 主进程专属开销（独立进程如 :accessibility 不在此列）。
 */
class BastionApplication : Application() {
    
    companion object {
        private const val TAG = "BastionApplication"
    }
    
    override fun onCreate() {
        super.onCreate()

        // —— 所有进程（含独立进程 :accessibility）都需要的轻量初始化 ——
        SessionManager.attachAppContext(this)

        // 「重启后锁定」(-2)：内部已按进程守卫，子进程为 no-op
        SessionManager.enforceLockOnRestartIfNeeded(this)

        // 版本变更强制锁定：安全守卫，无版本变更时仅是一次轻量 SharedPreferences 读写
        AppUpdateSecurityGuard.enforceLockIfAppUpdated(
            context = this,
            reason = "application_on_create"
        )

        // —— 以下为主进程专属的「重度」初始化 ——
        // :accessibility 等独立进程常驻后台，不应承担这些与主业务相关的开销
        // （主线程卡顿监控、诊断日志、附件清理、启动器入口同步、WebDAV 退避持久化、
        // KeePass 上传恢复、同步网络门），以降低常驻进程内存与后台 CPU 占用。
        // 仅在「确定」是非主进程时才跳过；进程名判不明时回退到完整初始化，
        // 保证主进程逻辑绝不漏跑。
        if (SessionManager.isNonMainProcess(this)) {
            return
        }

        // 后台线程预热加密单例（SecurityManager），将 Keystore / EncryptedSharedPreferences
        // 的初始化从主线程移出（A1），避免冷启动与旋转屏幕时的主线程阻塞。
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            SecurityManager.prewarm(this@BastionApplication)
        }

        SyncTaskRunner.installNetworkGate(AndroidSyncNetworkGate(this))
        // 主线程卡顿监控：release 包默认不启动，debug 包前台运行时生效、后台自动暂停，降低待机功耗
        MainThreadStallMonitor.start(this)
        syncLauncherEntryPointsWithSettings()
        WebDavBackoffState.attachPersistence(this)
        scheduleKeePassRemoteUploadRecovery()
        scheduleAttachmentHousekeeping()
    }
    
    private fun scheduleKeePassRemoteUploadRecovery() {
        runCatchingObserved {
            KeePassRemoteUploadWorker.enqueueIfPending(this)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule KeePass remote upload recovery", error)
        }
    }

    /**
     * 附件子系统的启动级维护：
     * - 扫描并删除 Room 已不再引用的密文孤儿文件
     *
     * 在独立协程里跑，失败不影响应用启动。
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun scheduleAttachmentHousekeeping() {
        ProcessLifecycleOwner.get().lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatchingObserved {
                val facade = AttachmentContainer.facade(this@BastionApplication)
                facade.purgeOrphanedLocalBlobs()
            }.onFailure { Log.w(TAG, "Attachment housekeeping failed", it) }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun syncLauncherEntryPointsWithSettings() {
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            runCatchingObserved {
                val settings = SettingsManager(this@BastionApplication).settingsFlow.first()
                AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                    this@BastionApplication,
                    settings.appLauncherIcon,
                    settings.appLauncherLabel
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to sync launcher entry points with settings", error)
                runCatchingObserved {
                    AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                        this@BastionApplication,
                        AppLauncherIcon.MODERN,
                        AppLauncherLabel.MONICA_PASS
                    )
                }.onFailure { fallbackError ->
                    Log.w(TAG, "Failed to apply fallback launcher entry points", fallbackError)
                }
            }
        }
    }

}

