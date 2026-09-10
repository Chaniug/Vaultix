package io.vaultix.data.repository

import android.os.Build
import io.vaultix.crypto.SecureBytes
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.auth.TwoFactorInvalidException
import io.vaultix.data.bitwarden.auth.TwoFactorRequiredException
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.data.bitwarden.sync.SyncOutcome
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.datastore.LocalUnlockKeyStore
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncReport
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 库生命周期实现（Docs/01 §5 状态机）。
 *
 * 设计要点：
 * - **vaultId = 规范化服务器 URL**（trimEnd('/')）：与认证层 / 401 刷新器按
 *   server 存 token 的语义一致（M1 限制：同一服务器仅支持一个账号，见 decisions）；
 * - 「解锁」默认 = 联网重新登录（prelogin → 派生 → token）→ 解包对称密钥进内存会话；
 * - 「本地快速解锁」= 首次登录成功后把对称密钥用 Keystore 用户认证 KEK 包裹落盘，
 *   此后锁库只清内存；再次解锁经生物识别 / 设备 PIN 认证后本地解封，离线可用、
 *   不重输主密码、不触发 2FA（模型与官方客户端 / Bastion 一致，见 decisions）。
 */
@Singleton
class VaultRepositoryImpl @Inject constructor(
    private val vaultDao: VaultDao,
    private val pendingOpDao: PendingOpDao,
    private val authRepository: BitwardenAuthRepository,
    private val sessions: VaultSessionManager,
    private val syncService: BitwardenSyncService,
    private val credentials: SecureCredentialStore,
    private val localUnlockKeyStore: LocalUnlockKeyStore,
    private val preferences: VaultixPreferences,
) : VaultRepository {

    override fun observeVaults(): Flow<List<VaultSummary>> =
        combine(vaultDao.observeAll(), sessions.unlockedIds) { rows, unlocked ->
            rows.map { row ->
                VaultSummary(
                    id = row.id,
                    kind = VaultKind.fromName(row.kind) ?: VaultKind.KDBX,
                    name = row.displayName,
                    account = row.account,
                    origin = row.origin,
                    unlocked = row.id in unlocked,
                )
            }
        }

    override fun observeUnlockedVaultIds(): Flow<Set<String>> = sessions.unlockedIds

    override suspend fun addBitwardenVault(
        server: String,
        email: String,
        masterPassword: String,
    ): UnlockResult {
        val normalized = normalizeServer(server)
        val outcome = doUnlock(email = email.trim(), server = normalized, password = masterPassword)
        if (outcome == UnlockResult.Success) {
            registerVaultRow(normalized, email.trim())
        }
        return outcome
    }

    override suspend fun unlockVault(vaultId: String, masterPassword: String): UnlockResult {
        val row = vaultDao.get(vaultId) ?: return UnlockResult.VaultMissing
        if (VaultKind.fromName(row.kind) != VaultKind.BITWARDEN) {
            return UnlockResult.Unknown("仅支持 Bitwarden 库解锁（当前类型：${row.kind}）")
        }
        val email = row.account ?: return UnlockResult.Unknown("该库缺少账号信息，请移除后重新添加")
        return doUnlock(email = email, server = row.origin, password = masterPassword)
    }

    override suspend fun unlockVaultWithTwoFactor(
        vaultId: String,
        masterPassword: String,
        provider: Int,
        code: String,
    ): UnlockResult {
        val row = vaultDao.get(vaultId) ?: return UnlockResult.VaultMissing
        if (VaultKind.fromName(row.kind) != VaultKind.BITWARDEN) {
            return UnlockResult.Unknown("仅支持 Bitwarden 库解锁（当前类型：${row.kind}）")
        }
        val email = row.account ?: return UnlockResult.Unknown("该库缺少账号信息，请移除后重新添加")
        return doUnlock(
            email = email,
            server = row.origin,
            password = masterPassword,
            twoFactor = TwoFactorAttempt(provider, code),
        )
    }

    override suspend fun addBitwardenVaultWithTwoFactor(
        server: String,
        email: String,
        masterPassword: String,
        provider: Int,
        code: String,
    ): UnlockResult {
        val normalized = normalizeServer(server)
        val outcome = doUnlock(
            email = email.trim(),
            server = normalized,
            password = masterPassword,
            twoFactor = TwoFactorAttempt(provider, code),
        )
        if (outcome == UnlockResult.Success) {
            registerVaultRow(normalized, email.trim())
        }
        return outcome
    }

    override fun lockVault(vaultId: String) {
        // 锁定入口来自 UI 线程，key 清零需要挂起；这里包一层同步桥接，
        // 保证锁定的语义是「调用返回后密钥已不可用」。
        runBlocking { sessions.lock(vaultId) }
    }

    override fun lockAll() {
        runBlocking { sessions.lockAll() }
    }

    override suspend fun removeVault(vaultId: String) {
        // 1) 内存会话清零（幂等；随后 keyOf 即 null，UI 观察的 unlocked 状态随之消失）
        sessions.lock(vaultId)
        // 2) 本地快速解锁痕迹（包裹密钥 + 开关）——尽力而为，失败不阻断移除
        runCatching { disableLocalUnlock(vaultId) }
        // 3) 认证凭据与 host→server 登记清除（服务端会话保留，属正常）
        authRepository.logout(vaultId)
        // 4) 待推送队列显式清空（该表无外键，须先清，防止同服务器重加账号后
        //    把旧账号的离线改动推到新账号）
        pendingOpDao.clearVault(vaultId)
        // 5) vault 行删除：ciphers / folders 经外键 CASCADE 一并移除
        vaultDao.delete(vaultId)
    }

    // ---- 本地快速解锁（Keystore 用户认证 KEK 包裹，见类 KDoc）----

    override fun localUnlockAvailable(vaultId: String): Flow<Boolean> =
        combine(
            preferences.isLocalUnlockEnabled(vaultId),
            flow { emit(wrappedPayload(vaultId) != null) },
        ) { enabled, hasPayload ->
            enabled && hasPayload && localUnlockKeyStore.keyAvailable
        }

    override suspend fun enrollLocalUnlock(vaultId: String, cipher: Cipher): Boolean {
        val key = sessions.keyOf(vaultId) ?: return false
        val fullKey = buildFullKey(key)
        val wrapped = try {
            localUnlockKeyStore.wrap(cipher, fullKey)
        } finally {
            fullKey.fill(0)
        }
        credentials.putString(LOCAL_UNLOCK_PREFIX + vaultId, wrapped)
        preferences.setLocalUnlockEnabled(vaultId, true)
        return true
    }

    override suspend fun prepareLocalUnlock(vaultId: String): Cipher? {
        val payload = wrappedPayload(vaultId) ?: return null
        return localUnlockKeyStore.newDecryptCipher(payload)
    }

    override suspend fun prepareLocalEnroll(): Cipher? =
        localUnlockKeyStore.newEncryptCipher()

    override suspend fun completeLocalUnlock(vaultId: String, cipher: Cipher): UnlockResult {
        val payload = wrappedPayload(vaultId) ?: return UnlockResult.Unknown("未启用本地快速解锁")
        return runCatching {
            val fullKey = localUnlockKeyStore.unwrap(cipher, payload)
            try {
                val key = SymmetricCryptoKey.fromFullKey(fullKey)
                sessions.unlock(vaultId, key)
            } finally {
                fullKey.fill(0)
            }
            // 进程重启后走快速解锁（不重登）：access token 持久化仍在，登记
            // host→server 使请求拦截器能预挂 Bearer / 401 时可刷新
            authRepository.registerServer(vaultId)
            UnlockResult.Success
        }.getOrElse { error ->
            if (error.isLocalUnlockUnrecoverable()) {
                // KEK 永久失效（新增/删除指纹）或不可恢复，
                // 或认证会话失效 / 密文校验失败：
                // 清干净，回退「未启用」→ UI 如实显示，用户可重新启用自愈。
                clearBrokenLocalUnlock(vaultId)
                UnlockResult.Unknown("本地解锁已失效（可能因指纹变更），请用主密码登录")
            } else {
                UnlockResult.Unknown(error.message)
            }
        }
    }

    override suspend fun disableLocalUnlock(vaultId: String) {
        credentials.remove(LOCAL_UNLOCK_PREFIX + vaultId)
        preferences.setLocalUnlockEnabled(vaultId, false)
    }

    /**
     * 清理已不可用的快速解锁状态（Bastion 不变量移植）。
     *
     * **触发场景**：Keystore KEK 被永久失效 —— 用户新增/删除指纹时
     * `setInvalidatedByBiometricEnrollment(true)`（Vaultix 默认行为）会让 KEK 彻底不可用；
     * 或 Keystore 返回不可恢复的陈旧密钥。
     *
     * **为什么必须清理**：若只吞掉异常而不清状态，开关仍是 `enabled = true`、
     * payload 仍在，`localUnlockAvailable` 就仍返回 true → 设置页显示「已启用」，
     * 但用户每次点指纹都失败，**且无法自愈**（重试永远失败，只能手动关闭再启用）。
     * Bastion 用 1923 行 + 回归测试防的正是这个静默死循环。
     *
     * **为什么不尝试自动重建**：Vaultix 的 KEK 用
     * `setUserAuthenticationParameters(0, …)` = **每次使用都需认证**
     * （安全性高于 Bastion 的 `setUserAuthenticationValidityDurationSeconds(300)`）。
     * 主密码登录路径上没有生物认证窗口，`Cipher.init()` 必然抛
     * `UserNotAuthenticatedException`，因此**无法静默重建**。清回「未启用」
     * 让 UI 如实反映状态、并允许用户重新启用（届时会走一次真实认证），是正确取舍。
     */
    private suspend fun clearBrokenLocalUnlock(vaultId: String) {
        runCatching {
            credentials.remove(LOCAL_UNLOCK_PREFIX + vaultId)
            preferences.setLocalUnlockEnabled(vaultId, false)
        }
    }

    /** 会话密钥 → 64B full key（enc ‖ mac）。 */
    private fun buildFullKey(key: SymmetricCryptoKey): ByteArray {
        val enc = key.encKey.useBytes { it.copyOf() }
        val mac = key.macKey.useBytes { it.copyOf() }
        return enc + mac
    }

    private fun wrappedPayload(vaultId: String): String? =
        credentials.getString(LOCAL_UNLOCK_PREFIX + vaultId)

    override suspend fun syncVault(vaultId: String): VaultSyncReport {
        val row = vaultDao.get(vaultId)
            ?: return VaultSyncReport.Fatal("本地不存在该库")
        if (VaultKind.fromName(row.kind) != VaultKind.BITWARDEN) {
            return VaultSyncReport.Unsupported
        }
        return when (val outcome = syncService.sync(vaultId = row.id, server = row.origin)) {
            is SyncOutcome.Success -> VaultSyncReport.Success(outcome.cipherCount, outcome.folderCount)
            SyncOutcome.Skipped -> VaultSyncReport.Skipped
            is SyncOutcome.Blocked -> VaultSyncReport.Blocked(outcome.reason)
            is SyncOutcome.RetryableError -> VaultSyncReport.Retryable(outcome.message)
            is SyncOutcome.FatalError -> VaultSyncReport.Fatal(outcome.message)
        }
    }

    // ---- 内部 ----

    /** 登录（可选 2FA 提交）→ 解包账号对称密钥 → 注册内存会话。 */
    private suspend fun doUnlock(
        email: String,
        server: String,
        password: String,
        twoFactor: TwoFactorAttempt? = null,
    ): UnlockResult {
        val deviceId = obtainDeviceId()
        val deviceName = deviceName()
        val login = if (twoFactor == null) {
            authRepository.login(server, email, password, deviceId, deviceName)
        } else {
            authRepository.loginWithTwoFactor(
                server = server,
                email = email,
                password = password,
                provider = twoFactor.provider,
                code = twoFactor.code,
                deviceId = deviceId,
                deviceName = deviceName,
            )
        }
        val session = login.getOrElse { return classifyLoginError(it) }

        val unpack = authRepository.unpackAccountKey(server, session.masterKey)
        // MasterKey 用毕即清（含失败路径）
        session.dispose()
        val key = unpack.getOrElse { return UnlockResult.KeyUnavailable }

        sessions.unlock(server, key)
        return UnlockResult.Success
    }

    private suspend fun registerVaultRow(server: String, email: String) {
        val now = System.currentTimeMillis()
        val existing = vaultDao.get(server)
        vaultDao.upsert(
            VaultEntity(
                id = server,
                kind = VaultKind.BITWARDEN.name,
                displayName = DISPLAY_NAME_BITWARDEN,
                origin = server,
                account = email,
                revisionDate = existing?.revisionDate,
                createdAt = existing?.createdAt ?: now,
            ),
        )
    }

    private fun classifyLoginError(error: Throwable): UnlockResult = when (error) {
        is TwoFactorRequiredException -> UnlockResult.TwoFactorRequired(error.providers)
        is TwoFactorInvalidException -> UnlockResult.TwoFactorInvalid
        is HttpException -> when {
            error.code() == HTTP_UNAUTHORIZED -> UnlockResult.InvalidCredentials
            // 官方 Bitwarden：prelogin 对未注册邮箱返回 404（Vaultwarden 不区分账号）
            error.code() == HTTP_NOT_FOUND -> UnlockResult.AccountNotFound
            // 400 invalid_grant（主密码错误）与其它 4xx 一律按凭据错误提示
            else -> UnlockResult.InvalidCredentials
        }
        is IOException -> UnlockResult.Network
        else -> UnlockResult.Unknown(error.message)
    }

    @Synchronized
    private fun obtainDeviceId(): String {
        credentials.getString(KEY_DEVICE_ID)?.let { return it }
        val id = UUID.randomUUID().toString()
        credentials.putString(KEY_DEVICE_ID, id)
        return id
    }

    private fun deviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { DEFAULT_DEVICE_NAME }

    private fun normalizeServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "服务器地址需以 http(s):// 开头"
        }
        return trimmed
    }

    private companion object {
        const val DISPLAY_NAME_BITWARDEN = "Bitwarden"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val KEY_DEVICE_ID = "device_id"
        const val DEFAULT_DEVICE_NAME = "Vaultix Device"
        const val LOCAL_UNLOCK_PREFIX = "local_unlock_key::"
    }
}

/** 2FA 提交参数（provider + 验证码）。 */
private data class TwoFactorAttempt(val provider: Int, val code: String)
