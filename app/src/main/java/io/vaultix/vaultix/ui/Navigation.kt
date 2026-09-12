package io.vaultix.vaultix.ui

import kotlinx.serialization.Serializable

/**
 * 类型安全导航路由（Docs/08 §1；navigation-compose 2.10 类型安全 API）。
 *
 * M1 最小闭环只用到 4 条：库列表 ↔ 添加库 / 解锁 / 条目列表。
 */
@Serializable
data object VaultListRoute

/**
 * **主界面**（解锁后的落点，Tab 容器：密码 / 验证码 / 卡包 / 设置 + 中央「+」）。
 *
 * ⚠️ **不带 `vaultId`**：对齐 Bastion「解锁即进主界面」——库是**筛选状态**
 * （[io.vaultix.vaultix.session.ActiveVaultStore]），不是导航参数
 * （依据 Docs/progress/main-shell-migration.md 方案 A4；Bastion `ui/main/`
 * 目录 grep `vaultId` 零命中已实证）。
 *
 * 二级页（[ItemRoute] / [TrashRoute] / [PasskeysRoute] / [AutofillSettingsRoute]）
 * 仍按原样 push，其中条目类页面继续携带 `vaultId`（条目属于具体库）。
 */
@Serializable
data object MainShellRoute

/**
 * 启动占位路由（无内容，仅居中 loading）。
 *
 * 作为 [io.vaultix.vaultix.ui.VaultixApp] 的 `startDestination`：库列表首帧到达前
 * 不定论，由 [io.vaultix.vaultix.ui.rootnav.RootNavState] 决定真正的落点
 * （首次使用 / 解锁 / 主功能图）。
 *
 * ⚠️ 不要删：它替 `NavHost` 承担了"首帧状态未知"的语义。若把 `startDestination`
 * 直接写成某个业务路由，首帧就必须猜状态，猜错会被固化到整个进程生命周期。
 */
@Serializable
data object SplashRoute

@Serializable
data object AddVaultRoute

/** 添加本地 KDBX（KeePass）库：SAF 选文件 → 主密码（+ 可选 keyfile）→ 解锁入库。 */
@Serializable
data object AddKdbxRoute

@Serializable
data object SettingsRoute

/** 自动填充二级设置页（设置首页「自动填充」入口进入；对齐 Bastion 的嵌套结构）。 */
@Serializable
data object AutofillSettingsRoute

@Serializable
data class UnlockRoute(val vaultId: String)

/**
 * 解锁**入口**路由。
 *
 * 由根导航 [io.vaultix.vaultix.ui.rootnav.RootNavState.VaultLocked] 直达：
 * 对齐 Bitwarden `RootNavState.VaultLocked -> VaultUnlockRoute.Standard`。
 * 与 [UnlockRoute] 的区别是它不预先绑定某个库，由 [UnlockViewModel] 自动选中
 * 第一个已锁定的库（无库时自动退回库列表）。
 *
 * [vaultId] 非空时表示**指定目标库**：根导航在「查看层锁」场景下携带该库 id，
 * 避免解锁页在「密钥仍在内存」的多库场景里选错库。空串 = 自动选。
 */
@Serializable
data class UnlockEntryRoute(val vaultId: String = "")

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
