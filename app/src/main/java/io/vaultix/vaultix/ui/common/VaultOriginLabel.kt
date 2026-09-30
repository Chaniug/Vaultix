/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「这个库在哪」的一句话**（2026-09-30 用户要求）。
 *
 * 用户原话：「我想加上一个密码库状态的显示……比如 bitwarden 就显示用户的域名就可以了，
 * 不要 https，不要完整的地址，然后有多少个条目；kdbx 的话显示本地的路径，或者 onedrive
 * 的路径，然后同样显示有多少个条目」。
 *
 * ## 为什么这个词要单独一处（而不是在页面里现拼）
 *
 * `origin` 是**给系统看的**字符串，三种库三种形态：
 *
 * | 库 | `VaultSummary.origin` 实际长什么样 |
 * |---|---|
 * | Bitwarden | `https://vault.bitwarden.com`（自建还带端口 / 子路径） |
 * | KDBX·本地 SAF | `content://com.android.externalstorage.documents/document/primary%3A…` |
 * | KDBX·OneDrive | `onedrive:<accountId>:<URL-encoded path>` |
 * | KDBX·WebDAV | `webdav:<credentialId>:<URL-encoded url>` |
 *
 * 直接渲染就是 `.ai/issues/05-KDBX本地库.md` **#95**：把 `content://` 那种
 * 给系统看的、URL 编码过的字符串怼到用户脸上。⇒ 一律解析成**用户语义**再展示。
 *
 * ## ⚠️ 与 `VaultListScreen.vaultSubtitle` 的关系（**刻意不同，不是漂移**）
 *
 * 库卡片问的是「**这是哪个账号的库**」（切库时用户靠它认库）⇒ Bitwarden 侧优先显示
 * **邮箱**；而本函数的场景问的是「**这个库存在哪**」（核对数据落点时看）⇒ 显示**域名 / 路径**。
 * 两者取 `VaultSummary` 的不同字段，是有意为之；**改文案时别顺手把两边"统一"掉**。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.R
import io.vaultix.vaultix.remote.onedrive.OneDriveVaultOrigin
import io.vaultix.data.repository.kdbx.WebDavVaultOrigin

/**
 * 库来源的一句话标签：
 * - Bitwarden → **域名**（`vault.example.com:8443`，不带 scheme、不带路径）；
 * - KDBX → 按来源分：`本地文件 · 文件名` / `OneDrive · 路径` / `WebDAV · 主机与路径`。
 */
@Composable
fun vaultOriginLabel(vault: VaultSummary): String = when (vault.kind) {
    VaultKind.BITWARDEN -> serverHostOf(vault.origin)
    VaultKind.KDBX -> kdbxSourceLabel(vault)
}

/**
 * Bitwarden 的服务器地址 → **域名（含端口，不含 scheme 与路径）**。
 *
 * 保留端口是有意的：自建实例常见 `nas.local:8443` 这种非标准端口，
 * 抹掉端口会让两台服务器看起来同名，而用户正是靠这一行确认"连的是哪台"。
 * 路径则一律去掉 —— 用户问的是"哪个服务器"，不是"哪个接口"。
 *
 * ⚠️ 刻意**不用** `android.net.Uri`：`origin` 在库表里是**用户输入后规范化过的串**
 * （见 `VaultRepositoryImpl.normalizeServer`），而库表数据在单测里要能直接验。
 * 纯字符串处理让本函数可在 JVM 单测里钉住（`Uri` 在单测里是空壳）。
 */
internal fun serverHostOf(origin: String): String {
    val withoutScheme = origin.substringAfter("://", missingDelimiterValue = origin)
    return withoutScheme.substringBefore('/').trim()
}

/**
 * 「当前库」的展示数据 —— **列表状态行**与 **⋮ 菜单顶部的库卡片**共用同一份。
 *
 * 两处共用是刻意的：它们要回答的是同一个问题（我在哪个库、里面有多少条），
 * 各拼一遍必然漂移（典型症状：改了一处文案，另一处还是老措辞）。
 * 差异只在**排版密度**：列表里一行（中间省略），菜单卡片里可以给全。
 *
 * @property countText 条目数文案；**null = 未解锁**（此刻条目数不可知）⇒ 调用方不显示它
 *   —— 显示 0 就是把"不知道"说成"没有"。
 */
data class VaultCardInfo(
    val name: String,
    val origin: String,
    val countText: String?,
)

/** 由库摘要 + 条目总数组装 [VaultCardInfo]。 */
@Composable
fun vaultCardInfoOf(vault: VaultSummary, itemCount: Int): VaultCardInfo = VaultCardInfo(
    name = vault.name,
    origin = vaultOriginLabel(vault),
    countText = if (vault.unlocked) {
        stringResource(R.string.vault_item_count_only, itemCount)
    } else {
        null
    },
)

/** KDBX 的来源分类（解析结果；供 UI 与单测共用）。 */
internal sealed interface KdbxSourceTarget {
    /** 本地 SAF 文件：只有文件名可展示（`content://` 不是给用户看的）。 */
    data object LocalFile : KdbxSourceTarget

    /** OneDrive：展示库在网盘里的路径。 */
    data class OneDrive(val path: String) : KdbxSourceTarget

    /** WebDAV：展示主机 + 路径（去掉 scheme）。 */
    data class WebDav(val fileUrl: String) : KdbxSourceTarget
}

/**
 * 从 `VaultSummary.origin` 判断 KDBX 的来源形态。
 *
 * ⚠️ **前缀匹配成功但 parse 失败**时退化为 [KdbxSourceTarget.LocalFile]：那种 origin 是
 * 坏配置（远端同步那一步会明确报出来），这里不必再造一个"来源未知"的展示分支 ——
 * 但**不要**据此认为它是本地文件，故 [LocalFile] 的文案一律用**文件名**而不是路径。
 */
internal fun kdbxSourceTargetOf(origin: String): KdbxSourceTarget {
    OneDriveVaultOrigin.parse(origin)?.let { return KdbxSourceTarget.OneDrive(it.path) }
    WebDavVaultOrigin.parse(origin)?.let { return KdbxSourceTarget.WebDav(it.fileUrl) }
    return KdbxSourceTarget.LocalFile
}

@Composable
private fun kdbxSourceLabel(vault: VaultSummary): String = when (val target = kdbxSourceTargetOf(vault.origin)) {
    // 复用库卡片那条串（「本地文件 · %1$s」）：本地 SAF 场景两边说的是同一件事，
    // 措辞必须一致，否则同一种库在两个页面叫两个名字。
    KdbxSourceTarget.LocalFile -> stringResource(R.string.vault_card_kdbx_subtitle, vault.name)

    is KdbxSourceTarget.OneDrive ->
        stringResource(R.string.vault_origin_onedrive, target.path)

    is KdbxSourceTarget.WebDav ->
        stringResource(R.string.vault_origin_webdav, serverAndPathOf(target.fileUrl))
}

/** `https://nas.local:5006/dav/x.kdbx` → `nas.local:5006/dav/x.kdbx`（去掉 scheme，保留路径）。 */
private fun serverAndPathOf(fileUrl: String): String =
    fileUrl.substringAfter("://", missingDelimiterValue = fileUrl)
