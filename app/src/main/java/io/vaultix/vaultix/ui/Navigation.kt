package io.vaultix.vaultix.ui

import kotlinx.serialization.Serializable

/**
 * 类型安全导航路由（Docs/08 §1；navigation-compose 2.10 类型安全 API）。
 *
 * M1 最小闭环只用到 4 条：库列表 ↔ 添加库 / 解锁 / 条目列表。
 */
@Serializable
data object VaultListRoute

@Serializable
data object AddVaultRoute

@Serializable
data object SettingsRoute

/** 自动填充二级设置页（设置首页「自动填充」入口进入；对齐 Bastion 的嵌套结构）。 */
@Serializable
data object AutofillSettingsRoute

@Serializable
data class UnlockRoute(val vaultId: String)

/**
 * 解锁**入口**路由（无参数）。
 *
 * 由根导航 [io.vaultix.vaultix.ui.rootnav.RootNavState.VaultLocked] 直达：
 * 对齐 Bitwarden `RootNavState.VaultLocked -> VaultUnlockRoute.Standard`。
 * 与 [UnlockRoute] 的区别是它不预先绑定某个库，由 [UnlockViewModel] 自动选中
 * 第一个已锁定的库（无库时自动退回库列表）。
 */
@Serializable
data object UnlockEntryRoute

@Serializable
data class ItemsRoute(val vaultId: String)

@Serializable
data class TrashRoute(val vaultId: String)

@Serializable
data class ItemRoute(val vaultId: String, val itemId: String)

/** 验证码统一界面（从密码条目列表的入口进入）。 */
@Serializable
data class TotpCodesRoute(val vaultId: String)

/** 通行密钥列表（从验证码界面的「通行密钥」按钮进入）。 */
@Serializable
data class PasskeysRoute(val vaultId: String)
