package io.vaultix.vaultix

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import io.vaultix.vaultix.security.AutoLockController
import io.vaultix.vaultix.security.VaultLockManager
import javax.inject.Inject

/**
 * 应用入口。
 *
 * 除原有的自动锁定接线外，2026-09-11 新增**进程创建**通知
 * （[VaultLockManager.onAppCreated]）——对齐 Bitwarden
 * `VaultLockManagerImpl.handleOnCreated(createdForAutofill, isFirstCreated)`。
 *
 * 为什么需要它：「重启 App 时锁定」（[io.vaultix.datastore.VaultTimeout.OnAppRestart]）
 * 这一档位的唯一触发点就是进程创建；同时「为 autofill / 凭据提供商拉起进程」必须能从
 * 该档位的锁定中**豁免**，否则用户点一次通行密钥就会把库锁掉。
 */
@HiltAndroidApp
class VaultixApplication : Application() {

    @Inject
    lateinit var autoLockController: AutoLockController

    @Inject
    lateinit var lockManager: VaultLockManager

    /**
     * 本进程是否为「为自动填充 / 凭据提供商而拉起」。
     *
     * 由 [MainActivity] / `AutofillActivity` / `CredentialProviderActivity` 在被系统
     * 以 autofill 语义启动时置位（见各 Activity 的 onCreate）。默认 false =
     * 用户正常点图标启动。
     *
     * ⚠️ 必须是进程级静态标记：进程创建时（[onCreate]）这些 Activity 还没起来，
     * 只能靠「先置位、后读」的约定——各 Activity 在 super.onCreate 之前调用
     * [markCreatedForAutofill]，随后 [onCreate] 里读它。若两者顺序反了，
     * 最坏结果是「重启时锁定」档位多锁一次，不会造成安全问题。
     */
    @Volatile
    private var createdForAutofill: Boolean = false

    /** 标记本次进程创建源于 autofill / 凭据提供商。 */
    fun markCreatedForAutofill() {
        createdForAutofill = true
    }

    override fun onCreate() {
        super.onCreate()
        // 自动锁定：进程前台/后台事件（Docs/10 §4）
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockController)
        // 进程创建：按档位决定是否立即上锁（含 autofill 豁免）。
        lockManager.onAppCreated(
            isFirstCreation = true,
            createdForAutofill = createdForAutofill,
        )
    }
}
