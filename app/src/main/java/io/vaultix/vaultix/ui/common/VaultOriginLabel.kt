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

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.KdbxCloudSyncStatus
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
    /**
     * 网盘同步状态的**角标**（null = 没什么要说的）。
     *
     * ## 为什么必须让它可见（2026-10-01 用户实测驱动）
     *
     * 用户原话：「我要更改位于 OneDrive 上的一个条目，修改了之后，它能正常顺利同步到
     * ……里面吗」。答案是**能，但上传不是自动的** —— 编辑只落本地缓存，云端要等一次「同步」。
     *
     * 加这个角标之前，`PENDING_UPLOAD` / `FAILED` **只在 设置→密码库管理** 显示一行文案，
     * 条目页与库列表**都看不到** ⇒ 用户改完条目、界面一切正常，而云端文件还是旧的，
     * **没有任何地方告诉他**。这正是本项目最忌讳的那种"静默"。
     */
    val syncBadge: SyncBadge? = null,

    /**
     * ⋮ 菜单「同步」项旁的四色状态点（null = 常态，不显示）。
     *
     * 挂在 [VaultCardInfo] 上是为了不改调用链：⋮ 下拉菜单本来就收它。
     * 与 [syncBadge] 的分工：角标回答「云端现在什么情况」，状态点回答
     * 「我点下去会发生什么」——所以状态点用的是同一份持久状态，但配色与文案独立。
     */
    val syncDot: SyncStatusDot? = null,
)

/**
 * 需要在界面上主动说出来的同步状态（其余状态不必占用视觉）。
 *
 * ⚠️ 刻意**区分"待上传"与"上传失败"**：两者都意味着"云端还是旧的"，但用户可做的事不同
 * —— 前者等一会儿就好，后者需要检查网络/账号或重试。合成一个"未同步"会让人不知道该干什么。
 */
enum class SyncBadge {
    /** 本地改了、还没传上去。 */
    PENDING,

    /** 上次上传失败（网络 / 账号 / 冲突前置）。 */
    FAILED,

    /** 两端都改了，等用户拍板。 */
    CONFLICT,

    /** 远端有新版本，本机还没拉下来（点一次「同步」即可，不用重新解锁）。 */
    REMOTE_CHANGED,
    ;

    /**
     * 纯函数映射（可 JVM 单测）：只有"需要用户知道"的状态才给角标。
     *
     * - `null`（本地 SAF 库 / 从未同步）⇒ 无角标（它根本没有云端这回事）；
     * - `IN_SYNC` / `SYNCING` ⇒ 无角标（前者无事，后者是瞬时态，闪一下反而像故障）；
     * - `LOCAL_ONLY` ⇒ 无角标（"还没有网盘来源"是配置态，不该常驻提示）。
     */
    companion object {
        fun of(status: KdbxCloudSyncStatus?): SyncBadge? = when (status) {
            KdbxCloudSyncStatus.PENDING_UPLOAD,
            KdbxCloudSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES,
            -> PENDING

            KdbxCloudSyncStatus.FAILED -> FAILED
            KdbxCloudSyncStatus.CONFLICT -> CONFLICT
            KdbxCloudSyncStatus.REMOTE_CHANGED -> REMOTE_CHANGED
            KdbxCloudSyncStatus.IN_SYNC,
            KdbxCloudSyncStatus.SYNCING,
            KdbxCloudSyncStatus.LOCAL_ONLY,
            null,
            -> null
        }
    }
}

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
    syncBadge = SyncBadge.of(vault.syncStatus),
    syncDot = SyncStatusDot.of(vault.syncStatus),
)

@Composable
fun syncBadgeLabel(badge: SyncBadge): String = stringResource(
    when (badge) {
        SyncBadge.PENDING -> R.string.vault_sync_badge_pending
        SyncBadge.FAILED -> R.string.vault_sync_badge_failed
        SyncBadge.CONFLICT -> R.string.vault_sync_badge_conflict
        SyncBadge.REMOTE_CHANGED -> R.string.vault_sync_badge_remote_changed
    },
)

/**
 * 角标颜色：**要用户动手的**（失败 / 冲突）用 error 色，其余只是告知。
 *
 * ⚠️ 不把四种状态都染成同一个颜色 —— 那等于把「该不该管」这条信息也抹掉了。
 */
