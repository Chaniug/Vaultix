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

    // ---- KDBX 本地库（M2 阶段 A：只读）----

    /**
     * 添加并解锁一个 **KDBX 本地库**（`.kdbx` 文件）。
     *
     * 与 [addBitwardenVault] 的架构差异（有意为之，见 `.ai/ISSUES.md` #60 第 4 批）：
     * - 认证发生在**文件**上（主密码 + 可选 keyfile），不联网、无账号、无 2FA；
     * - 解密后的内容**只活在内存**（KDBX 引擎的会话），条目**不落 ciphers 表** ——
     *   KDBX 的保真度靠「原文件」保证，抄一份密文到 Room 只会引入两处真源
     *   （写回阶段再谈缓存）。
     *
     * @param sourceUri SAF 选中的文件 URI（作为 vault 行的 `origin`；KDBX 库的 id 即它）。
     * @param displayName 列表里显示的名字（默认取文件名）。
     * @param keyFileUri 可选的 keyfile URI（需已取得持久读权限）。
     * @return 成功时返回 [KdbxAddResult]（区分「新增」与「已存在并覆盖」，
     *   供 UI 给出**非静默**的成功反馈 —— 见 `.ai/ISSUES.md` #94）。
     */
    suspend fun addKdbxVault(
        sourceUri: String,
        displayName: String,
        masterPassword: String,
        keyFileUri: String?,
    ): KdbxAddOutcome

    /** 解锁已注册的 KDBX 库（主密码 + 可选 keyfile；keyfile URI 由仓储从偏好里读）。 */
    suspend fun unlockKdbxVault(vaultId: String, masterPassword: String): UnlockResult

    /**
     * 切换活跃库时**锁定所有非 [keepVaultId] 的 KDBX 库**（内存只留一把密钥）。
     *
     * 为什么要主动锁：KDBX 的会话持有的是**整库明文**（比 Bitwarden 的一把对称密钥重得多），
     * 多库同时解锁会让「同时只能进一个库」的产品约束在内存层面失效。
     */
    suspend fun lockOtherKdbxVaults(keepVaultId: String)

    /** 该库当前是否已（在内存中）解锁 —— 含 KDBX 会话，供 UI 的解锁徽标使用。 */
    fun isVaultUnlocked(vaultId: String): Boolean

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
     * **退出数据库**（用户可见语义，见 `.ai/ISSUES.md` #60 第 3 步）：
     * 清会话 + 清本地缓存（快速解锁凭据 / token / 待推送队列 / 密文条目与文件夹
     * / 同步基线），**保留库行、不碰远程**。
     *
     * 与 [removeVault] 的差别：后者连 `vaults` 行一起删（库从列表里消失）；
     * 本方法保留库行，用户下次点一下重新登录即可。
     *
     * ⚠️ 丢弃待推送队列是「清缓存」的必然含义（本地未上传的改动会丢），
     * 调用方必须在 UI 上给出明确确认文案。
     */
    suspend fun signOut(vaultId: String)

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

    // ---- 本地快速解锁：KDBX 侧（`.ai/decisions/库选择与快速解锁-逻辑定稿.md` §4）----

    /**
     * **KDBX 库**启用本地快速解锁（`.ai/ISSUES.md` #93）。
     *
     * 与 Bitwarden 侧的 [enrollLocalUnlock] 是**两个方法**而非一个重载，因为包裹物
     * 本质不同：Bitwarden 包的是「会话里的对称密钥」，KDBX 会话**不含密钥**
     * （`KdbxSession` 只有整库明文，`Kdbx.unlock()` 用完即弃）⇒ 只能包「主密码 +
     * keyfile」这组**能重新开库的凭据**。
     *
     * ⚠️ **必须在 wrap 之前先用这组凭据真的解一次库**：`wrap` 只管包裹字节、
     * 不管字节对不对。若用户输错密码照样 wrap 成功，下次指纹就会解出错密码
     * ⇒ 开库失败且无法自愈。校验必须复用真实开库路径（不能只比对长度）。
     *
     * @param masterPassword 用户当场输入的主密码（**不落盘**，只进包裹物）。
     * @param keyFileUri 该库登记的 keyfile URI（可空；由上层从 preferences 读）。
     * @return 见 [KdbxEnrollOutcome]；`InvalidCredentials` 时 UI 应就地让用户重输。
     */
    suspend fun enrollLocalUnlockKdbx(
        vaultId: String,
        masterPassword: String,
        keyFileUri: String?,
        cipher: javax.crypto.Cipher,
    ): KdbxEnrollOutcome

    /**
     * 认证通过后：解封 KDBX 包裹物（主密码 + keyfile）并**真的开库**。
     *
     * 返回 [KdbxUnlockOutcome.StaleCredentials] 表示「指纹本身通过了，但包裹物已失效」
     * —— 典型成因是用户改了主密码。这是定稿 §4.4 的 **D3 = 明确提示 + 自动重包**：
     * UI 提示「主密码可能已变更」，用户输新密码成功后自动重新包裹，
     * 下次指纹即可用（**不删除用户的快速解锁登记**，与 [completeLocalUnlock]
     * 对 Bitwarden 的「不可恢复」处理取向不同 —— 那侧包裹物是密钥、密码变了也还能开）。
     */
    suspend fun completeLocalUnlockKdbx(
        vaultId: String,
        cipher: javax.crypto.Cipher,
    ): KdbxUnlockOutcome

    // ===== 应用内 PIN 解锁（定位：解锁便利，**不是**找回手段）=====

    /**
     * PIN 解锁入口是否可见（按库）。
     *
     * 取向与 [localUnlockAvailable] 一致：**只看持久化开关**，不在订阅时现探
     * 密钥可用性 —— 否则一次瞬时异常就会把入口整条藏掉，用户以为功能没了。
     * 真失败留到输入那一刻如实报错（那时原因才准确）。
     */
    fun pinUnlockAvailable(vaultId: String): Flow<Boolean>

    /**
     * 启用 / 重设 PIN（**Bitwarden 库**）。
     *
     * ⚠️ 与 [enrollPinKdbx] 分成**两个方法**而非一个重载，理由同
     * [enrollLocalUnlockKdbx]：Bitwarden 的会话里**有**要包裹的密钥（库正解锁），
     * 所以无需再输一次主密码；KDBX 的会话里**没有**主密码，必须当场输入并先校验。
     * 合并成一个方法就得让「主密码」这个参数在 Bitwarden 侧无意义地可选，
     * 那条隐式约定迟早被用错。
     *
     * 重设语义 = 重新包裹。旧信封会被覆盖 ⇒ **旧 PIN 立即失效**。
     */
    suspend fun enrollPin(vaultId: String, pin: String): PinEnrollOutcome

    /** 启用 / 重设 PIN（**KDBX 库**）：先校验凭据、后包裹。 */
    suspend fun enrollPinKdbx(
        vaultId: String,
        pin: String,
        masterPassword: String,
        keyFileUri: String?,
    ): PinEnrollOutcome

    /** 用 PIN 解锁 **Bitwarden 库**（解出的密钥直接登记会话）。 */
    suspend fun completePinUnlock(vaultId: String, pin: String): PinUnlockOutcome

    /** 用 PIN 解锁 **KDBX 库**（解出凭据后**真的开库**，与 [completeLocalUnlockKdbx] 同理）。 */
    suspend fun completePinUnlockKdbx(vaultId: String, pin: String): PinUnlockOutcome

    /**
     * 关闭 PIN 解锁：删除信封与开关，**并清零失败计数**。幂等。
     *
     * ⚠️ 只删 PIN 那一份，**不动**快速解锁的登记（两者是独立手段）。
     */
    suspend fun disablePin(vaultId: String)
}

