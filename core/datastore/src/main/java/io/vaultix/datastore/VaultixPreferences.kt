/*
 * Vaultix — core:datastore
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 偏好默认值（public：UI 层 stateIn 初始值与偏好层默认保持单一真值源）。
 */
object VaultixPreferencesDefaults {
    /** 回收站自动清理档位（天；0 = 不自动清空；语义对齐 Bastion autoDeleteDays）。 */
    const val TRASH_AUTO_DELETE_DAYS = 30

    /** 主题模式（对齐 Bastion themeMode：system / light / dark）。 */
    const val THEME_MODE = "system"
}

/**
 * 应用设置（非敏感）。
 *
 * 敏感凭据（token、主密钥材料）一律走 [SecureCredentialStore]，不放在这里。
 */
@Singleton
class VaultixPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private companion object {
        /**
         * 旧版自动锁定档位键（裸 Int：0=立即 / N=分钟 / 负=从不）。
         *
         * 2026-09-11 起**只用于一次性迁移**，不再作为读取来源。保留键名以免用户数据丢失。
         * ⚠️ 2026-09-30 起它仍是**全局默认档位**回退链的第二环（见 [globalDefaultTimeout]）。
         */
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")

        /**
         * **全局默认**自动锁定档位键（`VaultTimeout.toStorageValue()` 的编码）。
         *
         * 2026-09-30 起**转正**为「全局默认」的唯一真源（此前是 D3 之前的旧全局键，
         * 被降格为回退值；本轮模型修订把它的地位恢复回来 —— 见 [globalVaultTimeout]）。
         */
        val VAULT_TIMEOUT = intPreferencesKey("vault_timeout")

        /** 每库**覆盖**键的前缀（唯一拼接处；清理时按前缀扫）。 */
        const val PER_VAULT_TIMEOUT_PREFIX = "vault_timeout::"

        /**
         * 「档位作用域」迁移标记（2026-09-30）。
         *
         * 取代了已删除的 `auto_lock_migrated_v2`：那个标记**没有任何写入点**
         * （见 [globalDefaultTimeout] 的说明），作为读取门控是纯粹的隐患。
         * 本标记的用途只有一个：让 [purgeLegacyPerVaultTimeoutsOnce] 只跑一次。
         */
        val TIMEOUT_SCOPE_MIGRATED = booleanPreferencesKey("vault_timeout_scope_v2")

        val CLIPBOARD_CLEAR_MS = longPreferencesKey("clipboard_clear_ms")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SCREEN_SECURITY = booleanPreferencesKey("screen_security")
        val DEFAULT_VAULT_ID = stringPreferencesKey("default_vault_id")

        /**
         * **当前活跃库**（本次会话正在看哪一个）—— 与 [DEFAULT_VAULT_ID] 是**两个语义**。
         *
         * 拆分背景（`.ai/decisions/库选择与快速解锁-逻辑定稿.md` §3）：原先切库
         * （`ActiveVaultStore.select`）直接写 `default_vault_id`，于是用户「临时切去看一眼
         * 另一个库」会**静默永久改掉默认库**，下次冷启动进的就是那个临时看的库。
         *
         * - `active_vault_id`：用户切库时写（会随后被清理，仅表达"这次会话看谁"）；
         * - `default_vault_id`：**只在设置里明确修改时**写 —— 冷启动先开哪个。
         */
        val ACTIVE_VAULT_ID = stringPreferencesKey("active_vault_id")
        val QUICK_UNLOCK_PROMPT_DISMISSED = booleanPreferencesKey("quick_unlock_prompt_dismissed")

        /**
         * **指纹门锁信封存在性**（全局；「房子化」定稿 2026-09-28 起的新键）。
         *
         * 语义：`true` = [SecureCredentialStore] 里存在「KEK 包裹的房子钥匙」信封
         * —— 全 app 仅此一把指纹门锁信封，**没有每库粒度**。
         *
         * ⚠️ **真源是信封本身**，本键只是 UI 响应式的镜像（SharedPreferences 没有
         * Flow，设置页开关需要响应式源）。写镜像必须与信封的建立/删除发生在
         * **同一次动作**里，单独操作必造成双源漂移。
         *
         * 取代旧的每库 `local_unlock_enabled_*` 键：两把门锁包的是**同一把**房钥匙，
         * 「这个库用哪种方式解锁」在两级钥匙层级下不存在（定稿 §5.1 删除清单）。
         */
        val FINGERPRINT_LOCK_ENROLLED = booleanPreferencesKey("fingerprint_lock_enrolled")

        /**
         * **PIN 门锁信封存在性**（全局；与 [FINGERPRINT_LOCK_ENROLLED] 同批新键）。
         *
         * 语义：`true` = [SecureCredentialStore] 里存在「Argon2id(PIN) 包裹的房子钥匙」
         * 信封。真源同样是信封本身；PIN 失败计数在房子化后是**全局一份**（定稿 §6），
         * 不再按库。
         */
        val PIN_LOCK_ENROLLED = booleanPreferencesKey("pin_lock_enrolled")

