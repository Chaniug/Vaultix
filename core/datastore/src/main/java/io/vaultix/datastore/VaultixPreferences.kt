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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
         */
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")

        /** 新版自动锁定档位键（`VaultTimeout.toStorageValue()` 的编码）。 */
        val VAULT_TIMEOUT = intPreferencesKey("vault_timeout")

        /** 迁移完成标记（避免每次读取都做一次转换）。 */
        val AUTO_LOCK_MIGRATED_V2 = booleanPreferencesKey("auto_lock_migrated_v2")

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
         * **快速解锁的生效范围**：哪些库纳入快速解锁（指纹与 PIN 共用同一份范围）。
         *
         * 2026-09-16 新增。此前「快速解锁」被做成**每库独占的三选一**，与用户原本的意图
         * （`库选择与快速解锁-逻辑定稿.md` §4.7：「增加一个生效范围，选取哪些库生效」）
         * 相反 —— 用户要的是**能力级**：一个开关覆盖多个库。
         *
         * ⚠️ 这里**只存范围**，不存「总开关」。开关的 ON/OFF 由「范围 + 每库信封是否存在」
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

        const val DEFAULT_CLIPBOARD_CLEAR_MS = 30 * 1000L
    }

    private val safeData: Flow<Preferences> = dataStore.data
        .catch { error ->
            // 读取异常（首次运行或文件损坏）时退回空配置，避免整条流挂掉
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    /**
     * 自动锁定档位（[VaultTimeout] 模型，对齐 Bitwarden）。
     *
     * **含一次性迁移**（2026-09-11 起）：旧键 `auto_lock_minutes` 用裸 Int 表达档位，
     * 其中 `-1` 表示「从不」；而新模型里 `-1`（`OnAppRestart`）表示「重启时锁定」——
     * **语义正好相反**。因此首次读取时把旧值按
     * [VaultTimeout.fromLegacyMinutes] 转换后写入新键，并置迁移标记；此后一律读新键。
     */
    val vaultTimeout: Flow<VaultTimeout> = safeData.map { prefs ->
        if (prefs[AUTO_LOCK_MIGRATED_V2] == true) {
            prefs[VAULT_TIMEOUT]
                ?.let { VaultTimeout.fromStorageValue(it) }
                ?: VaultTimeout.DEFAULT
        } else {
            // 未迁移：旧键有值则按其语义转换；没有则用默认档位。
            prefs[AUTO_LOCK_MINUTES]
                ?.let { VaultTimeout.fromLegacyMinutes(it) }
                ?: VaultTimeout.DEFAULT
        }
    }

    /**
     * 写入新档位。
     *
     * **同时清除旧键并置迁移标记**：否则下次读取时（标记为假）会被旧值覆盖，
     * 用户的修改看起来"没生效"。
     */
    suspend fun setVaultTimeout(value: VaultTimeout) {
        dataStore.edit { prefs ->
            prefs[VAULT_TIMEOUT] = VaultTimeout.toStorageValue(value)
            prefs[AUTO_LOCK_MIGRATED_V2] = true
            prefs.remove(AUTO_LOCK_MINUTES)
        }
    }

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

    val defaultVaultId: Flow<String?> = safeData.map { it[DEFAULT_VAULT_ID] }

    /**
     * **当前活跃库 id**（本次会话看的是哪个）。
     *
     * ⚠️ 与 [defaultVaultId] 的区别是本键存在的原因：切库只动这个键，
     * **不动** [defaultVaultId] —— 否则「临时切库」会静默改掉冷启动默认库。
     */
    val activeVaultId: Flow<String?> = safeData.map { it[ACTIVE_VAULT_ID] }

    /**
     * 本地快速解锁开关（按库）。仅为元数据：真正的包裹密钥密文在
     * [SecureCredentialStore]（key：local_unlock_key::<vaultId>）。
     */
    fun isLocalUnlockEnabled(vaultId: String): Flow<Boolean> =
        safeData.map { it[localUnlockKey(vaultId)] ?: false }

    suspend fun setLocalUnlockEnabled(vaultId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            if (enabled) {
                prefs[localUnlockKey(vaultId)] = true
            } else {
                prefs.remove(localUnlockKey(vaultId))
            }
        }
    }

    private fun localUnlockKey(vaultId: String) =
        booleanPreferencesKey("local_unlock_enabled_$vaultId")

    /**
     * 应用内 PIN 解锁开关（按库）。同样只是元数据：PIN 信封在
     * [SecureCredentialStore]（key：local_pin_key::<vaultId>）。
     *
     * ⚠️ 与快速解锁**分成两个键**而不是共用一个：两者是彼此独立的解锁手段
     * （PIN 不需要系统认证，指纹需要），用户可以只要其中之一。
     * 共用一个键会让「关掉指纹」顺手把 PIN 也关掉，那是静默的功能丢失。
     */
    fun isPinUnlockEnabled(vaultId: String): Flow<Boolean> =
        safeData.map { it[pinUnlockKey(vaultId)] ?: false }

    suspend fun setPinUnlockEnabled(vaultId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            if (enabled) {
                prefs[pinUnlockKey(vaultId)] = true
            } else {
                prefs.remove(pinUnlockKey(vaultId))
            }
        }
    }

    private fun pinUnlockKey(vaultId: String) =
        booleanPreferencesKey("pin_unlock_enabled_$vaultId")

    /**
     * 快速解锁的**生效范围**（哪些库纳入）。
     *
     * 空集 = 快速解锁整体未启用（等价于「每次输主密码」）。
     *
     * ⚠️ 读出来是 `Set<String>`，**顺序不保证稳定** —— 需要有序展示时由调用方按库表顺序
     * 自行排列，不要依赖这个集合的迭代顺序（DataStore 的 stringSet 不保证保序）。
     */
    fun quickUnlockScope(): Flow<Set<String>> =
        safeData.map { it[QUICK_UNLOCK_SCOPE] ?: emptySet() }

    suspend fun setQuickUnlockScope(vaultIds: Set<String>) {
        dataStore.edit { prefs ->
            // 空集就删键，不留一个"空集合"的残留（与 setLocalUnlockEnabled 同款取向）。
            if (vaultIds.isEmpty()) {
                prefs.remove(QUICK_UNLOCK_SCOPE)
            } else {
                prefs[QUICK_UNLOCK_SCOPE] = vaultIds
            }
        }
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