/**
 * PIN 长度下限。
 *
 * 6 位是「好记」与「难猜」的平衡点。⚠️ 真正的安全**不来自**这个位数，而是
 * `SecureCredentialStore` 那层「不需用户认证但不可导出」的硬件 Keystore 密钥：
 * 破译者必须持有**这台设备**，光有落盘文件没用（详见 `PinKeyWrapper` 的 KDoc）。
 */
const val PIN_MIN_LENGTH: Int = 6

/** 连续输错上限；达到即锁定，须用主密码解锁后重设。 */
const val PIN_MAX_ATTEMPTS: Int = 5

/** [VaultRepository.enrollPin] / [VaultRepository.enrollPinKdbx] 的结果。 */
sealed interface PinEnrollOutcome {
    /** 已包裹落盘、开关已置位。 */
    data object Enrolled : PinEnrollOutcome

    /** PIN 低于 [PIN_MIN_LENGTH]。本地即可判定，故单独成一态（UI 要指着输入框说）。 */
    data class PinTooShort(val minimum: Int) : PinEnrollOutcome

    /** KDBX：主密码 / keyfile 不对 —— **校验阶段**就失败，未写任何东西。 */
    data object InvalidCredentials : PinEnrollOutcome

    /**
     * 当前没有可包裹的会话（库未解锁 / 会话已失效）。
     * ⇒ 引导用户先用主密码登录一次，而不是报「失败」让用户猜。
     */
    data object SessionUnavailable : PinEnrollOutcome

