package io.vaultix.vaultix

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import io.vaultix.common.logging.LogLevel
import io.vaultix.common.logging.VaultixLog
import io.vaultix.vaultix.di.KdbxCloudSyncInitializer
import io.vaultix.vaultix.security.AutoLockController
import io.vaultix.vaultix.security.AutoRestoreTrigger
import io.vaultix.vaultix.security.ScreenSecurityGuard
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
class VaultixApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var autoLockController: AutoLockController

    @Inject
    lateinit var lockManager: VaultLockManager

    /**
     * KDBX 网盘同步的启动钩子（2026-09-17）。
     *
     * ⚠️ 这个字段**看起来没用**（没有任何地方读它），但它**必须存在**：
     * [KdbxCloudSyncInitializer] 的全部价值就在它的 `init` 块里 ——
     * 把 app 侧的 OneDrive 来源工厂注册进 `KdbxCloudSyncCoordinator`。
     * 不在这里注入，Hilt 就不会构造它，注册也就不会发生 ⇒ 症状是
     * **"配好了 OneDrive 库，同步却一直说没有云端来源"**，且没有任何报错。
     *
     * ⚠️ 必须是 `@Inject lateinit var`（而不是 `by lazy` 或 `Provider`）：
     * 前者的构造时机是 `onCreate` 之前的字段注入期，早于任何同步请求；
     * 后者会把注册推迟到"第一次真的要用"，那时已经晚了。
     */
    @Inject
    lateinit var kdbxCloudSyncInitializer: KdbxCloudSyncInitializer

    /**
     * 「从不锁定」档自动恢复的触发器（2026-09-29，对齐 Bitwarden autoUnlockKey）。
     *
     * ⚠️ 与上面的初始化器同一模式：价值全在 `init` 块（订阅档位与房钥匙内存态，
     * 进程死亡后自动恢复会话）。**必须**在进程启动早期构造 —— autofill 拉起的
     * 进程也要能恢复（否则「从不」档在划掉后台后的第一次填充仍然要解锁，
     * 那正是用户报的「填充框弹条目不及时」）。
     */
    @Inject
    lateinit var autoRestoreTrigger: AutoRestoreTrigger

    /**
     ★ **防截屏守卫**（2026-10-02 双库健康度审计 · 批次 A）。
     *
     * ⚠️ 必须在 `onCreate` 里显式 [install]（不同于上面几个"价值全在 init 块"的同伴）：
     * 它需要 `Application` 引用来注册 `ActivityLifecycleCallbacks`，而这个引用在
     * 字段注入期拿到会过早且没必要 —— `onCreate` 仍早于**任何** Activity 被创建。
     *
     * 为什么非它不可：`FLAG_SECURE` 原先只挂在 `MainActivity`（Composable 那一路），
     * 其余 9 个 Activity（自动填充 / 凭据提供商 / 通行密钥存取）**全裸** ——
     * 而那几张恰恰是用户在第三方 App 里最常看到、屏上又有密码与条目名的页面。
     * 详见 [io.vaultix.vaultix.security.ScreenSecurityGuard] 的类 KDoc。
     */
    @Inject
    lateinit var screenSecurityGuard: ScreenSecurityGuard

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
        // ★ 第一个就该做的事情：`super.onCreate()` 之后、任何 Activity 创建之前注册
        //   防截屏。晚于此处的任何时机都会留下一个"能被截到敏感内容"的窗口。
        screenSecurityGuard.install(this)
        // 诊断日志装配（2026-09-16）：**只有 DEBUG 构建才开**。
        // release 下 enabled=false ⇒ 门面在拼字符串之前就返回，零开销、也绝不外泄异常栈。
        // ⚠️ 日志内容有铁律（禁记密码/密钥/token/明文），见 VaultixLog 的 KDoc。
        VaultixLog.install(enabled = BuildConfig.DEBUG) { tag, level, message, throwable ->
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, message, throwable)
                LogLevel.WARN -> Log.w(tag, message, throwable)
                LogLevel.ERROR -> Log.e(tag, message, throwable)
            }
        }
        // 自动锁定：进程前台/后台事件（Docs/10 §4）
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockController)
        // 锁定事件 → 导航回根代次。**必须显式启动**（2026-10-18：原来是 init 里自启，
        // 但 init 期间还没有机会注入测试调度器 ⇒ 单测里收集器挂不到虚拟时钟上）。
        autoLockController.startCollecting()
        // 进程创建：按档位决定是否立即上锁（含 autofill 豁免）。
        // 库列表观察（活动库 + 锁态快照）。**必须显式启动** —— 见
        // `VaultLockManagerImpl.startObservingVaults` 的 KDoc（构造期派发 + 无界 collect
        // 会把单测的虚拟时钟搅乱）。
        lockManager.startObservingVaults()
        lockManager.onAppCreated(
            isFirstCreation = true,
            createdForAutofill = createdForAutofill,
        )
    }

    /**
     * 全局 Coil `ImageLoader`（2026-11-01，用户报「图标每次打开都重新加载」）。
     *
     * ## 为什么必须在这里配，而不是在各个 `ImageRequest` 里
     *
     * `respectCacheHeaders` 是 **loader 级**开关，`ImageRequest` 上没有对应的 per-request
     * 选项。而它恰恰是「每次冷启动都要重新下载全部图标」最可能的成因：
     *
     * Coil 默认 `respectCacheHeaders = true` —— 服务端回什么 `Cache-Control` 就照办。
     * 图标端点 `<base>/icons/<host>/icon.png` 一旦返回 `no-cache` / `max-age=0`
     * （反向代理与 CDN 的默认行为并不统一），**磁盘缓存就完全不写**。
     * 于是：冷启动 → 内存缓存空 → 磁盘缓存也空 → 一屏图标全部重新联网。
     * 用户看到的正是「每次都重新加载」。
     *
     * ## 为什么关掉它是**正确**的（而不是拿安全换速度）
     *
     * 图标是「域名 → 一张小 PNG」的**准静态**资源，本地缓存优先是行业惯例
     * （Bitwarden 自己的客户端也长期缓存图标）。关掉之后唯一的代价是：
     * 站点换了 favicon，本地可能仍旧图，直到 LRU 淘汰或清缓存。
     * 这个代价远小于「每次启动重下几十张图」。
     *
     * ⚠️ 本仓库目前**只有图标**走 Coil，所以这条全局策略不会误伤别的图片。
     * 若将来引入"必须实时的图片"（如头像 / 分享图），**不要**改这里，
     * 而是在那条 `ImageRequest` 上单独设 `respectCacheHeaders` —— 它确实有 per-request 版本。
     *
     * ## 其余保持默认
     *
     * 内存缓存（`memoryClass` 的 20%）与磁盘缓存（空闲空间 2%，限幅 10~250MB）都**不收紧**：
     * 一张 40dp 图标在 3x 屏上是 120px ≈ 56KB，整库几百条也就几 MB ——
     * 离上限差两个数量级，收紧只会降低命中率、把空帧放回来。
     *
     * 磁盘缓存目录必须保持 Coil 默认的 `cacheDir/image_cache`：`AutofillItemIcon`
     * 直接按 URL 读这个目录做填充建议的图标，换目录等于把它废掉。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .respectCacheHeaders(false)
        // 全局也关一次淡入：单条 request 已各自关掉，这里是兜底，
        // 防止将来新增的图标调用点漏设而把「白圈闪一下」带回来。
        .crossfade(false)
        .build()
}
