/*
 * Vaultix — domain
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.domain

/**
 * 文件夹名称的唯一校验点（新建与重命名共用）。
 *
 * ## 为什么必须拦 `/`
 *
 * Bitwarden 的文件夹是**扁平**的（`FolderDto.name` 就是一个名字），而 KDBX 的分组是**树**，
 * 映射到领域模型时 `VaultFolder.name` 被拼成**完整路径**：
 *
 * ```kotlin
 * parentPath.isEmpty() -> group.name
 * else                 -> "$parentPath/${group.name}"     // KdbxItemMapper
 * ```
 *
 * ⇒ 组名里一旦允许 `/`，"`工作/服务器`" 到底是"根下的『工作/服务器』一个组"，
 * 还是"『工作』下的『服务器』"，就再也分不出来。**层级信息变得不可解析**。
 * 这不是风格问题，是数据问题 —— 所以两边**一律禁**，包括本来不需要路径语义的 Bitwarden
 * （同一套界面同时服务两种库，规则不一致会让用户在两个库之间切换时莫名失败）。
 *
 * ## 为什么不在空名/超长上做"自动修正"
 *
 * 自动 trim 会静默改掉用户输入（用户想保留首尾空格是极少见的，但"我输的名字和存下的不一样"
 * 是纯粹的困惑）。⇒ **拒绝并说明**，让用户看见自己输的是什么。
 */
object FolderNamePolicy {

    /** 名称长度上限（按字符计，与 Bitwarden 服务端的宽松上限一致，不额外收紧）。 */
    const val MAX_LENGTH = 100

    /** 禁止出现的字符：路径分隔符（原因见类注释）。 */
    private val FORBIDDEN_CHARS = charArrayOf('/', '\\')

    /** 校验结果：通过时返回**规范化后的**名称，失败时返回可直接展示的原因。 */
    sealed interface Verdict {
        data class Ok(val name: String) : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    /**
     * 校验并规范化。
     *
     * @param raw 用户原始输入。
     * @return [Verdict.Ok] 携带 `trim()` 之后的名称（**trim 是安全的方向**：首尾空白在
     *   两种存储里都会被吃掉或造成视觉重复，且用户无法分辨，因此这里统一裁掉）。
     */
    fun check(raw: String): Verdict {
        val name = raw.trim()
        if (name.isEmpty()) return Verdict.Rejected("文件夹名不能为空")
        if (name.length > MAX_LENGTH) return Verdict.Rejected("文件夹名不能超过 $MAX_LENGTH 个字符")
        val bad = FORBIDDEN_CHARS.firstOrNull { it in name }
        if (bad != null) {
            return Verdict.Rejected("文件夹名不能包含「$bad」")
        }
        return Verdict.Ok(name)
    }
}
