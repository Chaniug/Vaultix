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
class VaultSessionManager @Inject constructor() {

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
            unlockedIdsState.value = sessions.keys.toSet()
        }
    }

    /** 锁定全部库（清后台、手动锁定时调用）。幂等。 */
    suspend fun lockAll() {
        mutex.withLock {
            sessions.values.forEach { it.clear() }
            sessions.clear()
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

    /** 已「锁查看层」的库 id（UI 据此停回解锁页；不影响 autofill/CP 的可用性判定）。 */
    val viewLockedIds: Flow<Set<String>> = viewLockedIdsState.asStateFlow()

    /** 该库当前是否只是「查看层被锁」（密钥仍在内存）。 */
    fun isViewLocked(vaultId: String): Boolean = vaultId in viewLockedIdsState.value

    /**
     * 锁「查看层」：**不动密钥**，仅置标记。
     *
     * 未解锁时是 no-op（没有会话可保护，也不需要挡界面）。
     */
    suspend fun viewLock(vaultId: String) {
        mutex.withLock {
            if (sessions.containsKey(vaultId)) {
                viewLockedIdsState.value = viewLockedIdsState.value + vaultId
            }
        }
    }

    /** 认证通过后清除查看锁（幂等）。 */
    suspend fun clearViewLock(vaultId: String) {
        mutex.withLock {
            viewLockedIdsState.value = viewLockedIdsState.value - vaultId
        }
    }

    /** 清空全部查看锁（解锁/退出数据库时一并调用）。 */
    suspend fun clearAllViewLocks() {
        mutex.withLock { viewLockedIdsState.value = emptySet() }
    }
}
