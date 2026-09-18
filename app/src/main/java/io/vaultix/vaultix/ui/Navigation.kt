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

/**
 * 从**网盘**添加 KDBX 库（WebDAV / OneDrive）。
 *
 * 与 [AddKdbxRoute] 分开而不是合成一条：两者要填的东西完全不同
 * （一边是 SAF 选文件，一边是服务器 + 账号 / OAuth 登录），
 * 而"选哪一个"本身就已经由 [io.vaultix.vaultix.ui.common.AddVaultTypeDialog] 问过了。
 *
 * 无参数：来源在页面内选（方案 §16.3 与 §16.4 共用一个入口）。
 */
@Serializable
data object AddCloudVaultRoute

@Serializable
data object SettingsRoute

/** 自动填充二级设置页（设置首页「自动填充」入口进入；对齐 Bastion 的嵌套结构）。 */
@Serializable
data object AutofillSettingsRoute

/**
 * 权限引导二级页（设置首页「关于 → 权限管理」进入）。
 *
 * 2026-09-18 从「点一行直接跳系统应用信息页」改成**先进这一页**：应用信息页只列出
 * 权限名与开关，不解释**为什么要**（用户对密码管理器的权限是有戒心的，不解释等于心虚）。
 * 这一页把用途摊开讲清楚，再按状态给「就地授予 / 去设置」两种出口。
 *
 * 无参数：页面内容全部来自系统实时状态（`checkSelfPermission` / `BiometricManager`），
 * 与任何库、任何条目都无关。
 */
@Serializable
data object PermissionsRoute

/**
 * 密码库管理二级页（设置首页「密码库管理」入口进入）。
 *
 * 合并了原先散在两处的三件事：当前密码库 / 添加密码库（原「密码库」组）与
 * 快速解锁（原「解锁与隐私」组）—— 它们都是"库怎么管"，2026-09-15 用户要求放到一起。
 *
 * 无参数：选库 / 加库 / 配解锁方式都作用于库列表本身
 * （[io.vaultix.vaultix.session.ActiveVaultStore] / `VaultRepository`），
 * 不需要预先绑定某个 vaultId。
 */
@Serializable
data object VaultManagementRoute

/**
 * 网盘账号二级页（从「密码库管理」进入）。
 *
 * ★ 独立成页的理由是**状态寿命**：OneDrive 登录态 / WebDAV 凭据是**长寿命**的
 * （跨进程跨页面），而「添加密码库」是导航路由、一返回 ViewModel 即销毁
 * ⇒ 把长寿命状态存在短寿命页面里，必然"返回就没了"（用户实测）。
 * 详见 `.ai/decisions/设置页信息架构-定稿.md` §11.7。
 */
@Serializable
data object CloudAccountsRoute

/**
 * 导入 / 导出二级页（设置首页「数据管理 → 导入 / 导出」进入）。
 *
 * 无参数：导出 / 导入都作用于**当前活跃库**（[io.vaultix.vaultix.session.ActiveVaultStore]），
 * 与主界面各 Tab 同源，避免多传一个可能过期的 vaultId。
 */
@Serializable
data object ImportExportRoute

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
