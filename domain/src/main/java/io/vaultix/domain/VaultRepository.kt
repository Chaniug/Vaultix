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
     * **KDBX 库**启用本地快速解锁 —— 第一步：**校验并暂存**（`.ai/ISSUES.md` #93）。
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
     * ### ★ 为什么必须拆成「准备 / 提交」两步（2026-09-14 修闪退）
     *
     * 快解的保护器 KEK 是 **auth-per-use**（`setUserAuthenticationParameters(0, …)`）：
     * 只有**被 BiometricPrompt 授权过的那一个 Cipher 实例**才能完成 `doFinal`。
     * 而 KDBX 的主密码只能在弹指纹**之前**拿到（包裹物必须提前组装好），于是：
     *
     * | 顺序 | 结果 |
     * |---|---|
     * | ❌ 先 wrap 再弹指纹 | `doFinal` 抛 `UserNotAuthenticatedException` ⇒ 协程未捕获 ⇒ **闪退** |
     * | ✅ 先弹指纹再 wrap | 认证通过后用授权过的 cipher 包裹 |
     *
     * ⇒ 校验与组装密文留在本方法（此处的产物是**待包裹的明文**），
     * 真正的 `wrap` 移到指纹成功之后由 [commitKdbxEnroll] 完成。
     * 调用方在指纹**被取消/失败**时必须调 [discardKdbxEnroll] 把暂存明文擦掉。
     *
     * @param masterPassword 用户当场输入的主密码（**不落盘**，只进包裹物）。
     * @param keyFileUri 该库登记的 keyfile URI（可空；由上层从 preferences 读）。
     * @return 见 [KdbxEnrollOutcome]；`InvalidCredentials` 时 UI 应就地让用户重输。
     *   `Enrolled` 的语义是「校验通过且凭据已暂存，等待指纹」，**不代表已落盘**。
     */
    suspend fun prepareKdbxEnroll(
        vaultId: String,
        masterPassword: String,
        keyFileUri: String?,
    ): KdbxEnrollOutcome

    /**
     * KDBX 快速解锁第二步：用**已认证**的 [cipher] 包裹暂存的凭据并落盘、置位开关。
     *
     * @return true = 包裹成功（此后指纹可用）；false = 没有暂存凭据（流程被中断/重复提交）。
     * @throws Exception `wrap` 自身的异常（Keystore 状态错等）**不吞** —— 那是环境/编程错误，
     *   吞成 false 会掩盖它（与 [enrollLocalUnlock] 同款取向）。包装失败时暂存明文仍会被擦除。
     */
    suspend fun commitKdbxEnroll(vaultId: String, cipher: javax.crypto.Cipher): Boolean

    /**
     * 放弃本次 KDBX 快速解锁登记：把暂存的凭据明文**擦掉**（幂等，无暂存时是空操作）。
     *
     * 调用时机 = 指纹被用户取消 / 被系统终止。没有它，主密码副本会在单例里留到
     * 下一次登记或进程结束 —— 与项目「明文用完即擦」的一贯取向冲突。
     */
    suspend fun discardKdbxEnroll()

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

    /**
     * 一次勾选多个库启用快速解锁：**认证之后**用同一个 cipher 逐库落盘。
     *
     * ## 为什么要有这个批量入口
     *
     * 用户原话：「默认一个生物验证的指纹，管理解锁所有的库也可以吗」——
     * 原来每个库都要单独点一遍指纹、单独走一遍流程，库多了很烦。
     * 这个入口让「一次勾选 → 一次认证」覆盖全部选中的库。
     *
     * ## ⚠️ cipher 必须来自**本次**认证，且只能连续用完
     *
     * 快解的保护器 KEK 是 auth-per-use（`setUserAuthenticationParameters(0, …)`），
     * **一个 cipher 只对一次认证有效**。所以这里必须是**连续** wrap 完所有库，
     * 绝不能"提前给每个库各准备一个 cipher"—— 那些没被授权，第二个库就抛
     * `UserNotAuthenticatedException`（2026-09-14 闪退同源）。
     *
     * ## 认证前的校验与备料不走本方法
     *
     * 「勾了哪些库、KDBX 主密码对不对、要包什么明文」是**认证前**的事，由
     * `BiometricEnrollController` 直接调 `LocalUnlockEnrollment.prepareForVaults`
     * 完成。这样接口只多这一个方法 —— `VaultRepositoryImpl` 的函数数才能守住
     * detekt `TooManyFunctions` 的 40 上限（它本来就在顶格）。
     *
     * ## 部分成功是真实状态
     *
     * 返回值是**逐库**结论（`associate`），一个库失败不影响其它库。调用方必须
     * 逐条展示，**不能因为有失败就整体报错**，也不能只显示成功。
     *
     * @return vaultId → 该库的结论。**不含**备料阶段就已失败的库（那些在
     *   `prepareForVaults` 的结果里）。调用方应把两段结论合并后再展示。
     */
    suspend fun commitLocalUnlockEnrollForVaults(
        prepared: List<LocalUnlockPreparedEnrollment>,
        cipher: javax.crypto.Cipher,
    ): Map<String, LocalUnlockEnrollOutcome>

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
     * 「**一个 PIN 打开多个库**」的配齐流程（2026-09-16 用户诉求）。
     *
     * ## 用户要的效果
     *
     * 用户原话：「**我想要的是 app 一个 PIN 能够打开 bitwarden 和 kdbx。**」
     * 在设置里**一次性**把同一个 PIN 登记到多个库，之后解锁页只需输入这一个 PIN，
     * Bitwarden 与 KDBX 都能被打开 —— 而不是"每个库各设一次"。
     *
     * ⚠️ **PIN 值相同并不能让 KDBX 免掉主密码**：这是最容易误解的一点。
     * 差别不在 PIN，而在**包进信封的东西**（下节）。
     *
     * ## 为什么必须"一次配齐"，而不能只设一次就自动通用
     *
     * 两种库**包进信封的东西本质不同**，这是本设计的硬约束：
     * - Bitwarden 包的是**会话里的对称密钥**（库正解锁 ⇒ 直接可取，无需任何密码）；
     * - KDBX 包的是**「主密码 + keyfile 字节」** —— KDBX 会话里**根本没有主密码**
     *   （只有解密后的数据），所以必须由用户当场输入一次。
     *
     * ⇒ 想让同一个 PIN 覆盖多个库，KDBX 那部分的主密码**早晚要在某个时刻被收集**。
     *   最省事的收法就是"在设置 PIN 时一次问清"，而不是留到解锁时才逐个补。
     *   配齐之后，解锁链路**一行都不用改**：每个库本来就有自己的信封，
     *   输入同一个 PIN 即可各开各的。
     *
     * ## 每库独立信封 + 独立失败计数（安全边界不变）
     *
     * 本方法只是把**同一 PIN 值**分别包裹进各库自己的信封（各库密钥不同 ⇒ 密文亦不同）。
     * 因此：
     * - 某个库在别处重设了 PIN ⇒ **只影响那个库**，其余库不受牵连；
     * - 失败计数仍按库独立 ⇒ 一个库输错锁住，不会连带锁死其它库。
     *
     * ## 部分成功是真实状态
     *
     * 不做"全成功才算成功 / 整体回滚"：某个 KDBX 库的主密码可能输错，
     * 而 Bitwarden 侧的登记是好的。如实逐库反馈比整体失败更有用
     * （否则用户为了一个库的笔误就得把全部库重设一遍）。
     *
     * ## ⚠️ 必须由调用方指定目标，不得隐式覆盖全部库
     *
     * [vaultIds] 是**用户勾选**要设 PIN 的库。库表里有多少库 ≠ 用户想设几个 ——
     * 有些库用户可能根本不想启用 PIN（例如只读的共享库）。
     * 若本方法自己遍历全部库，就会**静默改掉用户没同意改的配置**，
     * 而 PIN 覆盖会影响解锁入口，属于用户可感知的安全设置，不能替用户决定。
     * UI 的默认勾选可以是全选（多数人的诉求就是"一个 PIN 全开"），
     * 但**选择权必须在用户手上**，且要能取消。
     *
     * ## 分派规则（两种库各走各的）
     *
     * | 库类型 | 包什么 | 当场要不要密码 |
     * | --- | --- | --- |
     * | Bitwarden | 会话里的对称密钥 | ❌ 不需要 |
     * | KDBX | 主密码 + keyfile 字节 | ✅ **必须**（先校验后包裹） |
     *
     * @param vaultIds 要设置 PIN 的库（调用方已按用户选择过滤）。
     * @param pin 对所有目标库生效的同一个 PIN。
     * @param masterPassword KDBX 库的主密码（用户当场输入的那一次）。
     *   ⚠️ 本方法接收它即视为**一次性使用**，实现方须确保用后清零、不落盘。
     *   没有 KDBX 目标库时应传空串。
     * @return 每库的结果（含失败原因），键为 vaultId。
     */
    suspend fun enrollPinForVaults(
        vaultIds: List<String>,
        pin: String,
        masterPassword: String,
    ): Map<String, PinEnrollOutcome>

    /**
     * 「**一个 PIN 打开多个库**」的配齐流程（2026-09-16 用户诉求）。
     *
     * ## 用户要的效果
     *
     * 在设置里**一次性**把同一个 PIN 登记到多个库，之后解锁页只需输入这一个 PIN，
     * Bitwarden 与 KDBX 都能被打开 —— 而不是现在这样"每个库各设一次，
     * 解锁时还得先把每个库都配一遍"。
     *
     * ## 为什么必须"一次配齐"，而不能只设一次就自动通用
     *
     * 两种库**包进信封的东西本质不同**，这是本设计的硬约束：
     * - Bitwarden 包的是**会话里的对称密钥**（库正解锁 ⇒ 直接可取，无需任何密码）；
     * - KDBX 包的是**「主密码 + keyfile 字节」** —— KDBX 会话里**根本没有主密码**
     *   （只有解密后的数据），所以必须由用户当场输入一次。
     *
     * ⇒ 想让同一个 PIN 覆盖多个库，KDBX 那部分的主密码**早晚要在某个时刻被收集**。
     *   最省事的收法就是"在设置 PIN 时一次问清"，而不是留到解锁时才逐个补。
     *   配齐之后，解锁链路**一行都不用改**：每个库本来就有自己的信封，
     *   输入同一个 PIN 即可各开各的。
     *
     * ## 每库独立信封 + 独立失败计数（安全边界不变）
     *
     * 本方法只是把**同一 PIN 值**分别包裹进各库自己的信封（各库密钥不同 ⇒ 密文亦不同）。
     * 因此：
     * - 某个库在别处重设了 PIN ⇒ **只影响那个库**，其余库不受牵连；
     * - 失败计数仍按库独立 ⇒ 一个库输错锁住，不会连带锁死其它库。
     *
     * ## 部分成功是真实状态
     *
     * 不做"全成功才算成功 / 整体回滚"：某个 KDBX 库的主密码可能输错，
     * 而 Bitwarden 侧的登记是好的。如实逐库反馈比整体失败更有用
     * （否则用户为了一个库的笔误就得把全部库重设一遍）。
     *
     * ## ⚠️ 必须由调用方指定目标，不得隐式覆盖全部库
     *
     * [vaultIds] 是**用户勾选**要设 PIN 的库。库表里有多少库 ≠ 用户想设几个 ——
     * 有些库用户可能根本不想启用 PIN（例如只读的共享库）。
     * 若本方法自己遍历全部库，就会**静默改掉用户没同意改的配置**，
     * 而 PIN 覆盖会影响解锁入口，属于用户可感知的安全设置，不能替用户决定。
     * UI 的默认勾选可以是全选（多数人的诉求就是"一个 PIN 全开"），
     * 但**选择权必须在用户手上**，且要能取消。
     *
     * @param vaultIds 要设置 PIN 的库（调用方已按用户选择过滤）。
     * @param pin 对所有目标库生效的同一个 PIN。
     * @param masterPassword KDBX 库的主密码（用户当场输入的那一次）。
     *   ⚠️ 本方法接收它即视为**一次性使用**，实现方须确保用后清零、不落盘。
     * @return 每库的结果（含失败原因），键为 vaultId。
     */
    suspend fun enrollPinForVaults(
        vaultIds: List<String>,
        pin: String,
        masterPassword: String,
    ): Map<String, PinEnrollOutcome>

    /**
     * 全部库的「PIN 覆盖候选」（供设置对话框列出勾选项）。
     *
     * 返回**全部**库（含 KDBX），由 UI 决定默认勾选谁：
     * - Bitwarden 库：可直接设置（无需额外输入）；
     * - KDBX 库：也能设，但用户要**另外提供主密码**，UI 应把这点标出来。
     *
     * ⚠️ 返回全部而不是"可设的"：KDBX 库并非不可设（只是要多输一次密码），
     * 若在这里过滤掉，用户会以为 KDBX 不支持 PIN —— 那是**假状态**。
     */
    suspend fun pinCandidateVaultIds(): List<String>

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

/** [VaultRepository.enrollPinForVaults] 的逐库结果。 */
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

/** [VaultRepository.prepareKdbxEnroll] 的结果（UI 据此决定文案与是否重输）。 */
sealed interface KdbxEnrollOutcome {
    /**
     * 校验通过、凭据已**暂存**，等待指纹认证后由
     * [VaultRepository.commitKdbxEnroll] 包裹落盘。
     *
     * ⚠️ 刻意**不叫 Enrolled + 不写「已落盘」**：这个名字若撒谎，调用方就会
     * 以为可以跳过 [VaultRepository.commitKdbxEnroll]，于是「指纹按了却没生效」。
     */
    data object Prepared : KdbxEnrollOutcome

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
     * UI 应提示并引导重输 → 成功后 [VaultRepository.prepareKdbxEnroll] 重新组装、
     * [VaultRepository.commitKdbxEnroll] 重包。
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

    /**
     * 是否需要在认证之后补取明文（Bitwarden 侧 = true）。
     *
     * KDBX 的明文（主密码 + keyfile）在备料阶段就必须备好，因为它只存在于用户脑子里；
     * Bitwarden 的对称密钥则在内存会话里，认证后取更省事。
     */
    val requiresPostAuthPlaintext: Boolean
}
