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

    // ---- 快速解锁（两级钥匙「房子化」，`.ai/decisions/快速解锁房子化-两级钥匙层级-定稿.md`）----
    //
    // 模型：指纹 / PIN 两把门锁各只有一个信封，包的都是**同一把**随机 256-bit
    // 房子钥匙（明文仅存内存，硬约束 #1；Never 档另有加密的自动恢复信封，
    // 见 `AutoUnlockKeyStore`）；每个库的凭据用这把钥匙**纯软件**封装
    // （房间信封，每库一份）。一次生物识别授权 = 一次 Keystore 操作（解房钥匙），
    // 其余全是软件解密。

    /**
     * 指纹门锁是否已启用（**全局**；门锁信封存在 && KEK 非永久失效）。
     *
     * 取向沿用 2026-09-12 的修正：LOADABLE 与 UNKNOWN 都给出入口，真失败留到
     * [prepareFingerprintUnlock] 那一刻如实报错 —— 否则一次瞬时 Keystore 异常
     * 就会把指纹入口整条藏掉（用户被迫重新联网登录，实测过的回归）。
     */
    fun fingerprintLockAvailable(): Flow<Boolean>

    /** PIN 门锁是否已启用（**全局**；真源 = 门锁信封存在性，取向同上）。 */
    fun pinLockAvailable(): Flow<Boolean>

    /**
     * 该库是否可用指纹快解（指纹门锁在 && 该库房间信封在）。
     * 解锁页指纹入口的可见性 = 本方法 —— 组合了「全局门锁」与「该库房间」两个事实。
     */
    fun fingerprintQuickUnlockAvailable(vaultId: String): Flow<Boolean>

    // ---- 指纹门锁（Keystore KEK，auth-per-use）----

    /**
     * 开指纹门锁第一步：创建包装用 Cipher（KEK 用户认证），交给 BiometricPrompt
     * 认证后传入 [enrollFingerprintLock]。null = 设备无可用认证方式。
     */
    suspend fun prepareFingerprintEnroll(): javax.crypto.Cipher?

    /**
     * 开指纹门锁第二步（认证已过）：用授权 cipher 包裹房子钥匙 ——
     * 登记只 wrap 这一个 blob（定稿硬约束 #3，H1 结构性消失）。
     *
     * @return true = 门锁信封已落盘。false = 另一把门锁已存在但房钥匙不在内存
     *   （**先解现有门锁复得钥匙再开这把**，否则两把门锁会包不同的钥匙 ——
     *   顺序约束由 `HouseKeyStore` 守，编排层负责引导用户先走一次解锁）。
     */
    suspend fun enrollFingerprintLock(cipher: javax.crypto.Cipher): Boolean

    /**
     * 解指纹门锁第一步：读门锁信封初始化解密 Cipher（IV 来自信封）。
     * 返回的 cipher 必须立刻交给本次 BiometricPrompt；null = 门锁未启用 / KEK 失效
     * （指纹变更等）→ 回退主密码。
     */
    suspend fun prepareFingerprintUnlock(): javax.crypto.Cipher?

    /**
     * 解指纹门锁第二步（认证已过）：解出房钥匙进内存。
     *
     * 这是解锁路径上**唯一**的 Keystore 操作（定稿硬约束 #2）；此后各库走
     * [unlockVaultFromRoom] 纯软件解密 ——「rest 库现取新 cipher」（H2）结构性不存在。
     *
     * @return false = unwrap 失败（KEK 失效 / 信封损坏）→ 按定稿 §6 优雅降级，
     *   **不要**静默重试（auth-per-use 下再取 cipher 也无人授权）。
     */
    suspend fun completeFingerprintUnlock(cipher: javax.crypto.Cipher): Boolean

    /**
     * 关指纹门锁（删门锁信封；**全局**，定稿 §5.1：门锁开关 = 一次 wrap 的建立/删除）。
     *
     * 若删完后一把门锁都不剩，房间信封会被连带清掉 —— 否则房钥匙再无恢复途径，
     * 进程一死全部成永远解不开的孤儿信封（定稿 §5 顺序约束的推论）。
     */
    suspend fun disableFingerprintLock()

    // ---- PIN 门锁（Argon2id，全局计数）----

    /**
     * PIN 位数门槛。返回非 null 表示**应直接拒绝** —— 先验后做昂贵事：
     * 位数都不对的 PIN 没必要白跑一次 Argon2id。
     */
    fun validatePin(pin: String): PinEnrollOutcome?

    /**
     * 开 PIN 门锁：Argon2id(PIN) 包裹房子钥匙。覆盖旧信封即「修改 PIN」
     * （既有决策「修改 PIN = 关掉再开」在此自然成立）。返回值语义同 [enrollFingerprintLock]。
     */
    suspend fun enrollPinLock(pin: String): Boolean

    /**
     * 用 PIN 解门锁（全局失败计数，连续输错 [PIN_MAX_ATTEMPTS] 次熔断 ——
     * 门锁是全局的，计数也是，定稿 §6：比旧模型的每库独立 5 次更严）。
     *
     * 成功（`PinUnlockOutcome.Opened`）= 房钥匙已进内存，随后逐库 [unlockVaultFromRoom]。
     */
    suspend fun openHouseWithPin(pin: String): PinUnlockOutcome

    /** 关 PIN 门锁（删信封 + 清计数；孤儿清理逻辑同 [disableFingerprintLock]）。 */
    suspend fun disablePinLock()

    // ---- 房间信封（生效范围：勾 = 该库房间信封已建，纯软件、不碰门锁）----

    /**
     * 「生效范围」登记：把备好的明文逐库**软件封装**成房间信封。
     *
     * **不收 cipher**（勾库不碰指纹，定稿 §5）：唯一前置是房钥匙在内存
     * （至少一把门锁已开）。逐库独立成败（部分成功是真实状态）；
     * **不含**备料阶段就已失败的库（那些在 `prepareForVaults` 的结果里）。
     *
     * @param prepared `LocalUnlockEnrollment.prepareForVaults` 的产物（备料仍由
     *   控制器直接注入该类调用、不经本契约 —— 维持 2026-09-16 起「备料不经
     *   repository」的函数数纪律）。
     */
    suspend fun sealRoomsForVaults(
        prepared: List<LocalUnlockPreparedEnrollment>,
    ): Map<String, LocalUnlockEnrollOutcome>

    // ⚠️ 这里曾有过 `removeVaultFromScope(vaultId)`（「取消勾选某库」=删房间信封
    //   + 范围镜像剔除），**2026-10-04 删除**：
    //   「范围勾选」向导已整段删除，范围恒等于全部库（见 `startEnrollment`），
    //   「移出范围」这个动作**已不可能由用户触发** ⇒ 留着它就是一段
    //   永远不成立的死逻辑 —— 与 QuickUnlockController 里同一件事的处理同因。
    //   库被**删除**时的房间信封清理由 `removeVault` 那条既有路径负责（不变）。

    // ---- 解锁扇出（1 次门锁 + N 次软件）----

    /**
     * 用房间信封打开一个库：软件解密取回该库凭据，按库类型开库
     * （Bitwarden 建会话 / KDBX 真开库）。
     *
     * 前置：房钥匙已在内存（先过一把门锁：指纹 [completeFingerprintUnlock]
     * 或 PIN [openHouseWithPin]）；不满足时返回 [RoomUnlockOutcome.Unavailable]。
     *
     * [RoomUnlockOutcome.StaleCredentials] = 该库主密码在别处改过（D3：门锁不动，
     * UI 引导输新密码后重包**该房间的软件信封**）。
     */
    suspend fun unlockVaultFromRoom(vaultId: String): RoomUnlockOutcome

    /**
     * 全部库的「生效范围候选」（供设置对话框列出勾选项）。
     *
     * 返回**全部**库（含 KDBX），由 UI 决定默认勾选谁：
     * - Bitwarden 库：房间信封可直接建（凭据就在会话里）；
     * - KDBX 库：也能纳入，但用户要**另外提供主密码**，UI 应把这点标出来。
     *
     * ⚠️ 返回全部而不是"可纳入的"：KDBX 库并非不可纳入（只是要多输一次密码），
     * 若在这里过滤掉，用户会以为 KDBX 不支持快速解锁 —— 那是**假状态**。
     */
    suspend fun quickUnlockCandidateVaultIds(): List<String>
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