        /**
         * **快速解锁的生效范围**：哪些库建了「房间信封」—— 勾选的库用房子钥匙
         * **纯软件**封装凭据（房子化定稿后的语义：勾库不碰指纹、不碰门锁）。
         * 指纹与 PIN 共用同一份范围。
         *
         * 2026-09-16 新增（此前「快速解锁」被做成每库独占的三选一，改造背景见
         * `库选择与快速解锁-逻辑定稿.md` §4.7 —— 用户要的是**能力级**：一个开关
         * 覆盖多个库）。
         *
         * ⚠️ 这里**只存范围**，不存「门锁总开关」。两个门锁的 ON/OFF 由门锁信封
         * 存在性（[FINGERPRINT_LOCK_ENROLLED] / [PIN_LOCK_ENROLLED]，真源 = 信封本身）
         * 推导（见 `QuickUnlockController`）：若另存一个开关字段，就会出现"开关说开着、
         * 信封却是空的"这种双源漂移 —— 那正是 #93「谎报状态的开关」的成因，别再造一个。
         */
        val QUICK_UNLOCK_SCOPE = stringSetPreferencesKey("quick_unlock_scope")

        /**
         * 「生效范围是否已被用户确认过」（2026-09-17 新增）。
         *
         * 为什么需要这个**独立的**标记，而不是拿 [QUICK_UNLOCK_SCOPE] 的空集兼职：
         * 空集在这里有确定含义 —— 「一个库都不要」（等价于整体未启用）。若把"空集"再解释成
         * "从未配置过 ⇒ 默认全部库"，就出现**一个值两种含义**：用户主动全部取消勾选之后，
         * 界面会反过来告诉他"全都勾上了"。这正是「空有三态」那条纪律要防的塌缩。
         *
         * ⇒ 拆成两个事实：`false` = 从未配置 ⇒ 向导**默认全勾**（用户 99% 想要的效果）；
         * `true` = 用户确认过范围 ⇒ 空集就是"一个都不要"，如实呈现。
         */
        val QUICK_UNLOCK_SCOPE_CONFIRMED = booleanPreferencesKey("quick_unlock_scope_confirmed")

        /**
         * ★ **已废弃**（旧「每库信封」模型的元数据，2026-09-29 房子化批次 2 起仅用于
         * **一次性清理**）：按库的「本地解锁已启用」标记。
         *
         * 保留这两个前缀**不是**为了兼容读写（旧读写代码连同 `PinUnlockStore` 等已于
         * 批次 1 整体删除，定稿 §8 明确**不写兼容层**），而是为了**认出并删掉**老用户
         * 设备上残留的垃圾键 —— 不枚举就永远删不干净（键里带 vaultId，无法穷举）。
         *
         * ⚠️ 键名**不得**再改动：改了就认不出老数据，残留变成永久垃圾。
         */
        const val LEGACY_LOCAL_UNLOCK_ENABLED_PREFIX = "local_unlock_enabled_"
        const val LEGACY_PIN_UNLOCK_ENABLED_PREFIX = "pin_unlock_enabled_"

        val TRASH_AUTO_DELETE_DAYS = intPreferencesKey("trash_auto_delete_days")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val OLED_PURE_BLACK = booleanPreferencesKey("oled_pure_black")
        val AUTOFILL_SAVE_PROMPT = booleanPreferencesKey("autofill_save_prompt")
        val AUTO_COPY_TOTP = booleanPreferencesKey("auto_copy_totp")
        val AUTOFILL_BASE_DOMAIN_MATCH = booleanPreferencesKey("autofill_base_domain_match")
        val AUTOFILL_EXACT_DOMAIN_ONLY = booleanPreferencesKey("autofill_exact_domain_only")
        val FILL_ASSIST_ENABLED = booleanPreferencesKey("fill_assist_enabled")
        val ITEMS_GROUP_MODE = stringPreferencesKey("items_group_mode")
        val ITEMS_CARD_DISPLAY_MODE = stringPreferencesKey("items_card_display_mode")
        val ITEMS_SHOW_ICON = booleanPreferencesKey("items_show_icon")

        /**
         * 检查更新时，是否把「前往下载」的地址换成**国内加速镜像**（2026-09-28 用户要求）。
         *
         * 默认 `false`（= 用 GitHub 原始地址）：镜像服务由第三方提供，可用性不保证；
         * 让用户**显式开启**比默认偷偷改地址更诚实 —— 尤其是本 App 的下载页是用户
         * 要去拿安装包的地方，地址被替换这种事必须让用户知情且可关闭。
         *
         * ⚠️ 只影响**打开下载页**，不影响 `api.github.com` 的检查请求
         * （理由见 `UpdateChecker.MIRROR_PREFIXES`）。
         */
        val UPDATE_USE_MIRROR = booleanPreferencesKey("update_use_mirror")

        /**
         * 验证码页是否**隐藏数字**（2026-09-21 用户要求）。
         *
         * ⚠️ 这里存的是**当前状态本身**，不是"默认值" —— 用户明确要
         * 「点击隐藏后，不再点击，重开 App 也保持隐藏」。
         * 参考实现 Bastion 只持久化"默认是否隐藏"（当前展开态是 `remember`，离开即复位），
         * **两者语义不同**，别照抄。
         */
        val TOTP_CODES_HIDDEN = booleanPreferencesKey("totp_codes_hidden")

        /**
         * 验证码**临期**（剩余 ≤ `TOTP_HOT_WARNING_SECONDS`）时，复制的是**下一个码**。
         *
         * 2026-09-21 加开关（行为本身 2026-09-18 就已在 `TotpCodesScreen` 实现）。
         * ⚠️ 语义是"你点复制的那一刻给哪个码"，**不是**定时器自动写剪贴板 ——
         * 参考实现 Bastion 也是如此（其 `codeToCopy` 只决定"复制哪一个"）。
         */
        val TOTP_COPY_NEXT_ON_EXPIRING = booleanPreferencesKey("totp_copy_next_on_expiring")

