package io.vaultix.data.repository

import io.vaultix.crypto.PinKeyWrapper
import io.vaultix.crypto.PinUnwrapResult
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.PIN_MAX_ATTEMPTS
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.domain.PinEnrollOutcome
import io.vaultix.domain.PinUnlockOutcome
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「PIN 打开信封」的结果。
 *
 * **四态**：未启用 / 已锁定 / 信封损坏 / PIN 不对 —— 它们对用户的含义与动作完全不同
 * （引导重设 / 等主密码 / 报数据损坏 / 重试计数）。压成一个「失败」值就必须在别处
 * 再判一次才能恢复区分度，而那份重判一旦与这里不一致，就会把「数据坏了」显示成
 * 「PIN 错了」—— 这正是项目反复强调的**假状态**。让类型带出区分度，就不会有第二处判断。
 *
 * ⚠️ 可见性是 `public` 而非 `internal`：它出现在 [PinUnlockStore] 的公开签名里，
 * 而后者要被公开的 `VaultRepositoryImpl` 注入 —— Kotlin 不允许 public 类型暴露
 * internal 类型。它仍然是**实现细节**，不对外承诺稳定。
 */
sealed interface PinOpen {
    /** 解开成功。⚠️ 调用方负责 `fill(0)` 这段明文。 */
    class Opened(val payload: ByteArray) : PinOpen

    /** PIN 不对，且**还可以再试**；带上剩余次数（不把减法留给 UI，两处各算必漂移）。 */
    data class WrongPin(val remainingAttempts: Int) : PinOpen

    /** 连续输错已达 [PIN_MAX_ATTEMPTS]。 */
    data object LockedOut : PinOpen

    /** 未启用 / 信封损坏 / 其它不可用（**不是** PIN 错，故不计数）。 */
    data class Unavailable(val detail: String) : PinOpen
}

/**
 * 非成功结局 → 对外结果。成功分支由调用方自行处理（[PinOpen.Opened] 带着明文）。
 *
 * 定义在**本文件**而不是 `VaultRepositoryImpl`：那边的函数数已经顶着 detekt
 * `TooManyFunctions` 上限，能搬出来的判断就搬出来。
 */
internal fun PinOpen.toFailureOutcome(): PinUnlockOutcome = when (this) {
    is PinOpen.WrongPin -> PinUnlockOutcome.WrongPin(remainingAttempts)
    PinOpen.LockedOut -> PinUnlockOutcome.LockedOut
    is PinOpen.Unavailable -> PinUnlockOutcome.Unavailable(detail)
    // 防御分支：`Opened` 不该走到这里（调用方必须先处理成功分支）。
    // 不抛异常是因为这只是「调用方判断有误」的兜底，抛出去会把一个编程错误
    // 伪装成用户可见的崩溃；给一个明确的不可用原因更好查。
    is PinOpen.Opened -> PinUnlockOutcome.Unavailable("PIN 已解开但载荷未被处理")
}

/**
 * 应用内 PIN 的**落盘状态**与「打开信封」这一动作。
 *
 * ## 职责边界（刻意收窄，这是它值得单独存在的理由）
 *
 * - 只管三件事：**存了什么 / 能不能打开 / 错了几次**；
 * - **不认识任何库类型** —— 要包裹的字节由调用方给（Bitwarden 给会话密钥、
 *   KDBX 给凭据块）。因此本文件里**没有一处** `if (kind == ...)`；
 *   库类型的差异全部留在 `VaultRepositoryImpl`。
 *
 * ## 为什么从 `VaultRepositoryImpl` 抽出来
 *
 * 直接原因是那个类加完 PIN 后函数数到了 47，越过 detekt `TooManyFunctions` 的 40 上限。
 * 但这不是为了绕门禁 —— 那批状态管理本来就是内聚的一块，抽出来之后两边的职责都更清楚了。
 * ⚠️ **下一个再往里加解锁手段时，同样要提取，而不是继续堆。**
 *
 * ⚠️ 可见性 `public` 的理由同 [PinOpen]：它要被公开的 `VaultRepositoryImpl` 注入。
 * 它仍是实现细节。
 *
 * ## 与之配套的硬件保护
 *
 * 信封落盘走 [SecureCredentialStore]：那把 Keystore 密钥**不要求用户认证、但不可导出**，
 * 于是 PIN 的低熵被硬件绑定补上了 —— 破译者必须持有**这台设备**，
 * 光有落盘文件没用。详见 `PinKeyWrapper` 的 KDoc。
 */
