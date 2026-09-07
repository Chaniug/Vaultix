package io.vaultix.data.repository

import android.os.Build
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.data.bitwarden.sync.SyncOutcome
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncReport
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 库生命周期实现（Docs/01 §5 状态机）。
 *
 * 设计要点：
 * - **vaultId = 规范化服务器 URL**（trimEnd('/')）：与认证层 / 401 刷新器按
 *   server 存 token 的语义一致（M1 限制：同一服务器仅支持一个账号，见 decisions）；
 * - 「解锁」= 联网重新登录（prelogin → 派生 → token）→ 解包对称密钥进内存会话；
 *   M1 不做离线解锁（PIN / 生物识别为后续里程碑）；
 * - 本地库行只登记元数据（服务器 / 邮箱），**不落任何密钥材料**。
 */
@Singleton
class VaultRepositoryImpl @Inject constructor(
    private val vaultDao: VaultDao,
    private val authRepository: BitwardenAuthRepository,
    private val sessions: VaultSessionManager,
    private val syncService: BitwardenSyncService,
    private val credentials: SecureCredentialStore,
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

    override fun lockVault(vaultId: String) {
        // 锁定入口来自 UI 线程，key 清零需要挂起；这里包一层同步桥接，
        // 保证锁定的语义是「调用返回后密钥已不可用」。
        runBlocking { sessions.lock(vaultId) }
    }

    override fun lockAll() {
        runBlocking { sessions.lockAll() }
    }

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

    /** 登录 → 解包账号对称密钥 → 注册内存会话。 */
    private suspend fun doUnlock(email: String, server: String, password: String): UnlockResult {
        val login = authRepository.login(
            server = server,
            email = email,
            password = password,
            deviceId = obtainDeviceId(),
            deviceName = deviceName(),
        )
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
        is HttpException -> when {
            error.code() == HTTP_UNAUTHORIZED -> UnlockResult.InvalidCredentials
            error.response()?.errorBody()?.string()
                ?.contains(TWO_FACTOR_MARKER, ignoreCase = true) == true ->
                UnlockResult.TwoFactorRequired
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
        const val TWO_FACTOR_MARKER = "two_factor"
        const val KEY_DEVICE_ID = "device_id"
        const val DEFAULT_DEVICE_NAME = "Vaultix Device"
    }
}