        const val DEFAULT_CLIPBOARD_CLEAR_MS = 30 * 1000L
    }

    private val safeData: Flow<Preferences> = dataStore.data
        .catch { error ->
            // 读取异常（首次运行或文件损坏）时退回空配置，避免整条流挂掉
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    /**
     * **某库**的自动锁定档位（有效值 = 覆盖 ?: 全局默认）。
     *
     * 存储键 `vault_timeout::<vaultId>`（**仅当用户为该库单独指定过**才存在）。
     *
     * ## ★ 2026-09-30 下午：模型由「每库一份」修订为「**全局默认 + 每库可覆盖**」
     *
     * 用户拍板（推翻 D3 的"档位每库一份"）：主流密码管理器（Bitwarden 按账号存、
     * KeePassDX 是 App 级、1Password 全局）之所以"一个设置就够"，前提是它们
     * **一次只有一个解锁对象**；Vaultix 是两个异构后端同时解锁，且两者重开成本
     * 差 100 倍（BW ≈33ms / KDBX ≈1.2s+），所以"全局一个值"要保留为**默认**，
     * 而"某库单独指定"降级为**覆盖**。
     *
     * 回退链（见 [globalDefaultTimeout]）：**更具体者优先** —— 该库的显式键 →
     * 全局键 [VAULT_TIMEOUT] → 更旧的 [AUTO_LOCK_MINUTES] → [VaultTimeout.DEFAULT]。
     *
     * 旧键 `auto_lock_minutes` 的语义陷阱见 [VaultTimeout.fromLegacyMinutes]：
     * 它的 `-1` 表示「从不」，而新模型的 `-1` 表示「重启时锁定」——**正好相反**。
     */
    fun vaultTimeout(vaultId: String): Flow<VaultTimeout> = safeData.map { prefs ->
        resolveVaultTimeout(prefs, vaultId)
    }

    /**
     * **全局默认**档位 —— 所有没单独指定档位的库共用这一档。
     *
     * 设置页「自动锁定」行读写的就是它（副标题「所有密码库默认：…」）。
     */
    fun globalVaultTimeout(): Flow<VaultTimeout> = safeData.map { globalDefaultTimeout(it) }

    /**
     * 某库的**显式覆盖**；`null` = 该库没单独指定过 ⇒ **跟随全局**。
     *
     * 与 [vaultTimeout]（有效值）的区别就是这个 `null`：档位对话框靠它决定
     * 「跟随全局设置」那一项是否被选中（两者都返回同一个数时，没法从值上分辨）。
     */
    fun vaultTimeoutOverride(vaultId: String): Flow<VaultTimeout?> = safeData.map { prefs ->
        resolveVaultTimeoutOverride(prefs, vaultId)
    }

    /**
     * 有效档位的**纯函数**形式（不碰 DataStore）——回退顺序的唯一判定点。
     *
     * ⚠️ 抽出来只有一个目的：让 `VaultTimeoutScopeTest` 能用内存 `Preferences`
     * 直接钉住**回退顺序**（覆盖 → 全局 → 更旧的裸分钟键 → 默认）。
     * 这条顺序是本模块最容易写反的地方，而它写反的症状极隐蔽
     * （"改了全局、某个库纹丝不动"）。
     */
    internal fun resolveVaultTimeout(prefs: Preferences, vaultId: String): VaultTimeout =
        resolveVaultTimeoutOverride(prefs, vaultId) ?: globalDefaultTimeout(prefs)

    /**
     * 覆盖的**纯函数**形式；`null` = 该库跟随全局（这一区分是档位对话框初值的依据）。
     */
    internal fun resolveVaultTimeoutOverride(prefs: Preferences, vaultId: String): VaultTimeout? =
        prefs[vaultTimeoutKey(vaultId)]?.let { VaultTimeout.fromStorageValue(it) }

    /**
     * 全局默认档位的回退链：全局键 → 更旧的裸分钟键 → [VaultTimeout.DEFAULT]。
     *
     * ⚠️ **2026-09-30 去掉了原来的 `auto_lock_migrated_v2` 门控**。理由（取证得出结论）：
     * 那个标记**全仓库没有任何写入点**（`grep` 只剩定义与读取），而 `VAULT_TIMEOUT`
     * 有历史写入 ⇒「全局键有值」本身就证明迁移发生过。留着它只会让"读哪把键"
     * 取决于一个没人维护的布尔：一旦某台设备的标记是 false，**新写的全局值就静默读不到**。
     * ⇒ 改成**纯"更具体者优先"**（与每库覆盖同一条规则），这条链就自洽了。
     *
     * `internal`（而非 private）：纯函数，单测直接钉（见 [resolveVaultTimeout]）。
     */
    internal fun globalDefaultTimeout(prefs: Preferences): VaultTimeout =
        prefs[VAULT_TIMEOUT]
            ?.let { VaultTimeout.fromStorageValue(it) }
            ?: prefs[AUTO_LOCK_MINUTES]
                ?.let { VaultTimeout.fromLegacyMinutes(it) }
            ?: VaultTimeout.DEFAULT

    /**
     * 写**全局默认**档位（不影响任何已单独指定档位的库）。
     */
    suspend fun setGlobalVaultTimeout(value: VaultTimeout) {
        dataStore.edit { prefs ->
            prefs[VAULT_TIMEOUT] = VaultTimeout.toStorageValue(value)
        }
    }

    /**
     * 写**某库**的档位（= 建立一条**覆盖**）。
     *
     * ⚠️ **只写这一个键**，不碰全局键、也不清 `auto_lock_minutes` ——
     * 后者仍是"更老的安装"的回退值，清了就是迁移事故（见 [vaultTimeout] 的 KDoc）。
     */
    suspend fun setVaultTimeout(vaultId: String, value: VaultTimeout) {
        dataStore.edit { prefs ->
            prefs[vaultTimeoutKey(vaultId)] = VaultTimeout.toStorageValue(value)
        }
    }

    /**
     * 删掉某库的覆盖 ⇒ 它回到**跟随全局**。
     *
     * ⚠️ 只删**这一个库**的键；其它库的覆盖与全局键都不动。
     */
    suspend fun clearVaultTimeoutOverride(vaultId: String) {
        dataStore.edit { prefs -> prefs.remove(vaultTimeoutKey(vaultId)) }
    }

    /**
     * 一次性清理：删掉 **D3 那版 UI（"每库一份"）留下的全部覆盖键**。
     *
     * ## 为什么这不是"删用户数据"
     *
     * D3 那一版的入口是**设置首页的一行**（副标题给活跃库的档位），用户点它时的心智
     * 就是"设一个全局档位" —— 但它写的是**活跃库的键** ⇒ 每点一次就给一个库留下一条
     * 覆盖。那些覆盖**不代表"这个库要单独指定"**，只代表"当时活跃的是它"。
     * 值也与全局相同（同一台设备取证：3 条键的值一致）。
     *
     * 不清理的症状很具体：用户改了新的全局行，**两个库纹丝不动** ——
     * 因为两条覆盖把它们钉住了 ⇒ 用户必然报「改了没用」。
     *
     * 幂等：由 [TIMEOUT_SCOPE_MIGRATED] 门控，只跑一次；此后用户**有意**建立的覆盖
     * （在 ⋮ 里显式选的档位）不会被清掉。
     */
    suspend fun purgeLegacyPerVaultTimeoutsOnce() {
        dataStore.edit { prefs ->
            if (prefs[TIMEOUT_SCOPE_MIGRATED] == true) return@edit
            prefs.asMap().keys
                .filterIsInstance<Preferences.Key<Int>>()
                .filter { it.name.startsWith(PER_VAULT_TIMEOUT_PREFIX) }
                .forEach { prefs.remove(it) }
            prefs[TIMEOUT_SCOPE_MIGRATED] = true
        }
    }

    private fun vaultTimeoutKey(vaultId: String) = intPreferencesKey("$PER_VAULT_TIMEOUT_PREFIX$vaultId")

    /** 敏感内容复制后自动清空剪贴板的延迟（0 = 不清除）。 */
    val clipboardClearMs: Flow<Long> =
        safeData.map { it[CLIPBOARD_CLEAR_MS] ?: DEFAULT_CLIPBOARD_CLEAR_MS }

    val dynamicColor: Flow<Boolean> =
        safeData.map { it[DYNAMIC_COLOR] ?: true }

    /** 是否开启 FLAG_SECURE（防截屏 / 防最近任务缩略图）。 */
    val screenSecurity: Flow<Boolean> =
        safeData.map { it[SCREEN_SECURITY] ?: true }

    /**
     * 回收站自动清理档位（天；0 = 不自动清空，语义见 [VaultixPreferencesDefaults.TRASH_AUTO_DELETE_DAYS]）。
     * 清理时机 = 进入回收站（TrashViewModel init），策略与倒计时口径见
     * [io.vaultix.common.TrashCleanupPolicy]。
     */
    val trashAutoDeleteDays: Flow<Int> =
        safeData.map { it[TRASH_AUTO_DELETE_DAYS] ?: VaultixPreferencesDefaults.TRASH_AUTO_DELETE_DAYS }

    /**
     * 主题模式（`system` / `light` / `dark`，语义对齐 Bastion themeMode）。
     * 由 MainActivity 收集驱动 [io.vaultix.vaultix.ui.theme.VaultixTheme]。
     */
    val themeMode: Flow<String> =
        safeData.map { it[THEME_MODE] ?: VaultixPreferencesDefaults.THEME_MODE }

    /** OLED 纯黑（对齐 Bastion oledPureBlackEnabled）：深色模式下 surface/background 用纯黑。 */
    val oledPureBlack: Flow<Boolean> =
        safeData.map { it[OLED_PURE_BLACK] ?: false }

    /**
     * 自动填充保存提示（对齐 Bitwarden `isAutofillSavePromptDisabled` 的反向开关）：
     * 在 App / 网页提交登录表单后询问是否保存 / 更新凭据。默认开启。
     */
    val autofillSavePrompt: Flow<Boolean> =
        safeData.map { it[AUTOFILL_SAVE_PROMPT] ?: true }

    /**
     * 自动填充后自动复制验证码（对齐 Bitwarden `isAutoCopyTotpDisabled = false`）：
     * 条目带 TOTP 而页面没有验证码框时，填充完成即把当前验证码放进剪贴板，
     * 用户直接粘贴即可完成 2FA 第二步。默认开启。
     */
    val autoCopyTotp: Flow<Boolean> =
        safeData.map { it[AUTO_COPY_TOTP] ?: true }

    /**
     * 检查更新时用国内加速镜像打开下载页。**默认关闭**（用 GitHub 原始地址）。
     *
     * 默认关的理由见 [Keys.UPDATE_USE_MIRROR] 的注释：下载页是要去拿安装包的地方，
     * 地址被第三方代理这件事必须由用户显式选择，不能默认替他决定。
     */
    val updateUseMirror: Flow<Boolean> =
        safeData.map { it[UPDATE_USE_MIRROR] ?: false }

    /**
     * 允许「基域 / 子域名」匹配（对齐 Bastion `allowBaseDomainMatch`，Bitwarden 默认开）。
     *
     * 例：条目存的是 `example.com`，页面在 `login.example.com` 时也能命中。关掉后只有
     * 域名完全一致才填充——更严格、更省心，但跨子域登录会填不出来。
     */
    val autofillBaseDomainMatch: Flow<Boolean> =
        safeData.map { it[AUTOFILL_BASE_DOMAIN_MATCH] ?: true }

    /**
     * 仅精确域匹配（对齐 Bastion `exactDomainOnly`，Bitwarden 默认关）。
     *
     * 开启后忽略条目上配的「起始匹配 / 正则匹配」等宽松规则，只认域名完全相等。
     * 与 [autofillBaseDomainMatch] 独立生效：两者都开 = 只认精确域名。
     */
    val autofillExactDomainOnly: Flow<Boolean> =
        safeData.map { it[AUTOFILL_EXACT_DOMAIN_ONLY] ?: false }

    /**
     * 「填充辅助」（Fill Assist，对齐 Bitwarden `isFillAssistEnabled`）。
     *
     * 开启后按**站点级选择器规则**精确识别账号 / 密码 / 卡号字段（规则表来自服务端下发的
     * Bitwarden map-the-web 清单），而不是靠文本启发式猜。默认开启：规则只覆盖白名单站点，
     * 未命中的主机完全走原启发式，行为不变。
     *
     * ⚠️ 上游对**特性**有双重门控（feature flag `fill-assist-targeting-rules` +
     * 设置项 `isFillAssistEnabled`），我们只有后者 —— 因为服务端 flag 在自建
     * Vaultwarden 上通常不返回，照搬会让功能永远关着（用户要求「有个独立按钮能开关」）。
     */
    val fillAssistEnabled: Flow<Boolean> =
        safeData.map { it[FILL_ASSIST_ENABLED] ?: true }

    /**
     * 条目列表的分组方式（`none` / `type` / `folder` / `initial`）。
     *
     * 默认 `none` = 不分组，与历史观感一致；分组是**列表展示**偏好，不影响任何数据。
     * 取值到枚举的映射在 UI 层（`ItemsGroupMode.from`），偏好层只存字符串。
     */
    val itemsGroupMode: Flow<String> =
        safeData.map { it[ITEMS_GROUP_MODE] ?: "none" }

    /**
     * 条目卡片的**信息密度**（`all` / `title_username` / `title_only`）。
     *
     * 语义对齐 Bastion `PasswordCardDisplayMode`（SHOW_ALL / TITLE_USERNAME / TITLE_ONLY）：
     * 卡片上到底显示多少字段。默认 `all`（标题 + 用户名/类型徽标，与历史观感一致）。
     */
    val itemsCardDisplayMode: Flow<String> =
        safeData.map { it[ITEMS_CARD_DISPLAY_MODE] ?: "all" }

    /**
     * 条目卡片是否显示**左侧图标**（字母头像 / 品牌图标）。
     *
     * 对齐 Bastion `iconCardsEnabled`。关掉后卡片只剩文字，密度更高、也少一层绘制。
     */
    val itemsShowIcon: Flow<Boolean> =
        safeData.map { it[ITEMS_SHOW_ICON] ?: true }

    /**
     * 验证码页是否隐藏数字（**跨重启保持**；默认 `false` = 展开）。
     *
     * 用户 2026-09-21 要求：点顶栏「验证码」切换；隐藏时只显示前若干位；
     * 隐藏**不影响**复制与自动填充（那两条走原始码，遮罩只作用在渲染层）。
     */
    val totpCodesHidden: Flow<Boolean> =
        safeData.map { it[TOTP_CODES_HIDDEN] ?: false }

    /**
     * 验证码**临期**（剩余 ≤ `TOTP_HOT_WARNING_SECONDS`）时复制下一个码。
     *
     * 默认 `true` = 保持既有行为（2026-09-18 起就是这样，加开关只为让用户能关掉，
     * 不静默改变已有观感）。
     */
    val totpCopyNextOnExpiring: Flow<Boolean> =
        safeData.map { it[TOTP_COPY_NEXT_ON_EXPIRING] ?: true }

    val defaultVaultId: Flow<String?> = safeData.map { it[DEFAULT_VAULT_ID] }

    /**
     * **当前活跃库 id**（本次会话看的是哪个）。
     *
     * ⚠️ 与 [defaultVaultId] 的区别是本键存在的原因：切库只动这个键，
     * **不动** [defaultVaultId] —— 否则「临时切库」会静默改掉冷启动默认库。
     */
    val activeVaultId: Flow<String?> = safeData.map { it[ACTIVE_VAULT_ID] }

    /**
     * 指纹门锁信封存在性（**全局**镜像；真源 = [SecureCredentialStore] 的门锁信封键）。
     *
     * 房子化（2026-09-28 定稿）后「指纹快解开没开」是全屋一个事实：门锁信封
     * （KEK 包裹的房子钥匙）存在 = 开。不存在每库粒度的指纹开关（定稿 §5.1）。
     */
    val fingerprintLockEnrolled: Flow<Boolean> =
        safeData.map { it[FINGERPRINT_LOCK_ENROLLED] ?: false }

    /**
     * 写指纹门锁镜像。⚠️ 只在与门锁信封建立/删除的**同一次动作**里调用 ——
     * 镜像单独走会漂移（真源是信封本身，这里只是 UI 响应式源）。
     */
    suspend fun setFingerprintLockEnrolled(enrolled: Boolean) {
        dataStore.edit { prefs ->
            if (enrolled) {
                prefs[FINGERPRINT_LOCK_ENROLLED] = true
            } else {
                prefs.remove(FINGERPRINT_LOCK_ENROLLED)
            }
        }
    }

    /**
     * PIN 门锁信封存在性（**全局**镜像；真源 = [SecureCredentialStore] 的门锁信封键）。
     *
     * ⚠️ 与指纹**分成两个键**而不是共用一个：两者是彼此独立的门锁
     * （PIN 不需要系统认证，指纹需要），用户可以只要其中之一。
     * 共用一个键会让「关掉指纹」顺手把 PIN 也关掉，那是静默的功能丢失。
     */
    val pinLockEnrolled: Flow<Boolean> =
        safeData.map { it[PIN_LOCK_ENROLLED] ?: false }

    /** 写 PIN 门锁镜像（约束同 [setFingerprintLockEnrolled]：与信封动作同批）。 */
    suspend fun setPinLockEnrolled(enrolled: Boolean) {
        dataStore.edit { prefs ->
            if (enrolled) {
                prefs[PIN_LOCK_ENROLLED] = true
            } else {
                prefs.remove(PIN_LOCK_ENROLLED)
            }
        }
    }

    /**
     * 快速解锁的**生效范围**（哪些库建了房间信封 = 勾选的库）。
     *
     * 空集 = 没有任何库纳入（等价于「每次输主密码」）；门锁信封是否另建
     * 见 [fingerprintLockEnrolled] / [pinLockEnrolled]，两个事实彼此独立。
     *
     * ⚠️ 读出来是 `Set<String>`，**顺序不保证稳定** —— 需要有序展示时由调用方按库表顺序
     * 自行排列，不要依赖这个集合的迭代顺序（DataStore 的 stringSet 不保证保序）。
     */
    fun quickUnlockScope(): Flow<Set<String>> =
        safeData.map { it[QUICK_UNLOCK_SCOPE] ?: emptySet() }

    suspend fun setQuickUnlockScope(vaultIds: Set<String>) {
        dataStore.edit { prefs ->
            // 空集就删键，不留一个"空集合"的残留（与门锁镜像 setter 同款取向）。
            if (vaultIds.isEmpty()) {
                prefs.remove(QUICK_UNLOCK_SCOPE)
            } else {
                prefs[QUICK_UNLOCK_SCOPE] = vaultIds
            }
        }
    }

    // ---- 旧体系（每库信封）残留：一次性检测与清理（房子化批次 2）----
    //
    // 旧模型的元数据键形如 `local_unlock_enabled_<vaultId>` —— **键里带 vaultId**，
    // 没法用固定 key 去读，只能把当前偏好表遍历一遍按前缀认领。
    // 这对方法只服务于 `LegacyQuickUnlockCleanup`，新代码**不得**再往这两个前缀下写东西。

    /**
     * 当前偏好表里属于旧「每库信封」模型的键名（空集 = 没有残留）。
     *
     * ⚠️ 只读键名、不读值：判定"有没有残留"不需要知道值，少一次反序列化。
     */
    suspend fun legacyQuickUnlockKeys(): Set<String> = withContext(Dispatchers.IO) {
        val snapshot = runCatching { dataStore.data.first() }.getOrNull() ?: return@withContext emptySet()
        snapshot.asMap().keys
            .map { it.name }
            .filter { name ->
                name.startsWith(LEGACY_LOCAL_UNLOCK_ENABLED_PREFIX) ||
                    name.startsWith(LEGACY_PIN_UNLOCK_ENABLED_PREFIX)
            }
            .toSet()
    }

    /**
     * 删掉 [keys] 里的旧键（幂等：本来没有也不报错）。
     *
     * @return 实际删掉的键数，供调用方如实记录。
     */
    suspend fun removeLegacyQuickUnlockKeys(keys: Set<String>): Int = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext 0
        val targets = keys.mapTo(mutableSetOf()) { booleanPreferencesKey(it) }
        dataStore.edit { prefs -> targets.forEach { prefs.remove(it) } }
        keys.size
    }

    /**
     * 用户是否**确认过**生效范围。
     *
     * `false` = 从未配置 ⇒ 「管理解锁方式」向导**默认全勾**；`true` = 已确认 ⇒ 空集就是
     * "一个都不要"。为什么这必须是独立事实而不是用空集兼职，见
     * [QUICK_UNLOCK_SCOPE_CONFIRMED] 的说明。
     */
    fun isQuickUnlockScopeConfirmed(): Flow<Boolean> =
        safeData.map { it[QUICK_UNLOCK_SCOPE_CONFIRMED] ?: false }

    /**
     * 写入范围并**同时**标记"已确认"。
     *
     * ⚠️ 两个事实必须在**同一次 edit** 里落盘：分两次写会留下一帧"范围已改、确认标记未改"的
     * 中间态，此时 `composeState` 可能正好读到一个自相矛盾的组合（例如范围为空但标记仍是
     * `false` ⇒ 界面把"一个都不要"显示成"全部库都在范围里"）。
     */
    suspend fun confirmQuickUnlockScope(vaultIds: Set<String>) {
        dataStore.edit { prefs ->
            if (vaultIds.isEmpty()) {
                prefs.remove(QUICK_UNLOCK_SCOPE)
            } else {
                prefs[QUICK_UNLOCK_SCOPE] = vaultIds
            }
            prefs[QUICK_UNLOCK_SCOPE_CONFIRMED] = true
        }
    }

    /**
     * 「待确认删除」的指纹（2026-09-16 新增）。
     *
     * 用途：同步时若发现「要删的本地行」数量可疑，**先不删**，把这批行的指纹存下来并阻断；
     * 下次同步若**仍是同一批**，才认为服务端确实如此、放行删除。
     *
     * 这样能把两种在服务端侧**表现完全相同**的情况分开：
     * - 用户在官方网页端真的删了（下次同步仍是同一批）→ 放行；
     * - 服务端瞬时故障返回不完整数据（下次同步就恢复了）→ 不会两次相同。
     *
     * ⚠️ **必须持久化**（不能只放内存）：进程重启后若丢失，用户会永远停在
     * 「首次发现大量删除」的阻断里，怎么同步都删不掉那批行。
     *
     * 值 = 待删 id 集合排序后的 `hashCode()`；**null = 当前没有待确认的删除**。
     */
    fun pendingPruneFingerprint(vaultId: String): Flow<Int?> =
        safeData.map { it[pendingPruneKey(vaultId)] }

    suspend fun setPendingPruneFingerprint(vaultId: String, fingerprint: Int?) {
        dataStore.edit { prefs ->
            if (fingerprint == null) {
                prefs.remove(pendingPruneKey(vaultId))
            } else {
                prefs[pendingPruneKey(vaultId)] = fingerprint
            }
        }
    }

    private fun pendingPruneKey(vaultId: String) =
        intPreferencesKey("pending_prune_fp_$vaultId")

    /**
     * KDBX 库的 keyfile URI（按库；null / 无记录 = 该库不用 keyfile）。
     *
     * 只存 **URI 字符串**，不存文件内容 —— 内容由 SAF 授权在需要时现读
     * （授权经 `takePersistableUriPermission` 跨进程重启有效）。
     */
    fun kdbxKeyFileUri(vaultId: String): Flow<String?> =
        safeData.map { it[kdbxKeyFileKey(vaultId)] }

    suspend fun setKdbxKeyFileUri(vaultId: String, uri: String?) {
        dataStore.edit { prefs ->
            if (uri.isNullOrBlank()) {
                prefs.remove(kdbxKeyFileKey(vaultId))
            } else {
                prefs[kdbxKeyFileKey(vaultId)] = uri
            }
        }
    }

    private fun kdbxKeyFileKey(vaultId: String) =
        stringPreferencesKey("kdbx_keyfile_uri_$vaultId")

    /** 登录后「启用快速解锁」引导横幅是否已被用户拒绝（不再打扰，设置页仍可启用）。 */
    fun isQuickUnlockPromptDismissed(): Flow<Boolean> =
        safeData.map { it[QUICK_UNLOCK_PROMPT_DISMISSED] ?: false }

    suspend fun setQuickUnlockPromptDismissed(dismissed: Boolean) {
        dataStore.edit { prefs ->
            if (dismissed) {
                prefs[QUICK_UNLOCK_PROMPT_DISMISSED] = true
            } else {
                prefs.remove(QUICK_UNLOCK_PROMPT_DISMISSED)
            }
        }
    }

    suspend fun setClipboardClearMs(value: Long) {
        dataStore.edit { it[CLIPBOARD_CLEAR_MS] = value }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setScreenSecurity(enabled: Boolean) {
        dataStore.edit { it[SCREEN_SECURITY] = enabled }
    }

    /** 回收站自动清理档位（天；0 = 不自动清空）。 */
    suspend fun setTrashAutoDeleteDays(days: Int) {
        dataStore.edit { it[TRASH_AUTO_DELETE_DAYS] = days }
    }

    /** 主题模式（`system` / `light` / `dark`）。 */
    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[THEME_MODE] = mode }
    }

    /** OLED 纯黑（深色模式 surface/background 纯黑）。 */
    suspend fun setOledPureBlack(enabled: Boolean) {
        dataStore.edit { it[OLED_PURE_BLACK] = enabled }
    }

    /** 自动填充保存提示开关（关闭后登录成功不再询问保存）。 */
    suspend fun setAutofillSavePrompt(enabled: Boolean) {
        dataStore.edit { it[AUTOFILL_SAVE_PROMPT] = enabled }
    }

    /** 自动填充后自动复制验证码开关。 */
    suspend fun setAutoCopyTotp(enabled: Boolean) {
        dataStore.edit { it[AUTO_COPY_TOTP] = enabled }
    }

    /**
     * 检查更新时用国内加速镜像打开下载页（**默认关**，见 [Keys.UPDATE_USE_MIRROR]）。
     *
     * ⚠️ 关掉时**删键**而不是写 `false`：与 [setTotpCodesHidden] 的取向相反 ——
     * 那里 `false` 是用户的一个显式选择（"我要一直显示"），不能删；而这里的
     * `false` 恰好就是默认值，删键能让"用户从没碰过这个开关"与"用户明确关掉"
     * 在磁盘上一致（两种情况下行为本来就相同：都用 GitHub 原始地址）。
     * 若将来默认值改成 `true`，需要像 [setTotpCodesHidden] 那样改为无条件写入。
     */
    suspend fun setUpdateUseMirror(enabled: Boolean) {
        dataStore.edit { prefs ->
            if (enabled) {
                prefs[UPDATE_USE_MIRROR] = true
            } else {
                prefs.remove(UPDATE_USE_MIRROR)
            }
        }
    }

    suspend fun setAutofillBaseDomainMatch(enabled: Boolean) {
        dataStore.edit { it[AUTOFILL_BASE_DOMAIN_MATCH] = enabled }
    }

    suspend fun setAutofillExactDomainOnly(enabled: Boolean) {
        dataStore.edit { it[AUTOFILL_EXACT_DOMAIN_ONLY] = enabled }
    }

    /** 「填充辅助」开关（对齐 Bitwarden `isFillAssistEnabled = value`）。 */
    suspend fun setFillAssistEnabled(enabled: Boolean) {
        dataStore.edit { it[FILL_ASSIST_ENABLED] = enabled }
    }

    /** 条目列表分组方式（`none` / `type` / `folder` / `initial`）。 */
    suspend fun setItemsGroupMode(mode: String) {
        dataStore.edit { it[ITEMS_GROUP_MODE] = mode }
    }

    /** 条目卡片信息密度（`all` / `title_username` / `title_only`）。 */
    suspend fun setItemsCardDisplayMode(mode: String) {
        dataStore.edit { it[ITEMS_CARD_DISPLAY_MODE] = mode }
    }

    /** 条目卡片是否显示左侧图标。 */
    suspend fun setItemsShowIcon(enabled: Boolean) {
        dataStore.edit { it[ITEMS_SHOW_ICON] = enabled }
    }

    /**
     * 验证码页隐藏/显示数字。
     *
     * ⚠️ 写的是**当前状态**（不是"默认值"）—— 用户要求跨重启保持，见 [TOTP_CODES_HIDDEN]。
     * 刻意**不**做"关掉就删键"的取向（与其它布尔开关不同）：这里 `false` 是一个**有意义的
     * 用户选择**（"我要一直显示"），删键会让它退回默认值 —— 目前默认恰好也是 false，
     * 但一旦将来默认值改成 true，删键就会静默把用户的显式选择丢掉。
     */
    suspend fun setTotpCodesHidden(hidden: Boolean) {
        dataStore.edit { it[TOTP_CODES_HIDDEN] = hidden }
    }

    /** 验证码临期时复制下一个码（默认开）。 */
    suspend fun setTotpCopyNextOnExpiring(enabled: Boolean) {
        dataStore.edit { it[TOTP_COPY_NEXT_ON_EXPIRING] = enabled }
    }

    /**
     * 设置**默认库**（冷启动先开哪个；null = 未设置，冷启动退回既有逻辑）。
     *
     * ⚠️ **唯一写入点是设置页**（`SettingsViewModel.setDefaultVault`）。切库
     * （`ActiveVaultStore.select`）**必须**走 [setActiveVaultId]，否则会静默改掉
     * 用户明确设过的默认库。
     */
    suspend fun setDefaultVaultId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(DEFAULT_VAULT_ID) else prefs[DEFAULT_VAULT_ID] = id
        }
    }

    /**
     * 写**当前活跃库**（切库时调；不清 [defaultVaultId]）。
     *
     * ⚠️ 刻意**不做**「切库顺带写默认库」：那正是「临时切库覆盖默认库」这个 bug。
     */
    suspend fun setActiveVaultId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_VAULT_ID) else prefs[ACTIVE_VAULT_ID] = id
        }
    }

    /**
     * **仅当默认库为空时**写入 [id]（新库首次接入用）。
     *
     * 与 [setDefaultVaultId] 的分工：
     * - [setDefaultVaultId] = 用户**显式**改默认库（设置页），无条件覆盖；
     * - 本方法 = 系统在「新库接入」时**补一个缺省**，绝不覆盖用户已有的选择。
     *
     * 「读 + 写」在**同一次 `dataStore.edit`** 内完成：`edit` 是事务性的，
     * 块内读到的就是本次写入前的值 ⇒ 两个库并发接入时只有一个能写成功，
     * 不会出现「都读到空、都写入」导致默认库被后者覆盖。
     *
     * @return true = 本次写入了（调用方可提示「已设为默认库」）；false = 已有默认库，未动。
     */
    suspend fun trySetDefaultVaultIfAbsent(id: String): Boolean {
        var written = false
        dataStore.edit { prefs ->
            if (prefs[DEFAULT_VAULT_ID] == null) {
                prefs[DEFAULT_VAULT_ID] = id
                written = true
            }
        }
        return written
    }
}
