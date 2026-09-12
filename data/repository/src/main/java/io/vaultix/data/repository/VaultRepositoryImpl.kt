package io.vaultix.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.crypto.SecureBytes
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.auth.TwoFactorInvalidException
import io.vaultix.data.bitwarden.auth.TwoFactorRequiredException
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.data.bitwarden.sync.SyncOutcome
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFailure
import io.vaultix.data.kdbx.KdbxOpenError
import io.vaultix.data.kdbx.KdbxSource
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.FolderDao
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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
    private val cipherDao: CipherDao,
    private val folderDao: FolderDao,
    private val pendingOpDao: PendingOpDao,
    private val authRepository: BitwardenAuthRepository,
    private val sessions: VaultSessionManager,
    private val syncService: BitwardenSyncService,
    private val credentials: SecureCredentialStore,
    private val localUnlockKeyStore: LocalUnlockKeyStore,
    private val preferences: VaultixPreferences,
    /** KDBX 会话变化的可观察桥（见 [KdbxSessionFlow] 的说明）。 */
    private val kdbxSessions: KdbxSessionFlow,
    @ApplicationContext context: Context,
) : VaultRepository {

    /**
     * KDBX 文件读取器（SAF `content://` URI → 字节）。
     *
     * 放在 Android 侧实现的原因：`data:kdbx` 是纯逻辑模块（只依赖 core:*、不碰 Android），
     * 它只认 [KdbxSource] 这个函数式接口。附带好处是引擎的单测可以直接喂 ByteArray。
     */
    private val kdbxSource = KdbxSource { uri ->
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        }.getOrNull()
    }

    override fun observeVaults(): Flow<List<VaultSummary>> =
        combine(vaultDao.observeAll(), sessions.unlockedIds, kdbxSessions.revisionFlow) { rows, unlocked, _ ->
            rows.map { row ->
                // 解锁口径必须与 [isVaultUnlocked] 一致：KDBX 的会话不在
                // [VaultSessionManager] 里（它持有的是整库明文，不是一把密钥），
                // 只看 `unlocked` 会让 KDBX 库永远显示成锁定状态。
                val isUnlocked = row.id in unlocked || Kdbx.isUnlocked(row.id)
                VaultSummary(
                    id = row.id,
                    kind = VaultKind.fromName(row.kind) ?: VaultKind.KDBX,
                    name = row.displayName,
                    account = row.account,
                    origin = row.origin,
                    unlocked = isUnlocked,
                )
            }
        }

    override fun observeUnlockedVaultIds(): Flow<Set<String>> =
        combine(sessions.unlockedIds, kdbxSessions.revisionFlow) { unlocked, _ -> unlocked + Kdbx.unlockedIds() }

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

    // ---- KDBX 本地库（M2 阶段 A：只读）----
    //
    // KDBX 与 Bitwarden 在**会话模型**上完全不同：Bitwarden 的内存会话是一把对称密钥
    // （`VaultSessionManager`），KDBX 的会话是**整库明文**（`data:kdbx` 的会话持有者）。
    // 因此 KDBX 不写 `ciphers` 表、不进 `VaultSessionManager` —— 条目直接从会话里取
    // （见 [io.vaultix.data.repository.ItemRepositoryImpl] 的读路径分流）。

    override suspend fun addKdbxVault(
        sourceUri: String,
        displayName: String,
        masterPassword: String,
        keyFileUri: String?,
    ): UnlockResult {
        if (sourceUri.isBlank()) return UnlockResult.Unknown("未选择数据库文件")
        val result = unlockKdbxInternal(
            vaultId = sourceUri,
            sourceUri = sourceUri,
            password = masterPassword,
            keyFileUri = keyFileUri,
        )
        if (result != UnlockResult.Success) return result

        val now = System.currentTimeMillis()
        val existing = vaultDao.get(sourceUri)
        vaultDao.upsert(
            VaultEntity(
                id = sourceUri,
                kind = VaultKind.KDBX.name,
                displayName = displayName.ifBlank { DEFAULT_DISPLAY_NAME_KDBX },
                origin = sourceUri,
                account = null,
                revisionDate = existing?.revisionDate,
                createdAt = existing?.createdAt ?: now,
            ),
        )
        if (!keyFileUri.isNullOrBlank()) {
            preferences.setKdbxKeyFileUri(sourceUri, keyFileUri)
        }
        return UnlockResult.Success
    }

    override suspend fun unlockKdbxVault(vaultId: String, masterPassword: String): UnlockResult {
        val row = vaultDao.get(vaultId) ?: return UnlockResult.VaultMissing
        if (VaultKind.fromName(row.kind) != VaultKind.KDBX) {
            return UnlockResult.Unknown("仅支持 KDBX 库解锁（当前类型：${row.kind}）")
        }
        val keyFileUri = runCatching { preferences.kdbxKeyFileUri(vaultId).first() }.getOrNull()
        return unlockKdbxInternal(
            vaultId = vaultId,
            sourceUri = row.origin,
            password = masterPassword,
            keyFileUri = keyFileUri,
        )
    }

    override suspend fun lockOtherKdbxVaults(keepVaultId: String) {
        val toLock = Kdbx.unlockedIds().filter { it != keepVaultId }
        if (toLock.isEmpty()) return
        toLock.forEach { Kdbx.lock(it) }
        kdbxSessions.bump()
        // 与 VaultSessionManager.lock 同款收尾：密钥没了，查看锁标记也就失去意义。
        sessions.clearAllViewLocks()
    }

    override fun isVaultUnlocked(vaultId: String): Boolean =
        sessions.isUnlocked(vaultId) || Kdbx.isUnlocked(vaultId)

    /** KDBX 解锁的公共尾部：读文件 → 尝试凭据 → 登记内存会话；失败分类成用户可执行提示。 */
    private suspend fun unlockKdbxInternal(
        vaultId: String,
        sourceUri: String,
        password: String,
        keyFileUri: String?,
    ): UnlockResult = withContext(Dispatchers.IO) {
        val opened = Kdbx.unlock(
            vaultId = vaultId,
            sourceUri = sourceUri,
            password = password,
            keyFileUri = keyFileUri,
            source = kdbxSource,
        )
        opened.fold(
            onSuccess = {
                // 会话建立成功即清查看锁（与 doUnlock 同一判据：有密钥了，标记失去意义），
                // 并自增代次让 `observeUnlockedVaultIds` / `observeVaults` 重发。
                sessions.clearViewLock(vaultId)
                kdbxSessions.bump()
                UnlockResult.Success
            },
            onFailure = { error -> classifyKdbxError(error) },
        )
    }

    /**
     * KDBX 失败 → [UnlockResult] 分类。
     *
     * ⚠️ 必须**分类**而不是一律「未知错误」：用户看到「密码错误」与
     * 「文件读不到了（请重新选择）」时要做的事完全不同（前者重输、后者重选文件并重新授权）。
     */
    private fun classifyKdbxError(error: Throwable): UnlockResult {
        val kind = (error as? KdbxFailure)?.error
            ?: return UnlockResult.Unknown(error.message)
        return when (kind) {
            // 密码 / keyfile 不对 → 与 Bitwarden 的「凭据错误」同一语义（UI 文案通用）
            is KdbxOpenError.InvalidCredentials -> UnlockResult.InvalidCredentials
            // 文件读不到：不是凭据问题，提示重新选择文件
            is KdbxOpenError.SourceUnavailable -> UnlockResult.Unknown(kind.detail)
            is KdbxOpenError.NotKdbxFile -> UnlockResult.Unknown("该文件不是 KDBX 数据库")
            is KdbxOpenError.UnsupportedVersion ->
                UnlockResult.Unknown("不支持的 KDBX 版本 ${kind.version}（请用 KeePass 另存为 3.1 / 4.x）")

            is KdbxOpenError.Unknown -> UnlockResult.Unknown(kind.detail)
        }
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

    override suspend fun lockVault(vaultId: String) {
        // 2026-09-11 改造：由 `fun` + `runBlocking` 改为 `suspend fun`（对齐 Bitwarden
        // 的挂起式锁定）。原实现用 runBlocking 阻塞调用线程来保证「返回即已锁」，
        // 但那会阻塞 UI 线程做密钥清零；现在由调用方在自己的协程里挂起等待，
        // 语义更清晰（挂起点之后密钥必然已不可用），也不再有阻塞。
        sessions.lock(vaultId)
        // KDBX 库的「密钥」是内存里的整库明文，锁库即丢弃（两条会话模型必须同时收）。
        Kdbx.lock(vaultId)
        kdbxSessions.bump()
    }

    override suspend fun lockAll() {
        sessions.lockAll()
        Kdbx.lockAll()
        kdbxSessions.bump()
    }

    /**
     * 退出数据库（用户语义，见 `.ai/ISSUES.md` #60 第 3 步）。
     *
     * 用户原话：「设置里的『立即锁定』应该改成**退出数据库**（清本地缓存，不动远程）」。
     * 与 [removeVault] 的唯一差别是**保留 vault 行**（保留库与账号信息，
     * 下次点一下重新登录即可）；与 [lockVault] 的差别是**连本地缓存一起清**
     * （密文条目 / 文件夹 / 待推送队列 / 快速解锁凭据 / token）。
     *
     * ⚠️ 只清本地，**不碰远程**：不调用任何服务端删除接口，条目在服务端原样保留。
     * 排除待推送队列是「丢弃本地未上传的改动」，这是「清缓存」语义的必然含义，
     * UI 必须就此给出明确确认文案（设置页对话框已写明）。
     */
    override suspend fun signOut(vaultId: String) {
        // 1) 内存会话清零（此后 keyOf 为 null，条目列表立刻变空）
        sessions.lock(vaultId)
        Kdbx.lock(vaultId)
        kdbxSessions.bump()
        // 1b) KDBX 的 keyfile 授权记录：属本地凭据，一并清（下次重新选文件）
        runCatching { preferences.setKdbxKeyFileUri(vaultId, null) }
        // 2) 本地快速解锁痕迹（包裹密钥 + 开关）——不清就会留着用旧 KEK 解封的路径
        runCatching { disableLocalUnlock(vaultId) }
        // 3) 认证凭据与 host→server 登记清除（远端会话不受影响；重登即重新换 token）
        authRepository.logout(vaultId)
        // 4) 待推送队列：属「本地缓存」的一部分，必须清 —— 否则下次登录同一服务器时
        //    旧账号的离线改动会被推到新会话（与 removeVault 第 4 步同因）
        pendingOpDao.clearVault(vaultId)
        // 5) 缓存条目与文件夹（vault 行保留）
        cipherDao.clearVault(vaultId)
        folderDao.clearVault(vaultId)
        // 6) 同步基线归零：revisionDate 留着会让下次同步误判「服务端无变化」而跳过全量，
        //    结果是一个「退出了数据库却什么都没拉回来」的空库
        vaultDao.updateRevision(vaultId, null)
    }

    override suspend fun removeVault(vaultId: String) {
        // 1) 内存会话清零（幂等；随后 keyOf 即 null，UI 观察的 unlocked 状态随之消失）
        sessions.lock(vaultId)
        Kdbx.lock(vaultId)
        kdbxSessions.bump()
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

    /**
     * 快速解锁入口是否可见。
     *
     * ⚠️ **只以「用户开关 + KEK 非永久失效」为准，不再在每次订阅时同步读 payload**
     * （2026-09-12 修回归）：payload 读的是 `SecureCredentialStore`（Keystore AES-GCM），
     * 任何**瞬时** Keystore 异常都会被 `getString` 吞成 null，而这里是 `flow { emit(...) }`
     * 的**一次性**读取 —— 于是「设备重启后 Keystore 尚未就绪」这一瞬间会把指纹入口整条藏掉，
     * 用户被迫重新联网登录（用户实测反馈）。
     *
     * 现在的取向与项目其它状态检测一致（见 `CredentialProviderStatus` 的「读不到=已启用」）：
     * **入口照常给出**，真失败时在 `prepareLocalUnlock` 那一刻如实报错（那时原因才准确）。
     * 上游 Bitwarden 同款取向：生物解锁按钮由 `isUnlockWithBiometricsEnabled` 这个
     * **持久化开关**决定，而不是每次现探密钥可用性。
     */
    override fun localUnlockAvailable(vaultId: String): Flow<Boolean> =
        preferences.isLocalUnlockEnabled(vaultId).map { enabled ->
            enabled && localUnlockKeyStore.keyAvailable
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
        const val DEFAULT_DISPLAY_NAME_KDBX = "KeePass 数据库"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val KEY_DEVICE_ID = "device_id"
        const val DEFAULT_DEVICE_NAME = "Vaultix Device"
        const val LOCAL_UNLOCK_PREFIX = "local_unlock_key::"
    }
}

/** 2FA 提交参数（provider + 验证码）。 */
private data class TwoFactorAttempt(val provider: Int, val code: String)