/** [VaultRepository.enrollPinForVaults] 的逐库结果。 */
sealed interface PinEnrollOutcome {
    /** 已包裹落盘、开关已置位。 */
    data object Enrolled : PinEnrollOutcome

    /** PIN 低于 [PIN_MIN_LENGTH]。本地即可判定，故单独成一态（UI 要指着输入框说）。 */
    data class PinTooShort(val minimum: Int) : PinEnrollOutcome

    /** KDBX：主密码 / keyfile 不对 —— **校验阶段**就失败，未写任何东西。 */
    data object InvalidCredentials : PinEnrollOutcome

    /**
     * 用户**主动跳过**该库（没输主密码，或在对话框里点了「跳过」）。
     *
     * ⚠️ 与 [InvalidCredentials] 分成两态：那不是失败，是用户的选择 ——
     * 结果页要写「已跳过」而不是「密码不对」，否则用户会以为是自己打错了。
     */
    data object Skipped : PinEnrollOutcome

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

/**
 * [VaultRepository.unlockVaultFromRoom] 的结果：用房间信封打开一个库的统一结论。
 *
 * 形状沿用旧 `KdbxUnlockOutcome` 的三态（那套区分度已被证明必要），但**不区分
 * 库类型** —— 房子化后 Bitwarden 与 KDBX 走同一条「软件解房间信封 → 开库」路径，
 * 差别只在实现内部（建会话 vs 真开库）。
 */
sealed interface RoomUnlockOutcome {
    /** 该库已打开（Bitwarden 建好会话 / KDBX 真开库）。 */
    data object Opened : RoomUnlockOutcome

