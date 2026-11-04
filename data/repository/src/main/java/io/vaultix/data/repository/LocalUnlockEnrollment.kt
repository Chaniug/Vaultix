/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.repository.kdbx.KdbxFileSourceResolver
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.database.dao.VaultDao
import io.vaultix.domain.LocalUnlockEnrollOutcome
import io.vaultix.domain.LocalUnlockPrepareOutcome
import io.vaultix.domain.LocalUnlockPreparedEnrollment
import io.vaultix.model.VaultKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 快速解锁「生效范围」（房间信封）的**备料与落盘**。
 *
 * 房子化（2026-09-28 定稿）后的职责边界：
 *
 * | 事 | 谁 |
 * |---|---|
 * | 房钥匙 / 门锁信封 / 房间信封的钥匙学 | [HouseKeyStore] |
 * | 「这个库往房间信封里包什么明文」+ 逐库校验 | 本类（[prepareForVaults]） |
 * | 逐库软封装落盘 + 范围镜像同步 | 本类（[sealRoomsForVaults]） |
 * | 向导编排（问密码、开门锁的顺序） | app 层控制器 |
 *
 * ## 与旧模型（每库信封）的关键差异
 *
 * - **勾库不再碰指纹**：房间信封是纯软件封装（[HouseKeyStore.sealRoom]），
 *   没有任何系统认证参与 ——「备料 → 弹指纹 → 认证后连包」的三段式整体消失，
 *   旧 [commitForVaults] 一把 cipher 连包 N 库的路径（H1 病灶）结构性不存在；
 * - 备料产物 [Prepared.plaintext] **恒非空**：旧模型里 Bitwarden 的密钥留到
 *   「认证后」取，是因为认证窗口里用户可能锁库；新模型没有认证窗口，
 *   取与封同一个时刻完成，锁库边角由 [sealOne] 如实报 `Failed`。
 *
 * ## 仍然保留的两条老纪律
 *
 * - **先校验后包裹**：KDBX 先 [Kdbx.verify] 真验一次才组装明文 —— `sealRoom`
 *   只负责封字节、不管字节对不对，先包后校会得到「启用成功、但躺的是错密码」；
 * - **明文用完即擦**：[Prepared] 实现 `AutoCloseable`，[sealRoomsForVaults]
 *   无论成败逐个 `close()`（且 [HouseKeyStore.sealRoom] 自带 `finally` 清零，
 *   明文副本不落任何第二处）。
 *
 * ## 为什么不能复用单库路径（历史教训，路径已于房子化删除）
 *
 * 旧单库路径的暂存位是**一个不按库分的 `ByteArray?`**：一次勾选多个 KDBX 库时
 * 后一个库的明文会覆盖前一个，前者的信封里躺的是别人的密码。该路径
 * （`prepareKdbxEnroll` / `commitKdbxEnroll` / `discardKdbxEnroll`）连同暂存槽
 * 于 2026-09-28 房子化时整体删除 —— app 层本就零调用。
 */
