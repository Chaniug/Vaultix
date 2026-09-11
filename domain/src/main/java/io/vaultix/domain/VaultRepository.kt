package io.vaultix.domain

import io.vaultix.model.VaultSummary
import kotlinx.coroutines.flow.Flow

/**
 * 库生命周期与同步入口（Docs/01 §5 状态机、Docs/10 §4 会话管理）。
 *
 * 实现位于 data:repository（Docs/11：接口在 domain，实现在 data）。
 * 纯 Kotlin，无 Android 依赖，便于 ViewModel 注入 fake 单测。
 */
interface VaultRepository {

    /** 全部库摘要（含当前锁定状态），供库列表 UI 收集。 */
    fun observeVaults(): Flow<List<VaultSummary>>

    /** 已解锁库 id 集合（供锁定徽标 / 请求密钥前判断）。 */
    fun observeUnlockedVaultIds(): Flow<Set<String>>

    /**
     * 添加并解锁一个 Bitwarden 库：
     * 登录换取 token → 解包账号对称密钥（进内存会话）→ 注册/更新本地库行。
     * 重复添加同一服务器视为「更新账号并解锁」（同一服务器仅支持一个账号，见 decisions）。
     */
    suspend fun addBitwardenVault(server: String, email: String, masterPassword: String): UnlockResult

    /** 解锁已注册的库：需要再次输入主密码（Bitwarden 云端解锁需联网做 prelogin）。 */
    suspend fun unlockVault(vaultId: String, masterPassword: String): UnlockResult

    /** 两步验证：主密码已通过，提交验证码完成解锁（[UnlockResult.TwoFactorRequired] 之后调用）。 */
    suspend fun unlockVaultWithTwoFactor(
        vaultId: String,
        masterPassword: String,
        provider: Int,
        code: String,
    ): UnlockResult

    /** 添加库的两步验证形态：与 [addBitwardenVault] 相同，但带验证码完成登录。 */
    suspend fun addBitwardenVaultWithTwoFactor(
        server: String,
        email: String,
        masterPassword: String,
        provider: Int,
        code: String,
    ): UnlockResult

    /**
     * 锁定单个库：清零内存中的对称密钥（幂等）。
     *
     * ⚠️ 挂起函数（2026-09-11 起）：密钥清零需要在协程中完成，调用方在自己的
     * 作用域里挂起等待。**挂起点之后**密钥必然已不可用。
     * （此前为 `fun` + 内部 `runBlocking`，会阻塞调用线程。）
     */
    suspend fun lockVault(vaultId: String)

    /** 锁定全部库（应用退到后台 / 手动锁定时调用）。同上，挂起式。 */
    suspend fun lockAll()

    /**
     * 移除库（本地删除，云端数据不受影响）：
     * 清内存会话 → 删本地快速解锁痕迹（包裹密钥/开关）→ 清待推送队列 →
     * 删 vault 行（ciphers/folders 经外键级联）。同服务器重加账号不会残留
     * 旧队列。抛异常 = 移除失败（调用方提示重试）。
     */
    suspend fun removeVault(vaultId: String)

    /** 触发一次同步（推送 dirty → revision 预检 → 全量拉取 → 安全校验 → 落库）。 */
    suspend fun syncVault(vaultId: String): VaultSyncReport

    // ---- 本地快速解锁（Docs/10 §4 会话管理的设备侧扩展）----

    /**
     * 该库是否已启用本地快速解锁（开关 + 包裹密钥均存在）。
     * 为 true 时解锁页显示「生物识别 / 设备 PIN 解锁」，锁库后免主密码免 2FA。
     */
    fun localUnlockAvailable(vaultId: String): Flow<Boolean>

    /**
     * 登录 / 主密码解锁成功后启用：把会话内对称密钥用「用户已认证的 cipher」
     * 包裹并落盘。cipher 由 UI 经 [LocalUnlockKeyStore.newEncryptCipher] 创建并
     * 交给 BiometricPrompt 认证后传入。
     *
     * @return false = 会话未解锁 / KEK 不可用（UI 提示稍后再试）。
     */
    suspend fun enrollLocalUnlock(vaultId: String, cipher: javax.crypto.Cipher): Boolean

    /**
     * 解锁前准备：读取包裹密钥并初始化解密 Cipher（IV 来自 payload）。
     * 返回的 cipher 必须立刻交给本次 BiometricPrompt；null = 未启用 / KEK 失效
     * （指纹变更等）→ 回退主密码登录。
     */
    suspend fun prepareLocalUnlock(vaultId: String): javax.crypto.Cipher?

