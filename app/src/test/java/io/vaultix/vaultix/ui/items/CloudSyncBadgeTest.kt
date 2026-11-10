package io.vaultix.vaultix.ui.items

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.VaultKind
import org.junit.Test

/**
 * [cloudSyncBadgeOrNull] 的单测 —— 钉住 2026-11-10 真机反馈的**两轮**修正。
 *
 * ## 第一轮：本地 KDBX 也挂了「已同步」的云
 *
 * 三条各自正确的代码串成了错误结果：
 * 1. `observeSyncStates` 对 KDBX 返回**空 map**（正确：文件即存储，没有条目级推送）；
 * 2. 读侧 `syncStates[id] ?: true` —— 空 map 查不到 ⇒ 每条都得到 `true`（对 Bitwarden 正确）；
 * 3. 行尾**无条件**画 `CloudSyncIcon(synced = true)` ⇒ 一朵「已同步」的云。
 *
 * ## 第二轮：OneDrive 上的库**没有**云图标了
 *
 * 第一轮修成「KDBX 一律不画」—— **过度修正**。用户原话：
 * > onedrive 上的 kdbx 是必有小云图标，因为它来自云，而本地文件没有云图标，它是来自本地的。
 *
 * 真正要回答的是**两个**问题，不是三个：
 * | 问题 | 数据源 |
 * |---|---|
 * | 这个库本身在云上吗 | `VaultSummary.origin` |
 * | 这一条推上云了吗 | Bitwarden 的 `pending_ops` |
 *
 * ⇒ 三态（[ItemCloudBadge]），而不是二值（画 / 不画）。
 *
 * ⚠️ 这里刻意**不测 Compose**，只测那个把语义决定下来的纯函数。
 * 用截图回归测它太贵（要起 instrumentation），而错误的形态又是"多一个 / 少一个图标"——
 * 正是肉眼最容易漏掉的那类。纯函数一条断言就锁死了。
 */
class CloudSyncBadgeTest {

    private companion object {
        /** 真实形态：`onedrive:<accountId>:<URL-encoded path>`（见 `OneDriveVaultOrigin.build`）。 */
        const val ONEDRIVE = "onedrive:acct-1:Personal%2Fvault.kdbx"

        /** 真实形态：`webdav:<credentialId>:<URL-encoded url>`。 */
        const val WEBDAV = "webdav:cred-1:https%3A%2F%2Fnas.local%3A5006%2Fdav%2Fx.kdbx"

        /** 真实形态：SAF 持久授权的 content URI（不是给用户看的，但 origin 里就是它）。 */
        const val LOCAL_SAF = "content://com.android.externalstorage.documents/document/primary%3Av.kdbx"
    }

    // ── KDBX·本地：不画（第一轮 bug 的守卫，不能因为这一轮改动而丢） ──────────────