    /**
     * 房间信封解开了、但凭据开不了库（主密码在别处改过，D3）。
     * 门锁不动；UI 引导输新密码后重包**该房间的软件信封**即可自愈。
     */
    data object StaleCredentials : RoomUnlockOutcome

    /** 未纳入范围 / 房钥匙不在内存 / 信封损坏 / 库文件不可用等。 */
    data class Unavailable(val detail: String) : RoomUnlockOutcome
}

/**
 * KDBX 快解的旧结局类型（房子化前）。
 *
 * ⚠️ 房子化（2026-09-28）后快解统一走 [RoomUnlockOutcome]；本类型仍被 KDBX
 * 引擎侧的开库结论使用，快解侧不再是它的出口。
 */
sealed interface KdbxUnlockOutcome {
    /** 解封成功且**库真的打开了**（会话已登记）。 */
    data object Opened : KdbxUnlockOutcome

    /**
     * **指纹通过了，但包裹物打不开库** ⇒ 主密码很可能已被用户在别处改过。
     * UI 应提示并引导重输 → 成功后 [VaultRepository.prepareKdbxEnroll] 重新组装、
     * [VaultRepository.commitKdbxEnroll] 重包。
     */
    data object StaleCredentials : KdbxUnlockOutcome

    /** 未启用快速解锁 / 包裹物缺失 / KEK 已失效（指纹变更等）⇒ 回退主密码解锁。 */
    data class Unavailable(val detail: String) : KdbxUnlockOutcome
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
 * KDBX 网盘同步的结果（映射自 data 层 `SyncOutcome`）。
 *
 * ## 为什么与 [VaultSyncReport] 分开
 *
 * Bitwarden 的同步是「服务端说了算」（revision 仲裁），KDBX 的是「条件写 + 用户拍板」。
 * 压成一个 sealed 接口会让 UI 拿到一堆永远走不到的分支，也会诱使调用方
 * 用 Bitwarden 的语义（比如"重试就能好"）去处理 KDBX 的冲突（**重试永远好不了**）。
 */
sealed interface KdbxSyncReport {

