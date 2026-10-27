/*
 * Vaultix — domain（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [FolderNamePolicy] 的表驱动单测。
 *
 * 重点不是"好名字能过"，而是**坏名字必须被拒** —— 尤其是 `/`：
 * KDBX 的 `VaultFolder.name` 是完整路径（`父/子`），组名里放 `/` 会让层级不可解析。
 */
class FolderNamePolicyTest {

    private fun ok(raw: String): String {
        val verdict = FolderNamePolicy.check(raw)
        assertThat(verdict).isInstanceOf(FolderNamePolicy.Verdict.Ok::class.java)
        return (verdict as FolderNamePolicy.Verdict.Ok).name
    }

    private fun rejected(raw: String): String {
        val verdict = FolderNamePolicy.check(raw)
        assertThat(verdict).isInstanceOf(FolderNamePolicy.Verdict.Rejected::class.java)
        return (verdict as FolderNamePolicy.Verdict.Rejected).reason
    }

    @Test
    fun `正常名称通过并保留原样`() {
        assertThat(ok("工作")).isEqualTo("工作")
        assertThat(ok("Personal Accounts")).isEqualTo("Personal Accounts")
        assertThat(ok("🔐 私密")).isEqualTo("🔐 私密")
        assertThat(ok("a-b_c.d")).isEqualTo("a-b_c.d")
    }

    /** trim 是**唯一**的自动修正：首尾空白在两种存储里都会造成视觉重复，用户无法分辨。 */
    @Test
    fun `首尾空白被裁掉`() {
        assertThat(ok("  工作  ")).isEqualTo("工作")
        assertThat(ok("\t工作\n")).isEqualTo("工作")
    }

    @Test
    fun `空名与纯空白被拒`() {
        assertThat(rejected("")).contains("不能为空")
        assertThat(rejected("   ")).contains("不能为空")
        assertThat(rejected("\t\n")).contains("不能为空")
    }

    /**
     * ★ 本类的核心判据：`/` 与 `\` 一律拒绝。
     *
     * 若哪天有人"顺手放开斜杠"，KDBX 的组织结构会**静默错位**
     * （「工作/服务器」被读成两级），而 Bitwarden 侧毫无异常 —— 极难察觉。
     */
    @Test
    fun `路径分隔符被拒`() {
        assertThat(rejected("工作/服务器")).contains("/")
        assertThat(rejected("工作\\服务器")).contains("\\")
        assertThat(rejected("/")).contains("/")
    }

    @Test
    fun `长度边界`() {
        assertThat(ok("a".repeat(FolderNamePolicy.MAX_LENGTH))).hasLength(FolderNamePolicy.MAX_LENGTH)
        assertThat(rejected("a".repeat(FolderNamePolicy.MAX_LENGTH + 1))).contains("不能超过")
    }

    /** 长度按**字符**算，不是字节 —— 一个中文字是 3 字节，按字节算会让中文上限只有 33 个字。 */
    @Test
    fun `长度按字符计而非字节`() {
        assertThat(ok("工".repeat(FolderNamePolicy.MAX_LENGTH))).hasLength(FolderNamePolicy.MAX_LENGTH)
    }
}
