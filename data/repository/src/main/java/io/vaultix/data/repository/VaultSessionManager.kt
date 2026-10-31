package io.vaultix.data.repository

import io.vaultix.crypto.SymmetricCryptoKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话与密钥管理（Docs/10 §4、Docs/01 §5）。
 *
 * 职责：在**内存**中按库持有已解包的账号对称密钥，锁定即清零。
 * 只持有密钥与锁定状态，不接触网络 / 数据库 / UI。
 *
 * 安全约定：
 * - 密钥不进 [kotlinx.coroutines.flow.StateFlow] 对外发布（防快照拷贝）；
 *   外部只能通过 [keyOf] 短时借用，且必须保证使用期间会话不被 [lock]；
 * - [lock] / [lockAll] 幂等；重复 unlock 同一库时先清零旧密钥再覆盖；
 * - 进程死亡密钥自然消失；自动锁定（后台超时）由后续 P2 的
 *   AppLifecycleObserver 驱动 [lockAll]，本类不感知生命周期。
 */
@Singleton
class VaultSessionManager @Inject constructor(
    /**
     * KDBX 会话的可观察桥 —— 这里只用它的 [KdbxSessionFlow.isUnlocked] 判据
     * （见 [hasOpenSession] 的 KDoc：**两个会话模型都要算**）。
     */
    private val kdbxSessions: KdbxSessionFlow,
) {

    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, SymmetricCryptoKey>()
    private val unlockedIdsState = MutableStateFlow<Set<String>>(emptySet())

    /** 已解锁库 id 集合（StateFlow 快照），供 UI 锁定徽标使用。 */
    val unlockedIds: Flow<Set<String>> = unlockedIdsState.asStateFlow()

    /** 注册会话密钥。调用方保证密钥已就绪（unlock 成功解包后）。 */
    suspend fun unlock(vaultId: String, key: SymmetricCryptoKey) {
        mutex.withLock {
            // 覆盖旧会话前先清零，避免密钥材料残留在内存
            sessions.remove(vaultId)?.clear()
            sessions[vaultId] = key
            unlockedIdsState.value = sessions.keys.toSet()
        }
    }

    /** 锁定单个库：清零密钥并移除会话。幂等。 */
    suspend fun lock(vaultId: String) {
        mutex.withLock {
            sessions.remove(vaultId)?.clear()
            // 真锁即「密钥已不在内存」→ 查看锁标记失去意义（没有会话可保护）。
            // 必须在这里一并清掉：否则超时真锁之后 `isViewLocked` 仍为 true，
            // 根导航会把界面收在「已解锁但查看锁定」的分支上，用户输入主密码解锁后
            // 又被弹回解锁页（2026-09-12 规范化时的判据，见 .ai/ISSUES.md #60 第 2 步）。
            viewLockedIdsState.value = viewLockedIdsState.value - vaultId
            autoViewLockedIdsState.value = autoViewLockedIdsState.value - vaultId
            unlockedIdsState.value = sessions.keys.toSet()
        }
    }

    /** 锁定全部库（清后台、手动锁定时调用）。幂等。 */
    suspend fun lockAll() {
        mutex.withLock {
            sessions.values.forEach { it.clear() }
            sessions.clear()
            viewLockedIdsState.value = emptySet()
            autoViewLockedIdsState.value = emptySet()
            unlockedIdsState.value = emptySet()
        }
    }

    /** 同步快照判断（供非挂起场景，如导航守卫）。 */
    fun isUnlocked(vaultId: String): Boolean = vaultId in unlockedIdsState.value

    /**
     * 是否**任一**库处于已解锁状态。
     *
     * 用途（对齐 Bitwarden `CredentialProviderProcessorImpl` 的 `isVaultUnlocked` 判定）：
     * 通行密钥链路上必须能**区分**三种情形，否则会把「库锁着」误报成「条目不存在」：
     *  1. 库锁定 → 走解锁引导（`authenticationActions` / 解锁界面）；
     *  2. 库已解锁但条目/凭证不在 → 才是真正的「找不到」；
     *  3. 库已解锁且凭证可取 → 正常签名。
     *
     * 只读快照，不涉及密钥材料。
     */
    fun isAnyUnlocked(): Boolean = unlockedIdsState.value.isNotEmpty()

    /**
     * 短时借用会话密钥（解密条目 / 加密新建条目）。
     *
     * @return 未解锁返回 null——由调用方决定降级（空列表）还是报错（保存需解锁）。
     */
    suspend fun keyOf(vaultId: String): SymmetricCryptoKey? = mutex.withLock { sessions[vaultId] }

    // ---- 查看层锁定（ViewLocked，2026-09-12 新增）----
    //
    // 与 [lock] 的区别：`lock` 会**清零并移除密钥**（真锁，必须重新提供主密码或解封 KEK）；
    // 这里的「查看锁」只加一个**内存标记**，密钥原样留在会话里 —— 用户按主页锁按钮后
    // 只是把界面挡回解锁页，重新认证（指纹/设备凭据）即可立刻回到已解锁视图，
    // **不联网、不重登、不重新派生密钥**（用户明确要求的语义，对齐"锁定=挡住屏幕而非销毁会话"）。
    //
    // ⚠️ 安全边界（有意为之，勿"顺手修掉"）：
    //  - 这是**界面门禁**，不是加密门禁：进程存活期间密钥在内存里；
    //  - 进程被杀 / 超时锁定 / 退出数据库 / 锁屏超时到期 时会走 [lock]，标记随之失效；
    //  - 因此 [viewLock] 必须在未解锁时是**幂等 no-op**（没密钥可保护，标记无意义）。
    private val viewLockedIdsState = MutableStateFlow<Set<String>>(emptySet())

    /**
     * **自动界面门禁**打上的查看锁（`viewLockedIdsState` 的子集）。
     *
     * ## 为什么必须区分来源（2026-10-30，用户诉求「填充不受影响」）
     *
     * 同是"界面被挡"，两种来源对**自动填充**的含义完全相反：
     *
     * | 来源 | 用户的意图 | 填充时该不该再认证 |
     * |---|---|---|
     * | 手动（条目页锁按钮） | "我现在就把界面关上" | **该** —— 否则那个按钮形同虚设 |
     * | 自动（熄屏 / 冷启动门禁） | "我离开了一下，回来确认是我" | **不该** —— 用户原话「自动填充依然是生效的」 |
     *
     * 若不做这个区分，两条路只能二选一：要么填充每次都被要求认证（违背诉求），
     * 要么手动锁定后填充照样能读（安全语义倒挂）。子集关系天然成立：
     * 自动门禁一定是"查看了得挡"，反之不成立。
     */
    private val autoViewLockedIdsState = MutableStateFlow<Set<String>>(emptySet())

    /** 已「锁查看层」的库 id（UI 据此停回解锁页；不影响 autofill/CP 的可用性判定）。 */
    val viewLockedIds: Flow<Set<String>> = viewLockedIdsState.asStateFlow()

    /** 该库当前是否只是「查看层被锁」（密钥仍在内存）。 */
    fun isViewLocked(vaultId: String): Boolean = vaultId in viewLockedIdsState.value

    /**
     * 该库的查看锁**是不是自动门禁打上的**（= 自动填充应当直通）。
     *
     * 见 [autoViewLockedIdsState] 的对照表：手动锁仍然要在填充前认证一次，
     * 自动门禁不要。
     */
    fun isAutoViewLocked(vaultId: String): Boolean = vaultId in autoViewLockedIdsState.value

    /** 任一库处于查看锁（根导航 / 解锁页自动选库用；只读快照）。 */
    fun anyViewLocked(): Boolean = viewLockedIdsState.value.isNotEmpty()

    /**
     * 是否有库处于**手动**查看锁（自动填充据此决定"要不要先让用户打开 App 认证"）。
     *
     * ⚠️ 自动门禁**不算**：那层只是"回到 App 时确认一下身份"，而填充本身是用户
     * 在另一个 App 里主动发起的动作，不该被它拦住（用户原话「填充并不会受影响」）。
     */
    fun anyManualViewLocked(): Boolean =
        (viewLockedIdsState.value - autoViewLockedIdsState.value).isNotEmpty()

    /**
     * 锁「查看层」：**不动密钥**，仅置标记。
     *
     * 未解锁时是 no-op（没有会话可保护，也不需要挡界面）。
     *
     * @param automatic `true` = 自动界面门禁（熄屏 / 冷启动恢复）打上的标记。
     *   **自动填充会看穿它**（见 [isAutoViewLocked]）；手动锁不会。
     *
     * ⚠️ **手动锁会把自动标记"升级"掉**（从 [autoViewLockedIdsState] 里移除）：
     * 同一库先被自动门禁挡住、用户又在条目页点了锁按钮，此刻的意图是**手动**的，
     * 填充就该重新认证。反过来的方向（自动覆盖手动）绝不允许 —— 那等于用户的
     * 显式锁定被一次熄屏悄悄降级成"只挡界面"。
     */
    suspend fun viewLock(vaultId: String, automatic: Boolean = false) {
        mutex.withLock {
            if (hasOpenSession(vaultId)) {
                viewLockedIdsState.value = viewLockedIdsState.value + vaultId
                autoViewLockedIdsState.value = if (automatic) {
                    autoViewLockedIdsState.value + vaultId
                } else {
                    autoViewLockedIdsState.value - vaultId
                }
            }
        }
    }

    /**
     * 该库当前**是否真有会话** —— 决定"查看锁"有没有意义（没有会话就没有可保护的东西）。
     *
     * ## ★ 2026-09-30 修：此前只看 Bitwarden 的会话表 ⇒ **KDBX 的查看锁永远是静默 no-op**
     *
     * 用户真机反馈：「kdbx 好像在密码条目页面，右上角点击锁定，**无法锁定**」。
     * 根因就是这个判断：**Vaultix 有两个互不相干的会话模型** ——
     *
     * | 库 | 会话是什么 | 存在哪 |
     * |---|---|---|
     * | Bitwarden | 解包的**对称密钥** | 本类的 [sessions]（`SymmetricCryptoKey`） |
     * | KDBX | **内存里的整库明文**（没有 SymmetricCryptoKey 可登记） | `KdbxSessionStore` |
     *
     * 而 `ItemsViewModel.lockNow`（条目页右上角「锁定」= **唯一的设置侧调用点**）走的
     * 是 `viewLock(vaultId)` ⇒ 对 KDBX 库 `containsKey` 恒为 false ⇒ 标记设不上 ⇒
     * 根导航（`RootNavViewModel`，它**确实**消费这个标记）看不到"查看锁" ⇒
     * **按了就跟没按一样**。这个缺陷从 2026-09-12 就一直存在，直到用户点出来。
     *
     * ⚠️ 口径与 `VaultRepositoryImpl.observeUnlockedVaultIds` 一致（那里也是
     * `sessions.unlockedIds + Kdbx.unlockedIds()`）—— **"已解锁"只有一个口径**，
     * 别处不要再各写一份。
     *
     * ⚠️ 不要为了"灵活"把判据换成"标记一律设上"：那会让**真锁**（无会话）的库被根导航
     * 当成查看锁 ⇒ 用户先白按一次生物识别、再被弹回解锁页要主密码（`.ai/ISSUES.md` #60
     * 第 2 步的同族）。查看锁的语义是"**密钥还在**，只是界面收回"，没会话就不成立。
     */
    private fun hasOpenSession(vaultId: String): Boolean =
        sessions.containsKey(vaultId) || kdbxSessions.isUnlocked(vaultId)

    /** 认证通过后清除查看锁（幂等，两个集合一起清）。 */
    suspend fun clearViewLock(vaultId: String) {
        mutex.withLock {
            viewLockedIdsState.value = viewLockedIdsState.value - vaultId
            autoViewLockedIdsState.value = autoViewLockedIdsState.value - vaultId
        }
    }

    /** 清空全部查看锁（解锁/退出数据库时一并调用）。 */
    suspend fun clearAllViewLocks() {
        mutex.withLock {
            viewLockedIdsState.value = emptySet()
            autoViewLockedIdsState.value = emptySet()
        }
    }

    /**
     * 一次认证，清掉**所有**库的**自动**界面门禁（2026-10-31，用户报「指纹要验两次」）。
     *
     * ## 为什么必须是「全部」而不是只清当前库
     *
     * 指纹是**全局**的（房子化后门锁只有一把，`HouseKeyStore`），所以"验证过是本人"
     * 这件事本身也是全局的。但 [viewLock] 的标记是**按库**存的 —— 熄屏时
     * [AutoViewGate] 会给**每个**已解锁的「从不」库各打一个标记。只清当前库的话：
     *
     * 1. `RootNavViewModel` 看到"还有别的库带着查看锁" ⇒ 再次判成 `VaultLocked`
     *    ⇒ 用户刚验过指纹，切到另一个库又被要求验一次（**这就是用户看到的现象**）；
     * 2. 而且第二次、第三次……每个库各要一次，库越多越离谱。
     *
     * Bitwarden 里锁定状态是**账号级**的（一个账号一次认证），不存在这个问题；
     * 项目改成多库之后，"门禁"与"认证"的粒度就不一致了 —— 本轮把认证粒度对齐到全局。
     *
     * ## 为什么**只**清自动标记
     *
     * 手动查看锁（条目页那个锁按钮）是用户对**某一个库**的显式指令。
     * 一次熄屏后的身份确认不该顺手把它撤销 —— 那等于"用户锁了 A，验个指纹 A 也开了"。
     * 两个集合本来就分得开（见 [autoViewLockedIdsState]），这里正是它的用武之地。
     */
    suspend fun clearAutoViewLocks() {
        mutex.withLock {
            viewLockedIdsState.value = viewLockedIdsState.value - autoViewLockedIdsState.value
            autoViewLockedIdsState.value = emptySet()
        }
    }
}
