/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxSource
import io.vaultix.datastore.LocalUnlockKeyStore
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.database.dao.VaultDao
import io.vaultix.domain.KdbxEnrollOutcome
import io.vaultix.domain.LocalUnlockEnrollOutcome
import io.vaultix.domain.LocalUnlockPrepareOutcome
import io.vaultix.domain.LocalUnlockPreparedEnrollment
import io.vaultix.domain.VaultKind
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 「本地快速解锁（生物识别）」登记的**备料与落盘**（2026-09-16 新增）。
 *
 * ## 为什么单独一个类
 *
 * 本次要支持「一次勾选多个库、一次指纹全部启用」，而 [VaultRepositoryImpl] 的函数数
 * 实测**正好卡在 40**（detekt `TooManyFunctions` 上限，顶格）—— 任何新增方法都会爆。
 * 但更实际的理由与 [PinEnrollment] 当初抽出时完全一样：**职责**。
 * 「这一个库往信封里包什么明文」是独立关注点，本就该自成一处。
 *
 * ⚠️ **下一个再往里加解锁手段时，同样要提取，而不是继续堆回 [VaultRepositoryImpl]。**
 * （同 [PinUnlockStore] / [PinEnrollmentCoordinator] / [PinEnrollment] 抽出去时留的告诫。）
 *
 * ## 为什么不能复用单库路径（关键）
 *
 * 单库路径的暂存位是 [VaultRepositoryImpl] 里的 `stagedKdbxPayload` —— 那是
 * **一个 `ByteArray?`，不按库分**。一次勾选多个 KDBX 库时，后一个库的明文会
 * **覆盖**前一个，于是前一个库的信封里躺的是别人的密码，用户要到解锁时才发现打不开。
 * 本类因此**完全绕开那个单槽**：备料结果由调用方（控制器）自己持有一份列表，
 * 认证通过后逐个落盘。与 [PinEnrollment.enrollKdbx] 绕开暂存槽是同一个思路。
 *
 * ## 两段式是硬约束，不是风格选择（本类最重要的一条）
 *
 * 快解的保护器 KEK 是 **auth-per-use**（`setUserAuthenticationParameters(0, …)`），
 * 只有**被 BiometricPrompt 授权过的那一个 `Cipher` 实例**才能 `doFinal`。所以：
 *
 * 1. **认证前**只能做不需要 KEK 的事 —— 校验凭据、把要包的明文读出来备好；
 * 2. **认证后**用**同一个** cipher 连续把所有库 wrap 完。
 *
 * ⚠️ **绝不能"优化"成提前为每个库各准备一个 cipher**：那些 cipher 没有被授权，
 * 第二个库就会抛 `UserNotAuthenticatedException`。这正是 2026-09-14 那次闪退的根因
 * （当时是 `wrap` 跑在弹指纹之前），别绕回去。
 *
 * ## Bitwarden 与 KDBX 备料的东西不同（因此路径也不同）
 *
 * | 库类型 | 信封里包什么 | 备料时机 |
 * | --- | --- | --- |
 * | Bitwarden | 会话里的对称密钥（64B full key） | **认证后**取（认证前只验"会话在"） |
 * | KDBX | 主密码 + keyfile 字节 | **认证前**就要读出来（会话里没有主密码） |
 *
 * Bitwarden 侧之所以把取密钥留到认证后：密钥本来就在内存会话里，取出即用最省事，
 * 也顺带让"认证窗口里用户把库锁了"这种情况能被如实报成 [LocalUnlockEnrollOutcome.Failed]。
 */