    /** 库文件读不到（URI 授权失效 / 文件被删）或其它异常。 */
    data class Failed(val detail: String) : PinEnrollOutcome
}

/** [VaultRepository.completePinUnlock] / [VaultRepository.completePinUnlockKdbx] 的结果。 */
sealed interface PinUnlockOutcome {
    /** 解包成功且库可用（Bitwarden 会话已登记 / KDBX 已开库）。 */
    data object Opened : PinUnlockOutcome

    /**
     * PIN 不对。[remainingAttempts] 是**还剩几次**（UI 直接展示，不要把减法留给 UI，
     * 否则两处各算一遍必然漂移）。
     */
    data class WrongPin(val remainingAttempts: Int) : PinUnlockOutcome

    /** 连续输错达 [PIN_MAX_ATTEMPTS] ⇒ 锁定。UI 应引导走主密码并重设 PIN。 */
    data object LockedOut : PinUnlockOutcome

    /**
     * PIN 对了，但包裹的凭据打不开库（主密码已在别处改过）⇒ 引导重设。
     * **不是** PIN 错，故不计入失败次数。
     */
    data object StaleCredentials : PinUnlockOutcome

    /** 未启用 / 信封缺失或损坏 ⇒ 回退主密码解锁（**不删登记**，用户可重设自愈）。 */
    data class Unavailable(val detail: String) : PinUnlockOutcome
}

/** [VaultRepository.enrollLocalUnlockKdbx] 的结果（UI 据此决定文案与是否重输）。 */
sealed interface KdbxEnrollOutcome {
    /** 校验通过、包裹物已落盘、开关已置位。 */
    data object Enrolled : KdbxEnrollOutcome

    /**
     * 主密码（或 keyfile）不对 —— **校验阶段**就失败了，未写任何东西。
     * UI 按「宽松」取向处理：提示后**保留输入框、就地重输**，不掉出流程。
     */
    data object InvalidCredentials : KdbxEnrollOutcome

    /** 库文件读不到（URI 授权失效 / 文件被删）。 */
    data class SourceUnavailable(val detail: String) : KdbxEnrollOutcome

    /** KEK 不可用 / 其它异常。 */
    data class Failed(val detail: String) : KdbxEnrollOutcome
}

/** [VaultRepository.completeLocalUnlockKdbx] 的结果。 */
sealed interface KdbxUnlockOutcome {
    /** 解封成功且**库真的打开了**（会话已登记）。 */
    data object Opened : KdbxUnlockOutcome

    /**
     * **指纹通过了，但包裹物打不开库** ⇒ 主密码很可能已被用户在别处改过。
     * UI 应提示并引导重输 → 成功后 [VaultRepository.enrollLocalUnlockKdbx] 自动重包。
     */
    data object StaleCredentials : KdbxUnlockOutcome

    /** 未启用快速解锁 / 包裹物缺失 / KEK 已失效（指纹变更等）⇒ 回退主密码解锁。 */
    data class Unavailable(val detail: String) : KdbxUnlockOutcome
}

/** 解锁 / 添加库的结果分类，便于 UI 给出可执行的提示（Docs/10 §5）。 */
sealed interface UnlockResult {    data object Success : UnlockResult

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

/**
 * KDBX 添加结果（`addKdbxVault` 的返回类型）。
 *
 * 为什么不能复用 [UnlockResult]（`.ai/ISSUES.md` #94）：KDBX 库的 `id` **就是文件 URI**，
 * 重复添加同一文件会 `upsert` 覆盖同一行 ⇒ 列表零变化，而旧实现的成功分支只有
 * `popBackStack()`、**一句提示都没有** ⇒ 用户的读法是「点了一点反应都没有」
 * （既非失败也无成功）。
 *
 * ⇒ 成功必须**分二态**，让 UI 能说清发生了什么：
 * - [Added]：新库进列表；
 * - [Updated]：同文件重新添加（用户改过密码 / 想刷新）—— D5 定稿选「允许覆盖 + 成功反馈」。
 *
 * 失败路径继续用 [UnlockResult]（凭据错 / 文件读不到 / 非 KDBX…），
 * UI 侧用 `when` 分支处理两件不同的事。
 */
sealed interface KdbxAddOutcome {
    /** 新增了一个此前不在列表里的库。 */
    data object Added : KdbxAddOutcome

    /** 该文件此前已添加过：本次**覆盖**了同一行（允许覆盖 + 成功反馈，D5 定稿）。 */
    data object Updated : KdbxAddOutcome

    /** 添加失败（凭据 / 文件 / 格式等原因，见 [UnlockResult]）。 */
    data class Failed(val result: UnlockResult) : KdbxAddOutcome
}
