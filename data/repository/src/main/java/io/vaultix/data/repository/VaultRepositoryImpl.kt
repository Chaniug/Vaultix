package io.vaultix.data.repository

import android.os.Build
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.auth.TwoFactorInvalidException
import io.vaultix.data.bitwarden.auth.TwoFactorRequiredException
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.data.bitwarden.sync.SyncOutcome
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFailure
import io.vaultix.data.kdbx.KdbxOpenError
import io.vaultix.data.repository.kdbx.KdbxFileCache
import io.vaultix.data.repository.kdbx.KdbxFileSourceResolver
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.datastore.LocalUnlockKeyStore
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.KdbxAddOutcome
import io.vaultix.domain.LocalUnlockEnrollOutcome
import io.vaultix.domain.LocalUnlockPreparedEnrollment
import io.vaultix.domain.PinEnrollOutcome
import io.vaultix.domain.PinUnlockOutcome
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncReport
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.model.KdbxCloudSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    /**
     * 房子钥匙层（两级钥匙层级的唯一协调者，2026-09-28 定稿）：
     * 门锁信封（指纹 KEK / PIN Argon2id 各一）+ 房钥匙（仅内存）+ 房间信封（每库）。
     *
     * ⚠️ 本类只做**薄转发 + 范围镜像同步**；钥匙学一律在 [HouseKeyStore]
     * （旧 `PinUnlockStore` / `PinEnrollment` / `PinEnrollmentCoordinator`
     * 的职责已全部并入它与 [LocalUnlockEnrollment]）。
     */
    private val houseKeyStore: HouseKeyStore,
    /**
     * 「本地快速解锁（生物识别）」的**多库备料与落盘**。
     *
     * ⚠️ 注入而不是把逻辑写在本类里：本类函数数已**正好卡在 40**（detekt
     * `TooManyFunctions` 上限），任何新增都会爆。见 [LocalUnlockEnrollment] 的 KDoc。
     */
    private val localUnlockEnrollment: LocalUnlockEnrollment,
    private val preferences: VaultixPreferences,
    /** KDBX 会话变化的可观察桥（见 [KdbxSessionFlow] 的说明）。 */
    private val kdbxSessions: KdbxSessionFlow,
    /**
     * 「origin ⇒ 文件来源」的解析（**读路径**的唯一入口）。
     *
     * ## 为什么必须是它，而不是"一个读 URI 的 lambda"（2026-09-17 迁移）
     *
     * 这里原本是一个 `KdbxSource { uri -> contentResolver.openInputStream(Uri.parse(uri)) }`
     * —— **只认 SAF `content://`**。于是把 `"webdav:…"` / `"onedrive:…"` 交给
     * `Uri.parse` 必然读不到，网盘库**即使配好了也加不进来、解锁不了**（方案 §16.1）。
     *
     * ⚠️ **判据**：读路径分流了，写路径就必须同时分流。写回早就走
     * `KdbxFileSource`（支持网盘），读路径却还留在旧接口上 —— 两条路一分叉，
     * 必然在某个新来源上悄悄失效，而且**不报错**。
     *
     * 现在两边共用 [KdbxCloudSyncCoordinator.fileSourceFor] 这一张判别表
     * （见 [KdbxFileSourceResolver] 的 KDoc：那张表**只能有一份**）。
     */
    private val kdbxFileSources: KdbxFileSourceResolver,
    /**
     * 远端 kdbx 的**本地缓存**（批次 B1，2026-09-30）：解锁提速用，
     * 见 [io.vaultix.data.repository.kdbx.CachedKdbxFileSource]。
     *
     * ⚠️ 本类只**清**它（`signOut` / `removeVault` 时按 origin 删条目）——
     * 读写都在 `fileSourceFor` 里由装饰器完成，本类不参与。
     * 放在本文件是因为「删库 ⇒ 清它那份本地副本」是仓储的职责边界。
     */
    private val kdbxFileCache: KdbxFileCache,
) : VaultRepository {

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
                    // null 原样传下去（= 该库不适用网盘同步），**不要**兜底成 LOCAL_ONLY：
                    // 那会让 Bitwarden 库在 UI 上凭空长出一个「仅本地」角标。
                    syncStatus = KdbxCloudSyncStatus.fromName(row.syncStatus),
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
        return doUnlock(
            email = email,
            server = row.origin,
            password = masterPassword,
            // ★ 已添加过的库：主密码默认走**本地解锁**（见 doUnlock 的长注释）
            preferLocalUnlock = true,
        )
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
    ): KdbxAddOutcome {
        if (sourceUri.isBlank()) {
            return KdbxAddOutcome.Failed(UnlockResult.Unknown("未选择数据库文件"))
        }
        val result = unlockKdbxInternal(
            vaultId = sourceUri,
            sourceUri = sourceUri,
            password = masterPassword,
            keyFileUri = keyFileUri,
        )
        if (result != UnlockResult.Success) return KdbxAddOutcome.Failed(result)

        val now = System.currentTimeMillis()
        // ⚠️ KDBX 库的 id **就是** sourceUri（文件路径即主键）⇒ 重复添加同一文件会
        // 覆盖同一行。用「添加前是否已有该 id」区分「新增 / 已存在」，让 UI 能给出
        // 不同的成功文案 —— 否则用户重复添加时列表零变化、又无任何提示，
        // 读起来就是「点了一点反应都没有」（`.ai/ISSUES.md` #94）。
        val existing = vaultDao.get(sourceUri)
        val outcome = if (existing == null) KdbxAddOutcome.Added else KdbxAddOutcome.Updated
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
        // 首次接入顺手设为默认库（**仅当默认库为空**，用户 2026-09-14 拍板）。
        // ⚠️ 只在 Added 时做：Updated 是「重复添加同一文件」，不是接入新库，
        //   不该有设默认的副作用。
        if (outcome is KdbxAddOutcome.Added) {
            preferences.trySetDefaultVaultIfAbsent(sourceUri)
        }
        return outcome
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
        // ⚠️ 只清**本次真正锁掉的那几个库**。原先是 `sessions.clearAllViewLocks()`，
        // 会顺手抹掉其它库（例如 Bitwarden）的查看锁标记 —— 越权清理的表现是
        // 「我明明刚把某个库锁上，切个库回来它自己变成未锁了」。
        toLock.forEach { sessions.clearViewLock(it) }
    }

    override fun isVaultUnlocked(vaultId: String): Boolean =
        sessions.isUnlocked(vaultId) || Kdbx.isUnlocked(vaultId)

    /**
     * KDBX 解锁的公共尾部：**解析来源** → 读文件 → 尝试凭据 → 登记内存会话；
     * 失败分类成用户可执行提示。
     *
     * ⚠️ 第一步"解析来源"是 2026-09-17 补上的关键一步（方案 §16.2 R1）：
     * 少了它，`webdav:` / `onedrive:` 的 origin 会被当成 SAF URI 去 `Uri.parse`，
     * **静默读不到** ⇒ 添加与解锁双双失败，而错误提示还是"密码错误"那种误导人的话。
     */
    private suspend fun unlockKdbxInternal(
        vaultId: String,
        sourceUri: String,
        password: String,
        keyFileUri: String?,
    ): UnlockResult = withContext(Dispatchers.IO) {
        val source = kdbxFileSources.fileSourceFor(sourceUri)
            ?: return@withContext UnlockResult.Unknown("这个库还没有可用的文件来源，请重新选择文件")
        // keyfile 与库文件走**同一套解析**（SAF 场景下它也是一个 content://）。
        // 读不到时会是 null ⇒ 引擎按"只有主密码"去试 ⇒ 验不过 ⇒ 报凭据错。
        // 那不是降级，而是**如实**：keyfile 开不了的库拿掉 keyfile 确实开不了。
        val keyFileBytes = kdbxFileSources.readBytes(keyFileUri)
        val opened = Kdbx.unlock(
            vaultId = vaultId,
            source = source,
            password = password,
            keyFileBytes = keyFileBytes,
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

    // 注：`classifyKdbxError` 与 `buildFullKey` 是两个纯函数，已移到文件末尾
    //（见文件级函数说明：类函数数受 detekt `TooManyFunctions` 40 上限约束）。

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
        // ★★ 多库锁模型定稿 **D1**（2026-09-29）：锁单库改为写**每库**「用户主动锁」标记，
        //   **不再删全局 `house_lock_auto` 信封**。
        //
        //   旧实现删信封的**本意是对的**（不删则下次冷启动会把这个库悄悄重新解锁，
        //   「锁定」被静默撤销），但**粒度错了**：那个信封包的是**全局房钥匙**，
        //   用一次单库动作去删它，会连带掐掉**其它库**的冷启动恢复能力。
        //   ⇒ 现在由每库标记表达意图，`AutoUnlockRepositoryImpl.restore()` 逐库跳过
        //   （定稿 §3.2）—— 「锁 A 不影响 B」与「A 不会被悄悄开回来」同时成立。
        //   ⚠️ 全局语义的动作仍删信封：见 [lockAll]。
        runCatching { houseKeyStore.markUserLocked(vaultId) }
    }

    override suspend fun lockAll() {
        // 房钥匙先清零（房子化硬约束 #4：锁 = 密钥清零）—— 顺序刻意在会话清理之前：
        // 之后任何「再想用快解开库」的路径都必须重新过一次门锁。
        houseKeyStore.lock()
        sessions.lockAll()
        Kdbx.lockAll()
        kdbxSessions.bump()
        // 同 lockVault：主动全锁 ⇒ 自动恢复信封删除（真锁定）。
        runCatching { houseKeyStore.removeAutoEnvelope() }
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
        // 2) 该库的房间信封与范围镜像（房子化：两把门锁是全局的，退出单个库只清它的房间）
        runCatching { removeRoomEnvelope(vaultId) }
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
        // 7) 远端库文件的本地缓存（批次 B1，2026-09-30）：「退出登录」的用户语义
        //    包含**清掉本地那份副本** —— 留一份 kdbx 在那与预期相反（虽然它只是密文）。
        evictKdbxFileCache(vaultDao, kdbxFileCache, vaultId)
    }

    override suspend fun removeVault(vaultId: String) {
        // 1) 内存会话清零（幂等；随后 keyOf 即 null，UI 观察的 unlocked 状态随之消失）
        sessions.lock(vaultId)
        Kdbx.lock(vaultId)
        kdbxSessions.bump()
        // 2) 该库的房间信封与范围镜像（同 signOut 第 2 步：门锁是全局的，不动）
        runCatching { removeRoomEnvelope(vaultId) }
        // 3) 认证凭据与 host→server 登记清除（服务端会话保留，属正常）
        authRepository.logout(vaultId)
        // 4) 待推送队列显式清空（该表无外键，须先清，防止同服务器重加账号后
        //    把旧账号的离线改动推到新账号）
        pendingOpDao.clearVault(vaultId)
        // 5) vault 行删除：ciphers / folders 经外键 CASCADE 一并移除
        // ⚠️ 先清远端库文件的本地缓存（批次 B1）—— **必须在删行之前**：
        //    缓存键是 `origin`，删行之后就再也读不到它，条目会变成**永久孤儿**。
        evictKdbxFileCache(vaultDao, kdbxFileCache, vaultId)
        vaultDao.delete(vaultId)
    }

    // ---- 快速解锁（两级钥匙「房子化」，钥匙学在 HouseKeyStore）----

    /**
     * 指纹门锁入口是否可用（**全局**）。
     *
     * 取向沿用 2026-09-12 的修正：**入口照常给出**（LOADABLE 与 UNKNOWN 都算可用），
     * 真失败留到 [prepareFingerprintUnlock] 那一刻如实报错 —— 订阅时现探可解性
     * 会被瞬时 Keystore 异常骗成「入口消失」（实测回归）。
     */
    override fun fingerprintLockAvailable(): Flow<Boolean> =
        preferences.fingerprintLockEnrolled
            .map { enrolled -> enrolled && localUnlockKeyStore.keyAvailable }
            // `keyAvailable` 要一次 Keystore 往返（冷启动可达数百毫秒）；
            // 搬 IO，首帧与探测并行（实测「指纹不能第一时间弹出」的修法）。
            .flowOn(Dispatchers.IO)

    /** PIN 门锁入口是否可用（**全局**；镜像键 = 门锁信封存在性）。 */
    override fun pinLockAvailable(): Flow<Boolean> = preferences.pinLockEnrolled

    /**
     * 该库是否可用指纹快解（指纹门锁在 && 该库在生效范围）。
     * 范围真源是房间信封（`house_room::`），偏好键只是响应式镜像。
     */
    override fun fingerprintQuickUnlockAvailable(vaultId: String): Flow<Boolean> =
        combine(fingerprintLockAvailable(), preferences.quickUnlockScope()) { lockAvailable, scope ->
            lockAvailable && vaultId in scope
        }

    /** 开指纹门锁第一步：备授权 cipher（KEK 门禁的既有入口，行为不变）。 */
    override suspend fun prepareFingerprintEnroll(): Cipher? =
        localUnlockKeyStore.newEncryptCipher()

    /**
     * 开指纹门锁第二步（认证已过）：包房钥匙 + 同步镜像键。
     *
     * @return false = 房钥匙不可得（另一把门锁已存在但未解 —— 先解它再开这把，
     *   否则两把门锁会包不同的钥匙；顺序约束由 HouseKeyStore 守）。
     */
    override suspend fun enrollFingerprintLock(cipher: Cipher): Boolean {
        val result = houseKeyStore.enrollFingerprintLock(cipher)
        val enrolled = result == LockEnrollResult.Enrolled
        if (enrolled) {
            preferences.setFingerprintLockEnrolled(true)
        }
        return enrolled
    }

    /** 解指纹门锁第一步：备认证 cipher。null = 门锁未启用 / KEK 失效 → 回主密码。 */
    override suspend fun prepareFingerprintUnlock(): Cipher? =
        houseKeyStore.prepareFingerprintUnlock()

    /**
     * 解指纹门锁第二步（认证已过）：房钥匙进内存。
     * 解锁路径上**唯一**的 Keystore 操作；此后各库 [unlockVaultFromRoom] 纯软件。
     */
    override suspend fun completeFingerprintUnlock(cipher: Cipher): Boolean =
        houseKeyStore.completeFingerprintUnlock(cipher)

    /** 关指纹门锁（全局；信封删除 + 镜像同步，孤儿房间由 HouseKeyStore 连带清理）。 */
    override suspend fun disableFingerprintLock() {
        houseKeyStore.disableFingerprintLock()
        preferences.setFingerprintLockEnrolled(false)
    }

    // ---- 应用内 PIN 门锁（Argon2id，全局计数）----

    /** 位数门槛（先验后做昂贵事）。 */
    override fun validatePin(pin: String): PinEnrollOutcome? = houseKeyStore.validatePin(pin)

    /** 开 PIN 门锁：包房钥匙 + 镜像同步（覆盖旧信封即「修改 PIN」）。 */
    override suspend fun enrollPinLock(pin: String): Boolean {
        val result = houseKeyStore.enrollPinLock(pin)
        val enrolled = result == LockEnrollResult.Enrolled
        if (enrolled) {
            preferences.setPinLockEnrolled(true)
        }
        return enrolled
    }

    /** 用 PIN 解门锁（全局计数）。成功 = 房钥匙已在内存，随后逐库 [unlockVaultFromRoom]。 */
    override suspend fun openHouseWithPin(pin: String): PinUnlockOutcome =
        when (val open = houseKeyStore.openWithPin(pin)) {
            PinOpen.Opened -> PinUnlockOutcome.Opened
            else -> open.toFailureOutcome()
        }

    /** 关 PIN 门锁（信封 + 计数 + 镜像；孤儿清理同指纹侧）。 */
    override suspend fun disablePinLock() {
        houseKeyStore.disablePinLock()
        preferences.setPinLockEnrolled(false)
    }

    // ---- 房间信封（生效范围）----

    /**
     * 逐库软封装落盘（纯转发：备料在 `LocalUnlockEnrollment.prepareForVaults`，
     * 落盘与范围镜像同步在同处 —— 本类不展开任何逻辑，函数数纪律）。
     */
    override suspend fun sealRoomsForVaults(
        prepared: List<LocalUnlockPreparedEnrollment>,
    ): Map<String, LocalUnlockEnrollOutcome> =
        localUnlockEnrollment.sealRoomsForVaults(prepared)

    /**
     * 删某库房间信封并从范围镜像剔除（`signOut` / `removeVault` 共用）。
     *
     * ⚠️ 2026-10-04：这里曾有一个 `override removeVaultFromScope`（「取消勾选某库」）
     *   同样转调本方法。因「范围勾选」向导已整段删除、范围恒等于全部库，
     *   该动作不可能再被用户触发 ⇒ 连同接口方法一起删除，**本私有方法保留**
     *   （`signOut` 第 2 步与 `removeVault` 第 2 步都还在用）。
     */
    private suspend fun removeRoomEnvelope(vaultId: String) {
        houseKeyStore.removeRoom(vaultId)
        val scope = preferences.quickUnlockScope().first()
        if (vaultId in scope) {
            preferences.setQuickUnlockScope(scope - vaultId)
        }
    }

    /**
     * 全部库的「生效范围候选」：返回**全部**库 id（含 KDBX），由 UI 决定勾选谁。
     *
     * ⚠️ 不在这里过滤"可纳入的"：KDBX 库并非不可纳入（只是要多输一次主密码），
     * 过滤掉会让用户以为 KDBX 不支持快速解锁 —— 那是**假状态**。
     */
    override suspend fun quickUnlockCandidateVaultIds(): List<String> =
        withContext(Dispatchers.IO) {
            vaultDao.observeAll().first().map { it.id }
        }

    // ---- 解锁扇出（1 次门锁 + N 次软件）----

    /**
     * 用房间信封打开一个库：软件解密取凭据 → 按库类型开库。
     *
     * 前置：房钥匙已在内存（先过一把门锁）。房间的 AEAD 自带完整性校验，
     * 损坏如实报 [RoomUnlockOutcome.Unavailable]（只影响该库，可删可重建）。
     */
    override suspend fun unlockVaultFromRoom(vaultId: String): RoomUnlockOutcome =
        withContext(Dispatchers.IO) {
            val row = vaultDao.get(vaultId)
                ?: return@withContext RoomUnlockOutcome.Unavailable("本地不存在该库")
            when (VaultKind.fromName(row.kind)) {
                VaultKind.BITWARDEN -> openBitwardenFromRoom(vaultId)
                VaultKind.KDBX -> openKdbxFromRoom(vaultId, row.origin)
                // 未知类型（数据损坏 / 未来新增）：不猜，如实报错。
                null -> RoomUnlockOutcome.Unavailable("无法识别该库类型")
            }
        }

    /** Bitwarden 房间：64B full key → 建会话（完全离线，不触发 2FA）。 */
    private suspend fun openBitwardenFromRoom(vaultId: String): RoomUnlockOutcome {
        val opened = houseKeyStore.openRoom(vaultId)
        if (opened !is RoomOpen.Opened) {
            return opened.toRoomFailure()
        }
        val outcome = runCatching {
            val fullKey = opened.payload
            try {
                sessions.unlock(vaultId, SymmetricCryptoKey.fromFullKey(fullKey))
            } finally {
                fullKey.fill(0)
            }
            // 进程重启后走快解（不重登）：token 持久化仍在，登记 host→server
            // 使请求拦截器能预挂 Bearer / 401 时可刷新。
            authRepository.registerServer(vaultId)
        }
        return if (outcome.isSuccess) {
            RoomUnlockOutcome.Opened
        } else {
            RoomUnlockOutcome.Unavailable(outcome.exceptionOrNull()?.message ?: "无法建立会话")
        }
    }

    /**
     * KDBX 房间：主密码 + keyfile → **真的开库**。
     *
     * `StaleCredentials` = 凭据开不了库（主密码在别处改过，D3）：门锁不动，
     * 引导输新密码后重包该房间的**软件信封**即可自愈 —— 房子化后重包不再碰指纹。
     */
    private suspend fun openKdbxFromRoom(vaultId: String, originUri: String): RoomUnlockOutcome {
        val opened = houseKeyStore.openRoom(vaultId)
        if (opened !is RoomOpen.Opened) {
            return opened.toRoomFailure()
        }
        val decoded = KdbxUnlockPayload.decode(opened.payload)
        opened.payload.fill(0)
        val credential = when (decoded) {
            is KdbxUnlockPayload.DecodeResult.Ok -> decoded
            is KdbxUnlockPayload.DecodeResult.Malformed ->
                return RoomUnlockOutcome.Unavailable(decoded.detail)
        }
        val source = kdbxFileSources.fileSourceFor(originUri)
            ?: return RoomUnlockOutcome.Unavailable("这个库还没有可用的文件来源")
        val result = try {
            Kdbx.unlock(
                vaultId = vaultId,
                source = source,
                password = credential.masterPassword,
                // keyfile 字节直接喂进去，**不落临时文件**（见 `Kdbx.unlock` 的 KDoc）。
                keyFileBytes = credential.keyFileBytes,
            )
        } finally {
            // 解出的 keyfile 字节用完即擦（减少内存转储可捞到的明文窗口）。
            credential.keyFileBytes?.fill(0)
        }
        return result.fold(
            onSuccess = {
                sessions.clearViewLock(vaultId)
                kdbxSessions.bump()
                RoomUnlockOutcome.Opened
            },
            onFailure = { error ->
                when ((error as? KdbxFailure)?.error) {
                    is KdbxOpenError.InvalidCredentials -> RoomUnlockOutcome.StaleCredentials
                    else -> RoomUnlockOutcome.Unavailable(error.message ?: "无法打开该库")
                }
            },
        )
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

    /**
     * 解锁 / 添加库的编排：优先本地解锁 → 失败才完整登录 → 解包账号对称密钥 → 注册内存会话。
     *
     * ## 为什么主密码解锁不能再走一次「登录」（2026-09-16 修正）
     *
     * 旧实现无论什么场景都调用 `authRepository.login(...)`，即一次全新的
     * `connect/token`（grant_type=password）。服务端只要开了两步验证，密码校验通过后
     * 必然回 `two_factor_required` ⇒ UI 弹出验证码框。表现是：**用户明明已经登录过，
     * 只是杀掉后台重开、输主密码解开密码库，却被要求再向服务器要一次验证码**。
     *
     * 正确的语义（对齐 Bitwarden 官方客户端）：主密码的作用是**解开密码库**，
     * 不是**重新登录**。登录只发生在两种场合 —— 添加新库、会话被服务端吊销。
     *
     * @param preferLocalUnlock 已有库的主密码解锁置 true：先用主密码解开本地持久化的
     *        账号对称密钥，**全程不触网、不要 2FA**（断网也能开库）。
     *        本地解不开（主密码在别处改过 / 本地无密钥）才退回完整登录自愈。
     */
    private suspend fun doUnlock(
        email: String,
        server: String,
        password: String,
        twoFactor: TwoFactorAttempt? = null,
        preferLocalUnlock: Boolean = false,
    ): UnlockResult {
        if (twoFactor == null && preferLocalUnlock) {
            val key = authRepository.unlockWithMasterKey(server, email, password)
            if (key != null) {
                // 本地解锁不经过登录 ⇒ 必须自己登记 host⇄server：进程重启后
                // `serverByHost` 是空的，401 刷新拦截器查不到 server 会让后续同步裸奔
                // （不带 Authorization），表现为「解锁成功但永远同步不了」。
                authRepository.registerServer(server)
                sessions.unlock(server, key)
                return UnlockResult.Success
            }
            // 落到这里 = 本地解不开，退回下面的完整登录（会刷新本地账号密钥，自愈）。
            // 密码输错时服务端只会回 invalid_grant（2FA 是在**密码校验通过之后**才拦的），
            // 因此打字错误不会被误判成「需要验证码」。
        }
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
        // 首次接入顺手设为默认库（**仅当默认库为空**）。
        // 放在这里而不是 UI 层：库 id 是 `normalizeServer(server)` 的结果，
        // 只有仓储知道它 —— 让 ViewModel 自己再算一遍会引入「两处口径漂移」
        // （一处改了、另一处没改 ⇒ 默认库写成了一个不存在的 id）。
        if (existing == null) preferences.trySetDefaultVaultIfAbsent(server)
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

    private companion object {
        const val DISPLAY_NAME_BITWARDEN = "Bitwarden"
        const val DEFAULT_DISPLAY_NAME_KDBX = "KeePass 数据库"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val KEY_DEVICE_ID = "device_id"
        const val DEFAULT_DEVICE_NAME = "Vaultix Device"
    }
}

/** 2FA 提交参数（provider + 验证码）。 */
private data class TwoFactorAttempt(val provider: Int, val code: String)

/**
 * 服务器地址规范化（trim + 去尾部 `/` + 校验 http(s) 前缀）。
 *
 * ⚠️ 文件级纯函数（原类内 private，2026-09-29 房子化时移出）：类内函数数卡在
 * detekt `TooManyFunctions` 40 上限，纯函数放文件作用域语义更诚实（同
 * [classifyKdbxError] / [buildFullKey] 的先例）。同类 `deviceName` 仍留在类内
 * （读 `Build` 常量，与类语境耦合更深，暂不动）。
 */
private fun normalizeServer(server: String): String {
    val trimmed = server.trim().trimEnd('/')
    require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        "服务器地址需以 http(s):// 开头"
    }
    return trimmed
}

/*
 * ── 下面两个是**文件级**纯函数 ─────────────────────────────────────────────
 *
 * 放在类外不是为了省事，而是因为 [VaultRepositoryImpl] 的函数数已卡在 detekt
 * `TooManyFunctions` 的 40 上限（同类问题见 `.ai/ISSUES.md` #93 时代提取
 * `PinUnlockStore` 的先例）。这两个函数**不依赖任何实例状态**，本来就是静态工具，
 * 移到文件作用域语义上更诚实；后续要再加 KDBX 相关方法时，也请优先考虑提取类，
 * 而不是继续往这个类里堆。
 */

/**
 * 会话密钥 → 64B full key（enc ‖ mac）。
 *
 * ⚠️ `internal` 而**不是** `private`：本文件的 `VaultRepositoryImpl` 与
 * `PinEnrollment`、`LocalUnlockEnrollment`（各自独立文件）都要用它。
 * 文件级 `private` 只在**本文件内**可见，跨文件调用会编译失败：
 *
 *     e: PinEnrollment.kt:91:32 Cannot access 'fun buildFullKey(...)': it is private in file.
 *     e: LocalUnlockEnrollment.kt:339:40 同上
 *
 * 同类"文件级私有被跨文件调用"的隐患：本仓库另有 `classifyKdbxError`（同一取向）。
 * **新增跨文件的工具函数时，用 `internal`，不要用 `private`。**
 * （`classifyKdbxError` 目前只被本文件调用，`private` 尚可；一旦有第二个文件要用，
 *  必须同样改成 `internal` —— 否则就是本条踩过的坑。）
 */
internal fun buildFullKey(key: SymmetricCryptoKey): ByteArray {
    val enc = key.encKey.useBytes { it.copyOf() }
    val mac = key.macKey.useBytes { it.copyOf() }
    return enc + mac
}

/**
 * KDBX 失败 → [UnlockResult] 分类。
 *
 * ⚠️ 必须**分类**而不是一律「未知错误」：用户看到「密码错误」与
 * 「文件读不到了（请重新选择）」时要做的事完全不同（前者重输、后者重选文件并重新授权）。
 *
 * ⚠️ ★ **[KdbxOpenError] 是 `sealed`，加分支时这里必须同步** ——
 * 2026-09-17 踩过：`Kdbx.save` 写回路径新增了 `KdbxOpenError.NotUnlocked`，
 * 但本 `when` 没加对应分支 ⇒ `'when' expression must be exhaustive`。
 * **更麻烦的是它不一定会暴露**：`when` 作为**表达式**才要求穷尽，
 * 而 CI 上这颗错误被前一步的 detekt 红牌 skip 掉了两轮（`build` 步骤都没跑到），
 * 直到 detekt 修绿才浮出来。⇒ 给 sealed 加分支后，**主动全局搜 `when (` 的消费点**，
 * 别等编译器告诉你。
 *
 * 映射取舍：`NotUnlocked` 归到 [UnlockResult.Unknown] 而不是新增一个 `UnlockResult` 分支 ——
 * 这个函数只服务**解锁/添加库**路径，那条路径上"未解锁"本来就是不该出现的状态
 * （你正在解锁它）；真出现了也只需要一句可读的提示。为它撑开 domain 层的
 * `UnlockResult` 会让 UI 多一个永远走不到的分支。
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
            // ⚠️ 用错误类型上的**唯一一份**文案（`guidance`），别在这里另写一句 ——
            //    此前这里写的是"请用 KeePass 另存为 3.1 / 4.x"，而 3.1 自 2026-09-30 起
            //    已被本应用拒绝，那句提示会把用户引向一个打不开的格式。
            UnlockResult.Unknown(kind.guidance)

        // 写回路径才有「未解锁」；解锁/添加路径走到这里说明会话已失效，提示重新解锁即可。
        KdbxOpenError.NotUnlocked -> UnlockResult.Unknown("请先解锁该密码库")

        // ★ 拉取远端时才发现远端换了密码。解锁/添加路径**不可能**走到这里
        //   （那条路径是用户当场输密码），列出来只为让 when 保持穷尽 ——
        //   真出现了也只需把错误自带的「下一步」原样转述。
        KdbxOpenError.RemoteCredentialsMismatch -> UnlockResult.Unknown(error.message)

        is KdbxOpenError.Unknown -> UnlockResult.Unknown(kind.detail)
    }
}

/**
 * 清某库**远端 kdbx 的本地缓存**（批次 B1，2026-09-30）。
 *
 * ## 为什么是**文件级**函数（不是类成员）
 *
 * `VaultRepositoryImpl` 的函数数**正好卡在 detekt `TooManyFunctions` 的 40 上限**，
 * 任何新增成员都会爆门禁 —— 与 [classifyKdbxError] / [buildFullKey] 同一处置
 * （见文件末尾的函数说明）。
 *
 * ## 两条纪律
 *
 * 1. ⚠️ 调用点必须在**删 vault 行之前**：缓存键是 `origin`，行没了就取不到键，
 *    条目会变成永久孤儿（占空间、且用户以为"删干净了"）。
 * 2. **取不到 origin / 删除失败一律静默跳过**：缓存是**优化**不是资产，
 *    清不掉最多占点空间；让"退出登录 / 移除密码库"因此失败，代价远大于收益。
 */
private suspend fun evictKdbxFileCache(
    vaultDao: VaultDao,
    cache: KdbxFileCache,
    vaultId: String,
) {
    val origin = runCatching { vaultDao.get(vaultId)?.origin }.getOrNull() ?: return
    runCatching { cache.remove(origin) }
}