    /**
     * 启用前的准备：创建包装用 Cipher（KEK 用户认证），交给 BiometricPrompt
     * 认证后传入 [enrollLocalUnlock]。null = 设备无可用认证方式。
     */
    suspend fun prepareLocalEnroll(): javax.crypto.Cipher?

    /** 认证通过后：解封本地密钥并建立会话（完全离线，不触发 2FA）。 */
    suspend fun completeLocalUnlock(vaultId: String, cipher: javax.crypto.Cipher): UnlockResult

    /** 关闭本地快速解锁：删除包裹密钥与开关（不动主密码登录）。 */
    suspend fun disableLocalUnlock(vaultId: String)
}

/** 解锁 / 添加库的结果分类，便于 UI 给出可执行的提示（Docs/10 §5）。 */
sealed interface UnlockResult {
    data object Success : UnlockResult

    /** 邮箱或主密码错误（401，或 OAuth invalid_grant） */
    data object InvalidCredentials : UnlockResult

    /**
     * 账号不存在：官方 Bitwarden 的 prelogin 对未注册邮箱返回 404，
     * Vaultwarden 则一律返回默认 KDF 参数（不区分账号是否存在），
     * 因此该分支只在官方端出现。
     */
    data object AccountNotFound : UnlockResult

    /**
     * 需要两步验证：密码已通过，服务端返回 two_factor_required。
     * provider 0 = Authenticator(TOTP)；1 = Email（服务端自动发码）；
     * 输入验证码后调 [VaultRepository.unlockVaultWithTwoFactor] /
     * [VaultRepository.addBitwardenVaultWithTwoFactor] 完成登录。
     */
    data class TwoFactorRequired(val providers: List<Int>) : UnlockResult

    /** 两步验证码错误或已过期。 */
    data object TwoFactorInvalid : UnlockResult

    /** 无法连接 / 超时 */
    data object Network : UnlockResult

    /** 服务端未返回受保护的账号对称密钥（自托管配置缺失等） */
    data object KeyUnavailable : UnlockResult

    /** 本地不存在该库（vaultId 无效或已删除） */
    data object VaultMissing : UnlockResult

    /** 服务端返回了不支持的 KDF 类型等未知错误 */
    data class Unknown(val detail: String?) : UnlockResult
}

/** 同步结果（映射自 data 层 SyncOutcome），供 UI 给出可执行提示而非笼统失败。 */
sealed interface VaultSyncReport {
    data class Success(val cipherCount: Int, val folderCount: Int) : VaultSyncReport

    /** 服务端无变化，跳过全量 */
    data object Skipped : VaultSyncReport

    /** 被保护机制拦截（空库保护等），需用户确认 */
    data class Blocked(val reason: String) : VaultSyncReport

    /** 可稍后重试（网络等） */
    data class Retryable(val reason: String) : VaultSyncReport

    /** 无法自动恢复（token 失效需重新登录等） */
    data class Fatal(val reason: String) : VaultSyncReport

    /** 该库类型暂不支持同步（KDBX 等后续里程碑） */
    data object Unsupported : VaultSyncReport
}

/**
 * 同步触发来源（编排器用；语义与 Bastion SyncTriggerReason 对齐，见 data:repository
 * 的 BitwardenSyncOrchestrator）。非 MANUAL 的自动触发默认「静默」：成功后不打断 UI，
 * 失败仍要可见。
 */
enum class SyncTrigger {
    /** 手动（按钮）：最高优先级、跳过节流 */
    MANUAL,

    /** 进入条目页 */
    PAGE_ENTER,

    /** 应用回前台 */
    APP_RESUME,

    /** WorkManager 周期后台同步 */
    PERIODIC,

    /** 失败后的自动重试 */
    RETRY,
}

/**
 * 单库同步运行时状态（Bastion VaultSyncStatus 语义子集）：UI 顶部细进度条 /
 * 库列表同步状态 / 设置页同步信息的统一数据源。
 */
data class VaultSyncStatus(
    val isRunning: Boolean = false,
    val trigger: SyncTrigger? = null,
    /** 自动触发（非 MANUAL）为静默：成功不打扰 UI，失败仍要可见。 */
    val isSilent: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastSuccessCipherCount: Int? = null,
    val lastErrorAt: Long? = null,
    val lastError: String? = null,
    val retryAttempt: Int = 0,
    val nextRetryAt: Long? = null,
)