    /** 两边一致，什么都没做。 */
    data object InSync : KdbxSyncReport

    /** 本地改动已推上远端。 */
    data class Uploaded(val newVersionToken: String?) : KdbxSyncReport

    /** 远端版本已拉到本地并替换了会话。 */
    data object Downloaded : KdbxSyncReport

    /**
     * ★ 两边都改了 —— **已拒写，远端未被覆盖**，需要用户拍板（见三个 `resolveKdbx*`）。
     */
    data class Conflict(val currentRemoteVersion: String?) : KdbxSyncReport

    /**
     * 🔴 远端更新、本地未改 —— 本该拉下来，但需要重新解锁才能替换本地会话。
     *
     * 调用方应提示「远端有更新，请重新解锁以拉取」，**不要**当成已同步。
     */
    data object NeedsReload : KdbxSyncReport

    /** 该库没有配置网盘来源。 */
    data object NoCloudSource : KdbxSyncReport

    /** 同步失败（网络 / 凭据 / IO）。[reason] 可直接展示。 */
    data class Failed(val reason: String) : KdbxSyncReport
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

/**
 * 一次为多个库启用快速解锁时，单个库的**认证前备料**结果。
 *
 * 「备料」= 校验凭据 + 把要包进信封的明文准备好。之所以要**分成认证前/后两段**，
 * 是因为快解的保护器 KEK 是 auth-per-use：认证之前做不了 `wrap`，只能先把料备好。
 * （与 PIN 侧的「先校验、后包裹」是同一条纪律，区别只是 PIN 不需要系统认证。）
 */
sealed interface LocalUnlockPrepareOutcome {
    /** 备料完成，等认证通过后落盘。 */
    class Ready(val prepared: LocalUnlockPreparedEnrollment) : LocalUnlockPrepareOutcome

    /** KDBX 主密码不对。UI 按「宽松」取向就地让用户重输。 */
    data object InvalidCredentials : LocalUnlockPrepareOutcome

    /**
     * 用户**主动跳过**该库（没给它输主密码）。
     *
     * ⚠️ 与 [InvalidCredentials] 分开：那不是失败，是用户的选择 —— 结果页要写
     * 「已跳过」，用户才知道是"我没配这个库"，而不是"我配错了"。
     */
    data object Skipped : LocalUnlockPrepareOutcome

    /** 库文件读不到（URI 授权失效 / 文件被移走）—— 与「密码错」必须分开报。 */
    data class SourceUnavailable(val detail: String) : LocalUnlockPrepareOutcome

    /** 其它失败（库不存在 / 类型不识别 / Bitwarden 库未解锁）。 */
    data class Failed(val detail: String) : LocalUnlockPrepareOutcome
}

/**
 * 一次为多个库启用快速解锁时，单个库的**认证后落盘**结果。
 *
 * ⚠️ 调用方必须**逐库**展示：部分成功是真实状态，不能因为有失败就整体报错，
 * 也不能只显示成功（否则用户下次解锁时才发现某个库打不开）。
 */
sealed interface LocalUnlockEnrollOutcome {
    /** 信封已落盘、开关已置位，此后指纹可开这个库。 */
    data object Enrolled : LocalUnlockEnrollOutcome

    /** 该库失败（Keystore 失效 / 会话已丢）。**只影响这一个库**。 */
    data class Failed(val detail: String) : LocalUnlockEnrollOutcome
}

/**
 * 「认证通过后要包进信封」的一份备料（域层只描述**是什么**，实现细节留给数据层）。
 *
 * 放在 domain 而不是 data：`domain` 模块**不依赖** `data`（只依赖 `core:model`），
 * 接口签名里出现 `data` 的类型会直接编译失败。这也正是它必须实现 [AutoCloseable]
 * 的原因 —— 明文用完即擦是**契约**，不能靠调用方自觉。
 */
interface LocalUnlockPreparedEnrollment : AutoCloseable {
    val vaultId: String

    /** 用于结果展示的库名。 */
    val displayName: String
}