@Singleton
class PinUnlockStore @Inject constructor(
    private val credentials: SecureCredentialStore,
    private val pinKeyWrapper: PinKeyWrapper,
    private val preferences: VaultixPreferences,
) {

    /** 入口是否可见（只看持久化开关，取向同快速解锁）。 */
    fun available(vaultId: String): Flow<Boolean> = preferences.isPinUnlockEnabled(vaultId)

    /**
     * PIN 本身的本地门槛（位数）。返回非 null 表示**应直接拒绝**。
     *
     * 让调用方先调这个再去做昂贵的事（如 KDBX 真解一次库）：
     * 位数都不对的 PIN 没必要白跑一遍 Argon2id。
     */
    fun validate(pin: String): PinEnrollOutcome? =
        if (pin.length < PIN_MIN_LENGTH) PinEnrollOutcome.PinTooShort(PIN_MIN_LENGTH) else null

    /**
     * 包裹 + 落盘 + 置开关 + 清零失败计数。
     *
     * ⚠️ **[payload] 的所有权转移给本方法**，返回前会被清零（`finally`）。
     * 调用方不要再持有它 —— 明文副本越少，能被内存转储捞到的窗口越小。
     */
    suspend fun persist(vaultId: String, pin: String, payload: ByteArray) {
        val envelope = try {
            pinKeyWrapper.wrap(pin, payload)
        } finally {
            payload.fill(0)
        }
        // 覆盖旧信封 ⇒ 旧 PIN 立即失效（这就是「修改 PIN」的实现）。
        credentials.putString(envelopeKey(vaultId), envelope)
        // 不清零失败计数的话，换了新 PIN 还会被上一次的失败次数莫名锁住。
        clearFailures(vaultId)
        preferences.setPinUnlockEnabled(vaultId, true)
    }

    /**
     * 用 PIN 打开信封。
     *
     * ⚠️ 计数规则：**只有 PIN 不对才计数**。信封损坏是数据问题、不是用户输错，
     * 让它把用户锁在门外是错的。
     */
    suspend fun open(vaultId: String, pin: String): PinOpen {
        val envelope = credentials.getString(envelopeKey(vaultId))
            ?: return PinOpen.Unavailable("未启用 PIN 解锁")
        if (attemptsOf(vaultId) >= PIN_MAX_ATTEMPTS) return PinOpen.LockedOut
        return when (val result = pinKeyWrapper.unwrap(pin, envelope)) {
            is PinUnwrapResult.Opened -> PinOpen.Opened(result.payload)
            PinUnwrapResult.WrongPin -> registerFailure(vaultId)
            is PinUnwrapResult.Malformed -> PinOpen.Unavailable(result.detail)
        }
    }

    /**
     * 关闭 PIN：删信封、清计数、落开关。幂等。
     *
     * ⚠️ 只动 PIN 那一份，**不动**快速解锁的登记 —— 两者是彼此独立的手段。
     */
    suspend fun disable(vaultId: String) {
        credentials.remove(envelopeKey(vaultId))
        credentials.remove(attemptsKey(vaultId))
        preferences.setPinUnlockEnabled(vaultId, false)
    }

    /**
     * 清零失败计数。
     *
     * ⚠️ 由调用方在**库真的打开之后**显式调用，而不是在 [open] 成功时顺手清：
     * `open` 只证明「PIN 对了」，此时库还没真的打开（KDBX 侧尤其如此，
     * PIN 对了但凭据过期的情况是存在的）。等库真开了再清，计数才忠实反映
     * 「这个 PIN 是好用的」。清早了会让「PIN 对但库打不开」白白重置计数。
     */
    fun clearFailures(vaultId: String) {
        credentials.remove(attemptsKey(vaultId))
    }

    /** 已连续输错次数（0 = 干净）。 */
    private fun attemptsOf(vaultId: String): Int =
        credentials.getString(attemptsKey(vaultId))?.toIntOrNull() ?: 0

    /** 记一次失败；达上限即翻成 [PinOpen.LockedOut]。 */
    private fun registerFailure(vaultId: String): PinOpen {
        val attempts = attemptsOf(vaultId) + 1
        credentials.putString(attemptsKey(vaultId), attempts.toString())
        return if (attempts >= PIN_MAX_ATTEMPTS) {
            PinOpen.LockedOut
        } else {
            PinOpen.WrongPin(remainingAttempts = PIN_MAX_ATTEMPTS - attempts)
        }
    }

    private fun envelopeKey(vaultId: String) = ENVELOPE_PREFIX + vaultId

    private fun attemptsKey(vaultId: String) = ATTEMPTS_PREFIX + vaultId

    private companion object {
        /** PIN 信封（`PinKeyWrapper` 产出的 `PV1:…`）。与快速解锁的信封**分开存**。 */
        const val ENVELOPE_PREFIX = "local_pin_key::"

        /**
         * 失败计数。
         *
         * 与信封一样放 [SecureCredentialStore] 而非明文偏好：它挡的是「拿到设备随手试几把」
         * 这类在线猜测。真正的成本由 PIN 派生密钥扛（每猜一次都要付 Argon2id 的代价），
         * 见 `PinKeyWrapper` 的 KDoc。
         */
        const val ATTEMPTS_PREFIX = "local_pin_attempts::"
    }
}
