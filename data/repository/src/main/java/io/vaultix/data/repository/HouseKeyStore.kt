/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.crypto.GcmSealed
import io.vaultix.crypto.PinKeyWrapper
import io.vaultix.crypto.PinUnwrapResult
import io.vaultix.crypto.SecureBytes
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.datastore.AutoUnlockKeyStore
import io.vaultix.datastore.LocalUnlockKekStatus
import io.vaultix.datastore.LocalUnlockKeyStore
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.domain.PIN_MAX_ATTEMPTS
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.domain.PinChangeOutcome
import io.vaultix.domain.PinEnrollOutcome
import io.vaultix.domain.PinUnlockOutcome
import io.vaultix.domain.RoomUnlockOutcome
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 「房子钥匙」打开信封的结果（PIN 门锁）。
 *
 * 四态语义与旧 `PinUnlockStore.PinOpen` 相同，唯一变化：**成功分支不再外发明文**。
 * 旧模型里 PIN 信封直接包每库凭据，调用方拿到明文自己去开库；房子化后 PIN 门锁包的
 * 是房子钥匙，解开即收进本类内存（[HouseKeyStore.isUnlocked] 变 true），
 * 调用方接下来走房间信封（[HouseKeyStore.openRoom]）逐库取凭据。
 * 把钥匙交出去再收回来只会多一份能被内存转储捞到的明文副本。
 *
 * ⚠️ 可见性 `public`：出现在 [HouseKeyStore] 公开签名里（后者被公开的
 * `VaultRepositoryImpl` 注入），Kotlin 不允许 public 类型暴露 internal 类型。
 */
sealed interface PinOpen {
    /** 门锁已开：房钥匙在内存里，可以逐库 [HouseKeyStore.openRoom]。 */
    data object Opened : PinOpen

    /** PIN 不对，且还可以再试；带上剩余次数（不把减法留给 UI，两处各算必漂移）。 */
    data class WrongPin(val remainingAttempts: Int) : PinOpen

    /** 连续输错已达 [PIN_MAX_ATTEMPTS]（全局计数，定稿 §6）。 */
    data object LockedOut : PinOpen

    /** 未启用 / 信封损坏 / 其它不可用（**不是** PIN 错，故不计数）。 */
    data class Unavailable(val detail: String) : PinOpen
}

/** 「房间信封」的打开结果。四态，语义对用户各不相同（「空有多态」纪律）。 */
sealed interface RoomOpen {
    /**
     * 房间信封解开。⚠️ [payload] 是明文凭据（Bitwarden 64B 密钥 / KDBX 主密码+keyfile），
     * 调用方**用完必须 `fill(0)`**。
     */
    class Opened(val payload: ByteArray) : RoomOpen

    /** 房钥匙不在内存（未解锁 / 已锁）。动作：先开一把门锁。 */
    data object NoKey : RoomOpen

    /** 该库没有房间信封（未纳入快速解锁）。 */
    data object NotEnrolled : RoomOpen

    /** 信封损坏（AEAD 标签不过 / 格式坏）。只影响该库，可删可重建（定稿 §6）。 */
    data class Damaged(val detail: String) : RoomOpen
}

/** 非成功结局 → 对外结果（成功分支由调用方处理）。从旧 `PinUnlockStore` 原样迁入。 */
internal fun PinOpen.toFailureOutcome(): PinUnlockOutcome = when (this) {
    is PinOpen.WrongPin -> PinUnlockOutcome.WrongPin(remainingAttempts)
    PinOpen.LockedOut -> PinUnlockOutcome.LockedOut
    is PinOpen.Unavailable -> PinUnlockOutcome.Unavailable(detail)
    // 防御分支：Opened 不该走到这里（调用方必须先处理成功分支）。不抛异常：
    // 这是「调用方判断有误」的兜底，抛出去会把编程错误伪装成用户可见的崩溃。
    PinOpen.Opened -> PinUnlockOutcome.Unavailable("PIN 门锁已解开但未被处理")
}

/**
 * 房间非成功结局 → 对外结果（[RoomOpen.Opened] 由调用方处理）。
 * 与 [toFailureOutcome] 同款取向：NoKey / NotEnrolled 都是「状态如实」，
 * 不是错误 —— UI 拿到 Unavailable(detail) 提示用户该做什么（先过门锁 / 重新登记）。
 */
internal fun RoomOpen.toRoomFailure(): RoomUnlockOutcome = when (this) {
    RoomOpen.NoKey -> RoomUnlockOutcome.Unavailable("房钥匙不在内存，请先解锁门锁")
    RoomOpen.NotEnrolled -> RoomUnlockOutcome.Unavailable("该库未纳入快速解锁")
    is RoomOpen.Damaged -> RoomUnlockOutcome.Unavailable(detail)
    // 防御分支：同 toFailureOutcome —— 编排 bug 的兜底，不拿崩溃惩罚用户。
    is RoomOpen.Opened -> RoomUnlockOutcome.Unavailable("房间已解开但未被处理")
}