@Composable
fun syncBadgeColor(badge: SyncBadge): Color = when (badge) {
    SyncBadge.FAILED -> MaterialTheme.colorScheme.error
    // ★ 冲突与失败分开染色（2026-11-04）：两者要用户做的事不同 ——
    //   失败是「重试」，冲突是「选保留哪一版」。染成同一个红等于抹掉这条信息。
    SyncBadge.CONFLICT -> MaterialTheme.colorScheme.primary
    SyncBadge.REMOTE_CHANGED -> MaterialTheme.colorScheme.primary
    SyncBadge.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * ⋮ 菜单「同步」项旁的四色状态点（2026-11-04）。
 *
 * 与 [SyncBadge] 刻意分开：角标是「常态告知」，状态点是「动作旁的状态」。
 * 数据源同是 `vaults.syncStatus`，但配色、文案与读屏描述独立。
 */
enum class SyncStatusDot {
    FAILED,
    SUCCESS,
    SYNCING,
    /** 本地改动已落盘、正在后台上传（11-07）。与 SYNCING 分开：那是「正在跑一次同步」。 */
    UPLOADING,
    CONFLICT,
    ;

    companion object {
        /** 纯函数映射，可 JVM 单测。常态 `IN_SYNC` 由 UI 决定短暂显示，不常驻。 */
        fun of(status: KdbxCloudSyncStatus?): SyncStatusDot? = when (status) {
            KdbxCloudSyncStatus.CONFLICT -> CONFLICT
            KdbxCloudSyncStatus.FAILED -> FAILED
            KdbxCloudSyncStatus.SYNCING -> SYNCING
            // ★ 11-07：此前两个「待上传」状态落到 else -> null ⇒ 保存后到上传成功
            //   这一两秒里 ⋮ 菜单的点整个消失，用户看不到后台在干活。
            //   SyncBadge.of 早就把它们映射成 PENDING 角标了，这里补上同源映射。
            KdbxCloudSyncStatus.PENDING_UPLOAD,
            KdbxCloudSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES,
            -> UPLOADING
            KdbxCloudSyncStatus.IN_SYNC -> SUCCESS
            else -> null
        }
    }
}

/**
 * 两类库的两套同步状态，合成菜单里那一个点（2026-11-04 用户反馈「零星显示」后补）。
 *
 * | 库 | 数据源 | 含义 |
 * |---|---|---|
 * | KDBX 网盘 | `vaults.syncStatus`（[KdbxCloudSyncStatus]） | **持久**的本地/远端差距 |
 * | Bitwarden | `VaultSyncStatus`（编排器运行时） | **本次**同步到哪一步 |
 * | KDBX 本地 SAF | 无 | 没有云端同步这回事 ⇒ 不显示 |
 *
 * ⚠️ 两套状态刻意不互串：持久差距不能靠一次刷新「变好」，
 *    瞬态进度也不能当成「云端还是旧的」（`VaultSummary` 类 KDoc 的同一条告诫）。
 *
 * ★ 常态也返回 [SyncStatusDot.SUCCESS]：用户实测反馈绿点闪一下就消失，
 *    看起来像功能时有时无；既然菜单是**主动点开才看得到**的低频区域，
 *    常驻绿点不会像列表角标那样变成滚动噪音。
 */
fun resolveSyncDot(vault: VaultSummary?, status: VaultSyncStatus?): SyncStatusDot? {
    if (vault == null) return null
    val kdbxStatus = vault.syncStatus
    if (kdbxStatus != null) return SyncStatusDot.of(kdbxStatus)
    if (vault.kind != VaultKind.BITWARDEN) return null
    val s = status ?: return SyncStatusDot.SUCCESS
    return when {
        s.isRunning -> SyncStatusDot.SYNCING
        (s.lastErrorAt ?: Long.MIN_VALUE) > (s.lastSuccessAt ?: Long.MIN_VALUE) ->
            SyncStatusDot.FAILED
        else -> SyncStatusDot.SUCCESS
    }
}

@Composable
fun syncStatusDotLabel(dot: SyncStatusDot): String = stringResource(
    when (dot) {
        SyncStatusDot.FAILED -> R.string.sync_dot_failed
        SyncStatusDot.SUCCESS -> R.string.sync_dot_success
        SyncStatusDot.SYNCING -> R.string.sync_dot_syncing
        SyncStatusDot.UPLOADING -> R.string.sync_dot_uploading
        SyncStatusDot.CONFLICT -> R.string.sync_dot_conflict
    },
)

/** 读屏描述：只靠颜色区分状态，色盲与读屏用户拿不到状态，必须配文案。 */
@Composable
fun syncStatusDotDescription(dot: SyncStatusDot): String = stringResource(
    when (dot) {
        SyncStatusDot.FAILED -> R.string.sync_dot_cd_failed
        SyncStatusDot.SUCCESS -> R.string.sync_dot_cd_success
        SyncStatusDot.SYNCING -> R.string.sync_dot_cd_syncing
        SyncStatusDot.UPLOADING -> R.string.sync_dot_cd_uploading
        SyncStatusDot.CONFLICT -> R.string.sync_dot_cd_conflict
    },
)

/**
 * 红=失败、绿=成功、黄=同步中、蓝=冲突。
 *
 * ⚠️ 琥珀/绿用固定中深色：MD3 没有 success/warning 语义色，亮黄在浅底几乎看不见。
 *    状态词与圆点同色且始终成对出现，即使圆点对比度不足也能读到状态。
 */
@Suppress("MagicNumber") // 两个 ARGB 颜色字面量本身就是语义常量，抽成命名常量反而更难读
@Composable
fun syncStatusDotColor(dot: SyncStatusDot): Color = when (dot) {
    SyncStatusDot.FAILED -> MaterialTheme.colorScheme.error
    SyncStatusDot.CONFLICT -> MaterialTheme.colorScheme.primary
    SyncStatusDot.SYNCING,
    SyncStatusDot.UPLOADING,
    -> Color(0xFFB26A00)
    SyncStatusDot.SUCCESS -> Color(0xFF2E7D32)
}

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
