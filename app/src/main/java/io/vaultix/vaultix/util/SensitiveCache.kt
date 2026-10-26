/*
 * Vaultix — app
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Vaultix is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Vaultix.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package io.vaultix.vaultix.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.common.logging.VaultixLog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 敏感内容的**落盘缓存**统一管理处（#193）。
 *
 * ## 为什么需要它
 *
 * 密码管理器里有几处东西会被写到 `cacheDir` 下作为"临时"文件，但内容是**明文**：
 *
 * | 子目录 | 内容 | 谁写 |
 * |---|---|---|
 * | `cache/attachments/` | 从库里解密出来的附件原文 | `ItemDetailViewModel.downloadAttachment` |
 * | `cache/thumbs/` | 卡片/证件照类附件的缩略图（解密后缩放） | 详情页预览 |
 *
 * `cacheDir` 只对本应用可读 —— 这是对的，**它不是"没风险"，而是"风险窗口可控却没人关窗"**：
 * 一旦库里那条密码被删、或者用户锁库离开设备，这些明文仍然躺在磁盘上，
 * 直到系统在**存储空间不足时**随手回收。而"系统什么时候回收缓存"完全不可控：
 * 一台 512G 的手机可能几个月都不回收一次。
 *
 * ## 语义
 *
 * - [invalidateFor]：条目被删 / 条目内容被改（附件可能换了一份）时清掉该条目的全部缓存；
 * - [clearAll]：锁库 / 退出登录时清掉整个敏感缓存根目录。
 *
 * ## ⚠️ 与"复制到系统剪贴板"不同，这里**不需要**「确认是我放的才清」
 *
 * 剪贴板是全系统共享的一块地，里面可能是用户刚复制的验证码（见 `VaultixClipboard.clearNow`
 * 的注释）。而 `cacheDir` 下这几个子目录**只有本应用会写**，删它们不会伤到任何别人的东西。
 * 所以这里的清除是**无条件**的 —— 少一层判断，就少一次"判断错了于是没清"。
 *
 * ## ⚠️ 不要把这里的目录与 `dev_logs` 混在一起
 *
 * `cache/dev_logs/` 是用户**主动导出**的排查日志，它的保留策略是"只留最近若干个"
 * （见 `DeveloperLogsDialog`），与库内容无关。把它一起删掉，会让用户刚导出的日志
 * 在锁库那一刻消失 —— 那是一个"看起来在修安全、实际在制造 bug"的改动。
 */
@Singleton
class SensitiveCache @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val appContext = context.applicationContext

    /**
     * 敏感缓存的**根**目录。
     *
     * 把附件、缩略图都挂在同一个根下，是为了让 [clearAll] 只需要删一次目录 ——
     * 将来谁再往这里加第三种敏感缓存（比如导出预览），**只要挂在根下就自动被清**，
     * 不需要回来改 [clearAll]。这是本类存在的核心理由：**清除的覆盖面不能靠"记得"**。
     */
    fun root(): File = File(appContext.cacheDir, ROOT_DIR)

    /** 某个用途的子目录（自动创建）。 */
    fun dirFor(kind: String): File = File(root(), kind).apply { mkdirs() }

    /**
     * 某个条目专用的子目录（自动创建）。
     *
     * [itemId] 里可能带路径分隔符（Bitwarden 的 id 是 UUID，理论上不会，但 KDBX 那边
     * 的 id 由本应用生成、将来也可能改格式），所以这里**替换掉分隔符**而不是直接拼，
     * 与 `downloadAttachment` 处理 `fileName` 的理由相同。
     */
    fun dirForItem(kind: String, itemId: String): File =
        File(dirFor(kind), itemId.replace(INVALID_PATH_CHARS, "_")).apply { mkdirs() }

    /** 清掉某个条目在**所有用途**下的缓存。 */
    fun invalidateFor(itemId: String) {
        val safeId = itemId.replace(INVALID_PATH_CHARS, "_")
        CACHE_KINDS.forEach { kind ->
            val dir = File(root(), kind)
            val target = File(dir, safeId)
            if (target.exists()) deleteRecursively(target)
        }
    }

    /** 清掉整个敏感缓存根目录（锁库 / 退出）。 */
    fun clearAll() {
        val root = root()
        if (!root.exists()) return
        deleteRecursively(root)
    }

    /**
     * 删除并**记录失败**。
     *
     * `File.deleteRecursively()` 在失败时只返回 `false`，不抛异常 —— 静默失败的后果是
     * "以为清了其实没清"。这里的取舍是：**仍然不抛**（锁库路径不能被一个删文件失败打断，
     * 那会让用户锁不上库，比留着缓存更糟），但**必须留下一条日志**。
     */
    private fun deleteRecursively(file: File) {
        val ok = runCatching { file.deleteRecursively() }.getOrDefault(false)
        if (!ok) {
            VaultixLog.w(TAG) { "敏感缓存删除失败：${file.absolutePath}" }
        }
    }

    companion object {
        /** 附件明文（`dirForItem(KIND_ATTACHMENTS, itemId)`）。 */
        const val KIND_ATTACHMENTS = "attachments"

        /** 附件缩略图。 */
        const val KIND_THUMBS = "thumbs"

        private const val TAG = "SensitiveCache"

        /** 敏感缓存根目录名（`cache/` 下）。 */
        private const val ROOT_DIR = "vaultix-sensitive"

        /**
         * 所有敏感缓存用途。
         *
         * ⚠️ **新增一种就在这里加一项** —— [invalidateFor] 按这个表逐项清。
         * 挂在根目录下只保证 [clearAll] 覆盖到它，"删单条"要在这个表里登记。
         */
        private val CACHE_KINDS = listOf(KIND_ATTACHMENTS, KIND_THUMBS)

        /** 路径分隔符（正则可移植，不依赖平台分隔符）。 */
        private val INVALID_PATH_CHARS = Regex("[/\\\\]")
    }
}