/**
 * 开门锁（指纹 / PIN）的结果。
 *
 * 失败只有一种**结构性原因**：已存在另一把门锁信封、但内存里没有房钥匙 ——
 * 此时**不能**凭空生成新钥匙（否则两把门锁包的将是不同的钥匙，房间信封跟谁走？），
 * 调用方必须先让用户解开现有门锁（输一次 PIN / 过一次指纹）复得钥匙，再开新锁。
 */
sealed interface LockEnrollResult {
    /** 门锁信封已建；房钥匙已确保在内存（新生成或本就在）。 */
    data object Enrolled : LockEnrollResult

    /** 房钥匙不可得：另一把门锁已存在但未解锁。见类 KDoc 的顺序约束。 */
    data object HouseKeyUnavailable : LockEnrollResult
}

/**
 * 房子钥匙层：两级钥匙层级（定稿 2026-09-28）的中间层与唯一协调者。
 *
 * ```
 * 门锁层（各只有一把，各只有一个信封）
 * ┌──────────────────────┐    ┌──────────────────────┐
 * │ 指纹锁 · Keystore KEK │    │ PIN 锁 · Argon2id    │
 * │ 一次授权一次使用      │    │ 全局 5 次熔断        │
 * └──────────┬───────────┘    └──────────┬───────────┘
 *            └───────────┬───────────────┘
 *                        ▼
 *        ┌───────────────────────────────┐
 *        │ 房子钥匙（随机 256-bit）        │ ← 本类持有的唯一内存状态
 *        │ 明文仅存内存（硬约束 #1）       │
 *        └───────────────┬───────────────┘
 *            ┌───────────┼───────────┐
 *            ▼           ▼           ▼
 *       房间信封①     房间信封②     …（纯软件 AES-GCM，每库一份）
 * ```
 * （另有第三把「从不」档免认证恢复锁，不画进主图：它只在 `vaultTimeout = Never`
 * 时存在，包的也是同一把房钥匙；见 [enrollAutoEnvelope] 与
 * `AutoUnlockKeyStore` 的 KDoc。）
 *
 * ## 硬约束（定稿 §4，逐条对应到代码；#1 含 2026-09-29 修订）
 * 1. **房钥匙绝不以明文落盘** —— 本类落盘的只有「被门锁包过的密文」（指纹 / PIN /
 *    Never 档恢复信封三者之一）与「纯软件封装的房间信封」；
 *    [lock] 是唯一擦除入口，[VaultRepositoryImpl.lockAll] 必须联动调它。
 * 2. 一次生物识别授权 = 一次 Keystore 操作 —— [completeFingerprintUnlock] 是解锁路径上
 *    **唯一**碰 Keystore 的动作；房间循环全是 [openRoom] 软件解密。H2 结构性消失。
 * 3. 登记只 wrap 1 次 —— [enrollFingerprintLock] 只包房钥匙这一个 blob。H1 结构性消失。
 * 4. 锁 = 密钥清零（[lock]）。
 * 5. 门锁失效 → 优雅降级（批次 4 失效矩阵，本批先留 [prepareFingerprintUnlock] 返回
 *    null 的如实信号）。
 * 6. 先校验后包裹 / 明文用完即擦 —— payload 的所有权一律「转移给本类、返回前清零」
 *    （[sealRoom]），解出的明文由调用方负责擦（[RoomOpen.Opened] 的 KDoc）。
 *
 * ## 顺序约束（定稿 §5）——「开第二把锁」必须先解「第一把锁」
 *
 * 房钥匙随机生成一次，此后两把门锁信封包的**必须**是同一把。进程重启后内存钥匙消失，
 * 若此时用户想补开第二把门锁，[obtainKeyForLockEnrollment] 会拒绝（返回 null）：
 * 凭空生成新钥匙会让「PIN 信封包钥匙 A、指纹信封包钥匙 B、房间信封跟着 A」的撕裂
 * 局面无声发生。正确路径是先解现有门锁复得钥匙。这个不变式守在本类，
 * 上层编排错了也只会得到响亮的 [LockEnrollResult.HouseKeyUnavailable]，不会坏数据。
 *
 * ## 为什么放 data:repository（而不是工作单最初设想的与 `LocalUnlockKeyStore` 同层）
 *
 * 本类要同时看到 core:crypto（[PinKeyWrapper] / [VaultixCrypto] / [SecureBytes]）与
 * core:datastore（[SecureCredentialStore] / [LocalUnlockKeyStore]）。core:datastore
 * 的既有单测纪律是「纯 JVM、无 Robolectric」，把它拖进 Android 依赖不划算；
 * data:repository 两边都已依赖，且解锁/登记的编排本就在这里。
 *
 * ## 存储键（全部在 [SecureCredentialStore]，与旧每库信封键不同名、迁移期无冲突）
 *
 * | 键 | 内容 |
 * |---|---|
 * | `house_lock_fingerprint` | KEK 包裹的房钥匙（`iv.b64|cipher.b64`，[LocalUnlockKeyStore.wrap] 产物） |
 * | `house_lock_pin` | Argon2id(PIN) 包裹的房钥匙（`PV1:…`，[PinKeyWrapper.wrap] 产物） |
 * | `house_lock_auto` | **仅 Never 档**：免认证 Keystore 密钥包裹的房钥匙（[AutoUnlockKeyStore] 产物；主动锁库即删） |
 * | `house_pin_attempts` | PIN 全局失败计数（**一份**，定稿 §6：门锁是全局的） |
 * | `house_room::<vaultId>` | 房钥匙纯软件封装的库凭据（`VG1.…`，AAD 绑 vaultId） |
 * | `house_user_locked::<vaultId>` | **每库「用户主动锁」标记**（值 `"1"`；多库锁模型定稿 D1，2026-09-29） |
 */