    @Test
    fun `kdbx local file never draws a cloud`() {
        // 这是**第一轮 bug 的精确复现条件**：本地 KDBX + 查不到状态（= null）
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, LOCAL_SAF, null)).isNull()
        // 防御性：即使将来有人给 KDBX 也塞了条目状态，也不该冒出云图标
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, LOCAL_SAF, true)).isNull()
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, LOCAL_SAF, false)).isNull()
    }

    // ── KDBX·云端：画**裸云**（第二轮 bug 的守卫） ────────────────────────────────

    @Test
    fun `kdbx on onedrive shows the cloud vault badge`() {
        // 用户原话：「onedrive 上的 kdbx 是必有小云图标，因为它来自云」
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, ONEDRIVE, null))
            .isEqualTo(ItemCloudBadge.CLOUD_VAULT)
    }

    @Test
    fun `kdbx on webdav shows the cloud vault badge`() {
        // WebDAV 同样是网盘来源 —— 判据是"来源不是本地文件"，不是"是不是 OneDrive"。
        // 写成 `startsWith("onedrive:")` 就会让 WebDAV 用户看到与本地库一样的行。
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, WEBDAV, null))
            .isEqualTo(ItemCloudBadge.CLOUD_VAULT)
    }

    @Test
    fun `kdbx cloud vault ignores per item sync state`() {
        // KDBX 没有条目级推送表；即使传了值也不该改变结论（否则又是"两个问题一个答案"）
        for (perItem in listOf(true, false, null)) {
            assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, ONEDRIVE, perItem))
                .isEqualTo(ItemCloudBadge.CLOUD_VAULT)
        }
    }

    @Test
    fun `kdbx with unknown origin does not draw a cloud`() {
        // origin 缺失（库还没加载出来）⇒ 不知道在不在云上 ⇒ 宁可不画，也不闪一朵错的云。
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, null, null)).isNull()
    }

    @Test
    fun `kdbx with broken cloud origin degrades to local`() {
        // 前缀是 onedrive: 但解析不出账号/路径 —— 坏配置。`kdbxSourceTargetOf` 把它
        // 归为 LocalFile（它的 KDoc 明确说了这个退化），这里必须与之一致，否则
        // 「来源标签说本地文件、行尾却画着云」。
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, "onedrive:", null)).isNull()
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, "onedrive:acct-1:", null)).isNull()
    }

    // ── Bitwarden：保留原有三种语义（且**与 origin 无关**） ──────────────────────

    @Test
    fun `bitwarden pending shows unsynced`() {
        assertThat(cloudSyncBadgeOrNull(VaultKind.BITWARDEN, "https://vault.example.com", false))
            .isEqualTo(ItemCloudBadge.PENDING)
    }

    @Test
    fun `bitwarden synced shows synced`() {
        assertThat(cloudSyncBadgeOrNull(VaultKind.BITWARDEN, "https://vault.example.com", true))
            .isEqualTo(ItemCloudBadge.SYNCED)
    }

    @Test
    fun `bitwarden missing entry defaults to synced`() {
        // 新建后立刻可见、还没进 pending_ops 的那一瞬间 —— 不能闪一下「未同步」
        assertThat(cloudSyncBadgeOrNull(VaultKind.BITWARDEN, "https://vault.example.com", null))
            .isEqualTo(ItemCloudBadge.SYNCED)
    }

    @Test
    fun `bitwarden ignores vault origin`() {
        // 反向门禁：Bitwarden 行尾那朵云回答的是「这一条推了吗」，
        // 与库存在哪无关 —— 若有人把 origin 判据提前到 kind 判断之前，
        // Bitwarden 的同步状态就会被来源吞掉（表现为"待推送"永远不显示）。
        for (origin in listOf(null, LOCAL_SAF, ONEDRIVE, "content://x")) {
            assertThat(cloudSyncBadgeOrNull(VaultKind.BITWARDEN, origin, false))
                .isEqualTo(ItemCloudBadge.PENDING)
        }
    }

    // ── 未知库：不画 ────────────────────────────────────────────────────────────

    @Test
    fun `unknown vault kind does not draw a cloud`() {
        assertThat(cloudSyncBadgeOrNull(null, null, null)).isNull()
        assertThat(cloudSyncBadgeOrNull(null, ONEDRIVE, true)).isNull()
    }

    // ── 判据本身：两类语义**互不越界** ──────────────────────────────────────────

    @Test
    fun `only kdbx can return cloud vault and only bitwarden can return sync states`() {
        // 把判据写成可枚举的形式。新增 VaultKind 时这条会立刻红 ——
        // 提醒作者先想清楚"这个库有没有云端"和"这个库有没有条目级推送"，是两个问题。
        val origins = listOf(null, LOCAL_SAF, ONEDRIVE, WEBDAV)
        val perItem = listOf(true, false, null)
        for (kind in VaultKind.entries) {
            val results = origins.flatMap { o -> perItem.map { cloudSyncBadgeOrNull(kind, o, it) } }
            when (kind) {
                VaultKind.BITWARDEN -> {
                    assertThat(results).doesNotContain(null)
                    assertThat(results).doesNotContain(ItemCloudBadge.CLOUD_VAULT)
                }
                VaultKind.KDBX -> {
                    // KDBX 永远不会产出条目级同步态（那是 Bitwarden 的语义）
                    assertThat(results).doesNotContain(ItemCloudBadge.SYNCED)
                    assertThat(results).doesNotContain(ItemCloudBadge.PENDING)
                }
            }
        }
    }
}
