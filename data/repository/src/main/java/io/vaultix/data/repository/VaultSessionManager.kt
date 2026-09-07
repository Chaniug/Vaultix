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
     * 短时借用会话密钥（解密条目 / 加密新建条目）。
     *
     * @return 未解锁返回 null——由调用方决定降级（空列表）还是报错（保存需解锁）。
     */
    suspend fun keyOf(vaultId: String): SymmetricCryptoKey? = mutex.withLock { sessions[vaultId] }
}