@Singleton
class LocalUnlockEnrollment @Inject constructor(
    /**
     * 内存里的对称密钥（Bitwarden 侧要包的就是它）。
     *
     * ⚠️ 只依赖会话管理器，**不依赖 [VaultRepositoryImpl]** —— 反过来会让两者成环。
     * 这也是 KDBX 校验走 [Kdbx.verify] 独立入口而不是复用仓储 `unlockKdbxInternal` 的原因。
     */
    private val sessions: VaultSessionManager,
    private val credentials: SecureCredentialStore,
    private val localUnlockKeyStore: LocalUnlockKeyStore,
    private val vaultDao: VaultDao,
    private val preferences: VaultixPreferences,
    @ApplicationContext context: Context,
) {

    /**
     * KDBX 文件读取器（SAF `content://` URI → 字节）。
     *
     * 与 [VaultRepositoryImpl] / [PinEnrollment] 里那两份是**同一个 lambda 的复制**。
     * 刻意不复用：那些是各自的私有字段，为了共享而把它提升成公开 API，
     * 等于把"仓储怎么读文件"变成对外契约，比重复这三行更贵。
     */
    private val kdbxSource = KdbxSource { uri ->
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        }.getOrNull()
    }

    /**
     * 一个库「认证通过后要包进信封」的备料。
     *
     * 实现 [LocalUnlockPreparedEnrollment]（域层接口）而不是自带一套公开属性：
     * 这样控制器只依赖 `domain` 的类型，不必反向依赖 `data.repository` ——
     * 后者会让 `domain` 与 `data` 之间出现环。
     *
     * 实现 [AutoCloseable] 是为了**明文用完即擦**：KDBX 这份里躺着主密码，
     * 没理由让它活到 GC。调用方必须保证 `close()` 一定被调到（用 `use { }` 或 finally）。
     */
    class Prepared internal constructor(
        override val vaultId: String,
        override val displayName: String,
        val kind: VaultKind,
        /**
         * 待包裹的明文。
         *
         * - KDBX：`KdbxUnlockPayload.encode(...)` 的结果（认证前已备好）；
         * - Bitwarden：**null** —— 它的明文是会话里的对称密钥，认证后才取。
         */
        internal val plaintext: ByteArray?,
    ) : LocalUnlockPreparedEnrollment {
        override val requiresPostAuthPlaintext: Boolean get() = plaintext == null

        override fun close() {
            plaintext?.fill(0)
        }
    }

    /**
     * **单库路径**的暂存凭据（主密码 + keyfile 的编码）。
     *
     * ⚠️ 与多库路径的 `Prepared` 列表是**两套东西**，别合并：多库路径的备料由调用方
     * （控制器）自己持有，走的是"一次认证连续 wrap 完一批"；单库路径由本类持有，
     * 因为它的调用方是 [VaultRepositoryImpl] 的旧接口，没有地方放这份列表。
     *
     * ⚠️ 这是**单槽**（一个 `ByteArray?`，不按库分）。多库场景**绝不能**用它 ——
     * 后一个库会覆盖前一个，导致前者的信封里躺着别人的密码。
     * 多库一律走 [prepareForVaults] + [commitForVaults]。
     *
     * 生命周期（每个出口都要收尾，否则明文留在内存）：
     * 写入 [prepareKdbxEnroll]（写前先擦）／消费 [commitKdbxEnroll]（成败都擦）／
     * 丢弃 [discardKdbxEnroll]、[VaultRepositoryImpl.disableLocalUnlock]。
     */
    private var stagedSinglePayload: ByteArray? = null

    /** 关闭某库的快速解锁时顺手丢弃单库暂存（用户可能在弹指纹前就关掉了开关）。 */
    fun discardStagedPayload() {
        stagedSinglePayload?.fill(0)
        stagedSinglePayload = null
    }

    /**
     * **认证之前**：逐库校验凭据 + 备料。
     *
     * 逐个库独立判定（`associate`），**一个库失败不影响其它库** —— 这是刻意保留的
     * 真实状态：用户可能勾了 3 个库、其中 1 个密码不对，另外 2 个没有理由不生效。
     *
     * KDBX 校验走 [Kdbx.verify]（**只验不开库**、不碰 `KdbxSessionStore`）：
     * 用 `unlock()` 验会把明文拉进内存并**覆盖该库已有会话**，多库时更会互相打架。
     *
     * @param masterPassword 用户为**全部**勾选的 KDBX 库输入的那一个主密码
     *   （与 PIN 侧「输一次、共用」的交互一致）。勾选里没有 KDBX 时不会被用到。
     */
    suspend fun prepareForVaults(
        vaultIds: List<String>,
        masterPassword: String,
    ): Map<String, LocalUnlockPrepareOutcome> = withContext(Dispatchers.IO) {
        val hasMasterPassword = masterPassword.isNotBlank()
        vaultIds.associateWith { vaultId -> prepareOne(vaultId, masterPassword, hasMasterPassword) }
    }

    /** 单个库的认证前备料（把 [prepareForVaults] 的 `when` 摊平成一行调用）。 */
    private suspend fun prepareOne(
        vaultId: String,
        masterPassword: String,
        hasMasterPassword: Boolean,
    ): LocalUnlockPrepareOutcome {
        val row = vaultDao.get(vaultId)
            ?: return LocalUnlockPrepareOutcome.Failed("本地不存在该库")
        return when (VaultKind.fromName(row.kind)) {
            VaultKind.BITWARDEN -> {
                // 认证前只确认"库还在会话里"。真正的密钥留到认证后取（见类 KDoc）。
                if (sessions.keyOf(vaultId) == null) {
                    LocalUnlockPrepareOutcome.Failed("该库未解锁，请先用主密码打开它")
                } else {
                    LocalUnlockPrepareOutcome.Ready(
                        Prepared(vaultId, row.displayName, VaultKind.BITWARDEN, null),
                    )
                }
            }
            VaultKind.KDBX -> {
                if (!hasMasterPassword) {
                    return LocalUnlockPrepareOutcome.Failed("需要该库的主密码才能启用")
                }
                prepareKdbx(row.id, row.displayName, row.origin, masterPassword)
            }
            null -> LocalUnlockPrepareOutcome.Failed("无法识别该库类型")
        }
    }

    /** KDBX 的认证前备料：先校验，过了才把「主密码 + keyfile」组装好暂放在返回值里。 */
    private suspend fun prepareKdbx(
        vaultId: String,
        displayName: String,
        originUri: String,
        masterPassword: String,
    ): LocalUnlockPrepareOutcome {
        val keyFileUri = keyFileUriOf(vaultId)
        // ★ 先校验、后组装。`wrap` 只负责封字节、不管字节对不对：先包后校会得到
        //   「启用成功、但躺的是错密码」，用户要到解锁时才发现打不开。
        val verified = Kdbx.verify(
            sourceUri = originUri,
            password = masterPassword,
            keyFileUri = keyFileUri,
            source = kdbxSource,
        )
        if (!verified) {
            // 校验不过有两种可能：密码错，或文件读不到。分辨它们对用户很重要 ——
            // 前者该重输，后者该重新选文件（`KdbxSource.read` 读不到会返回 null）。
            val readable = kdbxSource.read(originUri) != null
            return if (readable) {
                LocalUnlockPrepareOutcome.InvalidCredentials
            } else {
                LocalUnlockPrepareOutcome.SourceUnavailable("读不到该库文件，请重新选择")
            }
        }
        val keyFileBytes = keyFileUri
            ?.takeIf { it.isNotBlank() }
            ?.let { uri -> runCatching { kdbxSource.read(uri) }.getOrNull() }
        // ⚠️ keyfile 读不出来时**拒绝**而不是降级成"仅主密码"：keyfile 不是可选装饰，
        //    少它一个字节就是开不了。静默降级会让信封里躺一组永远解不开的凭据，
        //    而用户只会看到"指纹不对"，无从得知真因。（与 `PinEnrollment.enrollKdbx` 同款取向）
        return LocalUnlockPrepareOutcome.Ready(
            Prepared(
                vaultId = vaultId,
                displayName = displayName,
                kind = VaultKind.KDBX,
                plaintext = KdbxUnlockPayload.encode(masterPassword, keyFileBytes),
            ),
        )
    }

    /**
     * **认证之后**：用**本次认证的** cipher 逐库 wrap 并落盘。
     *
     * ⚠️ [cipher] 必须来自本次 BiometricPrompt 授权。KEK 是 auth-per-use，
     * 一个 cipher 只对一次认证有效 —— 所以这里是**连续** wrap，不是"每库一个 cipher"。
     *
     * 逐库独立：某个库 wrap 失败（KEK 被指纹变更永久失效等）只回退**该库**，
     * 绝不牵连其它库 —— 那会静默丢掉用户已经配好的部分。
     *
     * ⚠️ 无论成败，每个 [Prepared] 的明文都会被擦掉（`close()`）。
     */
    suspend fun commitForVaults(
        prepared: List<LocalUnlockPreparedEnrollment>,
        cipher: Cipher,
    ): Map<String, LocalUnlockEnrollOutcome> = withContext(Dispatchers.IO) {
        prepared.associate { unit ->
            val outcome = try {
                // 签名用域层接口（控制器只见 domain），但实现只可能是本类的 Prepared
                // —— 参数量与类型都由 `prepareForVaults` 决定，中间没有别的构造入口。
                // 用 `when` 而不是 `as`：万一将来有第二个实现，这里会**编译期**提醒
                // 需要处理，而不是在运行时抛 ClassCastException。
                when (unit) {
                    is Prepared -> commitOneSafely(unit, cipher)
                }
            } finally {
                // 明文凭据用完即擦（KDBX 这份含主密码字节）。
                unit.close()
            }
            unit.vaultId to outcome
        }
    }

    /** 单个库的认证后落盘。 */
    private suspend fun commitOne(unit: Prepared, cipher: Cipher): LocalUnlockEnrollOutcome {
        val plaintext = unit.plaintext ?: bitwardenPlaintext(unit.vaultId)
        // 会话没了（用户在认证窗口里锁了库）⇒ 如实报，不要拿个空信封糊过去。
        ?: return LocalUnlockEnrollOutcome.Failed("该库未解锁，请先用主密码打开它")
        return try {
            // ⚠️ 不吞异常（与 `enrollLocalUnlock` 同款取向）：`wrap` 抛错意味着这个
            //    cipher 根本用不了（认证已过但 Cipher 状态错），那是编程/环境错误、
            //    不是用户输入问题。吞成"启用失败"只会掩盖它。
            val wrapped = localUnlockKeyStore.wrap(cipher, plaintext)
            credentials.putString(localUnlockStorageKey(unit.vaultId), wrapped)
            preferences.setLocalUnlockEnabled(unit.vaultId, true)
            LocalUnlockEnrollOutcome.Enrolled
        } finally {
            // KDBX 的明文本就在 `unit.plaintext` 里、由 `close()` 统一擦；
            // 这里只管 Bitwarden 那条临时取出来的会话密钥副本。
            if (unit.plaintext == null) plaintext.fill(0)
        }
    }

    /**
     * [commitOne] 的失败归类：**只有**真正属于「该库登记失败」的异常才吞，
     * 其余一律重新抛出。
     *
     * ## 为什么不是直接 `catch (error: Exception)`
     *
     * 两难：`Keystore` 失效抛的是 `KeyPermanentlyInvalidatedException` /
     * `UnrecoverableKeyException`（都是 **`GeneralSecurityException` 的子类**），
     * 但 `wrap` 还可能抛别的 `GeneralSecurityException`（例如算法不可用），
     * 那些不该被当成"用户配置坏了"。而写 `catch (error: Exception)` 会被
     * detekt `TooGenericExceptionCaught` 拦下（CI 门禁，见 `config/detekt/detekt.yml`）。
     *
     * ⇒ 用项目既有的 `runCatching { }.getOrElse { }` 形态（detekt 该规则只看 `catch`
     *   子句，不看 `getOrElse`），与 [VaultRepositoryImpl.completeLocalUnlock] 完全一致。
     *   这不是绕过规则：归类逻辑确实需要看**任意**异常（`isLocalUnlockUnrecoverable`
     *   本身就是沿 cause 链找特定类型），先接住再判定是对的。
     */
    private suspend fun commitOneSafely(unit: Prepared, cipher: Cipher): LocalUnlockEnrollOutcome =
        runCatching { commitOne(unit, cipher) }.getOrElse { error ->
            // KEK 永久失效（用户新增/删除指纹）时会走到这里。若只吞掉异常而不清状态，
            // 开关仍是 enabled、payload 仍在 ⇒ 设置页显示"已启用"但每次点都失败，
            // 且无法自愈（见 `VaultRepositoryImpl.clearBrokenLocalUnlock` 的 KDoc）。
            // ⚠️ 只清**这一个**库，绝不牵连其它库 —— 那会静默丢掉用户已配好的部分。
            if (error.isLocalUnlockUnrecoverable()) {
                clearBrokenEnrollment(unit.vaultId)
                LocalUnlockEnrollOutcome.Failed("本地解锁已失效（可能因指纹变更），请重新启用")
            } else {
                LocalUnlockEnrollOutcome.Failed(error.message ?: "启用失败")
            }
        }

    /** Bitwarden 侧的待包明文：会话里的对称密钥 → 64B full key。 */
    private fun bitwardenPlaintext(vaultId: String): ByteArray? =
        sessions.keyOf(vaultId)?.let { buildFullKey(it) }

    /** 把单个库清回「未启用」（payload 与开关一起清，避免"显示已启用但永远失败"）。 */
    private suspend fun clearBrokenEnrollment(vaultId: String) {
        runCatching {
            credentials.remove(localUnlockStorageKey(vaultId))
            preferences.setLocalUnlockEnabled(vaultId, false)
        }
    }

    /** 该库的 keyfile URI（按库独立存于偏好；读失败当作"没配 keyfile"）。 */
    private suspend fun keyFileUriOf(vaultId: String): String? =
        runCatching { preferences.kdbxKeyFileUri(vaultId).first() }.getOrNull()

    // ===== 单库路径（2026-09-16 从 VaultRepositoryImpl 迁入）=====

    /**
     * 单库 KDBX 启用：**先校验、后暂存**待包裹明文。
     *
     * ## 为什么校验方式由调用方传进来（而不是本类自己验）
     *
     * 单库路径的历史行为是**复用 `unlockKdbxInternal`（真实开库）**做校验 ——
     * 用户刚证明自己能开这个库，把会话留着让他直接用，符合直觉。
     * 但 `unlockKdbxInternal` 在 [VaultRepositoryImpl] 上，本类若直接依赖它会成环。
     * 故把"怎么验"作为参数 [verifyCredentials] 传入：调用方（[VaultRepositoryImpl]）
     * 传自己的真实开库路径，本类只管"验过了才组装明文"。
     *
     * ⚠️ 多库路径**不用**这种方式，它走 [prepareForVaults] 里的 [Kdbx.verify]
     * （只验不开库）—— 一次勾多个库时"顺带开库"会互相覆盖会话，且用户并没要求打开它们。
     *
     * ⚠️ 顺序不可颠倒：`wrap` 只负责封字节、不管字节对不对，先包后校会得到
     * 「启用成功但躺的是错密码」，用户要到下次解锁才发现打不开。
     *
     * @param verifyCredentials 给定 (vaultId, origin, password, keyFileUri) 返回凭据是否正确。
     */
    suspend fun prepareKdbxEnroll(
        vaultId: String,
        masterPassword: String,
        keyFileUri: String?,
        verifyCredentials: suspend (vaultId: String, origin: String, password: String, keyFileUri: String?) -> Boolean,
    ): KdbxEnrollOutcome = withContext(Dispatchers.IO) {
        val row = vaultDao.get(vaultId)
            ?: return@withContext KdbxEnrollOutcome.Failed("本地不存在该库")
        if (VaultKind.fromName(row.kind) != VaultKind.KDBX) {
            return@withContext KdbxEnrollOutcome.Failed("该库不是 KDBX 类型")
        }
        if (!verifyCredentials(vaultId, row.origin, masterPassword, keyFileUri)) {
            return@withContext KdbxEnrollOutcome.InvalidCredentials
        }
        // 覆盖上一份前先擦：任何时刻内存里最多只有一份暂存明文。
        stagedSinglePayload?.fill(0)
        stagedSinglePayload = KdbxUnlockPayload.encode(masterPassword, readKeyFileBytes(keyFileUri))
        KdbxEnrollOutcome.Prepared
    }

    /** 单库 KDBX 启用第二步：用**已认证**的 cipher 包裹暂存明文并落盘。 */
    suspend fun commitKdbxEnroll(vaultId: String, cipher: Cipher): Boolean =
        withContext(Dispatchers.IO) {
            val plaintext = stagedSinglePayload ?: return@withContext false
            // 先取走再处理：无论 wrap 成败，暂存位都不能再指向这份明文。
            stagedSinglePayload = null
            // 与 Bitwarden 侧同款取向：**不吞异常**（`wrap` 抛错意味着这个 cipher
            // 根本用不了，那是编程/环境错误，吞成「包裹失败」只会掩盖它）。
            val wrapped = try {
                localUnlockKeyStore.wrap(cipher, plaintext)
            } finally {
                // 明文凭据用完即擦（含主密码字节）：这是本项目对明文的一贯取向。
                plaintext.fill(0)
            }
            credentials.putString(localUnlockStorageKey(vaultId), wrapped)
            preferences.setLocalUnlockEnabled(vaultId, true)
            true
        }

    /** 放弃单库 KDBX 登记：把暂存明文擦掉（幂等）。 */
    fun discardKdbxEnroll() {
        discardStagedPayload()
    }

    /** 读 keyfile 字节（只包 URI 不行：授权可能失效、用户可能换过文件）。 */
    private fun readKeyFileBytes(keyFileUri: String?): ByteArray? = keyFileUri
        ?.takeIf { it.isNotBlank() }
        ?.let { uri -> runCatching { kdbxSource.read(uri) }.getOrNull() }
}

/**
 * 本地快速解锁信封的存储键前缀。
 *
 * ⚠️ 提到包级 `internal` 是为了让 [LocalUnlockEnrollment] 与 [VaultRepositoryImpl]
 * **共用同一个常量**。此前它是 `VaultRepositoryImpl` 的私有常量，新类要用就只能
 * 复制一份字符串 —— 两份字面量一旦漂移，写入方与读取方会各看各的键，
 * 表现为"启用成功但解锁时找不到信封"，且不会有任何编译错误。
 */
internal const val LOCAL_UNLOCK_STORAGE_PREFIX = "local_unlock_key::"

/** 某个库的本地快速解锁信封存储键。 */
internal fun localUnlockStorageKey(vaultId: String): String =
    LOCAL_UNLOCK_STORAGE_PREFIX + vaultId