@Singleton
class LocalUnlockEnrollment @Inject constructor(
    /**
     * 内存里的对称密钥（Bitwarden 侧房间信封里包的就是它派生的 64B full key）。
     *
     * ⚠️ 只依赖会话管理器，**不依赖 [VaultRepositoryImpl]** —— 反过来会让两者成环。
     */
    private val sessions: VaultSessionManager,
    private val houseKeyStore: HouseKeyStore,
    private val vaultDao: VaultDao,
    private val preferences: VaultixPreferences,
    /**
     * 「origin ⇒ 文件来源」的解析。
     *
     * ⚠️ 来源解析必须走与读写两侧**同一张**判别表（[KdbxFileSourceResolver] 的
     * KDoc：那张表只能有一份）—— 曾各自维护一份导致网盘库在这里校验永远失败，
     * 用户看到的却是"主密码不正确"。
     */
    private val kdbxFileSources: KdbxFileSourceResolver,
) {

    /**
     * 一个库「要包进房间信封」的备料。
     *
     * 实现 [LocalUnlockPreparedEnrollment]（域层接口）而不是自带一套公开属性：
     * 控制器只依赖 `domain` 的类型，不会反向依赖 `data.repository`。
     *
     * [close] = 明文用完即擦：KDBX 这份里躺着主密码，没理由让它活到 GC。
     * 调用方必须保证 `close()` 一定被调到（[sealRoomsForVaults] 内部已保证；
     * 直接持有备料的调用方用 `use { }` 或 finally）。
     */
    class Prepared internal constructor(
        override val vaultId: String,
        override val displayName: String,
        /** 待封装的明文（恒非空，见类 KDoc）。 */
        internal val plaintext: ByteArray,
    ) : LocalUnlockPreparedEnrollment {
        override fun close() {
            plaintext.fill(0)
        }
    }

    /**
     * 备料：逐库校验凭据 + 组装待封装明文（**不碰任何门锁 / 指纹**）。
     *
     * 逐个库独立判定（`associate`），**一个库失败不影响其它库** —— 这是刻意保留的
     * 真实状态：用户可能勾了 3 个库、其中 1 个密码不对，另外 2 个没有理由不生效。
     *
     * KDBX 校验走 [Kdbx.verify]（**只验不开库**、不碰 `KdbxSessionStore`）。
     *
     * @param passwordOf 为每个 KDBX 库**单独**取一次它的主密码。
     *   返回 null / 空白 ⇒ 视为**用户跳过该库**（[LocalUnlockPrepareOutcome.Skipped]）。
     *   Bitwarden 库**不会**调用它（房间信封里包的是会话密钥，不需要密码）。
     */
    suspend fun prepareForVaults(
        vaultIds: List<String>,
        passwordOf: suspend (vaultId: String) -> String?,
    ): Map<String, LocalUnlockPrepareOutcome> = withContext(Dispatchers.IO) {
        vaultIds.associateWith { vaultId -> prepareOne(vaultId, passwordOf) }
    }

    /** 单个库的备料。 */
    private suspend fun prepareOne(
        vaultId: String,
        passwordOf: suspend (vaultId: String) -> String?,
    ): LocalUnlockPrepareOutcome {
        val row = vaultDao.get(vaultId)
            ?: return LocalUnlockPrepareOutcome.Failed("本地不存在该库")
        return when (VaultKind.fromName(row.kind)) {
            VaultKind.BITWARDEN -> {
                // 会话不在 ⇒ 没有密钥可包，如实报（不拿空信封糊过去）。
                val key = sessions.keyOf(vaultId)
                    ?: return LocalUnlockPrepareOutcome.Failed("该库未解锁，请先用主密码打开它")
                LocalUnlockPrepareOutcome.Ready(
                    Prepared(vaultId, row.displayName, buildFullKey(key)),
                )
            }
            VaultKind.KDBX -> {
                val masterPassword = passwordOf(vaultId)
                if (masterPassword.isNullOrBlank()) {
                    // 用户跳过（没输密码）⇒ 如实报，不当成功（假状态）。
                    LocalUnlockPrepareOutcome.Skipped
                } else {
                    prepareKdbx(row.id, row.displayName, row.origin, masterPassword)
                }
            }
            null -> LocalUnlockPrepareOutcome.Failed("无法识别该库类型")
        }
    }

    /**
     * KDBX 的备料：先校验，过了才把「主密码 + keyfile」组装好。
     */
    private suspend fun prepareKdbx(
        vaultId: String,
        displayName: String,
        originUri: String,
        masterPassword: String,
    ): LocalUnlockPrepareOutcome {
        val source = kdbxFileSources.fileSourceFor(originUri)
            ?: return LocalUnlockPrepareOutcome.SourceUnavailable("这个库还没有可用的文件来源")
        val keyFileUri = keyFileUriOf(vaultId)
        // keyfile 与库文件走同一套解析；读不到会是 null，届时 `verify` 自然验不过
        // （不是降级成"仅主密码"，而是**如实**：少一个 keyfile 字节就是开不了）。
        val keyFileBytes = kdbxFileSources.readBytes(keyFileUri)
        // ★ 先校验、后组装。`sealRoom` 只负责封字节、不管字节对不对。
        val verified = Kdbx.verify(
            source = source,
            password = masterPassword,
            keyFileBytes = keyFileBytes,
        )
        if (!verified) {
            // 校验不过有两种可能：密码错，或文件读不到。分辨它们对用户很重要 ——
            // 前者该重输，后者该重新选文件（读不到时 `KdbxFileSource.read` 会抛）。
            val readable = runCatching { source.read() }.isSuccess
            return if (readable) {
                LocalUnlockPrepareOutcome.InvalidCredentials
            } else {
                LocalUnlockPrepareOutcome.SourceUnavailable("读不到该库文件，请重新选择")
            }
        }
        // ⚠️ keyfile 读不出来时**拒绝**而不是降级成"仅主密码"：keyfile 不是可选装饰，
        //    少它一个字节就是开不了。静默降级会让信封里躺一组永远解不开的凭据。
        return LocalUnlockPrepareOutcome.Ready(
            Prepared(
                vaultId = vaultId,
                displayName = displayName,
                plaintext = KdbxUnlockPayload.encode(masterPassword, keyFileBytes),
            ),
        )
    }

    /**
     * 房钥匙是否已在内存（= 至少一把门锁在本次进程内被开过 / 解过）。
     *
     * 暴露给 app 层控制器**提前判断**用：封房间（[sealRoomsForVaults]）的前置就是它。
     * 与其让用户输完一整轮 KDBX 主密码、最后才被告知「房钥匙不在内存」，
     * 不如在问密码**之前**就知道，并给出「先解锁一次」的明确指引
     * —— 那是「先校验后包裹」这条老纪律在编排层的延伸。
     *
     * ⚠️ 非 suspend：读的是内存字段，不涉及 Keystore / IO，可在 UI 线程直接问。
     */
    val isHouseKeyReady: Boolean get() = houseKeyStore.isUnlocked

    /**
     * 落盘：把备好的明文逐库软封装成房间信封（纯软件，**不碰指纹、不收 cipher**）。
     *
     * ## 前置：房钥匙必须在内存（至少一把门锁已开）
     *
     * 定稿 §5 顺序约束 —— 房间信封只在至少一把门锁已存在时创建，否则房钥匙从未
     * 被包裹过，进程一死房间信封即成孤儿。不满足时**全部库**如实报失败
     * （不是静默跳过，那正是「假成功」）。
     *
     * ## 逐库独立成败
     *
     * 某个库封装失败（信封损坏等）只回退**该库**，绝不牵连其它库。
     *
     * ## 范围镜像同步
     *
     * 成功的库同步进 `QUICK_UNLOCK_SCOPE`（**真源仍是房间信封本身**，偏好键只是
     * UI 响应式镜像 —— 见 `VaultixPreferences.QUICK_UNLOCK_SCOPE` 的 KDoc）。
     *
     * ⚠️ 无论成败，每个 [Prepared] 的明文都会被擦掉（`close()`）。
     */
    suspend fun sealRoomsForVaults(
        prepared: List<LocalUnlockPreparedEnrollment>,
    ): Map<String, LocalUnlockEnrollOutcome> = withContext(Dispatchers.IO) {
        // ⚠️ 房钥匙守卫留在**公开入口**（这是契约的最后一道防线：任何调用点都别想
        //    在没钥匙时封出孤儿信封）。内部路径走 [sealPrepared] —— 它已由调用方
        //    验过前置（`autoEnrollUnlocked` 的调用点知道刚有没有解开门锁）。
        if (!houseKeyStore.isUnlocked) {
            // 全部如实失败 + 备料明文统一擦除（KDBX 那些含主密码字节）。
            prepared.forEach { it.close() }
            return@withContext prepared.associate {
                it.vaultId to LocalUnlockEnrollOutcome.Failed("请先开启并解锁一把门锁（指纹或 PIN）")
            }
        }
        sealPrepared(prepared)
    }

    /**
     * 真正落盘的那一半（**不查房钥匙**）。
     *
     * ⚠️ 只有两种调用者：公开入口 [sealRoomsForVaults]（查过房钥匙才进来）、
     * 以及 [autoEnrollUnlocked]（前置由调用方保证 —— 主密码解锁那一刻房钥匙
     * **必然**不在内存，再查一次等于把 #243 的时序倒回去）。
     */
    private suspend fun sealPrepared(
        prepared: List<LocalUnlockPreparedEnrollment>,
    ): Map<String, LocalUnlockEnrollOutcome> {
        val outcomes = prepared.associate { unit ->
            val outcome = try {
                // 🔴 强转而非 `when`：接口非 sealed（跨模块无法构成 sealed 层级），
                //    非单分支 when 不穷尽 ⇒ CI 报错。唯一构造入口是 [prepareForVaults]，
                //    真出现第二个实现会立刻 ClassCastException（响亮失败），
                //    好过静默走 else（旧 commitForVaults 的同款取向）。
                val target = unit as Prepared
                sealOneSafely(target)
            } finally {
                unit.close()
            }
            unit.vaultId to outcome
        }
        syncScopeMirror(outcomes)
        return outcomes
    }

    /** 单个库的软封装落盘。 */
    private suspend fun sealOne(unit: Prepared): LocalUnlockEnrollOutcome {
        // plaintext 所有权转移给 sealRoom（内部 finally 清零）。
        houseKeyStore.sealRoom(unit.vaultId, unit.plaintext)
        return LocalUnlockEnrollOutcome.Enrolled
    }

    /**
     * [sealOne] 的失败归类：软封装的异常（GCM/存储层）吞成逐库 `Failed`。
     *
     * 用 `runCatching` 形态与项目既有取向一致（detekt `TooGenericExceptionCaught`
     * 只看 `catch` 子句；这里确实需要接住任意异常再如实归类）。
     */
    private suspend fun sealOneSafely(unit: Prepared): LocalUnlockEnrollOutcome =
        runCatching { sealOne(unit) }.getOrElse { error ->
            LocalUnlockEnrollOutcome.Failed(error.message ?: "房间信封封装失败")
        }

    /** 把成功的库并入范围镜像（真源是房间信封；空集语义见 `quickUnlockScope`）。 */
    private suspend fun syncScopeMirror(outcomes: Map<String, LocalUnlockEnrollOutcome>) {
        val succeeded = outcomes.filterValues { it == LocalUnlockEnrollOutcome.Enrolled }.keys
        if (succeeded.isEmpty()) return
        val current = preferences.quickUnlockScope().first()
        preferences.setQuickUnlockScope(current + succeeded)
    }

    /** 该库的 keyfile URI（按库独立存于偏好；读失败当作"没配 keyfile"）。 */
    private suspend fun keyFileUriOf(vaultId: String): String? =
        runCatching { preferences.kdbxKeyFileUri(vaultId).first() }.getOrNull()

    /**
     * 新增库成功后**自动**把它纳入快速解锁范围（2026-11-01，修 #222）。
     *
     * ## 为什么能全自动（不问主密码、不按指纹）
     *
     * 调用点紧跟在「添加库成功」之后 —— 那一刻该库的会话密钥已在内存
     * （[VaultSessionManager.keyOf] 非空），而房间信封要包的正是它；
     * 房钥匙若也在内存（本次进程内至少开过一次门锁），[HouseKeyStore.sealRoom]
     * 是纯软件 AES-GCM，**没有任何系统认证参与**。
     *
     * ## 为什么可能什么也做不了，且这是**正常**的
     *
     * 冷启动后还没解锁任何库就直接新增库时，房钥匙不在内存（`isUnlocked == false`）
     * —— 物理上没有可用的房钥匙去封信封。此时如实返回 `false`，交给库列表横幅兜底
     * （用户点一下、按一次指纹即可）。**不在这里弹认证**：本类在 data 层，
     * 弹窗属于 UI 职责，越界会让仓储变成半个界面。
     *
     * ## 三道守卫，各自对应一种"不该动"的真实状态
     *
     * 1. 房钥匙不在内存 —— 没钥匙可封；
     * 2. 一把门锁都没装 —— 快速解锁从未启用，不能借"新增库"替用户擅自开启；
     * 3. 范围为**空集** —— 空集在这个键里有确定含义（"一个库都不要"，
     *    见 `VaultixPreferences.QUICK_UNLOCK_SCOPE` 的 KDoc），不是"未配置"。
     *    用户手动取消全部勾选后，不该被一次新增库悄悄改回来。
     *
     * @param masterPasswordOf KDBX 库的主密码来源（`null` = 没有可用密码 ⇒ 该库这次不纳入）。
     *   Bitwarden 走会话密钥，不需要它。**为什么 KDBX 必须单独给**：
     *   [prepareOne] 对 KDBX 走 [prepareKdbx]，要的就是主密码 + keyfile；
     *   而 Bitwarden 要的是内存里的会话密钥 —— 两者不是同一个东西，
     *   旧签名把密码源写死成 `{ _ -> null }`，等于**结构性排除了所有 KDBX 库**（#241）。
     *
     * @return true = 已为 [vaultId] 建好房间信封（并同步了范围镜像）。
     */
    suspend fun autoEnrollNewVault(
        vaultId: String,
        masterPasswordOf: suspend (String) -> String? = { null },
    ): Boolean = withContext(Dispatchers.IO) {
        if (!houseKeyStore.isUnlocked) return@withContext false
        autoEnrollUnlocked(vaultId, masterPasswordOf)
    }

    /**
     * ★ **[autoEnrollNewVault] 的「房钥匙已在内存」版本**（#243，2026-11-04）。
     *
     * ## 为什么要把这一半拆出来
     *
     * 冷启动后第一次进解锁页时，房钥匙**必然**不在内存（没有任何门锁被开过）。
     * 而用户在这一页输入的主密码 / 按下的指纹，**恰恰就是**"房钥匙该进内存"的
     * 那一次认证 —— 但在 [autoEnrollNewVault] 的旧形态里，操作**顺序**是反的：
     *
     * ```
     * 主密码解锁成功 → autoEnrollNewVault（房钥匙还没有 ⇒ 直接 return false）
     *                → 用户点指纹 → 门锁开了、房钥匙进内存 → **但已经没人再来封房间了**
     * ⇒ 该库永远不在范围里 ⇒ 指纹入口不渲染 ⇒ 每次都回到主密码（真机 2026-11-04 报障）
     * ```
     *
     * 解锁路径现在是：**先**开门锁（指纹）/ 拿主密码后补封 → **再**调本方法。
     * 「房钥匙在不在」这个前置由**调用方**保证（它有那个时序知识），本方法只做
     * 纯软件的两件事：备料 + 软封装。
     *
     * ⚠️ 三道守卫里只剩两道（门锁存在 / 范围非空）—— 房钥匙那条搬到了调用方，
     * 因为只有它知道刚刚有没有真的解开门锁。**不要**在这里再判一次
     * `isUnlocked`：那会重新把顺序倒回去（主密码路径下房钥匙仍是没封门锁的）。
     *
     * @param masterPasswordOf 见 [autoEnrollNewVault]。
     * @param force `true` = 即使已有信封也重封（自愈 `Damaged` / 凭据已轮换的那些）。
     * @return true = 已为 [vaultId] 建好房间信封（并同步了范围镜像）。
     */
    suspend fun autoEnrollUnlocked(
        vaultId: String,
        masterPasswordOf: suspend (String) -> String? = { null },
        force: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        // ★ 第一道：**还没有房间信封才需要做任何事**（#246，2026-11-04 真机「解锁很慢」）。
        //
        // ⚠️ 这一步必须在**所有**其它判断之前 —— 现在有三条路径（新增库 / 主密码解锁成功 /
        //   指纹补建）都会走到本方法，而它们每次都紧随一次用户认证之后。若先做
        //   `hasAnyLock()`（一次 Keystore 往返）、`quickUnlockScope().first()`
        //   （一次 DataStore 读，冷启动可达数百毫秒），那么"信封早就有了"这个
        //   绝大多数情况仍要白付这两笔开销 ⇒ 用户感知就是"每次解锁都慢一下"。
        //   而 `hasRoom` 只查一次凭据存储，是三者里最便宜、且**唯一真正必要**的前置。
        //
        // 这条短路的必要性：本方法会在「每次主密码解锁成功后」被调用（#243），
        // 而 KDBX 的备料要先跑一次 `Kdbx.verify` —— 那是一次**完整的 KDF**
        // （真机 64 MiB / 46 轮）。没有短路，用户每输一次主密码都要再烧一遍
        // 同样量级的 Argon2，解锁时间凭空翻倍。
        //
        // ⚠️ `force = true` 只给 [enrollJustAuthenticated] 用：那条路径的**前提**就是
        //   "刚刚开门失败了"（`NotEnrolled` / `Damaged`），此时"有信封"不代表"能用"，
        //   重封一次才是自愈。
        if (!force && houseKeyStore.hasRoom(vaultId)) return@withContext true
        if (!houseKeyStore.hasAnyLock()) return@withContext false
        // 空集有**两种**含义（见 `VaultixPreferences.QUICK_UNLOCK_SCOPE_CONFIRMED`）：
        //   - 未确认过 ⇒ 语义是「全部库都在范围内」（向导默认全勾）⇒ 该封；
        //   - 已确认过 ⇒ 用户明确说「一个库都不要」⇒ 不许替他改回来。
        // 旧写法只看「是否空集」，把未配置的单库用户永久挡在门外：他没有别的库
        // 可用，范围就永远是空集，于是这个库**永远**纳不进来（#243 的第二道死锁）。
        if (preferences.quickUnlockScope().first().isEmpty() &&
            preferences.isQuickUnlockScopeConfirmed().first()
        ) {
            return@withContext false
        }

        val prepared = prepareForVaults(listOf(vaultId), masterPasswordOf)
            .values
            .filterIsInstance<LocalUnlockPrepareOutcome.Ready>()
            .map { it.prepared }
        // 会话密钥不在（理论上不该发生：调用点刚 add 成功）⇒ 如实报"没做成"，
        // 不拿一个空信封糊过去（那正是「假成功」）。
        if (prepared.isEmpty()) return@withContext false

        sealPrepared(prepared).values.any { it == LocalUnlockEnrollOutcome.Enrolled }
    }

    /**
     * 全部库的「生效范围候选」：返回**全部**库 id（含 KDBX），由 UI 决定勾选谁。
     *
     * ⚠️ 不在这里过滤"可纳入的"：KDBX 库并非不可纳入（只是要多输一次主密码），
     *   过滤掉会让用户以为 KDBX 不支持快速解锁 —— 那是**假状态**。
     */
    suspend fun candidateVaultIds(): List<String> = withContext(Dispatchers.IO) {
        vaultDao.observeAll().first().map { it.id }
    }

    /**
     * 删某库房间信封并从范围镜像剔除（`signOut` / `removeVault` 共用）。
     *
     * ⚠️ 2026-10-04：`VaultRepository` 曾有一个 `removeVaultFromScope`（「取消勾选某库」）
     *   同样转调同一段逻辑。因「范围勾选」向导已整段删除、范围恒等于全部库，
     *   该动作不可能再被用户触发 ⇒ 连同接口方法一起删除，**本方法保留**
     *   （`signOut` 与 `removeVault` 都还在用）。
     *
     * ⚠️ 为什么住在本类：它是纯"房间信封 + 范围镜像"的操作，两个依赖
     *   （`houseKeyStore` / `preferences`）本来就在本类；放在 `VaultRepositoryImpl`
     *   会让那个类的函数数顶破 detekt 上限（见那里的说明）。
     */
    suspend fun removeRoom(vaultId: String) {
        houseKeyStore.removeRoom(vaultId)
        val scope = preferences.quickUnlockScope().first()
        if (vaultId in scope) {
            preferences.setQuickUnlockScope(scope - vaultId)
        }
    }

    /**
     * ★ **刚刚通过门锁的人**：补建该库的房间信封（#243）。
     *
     * 与 [autoEnrollUnlocked] 的唯一差别是**多一道房钥匙守卫** —— 它属于那些
     * "门锁可能还没开"的调用点（主密码路径）。已经过了门锁的调用点直接走
     * [autoEnrollUnlocked] 即可。
     *
     * ## ★ [force] 为什么必须由调用方显式指定（2026-11-04，#246）
     *
     * 本方法有**两个语义完全不同**的调用点，而它们对"已有信封"的处置正好相反：
     *
     * | 调用点 | 前置事实 | 正确动作 |
     * |---|---|---|
     * | `LocalUnlockFanout` 的补建（经 `enrollVaultAfterFingerprintUnlock`） | 刚刚开门**失败过**（`NotEnrolled` / `Damaged`）⇒ 有信封≠能用 | `force = true` 重封 |
     * | `unlockKdbxInternal` 解锁成功后（#243） | 刚刚**成功**开库，信封多半本来就是好的 | `force = false` 有信封就短路 |
     *
     * 旧实现把 `force` 写死成 `true`，于是第二个调用点——那条**每次主密码解锁都会走**
     * 的路径——每次都要 `prepareKdbx` → `Kdbx.verify` → **一次完整 Argon2**
     * （真机 64 MiB / 46 轮）才能发现"原来信封早就有了"。
     * 用户的原话就是「我输入了密码，依然很慢很慢」。
     *
     * ⚠️ 代价是"改了主密码后，信封里那份是旧的"要等**下次指纹解锁**才发现
     * （`StaleCredentials` → `resealStaleRoomIfPossible` 自愈）。
     * 这个取舍是有意的：把一次 KDF 摊在**每一次**解锁上，换一次性的自愈延迟，不划算。
     *
     * ⚠️ 为什么放在这里而不是 `VaultRepositoryImpl`：那个类的函数数**正好卡在**
     * detekt `TooManyFunctions` 的 40 上限（见其 KDoc）。这项逻辑本来也只用到
     * 本类已有的三个依赖（`houseKeyStore` / `preferences` / `vaultDao`），
     * 放在本类是自然的归属，不是"为了躲 lint 塞过来"。
     *
     * @param force 见上表。默认 `false`：绝大多数调用点是"刚成功解锁"，不该白烧 KDF。
     *   ⚠️ 刻意放在 [masterPasswordOf] **之前**：后者是 lambda，必须留在最后一位
     *   才能继续被当 trailing lambda 用（既有调用点都是 `enrollJustAuthenticated(id) { … }`）。
     * @param masterPasswordOf 见 [autoEnrollNewVault]。
     * @return true = 信封已就位（本次新建的，或本来就有）。
     */
    suspend fun enrollJustAuthenticated(
        vaultId: String,
        force: Boolean = false,
        masterPasswordOf: suspend (String) -> String?,
    ): Boolean = withContext(Dispatchers.IO) {
        // 房钥匙不在内存 ⇒ 物理上封不了（别落一个谁也解不开的孤儿信封）。
        // ⚠️ 先判它再备料：KDBX 的备料要跑一次完整 KDF，没有房钥匙时那条路
        //   必然在 `sealRoom` 抛异常 —— 白烧一次 Argon2 再失败，用户白等。
        if (!houseKeyStore.isUnlocked) return@withContext false
        autoEnrollUnlocked(vaultId, masterPasswordOf, force = force)
    }
}