@Singleton
class HouseKeyStore @Inject constructor(
    private val credentials: SecureCredentialStore,
    private val localUnlockKeyStore: LocalUnlockKeyStore,
    private val pinKeyWrapper: PinKeyWrapper,
    private val crypto: VaultixCrypto,
    /** 「从不」档自动恢复门禁（见 [enrollAutoEnvelope]；与指纹/PIN 门锁平行）。 */
    private val autoUnlockKeyStore: AutoUnlockKeyStore,
) {

    /** 内存里的房子钥匙；`null` = 未解出 / 已锁（硬约束 #1：**明文**仅存内存）。 */
    private var houseKey: SecureBytes? = null

    /** 房钥匙是否在内存（= 门锁开过且尚未 [lock]）。 */
    val isUnlocked: Boolean get() = houseKey != null

    private val _isUnlockedFlow = MutableStateFlow(false)

    /**
     * 房钥匙内存态的可观察版本（「从不」档自动恢复协调用）。
     *
     * ⚠️ 仅供协调器观察，**不要**拿来替代「锁库」动作 —— 它只是 [isUnlocked]
     * 的镜像，写点只有钥匙真实变动处（[adoptHouseKey] / [obtainKeyForLockEnrollment] /
     * [lock]）。
     */
    val isUnlockedFlow: StateFlow<Boolean> = _isUnlockedFlow.asStateFlow()

    /**
     * 锁：房钥匙清零（硬约束 #4）。
     *
     * 门锁一刀切全屋 —— 房间信封仍在盘上，但没了房钥匙谁也解不开；
     * 重新进门 = 再过一次门锁（指纹/PIN）。
     */
    fun lock() {
        houseKey?.zero()
        houseKey = null
        _isUnlockedFlow.value = false
    }

    // ===== 门锁信封存在性（真源；偏好层镜像见 VaultixPreferences）=====

    /**
     * 指纹门锁信封是否存在。
     *
     * ⚠️ 刻意走 [SecureCredentialStore.keysWithPrefix]（只读键名）而不是
     * `getString`：后者要过一次 Keystore 解密，任何**瞬时**异常都会被吞成 null，
     * 把「存在」误报成「不存在」—— 那正是设置页谎报状态的老病根。
     */
    suspend fun hasFingerprintEnvelope(): Boolean = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(FINGERPRINT_ENVELOPE_KEY).isNotEmpty()
    }

    /**
     * 指纹门锁信封**在盘上**，但平台 KEK 已不可用 —— 探测失败，不是「没开锁」。
     *
     * 判据两个条件缺一不可：
     * 1. 信封存在（否则是 [disableFingerprintLock] 之后的常态，不是失效）；
     * 2. `kekStatus == INVALIDATED`（**只认永久失效**：`MISSING`/`UNKNOWN` 都不算 ——
     *    把它们算进来会把「稍后可用」误报成「已废弃」，见 [LocalUnlockKekStatus]）。
     *
     * 与 [hasFingerprintEnvelope] 的分工：那个回答「用户开没开锁」，本方法回答
     * **「锁还在但钥匙废了」**。两者都为 true 时 **绝不能**报「已启用」——
     * 那是设置页谎报状态（#93）；也不能直接当「已关闭」删信封（钥匙丢了不该
     * 顺手砸锁，见 [LocalUnlockKeyStore.newDecryptCipher] 的告诫）。
     */
    suspend fun isFingerprintLockInvalidated(): Boolean = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(FINGERPRINT_ENVELOPE_KEY).isNotEmpty() &&
            localUnlockKeyStore.kekStatus == LocalUnlockKekStatus.INVALIDATED
    }

    /** PIN 门锁信封是否存在（取向同 [hasFingerprintEnvelope]）。 */
    suspend fun hasPinEnvelope(): Boolean = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(PIN_ENVELOPE_KEY).isNotEmpty()
    }

    /** 至少一把门锁信封存在（房间信封的创建前置条件，定稿 §5 顺序约束）。 */
    suspend fun hasAnyLock(): Boolean =
        hasFingerprintEnvelope() || hasPinEnvelope()

    /** 某库的房间信封是否存在。 */
    suspend fun hasRoom(vaultId: String): Boolean = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(roomStorageKey(vaultId)).isNotEmpty()
    }

    /** 建了房间信封的全部库 id（迁移/清理用；`house_room::` 前缀枚举）。 */
    suspend fun roomVaultIds(): List<String> = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(ROOM_ENVELOPE_PREFIX).map { it.removePrefix(ROOM_ENVELOPE_PREFIX) }
    }

    // ===== 指纹门锁（Keystore KEK，auth-per-use）=====

    /**
     * 开指纹门锁：用**本次 BiometricPrompt 授权的** cipher 包裹房钥匙（硬约束 #3：
     * 登记只 wrap 这一个 blob）。
     *
     * @return [LockEnrollResult.Enrolled] = 信封已落盘；
     *   [LockEnrollResult.HouseKeyUnavailable] = 另一把门锁已存在但内存无钥匙
     *   （见类 KDoc 顺序约束——先解现有门锁再开这把）。
     *
     * ⚠️ 不吞 wrap 的异常：它抛错意味着 cipher 不可用（编程/环境错误），
     * 吞成「失败」只会掩盖它（与旧 `commitOne` 同款取向）。
     */
    suspend fun enrollFingerprintLock(cipher: Cipher): LockEnrollResult =
        withContext(Dispatchers.IO) {
            val key = obtainKeyForLockEnrollment()
                ?: return@withContext LockEnrollResult.HouseKeyUnavailable
            val wrapped = key.useBytes { bytes -> localUnlockKeyStore.wrap(cipher, bytes) }
            credentials.putString(FINGERPRINT_ENVELOPE_KEY, wrapped)
            LockEnrollResult.Enrolled
        }

    /**
     * 解指纹门锁第一步：备好**待认证**的解密 cipher（IV 来自信封）。
     * 返回 null = 门锁信封不存在 / KEK 已失效 —— 上层如实提示并回退主密码。
     *
     * ⚠️ 返回的 cipher 必须交给本次 BiometricPrompt，认证通过后立刻
     * [completeFingerprintUnlock]（auth-per-use：一个 cipher 只对一次认证有效）。
     */
    suspend fun prepareFingerprintUnlock(): Cipher? = withContext(Dispatchers.IO) {
        val payload = credentials.getString(FINGERPRINT_ENVELOPE_KEY) ?: return@withContext null
        localUnlockKeyStore.newDecryptCipher(payload)
    }

    /**
     * 解指纹门锁第二步（认证已过）：unwrap 房钥匙进内存。
     *
     * 这是解锁路径上**唯一**的 Keystore 操作（硬约束 #2）——之后所有库都走
     * [openRoom] 纯软件解密，rest 库「现取新 cipher」的路径（H2）结构性不存在。
     *
     * @return false = unwrap 失败（KEK 失效 / 信封损坏）。上层按定稿 §6 降级处理，
     *   **不要**静默重试（auth-per-use 下再取 cipher 也无人授权）。
     */
    suspend fun completeFingerprintUnlock(cipher: Cipher): Boolean =
        withContext(Dispatchers.IO) {
            val payload = credentials.getString(FINGERPRINT_ENVELOPE_KEY)
                ?: return@withContext false
            runCatching { localUnlockKeyStore.unwrap(cipher, payload) }
                .getOrElse { return@withContext false }
                .let { adoptHouseKey(it) }
        }

    /**
     * 关指纹门锁：删信封。
     *
     * 若删完后**一把门锁都不剩**：房间信封全部成孤儿（房钥匙再无恢复途径，进程一死
     * 就永远解不开），本方法就地全部清掉并锁内存钥匙 —— 这个不变式守在这里，
     * 不指望每个调用方都记得。
     */
    suspend fun disableFingerprintLock() {
        withContext(Dispatchers.IO) {
            credentials.remove(FINGERPRINT_ENVELOPE_KEY)
            // 锁都关了，「待重装」自然失去意义（留着会让 UI 显示一个永远不兑现的承诺）。
            credentials.remove(FINGERPRINT_REARM_PENDING_KEY)
        }
        trimRoomsIfNoLocksRemain()
    }

    // ===== PIN 门锁（Argon2id，全局计数）=====

    /**
     * PIN 位数门槛。返回非 null 表示**应直接拒绝**（先验后做昂贵事：
     * 位数都不对的 PIN 没必要白跑一次 Argon2id）。
     */
    fun validatePin(pin: String): PinEnrollOutcome? =
        if (pin.length < PIN_MIN_LENGTH) PinEnrollOutcome.PinTooShort(PIN_MIN_LENGTH) else null

    /**
     * 开 PIN 门锁：Argon2id(PIN) 包裹房钥匙。
     *
     * ⚠️ 覆盖旧信封即「修改 PIN」（每次包裹都换新盐，见 [PinKeyWrapper.wrap]）。
     * 计数与钥匙来源约束同指纹侧。
     */
    suspend fun enrollPinLock(pin: String): LockEnrollResult = withContext(Dispatchers.IO) {
        val key = obtainKeyForLockEnrollment()
            ?: return@withContext LockEnrollResult.HouseKeyUnavailable
        val envelope = key.useBytes { bytes -> pinKeyWrapper.wrap(pin, bytes) }
        credentials.putString(PIN_ENVELOPE_KEY, envelope)
        // 换了新 PIN 不能被上一次的失败计数锁住（与旧 PinUnlockStore.persist 同款）。
        clearPinFailures()
        LockEnrollResult.Enrolled
    }

    /**
     * 修改 PIN：先验 [currentPin]，通过了才用 [newPin] 重新包裹房钥匙。
     *
     * ★ 这是 [enrollPinLock] 的"**验过旧 PIN**"版本。两者**不要**让调用方拆成两步用 ——
     * 拆开就有三种错法：只验旧 PIN 就重包（等于门锁形同虚设）、
     * 只重包不验旧 PIN（换了个陌生人的 PIN 上去，用户当场打不开自己的库）、
     * 验完被中断（房钥匙在内存而信封没动，状态说不清）。
     *
     * **顺序本身就是安全边界**：[openWithPin] 通过 ⇒ 房钥匙进内存，才有资格重包；
     * 不通过 ⇒ **一个字节都不写**。
     *
     * ⚠️ 新 PIN 的位数校验**先于**验旧 PIN，不是随手排的：位数判定是 O(1)，
     * 验旧 PIN 要跑 Argon2id（64MiB 级内存硬）。顺序反过来，
     * 一个手滑把新 PIN 打短了的用户会**白白消耗一次失败计数** ——
     * 计数是防暴力的，不该为"新 PIN 长度不合法"买单。
     *
     * ⚠️ 重包**就是**改 PIN，不需要任何"改标记"动作：[PinKeyWrapper.wrap] 每次生成新盐，
     * 覆盖写入即生效（与 [enrollPinLock] 同一套机制，见其 KDoc）。
     *
     * ⚠️ [clearPinFailures] 由 [enrollPinLock] 内部一并做了 ⇒ 换 PIN 顺带解除熔断。
     * 这是对的：新 PIN 已经过一次完整验证，再让上一次的输错记数挂在它头上没有道理。
     */
    suspend fun changePinLock(
        currentPin: String,
        newPin: String,
    ): PinChangeOutcome = withContext(Dispatchers.IO) {
        validatePin(newPin)?.let { outcome ->
            val minimum = (outcome as? PinEnrollOutcome.PinTooShort)?.minimum ?: PIN_MIN_LENGTH
            return@withContext PinChangeOutcome.PinTooShort(minimum)
        }
        when (val opened = openWithPin(currentPin)) {
            PinOpen.Opened -> {
                val enrolled = enrollPinLock(newPin)
                if (enrolled == LockEnrollResult.Enrolled) {
                    PinChangeOutcome.Changed
                } else {
                    // ⚠️ 理论不可达（openWithPin 已把房钥匙送进内存，enroll 的前置必然满足）。
                    // 但仍然如实返回，不留空：「静默没换成」会被用户下次解锁才发现 ——
                    // 那是「假成功」里最坏的一种（闹到要用主密码，却没人告诉他 PIN 早变了）。
                    PinChangeOutcome.Unavailable("PIN 门锁重新包裹失败，请重试")
                }
            }
            is PinOpen.WrongPin -> PinChangeOutcome.WrongPin(opened.remainingAttempts)
            PinOpen.LockedOut -> PinChangeOutcome.LockedOut
            is PinOpen.Unavailable -> PinChangeOutcome.Unavailable(opened.detail)
        }
    }

    /**
     * 用 PIN 解门锁（含全局计数）。
     *
     * 计数规则：**只有 PIN 不对才计数**；信封损坏是数据问题、不是用户输错
     * （让它把用户锁在门外是错的）。全局一份 5 次（定稿 §6）。
     *
     * PIN 验证通过即清零计数：计数器防的是**不知道 PIN 的人**暴力枚举，
     * 验证成功已证明不是暴力 —— 门锁开没开与房间凭据好坏无关（全局门锁模型），
     * 不必等「各库真开出来」才清（旧每库信封时代的告诫在此模型下不适用）。
     */
    suspend fun openWithPin(pin: String): PinOpen = withContext(Dispatchers.IO) {
        val envelope = credentials.getString(PIN_ENVELOPE_KEY)
            ?: return@withContext PinOpen.Unavailable("未启用 PIN 解锁")
        if (pinAttempts() >= PIN_MAX_ATTEMPTS) return@withContext PinOpen.LockedOut
        when (val result = pinKeyWrapper.unwrap(pin, envelope)) {
            is PinUnwrapResult.Opened ->
                if (adoptHouseKey(result.payload)) {
                    clearPinFailures()
                    PinOpen.Opened
                } else {
                    PinOpen.Unavailable("PIN 门锁信封损坏")
                }
            PinUnwrapResult.WrongPin -> registerPinFailure()
            is PinUnwrapResult.Malformed -> PinOpen.Unavailable(result.detail)
        }
    }

    /** 关 PIN 门锁（同 [disableFingerprintLock] 的孤儿清理逻辑）。 */
    suspend fun disablePinLock() {
        withContext(Dispatchers.IO) {
            credentials.remove(PIN_ENVELOPE_KEY)
            credentials.remove(PIN_ATTEMPTS_KEY)
        }
        trimRoomsIfNoLocksRemain()
    }

    /**
     * 清零 PIN 失败计数。
     *
     * [openWithPin] 验证通过时已自动清零；本方法供 [enrollPinLock]（换新 PIN
     * 不能被上一次的失败计数锁住）与未来需要手动重置的场景使用。
     */
    suspend fun clearPinFailures() {
        withContext(Dispatchers.IO) { credentials.remove(PIN_ATTEMPTS_KEY) }
    }

    // ===== 「从不」档自动恢复信封（第三把锁，免认证，对齐 Bitwarden autoUnlockKey）=====

    /**
     * 自动恢复信封是否存在。
     *
     * 取向同 [hasFingerprintEnvelope]：只读键名，不过 Keystore 解密。
     */
    suspend fun hasAutoEnvelope(): Boolean = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(AUTO_ENVELOPE_KEY).isNotEmpty()
    }

    /**
     * 写自动恢复信封：用免认证 Keystore 密钥包裹房钥匙落盘。
     *
     * **幂等**：信封已存在则跳过（Bitwarden `storeUserAutoUnlockKeyIfNecessary`
     * 同款 —— 房钥匙在门锁存续期内恒定，旧信封包的就是同一把钥匙，重写无益）。
     *
     * @return false = 房钥匙不在内存（无法包裹）。调用方（协调器）靠
     *   [isUnlockedFlow] 变化重试，不在这里轮询。
     */
    suspend fun enrollAutoEnvelope(): Boolean = withContext(Dispatchers.IO) {
        if (credentials.keysWithPrefix(AUTO_ENVELOPE_KEY).isNotEmpty()) return@withContext true
        val key = houseKey ?: return@withContext false
        // useBytes 非 inline，判空必须在 lambda 外做（return@withContext 进不去）。
        val wrapped = key.useBytes { bytes -> autoUnlockKeyStore.encrypt(bytes) }
        if (wrapped == null) return@withContext false
        credentials.putString(AUTO_ENVELOPE_KEY, wrapped)
        true
    }

    /**
     * 解自动恢复信封：房钥匙回内存（进程死亡后的自动恢复第一步）。
     *
     * @return false = 信封不存在 / 不可解（密钥失效或信封损坏 —— 后者就地删除，
     *   避免每次启动白跑一次注定失败的解密）。失败不上抛：协调器按「回主密码」降级。
     */
    suspend fun openAutoEnvelope(): Boolean = withContext(Dispatchers.IO) {
        val payload = credentials.getString(AUTO_ENVELOPE_KEY)
            ?: return@withContext false
        val plain = autoUnlockKeyStore.decrypt(payload)
        if (plain == null) {
            credentials.remove(AUTO_ENVELOPE_KEY)
            return@withContext false
        }
        adoptHouseKey(plain)
    }

    /**
     * 删自动恢复信封（**不删 Keystore 密钥** —— 密钥无害且可复用）。
     *
     * 调用点：① 主动锁库（lockVault/lockAll —— 用户表达「锁」的意图，自动恢复
     * 让位，对齐 Bitwarden `setVaultToLocked` 清 autoUnlockKey）；② 档位离开
     * Never（协调器）；③ 门锁全删（[trimRoomsIfNoLocksRemain]）。
     */
    suspend fun removeAutoEnvelope() {
        withContext(Dispatchers.IO) { credentials.remove(AUTO_ENVELOPE_KEY) }
    }

    // ===== 每库「用户主动锁」标记（多库锁模型定稿 D1，2026-09-29）=====

    /**
     * 标记「这个库是**用户主动锁**的」（[VaultRepositoryImpl.lockVault] 写）。
     *
     * ## 为什么需要这个状态（而不是继续用「删自动恢复信封」表达锁的意图）
     *
     * 旧实现：锁单库 ⇒ 删掉**全局**的 `house_lock_auto` 信封。删的**本意是对的**
     * （不删则下次冷启动会把刚锁的库悄悄开回来，「锁定」被静默撤销），
     * 但**粒度错了** —— 那个信封包的是**全局房钥匙**，用一次单库动作删它，
     * 会连带掐掉**其它库**的冷启动恢复能力（多库锁模型定稿 §4 第 2 条 / #131）。
     *
     * ⇒ 改成**每库**一份持久标记：`autoRestore` 逐库判定时跳过被标记的库
     * （见 [AutoUnlockRepositoryImpl.restore]）。于是两件事同时成立：
     * - 锁 A **不影响** B 的恢复能力；
     * - A 也**不会**在冷启动被悄悄开回来。
     *
     * 清除时机：该库**重新解锁成功** —— 由 `AutoRestoreTrigger` 的解锁成功钩子
     * 统一调 [AutoUnlockRepository.clearUserLock]，避免在 5 条解锁路径上各写一遍。
     */
    suspend fun markUserLocked(vaultId: String) {
        withContext(Dispatchers.IO) { credentials.putString(userLockedKey(vaultId), "1") }
    }

    /** 解除「用户主动锁」标记（该库重新解锁成功时调用）。幂等。 */
    suspend fun clearUserLocked(vaultId: String) {
        withContext(Dispatchers.IO) { credentials.remove(userLockedKey(vaultId)) }
    }

    /** 该库当前是否处于「用户主动锁」态。 */
    suspend fun isUserLocked(vaultId: String): Boolean = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(userLockedKey(vaultId)).isNotEmpty()
    }

    /** 处于「用户主动锁」态的全部库 id（`autoRestore` 的跳过名单）。 */
    suspend fun userLockedVaultIds(): Set<String> = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(USER_LOCKED_PREFIX)
            .map { it.removePrefix(USER_LOCKED_PREFIX) }
            .toSet()
    }

    private fun userLockedKey(vaultId: String) = USER_LOCKED_PREFIX + vaultId

    // ===== 房间信封（每库一份，纯软件）=====

    /**
     * 建房间信封：用房钥匙 AES-GCM 封装该库凭据。
     *
     * ⚠️ **[payload] 的所有权转移给本方法**，返回前清零（`finally`）——
     * 明文副本越少，能被内存转储捞到的窗口越小。
     * ⚠️ 调用前置：[isUnlocked] 必须为 true（至少一把门锁已解）。锁着调用 = 编排 bug，
     * 抛 [IllegalStateException] 响亮失败，好过静默写出一份谁也解不开的信封。
     *
     * @param payload Bitwarden = 64B `enc‖mac` full key；KDBX = `KdbxUnlockPayload.encode`。
     */
    suspend fun sealRoom(vaultId: String, payload: ByteArray) = withContext(Dispatchers.IO) {
        val key = houseKey
            ?: error("house key not in memory — a lock must be opened before sealing rooms")
        try {
            val sealed = crypto.encryptGcm(payload, key, aad = roomAad(vaultId))
            credentials.putString(roomStorageKey(vaultId), sealed.encode())
        } finally {
            payload.fill(0)
        }
    }

    /**
     * 开房间信封：软件解密取回该库凭据（不碰任何门锁 / Keystore）。
     *
     * ⚠️ [RoomOpen.Opened.payload] 的所有权转移给调用方，用完必须 `fill(0)`。
     */
    suspend fun openRoom(vaultId: String): RoomOpen = withContext(Dispatchers.IO) {
        val key = houseKey ?: return@withContext RoomOpen.NoKey
        val raw = credentials.getString(roomStorageKey(vaultId))
            ?: return@withContext RoomOpen.NotEnrolled
        val sealed = runCatching { GcmSealed.decode(raw) }
            .getOrElse { return@withContext RoomOpen.Damaged("房间信封格式损坏") }
        runCatching { crypto.decryptGcm(sealed, key, aad = roomAad(vaultId)) }
            .fold(
                onSuccess = { RoomOpen.Opened(it) },
                onFailure = { RoomOpen.Damaged("房间信封校验失败（密文被改或钥不对）") },
            )
    }

    /** 删某库的房间信封（取消勾选该库）。幂等。 */
    suspend fun removeRoom(vaultId: String) {
        withContext(Dispatchers.IO) { credentials.remove(roomStorageKey(vaultId)) }
    }

    // ===== 内部 =====

    /**
     * 开门锁时的钥匙来源（顺序约束的守门人）：
     * 内存有 → 复用（两把门锁包同一把钥匙）；
     * 内存无且已有门锁信封 → 拒绝（先解现有门锁复得钥匙）；
     * 内存无且无任何门锁 → 生成新钥匙（首启「开第一把锁」）。
     */
    private suspend fun obtainKeyForLockEnrollment(): SecureBytes? {
        houseKey?.let { return it }
        if (hasAnyLock()) return null
        return SecureBytes.random(HOUSE_KEY_BYTES).also {
            houseKey = it
            _isUnlockedFlow.value = true
        }
    }

    /**
     * 把解出的明文收编为房钥匙。
     *
     * @return false = 长度不是 32 字节（信封里躺的不是房钥匙 —— 门锁信封损坏），
     *   此时明文就地清零、内存钥匙保持原状（半路换钥匙比没有钥匙更糟）。
     */
    private fun adoptHouseKey(bytes: ByteArray): Boolean {
        if (bytes.size != HOUSE_KEY_BYTES) {
            bytes.fill(0)
            return false
        }
        houseKey = SecureBytes.of(bytes, wipeSource = true)
        _isUnlockedFlow.value = true
        return true
    }

    /**
     * 最后一把门锁没了 ⇒ 房间信封全清 + 锁内存（否则进程一死全是永远解不开的孤儿）。
     *
     * 自动恢复信封一并删：它包的也是房钥匙，且恢复依赖房间信封 —— 门锁全删后
     * 每次启动都会「解出钥匙 → 无房可开」白跑。快速解锁体系整体下线，恢复基建
     * 没有单独存活的理由。
     */
    private suspend fun trimRoomsIfNoLocksRemain() {
        if (hasAnyLock()) return
        withContext(Dispatchers.IO) {
            credentials.keysWithPrefix(ROOM_ENVELOPE_PREFIX).forEach { credentials.remove(it) }
            credentials.remove(AUTO_ENVELOPE_KEY)
        }
        lock()
    }

    private fun pinAttempts(): Int =
        credentials.getString(PIN_ATTEMPTS_KEY)?.toIntOrNull() ?: 0

    private fun registerPinFailure(): PinOpen {
        val attempts = pinAttempts() + 1
        credentials.putString(PIN_ATTEMPTS_KEY, attempts.toString())
        return if (attempts >= PIN_MAX_ATTEMPTS) {
            PinOpen.LockedOut
        } else {
            PinOpen.WrongPin(remainingAttempts = PIN_MAX_ATTEMPTS - attempts)
        }
    }

    private fun roomStorageKey(vaultId: String) = ROOM_ENVELOPE_PREFIX + vaultId

    // ===== 失效矩阵（定稿 §6；批次 4，2026-09-29）=====
    // 失效信号 [isFingerprintLockInvalidated] 定义在「门锁信封存在性」一组里
    // （它本质是那个问题的第三种答案）；本区放重装动作与标记。

    /**
     * 重装指纹门锁信封：用**本次认证的** cipher 把内存房钥匙重新包一遍。
     *
     * ## 为什么这不是"静默"的（定稿 §6 的措辞在此收窄）
     *
     * auth-per-use（硬约束 #2）下重包**必须**再弹一次 BiometricPrompt ——
     * 平台契约如此，不存在真正的静默重装。定稿说的"静默"指的是**用户无需
     * 重新走一遍登记向导**（不用重新勾库、不用重输主密码）。
     *
     * ⇒ 调用时机 = 用户下一次在设置向导里过指纹（那时天然有一个已授权的
     * `newEncryptCipher()`）；而不是失效当场（当场没有已授权的 cipher）。
     *
     * @return false = 房钥匙不在内存（无从重包）/ cipher 不可用。
     */
    suspend fun rearmFingerprintLock(cipher: Cipher): Boolean = withContext(Dispatchers.IO) {
        val key = houseKey ?: return@withContext false
        val wrapped = runCatching { key.useBytes { bytes -> localUnlockKeyStore.wrap(cipher, bytes) } }
            .getOrElse { return@withContext false }
        credentials.putString(FINGERPRINT_ENVELOPE_KEY, wrapped)
        // 重装成功 ⇒ 待重装标记作废（幂等：本来就没有也不报错）。
        credentials.remove(FINGERPRINT_REARM_PENDING_KEY)
        true
    }

    /**
     * 是否有「指纹门锁待重装」标记。
     *
     * 语义 = 「这把锁的信封已经解不开了（凭据变更 / 密文不匹配），但房钥匙还在手上，
     * 等下一次认证就能重装」。**不含**「钥匙也丢了」的情形（那时是降级，不是重装）。
     */
    suspend fun hasFingerprintRearmPending(): Boolean = withContext(Dispatchers.IO) {
        credentials.keysWithPrefix(FINGERPRINT_REARM_PENDING_KEY).isNotEmpty()
    }

    /** 标记「指纹门锁待重装」（幂等；开门态检测到可重装失效时写）。 */
    suspend fun markFingerprintRearmPending() {
        withContext(Dispatchers.IO) { credentials.putString(FINGERPRINT_REARM_PENDING_KEY, "1") }
    }

    /**
     * 清除待重装标记（用户主动关掉指纹锁 / 降级时调用 —— 两处都不该留下
     * 「等会儿会自动重装」的假预期）。
     */
    suspend fun clearFingerprintRearmPending() {
        withContext(Dispatchers.IO) { credentials.remove(FINGERPRINT_REARM_PENDING_KEY) }
    }

    /** 房间信封的 AAD：绑死 vaultId，防「A 库的信封被塞进 B 库的槽位」这类错位。 */
    private fun roomAad(vaultId: String): ByteArray =
        (ROOM_AAD_PREFIX + vaultId).toByteArray(Charsets.UTF_8)

    private companion object {
        /** 房钥匙长度（AES-256）。 */
        const val HOUSE_KEY_BYTES = 32

        /** 指纹门锁信封（KEK 包裹的房钥匙）。 */
        const val FINGERPRINT_ENVELOPE_KEY = "house_lock_fingerprint"

        /** PIN 门锁信封（Argon2id 包裹的房钥匙）。 */
        const val PIN_ENVELOPE_KEY = "house_lock_pin"

        /** PIN 全局失败计数（定稿 §6：门锁是全局的，计数也是）。 */
        const val PIN_ATTEMPTS_KEY = "house_pin_attempts"

        /**
         * 「指纹门锁待重装」标记（失效矩阵，定稿 §6）。
         *
         * 值为 `"1"`；存在即表示「信封已解不开但房钥匙还在手上，等下次认证重装」。
         * 放 `SecureCredentialStore` 而同层于信封：它是**安全状态**不是用户偏好，
         * 且要与信封同生共死（清偏好不该动它）。
         */
        const val FINGERPRINT_REARM_PENDING_KEY = "house_lock_fingerprint_rearm_pending"

        /**
         * 「从不」档自动恢复信封（免认证 Keystore 密钥包裹的房钥匙）。
         * 生命周期由 vaultTimeout 档位驱动（见 [enrollAutoEnvelope]），
         * 与指纹/PIN 两把门锁平行。
         */
        const val AUTO_ENVELOPE_KEY = "house_lock_auto"

        /** 房间信封键前缀。 */
        const val ROOM_ENVELOPE_PREFIX = "house_room::"

        /**
         * 「每库用户主动锁」标记前缀（多库锁模型定稿 D1，2026-09-29）。
         *
         * 键 = `house_user_locked::<vaultId>`，值 `"1"`。放 [SecureCredentialStore]
         * 而与房间信封同层：它是**安全状态**不是用户偏好，要与信封同生共死
         * （清偏好不该动它）。
         */
        const val USER_LOCKED_PREFIX = "house_user_locked::"

        /** 房间信封 AAD 前缀（再拼 vaultId）。 */
        const val ROOM_AAD_PREFIX = "vaultix-room-v1:"
    }
}
