package com.bastion.app.data

import androidx.compose.runtime.Immutable
import java.util.Date

/**
 * 密码条目列表展示用的投影 POJO（架构升级 C.3）。
 *
 * 仅包含列表卡片 / 搜索结果所需的轻量列，刻意排除以下大字段，以降低大 vault 首屏查询与内存开销：
 * - `sshKeyData` / `wifiMetadata` / `passkeyBindings`（JSON 元数据）
 * - `creditCardNumber` / `creditCardCVV` / `creditCardHolder` / `creditCardExpiry`（加密支付信息）
 * - Phase7 PII：`email` / `phone` / `addressLine` / `city` / `state` / `zipCode` / `country`
 * - `ssoRefEntryId` / `replicaGroupId` / `boundNoteId`（列表展示不依赖）
 *
 * **C.3.3 选项 A（已采用）**：`password` / `bitwardenFolderId` / `keepassGroupPath` **纳入**投影。
 * 原因见 [docs/architecture-phaseC-performance.md] 第 8 章——主列表流
 * [com.bastion.app.viewmodel.PasswordViewModel] 对每行做 `inspectSecretState(entry).plainValueOrEmpty()`
 * 解密以驱动「是否有密码」指示，并按 `bitwardenFolderId` / `keepassGroupPath` 做内存分组/文件夹筛选；
 * 排除它们会破坏既有行为。其余大字段仍排除，收益主要来自避免加载 JSON/blob/支付/PII。
 *
 * 列表点击进详情时，仍按 [PasswordEntry.id] 走 `getPasswordEntryById` 取完整实体。
 * 全 `val` 不可变，为 C.6 的 `@Immutable` 标注做准备。
 *
 * 投影列必须与列表 UI 实际读取字段保持一致（见 [com.bastion.app.ui.password.PasswordEntryCard]
 * 与 [com.bastion.app.ui.password.resolvePasswordCardDisplayLines]）：
 * id / title / website / username / notes / updatedAt / createdAt / isFavorite / isGroupCover /
 * appPackageName / appName / categoryId / keepassDatabaseId / authenticatorKey / loginType /
 * ssoProvider / customIconType / customIconValue / bitwardenVaultId / bitwardenLocalModified /
 * isDeleted / isArchived / sortOrder / password / bitwardenFolderId / keepassGroupPath。
 */
@Immutable
data class PasswordEntryListItem(
    val id: Long,
    val title: String,
    val website: String,
    val username: String,
    val notes: String,
    val updatedAt: Date,
    val createdAt: Date,
    val isFavorite: Boolean,
    val isGroupCover: Boolean,
    val appPackageName: String,
    val appName: String,
    val categoryId: Long?,
    val keepassDatabaseId: Long?,
    val authenticatorKey: String,
    val loginType: String,
    val ssoProvider: String,
    val customIconType: String,
    val customIconValue: String?,
    val bitwardenVaultId: Long?,
    val bitwardenLocalModified: Boolean,
    val isDeleted: Boolean,
    val isArchived: Boolean,
    val sortOrder: Int,
    // —— C.3.3 选项 A：纳入密码与主列表过滤/解密所需字段（见 docs/architecture-phaseC-performance.md 第 8 章）——
    // password 必须投影：主列表流 [com.bastion.app.viewmodel.PasswordViewModel] 对每行做
    // inspectSecretState(entry).plainValueOrEmpty() 解密以驱动「是否有密码」指示与隐藏态显示。
    val password: String,
    // bitwardenFolderId / keepassGroupPath 必须投影：主列表内存过滤按这两者做分组/文件夹筛选。
    val bitwardenFolderId: String?,
    val keepassGroupPath: String?,
    // keepassEntryUuid / bitwardenCipherId 必须投影：主列表的去重键 buildExactDisplayKey /
    // buildGhostGroupKey 以及「仅本地」判定会读取这两者，缺失会导致 KeePass/Bitwarden 条目
    // 去重与幽灵条目过滤结果改变。二者均为短 UUID，非大字段。
    val keepassEntryUuid: String?,
    val bitwardenCipherId: String?
)
